(ns accuracy.test-calib
  "Laws of the calibration arithmetic, the outcomes and the fit (DEVLOG P10.1, SPEC 9.6).   bb unit runs this:
     bb -cp tools:tools/lib tools/accuracy/test_calib.clj

Every expected number is worked out by hand in a comment beside it."
  (:require [accuracy.calib :as C]
            [accuracy.confrows :as CR]
            [calibration.calibration :as CA]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(defn- almost= [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

;; ------------------------------------------------------------------ the arithmetic ----

(deftest bins-are-ten-equal-and-the-last-takes-1000
  (is (= [0 0 1 9 9 9] (map C/bin-of [0 99 100 900 999 1000]))))

(deftest ece-and-brier-by-hand
  ;; four rows of confidence 800, three right and one wrong: bin 8 holds n 4, sum conf 3200, sum y 3000.
  ;; ECE = |3000 - 3200| / (1000 * 4) = 0.05.  Brier = (200^2 * 3 + 800^2) / 4 / 1e6 = 760000 / 4e6 = 0.19
  (let [c (C/row-counts [["heading" "text" 800 1000] ["heading" "text" 800 1000] ["heading" "text" 800 1000] ["heading" "text" 800 0]])]
    (is (= [[8 4 3200 3000]] (C/bins-of c "heading")))
    (is (almost= 0.05 (C/ece c "heading")))
    (is (almost= 0.19 (C/brier c "heading")))
    (is (= ["all" "heading" "heading/text"] (C/units-in c)))))

(deftest ece-weighs-bins-by-their-size
  ;; bin 1 (conf 150): 4 rows, 1 right: sum conf 600, sum y 1000, |1000 - 600| = 400.
  ;; bin 9 (conf 950): 6 rows, 6 right: sum conf 5700, sum y 6000, |6000 - 5700| = 300.
  ;; ECE = (400 + 300) / (1000 * 10) = 0.07
  (let [c (C/row-counts (concat (repeat 3 ["x" "text" 150 0]) [["x" "text" 150 1000]]
                                (repeat 6 ["x" "text" 950 1000])))]
    (is (= 10 (C/unit-n c "x")))
    (is (almost= 0.07 (C/ece c "x")))))

(deftest counts-of-two-documents-pool-by-addition
  (let [a (C/row-counts [["a" "text" 500 1000] ["a" "text" 500 0]])
        b (C/row-counts [["a" "text" 500 1000]])
        both (C/row-counts [["a" "text" 500 1000] ["a" "text" 500 0] ["a" "text" 500 1000]])]
    (is (= both (merge-with + a b)))))

(deftest a-perfectly-calibrated-set-has-no-error
  ;; 10 rows of 700, 7 right
  (let [c (C/row-counts (concat (repeat 7 ["k" "ocr" 700 1000]) (repeat 3 ["k" "ocr" 700 0])))]
    (is (almost= 0.0 (C/ece c "k")))
    ;; Brier = (300^2 * 7 + 700^2 * 3) / 10 / 1e6 = (630000 + 1470000) / 1e7 = 0.21
    (is (almost= 0.21 (C/brier c "k")))))

(deftest metric-names
  (is (= "heading-ocr" (C/unit-metric-name "heading/ocr")))
  (let [m (C/metrics (C/row-counts [["heading" "ocr" 500 1000]]))]
    (is (= #{"conf-ece-all" "conf-brier-all" "conf-ece-heading" "conf-brier-heading" "conf-ece-heading-ocr" "conf-brier-heading-ocr"}
           (set (keys m)))))
  (is (C/lower-better? ":conf-ece-heading"))
  (is (C/lower-better? "conf-brier-paragraph-ocr"))
  (is (not (C/lower-better? "heading-f1"))))

;; ------------------------------------------------------------------ isotonic ----

(deftest pav-pools-violators-and-ties
  ;; means 500, 900, 800: the last two violate; pooled (9000 + 8000) / 20 = 850
  (is (= [[500 500 10 5000] [600 700 20 17000]] (C/pav [[500 10 5000] [600 10 9000] [700 10 8000]])))
  ;; equal means pool: a step that says what the one before it says is no step
  (is (= [[500 600 20 18000]] (C/pav [[500 10 9000] [600 10 9000]])))
  ;; already monotone: untouched
  (is (= [[100 100 4 1000] [200 200 4 2000] [300 300 4 3000]] (C/pav [[100 4 1000] [200 4 2000] [300 4 3000]])))
  ;; a violation at the front pools backwards: means 800, 700, 600 all pool
  (is (= [[1 3 30 21000]] (C/pav [[1 10 8000] [2 10 7000] [3 10 6000]]))))

;; ------------------------------------------------------------------ the fit ----

(defn- rows [kind class conf ys]
  (for [[i y] (map-indexed vector ys)] {:kind kind :class class :conf conf :y y :id (str kind i)}))

(deftest the-fit-is-laplace-smoothed-isotonic-per-kind-and-class
  ;; 8 right of 8 at raw 500: (8 + 1) / (8 + 2) = 0.9 -> 900
  (let [f (CA/fit-base (rows "heading" "text" 500 (repeat 8 1000)))]
    (is (= {["heading" "any"] [[500 900 8]]} (into {} f))))
  ;; fewer than MIN-N rows of a kind: no fit, the confidence goes out as it is
  (is (= {} (into {} (CA/fit-base (rows "heading" "text" 500 (repeat 7 1000))))))
  ;; a class that says what its kind says is no row; one that differs by more than CLASS-TOL is
  (let [f (CA/fit-base (concat (rows "paragraph" "text" 500 (concat (repeat 40 1000) (repeat 10 0)))
                          (rows "paragraph" "ocr" 500 (concat (repeat 5 1000) (repeat 5 0)))))]
    ;; any: 45 of 60 -> (45 + 1) / 62 = 0.7419 -> 742; ocr: 5 of 10 -> 6 / 12 = 0.5 -> 500; text: 40 of 50 -> 41 / 52 = 0.788 -> 788 (46 off 742: a row)
    (is (= [["paragraph" "any"] ["paragraph" "ocr"] ["paragraph" "text"]] (keys f)))
    (is (= [[500 742 60]] (f ["paragraph" "any"])))
    (is (= [[500 500 10]] (f ["paragraph" "ocr"])))))

(deftest a-map-is-kept-only-where-it-holds-on-documents-it-has-not-seen
  ;; 400 documents of one row each, 360 right (0.9) at raw 500: the raw error is 0.4, a map fitted on the other folds
  ;; is true on this one (about 0.9): kept
  (let [kept (CA/fit (rows "heading" "text" 500 (concat (repeat 360 1000) (repeat 40 0))))]
    (is (= ["heading"] (distinct (map first (keys kept))))))
  ;; two documents, one all right and one all wrong, at raw 500: the raw error is already 0 (half of them are right) and
  ;; a map fitted on the other document says 0 or 1: no gain, the kind keeps the rule's own confidence
  (let [two (concat (for [_ (range 20)] {:kind "note" :class "infer" :conf 500 :y 1000 :id "a"})
                    (for [_ (range 20)] {:kind "note" :class "infer" :conf 500 :y 0 :id "b"}))]
    (is (= {} (into {} (CA/fit two))))))

(deftest the-lookup-clamps-and-falls-back
  (let [f {["table" "any"] [[500 800 10] [700 950 10]] ["table" "ocr"] [[600 700 10]]}]
    (is (= 800 (CA/apply-fit f "table" "text" 500)))     ; the kind's steps
    (is (= 800 (CA/apply-fit f "table" "text" 100)))     ; below the first step: the first
    (is (= 800 (CA/apply-fit f "table" "text" 699)))
    (is (= 950 (CA/apply-fit f "table" "text" 700)))
    (is (= 950 (CA/apply-fit f "table" "text" 1000)))    ; above the last: the last
    (is (= 700 (CA/apply-fit f "table" "ocr" 100)))      ; a class with a row of its own
    (is (= 321 (CA/apply-fit f "figure" "text" 321)))))  ; no row for the kind: as it is

(deftest the-kin-table-is-sorted-and-in-codes
  (let [f (sorted-map ["paragraph" "ocr"] [[500 500 10]] ["heading" "any"] [[500 900 8]] ["dropped" "any"] [[600 984 61]])
        s (CA/kin-table f)]
    ;; heading 3, paragraph 4 (kin/model.kin), the ledger 100; class any -1, ocr 1
    (is (str/includes? s ":data [3 -1 500 900\n         4 1 500 500\n         100 -1 600 984]"))))

;; ------------------------------------------------------------------ what 'right' is ----

(def ALL ["section" "heading" "paragraph" "list" "item" "table" "rowgroup" "row"
          "cell" "caption" "figure" "seal" "quote" "code" "note"])

(defn gold [children & {:as over}]
  {"annotated" (merge {"structure" ALL "attributes" {"heading" ["level"]} "text" true "furniture" false "dropped" [] "pages" ["n"]}
                      (update-keys over name))
   "model" {"pages" [{"n" 1}] "body" {"id" 0 "kind" "document" "children" children}}})

(defn pred [children]
  {"pages" [{"n" 1}] "body" {"id" 0 "kind" "document" "children" children}})

(defn P [t & {:as m}] (merge {"kind" "paragraph" "text" t} (update-keys m name)))

(deftest a-block-is-right-when-the-gold-has-it-with-its-text
  (let [g (gold [{"kind" "heading" "level" 1 "text" "Introduction"} (P "Hello wide world and so on")])
        p (pred [{"kind" "heading" "level" 1 "text" "Introduction" "src" ["text" "infer"] "conf" 500}
                 (P "Something else altogether, nothing alike" :src ["ocr"] :conf 400)])]
    (is (= [["heading" "text" 500 1000] ["paragraph" "ocr" 400 0]] (CR/rows p g)))))

(deftest a-heading-needs-its-level-when-the-gold-annotates-it
  (let [g (gold [{"kind" "heading" "level" 2 "text" "Methods"}])
        p (pred [{"kind" "heading" "level" 1 "text" "Methods" "src" ["text"] "conf" 500}])]
    (is (= [["heading" "text" 500 0]] (CR/rows p g)))))

(deftest a-node-with-no-conf-has-no-row-and-a-kind-the-gold-does-not-annotate-none
  (let [g (assoc-in (gold [(P "Hello")]) ["annotated" "structure"] ["paragraph"])
        p (pred [(P "Hello") {"kind" "heading" "level" 1 "text" "Hello" "conf" 500}])]
    (is (= [] (CR/rows p g)))))

(deftest a-text-run-is-right-by-its-words
  ;; the gold block says "alpha beta gamma delta epsilon zeta eta theta iota kappa"; the runs are the first four words
  ;; (all right) and the other six with `zetta` for `zeta` (5 of 6: 833 per mille); the block is one letter off in 55: right
  (let [g (gold [(P "alpha beta gamma delta epsilon zeta eta theta iota kappa")])
        p (pred [{"kind" "paragraph" "text" "alpha beta gamma delta epsilon zetta eta theta iota kappa"
                  "inlines" [{"kind" "text" "text" "alpha beta gamma delta " "src" ["text"] "conf" 1000}
                             {"kind" "text" "text" "epsilon zetta eta theta iota kappa" "src" ["ocr"] "conf" 600}]
                  "src" ["text" "ocr"] "conf" 500}])]
    (is (= [["paragraph" "fused" 500 1000] ["text" "text" 1000 1000] ["text" "ocr" 600 833]] (CR/rows p g)))))

(deftest sections-and-table-cells
  (let [cell (fn [t & {:as m}] (merge {"kind" "cell" "text" t} (update-keys m name)))
        tbl (fn [& cells] {"kind" "table" "children" [{"kind" "rowgroup" "children" [{"kind" "row" "children" (vec cells)}]}]})
        g (gold [{"kind" "section" "level" 1 "children" [{"kind" "heading" "level" 1 "text" "A"}
                                                         (tbl (cell "x") (cell "y"))]}])
        p (pred [{"kind" "section" "level" 1 "src" ["infer"] "conf" 500
                  "children" [{"kind" "heading" "level" 1 "text" "A"}
                              (assoc (tbl (cell "x" :src ["text"] :conf 600) (cell "z" :src ["text"] :conf 600))
                                     "conf" 600 "src" ["text"])]}])
        r (set (CR/rows p g))]
    (is (contains? r ["section" "infer" 500 1000]))
    (is (contains? r ["cell" "text" 600 1000]))     ; x: [row 0, 1, 1, "x"] is the gold's
    (is (contains? r ["cell" "text" 600 0]))        ; z is not
    (is (not-any? #(= "row" (first %)) r))))        ; no conf on the row or its group: no row

(deftest furniture-and-the-ledger
  (let [g (-> (gold [(P "Body")] :furniture true :dropped ["page-number"])
              (assoc-in ["model" "furniture"] [{"role" "header" "template" "ACME" "pages" [1 2]}])
              (assoc-in ["model" "dropped"] [{"reason" "page-number" "page" 1 "text" "1"}]))
        p (-> (pred [(P "Body")])
              (assoc "furniture" [{"role" "header" "template" "ACME" "pages" [1 2] "src" ["text" "infer"] "conf" 950}
                                  {"role" "footer" "template" "Other" "pages" [1] "src" ["text"] "conf" 800}]
                     "dropped" [{"reason" "page-number" "page" 1 "text" "1" "conf" 800 "src" ["text"]}
                                {"reason" "page-number" "page" 2 "text" "2" "conf" 800 "src" ["text"]}]))]
    (is (= [["furniture" "text" 950 1000] ["furniture" "text" 800 0] ["dropped" "text" 800 1000] ["dropped" "text" 800 0]]
           (vec (filter #(#{"furniture" "dropped"} (first %)) (CR/rows p g)))))))

(let [res (binding [clojure.test/*test-out* *err*] (run-tests (quote accuracy.test-calib)))]
  (if (zero? (+ (:fail res) (:error res)))
    (println (format "  ok   calibration-test: %d laws of the reliability arithmetic, the isotonic fit, its lookup, the kin table and the outcomes hold" (:test res)))
    (do (println (format "  FAIL calibration-test: %d failure(s)" (+ (:fail res) (:error res))))
        (System/exit 1))))
