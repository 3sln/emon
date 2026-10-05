(ns accuracy.test-graphics
  "Laws of the graphics metrics (DEVLOG P6.8, D227, SPEC 14.2).   bb unit runs this:
     bb -cp tools:tools/lib tools/accuracy/test_graphics.clj

Every number is worked out by hand in the comment beside it, from hand-written truth, dumps and golds; none is copied from
the scorer. The last group reads the committed corpus: the oracle predictor (the dump a perfect emon writes) is perfect on
every document of the GRAPHICS set in every variant it has, and a damaged dump is not."
  (:require [accuracy.accuracy :as A]
            [accuracy.graphics :as G]
            [accuracy.predict-oracle :as O]
            [clojure.string :as str]
            [accuracy.score :as S]
            [clojure.test :refer [deftest is run-tests]]
            [pyjson]))

(defn- almost= [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

;; a truth in rules.json's form: centi-points, page 1 of 100 x 100 pt
(defn truth [rules & [fills]]
  {"pages" [{"n" 1 "w" 10000 "h" 10000
             "rules" (mapv (fn [[x0 y0 x1 y1 t]] [(* 100 x0) (* 100 y0) (* 100 x1) (* 100 y1) (* 100 t) "solid"]) rules)
             "fills" (mapv (fn [[x0 y0 x1 y1]] [(* 100 x0) (* 100 y0) (* 100 x1) (* 100 y1) "#dddddd"]) (or fills []))}]})

(defn dump [rules & [fills]]
  {"grl" (mapv (fn [[x0 y0 x1 y1 t]] {"page" 1 "ex0" x0 "ey0" y0 "ex1" x1 "ey1" y1 "thickness" t}) rules)
   "gfl" (mapv (fn [b] {"page" 1 "box" (vec b)}) (or fills []))})

(defn run
  "Counts -> metrics of one document: truth rules/fills against a dump, no tables, no verdict."
  [tr d & [{:keys [variant facts] :or {variant "pdf" facts {}}}]]
  (let [g {"annotated" {"structure" []} "model" {"body" {"id" 0 "kind" "document"}}}
        c (get (S/score {"body" {"id" 0 "kind" "document"}} g {:variant variant :rules tr :facts facts :dump d}) "counts")]
    (S/metrics c)))

;; ------------------------------------------------------------------ rules: length weighted

(deftest rule-found-whole-and-in-part
  ;; truth: one rule 100 pt long. Predicted: its first half. Recall = 50 / 100; precision = 50 / 50.
  (let [m (run (truth [[0 50 100 50 1]]) (dump [[0 50 50 50 1]]))]
    (is (almost= 0.5 (m "rule-r")))
    (is (almost= 1.0 (m "rule-p"))))
  ;; exact
  (let [m (run (truth [[0 50 100 50 1]]) (dump [[0 50 100 50 1]]))]
    (is (almost= 1.0 (m "rule-r"))) (is (almost= 1.0 (m "rule-p")))))

(deftest rule-length-weighting-and-false-positives
  ;; truth: a 100 pt rule and a 200 pt rule. Predicted: the first whole, 50 pt of the second, and a 150 pt rule the truth
  ;; does not have. Recall = (100 + 50) / 300 = 1/2. Precision = (100 + 50 + 0) / (100 + 50 + 150) = 1/2.
  (let [m (run (truth [[0 10 100 10 1] [0 60 200 60 1]])
               (dump [[0 10 100 10 1] [0 60 50 60 1] [0 90 150 90 1]]))]
    (is (almost= 0.5 (m "rule-r")))
    (is (almost= 0.5 (m "rule-p")))))

(deftest rule-pieces-are-unioned-and-gaps-are-not-bridged
  ;; a dashed rule of 100 pt predicted as [0,30] and [40,70]: 60 of 100 covered; precision 1. Overlapping pieces count once:
  ;; [0,60] and [40,100] cover all 100 (the union), recall 1, and their 120 pt are all inside the truth, precision 1.
  (let [m (run (truth [[0 50 100 50 1]]) (dump [[0 50 30 50 1] [40 50 70 50 1]]))]
    (is (almost= 0.6 (m "rule-r"))) (is (almost= 1.0 (m "rule-p"))))
  (let [m (run (truth [[0 50 100 50 1]]) (dump [[0 50 60 50 1] [40 50 100 50 1]]))]
    (is (almost= 1.0 (m "rule-r"))) (is (almost= 1.0 (m "rule-p")))))

(deftest rule-needs-position-and-angle
  ;; thicknesses 1 and 1, dpi unknown (pdf): the tolerance is (1 + 1) / 2 + max(1 pt, none) = 2 pt.
  (is (almost= 1.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 52 100 52 1]])) "rule-r")))     ; 2 pt off: inside
  (is (almost= 0.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 52.5 100 52.5 1]])) "rule-r"))) ; 2.5 pt off: outside
  ;; angle: 100 pt long, 2 pt rise is 1.146 degrees (inside 1.5), 3 pt rise 1.718 (outside); both end points within 2 pt for
  ;; the first, the second is also 3 pt away at one end
  (is (almost= 1.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 50 100 51.5 1]])) "rule-r")))  ; 0.86 degrees, 1.5 pt
  (is (almost= 0.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 50 100 53 1]])) "rule-r")))
  ;; a vertical never matches a horizontal
  (is (almost= 0.0 ((run (truth [[50 0 50 100 1]]) (dump [[0 50 100 50 1]])) "rule-r")))
  ;; thickness widens the tolerance: (1 + 5) / 2 + 1 = 4 pt
  (is (almost= 1.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 53.5 100 53.5 5]])) "rule-r"))))

