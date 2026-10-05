(ns synth.degrade-graphics
  "The scanned twins of the GRAPHICS set (DEVLOG P6.8, D227).

      bb -cp tools:tools/lib -m synth.degrade-graphics                  scan-color/ of the committed colour subset; FACTS-gfx.json
      bb -cp tools:tools/lib -m synth.degrade-graphics --all DIR        scan-color of EVERY stem of GRAPHICS into DIR (a corpus
                                                                        category for the S3 store: `bb corpus-push`; NOT committed)
      bb -cp tools:tools/lib -m synth.degrade-graphics --check          FACTS-gfx.json is what the seeds say

  `test/corpus/synth/GRAPHICS` lists the documents whose rule and fill truth is measured. Their grey `scan/` twins are
  `degrade.clj`'s (150 dpi, skew +-1.2 degrees from the stem's seed); the new documents of P6.8 got theirs from it. The colour
  twins are the fusion set's kind (`degrade_fusion.clj`: 130 dpi RGB, skew +-3 degrees, light noise, JPEG 45): only COLOUR_SUBSET
  is committed, the pixel budget being what it is (D209, D227); the others are made by `--all` for long runs.

  `FACTS-gfx.json` records, for every stem, what `build.clj` copies into MANIFEST `facts`: `skew-scan` (degrees per page, the
  rotation of `scan/`), `dpi` {scan, scan-color} and, for the committed colour subset, `skew` when the fusion set did not
  record it. The scorer maps a twin's rule truth through them (tools/accuracy/graphics.clj)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pyjson :as pj :refer [om]]
            [synth.degrade :as D]
            [synth.degrade-fusion :as DF]))

(def CORPUS D/CORPUS)
(def FACTS (str CORPUS "/FACTS-gfx.json"))
(def COLOUR-SUBSET ["table-zebra" "table-header-fill" "table-white-rules" "gfx-shading-bands" "mixed-table-image-shaded"])

(defn stems [] (vec (remove #(or (str/blank? %) (str/starts-with? % "#")) (map str/trim (str/split-lines (slurp (str CORPUS "/GRAPHICS")))))))

(defn scan-skew
  "degrade.clj's rotation of a page of `scan/`, degrees (its formula, three decimals as it prints them)."
  [stem page]
  (let [sd (D/seed stem page)]
    (Double/parseDouble (String/format java.util.Locale/ROOT "%.3f" (to-array [(* (/ (- (mod sd 2001) 1000) 1000.0) 1.2)])))))

(defn pages
  "A document's page count: MANIFEST.json's when it has the document (the check reads only committed files), else poppler's."
  [stem]
  (or (some-> (io/file CORPUS "MANIFEST.json") (as-> f (when (.exists f) (pj/parse-file (str f)))) (get-in ["docs" stem "facts" "pages"]) count)
      (let [out (D/sh! "pdfinfo" (str CORPUS "/pdf/" stem ".pdf"))]
        (parse-long (second (re-find #"(?m)^Pages:\s+(\d+)" out))))))

(defn facts-of [stem]
  (let [n (pages stem)]
    (om "skew-scan" (vec (for [i (range 1 (inc n))] (scan-skew stem i)))
        "dpi" (om "scan" D/DPI "scan-color" DF/DPI)
        "color-skew" (vec (for [i (range 1 (inc n))] (DF/skew (D/seed stem i)))))))

(defn color! [stem out-dir]
  (let [tmp (str (java.nio.file.Files/createTempDirectory "emon-gfx" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (D/sh! "pdftoppm" "-r" (str DF/DPI) "-png" (str CORPUS "/pdf/" stem ".pdf") (str tmp "/p"))
      (let [pngs (vec (sort (filter #(str/ends-with? % ".png") (.list (io/file tmp)))))
            jpgs (vec (for [[i f] (map-indexed (fn [i f] [(inc i) f]) pngs)]
                        (let [img (str tmp "/" f)
                              [w h] (mapv parse-long (str/split (str/trim (D/sh! "magick" "identify" "-format" "%w %h" img)) #"\s+"))
                              out (format "%s/c%03d.jpg" tmp i)]
                          (D/sh! "magick" img "-background" "white" "-rotate" (String/format java.util.Locale/ROOT "%.3f" (to-array [(DF/skew (D/seed stem i))]))
                                 "-gravity" "center" "-crop" (format "%dx%d+0+0" w h) "+repage"
                                 "-seed" (str (D/seed stem i)) "-attenuate" "0.03" "+noise" "Gaussian" "-blur" "0x0.4"
                                 "-strip" "-sampling-factor" "4:2:0" "-quality" (str DF/QUALITY) out)
                          out)))]
        (.mkdirs (io/file out-dir))
        (apply D/sh! "magick" (concat jpgs ["-density" (str DF/DPI) "-units" "PixelsPerInch" (str out-dir "/" stem ".pdf")]))
        (println "  colour scan" stem (count pngs) "pages"))
      (finally (doseq [x (.listFiles (io/file tmp))] (.delete x)) (.delete (io/file tmp))))))

(defn facts-text [] (str (pj/dumps (apply om (mapcat (fn [s] [s (facts-of s)]) (stems))) {:indent 1}) "\n"))

(defn -main [& args]
  (cond
    (= args ["--check"])
    (if (= (facts-text) (slurp FACTS :encoding "UTF-8"))
      (println (str "  ok   degrade-graphics: FACTS-gfx.json is what the seeds say (" (count (stems)) " documents)"))
      (do (println "  FAIL degrade-graphics: FACTS-gfx.json is not what the seeds say") (System/exit 1)))
    (= "--all" (first args)) (doseq [s (stems)] (color! s (second args)))
    :else (do (doseq [s COLOUR-SUBSET] (color! s (str CORPUS "/scan-color")))
              (spit FACTS (facts-text) :encoding "UTF-8"))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
