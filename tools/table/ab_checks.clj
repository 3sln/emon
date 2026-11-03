(ns table.ab-checks
  "Structural protected ownership and exact repeated-header word accounting."
  (:require [babashka.fs :as fs] [cheshire.core :as json] [clojure.string :as str]
            [clojure.set :as set] [nfc :as nfc] [accuracy.score :as S]))

(defn walk [n] (tree-seq #(seq (get % "children")) #(get % "children") n))
(defn is-number [v] (boolean (re-matches #"[\(\-–]?\$?[0-9][0-9,]*(\.[0-9]+)?\)?%?" (str/trim (str/replace v "$" "")))))
(defn line-cols [rows]
  (let [cols (reduce (fn [m [_ cells]] (reduce-kv #(update %1 %2 (fnil conj []) %3) m cells)) {} rows)]
    (set (for [[c vs] cols :when (and (<= 3 (count vs)) (every? #(re-matches #"\d{1,3}" %) vs)
                                                    (apply <= (map parse-long vs)))] c))))
(defn text-sha256 [value]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes value "UTF-8"))
    (apply str (map #(format "%02x" (bit-and % 255)) (.digest digest)))))
(defn row-fingerprint [cells]
  (text-sha256
   (json/generate-string
    (sort (remove str/blank? (S/split-ws (nfc/normalize (str/join " " (vals cells)))))))))
(defn cells-of [model]
  (vec (mapcat
        (fn [[t table]]
          (let [rows (for [g (get table "children")
                           r (if (= "rowgroup" (get g "kind")) (get g "children") [g])
                           :when (and (= "body" (get g "role" "body")) (= "row" (get r "kind")))]
                       (let [[_ page cells] (reduce (fn [[col page cells] c]
                                                     [(+ col (get c "colspan" 1))
                                                      (if (seq (get c "at")) (ffirst (get c "at")) page)
                                                      (if (seq (get c "text")) (assoc cells col (get c "text")) cells)])
                                                   [0 nil (sorted-map)] (get r "children"))] [page cells]))
                skip (line-cols rows)]
            (for [[page cells] rows :let [key (vec (for [[c v] cells :when (and (not (skip c)) (is-number v))] v))
                                         fingerprint (row-fingerprint cells)]
                  [col v] cells]
              (with-meta [t page key col v] {:row-fingerprint fingerprint}))))
        (map-indexed vector (filter #(= "table" (get % "kind")) (walk (get model "body")))))))
(defn frozen-tuples [frozen]
  (mapv (fn [cell] (mapv #(get cell %) ["table" "page" "row" "col" "value"])) frozen))
(defn freeze-row-ownership
  "Record row-mates without storing document text or fixing output row indices."
  [frozen model]
  (let [cells (cells-of model)]
    (when-not (= (frozen-tuples frozen) cells)
      (throw (ex-info "row ownership reference differs from the frozen document" {})))
    {"signature" (text-sha256 (json/generate-string (frozen-tuples frozen)))
     "rows" (mapv (fn [group]
                    [(ffirst group) (count group)
                     (:row-fingerprint (meta (second (first group))))])
                  (partition-by #(-> % second meta :row-fingerprint)
                                (map-indexed vector cells)))}))
(defn registered-rows [doc frozen repairs]
  (let [entries (filter #(and (= doc (get % "doc")) (= "column-merge" (get % "kind"))) repairs)]
    (when (seq entries)
      (let [policy (first (keep #(get % "row_ownership") entries))
            rows (vec (for [[start count fingerprint] (get policy "rows")
                            k (range start (+ start count))] [k fingerprint]))]
        (when-not (and policy
                       (= (get policy "signature") (text-sha256 (json/generate-string (frozen-tuples frozen))))
                       (= (count rows) (count frozen))
                       (= (set (map first rows)) (set (range (count frozen)))))
          (throw (ex-info "column merge needs complete, matching frozen row ownership" {:doc doc})))
        (into {} rows)))))
(defn contains-value [text value]
  (and (not= text value) (boolean (re-find (re-pattern (str "(?U)(^|\\s)" (java.util.regex.Pattern/quote value) "(\\s|$)")) text))))
(defn repaired? [doc cell frozen repairs]
  (boolean (some (fn [r]
                   (let [nums (set (map str (get r "line_numbers"))) value (get cell "value")
                         mates (filter #(= (select-keys % ["table" "page" "row"]) (select-keys cell ["table" "page" "row"])) frozen)
                         lines (set (map #(get % "value") (filter #((set (get r "line_cols")) (get % "col")) mates)))]
                     (and (not= "column-merge" (get r "kind")) (= doc (get r "doc")) (= (get r "page") (get cell "page"))
                          (not (and (is-number value) ((set (get cell "row")) value) (not (nums value))))
                          (or (empty? (get cell "row")) (seq (set/intersection lines nums)))
                          (or (nums value) (not (is-number value)))))) repairs)))
(defn allowed-column-merge? [doc table a b pages repairs]
  (boolean (some #(and (= doc (get % "doc"))
                       (= "column-merge" (get % "kind"))
                       (= table (get % "table"))
                       (= #{(get % "page")} pages)
                       (= #{a b} (set (get % "columns")))) repairs)))

(defn value-spans [text value]
  (let [m (re-matcher (re-pattern (str "(?U)(?:^|\\s)(" (java.util.regex.Pattern/quote value) ")(?=\\s|$)")) text)]
    (loop [out []] (if (.find m) (recur (conj out [(.start m 1) (.end m 1)])) out))))

(defn ownership [doc frozen model repairs]
  (let [current (cells-of model)
        expected-rows (registered-rows doc frozen repairs)
        index (group-by #(let [[_ p k] (current %)] [p k]) (range (count current)))
        used (atom #{}) match (atom {}) allocations (atom {})]
    (doseq [exact [true false] [k f] (map-indexed vector frozen) :when (not (contains? @match k))]
      (when-let [[i span]
                 (first
                  (for [i (get index [(get f "page") (get f "row")])
                        :let [text (nth (current i) 4) value (get f "value") owners (get @allocations i)
                              share? (and (seq owners)
                                          (every? (fn [[_ _ old-k]]
                                                    (let [old (nth frozen old-k)]
                                                      (and (= (select-keys old ["table" "page" "row"])
                                                              (select-keys f ["table" "page" "row"]))
                                                           (allowed-column-merge? doc (get f "table") (get old "col") (get f "col")
                                                                                  #{(get f "page")} repairs)))) owners))]
                        :when (and (or (nil? expected-rows)
                                       (= (expected-rows k) (:row-fingerprint (meta (current i)))))
                                   (or (not (@used i)) share?)
                                   (if exact (= text value) (contains-value text value)))
                        span (value-spans text value)
                        :when (not-any? (fn [[start end]] (and (< (first span) end) (< start (second span)))) owners)]
                    [i span]))]
        (swap! match assoc k [i (if exact "kept" "extended")])
        (swap! used conj i)
        (swap! allocations update i (fnil conj []) (conj span k))))
    (let [status (atom (into {} (for [[k [_ s]] @match] [k s])))
          groups (group-by #(let [f (nth frozen %)] [(get f "table") (get f "col")]) (sort (keys @match)))
          members (into {} (for [[g ks] groups] [g (mapv (fn [k] (let [[t _ _ c] (current (first (@match k)))] [k t c])) ks)]))
          modal (into {} (for [[g ms] members [nt rows] (group-by second ms)
                               :let [cols (frequencies (map #(nth % 2) rows)) best (first (first (sort-by (fn [[c n]] [(- n) c]) cols)))]]
                           (do (doseq [[k _ nc] rows :when (not= nc best)] (swap! status assoc k "moved")) [[g nt] best])))
          by-new (group-by (fn [[[g nt] _]] [(first g) nt]) modal)]
      (doseq [[_ entries] by-new]
        (let [cols (sort-by first (for [[[g _] nc] entries] [(second g) nc g])) seen (atom #{})]
          (doseq [[_ nc g] cols]
            (when (@seen nc)
              (let [others (for [[_ oc og] cols :when (and (= oc nc) (< (second og) (second g)))] og)
                    allowed (and (= 1 (count others))
                                 (allowed-column-merge? doc (first g) (second (first others)) (second g)
                                                        (set (for [[k] (concat (members g) (members (first others)))]
                                                               (get (nth frozen k) "page"))) repairs))]
                (doseq [[k] (members g) :when (#{"kept" "extended"} (@status k))]
                  (swap! status assoc k (if allowed "repaired" "merged")))))
            (swap! seen conj nc))
          (doseq [[[ _ nc g] [_ prev]] (map vector (rest cols) cols) :when (< nc prev)
                  [k] (members g) :when (#{"kept" "extended"} (@status k))] (swap! status assoc k "reordered"))))
      (let [counts (frequencies (for [[k f] (map-indexed vector frozen) :let [s (get @status k "missing")]]
                                  (if (and (not (#{"kept" "extended"} s)) (repaired? doc f frozen repairs)) "repaired" s)))
            legacy (atom (frequencies (map #(vec (rest %)) current)))
            exact (count (for [f frozen :let [k [(get f "page") (get f "row") (get f "col") (get f "value")]]
                              :when (pos? (get @legacy k 0))] (swap! legacy update k dec)))]
        (assoc counts "total" (count frozen) "changed" (reduce + (map #(get counts % 0) ["missing" "moved" "merged" "reordered"])) "index_exact" exact)))))

(defn node-text [n]
  (str/join " " (cons (or (get n "text") (str/join " " (map node-text (get n "children"))))
                     (mapcat (fn [k] (let [v (get n k)]
                                       (cond (and (= k "label") (some #(and (= "caption" (get % "kind")) (= v (get % "label"))) (get n "children"))) []
                                             (string? v) [v] (vector? v) (filter string? v) :else []))) ["labels" "number" "label"]))))
(defn tokens [v] (frequencies (remove str/blank? (S/split-ws (nfc/normalize v)))))
(defn drop-key [d] [(get d "page" (first (get d "at" [0]))) (get d "text")])
(defn trusted [m] (map drop-key (filter #(= "duplicate-header-row" (get % "reason")) (get m "dropped"))))
(defn accounted [model trusted-rows]
  (let [trust (atom (frequencies trusted-rows))
        words (tokens (str/join " " (map node-text (cons (get model "body" {}) (get model "furniture")))))
        headers (for [t (filter #(= "table" (get % "kind")) (walk (get model "body")))
                      :let [pages (set (map first (mapcat #(get % "at") (walk t))))]
                      g (get t "children") :when (= "head" (get g "role")) r (get g "children")
                      :let [rp (map first (mapcat #(get % "at") (walk r)))]
                      :when (and (seq rp) (seq pages) (= (apply min rp) (apply min pages)))]
                  [(str/join " " (remove str/blank? (map #(str/trim (node-text %)) (get r "children")))) (apply min rp) pages])]
    (reduce (fn [[words count] d]
              (let [[page value :as k] (drop-key d) existing (pos? (get @trust k 0))]
                (when existing (swap! trust update k dec))
                (when-not (or existing (some (fn [[h f pages]] (and (= value h) (< f page) (pages page))) headers))
                  (throw (ex-info "duplicate-header-row is not an exact first-fragment header repeat" {})))
                (let [add (tokens value)] [(merge-with + words add) (+ count (reduce + (vals add)))])))
            [words 0] (filter #(= "duplicate-header-row" (get % "reason")) (get model "dropped")))))
(defn compare-words [before after]
  (let [trust (trusted before) [old oc] (accounted before trust) [new nc] (accounted after trust)
        diff (fn [a b] (reduce + (for [[w n] a] (max 0 (- n (get b w 0))))))]
    {"lost" (diff old new) "gained" (diff new old) "duplicate_header_words" nc "newly_accounted_header_words" (- nc oc)}))
(defn read-json [f] (json/parse-string (slurp (str f))))
(defn ownership-files [triples repairs folder]
  (into (sorted-map) (for [[doc cells] (read-json triples) :let [f (fs/path folder (str doc ".json"))]]
                       [doc (if (fs/exists? f) (ownership doc cells (read-json f) (get (read-json repairs) "repairs"))
                                {"total" (count cells) "changed" (count cells) "missing" (count cells)})])))
(defn word-files [items a b]
  (into (sorted-map) (for [item items :let [id (get item "id") name (str/replace id "/" "__") fa (fs/path a (str name ".json"))]]
                       [id (if-not (fs/exists? fa) {"failed" true}
                             (let [ma (read-json fa) row {"words" (reduce + (vals (first (accounted ma (trusted ma)))))}]
                               (if b (let [fb (fs/path b (str name ".json"))] (if (fs/exists? fb) (merge row (compare-words ma (read-json fb))) (assoc row "failed" true))) row)))])))

(defn -main [cmd & args]
  (case cmd
    "ownership" (println (json/generate-string (apply ownership-files args)))
    "words" (let [[items a b] args] (println (json/generate-string (word-files (read-json items) a b))))
    (do (println "bb tools/table/ab_checks.clj ownership TRIPLES.json REPAIRS.json MODEL_DIR")
        (println "bb tools/table/ab_checks.clj words ITEMS.json DIR_A [DIR_B]")
        (System/exit 2))))
(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
