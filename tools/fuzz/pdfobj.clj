;; A small PDF OBJECT MODEL for the fuzzer (DEVLOG P10.5, D700): parse the indirect objects of a classic
;; (cross-reference table) file into {:n :g :dict :data}, change them, and write the file again with a
;; FRESH, CORRECT cross-reference table. A byte-level mutant of a real file mostly dies in xref repair; a
;; mutant made here reaches the page tree, the fonts, the filters and the content interpreter with every
;; object where the table says it is. Babashka only; no kin.
;;
;; `parse` answers nil for a file it cannot rewrite faithfully (encrypted, object streams, cross-reference
;; streams, no objects): the caller then falls back to a byte-level mutator.
(ns fuzz.pdfobj
  (:require [clojure.string :as str])
  (:import (java.util.zip Inflater Deflater)))

(defn ->s ^String [^bytes bs] (String. bs "ISO-8859-1"))
(defn ->b ^bytes [^String s] (.getBytes s "ISO-8859-1"))

(defn cat-bytes ^bytes [& parts]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [p parts] (.write out ^bytes (if (string? p) (->b p) p)))
    (.toByteArray out)))

(defn inflate
  "The decoded bytes of a zlib stream (at most `cap`), or nil when it is not a whole, valid one."
  [^bytes bs cap]
  (try
    (let [inf (Inflater.) out (java.io.ByteArrayOutputStream.) buf (byte-array 8192)]
      (.setInput inf bs)
      (loop []
        (let [n (.inflate inf buf)]
          (cond
            (pos? n) (do (.write out buf 0 n) (if (< (.size out) cap) (recur) nil))
            (.finished inf) (.toByteArray out)
            :else nil))))
    (catch Exception _ nil)))

(defn deflate ^bytes [^bytes bs]
  (let [d (Deflater.) out (java.io.ByteArrayOutputStream.) buf (byte-array 8192)]
    (.setInput d bs)
    (.finish d)
    (loop []
      (when-not (.finished d)
        (let [n (.deflate d buf)] (.write out buf 0 n) (recur))))
    (.toByteArray out)))

;; ------------------------------------------------------------------ parsing

(defn- find-all-idx [^String s re]
  (let [m (re-matcher re s)]
    (loop [acc []]
      (if (.find m) (recur (conj acc [(.start m) (.end m) (.group m 1) (.group m 2)])) acc))))

