#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[cheshire.core :as json]
         '[clojure.string :as str] '[clojure.edn :as edn])
(def out (or (first *command-line-args*) "/Volumes/SSK SSD/emon-scratch/op2b/interop"))
(fs/create-dirs out)
(def input "test/raster/receipt-photo.png")
(def native ".cache/emon-rs-ppocr/target/release/emon-rs")
(doseq [name ["eng" "osd"]]
  (assert (fs/regular-file? (str ".cache/tessdata/" name ".traineddata"))
          "interop requires pinned traineddata: bb tessdata-fetch --langs eng,osd"))
(def env {"EMON_CS_SHARED_COMPILATION" "false" "TESSDATA_PREFIX" (str (fs/absolutize ".cache/tessdata"))})
(def no-models {"EMON_CS_SHARED_COMPILATION" "false" "TESSDATA_PREFIX" "/nonexistent/op2b-tessdata"
                "EMON_PPOCR_MODELS" "/nonexistent/op2b-models" "EMON_PPOCR_LIB" "/nonexistent/op2b-runtime"})
(defn run! [argv label environment]
  (doseq [p ["/System/Volumes/Data" "/Volumes/SSK SSD"]]
    (assert (> (.getUsableSpace (java.io.File. p)) (* 10 1024 1024 1024)) (str "disk below 10 GiB: " p)))
  (let [r @(process argv {:extra-env environment :out :string :err (str out "/" label ".err") :shutdown nil})]
    (assert (zero? (:exit r)) (str "failed: " label))
    (:out r)))
(def results (atom []))
(defn without-models [f]
  (let [paths [".cache/ppocr/ch_PP-OCRv5_det_mobile.onnx" ".cache/ppocr/en_PP-OCRv5_rec_mobile.onnx"
               ".cache/ppocr/ch_ppocr_mobile_v2.0_cls_mobile.onnx" ".cache/tessdata/eng.traineddata"
               ".cache/tessdata/osd.traineddata"]
        hashes (mapv #(vec (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes %))) paths)
        moved (atom [])]
    (try
      (doseq [p paths]
        (assert (not (fs/exists? (str p ".op2b-absent"))))
        (fs/move p (str p ".op2b-absent")) (swap! moved conj p))
      (f)
      (finally
        (doseq [p (reverse @moved)] (fs/move (str p ".op2b-absent") p))
        (assert (= hashes (mapv #(vec (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes %))) paths))
                "model bytes changed during absence test")))))
(doseq [host ["js" "native"]]
  (let [recording (str out "/" host ".replay") bundle (str out "/" host ".bundle")]
    (assert (not (fs/exists? recording)) "use a new output directory")
    (if (= host "js")
      (run! ["node" "--experimental-wasm-exnref" "tools/ppocr/web_replay.mjs" "record" input recording bundle] "js-record" env)
      (spit bundle (run! [native "--ocr-engine=ppocr" "--out=bundle" (str "--record=" recording) input] "native-record" env)))
    (let [transcript (edn/read-string (slurp (str recording "/transcript.edn")))]
      (assert (some #(some #{-3100} (:req %)) (:entries transcript)) "fixture did not request PP"))
    (without-models (fn []
    (let [replay (str out "/" host "-js-replay.bundle")
          native-replay (run! [native "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" recording) input]
                              (str host "-native-replay") no-models)]
      (run! ["node" "--experimental-wasm-exnref" "tools/ppocr/web_replay.mjs" "replay" input recording replay]
            (str host "-js-replay") no-models)
      (assert (= (slurp bundle) (slurp replay) native-replay) (str "cross-host replay differs: " host))
      (swap! results conj {:recorded-on host :js-replay-equal true :native-replay-equal true :no-inference true :models-actually-absent true}))))
    (let [r @(process [native "--ocr-engine=tesseract" "--out=json" (str "--replay=" recording) input]
                     {:extra-env no-models :out :string :err :string :shutdown nil})]
      (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) "transcript-miss"))))))
(doseq [host ["js" "native"]]
  (let [r @(process ["node" "--experimental-wasm-exnref" "tools/ppocr/web_replay.mjs" "replay-default"
                     input (str out "/" host ".replay") (str out "/unexpected-default.bundle")]
                    {:extra-env no-models :out :string :err :string :shutdown nil})]
    (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) "transcript-miss")))))
(swap! results #(mapv (fn [row] (assoc row :default-engine-mismatch-refused-on-both-hosts true)) %))
(spit (str out "/summary.json") (json/generate-string @results {:pretty true}))
(println "JS/native recordings replay as equal complete bundles without inference; default-engine mismatch refused.")
