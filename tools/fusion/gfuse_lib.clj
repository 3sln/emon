;; Shared by `kin/scripts/gfuse` (the gate), `tools/fusion/gfuse_measure.clj` (the measures) and `tools/accuracy/predict-emon-gfuse.sh`'s tests: the graphics-fusion
;; fixtures of test/fusion/graphics/ and what the graphics measures need of a run of one.   (DEVLOG P7.G, D1320-D1327)
;;
;; A run of a fixture is one of three SOURCES' answers, each a Model and an evidence dump (version "2"):
;;   :fused    the committed transcript replayed through the whole Engine (pdfium's renders, the tracer's reading of their pixels, Tesseract's words)
;;   :vector   the born-digital document converted with `--ocr=never` (no raster answer: the vector source alone, P5.5a's `gf-page`); a scanned page has no vector source
;;   :tracer   the fused run's dump read as the tracer's own answer: its `rules` rows as rules and its `prims` tints / rectangle edges as fills / rules, before any arbitration
(ns fusion.gfuse-lib
  (:require [accuracy.accuracy :as A]
            [accuracy.graphics :as G]
            [accuracy.score :as S]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def gdir "test/fusion/graphics")
(def fixtures (edn/read-string (slurp (str gdir "/FIXTURES.edn"))))
(def ids (vec (sort (filter #(fs/exists? (str gdir "/" % "/transcript.edn")) (keys fixtures)))))
(def digests (let [f (str gdir "/DIGESTS.edn")] (if (fs/exists? f) (read-string (slurp f)) {})))

(defn abs [p] (str (fs/absolutize p)))
(defn input-of [id] (let [{:keys [variant doc]} (get fixtures id)] (abs (str "test/corpus/synth/" variant "/" doc ".pdf"))))
(defn replay-dir [id] (abs (str gdir "/" id)))
(defn variant-of [id] (:variant (get fixtures id)))
(defn doc-of [id] (:doc (get fixtures id)))
(defn gold-of [id] (json/parse-string (slurp (str "test/corpus/synth/gold/" (doc-of id) ".gold.json"))))

(defn drive [bin & args]
  (let [r @(apply proc/process {:out :string :err :string} (abs bin) (map str args))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String s "UTF-8"))))))

;; ------------------------------------------------------------------ runs

(defn- parse [r] (when (zero? (:exit r)) (json/parse-string (:out r))))

(defn run-fused
  "{:model :dump} of the committed transcript replayed by the driver `bin`, or nil."
  [bin id]
  (let [rep (str "--replay=" (replay-dir id))
        m (parse (drive bin "--out=json" rep (input-of id)))
        d (parse (drive bin "--out=evidence" rep (input-of id)))]
    (when (and m d) {:model m :dump d})))

(defn run-vector
  "The vector source alone (`--ocr=never`): a born-digital fixture's, or nil."
  [bin id]
  (when (= "pdf" (variant-of id))
    (let [m (parse (drive bin "--ocr=never" "--out=json" (input-of id)))
          d (parse (drive bin "--ocr=never" "--out=evidence" (input-of id)))]
      (when (and m d) {:model m :dump d}))))

(defn tracer-dump
  "The tracer's candidates of an evidence dump as `grl` and `gfl` rows: its `rules` (the `line` is the centre line, in points), the edges of its unfilled `prims` rectangles
  (flag 2 = filled, 8 = tint) and its tints as fills."
  [dump]
  {"grl" (vec (concat
               (for [r (get dump "rules") :let [[x0 y0 x1 y1] (get r "line")]]
                 {"page" (get r "page") "ex0" x0 "ey0" y0 "ex1" x1 "ey1" y1 "thickness" (get r "thick")})
               (for [p (get dump "prims") :when (and (= "rect" (get p "kind")) (zero? (bit-and 8 (get p "flags" 0))) (zero? (bit-and 2 (get p "flags" 0))))
                     :let [[x0 y0 x1 y1] (get p "box") sw (let [s (get p "stroke" 0)] (if (pos? s) s 1.0)) h (/ sw 2.0)
                           pg (get p "page")]
                     e [[x0 (+ y0 h) x1 (+ y0 h)] [x0 (- y1 h) x1 (- y1 h)] [(+ x0 h) y0 (+ x0 h) y1] [(- x1 h) y0 (- x1 h) y1]]]
                 {"page" pg "ex0" (e 0) "ey0" (e 1) "ex1" (e 2) "ey1" (e 3) "thickness" sw})))
   "gfl" (vec (for [p (get dump "prims") :when (and (= "rect" (get p "kind")) (not (zero? (bit-and 8 (get p "flags" 0)))))]
                {"page" (get p "page") "box" (get p "box")}))
   "tb" []})

;; ------------------------------------------------------------------ scoring

(defn score-run
  "The counts of one run (S/score's \"counts\") of fixture `id` against the corpus's gold and rule truth."
  [id {:keys [model dump]}]
  (let [doc (doc-of id)
        g (A/doc-graphics doc)
        res (S/score model (gold-of id) (assoc g :variant (variant-of id) :dump dump))]
    (get res "counts")))

(defn metrics-of [counts] (S/metrics counts))

(defn pooled
  "Metrics of the pooled counts of several [id counts]."
  [rows]
  (S/metrics (S/pool (map second rows))))

(defn graphic-counts
  "Only the graphics counts of a run's dump (no Model): the rule and fill length and area counts of fixture `id`."
  [id dump]
  (let [g (A/doc-graphics (doc-of id))
        v (variant-of id)]
    (when (:rules g) (G/rule-counts (G/truth-for (:rules g) (:facts g) v) dump (G/tolerance (:facts g) v)))))

(defn graphic-counts*
  "As `graphic-counts` for a corpus document `doc` of input form `variant` (a fixture of another set)."
  [doc variant dump]
  (let [g (A/doc-graphics doc)]
    (when (:rules g) (G/rule-counts (G/truth-for (:rules g) (:facts g) variant) dump (G/tolerance (:facts g) variant)))))

(defn prf
  "Rule or fill P and R from pooled graphics counts."
  [c pre]
  (let [g (get c (str pre ".gold") 0.0) p (get c (str pre ".pred") 0.0) go (get c (str pre ".gold-ok") 0.0) po (get c (str pre ".pred-ok") 0.0)]
    {:p (if (zero? p) (if (zero? g) 1.0 0.0) (/ po p)) :r (if (zero? g) (if (zero? p) 1.0 0.0) (/ go g)) :gold g :pred p}))

(defn add-counts [rows] (apply merge-with + (map second rows)))

;; ------------------------------------------------------------------ rule ghosts

(def ruleish #"^[|¦│┃_‗—–=~\\/]{1,3}$")

(defn- walk [n] (when (map? n) (cons n (mapcat walk (get n "children")))))

(defn ghosts
  "Rule ghosts of a Model: the body's words that are only rule marks (| _ —) and the ledger's `rule-ghost` entries, against the gold's own count of such words."
  [model gold]
  (let [words (fn [m] (for [n (walk (get m "body")) :when (and (string? (get n "text")) (empty? (get n "children")))
                           w (str/split (get n "text") #"\s+") :when (re-matches ruleish w)] w))]
    {:body (count (words model))
     :gold (count (words (get gold "model")))
     :ledger (count (filter #(= "rule-ghost" (get % "reason")) (get model "dropped")))}))

(defn pr3 [x] (format "%.3f" (double x)))
