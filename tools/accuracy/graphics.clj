(ns accuracy.graphics
  "The graphics metrics (DEVLOG P6.8, D227, SPEC 14.2 \"Graphics\", 14.3 \"Rule and fill truth\").

  Counts, not ratios, as everything in score.clj: `add-counts!` adds one document's numerators and denominators,
  `metrics` turns pooled counts into the scoreboard's names.

    rule-p, rule-r         LENGTH-weighted. A predicted `grl` stretch matches a truth rule when their angles differ by at most
                           150 centi-degrees and the stretch's end points lie within half the two thicknesses plus
                           max(1 pt, 1 px of the variant's dpi) of the truth rule's line; the matched part is the overlap along
                           the line, UNIONED over every stretch that matches (a rule found in pieces counts once, a gap is a gap,
                           no gap is bridged). rule-r = matched truth length / truth length; rule-p = matched predicted length /
                           predicted length (a stretch counts what the truth rules cover of it).
    fill-p, fill-r         the same by AREA: the part of a predicted `gfl` box inside truth boxes, and the reverse.
    table-adj-p/-r/-f1     ICDAR 2013's adjacency relations (Gobel et al.): for every non-empty cell its nearest non-empty right and
                           down neighbours, a relation (text, text, direction) with the text normalized; precision, recall and
                           F1 over the relations of the gold's tables and the Model's tables (a multiset).
    tag-verdict-acc        the share of documents whose declared tagged-table verdict (MANIFEST `facts.tag-verdict`: confirmed,
                           partial, contradicted or none) equals the dump's: the verdict of the first `tb` row that has one,
                           none when there is none.
  `twin-gap` and `tag-table-agree` are recorded, not ratcheted: `bb fusion-report`.

  WHERE THE NUMBERS COME FROM. The prediction is the evidence dump (version \"2\", SPEC 9.7): `grl` rows (`page` from 1,
  `ex0 ey0 ex1 ey1` the centre line in POINTS (the dump prints centi-units as points), `thickness`), `gfl` rows (`page`, `box`
  [x0 y0 x1 y1]), `tb` rows (`verdict`). When the names of the real dump differ, THIS FILE changes (`row-rule`, `row-fill`,
  `row-verdict`), as `fusion_report.clj` says of its own. The truth is `gold/STEM.rules.json` (tools/oracle/rules.py) for the
  born-digital document, and a scan variant inherits it through `truth-for`: the page turned (`facts.turns`, scan-ovl only), then
  skewed about its centre by `facts.skew-scan`, `facts.skew` or `facts.skew-ovl` (degrees, clockwise, ImageMagick's
  `-rotate`); a fill keeps its size and moves its centre. No dump (a predictor with none) adds nothing here.
  A document the dump has no `grl` for scores recall 0: that is what emon scores until P5.5a and P7.G."
  (:require [clojure.string :as str]
            [nfc]))

(def ANGLE-TOL 1.5)   ; degrees: 150 centi-degrees

;; ------------------------------------------------------------------ geometry

(defn- len [[x0 y0 x1 y1]] (Math/hypot (- x1 x0) (- y1 y0)))
(defn- angle [[x0 y0 x1 y1]] (Math/toDegrees (Math/atan2 (- y1 y0) (- x1 x0))))
(defn- angle-diff [a b] (let [d (mod (Math/abs (- a b)) 180.0)] (min d (- 180.0 d))))

(defn- covered
  "Length of segment `a` [x0 y0 x1 y1 t] covered by the union of the segments of `bs` that match it, `tol` in points."
  [a bs tol]
  (let [[x0 y0 x1 y1 ta] a
        l (len a)]
    (if (< l 1e-9)
      0.0
      (let [ux (/ (- x1 x0) l) uy (/ (- y1 y0) l)
            ang (angle a)
            ivs (sort-by first
                         (for [b bs
                               :let [[bx0 by0 bx1 by1 tb] b]
                               :when (< (angle-diff ang (angle b)) (+ ANGLE-TOL 1e-9))
                               :let [d0 (Math/abs (- (* ux (- by0 y0)) (* uy (- bx0 x0))))
                                     d1 (Math/abs (- (* ux (- by1 y0)) (* uy (- bx1 x0))))]
                               :when (<= (max d0 d1) (+ (/ (+ ta tb) 2.0) tol 1e-9))
                               :let [s0 (+ (* ux (- bx0 x0)) (* uy (- by0 y0)))
                                     s1 (+ (* ux (- bx1 x0)) (* uy (- by1 y0)))
                                     lo (max 0.0 (min s0 s1)) hi (min l (max s0 s1))]
                               :when (> hi lo)]
                           [lo hi]))]
        (loop [ivs ivs, cur-lo nil, cur-hi nil, total 0.0]
          (if-let [[[lo hi] & more] (seq ivs)]
            (cond (nil? cur-lo) (recur more lo hi total)
                  (<= lo cur-hi) (recur more cur-lo (max cur-hi hi) total)
                  :else (recur more lo hi (+ total (- cur-hi cur-lo))))
            (if cur-lo (+ total (- cur-hi cur-lo)) total)))))))

(defn- area [[x0 y0 x1 y1]] (* (max 0.0 (- x1 x0)) (max 0.0 (- y1 y0))))
(defn- inter [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1]]
  (* (max 0.0 (- (min ax1 bx1) (max ax0 bx0))) (max 0.0 (- (min ay1 by1) (max ay0 by0)))))

