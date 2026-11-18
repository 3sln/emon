(ns receipts.receipts
  "Build the live Rust driver when needed, then run the non-gate measurement."
  (:require [babashka.process :as p]))
(def args (vec *command-line-args*))
(def ppocr? (or (some #{"--ocr-engine=ppocr" "--ocr-engine=ppocr-alone"} args)
                 (contains? #{"ppocr" "ppocr-alone"} (second (drop-while #(not= % "--ocr-engine") args)))))
(def vision? (or (some #{"--ocr-engine=apple-vision"} args)
                 (= "apple-vision" (second (drop-while #(not= % "--ocr-engine") args)))))
(def custom-bin (or (System/getenv "EMON_RECEIPTS_BIN")
                    (second (drop-while #(not= % "--bin") args))))
(when (and (not custom-bin) (not-any? #{"--report" "--rescore" "--help" "-h"} args))
  (p/shell {:extra-env (cond vision? {"EMON_RS_VISION" "1"} :else (cond-> {"EMON_RS_LIVE" "1"} ppocr? (assoc "EMON_RS_PPOCR" "1")))
            :out :err}
           "kin/scripts/emon-rs-build"))
(apply p/shell "python3" "tools/receipts/receipts.py" args)
