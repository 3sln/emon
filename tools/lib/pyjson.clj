(ns pyjson
  "JSON output byte-identical to Python's json.dumps, for the tooling ports (DEVLOG: scripting is
  babashka).  Object key order is the ORDER OF THE MAP: build objects with `om` / `oassoc` (an
  array-map stays ordered at any size; a hash-map does not).  Floats print as Python's repr.
  Reading is cheshire's (keys as strings, order not kept); use `oparse` when order matters."
  (:require [cheshire.core :as json]))

(defn om
  "An insertion-ordered map: (om :a 1 :b 2).  Stays ordered however large."
  [& kvs] (apply array-map kvs))

(defn oassoc
  "assoc that keeps an array-map an array-map when it grows past 8 keys."
  [m k v]
  (if (or (contains? m k) (not (instance? clojure.lang.PersistentArrayMap m)))
    (assoc m k v)
    (apply array-map (concat (mapcat identity m) [k v]))))

(defn float-repr
  "Python's repr(float): shortest round-trip digits; fixed notation for 1e-4 <= |x| < 1e16."
  [d]
  (let [d (double d)]
    (cond
      (Double/isNaN d) "NaN"
      (Double/isInfinite d) (if (pos? d) "Infinity" "-Infinity")
      (zero? d) (if (neg? (Math/copySign 1.0 d)) "-0.0" "0.0")
      :else
      (let [bd (.stripTrailingZeros (BigDecimal. (Double/toString (Math/abs d))))
            digits (.toString (.unscaledValue bd))
            ;; value = 0.DIGITS * 10^decpt
            decpt (- (count digits) (.scale bd))
            sign (if (neg? d) "-" "")]
        (str sign
             (if (and (> decpt -4) (<= decpt 16))
               (cond
                 (<= decpt 0) (str "0." (apply str (repeat (- decpt) "0")) digits)
                 (>= decpt (count digits)) (str digits (apply str (repeat (- decpt (count digits)) "0")) ".0")
                 :else (str (subs digits 0 decpt) "." (subs digits decpt)))
               (let [e (dec decpt)]
                 (str (first digits)
                      (when (> (count digits) 1) (str "." (subs digits 1)))
                      "e" (if (neg? e) "-" "+") (format "%02d" (Math/abs e))))))))))

(defn- esc-str* [^String s ascii?]
  (let [sb (StringBuilder. "\"")]
    (doseq [c s]
      (let [i (int c)]
        (cond
          (= c \") (.append sb "\\\"")
          (= c \\) (.append sb "\\\\")
          (= c \newline) (.append sb "\\n")
          (= c \return) (.append sb "\\r")
          (= c \tab) (.append sb "\\t")
          (= i 8) (.append sb "\\b")
          (= i 12) (.append sb "\\f")
          (or (< i 32) (and ascii? (> i 127))) (.append sb (format "\\u%04x" i))
          :else (.append sb c))))
    (.append sb "\"")
    (str sb)))

