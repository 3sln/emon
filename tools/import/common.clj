(ns import.common
  "Shared helpers for the public-dataset importers (DEVLOG P2.11).  Tooling, not runtime.

  Every importer turns one dataset's NATIVE ground truth into emon GOLD files
  (schema/emon-gold-1.json, D072): a partial emon-1 Model plus an `annotated`
  declaration of exactly what the source claims.  Output goes OUTSIDE git, under
  corpus/_gold/<dataset>/ (corpus/ is never committed).  Babashka (maintainer policy: DEVLOG
  \"scripting is babashka\"); the ONE Python step is the jsonschema 2020-12 validator in
  tools/oracle/gold_validate.py, a third-party oracle, run after the files are written.

  CONVENTIONS shared by all importers (DEVLOG D0xx, tools/import/README in
  test/corpus/DATASETS.md):

    * UNITS AND ORIGIN.  `at` regions are [page, x0, y0, x1, y1] in page space
      (SPEC 3.4): top-left origin, y down.  PDF-backed sources are in POINTS
      (1/72 in) after converting the source's own origin.  RASTER sources
      (no resolution stated) are in PIXELS, with the page's nominal size set
      to the pixel size (1 px = 1 pt), `px` recorded and `dpi` NOT annotated:
      a scorer rescales by (emon page w / gold page px[0]).
    * `annotated.attributes[kind]` may list the pseudo-field `text` (the node's
      own text is annotated) and `at` (its region is annotated).  The global
      `annotated.text` flag is only set where leaf text AND reading order are
      annotated.
    * Body order is the source's order, or a top-to-bottom, left-to-right sort
      when the source has none.  It is never claimed unless `annotated.text`.
    * Text is NFC, whitespace-collapsed, stripped (the gold schema's rule).
    * Nodes the source does not annotate but that are useful context are still
      emitted; a metric reads `annotated` and skips them.

  Data shapes.  A gold is built with `pyjson/om` (ordered) maps, so the files are byte-identical
  to what the Python importers wrote.  A block BEFORE ids are assigned is a plain map
  {:kind :fields <om of extra fields> :at [[...]] :text s :children [...]} made by `node`."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.data.xml :as xml]
            [clojure.string :as str]
            [nfc]
            [pyjson :as pj :refer [om]]))

(def ^String root (str (fs/canonicalize (fs/path (fs/parent (fs/parent (fs/parent *file*)))))))
(def corpus-dir (or (System/getenv "EMON_CORPUS") (str root "/corpus")))
(def out-dir (or (System/getenv "EMON_GOLD_OUT") (str corpus-dir "/_gold")))

(def all-kinds ["section" "heading" "paragraph" "list" "item" "table" "rowgroup" "row" "cell"
                "caption" "figure" "seal" "formula" "code" "quote" "note" "field" "break"])

;; ------------------------------------------------------------------ text, numbers

(def ^:private ws-re (re-pattern "(?U)[\\s\\x1c-\\x1f]+"))

(defn norm
  "NFC, whitespace collapsed, stripped."
  [s]
  (-> (nfc/normalize (or s ""))
      (str/replace ws-re " ")
      (str/replace #"^ +| +$" "")))

(defn num
  "A coordinate: at most two decimals; an integral value prints as an integer.
  (Python: round(float(x), 2), an exact half-even on the binary value.)"
  [x]
  (let [bd (.setScale (BigDecimal. (double x)) 2 java.math.RoundingMode/HALF_EVEN)
        d (.doubleValue bd)]
    (if (== d (Math/floor d)) (long d) d)))

(defn region [page x0 y0 x1 y1]
  (let [[x0 x1] (if (< x1 x0) [x1 x0] [x0 x1])
        [y0 y1] (if (< y1 y0) [y1 y0] [y0 y1])]
    [page (num x0) (num y0) (num x1) (num y1)]))

(defn union
  "[x0 y0 x1 y1] enclosing every box."
  [boxes]
  [(apply min (map #(nth % 0) boxes)) (apply min (map #(nth % 1) boxes))
   (apply max (map #(nth % 2) boxes)) (apply max (map #(nth % 3) boxes))])

;; ------------------------------------------------------------------ image headers

(defn- u8 [^bytes b i] (bit-and (aget b (int i)) 0xff))
(defn- u16 [^bytes b i be?]
  (if be? (bit-or (bit-shift-left (u8 b i) 8) (u8 b (inc i)))
      (bit-or (bit-shift-left (u8 b (inc i)) 8) (u8 b i))))
(defn- u32 [^bytes b i be?]
  (if be? (bit-or (bit-shift-left (u16 b i true) 16) (u16 b (+ i 2) true))
      (bit-or (bit-shift-left (u16 b (+ i 2) false) 16) (u16 b i false))))
(defn- s32le [^bytes b i] (let [v (u32 b i false)] (if (>= v 0x80000000) (- v 0x100000000) v)))

(defn- tiff-orientation [^bytes t]
  (try
    (let [be? (not= (u8 t 0) 0x49)
          ifd (u32 t 4 be?)
          n (u16 t ifd be?)]
      (or (first (for [i (range n)
                       :let [o (+ ifd 2 (* 12 i))]
                       :when (= (u16 t o be?) 274)]
                   (u16 t (+ o 8) be?)))
          1))
    (catch Throwable _ 1)))

(defn image-info
  "[width height format exif-orientation] of a PNG/JPEG/TIFF/GIF/BMP, from its header."
  [path]
  (let [^bytes data (fs/read-all-bytes path)
        len (alength data)
        at (fn [i n] (when (<= (+ i n) len) (java.util.Arrays/copyOfRange data (int i) (int (+ i n)))))
        starts? (fn [& bs] (and (>= len (count bs)) (every? true? (map-indexed (fn [i b] (= (u8 data i) b)) bs))))]
    (cond
      (starts? 0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a)
      [(u32 data 16 true) (u32 data 20 true) "png" 1]

      (starts? 0xff 0xd8)
      (loop [pos 2 orient 1]
        ;; find the next 0xFF, skip runs of them, read the marker
        (let [p (loop [p pos] (if (and (< p len) (not= (u8 data p) 0xff)) (recur (inc p)) p))
              p (loop [p p] (if (and (< p len) (= (u8 data p) 0xff)) (recur (inc p)) p))]
          (when (>= p len) (throw (ex-info (str path ": no JPEG frame") {})))
          (let [m (u8 data p)
                p (inc p)]
            (if (or (= m 0xd8) (= m 0x01) (<= 0xd0 m 0xd7))
              (recur p orient)
              (let [n (u16 data p true)
                    seg-start (+ p 2)
                    seg-len (- n 2)
                    orient (if (and (= m 0xe1) (>= seg-len 6)
                                    (= (seq (at seg-start 6)) (seq (byte-array [0x45 0x78 0x69 0x66 0 0]))))
                             (tiff-orientation (java.util.Arrays/copyOfRange data (int (+ seg-start 6)) (int (+ seg-start seg-len))))
                             orient)]
                (if (and (<= 0xc0 m 0xcf) (not (#{0xc4 0xc8 0xcc} m)))
                  [(u16 data (+ seg-start 3) true) (u16 data (+ seg-start 1) true) "jpeg" orient]
                  (recur (+ seg-start seg-len) orient)))))))

      (or (starts? 0x49 0x49 0x2a 0) (starts? 0x4d 0x4d 0 0x2a))
      (let [be? (= (u8 data 0) 0x4d)
            ifd (u32 data 4 be?)
            n (u16 data ifd be?)
            tags (into {}
                       (for [i (range n)
                             :let [o (+ ifd 2 (* 12 i))
                                   t (u16 data o be?)
                                   ty (u16 data (+ o 2) be?)]]
                         [t (if (= ty 3) (u16 data (+ o 8) be?) (u32 data (+ o 8) be?))]))]
        [(tags 256) (tags 257) "tiff" (get tags 274 1)])

      (or (starts? 0x47 0x49 0x46 0x38 0x37 0x61) (starts? 0x47 0x49 0x46 0x38 0x39 0x61))
      [(u16 data 6 false) (u16 data 8 false) "gif" 1]

      (starts? 0x42 0x4d)
      [(s32le data 18) (Math/abs (long (s32le data 22))) "bmp" 1]

      :else (throw (ex-info (str path ": unknown image format") {})))))

(defn raster-page
  "[page-table-entry format] for an image file: nominal 1 px = 1 pt (see the ns docstring)."
  [path]
  (let [[w h fmt orient] (image-info path)]
    (when (not= orient 1)
      (throw (ex-info (format "%s: EXIF orientation %d (annotation space would need rotating)" path orient) {})))
    [(om "n" 1 "w" w "h" h "rot" 0 "kind" "image" "px" [w h]) fmt]))

;; ------------------------------------------------------------------ nodes

(defn node
  "A block before ids are assigned.  `fields` is an ORDERED map of extra fields (use pyjson/om)."
  ([kind] (node kind nil))
  ([kind {:keys [fields at text children]}]
   (cond-> {:kind kind :fields (or fields (om))}
     at (assoc :at (if (vector? (first at)) at [at]))
     (some? text) (assoc :text text)
     (seq children) (assoc :children (vec children)))))

(defn add-field [n k v] (update n :fields pj/oassoc k v))

(defn assign-ids
  "The node as an ordered map, `id`s in document order (pre-order), keys in the canonical order.
  counter is an atom."
  [n counter]
  (let [id @counter]
    (swap! counter inc)
    (let [kids (when (seq (:children n)) (mapv #(assign-ids % counter) (:children n)))]
      (apply array-map
             (concat ["id" id "kind" (:kind n)]
                     (mapcat identity (:fields n))
                     (when (:at n) ["at" (:at n)])
                     (when (contains? n :text) ["text" (:text n)])
                     (when kids ["children" kids]))))))

(defn annotated
  [{:keys [structure attributes text inlines furniture dropped pages]
    :or {text false inlines false furniture false}}]
  (let [s (set structure)]
    (om "structure" (filterv s all-kinds)
        "attributes" (apply array-map (mapcat (fn [[k v]] [k (vec (sort (set v)))]) attributes))
        "text" text "inlines" inlines "furniture" furniture
        "dropped" (vec dropped) "pages" (vec pages))))

(defn gold
  [gid ann pages children fmt & {:keys [lang provenance furniture dropped]}]
  (let [counter (atom 0)
        body (assign-ids {:kind "document" :fields (om) :children children} counter)
        fur (vec (for [f furniture]
                   (let [id @counter]
                     (swap! counter inc)
                     ;; a furniture entry is a node whose `id` comes first (not passed through assign-ids)
                     (apply array-map (concat ["id" id "kind" (:kind f)]
                                              (mapcat identity (:fields f))
                                              (when (:at f) ["at" (:at f)])
                                              (when (contains? f :text) ["text" (:text f)]))))))
        src (cond-> (om "pages" (count pages) "format" fmt) lang (assoc "lang" lang))]
    (apply array-map
           (concat ["emon-gold" "1.0" "id" gid]
                   (when provenance ["provenance" provenance])
                   ["annotated" ann
                    "model" (om "emon" "1.0" "source" src "settings" (om "locale" lang) "pages" (vec pages)
                                "body" body "furniture" fur "dropped" (vec dropped))]))))

(defn pdf-pages
  "[[w h rot] ...] per page, from pdfinfo (poppler)."
  [pdf]
  (let [out (:out (proc/shell {:out :string :err :string} "pdfinfo" "-f" "1" "-l" "99999" (str pdf)))
        sizes (into {} (for [[_ n w h] (re-seq #"(?m)^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts" out)]
                         [(parse-long n) [(parse-double w) (parse-double h)]]))
        rots (into {} (for [[_ n r] (re-seq #"(?m)^Page\s+(\d+) rot:\s+(\d+)" out)]
                        [(parse-long n) (parse-long r)]))]
    (vec (for [i (sort (keys sizes))]
           [(first (sizes i)) (second (sizes i)) (get rots i 0)]))))

(defn rel [path] (str (fs/relativize corpus-dir path)))

;; ------------------------------------------------------------------ XML (ElementTree's findall/find/iter/get/text)

(defn xml-root [path]
  (let [s (slurp (str path) :encoding "UTF-8")]
    (xml/parse-str (if (str/starts-with? s "﻿") (subs s 1) s))))   ; expat accepts a BOM
(defn xkids "Direct children named TAG (ET findall)." [e tag]
  (filter #(and (instance? clojure.data.xml.node.Element %) (= (:tag %) (keyword tag))) (:content e)))
(defn xfirst "First direct child named TAG, or nil (ET find)." [e tag] (first (xkids e tag)))
(defn xattr "Attribute value or nil (ET get)." [e k] (get (:attrs e) (keyword k)))
(defn xiter "Every element named TAG in the tree, SELF included, in document order (ET iter)." [e tag]
  (filter #(= (:tag %) (keyword tag)) (tree-seq #(instance? clojure.data.xml.node.Element %) :content e)))
(defn xtext "Text before the first child element, or nil (ET .text)." [e]
  (let [c (first (:content e))] (when (string? c) c)))

;; ------------------------------------------------------------------ checks (the schema is the oracle's)

(defn check
  "Every structural problem with a gold: ids in document order, page refs, inverted regions.
  (Schema validity is checked afterwards by tools/oracle/gold_validate.py.)"
  [g]
  (let [m (get g "model")
        errs (volatile! [])
        err! (fn [s] (vswap! errs conj s))
        ids (volatile! [])
        rec (fn rec [n] (vswap! ids conj (get n "id")) (run! rec (get n "children")))
        _ (rec (get m "body"))
        ids @ids
        n (count ids)
        pages (get m "pages")
        npages (count pages)
        pw (into {} (map (fn [p] [(get p "n") [(get p "w") (get p "h")]]) pages))]
    (when (not= ids (vec (range n))) (err! "body ids are not 0..n in document order"))
    (when (not= (mapv #(get % "id") (get m "furniture")) (vec (range n (+ n (count (get m "furniture"))))))
      (err! "furniture ids do not continue after the body's"))
    (when (not= npages (get-in m ["source" "pages"])) (err! "source.pages and the page table disagree"))
    (when (not= (mapv #(get % "n") pages) (vec (range 1 (inc npages)))) (err! "pages are not numbered 1..n"))
    (let [geo (fn geo [nd]
                (doseq [[p x0 y0 x1 y1] (get nd "at")]
                  (cond (not (contains? pw p)) (err! (format "node %d: region on page %d, which does not exist" (get nd "id") p))
                        (not (and (<= x0 x1) (<= y0 y1))) (err! (format "node %d: inverted region" (get nd "id")))))
                (run! geo (get nd "children")))]
      (geo (get m "body")))
    @errs))

(defn count-nodes [n] (inc (reduce + (map count-nodes (get n "children")))))

;; ------------------------------------------------------------------ driver

(defn new-stats [] (atom {:written 0 :invalid 0 :skipped 0 :warnings (array-map) :examples [] :nodes 0}))

(defn warn! [st msg] (swap! st update :warnings #(pj/oassoc % msg (inc (get % msg 0)))))

(defn ^:private oracle-invalid
  "{gid [first-error]} for every gold in DIR the jsonschema oracle rejects."
  [dir]
  (let [r (proc/shell {:out :string :err :string :continue true} "python3" (str root "/tools/oracle/gold_validate.py") dir)]
    (when (= 2 (:exit r))
      (binding [*out* *err*] (println (str/trim (:err r))))
      (System/exit 2))
    (when-not (zero? (:exit r)) (throw (ex-info (str "gold_validate failed: " (:err r)) {})))
    (into {} (for [ln (str/split-lines (:out r)) :when (not (str/blank? ln))
                   :let [j (pj/parse ln)]]
               [(get j "file") (get j "errors")]))))

(defn run
  "`gen` is (fn [corpus st]) -> lazy seq of [id gold] (or [id nil] for a skipped item).  Writes
  <out>/<name>/<id>.gold.json, checks each, validates the lot with the jsonschema oracle, prints one
  summary line.  Returns 1 if any gold is invalid (or none written), else 0.
  opts: :limit N (0 = all), :corpus, :out, :quiet."
  [name gen {:keys [limit corpus out] :or {limit 0}}]
  (let [corpus (or corpus corpus-dir)
        out (str (or out out-dir) "/" name)
        _ (fs/create-dirs out)
        ;; exFAT removes a sidecar with its real file; never iterate/delete it twice.
        _ (doseq [f (fs/list-dir out)
                  :when (and (str/ends-with? (str f) ".gold.json")
                             (not (str/starts-with? (str (fs/file-name f)) "._")))]
            (fs/delete f))
        st (new-stats)
        seen (volatile! #{})
        nodes-of (atom {})                                  ; id -> node count, for the oracle's rejects
        t0 (System/nanoTime)]
    (loop [xs (gen corpus st)]
      (when-let [[gid g] (first xs)]
        (if (nil? g)
          (do (swap! st update :skipped inc) (recur (rest xs)))
          (let [errs (cond-> (check g) (contains? @seen gid)
                             (conj (str "duplicate id " gid " (an earlier item would be overwritten)")))]
            (vswap! seen conj gid)
            (if (seq errs)
              (do (swap! st update :invalid inc)
                  (when (< (count (:examples @st)) 5) (swap! st update :examples conj (str gid ": " (first errs))))
                  (recur (rest xs)))
              (do (let [nn (count-nodes (get-in g ["model" "body"]))]
                    (swap! nodes-of assoc gid nn)
                    (swap! st update :nodes + nn))
                  (spit (str out "/" gid ".gold.json") (str (pj/dumps g {:indent 1}) "\n"))
                  (swap! st update :written inc)
                  (if (and (pos? limit) (>= (:written @st) limit))
                    nil
                    (recur (rest xs)))))))))
    ;; the third-party oracle: jsonschema 2020-12
    (when (pos? (:written @st))
      (doseq [[file errs] (oracle-invalid out)
              :let [gid (str/replace (str (fs/file-name file)) #"\.gold\.json$" "")]]
        (swap! st (fn [s] (-> s (update :invalid inc) (update :written dec)
                              (update :nodes - (get @nodes-of gid 0))
                              (update :examples #(if (< (count %) 5) (conj % (str gid ": " (first errs))) %)))))
        (fs/delete file)))
    (let [s @st
          summary (cond-> (om "dataset" name "written" (:written s) "invalid" (:invalid s) "skipped" (:skipped s)
                              "nodes" (:nodes s) "warnings" (:warnings s)
                              "seconds" (/ (Math/round (/ (- (System/nanoTime) t0) 1e8)) 10.0)
                              "out" (if (str/starts-with? out corpus) (str (fs/relativize corpus out)) out))
                    (seq (:examples s)) (assoc "invalid_examples" (:examples s)))]
      (spit (str out "/_summary.json") (pj/dumps summary {:indent 1 :ascii true}))
      (println (pj/dumps summary {:ascii true}))
      (if (or (pos? (:invalid s)) (zero? (:written s))) 1 0))))