(defn- split-stream
  "[dict-text data-bytes-or-nil] of an object's body."
  [^String body]
  (if-let [m (re-matcher #"(?s)\bstream(\r\n|\n|\r)" body)]
    (if (.find m)
      (let [dict (str/trim (subs body 0 (.start m)))
            from (.end m)
            lm (re-find #"/Length\s+(\d+)(?!\s+\d+\s+R)" dict)
            len (when lm (parse-long (second lm)))
            es (str/index-of body "endstream" from)
            to (cond
                 (and len (<= (+ from len) (count body))
                      (re-find #"^\s*endstream" (subs body (+ from len) (min (count body) (+ from len 12)))))
                 (+ from len)
                 es (let [e es] (cond (and (> e from) (= \newline (.charAt body (dec e)))) (if (and (> (dec e) from) (= \return (.charAt body (- e 2)))) (- e 2) (dec e))
                                      (and (> e from) (= \return (.charAt body (dec e)))) (dec e)
                                      :else e))
                 :else (count body))]
        [dict (->b (subs body from (max from to)))])
      [(str/trim body) nil])
    [(str/trim body) nil]))

(defn parse
  "{:objs [{:n :g :dict :data}] :root \"N G R\" :info \"N G R\"|nil} or nil."
  [^bytes bs]
  (let [s (->s bs)]
    (when-not (or (re-find #"/Encrypt|/ObjStm|/Type\s*/XRef" s) (> (alength bs) 3000000))
      (let [hits (find-all-idx s #"(?:^|(?<=[\s>]))(\d{1,9}) (\d{1,5}) obj\b")
            objs (vec (for [[k [st en n g]] (map-indexed vector hits)
                            :let [nxt (if (< (inc k) (count hits)) (first (nth hits (inc k))) (count s))
                                  body0 (subs s en nxt)
                                  eo (str/last-index-of body0 "endobj")
                                  body (cond eo (subs body0 0 eo)
                                             :else (let [t (or (str/index-of body0 "\nxref") (str/index-of body0 "\ntrailer") (count body0))] (subs body0 0 t)))
                                  [dict data] (split-stream body)]
                            :when (seq dict)]
                        {:n (parse-long n) :g (parse-long g) :dict dict :data data}))
            ;; the last definition of a number wins (incremental updates)
            objs (vec (vals (into (sorted-map) (for [o objs] [(:n o) o]))))
            root (last (re-seq #"/Root\s+(\d+\s+\d+\s+R)" s))
            info (last (re-seq #"/Info\s+(\d+\s+\d+\s+R)" s))]
        (when (and (seq objs) root)
          {:objs objs :root (second root) :info (some-> info second)})))))

;; ------------------------------------------------------------------ writing

(defn with-length
  "dict text with /Length n set (replacing a direct /Length, or added before the closing >>)."
  [^String dict n]
  (let [d (str/replace dict #"/Length\s+\d+(?!\s+\d+\s+R)" "")]
    (if-let [i (str/last-index-of d ">>")]
      (str (subs d 0 i) " /Length " n " " (subs d i))
      d)))

(defn emit
  "A whole file from a model: header, the objects in the model's order, a fresh xref and trailer.
   A stream object whose :dict holds `/Length N R` keeps it as written (the length may be indirect)."
  [{:keys [objs root info extra-trailer]}]
  (let [out (java.io.ByteArrayOutputStream.)
        w (fn [x] (.write out ^bytes (if (string? x) (->b x) x)))
        offs (atom (sorted-map))]
    (w "%PDF-1.7\n%âãÏÓ\n")
    (doseq [{:keys [n g dict data keep-length]} objs]
      (swap! offs assoc (long n) [(.size out) (or g 0)])
      (w (str n " " (or g 0) " obj\n"))
      (if data
        (do (w (if (or keep-length (re-find #"/Length\s+\d+\s+\d+\s+R" dict)) dict (with-length dict (alength ^bytes data))))
            (w "\nstream\n") (w data) (w "\nendstream\nendobj\n"))
        (do (w dict) (w "\nendobj\n"))))
    (let [xr (.size out)
          mx (if (empty? @offs) 0 (key (last @offs)))]
      (w (str "xref\n0 " (inc mx) "\n"))
      (w "0000000000 65535 f \n")
      (doseq [k (range 1 (inc mx))]
        (if-let [[o g] (get @offs (long k))]
          (w (format "%010d %05d n \n" o g))
          (w "0000000000 00000 f \n")))
      (w (str "trailer\n<< /Size " (inc mx) " /Root " root (when info (str " /Info " info)) (or extra-trailer "") " >>\n"
              "startxref\n" xr "\n%%EOF\n")))
    (.toByteArray out)))

;; --------------------------------------------------------- dictionary tokens

(defn- ws? [c] (or (= c \space) (= c \newline) (= c \return) (= c \tab) (= c \formfeed) (= c (char 0))))
(defn- delim? [c] (or (ws? c) (contains? #{\( \) \< \> \[ \] \{ \} \/ \%} c)))

(defn skip-ws [^String s i]
  (loop [i i] (if (and (< i (count s)) (ws? (.charAt s i))) (recur (inc i)) i)))

(defn value-end
  "The index just past the PDF value that starts at i (after whitespace); i itself when there is none."
  [^String s i]
  (let [n (count s) i (skip-ws s i)]
    (if (>= i n)
      i
      (let [c (.charAt s i)]
        (cond
          (= c \<)
          (if (and (< (inc i) n) (= \< (.charAt s (inc i))))
            (loop [j (+ i 2) d 1]
              (cond (>= j n) n
                    (and (= \< (.charAt s j)) (< (inc j) n) (= \< (.charAt s (inc j)))) (recur (+ j 2) (inc d))
                    (and (= \> (.charAt s j)) (< (inc j) n) (= \> (.charAt s (inc j)))) (if (= d 1) (+ j 2) (recur (+ j 2) (dec d)))
                    (= \( (.charAt s j)) (recur (value-end s j) d)
                    :else (recur (inc j) d)))
            (let [e (str/index-of s ">" i)] (if e (inc e) n)))
          (= c \[)
          (loop [j (inc i)]
            (let [j (skip-ws s j)]
              (cond (>= j n) n
                    (= \] (.charAt s j)) (inc j)
                    :else (let [e (value-end s j)] (recur (if (= e j) (inc j) e))))))
          (= c \()
          (loop [j (inc i) d 1]
            (cond (>= j n) n
                  (= \\ (.charAt s j)) (recur (+ j 2) d)
                  (= \( (.charAt s j)) (recur (inc j) (inc d))
                  (= \) (.charAt s j)) (if (= d 1) (inc j) (recur (inc j) (dec d)))
                  :else (recur (inc j) d)))
          (= c \/) (loop [j (inc i)] (if (and (< j n) (not (delim? (.charAt s j)))) (recur (inc j)) j))
          :else
          (let [m (re-matcher #"\G\d+\s+\d+\s+R(?![A-Za-z0-9])" s)]
            (.region m i n)
            (if (.lookingAt m)
              (.end m)
              (loop [j i] (if (and (< j n) (not (delim? (.charAt s j)))) (recur (inc j)) (if (= j i) (inc i) j))))))))))

(defn top-pairs
  "[[key-start key-end val-start val-end] ...] of the top level of the first dictionary in s."
  [^String s]
  (if-let [i (and (<= (count s) 12000) (str/index-of s "<<"))]
    (let [n (count s)]
      (loop [j (+ i 2) acc []]
        (let [j (skip-ws s j)]
          (cond
            (or (>= j n) (>= (count acc) 400)) acc
            (and (< (inc j) n) (= \> (.charAt s j)) (= \> (.charAt s (inc j)))) acc
            (= \/ (.charAt s j))
            (let [ke (value-end s j) vs (skip-ws s ke) ve (value-end s vs)]
              (if (= ve vs) acc (recur ve (conj acc [j ke vs ve]))))
            :else (recur (inc j) acc)))))
    []))

(defn key-of [^String s [ks ke]] (subs s (inc ks) ke))

(defn find-pair [^String s k]
  (first (filter #(= k (key-of s %)) (top-pairs s))))

(defn put-pair
  "s with key k set to the value text v: replaced when present, appended before the closing >> otherwise."
  [^String s k v]
  (if-let [[_ _ vs ve] (find-pair s k)]
    (str (subs s 0 vs) v (subs s ve))
    (if-let [i (str/last-index-of s ">>")]
      (str (subs s 0 i) " /" k " " v " " (subs s i))
      s)))

(defn drop-pair [^String s k]
  (if-let [[ks _ _ ve] (find-pair s k)]
    (str (subs s 0 ks) (subs s ve))
    s))

(defn refs
  "[[start end n]] of every `n g R` in s."
  [^String s]
  (let [m (re-matcher #"(\d+)\s+\d+\s+R(?![A-Za-z0-9])" s)]
    (loop [acc []] (if (and (< (count acc) 1500) (.find m)) (recur (conj acc [(.start m) (.end m) (parse-long (.group m 1))])) acc))))

(defn deflate-zeros
  "zlib of n zero bytes, made without holding them."
  ^bytes [n]
  (let [d (Deflater. 9) out (java.io.ByteArrayOutputStream.) buf (byte-array 65536) chunk (byte-array 1048576)]
    (loop [left n]
      (if (pos? left)
        (let [k (min left (alength chunk))]
          (.setInput d chunk 0 k)
          (while (not (.needsInput d)) (.write out buf 0 (.deflate d buf)))
          (recur (- left k)))))
    (.finish d)
    (while (not (.finished d)) (.write out buf 0 (.deflate d buf)))
    (.toByteArray out)))
