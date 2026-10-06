#!/usr/bin/env bb
;; Record the OCR of the FIRST PAGE of fusion-set documents, LIVE, for the health laws (DEVLOG P7.2, D284). NOT a gate: it needs
;; poppler's `pdftoppm`, Tesseract (brew: tesseract, leptonica; EMON_TESS_LIB is the directory of the libraries) and rustc.
;;
;;     bb tools/fusion/record_health.clj [--dir pdf] (--all | ID...)        the page is test/corpus/synth/DIR/ID.pdf (default DIR pdf)
;;
;; The page is rendered by pdftoppm at 150 dpi (gray) and read by the same Tesseract port the Engine uses (`host/rust/src/tess.rs`,
;; through P2.6's driver `test/ocr/rust/main.rs`: a `render` answered from the PGM, then an `ocr`). What is committed, in
;; `test/fusion/health/DIR/ID.ocr`, is ONE LINE of integers: the page's width and height in pixels, then the OCR answer's cells
;; exactly as the engine made them (`emon.plan`'s answer record). A law (`kin/healthfusiontest.kin`) reads it back, builds the OCR
;; evidence with `sc-answer` as the page unit does, and aligns it to the text layer of the PDF: no engine runs and no pixel is read.
;; The producer (Tesseract's version, its models) is written to `test/fusion/health/PRODUCER.edn`.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(def variant (or (second (drop-while #(not= % "--dir") args)) "pdf"))
(def ids (if (some #{"--all"} args)
           (vec (sort (map #(str/replace (fs/file-name %) #"\.pdf$" "") (fs/glob (str "test/corpus/synth/" variant) "*.pdf"))))
           (vec (remove #(or (str/starts-with? % "--") (= % variant)) args))))
(when (empty? ids)
  (println "usage: bb tools/fusion/record_health.clj [--dir pdf|scan-color] ID...")
  (System/exit 2))

(def work (str (fs/create-temp-dir {:prefix "emon-health."})))
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
       "-L" (or (System/getenv "EMON_TESS_LIB") "/opt/homebrew/lib") "-o" "prog" "main.rs")

(def out-dir (str "test/fusion/health/" variant))
(fs/create-dirs out-dir)

(defn ocr-ans [edn]
  ;; the :ans vector of the :ocr entry
  (let [m (re-find #":kind :ocr :req \[[^\]]*\] :ans \[([^\]]*)\]" edn)]
    (when-not m (throw (ex-info "no ocr entry in the transcript" {})))
    (mapv parse-long (str/split (str/trim (second m)) #"\s+"))))

(doseq [id ids]
  (let [pdf (str "test/corpus/synth/" variant "/" id ".pdf")
        t0 (System/currentTimeMillis)
        pg (str work "/pg-" id)
        tdir (str work "/t-" id)]
    (shell {:out :string :err :string} "pdftoppm" "-r" "150" "-gray" "-f" "1" "-l" "1" pdf pg)
    (let [pgm (first (fs/glob work (str "pg-" id "*.pgm")))
          hdr (str/split-lines (slurp (str pgm) :encoding "ISO-8859-1"))
          [w h] (map parse-long (str/split (second hdr) #" "))
          r (sh {:err :inherit} (str work "/prog") "record" tdir pdf (str pgm) "150")]
      (when-not (zero? (:exit r)) (println "FAIL" id) (System/exit (:exit r)))
      (let [ans (ocr-ans (slurp (str tdir "/transcript.edn")))]
        (spit (str out-dir "/" id ".ocr") (str w " " h " " (str/join " " ans) "\n"))
        (when-not (fs/exists? "test/fusion/health/PRODUCER.edn")
          (let [e (slurp (str tdir "/transcript.edn"))]
            (spit "test/fusion/health/PRODUCER.edn" (str (second (re-find #"(:producer \{[^}]*\})" e)) "\n"))))
        (println (format "recorded %s/%s.ocr: %dx%d px, %d cells, %d ms" out-dir id w h (count ans) (- (System/currentTimeMillis) t0)))))))
