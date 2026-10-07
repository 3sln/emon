;; CORPUS SEEDS for `bb fuzz` (DEVLOG P10.5, D700): documents built here, byte for byte the same on every run,
;; so a case is still a pure function of (seed, index). They are what the committed fixtures are not: text in
;; plain (uncompressed, so a mutator can reach the content stream), fonts with ToUnicode maps, images, forms,
;; links and outlines; and the HOSTILE shapes of a hostile file, each at a size that a bounded reader must
;; refuse or finish and an unbounded one cannot: deep nesting (arrays, dictionaries, page tree, `q`, forms,
;; outlines), cycles (kids, parents, contents, resources, a form that draws itself, a length that is its own
;; stream, a ToUnicode that uses itself), huge counts (a /Count past 2^31, a page tree of 20000 leaves, a
;; hundred thousand widths, a CMap range of the whole code space), decompression bombs (Flate, RunLength, a
;; chain of two hundred filters), and bad image dictionaries and fonts.
(ns fuzz.seeds
  (:require [clojure.string :as str]
            [fuzz.pdfobj :as po]))

(defn- obj
  "[n dict] or [n dict data]: the builder gives a stream its /Length."
  ([n dict] {:n n :g 0 :dict dict :data nil})
  ([n dict data] {:n n :g 0 :dict dict :data (if (string? data) (po/->b data) data)}))

(defn- raw [& codes] (apply str (map char codes)))

(defn- build [objs] (po/emit {:objs (vec (sort-by :n objs)) :root "1 0 R"}))

