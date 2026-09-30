(ns import.stamps
  "mapo80/stamps \"Clean Core Stamp Detection Dataset\" (HF, CC-BY-4.0) -> emon gold (DEVLOG P2.11).
  bb import stamps

  The seal/stamp set found for P2.11 (Tobacco800, StaVer and SPODS need logins; this one downloads
  anonymously: `corpus/fetch.sh stamps`).  Native ground truth: images/<split>/<name>.{jpg,png} with
  labels/<split>/<name>.txt, YOLO format, one line per object: `class xc yc w h`, all four numbers
  NORMALISED to the image (fractions of width / height, origin top-left), class 0 = stamp.  An empty label
  file is a negative image.  Only the val and test splits are in the local corpus (train is 783 MB).

  COORDINATES: x0 = (xc - w/2) * W, x1 = (xc + w/2) * W, y likewise with H, in IMAGE PIXELS of the file
  (W x H from its header).  The page is the image at a nominal 1 px = 1 pt.

  MAPPING: one `seal` (subkind `stamp`) per box, in top-to-bottom, left-to-right order.
  CLAIMED: seal presence and absence (a negative image claims \"no seal\"), subkind, at.
  CAVEAT (recorded, not hidden): the dataset labels STAMPS only, so a signature or logo on an image is
  absent from the gold; a scorer should treat emon's `seal` nodes of subkind `stamp` (and, leniently,
  `seal`) as the thing compared.  The negative images are drawn from RVL-CDIP and FUNSD pages and inherit
  those sources' research-only terms (see test/corpus/DATASETS.md)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn items [corpus st]
  (let [base (str corpus "/seals/mapo80-stamps")]
    (for [split ["val" "test"]
          :let [idir (str base "/images/" split)]
          :when (fs/directory? idir)
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir idir)))
          :let [[_ stem ext] (re-matches #"(.*?)(\.[^.]*)?" fname)
                ext (str/lower-case (or ext ""))]
          :when (#{".jpg" ".jpeg" ".png"} ext)]
      (let [imgp (str idir "/" fname)
            lab (str base "/labels/" split "/" stem ".txt")
            gid (str "stamps-" split "-" stem)]
        (if-not (fs/exists? lab)
          (do (C/warn! st "label file missing") [gid nil])
          (let [[page fmt] (C/raster-page imgp)
                W (get page "w") H (get page "h")
                boxes (sort
                       (for [ln (str/split-lines (slurp lab :encoding "UTF-8"))
                             :let [p (str/split (str/trim ln) #"\s+")]
                             :when (not (str/blank? ln))
                             :let [ok (and (= 5 (count p)) (= "0" (first p)))]
                             :when (or ok (do (C/warn! st "unexpected label line (dropped)") false))]
                         (let [[xc yc w h] (map parse-double (rest p))]
                           (when-not (and (<= 0 (- xc (/ w 2))) (<= (+ xc (/ w 2)) 1.001)
                                          (<= 0 (- yc (/ h 2))) (<= (+ yc (/ h 2)) 1.001))
                             (C/warn! st "box beyond the image (clamped)"))
                           (let [x0 (max 0.0 (* (- xc (/ w 2)) W)) y0 (max 0.0 (* (- yc (/ h 2)) H))
                                 x1 (min (double W) (* (+ xc (/ w 2)) W)) y1 (min (double H) (* (+ yc (/ h 2)) H))]
                             [y0 x0 x1 y1]))))
                kids (vec (for [[y0 x0 x1 y1] boxes]
                            (C/node "seal" {:fields (om "subkind" "stamp") :at (C/region 1 x0 y0 x1 y1)})))
                ann (C/annotated {:structure ["seal"] :attributes (om "seal" ["subkind" "at"]) :pages ["n" "px" "kind"]})
                prov (om "producer" (str "mapo80/stamps Clean Core Stamp Detection Dataset (CC-BY-4.0; aggregate of Roboflow/Kaggle "
                                         "community sets; stamps only, single class)")
                         "variants" (om "image" (C/rel imgp)))]
            [gid (C/gold gid ann [page] kids fmt :provenance prov)]))))))

(defn import! [opts] (C/run "stamps" items opts))
