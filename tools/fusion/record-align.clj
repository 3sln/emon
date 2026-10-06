#!/usr/bin/env bb
;; Record the alignment fixtures LIVE: the render of each page of a fusion document by pdfium and the OCR of that render by
;; Tesseract, on the Rust host, with NO Engine (DEVLOG P7.1, D278-D280). NOT a gate: it needs the pinned pdfium (`bb pdfium-fetch`)
;; and Tesseract (brew: tesseract, leptonica).
;;
;;     bb tools/fusion/record-align.clj [ID...]       default the six documents of the brief and three more; each is test/corpus/synth/pdf/ID.pdf
;;
;; Each document goes through `test/fusion/align/rust/main.rs` built `--cfg pdfium --cfg live`: ONE `render` of every page at 300
;; dpi with its text (the born-digital render a fusion run asks for under `ocr: always`, D199), then ONE `ocr` of every render. The
;; transcript the run wrote is committed AS A REPLAY INPUT, slim like test/ocr/scan/ (the answers and the `:producer`, no bitmap: a
;; replay reads no pixel), as test/fusion/align/ID/transcript.edn, and the dump of its REPLAY is test/fusion/align/ID.dump. Before
;; they are kept the transcript is replayed and the replay's dump must be the recording's, line for line.
(require '[babashka.fs :as fs]
         '[babashka.classpath :as cp]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])
(cp/add-classpath "tools")
(require '[fusion.align-lib :as l])

(def args (vec *command-line-args*))
(def ids (if (seq args) args l/all-ids))

(shell {:out :string :err :string} "kin/scripts/emit")
(shell "bb" "tools/pdfium/fetch.clj")
(def pdfium-lib (str (fs/real-path ".cache/pdfium/current/lib")))
(def work (str (fs/absolutize (fs/create-temp-dir {:dir (do (fs/create-dirs ".cache") ".cache") :prefix "align-rec."}))))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (fs/delete-tree work))))

(def live (let [[prog err] (l/build-rust work {:pdfium-lib pdfium-lib})]
            (when-not prog (println "FAIL live build:\n" err) (System/exit 1))
            prog))
(def replay (let [[prog err] (l/build-rust work nil)]
              (when-not prog (println "FAIL replay build:\n" err) (System/exit 1))
              prog))

(defn strip-bitmaps [edn] (str/replace edn #" :bitmap \"[^\"]*\"" ""))

(doseq [id ids]
  (let [pdf (l/abs (str "test/corpus/synth/pdf/" id ".pdf"))
        out (l/abs (str "test/fusion/align/" id))
        t0 (System/currentTimeMillis)]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (let [r (sh {:err :inherit} live "record" out pdf)]
      (when-not (zero? (:exit r)) (println "FAIL" id "exited" (:exit r)) (System/exit (:exit r)))
      (spit (str out "/transcript.edn") (strip-bitmaps (slurp (str out "/transcript.edn"))))
      (doseq [f (fs/glob out "bitmap-*.png")] (fs/delete f))
      (let [rp (sh {:err :inherit} replay "replay" out pdf)
            groups (fn [o] (filterv #(str/starts-with? % "g") (str/split-lines o)))]
        (when-not (and (zero? (:exit rp)) (= (groups (:out r)) (groups (:out rp))))
          (println "FAIL" id "the replay is not the recording")
          (System/exit 1))
        (spit (str "test/fusion/align/" id ".dump") (:out rp))))
    (println (format "recorded %s: %d bytes of transcript, %d ms" out (fs/size (str out "/transcript.edn")) (- (System/currentTimeMillis) t0)))))
