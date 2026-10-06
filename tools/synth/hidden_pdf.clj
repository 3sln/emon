(ns synth.hidden-pdf
  "The hidden-text fixtures of P7.6 (DEVLOG P7.6, D320-D323): scans with an older producer's OCR layer (render mode 3) under them, the layer
  AGREEING with what a new OCR reads (every word exact) and DISAGREEING with it (one letter in seven substituted), made from the same
  scan and word boxes as `hidden-older-ocr` (P6.6; tools/synth/wm_pdf.clj) and kept OUT of the corpus of the pooled scoreboards.

      bb -cp tools:tools/lib tools/synth/hidden_pdf.clj make     write test/fusion/hidden/NAME/doc.pdf, gold.txt and transcript.edn
      bb -cp tools:tools/lib tools/synth/hidden_pdf.clj check    every committed file is what `make` writes

  THE REPLAY TRANSCRIPTS ARE DERIVED, NOT RECORDED: the page of each file is the same scan as `hidden-older-ocr`'s (the text layer is render mode 3,
  so the render with its text is the scan alone) and what Tesseract reads of a render is a function of its pixels, so each `transcript.edn` is
  test/fusion/replay/hidden-older-ocr's with the one number that changed (a replay holds the recording's input lengths). A replay never guesses
  (a changed request is `transcript-miss`). Re-record with pdfium at hand when the premise is to be tested."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [synth.wm-pdf :as wm]))

(def OUT "test/fusion/hidden")
(def TRANSCRIPT "test/fusion/replay/hidden-older-ocr/transcript.edn")

(defn substituting [seed one-in]
  (fn [words]
    (let [rnd (java.util.Random. seed)]
      (doall
       (for [w words]
         (apply str (for [c w]
                      (if (Character/isLetter ^char c)
                        (let [hit (zero? (.nextInt rnd one-in))] (if (and hit (wm/CONFUSE c)) (wm/CONFUSE c) c))
                        c))))))))

(def VARIANTS
  {"hidden-exact-ocr" (fn [words] (vec words))
   "hidden-noisy-ocr" (substituting 20261006 7)})

(defn gold-text []
  (->> (:pages (edn/read-string (slurp wm/WORDS-EDN)))
       (map (fn [ws] (str/join " " (map :t ws))))
       (str/join "\n")))

(defn make-pdf [variant]
  (with-redefs [wm/substitute (VARIANTS variant)]
    (wm/patch-hidden (wm/source-of "hidden-older-ocr"))))

(defn make-transcript [^bytes pdf]
  (let [edn (slurp TRANSCRIPT)
        len (alength pdf)]
    (when-not (re-find #":length 160855\}" edn) (throw (ex-info "the transcript's input length is not as expected" {})))
    (str/replace edn #":length 160855\}" (str ":length " len "}"))))

(defn -main [& [cmd]]
  (case cmd
    "make" (doseq [v (sort (keys VARIANTS))]
             (let [pdf (make-pdf v)]
               (io/make-parents (str OUT "/" v "/doc.pdf"))
               (with-open [o (io/output-stream (str OUT "/" v "/doc.pdf"))] (.write o ^bytes pdf))
               (spit (str OUT "/" v "/gold.txt") (str (gold-text) "\n"))
               (spit (str OUT "/" v "/transcript.edn") (make-transcript pdf))
               (println "  wrote" v (alength pdf) "bytes")))
    "check" (let [bad (for [v (sort (keys VARIANTS))
                            :let [pdf (make-pdf v)
                                  on-disk (wm/slurp-bytes (str OUT "/" v "/doc.pdf"))]
                            :when (or (not (java.util.Arrays/equals on-disk ^bytes pdf))
                                      (not= (slurp (str OUT "/" v "/transcript.edn")) (make-transcript pdf))
                                      (not= (slurp (str OUT "/" v "/gold.txt")) (str (gold-text) "\n")))]
                        v)]
              (if (empty? bad)
                (println "  ok   hidden-pdf: the hidden-text fixtures are what hidden_pdf.clj makes")
                (do (println (str "  FAIL hidden-pdf: differ: " (str/join " " bad))) (System/exit 1))))
    (do (println "usage: hidden_pdf.clj make|check") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
