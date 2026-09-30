(ns import.pdfa-eng-wds
  "pdfa-eng-wds (pixparse/pdfa-eng-wds) -> emon SILVER gold (DEVLOG P2.11).   bb import pdfa_eng_wds

  Native annotations, per PDF <id>.pdf: <id>.json {\"pages\": [{words {text, bbox, score, line_pos},
  lines {text, bbox, score, word_slice}, images_bbox, images_bbox_no_text_overlap}]}.  The words and
  lines are the PDF's own TEXT LAYER (dataset card: \"we extracted words, bounding boxes ... readily available
  in the pdf file\") regrouped into lines in a heuristic reading order by a column-detection algorithm: machine
  output, NOT human ground truth.  There is no hand-made ground truth in this dataset.

  COORDINATES (verified against pdftotext -bbox, test/corpus/DATASETS.md): bbox is [x, y, w, h] as FRACTIONS
  of the page's width and height, origin top-left.  So in points, for a page of W x H points (pdfinfo):
  x0 = x*W, y0 = y*H, x1 = (x+w)*W, y1 = (y+h)*H.

  MAPPING: one `paragraph` per text-layer line (text, at), in the source's order.  NOTHING IS CLAIMED except the
  page table (n, w, h): `annotated.structure` is empty, no attribute is listed, `text` is false.  The lines
  are emitted as UNCLAIMED context (silver; usable later as a text-layer oracle input, P3.10): none of it is
  scored as truth.  Documents whose PDF page count differs from the JSON, or with a rotated page, are skipped
  and counted."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn items [corpus st]
  (let [base (str corpus "/layout/pdfa-eng-wds")]
    (for [fname (sort (map #(str (fs/file-name %)) (fs/list-dir base)))
          :when (str/ends-with? fname ".json")]
      (let [stem (subs fname 0 (- (count fname) 5))
            pdf (str base "/" stem ".pdf")
            gid (str "pdfa-" stem)]
        (if-not (fs/exists? pdf)
          (do (C/warn! st "pdf missing") [gid nil])
          (let [pages (try (C/pdf-pages pdf) (catch Exception _ nil))]
            (if (nil? pages)
              (do (C/warn! st "pdfinfo failed (skipped)") [gid nil])
              (let [j (get (pj/parse-file (str base "/" fname)) "pages")]
                (cond
                  (not= (count pages) (count j)) (do (C/warn! st "page count differs from the PDF (skipped)") [gid nil])
                  (some #(pos? (nth % 2)) pages) (do (C/warn! st "rotated pages (skipped)") [gid nil])
                  :else
                  (let [kids (vec (for [[n [pg [W H _]]] (map-indexed (fn [i x] [(inc i) x]) (map vector j pages))
                                        :let [ln (get pg "lines")]
                                        [t b] (map vector (get ln "text") (get ln "bbox"))
                                        :let [t (C/norm t)] :when (seq t)]
                                    (C/node "paragraph"
                                            {:at (C/region n (* (nth b 0) W) (* (nth b 1) H)
                                                           (* (+ (nth b 0) (nth b 2)) W) (* (+ (nth b 1) (nth b 3)) H))
                                             :text t})))
                        plist (vec (map-indexed (fn [i [w h _]] (om "n" (inc i) "w" (C/num w) "h" (C/num h) "rot" 0)) pages))
                        ann (C/annotated {:pages ["n" "w" "h"]})
                        prov (om "pdf" (C/rel pdf)
                                 "producer" (str "SILVER: pixparse/pdfa-eng-wds text-layer lines with heuristic reading order "
                                                 "(not human-verified); PDF from SafeDocs CC-MAIN-2021-31-PDF-UNTRUNCATED"))]
                    [gid (C/gold gid ann plist kids "pdf" :provenance prov)]))))))))))

(defn import! [opts] (C/run "pdfa_eng_wds" items opts))
