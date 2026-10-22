(ns synth.build
  "Facts, manifest and gold for the synthetic corpus (DEVLOG P2.8, D063).

      bb -cp tools:tools/lib -m synth.build

  Run by print.sh after the PDFs exist.  For every test/corpus/synth/html/STEM.html:

    1. LAYOUT FACTS from pdf/STEM.pdf (poppler's pdfinfo and pdftotext): the page count and sizes, and
       for each `<table data-multipage>` the page every row landed on (found by the row's first cell,
       which the corpus keeps unique).  These are the only things the HTML cannot say, and they are
       committed in MANIFEST.json so that nothing downstream needs a PDF tool;
    2. MANIFEST.json: producer, tool versions, sha256 and size of every file;
    3. gold/STEM.gold.json: html2gold/convert(html, facts).

  kin/scripts/gold-check.py re-derives (3) from (HTML + MANIFEST facts) (by calling
  `bb -m synth.html2gold check`) and requires it to equal the committed gold."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nfc]
            [pyfmt]
            [pyjson :as pj :refer [om oassoc]]
            [synth.html2gold :as h2g])
  (:import (java.security MessageDigest)))

(def CORPUS "test/corpus/synth")
(def TABLE-MODEL-PRODUCERS
  {"table-ledger-sparse" "emon synth.pdfw sparse-ledger v1"
   "table-summary-and-ledger" "emon synth.pdfw summary-and-ledger v1"})

(defn sha [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn run [& cmd]
  (let [r (apply p/sh cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "command failed: " (str/join " " cmd)) r)))
    (:out r)))

