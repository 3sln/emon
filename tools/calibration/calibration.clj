(ns calibration.calibration
  "bb calibration: how well do the Model's confidences say how often they are right (P10.1, SPEC 9.6, 3.5).

    bb calibration                 REPORT: every kind's reliability (ten bins), ECE and Brier, over the committed
                                   synthetic corpus as the real pipeline converts it; a report, not a gate (the gate
                                   is the `conf-*` rows of `bb accuracy`, which ratchet)
    bb calibration --write         the report AND the committed data: test/calibration/reliability.csv (the bins),
                                   summary.csv (ECE and Brier per kind, category, run and source), reliability.svg
    bb calibration --fit           FIT the per-kind isotonic map from RAW confidences (an engine whose map is the
                                   identity, `--refit` makes one): test/calibration/fit.edn, the raw data files
                                   `raw-*`, and kin/calibdata.kin (the `defdata` table `emon.calib` reads)
    bb calibration --refit         identity table, `bb emit`, collect, fit, the real table, `bb emit`: the whole of a
                                   recalibration, which is a reviewed change with its reliability diagram (SPEC 9.6)

THE DATA is three runs of the real Engine: `emon` over every input form of the corpus, `emon-replay` over the scans
that have a committed OCR transcript, and `emon-fusion` over the born-digital documents that have a fusion transcript
(the replay predictors of `bb accuracy`: no engine but ours runs).  A node's `kind` is the Model's, its `class` is
where its text came from (text layer, OCR, both: `fused`; `infer` for structure), a document's `category` is its id's
family, a run is the predictor.  'Right' is `accuracy.confrows`'s (the scorer's matching).

THE FIT: per kind and class, over the distinct RAW confidences, isotonic regression of the outcome on the raw value
(pool adjacent violators), Laplace-smoothed ((right + 1) / (n + 2): a thousand right of a thousand is 999, not
certainty); a class with fewer than MIN-N rows takes its kind's pooled fit; a kind with fewer than MIN-N has no
row, and its confidence goes out as it is.  HELD-OUT numbers: the fit is repeated with each of five folds of the
documents (by a hash of the id) left out and applied to it; `ece-cv` and `brier-cv` are those."
  (:require [accuracy.accuracy :as A]
            [accuracy.calib :as C]
            [accuracy.confrows :as CR]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [pyjson]))

(def ROOT A/ROOT)
(def OUT (str (fs/path ROOT "test" "calibration")))
(def MIN-N 8)
(def CLASS-TOL 20)   ; per mille: a class within this of its kind's fit is not worth a row
(def FOLDS 5)

(defn- dirs-with [parent file]
  (set (for [d (fs/list-dir parent) :when (fs/exists? (fs/path d file))] (str (fs/file-name d)))))

