(ns import.cord
  "CORD v2 (naver-clova-ix/cord-v2) -> emon gold (DEVLOG P2.11).   bb import cord

  Native ground truth: <id>.json {image, ground_truth}; `ground_truth` is a JSON STRING holding
  {gt_parse (key-information tree), meta {image_size}, valid_line [{words [{quad {x1..x4,y1..y4}, is_key,
  row_id, text}], category, group_id, sub_group_id}], roi, repeating_symbol, dontcare}.  Quads are in
  IMAGE PIXELS, top-left origin; the bounding box of a quad's four points is used.  No transform: the
  page is the image at 1 px = 1 pt.  The annotated image size is checked against the PNG header.

  MAPPING: words that share a `row_id` are one printed ROW of the receipt: one `paragraph` per row
  (words left-to-right, joined by a space; `at` = union of the word boxes), rows top to bottom.
  CLAIMED: each row paragraph's `at` only.  NOT claimed: structure (CORD's own grouping is
  menu/total key-information, not layout: it is NOT imported), text (only the `valid_line`
  words are transcribed, `dontcare` text is absent, and the order is derived) and reading order."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn items [corpus st]
  (let [base (str corpus "/financial/cord-v2")]
    (for [split ["test" "validation"]
          :let [d (str base "/" split)]
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname ".json")]
      (let [j (pj/parse-file (str d "/" fname))
            gt (pj/parse (get j "ground_truth"))
            img (str d "/" (get-in j ["image" "file"]))
            [page fmt] (C/raster-page img)
            sz (get-in gt ["meta" "image_size"])]
        (if (not= [(get page "w") (get page "h")] [(get sz "width") (get sz "height")])
          (do (C/warn! st "annotated image size differs from the file") [fname nil])
          (let [ws (for [line (get gt "valid_line") w (get line "words")
                         :let [q (get w "quad")
                               xs [(get q "x1") (get q "x2") (get q "x3") (get q "x4")]
                               ys [(get q "y1") (get q "y2") (get q "y3") (get q "y4")]]]
                     [(get w "row_id") [(apply min xs) (apply min ys) (apply max xs) (apply max ys) (get w "text")]])
                order (distinct (map first ws))                 ; dict insertion order
                rows (group-by first ws)
                sorted-rows (sort-by (fn [rid] (let [r (map second (rows rid))]
                                                 [(apply min (map #(nth % 1) r)) (apply min (map #(nth % 0) r))]))
                                     order)
                kids (vec (for [rid sorted-rows
                                :let [ws (sort-by first (map second (rows rid)))
                                      text (C/norm (str/join " " (map #(nth % 4) ws)))]
                                :when (seq text)]
                            (let [[x0 y0 x1 y1] (C/union (map #(subvec % 0 4) ws))]
                              (when (or (< x0 0) (< y0 0) (> x1 (get page "w")) (> y1 (get page "h")))
                                (C/warn! st "row box outside the image (kept)"))
                              (C/node "paragraph" {:at (C/region 1 x0 y0 x1 y1) :text text}))))
                gid (str "cord-" split "-" (subs fname 0 (- (count fname) 5)))
                ann (C/annotated {:structure [] :attributes (om "paragraph" ["at"]) :pages ["n" "px" "kind"]})
                prov (om "producer" "CORD v2 (Park et al. 2019), naver-clova-ix/cord-v2; word quads, human-annotated"
                         "variants" (om "image" (C/rel img)))]
            [gid (C/gold gid ann [page] kids fmt :provenance prov)]))))))

(defn import! [opts] (C/run "cord" items opts))
