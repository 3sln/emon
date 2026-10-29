(ns table.abscore
  "The scores of `bb tables-ab` (TR-design): per document counts, pooled metrics, the frozen baseline and the pre-registered
  acceptance criteria. Every score is a count or a ratio; no document text is written anywhere.

  METRICS (pooled = micro-averaged counts):
    detect-p/-r/-f1    table detection, accuracy.score's block matching (geometry IoU when both sides have boxes; gold in
                       image pixels is rescaled to the Model's points, and a gold table without a box gets its cells' union)
    adj-f1             ICDAR 2013 adjacency relations of non-empty cells (accuracy.graphics)
    teds / teds-struct TEDS over the canonical table trees (accuracy.score), averaged over GOLD tables (a missed table scores 0)
    hb-acc             header binding: the multiset of (body cell text, its column's header text) pairs, recall against gold;
                       for ledgers, the share of transaction amounts whose column header names the gold role (debit, credit,
                       balance); for bsb, the gold column labels found as header cells
    rows-acc/cols-acc  matched tables whose row count / column count equals gold
    link-p/-r          continuation links: a link is two consecutive pages of one table. Synthetic: gold `continued`; ledgers:
                       consecutive gold transactions on different pages owned by one table (recall) and links whose both sides
                       own gold transactions (precision); named: source-reviewed page pairs with row-key fingerprints
    row-r/row-p        ledgers: gold transactions whose balance AND amount sit in one predicted row; predicted amount rows of
                       the ledger tables that own a gold transaction
    checks             named: the share of source-reviewed expectations met (test/tables-ab/named.json)
    cpu-page           (user + sys) seconds per page of the conversion; rss-max / rss-med: peak resident bytes"
  (:require [accuracy.score :as S]
            [accuracy.graphics :as G]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ the Model's tables

(defn walk [n] (tree-seq map? #(get % "children") n))
(defn tables [body] (filter #(= "table" (get % "kind")) (walk body)))

(defn norm [s]
  (str/join " " (remove str/blank? (str/split (str/lower-case (or s "")) #"\s+"))))

(defn grid
  "A table -> [{:r :c :rs :cs :text :role :page}], HTML placement of spans."
  [tbl]
  (let [rows (for [rg (get tbl "children") :when (= "rowgroup" (get rg "kind")) row (get rg "children") :when (= "row" (get row "kind"))]
               [(get rg "role" "body") row])]
    (loop [rows (seq rows) r 0 occ #{} cells []]
      (if-not rows
        cells
        (let [[role row] (first rows)
              [occ cells] (loop [cs (seq (filter #(= "cell" (get % "kind")) (get row "children"))) c 0 occ occ cells cells]
                            (if-not cs
                              [occ cells]
                              (let [c (loop [c c] (if (occ [r c]) (recur (inc c)) c))
                                    cell (first cs) rs (max 1 (get cell "rowspan" 1)) cw (max 1 (get cell "colspan" 1))
                                    occ (into occ (for [dr (range rs) dc (range cw)] [(+ r dr) (+ c dc)]))]
                                (recur (next cs) (+ c cw) occ
                                       (conj cells {:r r :c c :rs rs :cs cw :text (norm (get cell "text")) :role role
                                                    :page (some-> (get cell "at") first first)})))))]
          (recur (next rows) (inc r) occ cells))))))

(defn n-rows [tbl] (count (for [rg (get tbl "children") :when (= "rowgroup" (get rg "kind")) row (get rg "children") :when (= "row" (get row "kind"))] row)))
(defn n-cols [tbl] (or (get tbl "columns") (let [g (grid tbl)] (apply max 0 (map #(+ (:c %) (:cs %)) g)))))

(defn head-path
  "column -> the header text over it (head cells covering the column, top to bottom)."
  [cells]
  (let [heads (sort-by (juxt :r :c) (filter #(and (= "head" (:role %)) (seq (:text %))) cells))]
    (fn [c] (str/join " / " (for [h heads :when (and (<= (:c h) c) (< c (+ (:c h) (:cs h))))] (:text h))))))

(defn pages-of [tbl]
  (let [at (get tbl "at")]
    (vec (sort (distinct (concat (when (sequential? at) (map first at))
                                 (keep :page (grid tbl))))))))

(defn links-of
  "A table's continuation links: [from to] for consecutive pages it spans."
  [tbl]
  (let [ps (pages-of tbl)] (vec (for [[a b] (map vector ps (rest ps)) :when (= b (inc a))] [a b]))))

;; ------------------------------------------------------------------ numbers and row keys

(defn amount
  "A cell's money amount in cents, or nil: digits with a two-digit decimal part, after currency marks and Cr/Dr."
  [t]
  (let [s (-> (or t "") (str/replace #"(?i)\b(rs|inr|usd)\.?" "") (str/replace #"(?i)\b(cr|dr)\.?\s*$" "") (str/replace #"[₹$€£,\s]" ""))
        neg (or (str/starts-with? s "-") (str/starts-with? s "("))
        s (str/replace s #"[()\-+]" "")]
    (when (re-matches #"\d+\.\d\d" s)
      (cond-> (Math/round (* 100.0 (parse-double s))) neg -))))

(def NUMBER #"^[\(\-–]?\$?[0-9][0-9,]*(\.[0-9]+)?\)?%?$")
(defn numeric? [t] (boolean (re-matches NUMBER (str/trim (str/replace (or t "") "$" "")))))

(defn sha1 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes (str s) "UTF-8")))) 0 12)))

(defn row-keys
  "{page #{fingerprint}} of a table's body rows: the hash of the row's numeric cells in order."
  [tbl]
  (let [cells (filter #(= "body" (:role %)) (grid tbl))]
    (reduce (fn [m [[r page] cs]]
              (let [nums (map :text (sort-by :c (filter #(numeric? (:text %)) cs)))]
                (if (and page (seq nums)) (update m page (fnil conj #{}) (sha1 (str/join "|" nums))) m)))
            {} (group-by (fn [c] [(:r c) (:page c)]) cells))))

;; ------------------------------------------------------------------ gold normalisation

(defn- scale-at [at k] (mapv (fn [[pg & xs]] (into [pg] (map #(* k %) xs))) at))

(defn norm-gold
  "Gold in image pixels -> the Model's points (per page factor w_model / w_gold); a gold table without a box gets its cells' union."
  [gold model]
  (let [gp (into {} (for [pg (get-in gold ["model" "pages"])] [(get pg "n") pg]))
        mp (into {} (for [pg (get model "pages")] [(get pg "n") pg]))
        k (fn [n] (let [g (gp n) m (mp n)]
                    (if (and g m (get g "px") (pos? (get g "w" 0))) (/ (double (get m "w")) (double (get g "w"))) 1.0)))
        fix (fn fix [n]
              (let [n (if (sequential? (get n "at")) (assoc n "at" (vec (mapcat #(scale-at [%] (k (first %))) (get n "at")))) n)
                    n (if (seq (get n "children")) (assoc n "children" (mapv fix (get n "children"))) n)]
                (if (and (= "table" (get n "kind")) (not (seq (get n "at"))))
                  (let [boxes (for [c (walk n) :when (= "cell" (get c "kind")) b (get c "at")] b)]
                    (if (seq boxes)
                      (assoc n "at" (vec (for [[pg bs] (group-by first boxes)]
                                           [pg (apply min (map #(nth % 1) bs)) (apply min (map #(nth % 2) bs))
                                            (apply max (map #(nth % 3) bs)) (apply max (map #(nth % 4) bs))])))
                      n))
                  n)))]
    (update-in gold ["model" "body"] fix)))

;; ------------------------------------------------------------------ gold kinds

(defn emon-gold-counts
  "Counts against an emon-format gold (synthetic, ICDAR 2013, FinTabNet, cTDaR)."
  [model gold item]
  (let [gold (norm-gold gold model)
        structure? (get item "structure" true)
        c (get (S/score model gold {:variant "pdf"}) "counts")
        g (S/blocks (get-in gold ["model" "body"]) #{"table"})
        pr (S/blocks (get model "body") #{"table"})
        pairs (if (and (seq g) (seq pr)) (S/match g pr) [])
        heads? (some (fn [t] (some #(= "head" (get % "role")) (get t "children"))) g)
        cont? (some #{"continued"} (get-in gold ["annotated" "attributes" "table"]))
        hb (fn [t] (let [cells (grid t) hp (head-path cells)]
                     (frequencies (for [x cells :when (and (= "body" (:role x)) (seq (:text x)))
                                        :let [h (hp (:c x))] :when (seq h)] [(:text x) h]))))
        acc (atom {})
        add (fn [k v] (swap! acc update k (fnil + 0) v))]
    (add "detect.tp" (get c "block.table.tp" 0)) (add "detect.gold" (get c "block.table.gold" 0)) (add "detect.pred" (get c "block.table.pred" 0))
    (when (get c "gfx.adj.gold")
      (add "adj.tp" (get c "gfx.adj.tp" 0)) (add "adj.gold" (get c "gfx.adj.gold" 0)) (add "adj.pred" (get c "gfx.adj.pred" 0)))
    (when (and structure? (pos? (get c "table.n" 0)))
      (add "teds.n" (get c "table.n")) (add "teds.sum" (get c "table.teds-sum" 0)) (add "teds-struct.sum" (get c "table.teds-struct-sum" 0)))
    (when structure?
      (doseq [[i j] pairs]
        (add "shape.n" 1)
        (add "rows.ok" (if (= (n-rows (g i)) (n-rows (pr j))) 1 0))
        (add "cols.ok" (if (= (n-cols (g i)) (n-cols (pr j))) 1 0))))
    (when heads?
      (doseq [[i j] pairs]
        (let [a (hb (g i)) b (hb (pr j))]
          (add "hb.gold" (reduce + 0 (vals a)))
          (add "hb.tp" (reduce + 0 (for [[x n] a] (min n (get b x 0))))))))
    (when cont?
      (let [gl (fn [t] (max 0 (dec (get t "continued" 1))))
            pl (fn [t] (count (links-of t)))
            matched (set (map second pairs))]
        (add "link.gold" (reduce + 0 (map gl g)))
        (add "link.pred" (reduce + 0 (map pl pr)))
        (add "link.tp" (reduce + 0 (for [[i j] pairs] (min (gl (g i)) (pl (pr j))))))
        (add "link.unmatched-pred" (reduce + 0 (for [j (range (count pr)) :when (not (matched j))] (pl (pr j)))))))
    @acc))

(defn role-of [h]
  (let [h (norm h)]
    (cond (re-find #"balance|saldo|solde" h) "balance"
          (re-find #"debit|withdraw|\bdr\b|payment|charge" h) "debit"
          (re-find #"credit|deposit|\bcr\b|receipt" h) "credit")))

(defn agami-counts
  "Ledger counts against an agami transaction list (row ownership, amount roles, continuation)."
  [model gold]
  (let [ts (vec (tables (get model "body")))
        rows (vec (for [[ti t] (map-indexed vector ts)
                        :let [cells (grid t) hp (head-path cells)]
                        [[r] cs] (sort-by ffirst (group-by (fn [x] [(:r x)]) (filter #(= "body" (:role %)) cells)))
                        :let [am (for [x cs :let [a (amount (:text x))] :when a] {:a a :role (role-of (hp (:c x)))})]]
                    {:t ti :r r :page (some :page cs) :amounts (vec am)}))
        txs (vec (remove #(get % "failed") (get gold "transactions")))
        cents (fn [v] (when v (Math/round (* 100.0 (double v)))))
        ;; monotone greedy: each transaction takes the first unused row at or after the previous match holding its balance and amount
        matches (loop [i 0 from 0 out []]
                  (if (>= i (count txs))
                    out
                    (let [tx (txs i) b (cents (get tx "balance")) a (cents (or (get tx "debit") (get tx "credit")))
                          role (cond (get tx "debit") "debit" (get tx "credit") "credit")
                          hit (first (for [k (range from (count rows))
                                           :let [vs (set (map :a (:amounts (rows k))))]
                                           :when (and (vs b) (or (nil? a) (vs a)))] k))]
                      (if hit
                        (recur (inc i) (inc hit) (conj out {:tx i :row hit :b b :a a :role role}))
                        (recur (inc i) from (conj out {:tx i :row nil}))))))
        hits (filter :row matches)
        ledger (set (map #(:t (rows (:row %))) hits))
        amount-rows (count (filter #(and (ledger (:t %)) (seq (:amounts %))) rows))
        role-ok (fn [{:keys [row b a role]}]
                  (let [am (:amounts (rows row))
                        okb (some #(and (= b (:a %)) (= "balance" (:role %))) am)
                        oka (or (nil? a) (some #(and (= a (:a %)) (= role (:role %))) am))]
                    (+ (if okb 1 0) (if (and a oka) 1 0))))
        role-n (fn [{:keys [a]}] (if a 2 1))
        cross (for [[x y] (map vector matches (rest matches)) :when (and (:row x) (:row y))
                    :let [rx (rows (:row x)) ry (rows (:row y))] :when (not= (:page rx) (:page ry))]
                (= (:t rx) (:t ry)))
        owned (into {} (for [h hits :let [r (rows (:row h))]] [[(:t r) (:page r)] true]))
        plinks (for [[ti t] (map-indexed vector ts) [a b] (links-of t)] (and (owned [ti a]) (owned [ti b])))]
    {"row.gold" (count txs) "row.tp" (count hits) "row.pred" amount-rows
     "hb.gold" (reduce + 0 (map role-n hits)) "hb.tp" (reduce + 0 (map role-ok hits))
     "link.gold" (count cross) "link.tp-r" (count (filter true? cross))
     "link.pred" (count plinks) "link.tp" (count (filter true? plinks))}))

(def DATE #"^(\d{1,2}[-/.](\d{1,2}|[a-z]{3})([-/.]\d{2,4})?|\d{1,2} [a-z]{3,9}( \d{2,4})?|[a-z]{3,9} \d{1,2}|\d{4}-\d{2}-\d{2})\b.*")

(defn bsb-counts
  "bank-statement-benchmark statement-info: header labels found as header cells; transaction rows (a date and an amount) against the count."
  [model info]
  (let [ts (tables (get model "body"))
        heads (set (for [t ts x (grid t) :when (and (= "head" (:role x)) (seq (:text x)))] (:text x)))
        labels (map #(norm (first (str/split (get % "label") #"\n"))) (get info "columns"))
        found (count (filter (fn [l] (some #(str/includes? % l) heads)) labels))
        n (count (for [t ts [_ cs] (group-by :r (filter #(= "body" (:role %)) (grid t)))
                       :let [cs (sort-by :c cs)]
                       :when (and (some #(re-matches DATE (:text %)) cs) (some #(amount (:text %)) cs))] 1))
        want (get info "transactionCount")]
    {"hb.gold" (count labels) "hb.tp" found
     "bsb.rows" n "bsb.want" want "bsb.row-err" (Math/abs (long (- n want)))}))

;; ------------------------------------------------------------------ named expectations

(defn- body-rows [t]
  (for [[_ cs] (sort-by key (group-by :r (filter #(= "body" (:role %)) (grid t))))] (vec (sort-by :c cs))))

(defn- int-cell [x] (when (re-matches #"\d{1,3}" (or (:text x) "")) (parse-long (:text x))))

(defn check
  "One expectation -> true or false."
  [model {:strs [kind page pages expect min_values min_cols links] :as e}]
  (let [ts (vec (tables (get model "body")))
        on (fn [pg] (filter #(some #{pg} (pages-of %)) ts))]
    (case kind
      "numbered-rows"
      (let [ks (set (for [t (on page) row (body-rows t)
                          :let [xs (filter #(seq (:text %)) row) k (some-> (first xs) int-cell)]
                          :when (and k (<= 1 k expect) (>= (count (filter #(numeric? (:text %)) (rest xs))) min_values))] k))]
        (= expect (count ks)))
      "paired-numbers"
      (let [rs (for [t (on page) row (body-rows t) :let [xs (filter #(seq (:text %)) row)]
                     :when (or (some-> (first xs) int-cell) (some-> (last xs) int-cell))] xs)]
        (and (seq rs) (every? #(let [a (int-cell (first %)) b (int-cell (last %))] (and a b (= a b) (> (count %) 2))) rs)))
      "head-cover"
      (boolean (some (fn [t] (>= (count (set (for [x (grid t) :when (and (= "head" (:role x)) (seq (:text x)) (= page (:page x)))
                                                 c (range (:c x) (+ (:c x) (:cs x)))] c)))
                                 min_cols))
                     (on page)))
      "head-rows"
      (every? (fn [pg] (some (fn [t] (= expect (count (distinct (for [x (grid t) :when (and (= "head" (:role x)) (= pg (:page x)))] (:r x))))))
                             (on pg)))
              pages)
      "dated-rows"
      (= expect (count (for [t ts row (body-rows t) :let [xs (filter #(seq (:text %)) row)]
                             :when (and (seq xs) (re-matches DATE (:text (first xs))))] 1)))
      "links"
      (let [pred (for [t ts l (links-of t) :let [k (row-keys t)]] {:l l :from (get k (first l) #{}) :to (get k (second l) #{})})
            ok (fn [g p] (and (= (get g "pages") (:l p))
                              (or (get g "any") (seq (filter (set (get g "from")) (:from p))) (seq (filter (set (get g "to")) (:to p))))))
            tp (count (filter (fn [g] (some #(ok g %) pred)) links))
            fp (count (remove (fn [p] (some #(ok % p) links)) pred))]
        {:tp tp :gold (count links) :pred (count pred) :fp fp :pass (and (= tp (count links)) (zero? fp))}))))

(defn named-counts [model exp id]
  (let [es (get-in exp ["docs" id] [])
        rs (for [e es] [e (check model e)])]
    (reduce (fn [m [e r]]
              (let [pass (if (map? r) (:pass r) r)]
                (cond-> (-> m (update "checks.n" (fnil inc 0)) (update "checks.ok" (fnil + 0) (if pass 1 0))
                            (assoc-in ["checks" (get e "name")] pass))
                  (map? r) (-> (update "link.gold" (fnil + 0) (:gold r)) (update "link.tp" (fnil + 0) (:tp r))
                               (update "link.pred" (fnil + 0) (:pred r)) (update "link.tp-r" (fnil + 0) (:tp r))))))
            {} rs)))

;; ------------------------------------------------------------------ per document and pooled

(defn read-json [path] (json/parse-string (slurp path)))

(defn score-doc [pop item model named-exp]
  (let [gold-path (get item "gold")
        part (get item "part")]
    (cond
      (= pop "controls") {"tables" (count (tables (get model "body"))) "pages" (count (get model "pages"))}
      (= pop "named") (named-counts model named-exp (get item "id"))
      (or (= pop "ledgers") (= part "ledgers")) (agami-counts model (read-json gold-path))
      (= part "bsb") (bsb-counts model (read-json gold-path))
      :else (emon-gold-counts model (read-json gold-path) item))))

(defn ratio [a b] (when (and a b (pos? b)) (/ (double a) b)))
(defn f1 [p r] (when (and p r) (if (zero? (+ p r)) 0.0 (/ (* 2 p r) (+ p r)))))
(defn r6 [x] (when x (/ (Math/round (* 1e6 (double x))) 1e6)))

(defn metrics [c]
  (let [dp (ratio (c "detect.tp") (c "detect.pred")) dr (ratio (c "detect.tp") (c "detect.gold"))
        ap (ratio (c "adj.tp") (c "adj.pred")) ar (ratio (c "adj.tp") (c "adj.gold"))
        lp (ratio (c "link.tp") (c "link.pred")) lr (ratio (or (c "link.tp-r") (c "link.tp")) (c "link.gold"))
        rp (ratio (c "row.tp") (c "row.pred")) rr (ratio (c "row.tp") (c "row.gold"))]
    (into (sorted-map)
          (remove (comp nil? val)
                  {"detect-p" dp "detect-r" dr "detect-f1" (f1 dp dr)
                   "adj-f1" (f1 ap ar)
                   "teds" (ratio (c "teds.sum") (c "teds.n")) "teds-struct" (ratio (c "teds-struct.sum") (c "teds.n"))
                   "hb-acc" (ratio (c "hb.tp") (c "hb.gold"))
                   "rows-acc" (ratio (c "rows.ok") (c "shape.n")) "cols-acc" (ratio (c "cols.ok") (c "shape.n"))
                   "link-p" lp "link-r" lr "link-f1" (when (and lp lr) (f1 lp lr))
                   "row-p" rp "row-r" rr
                   "bsb-row-acc" (when (c "bsb.want") (max 0.0 (- 1.0 (/ (double (c "bsb.row-err")) (c "bsb.want")))))
                   "checks" (ratio (c "checks.ok") (c "checks.n"))}))))

;; THE HEADLINE of each population (pre-registered, test/tables-ab/criteria.edn names the same): the mean of these metrics, those
;; the population defines. teds-struct stands for teds where the gold has no text (cTDaR).
(def HEADLINE
  {"synth" ["detect-f1" "adj-f1" "teds" "hb-acc" "rows-acc" "cols-acc" "link-f1"]
   "icdar2013" ["detect-f1" "adj-f1" "teds" "rows-acc" "cols-acc"]
   "fintabnet" ["detect-f1" "adj-f1" "teds" "rows-acc" "cols-acc"]
   "ctdar" ["detect-f1" "teds-struct" "rows-acc" "cols-acc"]
   "ledgers" ["row-r" "row-p" "hb-acc" "link-r" "link-p"]
   "named" ["checks"]
   "bsb" ["hb-acc" "bsb-row-acc"]})
;; the held-out sample's headline is the mean of its parts' headlines, each part scored as its population is
(def HELDOUT-PARTS {"fintabnet" "fintabnet" "ctdar" "ctdar" "ledgers" "ledgers" "bsb" "bsb"})

(defn headline [pop m]
  (let [vs (keep #(get m %) (HEADLINE pop))]
    (when (seq vs) (/ (reduce + vs) (count vs)))))

(defn cost [docs]
  (let [cs (keep #(get % "cost") docs)
        cpu (reduce + 0.0 (keep #(when (get % "user") (+ (get % "user") (get % "sys" 0.0))) cs))
        pages (reduce + 0 (keep #(get % "pages") cs))
        rss (sort (keep #(get % "rss") cs))]
    {"cpu-s" (r6 cpu) "pages" pages "cpu-page" (r6 (ratio cpu pages))
     "rss-max" (last rss) "rss-med" (when (seq rss) (nth rss (quot (count rss) 2)))
     "how" (frequencies (keep #(get % "how") cs))
     "failed" (count (filter #(get % "failed") docs))}))

(defn counts-of [d] (into {} (filter (fn [[k v]] (and (number? v) (str/includes? k "."))) d)))

(defn pool [pop docs]
  (if (= pop "heldout")
    (let [parts (into (sorted-map) (for [[k ds] (group-by #(get % "part") docs)
                                         :let [c (S/pool (map counts-of ds)) mm (metrics c)]]
                                     [k {"counts" c "metrics" mm "headline" (r6 (headline (HELDOUT-PARTS k) mm)) "n" (count ds)}]))
          hs (keep #(get % "headline") (vals parts))]
      {"parts" parts "headline" (when (seq hs) (r6 (/ (reduce + hs) (count hs))))
       "metrics" (into (sorted-map) (for [[k v] parts [mk x] (get v "metrics")] [(str k "/" mk) x]))
       "counts" (into (sorted-map) (for [[k v] parts [ck x] (get v "counts")] [(str k "/" ck) x]))
       "cost" (cost docs) "n" (count docs)})
    (let [c (S/pool (map counts-of docs))
          m (metrics c)]
      {"counts" c "metrics" m "headline" (r6 (headline pop m)) "cost" (cost docs) "n" (count docs)})))

;; ------------------------------------------------------------------ the python checks

(defn- py [& xs]
  (let [r (apply p/sh "python3" xs)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "python failed: " (:err r)) {})))
    (json/parse-string (:out r))))

(defn ownership [dir named]
  (let [o (py "tools/table/ab_checks.py" "ownership" (get named "triples") "test/tables-ab/repairs.json" dir)]
    {"docs" o "total" (reduce + (map #(get % "total" 0) (vals o)))
     "preserved" (reduce + (map #(+ (get % "kept" 0) (get % "extended" 0)) (vals o)))
     "repaired" (reduce + (map #(get % "repaired" 0) (vals o)))
     "changed" (reduce + (map #(get % "changed" 0) (vals o)))
     "index-exact" (reduce + (map #(get % "index_exact" 0) (vals o)))}))

(defn control-words
  ([dir items] (control-words dir nil items))
  ([dir dir-b items]
   (let [f (str (fs/create-temp-file {:suffix ".json"}))]
     (spit f (json/generate-string items))
     (let [o (if dir-b (py "tools/table/ab_checks.py" "words" f dir dir-b) (py "tools/table/ab_checks.py" "words" f dir))]
       (fs/delete f)
       {"docs" o "words" (reduce + (keep #(get % "words") (vals o)))
        "lost" (reduce + (keep #(get % "lost") (vals o))) "gained" (reduce + (keep #(get % "gained") (vals o)))
        "failed" (count (filter #(get % "failed") (vals o)))}))))

;; ------------------------------------------------------------------ printing, baseline, comparison

(defn fmt [x] (if (number? x) (format "%.4f" (double x)) "-"))

(defn print-summary [label s]
  (doseq [[n v] s :let [pl (get v "pooled")]]
    (println (format "%-10s %-10s n=%-4s headline %s  %s  cpu/page %s rss-max %s %s" label n (get pl "n") (fmt (get pl "headline"))
                     (str/join " " (for [[k x] (get pl "metrics")] (str k "=" (fmt x))))
                     (fmt (get-in pl ["cost" "cpu-page"])) (get-in pl ["cost" "rss-max"]) (get-in pl ["cost" "how"])))
    (doseq [[k pv] (get pl "parts")] (println (format "%-10s   %-12s n=%-4s headline %s" "" k (get pv "n") (fmt (get pv "headline")))))
    (when-let [o (get v "ownership")]
      (println (format "%-10s ownership: %d triples, %d preserved, %d repaired, %d changed (index-exact %d)" label (o "total") (o "preserved") (o "repaired") (o "changed") (o "index-exact"))))
    (when-let [w (get v "words")]
      (println (format "%-10s controls: %d words, %d failed" label (w "words") (w "failed"))))
    (when-let [g (get v "goldens")]
      (println (format "%-10s test/table goldens reproduced: %d of %d" label (count (filter #(get % "same") g)) (count g))))))

(defn baseline-of [s eng]
  {"format" 1
   "note" "Frozen baseline of `bb tables-ab` (TR-design): the current table engine at main a39535b1, IDs, counts and scores only."
   "engine" (select-keys eng ["args"])
   "populations"
   (into (sorted-map)
         (for [[n v] s]
           [n (cond-> {"pooled" (dissoc (get v "pooled") "counts")
                       "counts" (get-in v ["pooled" "counts"])
                       "docs" (into (sorted-map) (for [d (get v "docs")]
                                                   [(get d "id") (into (sorted-map) (remove (fn [[k v]] (or (#{"id" "cost"} k) (nil? v))) d))]))}
                (get v "ownership") (assoc "ownership" (get v "ownership"))
                (get v "words") (assoc "words" (get v "words"))
                (get v "goldens") (assoc "goldens" (count (filter #(get % "same") (get v "goldens")))))]))})

(defn baseline-diff
  "Every score of the baseline that the new run does not reproduce (cost and engine excluded: they measure the machine)."
  [old new]
  (let [strip (fn strip [x] (cond (map? x) (into {} (for [[k v] x :when (not (#{"cost" "engine" "note" "rss" "user" "sys"} k))] [k (strip v)]))
                                  (number? x) (r6 x) :else x))
        a (strip old) b (strip new)
        diffs (atom [])]
    (letfn [(go [path x y]
              (cond (and (map? x) (map? y)) (doseq [k (distinct (concat (keys x) (keys y)))] (go (conj path k) (get x k) (get y k)))
                    (not= x y) (swap! diffs conj [path x y])))]
      (go [] a b))
    @diffs))

(defn paired-bootstrap
  "95% interval of mean(B - A) over the per-document composites, 2000 resamples, seed 20261029."
  [deltas]
  (when (seq deltas)
    (let [rng (java.util.Random. 20261029) v (vec deltas) n (count v)
          means (sort (for [_ (range 2000)] (/ (reduce + (repeatedly n #(v (.nextInt rng n)))) n)))]
      [(nth means 50) (nth means 1949)])))

(defn doc-composite
  "One document's composite: the mean of its own headline metrics (from its own counts), a held-out document by its part."
  [pop d]
  (let [m (metrics (counts-of d))] (headline (if (= pop "heldout") (HELDOUT-PARTS (get d "part")) pop) m)))

(defn compare-runs
  "The pre-registered criteria (test/tables-ab/criteria.edn) applied to scores A and B."
  [crit a b dir-a dir-b control-items]
  (let [pops (:populations crit)
        rows (vec (for [n pops :let [ha (get-in a [n "pooled" "headline"]) hb (get-in b [n "pooled" "headline"])]]
                    {:pop n :a ha :b hb :delta (when (and ha hb) (- hb ha))
                     :metrics (into (sorted-map) (for [[k va] (get-in a [n "pooled" "metrics"]) :let [vb (get-in b [n "pooled" "metrics" k])]]
                                                   [k [va vb]]))}))
        tol (:tolerance crit)
        per-pop-ok (every? #(and (:delta %) (>= (:delta %) (- tol))) rows)
        metric-regressions (vec (for [r rows [k [va vb]] (:metrics r) :when (and va vb (< (- vb va) (- (:metric-tolerance crit))))] [(:pop r) k va vb]))
        agg (fn [s] (let [vs (keep #(get-in s [% "pooled" "headline"]) (:aggregate crit))] (when (seq vs) (/ (reduce + vs) (count vs)))))
        agg-a (agg a) agg-b (agg b)
        ho-a (get-in a ["heldout" "pooled" "headline"]) ho-b (get-in b ["heldout" "pooled" "headline"])
        ho-deltas (let [da (into {} (for [d (get-in a ["heldout" "docs"])] [(get d "id") (doc-composite "heldout" d)]))]
                    (for [d (get-in b ["heldout" "docs"]) :let [x (da (get d "id")) y (doc-composite "heldout" d)] :when (and x y)] (- y x)))
        ci (paired-bootstrap ho-deltas)
        own (get-in b ["named" "ownership"])
        words (control-words dir-a dir-b control-items)
        cost-ratio (fn [pops k] (let [s (fn [x] (reduce + 0.0 (keep #(get-in x [% "pooled" "cost" k]) pops)))] (ratio (s b) (s a))))
        cpu-replay (cost-ratio (:replay-cost crit) "cpu-s")
        cpu-live (cost-ratio ["controls"] "cpu-s")
        rss (let [m (fn [x] (apply max 0 (keep #(get-in x [% "pooled" "cost" "rss-max"]) (conj (:replay-cost crit) "controls"))))] (ratio (m b) (m a)))
        failed (fn [x] (set (for [n (conj pops "controls") d (get-in x [n "docs"]) :when (get d "failed")] [n (get d "id")])))
        failures (count (remove (failed a) (failed b)))
        checks [["every population's headline >= A - tolerance" per-pop-ok]
                ["no component metric regresses beyond the metric tolerance" (empty? metric-regressions)]
                [(str "aggregate headline >= A + " (:aggregate-margin crit)) (and agg-a agg-b (>= (- agg-b agg-a) (:aggregate-margin crit)))]
                [(str "held-out headline >= A + " (:heldout-margin crit)) (and ho-a ho-b (>= (- ho-b ho-a) (:heldout-margin crit)))]
                ["held-out paired bootstrap 95% lower bound > 0" (and ci (pos? (first ci)))]
                ["no protected triple lost (changed = 0; repairs only as pre-registered)" (and own (zero? (get own "changed" 1)))]
                ["controls: zero words lost and zero gained" (and (zero? (get words "lost" 1)) (zero? (get words "gained" 1)) (zero? (get words "failed" 1)))]
                [(str "replay CPU B/A <= " (:cpu-replay crit)) (and cpu-replay (<= cpu-replay (:cpu-replay crit)))]
                [(str "live control CPU B/A <= " (:cpu-live crit)) (and cpu-live (<= cpu-live (:cpu-live crit)))]
                [(str "peak RSS B/A <= " (:rss crit)) (and rss (<= rss (:rss crit)))]
                ["B fails on no input that A converts" (zero? failures)]]]
    {:rows rows :metric-regressions metric-regressions :aggregate [agg-a agg-b] :heldout [ho-a ho-b] :heldout-ci ci
     :ownership (select-keys own ["total" "preserved" "repaired" "changed"]) :words (select-keys words ["lost" "gained" "failed"])
     :cpu-replay cpu-replay :cpu-live cpu-live :rss rss :checks (mapv (fn [[k v]] [k (boolean v)]) checks)
     :accept (every? (comp boolean second) checks)}))

(defn print-compare [r]
  (doseq [{:keys [pop a b delta]} (:rows r)] (println (format "  %-10s A %s  B %s  delta %s" pop (fmt a) (fmt b) (fmt delta))))
  (doseq [x (:metric-regressions r)] (println "  regression" x))
  (println "  aggregate" (map fmt (:aggregate r)) " held-out" (map fmt (:heldout r)) " CI" (map fmt (:heldout-ci r)))
  (doseq [[k v] (:checks r)] (println (if v "  ok  " "  FAIL") k))
  (println (if (:accept r) "  ok ACCEPT: the scored engine meets every pre-registered criterion" "  REJECT: see the FAIL lines")))
