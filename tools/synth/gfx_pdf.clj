(ns synth.gfx-pdf
  "The seven hand-made GRAPHICS PDFs of P6.8 (D227), written byte for byte, no PDF producer and no library (D094):

      bb -cp tools:tools/lib tools/synth/gfx_pdf.clj            write pdf/, html/ and gold/*.rules-gen.json of the seven
      bb -cp tools:tools/lib tools/synth/gfx_pdf.clj --check    fail if a committed file is not what this writes (`bb unit`)

  Every document is prose, a table and prose; what differs is HOW the table's lines and fills are drawn, which is what
  P5.5a's vector reader and P7.G's fusion must see through. The gold of each is the table's HTML (`html/STEM.html`,
  which this writes too: the HTML IS the truth, as for the Chrome documents, and html2gold reads it as ever); the PDF is
  never printed (print.sh's HAND list).

    gfx-onepath-grid     page 1 a 5 x 4 grid as ONE path of `m l` pairs; page 2 a 12 x 10 grid as one path of 1,100
                         points (550 segments), truncated by design (`pg` flag `truncated`, D219): still a table
                         through its box and the tracer;
    gfx-hidden-rules     visible rules make 3 x 3. The hidden rules, if drawn, make 4 x 4: one extra vertical and one
                         extra horizontal, each drawn in three pieces, and each piece hidden another way: clipped
                         away, in an optional content group that is off, white on white, painted over by a later
                         white rectangle, at alpha 0;
    gfx-shading-bands    zebra bands as axial `sh` shadings, no rules;
    gfx-pattern-bands    zebra bands as tiling-pattern fills, no rules;
    gfx-curve-lines      a ruled table whose rules are `c` curves with collinear control points;
    gfx-image-rules      a ruled table whose rules are one-pixel images stretched to one point;
    gfx-softmask-rule    a ruled table plus one rule under a luminosity soft mask that hides it.

  RULE TRUTH is `tools/oracle/rules.py` (pdfplumber, filtered by poppler's render). pdfplumber does not see shadings,
  patterns or images, so this writes `gold/STEM.rules-gen.json`: what the generator drew that pdfplumber cannot read
  (top-left page space, points). The oracle still keeps only what the render shows."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pyjson :as pj :refer [om]]
            [synth.make-html :as mh]
            [synth.pdfw :as w :refer [t num*]]))

(def CORPUS "test/corpus/synth")
(def PAGE-H 792)

;; ------------------------------------------------------------------ geometry

(defn grid
  "A table's geometry: column boundaries xs and row boundaries ys (PDF space, descending)."
  [x top colw rowh nrows]
  {:xs (vec (reductions + x colw)) :ys (vec (for [r (range (inc nrows))] (- top (* r rowh)))) :rowh rowh :x x :top top
   :w (reduce + colw) :h (* rowh nrows) :n nrows})

(defn cell-ops
  "The text of a table: row 0 bold. `size` pt, left aligned 6 pt inside the column."
  [g data size]
  (str/join "\n"
            (for [[r row] (map-indexed vector data) [c s] (map-indexed vector row)]
              (t (if (zero? r) :F2 :F1) size (+ (nth (:xs g) c) 6)
                 (- (nth (:ys g) r) (/ (:rowh g) 2) (* size 0.35)) s))))

(defn hrule [g r & [x0 x1]] (str (num* (or x0 (first (:xs g)))) " " (num* (nth (:ys g) r)) " m " (num* (or x1 (last (:xs g)))) " " (num* (nth (:ys g) r)) " l"))
(defn vrule [g c & [y0 y1]] (str (num* (nth (:xs g) c)) " " (num* (or y0 (first (:ys g)))) " m " (num* (nth (:xs g) c)) " " (num* (or y1 (last (:ys g)))) " l"))

(defn full-grid-path
  "Every horizontal and vertical rule of `g` as m l pairs of ONE path (no operator between)."
  [g]
  (str/join " " (concat (for [r (range (count (:ys g)))] (hrule g r))
                        (for [c (range (count (:xs g)))] (vrule g c)))))

(defn cell-rows-html [data profile-attrs]
  (str "<table " profile-attrs "><thead>" (mh/tr (first data) "th") "</thead><tbody>"
       (apply str (map #(mh/tr % "td") (rest data))) "</tbody></table>"))

;; ------------------------------------------------------------------ the page frame

(defn frame-ops
  "Title, two lines of prose above the table (at 690, 676) and two below it (`bottom` - 28, - 42)."
  [{:keys [title tsize before after bottom]}]
  (str/join "\n"
            (concat [(t :F2 tsize 72 720 title)]
                    (map-indexed (fn [i l] (t :F1 11 72 (- 690 (* 14 i)) l)) before)
                    (map-indexed (fn [i l] (t :F1 11 72 (- bottom 28 (* 14 i)) l)) after))))

(def TABLE-SMALL [["Item" "Qty" "Price"] ["Pens" "12" "3.40"] ["Paper" "4" "7.25"]])
(def TABLE-BANDS [["Item" "Qty" "Price"] ["Pens" "12" "3.40"] ["Paper" "4" "7.25"] ["Tape" "9" "2.10"]
                  ["Glue" "3" "4.80"] ["Clips" "20" "1.15"]])
(def TABLE-5x4 [["Region" "Q1" "Q2" "Q3" "Q4"] ["North" "120" "135" "150" "160"] ["South" "98" "104" "99" "112"]
                ["West" "75" "80" "91" "95"]])
(def TABLE-12x10 (into [(mapv #(str "C" %) (range 1 13))]
                       (for [r (range 9)] (mapv #(str (+ 100 (* r 12) %)) (range 12)))))

(def PROSE-A ["The table below lists the supplies ordered this quarter."
              "Quantities are in boxes and prices in dollars."])
(def PROSE-B ["Orders above fifty dollars ship free of charge."
              "Questions go to the purchasing office."])

(defn page-html [{:keys [h title before after table]}]
  (str "<h" h ">" title "</h" h "><p>" (str/join " " before) "</p>" table "<p>" (str/join " " after) "</p>"))

;; ------------------------------------------------------------------ the writer

(defn pdf
  "pages: [{:ops :res}], extra: {objnum body}, catalog: extra catalog entries."
  [pages extra catalog]
  (let [n (count pages)
        page-obj (fn [i] (+ 10 (* 2 i)))
        objs (merge w/font-objs extra
                    {1 (str "<< /Type /Catalog /Pages 2 0 R" catalog " >>")
                     2 (str "<< /Type /Pages /Kids [" (str/join " " (for [i (range n)] (str (page-obj i) " 0 R"))) "] /Count " n " >>")}
                    (into {} (mapcat (fn [i {:keys [ops res]}]
                                       [[(page-obj i) (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " (inc (page-obj i))
                                                           " 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> " (or res "") " >> >>")]
                                        [(inc (page-obj i)) [:stream "" ops]]])
                                     (range) pages)))]
    (w/build-pdf objs)))

;; extras: what pdfplumber cannot read (top-left page space, points)
(defn y-top [y] (- PAGE-H y))
(defn rule-gen [x0 y0 x1 y1 th] (om "x0" x0 "y0" (y-top y0) "x1" x1 "y1" (y-top y1) "t" th))
(defn fill-gen [x0 y0 x1 y1 hex] (om "box" [x0 (y-top y1) x1 (y-top y0)] "colour" hex))

(def BAND-RGB [221 232 245])
(def BAND-HEX "#dde8f5")
(defn rgb-frac [rgb] (str/join " " (map #(num* (/ (Math/round (* 10000.0 (/ % 255.0))) 10000.0)) rgb)))

;; ------------------------------------------------------------------ the seven documents

(defn onepath-grid []
  (let [g1 (grid 72 640 (repeat 5 80) 22 4)
        p1 {:title "Regional Sales" :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g1))}
        ops1 (str (frame-ops p1) "\n" (cell-ops g1 TABLE-5x4 10) "\n0.5 w " (full-grid-path g1) " S")
        ;; 12 x 10: every cell edge in two pieces (524 segments), 26 of them cut again: 550 segments, 1,100 points
        g2 (grid 48 640 (repeat 12 43) 24 10)
        edges (concat (for [r (range 11) c (range 12)] [(nth (:xs g2) c) (nth (:ys g2) r) (nth (:xs g2) (inc c)) (nth (:ys g2) r)])
                      (for [c (range 13) r (range 10)] [(nth (:xs g2) c) (nth (:ys g2) r) (nth (:xs g2) c) (nth (:ys g2) (inc r))]))
        halves (mapcat (fn [[x0 y0 x1 y1]] (let [mx (/ (+ x0 x1) 2.0) my (/ (+ y0 y1) 2.0)] [[x0 y0 mx my] [mx my x1 y1]])) edges)
        halves (vec halves)
        cut (mapcat (fn [i [x0 y0 x1 y1]]
                      (if (< i 26)
                        (let [mx (/ (+ x0 x1) 2.0) my (/ (+ y0 y1) 2.0)] [[x0 y0 mx my] [mx my x1 y1]])
                        [[x0 y0 x1 y1]]))
                    (range) halves)
        _ (assert (= 550 (count cut)) (count cut))
        path (str/join " " (for [[x0 y0 x1 y1] cut] (str (num* x0) " " (num* y0) " m " (num* x1) " " (num* y1) " l")))
        p2 {:title "Sample Readings" :tsize 14 :h 2 :before ["Readings in arbitrary units, twelve sensors, nine runs."] :after ["Sensor C7 was recalibrated after run five."]
            :bottom (last (:ys g2))}
        ops2 (str (frame-ops p2) "\n" (cell-ops g2 TABLE-12x10 7) "\n0.4 w " path " S")]
    {:pages [{:ops ops1} {:ops ops2}]
     :html (str (page-html (assoc p1 :table (cell-rows-html TABLE-5x4 "data-profile=\"ruled\"")))
                (page-html (assoc p2 :table (cell-rows-html TABLE-12x10 "data-profile=\"ruled\""))))
     :gen nil}))

(defn hidden-rules []
  (let [g (grid 72 640 [150 150 150] 22 3)
        p {:title "Office Supplies" :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g))}
        ys (:ys g)
        xmid (+ (first (:xs g)) 150 75)         ; inside column 2
        yh (- (first ys) 4)                     ; inside the header row, above its text
        seg-v (fn [r] (str (num* xmid) " " (num* (nth ys r)) " m " (num* xmid) " " (num* (nth ys (inc r))) " l"))
        seg-h (fn [c] (str (num* (+ 4 (nth (:xs g) c))) " " (num* yh) " m " (num* (- (nth (:xs g) (inc c)) 4)) " " (num* yh) " l"))
        visible (str "0.5 w " (full-grid-path g) " S")
        hidden (str/join "\n"
                         [;; extra vertical: row 1 clipped away, row 2 in an OCG that is off, row 3 white on white
                          (str "q 0 0 1 1 re W n " (seg-v 0) " S Q")
                          (str "/OC /oc1 BDC " (seg-v 1) " S EMC")
                          (str "q 1 G " (seg-v 2) " S Q")
                          ;; extra horizontal: col 1 painted over by a later white rectangle, col 2 at alpha 0, col 3 clipped away
                          (str (seg-h 0) " S")
                          (str "q 1 g " (num* (+ (first (:xs g)) 2)) " " (num* (- yh 1.5)) " " (num* 146) " 3 re f Q")
                          (str "q /GS0 gs " (seg-h 1) " S Q")
                          (str "q 0 0 1 1 re W n " (seg-h 2) " S Q")])
        ops (str (frame-ops p) "\n" (cell-ops g TABLE-SMALL 10) "\n" visible "\n" hidden)
        extra {100 "<< /Type /OCG /Name (Draft rules) >>"
               101 "<< /Type /ExtGState /CA 0 /ca 0 >>"}]
    {:pages [{:ops ops :res "/Properties << /oc1 100 0 R >> /ExtGState << /GS0 101 0 R >>"}]
     :extra extra
     :catalog " /OCProperties << /OCGs [100 0 R] /D << /Order [100 0 R] /OFF [100 0 R] >> >>"
     :html (page-html (assoc p :table (cell-rows-html TABLE-SMALL "data-profile=\"ruled\"")))}))

(defn bands-doc [title kind]
  (let [g (grid 72 640 [150 150 150] 22 6)
        p {:title title :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g))}
        band-rows [1 3 5]                       ; the odd body rows' bands: rows 2, 4 and 6 of the table
        band (fn [r] [(first (:xs g)) (nth (:ys g) (inc r)) (last (:xs g)) (nth (:ys g) r)])
        bands (str/join "\n"
                        (for [r band-rows :let [[x0 y0 x1 y1] (band r)]]
                          (if (= kind :shading)
                            (str "q " (num* x0) " " (num* y0) " " (num* (- x1 x0)) " " (num* (- y1 y0)) " re W n /Sh1 sh Q")
                            (str "q /Pattern cs /P1 scn " (num* x0) " " (num* y0) " " (num* (- x1 x0)) " " (num* (- y1 y0)) " re f Q"))))
        ops (str bands "\n" (frame-ops p) "\n" (cell-ops g TABLE-BANDS 10))
        c (rgb-frac BAND-RGB)
        extra (if (= kind :shading)
                {100 (str "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [72 0 522 0] /Extend [true true] /Function "
                          "<< /FunctionType 2 /Domain [0 1] /C0 [" c "] /C1 [" c "] /N 1 >> >>")}
                {100 [:stream "/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 8 8] /XStep 8 /YStep 8 /Resources << >>"
                      (str c " rg 0 0 8 8 re f")]})]
    {:pages [{:ops ops :res (if (= kind :shading) "/Shading << /Sh1 100 0 R >>" "/Pattern << /P1 100 0 R >>")}]
     :extra extra
     :html (page-html (assoc p :table (cell-rows-html TABLE-BANDS "data-profile=\"shaded\"")))
     :gen (om "fills" (vec (for [r band-rows :let [[x0 y0 x1 y1] (band r)]] (fill-gen x0 y0 x1 y1 BAND-HEX))))}))

(defn curve-lines []
  (let [g (grid 72 640 [150 150 150] 22 3)
        p {:title "Office Supplies" :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g))}
        xs (:xs g) ys (:ys g)
        hc (fn [r] (let [y (nth ys r) x0 (first xs) x1 (last xs) d (- x1 x0)]
                     (str (num* x0) " " (num* y) " m " (num* (+ x0 (/ d 3))) " " (num* y) " " (num* (+ x0 (/ (* 2 d) 3))) " " (num* y) " " (num* x1) " " (num* y) " c S")))
        vc (fn [c] (let [x (nth xs c) y0 (first ys) y1 (last ys) d (- y1 y0)]
                     (str (num* x) " " (num* y0) " m " (num* x) " " (num* (+ y0 (/ d 3))) " " (num* x) " " (num* (+ y0 (/ (* 2 d) 3))) " " (num* x) " " (num* y1) " c S")))
        ops (str (frame-ops p) "\n" (cell-ops g TABLE-SMALL 10) "\n0.5 w "
                 (str/join "\n" (concat (for [r (range (count ys))] (hc r)) (for [c (range (count xs))] (vc c)))))]
    {:pages [{:ops ops}]
     :html (page-html (assoc p :table (cell-rows-html TABLE-SMALL "data-profile=\"ruled\"")))}))

(defn image-rules []
  (let [g (grid 72 640 [150 150 150] 22 3)
        p {:title "Office Supplies" :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g))}
        xs (:xs g) ys (:ys g)
        himg (fn [r] (str "q " (num* (:w g)) " 0 0 1 " (num* (first xs)) " " (num* (- (nth ys r) 0.5)) " cm /Im1 Do Q"))
        vimg (fn [c] (str "q 1 0 0 " (num* (:h g)) " " (num* (- (nth xs c) 0.5)) " " (num* (last ys)) " cm /Im1 Do Q"))
        ops (str (frame-ops p) "\n" (cell-ops g TABLE-SMALL 10) "\n"
                 (str/join "\n" (concat (for [r (range (count ys))] (himg r)) (for [c (range (count xs))] (vimg c)))))]
    {:pages [{:ops ops :res "/XObject << /Im1 100 0 R >>"}]
     :extra {100 [:stream "/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceGray /BitsPerComponent 8" "\u0000"]}
     :html (page-html (assoc p :table (cell-rows-html TABLE-SMALL "data-profile=\"ruled\"")))
     :gen (om "rules" (vec (concat (for [r (range (count ys))] (rule-gen (first xs) (nth ys r) (last xs) (nth ys r) 1.0))
                                   (for [c (range (count xs))] (rule-gen (nth xs c) (first ys) (nth xs c) (last ys) 1.0)))))}))

(defn softmask-rule []
  (let [g (grid 72 640 [150 150 150] 22 3)
        p {:title "Office Supplies" :tsize 18 :h 1 :before PROSE-A :after PROSE-B :bottom (last (:ys g))}
        yh (- (first (:ys g)) 4)
        ;; the extra rule: under a luminosity mask of black, which hides everything painted through it
        ops (str (frame-ops p) "\n" (cell-ops g TABLE-SMALL 10) "\n0.5 w " (full-grid-path g) " S\n"
                 "q /GS1 gs 0.5 w " (num* 72) " " (num* yh) " m " (num* 522) " " (num* yh) " l S Q")]
    {:pages [{:ops ops :res "/ExtGState << /GS1 101 0 R >>"}]
     :extra {100 [:stream "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Group << /S /Transparency /CS /DeviceGray >>" "0 g 0 0 612 792 re f"]
             101 "<< /Type /ExtGState /SMask << /Type /Mask /S /Luminosity /G 100 0 R >> >>"}
     :html (page-html (assoc p :table (cell-rows-html TABLE-SMALL "data-profile=\"ruled\"")))}))

(def DOCS
  [["gfx-onepath-grid" onepath-grid "Grid Drawn As One Path"]
   ["gfx-hidden-rules" hidden-rules "Rules That Are Not Seen"]
   ["gfx-shading-bands" #(bands-doc "Bands From Shadings" :shading) "Bands From Shadings"]
   ["gfx-pattern-bands" #(bands-doc "Bands From Patterns" :pattern) "Bands From Patterns"]
   ["gfx-curve-lines" curve-lines "Rules That Are Curves"]
   ["gfx-image-rules" image-rules "Rules That Are Images"]
   ["gfx-softmask-rule" softmask-rule "A Rule Under A Soft Mask"]])

(defn outputs
  "{path bytes} of every file this writes."
  []
  (into {}
        (mapcat (fn [[stem f title]]
                  (let [d (f)
                        h (mh/doc title (:html d) {:css "table{width:100%}"})]
                    (cond-> [[(str CORPUS "/pdf/" stem ".pdf") (pdf (:pages d) (:extra d) (:catalog d ""))]
                             [(str CORPUS "/html/" stem ".html") (.getBytes ^String h "UTF-8")]]
                      (:gen d) (conj [(str CORPUS "/gold/" stem ".rules-gen.json") (.getBytes (str (pj/dumps (:gen d) {:indent 1}) "\n") "UTF-8")]))))
                DOCS)))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        outs (outputs)
        bad (remove (fn [[p bs]] (w/same-bytes? p bs)) outs)]
    (if check?
      (if (empty? bad)
        (println (str "  ok   gfx_pdf: " (count outs) " committed files are what tools/synth/gfx_pdf.clj writes"))
        (do (doseq [[p _] bad] (println "  FAIL" p "is not what tools/synth/gfx_pdf.clj writes"))
            (System/exit 1)))
      (do (doseq [[p bs] outs] (io/make-parents p) (w/write-bytes! p bs))
          (println "wrote" (count outs) "files")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
