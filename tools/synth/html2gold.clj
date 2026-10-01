(ns synth.html2gold
  "HTML -> gold Model (DEVLOG P2.8, D041, D063). Tooling, not runtime.

      bb -cp tools:tools/lib -m synth.html2gold DOC.html FACTS.json   print the gold JSON
      bb -cp tools:tools/lib -m synth.html2gold check                 re-derive every committed gold
                                                                      (kin/scripts/gold-check.py's check 4)

  The synthetic corpus is authored as semantic HTML.  The HTML *is* the ground truth; this converter,
  written by hand (no HTML-to-Model library), reads it and writes the partial Model that a correct emon
  run would have to reproduce for the annotated parts (schema/emon-gold-1.json).  It never runs a PDF
  producer: what only the printed PDF knows (page count and size, which page a table row landed on)
  comes from FACTS, which `tools/synth/build.clj` extracted once, at print time, and committed.

  THE HTML SUBSET (anything else is an error, so a typo cannot silently lose ground truth):

    headings   h1..h6, or any element with `data-level=\"N\"` (a heading that is not an <hN>).  The level
               is the DOCUMENT's level and has nothing to do with how big the heading prints: corpus
               documents on purpose print a level 1 heading smaller than a level 2 one (SPEC 10.3).
               `<span class=\"num\">1.2</span>` inside a heading is the printed number: `number` \"1.2\",
               excluded from `text`.
    blocks     p, ul/ol (li, nested), table (caption, thead/tbody/tfoot, th/td with rowspan/colspan),
               figure / `data-figure=KIND` (figcaption), blockquote, pre, `data-seal=KIND`
               (name/date/color in `data-name`/`data-date`/`data-color`), `data-note=footnote`
               (+ `data-marker`).  div/section/article/aside/main/header/footer are transparent:
               sections are built from heading levels.
    inlines    b strong i em u s del sub sup code kbd span.sc a[href] br and `<sup data-fnref=\"1\">`
               (a note reference).
    ignored    `data-gold=\"skip\"` (chrome that prints but is not content: a fixed header, a watermark,
               a rule, a footnote's own marker), script, style, head.
    hints      `<script type=\"application/emon-gold+json\">` holds what no DOM node says: margin-box
               running text from @page, page-number text, watermark text, and `\"layout\": true` for a
               document whose column structure is annotated (`facts.frags`, D114).
    initials   `data-initial=\"drop|raised|margin\"` (+ `data-lines`, D114): on a <p>, the ::first-letter
               extent of its first text (leading punctuation, one grapheme, trailing punctuation) is the
               `initial` inline; on a <span>, <img> or <svg> that element is, its text from
               `data-initial-text`, `alt` or its content.
    alignment  `data-align=\"start|center|end|justify|none\"` on p, headings, figcaption, td/th; `none` is an
               annotated absence (a decimal column).  Once a kind carries one it must carry one on every
               node of that kind (the gold claims absence where it is absent).
    columns    `data-cols=\"N\"` on a column container, `data-span=\"all\"` on a child spanning it: they say
               nothing to the gold; `convert`'s `:ctx-out` hands build.clj each leaf block's container and
               segment so it can turn printed lines into [page, band, col] fragments.
    notes      `data-note=\"sidebar|margin\"` needs no reference (it sits beside what it annotates).

  Rules that are DECISIONS (DEVLOG D063) rather than facts:
    * a `ref` contributes no text; a `linebreak` contributes \"\\n\".
    * a caption's `label` is the matched prefix (\"Figure 2\"); its `text` is the rest, like a
      heading's `number` and `text`.
    * a footnote `note` sits where the HTML puts it, i.e. where it is read (the page bottom), and each
      `ref` targets the next unmatched note with the same marker.
    * a table's head rowgroup is the <thead>; a header row that printed again on a continuation page is
      ONE row with `repeated: true` and one `duplicate-header-row` ledger entry per continuation page.

  The DOM is mutable (java.util.HashMap / ArrayList), as the Python original's dicts were: note
  references are patched with their target's id after ids are assigned.  The HTML tokenizer is a small
  reimplementation of the parts of Python's html.parser the corpus uses (start/end/self-closing tags,
  quoted and bare attributes, doctype, comments, script/style raw text, &amp; &lt; &gt; &quot; &apos;
  &nbsp; and numeric references)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nfc]
            [pyjson :as pj :refer [om]])
  (:import (java.util ArrayList HashMap)))

