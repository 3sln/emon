(ns import.icdar2013
  "ICDAR 2013 Table Competition (tabula-java copy) -> emon gold (DEVLOG P2.11).   bb import icdar2013

  Native ground truth, per PDF <doc>.pdf:
    <doc>-reg.xml   <table id><region id page><bounding-box x1 y1 x2 y2/></region></table>   table regions
    <doc>-str.xml   <table id><region id page row-increment col-increment><cell start-row start-col
                    [end-row end-col]><bounding-box x1 x2 y1 y2/><content>...</content></cell>...
  (<doc>.json is tabula-java's own extraction, not ground truth, and is ignored.)

  COORDINATE TRANSFORM (verified against pdftotext -bbox, test/corpus/DATASETS.md): boxes are PDF USER
  SPACE in POINTS, ORIGIN BOTTOM-LEFT, y up, with y1 <= y2.  Emon page space is top-left, y down, so for
  a page of height H (from `pdfinfo`, the displayed size; all pages here have /Rotate 0, else the item is
  skipped):  (x1, y1, x2, y2) -> (x1, H - y2, x2, H - y1).  Pages may differ in size: H is per page.

  GRID: a cell occupies rows start-row..end-row and columns start-col..end-col (end defaults to start; the
  one `col-end` typo in the data is read as `end-col`).  A region's row-increment / col-increment are added
  to its cells' indices (only us-019 and us-035a/b have non-zero increments; the reading is the natural one
  and is not verifiable from the files, so those 3 documents are flagged in the summary).

  MAPPING: each table -> table > rowgroup(body) > row > cell; the cell's text is the content with whitespace
  collapsed (the source's line wraps are not semantic); `empty` when the content is blank; rowspan/colspan
  when > 1; the table's `at` is the reg.xml region(s) (one per page), else the union of its cells; `columns` is
  the grid width.
  CLAIMED: table, row, cell presence AND absence over the whole document (the competition annotates every
  table); table at/columns; cell rowspan/colspan/empty/text/at.
  NOT claimed: rowgroup (no head/foot roles in the source), header cells, profile, everything that is not a table."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn fnum
  "A coordinate attribute; the source has typos such as '26ß' (leading number kept, counted)."
  [v st]
  (try (Double/parseDouble (str/trim v))
       (catch NumberFormatException e
         (if-let [m (re-find #"^\s*(-?\d+(?:\.\d+)?)" v)]
           (do (C/warn! st "malformed coordinate read by its leading number") (Double/parseDouble (second m)))
           (throw e)))))

(defn geti
  ([e k] (geti e k nil))
  ([e k d] (let [v (C/xattr e k)] (if (some? v) (parse-long v) d))))

(defn doc-gold [gid pdf reg-xml str-xml pdf-rel st]
  (let [pages (C/pdf-pages pdf)]
    (if (some #(pos? (nth % 2)) pages)
      (do (C/warn! st "rotated pages (skipped)") nil)
      (let [regs (into {}
                       (for [t (C/xiter (C/xml-root reg-xml) "table")
                             r (C/xkids t "region")
                             :let [b (C/xfirst r "bounding-box")] :when b]
                         [[(C/xattr t "id") (C/xattr r "id")]
                          [(parse-long (C/xattr r "page")) (mapv #(fnum (C/xattr b %) st) ["x1" "y1" "x2" "y2"])]]))
            flip (fn [page x1 y1 x2 y2]
                   (when-not (<= 1 page (count pages)) (throw (ex-info (str "page " page " not in the PDF") {})))
                   (let [h (second (nth pages (dec page)))]
                     (C/region page x1 (- h y2) x2 (- h y1))))
            flagged (volatile! false)
            tables
            (vec
             (keep
              (fn [t]
                (let [rs (C/xkids t "region")
                      cells+regs
                      (for [r rs]
                        (let [page (geti r "page")
                              ri (geti r "row-increment" 0) ci (geti r "col-increment" 0)
                              _ (when (or (not= ri 0) (not= ci 0)) (vreset! flagged true))
                              rb (regs [(C/xattr t "id") (C/xattr r "id")])
                              cs (keep
                                  (fn [c]
                                    (if-let [b (C/xfirst c "bounding-box")]
                                      (let [sr (+ (geti c "start-row") ri)
                                            er (+ (geti c "end-row" (geti c "start-row")) ri)
                                            sc (+ (geti c "start-col") ci)
                                            ec (+ (geti c "end-col" (geti c "col-end" (geti c "start-col"))) ci)
                                            txt (C/xfirst c "content")]
                                        [sr er sc ec (C/norm (when txt (C/xtext txt)))
                                         (apply flip page (map #(fnum (C/xattr b %) st) ["x1" "y1" "x2" "y2"]))])
                                      (do (C/warn! st "cell without a box (dropped)") nil)))
                                  (C/xkids r "cell"))
                              _ (when-not rb
                                  (when (seq (C/xkids r "cell"))
                                    (C/warn! st "table region missing from reg.xml (union of cells used)")))]
                          [cs (when rb (apply flip (first rb) (second rb)))]))
                      cells (vec (mapcat first cells+regs))
                      tregs (vec (keep second cells+regs))]
                  (when (seq cells)
                    (let [ncols (apply max 0 (map #(inc (nth % 3)) cells))
                          tregs (if (seq tregs) tregs
                                    [(let [[x0 y0 x1 y1] (C/union (map #(vec (rest (nth % 5))) cells))]
                                       (C/region (first (nth (first cells) 5)) x0 y0 x1 y1))])
                          nrows (inc (apply max (map #(nth % 1) cells)))
                          rows (reduce
                                (fn [rows [sr er sc ec txt at]]
                                  (let [f (cond-> (om)
                                            (> er sr) (assoc "rowspan" (inc (- er sr)))
                                            (> ec sc) (pj/oassoc "colspan" (inc (- ec sc)))
                                            (empty? txt) (pj/oassoc "empty" true))]
                                    (update rows sr conj (C/node "cell" {:fields f :at at :text (when (seq txt) txt)}))))
                                (vec (repeat nrows []))
                                (sort-by (juxt #(nth % 0) #(nth % 2)) cells))
                          group (C/node "rowgroup" {:fields (om "role" "body")
                                                    :children (mapv #(C/node "row" {:children %}) rows)})]
                      (C/node "table" {:fields (om "columns" ncols) :at tregs :children [group]})))))
              (C/xiter (C/xml-root str-xml) "table")))
            _ (when @flagged (C/warn! st "document with a non-zero row/col-increment (flagged, see docstring)"))
            ann (C/annotated {:structure ["table" "row" "cell"]
                              :attributes (om "table" ["at" "columns"] "cell" ["rowspan" "colspan" "empty" "text" "at"])
                              :pages ["n" "w" "h"]})
            plist (vec (map-indexed (fn [i [w h _]] (om "n" (inc i) "w" (C/num w) "h" (C/num h) "rot" 0)) pages))
            sid (second (str/split gid #"-" 2))
            prov (om "pdf" pdf-rel
                     "producer" (str "ICDAR 2013 Table Competition (Goebel et al. 2013) via tabula-java; human-annotated; "
                                     sid "-str.xml, " sid "-reg.xml"))]
        (C/gold gid ann plist tables "pdf" :provenance prov)))))

(defn items [corpus st]
  (let [base (str corpus "/tables/icdar2013")]
    (for [sub (sort (map #(str (fs/file-name %)) (fs/list-dir base)))
          :let [d (str base "/" sub)]
          :when (fs/directory? d)
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname "-str.xml")]
      (let [stem (subs fname 0 (- (count fname) 8))
            pdf (str d "/" stem ".pdf") reg (str d "/" stem "-reg.xml")]
        (if-not (and (fs/exists? pdf) (fs/exists? reg))
          (do (C/warn! st "pdf or reg.xml missing") [stem nil])
          (let [gid (str "icdar2013-" stem)]
            [gid (doc-gold gid pdf reg (str d "/" fname) (C/rel pdf) st)]))))))

(defn import! [opts] (C/run "icdar2013" items opts))