(deftest rule-tolerance-grows-with-the-pixel
  ;; a scan at 72 dpi: 1 px = 1 pt; at 36 dpi 1 px = 2 pt, so the tolerance is 1 + 2 = 3 pt: 2.5 pt off now matches
  (is (almost= 1.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 52.5 100 52.5 1]])
                         {:variant "scan" :facts {"dpi" {"scan" 36}}}) "rule-r")))
  (is (almost= 0.0 ((run (truth [[0 50 100 50 1]]) (dump [[0 52.5 100 52.5 1]])
                         {:variant "scan" :facts {"dpi" {"scan" 150}}}) "rule-r"))))

(deftest nothing-predicted-scores-zero-and-nothing-to-find-scores-one
  (let [m (run (truth [[0 50 100 50 1]] [[0 0 10 10]]) {"grl" [] "gfl" []})]
    (is (almost= 0.0 (m "rule-r"))) (is (almost= 0.0 (m "rule-p")))
    (is (almost= 0.0 (m "fill-r"))) (is (almost= 0.0 (m "fill-p"))))
  ;; a document with no rules (a whitespace table): a rule predicted is all false positive; none predicted is perfect
  (is (almost= 0.0 ((run (truth []) (dump [[0 50 100 50 1]])) "rule-p")))
  (is (almost= 1.0 ((run (truth []) (dump [])) "rule-p"))))

;; ------------------------------------------------------------------ fills: by area

(deftest fill-by-area
  ;; truth box 10 x 10 = 100. Predicted 10 x 5 (inside): recall 50 / 100, precision 50 / 50.
  (let [m (run (truth [] [[0 0 10 10]]) (dump [] [[0 0 10 5]]))]
    (is (almost= 0.5 (m "fill-r"))) (is (almost= 1.0 (m "fill-p"))))
  ;; predicted 10 x 20 = 200 covering the truth: recall 1, precision 100 / 200
  (let [m (run (truth [] [[0 0 10 10]]) (dump [] [[0 0 10 20]]))]
    (is (almost= 1.0 (m "fill-r"))) (is (almost= 0.5 (m "fill-p"))))
  ;; two truth boxes (a band and a header): 100 + 300; one predicted box covers the 300 and half of the 100:
  ;; recall (50 + 300) / 400 = 7/8; the predicted 50 + 300 = 350 lies wholly inside the truth: precision 1
  (let [m (run (truth [] [[0 0 10 10] [20 0 50 10]]) (dump [] [[0 0 10 5] [20 0 50 10]]))]
    (is (almost= 0.875 (m "fill-r"))) (is (almost= 1.0 (m "fill-p")))))