(def VOID #{"br" "img" "hr" "meta" "link" "input" "col" "wbr" "area" "base"})
(def WS " \t\r\n\f")

(defn nfc-str [s] (nfc/normalize s))

;; ---------------------------------------------------------------- tokenizer + DOM

(defn el [tag attrs parent]
  (doto (HashMap.)
    (.put "tag" tag) (.put "attrs" attrs) (.put "kids" (ArrayList.)) (.put "parent" parent)))

(defn el? [x] (instance? HashMap x))
(defn etag [e] (.get ^HashMap e "tag"))
(defn ekids [e] (.get ^HashMap e "kids"))
(defn eparent [e] (.get ^HashMap e "parent"))
(defn eget
  ([e k] (get (.get ^HashMap e "attrs") k))
  ([e k d] (let [v (get (.get ^HashMap e "attrs") k)] (if (nil? v) d v))))
(defn cls [e] (vec (remove empty? (str/split (or (eget e "class") "") #"\s+"))))

(def ^:private entities {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" "\u00a0"})

(defn unescape [s]
  (if-not (str/includes? s "&")
    s
    (str/replace s #"&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);"
                 (fn [[whole name]]
                   (cond
                     (str/starts-with? name "#x") (new String (Character/toChars (Integer/parseInt (subs name 2) 16)))
                     (str/starts-with? name "#X") (new String (Character/toChars (Integer/parseInt (subs name 2) 16)))
                     (str/starts-with? name "#") (new String (Character/toChars (Integer/parseInt (subs name 1))))
                     (contains? entities name) (get entities name)
                     :else (throw (ex-info (str "html2gold: unsupported entity " whole) {})))))))

(def ^:private attr-re #"(?s)\s*([^\s/>][^\s/=>]*)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^>\s]*)))?")

(defn- parse-attrs [s]
  (let [m (re-matcher attr-re s)]
    (loop [out {}]
      (if (.find m)
        (let [name (str/lower-case (.group m 1))
              v (or (.group m 2) (.group m 3) (.group m 4) "")]
          (recur (assoc out name (unescape v))))
        out))))

(defn parse
  "HTML text -> root El; errors on a stray end tag or an unclosed element."
  [^String html]
  (let [root (el "#root" {} nil)
        cur (volatile! root)
        n (count html)
        data! (fn [s] (when (seq s) (.add ^ArrayList (ekids @cur) s)))]
    (loop [i 0]
      (when (< i n)
        (let [lt (.indexOf html "<" (int i))]
          (if (neg? lt)
            (data! (unescape (subs html i)))
            (do
              (data! (unescape (subs html i lt)))
              (let [c1 (when (< (inc lt) n) (.charAt html (inc lt)))]
                (cond
                  (nil? c1) (do (data! "<") (recur n))
                  ;; <!DOCTYPE ...> or <!-- comment -->
                  (= c1 \!)
                  (if (str/starts-with? (subs html lt) "<!--")
                    (let [e (.indexOf html "-->" (int (+ lt 4)))]
                      (recur (+ e 3)))
                    (let [e (.indexOf html ">" (int lt))]
                      (recur (inc e))))
                  ;; end tag
                  (= c1 \/)
                  (let [e (.indexOf html ">" (int lt))
                        name (str/lower-case (str/trim (subs html (+ lt 2) e)))]
                    (when-not (VOID name)
                      (loop [x @cur]
                        (cond (nil? x) (throw (ex-info (str "stray </" name ">") {}))
                              (= (etag x) name) (vreset! cur (eparent x))
                              :else (recur (eparent x)))))
                    (recur (inc e)))
                  (Character/isLetter c1)
                  (let [tm (re-matcher #"(?s)<([a-zA-Z][^\t\n\r\f />\x00]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*)>" html)
                        _ (.region tm (int lt) (int n))
                        _ (when-not (.lookingAt tm) (throw (ex-info "html2gold: malformed tag" {:at lt})))
                        tag (str/lower-case (.group tm 1))
                        rest-s (.group tm 2)
                        selfclose (str/ends-with? rest-s "/")
                        rest-s (if selfclose (subs rest-s 0 (dec (count rest-s))) rest-s)
                        e (el tag (parse-attrs rest-s) @cur)
                        after (.end tm)]
                    (.add ^ArrayList (ekids @cur) e)
                    (cond
                      selfclose (recur after)
                      (VOID tag) (recur after)
                      (contains? #{"script" "style"} tag)
                      ;; raw text up to the matching end tag
                      (let [close (let [m (re-matcher (re-pattern (str "(?i)</" tag "\\s*>")) html)]
                                    (.region m (int after) (int n))
                                    (when (.find m) [(.start m) (.end m)]))]
                        (when-not close (throw (ex-info (str "unclosed <" tag ">") {})))
                        (when (< after (first close)) (.add ^ArrayList (ekids e) (subs html after (first close))))
                        (recur (second close)))
                      :else (do (vreset! cur e) (recur after))))
                  :else (do (data! "<") (recur (inc lt))))))))))
    (when-not (identical? @cur root)
      (throw (ex-info (str "unclosed <" (etag @cur) ">") {})))
    root))

(defn walk
  "Every descendant El, pre-order."
  [e]
  (vec (mapcat (fn [k] (when (el? k) (cons k (walk k)))) (ekids e))))

(defn find-el [e tag] (first (filter #(= (etag %) tag) (walk e))))

(defn norm-ws [s]
  (-> s (str/replace #"[ \t\r\n\f]+" " ") (str/replace #"^[ \t\r\n\f]+|[ \t\r\n\f]+$" "") nfc-str))

(defn text-content [e]
  (let [out (StringBuilder.)]
    (letfn [(rec [x]
              (doseq [k (ekids x)]
                (cond (string? k) (.append out ^String k)
                      (= (etag k) "br") (.append out " ")
                      (and (not (contains? #{"script" "style"} (etag k))) (not= (eget k "data-gold") "skip")) (rec k))))]
      (rec e))
    (norm-ws (str out))))

;; ---------------------------------------------------------------- nodes (mutable, like the dicts they port)

(defn node [& kvs]
  (let [m (HashMap.)]
    (doseq [[k v] (partition 2 kvs)] (.put m k v))
    m))
(defn nget [n k] (.get ^HashMap n k))
(defn nput! [n k v] (.put ^HashMap n k v) n)
(defn nhas? [n k] (.containsKey ^HashMap n k))
(defn truthy? [s] (and (some? s) (not= s "") (not (false? s))))

;; ---------------------------------------------------------------- inlines

(def STYLE-TAGS {"b" "bold" "strong" "bold" "i" "italic" "em" "italic" "cite" "italic" "u" "underline"
                 "s" "strike" "del" "strike" "strike" "strike" "sub" "sub" "sup" "sup" "code" "mono"
                 "kbd" "mono" "samp" "mono" "tt" "mono"})
(def INLINE-TAGS (into (set (keys STYLE-TAGS)) ["a" "span" "small" "br" "mark" "abbr" "q" "bdi" "bdo" "time" "wbr" "img"]))

(defn- new-inl [notes]
  (let [top (ArrayList.)]
    {:top top :stack (atom [top]) :pending (atom false) :started (atom false) :notes notes}))

(defn- inl-put [inl s]
  (let [^ArrayList c (peek @(:stack inl))]
    (if (and (pos? (.size c)) (= (nget (.get c (dec (.size c))) "kind") "text"))
      (let [last (.get c (dec (.size c)))] (nput! last "text" (str (nget last "text") s)))
      (.add c (node "kind" "text" "text" s)))))

(defn- inl-space [inl]
  (when (and @(:pending inl) @(:started inl)) (inl-put inl " "))
  (reset! (:pending inl) false))

(defn- ws? [c] (str/includes? WS (str c)))

(defn- inl-text [inl s]
  (doseq [seg (re-seq #"[ \t\r\n\f]+|[^ \t\r\n\f]+" s)]
    (if (ws? (first seg))
      (reset! (:pending inl) true)
      (do (when (and @(:pending inl) @(:started inl)) (inl-put inl " "))
          (reset! (:pending inl) false)
          (reset! (:started inl) true)
          (inl-put inl (nfc-str seg))))))

(defn- inl-br [inl]
  (reset! (:pending inl) false)
  (.add ^ArrayList (peek @(:stack inl)) (node "kind" "linebreak"))
  (reset! (:started inl) false))

(defn- inl-ref [inl marker]
  (inl-space inl)
  (reset! (:started inl) true)
  (let [n (node "kind" "ref" "marker" marker)]
    (.add ^ArrayList (peek @(:stack inl)) n)
    (.add ^ArrayList (:refs (:notes inl)) n)))

(defn- inl-open [inl n]
  (inl-space inl)
  (.add ^ArrayList (peek @(:stack inl)) n)
  (swap! (:stack inl) conj (nget n "children")))

(defn- inl-close [inl] (swap! (:stack inl) pop))

;; ---------------------------------------------------------------- initials (D114)

(def ^:private PUNCT-TYPES #{(int Character/START_PUNCTUATION) (int Character/INITIAL_QUOTE_PUNCTUATION)
                             (int Character/FINAL_QUOTE_PUNCTUATION) (int Character/OTHER_PUNCTUATION)})
(def ^:private MARK-TYPES #{(int Character/NON_SPACING_MARK) (int Character/ENCLOSING_MARK)
                            (int Character/COMBINING_SPACING_MARK)})

(defn first-letter-extent
  "UTF-16 length of the `::first-letter` of s: preceding punctuation (Unicode Ps, Pi, Pf, Po), one grapheme
  cluster (a code point and its combining marks), and the punctuation that follows it with no space
  between."
  [^String s]
  (let [n (count s)
        cp-at (fn [i] (.codePointAt s (int i)))
        ty (fn [cp] (Character/getType (int cp)))
        skip (fn [pred i] (loop [i i] (if (and (< i n) (pred (ty (cp-at i)))) (recur (+ i (Character/charCount (cp-at i)))) i)))
        i (skip PUNCT-TYPES 0)
        _ (when (>= i n) (throw (ex-info (str "first-letter: no letter in " (pr-str s)) {})))
        j (skip MARK-TYPES (+ i (Character/charCount (cp-at i))))]
    (skip PUNCT-TYPES j)))

(defn- initial-node [form lines text]
  (when-not (contains? #{"drop" "raised" "margin"} form)
    (throw (ex-info (str "data-initial must be drop, raised or margin, not " (pr-str form)) {})))
  (when (str/blank? text) (throw (ex-info "an initial with no text (give data-initial-text or alt)" {})))
  (let [n (node "kind" "initial" "form" form
                "children" (doto (ArrayList.) (.add (node "kind" "text" "text" (nfc-str text)))))]
    (when (contains? #{"drop" "margin"} form)
      (let [l (some-> lines parse-long)]
        (when-not (and l (>= l 2)) (throw (ex-info (str form " initial needs data-lines >= 2") {})))
        (nput! n "lines" l)))
    (when (and (= form "raised") (some? lines)) (throw (ex-info "a raised initial has no data-lines" {})))
    n))

(defn- split-initial
  "A post-processor for `leaf`: the first text of a <p data-initial> becomes initial + rest."
  [e]
  (fn [nodes]
    (let [n0 (first nodes)]
      (when-not (and n0 (= (nget n0 "kind") "text"))
        (throw (ex-info "a <p data-initial> must start with plain text" {})))
      (let [t (nget n0 "text")
            k (first-letter-extent t)
            out (ArrayList.)]
        (.add out (initial-node (eget e "data-initial") (eget e "data-lines") (subs t 0 k)))
        (when (< k (count t)) (.add out (node "kind" "text" "text" (subs t k))))
        (doseq [x (rest nodes)] (.add out x))
        out))))

(defn- inl-walk [inl e]
  (doseq [k (vec (ekids e))]
    (if (string? k)
      (inl-text inl k)
      (let [t (etag k)]
        (cond
          (some? (eget k "data-initial"))
          (do (inl-space inl)
              (reset! (:started inl) true)
              (.add ^ArrayList (peek @(:stack inl))
                    (initial-node (eget k "data-initial") (eget k "data-lines")
                                  (or (eget k "data-initial-text") (eget k "alt") (text-content k)))))
          (or (= (eget k "data-gold") "skip") (contains? #{"script" "style" "svg"} t)) nil
          (= t "br") (inl-br inl)
          (and (= t "sup") (some? (eget k "data-fnref"))) (inl-ref inl (eget k "data-fnref"))
          (or (contains? STYLE-TAGS t) (and (= t "span") (some #{"sc"} (cls k))))
          (do (inl-open inl (node "kind" "span" "style" [(get STYLE-TAGS t "smallcaps")] "children" (ArrayList.)))
              (inl-walk inl k)
              (inl-close inl))
          (and (= t "a") (some? (eget k "href")))
          (do (inl-open inl (node "kind" "link" "href" (eget k "href") "children" (ArrayList.)))
              (inl-walk inl k)
              (inl-close inl))
          (contains? INLINE-TAGS t) (when (not= t "img") (inl-walk inl k))
          :else (throw (ex-info (str "block element <" t "> inside inline content") {})))))))

(defn- prune [nodes]
  (let [out (ArrayList.)]
    (doseq [n nodes]
      (let [skip? (when (nhas? n "children")
                    (nput! n "children" (prune (nget n "children")))
                    (zero? (.size ^ArrayList (nget n "children"))))]
        (when-not (or skip? (and (= (nget n "kind") "text") (= (nget n "text") "")))
          (.add out n))))
    out))

(defn- flat [nodes]
  (apply str (for [n nodes]
               (case (nget n "kind")
                 "text" (nget n "text")
                 "linebreak" "\n"
                 "ref" ""
                 (flat (nget n "children"))))))

(defn leaf
  "A leaf block: `text`, and `inlines` only when they carry information.  `post` (optional) rewrites the
  pruned inline list before it is flattened (an initial splits the first text)."
  ([kind e notes fields] (leaf kind e notes fields nil))
  ([kind e notes fields post]
  (let [inl (new-inl notes)
        _ (inl-walk inl e)
        nodes (prune (:top inl))
        nodes (if post (post nodes) nodes)
        n (node "kind" kind)]
    (doseq [[k v] fields] (nput! n k v))
    (nput! n "text" (flat nodes))
    (when (some #(not= (nget % "kind") "text") nodes)
      (nput! n "inlines" nodes))
    n)))

;; ---------------------------------------------------------------- blocks

(def BLOCK-TRANSPARENT #{"div" "section" "article" "aside" "main" "header" "footer" "nav" "figure_" "span" "body" "html" "center"})
(def LABEL #"(?sU)^((?:Table|Figure|Fig\.|Chart|Exhibit|Diagram|Schedule|Plate|Image|Photo)\s+[0-9A-Za-z.]+)\s*[.:–—-]?\s*(.*)$")
(def SCHEMES {"1" "decimal" "a" "lower-alpha" "A" "upper-alpha" "i" "lower-roman" "I" "upper-roman"})

(defn roman [n]
  (loop [n n out "" [[v s] & more :as tbl] [[1000 "M"] [900 "CM"] [500 "D"] [400 "CD"] [100 "C"] [90 "XC"]
                                             [50 "L"] [40 "XL"] [10 "X"] [9 "IX"] [5 "V"] [4 "IV"] [1 "I"]]]
    (cond (nil? v) out
          (>= n v) (recur (- n v) (str out s) tbl)
          :else (recur n out more))))

(defn marker-for [scheme n depth]
  (case scheme
    "bullet" (nth ["•" "◦" "▪"] (min depth 2))
    "decimal" (str n ".")
    "lower-alpha" (str (char (+ (int \a) (mod (dec n) 26))) ".")
    "upper-alpha" (str (char (+ (int \A) (mod (dec n) 26))) ".")
    "lower-roman" (str (str/lower-case (roman n)) ".")
    "upper-roman" (str (roman n) ".")
    (throw (ex-info (str "scheme " scheme) {}))))

(defn heading-number [span-text]
  (-> (str/trim span-text)
      (str/replace #"^[(\[]|[)\].:]$" "")
      str/trim
      (str/replace #"^§\s*" "")))

(defn numbering-scheme [n]
  (cond (re-matches #"\d+(\.\d+)*" n) "decimal"
        (re-find #"^[IVXLCDM]+$" n) "upper-roman"
        (re-matches #"[A-Z](\.\d+)*" n) "upper-alpha"
        (re-matches #"[a-z]" n) "lower-alpha"
        :else "decimal"))

(defn- to-int [s] (if (integer? s) s (parse-long (str/trim s))))

(declare conv-blocks)

(defn- align-fields
  "[[\"align\" v]] for an element's `data-align` (none: an annotated absence, no field), and the tally
  that makes a document annotate a kind only when every node of the kind says (D114 convention 10)."
  [cv kind e]
  (let [a (eget e "data-align")]
    (swap! (:align-stat cv) update-in [kind (if (some? a) :authored :missing)] (fnil inc 0))
    (cond (nil? a) []
          (= a "none") []
          (and (= kind "cell") (= a "justify")) (throw (ex-info "a cell has no justify" {}))
          (contains? #{"start" "center" "end" "justify"} a) [["align" a]]
          :else (throw (ex-info (str "data-align " (pr-str a)) {})))))

(defn- next-id! [cv] (swap! (:ctr cv) inc))

(defn- tag-ctx!
  "Remember, for every block under n not yet remembered, the column container and segment in force."
  [cv n]
  (let [^java.util.IdentityHashMap m (:ctx-map cv)]
    (when-not (.containsKey m n) (.put m n @(:ctx cv)))
    (doseq [c (or (nget n "children") [])]
      (when-not (contains? #{"text" "span" "link" "ref" "linebreak" "initial"} (nget c "kind")) (tag-ctx! cv c)))))

(defn- conv-flush [cv run out]
  (when (seq @run)
    (let [holder (el "p" {} nil)]
      (.addAll ^ArrayList (ekids holder) @run)
      (let [n (leaf "paragraph" holder (:notes cv) {})]
        (when (or (not= (nget n "text") "") (nhas? n "inlines"))
          (doseq [[k v] (align-fields cv "paragraph" holder)] (nput! n k v))
          (tag-ctx! cv n)
          (.add ^ArrayList out n)))
      (vreset! run []))))

(defn- caption [cv e]
  (let [raw (leaf "caption" e (:notes cv) {})
        m (re-matches LABEL (nget raw "text"))
        n (if m
            (node "kind" "caption" "label" (nth m 1) "text" (nth m 2))
            (node "kind" "caption" "text" (nget raw "text")))]
    (when (and (nhas? raw "inlines") (not m)) (nput! n "inlines" (nget raw "inlines")))
    (doseq [[k v] (align-fields cv "caption" e)] (nput! n k v))
    (.put ^java.util.IdentityHashMap (:raw-map cv) n (nget raw "text"))
    n))

(defn- conv-heading [cv e]
  (let [lvl (to-int (let [v (eget e "data-level")] (if (truthy? v) v (subs (etag e) 1 2))))
        holder (el "h" {} nil)
        num (volatile! nil)]
    (doseq [k (ekids e)]
      (if (and (el? k) (= (etag k) "span") (some #{"num"} (cls k)))
        (vreset! num (heading-number (text-content k)))
        (.add ^ArrayList (ekids holder) k)))
    ;; a heading DRAWN as paths (D209): `data-outline-text` is the text the picture shows; the gold has it
    (when (truthy? (eget e "data-outline-text"))
      (.clear ^ArrayList (ekids holder))
      (.add ^ArrayList (ekids holder) (eget e "data-outline-text")))
    (let [extra (cond-> [["level" lvl]]
                  (truthy? @num) (into [["number" @num]
                                        ["numbering" (let [v (eget e "data-numbering")] (if (truthy? v) v (numbering-scheme @num)))]])
                  true (into (align-fields cv "heading" e)))]
      (let [n (leaf "heading" holder (:notes cv) extra)]
        (.put ^java.util.IdentityHashMap (:raw-map cv) n
              (if (truthy? (eget e "data-outline-text")) (norm-ws (eget e "data-outline-text")) (text-content e)))
        n))))

(defn- conv-list [cv e depth]
  (let [ordered (= (etag e) "ol")
        scheme (let [v (eget e "data-scheme")]
                 (if (truthy? v) v (if ordered (get SCHEMES (eget e "type" "1")) "bullet")))
        start (if ordered (to-int (eget e "start" 1)) 1)
        n (node "kind" "list" "ordered" ordered "scheme" scheme)
        items (ArrayList.)
        cur (volatile! start)]
    (when (not= start 1) (nput! n "start" start))
    (doseq [li (ekids e)
            :when (el? li)]
      (when (not= (etag li) "li") (throw (ex-info (str "<" (etag li) "> directly in a list") {})))
      (let [value (eget li "value")
            _ (when (some? value) (vreset! cur (to-int value)))
            own (el "li" {} nil)
            nested (ArrayList.)]
        (doseq [k (ekids li)]
          (if (and (el? k) (contains? #{"ul" "ol"} (etag k)))
            (.add nested k)
            (.add ^ArrayList (ekids own) k)))
        (let [item (leaf "item" own (:notes cv) [["marker" (marker-for scheme @cur depth)]])]
          (when (some? value) (nput! item "value" @cur))
          (when (pos? (.size nested))
            (nput! item "children" (ArrayList. ^java.util.Collection (mapv #(conv-list cv % (inc depth)) nested))))
          (.add items item)
          (vswap! cur inc))))
    (nput! n "children" items)))

(defn- conv-figure [cv e]
  (let [kind (or (eget e "data-figure") (eget e "data-kind"))
        _ (when (nil? kind) (throw (ex-info "figure without data-kind" {})))
        n (node "kind" "figure" "subkind" kind)
        cap (volatile! nil)
        labels (ArrayList.)]
    (doseq [x (walk e)]
      (cond
        (= (etag x) "figcaption") (vreset! cap (caption cv x))
        (and (= (etag x) "text") (not= (eget x "data-gold") "skip"))
        (let [s (text-content x)] (when (seq s) (.add labels s)))
        (some? (eget x "data-label"))
        (let [s (text-content x)] (when (seq s) (.add labels s)))))
    (when (and @cap (nhas? @cap "label")) (nput! n "label" (nget @cap "label")))
    (let [alt (let [a (eget e "aria-label")] (if (truthy? a) a (eget e "data-alt")))]
      (when (truthy? alt) (nput! n "alt" alt)))
    (when (and (pos? (.size labels)) (not= (eget e "data-labels") "none")) (nput! n "labels" labels))
    (when @cap (nput! n "children" (doto (ArrayList.) (.add @cap))))
    n))

(defn- conv-seal [cv e]
  (let [n (node "kind" "seal" "subkind" (eget e "data-seal"))]
    (when (truthy? (eget e "data-name")) (nput! n "name" (eget e "data-name")))
    (when (truthy? (eget e "data-date")) (nput! n "date" (eget e "data-date")))
    (when (truthy? (eget e "data-color")) (nput! n "color" (eget e "data-color")))
    (nput! n "text" (if (truthy? (eget e "data-text")) (norm-ws (eget e "data-text")) (text-content e)))))

(defn- conv-note [cv e]
  (let [role (eget e "data-note")
        marker (eget e "data-marker")
        n (node "kind" "note" "role" role)
        kids (conv-blocks cv e)]
    (when (truthy? marker) (nput! n "marker" marker))
    (nput! n "children" (if (pos? (.size ^ArrayList kids)) kids (doto (ArrayList.) (.add (node "kind" "paragraph" "text" "")))))
    (when (contains? #{"footnote" "endnote"} role) (.add ^ArrayList (:notes-list cv) n))
    n))

(defn- conv-table [cv e]
  (let [tid (eget e "id")
        n (node "kind" "table")
        kids (ArrayList.)
        groups (ArrayList.)       ; [role, ArrayList of tr]
        head-rows (ArrayList.)
        ncols (volatile! 0)]
    (when (truthy? (eget e "data-profile")) (nput! n "profile" (eget e "data-profile")))
    (doseq [k (ekids e) :when (el? k)]
      (let [t (etag k)]
        (cond
          (= t "caption") (.add kids (caption cv k))
          (contains? #{"thead" "tbody" "tfoot"} t)
          (.add groups [(get {"thead" "head" "tbody" "body" "tfoot" "foot"} t)
                        (ArrayList. ^java.util.Collection (filterv #(and (el? %) (= (etag %) "tr")) (ekids k)))])
          (= t "tr") (if (and (pos? (.size groups)) (= (first (.get groups (dec (.size groups)))) "body"))
                       (.add ^ArrayList (second (.get groups (dec (.size groups)))) k)
                       (.add groups ["body" (doto (ArrayList.) (.add k))]))
          (contains? #{"colgroup" "col"} t) nil
          :else (throw (ex-info (str "<" t "> in table") {})))))
    (doseq [[role trs] groups]
      (let [rg (node "kind" "rowgroup" "role" role "children" (ArrayList.))]
        (doseq [tr trs]
          (let [row (node "kind" "row" "children" (ArrayList.))
                width (volatile! 0)]
            (doseq [c (ekids tr) :when (el? c)]
              (when-not (contains? #{"td" "th"} (etag c)) (throw (ex-info (str "<" (etag c) "> in row") {})))
              (let [cell (leaf "cell" c (:notes cv) (align-fields cv "cell" c))
                    rs (to-int (eget c "rowspan" 1))
                    cs (to-int (eget c "colspan" 1))]
                (when (> rs 1) (nput! cell "rowspan" rs))
                (when (> cs 1) (nput! cell "colspan" cs))
                (when (= (etag c) "th")
                  (let [sc (eget c "scope")]
                    (nput! cell "header" (cond (= sc "row") "row" (= sc "col") "col" :else (if (= role "head") "col" "row")))))
                (when (and (= (nget cell "text") "") (not (nhas? cell "inlines"))) (nput! cell "empty" true))
                (vswap! width + cs)
                (.add ^ArrayList (nget row "children") cell)))
            (vswap! ncols max @width)
            (.add ^ArrayList (nget rg "children") row)
            (when (= role "head") (.add head-rows [row tr]))))
        (.add kids rg)))
    (nput! n "columns" @ncols)
    (let [pages (when (truthy? tid) (get (get (:facts cv) "rows") tid))]
      (when (seq pages)
        (let [lo (apply min pages) hi (apply max pages)]
          (when (> hi lo) (nput! n "continued" (inc (- hi lo))))
          (when (and (> hi lo) (= (eget e "data-repeat-head") "true") (pos? (.size head-rows)))
            (doseq [[row _] head-rows] (nput! row "repeated" true))
            (let [txt (str/join " " (for [c (nget (first (first head-rows)) "children") :when (not= (nget c "text") "")] (nget c "text")))]
              (doseq [p (range (inc lo) (inc hi))]
                (swap! (:dropped-extra cv) conj (om "reason" "duplicate-header-row" "page" p "text" txt))))))))
    (nput! n "children" kids)))

(defn- conv-block [cv e]
  (let [t (etag e)
        L (fn [& xs] (doto (ArrayList.) (.addAll ^java.util.Collection (vec xs))))]
    (cond
      (or (some? (eget e "data-heading")) (some? (eget e "data-level"))
          (and (= (count t) 2) (= (first t) \h) (str/includes? "123456" (str (second t)))))
      (L (conv-heading cv e))
      (some? (eget e "data-seal")) (L (conv-seal cv e))
      (some? (eget e "data-note")) (L (conv-note cv e))
      (or (some? (eget e "data-figure")) (= t "figure")) (L (conv-figure cv e))
      (= t "p") (L (leaf "paragraph" e (:notes cv) (align-fields cv "paragraph" e)
                         (when (some? (eget e "data-initial")) (split-initial e))))
      (contains? #{"ul" "ol"} t) (L (conv-list cv e 0))
      (and (= t "table") (some? (eget e "data-layout")))
      (let [out (ArrayList.)]
        (doseq [x (walk e)]
          (when (and (= (etag x) "td") (= (etag (eparent (eparent x))) "tbody"))
            (.addAll out ^java.util.Collection (conv-blocks cv x))))
        out)
      (= t "table") (L (conv-table cv e))
      (= t "blockquote") (L (node "kind" "quote" "children" (conv-blocks cv e)))
      (= t "pre") (let [s (apply str (for [k (ekids e)] (if (string? k) k (text-content k))))]
                    (L (node "kind" "code" "text" (str/replace s #"^\n+|\n+$" ""))))
      (contains? #{"hr" "svg" "img"} t) (L)
      (BLOCK-TRANSPARENT t) (conv-blocks cv e)
      :else (throw (ex-info (str "unsupported block element <" t ">") {})))))

(defn conv-blocks [cv e]
  (let [out (ArrayList.)
        run (volatile! [])]
    (doseq [k (vec (ekids e))]
      (if (string? k)
        (vswap! run conj k)
        (let [t (etag k)]
          (cond
            (or (contains? #{"script" "style" "head" "title" "meta" "link"} t) (= (eget k "data-gold") "skip")) nil
            (and (INLINE-TAGS t) (nil? (eget k "data-seal")) (nil? (eget k "data-heading")) (nil? (eget k "data-note")))
            (vswap! run conj k)
            :else (do (conv-flush cv run out)
                      (let [cols (eget k "data-cols")
                            span (eget k "data-span")
                            saved @(:ctx cv)]
                        (cond cols (vreset! (:ctx cv) {:cont (next-id! cv) :n (parse-long cols) :seg (next-id! cv)})
                              span (vreset! (:ctx cv) {:cont nil :seg (next-id! cv)}))
                        (let [nodes (conv-block cv k)]
                          (doseq [nd nodes] (tag-ctx! cv nd))
                          (when (or cols span) (vreset! (:ctx cv) (assoc saved :seg (next-id! cv))))
                          (.addAll out ^java.util.Collection nodes))))))))
    (conv-flush cv run out)
    out))

;; ---------------------------------------------------------------- the document

(defn expand-pages [spec N]
  (cond
    (or (nil? spec) (= spec "all")) (vec (range 1 (inc N)))
    (= spec "odd") (vec (filter odd? (range 1 (inc N))))
    (= spec "odd-rest") (vec (filter odd? (range 3 (inc N))))
    (= spec "even") (vec (filter even? (range 1 (inc N))))
    (= spec "first") [1]
    (= spec "rest") (vec (range 2 (inc N)))
    (= spec "last") [N]
    (sequential? spec) (vec (filter #(<= 1 % N) spec))
    :else (throw (ex-info (str "pages spec " (pr-str spec)) {}))))

(defn subst [t n N]
  (-> t
      (str/replace "{n:04}" (format "%04d" n))
      (str/replace "{n}" (str n))
      (str/replace "{N}" (str N))
      (str/replace "{roman}" (str/lower-case (roman n)))
      (str/replace "{ROMAN}" (roman n))))

(def ORDER ["id" "kind" "level" "number" "numbering" "ordered" "scheme" "start"
            "marker" "value" "profile" "continued" "columns" "role" "repeated"
            "rowspan" "colspan" "header" "empty" "label" "target" "subkind" "alt"
            "labels" "name" "date" "color" "display" "style" "href" "page" "pages"
            "template" "reason" "align" "form" "lines" "text" "inlines" "children"])
(def ^:private ORDER-IDX (zipmap ORDER (range)))

(defn ordered [x]
  (cond
    (instance? java.util.Map x)
    (apply array-map
           (mapcat (fn [k] [k (ordered (get x k))])
                   (sort-by (fn [k] [(get ORDER-IDX k (count ORDER)) k]) (keys x))))
    (instance? java.util.List x) (mapv ordered x)
    :else x))

(def INLINE-KINDS #{"text" "span" "link" "ref" "linebreak" "initial"})

(defn- assign-ids [root start]
  (let [nid (volatile! start)]
    (letfn [(rec [n]
              (nput! n "id" @nid)
              (vswap! nid inc)
              (doseq [c (or (nget n "children") [])]
                (when-not (INLINE-KINDS (nget c "kind")) (rec c))))]
      (rec root))
    @nid))

(defn- sections [flat-blocks]
  (let [doc (node "kind" "document" "children" (ArrayList.))
        stack (volatile! [[0 doc]])]
    (doseq [b flat-blocks]
      (if (= (nget b "kind") "heading")
        (let [lvl (nget b "level")]
          (while (>= (first (peek @stack)) lvl) (vswap! stack pop))
          (let [sec (node "kind" "section" "level" lvl "children" (doto (ArrayList.) (.add b)))]
            (.add ^ArrayList (nget (second (peek @stack)) "children") sec)
            (vswap! stack conj [lvl sec])))
        (.add ^ArrayList (nget (second (peek @stack)) "children") b)))
    doc))

(def ALL-KINDS ["section" "heading" "paragraph" "list" "item" "table" "rowgroup" "row"
                "cell" "caption" "figure" "seal" "quote" "code" "note"])
(def ATTRS (om "section" ["level"] "heading" ["level" "number" "numbering"]
               "list" ["ordered" "scheme" "start"] "item" ["marker" "value"]
               "table" ["profile" "columns" "continued"] "rowgroup" ["role"]
               "row" ["repeated"] "cell" ["rowspan" "colspan" "header" "empty"]
               "caption" ["label"] "figure" ["subkind" "label" "alt" "labels"]
               "seal" ["subkind" "name" "date" "color"] "note" ["role" "marker"]))
(def REASONS ["page-number" "running-title" "watermark" "duplicate-header-row"])

(declare all-blocks attrs-for block-records)

(defn convert
  "html2gold.convert(html, facts, doc_id, extra_provenance): facts is {\"pages\" [[w h]...] \"rows\" {...}}
  with string keys (pyjson/parse); extra-provenance (an ordered map) or nil.  `opts` (optional):
  `:ctx-out`, an atom that receives [leaf-block records] for build.clj (see `block-records`)."
  ([html facts doc-id extra-provenance] (convert html facts doc-id extra-provenance nil))
  ([html facts doc-id extra-provenance opts]
  (let [root (parse html)
        htmlel (find-el root "html")
        lang (let [l (when htmlel (eget htmlel "lang"))] (when (truthy? l) l))
        title-el (find-el root "title")
        title (when title-el (text-content title-el))
        hints (or (some (fn [s] (when (and (= (etag s) "script") (= (eget s "type") "application/emon-gold+json"))
                                  (pj/parse (apply str (filter string? (ekids s))))))
                        (reverse (walk root)))
                  {})
        body (or (find-el root "body") root)
        notes {:refs (ArrayList.)}
        cv {:facts facts :notes notes :notes-list (ArrayList.) :dropped-extra (atom [])
            :align-stat (atom {}) :ctr (atom 0) :ctx (volatile! {:cont nil :seg 0})
            :ctx-map (java.util.IdentityHashMap.) :raw-map (java.util.IdentityHashMap.)}
        blocks (conv-blocks cv body)
        doc (sections blocks)
        nxt (volatile! (assign-ids doc 0))
        align-kinds (vec (for [k ["paragraph" "heading" "caption" "cell"]
                               :when (pos? (get-in @(:align-stat cv) [k :authored] 0))]
                           (do (when (pos? (get-in @(:align-stat cv) [k :missing] 0))
                                 (throw (ex-info (str doc-id ": " (get-in @(:align-stat cv) [k :missing]) " " k
                                                      " node(s) without data-align in a document that annotates align on " k
                                                      "s (use data-align=none for an annotated absence)") {})))
                               k)))
        lines (get facts "lines")]
    ;; a justified block that printed as one line is start (D114 convention 10)
    (letfn [(fix [n]
              (when (and (= (nget n "align") "justify") (= 1 (get lines (str (nget n "id")))))
                (nput! n "align" "start"))
              (doseq [c (or (nget n "children") [])] (when-not (INLINE-KINDS (nget c "kind")) (fix c))))]
      (when (seq lines) (fix doc)))
    (when-let [out (:ctx-out opts)] (reset! out (block-records doc (:ctx-map cv) (:raw-map cv))))
    ;; note references: each targets the next unmatched note with its marker
    (let [pending (volatile! {})]
      (doseq [nt (:notes-list cv)]
        (vswap! pending update (nget nt "marker") (fnil conj []) nt))
      (doseq [r (:refs notes)]
        (let [q (get @pending (nget r "marker"))]
          (when (empty? q) (throw (ex-info (str "note reference " (pr-str (nget r "marker")) " has no note") {})))
          (nput! r "target" (nget (first q) "id"))
          (vswap! pending assoc (nget r "marker") (vec (rest q)))))
      (let [left (keep (fn [[m q]] (when (seq q) m)) @pending)]
        (when (seq left) (throw (ex-info (str "notes never referenced: " (pr-str left)) {})))))
    (let [pages (get facts "pages")
          N (count pages)
          labels (get hints "labels")
          off (get hints "label-offset" 0)
          lp (set (expand-pages (get hints "label-pages") N))
          mpages (vec (for [[i [w h]] (map-indexed (fn [i p] [(inc i) p]) pages)]
                        (let [p (node "n" i "w" w "h" h "rot" 0)]
                          (cond (and (lp i) (= labels "arabic")) (nput! p "label" (str (+ i off)))
                                (and (lp i) (= labels "roman")) (nput! p "label" (str/lower-case (roman (+ i off)))))
                          p)))
          furniture (vec (for [f (get hints "furniture" [])]
                           (do (when (empty? (expand-pages (get f "pages") N))
                                 (throw (ex-info (str "furniture covers no page: " (get f "template")) {})))
                               (let [item (node "id" @nxt "role" (get f "role") "template" (get f "template")
                                                "pages" (expand-pages (get f "pages") N))]
                                 (vswap! nxt inc)
                                 item))))
          dropped (vec (concat
                        (for [d (get hints "dropped" []) p (expand-pages (get d "pages") N)]
                          (om "reason" (get d "reason") "page" p "text" (subst (get d "text") (+ p off) N)))
                        @(:dropped-extra cv)))
          dropped (vec (sort-by (fn [d] [(get d "page") (let [i (.indexOf ^java.util.List REASONS (get d "reason"))] (if (neg? i) 9 i))]) dropped))
          source (cond-> (om "format" "pdf" "pages" N) lang (assoc "lang" lang))
          init? (or (boolean (get hints "initial"))
                    (boolean (some (fn [n] (some #(= (nget % "kind") "initial") (or (nget n "inlines") []))) (all-blocks doc))))
          layout? (boolean (get hints "layout"))
          sidebar? (boolean (some #(= (nget % "role") "sidebar") (filter #(= (nget % "kind") "note") (all-blocks doc))))
          new? (or init? layout? sidebar? (seq align-kinds))
          model (cond-> (om "emon" (if new? "1.1" "1.0") "source" source "settings" (om "locale" lang))
                  (truthy? title) (assoc "meta" (om "title" title))
                  true (assoc "pages" mpages "body" doc "furniture" furniture "dropped" dropped))]
      (pj/oassoc
       (cond-> (om "emon-gold" "1.0" "id" doc-id)
         extra-provenance (assoc "provenance" extra-provenance)
         true (assoc "annotated" (cond-> (om "structure" ALL-KINDS "attributes" (attrs-for align-kinds init?)
                                             "text" true "inlines" true
                                             "furniture" true "dropped" REASONS "pages" ["n" "w" "h" "label"])
                                   layout? (assoc "layout" true)))
         (seq (select-keys facts ["frags" "lines" "hyphens" "tl-untrusted"]))
         (assoc "facts" (apply om (mapcat (fn [k] (when (contains? facts k) [k (get facts k)])) ["frags" "lines" "hyphens" "tl-untrusted"]))))
       "model" (ordered model))))))

(defn- all-blocks
  "Every block node under n (n included), pre-order."
  [n]
  (cons n (mapcat all-blocks (remove #(INLINE-KINDS (nget % "kind")) (or (nget n "children") [])))))

(defn- attrs-for
  "ATTRS plus the attributes a new document annotates (D114): align on the kinds that say it, `initial`."
  [align-kinds init?]
  (let [base (for [[k v] ATTRS] [k (if (some #{k} align-kinds) (conj (vec v) "align") v)])
        base (if (some #{"paragraph"} align-kinds) (concat base [["paragraph" ["align"]]]) base)
        base (if init? (concat base [["initial" ["form" "lines"]]]) base)]
    (apply om (mapcat identity base))))

(defn- stream-text
  "What a leaf block prints: its text with note references as their markers."
  [n]
  (letfn [(flat* [nodes] (apply str (for [x nodes]
                                      (case (nget x "kind")
                                        "text" (nget x "text")
                                        "linebreak" " "
                                        "ref" (str (nget x "marker"))
                                        (flat* (nget x "children"))))))]
    (if (nhas? n "inlines") (flat* (nget n "inlines")) (nget n "text"))))

(defn block-records
  "For build.clj: one map per leaf block with text, in document order: its id, kind, gold text, printed
  stream text (references as their markers, a note's marker first), its column container, segment and
  column count (nil container: one flow), and its initial if any."
  [doc ctx-map raw-map]
  (let [out (volatile! [])
        marker (volatile! nil)]
    (letfn [(rec [n]
              (let [kind (nget n "kind")]
                (when (and (= kind "note") (nhas? n "marker")) (vreset! marker (nget n "marker")))
                (when (and (string? (nget n "text")) (not= (nget n "text") "") (not= kind "document"))
                  (let [ctx (.get ^java.util.IdentityHashMap ctx-map n)
                        ini (some #(when (= (nget % "kind") "initial") %) (or (nget n "inlines") []))]
                    (vswap! out conj
                            (cond-> {:id (nget n "id") :kind kind :text (nget n "text")
                                     :stream (str @marker (or (.get ^java.util.IdentityHashMap raw-map n) (stream-text n)))
                                     :cont (:cont ctx) :n (:n ctx) :seg (:seg ctx 0)
                                     :align (nget n "align")}
                              ini (assoc :initial {:form (nget ini "form") :lines (nget ini "lines")
                                                   :text (flat (nget ini "children"))})))
                    (vreset! marker nil)))
                (doseq [c (or (nget n "children") [])]
                  (when-not (INLINE-KINDS (nget c "kind")) (rec c)))))]
      (rec doc))
    @out))

(defn gold-text [gold] (str (pj/dumps gold {:indent 1}) "\n"))

(defn- check-all
  "Re-derive every committed gold from the committed HTML and MANIFEST facts; print the stems that differ."
  []
  (let [corpus "test/corpus/synth"
        manifest (pj/parse-file (str corpus "/MANIFEST.json"))
        stems (sort (for [f (.list (io/file corpus "html")) :when (str/ends-with? f ".html")] (subs f 0 (- (count f) 5))))
        bad (volatile! 0)]
    (doseq [stem stems]
      (let [gold-path (str corpus "/gold/" stem ".gold.json")
            committed (slurp gold-path :encoding "UTF-8")
            prov (get (pj/oparse committed) "provenance")
            want (gold-text (convert (slurp (str corpus "/html/" stem ".html") :encoding "UTF-8")
                                     (get-in manifest ["docs" stem "facts"]) stem prov))]
        (when (not= want committed)
          (vswap! bad inc)
          (println stem))))
    (System/exit (if (zero? @bad) 0 1))))

(defn -main [& args]
  (cond
    (= args ["check"]) (check-all)
    (= (count args) 2)
    (let [[html-path facts-path] args
          stem (-> html-path (str/split #"/") last (str/replace #"\.[^.]*$" ""))]
      (print (gold-text (convert (slurp html-path :encoding "UTF-8") (pj/parse-file facts-path) stem nil)))
      (flush))
    :else (do (println "usage: html2gold DOC.html FACTS.json | html2gold check") (System/exit 2))))
