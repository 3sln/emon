(ns synth.sig-pdf
  "The fixtures of P8.3 (handwritten signatures, initials and digital signatures, D900-D907): born-digital PDFs written byte for byte, no PDF producer and no library
  (D094). The vector signatures are VECTOR paths, the raster ones JPEG images drawn with the blend mode Multiply, the digital ones AcroForm /Sig fields.

      bb -cp tools:tools/lib tools/synth/sig_pdf.clj            write test/signatures/*.pdf, test/signatures/gold.edn and FIXTURES.edn   (needs pdftoppm and ImageMagick)
      bb -cp tools:tools/lib tools/synth/sig_pdf.clj --check    fail if a committed file is not what this writes (the JPEGs are committed inside the PDFs: the check
                                                                compares the gold only)

    sig-contract    two pages of a purchase agreement. Page 1: clauses and INITIALS (vector, black) in the foot margin. Page 2: clauses; a Buyer block, a rule with a blue VECTOR
                    signature over it, the typed name `Alex Morgan, Director` under it and the label `Unterschrift`; a Seller block, a rule with a RASTER signature over it (an
                    image), the typed name `Jordan Lee` and the label `Firma`; and the same initials in the same margin. No label says `signature` in English: the cues are the rules.
    sig-negatives   NO signature and no initials: a blue vector squiggle in the middle of a paragraph (a doodle: no cue), a rule with a typed name and no ink on it, the word
                    `Signed` far from a chart's line with its axes, a raster scribble (an image, no cue) and a typed `Signature:` label beside blank space.
    sig-scan        a SCANNED page (an image: rendered at 150 dpi, turned a third of a degree, noisy, blurred, JPEG at quality 60): a paragraph, `Authorised signatory`, a rule with
                    a blue pen signature over it, the typed name `Dana Kim` and `Date`. No text layer: the typed name, the label and the paragraph are OCR's, the signature is the strokes.
    sig-digital     one page with three signature FIELDS: a signed one (a /Name, a /M with the time, a /Reason and a /Location), a signed one whose dictionary has no /Name and whose
                    /Contents is a real CMS signature (the signer's name is the certificate's common name), and an unsigned one.

  The gold (`test/signatures/gold.edn`): per document the body words (every one must be in the body), the nodes expected (subkind, page, colour, name; for a digital signature the
  name, date, reason, location and the signed state) and the words that must NOT be in the body."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [synth.pdfw :as w :refer [pstr num*]]))

(def OUT "test/signatures")
(def words (str/split "school house mother water river morning garden letter window summer winter paper table city story friend family number answer problem question picture example country evening machine market office season system teacher village weather yellow bridge button candle dinner engine forest ground hammer island jacket kitchen lesson mirror napkin orange pencil rabbit shadow ticket valley wagon" #" "))

(defn lines [seed n k]
  (let [r (java.util.Random. seed)]
    (vec (repeatedly n #(str/join " " (repeatedly k (fn [] (nth words (.nextInt r (count words))))))))))

(defn txt [font size x y s] (str "BT /" font " " (num* size) " Tf " (num* x) " " (num* y) " Td " (pstr s) " Tj ET\n"))
(defn paragraph [x y lns] (apply str (map-indexed (fn [i s] (txt "F1" 11 x (- y (* i 15)) s)) lns)))

(def ink-black "0.1 0.1 0.1")
(def ink-blue "0.1216 0.2471 0.6235")
(def ink-pen "0.05 0.1 0.45")

;; ---------------------------------------------------------------- the pens (points, y up)

(defn signature-strokes
  "A cursive signature: loops along a baseline and a flourish under it, from (x, y) about 170 pt wide and 44 pt high."
  [x y]
  (let [p (fn [& xs] (str/join " " (map (fn [v i] (num* (if (even? i) (+ x v) (+ y v)))) xs (range))))]
    (str (p 4 2) " m " (p 10 40 26 36 22 12) " c " (p 18 -4 34 0 40 22) " c " (p 46 40 56 38 58 14) " c " (p 60 -2 72 4 76 24) " c "
         (p 80 38 96 36 100 14) " c " (p 104 0 116 10 128 30) " c " (p 140 46 152 18 162 8) " c S\n"
         (p 0 -4) " m " (p 50 -12 110 0 170 -6) " c S\n")))

(defn initials-strokes
  "Initials: two loops about 22 pt wide and 16 pt high from (x, y)."
  [x y]
  (let [p (fn [& xs] (str/join " " (map (fn [v i] (num* (if (even? i) (+ x v) (+ y v)))) xs (range))))]
    (str (p 0 0) " m " (p 2 14 8 14 7 4) " c " (p 6 -2 12 -2 17 8) " c " (p 20 14 22 4 16 1) " c S\n")))

(defn rule [x0 x1 y] (str "0.6 G 0.8 w " (num* x0) " " (num* y) " m " (num* x1) " " (num* y) " l S\n"))

;; ---------------------------------------------------------------- the artwork of a raster signature

(defn artwork-pdf []
  (w/build-pdf
   {1 "<< /Type /Catalog /Pages 2 0 R >>"
    2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 190 60] /Contents 4 0 R /Resources << >> >>"
    4 [:stream "" (str ink-pen " RG 1.7 w 1 J 1 j\n"
                       ;; a different hand from the vector one: taller loops, a long final stroke
                       "8 18 m 16 52 30 50 26 22 c 22 2 42 8 46 30 c 50 50 62 46 64 24 c 66 6 80 12 86 34 c 90 48 104 44 108 24 c 110 10 126 14 130 30 c 134 46 150 22 182 16 c S\n"
                       "1.2 w 20 8 m 70 2 130 12 186 6 c S\n")]}))

(defn scribble-pdf []
  (w/build-pdf
   {1 "<< /Type /Catalog /Pages 2 0 R >>"
    2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 150 100] /Contents 4 0 R /Resources << >> >>"
    4 [:stream "" (str "0.3 G 1.6 w 1 J 1 j\n"
                       "10 20 m 30 90 50 10 70 80 c 85 95 95 20 120 70 c 130 90 140 40 142 20 c S\n"
                       "15 70 m 45 30 75 95 105 25 c S\n")]}))

(defn jpeg-of
  "The artwork PDF rasterised at 200 dpi as JPEG bytes: [bytes width height]."
  [pdf-bytes]
  (let [tmp (str (fs/create-temp-dir {:prefix "emon-sig."}))
        pdf (str tmp "/a.pdf")]
    (w/write-bytes! pdf pdf-bytes)
    (shell "pdftoppm" "-r" "200" "-png" "-singlefile" pdf (str tmp "/a"))
    (shell "magick" (str tmp "/a.png") "-type" "TrueColor" "-quality" "92" "-sampling-factor" "4:4:4" (str tmp "/a.jpg"))
    (let [bs (fs/read-all-bytes (str tmp "/a.jpg"))
          dims (str/split (str/trim (:out (shell {:out :string} "magick" "identify" "-format" "%w %h" (str tmp "/a.jpg")))) #" ")]
      (fs/delete-tree tmp)
      [bs (parse-long (first dims)) (parse-long (second dims))])))

(defn latin1-str [^bytes bs] (String. bs "ISO-8859-1"))

;; ---------------------------------------------------------------- the documents

(def body-1 (lines 31 6 8))
(def body-2 (lines 32 5 8))
(def body-3 (lines 33 6 8))
(def body-4 (lines 34 4 8))

(def contract-p1
  (str "0.1 g 0.1 G\n"
       (txt "F2" 16 72 720 "Purchase Agreement")
       (paragraph 72 690 body-1)
       (paragraph 72 575 body-2)
       (paragraph 72 480 body-3)
       ink-black " RG 1.2 w 1 J\n"
       (initials-strokes 520 40)))

(def contract-p2
  (str "0.1 g 0.1 G\n"
       (txt "F2" 16 72 720 "Signatures")
       (paragraph 72 690 body-4)
       (txt "F1" 11 72 560 "Buyer")
       (rule 72 300 482)
       ink-blue " RG 1.4 w 1 J\n"
       (signature-strokes 90 484)
       "0.1 g\n"
       (txt "F1" 11 72 462 "Alex Morgan, Director")
       (txt "F1" 9 72 448 "Unterschrift")
       (txt "F1" 11 72 400 "Seller")
       (rule 72 300 318)
       "q /GM gs q 190 0 0 60 88 320 cm /Im1 Do Q Q\n"
       "0.1 g\n"
       (txt "F1" 11 72 304 "Jordan Lee")
       (txt "F1" 9 72 290 "Firma")
       ink-black " RG 1.2 w 1 J\n"
       (initials-strokes 520 40)))

(def negatives
  (str "0.1 g 0.1 G\n"
       (txt "F2" 16 72 720 "Quarterly Notes")
       (paragraph 72 690 body-1)
       ;; a doodle in blue between two paragraphs: no cue near it
       ink-blue " RG 1.4 w 1 J\n"
       (signature-strokes 300 590)
       "0.1 g 0.1 G\n"
       (paragraph 72 520 body-2)
       ;; a form line with a typed name and nothing written on it
       (rule 72 280 410)
       (txt "F1" 11 72 396 "Casey Brown, Manager")
       ;; the word `Signed` far from a chart's line; the line stands on its x axis, which a y axis meets
       (txt "F1" 11 72 80 "Signed copies are kept in the archive.")
       ink-blue " RG 1.5 w 80 215 m 120 245 150 185 190 225 c 220 257 250 195 300 235 c S\n"
       "0.1 G 0.8 w 80 205 m 320 205 l S 80 205 m 80 290 l S\n"
       ;; a raster scribble, no cue
       "q /GM gs q 150 0 0 100 330 215 cm /Im1 Do Q Q\n"
       "0.1 g\n"
       (txt "F1" 11 72 150 "Signature:")))

(def scan-stream
  (str "0.1 g 0.1 G\n"
       (txt "F2" 16 72 720 "Supplier Declaration")
       (paragraph 72 690 body-1)
       (txt "F1" 11 72 560 "Authorised signatory")
       (rule 72 300 482)
       ink-pen " RG 1.5 w 1 J\n"
       (signature-strokes 92 484)
       "0.1 g\n"
       (txt "F1" 11 72 462 "Dana Kim")
       (txt "F1" 9 72 448 "Date")))

(defn scan-pdf []
  (let [tmp (str (fs/create-temp-dir {:prefix "emon-sigscan."}))
        pdf (str tmp "/a.pdf")]
    (w/write-bytes! pdf (w/build-pdf
                         {1 "<< /Type /Catalog /Pages 2 0 R >>"
                          2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                          3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> >>"
                          4 [:stream "" scan-stream]
                          8 (w/font-objs 8)
                          9 (w/font-objs 9)}))
    (shell "pdftoppm" "-r" "150" "-png" "-singlefile" pdf (str tmp "/a"))
    (shell "magick" (str tmp "/a.png") "-background" "white" "-rotate" "0.35" "-gravity" "center" "-crop" "1275x1650+0+0" "+repage"
           "-seed" "17" "-attenuate" "0.15" "+noise" "Gaussian" "-blur" "0x0.4" "-type" "TrueColor" "-quality" "60" (str tmp "/a.jpg"))
    (let [bs (fs/read-all-bytes (str tmp "/a.jpg"))]
      (fs/delete-tree tmp)
      (w/build-pdf
       {1 "<< /Type /Catalog /Pages 2 0 R >>"
        2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
        3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /XObject << /Im1 10 0 R >> >> >>"
        4 [:stream "" "q 612 0 0 792 0 0 cm /Im1 Do Q"]
        10 [:stream "/Type /XObject /Subtype /Image /Width 1275 /Height 1650 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode" (latin1-str bs)]}))))

;; the signature dictionary of the first field and of the second (no /Name; the certificate's), read from the committed CMS file
(defn cms-hex [] (str/trim (slurp (str OUT "/cms.hex"))))

(def digital-stream
  (str "0.1 g 0.1 G\n"
       (txt "F2" 16 72 720 "Service Approval")
       (paragraph 72 690 body-4)
       (txt "F1" 11 72 560 "Approved by")
       (txt "F1" 11 72 480 "Vendor")
       (txt "F1" 11 72 400 "Counter-signed by")
       "0.6 G 0.8 w 190 556 m 400 556 l S 190 476 m 400 476 l S 190 396 m 400 396 l S\n"))

(defn digital-pdf []
  (w/build-pdf
   {1 "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [10 0 R 11 0 R 12 0 R] /SigFlags 3 >> >>"
    2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /Annots [10 0 R 11 0 R 12 0 R] >>"
    4 [:stream "" digital-stream]
    8 (w/font-objs 8)
    9 (w/font-objs 9)
    10 "<< /Type /Annot /Subtype /Widget /FT /Sig /T (ApproverSig) /F 4 /Rect [190 556 400 590] /P 3 0 R /V 20 0 R >>"
    11 "<< /Type /Annot /Subtype /Widget /FT /Sig /T (VendorSig) /F 4 /Rect [190 476 400 510] /P 3 0 R /V 21 0 R >>"
    12 "<< /Type /Annot /Subtype /Widget /FT /Sig /T (CounterSig) /F 4 /Rect [190 396 400 430] /P 3 0 R >>"
    20 (str "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /Name (Morgan Reyes) /M (D:20260312143005+01'00') "
            "/Reason (I approve this document) /Location (Berlin) /ByteRange [0 100 200 300] /Contents <" (apply str (repeat 64 "00")) "> >>")
    21 (str "<< /Type /Sig /Filter /Adobe.PPKLite /SubFilter /adbe.pkcs7.detached /M (D:20260401) "
            "/ByteRange [0 100 200 300] /Contents <" (cms-hex) "> >>")}))

(def docs
  [{:id "sig-contract" :kind :born
    :body (concat ["Purchase Agreement"] body-1 body-2 body-3 ["Signatures"] body-4 ["Buyer" "Alex Morgan, Director" "Unterschrift" "Seller" "Jordan Lee" "Firma"])
    :nodes [{:page 1 :subkind "initials" :color "black"}
            {:page 2 :subkind "signature" :color "blue" :name "Alex Morgan"}
            {:page 2 :subkind "signature" :color "blue" :name "Jordan Lee" :raster true}
            {:page 2 :subkind "initials" :color "black"}]
    :leak []}
   {:id "sig-negatives" :kind :born
    :body (concat ["Quarterly Notes"] body-1 body-2 ["Casey Brown, Manager" "Signature:" "Signed copies are kept in the archive."])
    :nodes []
    :leak []}
   {:id "sig-scan" :kind :scan
    :body (concat ["Supplier Declaration"] body-1 ["Authorised signatory" "Dana Kim" "Date"])
    :nodes [{:page 1 :subkind "signature" :color "blue" :name "Dana Kim"}]
    :leak []}
   {:id "sig-digital" :kind :born
    :body (concat ["Service Approval"] body-4 ["Approved by" "Vendor" "Counter-signed by"])
    :nodes [{:page 1 :subkind "digital-signature" :signed true :name "Morgan Reyes" :date "2026-03-12T14:30:05+01:00" :reason "I approve this document" :location "Berlin"}
            {:page 1 :subkind "digital-signature" :signed true :name "Taylor Certificate" :date "2026-04-01"}
            {:page 1 :subkind "digital-signature" :signed false}]
    :leak []}])

(defn pdf-of [id]
  (case id
    "sig-contract"
    (let [[sig sw sh] (jpeg-of (artwork-pdf))]
      (w/build-pdf
       {1 "<< /Type /Catalog /Pages 2 0 R >>"
        2 "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>"
        3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> >>"
        4 [:stream "" contract-p1]
        5 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> /ExtGState << /GM 7 0 R >> /XObject << /Im1 10 0 R >> >> >>"
        6 [:stream "" contract-p2]
        7 "<< /Type /ExtGState /BM /Multiply >>"
        8 (w/font-objs 8)
        9 (w/font-objs 9)
        10 [:stream (str "/Type /XObject /Subtype /Image /Width " sw " /Height " sh " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode") (latin1-str sig)]}))
    "sig-negatives"
    (let [[sc sw sh] (jpeg-of (scribble-pdf))]
      (w/build-pdf
       {1 "<< /Type /Catalog /Pages 2 0 R >>"
        2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
        3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> /ExtGState << /GM 7 0 R >> /XObject << /Im1 10 0 R >> >> >>"
        4 [:stream "" negatives]
        7 "<< /Type /ExtGState /BM /Multiply >>"
        8 (w/font-objs 8)
        9 (w/font-objs 9)
        10 [:stream (str "/Type /XObject /Subtype /Image /Width " sw " /Height " sh " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode") (latin1-str sc)]}))
    "sig-scan" (scan-pdf)
    "sig-digital" (digital-pdf)))

;; ---------------------------------------------------------------- the gold Models for the scorer (gold/ID.gold.json, the fixture set `emon-sigs` of tools/accuracy)

(def sig-blue {"subkind" "signature" "color" "blue"})

(defn joined [lns] (str/join " " lns))

(def blocks
  {"sig-contract"
   [[:section 1
     [[:heading 1 "Purchase Agreement"] [:paragraph (joined body-1)] [:paragraph (joined body-2)] [:paragraph (joined body-3)]
      [:seal {"subkind" "initials" "color" "black" "text" ""}]]]
    [:section 1
     [[:heading 1 "Signatures"] [:paragraph (joined body-4)] [:paragraph "Buyer"]
      [:seal (assoc sig-blue "name" "Alex Morgan" "text" "")]
      [:paragraph "Alex Morgan, Director"] [:paragraph "Unterschrift"] [:paragraph "Seller"]
      [:seal (assoc sig-blue "name" "Jordan Lee" "text" "")]
      [:paragraph "Jordan Lee"] [:paragraph "Firma"]
      [:seal {"subkind" "initials" "color" "black" "text" ""}]]]]
   "sig-negatives"
   [[:section 1
     [[:heading 1 "Quarterly Notes"] [:paragraph (joined body-1)] [:paragraph (joined body-2)]
      [:paragraph "Casey Brown, Manager"] [:figure "chart"] [:paragraph "Signature:"] [:paragraph "Signed copies are kept in the archive."]]]]
   "sig-digital"
   [[:section 1
     [[:heading 1 "Service Approval"] [:paragraph (joined body-4)]
      [:paragraph "Approved by"]
      [:seal {"subkind" "digital-signature" "signed" true "name" "Morgan Reyes" "date" "2026-03-12T14:30:05+01:00" "reason" "I approve this document" "location" "Berlin"}]
      [:paragraph "Vendor"]
      [:seal {"subkind" "digital-signature" "signed" true "name" "Taylor Certificate" "date" "2026-04-01"}]
      [:paragraph "Counter-signed by"]
      [:seal {"subkind" "digital-signature" "signed" false}]]]]
   "sig-scan"
   [[:section 1
     [[:heading 1 "Supplier Declaration"] [:paragraph (joined body-1)] [:paragraph "Authorised signatory"]
      [:seal (assoc sig-blue "name" "Dana Kim" "text" "")]
      [:paragraph "Dana Kim"] [:paragraph "Date"]]]]})

(defn model-nodes
  "Blocks to gold nodes with ids in preorder from `n`: [nodes next-id]."
  [bs n]
  (reduce (fn [[out n] [k & more]]
            (case k
              :section (let [[lvl kids] more [cs n2] (model-nodes kids (inc n))]
                         [(conj out {"id" n "kind" "section" "level" lvl "children" cs}) n2])
              :heading (let [[lvl t] more] [(conj out {"id" n "kind" "heading" "level" lvl "text" t}) (inc n)])
              :paragraph [(conj out {"id" n "kind" "paragraph" "text" (first more)}) (inc n)]
              :figure [(conj out {"id" n "kind" "figure" "subkind" (first more)}) (inc n)]
              :seal [(conj out (merge {"id" n "kind" "seal"} (first more))) (inc n)]))
          [[] n] bs))

(def gold-attributes
  {"caption" ["label"] "cell" ["rowspan" "colspan" "header" "empty"] "figure" ["subkind" "label" "alt" "labels"] "heading" ["level" "number" "numbering"]
   "item" ["marker" "value"] "list" ["ordered" "scheme" "start"] "note" ["role" "marker"] "row" ["repeated"] "rowgroup" ["role"]
   "seal" ["subkind" "name" "date" "color" "signed" "reason" "location"] "section" ["level"] "table" ["profile" "columns" "continued"]})

(defn gold-json [id pages]
  (let [[kids _] (model-nodes (get blocks id) 1)]
    {"emon-gold" "1.0"
     "id" id
     "provenance" {"pdf" (str id ".pdf") "producer" "tools/synth/sig_pdf.clj (P8.3)"}
     "annotated" {"attributes" gold-attributes "dropped" ["page-number" "running-title" "watermark" "duplicate-header-row"] "furniture" true "inlines" true
                  "pages" ["n" "w" "h" "label"]
                  "structure" ["section" "heading" "paragraph" "list" "item" "table" "rowgroup" "row" "cell" "caption" "figure" "seal" "quote" "code" "note"]
                  "text" true}
     "model" {"body" {"id" 0 "kind" "document" "children" kids}
              "dropped" [] "emon" "1.0" "furniture" [] "meta" {}
              "pages" (vec (for [p (range 1 (inc pages))] {"n" p "w" 612 "h" 792 "rot" 0}))
              "settings" {"locale" "en"}
              "source" {"format" "pdf" "lang" "en" "pages" pages}}}))

(defn gold-json-text [id pages] (str (json/generate-string (gold-json id pages) {:pretty true}) "\n"))

(defn gold []
  (into (sorted-map)
        (for [{:keys [id body nodes leak]} docs]
          [id {:body (vec (mapcat #(str/split % #"\s+") body)) :nodes (vec nodes) :leak (vec leak)}])))

(defn gold-text [] (str ";; The gold of the P8.3 fixtures (tools/synth/sig_pdf.clj): the body words, the nodes expected and the words that must not be in the body.\n"
                        (with-out-str (pp/pprint (gold)))))

(defn fixtures-text []
  (str ";; The signature fixtures `bb sigs-record` records (P8.3): :born pages are rendered by pdfium, :scan pages are decoded.\n"
       (with-out-str (pp/pprint (into (sorted-map) (for [{:keys [id kind]} docs] [id {:pdf (str OUT "/" id ".pdf") :kind kind}]))))))

(def page-counts {"sig-contract" 2 "sig-negatives" 1 "sig-digital" 1 "sig-scan" 1})

(let [check? (some #{"--check"} *command-line-args*)]
  (fs/create-dirs OUT)
  (fs/create-dirs (str OUT "/gold"))
  (if check?
    (System/exit (if (and (= (gold-text) (slurp (str OUT "/gold.edn"))) (= (fixtures-text) (slurp (str OUT "/FIXTURES.edn")))
                          (every? (fn [d] (= (gold-json-text (:id d) (page-counts (:id d))) (slurp (str OUT "/gold/" (:id d) ".gold.json")))) docs))
                   0
                   (do (println "FAIL the gold or FIXTURES.edn is not what sig_pdf.clj writes") 1)))
    (do (doseq [d docs]
          (w/write-bytes! (str OUT "/" (:id d) ".pdf") (pdf-of (:id d)))
          (spit (str OUT "/gold/" (:id d) ".gold.json") (gold-json-text (:id d) (page-counts (:id d)))))
        (spit (str OUT "/gold.edn") (gold-text))
        (spit (str OUT "/FIXTURES.edn") (fixtures-text))
        (println "wrote" OUT))))
