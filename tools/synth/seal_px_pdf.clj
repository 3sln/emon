(ns synth.seal-px-pdf
  "The fixtures of P8.2 (seals and stamps read from PIXELS, D800-D806): born-digital pages whose seals and stamps are RASTER IMAGES, so that nothing about them is in
  the text layer or the vector paths (`emon.sealmark` cannot see them) and only the tracer's primitives, the polar unwarp and a targeted OCR of the strip can.

      bb -cp tools:tools/lib tools/synth/seal_px_pdf.clj            write test/sealpx/pdf/*.pdf and test/sealpx/gold.edn   (needs pdftoppm and ImageMagick)
      bb -cp tools:tools/lib tools/synth/seal_px_pdf.clj --check    fail if a committed file is not what this writes (the JPEGs are committed inside the PDFs: the check
                                                                    compares the page streams and gold, not the pixels)

  Each artwork is a small PDF page of the kind `seal_pdf.clj` writes (a ring, its arc text glyph by glyph, a centre label), rasterised at 200 dpi and embedded
  as a DCT image drawn with the blend mode Multiply, so that its white is transparent and the paragraph under it is visible:

    px-seal-notary   a BLUE double ring, `CITY OF MILLBROOK - NOTARY PUBLIC -` round it, `SEAL` and a date in the centre, over the end of a paragraph
    px-stamp-paid    a RED rotated stamp, `APPROVED` and `2026-05-09`, over two paragraphs
    px-seal-black    a BLACK double ring, `STATE OF OHIO - NOTARY PUBLIC -`, over a paragraph's end
    px-negatives     NO seal: a solid blue disc (an image of a chart's pie), a blue-bordered empty box (a picture frame), a gray gradient bar. Everything is body text."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [synth.pdfw :as w :refer [pstr num*]]))

(def OUT "test/sealpx")
(def words (str/split "school house mother water river morning garden letter window summer winter paper table city story friend family number answer problem question picture example country evening machine market office season system teacher village weather yellow bridge button candle dinner engine forest ground hammer island jacket kitchen lesson mirror napkin orange pencil rabbit shadow ticket valley wagon" #" "))

(defn lines [seed n k]
  (let [r (java.util.Random. seed)]
    (vec (repeatedly n #(str/join " " (repeatedly k (fn [] (nth words (.nextInt r (count words))))))))))

(defn txt [font size x y s] (str "BT /" font " " (num* size) " Tf " (num* x) " " (num* y) " Td " (pstr s) " Tj ET\n"))
(defn txt-m [font size x y deg s]
  (let [r (Math/toRadians deg) c (Math/cos r) sn (Math/sin r)]
    (str "BT /" font " " (num* size) " Tf " (num* c) " " (num* sn) " " (num* (- sn)) " " (num* c) " " (num* x) " " (num* y) " Tm " (pstr s) " Tj ET\n")))
(defn paragraph [x y lns] (apply str (map-indexed (fn [i s] (txt "F1" 11 x (- y (* i 15)) s)) lns)))

(defn circle [cx cy r]
  (let [n 40 k (/ (* 4.0 (Math/tan (/ Math/PI (* 2 n)))) 3.0)
        pt (fn [a] [(+ cx (* r (Math/cos a))) (+ cy (* r (Math/sin a)))])
        d (fn [a] [(- (Math/sin a)) (Math/cos a)])]
    (str (let [[x y] (pt 0.0)] (str (num* x) " " (num* y) " m\n"))
         (apply str (for [i (range n)
                          :let [a0 (* i (/ (* 2 Math/PI) n)) a1 (* (inc i) (/ (* 2 Math/PI) n))
                                [x0 y0] (pt a0) [x1 y1] (pt a1) [dx0 dy0] (d a0) [dx1 dy1] (d a1)
                                h (* r k)]]
                      (str (num* (+ x0 (* h dx0))) " " (num* (+ y0 (* h dy0))) " "
                           (num* (- x1 (* h dx1))) " " (num* (- y1 (* h dy1))) " "
                           (num* x1) " " (num* y1) " c\n")))
         "h\n")))

(defn arc-text [font size cx cy r start step s]
  (apply str
         (map-indexed (fn [i ch]
                        (if (= ch \space)
                          ""
                          (let [a (- start (* i step)) rad (Math/toRadians a)
                                x (+ cx (* r (Math/cos rad))) y (+ cy (* r (Math/sin rad)))]
                            (txt-m font size x y (- a 90) (str ch)))))
                      s)))

(def ink-black "0.1 0.1 0.1")
(def ink-blue "0.1216 0.2471 0.6235")
(def ink-red "0.7529 0.1255 0.1216")

;; ---------------------------------------------------------------- the artworks: [stream-string, width pt, height pt]

(def artworks
  {:notary [(str ink-blue " rg " ink-blue " RG\n"
                 "2.6 w\n" (circle 75 75 68) "S\n"
                 "1 w\n" (circle 75 75 48) "S\n"
                 (arc-text "F2" 10 75 75 56 200 9.5 "CITY OF MILLBROOK - NOTARY PUBLIC -")
                 (txt "F2" 14 52 66 "SEAL")
                 (txt "F1" 8 49 52 "2026-04-11"))
            150 150]
   :stamp [(str ink-red " rg " ink-red " RG\n"
                "3 w 3 3 164 64 re S\n"
                "1 w 7 7 156 56 re S\n"
                (txt "F2" 24 14 38 "APPROVED")
                (txt "F2" 14 14 14 "2026-05-09"))
           170 70]
   :black [(str ink-black " rg " ink-black " RG\n"
                "2.6 w\n" (circle 75 75 68) "S\n"
                "1 w\n" (circle 75 75 48) "S\n"
                (arc-text "F2" 10 75 75 56 200 9.5 "STATE OF OHIO - NOTARY PUBLIC -"))
           150 150]
   :disc [(str ink-blue " rg\n" (circle 60 60 50) "f\n") 120 120]
   :frame [(str ink-blue " RG 4 w 6 6 148 88 re S\n") 160 100]
   :bar [(str "0.6 g 0 0 200 14 re f 0.8 g 0 14 200 14 re f\n") 200 28]})

(defn artwork-pdf [[stream w h]]
  (w/build-pdf
   {1 "<< /Type /Catalog /Pages 2 0 R >>"
    2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    3 (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " w " " h "] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> >>")
    4 [:stream "" stream]
    8 (w/font-objs 8)
    9 (w/font-objs 9)}))

(defn jpeg-of
  "The artwork rasterised at 200 dpi as JPEG bytes: [bytes width height]."
  [k]
  (let [tmp (str (fs/create-temp-dir {:prefix "emon-sealpx."}))
        pdf (str tmp "/a.pdf")]
    (w/write-bytes! pdf (artwork-pdf (artworks k)))
    (shell "pdftoppm" "-r" "200" "-png" "-singlefile" pdf (str tmp "/a"))
    (shell "magick" (str tmp "/a.png") "-type" "TrueColor" "-quality" "92" "-sampling-factor" "4:4:4" (str tmp "/a.jpg"))
    (let [bs (fs/read-all-bytes (str tmp "/a.jpg"))
          dims (str/split (str/trim (:out (shell {:out :string} "magick" "identify" "-format" "%w %h" (str tmp "/a.jpg")))) #" ")]
      (fs/delete-tree tmp)
      [bs (parse-long (first dims)) (parse-long (second dims))])))

(def body-1 (lines 21 6 8))
(def body-2 (lines 22 6 8))
(def body-3 (lines 23 5 8))
(def body-4 (lines 24 5 8))

;; each doc: its content stream, the artworks it places [key obj-number x y w h rotation-degrees], gold
(def docs
  [{:id "px-seal-notary"
    :stream (str "0.1 g 0.1 G\n"
                 (txt "F2" 16 72 720 "Notary Certificate")
                 (paragraph 72 690 body-1)
                 (paragraph 72 585 body-2)
                 (paragraph 72 470 body-3)
                 "q /GM gs 1 0 0 1 0 0 cm\n" "q 150 0 0 150 290 520 cm /Im1 Do Q Q\n")
    :images [{:key :notary :x 290 :y 520 :w 150 :h 150}]
    :body (concat ["Notary Certificate"] body-1 body-2 body-3)
    :nodes [{:subkind "seal" :color "blue" :name "CITY OF MILLBROOK" :date "2026-04-11" :text "CITY OF MILLBROOK NOTARY PUBLIC SEAL 2026-04-11"}]
    :leak ["MILLBROOK" "NOTARY" "PUBLIC" "SEAL"]}

   {:id "px-stamp-paid"
    :stream (str "0.1 g 0.1 G\n"
                 (txt "F2" 16 72 720 "Delivery Note")
                 (paragraph 72 690 body-1)
                 (paragraph 72 565 body-2)
                 (paragraph 72 440 body-3)
                 "q /GM gs q 170 0 0 70 250 585 cm /Im1 Do Q Q\n")
    :images [{:key :stamp :x 250 :y 585 :w 170 :h 70}]
    :body (concat ["Delivery Note"] body-1 body-2 body-3)
    :nodes [{:subkind "stamp" :color "red" :date "2026-05-09" :text "APPROVED 2026-05-09"}]
    :leak ["APPROVED"]}

   {:id "px-seal-black"
    :stream (str "0.1 g 0.1 G\n"
                 (txt "F2" 16 72 720 "Affidavit")
                 (paragraph 72 690 body-1)
                 (paragraph 72 585 body-2)
                 (paragraph 72 470 body-4)
                 "q /GM gs q 150 0 0 150 300 520 cm /Im1 Do Q Q\n")
    :images [{:key :black :x 300 :y 520 :w 150 :h 150}]
    :body (concat ["Affidavit"] body-1 body-2 body-4)
    :nodes [{:subkind "seal" :color "black" :name "STATE OF OHIO" :text "STATE OF OHIO NOTARY PUBLIC"}]
    :leak ["STATE" "OHIO" "NOTARY" "PUBLIC"]}

   {:id "px-negatives"
    :stream (str "0.1 g 0.1 G\n"
                 (txt "F2" 16 72 720 "Quarterly Pictures")
                 (paragraph 72 690 body-1)
                 "q /GM gs q 120 0 0 120 90 520 cm /Im1 Do Q Q\n"
                 "q /GM gs q 160 0 0 100 270 530 cm /Im2 Do Q Q\n"
                 "q q 200 0 0 28 90 470 cm /Im3 Do Q Q\n"
                 (paragraph 72 420 body-3))
    :images [{:key :disc :x 90 :y 520 :w 120 :h 120} {:key :frame :x 270 :y 530 :w 160 :h 100} {:key :bar :x 90 :y 470 :w 200 :h 28}]
    :body (concat ["Quarterly Pictures"] body-1 body-3)
    :nodes []
    :leak []}])

(defn latin1-str [^bytes bs] (String. bs "ISO-8859-1"))

(defn pdf [{:keys [stream images]}]
  (let [jp (mapv (comp jpeg-of :key) images)
        objs (merge
              {1 "<< /Type /Catalog /Pages 2 0 R >>"
               2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
               3 (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> "
                      "/ExtGState << /GM 5 0 R >> /XObject << "
                      (apply str (map-indexed (fn [i _] (str "/Im" (inc i) " " (+ 10 i) " 0 R ")) images)) ">> >> >>")
               4 [:stream "" stream]
               5 "<< /Type /ExtGState /BM /Multiply >>"
               8 (w/font-objs 8)
               9 (w/font-objs 9)}
              (into {} (map-indexed (fn [i [bs iw ih]]
                                      [(+ 10 i) [:stream (str "/Type /XObject /Subtype /Image /Width " iw " /Height " ih
                                                              " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode")
                                                 (latin1-str bs)]])
                                    jp)))]
    (w/build-pdf objs)))

(defn gold []
  (into (sorted-map)
        (for [{:keys [id body nodes leak]} docs]
          [id {:body (vec (mapcat #(str/split % #"\s+") body)) :nodes (vec nodes) :leak (vec leak)}])))

(defn gold-text [] (str ";; The gold of the P8.2 fixtures (tools/synth/seal_px_pdf.clj): the body words, the nodes expected (subkind, colour, name, date, text) and the words that must not be in the body.\n"
                        (with-out-str (pp/pprint (gold)))))

(let [check? (some #{"--check"} *command-line-args*)]
  (fs/create-dirs (str OUT "/pdf"))
  (if check?
    (let [f (str OUT "/gold.edn")]
      (System/exit (if (= (gold-text) (slurp f)) 0 (do (println "FAIL" f "is not what seal_px_pdf.clj writes") 1))))
    (do (doseq [d docs]
          (w/write-bytes! (str OUT "/pdf/" (:id d) ".pdf") (pdf d)))
        (spit (str OUT "/gold.edn") (gold-text))
        (println "wrote" OUT))))
