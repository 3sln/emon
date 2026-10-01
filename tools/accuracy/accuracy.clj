(ns accuracy.accuracy
  "bb accuracy: score a predictor over the synthetic corpus and ratchet (D023, D077, D078).

    bb accuracy                        predictor \"emon\": the Rust Engine's driver (P1.10); the ratchet binds it (D078, D145)
    bb accuracy --predictor oracle     reference: gold -> Model, must score perfect
    bb accuracy --predictor degraded   fixture: damaged gold, proves the metrics move
    bb accuracy --predictor fixtures   both of the above
    bb accuracy --predictor \"CMD ...\"  any command (protocol in predict_oracle.clj)
    bb accuracy --update               record improvements and new entries in the scoreboard
    bb accuracy --only ID              score one document (no ratchet)
    bb accuracy --variants scan,pdf    score only these variants of the corpus (default: every one)
    bb accuracy --ids ID,ID            score only these documents
    bb accuracy --predictor emon-live  the Rust Engine WITH LIVE TESSERACT (P6.5, D211): the scan variants through real OCR.
                                       NOT a gate (it needs Tesseract and takes minutes); `bb ocr-live` runs it for the scans
    bb accuracy --predictor emon-replay  the Rust Engine REPLAYING the committed OCR transcripts of test/ocr/scan/ID/ (a gate's:
                                       `bb ocr` scores the documents that have one; no engine runs)

RATCHET (SPEC 14.2): for every predictor that has a committed entry in
test/scoreboard.edn, every tracked metric of every category must not be worse
than committed by more than the tolerance (absolute; default 0.002, the file's
`:tolerance`). A metric that rose is reported, and `--update` writes it (the
ratchet only ever moves the recorded value toward better, never back). A metric
or category that VANISHED fails. A predictor with no committed entry is reported
\"no baseline\" and does not fail; `--update` creates it.

Scoreboard keys: predictor -> \"VARIANT/CATEGORY\" -> metric -> value. VARIANT is
the committed input form (pdf, scan, pdf-tagged); CATEGORY is the document id's
family (h, table, furn, ...) or `all`. Counts are pooled (micro-average).
No PDF tool and no producer is invoked; only committed files are read.

A PREDICTOR is a command run as  CMD ID VARIANT INPUT GOLD  that prints an emon-1
Model as JSON on stdout (exit 3: not available yet). The built-in `oracle` and
`degraded` are functions of the same shape, run in-process (their standalone
forms, predict_oracle.clj / predict_degraded.clj, print the same JSON)."
  (:require [accuracy.predict-degraded :as degraded]
            [accuracy.predict-oracle :as oracle]
            [accuracy.score :as S]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [pyjson])
  (:import (java.math BigDecimal RoundingMode)))

(def ROOT
  (loop [d (fs/canonicalize (System/getProperty "user.dir"))]
    (cond (nil? d) (System/getProperty "user.dir")
          (fs/exists? (fs/path d "bb.edn")) (str d)
          :else (recur (fs/parent d)))))
(def HERE (str (fs/path ROOT "tools" "accuracy")))
(def CORPUS (str (fs/path ROOT "test" "corpus" "synth")))
(def BOARD (or (System/getenv "EMON_SCOREBOARD") (str (fs/path ROOT "test" "scoreboard.edn"))))   ; the override is for tests

(def TOLERANCE 0.002)
(def UNAVAILABLE 3)
(def PREDICTORS
  {"oracle" oracle/predict
   "degraded" degraded/predict
   "emon" ["sh" (str (fs/path HERE "predict-emon.sh"))]
   "emon-live" ["sh" (str (fs/path HERE "predict-emon-live.sh"))]
   "emon-replay" ["sh" (str (fs/path HERE "predict-emon-replay.sh"))]})
(def FAMILY {"h" "headings" "table" "tables" "furn" "furniture" "figures" "figures"
             "lists" "lists" "script" "scripts" "columns" "columns" "footnotes" "footnotes"
             "stamp" "seals" "watermark" "watermarks" "invoice" "forms" "bank" "forms"
             "initial" "initials" "align" "align"})

