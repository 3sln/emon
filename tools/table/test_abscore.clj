;; bb -cp tools:tools/lib tools/table/test_abscore.clj   -- the scorer of `bb tables-ab` on hand-made Models (no corpus needed)
(ns table.test-abscore
  (:require [clojure.test :refer [deftest is run-tests]]
            [table.abscore :as AS]))

(defn cell [t page] (cond-> {"kind" "cell"} t (assoc "text" t "at" [[page 0 0 1 1]])))
(defn table [role-rows & {:keys [at]}]
  (cond-> {"kind" "table" "children" (vec (for [[role rows page] role-rows]
                                            {"kind" "rowgroup" "role" role
                                             "children" (vec (for [r rows] {"kind" "row" "children" (mapv #(cell % page) r)}))}))}
    at (assoc "at" at)))
(defn model [& ts] {"pages" [{"n" 1 "w" 100} {"n" 2 "w" 100}] "body" {"id" 0 "kind" "document" "children" (vec ts)}})

(deftest amounts
  (is (= 443540 (AS/amount "Rs. 4,435.40")))
  (is (= -1250 (AS/amount "(12.50)")))
  (is (= 100 (AS/amount "1.00 Cr")))
  (is (nil? (AS/amount "2984")))          ; a branch code is not money
  (is (nil? (AS/amount "12/03"))))

(deftest links-are-consecutive-pages-of-one-table
  (let [t (table [["head" [["Date" "Amount"]] 1] ["body" [["01/02" "1.00"]] 1] ["body" [["02/02" "2.00"]] 2]])]
    (is (= [1 2] (AS/pages-of t)))
    (is (= [[1 2]] (AS/links-of t)))))

(deftest gold-pixels-are-rescaled-to-points
  (let [gold {"model" {"pages" [{"n" 1 "w" 400 "px" [400 200] "kind" "image"}]
                       "body" {"kind" "document" "children" [{"kind" "table" "children" [{"kind" "cell" "at" [[1 0 0 400 200]]}]}]}}}
        m {"pages" [{"n" 1 "w" 96.0}]}
        g (AS/norm-gold gold m)
        t (first (get-in g ["model" "body" "children"]))]
    (is (= [[1 0.0 0.0 96.0 48.0]] (get t "at")))))

(deftest header-binding-pairs-are-index-free
  ;; the same (value, header) pairs read with a phantom leading column bind identically
  (let [gold {"annotated" {"structure" ["table" "row" "cell"] "attributes" {"table" []}}
              "model" {"pages" [] "body" {"kind" "document" "children" [(table [["head" [["Item" "Cost"]] 1] ["body" [["Tea" "1.00"] ["Cake" "2.50"]] 1]])]}}}
        pred (model (table [["head" [[nil "Item" "Cost"]] 1] ["body" [[nil "Tea" "1.00"] [nil "Cake" "2.50"]] 1]]))
        c (AS/emon-gold-counts pred gold {})]
    (is (= 4 (get c "hb.gold")))
    (is (= 4 (get c "hb.tp")))))

(deftest ledger-rows-roles-and-continuation
  (let [gold {"transactions" [{"debit" 4.5 "credit" nil "balance" 95.5} {"debit" nil "credit" 1000.0 "balance" 1095.5}
                               {"debit" 500.0 "credit" nil "balance" 595.5}]}
        linked (model (table [["head" [["Date" "Debit" "Credit" "Balance"]] 1]
                              ["body" [["01/02" "4.50" nil "95.50"] ["02/02" nil "1,000.00" "1,095.50"]] 1]
                              ["body" [["03/02" "500.00" nil "595.50"]] 2]]))
        split (model (table [["head" [["Date" "Debit" "Credit" "Balance"]] 1] ["body" [["01/02" "4.50" nil "95.50"] ["02/02" nil "1,000.00" "1,095.50"]] 1]])
                     (table [["body" [["03/02" nil "500.00" "595.50"]] 2]]))
        a (AS/agami-counts linked gold) b (AS/agami-counts split gold)]
    (is (= [3 3] [(get a "row.tp") (get a "row.gold")]))
    (is (= [6 6] [(get a "hb.tp") (get a "hb.gold")]))
    (is (= [1 1 1 1] [(get a "link.tp-r") (get a "link.gold") (get a "link.tp") (get a "link.pred")]))
    ;; the unlinked fragment loses the cross-page pair, and its unheaded amounts lose their roles
    (is (= [0 1] [(get b "link.tp-r") (get b "link.gold")]))
    (is (= 4 (get b "hb.tp")))))

(deftest named-links-match-by-fingerprint
  (let [t (table [["body" [["1.00" "2.00"]] 1] ["body" [["3.00" "4.00"]] 2]])
        k (AS/row-keys t)
        m (model t)
        good {"kind" "links" "links" [{"pages" [1 2] "from" (vec (get k 1)) "to" []}]}
        wrong {"kind" "links" "links" [{"pages" [1 2] "from" ["000000000000"] "to" ["000000000000"]}]}]
    (is (:pass (AS/check m good)))
    (is (not (:pass (AS/check m wrong))))
    (is (not (:pass (AS/check m {"kind" "links" "links" []}))))))

(let [{:keys [fail error]} (run-tests 'table.test-abscore)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
