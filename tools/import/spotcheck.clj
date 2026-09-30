(ns import.spotcheck
  "Draw a gold file's regions on its page, to check the coordinate system by eye (DEVLOG P2.11).

      bb tools/import/spotcheck.clj DATASET [ID ...]    (default: 2 items; needs ImageMagick `magick`; PDFs need pdftoppm)

  Writes corpus/_gold/_spotcheck/<id>-p<N>.png.  A PDF page is rendered at 72 dpi, so one output pixel
  is exactly one point and a region in points lands where its text is; a raster page is drawn as is
  (1 px = 1 pt, nominal).  Colours: table red, cell blue, field/heading green, seal magenta, others orange.
  Not a gate: a human looks at the picture.  Also prints, per item, how many regions lie outside the page.
  (Drawing is done with ImageMagick, not a Python imaging library.)"
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj]))

(def COL {"table" [220 0 0] "cell" [0 70 220] "field" [0 150 0] "heading" [0 150 0]
          "seal" [220 0 220] "figure" [150 90 0] "row" [0 0 0]})

(defn- regions [n]
  (concat (for [r (get n "at")] [(get n "kind") r]) (mapcat regions (get n "children"))))

(defn -main [& argv]
  (let [[ds & ids] argv
        gdir (str C/out-dir "/" ds)
        ids (if (seq ids) ids
                (let [names (sort (for [f (fs/list-dir gdir) :let [s (str (fs/file-name f))]
                                        :when (str/ends-with? s ".gold.json")]
                                    (subs s 0 (- (count s) 10))))]
                  [(first names) (nth names (quot (count names) 2))]))
        outd (str C/out-dir "/_spotcheck")]
    (fs/create-dirs outd)
    (doseq [gid ids]
      (let [g (pj/parse-file (str gdir "/" gid ".gold.json"))
            prov (get g "provenance" {})
            regs (regions (get-in g ["model" "body"]))
            pages (let [ps (into {} (for [p (get-in g ["model" "pages"])
                                          :when (some (fn [[_ r]] (= (first r) (get p "n"))) regs)]
                                      [(get p "n") p]))]
                    (if (seq ps) ps {1 (first (get-in g ["model" "pages"]))}))]
        (doseq [[n p] (sort pages)]
          (let [src (if (contains? prov "pdf")
                      (let [tmp (str outd "/_tmp")]
                        (proc/shell "pdftoppm" "-r" "72" "-f" (str n) "-l" (str n) "-png" "-singlefile"
                                    (str C/corpus-dir "/" (get prov "pdf")) tmp)
                        (str tmp ".png"))
                      (str C/corpus-dir "/" (get-in prov ["variants" "image"])))
                [iw ih] (map parse-long (str/split (:out (proc/shell {:out :string} "magick" "identify" "-format" "%w %h" (str src "[0]"))) #" "))
                sx (/ iw (double (get p "w"))) sy (/ ih (double (get p "h")))
                mine (for [[kind r] regs :when (= (first r) n)] [kind r])
                out (count (for [[_ [_ x0 y0 x1 y1]] mine
                                 :when (or (> x1 (+ (get p "w") 1)) (> y1 (+ (get p "h") 1)) (< x0 -1) (< y0 -1))] 1))
                path (str outd "/" gid "-p" n ".png")
                draw (mapcat (fn [[kind [_ x0 y0 x1 y1]]]
                               (let [[r g b] (COL kind [255 130 0])]
                                 ["-stroke" (format "rgb(%d,%d,%d)" r g b) "-draw"
                                  (format "rectangle %.2f,%.2f %.2f,%.2f" (* x0 sx) (* y0 sy) (* x1 sx) (* y1 sy))]))
                             mine)]
            (apply proc/shell "magick" (str src "[0]") "-colorspace" "sRGB" "-fill" "none" "-strokewidth" "2"
                   (concat draw ["-resize" "1600x1600>" path]))
            (println (format "%s page %d: %dx%d page units, image scale %.3f, %d regions outside -> %s"
                             gid n (get p "w") (get p "h") sx out path))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
