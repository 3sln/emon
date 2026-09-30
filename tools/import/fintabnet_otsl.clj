(ns import.fintabnet-otsl
  "FinTabNet-OTSL (ds4sd/FinTabNet_OTSL shards) -> emon gold (DEVLOG P2.11).   bb import fintabnet_otsl

  Native ground truth, per table crop <id>.json (+ <id>.png): {filename, imgid, cells [[{tokens [chars],
  bbox [x0,y0,x1,y1,k]}...]], otsl [tokens], html, html_restored, rows, cols}.
  OTSL tokens (Lysak et al. 2023): fcel (cell with content), ecel (empty cell), lcel (merged with the cell
  to its LEFT: a column span), ucel (merged with the cell above: a row span), xcel (both), nl (end of row).
  `cells[0]` lists the fcel/ecel cells in OTSL order (the k of a bbox is 2 for fcel, 1 for ecel).
  Only fcel, ecel, lcel and nl occur in this shard (spans are column spans only).

  COORDINATES need NO transform: bbox is in the CROP IMAGE's pixels, origin top-left (some x0/y0 are -1
  because the crop margin is tight; they are clamped to 0).  The page is the crop at a nominal 1 px = 1 pt.
  The crop is a TABLE, not a page: the gold body is one table and nothing else.

  MAPPING: table > rowgroup(body) > row > cell; text = the cell's characters joined (\"L\",\"a\",\"n\",\"d\" ->
  \"Land\"), whitespace-collapsed; colspan = 1 + the lcels to its right, rowspan = 1 + the ucels below;
  `empty` for an ecel.  The source has no header annotation, so no head rowgroup.
  CLAIMED: table, row, cell (presence and absence: the crop holds exactly one table); table columns;
  cell rowspan, colspan, empty, text.  `at` of a cell is emitted (the source's word/cell box) but NOT
  claimed: for a text cell it is the text's extent, not the ruled cell.  NOT claimed: rowgroup roles,
  header cells, table `at`."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn grid
  "[[token]] rows of an OTSL sequence."
  [otsl]
  (let [[rows cur] (reduce (fn [[rows cur] t] (if (= t "nl") [(conj rows cur) []] [rows (conj cur t)]))
                           [[] []] otsl)]
    (if (seq cur) (conj rows cur) rows)))

(defn table-gold [gid j imgp rel-img st]
  (let [[page fmt] (C/raster-page imgp)
        W (get page "w") H (get page "h")
        cs (get j "cells")
        cells (vec (if (and (seq cs) (sequential? (first cs))) (first cs) cs))
        rows (grid (get j "otsl"))
        want (count (for [r rows t r :when (#{"fcel" "ecel"} t)] t))]
    (cond
      (not= want (count cells)) (do (C/warn! st "otsl and cells disagree (skipped)") nil)
      (not= 1 (count (set (map count rows)))) (do (C/warn! st "ragged otsl rows (skipped)") nil)
      :else
      (let [ci (atom 0)
            rowkids
            (vec (for [[r toks] (map-indexed vector rows)]
                   (let [kids (vec (for [[c t] (map-indexed vector toks)
                                         :when (#{"fcel" "ecel"} t)]
                                     (let [cell (nth cells @ci)
                                           _ (swap! ci inc)
                                           cs (inc (count (take-while #{"lcel" "xcel"} (drop (inc c) toks))))
                                           rs (inc (count (take-while #(#{"ucel" "xcel"} (nth % c)) (drop (inc r) rows))))
                                           text (C/norm (apply str (get cell "tokens")))
                                           f (cond-> (om)
                                               (> rs 1) (assoc "rowspan" rs)
                                               (> cs 1) (pj/oassoc "colspan" cs)
                                               (or (= t "ecel") (empty? text)) (pj/oassoc "empty" true))
                                           [x0 y0 x1 y1] (take 4 (get cell "bbox"))
                                           x0 (max 0 x0) y0 (max 0 y0) x1 (min W x1) y1 (min H y1)
                                           at (when (and (> x1 x0) (> y1 y0)) (C/region 1 x0 y0 x1 y1))]
                                       (C/node "cell" {:fields f :at at :text (when (seq text) text)}))))]
                     (C/node "row" {:children kids}))))
            g (C/node "rowgroup" {:fields (om "role" "body") :children rowkids})
            tab (C/node "table" {:fields (om "columns" (count (first rows))) :children [g]})
            ann (C/annotated {:structure ["table" "row" "cell"]
                              :attributes (om "table" ["columns"] "cell" ["rowspan" "colspan" "empty" "text"])
                              :pages ["n" "px" "kind"]})
            prov (om "producer" (str "FinTabNet (IBM, SEC filings) in OTSL form, ds4sd/FinTabNet_OTSL; source "
                                     (get j "filename" "?"))
                     "variants" (om "image" rel-img))]
        (C/gold gid ann [page] [tab] fmt :provenance prov)))))

(defn items [corpus st]
  (let [base (str corpus "/tables/fintabnet-otsl")]
    (for [shard (sort (map #(str (fs/file-name %)) (fs/list-dir base)))
          :let [d (str base "/" shard)]
          :when (fs/directory? d)
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname ".json")]
      (let [j (pj/parse-file (str d "/" fname))
            imgp (str d "/" (get-in j ["image" "file"]))
            gid (str "fintabnet-" (first (str/split shard #"-" 2)) "-" (subs fname 0 (- (count fname) 5)))]
        [gid (table-gold gid j imgp (C/rel imgp) st)]))))

(defn import! [opts] (C/run "fintabnet_otsl" items opts))
