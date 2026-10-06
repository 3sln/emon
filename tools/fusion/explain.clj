#!/usr/bin/env bb
;; What fusion does on documents, LIVE (pdfium and Tesseract): the evidence dump of each (version 2) into .cache/fusion-explain/ID.ev and its
;; verdicts printed by tools/fusion/explain.py. A debugging aid (P7.3). NOT a gate.   bb tools/fusion/explain.clj [--all] ID...
(require '[babashka.fs :as fs] '[babashka.process :refer [sh]] '[clojure.string :as str])
(def args (vec *command-line-args*))
(fs/create-dirs ".cache/fusion-explain")
(doseq [id (remove #(str/starts-with? % "--") args)]
  (let [out (str ".cache/fusion-explain/" id ".ev")
        r (sh {:extra-env {"EMON_OUT" "evidence"}} "sh" "tools/accuracy/predict-emon-fusion-live.sh" id "pdf" (str "test/corpus/synth/pdf/" id ".pdf") "x")]
    (spit out (:out r))
    (println "==" id)
    (let [e (sh "python3" "tools/fusion/explain.py" out (if (some #{"--all"} args) "--all" ""))]
      (print (:out e))
      (print (:err e)))))
