#!/usr/bin/env bb
;; Serial OP1c qualification. Source-bearing artifacts stay on the external drive.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str])
(import '[java.security MessageDigest])
(assert (or (empty? *command-line-args*) (= ["--resume"] *command-line-args*)))
(def resume? (= ["--resume"] *command-line-args*))
(def root (str (fs/absolutize ".")))
(def out "/Volumes/SSK SSD/emon-scratch/op1c")
(def live (str out "/live"))
(def bin (str root "/.cache/emon-rs-fusion-ppocr/target/release/emon-rs"))
(def pp-bin (str root "/.cache/emon-rs-fusion-ppocr/target/release/ppocr-repeat"))
(def pdfium (str root "/.cache/pdfium/current/lib"))
(def env {"EMON_CS_SHARED_COMPILATION" "false" "OMP_THREAD_LIMIT" "1"})
(def rows (atom (if resume? (filterv #(zero? (:exit %)) (json/parse-string (slurp (str out "/qualification-phases.json")) true)) [])))
(defn sha [file]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes file)))))
(defn run! [label argv extra-env]
  (if (some #(= label (:phase %)) @rows)
    (println "REUSE completed phase" label)
    (do
  (assert (> (.getUsableSpace (java.io.File. "/System/Volumes/Data")) (* 5 1024 1024 1024)) "internal disk below 5 GiB")
  (let [start (System/nanoTime) log (str out "/" label ".log")]
    (println "BEGIN" label)
    (spit (str out "/active-phase.json") (json/generate-string {:phase label :argv argv :log log}))
    (let [result @(process argv {:extra-env (merge env extra-env) :out log :err :out :shutdown nil})
          row {:phase label :exit (:exit result) :seconds (/ (- (System/nanoTime) start) 1e9) :log log}]
      (swap! rows conj row)
      (spit (str out "/qualification-phases.json") (json/generate-string @rows {:pretty true}))
      (println "END" label (:exit result))
      (assert (zero? (:exit result)) (str label " failed; inspect " log)))))))
(fs/create-dirs out)
(def policy (apply str (map #(char (parse-long %))
                           (str/split (second (re-find #":data \[([0-9 ]+)\]" (slurp "kin/ocrengine.kin"))) #" "))))
(def sources ["kin/ocrengine.kin" "kin/ofuse.kin" "kin/pageunit.kin" "kin/ocrenginetest.kin"
              "kin/ofusetest.kin" "host/rust/src/ppocr.rs" "host/rust/src/ppocr_calibration.rs"])
(def frozen {:policy policy :thresholds {:insertion-confidence 700 :replacement-margin 300}
                             :calibration "D845 unchanged; finer fit-only calibration rejected before receipt measurements"
                             :source-sha256 (into {} (map (fn [p] [(keyword p) (sha p)]) sources))})
(if resume?
  (assert (= frozen (json/parse-string (slurp (str out "/frozen-candidate.json")) true)) "candidate changed across resume")
  (spit (str out "/frozen-candidate.json") (json/generate-string frozen {:pretty true})))
(run! "offline-rescore-final" ["python3" "tools/oracle/ppocr/gain_diagnosis.py" "--out" out] {})
(run! "build-candidate" ["kin/scripts/emon-rs-build"] {"EMON_RS_PPOCR" "1" "EMON_RS_PDFIUM" pdfium})
(spit (str out "/qualification-build.json")
      (json/generate-string {:policy policy :sha256 {bin (sha bin) pp-bin (sha pp-bin)}} {:pretty true}))
(def native-flags (str "-A warnings -L native=/opt/homebrew/lib --cfg ppocr -L native=" root "/.cache/ppocr -L native=" root
                       "/.cache/ppocr/onnxruntime-osx-arm64-1.22.0/lib -C link-arg=-Wl,-rpath," root
                       "/.cache/ppocr/onnxruntime-osx-arm64-1.22.0/lib --cfg pdfium -L native=" pdfium " -C link-arg=-Wl,-rpath," pdfium))
(run! "native-engine-contracts" ["cargo" "test" "--release" "--offline" "--manifest-path" ".cache/emon-rs-fusion-ppocr/Cargo.toml"
                                 "--features" "tesseract,__test_hooks" "--test" "engine" "optional_ocr" "--" "--test-threads=1"]
      {"RUSTFLAGS" native-flags})
(run! "targets" ["python3" "tools/oracle/ppocr/evaluate.py" "--out" live "--bin" bin "--only" "controls"
                  "--indices" "10,39,44,47,53" "--reference-controls" "/Volumes/SSK SSD/emon-scratch/op1b/live"] {})
(run! "receipts-fixed" ["python3" "tools/oracle/ppocr/fixed_receipts.py" "--out" live "--bin" bin] {})
(run! "receipts-native-audit" ["python3" "tools/oracle/ppocr/receipt_audit.py" "--out" live] {})
(run! "receipts-fresh" ["python3" "tools/oracle/ppocr/fresh_receipts.py" "--out" (str out "/fresh") "--bin" bin] {})
(run! "controls-full" ["python3" "tools/oracle/ppocr/evaluate.py" "--out" live "--bin" bin "--only" "controls"
                        "--resume" "--reference-controls" "/Volumes/SSK SSD/emon-scratch/op1b/live"] {})
(run! "controls-audit" ["python3" "tools/oracle/ppocr/control_audit.py" "--live" live "--prior" "/Volumes/SSK SSD/emon-scratch/op1b"] {})
(run! "scope-audit" ["bb" "tools/oracle/ppocr/scope_audit.clj" live] {})
(def fresh-recordings (mapv str (sort (filter #(and (fs/directory? %) (str/ends-with? (str %) ".replay"))
                                              (fs/list-dir (str out "/fresh/ppocr"))))))
(assert (= 24 (count fresh-recordings)) "fresh determinism must cover both 12-document samples")
(def fresh-manifest @(process (into ["bb" "tools/ppocr/bitmaps.clj"] fresh-recordings) {:extra-env env :out :string :err :string :shutdown nil}))
(assert (zero? (:exit fresh-manifest)) (:err fresh-manifest))
(spit (str out "/bitmaps.json")
      (json/generate-string (into (json/parse-string (slurp "/Volumes/SSK SSD/emon-scratch/op1b/bitmaps-v5.json"))
                                  (json/parse-string (:out fresh-manifest))) {:pretty true}))
(run! "determinism" ["python3" "tools/oracle/ppocr/repeat.py" "--manifest" (str out "/bitmaps.json")
                      "--bin" pp-bin "--out" (str out "/repeats")] {})
;; Keep fresh coverage independently checkable across resumes of older manifests.
(spit (str out "/fresh-bitmaps.json") (:out fresh-manifest))
(run! "determinism-fresh" ["python3" "tools/oracle/ppocr/repeat.py" "--manifest" (str out "/fresh-bitmaps.json")
                            "--bin" pp-bin "--out" (str out "/fresh-repeats")] {})
(def detector ".cache/ppocr/ch_PP-OCRv5_det_mobile.onnx")
(def absent (str detector ".op1c-absent"))
(def detector-sha (sha detector))
(assert (not (fs/exists? absent)))
(try
  (fs/move detector absent)
  (run! "replay-detector-absent" ["python3" "tools/oracle/ppocr/replay.py" "--out" live "--bin" bin] {})
  (run! "replay-default-receipts" ["python3" "tools/oracle/ppocr/replay.py" "--out" live "--bin" bin
                                  "--fast-dir" (str live "/receipts-tesseract")] {})
  (run! "replay-fresh-receipts" ["python3" "tools/oracle/ppocr/fresh_receipts.py" "--out" (str out "/fresh")
                                "--bin" bin "--replay-only"] {})
  (finally (fs/move absent detector) (assert (= detector-sha (sha detector)))))
(def gates ["gates-drift" "ci-core" "rederive---check" "rederive" "accuracy" "samples" "identity" "repeat" "plan" "ocr" "render"
            "pdfium-wasm" "align" "fusion" "gfuse" "artifacts" "seals" "sealpx" "sigs" "retarget" "asset" "embedders" "unit" "package---check"])
;; The inference thread cap is not an ImageMagick gate setting: its warning
;; precedes compare's zero-error metric and is correctly rejected by asset-check.
(run! "final-gates" ["env" "-u" "OMP_THREAD_LIMIT" "bb" "tools/ppocr/final_gates.clj" "--out" (str out "/final-gates") "--only" (str/join "," gates)] {})
(doseq [[p expected] (into {} (map (fn [p] [p (sha p)]) sources))]
  (assert (= expected (get-in (json/parse-string (slurp (str out "/frozen-candidate.json")) true) [:source-sha256 (keyword p)]))
          (str "frozen policy source changed: " p)))
(spit (str out "/active-phase.json") (json/generate-string {:phase "complete"}))
