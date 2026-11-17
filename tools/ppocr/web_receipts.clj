#!/usr/bin/env bb
;; D891: replay actual web readings through the frozen native composition policy.
;; Tesseract answers and all other requests remain the recorded fixed inputs.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str]
         '[ppocr.qualification :as q])
(def args (apply hash-map *command-line-args*))
(def scratch (or (System/getenv "EMON_SCRATCH") ".cache/scratch"))
(def qual (get args "--qualification" (str scratch "/ppocr-qual")))
(def captures (get args "--captures" (str scratch "/op2b/qualification")))
(def candidate (get args "--candidate" "web"))
(def out (str captures "/" candidate "-receipts"))
(def driver (get args "--driver" ".cache/emon-rs-fusion-ppocr/target/release/emon-rs"))
(def provenance (edn/read-string (slurp (str qual "/PROVENANCE.edn"))))
(assert (= (:record-driver-sha256 provenance) (q/file-sha256 driver))
        "web receipts require the native composition driver recorded in qualification provenance")
(def summary (json/parse-string (slurp (str captures "/" candidate "-summary.json")) true))
(assert (and (:qualification summary) (:deterministic summary) (= 225 (:accepted summary)))
        "full scoped web qualification and exact repeats must pass before receipt scoring")
(assert (= (:capture-sha256 summary) (q/file-sha256 (str captures "/" candidate ".jsonl")))
        "web capture changed after scoped qualification")
(def rows (json/parse-string (slurp (str captures "/manifest.json")) true))
(def answers (mapv #(vec (json/parse-string %))
                   (str/split-lines (slurp (str captures "/" candidate ".jsonl")))))
(def native (mapv #(vec (json/parse-string %)) (q/reference-lines qual)))
(def selected (filterv #(and (= "fixed" (:population %))
                             (re-find #"^(cord|sroie)-" (:id %)))
                       (json/parse-string (slurp (str qual "/replays.json")) true)))
(assert (= 120 (count selected)) "expected fixed 60 CORD and 60 SROIE")
(assert (not (fs/exists? out)) "use fresh capture output for web receipts")
(fs/create-dirs out)
(def results (atom []))
(doseq [[i row] (map-indexed vector selected)]
  (assert (> (.getUsableSpace (java.io.File. scratch)) (* 10 1024 1024 1024)) "disk below 10 GiB")
  (let [indices (keep-indexed #(when (str/starts-with? (:id %2) (str (:id row) "/")) %1) rows)
        _ (assert (= 1 (count indices)) "fixed receipt must have one qualified PP bitmap")
        index (first indices) reference (native index) web (answers index)
        source (str qual "/" (:recording row)) target (str out "/" i ".replay")
        transcript (edn/read-string (slurp (str source "/transcript.edn")))
        changed (atom 0)
        entries (mapv (fn [entry]
                        (if (and (= :ocr (:kind entry)) (= 2 (get (:ans entry) 5)))
                          (let [original (vec (:ans entry)) id (original 2)]
                            (assert (= original (assoc reference 2 id))
                                    "receipt PP answer differs from qualified native bitmap reference")
                            (swap! changed inc)
                            (assoc entry :ans (assoc web 2 id))) entry)) (:entries transcript))
        bundle (str out "/" i ".bundle")]
    (assert (= 1 @changed) "receipt must replace exactly one PP reading")
    (fs/copy-tree source target)
    (spit (str target "/transcript.edn")
          (pr-str (-> transcript (assoc :entries entries)
                      (assoc-in [:producer :onnxruntime] q/web-runtime)
                      (assoc-in [:producer :qualification] "D891 captured web PP; frozen recorded Tesseract"))))
    (let [result @(process [driver "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" target) (:input row)]
                          {:out bundle :err (str captures "/" candidate "-receipt.err") :shutdown nil
                           :extra-env {"EMON_CS_SHARED_COMPILATION" "false"
                                       "EMON_PPOCR_MODELS" "/nonexistent/web-qualification"
                                       "EMON_PPOCR_LIB" "/nonexistent/web-qualification"}})]
      (assert (zero? (:exit result)) (str "web receipt replay failed: " (:id row))))
    (q/compress-bundle! bundle (q/file-sha256 bundle))
    (fs/delete-tree target)
    (swap! results conj (assoc row :expected (str i ".bundle")))
    (println (inc i) "/ 120 web receipt replays") (flush)))
(spit (str out "/replays.json") (json/generate-string @results))
(let [result @(process ["python3" "tools/oracle/ppocr/oil_accuracy.py" "--qualification" out]
                      {:inherit true :shutdown nil})]
  (assert (zero? (:exit result)) "web receipt P/R outside D864 tolerance"))
(println "PASS D891 web receipt P/R within +/-0.002 on fixed 60 CORD and 60 SROIE")
