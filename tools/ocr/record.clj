#!/usr/bin/env bb
;; Record one scanned page's OCR into a transcript, LIVE, on the Rust host
;; (DEVLOG P2.6, D095). NOT a gate: it needs Tesseract (brew: tesseract, leptonica),
;; poppler's `pdfimages` and ImageMagick, and is what produced test/ocr/<id>/.
;;
;;     bb tools/ocr/record.clj [ID]        default h-keyword; the scan is test/corpus/synth/scan/ID.pdf
;;
;; The scan PDFs are one embedded JPEG per page (150 dpi). There is no PDF renderer
;; or JPEG decoder in emon yet, so the page image is extracted and gray-converted
;; here and answers the unit's `render` request: a stand-in Renderer. The PDF's
;; bytes are still the transcript's input 0 (only its length is recorded).
;; Writes test/ocr/ID/ (transcript.edn, bitmap-0000.png) and test/ocr/ID.dump (the
;; driver's stdout, which `bb ocr` replays on Rust and Go and compares).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def id (or (first *command-line-args*) "h-keyword"))
(def pdf (str "test/corpus/synth/scan/" id ".pdf"))
(def out (str "test/ocr/" id))
(def work (str (fs/create-temp-dir {:prefix "emon-ocrrec."})))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (fs/delete-tree work))))

(shell {:out :string :err :string} "kin/scripts/emit")
(fs/create-dirs (str work "/emon"))
(doseq [f (fs/glob "gen/rust/emon" "*.rs")] (fs/copy f (str work "/emon/" (fs/file-name f))))
(doseq [f ["port.rs" "testport.rs" "plan.rs" "tess.rs"]] (fs/copy (str "host/rust/src/" f) (str work "/" f)))
(spit (str work "/emon.rs")
      (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                   (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
(fs/copy "test/ocr/rust/main.rs" (str work "/main.rs"))
(shell {:dir work} "rustc" "--edition" "2021" "-O" "-A" "warnings" "--cfg" "live"
       "-L" "/opt/homebrew/lib" "-o" "prog" "main.rs")

(shell "pdfimages" "-all" pdf (str work "/pg"))
(shell "magick" (str work "/pg-000.jpg") "-colorspace" "Gray" (str work "/pg.pgm"))
(fs/delete-tree out)
(let [r (sh {:err :inherit} (str work "/prog") "record" out pdf (str work "/pg.pgm") "150")]
  (when-not (zero? (:exit r)) (System/exit (:exit r)))
  (spit (str out ".dump") (:out r)))
(println "recorded" out "and" (str out ".dump"))
