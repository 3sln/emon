#!/usr/bin/env bb
;; Policy identity is a hash of exact file names, NUL separators and bytes.
(require '[babashka.fs :as fs] '[clojure.string :as str])
(import '[java.security MessageDigest])
(def names ["host/rust/src/tess.rs" "tools/tessdata/models.edn" "host/rust/src/ppocr.rs" "host/rust/src/ppocr_runtime.rs" "tools/ppocr/assets.edn"
            "host/rust/src/ppocr_image.rs" "host/rust/src/ppocr_ctc.rs" "host/rust/src/ppocr_pipeline.rs"
            "host/rust/src/ppocr_ort.c" "host/rust/src/ppocr_calibration.rs" "kin/ocrport.kin" "kin/ocrengine.kin"
            "kin/scan.kin" "kin/pageunit.kin" "kin/ofuse.kin" "kin/oapp.kin" "kin/oarb.kin"])
(def h (MessageDigest/getInstance "SHA-256"))
(doseq [name names]
  (.update h (.getBytes (str name \u0000) "UTF-8"))
  (.update h (if (= name "kin/ocrengine.kin")
               (.getBytes (str/replace-first (slurp name) #"(:data \[)[0-9\s]+(\])" "$1MODEL_IDENTITY$2") "UTF-8")
               (fs/read-all-bytes name))))
(def identity (str "ppocr5-en-" (apply str (map #(format "%02x" (bit-and % 255)) (.digest h)))))
(def file "kin/ocrengine.kin")
(def content (slurp file))
(def want (str/join " " (map int identity)))
(if (= ["--write"] *command-line-args*)
  (spit file (str/replace-first content #":data \[[0-9 ]+\]" (str ":data [" want "]")))
  (do (assert (empty? *command-line-args*) "usage: identity.clj [--write]")
      (assert (= want (second (re-find #":data \[([0-9 ]+)\]" content)))
              "PP-OCR policy changed; regenerate identity and invalidate recordings")))
(println identity)
