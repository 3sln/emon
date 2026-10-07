(ns accuracy.confrows
  "What each confidence of a Model claims, and whether the gold agrees (P10.1, SPEC 9.6, 3.5).

    (rows pred gold) -> [[kind class conf y] ...]

for every node of the prediction that carries a `conf` and that the gold can judge.  `conf` is the node's
confidence (per mille); `y` is 1000 when the node is right, 0 when it is not, and for a text run the share of
its words that are right; `class` is where the node's own text came from: `text` (the text layer), `ocr`,
`fused` (both) or `infer` (neither: structure).

WHAT 'RIGHT' MEANS, per kind (the gold's `annotated` says which kinds it judges; a kind it does not annotate has
no row, never a wrong one):
  * heading paragraph list item table figure caption seal note code quote: the scorer's block match
    (reading-order alignment on text) pairs the node with a gold block of the SAME kind whose text is at least
    0.9 similar; a heading's level must also be the gold's when the gold annotates it;
  * section: a gold section of the same level has the same first heading text (multiset);
  * cell: the tuple [row, colspan, rowspan, text] is in the gold's matched table (an unmatched table has none);
    row and rowgroup: every cell of it is;
  * text (an inline run): the share of its words that the difflib alignment finds in the matched gold block;
  * span, link: the (style or href, text) key is the gold's (multiset); initial: the matched gold block has an
    initial of the same text;
  * furniture: every (role, page, text) it stands for is the gold's; dropped: the (reason, page, text) is.
The kinds the gold has no judgement of (break, ref, linebreak, field, formula) have no row."
  (:require [accuracy.calib :as C]
            [accuracy.score :as S]
            [clojure.string :as str]))

(def ^:private SIM-OK 0.9)

(defn src-class [n]
  (let [s (set (get n "src"))
        tl (or (contains? s "text") (contains? s "hidden-text"))
        oc (contains? s "ocr")]
    (cond (and tl oc) "fused" oc "ocr" tl "text" :else "infer")))

(defn- permille [ok] (if ok 1000 0))

(defn- has-conf? [n] (integer? (get n "conf")))

(defn- consume
  "[ok ms'] : the key's count in ms, decremented."
  [ms k]
  (let [c (get ms k 0)] (if (pos? c) [true (assoc ms k (dec c))] [false ms])))

(defn- consume-all
  "[ok ms'] for several keys: right when every one is."
  [ms ks]
  (reduce (fn [[ok ms] k] (let [[o m] (consume ms k)] [(and ok o) m])) [(boolean (seq ks)) ms] ks))

;; ------------------------------------------------------------------ tables ----