;; ------------------------------------------------------------------ the variants' truth

(deftest scan-truth-is-the-twins-through-skew-and-turns
  (let [t (truth [[60 50 90 50 1]] [[40 40 60 60]])
        facts {"skew-scan" [0.0] "skew" [90.0] "skew-ovl" [0.0] "turns" {"1" 90}}
        pdf (G/truth-for t facts "pdf")
        s0 (G/truth-for t facts "scan")
        c90 (G/truth-for t facts "scan-color")
        ovl (G/truth-for t facts "scan-ovl")]
    ;; pdf: as written, in points
    (is (= [[60.0 50.0 90.0 50.0 1.0]] (get-in pdf [1 :rules])))
    ;; a zero skew changes nothing
    (is (= (get-in pdf [1 :rules]) (get-in s0 [1 :rules])))
    ;; 90 degrees clockwise about (50, 50), y down: (60, 50): dx 10, dy 0 -> (50 + 0, 50 + 10) = (50, 60); (90, 50) -> (50, 90)
    (let [[x0 y0 x1 y1 th] (first (get-in c90 [1 :rules]))]
      (is (almost= 50 x0)) (is (almost= 60 y0)) (is (almost= 50 x1)) (is (almost= 90 y1)) (is (almost= 1 th)))
    ;; a fill keeps its size and moves its centre: the box centred on (50, 50) stays where it is
    (is (every? true? (map almost= [40 40 60 60] (first (get-in c90 [1 :fills])))))
    ;; a quarter turn of a 100 x 100 page: (x, y) -> (h - y, x): (60, 50) -> (50, 60), (90, 50) -> (50, 90)
    (let [[x0 y0 x1 y1] (first (get-in ovl [1 :rules]))]
      (is (almost= 50 x0)) (is (almost= 60 y0)) (is (almost= 50 x1)) (is (almost= 90 y1)))
    ;; 0.7 degrees: (60, 50) -> (50 + 10 cos, 50 + 10 sin) = (59.99925, 50.12217)
    (let [[x0 y0] (first (get-in (G/truth-for t {"skew-scan" [0.7]} "scan") [1 :rules]))]
      (is (< (Math/abs (- x0 59.99925)) 1e-4)) (is (< (Math/abs (- y0 50.12217)) 1e-4)))))

;; ------------------------------------------------------------------ table adjacency

(defn cell [t & {:as a}] (merge {"kind" "cell" "text" t} (update-keys a name)))
(defn row [& cs] {"kind" "row" "children" (vec cs)})
(defn tbl [& rows] {"kind" "table" "children" [{"kind" "rowgroup" "role" "body" "children" (vec rows)}]})
(defn body [& children] {"id" 0 "kind" "document" "children" (vec children)})

(defn adj [gold-tbl pred-tbl]
  (let [g {"annotated" {"structure" ["table"]} "model" {"body" (body gold-tbl)}}
        c (get (S/score {"body" (body pred-tbl)} g {:variant "pdf"}) "counts")]
    (S/metrics c)))

