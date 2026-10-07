(ns synth.seal-pdf
  "The fixtures of P7.7 (seals, stamps and signatures, D314-D319): four born-digital pages written byte for byte, no PDF producer and no library
  (D094), with the seal drawn as VECTOR paths and its text in the TEXT LAYER, which is where `emon.sealmark` reads it.

      bb -cp tools:tools/lib tools/synth/seal_pdf.clj            write test/seals/pdf/*.pdf and test/seals/gold.edn
      bb -cp tools:tools/lib tools/synth/seal_pdf.clj --check    fail if a committed file is not what this writes

    seal-black-ring    a BLACK double ring over the end of a paragraph, its arc text set glyph by glyph (a rotation each); no colour tells it from the
                       text under it, only the glyphs turned along the ring do. The paragraph under it is body text.
    stamp-translucent  a RED rotated stamp (a 3 pt border and two lines of bold red text) drawn at alpha 0.6 over two paragraphs: their lines
                       stay body text, the stamp's words are the stamp's.
    sig-by-line        a blue pen signature (two Bezier strokes) over the rule under `By:`; the typed name under the rule is body text.
    seal-negatives     NO seal: a blue-bordered callout holding black body text, a black circle with its label (a diagram node), a blue line chart
                       far from the word `Signed`, and a second, dashed black ring. Everything on the page is body text.

  The gold (`test/seals/gold.edn`): per document the body words (every one must be in the body), the nodes expected (subkind, colour, text) and the words
  that must NOT be in the body."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [synth.pdfw :as w :refer [pstr num*]]))

(def OUT "test/seals")
(def words (str/split "school house mother water river morning garden letter window summer winter paper table city story friend family number answer problem question picture example country evening machine market office season system teacher village weather yellow bridge button candle dinner engine forest ground hammer island jacket kitchen lesson mirror napkin orange pencil rabbit shadow ticket valley wagon" #" "))

(defn lines [seed n k]
  (let [r (java.util.Random. seed)]
    (vec (repeatedly n #(str/join " " (repeatedly k (fn [] (nth words (.nextInt r (count words))))))))))

(defn txt [font size x y s] (str "BT /" font " " (num* size) " Tf " (num* x) " " (num* y) " Td " (pstr s) " Tj ET\n"))
(defn txt-m
  "Text at the angle `deg` (degrees, counter-clockwise), its origin at x y."
  [font size x y deg s]
  (let [r (Math/toRadians deg) c (Math/cos r) sn (Math/sin r)]
    (str "BT /" font " " (num* size) " Tf " (num* c) " " (num* sn) " " (num* (- sn)) " " (num* c) " " (num* x) " " (num* y) " Tm " (pstr s) " Tj ET\n")))

(defn paragraph [x y lns] (apply str (map-indexed (fn [i s] (txt "F1" 11 x (- y (* i 15)) s)) lns)))

(defn circle
  "A circle of 40 Bezier arcs (Chrome draws one in about forty segments), stroked."
  [cx cy r]
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

(defn arc-text
  "The glyphs of `s` one by one along a circle of radius r about (cx, cy), clockwise from `start` degrees (math angle, 90 is the top), `step` degrees apart,
  each turned to stand on the ring; a space only advances."
  [font size cx cy r start step s]
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

(defn page [& parts] (apply str parts))

(def body-1 (lines 11 7 8))
(def body-2 (lines 12 6 8))
(def body-3 (lines 13 5 8))
(def body-4 (lines 14 4 8))

(def docs
  [{:id "seal-black-ring"
    :stream (page "0.1 g 0.1 G\n"
                  (txt "F2" 16 72 720 "Notary Certificate")
                  (paragraph 72 690 body-1)
                  (paragraph 72 575 body-2)
                  ;; the seal: a double black ring over the right end of the second paragraph, black arc text, no centre text
                  "2.5 w\n" (circle 440 590 62) "S\n"
                  "1 w\n" (circle 440 590 44) "S\n"
                  (arc-text "F2" 10 440 590 51 160 11 "STATE OF OHIO NOTARY PUBLIC")
                  (paragraph 72 450 body-3))
    :body (concat ["Notary Certificate"] body-1 body-2 body-3)
    :nodes [{:subkind "seal" :color "black" :text "STATE OF OHIO NOTARY PUBLIC"}]
    :leak ["STATE" "OHIO" "NOTARY" "PUBLIC"]}

   {:id "stamp-translucent"
    :stream (page "0.1 g 0.1 G\n"
                  (txt "F2" 16 72 720 "Delivery Note")
                  (paragraph 72 690 body-1)
                  (paragraph 72 560 body-2)
                  ;; the stamp at alpha 0.6 over the paragraphs: a rotated frame and two lines of bold red text
                  "q /G6 gs " ink-red " rg " ink-red " RG 3 w\n"
                  "0.99 -0.14 0.14 0.99 330 600 cm\n"
                  "0 0 150 62 re S\n"
                  (txt "F2" 24 14 36 "RECEIVED")
                  (txt "F2" 12 14 14 "04 MAR 2026")
                  "Q\n"
                  (paragraph 72 440 body-3))
    :body (concat ["Delivery Note"] body-1 body-2 body-3)
    :nodes [{:subkind "stamp" :color "red" :text "RECEIVED 04 MAR 2026"}]
    :leak ["RECEIVED" "MAR" "2026"]}

   {:id "sig-by-line"
    :stream (page "0.1 g 0.1 G\n"
                  (txt "F2" 16 72 720 "Agreement")
                  (paragraph 72 690 body-1)
                  (paragraph 72 590 (concat ["Agreed and accepted by the parties."] body-4))
                  (txt "F1" 11 72 430 "By:")
                  "0.6 G 0.8 w 100 428 m 300 428 l S\n"
                  (txt "F1" 11 100 412 "Alex Morgan, Director")
                  ;; the pen signature: two strokes in blue ink over the rule, crossing the line
                  ink-blue " RG 1.4 w 1 J\n"
                  "104 424 m 112 452 124 408 134 436 c 144 462 156 410 170 434 c 184 456 194 416 214 438 c 232 452 240 428 262 436 c S\n"
                  "1.1 w 110 420 m 160 414 220 424 276 418 c S\n")
    :body (concat ["Agreement"] body-1 ["Agreed and accepted by the parties."] body-4 ["By:" "Alex Morgan, Director"])
    :nodes [{:subkind "signature" :color "blue" :text ""}]
    :leak []}

   {:id "seal-negatives"
    :stream (page "0.1 g 0.1 G\n"
                  (txt "F2" 16 72 720 "Process Overview")
                  (paragraph 72 690 body-1)
                  ;; a callout: a blue 2 pt border holding black text (its ink is the body's)
                  ink-blue " RG 2 w 72 560 220 70 re S\n" "0.1 g\n"
                  (txt "F1" 11 82 612 "Note that every step is checked")
                  (txt "F1" 11 82 596 "by two people before it is filed")
                  (txt "F1" 11 82 580 "and again once it is closed")
                  ;; a diagram node: a black circle with its label
                  "0.1 G 1.5 w\n" (circle 400 590 40) "S\n"
                  (txt "F1" 12 382 586 "Start")
                  ;; a dashed ring
                  "[4 3] 0 d 1 w\n" (circle 400 470 38) "S\n[] 0 d\n"
                  (txt "F1" 11 380 466 "Phase")
                  ;; a line chart in blue, no cue near it
                  ink-blue " RG 1.5 w 80 330 m 120 360 150 300 190 340 c 220 372 250 310 300 350 c S\n"
                  "0.1 G 0.8 w 80 300 m 320 300 l S 80 300 m 80 390 l S\n"
                  (txt "F1" 9 80 288 "Q1")
                  (txt "F1" 9 180 288 "Q2")
                  (txt "F1" 9 280 288 "Q3")
                  "0.1 g\n"
                  (paragraph 72 230 body-3)
                  (txt "F1" 11 72 120 "Signed copies are kept in the archive."))
    :body (concat ["Process Overview"] body-1
                  ["Note that every step is checked" "by two people before it is filed" "and again once it is closed" "Start" "Phase"]
                  body-3 ["Signed copies are kept in the archive."])
    :nodes []
    :leak []}])

(defn pdf [{:keys [stream]}]
  (w/build-pdf
   {1 "<< /Type /Catalog /Pages 2 0 R >>"
    2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> /ExtGState << /G6 5 0 R >> >> >>"
    4 [:stream "" stream]
    5 "<< /Type /ExtGState /ca 0.6 /CA 0.6 >>"
    8 (w/font-objs 8)
    9 (w/font-objs 9)}))

(defn gold []
  (into (sorted-map)
        (for [{:keys [id body nodes leak]} docs]
          [id {:body (vec (mapcat #(str/split % #"\s+") body)) :nodes (vec nodes) :leak (vec leak)}])))

(defn gold-text [] (str ";; The gold of the P7.7 fixtures (tools/synth/seal_pdf.clj): the body words, the nodes expected and the words that must not be in the body.\n"
                        (with-out-str (pp/pprint (gold)))))

(let [check? (some #{"--check"} *command-line-args*)
      bad (atom 0)]
  (fs/create-dirs (str OUT "/pdf"))
  (doseq [d docs]
    (let [f (str OUT "/pdf/" (:id d) ".pdf") bs (pdf d)]
      (if check?
        (when-not (w/same-bytes? f bs) (swap! bad inc) (println "FAIL" f "is not what seal_pdf.clj writes"))
        (w/write-bytes! f bs))))
  (let [f (str OUT "/gold.edn") s (gold-text)]
    (if check?
      (when-not (= s (slurp f)) (swap! bad inc) (println "FAIL" f "is not what seal_pdf.clj writes"))
      (spit f s)))
  (if check?
    (System/exit (if (zero? @bad) 0 1))
    (println "wrote" OUT)))
