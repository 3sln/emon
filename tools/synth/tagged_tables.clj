(ns synth.tagged-tables
  "Tagged-table fixtures of P6.8 (D227, D225), written byte for byte (no PDF producer, no library, D094):

      bb -cp tools:tools/lib tools/synth/tagged_tables.clj            write pdf/, html/ and FACTS-ttab.json
      bb -cp tools:tools/lib tools/synth/tagged_tables.clj --check    fail if a committed file is not what this writes

  Eight documents, each a title, prose, ONE table (or, for `ttab-layout`, prose) and prose, and an UNTAGGED twin of each
  (`ttab-*-untagged`: the same bytes of page content without a structure tree, the BEFORE column of P5.5). What differs is
  what the file SAYS about the table. `FACTS-ttab.json` declares the verdict a correct P5.5 reaches, per document
  (`facts.tag-verdict` in MANIFEST): confirmed, partial, contradicted, or none (no tagged table at all):

    ttab-good          ruled; TH with Scope Column; a ColSpan 2 header; a RowSpan 2 first-column cell      confirmed
    ttab-good-ws       a whitespace table, no rule, correctly tagged                                       confirmed
    ttab-bad-spans     ColSpan 3 where the rules close at 2                                                partial
    ttab-bad-rows      two visual rows in one TR                                                           contradicted
    ttab-allp          cells tagged P: no grid (a whitespace table; not vetoed below trust 800)            none
    ttab-layout        two columns of prose tagged Table/TR/TD                                             contradicted
    ttab-th-as-td      headers tagged TD                                                                   confirmed
    ttab-missing-row   the last row is an artifact: tags cover one row fewer                               partial
  Every untagged twin: none."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [pyjson :as pj :refer [om]]
            [synth.make-html :as mh]
            [synth.pdfw :as w :refer [t num* pstr]]))

(def CORPUS "test/corpus/synth")
(def XS (vec (reductions + 72 [110 110 110 110])))
(def ROWH 22)
(def TOP 600)
(defn y-of [r] (- TOP (* r ROWH)))

;; a cell: [row col rowspan colspan text]  (head? = the row is in the head)
(def GOOD-CELLS
  [[0 0 1 1 "Region"] [0 1 1 2 "Sales"] [0 3 1 1 "Notes"]
   [1 0 2 1 "North"] [1 1 1 1 "120"] [1 2 1 1 "135"] [1 3 1 1 "up"]
   [2 1 1 1 "98"] [2 2 1 1 "104"] [2 3 1 1 "flat"]
   [3 0 1 1 "South"] [3 1 1 1 "75"] [3 2 1 1 "80"] [3 3 1 1 "up"]])

(def PLAIN-CELLS
  (vec (concat (map-indexed (fn [c s] [0 c 1 1 s]) ["Item" "Qty" "Price" "Stock"])
               (for [[r row] (map-indexed vector [["Pens" "12" "3.40" "yes"] ["Paper" "4" "7.25" "no"] ["Tape" "9" "2.10" "yes"]])
                     [c s] (map-indexed vector row)]
                 [(inc r) c 1 1 s]))))

(def FIVE-CELLS
  (vec (concat PLAIN-CELLS (map-indexed (fn [c s] [4 c 1 1 s]) ["Glue" "3" "4.80" "no"]))))

(def PROSE ["The table below was extracted from the supplies ledger." "Figures are totals for the quarter."])
(def AFTER ["Questions go to the purchasing office."])

(defn edges
  "The rule segments of a ruled table: every cell's border, as a set of [x0 y0 x1 y1], keeping only the spans' outer edges."
  [cells]
  (set (mapcat (fn [[r c rs cs]]
                 (let [x0 (XS c) x1 (XS (+ c cs)) y0 (y-of r) y1 (y-of (+ r rs))]
                   [[x0 y0 x1 y0] [x0 y1 x1 y1] [x0 y1 x0 y0] [x1 y1 x1 y0]]))
               cells)))

(defn rules-ops [cells]
  (str "0.5 w " (str/join " " (for [[x0 y0 x1 y1] (sort (edges cells))] (str (num* x0) " " (num* y0) " m " (num* x1) " " (num* y1) " l"))) " S"))

(defn table-html [cells head-rows profile]
  (let [rows (group-by first cells)
        row-html (fn [r tag]
                   (str "<tr>" (apply str (for [[_ _ rs cs s] (sort-by second (get rows r))]
                                            (str "<" tag (when (> rs 1) (str " rowspan=\"" rs "\"")) (when (> cs 1) (str " colspan=\"" cs "\"")) ">" s "</" tag ">")))
                        "</tr>"))
        nrows (inc (apply max (map first cells)))]
    (str "<table data-profile=\"" profile "\"><thead>" (apply str (for [r (range nrows) :when (head-rows r)] (row-html r "th")))
         "</thead><tbody>" (apply str (for [r (range nrows) :when (not (head-rows r))] (row-html r "td"))) "</tbody></table>")))

