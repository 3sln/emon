(ns accuracy.predict-oracle
  "REFERENCE PREDICTOR \"oracle\": gold -> Model. Scores perfect, by construction.

A predictor is a command run as  CMD ID VARIANT INPUT GOLD  (variant: pdf, scan,
pdf-tagged; INPUT the committed file; GOLD the gold file) that prints an emon-1
Model as JSON on stdout. Exit 3 means \"not available yet\" (not a failure).

This one ignores INPUT and copies the gold's model. It exists to prove the
scorer and the ratchet: if it is not perfect, the SCORER is wrong (D077).
Two things the gold does not hold are made from its `facts` (D114, D115), as a
perfect emon would write them: with no locale, the text carries each printed
hyphen (SPEC 3.6); and a document with `facts.frags` gets a page `layout` (one
cell per [page band col], 100 pt apart) and, on each block, one region per
fragment inside its cell.

    bb -cp tools:tools/lib tools/accuracy/predict_oracle.clj ID VARIANT INPUT GOLD"
  (:require [accuracy.score :as S]
            [pyjson]))

(defn- cell-box [[_ band col]]
  [(* 100 col) (* 100 band) (+ (* 100 col) 90) (+ (* 100 band) 90)])

(defn- add-layout [m frags]
  (let [cells (group-by first (distinct (mapcat val frags)))
        pages (mapv (fn [pg]
                      (if-let [cs (get cells (get pg "n"))]
                        (assoc pg "layout"
                               (vec (for [c (sort-by (juxt second #(nth % 2)) cs)]
                                      {"band" (second c) "col" (nth c 2) "role" "body" "box" (cell-box c)})))
                        pg))
                    (get m "pages"))
        body ((fn go [n]
                (let [n (if-let [fs (get frags (str (get n "id")))]
                          (assoc n "at" (mapv (fn [[pg :as c]] (into [pg] (cell-box c))) fs))
                          n)]
                  (if (contains? n "children") (assoc n "children" (mapv go (get n "children"))) n)))
              (get m "body"))]
    (assoc m "pages" pages "body" body)))

(defn predict-gold
  "The Model a perfect emon writes for a parsed gold map."
  [gold]
  (let [m (get gold "model")
        hy (get-in gold ["facts" "hyphens"])
        frags (S/frag-map (get gold "facts"))
        m (if (and (seq hy) (nil? (get-in m ["settings" "locale"])))
            (assoc m "body" (S/rewrite-hyphens (get m "body") hy))
            m)]
    (if (and (get-in gold ["annotated" "layout"]) (seq frags)) (add-layout m frags) m)))

(defn predict
  "In-process form: (predict id variant input gold-path) -> the Model map."
  [_id _variant _input gold-path]
  (predict-gold (pyjson/parse-file gold-path)))

(when (= *file* (System/getProperty "babashka.file"))
  (let [[_ _ _ gold] *command-line-args*]
    (print (pyjson/dumps (predict-gold (pyjson/oparse (slurp gold :encoding "UTF-8"))) {:ascii true}))
    (flush)))
