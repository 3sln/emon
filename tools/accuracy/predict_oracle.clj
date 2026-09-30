(ns accuracy.predict-oracle
  "REFERENCE PREDICTOR \"oracle\": gold -> Model. Scores perfect, by construction.

A predictor is a command run as  CMD ID VARIANT INPUT GOLD  (variant: pdf, scan,
pdf-tagged; INPUT the committed file; GOLD the gold file) that prints an emon-1
Model as JSON on stdout. Exit 3 means \"not available yet\" (not a failure).

This one ignores INPUT and copies the gold's model. It exists to prove the
scorer and the ratchet: if it is not perfect, the SCORER is wrong (D077).

    bb -cp tools:tools/lib tools/accuracy/predict_oracle.clj ID VARIANT INPUT GOLD"
  (:require [pyjson]))

(defn predict
  "In-process form: (predict id variant input gold-path) -> the Model map."
  [_id _variant _input gold-path]
  (get (pyjson/parse-file gold-path) "model"))

(when (= *file* (System/getProperty "babashka.file"))
  (let [[_ _ _ gold] *command-line-args*]
    (print (pyjson/dumps (get (pyjson/oparse (slurp gold :encoding "UTF-8")) "model") {:ascii true}))
    (flush)))
