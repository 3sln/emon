;; Make the P2.7 benchmark page: one scanned 300-dpi A4 page as raw 8-bit gray.
;;
;;     bb bench/mkpage.clj [SRC.png] [OUT]
;;
;; The default source is corpus/formats/derived/inv_00007.noise.png: a scanned
;; invoice page (katanaml-invoices) with scan noise added, 2481x3508 px = A4 at
;; 300 dpi (corpus/ is untracked; see corpus/MANIFEST.md). The output is
;; tightly packed, row-major, top-left: width*height bytes, no header. The
;; "W H" goes to stdout and to OUT.size. The raw file is 8.7 MB and is NOT
;; committed (bench/ keeps data/ untracked).
;;
;; Decoding is ImageMagick's (`magick`), called from here: luma, 8 bit.
(require '[babashka.process :refer [shell]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(let [[src out] *command-line-args*
      src (or src "corpus/formats/derived/inv_00007.noise.png")
      out (or out "bench/data/page.gray")
      _ (io/make-parents out)
      [w h] (map parse-long
                 (str/split (str/trim (:out (shell {:out :string} "magick" "identify" "-format" "%w %h" src)))
                            #" "))]
  (shell "magick" src "-colorspace" "Gray" "-depth" "8" (str "gray:" out))
  (assert (= (.length (io/file out)) (* w h)) "raw size is not w*h")
  (spit (str out ".size") (str w " " h "\n"))
  (println w h))
