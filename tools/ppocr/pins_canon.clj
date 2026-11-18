;; The identity of the PP-OCR policy and the calibration's pin hash are over the BYTES of tools/ppocr/assets.edn (identity.clj, check_calibration.clj).
;; ORTPIN (D901) moved the ONNX Runtime pin out of that file, into tools/ppocr/runtimes.edn, when the GitHub archive it named went away.  Behaviour did not change
;; (bb ppocr-hosts proved it), so the identity must not either: it would re-key every recording.  The hashes are therefore taken over `canonical`: assets.edn with
;; the marked ORTPIN region put back as the entry text it replaced, byte for byte.  The runtime itself is pinned, by SHA-256 and size, in runtimes.edn.
(ns ppocr.pins-canon
  (:require [clojure.string :as str]))

(def legacy-ort-entry
  (str "  {:file \"onnxruntime-osx-arm64-1.22.0.tgz\"\n"
       "   :url \"https://github.com/microsoft/onnxruntime/releases/download/v1.22.0/onnxruntime-osx-arm64-1.22.0.tgz\"\n"
       "   :sha256 \"cab6dcbd77e7ec775390e7b73a8939d45fec3379b017c7cb74f5b204c1a1cc07\"\n"
       "   :bytes 25943843 :licence \"MIT\" :target \"aarch64-apple-darwin\"}\n"))

(defn canonical
  "TEXT of assets.edn with its ORTPIN region replaced by the entry it replaced. A text with no region is returned as it is."
  [text]
  (str/replace text #"(?s)  ;; ORTPIN-BEGIN\n.*?  ;; ORTPIN-END\n" (str/re-quote-replacement legacy-ort-entry)))
