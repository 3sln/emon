(ns table.test-ab-checks
  (:require [clojure.test :refer [deftest is run-tests]] [table.ab-checks :as C]))
(defn table [rows page]
  {"kind" "table" "children" [{"kind" "rowgroup" "role" "body" "children"
                               (mapv (fn [row] {"kind" "row" "children" (mapv #(if (seq %) {"kind" "cell" "text" % "at" [[page 0 0 1 1]]} {"kind" "cell"}) row)}) rows)}]})
(defn model [& tables] {"body" {"kind" "document" "children" (vec tables)}})
(defn freeze [m] (mapv (fn [[t p k c v]] {"table" t "page" p "row" k "col" c "value" v}) (C/cells-of m)))
(defn column-repair [before entry]
  [(assoc entry "row_ownership" (C/freeze-row-ownership (freeze before) before))])
(def ledger [["01/02" "Coffee" "4.50" "" "95.50"] ["02/02" "Salary" "" "1,000.00" "1,095.50"] ["03/02" "Rent" "500.00" "" "595.50"]])
(def base (model (table ledger 1)))
(def frozen (freeze base))
(defn check [rows] (C/ownership "X" frozen (model (table rows 1)) []))
(deftest identity (is (= [12 0] ((juxt #(get % "kept") #(get % "changed")) (check ledger)))))
(deftest regrid (is (= [12 0 0] ((juxt #(get % "kept") #(get % "changed") #(get % "index_exact")) (check (mapv #(into [""] %) ledger))))))
(deftest linked
  (let [base (model (table (subvec ledger 0 2) 1) (table [(into [""] (ledger 2))] 2))
        linked (model {"kind" "table" "children" (into (get (table (subvec ledger 0 2) 1) "children") (get (table [(ledger 2)] 2) "children"))})]
    (is (zero? (get (C/ownership "X" (freeze base) linked []) "changed")))))
(deftest moved (is (= 1 (get (check (-> ledger (assoc-in [2 2] "") (assoc-in [2 3] "500.00"))) "moved"))))
(deftest merged-columns (is (pos? (get (check (mapv (fn [[a b c d e]] [a b (if (seq c) c d) e]) ledger)) "merged"))))
(deftest missing (is (= 1 (get (check (assoc-in ledger [0 1] "")) "missing"))))
(deftest extended (is (= [1 0] ((juxt #(get % "extended") #(get % "changed")) (check (assoc-in ledger [0 1] "Coffee beans"))))))
(deftest numeric-not-repaired
  (is (= 4 (get (C/ownership "X" frozen (model (table (-> ledger (assoc-in [0 4] "") (assoc-in [0 1] "")) 1))
                            [{"doc" "X" "page" 1 "line_numbers" [] "line_cols" []}]) "changed"))))
(deftest registered-repair
  (let [rows [["1" "Alpha" "5.0" "1"] ["2" "Beta" "6.0" "2"] ["3" "Gamma" "7.0" "3"]]
        frozen (freeze (model (table rows 1))) fixed (model (table (assoc-in rows [1 1] "Beta-wrapped") 1))
        r (C/ownership "X" frozen fixed [{"doc" "X" "page" 1 "line_numbers" [2] "line_cols" [0 3]}])]
    (is (= [1 0] ((juxt #(get % "repaired") #(get % "changed")) r)))
    (is (= 1 (get (C/ownership "X" frozen fixed []) "changed")))))
(deftest words
  (is (= {"lost" 0 "gained" 0 "duplicate_header_words" 0 "newly_accounted_header_words" 0} (C/compare-words base base)))
  (is (= 1 (get (C/compare-words base (model (table (assoc-in ledger [0 1] "") 1))) "lost")))
  (is (= (C/tokens "é") (C/tokens "é"))))
(deftest occupied-columns-cannot-collapse-even-when-every-word-is-preserved
  ;; A phantom EMPTY column may disappear; nine occupied baseline columns
  ;; cannot map into one eight-column table under the registered guard.
  (let [rows (mapv (fn [r] (into (mapv #(str "column-" % "-row-" r) (range 7))
                                 (if (= r 1) ["left" ""] ["" (str "right-" r)]))) (range 3))
        before (model (table rows 1))
        after (model (table (mapv (fn [r] (conj (subvec r 0 7) (if (seq (r 7)) (r 7) (r 8)))) rows) 1))
        result (C/ownership "X" (freeze before) after [])]
    (is (zero? (get (C/compare-words before after) "lost")))
    (is (zero? (get (C/compare-words before after) "gained")))
    (is (= 2 (get result "merged")))
    (is (= 2 (get result "changed")))))

(defn header-model []
  {"body" {"kind" "document" "children"
           [{"kind" "table" "at" [[1 0 0 10 10] [2 0 0 10 10]] "children"
             [{"kind" "rowgroup" "role" "head" "children"
               [{"kind" "row" "at" [[1 0 0 10 1]] "children" [{"kind" "cell" "text" "Date Balance"}]}]}]}]}
   "dropped" []})
(defn with-drop [m page text]
  (assoc m "dropped" [{"reason" "duplicate-header-row" "page" page "text" text}]))
(deftest exact-header-repeat
  (let [before (update-in (header-model) ["body" "children"] conj {"kind" "paragraph" "text" "Date Balance"})
        after (with-drop (header-model) 2 "Date Balance")]
    (is (= {"lost" 0 "gained" 0 "duplicate_header_words" 2 "newly_accounted_header_words" 2}
           (C/compare-words before after)))))
(deftest invalid-repeat
  (doseq [[page text] [[2 "Date Amount"] [3 "Date Balance"] [1 "Date Balance"]]]
    (is (thrown? clojure.lang.ExceptionInfo (C/compare-words (header-model) (with-drop (header-model) page text))))))
(deftest untrusted-drop-does-not-mask-loss
  (let [before (update-in (header-model) ["body" "children"] conj {"kind" "paragraph" "text" "transaction"})
        after (assoc (header-model) "dropped" [{"reason" "running-title" "page" 2 "text" "transaction"}])]
    (is (= 1 (get (C/compare-words before after) "lost")))
    (is (= 1 (get (C/compare-words after before) "gained")))))
(deftest legacy-repeat-occurrences
  (let [before (with-drop (header-model) 2 "legacy reordered header")]
    (is (zero? (get (C/compare-words before before) "lost")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (C/compare-words before (update before "dropped" #(into % %)))))))
(deftest duplicate-repeat-does-not-mask-loss
  (let [before (update-in (header-model) ["body" "children"] conj {"kind" "paragraph" "text" "Date Balance Date Balance"})
        after (with-drop (header-model) 2 "Date Balance")]
    (is (= 2 (get (C/compare-words before after) "lost")))))

(deftest registered-column-merge-is-narrow
  (let [before (model (table ledger 1))
        after (model (table (mapv (fn [[a b c d e]] [a b (if (seq c) c d) e]) ledger) 1))
        repair (column-repair before {"doc" "X" "kind" "column-merge" "table" 0 "page" 1 "columns" [2 3]})
        result (C/ownership "X" (freeze before) after repair)]
    (is (zero? (get result "changed")))
    (is (= 1 (get result "repaired")))
    (is (pos? (get (C/ownership "X" (freeze before) after [(assoc (first repair) "page" 2)]) "changed")))
    (is (pos? (get (C/ownership "Y" (freeze before) after repair) "changed")))
    (is (pos? (get (C/ownership "X" (freeze before) after [(assoc (first repair) "table" 1)]) "changed")))
    (is (pos? (get (C/ownership "X" (freeze before) (model (table (assoc-in ledger [0 2] "") 1)) repair) "changed")))
    (is (pos? (get (C/ownership "X" (freeze before) (model (table (assoc-in ledger [0 1] "missing") 1)) repair) "changed")))))


(deftest registered-merge-keeps-two-values-in-one-cell-without-reusing-a-token
  (let [before (model (table [["stub" "left" "right" "tail"]] 1))
        after (model (table [["stub" "left right" "tail"]] 1))
        repair (column-repair before {"doc" "X" "kind" "column-merge" "table" 0 "page" 1 "columns" [1 2]})]
    (is (zero? (get (C/ownership "X" (freeze before) after repair) "changed")))
    (is (pos? (get (C/ownership "X" (freeze before) after []) "changed"))))
  (let [before (model (table [["stub" "same" "same" "tail"]] 1))
        after (model (table [["stub" "same" "tail"]] 1))
        repair (column-repair before {"doc" "X" "kind" "column-merge" "table" 0 "page" 1 "columns" [1 2]})]
    (is (zero? (get (C/ownership "X" (freeze before) (model (table [["stub" "same same" "tail"]] 1)) repair) "changed")))
    (is (pos? (get (C/ownership "X" (freeze before) after repair) "changed")))))


(deftest registered-column-merge-never-excuses-moving-a-value-to-another-row
  (let [before (model (table [["1.00" "left" "" "tail-a"] ["2.00" "" "right" "tail-b"]] 1))
        after (model (table [["1.00" "left right" "tail-a"] ["2.00" "" "tail-b"]] 1))
        repair (column-repair before {"doc" "X" "kind" "column-merge" "table" 0 "page" 1 "columns" [1 2]})]
    (is (zero? (get (C/compare-words before after) "lost")))
    (is (zero? (get (C/compare-words before after) "gained")))
    (is (pos? (get (C/ownership "X" (freeze before) after repair) "changed")))))

(deftest merge-exception-preserves-row-mates-when-numeric-keys-repeat
  (let [before (model (table [["1.00" "left" "" "tail-a"] ["1.00" "" "right" "tail-b"]] 1))
        kept (model (table [["1.00" "left" "tail-a"] ["1.00" "right" "tail-b"]] 1))
        swapped (model (table [["1.00" "right" "tail-a"] ["1.00" "left" "tail-b"]] 1))
        repair (column-repair before {"doc" "X" "kind" "column-merge" "table" 0 "page" 1 "columns" [1 2]})]
    (is (zero? (get (C/ownership "X" (freeze before) kept repair) "changed")))
    (is (zero? (get (C/compare-words before swapped) "lost")))
    (is (zero? (get (C/compare-words before swapped) "gained")))
    (is (pos? (get (C/ownership "X" (freeze before) swapped repair) "changed")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (C/ownership "X" (freeze before) kept [(dissoc (first repair) "row_ownership")])))))

(let [r (run-tests)] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))
