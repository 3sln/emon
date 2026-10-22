(ns accuracy.test-score
  "Laws of the accuracy scorer (DEVLOG P2.9, D077).   bb unit runs this:
     bb -cp tools:tools/lib tools/accuracy/test_score.clj

Every expected number below is worked out by hand in a comment beside it; none
is copied from the scorer's output. No corpus is needed except for the last
group, which checks the oracle and the degraded fixture on real documents.
(The random cases of the old Python suite use java.util.Random: the coverage is
the same, the stream is not Python's.)"
  (:require [accuracy.accuracy :as A]
            [accuracy.predict-degraded :as D]
            [accuracy.predict-oracle :as O]
            [clojure.string :as str]
            [accuracy.score :as S]
            [accuracy.confrows :as CR]
            [accuracy.calib :as CAL]
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

;; ---------------------------------------------------------------- Fusion ----
;; D206 / SPEC 14.2: ghost-rate, seal-leak, src-ocr-share, tl-override. Every expected number is worked by hand.

(defn- T [t & {:as prov}] (merge {"kind" "text" "text" t} (update-keys prov name)))
(defn- PX
  "A paragraph made of inlines (text = their concatenation) and the provenance of the block itself."
  ([inlines] (PX inlines {}))
  ([inlines prov]
   (merge {"kind" "paragraph" "text" (apply str (map #(get % "text") inlines)) "inlines" (vec inlines)}
          (update-keys prov name))))

(deftest ghost-rate
  ;; gold body: "alpha beta gamma". Pred: `alpha beta` from the text layer, `gamma delta delta` from OCR alone.
  ;; OCR-only words: gamma 1, delta 2. The gold has gamma once (predicted once: no excess), delta never
  ;; (predicted twice: excess 2). ghosts = min(1, 0) + min(2, 2) = 2 of 5 predicted words.
  (let [g (gold [(P "alpha beta gamma")])
        p (pred [(PX [(T "alpha beta " :src ["text"]) (T "gamma delta delta" :src ["ocr"])])])
        c (counts g p)]
    (is (= [2 5] [(c "fusion.ghost-words") (c "fusion.body-words")]))
    (is (almost= 0.4 ((M c) "ghost-rate"))))
  ;; the same words with the provenance on the BLOCK (no inlines): the block's sources are used
  (let [c (counts (gold [(P "alpha beta gamma")])
                  (pred [{"kind" "paragraph" "text" "alpha beta gamma delta delta" "src" ["ocr"]}]))]
    (is (= [2 5] [(c "fusion.ghost-words") (c "fusion.body-words")])))
  ;; an inline's own `src` beats its block's: the block says ocr, the inlines say text, so nothing is OCR-only
  (let [c (counts (gold [(P "alpha beta")])
                  (pred [(PX [(T "alpha" :src ["text"]) (T " beta" :src ["text" "ocr"])] {:src ["ocr"]})]))]
    (is (= 0 (c "fusion.ghost-words"))))
  ;; a word the gold has once, predicted twice, once from each source: one excess, and it is the OCR one
  (let [c (counts (gold [(P "alpha")])
                  (pred [(PX [(T "alpha " :src ["text"]) (T "alpha" :src ["ocr"])])]))]
    (is (= [1 2] [(c "fusion.ghost-words") (c "fusion.body-words")])))
  ;; no provenance, no ghosts; a seal's own OCR words are not counted
  (let [c (counts (gold [(P "alpha")])
                  (pred [(P "alpha") {"kind" "seal" "subkind" "stamp" "text" "PAID" "src" ["ocr"]}]))]
    (is (= [0 1] [(c "fusion.ghost-words") (c "fusion.body-words")]))))

(deftest seal-leak
  ;; gold: body "real body text" and a stamp PAID NOW. Pred A has the stamp's PAID in the paragraph:
  ;; 4 leaked characters over the 19 characters of "real body text PAID".
  (let [g (gold [(P "real body text") {"kind" "seal" "subkind" "stamp" "text" "PAID NOW"}])
        a (counts g (pred [(P "real body text PAID")]))
        b (counts g (pred [(P "real body text") {"kind" "seal" "subkind" "stamp" "text" "PAID NOW"}]))
        c' (counts g (pred [(P "real body text NOW PAID PAID")]))]
    (is (= [4 19] [(a "fusion.seal-leak-chars") (a "fusion.seal-body-chars")]))
    (is (almost= (/ 4.0 19) ((M a) "seal-leak")))
    ;; the stamp where it belongs (a seal node) is no leak, and its text is not body text
    (is (= [0 14] [(b "fusion.seal-leak-chars") (b "fusion.seal-body-chars")]))
    (is (= 0.0 ((M b) "seal-leak")))
    ;; counts are by occurrence: NOW once and PAID twice = 3 + 4 + 4 = 11 over 28 characters
    (is (= [11 28] [(c' "fusion.seal-leak-chars") (c' "fusion.seal-body-chars")])))
  ;; a word the gold body has itself is no leak: "now" is not NOW, but NOW in the body is
  (let [g (gold [(P "pay NOW please") {"kind" "seal" "subkind" "stamp" "text" "NOW"}])
        c (counts g (pred [(P "pay NOW please")]))]
    (is (= 0 (c "fusion.seal-leak-chars"))))
  ;; a gold with no seal text has no seal-leak metric at all
  (is (nil? ((M (counts (gold [(P "alpha")]) (pred [(P "alpha")]))) "seal-leak"))))

(deftest src-ocr-share
  ;; "abcd" (4 characters) from OCR alone, "efghij" (6) from the text layer: 4 of 10
  (let [g (gold [(P "abcd") (P "efghij")])
        c (counts g (pred [{"kind" "paragraph" "text" "abcd" "src" ["ocr"]}
                           {"kind" "paragraph" "text" "efghij" "src" ["text"]}]))]
    (is (= [4 10] [(c "fusion.src-ocr-chars") (c "fusion.src-chars")]))
    (is (almost= 0.4 ((M c) "src-ocr-share"))))
  ;; both sources, `text` and `ocr`, is not OCR-only; inline over block: ab is OCR, cd inherits the block's text
  (let [c (counts (gold [(P "abcd")])
                  (pred [(PX [(T "ab" :src ["ocr"]) (T "cd")] {:src ["text"]})]))]
    (is (= [2 4] [(c "fusion.src-ocr-chars") (c "fusion.src-chars")])))
  (let [c (counts (gold [(P "ab")]) (pred [{"kind" "paragraph" "text" "ab" "src" ["text" "ocr"]}]))]
    (is (= 0 (c "fusion.src-ocr-chars"))))
  ;; a prediction that says nothing of its sources has no share (not zero): the metric is absent
  (is (nil? ((M (counts (gold [(P "ab")]) (pred [(P "ab")]))) "src-ocr-share"))))

(deftest tl-override
  ;; the suspect flag on "abc" of "abcdef": 3 of 6, on a born-digital variant
  (let [g (gold [(P "abcdef")])
        p (pred [(PX [(T "abc" :flags ["text-layer-suspect"]) (T "def")])])
        pdf (get (S/score p g {:variant "pdf"}) "counts")
        scan (get (S/score p g {:variant "scan"}) "counts")
        untrusted (get (S/score p (assoc g "facts" {"tl-untrusted" true}) {:variant "pdf"}) "counts")]
    (is (= [3 6] [(pdf "fusion.tl-susp-chars") (pdf "fusion.tl-chars")]))
    (is (almost= 0.5 ((M pdf) "tl-override")))
    ;; a scan has no text layer to override; a text layer that is wrong on purpose is overridden rightly
    (is (nil? ((M scan) "tl-override")))
    (is (nil? ((M untrusted) "tl-override"))))
  ;; the flag on the block applies to its inlines, and a seal's text is not counted
  (let [c (get (S/score (pred [(PX [(T "ab") (T "cd")] {:flags ["text-layer-suspect"]})
                               {"kind" "seal" "subkind" "stamp" "text" "ZZ" "flags" ["text-layer-suspect"]}])
                        (gold [(P "abcd")]) {:variant "pdf"})
               "counts")]
    (is (= [4 4] [(c "fusion.tl-susp-chars") (c "fusion.tl-chars")]))))

(deftest fusion-metrics-ratchet-lower
  (doseq [m ["ghost-rate" "seal-leak" "src-ocr-share" "tl-override"]]
    (is (contains? S/LOWER-IS-BETTER m) m)))

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

;; ------------------------------------------------------ Layout features (D115) ----

(deftest real-table-teds
  (let [cell (fn [s] {"kind" "cell" "text" s})
        row (fn [& cs] {"kind" "row" "children" (vec cs)})
        table (fn [& rs] {"kind" "table" "children"
                         [{"kind" "rowgroup" "role" "body" "children" (vec rs)}]})
        a (table (row (cell "abcd") (cell "z")))
        score (fn [p] (M (counts (gold [a]) (pred [p]))))]
    (is (= 1.0 (get (score a) "table-teds")))
    ;; Four descendants of table; one character substitution costs 1/4.
    (is (almost= 0.9375 (get (score (table (row (cell "abce") (cell "z")))) "table-teds")))
    (is (= 1.0 (get (score (table (row (cell "abce") (cell "z")))) "table-teds-struct")))
    ;; Deleting a row promotes its children in ordered tree edit distance.
    ;; This differs from recursively comparing only sibling sequences.
    (is (almost= 0.75 (get (S/table-teds a (table (cell "abcd") (cell "z"))) "teds")))
    (is (= 0.0 (get (score (P "abcd z")) "table-teds")))
    (is (< (get (score (table (row (assoc (cell "abcd") "colspan" 2) (cell "z")))) "table-teds-struct") 1.0))
    (is (< (get (score (assoc-in a ["children" 0 "role"] "head")) "table-teds-struct") 1.0))
    (let [styled (assoc-in a ["children" 0 "children" 0 "children" 0 "inlines"]
                           [{"kind" "span" "style" ["bold"]
                             "children" [{"kind" "text" "text" "abcd"}]}])]
      ;; Two formatting tokens inserted in six tokens, divided by five HTML descendants.
      (is (almost= (- 1.0 (/ 1.0 15)) (get (score styled) "table-teds")))
      (is (= 1.0 (get (score styled) "table-teds-struct"))))
    (let [inline (fn [n] (assoc-in a ["children" 0 "children" 0 "children" 0 "inlines"] [n]))]
      ;; An unresolved reference emits a span and falls back to its marker.
      (is (= 1.0 (get (S/table-teds (inline {"kind" "ref" "marker" "abcd"})
                                   (inline {"kind" "span" "children" [{"kind" "text" "text" "abcd"}]})) "teds"))))))
;; Hand-built golds; every expected number is worked out in the comment beside it.

(def FEAT {"paragraph" ["initial" "align"] "heading" ["level" "align"] "caption" ["align"]
           "cell" ["align"] "figure" ["subkind"]})

(defn fgold
  "A gold that annotates initials and alignment (and layout when `facts` has frags)."
  ([children] (fgold children nil))
  ([children facts]
   (cond-> (gold children :attributes FEAT)
     facts (-> (assoc "facts" facts) (assoc-in ["annotated" "layout"] (contains? facts "frags"))))))

(defn INI [t form lines] {"kind" "initial" "form" form "lines" lines "children" [{"kind" "text" "text" t}]})
(defn TX [t] {"kind" "text" "text" t})
(defn PI
  "A paragraph whose text is `text` and whose first inline is an initial."
  [id text ini rest-text & {:as extra}]
  (merge {"kind" "paragraph" "id" id "text" text "inlines" [ini (TX rest-text)]} extra))
(defn PA [t al] (assoc (P t) "align" al))

(deftest initial-split-off
  ;; gold `Once upon a time` with a 3-line drop `O`; pred: a heading `O` then `nce upon a time`
  (let [g (fgold [(PI 1 "Once upon a time" (INI "O" "drop" 3) "nce upon a time")])
        c (counts g (pred [(H "O" 2) (P "nce upon a time")]))
        m (M c)]
    (is (= [0 1 0] [(c "initial.tp") (c "initial.gold") (c "initial.pred")]))
    (is (= 0.0 (m "initial-r")))
    (is (= 0.0 (m "initial-p")))             ; nothing predicted, one expected
    (is (= 0.0 (m "initial-form-acc")))      ; gold has an initial, none was found: 0, not absent
    (is (= 0.0 (m "first-word-acc")))        ; `Once` vs `nce`
    (is (= 1.0 (m "initial-false-heading"))) ; the heading `O` is a gold initial's text: 1 of 1 gold initials
    ;; CER +1 (the space), WER +2 (`Once` -> `O`, insert `nce`): 1/16 and 2/4
    (is (almost= (/ 1.0 16) (m "cer")))
    (is (almost= 0.5 (m "wer")))))

(deftest initial-false-heading-spares-a-real-heading
  ;; the negative control: the gold's `A` really is a heading, so a predicted heading `A` is right
  (let [g (fgold [(P "An intro that opens the text") (H "A" 2) (P "Apples grow on trees")])
        c (counts g (pred [(P "An intro that opens the text") (H "A" 2) (P "Apples grow on trees")]))]
    (is (= 0 (c "initial.fh")))
    (is (= 0 (c "initial.gold")))
    (is (= 0.0 ((M c) "initial-false-heading")))))

(deftest initial-moved-to-line-three
  ;; found, right form, but the letter sits inside the text: `nce upon O a time`
  (let [g (fgold [(PI 1 "Once upon a time" (INI "O" "drop" 3) "nce upon a time")])
        c (counts g (pred [(PI 1 "nce upon O a time" (INI "O" "drop" 3) "nce upon ")]))
        m (M c)]
    (is (= [1 1 1] [(c "initial.tp") (c "initial.gold") (c "initial.pred")]))
    (is (= [1.0 1.0 1.0] (map m ["initial-p" "initial-r" "initial-form-acc"])))
    (is (= 0.0 (m "first-word-acc")))        ; `Once` vs `nce`
    (is (= 0.0 (m "initial-false-heading")))
    ;; a wrong form on the same initial
    (is (= 0.0 ((M (counts g (pred [(PI 1 "Once upon a time" (INI "O" "raised" nil) "nce upon a time")])))
                "initial-form-acc")))))

(deftest a-long-read-as-along
  ;; `A long time` with a one-letter-word initial read as `Along time`: CER +1, WER +2
  (let [g (fgold [(PI 1 "A long time" (INI "A" "drop" 2) " long time")])
        c (counts g (pred [(PI 1 "Along time" (INI "A" "drop" 2) "long time")]))
        m (M c)]
    (is (= 1.0 (m "initial-p")))
    (is (= 0.0 (m "first-word-acc")))        ; `A` vs `Along`
    (is (almost= (/ 1.0 11) (m "cer")))      ; one deleted space in 11 characters
    (is (almost= (/ 2.0 3) (m "wer")))))     ; [A long time] -> [Along time]: substitute, delete

(deftest initial-perfect-and-unannotated
  (let [g (fgold [(PI 1 "Once upon a time" (INI "O" "drop" 3) "nce upon a time")])
        m (M (counts g (pred [(PI 1 "Once upon a time" (INI "O" "drop" 3) "nce upon a time")])))]
    (is (= [1.0 1.0 1.0 1.0 0.0] (map m ["initial-p" "initial-r" "initial-form-acc" "first-word-acc" "initial-false-heading"]))))
  ;; a gold that does not annotate the attribute: no initial metric at all
  (let [c (counts (gold [(PI 1 "Once upon a time" (INI "O" "drop" 3) "nce upon a time")])
                  (pred [(H "O" 2) (P "nce upon a time")]))]
    (is (not-any? #(str/starts-with? % "initial.") (keys c)))
    (is (not-any? #(str/starts-with? % "initial") (keys (M c))))))

(deftest alignment-single-line-justify-against-start
  ;; a single-line block the gold calls `justify`, scored against `start`
  (let [g (fgold [(PA "One line only" "justify")])
        c (counts g (pred [(PA "One line only" "start")]))
        m (M c)]
    (is (= [1 1 1 1 0] (map c ["align.gold" "align.pred" "align.n" "align.paragraph.n" "align.ok"])))
    (is (= 0.0 (m "align-acc")))
    (is (= 1.0 (m "align-cov")))             ; it is aligned, just wrongly
    (is (= 1.0 (m "align-wrong")))           ; 1 wrong of 1 predicted aligned
    (is (= 0.0 (m "align-acc-paragraph"))))
  ;; no alignment predicted: coverage 0, accuracy 0 (not absent), nothing wrong
  (let [m (M (counts (fgold [(PA "One line only" "justify")]) (pred [(P "One line only")])))]
    (is (= [0.0 0.0 0.0] (map m ["align-acc" "align-cov" "align-wrong"]))))
  ;; right
  (let [m (M (counts (fgold [(PA "One line only" "justify")]) (pred [(PA "One line only" "justify")])))]
    (is (= [1.0 1.0 0.0] (map m ["align-acc" "align-cov" "align-wrong"])))))

(deftest alignment-per-kind-and-cells
  ;; gold: paragraph `start`, heading `center`, cells `end` and `center`. pred: the paragraph right,
  ;; the heading `start` (wrong), the cells `end` (right) and `end` (wrong), and one extra aligned paragraph.
  (let [cell (fn [t a] {"kind" "cell" "text" t "align" a})
        tbl (fn [a b] {"kind" "table" "children" [{"kind" "rowgroup" "children" [{"kind" "row" "children" [(cell "1" a) (cell "2" b)]}]}]})
        g (fgold [(PA "First paragraph here" "start") (assoc (H "Heading words" 1) "align" "center") (tbl "end" "center")])
        c (counts g (pred [(PA "First paragraph here" "start") (assoc (H "Heading words" 1) "align" "start") (tbl "end" "end")
                           (PA "An extra paragraph" "center")]))
        m (M c)]
    ;; paragraph: both 1, ok 1; heading: both 1, ok 0; cell: both 2, ok 1
    (is (= [1.0 0.0 0.5] (map m ["align-acc-paragraph" "align-acc-heading" "align-acc-cell"])))
    (is (= [1.0 1.0 1.0] (map m ["align-cov-paragraph" "align-cov-heading" "align-cov-cell"])))
    ;; pooled: n 4 (1+1+2), ok 2 (1+0+1), gold 4, predicted aligned 5 (2 paragraphs, 1 heading, 2 cells)
    (is (= [4 2 4 5] (map c ["align.n" "align.ok" "align.gold" "align.pred"])))
    (is (almost= 0.5 (m "align-acc")))
    (is (almost= 0.4 (m "align-wrong")))))     ; (4 - 2) / 5

(deftest alignment-unannotated-is-absent
  (let [c (counts (gold [(PA "One line only" "justify")]) (pred [(PA "One line only" "start")]))]
    (is (not-any? #(str/starts-with? % "align") (keys c)))
    (is (not-any? #(str/starts-with? % "align") (keys (M c))))))

;; ------------------------------------------------- inline styles and links (P4.7, D194) ----

(defn IT [t] {"kind" "text" "text" t})
(defn SP [styles t] {"kind" "span" "style" styles "children" [(IT t)]})
(defn LK [href t] {"kind" "link" "href" href "children" [(IT t)]})
(defn IP [inl] {"kind" "paragraph" "text" "x" "inlines" inl})
(defn ic [g p] (counts (gold [g] :inlines true) (pred [p])))

(def INL-GOLD (IP [(IT "a ") (SP ["bold"] "b c") (IT " d ") (SP ["italic"] "e") (LK "http://x" "f")]))

(deftest inline-perfect
  ;; one bold span, one italic, one link: each tp 1, gold 1, pred 1; no other style is counted at all
  (let [c (ic INL-GOLD INL-GOLD) m (M c)]
    (is (= [1 1 1] [(c "span.bold.tp") (c "span.bold.gold") (c "span.bold.pred")]))
    (is (= [1 1 1] [(c "link.tp") (c "link.gold") (c "link.pred")]))
    (is (nil? (c "span.mono.gold")))
    (is (nil? (m "span-mono-p")))
    (doseq [k ["span-bold-p" "span-bold-r" "span-bold-f1" "span-italic-f1" "link-p" "link-r" "link-f1"]]
      (is (= 1.0 (m k)) k))))

(deftest inline-wrong-missing-and-extra
  ;; pred: bold on "b" (not "b c"), no italic, an extra mono span "z", no link
  ;; bold: tp 0 of gold 1 and pred 1 -> P 0, R 0; italic: tp 0, gold 1, pred 0 -> P 0 (nothing predicted, something there), R 0;
  ;; mono: gold 0, pred 1 -> P 0, R 0; link: tp 0, gold 1, pred 0 -> P 0, R 0
  (let [p (IP [(IT "a ") (SP ["bold"] "b") (SP ["mono"] "z")])
        c (ic INL-GOLD p) m (M c)]
    (is (= [0 1 1] [(c "span.bold.tp") (c "span.bold.gold") (c "span.bold.pred")]))
    (is (= [0 1 0] [(c "span.italic.tp") (c "span.italic.gold") (c "span.italic.pred")]))
    (is (= [0 0 1] [(c "span.mono.tp") (c "span.mono.gold") (c "span.mono.pred")]))
    (is (= [0 1 0] [(c "link.tp") (c "link.gold") (c "link.pred")]))
    (doseq [k ["span-bold-p" "span-bold-r" "span-italic-p" "span-italic-r" "span-mono-p" "span-mono-r" "link-p" "link-r"]]
      (is (= 0.0 (m k)) k))))

(deftest inline-two-styles-count-once-each-and-whitespace-is-one-space
  ;; gold: one span of bold AND italic with the text "x  y" (two spaces); pred: a bold span "x y" only.
  ;; the text normalizes to "x y" on both sides; bold tp 1 of 1 and 1; italic tp 0 of gold 1, pred 0
  (let [c (ic (IP [(SP ["bold" "italic"] "x  y")]) (IP [(SP ["bold"] "x y")]))]
    (is (= [1 1 1] [(c "span.bold.tp") (c "span.bold.gold") (c "span.bold.pred")]))
    (is (= [0 1 0] [(c "span.italic.tp") (c "span.italic.gold") (c "span.italic.pred")]))))

(deftest inline-spans-count-where-they-are-and-twice-is-twice
  ;; the same bold word twice in the gold (a paragraph and a list item) and once in the pred, anywhere: tp 1 of gold 2, pred 1
  (let [g (gold [(IP [(SP ["bold"] "w")]) {"kind" "item" "text" "y" "inlines" [(SP ["bold"] "w")]}] :inlines true)
        p (pred [{"kind" "list" "children" [{"kind" "item" "text" "y" "inlines" [(SP ["bold"] "w")]}]}])
        c (counts g p)]
    (is (= [1 2 1] [(c "span.bold.tp") (c "span.bold.gold") (c "span.bold.pred")]))
    (is (almost= 1.0 ((M c) "span-bold-p")))
    (is (almost= 0.5 ((M c) "span-bold-r")))))

(deftest inline-internal-link-is-hash-and-a-span-in-a-link-is-both
  ;; an internal link (a `target`) in the gold matches an internal link in the pred whatever node it names;
  ;; a bold span inside a link counts as a span and the link counts with the text of the whole
  (let [gl {"kind" "link" "target" 5 "children" [(IT "see ") (SP ["bold"] "p. 3")]}
        pl {"kind" "link" "target" 9 "children" [(IT "see ") (SP ["bold"] "p. 3")]}
        c (ic (IP [gl]) (IP [pl]))]
    (is (= [1 1 1] [(c "link.tp") (c "link.gold") (c "link.pred")]))
    (is (= [1 1 1] [(c "span.bold.tp") (c "span.bold.gold") (c "span.bold.pred")])))
  ;; a link with another href is another link
  (let [c (ic (IP [(LK "http://a" "t")]) (IP [(LK "http://b" "t")]))]
    (is (= [0 1 1] [(c "link.tp") (c "link.gold") (c "link.pred")]))))

(deftest inline-unannotated-is-absent
  ;; a gold that does not claim its inlines has no inline counts, whatever the pred has
  (let [c (counts (gold [(P "a")]) (pred [(IP [(SP ["bold"] "a")])]))]
    (is (nil? (c "span.bold.pred")))
    (is (nil? ((M c) "span-bold-p")))))

(def LAYOUT-PAGE
  {"n" 1 "layout" [{"band" 0 "col" 0 "role" "body" "box" [0 0 90 90]}
                   {"band" 0 "col" 1 "role" "body" "box" [100 0 190 90]}]})
(def C0 [1 0 0 90 90])
(def C1 [1 100 0 190 90])
(defn AT [n & regions] (assoc n "at" (vec regions)))
(defn IDP [id t] (assoc (P t) "id" id))

(def COLS-GOLD
  ;; paragraph 1 runs from the bottom of column 0 to the top of column 1; paragraph 2 is in column 1
  (fgold [(IDP 1 "alpha beta gamma delta epsilon zeta") (IDP 2 "eta theta iota kappa")]
         {"frags" {"1" [[1 0 0] [1 0 1]] "2" [[1 0 1]]}}))

(deftest column-continuation-joined
  (let [c (counts COLS-GOLD
                  (pred [(AT (P "alpha beta gamma delta epsilon zeta") C0 C1) (AT (P "eta theta iota kappa") C1)]
                        :pages [LAYOUT-PAGE]))
        m (M c)]
    (is (= [2 2] [(c "frag.n") (c "frag.ok")]))
    (is (= 1.0 (m "frag-col-acc")))
    (is (= [1 1 1] [(c "join.gold") (c "join.tp") (c "join.pred")]))
    (is (= [1.0 1.0] [(m "join-p") (m "join-r")]))))

(deftest column-continuation-split
  ;; pred cuts paragraph 1 at the column break: each half holds 3 of its 6 words, so none holds it
  (let [c (counts COLS-GOLD
                  (pred [(AT (P "alpha beta gamma") C0) (AT (P "delta epsilon zeta") C1) (AT (P "eta theta iota kappa") C1)]
                        :pages [LAYOUT-PAGE]))
        m (M c)]
    ;; paragraph 1 (35 chars) is under 0.5 similar to either half (16 and 18 chars): unmatched, a miss
    (is (= [2 1] [(c "frag.n") (c "frag.ok")]))
    (is (= 0.5 (m "frag-col-acc")))
    (is (= [1 0 0] [(c "join.gold") (c "join.tp") (c "join.pred")]))
    (is (= [0.0 0.0] [(m "join-p") (m "join-r")]))))

(deftest column-false-join
  ;; two single-fragment paragraphs, the second starting a new column; pred runs them together
  (let [g (fgold [(IDP 1 "alpha beta gamma delta") (IDP 2 "epsilon zeta eta theta")]
                 {"frags" {"1" [[1 0 0]] "2" [[1 0 1]]}})
        c (counts g (pred [(AT (P "alpha beta gamma delta epsilon zeta eta theta") C0 C1)] :pages [LAYOUT-PAGE]))]
    ;; the boundary falls on a fragment boundary and one predicted block spans `gamma delta epsilon zeta`
    (is (= [0 0 1] [(c "join.gold") (c "join.tp") (c "join.pred")]))
    (is (= 0.0 ((M c) "join-p")))
    ;; the same two paragraphs kept apart are not a false join
    (let [c (counts g (pred [(AT (P "alpha beta gamma delta") C0) (AT (P "epsilon zeta eta theta") C1)] :pages [LAYOUT-PAGE]))]
      (is (= [0 0 0] [(c "join.gold") (c "join.tp") (c "join.pred")]))
      (is (= 1.0 ((M c) "frag-col-acc"))))))

(deftest layout-missing-scores-zero-not-absent
  ;; no `layout` on the predicted page: no region maps to a cell, every layout block misses
  (let [m (M (counts COLS-GOLD (pred [(AT (P "alpha beta gamma delta epsilon zeta") C0 C1) (AT (P "eta theta iota kappa") C1)])))]
    (is (= 0.0 (m "frag-col-acc")))
    (is (= 1.0 (m "join-r"))))                ; the text is one block whatever the layout
  ;; a gold that is not a layout document has no column metric
  (let [g (fgold [(IDP 1 "alpha beta") (IDP 2 "gamma delta")] {"frags" {"1" [[1 0 0]] "2" [[1 0 0]]}})
        g (assoc-in g ["annotated" "layout"] false)
        c (counts g (pred [(P "alpha beta") (P "gamma delta")]))]
    (is (not-any? #(or (str/starts-with? % "frag") (str/starts-with? % "join")) (keys c)))))

(def HY-GOLD
  (fgold [(IDP 1 "a hyphenated word")] {"hyphens" [{"id" 1 "printed" "hy-phenated" "word" "hyphenated"}]}))
(defn with-locale [p loc] (assoc p "settings" {"locale" loc}))

(deftest hyphen-kept-and-joined
  ;; no locale: emon keeps the printed hyphen (SPEC 3.6) and the gold is read in its printed form
  (let [c (counts HY-GOLD (with-locale (pred [(P "a hy-phenated word")]) nil))]
    (is (= [1 1] [(c "hyphen.n") (c "hyphen.ok")]))
    (is (= 0 (c "text.char-err")))
    (is (= 0 (c "text.word-err"))))
  ;; no locale but the word joined: CER 1 of 18 (`a hy-phenated word`), hyphen wrong
  (let [c (counts HY-GOLD (with-locale (pred [(P "a hyphenated word")]) nil))]
    (is (= [1 0] [(c "hyphen.n") (c "hyphen.ok")]))
    (is (= [1 18] [(c "text.char-err") (c "text.chars")])))
  ;; locale en: the gold as written, the joined word is right
  (let [c (counts HY-GOLD (with-locale (pred [(P "a hyphenated word")]) "en"))]
    (is (= [1 1 0] [(c "hyphen.n") (c "hyphen.ok") (c "text.char-err")])))
  ;; locale en but the hyphen kept: CER 1 of 17, hyphen wrong
  (let [c (counts HY-GOLD (with-locale (pred [(P "a hy-phenated word")]) "en"))]
    (is (= [1 0 1 17] [(c "hyphen.n") (c "hyphen.ok") (c "text.char-err") (c "text.chars")])))
  ;; a gold without facts is scored as written whatever the locale
  (is (= 1 ((counts (gold [(P "a hyphenated word")]) (with-locale (pred [(P "a hy-phenated word")]) nil)) "text.char-err"))))

(def BIG-GOLD
  (let [cell (fn [t a] {"kind" "cell" "text" t "align" a})]
    (-> (fgold [(PI 1 "Once upon a time there was a king" (INI "O" "drop" 3) "nce upon a time there was a king" :align "start")
                (assoc (H "Chapter" 1) "id" 2 "align" "center")
                (assoc (IDP 3 "alpha beta gamma delta epsilon zeta") "align" "justify")
                (assoc (IDP 4 "eta theta iota kappa") "align" "end")
                (IDP 5 "a hyphenated word here")
                {"kind" "table" "id" 6 "children" [{"kind" "rowgroup" "children"
                                                    [{"kind" "row" "children" [(cell "1" "end") (cell "2" "center")]}]}]}]
               {"frags" {"1" [[1 0 0]] "2" [[1 0 0]] "3" [[1 0 0] [1 0 1]] "4" [[1 0 1]] "5" [[1 0 1]]}
                "hyphens" [{"id" 5 "printed" "hy-phenated" "word" "hyphenated"}]})
        (assoc-in ["model" "settings"] {"locale" nil}))))

(def NEW-METRICS ["initial-p" "initial-r" "initial-form-acc" "first-word-acc" "align-acc" "align-cov"
                  "align-acc-paragraph" "align-acc-heading" "align-acc-cell" "frag-col-acc" "join-p" "join-r" "hyphen-acc"])

(deftest oracle-perfect-and-degraded-worse-on-layout-features
  (let [o (M (get (S/score (O/predict-gold BIG-GOLD) BIG-GOLD) "counts"))
        d (M (get (S/score (D/predict-gold BIG-GOLD "pdf") BIG-GOLD) "counts"))]
    (is (= 0.0 (o "cer")))
    (is (= 0.0 (o "wer")))
    (doseq [k NEW-METRICS] (is (= 1.0 (o k)) k))
    (is (= 0.0 (o "initial-false-heading")))
    (is (= 0.0 (o "align-wrong")))
    ;; every damage moves its metrics
    (doseq [k NEW-METRICS] (is (< (d k) 1.0) k))
    (is (> (d "initial-false-heading") 0.0))   ; the split-off `O` heading
    (is (= 0.0 (d "initial-r")))
    (is (= 0.0 (d "align-cov")))               ; every align dropped
    (is (= 0.0 (d "frag-col-acc")))            ; no layout
    (is (= 0.0 (d "join-r")))                  ; the multi-fragment paragraph is cut
    (is (> (d "cer") 0.0))))

(deftest table-model-fixtures-still-count-in-all
  (let [t {"kind" "table" "children"
           [{"kind" "rowgroup" "role" "body" "children"
             [{"kind" "row" "children" [{"kind" "cell" "text" "x"}]}]}]}
        truth (gold [t])
        predictor (fn [id _ _ _] (if (= id "table-whitespace") (pred [t]) (pred [])))]
    (with-redefs [A/corpus (fn [] [["table-whitespace" "pdf" "input" "gold"]
                                  ["table-ledger-sparse" "pdf" "input" "gold"]])
                  A/doc-graphics (fn [_] {})
                  pyjson/parse-file (fn [_] truth)]
      (let [[entry _] (A/evaluate predictor)]
        ;; Missing the entire new table still lowers the global score: 1 TP, 1 FN.
        (is (= 0.666667 (get-in entry ["pdf/all" ":block-table-f1"])))
        (is (= 1.0 (get-in entry ["pdf/tables" ":block-table-f1"])))
        (is (= 0.0 (get-in entry ["pdf/table-model" ":block-table-f1"])))))))

(deftest new-fixtures-preserve-historical-calibration-populations
  (let [truth (gold [])
        predictor (fn [id _ _ _] {"id" id})]
    (with-redefs [A/corpus (fn [] [["table-whitespace" "pdf" "input" "gold"]
                                  ["table-ledger-sparse" "pdf" "input" "gold"]])
                  A/doc-graphics (fn [_] {})
                  pyjson/parse-file (fn [_] truth)
                  CR/counts (fn [p _] (CAL/row-counts
                                      [["table" "text" (if (= "table-whitespace" (get p "id")) 1000 0) 1000]]))]
      (let [[entry _] (A/evaluate predictor)]
        (doseq [unit ["all" "table" "table-text"] metric ["ece" "brier"]]
          (is (= 0.0 (get-in entry ["pdf/all" (str ":conf-" metric "-" unit)])))
          (is (= 1.0 (get-in entry ["pdf/table-model" (str ":conf-" metric "-" unit)]))))))))

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
      (doseq [[k v] o :when (not (S/LOWER-IS-BETTER k))]
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

(deftest unavailable-predictor-is-not-a-failure
  ;; exit 3 is the protocol's "not available" (a predictor without its toolchain says so), not a failure
  (is (nil? (first (A/run-predictor ["sh" "-c" "echo no toolchain >&2; exit 3"] "x" "pdf" "x" "x")))))

(let [res (binding [clojure.test/*test-out* *err*]
             (if-let [only (System/getenv "EMON_TEST_ONLY")]
               (let [vs (vec (filter #(re-find (re-pattern only) (str %)) (vals (ns-publics (quote accuracy.test-score)))))]
                 (binding [*out* *err*] (println "running" (count vs) "tests"))
                 (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
                   (clojure.test/test-vars vs)
                   @clojure.test/*report-counters*))
               (run-tests (quote accuracy.test-score))))]
  (if (zero? (+ (:fail res) (:error res)))
    (println (format "  ok   accuracy-test: %d laws of the scorer and the ratchet hold (CER/WER, P/R/F1, heading level, boundaries, IoU, ledger, oracle perfect, degraded worse)" (:test res)))
    (do (println (format "  FAIL accuracy-test: %d failure(s)" (+ (:fail res) (:error res))))
        (System/exit 1))))