;; ------------------------------------------------------------------ one document

(defn strip [content]
  (-> content (str/replace #"/(H1|P|TH|TD) <</MCID \d+>> BDC\n" "") (str/replace #"/Artifact <</Type /Layout>> BDC\n" "") (str/replace #"\nEMC" "")))

(defn build
  "spec: {:title :cells :head #{rows} :profile :ruled? :tag :verdict :cell-tag (fn [cell head?] role) :tr (fn [cell] tr-index)
          :artifact-row r :layout? bool}. -> {:pdf bytes-fn :html}"
  [{:keys [title cells head profile ruled? cell-role tr-of artifact-row layout? prose bad-spans?]}]
  (let [prose (or prose PROSE)
        mcid (atom -1)
        next! #(swap! mcid inc)
        title-id (next!)
        p1-id (next!)
        ;; cells in reading order, one MCID each (an artifact row has none)
        cell-marks (vec (for [cell cells]
                          (let [[r c rs cs s] cell]
                            {:cell cell :art? (= r artifact-row) :mcid (when-not (= r artifact-row) (next!))})))
        p2-id (next!)
        text-of (fn [[r c rs cs s]]
                  (let [size 10 x (+ (XS c) 6) base (- (y-of r) (/ (* ROWH rs) 2) (* size 0.35))]
                    (t (if (head r) :F2 :F1) size x base s)))
        tagged (fn [role id ops] (str "/" role " <</MCID " id ">> BDC\n" ops "\nEMC"))
        ops-pre (str (tagged "H1" title-id (t :F2 18 72 720 title)) "\n"
                     (tagged "P" p1-id (str/join "\n" (map-indexed (fn [i l] (t :F1 11 72 (- 690 (* 14 i)) l)) prose))))
        bottom (y-of (inc (apply max (map first cells))))
        ops-cells (str/join "\n" (for [{:keys [cell art? mcid]} cell-marks]
                                   (if art?
                                     (str "/Artifact <</Type /Layout>> BDC\n" (text-of cell) "\nEMC")
                                     (tagged (cell-role cell) mcid (text-of cell)))))
        ops-rules (when ruled? (str "/Artifact <</Type /Layout>> BDC\n" (rules-ops cells) "\nEMC"))
        ops-post (tagged "P" p2-id (str/join "\n" (map-indexed (fn [i l] (t :F1 11 72 (- bottom 28 (* 14 i)) l)) AFTER)))
        content (str/join "\n" (remove nil? [ops-pre ops-rules ops-cells ops-post]))
        ;; ---- the tree: Document 20; H1 21; P 22; Table 23; TRs 24..; cells; last P
        tr-ids (sort (distinct (map #(tr-of (:cell %)) (remove :art? cell-marks))))
        table-obj 23
        tr-obj (into {} (map-indexed (fn [i k] [k (+ 24 i)]) tr-ids))
        c0 (+ 24 (count tr-ids))
        cell-objs (vec (map-indexed (fn [i m] (assoc m :obj (+ c0 i))) (remove :art? cell-marks)))
        last-p (+ c0 (count cell-objs))
        ref #(str % " 0 R")
        tattr (fn [[r c rs cs] role]
                (let [parts (cond-> []
                              (and (= role "TH") (head r)) (conj "/Scope /Column")
                              (> cs 1) (conj (str "/ColSpan " (if (and bad-spans? (= cs 2)) 3 cs)))
                              (> rs 1) (conj (str "/RowSpan " rs)))]
                  (when (seq parts) (str " /A << /O /Table " (str/join " " parts) " >>"))))
        flat? (= layout? :flat)
        doc-kids (if flat?
                   (concat [21 22] (map :obj cell-objs) [last-p])
                   (concat [21 22 table-obj] [last-p]))
        objs-tree
        (merge
         {20 (str "<< /Type /StructElem /S /Document /P 3 0 R /K [" (str/join " " (map ref doc-kids)) "] >>")
          21 (str "<< /Type /StructElem /S /H1 /P 20 0 R /Pg 4 0 R /K " title-id " >>")
          22 (str "<< /Type /StructElem /S /P /P 20 0 R /Pg 4 0 R /K " p1-id " >>")
          last-p (str "<< /Type /StructElem /S /P /P 20 0 R /Pg 4 0 R /K " p2-id " >>")}
         (when-not flat?
           (merge
            {table-obj (str "<< /Type /StructElem /S /Table /P 20 0 R /K [" (str/join " " (map #(ref (tr-obj %)) tr-ids)) "] >>")}
            (into {} (for [k tr-ids]
                       [(tr-obj k) (str "<< /Type /StructElem /S /TR /P 23 0 R /K ["
                                        (str/join " " (map #(ref (:obj %)) (filter #(= k (tr-of (:cell %))) cell-objs))) "] >>")]))))
         (into {} (for [{:keys [cell obj mcid]} cell-objs]
                    (let [role (cell-role cell)
                          parent (if flat? 20 (tr-obj (tr-of cell)))]
                      [obj (str "<< /Type /StructElem /S /" role " /P " parent " 0 R /Pg 4 0 R /K " mcid (tattr cell role) " >>")]))))
        owner (into {title-id 21 p1-id 22 p2-id last-p} (for [{:keys [obj mcid]} cell-objs] [mcid obj]))
        parent-tree (str "<< /Nums [0 [" (str/join " " (map #(ref (owner %)) (range (inc @mcid)))) "]] >>")
        page "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /StructParents 0 >>"
        base {2 "<< /Type /Pages /Kids [4 0 R] /Count 1 >>" 4 page 5 [:stream "" content]}]
    {:tagged (w/build-pdf (merge w/font-objs base objs-tree
                                 {1 "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 3 0 R /MarkInfo << /Marked true >> /Lang (en-US) >>"
                                  3 "<< /Type /StructTreeRoot /K 20 0 R /ParentTree 6 0 R /ParentTreeNextKey 1 >>"
                                  6 parent-tree}))
     :untagged (w/build-pdf (merge w/font-objs
                                   {1 "<< /Type /Catalog /Pages 2 0 R >>"
                                    2 "<< /Type /Pages /Kids [4 0 R] /Count 1 >>"
                                    4 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> >>"
                                    5 [:stream "" (strip content)]}))}))

(defn th-role [head] (fn [[r]] (if (head r) "TH" "TD")))

(def SPECS
  [["ttab-good" "Supplies Ledger" "confirmed"
    {:cells GOOD-CELLS :head #{0} :profile "ruled" :ruled? true :cell-role (th-role #{0}) :tr-of first}]
   ["ttab-good-ws" "Supplies Overview" "confirmed"
    {:cells PLAIN-CELLS :head #{0} :profile "whitespace" :ruled? false :cell-role (th-role #{0}) :tr-of first}]
   ["ttab-bad-spans" "Supplies Ledger" "partial"
    {:cells GOOD-CELLS :head #{0} :profile "ruled" :ruled? true :cell-role (th-role #{0}) :tr-of first :bad-spans? true}]
   ["ttab-bad-rows" "Supplies Ledger" "contradicted"
    {:cells PLAIN-CELLS :head #{0} :profile "ruled" :ruled? true :cell-role (th-role #{0})
     :tr-of (fn [[r]] (if (#{1 2} r) 1 r))}]
   ["ttab-allp" "Supplies Overview" "none"
    {:cells PLAIN-CELLS :head #{0} :profile "whitespace" :ruled? false :cell-role (fn [_] "P") :tr-of first :layout? :flat}]
   ["ttab-th-as-td" "Supplies Ledger" "confirmed"
    {:cells PLAIN-CELLS :head #{0} :profile "ruled" :ruled? true :cell-role (fn [_] "TD") :tr-of first}]
   ["ttab-missing-row" "Supplies Ledger" "partial"
    {:cells FIVE-CELLS :head #{0} :profile "ruled" :ruled? true :cell-role (th-role #{0}) :tr-of first :artifact-row 4}]])

(def LAYOUT-TEXT
  ["The committee met on Tuesday" "to review the plan for the new" "building. Members agreed that" "the schedule is tight but that" "the budget leaves room for the" "changes requested in March."])
(def LAYOUT-TEXT-2
  ["A second round of drawings" "will be ready by the end of May" "and the contractors will be" "invited to comment on them" "before any contract is signed." "Minutes follow on Friday."])

(defn layout-doc
  "Two columns of prose tagged Table/TR/TD: not a table."
  []
  (let [col (fn [x lines] (str/join "\n" (map-indexed (fn [i l] (t :F1 11 x (- 600 (* 14 i)) l)) lines)))
        tagged (fn [role id ops] (str "/" role " <</MCID " id ">> BDC\n" ops "\nEMC"))
        content (str/join "\n" [(tagged "H1" 0 (t :F2 18 72 720 "Committee Notes"))
                                (tagged "P" 1 (t :F1 11 72 690 "Two columns of notes follow."))
                                (tagged "TD" 2 (col 72 LAYOUT-TEXT))
                                (tagged "TD" 3 (col 330 LAYOUT-TEXT-2))])
        objs {20 "<< /Type /StructElem /S /Document /P 3 0 R /K [21 0 R 22 0 R 23 0 R] >>"
              21 "<< /Type /StructElem /S /H1 /P 20 0 R /Pg 4 0 R /K 0 >>"
              22 "<< /Type /StructElem /S /P /P 20 0 R /Pg 4 0 R /K 1 >>"
              23 "<< /Type /StructElem /S /Table /P 20 0 R /K [24 0 R] >>"
              24 "<< /Type /StructElem /S /TR /P 23 0 R /K [25 0 R 26 0 R] >>"
              25 "<< /Type /StructElem /S /TD /P 24 0 R /Pg 4 0 R /K 2 >>"
              26 "<< /Type /StructElem /S /TD /P 24 0 R /Pg 4 0 R /K 3 >>"}
        res "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >>"]
    {:tagged (w/build-pdf (merge w/font-objs objs
                                 {1 "<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 3 0 R /MarkInfo << /Marked true >> /Lang (en-US) >>"
                                  2 "<< /Type /Pages /Kids [4 0 R] /Count 1 >>"
                                  3 "<< /Type /StructTreeRoot /K 20 0 R /ParentTree 6 0 R /ParentTreeNextKey 1 >>"
                                  4 (str res " /StructParents 0 >>")
                                  5 [:stream "" content]
                                  6 "<< /Nums [0 [21 0 R 22 0 R 25 0 R 26 0 R]] >>"}))
     :untagged (w/build-pdf (merge w/font-objs
                                   {1 "<< /Type /Catalog /Pages 2 0 R >>"
                                    2 "<< /Type /Pages /Kids [4 0 R] /Count 1 >>"
                                    4 (str res " >>")
                                    5 [:stream "" (strip content)]}))
     :html (str "<h1>Committee Notes</h1><p>Two columns of notes follow.</p><p>" (str/join " " LAYOUT-TEXT) "</p><p>" (str/join " " LAYOUT-TEXT-2) "</p>")}))

(defn outputs []
  (let [docs (concat
              (for [[stem title verdict spec] SPECS]
                (let [b (build (assoc spec :title title))
                      html (str "<h1>" title "</h1><p>" (str/join " " PROSE) "</p>"
                                (table-html (:cells spec) (:head spec) (:profile spec))
                                "<p>" (str/join " " AFTER) "</p>")]
                  [stem verdict (:tagged b) (:untagged b) html (:artifact-row spec)]))
              [(let [b (layout-doc)] ["ttab-layout" "contradicted" (:tagged b) (:untagged b) (:html b) nil])])]
    (into {}
          (concat
           (mapcat (fn [[stem _ tagged untagged html art-row]]
                     (let [h (fn [] (mh/doc "Tagged table" html {:css "table{width:100%}"}))]
                       [[(str CORPUS "/pdf/" stem ".pdf") tagged]
                        [(str CORPUS "/pdf/" stem "-untagged.pdf") untagged]
                        [(str CORPUS "/html/" stem ".html") (.getBytes ^String (h) "UTF-8")]
                        [(str CORPUS "/html/" stem "-untagged.html") (.getBytes ^String (h) "UTF-8")]]))
                   docs)
           [[(str CORPUS "/FACTS-ttab.json")
             (.getBytes (str (pj/dumps (apply om (mapcat (fn [[stem v]] [stem (om "tag-verdict" v)])
                                                         (sort-by first (concat (for [[s v] (map (juxt first #(nth % 1)) docs)] [s v])
                                                                                (for [[s _] (map (juxt first #(nth % 1)) docs)] [(str s "-untagged") "none"])))))
                             {:indent 1}) "\n") "UTF-8")]]))))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        outs (outputs)
        bad (remove (fn [[p bs]] (w/same-bytes? p bs)) outs)]
    (if check?
      (if (empty? bad)
        (println (str "  ok   tagged_tables: " (count outs) " committed files are what tools/synth/tagged_tables.clj writes"))
        (do (doseq [[p _] bad] (println "  FAIL" p "is not what tools/synth/tagged_tables.clj writes"))
            (System/exit 1)))
      (do (doseq [[p bs] outs] (io/make-parents p) (w/write-bytes! p bs))
          (println "wrote" (count outs) "files")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
