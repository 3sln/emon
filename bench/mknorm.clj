;; Make the P6.3 benchmark's RGB page: page 1 of the synthetic `watermark-draft` document, rendered at 300 dpi.
;;
;;     bb bench/mknorm.clj [SRC.pdf] [OUT]
;;
;; Output: tightly packed 8-bit RGB (width*height*3 bytes, row-major, top-left), `OUT.size` holds "W H". Rendering is
;; poppler's (`pdftoppm`) and the pixel dump ImageMagick's, both called from here; the file is NOT committed (bench/data
;; is untracked), like `bench/mkpage.clj`'s.
(require '[babashka.process :refer [shell]]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(let [[src out] *command-line-args*
      src (or src "test/corpus/synth/pdf/watermark-draft.pdf")
      out (or out "bench/data/wm.rgb")
      _ (io/make-parents out)
      tmp (str out ".png")]
  (shell "pdftoppm" "-r" "300" "-f" "1" "-l" "1" "-png" "-singlefile" src (str/replace tmp #"\.png$" ""))
  (let [[w h] (map parse-long (str/split (str/trim (:out (shell {:out :string} "magick" "identify" "-format" "%w %h" tmp))) #" "))]
    (shell "magick" tmp "-depth" "8" (str "rgb:" out))
    (assert (= (.length (io/file out)) (* w h 3)) "raw size is not w*h*3")
    (spit (str out ".size") (str w " " h "\n"))
    (io/delete-file tmp)
    (println w h)))