(def ^:private plain-re #"[\"\\\x00-\x1f]")
(def ^:private plain-ascii-re #"[\"\\\x00-\x1f\u0080-\uffff]")

(defn- esc-str [^String s ascii?]
  (if-not (re-find (if ascii? plain-ascii-re plain-re) s)
    (str "\"" s "\"")                    ; the common case: nothing to escape
    (esc-str* s ascii?)))

(defn- key-str [k] (if (keyword? k) (name k) (str k)))

(defn dumps
  "json.dumps(v, ...).  opts: :indent n (default none: separators \", \" and \": \"),
  :ascii true (ensure_ascii), :sort-keys true."
  ([v] (dumps v {}))
  ([v {:keys [indent ascii sort-keys]}]
   (let [sb (StringBuilder.)
         spaces (apply str (repeat 256 " "))
         pad (fn [lvl] (when indent
                         (.append sb "\n")
                         (let [n (* indent lvl)]
                           (.append sb (if (<= n 256) (subs spaces 0 n) (apply str (repeat n " ")))))))
         ent (fn [m] (let [es (seq m)] (if sort-keys (sort-by (comp key-str key) es) es)))
         emit (fn emit [x lvl]
                (cond
                  (nil? x) (.append sb "null")
                  (true? x) (.append sb "true")
                  (false? x) (.append sb "false")
                  (string? x) (.append sb (esc-str x ascii))
                  (keyword? x) (.append sb (esc-str (name x) ascii))
                  (integer? x) (.append sb (str x))
                  (float? x) (.append sb (float-repr x))
                  (number? x) (.append sb (str x))
                  (instance? java.util.Map x)
                  (let [es (ent x)]
                    (if (empty? es)
                      (.append sb "{}")
                      (do (.append sb "{")
                          (reduce (fn [first? [k val]]
                                    (when-not first? (.append sb (if indent "," ", ")))
                                    (pad (inc lvl))
                                    (.append sb (esc-str (key-str k) ascii))
                                    (.append sb ": ")
                                    (emit val (inc lvl))
                                    false)
                                  true es)
                          (pad lvl)
                          (.append sb "}"))))
                  (or (sequential? x) (instance? java.util.List x) (set? x))
                  (if (empty? x)
                    (.append sb "[]")
                    (do (.append sb "[")
                        (reduce (fn [first? val]
                                  (when-not first? (.append sb (if indent "," ", ")))
                                  (pad (inc lvl))
                                  (emit val (inc lvl))
                                  false)
                                true x)
                        (pad lvl)
                        (.append sb "]")))
                  :else (throw (ex-info (str "pyjson: cannot serialise " (type x)) {:v x}))))]
     (emit v 0)
     (str sb))))

(defn parse
  "json.loads: objects as string-keyed maps (order NOT kept), arrays as vectors."
  [s] (json/parse-string s false))

(defn parse-file [path] (parse (slurp path :encoding "UTF-8")))

(defn- bigint-or-long [t] (let [b (bigint t)] (if (<= Long/MIN_VALUE b Long/MAX_VALUE) (long b) b)))

(defn oparse
  "json.loads keeping object key order (array-maps).  Slower than `parse`."
  [^String s]
  (let [n (count s)
        pos (volatile! 0)
        peek (fn [] (while (and (< @pos n) (Character/isWhitespace (.charAt s (int @pos)))) (vswap! pos inc))
               (.charAt s (int @pos)))
        str! (fn []
               (vswap! pos inc)
               (let [sb (StringBuilder.)]
                 (loop []
                   (let [c (.charAt s (int @pos))]
                     (vswap! pos inc)
                     (cond
                       (= c \") nil
                       (= c \\) (let [e (.charAt s (int @pos))]
                                  (vswap! pos inc)
                                  (case e
                                    \n (.append sb \newline) \t (.append sb \tab) \r (.append sb \return)
                                    \b (.append sb \backspace) \f (.append sb \formfeed)
                                    \u (do (.append sb (char (Integer/parseInt (subs s @pos (+ @pos 4)) 16)))
                                           (vswap! pos + 4))
                                    (.append sb e))
                                  (recur))
                       :else (do (.append sb c) (recur)))))
                 (str sb)))
        val! (fn val! []
               (let [c (peek)]
                 (cond
                   (= c \{) (do (vswap! pos inc)
                                (if (= (peek) \})
                                  (do (vswap! pos inc) (om))
                                  (loop [kvs []]
                                    (let [k (do (peek) (str!))
                                          _ (do (peek) (vswap! pos inc))
                                          v (val!)
                                          kvs (conj kvs k v)
                                          d (peek)]
                                      (vswap! pos inc)
                                      (if (= d \,) (recur kvs) (apply array-map kvs))))))
                   (= c \[) (do (vswap! pos inc)
                                (if (= (peek) \])
                                  (do (vswap! pos inc) [])
                                  (loop [out []]
                                    (let [v (val!) d (peek)]
                                      (vswap! pos inc)
                                      (if (= d \,) (recur (conj out v)) (conj out v))))))
                   (= c \") (str!)
                   :else (let [st @pos]
                           (while (and (< @pos n) (not (contains? #{\, \} \]} (.charAt s (int @pos))))
                                       (not (Character/isWhitespace (.charAt s (int @pos)))))
                             (vswap! pos inc))
                           (let [t (subs s st @pos)]
                             (case t "true" true "false" false "null" nil
                                   (if (re-find #"[.eE]" t) (Double/parseDouble t) (bigint-or-long t))))))))]
    (val!)))
