(ns import.sroie
  "SROIE 2019 (HF jsdnrs/ICDAR2019-SROIE form) -> emon gold (DEVLOG P2.11).   bb import sroie

  Native ground truth: <id>.json {image, key, image_size {width,height}, entities {company,date,address,
  total}, words [line text...], bboxes [[x0,y0,x1,y1]...]} beside <id>.jpg.  Despite the name `words`,
  each entry is a whole TEXT LINE (SROIE task 1 is line-level), listed top to bottom; bboxes are in
  IMAGE PIXELS, top-left origin, x0<x1, y0<y1.  No transform: the page is the image at 1 px = 1 pt.
  The annotated image size is checked against the JPEG header (a mismatch skips the item).

  MAPPING: one `paragraph` per line (text, at).  The receipt is not claimed to be paragraphs, so
  NO structure is claimed: what IS claimed is `annotated.text` (the transcription in top-to-bottom
  order; SROIE's transcription keeps OCR-style typos such as \"BND\", and is the reference as given)
  and each line's `at`.  The key-information entities (company/date/address/total) are an
  information-extraction label, not document structure, and are NOT imported."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn items [corpus st]
  (let [base (str corpus "/financial/sroie2019")]
    (for [split ["train" "test"]
          :let [d (str base "/" split)]
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname ".json")]
      (let [j (pj/parse-file (str d "/" fname))
            img (str d "/" (get-in j ["image" "file"]))
            [page fmt] (C/raster-page img)]
        (if (not= [(get page "w") (get page "h")] [(get-in j ["image_size" "width"]) (get-in j ["image_size" "height"])])
          (do (C/warn! st "annotated image size differs from the file") [fname nil])
          (let [kids (vec (for [[t b] (map vector (get j "words") (get j "bboxes"))
                                :let [t (C/norm t)] :when (seq t)]
                            (do (when-not (and (<= 0 (nth b 0)) (< (nth b 0) (nth b 2)) (<= (nth b 2) (get page "w"))
                                               (<= 0 (nth b 1)) (< (nth b 1) (nth b 3)) (<= (nth b 3) (get page "h")))
                                  (C/warn! st "line box outside the image (kept)"))
                                (C/node "paragraph" {:at (apply C/region 1 b) :text t}))))
                gid (str "sroie-" split "-" (subs fname 0 (- (count fname) 5)))
                ann (C/annotated {:structure [] :attributes (om "paragraph" ["text" "at"]) :text true
                                  :pages ["n" "px" "kind"]})
                prov (om "producer" "SROIE 2019 (ICDAR competition), via HF jsdnrs/ICDAR2019-SROIE; line-level human transcription"
                         "variants" (om "image" (C/rel img)))]
            [gid (C/gold gid ann [page] kids fmt :provenance prov)]))))))

(defn import! [opts] (C/run "sroie" items opts))
