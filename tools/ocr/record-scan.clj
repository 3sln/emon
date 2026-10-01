#!/usr/bin/env bb
;; Record the OCR transcript of scanned documents, LIVE, on the Rust host (DEVLOG P6.5, D184, D187). NOT a gate: it needs Tesseract
;; (brew: tesseract, leptonica; EMON_TESS_LIB is the directory of the libraries, default /opt/homebrew/lib), and is what produced
;; test/ocr/scan/ID/.
;;
;;     bb tools/ocr/record-scan.clj [--keep-bitmaps] ID...       each ID is test/corpus/synth/scan/ID.pdf
;;
;; Each document goes through the Engine with the Tesseract `Ocr` port (`emon-rs --tesseract --record`), and the transcript the
;; run wrote is committed AS A REPLAY INPUT: `transcript.edn` (the decode and the OCR request and answer of every page, and the
;; `:producer` that says whose engine answered) and NOT the bitmaps. A replay returns the recorded answers and reads no pixel (the
;; decoded page and the engine's thresholded image are what the engine read and saw, not what a replay needs), so a page that costs
;; 450 KB of PNG costs nothing here; `--keep-bitmaps` keeps them, for a transcript someone will look at. The conversion's JSON of
;; the recording is written beside it as `model.json` (the Model the recording gave), which `bb ocr` requires a replay on Rust and
;; on Go to reproduce byte for byte.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(def keep? (some #{"--keep-bitmaps"} args))
(def ids (vec (remove #(str/starts-with? % "--") args)))
(when (empty? ids)
  (println "usage: bb tools/ocr/record-scan.clj [--keep-bitmaps] ID...")
  (System/exit 2))

(shell {:out :string :err :string} "kin/scripts/emit")
(let [r (sh {:err :inherit :out :string :extra-env {"EMON_RS_LIVE" "1"}} "kin/scripts/emon-rs-build")]
  (when-not (zero? (:exit r)) (System/exit (:exit r))))
(def bin ".cache/emon-rs-live/target/release/emon-rs")

(defn strip-bitmaps [edn] (str/replace edn #" :bitmap \"[^\"]*\"" ""))

(doseq [id ids]
  (let [pdf (str "test/corpus/synth/scan/" id ".pdf")
        out (str "test/ocr/scan/" id)
        t0 (System/currentTimeMillis)]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (let [r (sh {:err :inherit} bin "--tesseract" (str "--record=" out) pdf)]
      (when-not (zero? (:exit r))
        (println "FAIL" id "exited" (:exit r))
        (System/exit (:exit r)))
      (spit (str out "/model.json") (:out r)))
    (when-not keep?
      (spit (str out "/transcript.edn") (strip-bitmaps (slurp (str out "/transcript.edn"))))
      (doseq [f (fs/glob out "bitmap-*.png")] (fs/delete f)))
    (println (format "recorded %s: %d bytes of transcript, %d ms" out (fs/size (str out "/transcript.edn")) (- (System/currentTimeMillis) t0)))))