(deftest adjacency-relations-by-hand
  ;; a b / c d: a-b right, a-c down, b-d down, c-d right: four
  (is (= #{["a" "b" "right"] ["a" "c" "down"] ["b" "d" "down"] ["c" "d" "right"]}
         (set (G/relations (body (tbl (row (cell "a") (cell "b")) (row (cell "c") (cell "d"))))))))
  ;; the nearest NON-EMPTY neighbour: a . b -> a-b right, however many empty cells lie between
  (is (= [["a" "b" "right"]] (G/relations (body (tbl (row (cell "a") (cell "") (cell "b")))))))
  ;; spans: a (colspan 2) over b c: a's down neighbour is b (the first non-empty below its left column); b-c right;
  ;; a has no right neighbour on its own row. Relations: a-b down, b-c right.
  (is (= #{["a" "b" "down"] ["b" "c" "right"]}
         (set (G/relations (body (tbl (row (cell "a" :colspan 2)) (row (cell "b") (cell "c"))))))))
  ;; text is normalized: runs of white space are one space, ends trimmed
  (is (= [["x y" "z" "right"]] (G/relations (body (tbl (row (cell "  x \n y ") (cell "z"))))))))

(deftest adjacency-f1
  ;; gold a b / c d (4 relations); the prediction swaps d for e: a-b, a-c survive; b-d, c-d do not; it adds b-e, c-e.
  ;; tp 2, precision 2/4, recall 2/4, F1 1/2.
  (let [m (adj (tbl (row (cell "a") (cell "b")) (row (cell "c") (cell "d")))
               (tbl (row (cell "a") (cell "b")) (row (cell "c") (cell "e"))))]
    (is (almost= 0.5 (m "table-adj-p"))) (is (almost= 0.5 (m "table-adj-r"))) (is (almost= 0.5 (m "table-adj-f1"))))
  ;; the same table: perfect. A prediction with no table: recall 0, precision 0 (nothing predicted, something to find).
  (let [t (tbl (row (cell "a") (cell "b")) (row (cell "c") (cell "d")))]
    (is (almost= 1.0 ((adj t t) "table-adj-f1")))
    (is (almost= 0.0 ((adj t {"kind" "paragraph" "text" "a b c d"}) "table-adj-r"))))
  ;; one table split in two rows of the prediction: gold a b / c d against a b and c d as two tables: a-b, c-d survive: tp 2 of
  ;; 4 gold, of 2 predicted: precision 1, recall 1/2, F1 2 * 1 * .5 / 1.5 = 2/3
  (let [g {"annotated" {"structure" ["table"]} "model" {"body" (body (tbl (row (cell "a") (cell "b")) (row (cell "c") (cell "d"))))}}
        m (S/metrics (get (S/score {"body" (body (tbl (row (cell "a") (cell "b"))) (tbl (row (cell "c") (cell "d"))))} g {:variant "pdf"}) "counts"))]
    (is (almost= 1.0 (m "table-adj-p"))) (is (almost= 0.5 (m "table-adj-r"))) (is (almost= (/ 2.0 3) (m "table-adj-f1")))))

;; ------------------------------------------------------------------ tagged verdicts

(defn verdicts [pairs]
  ;; pairs: [[declared dump-tb-rows]]: pooled counts over documents
  (let [g {"annotated" {"structure" []} "model" {"body" {"id" 0 "kind" "document"}}}
        cs (for [[v tb] pairs]
             (get (S/score {"body" {"id" 0 "kind" "document"}} g {:variant "pdf" :facts {"tag-verdict" v} :dump {"tb" tb}}) "counts"))]
    (S/metrics (S/pool cs))))

(deftest tag-verdict-accuracy
  ;; three documents: confirmed/confirmed (right), none declared and no verdict row (right), partial declared but the dump says
  ;; contradicted (wrong): 2 / 3
  (is (almost= (/ 2.0 3) ((verdicts [["confirmed" [{"verdict" "confirmed"}]] ["none" []] ["partial" [{"verdict" "contradicted"}]]])
                          "tag-verdict-acc")))
  ;; a verdict row where none was declared is wrong; the first row with a verdict decides; rows without one are skipped
  (is (almost= 0.0 ((verdicts [["none" [{"verdict" "partial"}]]]) "tag-verdict-acc")))
  (is (almost= 1.0 ((verdicts [["contradicted" [{"score" 1} {"verdict" "contradicted"} {"verdict" "confirmed"}]]]) "tag-verdict-acc")))
  ;; a document with no declared verdict is not counted at all
  (is (nil? ((verdicts []) "tag-verdict-acc"))))

