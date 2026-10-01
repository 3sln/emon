#!/usr/bin/env bb
;; Tagged-PDF synthetic pairs (DEVLOG P3.7, D076, D150): one CORRECTLY tagged document and deliberately MIS-TAGGED twins
;; of it, written byte for byte from a description (no PDF producer, no PDF library).
;;
;;     bb tools/synth/tagged_pair.clj            write test/pdf/tagged/*.pdf
;;     bb tools/synth/tagged_pair.clj --check    fail if the committed files are not what this writes
;;
;; The page is the same in the first six files: the same text at the same places on two US Letter pages (a title, four
;; section headings, paragraphs, a numbered list, a ruled table with header cells, a figure with a caption, a running
;; header and footer, a DRAFT watermark). What differs is only what the file SAYS about the page:
;;
;;   good.pdf            the tree an accessibility tool would write: H1 H2 P, L/LI/Lbl/LBody with ListNumbering Decimal,
;;                       Table/TR/TH/TD with Scope (through a class) and Summary, Figure with Alt, Caption, a Span with
;;                       ActualText and E, /Lang on the document and on a French paragraph, custom roles through the
;;                       RoleMap, a ParentTree; header, footer, watermark and the table's rules are /Artifact with
;;                       the Type and Subtype that say so.
;;   bad.pdf             the three failures D076 names, together: every block is a `P` directly under the document
;;                       (no list, no table), the headings that remain have the wrong levels, the artifacts are
;;                       mis-marked (a running header tagged `P`, body text marked as Pagination).
;;   bad-allp.pdf        only the first: everything is P, flat.
;;   bad-levels.pdf      only the second: the right tree with the wrong heading levels (the title H6, the sections
;;                       H3 H1 H4 H2).
;;   bad-artifacts.pdf   only the third: the running header, footer and watermark are tagged P and two body blocks are
;;                       marked as /Artifact (a heading as a Footer, a paragraph as Pagination).
;;   bad-order.pdf       the right roles in the wrong order: every list of kids reversed (the tree reads from the bottom).
;;   odd.pdf             not a twin: every shape the reader must survive (loops, shared elements, Form XObject content, ...).
;;
;; THE EXPECTATIONS (kin/pdfstructtest.kin and tools/oracle/tagged-check.py) are the counts these definitions imply.
;; Babashka (D094); the PDF is classic (one cross-reference table), the fonts are two of the standard 14.
(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParentFile .getPath))
(def out-dir (str root "/test/pdf/tagged"))

;; ------------------------------------------------------------------ the PDF writer

(defn latin1 ^bytes [^String s] (.getBytes s "ISO-8859-1"))

(defn build-pdf
  "objs: {num body}. A body is a string (an object) or [:stream dict-string data-string]."
  [objs]
  (let [buf (java.io.ByteArrayOutputStream.)
        offs (atom {})
        w (fn [s] (.write buf (latin1 s)))]
    (w "%PDF-1.7\n%âãÏÓ\n")
    (doseq [n (sort (keys objs))]
      (swap! offs assoc n (.size buf))
      (let [b (objs n)]
        (w (str n " 0 obj\n"))
        (if (vector? b)
          (let [[_ dict data] b]
            (w (str "<< " dict " /Length " (count (latin1 data)) " >>\nstream\n"))
            (w data)
            (w "\nendstream"))
          (w b))
        (w "\nendobj\n")))
    (let [xref (.size buf)
          n (inc (apply max (keys objs)))]
      (w (str "xref\n0 " n "\n0000000000 65535 f \n"))
      (doseq [i (range 1 n)]
        (w (if-let [o (@offs i)] (format "%010d 00000 n \n" o) "0000000000 65535 f \n")))
      (w (str "trailer\n<< /Size " n " /Root 1 0 R >>\nstartxref\n" xref "\n%%EOF\n")))
    (.toByteArray buf)))

(defn pstr [s] (str "(" (str/replace s #"([\\()])" "\\\\$1") ")"))

;; ------------------------------------------------------------------ the page

;; Each mark is a piece of content that carries an id, a page, drawing operators and, when the file is tagged, the
;; structure element it belongs to. :ops are the operators between BDC and EMC.
(defn t
  "A text object: font key (:F1 regular, :F2 bold), size, x, y, and one or more lines (14 pt apart)."
  [font size x y & lines]
  (str "BT /" (name font) " " size " Tf " (* 1.25 size) " TL " x " " y " Td "
       (str/join " T* " (map #(str (pstr %) " Tj") lines)) " ET"))

(def marks
  "Every piece of content, in content-stream order. :page 0 or 1; :art (type subtype) is what the CORRECT file says it
  is when it is furniture; otherwise :id names it for the tree."
  [;; page 1
   {:id :header-1 :page 0 :art [:Pagination :Header] :ops (t :F1 9 72 752 "Quarterly Report")}
   {:id :mark-water :page 0 :art [:Pagination :Watermark]
    :ops (str "q 0.86 g BT /F2 72 Tf 0.7071 0.7071 -0.7071 0.7071 170 250 Tm (DRAFT) Tj ET Q")}
   {:id :title :page 0 :ops (t :F2 24 72 700 "Quarterly Report")}
   {:id :intro :page 0 :ops (t :F1 11 72 670 "This report summarises the quarter in three short sections."
                                   "Figures are in thousands of dollars unless stated otherwise.")}
   {:id :h-results :page 0 :ops (t :F2 16 72 620 "1. Results")}
   {:id :results :page 0 :ops (t :F1 11 72 596 "Revenue grew by twelve percent on the previous quarter, driven by"
                                     "subscriptions. Costs were flat.")}
   {:id :h-priorities :page 0 :ops (t :F2 16 72 548 "2. Priorities")}
   {:id :lbl-1 :page 0 :ops (t :F1 11 72 524 "1.")}
   {:id :li-1 :page 0 :ops (t :F1 11 96 524 "Renew the two largest contracts.")}
   {:id :lbl-2 :page 0 :ops (t :F1 11 72 508 "2.")}
   {:id :li-2 :page 0 :ops (t :F1 11 96 508 "Hire three engineers.")}
   {:id :lbl-3 :page 0 :ops (t :F1 11 72 492 "3.")}
   {:id :li-3 :page 0 :ops (t :F1 11 96 492 "Close the Lisbon office.")}
   {:id :h-summary :page 0 :ops (t :F2 16 72 452 "3. Summary")}
   {:id :rules :page 0 :art [:Layout nil]
    :ops "0.5 w 72 436 m 500 436 l S 72 418 m 500 418 l S 72 400 m 500 400 l S 72 382 m 500 382 l S"}
   {:id :th-1 :page 0 :ops (t :F2 11 78 422 "Region")}
   {:id :th-2 :page 0 :ops (t :F2 11 238 422 "Revenue")}
   {:id :th-3 :page 0 :ops (t :F2 11 398 422 "Growth")}
   {:id :td-1 :page 0 :ops (t :F1 11 78 404 "North")}
   {:id :td-2 :page 0 :ops (t :F1 11 238 404 "1,240")}
   {:id :td-3 :page 0 :ops (t :F1 11 398 404 "12%")}
   {:id :td-4 :page 0 :ops (t :F1 11 78 386 "South")}
   {:id :td-5 :page 0 :ops (t :F1 11 238 386 "980")}
   {:id :td-6 :page 0 :ops (t :F1 11 398 386 "8%")}
   {:id :footer-1 :page 0 :art [:Pagination :Footer] :ops (t :F1 9 280 36 "Page 1")}
   ;; page 2
   {:id :header-2 :page 1 :art [:Pagination :Header] :ops (t :F1 9 72 752 "Quarterly Report")}
   {:id :h-outlook :page 1 :ops (t :F2 16 72 700 "4. Outlook")}
   {:id :q3 :page 1 :ops (t :F1 11 72 676 "Q3")}
   {:id :outlook :page 1 :ops (t :F1 11 90 676 "should be steadier, with revenue near 2,300 if the")}
   {:id :outlook-2 :page 1 :ops (t :F1 11 72 662 "contracts renew.")}
   {:id :figure :page 1 :ops "0.6 0.8 0.9 rg 72 520 100 120 re f 0.4 0.6 0.8 rg 190 520 100 90 re f 0.2 0.4 0.7 rg 308 520 100 150 re f"}
   {:id :caption :page 1 :ops (t :F1 10 72 500 "Figure 1: Revenue by month.")}
   {:id :french :page 1 :ops (t :F1 11 72 460 "Bonjour tout le monde, voici le r\u00e9sum\u00e9 en fran\u00e7ais.")}
   {:id :footer-2 :page 1 :art [:Pagination :Footer] :ops (t :F1 9 280 36 "Page 2")}])

(def mark-by-id (into {} (map (juxt :id identity) marks)))

;; ------------------------------------------------------------------ the tree

;; A node: {:role "H1" :marks [ids] :kids [nodes] :attrs {...}}. :marks are the content ids it holds directly; a mark that
;; is on another page than the element's first is written as an /MCR dictionary.
(defn n [role & {:as m}] (merge {:role role} m))

(def good-tree
  (n "Document" :lang "en-US"
     :kids
     [(n "Heading" :marks [:title])                         ; custom role -> H1 through the RoleMap
      (n "Body" :marks [:intro])                            ; custom role -> P
      (n "Sect" :kids [(n "H2" :marks [:h-results])
                       (n "P" :marks [:results])])
      (n "Sect" :kids [(n "H2" :marks [:h-priorities])
                       (n "L" :attrs {:O "List" :ListNumbering "Decimal"}
                          :kids [(n "LI" :kids [(n "Lbl" :marks [:lbl-1]) (n "LBody" :marks [:li-1])])
                                 (n "LI" :kids [(n "Lbl" :marks [:lbl-2]) (n "LBody" :marks [:li-2])])
                                 (n "LI" :kids [(n "Lbl" :marks [:lbl-3]) (n "LBody" :marks [:li-3])])])])
      (n "Sect" :kids [(n "H2" :marks [:h-summary])
                       (n "Table" :attrs {:O "Table" :Summary "Revenue and growth by region"}
                          :kids [(n "TR" :kids [(n "TH" :class "ColHead" :marks [:th-1])
                                                (n "TH" :class "ColHead" :marks [:th-2])
                                                (n "TH" :class "ColHead" :marks [:th-3])])
                                 (n "TR" :kids [(n "TD" :marks [:td-1]) (n "TD" :marks [:td-2]) (n "TD" :marks [:td-3])])
                                 (n "TR" :kids [(n "TD" :marks [:td-4]) (n "TD" :marks [:td-5]) (n "TD" :marks [:td-6])])])])
      (n "Sect" :kids [(n "H2" :marks [:h-outlook])
                       (n "P" :kids [(n "Span" :marks [:q3] :actual "third quarter" :exp "Quarter three")
                                     (n "Span" :marks [:outlook :outlook-2])])
                       (n "Figure" :marks [:figure] :alt "Bar chart of revenue by month" :mcr true)
                       (n "Caption" :marks [:caption])
                       (n "P" :marks [:french] :lang "fr-FR")])]))

(defn all-content-ids [node]
  (concat (:marks node) (mapcat all-content-ids (:kids node))))

;; ------------------------------------------------------------------ the variants

(defn walk-nodes [f node]
  (let [node' (f node)]
    (cond-> node' (:kids node') (update :kids #(mapv (partial walk-nodes f) %)))))

(defn flat-p
  "Everything P: no structure, one P under the Document for each block of content."
  [tree ids]
  (n "Document" :lang (:lang tree) :kids (mapv #(n "P" :marks [%]) ids)))

(defn reverse-kids [tree]
  (walk-nodes (fn [nd] (cond-> nd (:kids nd) (update :kids (comp vec reverse)))) tree))

(defn wrong-levels [tree]
  (let [h (atom 0)
        sect-levels ["H3" "H1" "H4" "H2"]]
    (walk-nodes (fn [nd]
                  (case (:role nd)
                    "Heading" (assoc nd :role "H6")
                    "H2" (let [r (nth sect-levels (mod @h 4))] (swap! h inc) (assoc nd :role r))
                    nd))
                tree)))

(defn roles-to-p-except-headings
  "The `bad` tree: flat, every block a P, but the headings keep a (wrong) level. `ids`: the tagged content, in order."
  [tree ids]
  (let [lv {:title "H6" :h-results "H3" :h-priorities "H1" :h-summary "H4" :h-outlook "H2"}]
    (n "Document" :lang (:lang tree)
       :kids (mapv (fn [id] (n (get lv id "P") :marks [id])) ids))))

;; a variant: the tree, and which marks are furniture in THIS file (id -> [Type Subtype] or nil for tagged content)
(defn correct-art [] (into {} (for [m marks :when (:art m)] [(:id m) (:art m)])))

(def mis-artifacts
  "The furniture the author did not mark, and body content they did: the header, footer and watermark are ordinary tagged
  text; a section heading is a Footer and a paragraph is Pagination (no subtype)."
  {:h-results [:Pagination :Footer] :results [:Pagination nil]})

(defn variant [which]
  (let [ids (vec (all-content-ids good-tree))]
    (case which
      :good {:tree good-tree :art (correct-art)}
      :bad-allp {:tree (flat-p good-tree ids) :art (correct-art)}
      :bad-levels {:tree (wrong-levels good-tree) :art (correct-art)}
      :bad-artifacts (let [art (merge {:rules [:Layout nil]} mis-artifacts)
                           drop-ids #{:h-results :results}
                           ;; the two blocks that became artifacts leave the tree; the furniture joins it as ordinary P
                           tree (walk-nodes (fn [nd]
                                              (cond-> nd
                                                (:kids nd) (update :kids (fn [ks] (vec (remove #(and (seq (:marks %)) (empty? (:kids %))
                                                                                                    (every? drop-ids (:marks %))) ks))))))
                                            good-tree)
                           [pre outlook] (split-at 5 (:kids tree))
                           tree (assoc tree :kids (vec (concat [(n "P" :marks [:header-1 :mark-water])]
                                                               pre
                                                               [(n "P" :marks [:footer-1]) (n "P" :marks [:header-2])]
                                                               outlook
                                                               [(n "P" :marks [:footer-2])])))]
                       {:tree tree :art art})
      :bad-order {:tree (reverse-kids good-tree) :art (correct-art)}
      :bad (let [art (merge (select-keys (correct-art) [:rules]) mis-artifacts)]
             {:tree (roles-to-p-except-headings good-tree (vec (remove art (map :id marks))))
              :art art}))))

;; ------------------------------------------------------------------ writing a variant

(def fixed-objs
  {1 :catalog 2 :pages 3 :struct-root 4 :page-0 5 :page-1 6 :content-0 7 :content-1 8 :font-1 9 :font-2 10 :parent-tree})

(defn assign
  "Pre-order object numbers from 11 for every node: returns the tree with :obj on each node."
  [tree]
  (let [c (atom 10)]
    (walk-nodes (fn [nd] (assoc nd :obj (swap! c inc))) tree)))

(defn nodes [tree] (cons tree (mapcat nodes (:kids tree))))

(defn content-stream
  "The operators of one page: every mark in order, with an MCID per tagged mark (counted per page), tagged by its element's role."
  [page art owner-role]
  (let [mcid (atom -1)
        ids (atom [])]
    [(str/join "\n"
               (for [m marks :when (= page (:page m))
                     :let [a (get art (:id m))]]
                 (if a
                   (str "/Artifact <</Type /" (name (first a)) (when (second a) (str " /Subtype /" (name (second a)))) ">> BDC\n"
                        (:ops m) "\nEMC")
                   (let [k (swap! mcid inc)]
                     (swap! ids conj (:id m))
                     (str "/" (owner-role (:id m)) " <</MCID " k ">> BDC\n" (:ops m) "\nEMC")))))
     @ids]))

(defn write-variant [which]
  (let [{:keys [tree art]} (variant which)
        tree (assign tree)
        owner (into {} (for [nd (nodes tree) id (:marks nd)] [id (:role nd)]))
        ;; marks that no element holds (artifacts) have no owner; the tag of a mark is its element's role
        [c0 ids0] (content-stream 0 art #(get owner % "P"))
        [c1 ids1] (content-stream 1 art #(get owner % "P"))
        mcid-of (fn [id] (let [i0 (.indexOf ^java.util.List ids0 id)] (if (>= i0 0) [0 i0] [1 (.indexOf ^java.util.List ids1 id)])))
        page-obj {0 4 1 5}
        elem-page (fn [nd] (some-> (first (:marks nd)) mcid-of first))
        ref (fn [o] (str o " 0 R"))
        node-obj (fn [nd] (:obj nd))
        parent-of (let [m (atom {})] (doseq [nd (nodes tree) k (:kids nd)] (swap! m assoc (:obj k) nd)) @m)
        elem-body
        (fn [nd]
          (let [pn (parent-of (:obj nd))
                role (:role nd)
                own (mapv mcid-of (:marks nd))
                kids (:kids nd)
                ;; K: bare MCIDs for content on the element's own page (an /MCR where :mcr or another page), then element kids
                kitems (concat
                        (for [[p i] own]
                          (if (or (:mcr nd) (not= p (elem-page nd)))
                            (str "<< /Type /MCR /Pg " (ref (page-obj p)) " /MCID " i " >>")
                            (str i)))
                        (map #(ref (:obj %)) kids))
                k (cond (empty? kitems) nil
                        (= 1 (count kitems)) (first kitems)
                        :else (str "[" (str/join " " kitems) "]"))
                attrs (:attrs nd)]
            (str "<< /Type /StructElem /S /" role
                 " /P " (ref (if pn (:obj pn) 3))
                 (when (seq own) (str " /Pg " (ref (page-obj (elem-page nd)))))
                 (when-let [l (:lang nd)] (str " /Lang " (pstr l)))
                 (when-let [a (:alt nd)] (str " /Alt " (pstr a)))
                 (when-let [a (:actual nd)] (str " /ActualText " (pstr a)))
                 (when-let [e (:exp nd)] (str " /E " (pstr e)))
                 (when-let [c (:class nd)] (str " /C /" c))
                 (when attrs
                   (str " /A << /O /" (:O attrs)
                        (when-let [s (:Summary attrs)] (str " /Summary " (pstr s)))
                        (when-let [s (:ListNumbering attrs)] (str " /ListNumbering /" s))
                        " >>"))
                 (when k (str " /K " k))
                 " >>")))
        elem-objs (into {} (for [nd (nodes tree)] [(:obj nd) (elem-body nd)]))
        ;; the parent tree: for each page the element object of each MCID
        owner-obj (into {} (for [nd (nodes tree) id (:marks nd)] [id (:obj nd)]))
        pt-arr (fn [ids] (str "[" (str/join " " (map #(ref (owner-obj % 3)) ids)) "]"))
        doc-obj (:obj tree)
        objs (merge
              elem-objs
              {1 (str "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 3 0 R /MarkInfo << /Marked true >> /Lang (en-US) >>")
               2 "<< /Type /Pages /Kids [4 0 R 5 0 R] /Count 2 >>"
               3 (str "<< /Type /StructTreeRoot /K " (ref doc-obj)
                      " /RoleMap << /Heading /H1 /Body /P >>"
                      " /ClassMap << /ColHead << /O /Table /Scope /Column >> >>"
                      " /ParentTree 10 0 R /ParentTreeNextKey 2 >>")
               4 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /StructParents 0 >>"
               5 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 7 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /StructParents 1 >>"
               6 [:stream "" c0]
               7 [:stream "" c1]
               8 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
               9 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
               10 (str "<< /Nums [0 " (pt-arr ids0) " 1 " (pt-arr ids1) "] >>")})]
    (build-pdf objs)))

;; ------------------------------------------------------------------ odd.pdf: every shape the reader must survive

(defn odd-pdf
  "A tree that exercises what real producers get wrong and the standard allows: a RoleMap with a loop, a custom role
  nobody mapped, a standard role the RoleMap remaps, Hn past H6, a K that names its own element, an element reached from
  two parents, a K with nulls, strings and a missing object, an MCID claimed twice and one nobody claims, marked content
  in a Form XObject joined through /Stm, an object reference to a Link annotation, attributes through /A and the ClassMap
  (an owner that does not match, a revision number, an array of classes), an element whose /Pg is not a page, and a chain
  of 70 nested elements (the depth limit). Text with no marked content at all, and an /Artifact."
  []
  (let [chain (for [i (range 70)]
                [(+ 100 i) (str "<< /Type /StructElem /S /Div /P " (if (zero? i) "47 0 R" (str (+ 99 i) " 0 R"))
                                (when (< i 69) (str " /K " (+ 101 i) " 0 R")) " >>")])
        objs
        (merge
         (into {} chain)
         {1 "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 3 0 R /MarkInfo << /Marked true /Suspects true >> /Lang (de) >>"
          2 "<< /Type /Pages /Kids [4 0 R] /Count 1 >>"
          3 (str "<< /Type /StructTreeRoot /K [10 0 R]"
                 " /RoleMap << /Foo /Bar /Bar /Foo /Heading /H2 /Para /P /P /Span /Quote /Quote /Loner /Nowhere >>"
                 " /ClassMap << /C1 << /O /Table /Scope /Row /ColSpan 2 >>"
                 "              /C2 [ << /O /Table /RowSpan 3 >> << /O /List /ListNumbering /Disc >> ] >>"
                 " /ParentTree 30 0 R /ParentTreeNextKey 6 >>")
          4 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /StructParents 0 /Annots [7 0 R] /Resources << /Font << /F1 8 0 R >> /XObject << /Fm1 6 0 R >> >> >>"
          5 [:stream "" (str/join "\n"
                                  ["/P <</MCID 0>> BDC BT /F1 12 Tf 72 700 Td (Alpha) Tj ET EMC"
                                   "/Heading <</MCID 1>> BDC BT /F1 18 Tf 72 670 Td (Beta) Tj ET EMC"
                                   "/Span <</MCID 2>> BDC BT /F1 12 Tf 72 640 Td (Gamma) Tj ET EMC"
                                   "q /Fm1 Do Q"
                                   "/Artifact <</Type /Pagination /Subtype /Footer>> BDC BT /F1 9 Tf 280 30 Td (Page 1) Tj ET EMC"
                                   "/P <</MCID 3>> BDC BT /F1 12 Tf 72 560 Td (Delta) Tj ET EMC"
                                   "/P <</MCID 4>> BDC BT /F1 12 Tf 72 540 Td (Epsilon) Tj ET EMC"
                                   "BT /F1 12 Tf 72 520 Td (Stray) Tj ET"])]
          6 [:stream "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /StructParents 5 /Resources << /Font << /F1 8 0 R >> >>"
             (str/join "\n" ["/P <</MCID 0>> BDC BT /F1 12 Tf 72 600 Td (Form text) Tj ET EMC"
                             "/Span <</MCID 1>> BDC BT /F1 12 Tf 72 580 Td (Form span) Tj ET EMC"])]
          7 "<< /Type /Annot /Subtype /Link /Rect [72 696 100 712] /Border [0 0 0] /StructParent 1 /A << /S /URI /URI (http://example.com/) >> >>"
          8 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
          10 (str "<< /Type /StructElem /S /Document /P 3 0 R /Lang (de-DE) /K [20 0 R 21 0 R 22 0 R 23 0 R 24 0 R 25 0 R 26 0 R 27 0 R "
                  "28 0 R 29 0 R 29 0 R 48 0 R 47 0 R] >>")
          20 "<< /Type /StructElem /S /P /P 10 0 R /Pg 4 0 R /K 0 >>"
          21 "<< /Type /StructElem /S /Heading /P 10 0 R /Pg 4 0 R /K 1 >>"
          22 "<< /Type /StructElem /S /Span /P 10 0 R /Pg 4 0 R /K [2 << /Type /MCR /Pg 4 0 R /MCID 3 >>] >>"
          23 "<< /Type /StructElem /S /P /P 10 0 R /Pg 4 0 R /K 3 >>"
          24 "<< /Type /StructElem /S /Foo /P 10 0 R /Pg 4 0 R /K [<< /Type /MCR /Stm 6 0 R /MCID 0 /Pg 4 0 R >> << /Type /MCR /Stm 6 0 R /MCID 1 >>] >>"
          25 "<< /Type /StructElem /S /Weird /P 10 0 R /K [25 0 R] >>"
          26 "<< /Type /StructElem /S /H7 /P 10 0 R /Pg 4 0 R /K [<< /Type /OBJR /Obj 7 0 R /Pg 4 0 R >>] >>"
          27 (str "<< /Type /StructElem /S /Para /P 10 0 R /Pg 4 0 R /Alt (alternative) /ActualText <FEFF00410042> /E (expansion)"
                  " /Lang (fr) /ID (id27) /T (title) /K 4 >>")
          28 "<< /Type /StructElem /S /Table /P 10 0 R /A << /O /Table /Summary (a table) >> /K [40 0 R] >>"
          29 "<< /Type /StructElem /S /L /P 10 0 R /C /C2 /K [44 0 R] >>"
          30 "<< /Nums [0 [20 0 R 21 0 R 22 0 R 23 0 R null] 1 26 0 R 5 [24 0 R 24 0 R]] >>"
          40 "<< /Type /StructElem /S /TR /P 28 0 R /K [41 0 R 42 0 R 43 0 R] >>"
          41 "<< /Type /StructElem /S /TH /P 40 0 R /C /C1 /A << /O /Table /RowSpan 2 /Headers [(a) (b)] >> >>"
          42 "<< /Type /StructElem /S /TD /P 40 0 R /C [/C2] >>"
          43 "<< /Type /StructElem /S /TD /P 40 0 R /A [<< /O /Layout /Scope /Column >> 1 << /O /Table /Scope /Column /ColSpan 3 >>] >>"
          44 "<< /Type /StructElem /S /LI /P 29 0 R /K [45 0 R 46 0 R] >>"
          45 "<< /Type /StructElem /S /Lbl /P 44 0 R >>"
          46 "<< /Type /StructElem /S /LBody /P 44 0 R >>"
          47 "<< /Type /StructElem /S /Div /P 10 0 R /Pg 2 0 R /K [null (a string) 5 /Name 99 0 R 100 0 R] >>"
          48 "<< /S /Quote /P 10 0 R /K << /Type /MCR /MCID 7 /Pg 4 0 R >> >>"})]
    (build-pdf objs)))

(def files
  {"good.pdf" :good "bad.pdf" :bad "bad-allp.pdf" :bad-allp "bad-levels.pdf" :bad-levels
   "bad-artifacts.pdf" :bad-artifacts "bad-order.pdf" :bad-order "odd.pdf" :odd})

(let [check? (some #{"--check"} *command-line-args*)
      bad (atom 0)]
  (.mkdirs (io/file out-dir))
  (doseq [[f v] (sort files)]
    (let [bs (if (= v :odd) (odd-pdf) (write-variant v))
          p (str out-dir "/" f)]
      (if check?
        (if (and (.exists (io/file p)) (java.util.Arrays/equals ^bytes bs (java.nio.file.Files/readAllBytes (.toPath (io/file p)))))
          nil
          (do (swap! bad inc) (println "  FAIL" (str "test/pdf/tagged/" f) "is not what tools/synth/tagged_pair.clj writes")))
        (with-open [o (io/output-stream p)] (.write o ^bytes bs)))))
  (if check?
    (if (zero? @bad)
      (println "  ok   test/pdf/tagged/*.pdf are current")
      (System/exit 1))
    (println "wrote" (count files) "files to test/pdf/tagged")))
