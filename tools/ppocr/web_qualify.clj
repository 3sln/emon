#!/usr/bin/env bb
;; Compare web transports with the newly recorded Rust OIL reference.
;; Never fetch assets, re-answer the reference, or consult historical hashes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str]
         '[ppocr.qualification :as qualification])
(def args (apply hash-map *command-line-args*))
(assert (every? #{"--out" "--limit" "--compare-only" "--candidate" "--qualification"} (keys args)) "unknown option")
(def scratch (or (System/getenv "EMON_SCRATCH") ".cache/scratch"))
(def out (get args "--out" (str scratch "/op2b/qualification")))
(def qual (get args "--qualification" (str scratch "/ppocr-qual")))
(def candidate (get args "--candidate" "web"))
(assert (#{"web" "browser"} candidate) "candidate must be web or browser")
(defn space! []
  (assert (> (.getUsableSpace (java.io.File. ".")) (* 10 1024 1024 1024)) "disk below 10 GiB"))
(space!)
(fs/create-dirs out)
(def rows (vec (concat (json/parse-string (slurp (str qual "/bitmaps.json")) true)
                      (json/parse-string (slurp (str qual "/fresh-bitmaps.json")) true))))
(assert (= 225 (count rows)) "qualification needs exactly 225 bitmaps")
(def reference-lines (qualification/reference-lines qual))
(def selected (if-let [limit (get args "--limit")] (subvec rows 0 (parse-long limit)) rows))
(assert (seq selected) "qualification selection is empty")
(def manifest (mapv #(assoc % :file (qualification/bitmap-file qual %)) selected))
(assert (= (count manifest) (count (set (map :file manifest)))) "duplicate qualification bitmap")
(spit (str out "/manifest.json") (json/generate-string manifest))
(def native (mapv #(qualification/oil-answer (vec (json/parse-string %)))
                  (take (count selected) reference-lines)))
(when-not (= "true" (get args "--compare-only"))
  (space!)
  (fs/delete-if-exists (str out "/" candidate ".jsonl"))
  (let [script (if (= candidate "browser") "tools/ppocr/web_browser.mjs" "tools/ppocr/web_probe.mjs")
        result @(process ["node" "--experimental-wasm-exnref" script out]
                         {:shutdown nil :out (str out "/" candidate ".log") :err :out
                          :extra-env {"EMON_CS_SHARED_COMPILATION" "false"
                                      "TESSDATA_PREFIX" (str (fs/absolutize ".cache/tessdata"))}})]
    (assert (zero? (:exit result)) (str candidate " inference failed; see " out "/" candidate ".log"))))
(def candidate-lines (str/split-lines (slurp (str out "/" candidate ".jsonl"))))
(def web (mapv #(qualification/oil-answer (vec (json/parse-string %))) candidate-lines))
(assert (= (count selected) (count web)) "incomplete candidate answers")
(def differences
  (mapv (fn [row a b reference-line candidate-line]
          (assoc (qualification/answer-difference a b) :id (:id row)
                 :bytes-equal (= reference-line candidate-line)))
        selected native web (take (count selected) reference-lines) candidate-lines))
(spit (str out "/" candidate "-differences.json") (json/generate-string differences {:pretty true}))
(def summary {:answers (count selected)
              :qualification (= 225 (count selected))
              :equal (count (filter :bytes-equal differences))
              :different (count (remove :bytes-equal differences))})
(spit (str out "/" candidate "-summary.json") (json/generate-string summary {:pretty true}))
(println (json/generate-string summary))
(assert (zero? (:different summary)) (str candidate " answer bytes differ from Rust OIL reference"))
