#!/usr/bin/env bb
;; THE IMAGE FIXTURES AND THEIR ORACLE (P6.1, D187-D190; babashka, D094; ImageMagick as the third-party oracle).
;;
;;   bb tools/image-fixtures.clj            write test/image/*: small PNG, JPEG and TIFF files covering every coding the
;;                                          ImageDecode port reads (and the ones it refuses), and test/image/expect.edn
;;   bb tools/image-fixtures.clj --check    do the committed files still say what expect.edn says, to ImageMagick?
;;
;; THE FILES ARE WRITTEN BY ImageMagick (and libtiff's `tiffcp`/`tiffset`) from one 48x36 picture with a corner mark
;; in every corner (so an orientation is visible) and a gradient (so a depth is). The EXPECTATION is not the port's
;; output: it is what ImageMagick says the pixels are, read after `-auto-orient`, with alpha taken over white, at eight
;; bits (so the port's EXIF orientation, alpha and depth rules are checked against another decoder's opinion, SPEC 7.2):
;; per frame its width, height and an FNV-1a 64 hash of the raw pixel bytes; the channel count, the `bilevel` flag and
;; the resolution are stated here BY HAND, because they are the contract and not a decoder's opinion.
;;
;; WHERE THE PORT AND THE ORACLE MAY DIFFER, and by how much, is the `:tol` of a row: 0 (exact) unless the row says
;; why not (a lossy JPEG: two decoders' IDCTs and chroma filters; CMYK: two roundings of the same formula; a 16-bit
;; alpha: ImageMagick blends at sixteen bits and truncates, the port reduces to eight bits first, D188). `bb image` is the gate: both
;; hosts, every row, exactly (or within `:tol`), and the two hosts to each other.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell process]] '[clojure.string :as str] '[clojure.edn :as edn])

(def out "test/image")
(def check? (some #{"--check"} *command-line-args*))

(defn run
  "Run a command; the stdout bytes, or throw with its stderr."
  [& args]
  (when (System/getenv "IMGFIX_DEBUG") (println (str/join " " args)))
  (let [r @(apply process {:out :bytes :err :string} (map str args))]
    (when-not (zero? (:exit r)) (throw (ex-info (str (str/join " " args) ": " (:err r)) {})))
    (:out r)))

(defn fnv64 [^bytes b]
  (let [h (reduce (fn [h x] (unchecked-multiply (bit-xor h (bit-and (long x) 255)) 0x100000001b3))
                  (unchecked-long 0xcbf29ce484222325) b)]
    (format "%016x" h)))

;; --------------------------------------------------------------- the rows
;; [file {:ch 1|3 :bilevel? :dpi100 :tol :make [magick args after the picture] | :copy [from, tiffcp args]}]
;; `pic` is the base picture, `gray` its gray form, `bw` its one-bit form.

(def sizes ["48x36" "40x30" "33x25"])

(defn rows []
  (let [p {:ch 3}
        g {:ch 1}
        bw {:ch 1 :bilevel true}]
    [;; ------------------------------------------------------------------- PNG
     ["png-gray8.png" (assoc g :make [:gray "-define" "png:color-type=0" "-depth" "8"])]
     ["png-gray16.png" (assoc g :make [:gray "-define" "png:color-type=0" "-depth" "16"])]
     ["png-gray4.png" (assoc g :make [:gray "-define" "png:color-type=0" "-depth" "4"])]
     ["png-gray2.png" (assoc g :make [:gray "-define" "png:color-type=0" "-depth" "2"])]
     ["png-gray1.png" (assoc bw :make [:bw "-define" "png:color-type=0" "-depth" "1"])]
     ["png-gray-alpha8.png" (assoc g :make [:gray-alpha "-define" "png:color-type=4" "-depth" "8"])]
     ["png-gray-alpha16.png" (assoc g :tol 1 :make [:gray-alpha "-define" "png:color-type=4" "-depth" "16"])]
     ["png-rgb8.png" (assoc p :make [:pic "-define" "png:color-type=2" "-depth" "8"])]
     ["png-rgb16.png" (assoc p :make [:pic "-define" "png:color-type=2" "-depth" "16"])]
     ["png-rgba8.png" (assoc p :make [:pic-alpha "-define" "png:color-type=6" "-depth" "8"])]
     ["png-rgba16.png" (assoc p :tol 1 :make [:pic-alpha "-define" "png:color-type=6" "-depth" "16"])]
     ["png-palette8.png" (assoc p :make [:pic "-colors" "64" "-define" "png:color-type=3" "-depth" "8"])]
     ["png-palette4.png" (assoc p :make [:pic "-colors" "12" "-define" "png:color-type=3" "-depth" "4"])]
     ["png-palette-trns.png" (assoc p :make [:pic-alpha "-colors" "40" "-define" "png:color-type=3" "-depth" "8"])]
     ["png-interlaced.png" (assoc p :make [:pic "-interlace" "PNG" "-define" "png:color-type=2"])]
     ["png-phys300.png" (assoc p :dpi100 30000 :make [:pic "-units" "PixelsPerInch" "-density" "300" "-define" "png:color-type=2"])]
     ["png-phys-cm.png" (assoc p :dpi100 25400 :make [:pic "-units" "PixelsPerCentimeter" "-density" "100" "-define" "png:color-type=2"])]
     ;; ------------------------------------------------------------------ JPEG (two decoders' IDCTs differ: :tol)
     ["jpeg-gray.jpg" (assoc g :tol 1 :make [:gray "-quality" "90"])]
     ["jpeg-444.jpg" (assoc p :tol 2 :make [:pic "-sampling-factor" "1x1" "-quality" "92"])]
     ["jpeg-420.jpg" (assoc p :tol 6 :make [:pic "-sampling-factor" "2x2" "-quality" "85"])]
     ["jpeg-422.jpg" (assoc p :tol 6 :make [:pic "-sampling-factor" "2x1" "-quality" "85"])]
     ["jpeg-progressive.jpg" (assoc p :tol 6 :make [:pic "-interlace" "JPEG" "-quality" "80"])]
     ["jpeg-jfif300.jpg" (assoc p :tol 6 :dpi100 30000 :make [:pic "-units" "PixelsPerInch" "-density" "300" "-quality" "80"])]
     ["jpeg-jfif-cm.jpg" (assoc p :tol 6 :dpi100 25400 :make [:pic "-units" "PixelsPerCentimeter" "-density" "100" "-quality" "80"])]
     ["jpeg-cmyk.jpg" (assoc p :refuse "unsupported-image" :make [:pic "-colorspace" "CMYK" "-quality" "80"])]
     ["jpeg-orient2.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 2}])]
     ["jpeg-orient3.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 3}])]
     ["jpeg-orient4.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 4}])]
     ["jpeg-orient5.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 5}])]
     ["jpeg-orient6.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 6}])]
     ["jpeg-orient7.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 7}])]
     ["jpeg-orient8.jpg" (assoc p :tol 6 :make [:pic "-quality" "80"] :post [:exif {:orient 8}])]
     ["jpeg-exif-dpi.jpg" (assoc p :tol 6 :dpi100 30000 :make [:pic "-quality" "80"] :post [:exif {:dpi [300 1 300 1] :unit 2}])]
     ["jpeg-exif-dpi-cm.jpg" (assoc p :tol 6 :dpi100 25400 :make [:pic "-quality" "80"] :post [:exif {:dpi [100 1 100 1] :unit 3}])]
     ["jpeg-exif-orient8-dpi.jpg" (assoc p :tol 6 :dpi100 30000 :make [:pic "-quality" "80"] :post [:exif {:orient 8 :dpi [150 1 300 1] :unit 2}])]
     ["png-exif-orient6.png" (assoc p :make [:pic "-define" "png:color-type=2"] :post [:png-exif {:orient 6}])]
     ["png-exif-orient5.png" (assoc g :make [:gray "-define" "png:color-type=0"] :post [:png-exif {:orient 5}])]
     ;; ------------------------------------------------------------------ TIFF
     ["tiff-gray8-none.tif" (assoc g :make [:gray "-compress" "None" "-depth" "8"])]
     ["tiff-gray8-packbits.tif" (assoc g :make [:gray "-compress" "RLE" "-depth" "8"])]
     ["tiff-gray8-lzw.tif" (assoc g :make [:gray "-compress" "LZW" "-depth" "8"])]
     ["tiff-gray8-lzw-pred2.tif" (assoc g :make [:gray "-compress" "LZW" "-define" "tiff:predictor=2" "-depth" "8"])]
     ["tiff-gray8-zip.tif" (assoc g :make [:gray "-compress" "Zip" "-depth" "8"])]
     ["tiff-gray8-zip-pred2.tif" (assoc g :make [:gray "-compress" "Zip" "-define" "tiff:predictor=2" "-depth" "8"])]
     ["tiff-gray16-lzw-pred2.tif" (assoc g :make [:gray "-compress" "LZW" "-define" "tiff:predictor=2" "-depth" "16"])]
     ["tiff-gray16-none-be.tif" (assoc g :make [:gray "-compress" "None" "-depth" "16" "-endian" "MSB"])]
     ["tiff-gray16-none-le.tif" (assoc g :make [:gray "-compress" "None" "-depth" "16" "-endian" "LSB"])]
     ["tiff-gray4.tif" (assoc g :make [:gray "-compress" "None" "-depth" "4" "-define" "quantum:polarity=min-is-black"])]
     ["tiff-rgb8-none.tif" (assoc p :make [:pic "-compress" "None" "-depth" "8"])]
     ["tiff-rgb8-lzw.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"])]
     ["tiff-rgb8-lzw-pred2.tif" (assoc p :make [:pic "-compress" "LZW" "-define" "tiff:predictor=2" "-depth" "8"])]
     ["tiff-rgb8-packbits.tif" (assoc p :make [:pic "-compress" "RLE" "-depth" "8"])]
     ["tiff-rgb8-zip.tif" (assoc p :make [:pic "-compress" "Zip" "-depth" "8"])]
     ["tiff-rgb16-lzw-pred2.tif" (assoc p :make [:pic "-compress" "LZW" "-define" "tiff:predictor=2" "-depth" "16"])]
     ["tiff-rgba8-assoc.tif" (assoc p :make [:pic-alpha "-compress" "LZW" "-depth" "8" "-define" "tiff:alpha=associated"])]
     ["tiff-rgba8-unassoc.tif" (assoc p :make [:pic-alpha "-compress" "LZW" "-depth" "8" "-define" "tiff:alpha=unassociated"])]
     ["tiff-gray-alpha8.tif" (assoc g :make [:gray-alpha "-compress" "LZW" "-depth" "8" "-define" "tiff:alpha=unassociated"])]
     ["tiff-palette8.tif" (assoc p :make [:pic "-colors" "50" "-type" "Palette" "-compress" "LZW" "-depth" "8"])]
     ["tiff-cmyk8.tif" (assoc p :tol 1 :make [:pic "-colorspace" "CMYK" "-compress" "LZW" "-depth" "8"])]
     ["tiff-bilevel-none.tif" (assoc bw :make [:bw "-compress" "None" "-depth" "1"])]
     ["tiff-bilevel-lzw.tif" (assoc bw :make [:bw "-compress" "LZW" "-depth" "1"])]
     ["tiff-bilevel-packbits.tif" (assoc bw :make [:bw "-compress" "RLE" "-depth" "1"])]
     ["tiff-bilevel-g4.tif" (assoc bw :make [:bw "-compress" "Group4" "-depth" "1"])]
     ;; Group 3: one-dimensional is read; fill bits and two-dimensional are not (x/image/ccitt reads neither, D189)
     ["tiff-bilevel-g3.tif" (assoc bw :copy ["tiff-bilevel-none.tif" "-c" "g3:1d"])]
     ["tiff-bilevel-g3-fill.tif" (assoc bw :refuse "unsupported-image" :copy ["tiff-bilevel-none.tif" "-c" "g3:1d:fill"])]
     ["tiff-bilevel-g3-2d.tif" (assoc bw :refuse "unsupported-image" :copy ["tiff-bilevel-none.tif" "-c" "g3:2d"])]
     ["tiff-strips5.tif" (assoc p :make [:pic "-compress" "LZW" "-define" "tiff:rows-per-strip=5" "-depth" "8"])]
     ["tiff-tiled16.tif" (assoc p :make [:pic "-compress" "LZW" "-define" "tiff:tile-geometry=16x16" "-depth" "8"])]
     ["tiff-tiled-none.tif" (assoc g :make [:gray "-compress" "None" "-define" "tiff:tile-geometry=32x32" "-depth" "8"])]
     ["tiff-dpi200.tif" (assoc p :dpi100 20000 :make [:pic "-compress" "None" "-units" "PixelsPerInch" "-density" "200" "-depth" "8"])]
     ["tiff-dpi-cm.tif" (assoc p :dpi100 25400 :make [:pic "-compress" "None" "-units" "PixelsPerCentimeter" "-density" "100" "-depth" "8"])]
     ["tiff-orient2.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "2"])]
     ["tiff-orient3.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "3"])]
     ["tiff-orient4.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "4"])]
     ["tiff-orient5.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "5"])]
     ["tiff-orient6.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "6"])]
     ["tiff-orient7.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "7"])]
     ["tiff-orient8.tif" (assoc p :make [:pic "-compress" "LZW" "-depth" "8"] :post [:tiffset "-s" "274" "8"])]
     ["tiff-orient6-bilevel-g4.tif" (assoc bw :make [:bw "-compress" "Group4" "-depth" "1"] :post [:tiffset "-s" "274" "6"])]
     ["tiff-orient8-dpi.tif" (assoc p :dpi100 30000 :make [:pic "-compress" "None" "-units" "PixelsPerInch" "-density" "150x300" "-depth" "8"] :post [:tiffset "-s" "274" "8"])]
     ["tiff-planar-rgb.tif" (assoc p :copy ["tiff-rgb8-none.tif" "-p" "separate"])]
     ["tiff-planar-strips.tif" (assoc p :copy ["tiff-strips5.tif" "-p" "separate"])]
     ["tiff-jpeg.tif" (assoc p :refuse "unsupported-image" :make [:pic "-compress" "JPEG" "-depth" "8"])]
     ["tiff-float32.tif" (assoc g :refuse "unsupported-image" :make [:gray "-compress" "None" "-depth" "32" "-define" "quantum:format=floating-point"])]
     ;; multi-page: the frames differ in size and in content
     ["tiff-multi3-rgb-lzw.tif" (assoc p :multi true :make [:multi "-compress" "LZW" "-depth" "8"])]
     ["tiff-multi3-gray-none.tif" (assoc g :multi true :make [:multi-gray "-compress" "None" "-depth" "8"])]
     ["tiff-multi3-g4.tif" (assoc bw :multi true :make [:multi-bw "-compress" "Group4" "-depth" "8"])]
     ;; refusals and damage
     ["png-truncated.png" {:corrupt [:truncate "png-rgb8.png" 120] :refuse "undecodable"}]
     ["tiff-truncated.tif" {:corrupt [:truncate "tiff-gray8-none.tif" 900] :refuse "undecodable"}]
     ["jpeg-truncated.jpg" {:corrupt [:truncate "jpeg-444.jpg" 300] :refuse "undecodable"}]]))

;; ------------------------------------------------------------- the pictures

(defn pictures
  "Write the base pictures into dir: {:pic :pic-alpha :gray :gray-alpha :bw} and the multi-page sources."
  [dir]
  (let [f (fn [n] (str dir "/" n))
        ;; (babashka's SCI drops, without a word, a direct call of a function with more than about a dozen
        ;; arguments: every call here hands `mk` ONE vector)
        mk (fn [v] (apply run "magick" v))]
    ;; a gradient, a mark in each corner (a different one: the orientation shows), a disc, a diagonal
    (mk ["-size" "48x36" "gradient:rgb(250,240,20)-rgb(20,60,230)" "-fill" "rgb(220,20,20)" "-draw" "rectangle 0,0 13,9"
         "-fill" "rgb(10,160,40)" "-draw" "rectangle 40,0 47,4" "-fill" "rgb(20,20,20)" "-draw" "rectangle 0,30 4,35"
         "-fill" "white" "-draw" "rectangle 44,32 47,35" "-fill" "rgb(130,30,160)" "-draw" "circle 26,19 26,28"
         "-stroke" "black" "-draw" "line 2,12 30,34" "+stroke" (f "pic.png")])
    (mk [(f "pic.png") "-colorspace" "Gray" "-define" "png:color-type=0" (f "gray.png")])
    (mk [(f "pic.png") "-colorspace" "Gray" "-threshold" "50%" "-monochrome" (f "bw.png")])
    ;; a soft-edged alpha: opaque at the left, clear at the right, with a hole
    (mk [(f "pic.png") "(" "-size" "48x36" "gradient:white-black" "-rotate" "-90" "-resize" "48x36!" "-fill" "black" "-draw" "circle 10,25 10,29" ")"
         "-alpha" "off" "-compose" "CopyOpacity" "-composite" (f "pic-alpha.png")])
    (mk [(f "gray.png") "(" (f "pic-alpha.png") "-channel" "A" "-separate" "+channel" ")" "-alpha" "off" "-compose" "CopyOpacity" "-composite" (f "gray-alpha.png")])
    ;; three frames of different sizes, each with its own picture
    (doseq [[i sz] (map-indexed vector sizes)]
      (mk ["-size" sz "-seed" (str (+ 7 i)) "plasma:fractal" "-fill" (nth ["red" "blue" "green"] i)
           "-draw" "rectangle 1,1 9,6" (f (str "m" i ".png"))])
      (mk [(f (str "m" i ".png")) "-colorspace" "Gray" (f (str "mg" i ".png"))])
      (mk [(f (str "m" i ".png")) "-colorspace" "Gray" "-threshold" "45%" "-monochrome" (f (str "mb" i ".png"))]))))

;; ------------------------------------------------------------- the oracle

(defn expected-raw
  "ImageMagick's pixels for one frame of a file, as it would be displayed: auto-oriented, over white, eight bits."
  [file i ch]
  (run "magick" (str file "[" i "]") "-auto-orient" "-background" "white" "-alpha" "remove" "-alpha" "off" "-depth" "8"
       "-colorspace" (if (= ch 1) "Gray" "sRGB") (str (if (= ch 1) "gray" "rgb") ":-")))

(defn frame-count [file]
  (count (str/split-lines (String. ^bytes (run "magick" "identify" "-format" "%s\n" file)))))

(defn dims [file i]
  (let [[w h] (map parse-long (str/split (str/trim (String. ^bytes (run "magick" "identify" "-auto-orient" "-format" "%w %h" (str file "[" i "]")))) #" "))]
    [w h]))

(defn expectation [path {:keys [ch bilevel dpi100 tol refuse]}]
  (if refuse
    {:refuse refuse}
    (let [n (frame-count path)]
      {:ch ch :bilevel (if bilevel 1 0) :dpi100 (or dpi100 0) :tol (or tol 0) :frames n
       :px (vec (for [i (range n)]
                  (let [[w h] (dims path i)
                        raw (expected-raw path i ch)]
                    (assert (= (alength ^bytes raw) (* w h ch)) (str path " frame " i ": " (alength ^bytes raw) " bytes for " w "x" h "x" ch))
                    {:w w :h h :fnv (fnv64 raw)})))})))

;; ------------------------------------------------------------- the writer

(defn make-file [dir pdir path [src & args]]
  (let [P (fn [n] (str pdir "/" n))
        base (case src
               :pic (P "pic.png") :pic-alpha (P "pic-alpha.png") :gray (P "gray.png") :gray-alpha (P "gray-alpha.png")
               :bw (P "bw.png") nil)]
    (case src
      (:multi :multi-gray :multi-bw)
      (let [pre (case src :multi "m" :multi-gray "mg" :multi-bw "mb")]
        (apply run "magick" (concat (for [i (range 3)] (P (str pre i ".png"))) args [path])))
      (apply run "magick" (concat [base] args [path])))))

;; ----------------------------------------------------------- post-processing
;; ImageMagick will not write an EXIF orientation (or a resolution that is EXIF's alone) the way a camera does, so the
;; bytes are made here: an EXIF TIFF structure (big endian) with the tags the port reads, spliced in.

(defn be16 [n] [(bit-and (bit-shift-right n 8) 255) (bit-and n 255)])
(defn be32 [n] (concat (be16 (bit-shift-right n 16)) (be16 (bit-and n 65535))))

(defn exif-blob
  "The TIFF structure of an EXIF block: {:orient 1..8, :dpi [xn xd yn yd], :unit 2|3}."
  [{:keys [orient dpi unit]}]
  (let [entries (cond-> []
                  orient (conj [274 3 1 [(first (be16 orient)) (second (be16 orient)) 0 0]])
                  dpi (conj [282 5 1 :x] [283 5 1 :y])
                  unit (conj [296 3 1 [(first (be16 unit)) (second (be16 unit)) 0 0]]))
        n (count entries)
        data-at (+ 8 2 (* 12 n) 4)
        [xn xd yn yd] dpi
        entry (fn [[tag typ cnt v]]
                (concat (be16 tag) (be16 typ) (be32 cnt)
                        (case v :x (be32 data-at) :y (be32 (+ data-at 8)) v)))]
    (byte-array (map unchecked-byte
                     (concat [0x4d 0x4d 0 42] (be32 8) (be16 n) (mapcat entry entries) (be32 0)
                             (when dpi (concat (be32 xn) (be32 xd) (be32 yn) (be32 yd))))))))

(defn jpeg-with-exif [^bytes jpg exif]
  (let [blob (exif-blob exif)
        seg (byte-array (concat [0xff 0xe1] (be16 (+ 2 6 (alength blob))) (map int "Exif") [0 0] blob))
        ;; after SOI and, when present, the JFIF APP0 segment
        at (if (and (= (bit-and (aget jpg 2) 255) 0xff) (= (bit-and (aget jpg 3) 255) 0xe0))
             (+ 4 (bit-or (bit-shift-left (bit-and (aget jpg 4) 255) 8) (bit-and (aget jpg 5) 255)))
             2)]
    (byte-array (concat (take at jpg) seg (drop at jpg)))))

(defn png-with-exif [^bytes png exif]
  (let [blob (exif-blob exif)
        crc (doto (java.util.zip.CRC32.) (.update (byte-array (concat (map int "eXIf") blob))))
        chunk (byte-array (concat (be32 (alength blob)) (map int "eXIf") blob (be32 (.getValue crc))))
        ;; after the signature (8) and IHDR (4 + 4 + 13 + 4)
        at 33]
    (byte-array (concat (take at png) chunk (drop at png)))))

(defn post! [path [kind & args]]
  (case kind
    :exif (fs/write-bytes path (jpeg-with-exif (fs/read-all-bytes path) (first args)))
    :png-exif (fs/write-bytes path (png-with-exif (fs/read-all-bytes path) (first args)))
    :tiffset (apply run "tiffset" (concat args [path]))))

(defn write-all []
  (fs/create-dirs out)
  (let [pdir (str (fs/create-temp-dir {:prefix "imgfix."}))]
    (try
      (pictures pdir)
      (let [res (reduce
                 (fn [acc [name {:keys [make copy corrupt] :as row}]]
                   (let [path (str out "/" name)]
                     (cond
                       make (make-file out pdir path make)
                       copy (let [[from & a] copy] (apply run "tiffcp" (concat a [(str out "/" from) path])))
                       corrupt (let [[_ from n] corrupt
                                     b (fs/read-all-bytes (str out "/" from))]
                                 (fs/write-bytes path (byte-array (take n b)))))
                     (when-let [post (:post row)] (post! path post))
                     (assoc acc name (expectation path row))))
                 (sorted-map) (rows))]
        (spit (str out "/expect.edn")
              (str ";; WRITTEN by tools/image-fixtures.clj: what ImageMagick says each fixture's pixels are (D187-D190).\n"
                   ";; :px is per frame [w h fnv] AFTER orientation, over white, eight bits; :tol is the allowed difference of a\n"
                   ";; host's byte from the oracle's (0 exact); a :refuse row is an input the port reads no coding of, or damage.\n"
                   "{\n"
                   (str/join "\n" (for [[k v] res] (str " " (pr-str k) " " (pr-str v))))
                   "\n}\n")))
      (finally (fs/delete-tree pdir)))))

(defn check-all []
  (let [exp (edn/read-string (slurp (str out "/expect.edn")))
        bad (atom 0)]
    (doseq [[name row] (rows)
            :let [path (str out "/" name)
                  want (get exp name)
                  got (expectation path row)]]
      (when-not (= want got)
        (swap! bad inc)
        (println "  FAIL" name "no longer says what expect.edn says to ImageMagick")))
    (when (not= (set (map first (rows))) (set (keys exp)))
      (swap! bad inc) (println "  FAIL expect.edn and the row list name different files"))
    (if (zero? @bad)
      (println "  ok   " (count exp) "image fixtures say to ImageMagick what test/image/expect.edn says")
      (System/exit 1))))

(when-not (System/getenv "IMGFIX_LIB") (if check? (check-all) (write-all)))