(def ^:private helv "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

(defn doc
  "A one-page document. opts: :content (a string, or bytes), :media, :resources (inner text), :objs (more objects),
   :page-extra (more page keys), :font (the text of object 5)."
  [{:keys [content media resources objs page-extra font content-dict]
    :or {media "[0 0 612 792]" font helv}}]
  (build (concat
          [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>")
           (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
           (obj 3 (str "<< /Type /Page /Parent 2 0 R /MediaBox " media " /Contents 4 0 R /Resources << "
                       (or resources "/Font << /F1 5 0 R >>") " >> " page-extra " >>"))
           (obj 4 (or content-dict "<< >>") (or content "BT /F1 12 Tf 72 700 Td (Hello) Tj ET"))
           (obj 5 font)]
          objs)))

(def text-body
  (str "BT /F1 24 Tf 72 720 Td (A Heading For The Page) Tj ET\n"
       "BT /F1 11 Tf 72 690 Td 14 TL (The quick brown fox jumps over the lazy dog, and then) Tj T* "
       "(it jumps over the dog again, which is a second line.) Tj T* (A third line, with [array] text) Tj ET\n"
       "BT /F1 11 Tf 72 620 Td [(Kerned) -80 (text) 120 ( in) -20 ( an) 30 ( array)] TJ ET\n"
       "BT /F1 11 Tf 1 0 0 1 72 600 Tm (Placed with Tm) Tj 0 -14 Td (and Td) Tj ET\n"))

(def tounicode
  (str "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
       "/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n"
       "1 begincodespacerange <00> <FF> endcodespacerange\n"
       "2 beginbfchar <41> <0058> <42> <00590059> endbfchar\n"
       "2 beginbfrange <61> <7A> <0061> <30> <39> [<0030> <0031> <0032> <0033> <0034> <0035> <0036> <0037> <0038> <0039>] endbfrange\n"
       "endcmap CMapName currentdict /CMap defineresource pop end end\n"))

(defn- font-with-tounicode [encoding-extra]
  [(str "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /FirstChar 32 /LastChar 126 /Widths ["
        (str/join " " (repeat 95 600)) "] /ToUnicode 6 0 R /FontDescriptor 7 0 R " encoding-extra " >>")
   [(obj 6 "<< >>" tounicode)
    (obj 7 "<< /Type /FontDescriptor /FontName /Courier /Flags 33 /FontBBox [-23 -250 715 805] /ItalicAngle 0 /Ascent 629 /Descent -157 /CapHeight 562 /StemV 51 >>")]])

(defn type0-doc []
  (doc {:content "BT /F1 12 Tf 72 700 Td <0001000200030004> Tj <0005> Tj ET"
        :font "<< /Type /Font /Subtype /Type0 /BaseFont /Fuzz /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode 8 0 R >>"
        :objs [(obj 6 "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Fuzz /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor 7 0 R /DW 1000 /W [1 [500 600] 3 5 700] >>")
               (obj 7 "<< /Type /FontDescriptor /FontName /Fuzz /Flags 4 /FontBBox [0 0 1000 1000] /ItalicAngle 0 /Ascent 900 /Descent -200 /CapHeight 700 /StemV 80 >>")
               (obj 8 "<< >>" (str "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                                   "2 beginbfchar <0001> <0041> <0002> <0042> endbfchar\n1 beginbfrange <0003> <0005> <0043> endbfrange\nendcmap end end\n"))]}))

(defn image-doc [img-dict img-data]
  (doc {:content "q 200 0 0 200 100 400 cm /Im1 Do Q BT /F1 12 Tf 72 380 Td (A caption below the image) Tj ET"
        :resources "/Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >>"
        :objs [(obj 6 img-dict img-data)]}))

(defn form-doc []
  (doc {:content "q /Fm1 Do Q BT /F1 12 Tf 72 500 Td (After the form) Tj ET"
        :resources "/Font << /F1 5 0 R >> /XObject << /Fm1 6 0 R >>"
        :objs [(obj 6 "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] /Matrix [1 0 0 1 0 0] /Resources << /Font << /F1 5 0 R >> >> >>"
                    "BT /F1 12 Tf 72 700 Td (Inside a form) Tj ET")]}))

(defn links-doc []
  (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R /Outlines 8 0 R /Names << /Dests 12 0 R >> /Lang (en) >>")
          (obj 2 "<< /Type /Pages /Kids [3 0 R 9 0 R] /Count 2 >>")
          (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> /Annots [6 0 R 7 0 R] >>")
          (obj 4 "<< >>" "BT /F1 12 Tf 72 700 Td (A link to the site and a jump) Tj ET")
          (obj 5 helv)
          (obj 6 "<< /Type /Annot /Subtype /Link /Rect [72 695 160 712] /A << /S /URI /URI (https://example.com/) >> >>")
          (obj 7 "<< /Type /Annot /Subtype /Link /Rect [170 695 230 712] /Dest [9 0 R /XYZ 0 700 0] >>")
          (obj 8 "<< /Type /Outlines /First 10 0 R /Last 11 0 R /Count 2 >>")
          (obj 9 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 13 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
          (obj 10 "<< /Title (One) /Parent 8 0 R /Next 11 0 R /Dest [3 0 R /Fit] >>")
          (obj 11 "<< /Title (Two) /Parent 8 0 R /Prev 10 0 R /Dest [9 0 R /Fit] >>")
          (obj 12 "<< /Names [(first) [3 0 R /Fit] (second) [9 0 R /Fit]] >>")
          (obj 13 "<< >>" "BT /F1 12 Tf 72 700 Td (Second page) Tj ET")]))

(defn table-doc []
  (let [lines (apply str (for [y [600 580 560 540] ] (str "72 " y " m 372 " y " l S\n")))
        vlines (apply str (for [x [72 172 272 372]] (str x " 540 m " x " 600 l S\n")))
        cells (apply str (for [r (range 3) c (range 3)]
                           (str "BT /F1 10 Tf " (+ 76 (* 100 c)) " " (- 586 (* 20 r)) " Td (r" r "c" c ") Tj ET\n")))]
    (doc {:content (str "0.5 w\n" lines vlines cells)})))

(defn many-pages [n]
  (build (concat [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>")
                  (obj 2 (str "<< /Type /Pages /Kids [" (str/join " " (for [i (range n)] (str (+ 4 (* 2 i)) " 0 R"))) "] /Count " n " >>"))
                  (obj 3 helv)]
                 (mapcat (fn [i]
                           [(obj (+ 4 (* 2 i)) (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents " (+ 5 (* 2 i)) " 0 R /Resources << /Font << /F1 3 0 R >> >> >>"))
                            (obj (+ 5 (* 2 i)) "<< >>" (str "BT /F1 12 Tf 20 100 Td (page " i ") Tj ET"))])
                         (range n)))))

;; ------------------------------------------------------------------ hostile

(defn deep-kids [d]
  ;; a page tree d deep: node i has one kid, node i+1; the last holds one page
  (build (concat [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>") (obj (+ 4 d) helv)]
                 (for [i (range d)]
                   (obj (+ 2 i) (if (= i (dec d))
                                  (str "<< /Type /Pages /Parent " (+ 1 i) " 0 R /Kids [" (+ 2 d) " 0 R] /Count 1 >>")
                                  (str "<< /Type /Pages " (when (pos? i) (str "/Parent " (+ 1 i) " 0 R ")) "/Kids [" (+ 3 i) " 0 R] /Count 1 >>"))))
                 [(obj (+ 2 d) (str "<< /Type /Page /Parent " (+ 1 d) " 0 R /MediaBox [0 0 200 200] /Contents " (+ 3 d) " 0 R /Resources << /Font << /F1 " (+ 4 d) " 0 R >> >> >>"))
                  (obj (+ 3 d) "<< >>" "BT /F1 12 Tf 20 100 Td (deep) Tj ET")])))

(defn deep-direct [open close d key]
  (doc {:page-extra (str "/" key " " (apply str (repeat d open)) (apply str (repeat d close)))}))

(defn deep-q [d]
  (doc {:content (str (apply str (repeat d "q ")) "BT /F1 12 Tf 72 700 Td (nested) Tj ET " (apply str (repeat d "Q ")))}))

(defn deep-forms [d]
  (doc {:content "/Fm0 Do BT /F1 12 Tf 72 700 Td (top) Tj ET"
        :resources "/Font << /F1 5 0 R >> /XObject << /Fm0 6 0 R >>"
        :objs (for [i (range d)]
                (obj (+ 6 i) (str "<< /Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> /XObject << /Fm" (inc i) " " (+ 7 i) " 0 R >> >> >>")
                     (str "/Fm" (inc i) " Do BT /F1 10 Tf 72 " (+ 100 i) " Td (f" i ") Tj ET")))}))

(defn deep-outline [d]
  (build (concat [(obj 1 "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>")
                  (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                  (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>")
                  (obj 4 "<< >>" "")
                  (obj 5 "<< /Type /Outlines /First 6 0 R /Last 6 0 R /Count 1 >>")]
                 (for [i (range d)]
                   (obj (+ 6 i) (str "<< /Title (n" i ") /Parent " (+ 5 i) " 0 R " (when (< i (dec d)) (str "/First " (+ 7 i) " 0 R /Last " (+ 7 i) " 0 R /Count 1 ")) "/Dest [3 0 R /Fit] >>"))))))

(defn deep-structure [d]
  ;; a structure tree d deep (tagged)
  (build (concat [(obj 1 "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true >> /StructTreeRoot 5 0 R >>")
                  (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                  (obj 3 (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 " (+ 6 d) " 0 R >> >> /StructParents 0 >>"))
                  (obj 4 "<< >>" "/P <</MCID 0>> BDC BT /F1 12 Tf 20 100 Td (tagged) Tj ET EMC")
                  (obj (+ 6 d) helv)
                  (obj 5 "<< /Type /StructTreeRoot /K 6 0 R >>")]
                 (for [i (range d)]
                   (obj (+ 6 i) (str "<< /Type /StructElem /S /Div /P " (+ 5 i) " 0 R /Pg 3 0 R /K "
                                     (if (< i (dec d)) (str (+ 7 i) " 0 R") "0") " >>"))))))

(defn cycle-docs []
  (let [m "[0 0 200 200]"
        base (fn [k] (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>")
                             (obj 2 (str "<< /Type /Pages /Kids [3 0 R] /Count 1 " (:pages k) " >>"))
                             (obj 3 (str "<< /Type /Page /Parent " (or (:parent k) "2 0 R") " /MediaBox " m " /Contents " (or (:contents k) "4 0 R")
                                         " /Resources " (or (:resources k) "<< /Font << /F1 5 0 R >> >>") " >>"))
                             (obj 4 (or (:cdict k) "<< >>") (or (:cdata k) "BT /F1 12 Tf 20 100 Td (cyc) Tj ET"))
                             (obj 5 (or (:font k) helv))
                             (obj 6 (or (:o6 k) "6 0 R"))
                             (obj 7 (or (:o7 k) "<< >>") (:o7data k))]))]
    {"cycle-kids" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>") (obj 2 "<< /Type /Pages /Kids [2 0 R 3 0 R 2 0 R] /Count 3 >>")
                          (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")])
     "cycle-parent" (base {:parent "3 0 R"})
     "cycle-pages-parent" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>") (obj 2 "<< /Type /Pages /Parent 8 0 R /Kids [3 0 R] /Count 1 >>")
                                  (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>") (obj 8 "<< /Type /Pages /Parent 2 0 R /Kids [2 0 R] /Count 1 >>")])
     "cycle-contents" (base {:contents "6 0 R" :o6 "[6 0 R 4 0 R 6 0 R]"})
     "cycle-contents-self" (base {:contents "4 0 R" :cdict "<< /Length 4 0 R >>"})
     "cycle-resources" (base {:resources "6 0 R" :o6 "<< /Font 6 0 R /XObject 6 0 R /ExtGState 6 0 R >>"})
     "cycle-length-self" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>") (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                                 (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>")
                                 {:n 4 :g 0 :dict "<< /Length 4 0 R >>" :data (po/->b "BT ET") :keep-length true}])
     "cycle-length-chain" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>") (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                                  (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>")
                                  {:n 4 :g 0 :dict "<< /Length 5 0 R >>" :data (po/->b "BT ET") :keep-length true}
                                  (obj 5 "6 0 R") (obj 6 "5 0 R")])
     "self-object" (base {:o6 "6 0 R" :resources "<< /Font << /F1 6 0 R >> >>"})
     "cycle-form" (doc {:content "/Fm1 Do" :resources "/XObject << /Fm1 6 0 R >> /Font << /F1 5 0 R >>"
                        :objs [(obj 6 "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << /XObject << /Fm1 6 0 R >> /Font << /F1 5 0 R >> >> >>"
                                    "/Fm1 Do BT /F1 12 Tf 20 100 Td (loop) Tj ET")]})
     "cycle-form-pair" (doc {:content "/Fm1 Do" :resources "/XObject << /Fm1 6 0 R >> /Font << /F1 5 0 R >>"
                             :objs [(obj 6 "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << /XObject << /Fm2 7 0 R >> >> >>" "/Fm2 Do")
                                    (obj 7 "<< /Type /XObject /Subtype /Form /BBox [0 0 200 200] /Resources << /XObject << /Fm1 6 0 R >> >> >>" "/Fm1 Do")]})
     "cycle-tounicode" (doc {:content "BT /F1 12 Tf 72 700 Td (abc) Tj ET"
                             :font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 6 0 R >>"
                             :objs [(obj 6 "<< /UseCMap 6 0 R >>" "/CIDInit /ProcSet findresource begin begincmap /Fuzz usecmap\n1 begincodespacerange <00> <FF> endcodespacerange\n1 beginbfchar <61> <0041> endbfchar\nendcmap end\n")]})
     "cycle-descendant" (doc {:content "BT /F1 12 Tf 72 700 Td <0001> Tj ET"
                              :font "<< /Type /Font /Subtype /Type0 /BaseFont /Fuzz /Encoding /Identity-H /DescendantFonts [5 0 R] >>"})
     "cycle-type3" (doc {:content "BT /F1 12 Tf 72 700 Td (a) Tj ET"
                         :font "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 1000 1000] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /a 6 0 R >> /Encoding << /Type /Encoding /Differences [97 /a] >> /FirstChar 97 /LastChar 97 /Widths [1000] /Resources << /Font << /F1 5 0 R >> >> >>"
                         :objs [(obj 6 "<< >>" "1000 0 d0 BT /F1 1000 Tf (a) Tj ET")]})
     "cycle-smask" (image-doc "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceGray /SMask 6 0 R >>" "\u0000ÿÿ\u0000")
     "cycle-outline" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>") (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                             (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
                             (obj 5 "<< /Type /Outlines /First 6 0 R /Last 7 0 R /Count 2 >>")
                             (obj 6 "<< /Title (a) /Parent 5 0 R /Next 7 0 R /First 5 0 R /Last 5 0 R /Count 1 >>")
                             (obj 7 "<< /Title (b) /Parent 5 0 R /Prev 6 0 R /Next 6 0 R >>")])
     "cycle-struct" (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R /MarkInfo << /Marked true >> /StructTreeRoot 5 0 R >>") (obj 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                            (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /StructParents 0 >>") (obj 4 "<< >>" "")
                            (obj 5 "<< /Type /StructTreeRoot /K [6 0 R 7 0 R] >>")
                            (obj 6 "<< /Type /StructElem /S /Div /P 7 0 R /K [7 0 R] >>") (obj 7 "<< /Type /StructElem /S /Div /P 6 0 R /K [6 0 R] >>")])}))

(defn pages-flat [n]
  ;; a flat page tree of n leaves, all the one page object, and one with /Count far past n
  (build [(obj 1 "<< /Type /Catalog /Pages 2 0 R >>")
          (obj 2 (str "<< /Type /Pages /Kids [" (str/join " " (repeat n "3 0 R")) "] /Count " n " >>"))
          (obj 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")]))

(defn huge-widths [n]
  (doc {:content "BT /F1 12 Tf 72 700 Td (abc) Tj ET"
        :font (str "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 0 /LastChar " (dec n) " /Widths [" (str/join " " (repeat n 500)) "] >>")}))

(defn many-ops [n]
  (doc {:content (apply str (repeat n "BT /F1 8 Tf 10 10 Td (a) Tj ET\n"))}))

(defn many-words-flate
  "n words on one baseline, the content stream Flate-compressed (a few KB for any n): the sum of a row's baselines
   (`tb-build`) is n * 78200 centipoints and passed 2^31-1 at 27463 words (P10.5, D703)."
  [n]
  (doc {:content-dict "<< /Filter /FlateDecode >>"
        :content (po/deflate (po/->b (apply str "BT /F1 8 Tf 10 10 Td " (repeat n "(a) Tj 0 0 Td\n"))))}))

(defn big-cmap [kind]
  (let [body (case kind
               :range-all "1 begincodespacerange <0000> <FFFF> endcodespacerange\n1 beginbfrange <0000> <FFFF> <0000> endbfrange\n"
               :range-32 "1 begincodespacerange <00000000> <FFFFFFFF> endcodespacerange\n1 beginbfrange <00000000> <FFFFFFFF> <00000000> endbfrange\n"
               :many-chars (str "20000 beginbfchar\n" (apply str (for [i (range 20000)] (format "<%04X> <%04X>\n" (mod i 65536) (+ 32 (mod i 90))))) "endbfchar\n")
               :long-dst (str "1 beginbfchar <41> <" (apply str (repeat 4000 "0041")) "> endbfchar\n")
               :deep-array (str "1 beginbfrange <41> <42> " (apply str (repeat 3000 "[")) "<0041>" (apply str (repeat 3000 "]")) " endbfrange\n")
               :inverted "2 beginbfrange <7A> <61> <0061> <FF> <00> <0000> endbfrange\n"
               :surrogates "2 beginbfchar <41> <D800> <42> <DC00> endbfchar\n1 beginbfchar <43> <D83DDE00> endbfchar\n"
               :unbalanced "3 begincodespacerange <00> <FF endcodespacerange\n5 beginbfchar <41> <0058> endbfchar endbfchar beginbfrange <00>\n"
               :neg-count "-5 beginbfchar <41> <0058> endbfchar 2147483647 beginbfrange <41> <42> <0041> endbfrange\n"
               :usecmap "/Identity-H usecmap /NoSuchCMap usecmap 1 begincodespacerange <00> <FF> endcodespacerange\n")]
    (doc {:content "BT /F1 12 Tf 72 700 Td (ABCabc) Tj <00410042> Tj ET"
          :font "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /ToUnicode 6 0 R >>"
          :objs [(obj 6 "<< >>" (str "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n" body "endcmap end end\n"))]})))

(defn bomb [kind n]
  (let [
        data (case kind
               :flate (po/deflate-zeros n)
               :runlength (po/->b (apply str (repeat (quot n 128) "\u0081\u0000")))
               :hex (po/->b (apply str (repeat (quot n 2) "00")))
               :a85 (po/->b (apply str (repeat (quot n 4) "z"))))
        filter (case kind :flate "/FlateDecode" :runlength "/RunLengthDecode" :hex "/ASCIIHexDecode" :a85 "/ASCII85Decode")]
    (doc {:content-dict (str "<< /Filter " filter " >>") :content data})))

(defn bomb-image [n]
  (image-doc (str "<< /Type /XObject /Subtype /Image /Width " n " /Height 1 /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /FlateDecode >>")
             (po/deflate-zeros n)))

(defn bomb-nested [k]
  ;; Flate of Flate of ... of 8 MB of zeros, k deep: a chain that bounds each stage or the whole
  (let [data (reduce (fn [d _] (po/deflate d)) (po/inflate (po/deflate-zeros 8000000) 9000000) (range k))]
    (doc {:content-dict (str "<< /Filter [" (str/join " " (repeat k "/FlateDecode")) "] >>") :content data})))

(defn filter-chain [k filt]
  (doc {:content-dict (str "<< /Filter [" (str/join " " (repeat k filt)) "] >>") :content "BT /F1 12 Tf 72 700 Td (chained) Tj ET"}))

(defn bad-image [which]
  (case which
    :huge-dims (image-doc "<< /Type /XObject /Subtype /Image /Width 2147483647 /Height 2147483647 /BitsPerComponent 8 /ColorSpace /DeviceRGB >>" "abcd")
    :big-1bit (image-doc "<< /Type /XObject /Subtype /Image /Width 65536 /Height 65536 /BitsPerComponent 1 /ColorSpace /DeviceGray /Filter /FlateDecode >>" (po/deflate (byte-array 1000)))
    :zero-dims (image-doc "<< /Type /XObject /Subtype /Image /Width 0 /Height 0 /BitsPerComponent 8 /ColorSpace /DeviceGray >>" "")
    :neg-dims (image-doc "<< /Type /XObject /Subtype /Image /Width -3 /Height -2 /BitsPerComponent 8 /ColorSpace /DeviceGray >>" "abcdef")
    :bpc-17 (image-doc "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 17 /ColorSpace /DeviceGray >>" "abcd")
    :indexed-short (image-doc "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace [/Indexed /DeviceRGB 255 <00FF>] >>" "\u0000\u0001\u0002\u0003")
    :indexed-self (image-doc "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace [/Indexed 6 0 R 3 <000000FFFFFF>] >>" "\u0000\u0001\u0002\u0003")
    :cs-deep (image-doc (str "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace " (apply str (repeat 500 "[/ICCBased ")) "/DeviceGray" (apply str (repeat 500 "]")) " >>") "abcd")
    :decode-short (image-doc "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Decode [0] >>" "abcdefghijkl")
    :mask-bpc (image-doc "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /BitsPerComponent 8 /ImageMask true >>" (apply str (repeat 8 "ABCDEFGH")))
    :dct-garbage (image-doc "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /DCTDecode >>" "ÿØÿàgarbage garbage garbage")
    :ccitt-columns (image-doc "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /BitsPerComponent 1 /ColorSpace /DeviceGray /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 2147483647 /Rows 2147483647 >> >>" "\u0000\u0001\u0002")
    :jbig2-garbage (image-doc "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /BitsPerComponent 1 /ColorSpace /DeviceGray /Filter /JBIG2Decode >>" "\u0000\u0000\u0000\u00000\u0000\u0001")
    :jpx-garbage (image-doc "<< /Type /XObject /Subtype /Image /Width 8 /Height 8 /Filter /JPXDecode >>" "\u0000\u0000\u0000\u000cjP  \r\n\u0087\n")
    :predictor (image-doc "<< /Type /XObject /Subtype /Image /Width 4 /Height 4 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode /DecodeParms << /Predictor 15 /Columns 2147483647 /Colors 2147483647 /BitsPerComponent 16 >> >>" (po/deflate (byte-array 100)))
    :predictor-small (image-doc "<< /Type /XObject /Subtype /Image /Width 4 /Height 4 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 0 /Colors 0 >> >>" (po/deflate (byte-array 100)))
    :lzw-early (image-doc "<< /Type /XObject /Subtype /Image /Width 4 /Height 4 /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /LZWDecode /DecodeParms << /EarlyChange 7 >> >>" (raw 128 11 96 80 34 12 12 133 1))
    :inline-garbage (doc {:content "q 100 0 0 100 50 50 cm BI /W 99999 /H 99999 /BPC 8 /CS /RGB /F /Fl ID garbage bytes here EI Q BT /F1 12 Tf 72 700 Td (after) Tj ET"})
    :inline-nodata (doc {:content "BI /W 4 /H 4 /BPC 8 /CS /G ID abcd EI BI /W 4 ID EI BI ID"})
    :good-gray (image-doc "<< /Type /XObject /Subtype /Image /Width 4 /Height 4 /BitsPerComponent 8 /ColorSpace /DeviceGray >>" "0123456789abcdef")
    :good-rgb-smask (doc {:content "q 100 0 0 100 50 500 cm /Im1 Do Q"
                          :resources "/XObject << /Im1 6 0 R >>"
                          :objs [(obj 6 "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceRGB /SMask 7 0 R >>" "abcdefghijkl")
                                 (obj 7 "<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /BitsPerComponent 8 /ColorSpace /DeviceGray >>" "ÿ\u0080@\u0000")]})))

(defn bad-font [which]
  (case which
    :first-last (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 2147483647 /LastChar -5 /Widths [1 2 3] >>"})
    :widths-short (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 65 /LastChar 90 /Widths [500] >>"})
    :widths-junk (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar 32 /LastChar 40 /Widths [/a (b) [1] << >> null 99999999999 -1 1.5e3 true] >>"})
    :differences (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding /Differences [2147483647 /A /B -5 /C 255 /D /E /F 65536 /G (x) 3 0 R] >> >>"})
    :encoding-name (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /NoSuchEncoding >>"})
    :matrix-zero (doc {:font "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 0 0] /FontMatrix [0 0 0 0 0 0] /CharProcs << >> /Encoding << /Differences [97 /a] >> /FirstChar 97 /LastChar 97 /Widths [1000] >>"})
    :no-subtype (doc {:font "<< /Type /Font /BaseFont /Helvetica >>"})
    :font-is-array (doc {:font "[1 2 3]"})
    :descriptor-junk (doc {:font "<< /Type /Font /Subtype /TrueType /BaseFont /Fuzz /FontDescriptor 6 0 R /FirstChar 0 /LastChar 255 >>"
                           :objs [(obj 6 "<< /Type /FontDescriptor /FontFile2 7 0 R /Ascent 2147483647 /Descent -2147483647 /FontBBox [2147483647 -2147483647 0 0] /Flags -1 >>")
                                  (obj 7 "<< /Length1 99999999 >>" "\u0000\u0001\u0000\u0000\u0000\u000f\u0000\u0080\u0000\u0003\u00000cmapjunkjunkjunk")]})
    :cff-junk (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Fuzz /FontDescriptor 6 0 R >>"
                    :objs [(obj 6 "<< /Type /FontDescriptor /FontFile3 7 0 R >>")
                           (obj 7 "<< /Subtype /Type1C >>" "\u0001\u0000\u0004\u0001\u0000\u0001\u0001\u0001\u0004ÿÿÿÿ\u0000")]})
    :type1-junk (doc {:font "<< /Type /Font /Subtype /Type1 /BaseFont /Fuzz /FontDescriptor 6 0 R >>"
                      :objs [(obj 6 "<< /Type /FontDescriptor /FontFile 7 0 R >>")
                             (obj 7 "<< /Length1 10 /Length2 99999999 /Length3 0 >>" "%!PS-AdobeFont-1.0\ncurrentfile eexec\n\u0000\u0001\u0002")]})
    :cid-w (doc {:content "BT /F1 12 Tf 72 700 Td <0001000200030004> Tj ET"
                 :font "<< /Type /Font /Subtype /Type0 /BaseFont /Fuzz /Encoding /Identity-H /DescendantFonts [6 0 R] >>"
                 :objs [(obj 6 "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Fuzz /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 2147483647 /W [1 [2147483647] 0 2147483647 5 5 1 [] 7 [1 2] -1 [3] 100 90 -4] /CIDToGIDMap 99 0 R >>")]})
    :cid-ordering (doc {:content "BT /F1 12 Tf 72 700 Td <00010002> Tj ET"
                        :font "<< /Type /Font /Subtype /Type0 /BaseFont /Fuzz /Encoding /UniJIS-UCS2-H /DescendantFonts [6 0 R] >>"
                        :objs [(obj 6 "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /Fuzz /CIDSystemInfo << /Registry (Adobe) /Ordering (Japan1) /Supplement 99 >> >>")]})
    :encoding-stream (doc {:content "BT /F1 12 Tf 72 700 Td <00010002> Tj ET"
                           :font "<< /Type /Font /Subtype /Type0 /BaseFont /Fuzz /Encoding 6 0 R /DescendantFonts [7 0 R] >>"
                           :objs [(obj 6 "<< /Type /CMap /CMapName /Fuzz /UseCMap /Identity-H >>" "begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 1 begincidrange <0000> <FFFF> 0 endcidrange endcmap")
                                  (obj 7 "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Fuzz /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> >>")]})))

(defn content-torture [which]
  (doc {:content (case which
                   :operands "1 2 3 4 5 6 7 8 9 0 Tf (x) Tj ET BT BT BT ET ET ET -2147483648 2147483647 Td 1e999 Tz /a /b /c Tf [(a) 99999999999 (b)] TJ ( ) ' 1 2 \" ] } >> << [ ( \\"
                   :matrix "BT /F1 12 Tf 0 0 0 0 0 0 Tm (zero) Tj 1e308 0 0 1e308 0 0 Tm (big) Tj -1 0 0 -1 0 0 Tm (flip) Tj ET q 0 0 0 0 0 0 cm BT /F1 12 Tf (z) Tj ET Q"
                   :size "BT /F1 2147483647 Tf (huge) Tj /F1 -2147483647 Tf (neg) Tj /F1 0 Tf (zero) Tj /F1 0.0000001 Tf (tiny) Tj 2147483647 Tc 2147483647 Tw (spaced out) Tj -2147483647 TL T* ET"
                   :unbalanced "Q Q Q Q q q BT /F1 12 Tf (a) Tj Q ET ET EMC EMC BDC BMC BDC /OC /x BDC BT"
                   :marked (str (apply str (repeat 3000 "/Span <</MCID 1>> BDC ")) "BT /F1 12 Tf (m) Tj ET " (apply str (repeat 3000 "EMC ")))
                   :dash-path (str "[1 2 3] 0 d 5 w 0 0 m " (apply str (for [i (range 5000)] (str i " " (* 7 i) " l "))) "S 100 100 50 50 re f* W n")
                   :shading "/Sh1 sh /Pat cs /P1 scn /P1 scn 0 0 100 100 re f /Pat CS /P2 SCN 0 0 m 100 100 l S"
                   :text-clip "BT 7 Tr /F1 12 Tf 72 700 Td (clip) Tj ET 3 Tr BT /F1 12 Tf 72 680 Td (hidden) Tj ET W n BT /F1 12 Tf 72 660 Td (after) Tj ET")}))

(defmacro ^:private seed-vec
  "[[name (delay expr)] ...]: a document is made when a case first needs it."
  [& entries]
  (vec (for [[n e] entries] [n (list 'delay e)])))

(defn seeds
  "[{:name :bytes}] in a fixed order: every document of this namespace."
  []
  (let [pdfs (concat
              (seed-vec ["text-simple" (doc {:content text-body})]
               ["text-multipage" (many-pages 6)]
               ["text-tounicode" (let [[f objs] (font-with-tounicode "")]
                                   (doc {:content "BT /F1 12 Tf 72 700 Td (abcdefg ABCDEFG 0123) Tj ET" :font f :objs objs}))]
               ["text-tounicode-differences" (let [[f objs] (font-with-tounicode "/Encoding << /Type /Encoding /Differences [65 /Alpha /Beta /uni0041 /g12] >>")]
                                               (doc {:content "BT /F1 12 Tf 72 700 Td (ABCD) Tj ET" :font f :objs objs}))]
               ["type0-identity" (type0-doc)]
               ["form-xobject" (form-doc)]
               ["links-outlines" (links-doc)]
               ["table-ruled" (table-doc)]
               ["image-gray" (bad-image :good-gray)]
               ["image-rgb-smask" (bad-image :good-rgb-smask)]
               ["deep-kids-400" (deep-kids 400)]
               ["deep-kids-3000" (deep-kids 3000)]
               ["deep-array-100000" (deep-direct "[" "]" 100000 "Foo")]
               ["deep-dict-20000" (deep-direct "<< /K " ">>" 20000 "Foo")]
               ["deep-q-50000" (deep-q 50000)]
               ["deep-forms-300" (deep-forms 300)]
               ["deep-outline-3000" (deep-outline 3000)]
               ["deep-structure-3000" (deep-structure 3000)]
               ["pages-flat-20000" (pages-flat 20000)]
               ["pages-many-1500" (many-pages 1500)]
               ["widths-100000" (huge-widths 100000)]
               ["ops-60000" (many-ops 60000)]
               ["words-flate-40000" (many-words-flate 40000)]
               ["bomb-flate-64m" (bomb :flate 64000000)]
               ["bomb-flate-400m" (bomb :flate 400000000)]
               ["bomb-runlength-8m" (bomb :runlength 8000000)]
               ["bomb-hex-1m" (bomb :hex 1000000)]
               ["bomb-a85-8m" (bomb :a85 8000000)]
               ["bomb-image-100m" (bomb-image 100000000)]
               ["bomb-nested-3" (bomb-nested 3)]
               ["chain-flate-200" (filter-chain 200 "/FlateDecode")]
               ["chain-hex-200" (filter-chain 200 "/ASCIIHexDecode")]
               ["chain-lzw-200" (filter-chain 200 "/LZWDecode")])
              (for [k [:range-all :range-32 :many-chars :long-dst :deep-array :inverted :surrogates :unbalanced :neg-count :usecmap]]
                [(str "cmap-" (name k)) (delay (big-cmap k))])
              (for [k [:huge-dims :big-1bit :zero-dims :neg-dims :bpc-17 :indexed-short :indexed-self :cs-deep :decode-short :mask-bpc :dct-garbage
                       :ccitt-columns :jbig2-garbage :jpx-garbage :predictor :predictor-small :lzw-early :inline-garbage :inline-nodata]]
                [(str "image-" (name k)) (delay (bad-image k))])
              (for [k [:first-last :widths-short :widths-junk :differences :encoding-name :matrix-zero :no-subtype :font-is-array :descriptor-junk
                       :cff-junk :type1-junk :cid-w :cid-ordering :encoding-stream]]
                [(str "font-" (name k)) (delay (bad-font k))])
              (for [k [:operands :matrix :size :unbalanced :marked :dash-path :shading :text-clip]]
                [(str "content-" (name k)) (delay (content-torture k))])
              (for [[k v] (sort-by key (cycle-docs))] [k (delay v)]))]
    (vec (for [[nm bs] pdfs] {:name (str "seed:" nm ".pdf") :bytes bs}))))
