(ns receipts.receipts
  "Build the live Rust driver when needed, then run the non-gate measurement."
  (:require [babashka.process :as p]))
(def args (vec *command-line-args*))
(def custom-bin (or (System/getenv "EMON_RECEIPTS_BIN")
                    (second (drop-while #(not= % "--bin") args))))
(when (and (not custom-bin) (not-any? #{"--report" "--rescore" "--help" "-h"} args))
  (p/shell {:extra-env {"EMON_RS_LIVE" "1" "CARGO_TARGET_DIR" "/private/tmp/emon-rc/live"}
            :out :err}
           "kin/scripts/emon-rs-build"))
(apply p/shell "python3" "tools/receipts/receipts.py" args)