(defn page-sizes [pdf]
  (let [out (run "pdfinfo" "-f" "1" "-l" "9999" pdf)
        sizes (vec (for [[_ _ w h] (re-seq #"(?m)^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts" out)]
                     [(pyfmt/round-half-even (parse-double w) 2) (pyfmt/round-half-even (parse-double h) 2)]))
        n (parse-long (second (re-find #"(?m)^Pages:\s+(\d+)" out)))
        producer (second (re-find #"(?m)^Producer:\s+(.*)$" out))]
    (assert (= n (count sizes)) (str pdf " " n " " (count sizes)))
    [sizes producer]))

(defn norm [s] (str/replace s #"(?U)\s+" " "))

(defn row-pages
  "{table id: [page of each <tr> in document order]} for data-multipage tables."
  [html pdf npages]
  (let [root (h2g/parse html)
        texts (atom {})
        text (fn [pg] (or (get @texts pg)
                          (let [t (norm (run "pdftotext" "-f" (str pg) "-l" (str pg) "-raw" pdf "-"))]
                            (swap! texts assoc pg t)
                            t)))]
    (reduce
     (fn [out t]
       (if (or (not= (h2g/etag t) "table") (nil? (h2g/eget t "data-multipage")))
         out
         (let [cur (volatile! 1)
               pages (vec (for [tr (h2g/walk t) :when (= (h2g/etag tr) "tr")]
                            (let [first-cell (first (filter #(and (h2g/el? %) (contains? #{"td" "th"} (h2g/etag %))) (h2g/ekids tr)))
                                  key (h2g/text-content first-cell)
                                  re (re-pattern (str "(?U)(?<![\\w-])" (java.util.regex.Pattern/quote key) "(?![\\w-])"))
                                  found (first (filter #(re-find re (text %)) (range @cur (inc npages))))]
                              (when-not found
                                (throw (ex-info (str "row " (pr-str key) " of table " (h2g/eget t "id") " not found in " pdf) {})))
                              (vreset! cur found)
                              found)))]
           (oassoc out (h2g/eget t "id") pages))))
     (om) (h2g/walk root))))

;; ---------------------------------------------------------------- P2.13 facts (D114)
;;
;; What only the print knows about the new documents: for every leaf block of the gold, where its lines
;; went.  From `pdftotext -bbox-layout` LINES (no word order is trusted): every printed line is found in
;; the concatenation of the blocks' printed text (white space removed), by the line's own characters;
;; a line found exactly once is anchored, the rest (a drop cap's single letter, a two-word last line)
;; are anchored by touching an anchored line of the same block.  From the anchors:
;;   lines    the visual lines of each block that has `align` or an initial;
;;   hyphens  every line that printed a hyphen the HTML does not have, in the middle of a word;
;;   frags    (layout documents) each block's runs [page, band, col] in reading order: col is the index of
;;            the line's left edge among the N most common left edges of the column container
;;            (`data-cols`), band the rank of the block's segment (container, or a flow between containers,
;;            cut again at each `data-span`) among the segments with lines on that page, top down.
;; and the build FAILS when a block cannot be anchored, when a drop cap does not sit beside exactly the
;; `data-lines` lines it declares, or when columns-continue has no paragraph that printed on two pages
;; and none that broke between two columns.

(defn- unescape-xml [s]
  (-> s (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'") (str/replace "&apos;" "'") (str/replace "&amp;" "&")))

(def ^:private TOK #"<page width=\"([\d.]+)\" height=\"([\d.]+)\">|<line xMin=\"([\d.]+)\" yMin=\"([\d.]+)\" xMax=\"([\d.]+)\" yMax=\"([\d.]+)\">|<word xMin=\"[\d.]+\" yMin=\"[\d.]+\" xMax=\"[\d.]+\" yMax=\"[\d.]+\">([^<]*)</word>")

(defn- rtl-cp? [c] (or (<= 0x0590 c 0x08FF) (<= 0xFB1D c 0xFDFF) (<= 0xFE70 c 0xFEFF)))

(defn- logical-words
  "poppler writes right-to-left lines in visual order (the words left to right, each word's letters
  reversed); answer the line's words in logical order."
  [words]
  (let [cps (mapcat #(.toArray (.codePoints ^String %)) words)
        rtl (count (filter rtl-cp? cps))
        letters (count (filter #(Character/isLetter (int %)) cps))]
    (if (and (pos? letters) (>= (* 2 rtl) letters))
      (vec (reverse (map (fn [w] (if (some rtl-cp? (.toArray (.codePoints ^String w)))
                                   (str (.reverse (StringBuilder. ^String w)))
                                   w))
                         words)))
      words)))

(defn pdf-lines
  "[{:page :x0 :y0 :x1 :y1 :text}] of pdftotext -bbox-layout, words joined in logical order."
  [pdf]
  (let [out (run "pdftotext" "-bbox-layout" pdf "-")
        page (volatile! 0) cur (volatile! nil) acc (volatile! [])
        ;; poppler writes a hyphen at a line's end as U+2010; the PDF's ToUnicode says U+002D (qpdf: `<0010> <0011>
        ;; <002D>`), which is what emon's text layer will hold, so that is what `printed` records
        flush! (fn [] (when-let [l @cur]
                        (vswap! acc conj (assoc l :text (str/replace (nfc/normalize (str/join " " (logical-words (:words l)))) "‐" "-")))
                        (vreset! cur nil)))]
    (doseq [[_ pw _ lx0 ly0 lx1 ly1 w] (re-seq TOK out)]
      (cond pw (do (flush!) (vswap! page inc))
            lx0 (do (flush!) (vreset! cur {:page @page :x0 (parse-double lx0) :y0 (parse-double ly0)
                                           :x1 (parse-double lx1) :y1 (parse-double ly1) :words []}))
            :else (vswap! cur update :words conj (unescape-xml w))))
    (flush!)
    @acc))

(defn- strip-ws [s] (str/replace s #"[\s ​]+" ""))

(defn- occurrences [^String s ^String t]
  (loop [i 0 acc []]
    (let [j (.indexOf s t (int i))]
      (if (neg? j) acc (recur (inc j) (conj acc j))))))

(defn- block-stream
  "{:s stripped text of every block, in order; :blk block index per char; :orig index into the block's
  own printed text (with its spaces) per char; :len [stripped length per block]}."
  [blocks]
  (let [sb (StringBuilder.) blk (java.util.ArrayList.) orig (java.util.ArrayList.)]
    (doseq [[bi b] (map-indexed vector blocks)]
      (let [t (nfc/normalize (:stream b))]
        (doseq [i (range (count t))]
          (when-not (re-matches #"[\s ​]" (str (.charAt t i)))
            (.append sb (.charAt t i)) (.add blk bi) (.add orig i)))))
    {:s (str sb) :blk (vec blk) :orig (vec orig)}))

(defn- variants [t]
  (cond-> [[t nil]]
    (and (> (count t) 1) (or (str/ends-with? t "-") (str/ends-with? t "‐")))
    (conj [(subs t 0 (dec (count t))) (str (.charAt ^String t (dec (count t))))])))

(defn- split-by-block
  "A printed line can run over several blocks (a table row poppler read as one line): one piece per block."
  [m blk]
  (loop [a (:a m) acc []]
    (if (>= a (:b m))
      acc
      (let [bk (blk a)
            e (or (first (filter #(not= (blk %) bk) (range a (:b m)))) (:b m))]
        (recur e (conj acc (assoc m :a a :b e :hy (when (= e (:b m)) (:hy m)))))))))

(defn anchor-lines
  "Anchor every pdf line to a range of the stream: {:anchored [{:line L :a :b :hy}] :loose [lines]}.  See the
  section comment."
  [lines {:keys [s blk]}]
  (let [items (vec (for [l lines :let [t (strip-ws (:text l))] :when (seq t)] {:line l :t t}))
        matched (volatile! {})                                     ; item index -> {:a :b :hy}
        block-start? (fn [p] (or (= p 0) (not= (blk (dec p)) (blk p))))
        block-end? (fn [q] (or (= q (count s)) (not= (blk (dec q)) (blk q))))
        ;; pass 1: long lines, unique in the stream
        _ (doseq [[i it] (map-indexed vector items)]
            (when (>= (count (:t it)) 10)
              (when-let [m (some (fn [[t hy]]
                                   (let [cs (occurrences s t)]
                                     (when (= 1 (count cs)) {:a (first cs) :b (+ (first cs) (count t)) :hy hy})))
                                 (variants (:t it)))]
                (vswap! matched assoc i m))))
        ;; touching an anchored line of the same block (or being a whole block); of several places, the
        ;; one whose neighbour is on the same page and the same printed row, or within 30 pt and touching
        ;; in x (two `I` drop caps: each is beside its own paragraph)
        pass2! (fn []
                 (let [ends (volatile! {}) starts (volatile! {}) changed (volatile! false)
                       _ (doseq [[i m] @matched] (vswap! ends assoc (:b m) i) (vswap! starts assoc (:a m) i))]
                   (doseq [[i it] (map-indexed vector items) :when (not (contains? @matched i))]
                     (let [l (:line it)
                           ;; poppler's own order is a hint worth trying first: the line it read just before
                           ;; (or after) this one, if anchored, says where this one must be
                           prev (when (pos? i) (get @matched (dec i)))
                           next (get @matched (inc i))
                           by-order (some (fn [[t hy]]
                                            (let [n (count t)]
                                              (or (when (and prev (<= (+ (:b prev) n) (count s)) (= (blk (:b prev)) (blk (dec (:b prev))))
                                                             (.startsWith ^String s ^String t (int (:b prev))))
                                                    {:a (:b prev) :b (+ (:b prev) n) :hy hy})
                                                  (when (and next (>= (- (:a next) n) 0) (= (blk (dec (:a next))) (blk (:a next)))
                                                             (.startsWith ^String s ^String t (int (- (:a next) n))))
                                                    {:a (- (:a next) n) :b (:a next) :hy hy}))))
                                          (variants (:t it)))
                           m (or by-order (some (fn [[t hy]]
                                     (let [cs (keep (fn [p]
                                                      (let [q (+ p (count t))
                                                            nb (cond (and (contains? @ends p) (> p 0) (= (blk p) (blk (dec p)))) (get @ends p)
                                                                     (and (contains? @starts q) (< q (count s)) (= (blk (dec q)) (blk q))) (get @starts q)
                                                                     (and (block-start? p) (block-end? q)) i)]
                                                        (when nb [p (:line (nth items nb))
                                                                  (+ (if (and (contains? @ends p) (> p 0) (= (blk p) (blk (dec p)))) 1 0)
                                                                     (if (and (contains? @starts q) (< q (count s)) (= (blk (dec q)) (blk q))) 1 0))])))
                                                    (occurrences s t))
                                           best (when (seq cs) (apply max (map #(nth % 2) cs)))
                                           cs (if (> (count cs) 1) (filter #(= best (nth % 2)) cs) cs)
                                           cs (if (> (count cs) 1)
                                                (filter (fn [[_ nl _]] (and (= (:page nl) (:page l))
                                                                          (let [dy (Math/abs (- (:y0 nl) (:y0 l)))]
                                                                            (or (< dy 3.0)
                                                                                (and (< dy 30.0)
                                                                                     (< (- (max (:x0 nl) (:x0 l)) (min (:x1 nl) (:x1 l))) 12.0))))))
                                                        cs)
                                                cs)]
                                       (when (= 1 (count cs)) {:a (ffirst cs) :b (+ (ffirst cs) (count t)) :hy hy})))
                                   (variants (:t it))))]
                       (when m (vswap! matched assoc i m) (vreset! changed true))))
                   @changed))
        ;; k unanchored lines with the same text and k whole blocks of that text (a column of cells that
        ;; all say `South`): in reading order
        group! (fn []
                 (let [changed (volatile! false)]
                   (doseq [[t grp] (group-by :t (for [[i it] (map-indexed vector items) :when (not (contains? @matched i))] (assoc it :i i)))]
                     (let [cs (filterv #(block-end? (+ % (count t))) (filter block-start? (occurrences s t)))
                           grp (sort-by (fn [it] [(:page (:line it)) (:y0 (:line it)) (:x0 (:line it))]) grp)]
                       (when (and (> (count cs) 1) (= (count cs) (count grp)))
                         (doseq [[it p] (map vector grp cs)]
                           (vswap! matched assoc (:i it) {:a p :b (+ p (count t)) :hy nil})
                           (vreset! changed true)))))
                   @changed))]
    (loop []
      (let [c1 (loop [any false] (if (pass2!) (recur true) any))
            c2 (group!)]
        (when (or c1 c2) (recur))))
    {:anchored (vec (mapcat (fn [[i m]] (split-by-block (assoc m :line (:line (nth items i))) blk)) (sort-by key @matched)))
     :loose (vec (for [[i it] (map-indexed vector items) :when (not (contains? @matched i))] (:line it)))}))

(defn- median [xs] (let [v (vec (sort xs)) n (count v)] (when (pos? n) (nth v (quot n 2)))))

(defn- cluster-starts
  "The n most common left edges (merging edges within 1.5 pt), left to right."
  [xs n]
  (let [buckets (sort (frequencies (map #(Math/round (double %)) xs)))
        merged (reduce (fn [acc [x c]]
                         (if (and (seq acc) (<= (- x (:x (peek acc))) 1.5))
                           (update acc (dec (count acc)) #(update % :c + c))
                           (conj acc {:x x :c c})))
                       [] buckets)]
    (vec (sort (map :x (take n (sort-by (comp - :c) merged)))))))

(defn- col-of [starts x]
  (let [i (count (take-while #(<= % (+ x 2.0)) starts))] (max 0 (dec i))))

(defn block-facts
  "For the gold's leaf blocks (html2gold/block-records) and the printed pdf lines: {:lines {id n} :hyphens [...]
  :frags {id [[page band col]..]} (when layout?) :report ...}.  Throws when a block cannot be anchored."
  [blocks pdf-lines* layout? stem]
  (let [st (block-stream blocks)
        {:keys [anchored loose]} (anchor-lines pdf-lines* st)
        by-block (group-by (fn [m] ((:blk st) (:a m))) anchored)
        ;; coverage
        _ (doseq [[bi b] (map-indexed vector blocks)]
            (let [need (count (filter #(= % bi) (:blk st)))
                  got (reduce + (map #(- (:b %) (:a %)) (get by-block bi)))]
              (when (or (zero? got) (< (/ got (double (max 1 need))) 0.97))
                (throw (ex-info (str stem ": block " (:id b) " (" (:kind b) ") cannot be anchored in the print: "
                                     got "/" need " characters; " (pr-str (subs (:text b) 0 (min 40 (count (:text b))))) " unanchored: " (pr-str (mapv :text (take 6 loose)))) {})))))
        ;; per-block lines in offset order, the initial glyph's own line set aside
        prep (into {}
                   (for [[bi b] (map-indexed vector blocks)]
                     (let [ls (sort-by :a (get by-block bi))
                           base (first (remove nil? [(some-> ls first :a)]))
                           ini-len (count (strip-ws (get-in b [:initial :text] "")))
                           hs (map #(- (:y1 (:line %)) (:y0 (:line %))) ls)
                           med (median (map #(- (:y1 (:line %)) (:y0 (:line %))) (rest ls)))
                           glyph? (fn [m] (and (:initial b) (pos? ini-len) (= (:a m) base) (<= (- (:b m) (:a m)) ini-len)
                                               med (> (- (:y1 (:line m)) (:y0 (:line m))) (* 1.4 med))))
                           glyph (first (filter glyph? ls))]
                       [bi {:lines (vec (remove #(identical? % glyph) ls)) :glyph glyph}])))
        ;; visual lines: consecutive pieces overlapping vertically are one
        visual (fn [bi]
                 (let [ls (:lines (prep bi))]
                   (reduce (fn [groups m]
                             (let [l (:line m) g (peek groups)
                                   p (when g (:line (peek g)))]
                               (if (and p (= (:page p) (:page l))
                                        (let [ov (- (min (:y1 p) (:y1 l)) (max (:y0 p) (:y0 l)))]
                                          (>= ov (* 0.4 (min (- (:y1 p) (:y0 p)) (- (:y1 l) (:y0 l)))))))
                                 (conj (pop groups) (conj g m))
                                 (conj groups [m]))))
                           [] ls)))
        vis (into {} (for [bi (range (count blocks))] [bi (visual bi)]))
        ;; a printed row poppler split into pieces starts at its first piece
        left (into {} (for [[_ gs] vis g gs m g] [m (:x0 (:line (first g)))]))
        ;; column starts per container (layout documents)
        starts (when layout?
                 (into {} (for [[c bis] (group-by #(:cont (nth blocks %)) (range (count blocks))) :when c]
                            [c (cluster-starts (for [bi bis g (vis bi)] (:x0 (:line (first g))))
                                               (:n (nth blocks (first bis))))])))
        segs-on-page (when layout?
                       (reduce (fn [m [bi {:keys [lines]}]]
                                 (reduce (fn [m l] (update m (:page (:line l)) (fnil conj #{}) (:seg (nth blocks bi)))) m lines))
                               {} prep))
        place (fn [bi l]
                (let [b (nth blocks bi) pg (:page (:line l))]
                  [pg (.indexOf ^java.util.List (vec (sort (get segs-on-page pg))) (:seg b))
                   (if (:cont b) (col-of (get starts (:cont b)) (get left l (:x0 (:line l)))) 0)]))
        lines-fact (into (sorted-map)
                         (for [[bi b] (map-indexed vector blocks) :when (or (:align b) (:initial b))]
                           [(:id b) (count (visual bi))]))
        ;; drop caps: the lines beside the cap
        caps (vec (for [[bi b] (map-indexed vector blocks) :when (= "drop" (get-in b [:initial :form]))]
                    (let [ls (:lines (prep bi))
                          rtl? (boolean (re-find #"[֐-ࣿ]" (:text b)))
                          first-frag (if layout? (take-while #(= (place bi %) (place bi (first ls))) ls) ls)
                          edge (fn [m] (if rtl? (- (:x1 (:line m))) (:x0 (:line m))))
                          ordinary (apply min (map edge first-frag))
                          n (count (take-while #(>= (edge %) (+ ordinary 6.0)) first-frag))]
                      (when (not= n (get-in b [:initial :lines]))
                        (throw (ex-info (str stem ": block " (:id b) " declares a " (get-in b [:initial :lines])
                                             "-line drop cap but " n " lines sit beside it (" (pr-str (subs (:text b) 0 20)) ")") {})))
                      {:id (:id b) :lines n :form "drop"})))
        hyphens (vec (for [[bi b] (map-indexed vector blocks)
                           m (sort-by :a (get by-block bi))
                           :when (and (:hy m) (< (:b m) (count (:s st))) (= bi ((:blk st) (:b m)))
                                      (= 1 (- ((:orig st) (:b m)) ((:orig st) (dec (:b m)))))
                                      (not (contains? #{"-" "‐"} (str (.charAt ^String (:s st) (dec (:b m)))))))]
                       (let [t (nfc/normalize (:stream b))
                             o ((:orig st) (:b m))
                             ws? #(re-matches #"\s" (str %))
                             a (loop [i o] (if (and (pos? i) (not (ws? (.charAt t (dec i))))) (recur (dec i)) i))
                             e (loop [i o] (if (and (< i (count t)) (not (ws? (.charAt t i)))) (recur (inc i)) i))
                             word (subs t a e)
                             printed (str (subs t a o) (:hy m) (subs t o e))]
                         (om "id" (:id b) "printed" printed "word" word))))
        frags (when layout?
                (into (sorted-map)
                      (for [[bi b] (map-indexed vector blocks)]
                        [(:id b) (vec (map first (partition-by identity (map #(place bi %) (:lines (prep bi))))))])))]
    {:lines lines-fact :hyphens hyphens :frags frags :caps caps :loose (count loose) :loose-text (mapv :text loose)}))

(defn extra-facts
  "The facts of a NEW document (D114: its gold is emon 1.1): {:facts (om \"frags\" .. \"lines\" .. \"hyphens\" ..) :report ..},
  or nil for one of the 36 of P2.8.  Throws when the print does not show what the HTML declares."
  [html pdf stem base-facts]
  (let [out (atom nil)
        gold (h2g/convert html (pj/parse (pj/dumps base-facts)) stem nil {:ctx-out out})]
    (when (= "1.1" (get-in gold ["model" "emon"]))
      (let [layout? (boolean (get-in gold ["annotated" "layout"]))
            blocks @out
            f (block-facts blocks (pdf-lines pdf) layout? stem)
            by-id (fn [m] (apply om (mapcat (fn [[id v]] [(str id) v]) (sort-by key m))))]
        (when (= stem "columns-continue")
          (let [ps (filter #(= "paragraph" (:kind %)) blocks)
                fr (fn [b] (get (:frags f) (:id b)))]
            (when-not (some #(> (count (distinct (map first (fr %)))) 1) ps)
              (throw (ex-info "columns-continue: no paragraph printed on two pages" {})))
            (when-not (some #(some (fn [[a b]] (and (= (first a) (first b)) (not= (nth a 2) (nth b 2)))) (partition 2 1 (fr %))) ps)
              (throw (ex-info "columns-continue: no paragraph broke between two columns" {})))))
        {:facts (cond-> (om)
                  layout? (assoc "frags" (by-id (:frags f)))
                  true (assoc "lines" (by-id (:lines f)) "hyphens" (:hyphens f)))
         :report (str (count blocks) " blocks, " (if layout? (str (reduce + (map count (vals (:frags f)))) " fragments, ") "")
                      (count (:hyphens f)) " hyphen points, " (:loose f) " unanchored lines " (pr-str (vec (take 6 (:loose-text f)))) "; initials "
                      (pr-str (mapv :lines (:caps f))) "; lines " (pr-str (:lines f)))}))))

(defn tool-version [& cmd]
  (let [r (apply p/sh cmd)]
    (first (str/split-lines (str/trim (str (:out r) (:err r)))))))

(def TL-UNTRUSTED #{"tl-broken-shift" "tl-broken-unmapped" "hidden-older-ocr"})

(defn -main [& _]
  (let [stems (sort (for [f (.list (io/file CORPUS "html")) :when (str/ends-with? f ".html")] (subs f 0 (- (count f) 5))))
        producer (pj/oparse (slurp (str CORPUS "/PRODUCER.json") :encoding "UTF-8"))
        docs (volatile! (om))
        ;; what degrade_fusion.clj did to the FUSION set's pages (P6.6, D209): skew, turns, overlay boxes, the seal
        fusion (let [f (io/file CORPUS "scan-ovl" "FACTS.json")] (if (.exists f) (pj/oparse (slurp f :encoding "UTF-8")) (om)))
        ;; the GRAPHICS set's scan facts (degrade_graphics.clj) and the tagged tables' declared verdicts (tagged_tables.clj), P6.8
        read-facts (fn [f] (let [f (io/file CORPUS f)] (if (.exists f) (pj/oparse (slurp f :encoding "UTF-8")) (om))))
        gfx (read-facts "FACTS-gfx.json")
        ttab (read-facts "FACTS-ttab.json")
        _ (.mkdirs (io/file CORPUS "gold"))]
    (doseq [stem stems]
      (let [hp (str CORPUS "/html/" stem ".html")
            pp (str CORPUS "/pdf/" stem ".pdf")
            html (slurp hp :encoding "UTF-8")
            [sizes prod] (page-sizes pp)
            prod (or (get TABLE-MODEL-PRODUCERS stem) prod)
            rp (row-pages html pp (count sizes))
            facts (cond-> (om "pages" sizes) (seq rp) (assoc "rows" rp))
            extra (extra-facts html pp stem facts)
            _ (when extra (println (str "  facts " stem ": " (:report extra))))
            facts (reduce-kv oassoc facts (or (:facts extra) {}))
            ;; a text layer that is wrong or older than the pixels ON PURPOSE (the scorer's tl-override skips it)
            facts (cond-> facts (contains? TL-UNTRUSTED stem) (oassoc "tl-untrusted" true))
            ff (get fusion stem)
            manifest-facts (cond-> facts
                             ff (-> (oassoc "skew" (get ff "skew")) (oassoc "skew-ovl" (get ff "skew-ovl"))
                                    (oassoc "turns" (get ff "turns")) (oassoc "overlays" (get ff "overlays"))
                                    (oassoc "seal" (get ff "seal")) (oassoc "watermark" (get ff "watermark"))
                                    (oassoc "bleed" (get ff "bleed"))))
            gf (get gfx stem)
            manifest-facts (cond-> manifest-facts
                             gf (-> (oassoc "skew-scan" (get gf "skew-scan")) (oassoc "dpi" (get gf "dpi")))
                             (and gf (not ff) (.exists (io/file CORPUS "scan-color" (str stem ".pdf")))) (oassoc "skew" (get gf "color-skew"))
                             (get ttab stem) (oassoc "tag-verdict" (get-in ttab [stem "tag-verdict"])))
            rel (fn [d & [ext]] (str d "/" stem (or ext ".pdf")))
            files (cond-> (om "html" (rel "html" ".html") "pdf" (rel "pdf"))
                    (re-find #"src=\"img/" html) (assoc "asset" (str "html/" (second (re-find #"src=\"(img/[^\"]+)\"" html))))
                    (.exists (io/file CORPUS (rel "pdf-tagged"))) (assoc "pdf-tagged" (rel "pdf-tagged"))
                    (.exists (io/file CORPUS (rel "scan"))) (assoc "scan" (rel "scan"))
                    (.exists (io/file CORPUS (rel "scan-color"))) (assoc "scan-color" (rel "scan-color"))
                    (.exists (io/file CORPUS (rel "scan-ovl"))) (assoc "scan-ovl" (rel "scan-ovl")))
            entry (om "files" (apply om (mapcat (fn [[k v]] [k (om "path" v "sha256" (sha (str CORPUS "/" v))
                                                                  "bytes" (.length (io/file CORPUS v)))]) files))
                      "pdf-producer" prod "facts" manifest-facts)
            prov (om "html" (files "html") "pdf" (files "pdf") "producer" (if (contains? TABLE-MODEL-PRODUCERS stem) prod (str (get producer "version") "; " prod)))
            variants (into (om) (filter (fn [[k _]] (#{"pdf-tagged" "scan" "scan-color"} k)) files))
            prov (if (seq variants) (assoc prov "variants" variants) prov)
            gold (h2g/convert html (pj/parse (pj/dumps facts)) stem prov)]
        (vswap! docs oassoc stem entry)
        (spit (str CORPUS "/gold/" stem ".gold.json") (h2g/gold-text gold) :encoding "UTF-8")))
    (let [manifest (om "format" 1
                       "note" "Committed so that no gate needs a producer (D041). Regenerate with tools/synth/print.sh."
                       "producer" producer
                       "tools" (om "pdfinfo" (tool-version "pdfinfo" "-v") "magick" (tool-version "magick" "-version"))
                       "docs" @docs)]
      (spit (str CORPUS "/MANIFEST.json") (h2g/gold-text manifest) :encoding "UTF-8"))
    (println (str "  built " (count stems) " documents: MANIFEST.json and gold/*.gold.json"))))
