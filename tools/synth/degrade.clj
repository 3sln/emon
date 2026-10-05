(ns synth.degrade
  "Degraded 'scanned' variants of the synthetic PDFs (DEVLOG P2.8, D063).

      bb -cp tools:tools/lib -m synth.degrade [--out DIR] [stem ...]     pdf/STEM.pdf -> scan/STEM.pdf

  Rasterise at 150 dpi (poppler `pdftoppm`), skew by a small per-page angle, add Gaussian noise and a
  slight blur, JPEG at quality 50, and assemble an image-only PDF whose page size equals the source's
  (ImageMagick `magick`).  Everything is seeded from the stem and page number, so a rerun gives the same
  pixels.  The gold Model of a document is the SAME for its scan: the truth did not change, only the
  evidence.  Tooling only (poppler + ImageMagick); no gate runs it.  The magick version in use is printed
  (and build.clj records it in MANIFEST.json `tools.magick`): the same version reproduces the committed
  scan PDFs byte for byte."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pyfmt :refer [fmt]])
  (:import (java.security MessageDigest)))

(def CORPUS "test/corpus/synth")
(def DPI 150)

;; The 12 documents of P6.6 (D209) are scanned in colour and with overlays (degrade_fusion.clj) INSTEAD of in grey:
;; the pixel budget (the new committed files stay near 10 MB) cannot carry a third variant of each.
(def NO-PLAIN-SCAN #{"wm-gray-behind" "wm-image-behind" "bg-texture" "seal-over-text" "sig-over-name" "outlined-heading"
                     "wm-marked" "wm-ocg" "wm-annot" "tl-broken-shift" "tl-broken-unmapped" "hidden-older-ocr"})

(defn sh! [& cmd]
  (let [r (apply p/sh cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "command failed: " (str/join " " cmd) "\n" (:err r)) r)))
    (:out r)))

(defn seed [stem page]
  (let [md (MessageDigest/getInstance "SHA-256")
        hex (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes (fmt "%s/%d" stem page) "UTF-8"))))]
    (Long/parseLong (subs hex 0 8) 16)))

(defn magick-version [] (first (str/split-lines (sh! "magick" "-version"))))

(defn degrade! [stem out-dir]
  (let [src (str CORPUS "/pdf/" stem ".pdf")
        tmp (str (java.nio.file.Files/createTempDirectory "emon-degrade" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (sh! "pdftoppm" "-r" (str DPI) "-gray" "-png" src (str tmp "/p"))
      (let [pages (sort (filter #(str/ends-with? % ".png") (.list (io/file tmp))))
            outs (vec (for [[i f] (map-indexed (fn [i f] [(inc i) f]) pages)]
                        (let [sd (seed stem i)
                              angle (* (/ (- (mod sd 2001) 1000) 1000.0) 1.2)
                              out (fmt "%s/s%03d.jpg" tmp i)
                              [w h] (str/split (sh! "magick" "identify" "-format" "%w %h" (str tmp "/" f)) #"\s+")]
                          (sh! "magick" (str tmp "/" f) "-background" "white" "-rotate" (fmt "%.3f" angle)
                               "-gravity" "center" "-crop" (fmt "%sx%s+0+0" w h) "+repage"
                               "-seed" (str sd) "-attenuate" "0.35" "+noise" "Gaussian"
                               "-blur" "0x0.5" "-strip" "-quality" "50" out)
                          out)))
            dst (str out-dir "/" stem ".pdf")]
        (.mkdirs (io/file out-dir))
        (apply sh! "magick" (concat outs ["-density" (str DPI) "-units" "PixelsPerInch" dst]))
        (println (str "  degraded " stem " (" (count pages) " pages)")))
      (finally
        (doseq [f (.listFiles (io/file tmp))] (.delete f))
        (.delete (io/file tmp))))))

(defn -main [& args]
  (let [[out-dir args] (if (= (first args) "--out") [(second args) (drop 2 args)] [(str CORPUS "/scan") args])
        stems (if (seq args)
                args
                (sort (for [f (.list (io/file CORPUS "html")) :when (str/ends-with? f ".html")] (subs f 0 (- (count f) 5)))))]
    (println (str "  ImageMagick: " (magick-version)))
    ;; the tagged-table fixtures (P6.8) have no scans: their point is the tags
    (doseq [s stems :when (not (or (NO-PLAIN-SCAN s) (str/starts-with? s "ttab-")))] (degrade! s out-dir))))
