(ns import.ctdar
  "ICDAR 2019 cTDaR (cndplab-founder/ICDAR2019_cTDaR) -> emon gold (DEVLOG P2.11).   bb import ctdar

  Native ground truth, one XML per image: <document filename><table><Coords points=\"x,y x,y ...\"/>
  [<cell start-row end-row start-col end-col><Coords points=.../></cell>...]</table></document>.
  Track A (table detection) has the `table` region only; Track B (recognition) adds the cells.
  Sources used (all under corpus/tables/icdar2019-ctdar):
    train  training/TRACKA/ground_truth (xml+image, 600 archival + 600 modern), and
           training/TRACKB1/ground_truth (600 archival, cells)
    test   test_ground_truth/TRACK{A,B1,B2}/*.xml with the images in test/TRACK{A,B1,B2}/
  (test/TRACKB1/*.xml are the INPUT table regions, not truth, and are ignored.)
  The same image name can occur in several tracks: per image the richest XML wins (B2 > B1 > A).
  Names repeat between training and test, so ids are cTDaR-train-<name> / cTDaR-test-<name>.

  COORDINATES need NO transform: points are IMAGE PIXELS, origin top-left, y down; a region is the bounding
  box of the polygon's points (cell polygons are not rectangles).  The page is the image at a nominal
  1 px = 1 pt.  An image with EXIF orientation != 1 would be refused (none exist).

  MAPPING: table > rowgroup(body) > row > cell; a cell is placed in its start-row and ordered by start-col;
  rowspan = end-row - start-row + 1, colspan likewise (when > 1).  No text (cTDaR has none).
  CLAIMED, per image: Track A only: table presence/absence, table `at`.  With cells (B1/B2): also row, cell,
  table `columns`, cell rowspan/colspan.  Cell `at` is claimed only for the HISTORICAL set (cTDaR_t0*):
  the README says modern cells' boxes are the CONTENT's bounding box, not the cell area.
  NOT claimed: rowgroup roles, header cells, text, empty, everything that is not a table."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(def img-ext [".jpg" ".jpeg" ".png" ".tif" ".tiff"])

(defn bbox [points]
  (let [pts (map (fn [p] (map parse-double (str/split p #","))) (str/split (str/trim points) #"\s+"))
        xs (map first pts) ys (map second pts)]
    [(apply min xs) (apply min ys) (apply max xs) (apply max ys)]))

(defn find-image [dirs stem]
  (first (for [d dirs e img-ext :let [p (str d "/" stem e)] :when (fs/exists? p)] p)))

(defn- geti [c k] (parse-long (C/xattr c k)))

(defn doc-gold [gid stem xmlp rich imgp st]
  (let [[page fmt] (C/raster-page imgp)
        root (C/xml-root xmlp)
        modern (str/starts-with? stem "cTDaR_t1")
        tabs (for [t (C/xkids root "table")
                   :let [co (C/xfirst t "Coords") cells (C/xkids t "cell")]
                   :when (not (and (nil? co) (empty? cells)))]
               (let [tb (if co (bbox (C/xattr co "points"))
                            (C/union (map #(bbox (C/xattr (C/xfirst % "Coords") "points")) cells)))
                     _ (when (or (< (nth tb 0) -1) (< (nth tb 1) -1) (> (nth tb 2) (inc (get page "w"))) (> (nth tb 3) (inc (get page "h"))))
                         (C/warn! st "table region outside the image (kept)"))
                     tab (C/node "table" {:at (apply C/region 1 tb)})]
                 (if (seq cells)
                   (let [nrows (inc (apply max (map #(geti % "end-row") cells)))
                         ncols (inc (apply max (map #(geti % "end-col") cells)))
                         rows (reduce
                               (fn [rows c]
                                 (let [sr (geti c "start-row") er (geti c "end-row")
                                       sc (geti c "start-col") ec (geti c "end-col")
                                       f (cond-> (om)
                                           (> er sr) (assoc "rowspan" (inc (- er sr)))
                                           (> ec sc) (pj/oassoc "colspan" (inc (- ec sc))))
                                       cb (bbox (C/xattr (C/xfirst c "Coords") "points"))]
                                   (update rows sr conj (C/node "cell" {:fields f :at (apply C/region 1 cb)}))))
                               (vec (repeat nrows []))
                               (sort-by (juxt #(geti % "start-row") #(geti % "start-col")) cells))
                         g (C/node "rowgroup" {:fields (om "role" "body")
                                               :children (mapv #(C/node "row" {:children %}) rows)})]
                     [(-> tab (C/add-field "columns" ncols) (assoc :children [g])) true])
                   [tab false])))
        has-cells (some second tabs)
        ann (if has-cells
              (C/annotated {:structure ["table" "row" "cell"]
                            :attributes (om "table" ["at" "columns"]
                                            "cell" (into ["rowspan" "colspan"] (when-not modern ["at"])))
                            :pages ["n" "px" "kind"]})
              (C/annotated {:structure ["table"] :attributes (om "table" ["at"]) :pages ["n" "px" "kind"]}))
        prov (om "producer" (str "ICDAR 2019 cTDaR (Gao et al. 2019), human-annotated; " rich)
                 "variants" (om "image" (C/rel imgp)))]
    (C/gold gid ann [page] (mapv first tabs) fmt :provenance prov)))

(defn items [corpus st]
  (let [base (str corpus "/tables/icdar2019-ctdar")
        sets [["train" [["training/TRACKA/ground_truth" "A"] ["training/TRACKB1/ground_truth" "B1"]]
               ["training/TRACKA/ground_truth" "training/TRACKB1/ground_truth"]]
              ["test" [["test_ground_truth/TRACKA" "A"] ["test_ground_truth/TRACKB1" "B1"] ["test_ground_truth/TRACKB2" "B2"]]
               ["test/TRACKA" "test/TRACKB1" "test/TRACKB2"]]]
        rank {"A" 0 "B1" 1 "B2" 2}]
    (for [[split xmls imgdirs] sets
          :let [best (reduce
                      (fn [best [d track]]
                        (let [full (str base "/" d)]
                          (reduce (fn [best fname]
                                    (if (str/ends-with? fname ".xml")
                                      (let [stem (subs fname 0 (- (count fname) 4))]
                                        (if (or (not (contains? best stem)) (> (rank track) (rank (second (best stem)))))
                                          (assoc best stem [(str full "/" fname) track])
                                          best))
                                      best))
                                  best (sort (map #(str (fs/file-name %)) (fs/list-dir full))))))
                      {} xmls)
                dirs (map #(str base "/" %) imgdirs)]
          stem (sort (keys best))
          :let [[xmlp track] (best stem)
                imgp (find-image dirs stem)
                gid (str "cTDaR-" split "-" (subs stem (count "cTDaR_")))]]
      (if (nil? imgp)
        (do (C/warn! st "image missing") [gid nil])
        [gid (doc-gold gid stem xmlp (str (C/rel xmlp) " Track " track) imgp st)]))))

(defn import! [opts] (C/run "ctdar" items opts))
