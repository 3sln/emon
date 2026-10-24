(ns synth.raster-fixtures
  "The raster fixtures of P12.3 (DEVLOG D564-D565): small images and PDFs of ONE text page, for the raster and OCR pipeline's laws and replay fixtures.

      bb -cp tools:tools/lib tools/synth/raster_fixtures.clj make     write test/raster/
      bb -cp tools:tools/lib tools/synth/raster_fixtures.clj check    the PDFs hold the pixels of the committed PNG (the PNGs themselves are authoritative: their bytes
                                                                      are poppler's and ImageMagick's, as `synth.degrade`'s scans are; without `magick` it SKIPS loudly)

  The page is the first page of the synthetic `test/corpus/synth/pdf/h-plain.pdf` (a heading, headed paragraphs, 10 point body type) rendered by poppler's
  `pdftoppm`, in grey:

    h-plain-72dpi.png      at 72 dpi, with NO stated resolution (the metadata is stripped): body type has an x-height of about 5 pixels, a quarter of what the engine
                           reads well (P12.3 I-3: an engine that is not told so loses words and decimal points, and says nothing)
    h-plain-rot90.png      at 150 dpi, turned a quarter turn clockwise (a page fed sideways; I-5)
    h-plain-rot180.png     at 150 dpi, turned a half turn (a page fed upside down; I-5)
    receipt-photo.png     the 72 dpi page with a coloured surround (D709: receipt-classified input, from public synthetic pixels)
    receipt-empty.png     a larger blank white page with the same surround (D709: all-empty receipt PSM 11 rescue)
    flate-gray-72dpi.pdf   the 72 dpi page as ONE FlateDecode image covering a PDF page and nothing else (I-4: a page read as `vector`, no OCR, nothing in its body)
    runlength-gray-72dpi.pdf   the same as a RunLengthDecode image

  The pixels of a PDF are the PNG's (`magick PNG gray:-`); the PDFs are written byte for byte here (`synth.pdfw`), no PDF library."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [synth.pdfw :as w]))

(def OUT "test/raster")
(def SOURCE "test/corpus/synth/pdf/h-plain.pdf")

(defn sh! [& cmd]
  (let [r (apply p/sh {:out :bytes :err :string} cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "command failed: " (str/join " " cmd) "\n" (:err r)) r)))
    (:out r)))

(defn have-magick? [] (some? (try (sh! "magick" "-version") (catch Exception _ nil))))

