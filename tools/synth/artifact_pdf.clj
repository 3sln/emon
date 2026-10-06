#!/usr/bin/env bb
;; The fixtures of P7.5 (watermarks, backgrounds and OCR ghosts of them, D204, D300-D306): born-digital pages with a RASTER watermark UNDER the
;; text, so the text layer says nothing about it and OCR reads it as text no text-layer word explains.
;;
;; THE BODY is four letter pages of ghostscript's own text (a seeded list of 60 plain words, six lines at the top and five at the foot of each
;; page, the middle left bare so Tesseract can read what is behind: a watermark under dense text is read as garbage that rule 170 drops anyway).
;; Its text is the gold: test/artifacts/gold/body.txt. The watermark is an image of CONFIDENTIAL COPY in grey (#909090, Helvetica Bold 56 pt) the
;; size of the page, underlaid by qpdf:
;;
;;     wm-recur-image    on all four pages, upright, mid-page         (defence 2: the same text at the same place on every page)
;;     wm-recur-partial  on pages 1 to 3 of the four (75 per cent)    (defence 2 again: 60 per cent of the pages and at least three)
;;
;;     bb tools/synth/artifact_pdf.clj            write test/artifacts/pdf/*.pdf and gold/body.txt (needs ghostscript, ImageMagick and qpdf)
;;
;; The files are committed, so no gate needs any of the three. (A diagonal watermark was tried and is not here: Tesseract does not read text
;; turned by 20 degrees, so no OCR transcript has a diagonal ghost to take out; defence 4 is a law of `emon.arbitertest` until one does.)
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def out "test/artifacts")
(def tmp (str (fs/create-temp-dir {:prefix "emon-artifact."})))

(def words (str/split "school house mother water river morning garden letter window summer winter paper table city story friend family number answer problem question picture example country evening machine market office season system teacher village weather yellow bridge button candle dinner engine forest ground hammer island jacket kitchen lesson mirror napkin orange pencil rabbit shadow ticket valley wagon" #" "))
(defn line [r n] (str/join " " (repeatedly n #(nth words (.nextInt r (count words))))))
(def pages (vec (for [p (range 1 5)]
                  (let [r (java.util.Random. (+ 100 p))]
                    {:top (vec (repeatedly 6 #(line r 9))) :bot (vec (repeatedly 5 #(line r 9)))}))))

(defn show [x y s] (format "%d %d moveto (%s) show\n" x y s))
(spit (str tmp "/body.ps")
      (str "%!PS\n/Helvetica findfont 12 scalefont setfont\n"
           (apply str (for [{:keys [top bot]} pages]
                        (str (apply str (map-indexed (fn [i s] (show 72 (- 720 (* i 18)) s)) top))
                             (apply str (map-indexed (fn [i s] (show 72 (- 230 (* i 18)) s)) bot))
                             "showpage\n")))))
(fs/create-dirs (str out "/pdf"))
(fs/create-dirs (str out "/gold"))
(spit (str out "/gold/body.txt") (str (str/join "\n" (for [{:keys [top bot]} pages l (concat top bot)] l)) "\n"))
(shell "gs" "-q" "-dNOPAUSE" "-dBATCH" "-sDEVICE=pdfwrite" "-sPAPERSIZE=letter" (str "-sOutputFile=" tmp "/body.pdf") (str tmp "/body.ps"))

(shell "magick" "-size" "612x792" "xc:white" "-gravity" "center" "-font" "Helvetica-Bold" "-pointsize" "56" "-fill" "#909090"
       "-annotate" "0" "CONFIDENTIAL COPY" (str tmp "/wm.png"))
(shell "magick" (str tmp "/wm.png") "-units" "PixelsPerInch" "-density" "72" (str tmp "/wm.pdf"))
(shell "qpdf" "--deterministic-id" (str tmp "/body.pdf") "--underlay" (str tmp "/wm.pdf") "--repeat=1" "--" (str out "/pdf/wm-recur-image.pdf"))
(shell "qpdf" "--deterministic-id" (str tmp "/body.pdf") "--underlay" (str tmp "/wm.pdf") "--to=1-3" "--repeat=1" "--" (str out "/pdf/wm-recur-partial.pdf"))
(fs/delete-tree tmp)
(println "wrote" out)