;; ------------------------------------------------------------------ the committed corpus

(defn graphics-ids [] (vec (remove #(or (= "" %) (.startsWith ^String % "#")) (map #(.trim ^String %) (str/split-lines (slurp (str A/CORPUS "/GRAPHICS")))))))

(deftest every-graphics-document-has-truth-and-twins
  (let [man @A/MANIFEST]
    (doseq [id (graphics-ids)]
      (is (.exists (java.io.File. (str A/CORPUS "/gold/" id ".rules.json"))) (str id " has rule truth"))
      (let [files (get-in man ["docs" id "files"])]
        (is (contains? files "pdf") id)))))

(deftest oracle-is-perfect-and-a-damaged-dump-is-not
  (let [man @A/MANIFEST
        ids (set (graphics-ids))
        ttab (filter #(.startsWith ^String % "ttab-") (keys (get man "docs")))
        docs (for [[id variant inp gp] (A/corpus) :when (or (ids id) (.startsWith ^String id "ttab-"))] [id variant inp gp])
        variants (frequencies (map second (filter (fn [[id]] (ids id)) docs)))]
    ;; the GRAPHICS set has scan twins and colour twins
    (is (pos? (get variants "scan" 0))) (is (pos? (get variants "scan-color" 0)))
    (doseq [[id variant _ gp] docs]
      (let [g (A/doc-graphics id)
            gold (pyjson/parse-file gp)
            c (get (S/score (O/predict-gold gold) gold {:variant variant :rules (:rules g) :facts (:facts g)
                                                            :dump (G/oracle-dump (:rules g) (:facts g) variant)}) "counts")
            m (S/metrics c)]
        (doseq [k ["rule-p" "rule-r" "fill-p" "fill-r" "tag-verdict-acc"] :when (contains? m k)]
          (is (almost= 1.0 (m k)) (str variant "/" id " " k)))
        ;; a damaged dump (every second rule dropped, every fill dropped) scores below 1 wherever there is something to find
        (when (and (:rules g) (> (count (filter #(seq (get % "rules")) (get (:rules g) "pages"))) 0) (pos? (get c "gfx.rule.gold" 0)))
          (let [d (G/oracle-dump (:rules g) (:facts g) variant)
                d (assoc d "grl" (vec (take-nth 2 (get d "grl"))) "gfl" [])
                m2 (S/metrics (get (S/score (O/predict-gold gold) gold {:variant variant :rules (:rules g) :facts (:facts g) :dump d}) "counts"))]
            (is (< (m2 "rule-r") 1.0) (str variant "/" id))
            (when (pos? (get c "gfx.fill.gold" 0)) (is (almost= 0.0 (m2 "fill-r"))))))))
    ;; every tagged-table fixture declares one of the four verdicts and the untagged twins declare none
    (doseq [id ttab]
      (let [v (get-in man ["docs" id "facts" "tag-verdict"])]
        (is (#{"confirmed" "partial" "contradicted" "none"} v) id)
        (when (.endsWith ^String id "-untagged") (is (= "none" v) id))))))

(let [res (binding [clojure.test/*test-out* *err*]
            (run-tests (quote accuracy.test-graphics)))]
  (if (zero? (+ (:fail res) (:error res)))
    (println (format "  ok   graphics-test: %d laws of the graphics metrics hold (rule and fill P/R by length and area, tolerance, twins' truth, ICDAR adjacency, tagged verdicts, oracle perfect, damaged dump worse)" (:test res)))
    (do (println (format "  FAIL graphics-test: %d failure(s)" (+ (:fail res) (:error res))))
        (System/exit 1))))