(defn render! [dpi out]
  (let [tmp (str (java.nio.file.Files/createTempDirectory "emon-raster" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (sh! "pdftoppm" "-r" (str dpi) "-gray" "-png" "-f" "1" "-l" "1" SOURCE (str tmp "/p"))
      (let [f (first (sort (filter #(str/ends-with? % ".png") (.list (io/file tmp)))))]
        (sh! "magick" (str tmp "/" f) "-strip" "-depth" "8" out))
      (finally
        (doseq [f (.listFiles (io/file tmp))] (.delete f))
        (.delete (io/file tmp))))))

(defn rotate! [src degrees out]
  (sh! "magick" src "-rotate" (str degrees) "+repage" "-strip" "-depth" "8" out))

(defn gray-of [png]
  {:raw (sh! "magick" png "-depth" "8" "gray:-")
   :wh (mapv parse-long (str/split (String. ^bytes (sh! "magick" "identify" "-format" "%w %h" png)) #"\s+"))})

(defn deflate ^bytes [^bytes raw]
  (let [d (java.util.zip.Deflater. 9)
        out (java.io.ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (.setInput d raw)
    (.finish d)
    (while (not (.finished d))
      (let [n (.deflate d buf)] (.write out buf 0 n)))
    (.end d)
    (.toByteArray out)))

(defn run-length ^bytes [^bytes raw]
  (let [out (java.io.ByteArrayOutputStream.)
        n (alength raw)]
    (loop [i 0]
      (when (< i n)
        (let [b (aget raw i)
              run (loop [j (inc i)] (if (and (< j n) (< (- j i) 128) (= (aget raw j) b)) (recur (inc j)) (- j i)))]
          (if (>= run 3)
            (do (.write out (- 257 run)) (.write out (bit-and b 0xff)) (recur (+ i run)))
            (let [end (loop [j i]
                        (if (and (< j n) (< (- j i) 128)
                                 (not (and (< (+ j 2) n) (= (aget raw j) (aget raw (+ j 1))) (= (aget raw j) (aget raw (+ j 2))))))
                          (recur (inc j))
                          j))
                  end (max end (inc i))]
              (.write out (dec (- end i)))
              (.write out raw i (- end i))
              (recur end))))))
    (.write out 128)
    (.toByteArray out)))

(defn page-pdf
  "One page holding the grey image as its only object, `dpi` its resolution."
  ^bytes [{:keys [raw wh]} filter-name ^bytes data dpi]
  (let [[iw ih] wh
        pw (/ (* iw 72.0) dpi)
        ph (/ (* ih 72.0) dpi)
        content (str "q " (w/num* pw) " 0 0 " (w/num* ph) " 0 0 cm /Im0 Do Q")]
    (w/build-pdf
     {1 "<< /Type /Catalog /Pages 2 0 R >>"
      2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
      3 (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " (w/num* pw) " " (w/num* ph) "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>")
      4 [:stream (str "/Type /XObject /Subtype /Image /Width " iw " /Height " ih " /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /" filter-name)
         (String. data "ISO-8859-1")]
      5 [:stream "" content]})))

(defn make-pdfs []
  (let [g (gray-of (str OUT "/h-plain-72dpi.png"))]
    {"flate-gray-72dpi.pdf" (page-pdf g "FlateDecode" (deflate (:raw g)) 72)
     "runlength-gray-72dpi.pdf" (page-pdf g "RunLengthDecode" (run-length (:raw g)) 72)}))

(defn receipt-images []
  ;; D709: synthetic only; no private receipt pixels enter replay fixtures.
  ;; A coloured surround leaves the white page inside the detector's paper-share bounds.
  (let [[iw ih] (:wh (gray-of (str OUT "/h-plain-72dpi.png")))
        border ["-bordercolor" "#604020" "-border" "120x120" "-strip" "-depth" "8"]]
    {"receipt-photo.png" (into [(str OUT "/h-plain-72dpi.png")] border)
     "receipt-empty.png" (into ["-size" (str (* 2 iw) "x" (* 2 ih)) "xc:white"] border)}))

(defn receipt-check! []
  (doseq [[name args] (receipt-images)]
    (when-not (java.util.Arrays/equals
               ^bytes (apply sh! (concat ["magick"] args ["rgb:-"]))
               ^bytes (sh! "magick" (str OUT "/" name) "-depth" "8" "rgb:-"))
      (throw (ex-info (str "receipt fixture pixels differ: " name) {}))))
  (println "  ok   raster-fixtures: receipt photo and empty controls derive from the synthetic page"))

(defn -main [& [cmd]]
  (case cmd
    "make" (do (io/make-parents (str OUT "/x"))
               (render! 72 (str OUT "/h-plain-72dpi.png"))
               (let [tmp (str OUT "/.tmp-150.png")]
                 (render! 150 tmp)
                 (rotate! tmp 90 (str OUT "/h-plain-rot90.png"))
                 (rotate! tmp 180 (str OUT "/h-plain-rot180.png"))
                 (.delete (io/file tmp)))
               (doseq [[name args] (receipt-images)]
                 (apply sh! (concat ["magick"] args [(str OUT "/" name)])))
               (doseq [[n bs] (make-pdfs)]
                 (w/write-bytes! (str OUT "/" n) bs)
                 (println "  wrote" n (alength ^bytes bs) "bytes")))
    "check" (if-not (have-magick?)
              (println "  SKIP raster-fixtures --check: magick is needed (test/raster is read as committed)")
              (let [_ (receipt-check!)
                    g (gray-of (str OUT "/h-plain-72dpi.png"))
                    inflate (fn [^bytes z]
                              (let [i (java.util.zip.Inflater.) out (java.io.ByteArrayOutputStream.) buf (byte-array 65536)]
                                (.setInput i z)
                                (while (not (.finished i)) (let [n (.inflate i buf)] (when (zero? n) (throw (ex-info "short" {}))) (.write out buf 0 n)))
                                (.toByteArray out)))
                    stream-of (fn [^bytes pdf]
                                (let [s (String. pdf "ISO-8859-1")
                                      a (+ (.indexOf s "stream\n") 7)
                                      b (.indexOf s "\nendstream" a)]
                                  (.getBytes (subs s a b) "ISO-8859-1")))
                    unrun (fn [^bytes z]
                            (let [out (java.io.ByteArrayOutputStream.)]
                              (loop [i 0]
                                (let [n (bit-and (aget z i) 0xff)]
                                  (cond (= n 128) nil
                                        (< n 128) (do (.write out z (inc i) (inc n)) (recur (+ i n 2)))
                                        :else (do (dotimes [_ (- 257 n)] (.write out (int (bit-and (aget z (inc i)) 0xff)))) (recur (+ i 2))))))
                              (.toByteArray out)))
                    read-pdf #(java.nio.file.Files/readAllBytes (.toPath (io/file (str OUT "/" %))))
                    ok? (and (java.util.Arrays/equals ^bytes (inflate (stream-of (read-pdf "flate-gray-72dpi.pdf"))) ^bytes (:raw g))
                             (java.util.Arrays/equals ^bytes (unrun (stream-of (read-pdf "runlength-gray-72dpi.pdf"))) ^bytes (:raw g)))]
                (if ok?
                  (println "  ok   raster-fixtures: the Flate and RunLength PDFs hold the pixels of test/raster/h-plain-72dpi.png")
                  (do (println "  FAIL raster-fixtures: a PDF does not hold the PNG's pixels") (System/exit 1)))))
    (do (println "usage: raster_fixtures.clj make|check") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
