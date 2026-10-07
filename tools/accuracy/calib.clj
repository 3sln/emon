(ns accuracy.calib
  "Confidence calibration arithmetic (P10.1, SPEC 9.6, 3.5).  No dependencies: the scorer's counts, the
`bb calibration` report and the fitter all read their numbers from here.

A ROW is [unit conf y]: `conf` the node's confidence (per mille, 0..1000), `y` how right it was (per mille, 1000
right, 0 wrong, in between for a text run of which only some words were). The reliability of a set of rows is
read from TEN EQUAL BINS ([0,100), ... [900,1000]); per bin the count `n`, the sum of the confidences `sc` and the
sum of the outcomes `sy`, all integers, so counts of documents pool by addition (D077).

  ECE   = sum over bins of  n_b / N * | mean y_b - mean conf_b |          (per unit, 0..1)
  Brier = mean of (conf - y)^2                                           (per unit, 0..1)

and a unit is a kind (`heading`), a kind and a source class (`heading/ocr`), or `all`."
  (:require [clojure.string :as str]))

(def NBINS 10)

(defn bin-of [conf] (min (dec NBINS) (quot (max 0 conf) 100)))

(defn unit-keys
  "The units a row [kind class conf y] counts in: its kind, its kind and class, and `all`."
  [kind class]
  [kind (str kind "/" class) "all"])

(defn row-counts
  "The counts of rows [[kind class conf y]]: {\"conf.UNIT.B.n\" .. \".sc\" .. \".sy\", \"conf.UNIT.sq\" ..} with the
  per-mille squares summed exactly (an integer)."
  [rows]
  (reduce (fn [c [kind class conf y]]
            (let [b (bin-of conf)
                  d (- conf y)]
              (reduce (fn [c u]
                        (-> c
                            (update (str "conf." u "." b ".n") (fnil + 0) 1)
                            (update (str "conf." u "." b ".sc") (fnil + 0) conf)
                            (update (str "conf." u "." b ".sy") (fnil + 0) y)
                            (update (str "conf." u ".sq") (fnil + 0) (* d d))))
                      c (unit-keys kind class))))
          (array-map) rows))

(defn units-in
  "The unit names a pooled count map holds, sorted."
  [c]
  (sort (distinct (for [k (keys c) :let [m (re-matches #"conf\.(.+)\.sq" k)] :when m] (second m)))))

(defn bins-of
  "[[b n sc sy] ...] of a unit, the bins that hold at least one row."
  [c u]
  (vec (for [b (range NBINS) :let [n (get c (str "conf." u "." b ".n") 0)] :when (pos? n)]
         [b n (get c (str "conf." u "." b ".sc") 0) (get c (str "conf." u "." b ".sy") 0)])))

(defn unit-n [c u] (reduce + 0 (map second (bins-of c u))))

(defn ece
  "Expected calibration error of the unit, 0..1."
  [c u]
  (let [bs (bins-of c u) n (reduce + 0 (map second bs))]
    (when (pos? n)
      (/ (double (reduce + 0 (for [[_ _ sc sy] bs] (Math/abs (long (- sy sc)))))) (* 1000.0 n)))))

(defn brier
  "Mean squared error of confidence against outcome, 0..1."
  [c u]
  (let [n (unit-n c u)]
    (when (pos? n) (/ (double (get c (str "conf." u ".sq") 0)) (* 1.0e6 n)))))

(defn unit-metric-name
  "`heading/ocr` -> `heading-ocr`: the name part of a scoreboard key."
  [u] (str/replace u "/" "-"))

(defn metrics
  "{\"conf-ece-U\" v, \"conf-brier-U\" v} for every unit of the pooled counts, insertion-ordered."
  [c]
  (reduce (fn [m u]
            (let [n (unit-metric-name u)]
              (-> m (assoc (str "conf-ece-" n) (ece c u)) (assoc (str "conf-brier-" n) (brier c u)))))
          (array-map) (units-in c)))

(defn lower-better? [metric]
  (boolean (re-find #"^(conf-ece|conf-brier)" (str/replace metric #"^:+" ""))))

;; ------------------------------------------------------------------ isotonic ----

(defn pav
  "Isotonic regression (pool adjacent violators) of points [x n sy] sorted by x: the weighted mean outcome
  non-decreasing in x, and EQUAL steps pooled (a step that says what the one before it says is no step).  -> [[x-lo x-hi n sum-y-permille] ...], one block per pooled run of points."
  [pts]
  (let [blocks (reduce (fn [bs [x n sy]]
                         (loop [bs (conj bs {:lo x :hi x :n n :sy sy})]
                           (let [k (count bs)]
                             (if (and (>= k 2)
                                      (>= (* (:sy (bs (- k 2))) (:n (bs (- k 1)))) (* (:sy (bs (- k 1))) (:n (bs (- k 2))))))
                               (let [a (bs (- k 2)) b (bs (- k 1))]
                                 (recur (conj (subvec bs 0 (- k 2)) {:lo (:lo a) :hi (:hi b) :n (+ (:n a) (:n b)) :sy (+ (:sy a) (:sy b))})))
                               bs))))
                       [] pts)]
    (mapv (fn [b] [(:lo b) (:hi b) (:n b) (:sy b)]) blocks)))