(defn category [doc-id]
  (get FAMILY (first (str/split doc-id #"-" 2)) "other"))

;; --------------------------------------------------------- python number text ----

(defn pystr
  "str() of a number as Python prints it."
  [v] (if (float? v) (pyjson/float-repr v) (str v)))

(defn round6
  "Python's round(v, 6) of a float: half-even on the exact binary value."
  [v]
  (.doubleValue (.setScale (BigDecimal. (double v)) 6 RoundingMode/HALF_EVEN)))

(defn fmt-f
  "C's %.Nf of a double (round-half-even on the exact binary value)."
  [v n]
  (.toPlainString (.setScale (BigDecimal. (double v)) (int n) RoundingMode/HALF_EVEN)))

(defn fmt-g
  "C's %.<prec>g of a double."
  [v prec]
  (if (zero? v)
    "0"
    (let [bd (BigDecimal. (double v))
          r (.round bd (java.math.MathContext. (int prec) RoundingMode/HALF_EVEN))
          x (- (.precision r) (.scale r) 1)]      ; decimal exponent of the rounded value
      (if (and (>= x -4) (< x prec))
        (let [s (.toPlainString (.setScale r (max 0 (- (dec prec) x)) RoundingMode/HALF_EVEN))]
          (if (str/includes? s ".") (str/replace (str/replace s #"0+$" "") #"\.$" "") s))
        (let [digits (str (.abs (.unscaledValue r)))
              digits (str/replace digits #"0+$" "")
              digits (if (empty? digits) "0" digits)]
          (str (when (neg? v) "-") (first digits)
               (when (> (count digits) 1) (str "." (subs digits 1)))
               "e" (if (neg? x) "-" "+") (format "%02d" (Math/abs x))))))))

;; ------------------------------------------------------------------ EDN ----
;; Just enough EDN for the scoreboard: maps, keyword/string keys, numbers, strings.

(defn- sort-key [k] [(not (str/starts-with? k ":")) k])

(defn edn-write
  ([v] (edn-write v 0))
  ([v ind]
   (let [pad (apply str (repeat ind "  "))]
     (cond
       (map? v)
       (if (empty? v)
         "{}"
         (str "{\n"
              (str/join "\n"
                        (for [k (sort-by sort-key (keys v))]
                          (str pad "  " (if (str/starts-with? k ":") k (pyjson/dumps k {:ascii true}))
                               " " (edn-write (get v k) (inc ind)))))
              "}"))
       (float? v) (pyjson/float-repr (round6 v))
       :else (pyjson/dumps v {:ascii true})))))

(defn edn-read [text]
  (let [toks (vec (remove #(str/starts-with? % ";")
                          (re-seq #"\"(?:[^\"\\]|\\.)*\"|;[^\n]*|[{}]|[^\s{}]+" text)))
        pos (volatile! 0)
        val (fn val []
              (let [t (toks @pos)]
                (vswap! pos inc)
                (cond
                  (= t "{") (loop [kvs []]
                              (if (= (toks @pos) "}")
                                (do (vswap! pos inc) (apply array-map kvs))
                                (let [k (val) v (val)] (recur (conj kvs k v)))))
                  (str/starts-with? t "\"") (pyjson/parse t)
                  (str/starts-with? t ":") t
                  (= t "nil") nil
                  (re-find #"[.eE]" t) (Double/parseDouble t)
                  :else (Long/parseLong t))))]
    (val)))

(def HEADER
  ";; The accuracy scoreboard (D023, D077, SPEC 14.2).  `bb accuracy` compares a run
;; with this and FAILS on a regression beyond :tolerance; `bb accuracy --update`
;; records improvements and new entries (the ratchet only moves toward better).
;; predictor -> \"VARIANT/CATEGORY\" -> metric -> value.  Edit by running, not by hand.
;; `oracle` and `degraded` are fixtures that prove the scorer; `emon` is the real pipeline (the Rust
;; Engine, P1.10): its first run is the baseline (D145) and `bb gates` runs the ratchet.
")

(defn load-board []
  (if-not (fs/exists? BOARD)
    {":format" 1 ":tolerance" TOLERANCE ":predictors" {}}
    (edn-read (slurp BOARD :encoding "UTF-8"))))

(defn save-board [b]
  (spit BOARD (str HEADER (edn-write b) "\n") :encoding "UTF-8"))

;; ------------------------------------------------------------------ run ----

(defn run-predictor
  "-> [model why]; model is nil when the predictor is not available yet."
  [cmd doc-id variant inp gold-path]
  (if (fn? cmd)
    [(cmd doc-id variant inp gold-path) ""]
    (let [r (apply proc/sh (concat cmd [doc-id variant inp gold-path]))]
      (cond
        (= (:exit r) UNAVAILABLE) [nil (str/trim (:err r))]
        (not= (:exit r) 0) (do (println (format "FAIL predictor exited %d on %s/%s: %s" (:exit r) variant doc-id (str/trim (:err r))))
                               (System/exit 1))
        :else [(pyjson/parse (:out r)) ""]))))

(def ^:dynamic *variants* nil)   ; a set of variant names, or nil for every one
(def ^:dynamic *ids* nil)        ; a set of document ids, or nil for every one

(defn corpus []
  (let [man (pyjson/parse-file (str (fs/path CORPUS "MANIFEST.json")))]
    (for [doc-id (sort (keys (get man "docs")))
          :when (or (nil? *ids*) (contains? *ids* doc-id))
          :let [files (get-in man ["docs" doc-id "files"])]
          variant ["pdf" "scan" "pdf-tagged"]
          :when (or (nil? *variants*) (contains? *variants* variant))
          :when (contains? files variant)]
      [doc-id variant (str (fs/path CORPUS (get-in files [variant "path"])))
       (str (fs/path CORPUS "gold" (str doc-id ".gold.json")))])))

(defn evaluate
  "-> [board entry {\"variant/cat\" {\":metric\" v}}, per-document metrics] or [nil why] if unavailable."
  ([cmd] (evaluate cmd nil))
  ([cmd only]
   ;; each document is scored independently: predict in order (a predictor may say
   ;; "unavailable" on the first), score in parallel
   (let [docs (filter (fn [[doc-id]] (or (nil? only) (= doc-id only))) (corpus))
         preds (loop [ds (seq docs), out []]
                 (if-let [[[doc-id variant inp gp] & more] ds]
                   (let [[pred why] (run-predictor cmd doc-id variant inp gp)]
                     (if (nil? pred) [nil why] (recur more (conj out pred))))
                   [out nil]))]
     (if (nil? (first preds))
       [nil (second preds)]
       (loop [rs (pmap (fn [[doc-id variant _ gp] pred]
                         [doc-id variant (get (S/score pred (pyjson/parse-file gp)) "counts")])
                       docs (first preds))
              pools {}, per-doc (array-map)]
         (if-let [[[doc-id variant c] & more] (seq rs)]
           (recur more
                  (reduce (fn [p cat] (update p (str variant "/" cat) (fnil conj []) c))
                          pools ["all" (category doc-id)])
                  (assoc per-doc (str variant "/" doc-id) (S/metrics c)))
       [(apply array-map
               (mapcat (fn [[k cs]]
                         [k (apply array-map
                                   (mapcat (fn [[m v]] [(str ":" m) (round6 v)])
                                           (S/metrics (S/pool cs))))])
                       (sort-by key pools)))
        per-doc]))))))

(defn lower-better? [metric]
  (contains? S/LOWER-IS-BETTER (str/replace metric #"^:+" "")))

(defn compare-boards
  "-> [regressions improvements vanished] as vectors of [key metric old new]."
  [old new tol]
  (reduce
   (fn [[reg imp gone] [key ms]]
     (reduce
      (fn [[reg imp gone] [m ov]]
        (let [nv (get-in new [key m])]
          (if (nil? nv)
            [reg imp (conj gone [key m ov nil])]
            (let [d (if (lower-better? m) (- ov nv) (- nv ov))]
              (cond (< d (- tol)) [(conj reg [key m ov nv]) imp gone]
                    (> d 1e-7) [reg (conj imp [key m ov nv]) gone]
                    :else [reg imp gone])))))
      [reg imp gone] ms))
   [[] [] []] old))

(defn show
  ([title rows] (show title rows 25))
  ([title rows limit]
   (when (seq rows)
     (println (format "  %s: (%d)" title (count rows)))
     (doseq [[key m ov nv] (take limit rows)]
       (println (format "    %-22s %-24s %s -> %s" key m (pystr ov) (if (nil? nv) "absent" (fmt-g nv 6)))))
     (when (> (count rows) limit)
       (println (format "    ... %d more" (- (count rows) limit)))))))

(defn merge-up
  "The ratchet: per metric keep the better value; add what is new."
  [old new]
  (reduce (fn [out [key ms]]
            (reduce (fn [out [m nv]]
                      (let [ov (get-in out [key m])]
                        (if (or (nil? ov) (if (lower-better? m) (< nv ov) (> nv ov)))
                          (assoc-in out [key m] nv)
                          out)))
                    (if (contains? out key) out (assoc out key (array-map)))
                    ms))
          old new))

(defn do-predictor [name cmd board update only]
  (let [[entry extra] (evaluate cmd only)]
    (cond
      (nil? entry)
      (do (println (format "  ok   accuracy[%s]: not yet available (%s); nothing scored, not a failure" name extra))
          [0 board])

      only
      (do (doseq [[k ms] extra]
            (println (str "  " k))
            (doseq [[m v] (sort-by key ms)]
              (println (format "    %-24s %s" m (fmt-f v 4)))))
          [0 board])

      :else
      (let [tol (get board ":tolerance" TOLERANCE)
            old (get-in board [":predictors" name])
            n (reduce + (map count (vals entry)))
            fails 0
            fails (if (= name "oracle")
                    (let [bad (vec (for [[k ms] entry [m v] ms
                                         :when (and (> (Math/abs (- v (if (lower-better? m) 0.0 1.0))) 1e-9)
                                                    (not (str/starts-with? m ":order")))]
                                     [k m 1.0 v]))]
                      (if (seq bad)
                        (do (println "  FAIL accuracy[oracle] is not perfect, so the SCORER is wrong:")
                            (show "imperfect" bad)
                            (inc fails))
                        fails))
                    fails)
            fails (if (nil? old)
                    (do (println (format "  ok   accuracy[%s]: %d metrics in %d categories, no committed baseline%s"
                                         name n (count entry) (if update "" " (run --update to record)")))
                        fails)
                    (let [[reg imp gone] (compare-boards old entry tol)]
                      (show (format "regressions beyond %s" (fmt-g tol 4)) reg)
                      (show "vanished" gone)
                      (show "improved (--update records)" imp)
                      (if (or (seq reg) (seq gone))
                        (do (println (format "  FAIL accuracy[%s]: %d regression(s), %d vanished metric(s)" name (count reg) (count gone)))
                            (inc fails))
                        (do (println (format "  ok   accuracy[%s]: %d metrics, none regressed beyond %s (%d improved)"
                                             name n (fmt-g tol 4) (count imp)))
                            fails))))]
        (if (and update (zero? fails))
          (let [board (-> board
                          (assoc-in [":predictors" name] (merge-up (or old (array-map)) entry))
                          (assoc ":tolerance" tol))]
            (save-board board)
            (println (format "  ok   scoreboard updated: %s" (str (fs/relativize ROOT BOARD))))
            [fails board])
          [fails board])))))

(defn -main [& argv]
  (loop [a argv, pred "emon", update false, only nil]
    (if (seq a)
      (let [x (first a)]
        (case x
          "--predictor" (recur (nnext a) (second a) update only)
          "--update" (recur (next a) pred true only)
          "--only" (recur (nnext a) pred update (second a))
          "--variants" (do (alter-var-root #'*variants* (constantly (set (str/split (second a) #","))))
                           (recur (nnext a) pred update only))
          "--ids" (do (alter-var-root #'*ids* (constantly (set (str/split (second a) #","))))
                      (recur (nnext a) pred update only))
          (do (println "usage: accuracy.clj [--predictor emon|emon-live|emon-replay|oracle|degraded|fixtures|CMD] [--update] [--only ID] [--variants V,V] [--ids ID,ID]")
              (System/exit 2))))
      (let [board (load-board)
            runs (cond (= pred "fixtures") [["oracle" (PREDICTORS "oracle")] ["degraded" (PREDICTORS "degraded")]]
                       (contains? PREDICTORS pred) [[pred (PREDICTORS pred)]]
                       :else (let [cmd (str/split (str/trim pred) #"\s+")] [[(first cmd) cmd]]))
            [fails _] (reduce (fn [[fails board] [name cmd]]
                                (let [[f board] (do-predictor name cmd board update only)]
                                  [(+ fails f) board]))
                              [0 board] runs)]
        (System/exit (if (pos? fails) 1 0))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
