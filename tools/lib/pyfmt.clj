(ns pyfmt
  "Python string and number formatting semantics for the tooling ports: `%` formatting (%s %d %f %x
  with flags/width/precision and %%), round() (half-even on the exact binary value), '{:,.2f}',
  str.capitalize().  Floats are formatted from their EXACT binary value, as CPython does (Java's
  String/format rounds the shortest decimal and can differ)."
  (:require [clojure.string :as str]
            [pyjson]))

(defn round-half-even
  "Python round(x, nd) for a float: correctly rounded from the exact value, ties to even."
  [x nd]
  (.doubleValue (.setScale (BigDecimal. (double x)) (int nd) java.math.RoundingMode/HALF_EVEN)))

(defn fixed
  "'%.{nd}f' % x (no sign/width handling)."
  [x nd]
  (let [d (double x)]
    (cond (Double/isNaN d) "nan"
          (Double/isInfinite d) (if (pos? d) "inf" "-inf")
          :else (let [s (.toPlainString (.setScale (BigDecimal. d) (int nd) java.math.RoundingMode/HALF_EVEN))]
                  ;; Python keeps the sign of a negative that rounds to zero ("-0.00"); BigDecimal loses it
                  (if (and (or (neg? d) (and (zero? d) (neg? (/ 1.0 d)))) (not (str/starts-with? s "-")))
                    (str "-" s)
                    s)))))

(defn commas
  "'{:,.2f}'.format(x) generalised to nd decimals."
  [x nd]
  (let [s (fixed x nd)
        neg (str/starts-with? s "-")
        s (if neg (subs s 1) s)
        [ip fp] (str/split s #"\." 2)
        ip (str/replace ip #"\B(?=(\d{3})+(?!\d))" ",")]
    (str (when neg "-") ip (when fp (str "." fp)))))

(defn capitalize [s] (if (empty? s) s (str (str/upper-case (subs s 0 1)) (str/lower-case (subs s 1)))))

(defn pystr
  "str(x) for the value kinds the tooling prints."
  [x]
  (cond (nil? x) "None"
        (true? x) "True"
        (false? x) "False"
        (float? x) (pyjson/float-repr x)
        :else (str x)))

(defn- pad [s width left? zero?]
  (let [n (- (or width 0) (count s))]
    (cond (<= n 0) s
          left? (str s (apply str (repeat n " ")))
          zero? (let [neg (str/starts-with? s "-")
                      body (if neg (subs s 1) s)]
                  (str (when neg "-") (apply str (repeat n "0")) body))
          :else (str (apply str (repeat n " ")) s))))

(defn fmt
  "Python: fmt % args.  Supports %% %s %d %i %f %x, flags `-`/`0`/`+`, width, .precision."
  [^String f & args]
  (let [m (re-matcher #"%([-0+ #]*)(\d+)?(?:\.(\d+))?([sdifxXr%])" f)
        sb (StringBuilder.)
        args (vec args)]
    (loop [pos 0 ai 0]
      (if (.find m)
        (do
          (.append sb (subs f pos (.start m)))
          (let [[_ flags width prec conv] (re-groups m)
                width (when width (parse-long width))
                prec (when prec (parse-long prec))
                left? (str/includes? flags "-")
                zero? (and (str/includes? flags "0") (not left?))
                plus? (str/includes? flags "+")]
            (if (= conv "%")
              (do (.append sb "%") (recur (.end m) ai))
              (let [a (nth args ai)
                    s (case conv
                        ("s" "r") (let [s (pystr a)] (if prec (subs s 0 (min prec (count s))) s))
                        ("d" "i") (let [n (if (float? a) (long a) a)]
                                    (str (when (and plus? (>= n 0)) "+") n))
                        "f" (str (when (and plus? (>= a 0)) "+") (fixed a (or prec 6)))
                        "x" (Long/toHexString a)
                        "X" (str/upper-case (Long/toHexString a)))]
                (.append sb (pad s width left? zero?))
                (recur (.end m) (inc ai))))))
        (do (.append sb (subs f pos))
            (when (not= ai (count args))
              (throw (ex-info "pyfmt: not all arguments converted" {:fmt f})))
            (str sb))))))
