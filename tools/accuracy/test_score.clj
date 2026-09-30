(ns accuracy.test-score
  "Laws of the accuracy scorer (DEVLOG P2.9, D077).   bb unit runs this:
     bb -cp tools:tools/lib tools/accuracy/test_score.clj

Every expected number below is worked out by hand in a comment beside it; none
is copied from the scorer's output. No corpus is needed except for the last
group, which checks the oracle and the degraded fixture on real documents.
(The random cases of the old Python suite use java.util.Random: the coverage is
the same, the stream is not Python's.)"
  (:require [accuracy.accuracy :as A]
            [accuracy.score :as S]
            [clojure.test :refer [deftest is run-tests]]
            [pyjson]))

(def ALL ["section" "heading" "paragraph" "list" "item" "table" "rowgroup" "row"
          "cell" "caption" "figure" "seal" "quote" "code" "note"])

(defn- almost= [a b] (< (Math/abs (- (double a) (double b))) 5e-8))

(defn gold [children & {:as over}]
  (let [ann (merge {"structure" ALL "attributes" {"heading" ["level"] "figure" ["subkind"]}
                    "text" true "furniture" false "dropped" [] "pages" ["n"]}
                   (update-keys over name))]
    {"annotated" ann
     "model" {"pages" [{"n" 1}] "body" {"id" 0 "kind" "document" "children" children}}}))

(defn pred [children & {:as extra}]
  (merge {"pages" [{"n" 1}] "body" {"id" 0 "kind" "document" "children" children}}
         (update-keys extra name)))

(defn P [t] {"kind" "paragraph" "text" t})
(defn H [t level] {"kind" "heading" "level" level "text" t})
(defn counts [g p] (get (S/score p g) "counts"))
(defn M [c] (S/metrics c))

;; ------------------------------------------------------------------ Text ----

(deftest lev-hand
  (is (= 3 (S/lev "kitten" "sitting")))      ; k>s, e>i, +g
  (is (= 3 (S/lev "" "abc")))
  (is (= 3 (S/lev "abc" "")))
  (is (= 2 (S/lev "flaw" "lawn")))           ; -f, +n
  (is (= 1 (S/lev ["a" "b"] ["a" "c" "b"]))))

(deftest lev-matches-dp
  (let [rnd (java.util.Random. 7)
        dp (fn [a b]
             (let [a (vec a) b (vec b)]
               (peek (reduce (fn [prev i]
                               (reduce (fn [cur j]
                                         (conj cur (min (inc (prev (inc j))) (inc (peek cur))
                                                        (+ (prev j) (if (= (a i) (b j)) 0 1)))))
                                       [(inc i)] (range (count b))))
                             (vec (range (inc (count b)))) (range (count a))))))
        word (fn [maxlen] (apply str (repeatedly (.nextInt rnd (inc maxlen)) #(nth "abc" (.nextInt rnd 3)))))]
    ;; every implementation path: one long (<= 62), limbs (<= 600), BigInteger (> 600)
    (doseq [maxlen [25 25 25 150 150 700]
            _ (range (if (= maxlen 700) 3 100))]
      (let [a (word maxlen) b (word maxlen)]
        (is (= (dp a b) (S/lev a b)) (pr-str [a b]))))))

(deftest cer-wer
  ;; gold "abcd" (4 chars), pred "abxd": 1 substitution -> CER 1/4
  (let [c (counts (gold [(P "abcd")]) (pred [(P "abxd")]))]
    (is (= [4 1] [(c "text.chars") (c "text.char-err")]))
    (is (almost= 0.25 ((M c) "cer"))))
  ;; gold "the cat sat" (3 words), pred "the cat": one deleted word -> WER 1/3; chars: " sat" deleted = 4 of 11
  (let [c (counts (gold [(P "the cat sat")]) (pred [(P "the cat")]))]
    (is (= [3 1] [(c "text.words") (c "text.word-err")]))
    (is (= [11 4] [(c "text.chars") (c "text.char-err")]))))

(deftest pooling-is-micro
  (let [a (counts (gold [(P "abcd")]) (pred [(P "abxd")]))              ; 1 / 4
        b (counts (gold [(P "abcdefgh")]) (pred [(P "abcdefgh")]))]     ; 0 / 8
    (is (almost= (/ 1.0 12) ((M (S/pool [a b])) "cer")))))               ; not the mean 1/8

(deftest bag-of-words-ignores-order
  (let [c (counts (gold [(P "one two three")]) (pred [(P "three one two")]))
        m (M c)]
    (is (= 1.0 (m "bow-f1")))               ; same words
    (is (> (m "wer") 0.0))                  ; wrong order
    (is (= 2 (c "text.word-err")))))        ; delete-insert = 2 word edits

(deftest normalization
  ;; NFC: decomposed e + U+0301 equals the composed character
  (is (= 0 ((counts (gold [(P "café")]) (pred [(P "café")])) "text.char-err")))
  ;; whitespace: runs of (Unicode) spaces, newlines, NBSP and tabs are one space
  (is (= 0 ((counts (gold [(P "a b\nc")]) (pred [(P "a   b\t\tc ")])) "text.char-err")))
  ;; no case folding, no locale: Turkish dotted/dotless I stays distinct
  (is (= 1 ((counts (gold [(P "I")]) (pred [(P "ı")])) "text.char-err")))
  (is (= 1 ((counts (gold [(P "Ab")]) (pred [(P "ab")])) "text.char-err"))))

(deftest text-unit-boundaries-are-whitespace
  ;; one paragraph vs two with the same words: CER/WER do not see the split
  (is (= 0 ((counts (gold [(P "a b") (P "c d")]) (pred [(P "a b c d")])) "text.char-err"))))

;; ---------------------------------------------------------------- Blocks ----

(deftest precision-recall-hand
  ;; gold: 3 paragraphs. pred: two of them + one spurious -> tp 2, gold 3, pred 3
  (let [g (gold [(P "alpha alpha alpha") (P "bravo bravo bravo") (P "charlie charlie")])
        p (pred [(P "alpha alpha alpha") (P "zulu yankee xray") (P "charlie charlie")])
        c (counts g p)
        m (M c)]
    (is (= [2 3 3] [(c "block.paragraph.tp") (c "block.paragraph.gold") (c "block.paragraph.pred")]))
    (is (almost= (/ 2.0 3) (m "block-paragraph-p")))
    (is (almost= (/ 2.0 3) (m "block-paragraph-r")))
    (is (almost= (/ 2.0 3) (m "block-paragraph-f1")))))

(deftest f1-unequal
  ;; gold 4, pred 2 both right: P = 1, R = 1/2, F1 = 2*1*.5/1.5 = 2/3
  (let [m (M (counts (gold [(P "one one") (P "two two") (P "three three") (P "four four")])
                     (pred [(P "one one") (P "three three")])))]
    (is (= 1.0 (m "block-paragraph-p")))
    (is (= 0.5 (m "block-paragraph-r")))
    (is (almost= (/ 2.0 3) (m "block-paragraph-f1")))))

(deftest mistyped-block-is-fn-and-fp
  (let [m (M (counts (gold [(H "Intro" 1) (P "body text here")])
                     (pred [(P "Intro") (P "body text here")])))]
    (is (= 0.0 (m "block-heading-r")))             ; the heading was missed
    (is (almost= 0.5 (m "block-paragraph-p")))     ; 1 of 2 predicted paragraphs is right
    (is (= 1.0 (m "block-paragraph-r")))))

(deftest fuzzy-match-above-half
  ;; "abcdefgh" vs "abcdefxx": similarity 0.75 >= 0.5, a match; "abcd" vs "wxyz" is not
  (is (= 1 ((counts (gold [(P "abcdefgh")]) (pred [(P "abcdefxx")])) "block.paragraph.tp")))
  (is (= 0 ((counts (gold [(P "abcd")]) (pred [(P "wxyz")])) "block.paragraph.tp"))))

(deftest annotated-honoured
  (let [g (gold [(H "T" 1) (P "body")] :structure ["paragraph"] :text false)
        c (counts g (pred [(P "body") (P "extra")]))]
    (is (not (contains? c "block.heading.gold")))        ; heading not annotated: no claim
    (is (= 2 (c "block.paragraph.pred")))
    (is (not (contains? c "text.chars")))                ; text not annotated
    (is (not (contains? c "heading.level.n"))))
  ;; absence is annotated too: an annotated kind with no gold makes predictions FPs
  (let [c (counts (gold [(P "body")])
                  (pred [(P "body") {"kind" "list" "children" [{"kind" "item" "text" "x"}]}]))]
    (is (= [0 1 0] [(c "block.list.gold") (c "block.list.pred") (c "block.list.tp")]))
    (is (= 0.0 ((M c) "block-list-p")))))

(deftest reading-order-tau
  (is (= 1.0 (S/kendall-tau [[0 0] [1 1] [2 2]])))
  (is (= -1.0 (S/kendall-tau [[0 1] [1 0]])))
  (is (almost= (/ 1.0 3) (S/kendall-tau [[0 0] [1 2] [2 1]]))))   ; 2 concordant, 1 discordant

;; -------------------------------------------------------------- Headings ----

(deftest level-accuracy-hand
  (let [c (counts (gold [(H "Alpha" 1) (H "Bravo" 2) (H "Charlie" 2)])
                  (pred [(H "Alpha" 1) (H "Bravo" 2) (H "Charlie" 4)]))
        m (M c)]
    (is (= [3 2 2] [(c "heading.level.n") (c "heading.level.ok") (c "heading.level.near")]))
    (is (almost= (/ 2.0 3) (m "heading-level-acc")))
    (is (almost= (/ 2.0 3) (m "heading-level-near")))   ; 4 vs 2 is not within one
    (is (= 1.0 (m "heading-p")))
    (is (= 1.0 (m "heading-r")))))

(deftest off-by-one-is-near-not-right
  (let [m (M (counts (gold [(H "Alpha" 2)]) (pred [(H "Alpha" 3)])))]
    (is (= 0.0 (m "heading-level-acc")))
    (is (= 1.0 (m "heading-level-near")))))

(deftest missed-heading-costs-end-to-end-only
  ;; 4 gold headings, 3 found, 2 of those at the right level:
  ;; detection R = 3/4, level acc (matched) = 2/3, end-to-end = 2/4
  (let [m (M (counts (gold [(H "Alpha Alpha" 1) (H "Bravo Bravo" 2) (H "Charlie Charlie" 3) (H "Delta Delta" 2)])
                     (pred [(H "Alpha Alpha" 1) (H "Bravo Bravo" 2) (H "Charlie Charlie" 1)])))]
    (is (almost= 0.75 (m "heading-r")))
    (is (= 1.0 (m "heading-p")))
    (is (almost= (/ 2.0 3) (m "heading-level-acc")))
    (is (almost= 0.5 (m "heading-level-e2e")))))

(deftest level-skipped-when-not-annotated
  (let [c (counts (gold [(H "Alpha" 1)] :attributes {"heading" []}) (pred [(H "Alpha" 3)]))]
    (is (not (contains? c "heading.level.n")))
    (is (not (contains? (M c) "heading-level-acc")))))

(deftest nested-sections-found
  (let [sec (fn [lv & ch] {"kind" "section" "level" lv "children" (vec ch)})
        tree (fn [] [(sec 1 (H "Top" 1) (sec 2 (H "Sub" 2) (P "x y z")))])
        m (M (counts (gold (tree)) (pred (tree))))]
    (is (= 1.0 (m "heading-level-acc")))))

;; ----------------------------------------------------------------- Other ----

(deftest paragraph-boundaries
  ;; gold: "a b" | "c d"  (one boundary, before word 2).  pred merges them:
  (let [c (counts (gold [(P "a b") (P "c d")]) (pred [(P "a b c d")]))
        m (M c)]
    (is (= [0 1 0] [(c "para.boundary.tp") (c "para.boundary.gold") (c "para.boundary.pred")]))
    (is (= 0.0 (m "para-boundary-r")))
    (is (= 0.0 (m "para-boundary-p"))))        ; expected one, predicted none
  ;; an extra split costs precision: gold one boundary, pred 3 paragraphs
  (let [c (counts (gold [(P "a b") (P "c d e")]) (pred [(P "a b") (P "c d") (P "e")]))]
    (is (= [1 1 2] [(c "para.boundary.tp") (c "para.boundary.gold") (c "para.boundary.pred")]))
    (is (almost= 0.5 ((M c) "para-boundary-p"))))
  ;; a boundary survives a typo in a neighbouring word
  (is (= 1 ((counts (gold [(P "a b c") (P "d e f")]) (pred [(P "a b x") (P "d e f")])) "para.boundary.tp"))))

(deftest geometry-iou
  (let [a [[1 0 0 10 10]]]
    (is (= 1.0 (S/iou a a)))
    (is (= 0.0 (S/iou a [[1 20 20 30 30]])))
    (is (= 0.0 (S/iou a [[2 0 0 10 10]])))                         ; other page
    (is (almost= (/ 50.0 150) (S/iou a [[1 5 0 15 10]]))))         ; half shifted: 50 / (100+100-50)
  ;; the rule: >= 0.5 matches irrespective of text when both have geometry
  (let [g (gold [(assoc (P "same") "at" [[1 0 0 10 10]])])
        ok (counts g (pred [(assoc (P "other words") "at" [[1 0 0 10 9]])]))    ; IoU 0.9
        no (counts g (pred [(assoc (P "same") "at" [[1 5 0 15 10]])]))]         ; IoU 1/3
    (is (= 1 (ok "block.paragraph.tp")))
    (is (= 0 (no "block.paragraph.tp")))))

(deftest furniture-and-ledger
  (let [g (-> (gold [(P "body")] :furniture true :dropped ["page-number"])
              (assoc-in ["model" "pages"] [{"n" 1} {"n" 2}])
              (assoc-in ["model" "furniture"] [{"id" 1 "role" "footer" "template" "ACME-{n:02}" "pages" [1 2]}])
              (assoc-in ["model" "dropped"] [{"reason" "page-number" "page" 1 "text" "1"}
                                             {"reason" "page-number" "page" 2 "text" "2"}]))
        p (pred [(P "body")]
                :furniture [{"id" 1 "role" "footer" "pages" [1 2]
                             "children" [{"kind" "paragraph" "text" "ACME-01"}]}]
                :dropped [{"reason" "page-number" "page" 1 "text" "1" "at" [[1 0 0 1 1]]}])
        c (counts g p)]
    ;; gold: 2 page-numbers + 2 expanded furniture = 4.  pred: 1 + one furniture item over 2 pages
    ;; (ACME-01 on both pages; page 2's text is wrong) -> tp = 1 page-number + 1 furniture = 2
    (is (= [2 4 3] [(c "furniture.tp") (c "furniture.gold") (c "furniture.pred")]))))

(deftest watermark-leak
  (let [g (-> (gold [(P "real body text")] :dropped ["watermark"])
              (assoc-in ["model" "dropped"] [{"reason" "watermark" "page" 1 "text" "DRAFT"}]))
        c (counts g (pred [(P "real body text DRAFT")]
                          :dropped [{"reason" "watermark" "page" 1 "at" [[1 0 0 1 1]] "text" "DRAFT"}]))]
    (is (= 5 (c "contam.leak-chars")))
    (is (= 20 (c "contam.body-chars")))
    (is (almost= 0.25 ((M c) "watermark-leak")))))

(deftest table-teds-lite
  (let [cell (fn [t] {"kind" "cell" "text" t})
        row (fn [& ts] {"kind" "row" "children" (mapv cell ts)})
        tbl (fn [& rows] {"kind" "table" "children" [{"kind" "rowgroup" "children" (vec rows)}]})
        g (gold [(tbl (row "a" "b") (row "c" "d"))])]
    (is (= 1.0 ((M (counts g (pred [(tbl (row "a" "b") (row "c" "d"))]))) "table-teds-lite")))
    ;; one wrong cell of four: similarity 1 - 1/4
    (let [r (S/table-teds-lite (get-in g ["model" "body" "children" 0]) (tbl (row "a" "b") (row "c" "x")))]
      (is (almost= 0.75 (r "teds")))
      (is (= 1.0 (r "teds-struct"))))
    ;; a table missed entirely scores 0
    (is (= 0.0 ((M (counts g (pred [(P "a b c d")]))) "table-teds-lite")))))

;; --------------------------------------------------------------- Ratchet ----

(deftest edn-round-trip
  (let [b {":format" 1 ":tolerance" 0.002 ":predictors" {"x" {"pdf/all" {":cer" 0.0123 ":wer" 1.0}}}}]
    (is (= b (A/edn-read (str A/HEADER (A/edn-write b)))))))

(deftest compare-directions
  (let [old {"k" {":cer" 0.10 ":heading-level-acc" 0.80}}]
    ;; within tolerance either way: fine
    (let [[reg _ gone] (A/compare-boards old {"k" {":cer" 0.101 ":heading-level-acc" 0.799}} 0.002)]
      (is (= [[] []] [reg gone])))
    ;; CER is lower-is-better: a rise beyond tolerance is a regression; accuracy falling is too
    (let [[reg] (A/compare-boards old {"k" {":cer" 0.12 ":heading-level-acc" 0.70}} 0.002)]
      (is (= [":cer" ":heading-level-acc"] (sort (map second reg)))))
    ;; improvements in either direction
    (let [[reg imp] (A/compare-boards old {"k" {":cer" 0.05 ":heading-level-acc" 0.90}} 0.002)]
      (is (= [[] 2] [reg (count imp)])))
    ;; a vanished metric or category fails
    (let [[_ _ gone] (A/compare-boards old {"k" {":cer" 0.1}} 0.002)]
      (is (= [":heading-level-acc"] (map second gone))))
    (is (= 2 (count (nth (A/compare-boards old {} 0.002) 2))))))

(deftest merge-only-moves-toward-better
  (let [old {"k" {":cer" 0.10 ":f" 0.80}}
        new {"k" {":cer" 0.12 ":f" 0.85 ":g" 0.5}}]
    (is (= {"k" {":cer" 0.10 ":f" 0.85 ":g" 0.5}} (A/merge-up old new)))))

;; -------------------------------------------------------------- Fixtures ----
;; The oracle is perfect and the degraded predictor is not, on real documents.

(def DOCS ["h-numbered" "table-multipage-thead" "furn-title-pagenum" "watermark-draft"])

(defn run-pred [name doc]
  (let [inp (str A/CORPUS "/pdf/" doc ".pdf")
        gp (str A/CORPUS "/gold/" doc ".gold.json")
        [m _] (A/run-predictor (A/PREDICTORS name) doc "pdf" inp gp)]
    (S/metrics (get (S/score m (pyjson/parse-file gp)) "counts"))))

(deftest oracle-perfect-degraded-worse
  (doseq [doc DOCS]
    (let [o (run-pred "oracle" doc) d (run-pred "degraded" doc)]
      (is (= 0.0 (o "cer")) doc)
      (is (= 0.0 (o "wer")) doc)
      (doseq [[k v] o :when (not (#{"cer" "wer" "watermark-leak"} k))]
        (is (= 1.0 v) (pr-str [doc k])))
      (is (> (d "cer") 0.0) doc)))
  (let [dh (run-pred "degraded" "h-numbered")]     ; many headings: levels and kinds get damaged
    (is (< (dh "block-heading-f1") 1.0))
    (is (< (dh "heading-level-acc") 1.0))
    (is (< (dh "para-boundary-p") 1.0)))           ; demoted headings add boundaries
  (is (< ((run-pred "degraded" "furn-title-pagenum") "para-boundary-r") 1.0))   ; merged paragraphs
  (is (< ((run-pred "degraded" "table-multipage-thead") "table-teds-lite") 1.0))
  (is (< ((run-pred "degraded" "furn-title-pagenum") "furniture-r") 1.0))
  (is (> ((run-pred "degraded" "watermark-draft") "watermark-leak") 0.0)))

(deftest ratchet-fails-a-degraded-predictor-against-the-oracle-board
  ;; the ratchet, end to end: the oracle's values are the "committed" board,
  ;; the degraded predictor is the run that regressed
  (let [[good] (A/evaluate (A/PREDICTORS "oracle"))
        [bad] (A/evaluate (A/PREDICTORS "degraded"))
        [reg] (A/compare-boards good bad 0.002)]
    (is (> (count reg) 100))
    (is (= [[] [] []] (A/compare-boards good good 0.002)))))

(deftest placeholder-is-not-a-failure
  (is (nil? (first (A/run-predictor (A/PREDICTORS "emon") "x" "pdf" "x" "x")))))

(let [res (binding [clojure.test/*test-out* *err*] (run-tests 'accuracy.test-score))]
  (if (zero? (+ (:fail res) (:error res)))
    (println (format "  ok   accuracy-test: %d laws of the scorer and the ratchet hold (CER/WER, P/R/F1, heading level, boundaries, IoU, ledger, oracle perfect, degraded worse)" (:test res)))
    (do (println (format "  FAIL accuracy-test: %d failure(s)" (+ (:fail res) (:error res))))
        (System/exit 1))))
