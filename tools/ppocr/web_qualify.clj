#!/usr/bin/env bb
;; D891: scoped ORT Web/native comparison plus exact within-web repeats.
;; Never fetch assets, re-answer the reference, or consult historical hashes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str]
         '[clojure.edn :as edn]
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
(def policy (:policy-identity (edn/read-string (slurp (str qual "/PROVENANCE.edn")))))
(assert (and (string? policy) (not (str/blank? policy))) "native qualification has no policy identity")
(def expected-observed (qualification/observed-hash (str policy " runtime=" qualification/web-runtime)))
(when-not (= "true" (get args "--compare-only"))
 (doseq [pass ["repeat" "current"]]
  (space!)
  (fs/delete-if-exists (str out "/" candidate ".jsonl"))
  (let [script (if (= candidate "browser") "tools/ppocr/web_browser.mjs" "tools/ppocr/web_probe.mjs")
        result @(process ["node" "--experimental-wasm-exnref" script out]
                         {:shutdown nil :out (str out "/" candidate ".log") :err :out
                          :extra-env {"EMON_CS_SHARED_COMPILATION" "false"
                                      "EMON_PPOCR_WASM" (or (System/getenv "EMON_PPOCR_WASM")
                                                           ".cache/emon-wasm-ppocr/target/wasm32-unknown-unknown/release/emon.wasm")
                                      "TESSDATA_PREFIX" (str (fs/absolutize ".cache/tessdata"))}})]
    (assert (zero? (:exit result)) (str candidate " inference failed; see " out "/" candidate ".log")))
  (when (= pass "repeat")
    (fs/copy (str out "/" candidate ".jsonl") (str out "/" candidate "-repeat.jsonl")
             {:replace-existing true}))))
(def candidate-lines (str/split-lines (slurp (str out "/" candidate ".jsonl"))))
(def web (mapv #(qualification/oil-answer (vec (json/parse-string %))) candidate-lines))
(assert (= (count selected) (count web)) "incomplete candidate answers")
(def differences
  (mapv (fn [row a b reference-line candidate-line]
          (assoc (qualification/web-answer-difference a b expected-observed) :id (:id row)
                 :bytes-equal (= reference-line candidate-line)
                 :canonical (= candidate-line (json/generate-string b))))
        selected native web (take (count selected) reference-lines) candidate-lines))
(spit (str out "/" candidate "-differences.json") (json/generate-string differences {:pretty true}))
(def deterministic (= (slurp (str out "/" candidate ".jsonl"))
                      (slurp (str out "/" candidate "-repeat.jsonl"))))
(def summary {:comparison "D891 scoped ORT Web/native; only raw word-confidence and det-score +/-1"
              :runtime qualification/web-runtime :deterministic deterministic
              :observed (str policy " runtime=" qualification/web-runtime)
              :observed-hash expected-observed :complete false
              :capture-sha256 (qualification/file-sha256 (str out "/" candidate ".jsonl"))
              :reference-sha256 (qualification/file-sha256 (str qual "/reference.answers"))
              :answers (count selected)
              :qualification (= 225 (count selected))
              :equal (count (filter :bytes-equal differences))
              :different (count (remove :bytes-equal differences))
              :raw-score-different (count (filter #(pos? (:score-differences %)) differences))
              :raw-score-cells (reduce + (map :score-differences differences))
              :accepted (count (filter #(and (:accepted %) (:canonical %)) differences))})
(spit (str out "/" candidate "-summary.json") (json/generate-string summary {:pretty true}))
(println (json/generate-string summary))
(assert (and deterministic (= (:answers summary) (:accepted summary)))
        (str candidate " answer bytes differ outside D891 scope or web repeat is nondeterministic"))
(when (:qualification summary)
  (let [result @(process ["bb" "-cp" "tools" "tools/ppocr/web_receipts.clj"
                         "--qualification" qual "--captures" out "--candidate" candidate]
                        {:inherit true :shutdown nil})]
    (assert (zero? (:exit result)) "web fixed-receipt acceptance failed")
    (spit (str out "/" candidate "-summary.json")
          (json/generate-string (assoc summary :complete true :receipts-accepted true) {:pretty true}))))
