#!/usr/bin/env bb
;; Record the FUSION REPLAY set LIVE: the whole Engine converting each document of the fusion set with the hidden setting
;; `ocr-born-digital`, pdfium rendering every page and Tesseract reading it, on the Rust host (DEVLOG P7.3, D259-D264). NOT a gate: it needs the
;; pinned pdfium (`bb pdfium-fetch`) and Tesseract (brew: tesseract, leptonica).
;;
;;     bb tools/fusion/record-replay.clj [ID...]     default: test/corpus/synth/FUSION (the 22 documents of P6.6's fusion set)
;;
;; Each document goes through `emon-rs --pdfium --tesseract --ocr-born-digital --record=DIR`, built with `EMON_RS_PDFIUM=DIR
;; kin/scripts/emon-rs-build` (a crate of its own, .cache/emon-rs-fusion). What the loop asked and what the engines answered is the
;; transcript; it is committed AS A REPLAY INPUT, slim like test/ocr/scan/ (the answers and the `:producer`, no bitmap: a replay reads no
;; pixel), as test/fusion/replay/ID/transcript.edn. Before it is kept the transcript is REPLAYED by the same build with no port, and the
;; replay's canonical JSON and evidence dump must be the recording's, byte for byte. The SHA-256 of each document's replay bundle (JSON,
;; HTML, evidence) is written to test/fusion/replay/DIGESTS.edn: what `bb fusion` holds both Engines to.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.pprint]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(def ids (if (seq args)
           args
           (vec (remove str/blank? (str/split-lines (slurp "test/corpus/synth/FUSION"))))))

(shell {:out :string :err :string} "kin/scripts/emit")
(shell "bb" "tools/pdfium/fetch.clj")
(def pdfium-lib (str (fs/real-path ".cache/pdfium/current/lib")))
(def bin
  (let [r (sh {:extra-env {"EMON_RS_PDFIUM" pdfium-lib} :err :inherit} "kin/scripts/emon-rs-build")]
    (when-not (zero? (:exit r)) (println "FAIL fusion driver did not build") (System/exit 1))
    (str (fs/absolutize (str/trim (:out r))))))

(defn strip-bitmaps [edn] (str/replace edn #" :bitmap \"[^\"]*\"" ""))

(defn sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String s "UTF-8"))))))

(def digests-file "test/fusion/replay/DIGESTS.edn")
(def digests (atom (if (fs/exists? digests-file) (read-string (slurp digests-file)) {})))

(doseq [id ids]
  (let [pdf (str (fs/absolutize (str "test/corpus/synth/pdf/" id ".pdf")))
        out (str (fs/absolutize (str "test/fusion/replay/" id)))
        t0 (System/currentTimeMillis)]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (let [r (sh {:err :inherit} bin "--pdfium" "--tesseract" "--ocr-born-digital" "--out=bundle" (str "--record=" out) pdf)]
      (when-not (zero? (:exit r)) (println "FAIL" id "exited" (:exit r)) (System/exit (:exit r)))
      (spit (str out "/transcript.edn") (strip-bitmaps (slurp (str out "/transcript.edn"))))
      (doseq [f (fs/glob out "bitmap-*.png")] (fs/delete f))
      (let [rp (sh {:err :inherit} bin "--ocr-born-digital" "--out=bundle" (str "--replay=" out) pdf)]
        (when-not (and (zero? (:exit rp)) (= (:out r) (:out rp)))
          (println "FAIL" id "the replay is not the recording")
          (System/exit 1))
        (swap! digests assoc id (sha256 (:out rp)))))
    (println (format "recorded %s: %d bytes of transcript, %d ms" id (fs/size (str out "/transcript.edn")) (- (System/currentTimeMillis) t0)))))

(spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/fusion/replay/ID, as `bb fusion-record` made it (P7.3, D259-D264).\n"
                        (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests)))))
