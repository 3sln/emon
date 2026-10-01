(ns ocr.gold-sample
  "A SAMPLE of the public datasets' gold through live Tesseract (P6.5, D189). NOT a gate, and it needs the corpus
(`corpus/_gold`, never committed) and Tesseract.

    bb -cp tools:tools/lib tools/ocr/gold_sample.clj [--n N] [--seed K] [dataset ...]     datasets: funsd sroie cord (default all three)

For each dataset N gold files are taken evenly spaced over the sorted list (default 12); each one's image (the gold's
`provenance.variants.image`, a path under `corpus/`) goes through the Rust Engine with the Tesseract port
(`predict-emon-live.sh`'s binary), and the Model it gives is scored against the gold by `accuracy.score`, which scores only what the
gold claims (`annotated`): SROIE's gold is line-level human transcription (`text true`: CER, WER and a bag of words are
scored); FUNSD's and CORD's are layout (headings, fields; word boxes) with no text claim, so their numbers are the blocks'
precision and recall by box overlap. THE BOXES: a gold page is its image's pixel size taken as points (1 px = 1 pt; `w` and `h`
are the pixels), and the Model's image page is in points at the resolution the image states (300 dpi when it states none), so
each predicted box is scaled by (gold page / predicted page) per axis before scoring; nothing else is touched. The scorer's
matching of blocks is kind-blind, and a receipt line is a paragraph for SROIE and a heading, field or paragraph for FUNSD:
read these numbers as OCR quality under the Engine's whole pipeline, not as a layout benchmark (no stage of the pipeline is trained
for forms yet: P4 follows)."
  (:require [accuracy.score :as S]
            [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [pyjson]))

(def BIN ".cache/emon-rs-live/target/release/emon-rs")

(defn scale-boxes
  "Scale every `at` box of a predicted Model into the gold's page units, per page."
  [pred gold]
  (let [gp (into {} (map (fn [p] [(get p "n") p]) (get-in gold ["model" "pages"] (get gold "pages"))))
        pp (into {} (map (fn [p] [(get p "n") p]) (get pred "pages")))
        scale (fn [at]
                (vec (for [[n x0 y0 x1 y1] at]
                       (let [g (get gp n) p (get pp n)
                             sx (if (and g p (pos? (get p "w"))) (/ (double (get g "w")) (get p "w")) 1.0)
                             sy (if (and g p (pos? (get p "h"))) (/ (double (get g "h")) (get p "h")) 1.0)]
                         [n (* x0 sx) (* y0 sy) (* x1 sx) (* y1 sy)]))))
        walk (fn walk [n]
               (cond-> n
                 (get n "at") (update "at" scale)
                 (get n "children") (update "children" #(mapv walk %))))]
    (update pred "body" walk)))

(defn gold-docs [dataset]
  (->> (fs/glob (str "corpus/_gold/" dataset) "*.gold.json") (map str) sort vec))

(defn sample [xs n]
  (if (<= (count xs) n) xs (mapv #(nth xs (quot (* % (count xs)) n)) (range n))))

(defn predict [image]
  (let [r (proc/sh BIN "--tesseract" (str (fs/path "corpus" image)))]
    (when (zero? (:exit r)) (pyjson/parse (:out r)))))

(defn run-dataset [dataset n]
  (let [docs (sample (gold-docs dataset) n)
        t0 (System/currentTimeMillis)
        rows (for [f docs
                   :let [gold (pyjson/parse-file f)
                         img (get-in gold ["provenance" "variants" "image"]
                                     (second (re-find #"variants \{image ([^}]+)\}" (str (get gold "provenance")))))
                         pred (when img (predict img))]
                   :when pred]
               (get (S/score (scale-boxes pred gold) gold) "counts"))
        pooled (S/pool rows)
        m (S/metrics pooled)]
    (println (format "  %-6s %d/%d documents, %d s" dataset (count rows) (count docs) (quot (- (System/currentTimeMillis) t0) 1000)))
    (doseq [[k v] (sort-by key m)]
      (println (format "    %-22s %.4f" k (double v))))))

(defn -main [& argv]
  (let [n (or (some->> (drop-while #(not= % "--n") argv) second parse-long) 12)
        sets (let [s (remove #(or (str/starts-with? % "--") (re-matches #"[0-9]+" %)) argv)] (if (seq s) s ["funsd" "sroie" "cord"]))]
    (when-not (fs/exists? BIN)
      (println "no live driver: run EMON_RS_LIVE=1 kin/scripts/emon-rs-build first")
      (System/exit 3))
    (when-not (fs/exists? "corpus/_gold")
      (println "no corpus/_gold (symlink the corpus, then `bb import`)")
      (System/exit 3))
    (println (format "  public-dataset gold through live Tesseract: %d documents each (a sample, not a gate)" n))
    (doseq [d sets] (run-dataset d n))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
