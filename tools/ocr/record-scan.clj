#!/usr/bin/env bb
;; Record the OCR transcript of scanned documents, LIVE, on the Rust host (DEVLOG P6.5, D211, D214). NOT a gate: it needs Tesseract
;; (brew: tesseract, leptonica; EMON_TESS_LIB is the directory of the libraries, default /opt/homebrew/lib), and is what produced
;; test/ocr/scan/ID/.
;;
;;     bb tools/ocr/record-scan.clj [--engine=apple-vision] [--keep-bitmaps] [--px] [VARIANT/]ID...   each ID is test/corpus/synth/VARIANT/ID.pdf (VARIANT scan, scan-color or
;;                                                                       scan-ovl; scan when left out); the directory is ID for scan, VARIANT--ID else (D459)
;;
;; --engine=apple-vision (K8, D901; macOS): the same with Apple's Vision (`emon-rs --ocr-engine=apple-vision`), into test/ocr/vision/ID. The transcript says whose engine answered, in its
;; engine table and the observed identity (the OS build and the Vision revision); a replay needs `--ocr-engine=apple-vision` too, because a request names its engine.
;;
;; Each document goes through the Engine with the Tesseract `Ocr` port (`emon-rs --tesseract --record`), and the transcript the
;; run wrote is committed AS A REPLAY INPUT: `transcript.edn` (the decode and the OCR request and answer of every page, and the
;; `:producer` that says whose engine answered) and NOT the bitmaps. A replay returns the recorded answers and reads no pixel (the
;; decoded page and the engine's thresholded image are what the engine read and saw, not what a replay needs), so a page that costs
;; 450 KB of PNG costs nothing here; `--keep-bitmaps` keeps them, for a transcript someone will look at. Before it is kept the
;; transcript is replayed on Rust and its JSON must be the recording's, byte for byte (the recording is not kept: the Model is the
;; pipeline's, which moves, and the transcript is the engine's, which does not).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
;; --px: the transcripts of test/ocr/scan-px/ (D392): WHOLE, bitmaps kept. kin reads pixels since P6.3 and a table on a scan asks for per-cell
;; OCR from them, so a slim transcript of such a page is not what the engine did (the replay "never asked for" 3 recorded entries).
(def px? (some #{"--px"} args))
(def keep? (or px? (some #{"--keep-bitmaps"} args)))
(def vision? (some #{"--engine=apple-vision"} args))
(def ids (vec (remove #(str/starts-with? % "--") args)))
(when (empty? ids)
  (println "usage: bb tools/ocr/record-scan.clj [--keep-bitmaps] ID...")
  (System/exit 2))

(shell {:out :string :err :string} "kin/scripts/emit")
(let [r (sh {:err :inherit :out :string :extra-env (if vision? {"EMON_RS_VISION" "1"} {"EMON_RS_LIVE" "1"})} "kin/scripts/emon-rs-build")]
  (when-not (zero? (:exit r)) (System/exit (:exit r))))
(def bin (if vision? ".cache/emon-rs-vision/target/release/emon-rs" ".cache/emon-rs-live/target/release/emon-rs"))
(def engine-flag (if vision? "--ocr-engine=apple-vision" "--tesseract"))

(defn strip-bitmaps [edn] (str/replace edn #" :bitmap \"[^\"]*\"" ""))

(doseq [spec ids]
  (let [[variant id] (if (str/includes? spec "/") (str/split spec #"/" 2) ["scan" spec])
        pdf (str "test/corpus/synth/" variant "/" id ".pdf")
        out (str (cond vision? "test/ocr/vision/" px? "test/ocr/scan-px/" :else "test/ocr/scan/") (if (= variant "scan") id (str variant "--" id)))
        t0 (System/currentTimeMillis)]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (let [r (sh {:err :inherit} bin engine-flag (str "--record=" out) pdf)]
      (when-not (zero? (:exit r))
        (println "FAIL" id "exited" (:exit r))
        (System/exit (:exit r)))
      (when-not keep?
        (spit (str out "/transcript.edn") (strip-bitmaps (slurp (str out "/transcript.edn"))))
        (doseq [f (fs/glob out "bitmap-*.png")] (fs/delete f)))
      (let [rp (apply sh {:err :inherit} (concat [bin] (when vision? ["--ocr-engine=apple-vision"]) [(str "--replay=" out) pdf]))]
        (when-not (and (zero? (:exit rp)) (= (:out r) (:out rp)))
          (println "FAIL" id "the replay is not the recording")
          (System/exit 1))))
    (println (format "recorded %s: %d bytes of transcript, %d ms" out (fs/size (str out "/transcript.edn")) (- (System/currentTimeMillis) t0)))))
