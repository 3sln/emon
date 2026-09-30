(ns import.doclaynet
  "DocLayNet v1.2 (ds4sd/DocLayNet-v1.2 shards) -> emon gold (DEVLOG P2.11).   bb import doclaynet

  Native ground truth, per page <id>.json (+ <id>.png, <id>.pdf, one page): COCO-style
    bboxes      [[x, y, w, h]...]   in the 1025 x 1025 PNG's pixels, origin top-left
    category_id [int...]            1 Caption 2 Footnote 3 Formula 4 List-item 5 Page-footer 6 Page-header
                                    7 Picture 8 Section-header 9 Table 10 Text 11 Title
    pdf_cells   [[{bbox [x,y,w,h], text, font}...] ...]  per box, same pixel space
    metadata    {coco_width, coco_height, original_width, original_height (PDF points), page_no, num_pages,
                 doc_category, collection, original_filename}

  COORDINATE TRANSFORM (verified against the paired PDF with pdftotext -bbox, see test/corpus/DATASETS.md):
  the page is STRETCHED, not letterboxed, onto the square 1025 x 1025 canvas, so
      x_pt = x_px * original_width  / coco_width        y_pt = y_px * original_height / coco_height
  and (x, y, w, h) becomes (x_pt, y_pt, x_pt + w_pt, y_pt + h_pt).  Origin stays top-left (the PNG's).
  The page is original_width x original_height points, rot 0 (the paired PDF agrees; a 1-page PDF).

  MAPPING (decisions, D0xx):
    Title, Section-header -> heading (NO level; DocLayNet has none)   Text -> paragraph
    List-item -> item, consecutive items wrapped in one `list` (the grouping is ours, unclaimed)
    Caption -> caption   Footnote -> note (role footnote)   Formula -> formula (display block)
    Picture -> figure (subkind unknown)   Table -> table (region only: no rows or cells)
    Page-header / Page-footer -> body `furniture` blocks (role, pages [page]) WITH geometry, because the
    gold's model.furniture list has no `at`; text from the PDF cells.
  `text` on a block is the PDF cells inside it, joined by a space: convenience, NOT annotation.

  CLAIMED (presence, absence and region): heading, paragraph, item, caption, note, formula, figure, table.
  NOT claimed: heading level, list/section/furniture structure, text, reading order (body order is the
  order of the source's box list, which is not documented as reading order), table internals."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(def CAT {1 "Caption" 2 "Footnote" 3 "Formula" 4 "List-item" 5 "Page-footer" 6 "Page-header"
          7 "Picture" 8 "Section-header" 9 "Table" 10 "Text" 11 "Title"})

(defn page-gold [gid j pdf-rel png-rel st]
  (let [md (get j "metadata")
        cw (get md "coco_width") ch (get md "coco_height")
        ow (get md "original_width") oh (get md "original_height")
        sx (/ (double ow) cw) sy (/ (double oh) ch)
        cells-all (get j "pdf_cells")
        ;; state: kids, and whether the last kid is an open list
        {:keys [kids]}
        (reduce
         (fn [{:keys [kids open] :as s} [i [b cat]]]
           (let [nm (CAT cat)]
             (if (nil? nm)
               (do (C/warn! st "unknown category") s)
               (let [[x y w h] b
                     at (C/region 1 (* x sx) (* y sy) (* (+ x w) sx) (* (+ y h) sy))
                     _ (when (or (> (nth at 3) (+ ow 0.5)) (> (nth at 4) (+ oh 0.5)))
                         (C/warn! st "box beyond the page (kept)"))
                     cells (if (< i (count cells-all)) (nth cells-all i) [])
                     text (C/norm (str/join " " (map #(get % "text") cells)))
                     text (when (seq text) text)
                     open (and open (= nm "List-item"))
                     o {:at at :text text}
                     n (case nm
                         ("Title" "Section-header") (C/node "heading" o)
                         "Text" (C/node "paragraph" o)
                         "List-item" (C/node "item" o)
                         "Caption" (C/node "caption" o)
                         "Footnote" (C/node "note" (assoc o :fields (om "role" "footnote")))
                         "Formula" (C/node "formula" (assoc o :fields (om "display" "block")))
                         "Picture" (C/node "figure" {:at at})
                         "Table" (C/node "table" {:at at})
                         (C/node "furniture" (assoc o :fields (om "role" (if (= nm "Page-header") "header" "footer")
                                                                  "pages" [1]))))]
                 (if (= nm "List-item")
                   (if open
                     {:kids (update kids (dec (count kids)) update :children conj n) :open true}
                     {:kids (conj kids {:kind "list" :fields (om) :children [n]}) :open true})
                   {:kids (conj kids n) :open false})))))
         {:kids [] :open false}
         (map-indexed vector (map vector (get j "bboxes") (get j "category_id"))))
        ;; list regions: the union of their items (ours, unclaimed)
        kids (mapv (fn [k]
                     (if (= "list" (:kind k))
                       (let [[x0 y0 x1 y1] (C/union (map #(vec (rest (first (:at %)))) (:children k)))]
                         (assoc k :at [(C/region 1 x0 y0 x1 y1)]))
                       k))
                   kids)
        page (om "n" 1 "w" (C/num ow) "h" (C/num oh) "rot" 0)
        attrs ["heading" "paragraph" "item" "caption" "note" "formula" "figure" "table"]
        ann (C/annotated {:structure attrs :attributes (apply array-map (mapcat (fn [k] [k ["at"]]) attrs))
                          :pages ["n" "w" "h"]})
        prov (om "pdf" pdf-rel
                 "producer" (format "DocLayNet v1.2 (human-annotated layout; %s, %s p.%d of %d)"
                                    (or (get md "collection") "None") (or (get md "original_filename") "None")
                                    (get md "page_no" 0) (get md "num_pages" 0))
                 "variants" (om "png" png-rel))]
    (C/gold gid ann [page] kids "pdf" :provenance prov)))

(defn items [corpus st]
  (let [base (str corpus "/layout/doclaynet-v1.2")]
    (for [shard (sort (map #(str (fs/file-name %)) (fs/list-dir base)))
          :let [d (str base "/" shard)]
          :when (fs/directory? d)
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname ".json")]
      (let [j (pj/parse-file (str d "/" fname))
            stem (subs fname 0 (- (count fname) 5))
            gid (str "doclaynet-" (first (str/split shard #"-" 2)) "-" stem)]
        [gid (page-gold gid j (C/rel (str d "/" stem ".pdf")) (C/rel (str d "/" stem ".png")) st)]))))

(defn import! [opts] (C/run "doclaynet" items opts))