(defn- cell-tuples
  "[[ri colspan rowspan text] ...] of a table, rows in document order."
  [t]
  (vec (for [[ri r] (map-indexed vector (filter #(= "row" (get % "kind")) (S/walk t)))
             c (filter #(= "cell" (get % "kind")) (get r "children"))]
         [ri (get c "colspan" 1) (get c "rowspan" 1) (S/norm (get c "text" ""))])))

(defn- table-rows
  "Rows for one predicted table: its cells, rows and rowgroups, judged against `gms`, a multiset of the gold
  table's cell tuples (nil when the table is unmatched)."
  [t gms]
  (let [out (volatile! []) ms (volatile! (or gms {})) ri (volatile! -1)
        emit! (fn [n ok] (when (has-conf? n) (vswap! out conj [(get n "kind") (src-class n) (get n "conf") (permille ok)])))]
    (letfn [(visit [n]
              (case (get n "kind")
                "row" (let [i (vswap! ri inc)
                           oks (doall (for [c (get n "children") :when (= "cell" (get c "kind"))]
                                        (let [k [i (get c "colspan" 1) (get c "rowspan" 1) (S/norm (get c "text" ""))]
                                              [ok m] (consume @ms k)]
                                          (vreset! ms m)
                                          (emit! c ok)
                                          ok)))]
                        (emit! n (and (seq oks) (every? true? oks)))
                        oks)
                "rowgroup" (let [oks (doall (mapcat visit (get n "children")))]
                             (emit! n (and (seq oks) (every? true? oks)))
                             oks)
                (doall (mapcat visit (get n "children")))))]
      (visit t))
    @out))

;; ------------------------------------------------------------------ inlines ----

(defn- text-leaves [b]
  (vec (for [i (get b "inlines") d (S/walk i) :when (and (= "text" (get d "kind")) (string? (get d "text")))] d)))

(defn- text-rows
  "One row per `text` run of a block with a conf: the share of its words the alignment finds in the gold block."
  [pb gtext]
  (let [leaves (text-leaves pb)
        toks (mapv #(S/split-ws (S/norm (get % "text"))) leaves)
        pw (vec (apply concat toks))
        gw (vec (S/split-ws gtext))
        hit (set (for [[a b size] (S/matching-blocks gw pw) k (range size)] (+ b k)))]
    (loop [ls leaves, ts toks, off 0, out []]
      (if (empty? ls)
        out
        (let [n (count (first ts))
              out (if (and (has-conf? (first ls)) (pos? n))
                    (conj out ["text" (src-class (first ls)) (get (first ls) "conf")
                               (long (Math/round (* 1000.0 (/ (double (count (filter #(contains? hit (+ off %)) (range n)))) n))))])
                    out)]
          (recur (rest ls) (rest ts) (+ off n) out))))))

(defn- unmatched-text-rows [pb]
  (vec (for [l (text-leaves pb) :when (has-conf? l)] ["text" (src-class l) (get l "conf") 0])))

;; ------------------------------------------------------------------ the rows ----

(defn- section-key [n]
  [(get n "level")
   (S/norm (or (some (fn [c] (when (= "heading" (get c "kind")) (get c "text"))) (get n "children")) ""))])

(defn- any-conf?
  "Does any node, furniture entry or dropped entry of the prediction carry a conf? (The oracle's Model and the fixtures'
  have none: they cost no alignment.)"
  [pred]
  (boolean (or (some #(has-conf? %) (S/walk (or (get pred "body") {})))
               (some #(has-conf? %) (mapcat S/walk (get pred "furniture")))
               (some #(has-conf? %) (get pred "dropped"))
               (some #(has-conf? %) (mapcat (fn [n] (mapcat S/walk (get n "inlines"))) (S/walk (or (get pred "body") {})))))))

(declare rows*)

(defn rows [pred gold]
  (if-not (any-conf? pred) [] (rows* pred gold)))

(defn- rows* [pred gold]
  (let [ann (get gold "annotated")
        gm (get gold "model")
        gb (let [b (get gm "body") hy (get-in gold ["facts" "hyphens"])]
             (if (and (seq hy) (nil? (get-in pred ["settings" "locale"]))) (S/rewrite-hyphens b hy) b))
        pb (or (get pred "body") {"id" 0 "kind" "document"})
        structure (set (get ann "structure"))
        kset (set (filter structure S/BLOCK-KINDS))
        g (S/blocks gb kset) p (S/blocks pb kset)
        pairs (S/match g p)
        by-p (into {} (map (fn [[i j]] [j i]) pairs))
        gtext (mapv S/block-text g) ptext (mapv S/block-text p)
        level? (contains? (set (get-in ann ["attributes" "heading"])) "level")
        out (volatile! [])
        add! (fn [kind n y] (when (has-conf? n) (vswap! out conj [kind (src-class n) (get n "conf") y])))]
    ;; ---- blocks, their text runs, tables
    (doseq [[j n] (map-indexed vector p)]
      (let [k (get n "kind") i (by-p j)
            ok (boolean (and i (= k (get-in g [i "kind"]))
                             (>= (S/sim (gtext i) (ptext j)) SIM-OK)
                             (or (not (and level? (= k "heading")))
                                 (S/pyeq (get-in g [i "level"]) (get n "level")))))]
        (add! k n (permille ok))
        (when (and (get ann "text") (seq (text-leaves n)))
          (vswap! out into (if i (text-rows n (gtext i)) (unmatched-text-rows n))))
        (when (and (= k "table") (contains? structure "cell"))
          (vswap! out into (table-rows n (when (and i (= "table" (get-in g [i "kind"])))
                                           (frequencies (cell-tuples (g i)))))))
        ;; an initial: the matched gold block has one of the same text
        (when (and i (get ann "inlines") (S/initial-of n))
          (let [gi (S/initial-of (g i))]
            (add! "initial" (S/initial-of n)
                  (permille (and gi (= (S/norm (S/leaf-text gi)) (S/norm (S/leaf-text (S/initial-of n)))))))))))
    ;; ---- sections
    (when (contains? structure "section")
      (let [ms (volatile! (frequencies (map section-key (filter #(= "section" (get % "kind")) (S/walk gb)))))]
        (doseq [n (S/walk pb) :when (= "section" (get n "kind"))]
          (let [[ok m] (consume @ms (section-key n))]
            (vreset! ms m)
            (add! "section" n (permille ok))))))
    ;; ---- spans and links, as multisets over the body (the scorer's own keys)
    (when (get ann "inlines")
      (let [sms (volatile! (frequencies (S/span-keys gb)))
            lms (volatile! (frequencies (S/link-keys gb)))]
        (doseq [d (S/inline-nodes pb)]
          (case (get d "kind")
            "span" (let [t (S/norm (S/leaf-text d))]
                     (when (seq t)
                       (let [[ok m] (consume-all @sms (for [s (get d "style")] [s t]))]
                         (vreset! sms m)
                         (add! "span" d (permille ok)))))
            "link" (let [[ok m] (consume @lms (first (S/link-keys {"inlines" [d]} )))]
                     (vreset! lms m)
                     (add! "link" d (permille ok)))
            nil))))
    ;; ---- furniture and the dropped ledger
    (when (or (get ann "furniture") (seq (get ann "dropped")))
      (let [reasons (set (get ann "dropped"))
            gx (volatile! (merge (S/ledger gm reasons) (when (get ann "furniture") (S/furniture gm))))]
        (when (get ann "furniture")
          (doseq [f (or (get pred "furniture") [])]
            (let [ks (keys (S/furniture {"furniture" [f]}))
                  [ok m] (consume-all @gx ks)]
              (vreset! gx m)
              (add! "furniture" f (permille ok)))))
        (doseq [d (or (get pred "dropped") []) :when (contains? reasons (get d "reason"))]
          (let [ks (keys (S/ledger {"dropped" [d]} reasons))
                [ok m] (consume-all @gx ks)]
            (vreset! gx m)
            (add! "dropped" d (permille ok))))))
    @out))

(defn counts
  "The pooled-ready counts of one document's rows (`accuracy.calib/row-counts`)."
  [pred gold]
  (C/row-counts (rows pred gold)))