(defn- fill-covered [a bs] (min (area a) (reduce + 0.0 (map #(inter a %) bs))))

;; ------------------------------------------------------------------ the truth of a variant

(defn- rotate-pt
  "(x, y) turned clockwise by `deg` about (cx, cy) in y-down page space."
  [deg cx cy [x y]]
  (let [th (Math/toRadians deg) c (Math/cos th) s (Math/sin th) dx (- x cx) dy (- y cy)]
    [(+ cx (- (* dx c) (* dy s))) (+ cy (+ (* dx s) (* dy c)))]))

(defn- turn-pt
  "A quarter turn clockwise (90, 180, 270) of a point of a w x h page."
  [t w h [x y]]
  (case (long t) 0 [x y] 90 [(- h y) x] 180 [(- w x) (- h y)] 270 [y (- w x)]))

(defn crop-shift
  "The translation, in points, that the twins' ImageMagick `-rotate DEG -gravity center -crop WxH+0+0` adds to a content turned by `deg` degrees about the page's
  centre (P7.G, D1322): `-rotate` grows the canvas to the box of its three shears and the crop takes the middle of that canvas, but one pixel (and half a pixel of
  rounding) too far out, so the content lands at (-s, -t) pixels with s = floor((W' - W) / 2 + 1.5), t = floor((H' - H) / 2 + 1.5) for the rotated box W' x H' of
  a W x H page (measured against ImageMagick 7 at twelve angles and three sizes). Nothing when the angle is 0 (a no-op to ImageMagick) or when the page's dpi is not known."
  [deg w h dpi]
  (if (or (nil? dpi) (< (Math/abs (double deg)) 1e-9))
    [0.0 0.0]
    (let [pw (Math/round (* w (/ dpi 72.0))) ph (Math/round (* h (/ dpi 72.0)))
          th (Math/toRadians (Math/abs (double deg))) c (Math/cos th) s (Math/sin th)
          wb (+ (* pw c) (* ph s)) hb (+ (* ph c) (* pw s))
          sx (Math/floor (+ (/ (- wb pw) 2.0) 1.5)) sy (Math/floor (+ (/ (- hb ph) 2.0) 1.5))
          k (/ 72.0 dpi)]
      [(- (* sx k)) (- (* sy k))])))

(defn truth-for
  "The rule truth (a parsed rules.json: cpt) of a document, as it is in `variant`'s pages:
  {page {:rules [[x0 y0 x1 y1 thickness]] :fills [[x0 y0 x1 y1]]}} in points; `facts` is the MANIFEST entry's."
  [truth facts variant]
  (let [skews (case variant "scan" (get facts "skew-scan") "scan-color" (get facts "skew") "scan-ovl" (get facts "skew-ovl") nil)
        turns (when (= variant "scan-ovl") (get facts "turns"))
        dpi (case variant "scan" (get-in facts ["dpi" "scan"]) ("scan-color" "scan-ovl") (get-in facts ["dpi" "scan-color"]) nil)]
    (into {}
          (for [pg (get truth "pages")
                :let [n (get pg "n") w (/ (get pg "w") 100.0) h (/ (get pg "h") 100.0)
                      t (get turns (str n) 0)
                      [w h] (if (#{90 270} t) [h w] [w h])
                      deg (if skews (nth skews (dec n) 0.0) 0.0)
                      [sx sy] (crop-shift deg w h dpi)
                      mv (fn [p] (let [[x y] (rotate-pt deg (/ w 2.0) (/ h 2.0) (turn-pt t (/ (get pg "w") 100.0) (/ (get pg "h") 100.0) p))]
                                   [(+ x sx) (+ y sy)]))
                      ]]
            [n {:rules (vec (for [[x0 y0 x1 y1 th] (get pg "rules")
                                  :let [[a b] (mv [(/ x0 100.0) (/ y0 100.0)]) [c d] (mv [(/ x1 100.0) (/ y1 100.0)])]]
                              [a b c d (/ th 100.0)]))
                :fills (vec (for [[x0 y0 x1 y1] (get pg "fills")
                                  :let [[ax ay] (turn-pt t (/ (get pg "w") 100.0) (/ (get pg "h") 100.0) [(/ x0 100.0) (/ y0 100.0)])
                                        [bx by] (turn-pt t (/ (get pg "w") 100.0) (/ (get pg "h") 100.0) [(/ x1 100.0) (/ y1 100.0)])
                                        [lx hx] [(min ax bx) (max ax bx)] [ly hy] [(min ay by) (max ay by)]
                                        [mx my] (let [[x y] (rotate-pt deg (/ w 2.0) (/ h 2.0) [(/ (+ lx hx) 2) (/ (+ ly hy) 2)])] [(+ x sx) (+ y sy)])
                                        hw (/ (- hx lx) 2) hh (/ (- hy ly) 2)]]
                              [(- mx hw) (- my hh) (+ mx hw) (+ my hh)]))}]))))

(defn tolerance
  "max(1 pt, 1 px of the variant's dpi) in points."
  [facts variant]
  (let [dpi (case variant "scan" (get-in facts ["dpi" "scan"]) ("scan-color" "scan-ovl") (get-in facts ["dpi" "scan-color"]) nil)]
    (max 1.0 (if dpi (/ 72.0 dpi) 0.0))))

;; ------------------------------------------------------------------ the dump

(defn- num-of [row & ks] (some (fn [k] (let [v (get row k)] (when (number? v) v))) ks))

(defn row-rule
  "A `grl` row -> [page x0 y0 x1 y1 thickness] (points) or nil."
  [row]
  (let [x0 (num-of row "ex0" "x0") y0 (num-of row "ey0" "y0") x1 (num-of row "ex1" "x1") y1 (num-of row "ey1" "y1")]
    (when (and x0 y0 x1 y1)
      [(or (num-of row "page") 1) x0 y0 x1 y1 (or (num-of row "thickness" "t") 0.0)])))

(defn row-fill [row]
  (when-let [[x0 y0 x1 y1] (let [b (get row "box")] (when (and (sequential? b) (= 4 (count b))) b))]
    [(or (num-of row "page") 1) x0 y0 x1 y1]))

(defn row-verdict [row] (let [v (get row "verdict")] (when (#{"confirmed" "partial" "contradicted"} v) v)))

(defn dump-verdict [dump] (or (some row-verdict (get dump "tb")) "none"))

(defn oracle-dump
  "The dump a perfect emon writes: the variant's truth as `grl` and `gfl`, and the declared verdict as a `tb` row."
  [truth facts variant]
  (let [tv (when truth (truth-for truth facts variant))]
    {"grl" (vec (for [[n pg] (sort-by key tv) [x0 y0 x1 y1 t] (:rules pg)]
                  {"page" n "ex0" x0 "ey0" y0 "ex1" x1 "ey1" y1 "thickness" t}))
     "gfl" (vec (for [[n pg] (sort-by key tv) box (:fills pg)] {"page" n "box" box}))
     "tb" (let [v (get facts "tag-verdict")]
            (if (and v (not= v "none")) [{"verdict" v "tagged" true}] []))}))

;; ------------------------------------------------------------------ table adjacency (ICDAR 2013)

(defn- norm [s]
  (let [s (nfc/normalize (or s ""))
        s (str/replace s #"[\u0085   ]" " ")]
    (str/join " " (remove str/blank? (str/split s #"(?U)\s+")))))

(defn- tables [node]
  (filter #(= "table" (get % "kind")) (tree-seq map? #(get % "children") node)))

(defn- grid
  "A table node -> {[r c] cell-index} and the cells [{:r :c :rs :cs :text}] (HTML's placement of spanning cells)."
  [tbl]
  (let [rows (for [rg (get tbl "children") :when (= "rowgroup" (get rg "kind")) row (get rg "children") :when (= "row" (get row "kind"))] row)]
    (loop [rows (seq rows), r 0, occ {}, cells []]
      (if-not rows
        [occ cells]
        (let [[occ cells]
              (loop [cs (seq (filter #(= "cell" (get % "kind")) (get (first rows) "children"))), c 0, occ occ, cells cells]
                (if-not cs
                  [occ cells]
                  (let [c (loop [c c] (if (contains? occ [r c]) (recur (inc c)) c))
                        cell (first cs) rs (max 1 (get cell "rowspan" 1)) cw (max 1 (get cell "colspan" 1))
                        i (count cells)
                        occ (reduce #(assoc %1 %2 i) occ (for [dr (range rs) dc (range cw)] [(+ r dr) (+ c dc)]))]
                    (recur (next cs) (+ c cw) occ (conj cells {:r r :c c :rs rs :cs cw :text (norm (get cell "text"))})))))]
          (recur (next rows) (inc r) occ cells))))))

(defn relations
  "The adjacency relations of the tables under `body`, as a vector of [text text direction]."
  [body]
  (vec (for [t (tables body)
             :let [[occ cells] (grid t)
                   maxr (apply max 0 (map (fn [[[r _] _]] r) occ))
                   maxc (apply max 0 (map (fn [[[_ c] _]] c) occ))]
             [i cell] (map-indexed vector cells)
             :when (seq (:text cell))
             [dir cands] [["right" (for [c (range (+ (:c cell) (:cs cell)) (inc maxc))] (get occ [(:r cell) c]))]
                          ["down" (for [r (range (+ (:r cell) (:rs cell)) (inc maxr))] (get occ [r (:c cell)]))]]
             :let [nb (first (filter #(and % (not= % i) (seq (:text (nth cells %)))) cands))]
             :when nb]
         [(:text cell) (:text (nth cells nb)) dir])))

;; ------------------------------------------------------------------ counts and metrics

(defn rule-counts
  "{\"gfx.rule.gold\" ..} from the truth {page {:rules :fills}} and the dump's rows."
  [tv dump tol]
  (let [pr (group-by first (keep row-rule (get dump "grl")))
        pf (group-by first (keep row-fill (get dump "gfl")))
        pages (sort (distinct (concat (keys tv) (keys pr) (keys pf))))
        rules-of (fn [rows] (mapv (fn [[_ & s]] (vec s)) rows))
        acc (reduce
             (fn [a n]
               (let [t (get-in tv [n :rules] []) p (rules-of (get pr n))
                     tf (get-in tv [n :fills] []) pfb (mapv (fn [[_ & b]] (vec b)) (get pf n))]
                 (-> a
                     (update :rg + (reduce + 0.0 (map len t)))
                     (update :rgo + (reduce + 0.0 (map #(covered % p tol) t)))
                     (update :rp + (reduce + 0.0 (map len p)))
                     (update :rpo + (reduce + 0.0 (map #(covered % t tol) p)))
                     (update :fg + (reduce + 0.0 (map area tf)))
                     (update :fgo + (reduce + 0.0 (map #(fill-covered % pfb) tf)))
                     (update :fp + (reduce + 0.0 (map area pfb)))
                     (update :fpo + (reduce + 0.0 (map #(fill-covered % tf) pfb))))))
             {:rg 0.0 :rgo 0.0 :rp 0.0 :rpo 0.0 :fg 0.0 :fgo 0.0 :fp 0.0 :fpo 0.0} pages)]
    {"gfx.rule.gold" (:rg acc) "gfx.rule.gold-ok" (:rgo acc) "gfx.rule.pred" (:rp acc) "gfx.rule.pred-ok" (:rpo acc)
     "gfx.fill.gold" (:fg acc) "gfx.fill.gold-ok" (:fgo acc) "gfx.fill.pred" (:fp acc) "gfx.fill.pred-ok" (:fpo acc)}))

;; ------------------------------------------------------------------ rule ghosts (P7.G, D1325)

(def RULE-MARKS #"^[|¦│┃_‗—–=~\\/]{1,3}$")

(defn- body-words
  "The words of every leaf text of a body."
  [body]
  (vec (for [n (tree-seq map? #(get % "children") body)
             :when (and (string? (get n "text")) (empty? (get n "children")))
             w (str/split (str/trim (get n "text")) #"\s+")
             :when (seq w)]
         w)))

(defn ghost-counts
  "{words, ghosts}: the words of the prediction's body, and those of them that are only rule marks (| _ — =) beyond the gold's own count of such words:
  a border read by the engine as a letter, that fusion should have kept out of the body (row 601)."
  [pred gold]
  (let [pw (body-words (get pred "body"))
        gw (body-words (get-in gold ["model" "body"]))
        marks (fn [ws] (count (filter #(re-matches RULE-MARKS %) ws)))]
    {:words (count pw) :ghosts (max 0 (- (marks pw) (marks gw)))}))

(defn add-counts!
  "Adds one document's graphics counts through `add` (k v). `opts`: :variant, :rules (parsed rules.json or nil), :facts (the
  MANIFEST entry's facts), :dump (a parsed dump, or nil/:none for a predictor with none)."
  [add pred gold {:keys [variant rules facts dump] :as opts}]
  ;; rule ghosts: on the documents whose rules are measured
  (when rules
    (let [{:keys [words ghosts]} (ghost-counts pred gold)]
      (add "gfx.ghost.words" words)
      (add "gfx.ghost.n" ghosts)))
  ;; table adjacency: whenever the gold annotates tables
  (when (some #{"table"} (get-in gold ["annotated" "structure"]))
    (let [g (relations (get-in gold ["model" "body"])) p (relations (get pred "body"))
          fg (frequencies g) fp (frequencies p)]
      (add "gfx.adj.tp" (reduce + 0 (for [[r n] fg] (min n (get fp r 0)))))
      (add "gfx.adj.gold" (count g)) (add "gfx.adj.pred" (count p))))
  (when (and dump (not= dump :none))
    (when rules
      (doseq [[k v] (rule-counts (truth-for rules facts variant) dump (tolerance facts variant))] (add k v)))
    (when-let [v (get facts "tag-verdict")]
      (add "gfx.tag.n" 1)
      (add "gfx.tag.ok" (if (= v (dump-verdict dump)) 1 0)))))

(defn- pr
  "precision and recall of `pre`.gold/.pred/.gold-ok/.pred-ok, as score.clj's `prf`: nothing predicted scores 0 against a
  gold that has something, 1 against one that has nothing."
  [set* m c pre pk rk]
  (if-not (or (contains? c (str pre ".gold")) (contains? c (str pre ".pred")))
    m
    (let [g (get c (str pre ".gold") 0) p (get c (str pre ".pred") 0)
          go (get c (str pre ".gold-ok") 0) po (get c (str pre ".pred-ok") 0)]
      (-> m
          (set* pk (if (zero? p) (if (zero? g) 1.0 0.0) (/ (double po) p)))
          (set* rk (if (zero? g) (if (zero? p) 1.0 0.0) (/ (double go) g)))))))

(defn metrics
  "Adds the graphics metrics of pooled counts `c` to `m` through `set*` (m k v)."
  [set* m c]
  (let [m (pr set* m c "gfx.rule" "rule-p" "rule-r")
        m (pr set* m c "gfx.fill" "fill-p" "fill-r")
        m (if (contains? c "gfx.adj.gold")
            (let [g (c "gfx.adj.gold") p (c "gfx.adj.pred") tp (c "gfx.adj.tp")
                  pp (if (zero? p) (if (zero? g) 1.0 0.0) (/ (double tp) p))
                  rr (if (zero? g) (if (zero? p) 1.0 0.0) (/ (double tp) g))]
              (-> m (set* "table-adj-p" pp) (set* "table-adj-r" rr)
                  (set* "table-adj-f1" (if (zero? (+ pp rr)) 0.0 (/ (* 2 pp rr) (+ pp rr))))))
            m)
        m (if (contains? c "gfx.tag.n") (set* m "tag-verdict-acc" (/ (double (c "gfx.tag.ok")) (c "gfx.tag.n"))) m)
        m (if (pos? (get c "gfx.ghost.words" 0)) (set* m "rule-ghost" (/ (double (c "gfx.ghost.n")) (c "gfx.ghost.words"))) m)]
    m))
