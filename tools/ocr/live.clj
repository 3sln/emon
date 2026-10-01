#!/usr/bin/env bb
;; LIVE OCR scoring (DEVLOG P6.5, D211, D216). NOT a gate: it needs Tesseract and takes minutes.
;;
;;     bb ocr-live [--update] [--no-gold] [--gold-n N]
;;
;;   1. the 55 scan variants of the synthetic corpus through the Rust Engine WITH the Tesseract port (`predict-emon-live.sh`),
;;      scored by `bb accuracy --predictor emon-live --variants scan`: the ratchet of the scoreboard's `emon-live` entry (a regression
;;      beyond the tolerance fails; `--update` records improvements). The same documents' text-layer variants (`pdf/`) are what the
;;      gates score and the OCR text is measured against: CER and WER are read as the scan against the same gold.
;;   2. a sample of the public datasets' gold (FUNSD, SROIE, CORD: `corpus/_gold`, never committed) through the same Engine,
;;      `tools/ocr/gold_sample.clj`: N documents each (default 12), reported, never ratcheted.
;;
;; The gates do not run this: they REPLAY the committed transcripts (`bb ocr`), which is why a gate never depends on whose Tesseract
;; is installed (D008, D095).
(require '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(def update? (some #{"--update"} args))
(def gold? (not (some #{"--no-gold"} args)))
(def gold-n (or (some->> (drop-while #(not= % "--gold-n") args) second) "12"))

(let [r (apply shell {:continue true} "bb" "tools/accuracy/accuracy.clj" "--predictor" "emon-live" "--variants" "scan"
               (when update? ["--update"]))]
  (when-not (zero? (:exit r))
    (println "  FAIL bb ocr-live: the emon-live ratchet failed (exit" (:exit r) ")")
    (System/exit (:exit r))))
(when gold?
  (let [r (shell {:continue true} "bb" "-cp" "tools:tools/lib" "tools/ocr/gold_sample.clj" "--n" gold-n)]
    (when-not (contains? #{0 3} (:exit r))
      (println "  FAIL bb ocr-live: the gold sample failed (exit" (:exit r) ")")
      (System/exit (:exit r)))))