(defn runs []
  [{:name "emon" :variants nil :ids nil}
   {:name "emon-replay" :variants #{"scan"} :ids (dirs-with (fs/path ROOT "test" "ocr" "scan") "transcript.edn")}
   {:name "emon-fusion" :variants #{"pdf"} :ids (dirs-with (fs/path ROOT "test" "fusion" "replay") "transcript.edn")}])

(defn- predict [cmd doc-id variant inp gold-path]
  (let [r (apply proc/sh (concat cmd [doc-id variant inp gold-path]))]
    (case (:exit r)
      0 (pyjson/parse (:out r))
      3 nil
      (throw (ex-info (format "predictor %s exited %d on %s/%s: %s" (first cmd) (:exit r) variant doc-id (str/trim (:err r))) {})))))

(defn collect
  "Every row of every run: {:run :variant :cat :id :kind :class :conf :y}."
  []
  (vec
   (for [{:keys [name variants ids]} (runs)
         :let [cmd (A/PREDICTORS name)
               docs (filter (fn [[id v]] (and (or (nil? variants) (contains? variants v)) (or (nil? ids) (contains? ids id))))
                            (A/corpus))
               per (doall (pmap (fn [[id v inp gp]]
                                  (when-let [m (predict cmd id v inp gp)]
                                    (for [[kind class conf y] (CR/rows m (pyjson/parse-file gp))]
                                      {:run name :variant v :cat (A/category id) :id id :kind kind :class class :conf conf :y y})))
                                docs))]
         row (apply concat per)]
     row)))

(defn- header-conf
  "The `conf` word of the Model's header as the Engine writes it now: `uncalibrated` or `calibrated`."
  []
  (let [[id v inp gp] (first (filter (fn [[id v]] (and (= id "h-plain") (= v "pdf"))) (A/corpus)))
        m (predict (A/PREDICTORS "emon") id v inp gp)]
    (get m "conf")))

;; ------------------------------------------------------------------ aggregation ----

(defn- counts-of [rows]
  (C/row-counts (map (juxt :kind :class :conf :y) rows)))

(defn- group-by-scope
  "{[scope group] rows}: the whole, each category, each run, each variant."
  [rows]
  (merge {["all" "all"] rows}
         (into {} (for [[k rs] (group-by :cat rows)] [["category" k] rs]))
         (into {} (for [[k rs] (group-by :run rows)] [["run" k] rs]))))

(defn- fmt [v n] (if (nil? v) "" (String/format java.util.Locale/ROOT (str "%." n "f") (to-array [(double v)]))))

;; ------------------------------------------------------------------ the fit ----

(defn- smoothed
  "Laplace: (right + 1) / (n + 2), per mille, `sy` the sum of the outcomes per mille."
  [n sy]
  (long (Math/round (* 1000.0 (/ (+ (/ (double sy) 1000.0) 1.0) (+ n 2.0))))))

(defn- fit-points
  "Isotonic steps [[raw-lo cal n] ...] of rows (maps) -> the smoothed per mille accuracy per pooled run of raw values."
  [rows]
  (let [pts (for [[raw rs] (sort-by key (group-by :conf rows))] [raw (count rs) (reduce + 0 (map :y rs))])]
    (vec (for [[lo _hi n sy] (C/pav pts)]
           [lo (smoothed n sy) n]))))

(defn fit-base
  "{[kind class] steps}: the per (kind, class) fit, a class with too few rows taking its kind's pooled fit under the
  class `any`, a kind with too few rows nothing."
  [rows]
  (let [by-kind (group-by :kind rows)]
    (into (sorted-map)
          (mapcat (fn [[kind rs]]
                    (when (>= (count rs) MIN-N)
                      (let [any (fit-points rs)]
                        (concat [[[kind "any"] any]]
                                ;; a class that says what its kind says (the same steps, within CLASS-TOL) is no row
                                (for [[class crs] (group-by :class rs)
                                      :when (>= (count crs) MIN-N)
                                      :let [steps (fit-points crs)]
                                      :when (not (and (= (map first steps) (map first any))
                                                      (every? #(<= % CLASS-TOL) (map #(Math/abs (long (- (second %1) (second %2)))) steps any))))]
                                  [[kind class] steps])))))
                  by-kind))))

(defn apply-fit
  "The calibrated confidence of a raw one under `fit`, the way `emon.calib` reads the table."
  [fit kind class raw]
  (if-let [steps (or (get fit [kind class]) (get fit [kind "any"]))]
    (let [s (or (last (filter #(<= (first %) raw) steps)) (first steps))] (second s))
    raw))

(defn- fold-of [id] (mod (Math/abs (long (hash id))) FOLDS))

(defn cv-rows
  "The rows with `:conf` replaced by the held-out calibration of it: each fold calibrated by a `fit-base` that has
  not seen its documents, and only for the kinds in `kinds` (nil: every kind)."
  ([rows] (cv-rows rows nil))
  ([rows kinds]
   (let [fits (into {} (for [f (range FOLDS)]
                         [f (into {} (filter (fn [[[k _] _]] (or (nil? kinds) (contains? kinds k)))
                                             (fit-base (remove #(= f (fold-of (:id %))) rows))))]))]
     (mapv (fn [r] (assoc r :conf (apply-fit (fits (fold-of (:id r))) (:kind r) (:class r) (:conf r)))) rows))))

(defn fit
  "`fit-base`, for the kinds whose HELD-OUT ECE beats the raw one: a map fitted on other documents that does not
  make a kind's confidence truer on these is not a calibration of it (a kind that lives in a handful of documents,
  whose hard ones the others do not predict, is left as the rule wrote it)."
  [rows]
  (let [base (fit-base rows)
        craw (counts-of rows)
        ccv (counts-of (cv-rows rows))
        keep (set (for [kind (distinct (map :kind rows))
                        :let [r (C/ece craw kind) c (C/ece ccv kind)]
                        :when (and r c (< c r))]
                    kind))]
    (into (sorted-map) (filter (fn [[[kind _] _]] (contains? keep kind)) base))))

;; ------------------------------------------------------------------ files ----

(defn- csv [header rows] (str (str/join "," header) "\n" (str/join "\n" (map #(str/join "," %) rows)) "\n"))

(defn- reliability-csv [rows]
  (csv ["scope" "group" "unit" "bin" "lo" "hi" "n" "mean_conf" "accuracy"]
       (for [[[scope group] rs] (sort-by key (group-by-scope rows))
             :let [c (counts-of rs)]
             u (C/units-in c)
             [b n sc sy] (C/bins-of c u)]
         [scope group u b (* 100 b) (if (= b 9) 1000 (dec (* 100 (inc b)))) n
          (fmt (/ sc (* 1000.0 n)) 4) (fmt (/ sy (* 1000.0 n)) 4)])))

(defn- summary-csv [rows cvrows]
  (let [cvg (group-by-scope cvrows)]
    (csv ["scope" "group" "unit" "n" "mean_conf" "accuracy" "ece" "brier" "ece_cv" "brier_cv"]
         (for [[[scope group] rs] (sort-by key (group-by-scope rows))
               :let [c (counts-of rs) cc (some-> (get cvg [scope group]) counts-of)]
               u (C/units-in c)
               :let [bs (C/bins-of c u) n (C/unit-n c u)]]
           [scope group u n
            (fmt (/ (reduce + 0 (map #(nth % 2) bs)) (* 1000.0 n)) 4)
            (fmt (/ (reduce + 0 (map #(nth % 3) bs)) (* 1000.0 n)) 4)
            (fmt (C/ece c u) 4) (fmt (C/brier c u) 4)
            (if (and cc (pos? (C/unit-n cc u))) (fmt (C/ece cc u) 4) "")
            (if (and cc (pos? (C/unit-n cc u))) (fmt (C/brier cc u) 4) "")]))))

;; the reliability diagram: one small plot per kind, bins as circles (area ~ n) on the mean confidence and the accuracy
(defn- svg [rows title]
  (let [c (counts-of rows)
        kinds (filter #(not (str/includes? % "/")) (C/units-in c))
        kinds (concat (remove #{"all"} kinds) ["all"])
        cols 4 w 220 h 250 pad 34 sz (- w (* 2 pad))
        rowsn (quot (+ (count kinds) cols -1) cols)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " (* cols w) " " (+ 40 (* rowsn h)) "\" font-family=\"sans-serif\" font-size=\"11\">\n"
         "<title>" title "</title>\n"
         "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/>\n"
         "<text x=\"10\" y=\"22\" font-size=\"14\" font-weight=\"bold\">" title "</text>\n"
         (str/join
          (for [[i u] (map-indexed vector kinds)
                :let [x0 (+ (* (mod i cols) w) pad) y0 (+ 40 (* (quot i cols) h) 24)
                      bs (C/bins-of c u)
                      px (fn [v] (+ x0 (* sz v))) py (fn [v] (+ y0 (* sz (- 1.0 v))))
                      n (C/unit-n c u)]]
            (str "<g>\n"
                 "<rect x=\"" x0 "\" y=\"" y0 "\" width=\"" sz "\" height=\"" sz "\" fill=\"none\" stroke=\"#999\"/>\n"
                 "<line x1=\"" (px 0) "\" y1=\"" (py 0) "\" x2=\"" (px 1) "\" y2=\"" (py 1) "\" stroke=\"#bbb\" stroke-dasharray=\"4 3\"/>\n"
                 "<polyline fill=\"none\" stroke=\"#1f6feb\" stroke-width=\"1.5\" points=\""
                 (str/join " " (for [[_ nb sc sy] bs] (str (fmt (px (/ sc (* 1000.0 nb))) 1) "," (fmt (py (/ sy (* 1000.0 nb))) 1)))) "\"/>\n"
                 (str/join (for [[_ nb sc sy] bs]
                             (str "<circle cx=\"" (fmt (px (/ sc (* 1000.0 nb))) 1) "\" cy=\"" (fmt (py (/ sy (* 1000.0 nb))) 1)
                                  "\" r=\"" (fmt (+ 2.5 (* 6.0 (Math/sqrt (/ (double nb) n)))) 1) "\" fill=\"#1f6feb\" fill-opacity=\"0.55\"/>\n")))
                 "<text x=\"" x0 "\" y=\"" (- y0 12) "\" font-weight=\"bold\">" u "  n=" n "</text>\n"
                 "<text x=\"" x0 "\" y=\"" (+ y0 sz 14) "\">ECE " (fmt (C/ece c u) 3) "  Brier " (fmt (C/brier c u) 3) "</text>\n"
                 "<text x=\"" x0 "\" y=\"" (+ y0 sz 26) "\" fill=\"#666\">confidence (x) vs accuracy (y)</text>\n"
                 "</g>\n")))
         "</svg>\n")))

;; ------------------------------------------------------------------ the kin table ----

(def CLASS-CODE {"text" 0 "ocr" 1 "fused" 2 "infer" 3 "any" -1})

(defn- model-codes
  "{\"heading\" 3 ...}: the node kinds of kin/model.kin's `nk`."
  []
  (into {} (for [[_ code name] (re-seq #"\[nk-[a-z-]+ (\d+) \"([a-z-]+)\"\]" (slurp (str (fs/path ROOT "kin" "model.kin"))))]
             [name (Long/parseLong code)])))

(defn kin-table
  "kin/calibdata.kin for `fit` ({[kind class] steps}); nil fit -> the identity table (no rows)."
  [fit]
  (let [codes (assoc (model-codes) "dropped" 100)
        rows (for [[[kind class] steps] fit [lo cal _n] steps]
               [(codes kind) (CLASS-CODE class) lo cal])
        rows (sort rows)]
    (str ";; GENERATED by tools/calibration/calibration.clj (`bb calibration --fit`) -- do not edit.\n"
         ";;\n"
         ";; SPEC 9.6: the per-kind isotonic map from a rule's raw confidence to the calibrated one, fitted offline on the\n"
         ";; evaluation corpora (test/calibration/fit.edn holds the same rows with their counts, the reliability diagrams\n"
         ";; and the numbers are test/calibration/*) and committed.  A row is [kind class raw-lo calibrated]: for the node\n"
         ";; kind (`nk`, 100 the dropped ledger), the class of its source (0 text layer, 1 OCR, 2 both, 3 inferred, -1 any)\n"
         ";; and the step of raw confidences from raw-lo up (to the next row's), the calibrated confidence, per mille.\n"
         ";; " (count rows) " rows.  Sorted by kind, class, raw-lo.  `emon.calib` reads it.\n"
         "(ns emon.calibdata\n"
         "  (:require [emon.lang :refer [I32]]\n"
         "            [kin.lang :refer [defdata]]))\n\n"
         "(defdata ^:pub cd-rows\n"
         "  :emitter kin.lang/flat-array\n"
         "  :stride 4\n"
         "  :data [" (if (empty? rows)
                       "0 0 0 0"
                       (str/join "\n         " (map #(str/join " " %) (partition-all 4 (apply concat rows)))))
         "]\n"
         "  :accessors {^I32 cd-count [^I32 ignored]\n"
         "              ^I32 cd-at [^I32 ignored ^I32 row ^I32 field]})\n")))

(defn- edn-of [fit]
  (str ";; The fitted confidence calibration (P10.1, SPEC 9.6): [kind class raw-lo calibrated n] per step, per mille.\n"
       ";; `bb calibration --fit` writes it with kin/calibdata.kin, which is these rows without n.\n"
       "{:min-n " MIN-N " :smoothing :laplace\n :rows [\n"
       (str/join "\n" (for [[[kind class] steps] fit [lo cal n] steps] (format "  [%s %s %d %d %d]" (pr-str kind) (pr-str class) lo cal n)))
       "]}\n"))

;; ------------------------------------------------------------------ report ----

(defn- print-report [rows cvrows]
  (let [c (counts-of rows) cc (some-> cvrows counts-of)]
    (println (format "  %-18s %7s %9s %9s %9s %8s %8s" "unit" "n" "mean-conf" "accuracy" "ECE" "Brier" "ECE-cv"))
    (doseq [u (C/units-in c) :when (not (str/includes? u "/"))
            :let [bs (C/bins-of c u) n (C/unit-n c u)]]
      (println (format "  %-18s %7d %9s %9s %9s %8s %8s" u n
                       (fmt (/ (reduce + 0 (map #(nth % 2) bs)) (* 1000.0 n)) 3)
                       (fmt (/ (reduce + 0 (map #(nth % 3) bs)) (* 1000.0 n)) 3)
                       (fmt (C/ece c u) 3) (fmt (C/brier c u) 3)
                       (if (and cc (pos? (C/unit-n cc u))) (fmt (C/ece cc u) 3) ""))))))

(defn- write! [name s]
  (fs/create-dirs OUT)
  (spit (str (fs/path OUT name)) s)
  (println "  wrote" (str (fs/relativize ROOT (fs/path OUT name)))))

(defn -main [& argv]
  (let [flags (set argv)
        refit? (flags "--refit")
        fit? (or (flags "--fit") refit?)
        write? (flags "--write")]
    (when refit?
      (spit (str (fs/path ROOT "kin" "calibdata.kin")) (kin-table nil))
      (let [r (proc/shell {:continue true} "bb" "emit")] (when-not (zero? (:exit r)) (System/exit 1))))
    (let [hc (header-conf)
          rows (collect)
          ;; RAW: no map is applied (the Model says so, or the committed table is the identity one `--refit` writes)
          raw? (or (= hc "uncalibrated")
                   (str/includes? (slurp (str (fs/path ROOT "kin" "calibdata.kin"))) ":data [0 0 0 0]"))]
      (println (format "calibration: %d rows from %d documents; the Model's header says conf %s%s"
                       (count rows) (count (distinct (map (juxt :run :variant :id) rows))) (pr-str hc)
                       (if raw? " (RAW confidences)" " (calibrated confidences)")))
      (when (and fit? (not raw?))
        (println "FAIL --fit needs RAW confidences: the Engine's map is applied (use --refit, which builds an identity one)")
        (System/exit 1))
      (let [f (when raw? (fit rows))
            ;; the held-out numbers are those of the fit that is kept: a kind without a map is as it was
            cvrows (when raw? (cv-rows rows (set (map first (keys f)))))
            pre (if fit? "raw-" "")]
        (print-report rows cvrows)
        (when (or write? fit?)
          (write! (str pre "reliability.csv") (reliability-csv rows))
          (write! (str pre "summary.csv") (summary-csv rows cvrows))
          (write! (str pre "reliability.svg") (svg rows (if fit? "Reliability, raw confidences (before the map)" "Reliability, as the Engine writes it"))))
        (when fit?
          (write! "fit.edn" (edn-of f))
          (spit (str (fs/path ROOT "kin" "calibdata.kin")) (kin-table f))
          (println "  wrote kin/calibdata.kin")
          (println "  kinds with a map:" (str/join ", " (distinct (map first (keys f)))))
          (println "  kinds left as the rules wrote them:" (str/join ", " (sort (remove (set (map first (keys f))) (distinct (map :kind rows))))))
          (when refit?
            (let [r (proc/shell {:continue true} "bb" "emit")] (when-not (zero? (:exit r)) (System/exit 1)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
