(ns accuracy.score
  "The emon accuracy scorer (DEVLOG P2.9, D023, SPEC 14.2, D077).   bb tools/accuracy/score.clj PRED.json GOLD.json

    (score pred-model gold-file) -> {\"counts\" {...} \"stubs\" [...]}

COUNTS, NOT RATIOS: each document returns the numerators and denominators of
every metric, so a corpus can be pooled (micro-averaged) by adding them.
`metrics` turns pooled counts into the ratios the scoreboard tracks.

WHAT IS SCORED IS WHAT THE GOLD CLAIMS (D072): `annotated` says which node kinds,
which attributes per kind, whether text is annotated, which ledger reasons are
exhaustive, and which page fields. A metric the gold does not support is absent
from the counts, never zero.

NORMALIZATION (locale-independent, D077): NFC; every run of Unicode whitespace
(Python's str.split() set, no locale) is ONE space; ends trimmed. Case,
punctuation and digits are compared as they are. Text units are joined by
newline before this, so a line break and a space are the same thing to CER/WER,
and a paragraph boundary is scored by its own metric. WER tokens are whitespace
runs (for scripts without spaces WER is a line-level measure; CER is the one
to read).

MATCH RULE: predicted blocks are matched to gold blocks kind-blind, and kind is
scored AFTER matching (a mis-typed block is an FN of its gold kind and an FP of
its predicted kind).
  * if both sides have `at`: per-page bounding boxes overlap at IoU >= 0.5
    (areas summed over pages), greedy by IoU; DocLayNet style;
  * else (the synthetic gold has no geometry): a monotone alignment in reading
    order on normalized text, pairs admitted at similarity >= 0.5, where
    similarity = 1 - levenshtein/max(len), or 1 for two empty texts of one kind.

This is a port of the Python scorer and is written to give identical numbers:
strings are code-point sequences, the sequence matcher is difflib's algorithm
(autojunk off), and float sums are CPython's compensated `sum`."
  (:require [accuracy.graphics :as G]
            [clojure.string :as str]
            [nfc]
            [pyjson])
  (:import (java.math BigInteger)))

;; kinds that are "blocks" for P/R; everything else is structure inside them
(def BLOCK-KINDS ["heading" "paragraph" "list" "item" "table" "figure"
                  "caption" "seal" "note" "code" "quote"])
(def TEXT-KINDS #{"heading" "paragraph" "item" "caption" "cell" "code" "quote" "seal"})
(def CONTAINERS #{"figure" "table" "list" "note"})
(def FIGURE-KINDS ["chart" "diagram" "photo" "logo" "map" "illustration" "screenshot" "qr" "barcode" "unknown"])
(def IOU 0.5)
(def SIM 0.5)
(def LOWER-IS-BETTER #{"cer" "wer" "watermark-leak" "initial-false-heading" "align-wrong"
                       ;; the fusion metrics (D206, SPEC 14.2)
                       "ghost-rate" "seal-leak" "src-ocr-share" "tl-override"})

;; ------------------------------------------------------------ python-isms ----

(defn div
  "Python's true division of two numbers (ints too): always a double."
  [a b] (/ (double a) (double b)))

(defn psum
  "Python 3.12+ sum() of numbers: ints exactly, floats with Neumaier compensation."
  [xs]
  (let [xs (vec xs)]
    (if (every? integer? xs)
      (reduce + 0 xs)
      (loop [xs xs, f 0.0, c 0.0]
        (if-let [s (seq xs)]
          (let [x (double (first s))
                t (+ f x)
                c (if (>= (Math/abs f) (Math/abs x)) (+ c (+ (- f t) x)) (+ c (+ (- x t) f)))]
            (recur (rest s) t c))
          (if (and (not (zero? c)) (not (Double/isNaN c)) (not (Double/isInfinite c))) (+ f c) f))))))

(defn pyeq
  "Python ==, where True == 1 and 1 == 1.0."
  [a b]
  (let [f (fn [x] (cond (true? x) 1 (false? x) 0 :else x))
        a (f a) b (f b)]
    (if (and (number? a) (number? b)) (== a b) (= a b))))

(defn py-space? [c]
  (let [i (int c)]
    (or (Character/isWhitespace c) (= i 0x85) (= i 0xa0) (= i 0x2007) (= i 0x202f))))

(defn split-ws
  "str.split() with no argument."
  [^String s]
  (loop [i 0, start -1, out []]
    (if (= i (count s))
      (if (>= start 0) (conj out (subs s start i)) out)
      (if (py-space? (.charAt s i))
        (recur (inc i) -1 (if (>= start 0) (conj out (subs s start i)) out))
        (recur (inc i) (if (neg? start) i start) out)))))

(defn nlen [^String s] (.codePointCount s 0 (count s)))

;; ------------------------------------------------------------------ text ----

(defn norm [s]
  (str/join " " (split-ws (nfc/normalize (or s "")))))

(defn- cps [x] (if (string? x) (vec (.toArray (.codePoints ^String x))) (vec x)))

(defn- lev-long
  "Hyyro on primitive longs: the longer sequence `a` has at most 62 elements."
  [a b]
  (let [m (count a)
        peq (reduce-kv (fn [acc i c] (assoc acc c (bit-or (get acc c 0) (bit-shift-left 1 i)))) {} a)
        mask (dec (bit-shift-left 1 m))
        top (bit-shift-left 1 (dec m))]
    (loop [bs (seq b), pv mask, mv 0, score m]
      (if-not bs
        score
        (let [eq (long (get peq (first bs) 0))
              xv (bit-or eq mv)
              xh (bit-or (bit-xor (bit-and (+ (bit-and eq pv) pv) mask) pv) eq)
              ph (bit-or mv (bit-and (bit-not (bit-or xh pv)) mask))
              mh (bit-and pv xh)
              score (cond (not (zero? (bit-and ph top))) (inc score)
                          (not (zero? (bit-and mh top))) (dec score)
                          :else score)
              ph (bit-and (bit-or (bit-shift-left ph 1) 1) mask)
              mh (bit-and (bit-shift-left mh 1) mask)]
          (recur (next bs)
                 (bit-or mh (bit-and (bit-not (bit-or xv ph)) mask))
                 (bit-and ph xv)
                 score))))))

(defn- lev-big [a b]
  (let [m (count a)
        ^BigInteger zero BigInteger/ZERO
        peq (persistent!
             (reduce-kv (fn [acc i c]
                          (assoc! acc c (.or ^BigInteger (get acc c zero) (.shiftLeft BigInteger/ONE (int i)))))
                        (transient {}) a))
        ^BigInteger mask (.subtract (.shiftLeft BigInteger/ONE (int m)) BigInteger/ONE)
        top (dec m)
        inv (fn ^BigInteger [^BigInteger x] (.and (.not x) mask))]
    (loop [bs (seq b), ^BigInteger pv mask, ^BigInteger mv zero, score m]
      (if-not bs
        score
        (let [^BigInteger eq (get peq (first bs) zero)
              ^BigInteger xv (.or eq mv)
              ^BigInteger xh (.or (.xor (.and (.add (.and eq pv) pv) mask) pv) eq)
              ^BigInteger ph (.or mv ^BigInteger (inv (.or xh pv)))
              ^BigInteger mh (.and pv xh)
              score (cond (.testBit ph (int top)) (inc score)
                          (.testBit mh (int top)) (dec score)
                          :else score)
              ^BigInteger ph (.and (.or (.shiftLeft ph 1) BigInteger/ONE) mask)
              ^BigInteger mh (.and (.shiftLeft mh 1) mask)]
          (recur (next bs)
                 (.or mh ^BigInteger (inv (.or xv ph)))
                 (.and ph xv)
                 score))))))

(defn- lev-limbs
  "Hyyro with the bit-vector in 62-bit limbs of a long-array (no BigInteger interop)."
  [a b]
  (let [m (count a)
        L (quot (+ m 61) 62)
        M (dec (bit-shift-left 1 62))
        topbits (- m (* 62 (dec L)))
        masks (let [ms (long-array L M)] (aset ms (dec L) (dec (bit-shift-left 1 topbits))) ms)
        topbit (bit-shift-left 1 (dec topbits))
        peq (let [h (java.util.HashMap.)]
              (dotimes [i m]
                (let [c (nth a i)
                      arr (or (.get h c) (let [x (long-array L)] (.put h c x) x))]
                  (aset ^longs arr (quot i 62)
                        (bit-or (aget ^longs arr (quot i 62)) (bit-shift-left 1 (rem i 62))))))
              h)
        zero (long-array L)
        pv (long-array masks)
        mv (long-array L)]
    (loop [bs (seq b), score m]
      (if-not bs
        score
        (let [eqa ^longs (or (.get ^java.util.HashMap peq (first bs)) zero)
              delta (loop [k 0, carry 0, sph 1, smh 0, delta 0]
                      (if (= k L)
                        delta
                        (let [mk (aget masks k) eqk (aget eqa k) pvk (aget pv k) mvk (aget mv k)
                              xvk (bit-or eqk mvk)
                              t (+ (+ (bit-and eqk pvk) pvk) carry)
                              xhk (bit-or (bit-xor (bit-and (bit-and t M) mk) pvk) eqk)
                              phk (bit-or mvk (bit-and (bit-not (bit-or xhk pvk)) mk))
                              mhk (bit-and pvk xhk)
                              delta (if (= k (dec L))
                                      (cond (not (zero? (bit-and phk topbit))) 1
                                            (not (zero? (bit-and mhk topbit))) -1
                                            :else 0)
                                      delta)
                              phs (bit-or (bit-and (bit-shift-left phk 1) mk) sph)
                              mhs (bit-or (bit-and (bit-shift-left mhk 1) mk) smh)]
                          (aset pv k (bit-or mhs (bit-and (bit-not (bit-or xvk phs)) mk)))
                          (aset mv k (bit-and phs xvk))
                          (recur (inc k) (bit-shift-right t 62)
                                 (bit-and (bit-shift-right phk 61) 1)
                                 (bit-and (bit-shift-right mhk 61) 1)
                                 delta))))]
          (recur (next bs) (+ score delta)))))))

(defn lev
  "Levenshtein distance of two sequences (string or collection of values); Hyyro's
  bit-parallel algorithm (longs up to 62 elements, BigIntegers beyond)."
  [a b]
  (let [a (cps a) b (cps b)
        [a b] (if (< (count a) (count b)) [b a] [a b])
        m (count a)]
    (cond (zero? (count b)) m
          (<= m 62) (lev-long a b)
          (<= m 600) (lev-limbs a b)
          :else (lev-big a b))))

(defn sim [a b]
  (if (and (empty? a) (empty? b))
    1.0
    (- 1.0 (div (lev a b) (max (count (cps a)) (count (cps b)))))))

;; ------------------------------------------------------------------ walk ----

(defn walk [n]
  (cons n (mapcat walk (or (get n "children") []))))

(defn units
  "Reading-order text units: [kind text] of every text-bearing node."
  [body]
  (for [n (walk body) :when (and (contains? TEXT-KINDS (get n "kind")) (string? (get n "text")))]
    [(get n "kind") (get n "text")]))

(defn units-of-children [children]
  (units {"children" children}))

(defn body-text [body]
  (norm (str/join "\n" (map second (units body)))))

(defn block-text [n]
  (if (and (not (contains? CONTAINERS (get n "kind"))) (string? (get n "text")))
    (norm (get n "text"))
    (norm (str/join "\n" (map second (units-of-children (get n "children")))))))

(defn blocks [body kinds]
  (vec (for [n (rest (walk body)) :when (contains? kinds (get n "kind"))] n)))

;; -------------------------------------------------------------- geometry ----

(defn- boxes [at]
  (reduce (fn [by [p x0 y0 x1 y1]]
            (let [b (get by p [x0 y0 x1 y1])]
              (assoc by p [(min (b 0) x0) (min (b 1) y0) (max (b 2) x1) (max (b 3) y1)])))
          {} at))

(defn iou [a b]
  (let [ba (boxes a) bb (boxes b)
        [inter ua]
        (reduce (fn [[inter ua] p]
                  (let [x (get ba p) y (get bb p)
                        ax (if x (double (* (- (x 2) (x 0)) (- (x 3) (x 1)))) 0.0)
                        ay (if y (double (* (- (y 2) (y 0)) (- (y 3) (y 1)))) 0.0)
                        i (if (and x y)
                            (let [w (- (min (x 2) (y 2)) (max (x 0) (y 0)))
                                  h (- (min (x 3) (y 3)) (max (x 1) (y 1)))]
                              (* (double (max w 0.0)) (double (max h 0.0))))
                            0.0)]
                    [(+ inter i) (- (+ ua ax ay) i)]))
                [0.0 0.0] (sort (set (concat (keys ba) (keys bb)))))]
    (if (> ua 0) (/ inter ua) 0.0)))

;; -------------------------------------------------------------- difflib ----
;; difflib.SequenceMatcher(None, a, b, autojunk=False).get_matching_blocks()

(defn- longest-match [a b2j alo ahi blo bhi]
  (loop [i alo, j2len {}, bi alo, bj blo, bs 0]
    (if (>= i ahi)
      [bi bj bs]
      (let [[nj2 bi bj bs]
            (reduce (fn [[nj bi bj bs] j]
                      (cond
                        (< j blo) [nj bi bj bs]
                        (>= j bhi) (reduced [nj bi bj bs])
                        :else (let [k (inc (get j2len (dec j) 0))]
                                (if (> k bs)
                                  [(assoc nj j k) (inc (- i k)) (inc (- j k)) k]
                                  [(assoc nj j k) bi bj bs]))))
                    [{} bi bj bs] (get b2j (nth a i)))]
        (recur (inc i) nj2 bi bj bs)))))

(defn matching-blocks [a b]
  (let [a (vec a) b (vec b)
        la (count a) lb (count b)
        b2j (reduce-kv (fn [m j x] (update m x (fnil conj []) j)) {} b)
        found (loop [queue [[0 la 0 lb]], out []]
                (if (empty? queue)
                  out
                  (let [[alo ahi blo bhi] (peek queue)
                        queue (pop queue)
                        [i j k] (longest-match a b2j alo ahi blo bhi)]
                    (if (pos? k)
                      (recur (cond-> queue
                               (and (< alo i) (< blo j)) (conj [alo i blo j])
                               (and (< (+ i k) ahi) (< (+ j k) bhi)) (conj [(+ i k) ahi (+ j k) bhi]))
                             (conj out [i j k]))
                      (recur queue out)))))
        found (sort found)
        [nonadj i1 j1 k1]
        (reduce (fn [[out i1 j1 k1] [i2 j2 k2]]
                  (if (and (= (+ i1 k1) i2) (= (+ j1 k1) j2))
                    [out i1 j1 (+ k1 k2)]
                    [(if (pos? k1) (conj out [i1 j1 k1]) out) i2 j2 k2]))
                [[] 0 0 0] found)
        nonadj (if (pos? k1) (conj nonadj [i1 j1 k1]) nonadj)]
    (conj nonadj [la lb 0])))

;; -------------------------------------------------------------- matching ----

(defn match-geometry
  "Greedy by descending IoU. Returns [[gi pi]] sorted."
  [g p]
  (let [cand (sort (fn [x y] (compare y x))
                   (for [[i a] (map-indexed vector g) [j b] (map-indexed vector p)]
                     [(iou (get a "at") (get b "at")) i j]))]
    (loop [cand cand, ug #{}, up #{}, out []]
      (if-let [[v i j] (first cand)]
        (cond
          (< v IOU) (vec (sort out))
          (or (ug i) (up j)) (recur (rest cand) ug up out)
          :else (recur (rest cand) (conj ug i) (conj up j) (conj out [i j])))
        (vec (sort out))))))

(defn- dp
  "Monotone alignment on a gap: maximise summed similarity."
  [gt pt gk pk]
  (let [n (count gt) m (count pt)
        best (object-array (inc n))
        back (object-array (inc n))]
    (dotimes [i (inc n)]
      (aset best i (double-array (inc m)))
      (aset back i (object-array (inc m))))
    (let [s0 (fn [i j]
               (if (and (empty? (gt i)) (empty? (pt j)))
                 (if (= (gk i) (pk j)) 1.0 0.0)
                 (let [a (gt i) b (pt j)
                       la (nlen a) lb (nlen b)]
                   ;; lev >= |la - lb|: a pair that cannot reach SIM needs no edit distance
                   (if (< (- 1.0 (div (Math/abs (- la lb)) (max la lb))) SIM)
                     0.0
                     (let [v (sim a b)] (if (>= v SIM) v 0.0))))))
          ;; the similarity of every cell is independent: compute the matrix in parallel
          rows (vec ((if (> (* n m) 256) pmap map) (fn [i] (vec (for [j (range m)] (s0 i j)))) (range n)))
          s (fn [i j] (get-in rows [i j]))]
      (doseq [i (range 1 (inc n)) j (range 1 (inc m))]
        (let [b0 (aget ^doubles (aget best (dec i)) j)
              pj (aget ^doubles (aget best i) (dec j))
              [b how] (if (> pj b0) [pj "p"] [b0 "g"])
              v (s (dec i) (dec j))
              diag (aget ^doubles (aget best (dec i)) (dec j))
              [b how] (if (and (> v 0) (>= (+ diag v) b)) [(+ diag v) "m"] [b how])]
          (aset ^doubles (aget best i) j (double b))
          (aset ^objects (aget back i) j how)))
      (loop [i n j m out []]
        (if (and (> i 0) (> j 0))
          (let [h (aget ^objects (aget back i) j)]
            (case h
              "m" (recur (dec i) (dec j) (conj out [(dec i) (dec j)]))
              "g" (recur (dec i) j out)
              (recur i (dec j) out)))
          (vec (reverse out)))))))

(defn match-text [g p]
  (let [gt (mapv block-text g) pt (mapv block-text p)
        gk (mapv #(get % "kind") g) pk (mapv #(get % "kind") p)
        ga (mapv (fn [t k] (if (empty? t) (str "\u0000" k) t)) gt gk)
        pa (mapv (fn [t k] (if (empty? t) (str "\u0000" k) t)) pt pk)]
    (loop [bl (matching-blocks ga pa), gi 0, pi 0, out []]
      (if-let [[a b size] (first bl)]
        (let [out (into out (map (fn [[i j]] [(+ gi i) (+ pi j)])
                                 (dp (subvec gt gi a) (subvec pt pi b) (subvec gk gi a) (subvec pk pi b))))
              out (into out (map (fn [k] [(+ a k) (+ b k)]) (range size)))]
          (recur (rest bl) (+ a size) (+ b size) out))
        out))))

(defn match [g p]
  (if (and (seq g) (seq p) (every? #(seq (get % "at")) g) (every? #(seq (get % "at")) p))
    (match-geometry g p)
    (match-text g p)))

;; -------------------------------------------------------------- sections ----

(defn kendall-tau
  "tau over matched blocks: pairs are [gold-idx pred-idx], gold-sorted."
  [pairs]
  (let [pairs (vec pairs) n (count pairs)]
    (when (>= n 2)
      (let [[c d] (reduce (fn [[c d] [a b]]
                            (if (< (get-in pairs [a 1]) (get-in pairs [b 1])) [(inc c) d] [c (inc d)]))
                          [0 0]
                          (for [a (range n) b (range (inc a) n)] [a b]))]
        (div (- c d) (+ c d))))))

(defn para-boundaries [body]
  (reduce (fn [[words cuts] [k t]]
            (if (contains? #{"paragraph" "item" "quote" "caption"} k)
              (let [w (split-ws (norm t))]
                (if (empty? w)
                  [words cuts]
                  [(into words w) (if (seq words) (conj cuts (count words)) cuts)]))
              [words cuts]))
          [[] #{}] (units body)))

(defn boundary-counts [gb pb]
  (let [[gw gc] (para-boundaries gb)
        [pw pc] (para-boundaries pb)
        pair (into {} (for [[a b size] (matching-blocks gw pw) k (range size)] [(+ a k) (+ b k)]))
        tp (count (for [i gc
                        :let [j (cond (contains? pair i) (pair i)
                                      (contains? pair (dec i)) (inc (pair (dec i))))]
                        :when (and j (contains? pc j))] i))]
    {"para.boundary.tp" tp "para.boundary.gold" (count gc) "para.boundary.pred" (count pc)}))

(defn- fmt-template [t n]
  (str/replace t #"\{n(?::0?(\d+))?\}"
               (fn [[_ w]]
                 (let [s (str n)]
                   (if w (str (apply str (repeat (max 0 (- (Long/parseLong w) (count s))) "0")) s) s)))))

(defn ledger
  "Multiset of [reason page text] for the dropped entries of `reasons`."
  [model reasons]
  (frequencies
   (for [d (or (get model "dropped") []) :when (contains? reasons (get d "reason"))]
     (let [page (let [p (get d "page")]
                  (if (and p (not (zero? p))) p (if (seq (get d "at")) (get-in d ["at" 0 0]) 0)))]
       [(str "dropped:" (get d "reason")) page (norm (get d "text" ""))]))))

(defn furniture
  "Multiset of [role page text]: a template is expanded per page."
  [model]
  (frequencies
   (for [f (or (get model "furniture") []) pg (or (get f "pages") [])]
     (let [t (if (some? (get f "template"))
               (fmt-template (get f "template") pg)
               (str/join "\n" (map second (units-of-children (get f "children")))))]
       [(str "furniture:" (get f "role" "")) pg (norm t)]))))

(defn multiset-counts [g p]
  [(psum (for [[k v] g] (min v (get p k 0)))) (psum (vals g)) (psum (vals p))])

(defn table-teds-lite
  "A TEDS stand-in: a table's cells as [row colspan rowspan text] tuples in order;
  similarity = 1 - edit distance / max length. `teds-struct` drops the text."
  [g p]
  (let [cells (fn [t with-text]
                (vec (for [[ri r] (map-indexed vector (filter #(= "row" (get %  "kind")) (walk t)))
                           c (or (get r "children") [])]
                       [ri (get c "colspan" 1) (get c "rowspan" 1) (if with-text (norm (get c "text" "")) "")])))]
    (into {} (for [[name wt] [["teds" true] ["teds-struct" false]]]
               (let [a (cells g wt) b (cells p wt)]
                 [name (- 1.0 (div (lev a b) (max (count a) (count b) 1)))])))))

(def STUBS
  ["table.teds: real TEDS (tree edit distance over table HTML) not implemented; table-teds-lite is a cell-sequence stand-in"
   "heading.hierarchy-teds: hierarchy TEDS not implemented (heading detection and level accuracy are)"
   "confidence.ece: expected calibration error needs calibrated confidences (Model conf is 'uncalibrated', SPEC 14.2)"
   "seals.name-cer: seal name/text CER is not split out of block text"
   "gold has no geometry: the IoU >= 0.5 rule is implemented and unit-tested but unexercised on the synthetic corpus"])

;; ---------------------------------------------------- layout features (D115) ----
;; Initials, alignment, columns and continuation. Every metric here is gated by
;; what the gold claims: an attribute key in `annotated.attributes` (`initial`,
;; `align`), `annotated.layout` with `facts.frags`, and `facts.hyphens`.

(def COVER 0.9)   ; share of a gold block's words one predicted block must hold to "hold it"

(defn- attr? [ann kind a] (contains? (set (get-in ann ["attributes" kind])) a))

(defn- leaf-text
  "The text of an inline subtree: its own `text`, else its leaves' in order."
  [n]
  (if (string? (get n "text"))
    (get n "text")
    (str/join (for [d (rest (walk n)) :when (and (string? (get d "text")) (empty? (get d "children")))]
                (get d "text")))))

(defn initial-of
  "The first `initial` inline of a block, or nil."
  [n]
  (first (filter #(= "initial" (get % "kind")) (mapcat walk (get n "inlines")))))

(defn- word-at? [^String t i n]
  (let [j (+ i n)]
    (and (or (zero? i) (not (Character/isLetterOrDigit (.charAt t (dec i)))))
         (or (>= j (count t)) (not (Character/isLetterOrDigit (.charAt t j)))))))

(defn- rewrite-text
  "Replace, left to right, each hyphenated `word` by its `printed` form."
  [^String t pairs]
  (loop [t t, from 0, ps pairs]
    (if-let [[printed word] (first ps)]
      (let [i (loop [f from]
                (let [i (.indexOf t ^String word (int f))]
                  (cond (neg? i) -1
                        (word-at? t i (count word)) i
                        :else (recur (inc i)))))]
        (if (neg? i)
          (recur t from (rest ps))
          (recur (str (subs t 0 i) printed (subs t (+ i (count word)))) (+ i (count printed)) (rest ps))))
      t)))

(defn rewrite-hyphens
  "D114 convention 12: the gold text as a no-locale run must read it, every
  `facts.hyphens` word in its printed form (hyphen kept, joined, SPEC 3.6)."
  [body hyphens]
  (let [by-id (reduce (fn [m h] (update m (str (get h "id")) (fnil conj []) [(get h "printed") (get h "word")]))
                      {} hyphens)]
    (if (empty? by-id)
      body
      ((fn go [n]
         (let [n (if-let [ps (and (string? (get n "text")) (get by-id (str (get n "id"))))]
                   (assoc n "text" (rewrite-text (get n "text") ps))
                   n)]
           (if (contains? n "children") (assoc n "children" (mapv go (get n "children"))) n)))
       body))))

(defn frag-map
  "facts.frags as {id-string [[page band col] ...]}: a map keyed by block id, or a
  list of {id, frags}."
  [facts]
  (let [f (get facts "frags")]
    (cond (map? f) (into {} (for [[k v] f] [(str k) (mapv vec v)]))
          (sequential? f) (into {} (for [e f] [(str (get e "id")) (mapv vec (or (get e "frags") (get e "at")))]))
          :else {})))

(defn- pred-frags
  "Each region of a predicted block as [page band col], through the predicted page's
  `layout` cell containing the region's centre; nil where there is no such cell."
  [pred n]
  (let [cells (into {} (for [pg (or (get pred "pages") [])] [(get pg "n") (get pg "layout")]))]
    (vec (for [[pg x0 y0 x1 y1] (get n "at")]
           (let [cx (/ (+ x0 x1) 2.0) cy (/ (+ y0 y1) 2.0)]
             (when-let [c (first (filter (fn [c] (let [[a b c2 d] (get c "box")]
                                                  (and a (<= a cx c2) (<= b cy d))))
                                         (get cells pg)))]
               [pg (get c "band") (get c "col")]))))))

(defn- cover-ratio [gw pw]
  (if (empty? gw) 0.0 (div (reduce + (map #(nth % 2) (matching-blocks gw pw))) (count gw))))

(defn- sub-seq? [needle hay]
  (let [n (count needle)]
    (boolean (and (pos? n) (some #(= needle (subvec hay % (+ % n))) (range 0 (inc (- (count hay) n))))))))

(defn- cells-of [t]
  (vec (for [r (filter #(= "row" (get % "kind")) (walk t))]
         (vec (filter #(= "cell" (get % "kind")) (get r "children"))))))

(def ALIGN-KINDS ["paragraph" "heading" "caption" "cell"])

(defn feature-counts!
  "Adds the initial, alignment, column and continuation counts. `g` and `p` are the
  gold and predicted blocks of the annotated kinds, `pairs` the match [[gi pj]]."
  [add ann gold pred g p pairs]
  (let [structure (set (get ann "structure"))
        pairs (vec pairs)
        gtext (mapv block-text g) ptext (mapv block-text p)
        by-g (into {} pairs)
        ;; the committed gold declares the attribute on the inline kind: attributes.initial = [form lines]
        ikinds (if (or (attr? ann "initial" "form") (attr? ann "initial" "lines"))
                 (set (filter #(contains? structure %) BLOCK-KINDS))
                 (set (filter #(and (contains? structure %) (attr? ann % "initial")) BLOCK-KINDS)))]
    ;; ---- initials
    (when (seq ikinds)
      (let [gi (vec (for [[i n] (map-indexed vector g) :when (and (ikinds (get n "kind")) (initial-of n))] i))
            pi (vec (for [[j n] (map-indexed vector p) :when (initial-of n)] j))
            both (filter (fn [i] (when-let [j (by-g i)] (initial-of (p j)))) gi)
            g-init-text (set (for [i gi] (norm (leaf-text (initial-of (g i))))))
            g-first (set (for [i gi :let [w (first (split-ws (gtext i)))] :when w] w))
            g-heading (set (for [[i j] pairs :when (= "heading" (get-in g [i "kind"]))] j))
            fh (count (for [[j n] (map-indexed vector p)
                            :when (and (= "heading" (get n "kind")) (not (g-heading j))
                                       (<= 1 (nlen (ptext j)) 3)
                                       (or (g-init-text (ptext j)) (some #(str/starts-with? % (ptext j)) g-first)))]
                        j))]
        (add "initial.tp" (count both)) (add "initial.gold" (count gi)) (add "initial.pred" (count pi))
        (add "initial.form.n" (count both))
        (add "initial.form.ok" (count (filter (fn [i] (pyeq (get (initial-of (g i)) "form")
                                                            (get (initial-of (p (by-g i))) "form")))
                                              both)))
        (add "initial.fw.n" (count gi))
        (add "initial.fw.ok" (count (filter (fn [i] (when-let [j (by-g i)]
                                                      (= (first (split-ws (gtext i))) (first (split-ws (ptext j))))))
                                            gi)))
        (add "initial.fh" fh)))
    ;; ---- alignment
    (let [akinds (vec (filter #(and (contains? structure %) (attr? ann % "align")) ALIGN-KINDS))]
      (when (seq akinds)
        (let [tabs (filter (fn [[i j]] (and (= "table" (get-in g [i "kind"])) (= "table" (get-in p [j "kind"])))) pairs)
              items (concat
                     (for [[i j] pairs :when (not= "cell" (get-in g [i "kind"]))]
                       [(get-in g [i "kind"]) (g i) (p j)])
                     (for [[i j] tabs
                           :let [gc (cells-of (g i)) pc (cells-of (p j))]
                           ri (range (min (count gc) (count pc)))
                           ci (range (min (count (gc ri)) (count (pc ri))))]
                       ["cell" (get-in gc [ri ci]) (get-in pc [ri ci])]))
              all (fn [body k] (filter #(= k (get %  "kind")) (walk body)))
              gall (fn [k] (if (= k "cell") (all (get-in gold ["model" "body"]) k) (filter #(= k (get % "kind")) g)))
              pall (fn [k] (if (= k "cell") (all (or (get pred "body") {}) k) (filter #(= k (get % "kind")) p)))]
          (doseq [k akinds]
            (let [its (filter (fn [[kk gn pn]] (and (= kk k) (get gn "align") (get pn "align"))) items)
                  ng (count (filter #(get % "align") (gall k)))
                  np (count (filter #(get % "align") (pall k)))
                  ok (count (filter (fn [[_ gn pn]] (= (get gn "align") (get pn "align"))) its))]
              (add (str "align." k ".gold") ng) (add (str "align." k ".n") (count its))
              (add (str "align." k ".ok") ok)
              (add "align.gold" ng) (add "align.n" (count its)) (add "align.ok" ok) (add "align.pred" np))))))
    ;; ---- columns and continuation
    (let [facts (get gold "facts")
          frags (frag-map facts)
          fg (vec (for [[i n] (map-indexed vector g) :when (contains? frags (str (get n "id")))] i))
          fid (fn [i] (frags (str (get-in g [i "id"]))))]
      (when (and (get ann "layout") (seq frags))
        (add "frag.n" (count fg))
        (add "frag.ok" (count (filter (fn [i] (when-let [j (by-g i)] (= (fid i) (pred-frags pred (p j))))) fg)))
        (let [gw (mapv split-ws gtext) pw (mapv split-ws ptext)
              multi (filter #(>= (count (fid %)) 2) fg)
              hits (count (filter (fn [i] (some #(>= (cover-ratio (gw i) (pw %)) COVER) (range (count p)))) multi))
              ;; consecutive gold blocks whose boundary falls on a fragment boundary
              bnds (for [[a b] (partition 2 1 fg) :when (not= (peek (fid a)) (first (fid b)))] [a b])
              spanning (set (for [[a b] bnds
                                  :when (and (seq (gw a)) (seq (gw b)))
                                  :let [needle (vec (concat (take-last 2 (gw a)) (take 2 (gw b))))]
                                  j (range (count p))
                                  :when (sub-seq? needle (pw j))]
                              j))]
          (add "join.gold" (count multi)) (add "join.tp" hits) (add "join.pred" (+ hits (count spanning)))))
      (when-let [hy (seq (get facts "hyphens"))]
        (let [nolocale (nil? (get-in pred ["settings" "locale"]))]
          (add "hyphen.n" (count hy))
          (add "hyphen.ok"
               (count (filter (fn [h]
                                (let [gi (first (keep-indexed (fn [i n] (when (= (str (get n "id")) (str (get h "id"))) i)) g))
                                      j (when gi (by-g gi))]
                                  (boolean (and j (str/includes? (ptext j) (norm (get h (if nolocale "printed" "word"))))))))
                              hy))))))))

;; ---------------------------------------------------------------- fusion ----
;; The fusion metrics (D206, SPEC 14.2). They read PROVENANCE: a node's (or an inline's) `src` and
;; `flags`, at the finest level the JSON has: an inline's own `src`/`flags` when it has them, else those of
;; the nearest enclosing inline or block. `seal` nodes are outside every one of these counts (their text is
;; judged by the seal metrics, and OCR is the right source for a stamp).

(defn- prov-segments
  "[{:text norm-text :src #{..} :flags #{..}}] of every text segment of a leaf node, finest level first."
  [n src flags]
  (let [src (if (contains? n "src") (set (get n "src")) src)
        flags (if (contains? n "flags") (set (get n "flags")) flags)
        kids (get n "inlines")]
    (cond
      (and (= "text" (get n "kind")) (string? (get n "text")))
      [{:text (norm (get n "text")) :src src :flags flags}]
      (seq kids) (vec (mapcat #(prov-segments % src flags) kids))
      (seq (get n "children")) (vec (mapcat #(prov-segments % src flags) (get n "children")))
      (string? (get n "text")) [{:text (norm (get n "text")) :src src :flags flags}]
      :else [])))

(defn- text-nodes
  "Every text-bearing node of the body outside `seal` nodes, in reading order."
  [body]
  (for [n (walk body) :when (and (contains? TEXT-KINDS (get n "kind")) (not= "seal" (get n "kind"))
                                 (string? (get n "text")))]
    n))

(defn- body-segments [body]
  (vec (for [n (text-nodes body)
             s (prov-segments (dissoc n "children") (set (get n "src")) (set (get n "flags")))]
         s)))

(defn- non-seal-text [body]
  (norm (str/join "\n" (map #(get % "text") (text-nodes body)))))

(def TL-SRC #{"text" "hidden-text"})

(defn- has-prov? [segs] (boolean (some #(or (seq (:src %)) (seq (:flags %))) segs)))

(defn fusion-counts!
  "Adds the fusion counts of one document. `opts` {:variant \"pdf\"|\"scan\"|...}: `tl-override` is a
  born-digital measure, so scans do not count it, and a gold whose `facts.tl-untrusted` is true (a text layer
  that is wrong or older on purpose: overriding it is right) does not either."
  [add gold pred opts]
  (let [gold-body (get-in gold ["model" "body"])
        gb (let [hy (get-in gold ["facts" "hyphens"])]
             (if (and (seq hy) (nil? (get-in pred ["settings" "locale"]))) (rewrite-hyphens gold-body hy) gold-body))
        pb (or (get pred "body") {"id" 0 "kind" "document"})
        segs (body-segments pb)
        gt (non-seal-text gb)
        pt (non-seal-text pb)
        pw (split-ws pt)
        cg (frequencies (split-ws gt))
        cp (frequencies pw)
        ;; ghost words: OCR-only words beyond what the gold body has, by count (as watermark-leak counts)
        ocr-only? (fn [s] (and (contains? (:src s) "ocr") (not-any? TL-SRC (:src s))))
        ocr-only (frequencies (for [s segs :when (ocr-only? s) w (split-ws (:text s))] w))
        ghosts (reduce + 0 (for [[w n] ocr-only] (min n (max 0 (- (get cp w 0) (get cg w 0))))))
        chars (fn [pred-seg] (reduce + 0 (for [s segs :when (pred-seg s)] (nlen (str/join "" (split-ws (:text s)))))))
        body-chars (chars (constantly true))]
    (add "fusion.body-words" (count pw))
    (add "fusion.ghost-words" ghosts)
    (when (has-prov? segs)
      (add "fusion.src-ocr-chars" (chars ocr-only?))
      (add "fusion.src-chars" body-chars))
    (when (and (not= "scan" (get opts :variant)) (not (get-in gold ["facts" "tl-untrusted"])))
      (add "fusion.tl-susp-chars" (chars #(contains? (:flags %) "text-layer-suspect")))
      (add "fusion.tl-chars" body-chars))
    ;; seal leak: characters of the gold's seal and stamp words that reach the body OUTSIDE seal nodes,
    ;; beyond the gold body's own count of them, over the body's characters
    (let [sw (set (for [n (walk gb) :when (= "seal" (get n "kind")) w (split-ws (norm (get n "text" "")))] w))]
      (when (seq sw)
        (add "fusion.seal-leak-chars" (reduce + 0 (for [w sw] (* (nlen w) (max 0 (- (get cp w 0) (get cg w 0)))))))
        (add "fusion.seal-body-chars" (nlen pt))))))

;; ------------------------------------------------------------- inline styles and links (P4.7, D194) ----
;; Gated by `annotated.inlines`: the gold claims its spans and links. Scored over the WHOLE body as multisets
;; (no block matching: a span is where its text is), a span by (style, normalized text) once for each style
;; it has, a link by (href, normalized text), an internal link (a `target` node) by ("#", text). A span
;; with no text is not counted. Precision and recall per style, and for links.

(def SPAN-STYLES ["bold" "italic" "mono" "sup" "sub"])

(defn inline-nodes
  "Every inline node under every block of a body, with its descendants, in document order."
  [body]
  (vec (for [n (walk body) i (get n "inlines") d (walk i)] d)))

(defn span-keys
  "[[style text] ...] for the spans of a body."
  [body]
  (vec (for [d (inline-nodes body) :when (= "span" (get d "kind"))
             :let [t (norm (leaf-text d))] :when (seq t)
             s (get d "style")]
         [s t])))

(defn link-keys
  "[[href text] ...] for the links of a body."
  [body]
  (vec (for [d (inline-nodes body) :when (= "link" (get d "kind"))
             :let [t (norm (leaf-text d))]]
         [(or (get d "href") (when (some? (get d "target")) "#")) t])))

(defn inline-counts!
  [add gb pb]
  (let [gs (span-keys gb) ps (span-keys pb)
        one (fn [pre g p]
              (let [[tp ng npd] (multiset-counts (frequencies g) (frequencies p))]
                (when (or (pos? ng) (pos? npd))
                  (add (str pre ".tp") tp) (add (str pre ".gold") ng) (add (str pre ".pred") npd))))]
    (doseq [s SPAN-STYLES]
      (one (str "span." s) (for [[st t] gs :when (= st s)] t) (for [[st t] ps :when (= st s)] t)))
    (one "link" (link-keys gb) (link-keys pb))))

;; ----------------------------------------------------------------- score ----

(declare score*)

(defn score
  "Counts for one document. `pred` is an emon-1 Model map, `gold` a gold map. `opts` {:variant v} is the
  committed input form (pdf, scan, ...), which only the born-digital fusion metric reads."
  ([pred gold] (score pred gold {}))
  ([pred gold opts] (score* pred gold opts)))

(defn- score*
  [pred gold opts]
  (let [ann (get gold "annotated")
        gm (get gold "model")
        gb (let [b (get gm "body") hy (get-in gold ["facts" "hyphens"])]
             ;; a run with no locale keeps printed hyphens (SPEC 3.6): read the gold that way (D114 12)
             (if (and (seq hy) (nil? (get-in pred ["settings" "locale"]))) (rewrite-hyphens b hy) b))
        pb (or (get pred "body") {"id" 0 "kind" "document"})
        attrs (get ann "attributes" {})
        c (atom (array-map))
        add (fn ([k] (swap! c update k (fnil + 0) 1))
              ([k v] (swap! c update k (fnil + 0) v)))]
    ;; text: CER / WER / bag of words over reading order
    (when (get ann "text")
      (let [gt (body-text gb) pt (body-text pb)
            gw (split-ws gt) pw (split-ws pt)]
        (add "text.chars" (nlen gt)) (add "text.char-err" (lev gt pt))
        (add "text.words" (count gw)) (add "text.word-err" (lev gw pw))
        (let [fg (frequencies gw) fp (frequencies pw)]
          (add "text.bow.tp" (reduce + 0 (for [[w n] fg] (min n (get fp w 0))))))
        (add "text.bow.gold" (count gw)) (add "text.bow.pred" (count pw))))
    ;; blocks by kind (presence AND absence)
    (let [structure (set (get ann "structure"))
          kinds (vec (filter structure BLOCK-KINDS))
          kset (set kinds)
          g (blocks gb kset) p (blocks pb kset)
          pairs (match g p)
          gk (fn [i] (get-in g [i "kind"])) pk (fn [j] (get-in p [j "kind"]))]
      (doseq [k kinds]
        (let [ng (count (filter #(= k (get % "kind")) g))
              npd (count (filter #(= k (get % "kind")) p))
              tp (count (filter (fn [[i j]] (and (= (gk i) k) (= k (pk j)))) pairs))]
          (when (or (pos? ng) (pos? npd))
            (add (str "block." k ".tp") tp) (add (str "block." k ".gold") ng) (add (str "block." k ".pred") npd))))
      (when-let [t (kendall-tau pairs)]
        (add "order.tau-sum" t) (add "order.docs"))
      (feature-counts! add ann gold pred g p pairs)
      ;; headings
      (when (and (contains? kset "heading") (contains? (set (get attrs "heading")) "level"))
        (let [hp (filter (fn [[i j]] (and (= (gk i) "heading") (= "heading" (pk j)))) pairs)]
          (add "heading.level.n" (count hp))
          (add "heading.level.ok" (count (filter (fn [[i j]] (pyeq (get-in g [i "level"]) (get-in p [j "level"]))) hp)))
          (add "heading.level.near" (count (filter (fn [[i j]]
                                                     (let [pl (get-in p [j "level"])]
                                                       (and (integer? pl) (<= (Math/abs (long (- (get-in g [i "level"]) pl))) 1))))
                                                   hp)))
          (add "heading.gold" (count (filter #(= "heading" (get % "kind")) g)))))
      ;; figures
      (when (and (contains? kset "figure") (contains? (set (get attrs "figure")) "subkind"))
        (let [fp (filter (fn [[i j]] (and (= (gk i) "figure") (= "figure" (pk j)))) pairs)]
          (add "figure.subkind.n" (count fp))
          (add "figure.subkind.ok" (count (filter (fn [[i j]] (pyeq (get-in g [i "subkind"]) (get-in p [j "subkind"]))) fp)))
          ;; per kind (P8.1): among the figures that matched, how many of each kind the gold has, the prediction says, and both
          (doseq [k FIGURE-KINDS]
            (let [kg (count (filter (fn [[i _]] (= k (get-in g [i "subkind"]))) fp))
                  kp (count (filter (fn [[_ j]] (= k (get-in p [j "subkind"]))) fp))
                  ktp (count (filter (fn [[i j]] (and (= k (get-in g [i "subkind"])) (= k (get-in p [j "subkind"])))) fp))]
              (when (or (pos? kg) (pos? kp))
                (add (str "figure.kind." k ".tp") ktp) (add (str "figure.kind." k ".gold") kg) (add (str "figure.kind." k ".pred") kp))))))
      ;; tables
      (when (contains? kset "table")
        (let [ng (count (filter #(= "table" (get % "kind")) g))]
          (when (pos? ng)
            (let [tp_ (filter (fn [[i j]] (and (= (gk i) "table") (= "table" (pk j)))) pairs)
                  s (mapv (fn [[i j]] (table-teds-lite (g i) (p j))) tp_)]
              (add "table.n" ng)
              (add "table.teds-lite-sum" (psum (map #(get % "teds") s)))
              (add "table.teds-struct-lite-sum" (psum (map #(get % "teds-struct") s)))
              (when (contains? (set (get attrs "table")) "continued")
                (add "table.continued.n" ng)
                (add "table.continued.ok"
                     (count (filter (fn [[i j]] (pyeq (get-in g [i "continued"] 1) (get-in p [j "continued"] 1))) tp_)))))))))
    (when (get ann "text") (fusion-counts! add gold pred opts))
    ;; inline styles and links
    (when (get ann "inlines") (inline-counts! add gb pb))
    ;; paragraph boundaries
    (when (and (get ann "text") (contains? (set (get ann "structure")) "paragraph"))
      (doseq [[k v] (boundary-counts gb pb)] (add k v)))
    ;; furniture / dropped ledger
    (when (or (get ann "furniture") (seq (get ann "dropped")))
      (let [reasons (set (get ann "dropped"))
            gx (ledger gm reasons) px (ledger pred reasons)
            [gx px] (if (get ann "furniture") [(merge gx (furniture gm)) (merge px (furniture pred))] [gx px])
            [tp ng npd] (multiset-counts gx px)]
        (add "furniture.tp" tp) (add "furniture.gold" ng) (add "furniture.pred" npd)
        (when (contains? reasons "watermark")
          (let [wm (set (for [d (or (get gm "dropped") []) :when (= "watermark" (get d "reason"))
                              w (split-ws (norm (get d "text" "")))] w))]
            (when (seq wm)
              (let [cg (frequencies (filter wm (split-ws (body-text gb))))
                    cp (frequencies (filter wm (split-ws (body-text pb))))]
                (add "contam.leak-chars" (reduce + 0 (for [[w n] cp :when (> n (get cg w 0))]
                                                       (* (nlen w) (- n (get cg w 0))))))
                (add "contam.body-chars" (nlen (body-text pb)))))))))
    ;; page labels
    (when (contains? (set (get ann "pages")) "label")
      (let [gp (into {} (for [x (or (get gm "pages") [])] [(get x "n") (get x "label")]))
            pp (into {} (for [x (or (get pred "pages") [])] [(get x "n") (get x "label")]))]
        (add "pages.label.n" (count gp))
        (add "pages.label.ok" (count (filter (fn [[n v]] (= (get pp n) v)) gp)))))
    ;; the graphics metrics (P6.8, D227): the section at the end, in tools/accuracy/graphics.clj
    (G/add-counts! add pred gold opts)
    {"counts" @c "stubs" STUBS}))

;; ---------------------------------------------------------------- ratios ----

(defn- ratio [n d] (when (and d (not (zero? d))) (div n d)))

(defn- prf [out c pre name]
  (if (and (not (contains? c (str pre ".gold"))) (not (contains? c (str pre ".pred"))))
    out
    (let [tp (get c (str pre ".tp") 0) g (get c (str pre ".gold") 0) p (get c (str pre ".pred") 0)
          pr (if (zero? p) (if (zero? g) 1.0 0.0) (div tp p))
          rc (if (zero? g) (if (zero? p) 1.0 0.0) (div tp g))]
      (-> out
          (pyjson/oassoc (str name "-p") pr)
          (pyjson/oassoc (str name "-r") rc)
          (pyjson/oassoc (str name "-f1") (if (not (zero? (+ pr rc))) (/ (* (* 2 pr) rc) (+ pr rc)) 0.0))))))

(defn metrics
  "Pooled counts -> {metric-name value} (insertion-ordered). Names are scoreboard keys."
  [c]
  (let [c (into {} c)
        m (array-map)
        set* (fn [m k v] (if (nil? v) m (pyjson/oassoc m k v)))
        m (if (contains? c "text.chars")
            (-> m
                (set* "cer" (ratio (c "text.char-err") (c "text.chars")))
                (set* "wer" (ratio (c "text.word-err") (c "text.words")))
                (prf c "text.bow" "bow"))
            m)
        m (if (pos? (get c "order.docs" 0)) (set* m "order-tau" (div (c "order.tau-sum") (c "order.docs"))) m)
        m (reduce (fn [m k] (prf m c (str "block." k) (str "block-" k))) m BLOCK-KINDS)
        m (prf m c "block.heading" "heading")
        m (if (contains? c "heading.level.n")
            (-> m
                (set* "heading-level-acc" (ratio (c "heading.level.ok") (c "heading.level.n")))
                (set* "heading-level-near" (ratio (c "heading.level.near") (c "heading.level.n")))
                (set* "heading-level-e2e" (ratio (c "heading.level.ok") (c "heading.gold"))))
            m)
        m (if (contains? c "figure.subkind.n")
            (set* m "figure-subkind-acc" (ratio (c "figure.subkind.ok") (c "figure.subkind.n")))
            m)
        ;; per kind: precision and recall over the matched figures (placement is block-figure-*)
        m (reduce (fn [m k] (prf m c (str "figure.kind." k) (str "figure-kind-" k))) m FIGURE-KINDS)
        m (if (pos? (get c "table.n" 0))
            (let [m (-> m
                        (set* "table-teds-lite" (div (c "table.teds-lite-sum") (c "table.n")))
                        (set* "table-teds-struct-lite" (div (c "table.teds-struct-lite-sum") (c "table.n"))))]
              (if (pos? (get c "table.continued.n" 0))
                (set* m "table-continued-acc" (div (c "table.continued.ok") (c "table.continued.n")))
                m))
            m)
        m (reduce (fn [m k] (prf m c (str "span." k) (str "span-" k))) m SPAN-STYLES)
        m (prf m c "link" "link")
        m (prf m c "para.boundary" "para-boundary")
        m (prf m c "furniture" "furniture")
        m (if (pos? (get c "pages.label.n" 0))
            (set* m "page-label-acc" (div (c "pages.label.ok") (c "pages.label.n")))
            m)
        m (if (contains? c "initial.gold")
            (-> m
                (prf c "initial" "initial")
                ;; an initial the gold has and nothing found scores 0 here, not absent
                (set* "initial-form-acc" (if (pos? (c "initial.form.n"))
                                           (div (c "initial.form.ok") (c "initial.form.n"))
                                           (when (pos? (c "initial.gold")) 0.0)))
                (set* "first-word-acc" (ratio (c "initial.fw.ok") (c "initial.fw.n")))
                (set* "initial-false-heading" (or (ratio (c "initial.fh") (c "initial.gold")) 0.0)))
            m)
        m (if (contains? c "align.gold")
            (let [acc (fn [n ok g] (if (pos? n) (div ok n) (when (pos? g) 0.0)))
                  m (-> m
                        (set* "align-acc" (acc (c "align.n") (c "align.ok") (c "align.gold")))
                        (set* "align-cov" (ratio (c "align.n") (c "align.gold")))
                        (set* "align-wrong" (or (ratio (- (c "align.n") (c "align.ok")) (c "align.pred")) 0.0)))]
              (reduce (fn [m k]
                        (if (contains? c (str "align." k ".gold"))
                          (let [n (c (str "align." k ".n")) g (c (str "align." k ".gold"))]
                            (-> m
                                (set* (str "align-acc-" k) (acc n (c (str "align." k ".ok")) g))
                                (set* (str "align-cov-" k) (ratio n g))))
                          m))
                      m ALIGN-KINDS))
            m)
        m (if (contains? c "frag.n")
            (-> m
                (set* "frag-col-acc" (ratio (c "frag.ok") (c "frag.n")))
                (prf c "join" "join"))
            m)
        m (if (contains? c "hyphen.n")
            (set* m "hyphen-acc" (ratio (c "hyphen.ok") (c "hyphen.n")))
            m)
        m (if (contains? c "contam.leak-chars")
            (set* m "watermark-leak" (or (ratio (c "contam.leak-chars") (c "contam.body-chars")) 0.0))
            m)
        m (if (contains? c "fusion.body-words")
            (set* m "ghost-rate" (or (ratio (c "fusion.ghost-words") (c "fusion.body-words")) 0.0))
            m)
        m (if (contains? c "fusion.seal-leak-chars")
            (set* m "seal-leak" (or (ratio (c "fusion.seal-leak-chars") (c "fusion.seal-body-chars")) 0.0))
            m)
        m (if (contains? c "fusion.src-chars")
            (set* m "src-ocr-share" (or (ratio (c "fusion.src-ocr-chars") (c "fusion.src-chars")) 0.0))
            m)
        m (if (contains? c "fusion.tl-chars")
            (set* m "tl-override" (or (ratio (c "fusion.tl-susp-chars") (c "fusion.tl-chars")) 0.0))
            m)
        ;; the graphics metrics (P6.8, D227): rule-p/r, fill-p/r, table-adj-p/r/f1, tag-verdict-acc
        m (G/metrics set* m c)]
    m))

(defn pool [list-of-counts]
  (reduce (fn [out c] (reduce-kv (fn [o k v] (update o k (fnil + 0) v)) out c))
          (array-map) list-of-counts))

(defn -main [& argv]
  (if (not= 2 (count argv))
    (do (println "usage: score.clj PRED.json GOLD.json") (System/exit 2))
    (let [pred (pyjson/parse-file (first argv))
          gold (pyjson/parse-file (second argv))
          r (score pred gold)]
      (println (pyjson/dumps {"metrics" (metrics (get r "counts")) "counts" (get r "counts")}
                             {:indent 1 :sort-keys true})))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
