(ns multishot
  (:require [babashka.cli :as cli]
            [clojure.edn]
            [babashka.fs :as fs]
            [babashka.process :refer [process destroy shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def engines ["tesseract" "apple-vision" "ppocr"])
(def buckets ["gap" "abut" "partial" "1" "2-3" "4+"])
(def traps ["clean" "identical" "asymmetric" "repeat" "separator" "different-pages"])
(def seam-metric "model-v3")
(def false-merge-metric "missing-model-row-v1")
(def scratch (fs/path (or (System/getenv "EMON_SCRATCH") ".cache/scratch") "multishot"))
(def corpus (fs/path (or (System/getenv "EMON_MULTISHOT_CORPUS") "corpus")))
(defn read-json [f] (json/parse-string (slurp (str f)) true))
(defn fail [msg] (throw (ex-info msg {})))
(defn between [rng n] (.nextInt rng n))
(defn shuffled [rng xs]
  (let [a (java.util.ArrayList. xs)]
    (java.util.Collections/shuffle a rng)
    (vec a)))

(defn words [s] (re-seq #"[\p{L}\p{N}]+" (str/lower-case (or s ""))))
(defn common [a b]
  (let [freq (frequencies b)]
    (reduce-kv (fn [n w c] (+ n (min c (get freq w 0)))) 0 (frequencies a))))
(defn ratio [a b] (if (zero? b) 1.0 (/ (double a) b)))
(defn nodes [root] (tree-seq #(seq (:children %)) :children root))
(defn model-nodes [model]
  (mapcat nodes (cons (:body model) (:furniture model))))

(defn model-text [model]
  (str/join " " (keep :text (model-nodes model))))

(defn gold-rows [gold]
  (let [units (sort-by (fn [{[[_ _ y0 _ y1]] :at}] (+ y0 y1))
                       (filter #(and (seq (:at %)) (not (str/blank? (:text %))))
                               (nodes (get-in gold [:model :body]))))]
    (mapv (fn [line]
            (let [line (sort-by #(nth (first (:at %)) 1) line)
                  boxes (map #(rest (first (:at %))) line)]
              {:box (mapv (fn [i f] (apply f (map #(nth % i) boxes)))
                          (range 4) [min min max max])
               :text (str/join " " (map :text line))}))
          (reduce (fn [rows unit]
                    (let [[_ _ y0 _ y1] (first (:at unit))
                          [_ _ a _ b] (first (:at (first (peek rows))))]
                      (if (and a (<= a (/ (+ y0 y1) 2) b)
                               (> (- (min b y1) (max a y0)) (* 0.4 (min (- b a) (- y1 y0)))))
                        (conj (pop rows) (conj (peek rows) unit))
                        (conj rows [unit]))))
                  [] units))))

(defn gold-total [source dataset]
  (let [native (read-json (str/replace (str source) #"\.[^.]+$" ".json"))]
    (if (= dataset "sroie")
      (get-in native [:entities :total])
      (let [truth (:ground_truth native)
            truth (if (string? truth) (json/parse-string truth true) truth)]
        (some (fn [line]
                (when (= "total.total_price" (:category line))
                  (str/join " " (map :text (:words line)))))
              (:valid_line truth))))))

(defn load-cases
  ([dataset rng n] (load-cases dataset rng n false))
  ([dataset rng n sweep]
   (let [pattern (if (and sweep (= dataset "cord")) "cord-*.gold.json" (str dataset "-test-*.gold.json"))
         files (shuffled rng (mapv str (sort (fs/glob (fs/path corpus "_gold" dataset) pattern))))]
     (when (empty? files) (fail (str "missing " dataset " gold under " corpus)))
     (let [cases
           (take n
                 (keep (fn [file]
                         (let [gold (read-json file)
                               rows (gold-rows gold)
                               [_ height] (get-in gold [:model :pages 0 :px])
                               source (fs/path corpus (get-in gold [:provenance :variants :image]))]
                           (when (and height (>= (count rows) 12) (fs/exists? source))
                             {:id (:id gold) :source (str (fs/absolutize source))
                              :height height :rows rows :total (gold-total source dataset)})))
                       files))]
       (when (and (not sweep) (< (count cases) n))
         (fail (str "insufficient " dataset " receipts")))
       (vec cases)))))

(defn top [r] (get-in r [:box 1]))
(defn bottom [r] (get-in r [:box 3]))
(defn mid [rows i] (quot (+ (bottom (rows i)) (top (rows (inc i)))) 2))
(defn cut [rows i rng] (let [r (rows i)] (+ (top r) (* (+ 0.35 (* 0.3 (.nextDouble rng))) (- (bottom r) (top r))))))
(defn shift [row height]
  (update row :box (fn [[x0 y0 x1 y1]] [x0 (+ y0 height) x1 (+ y1 height)])))

(defn repeat-item [rows i]
  (let [lo (mid rows (dec i)) hi (mid rows i) h (- hi lo)]
    [(into (conj (subvec rows 0 (inc i)) (shift (rows i) h))
           (map #(shift % h) (subvec rows (inc i))))
     {:at hi :top lo :bottom hi} h]))

(defn separator [rows i]
  (let [at (mid rows i) h 18]
    [(into (conj (subvec rows 0 (inc i)) {:text "--------" :box [0 (+ at 7) (get-in (rows i) [:box 2]) (+ at 11)]})
           (map #(shift % h) (subvec rows (inc i))))
     {:at at :top at :bottom (+ at h) :rule true} h]))

(defn design [source rng kind pieces]
  (let [rows (:rows source)
        n (count rows)
        center (quot n 2)
        item (or (some #(when (and (re-find #"\d[.,]\d{2}" (:text (rows %)))
                                    (< (bottom (rows (dec %))) (top (rows %)))
                                    (< (bottom (rows %)) (top (rows (inc %))))) %)
                       (sort-by #(abs (- % center)) (range 2 (- n 4))))
                 center)
        [rows insertion extra] (case kind
                                 ("identical" "repeat") (repeat-item rows item)
                                 "separator" (separator rows center)
                                 [rows nil 0])
        careful (boolean (some #{kind} traps))
        cuts (if careful
               (case kind
                 "identical" [[(mid rows item) (mid rows item)]]
                 "repeat" [[(mid rows (+ item 2)) (mid rows (+ item 2))]]
                 "separator" [[(mid rows (+ center 2)) (mid rows (- center 2))]]
                 "asymmetric" [[(mid rows (+ center 2)) (mid rows (dec center))]]
                 [[(mid rows center) (mid rows center)]])
               (mapv (fn [j]
                       (let [i (max 1 (min (- n 6) (+ (quot (* j n) pieces) (- (between rng 3) 1))))
                             b (if (= pieces 2) kind (nth buckets (between rng (count buckets))))]
                         (case b
                           "gap" [(cut rows i rng) (cut rows (inc i) rng)]
                           "abut" [(mid rows i) (mid rows i)]
                           "partial" (let [y (cut rows i rng)] [y y])
                           "1" [(mid rows i) (mid rows (dec i))]
                           "2-3" [(mid rows (+ i (inc (between rng 2)))) (mid rows (dec i))]
                           "4+" [(mid rows (+ i 3 (between rng 2))) (mid rows (dec i))])))
                     (range 1 pieces)))
        windows (mapv (fn [i lo hi] [lo hi (if (and (zero? i) (= kind "asymmetric")) 130 0)])
                      (range) (cons 0 (map second cuts))
                      (concat (map first cuts) [(+ extra (:height source))]))
        order (vec (range (count windows)))]
    (assoc source :rows rows :insert (cond-> [] insertion (conj insertion))
           :seed (between rng Integer/MAX_VALUE) :kind kind :careful careful :windows windows
           :order (if (and (not careful) (zero? (between rng 4))) (shuffled rng order) order))))

(defn valid-design [source rng kind pieces]
  (or (some (fn [_]
              (let [case (design source rng kind pieces)]
                (when (every? (fn [[a b]] (>= (- b a) 12)) (:windows case))
                  case)))
            (range 30))
      (fail (str "could not place " pieces " crop windows on " (:id source)))))

(defn match-score [expected actual]
  (let [a (words expected) b (words actual)]
    (if (seq a) (/ (* 2.0 (common a b)) (+ (count a) (count b))) 0.0)))

(defn bundle [bin engine args paths]
  (let [command (into [bin "--out=bundle"
                       (if (= engine "tesseract") "--tesseract" (str "--ocr-engine=" engine))]
                      (concat args paths))
        child (process command {:out :string :err :string :shutdown destroy
                                :extra-env {"OMP_THREAD_LIMIT" "1"}})
        result (deref child 180000 ::timeout)]
    (when (= result ::timeout)
      (destroy child)
      (fail (str "driver timed out (" engine ")")))
    (when-not (zero? (:exit result))
      (fail (str "driver failed (" engine ", exit " (:exit result) "): " (str/trim (:err result)))))
    (let [sections (into {} (map (fn [[_ tag body]] [tag (str/trimr body)])
                                 (re-seq #"(?ms)^== ([^\n]+)\n(.*?)(?=^== |\z)" (:out result))))]
      {:canonical (sections "json") :model (json/parse-string (sections "json") true)
       :evidence (json/parse-string (sections "evidence") true)})))

(defn dropped-seam-reads [model]
  (for [{[page x0 y0 x1 y1] :at text :text reason :reason} (:dropped model)
        :when (= reason "seam-duplicate")
        :let [{[width height] :px w :w h :h} (get (:pages model) (dec page))]
        :when (and width height)]
    {:page page :text text
     :box [(* x0 (/ width w)) (* y0 (/ height h)) (* x1 (/ width w)) (* y1 (/ height h))]}))

(defn y-intersects? [[_ a _ b] [_ c _ d]]
  (> (min b d) (max a c)))

(defn line-reads [{:keys [evidence model]}]
  (for [{[start length] :words page :page :as line} (:lines evidence)
        :when (and start (pos? length))
        :let [text (str/join " " (map :text (subvec (:words evidence) start (+ start length))))]
        :when (not-any? (fn [{[pg _ _ _ _ :as at] :at removed :text reason :reason}]
                          (and (= reason "seam-duplicate") (= pg page)
                               (y-intersects? (rest at) (:box line))
                               (>= (match-score removed text) 0.95)))
                        (:dropped model))
        :let [p (get (:pages model) (dec page))
              [width height] (:px p)
              [x0 y0 x1 y1] (:box line)]
        :when (and width height)]
    {:page page
     :box [(* x0 (/ width (:w p))) (* y0 (/ height (:h p)))
           (* x1 (/ width (:w p))) (* y1 (/ height (:h p)))]
     :text text}))

(defn total-count [total text]
  (if-let [amount (re-find #"\d[\d.,]*" (or total ""))]
    (count (re-seq (re-pattern (str "(?<![\\d.,])" (java.util.regex.Pattern/quote amount) "(?![\\d.,])")) text))
    0))

(defn score-words [rows text]
  (let [a (mapcat (comp words :text) rows) b (words text) n (common a b)]
    {:gold-words (count a) :read-words (count b) :matched-words n}))

(defn seam-kind [shared lost a b]
  (cond
    (>= (count shared) 4) "4+"
    (>= (count shared) 2) "2-3"
    (= (count shared) 1) "1"
    (some #(and (pos? (a %)) (pos? (b %))) lost) "partial"
    (seq lost) "gap"
    :else "abut"))

(defn seam-gold [left right]
  (let [a (:visible left) b (:visible right)
        indices (range (count a))
        lo (last (filter #(pos? (a %)) indices))
        hi (first (filter #(pos? (b %)) indices))
        full? #(>= % 0.97)
        shared (filterv #(and (full? (a %)) (full? (b %))) indices)
        lost (if (and lo hi)
               (filterv #(and (not (full? (a %))) (not (full? (b %))))
                        (range (min lo hi) (inc (max lo hi))))
               [])]
    {:bucket (seam-kind shared lost a b) :shared shared :lost lost}))

(defn row-read? [row box lines]
  (some (fn [{read-box :box text :text}]
          (and (y-intersects? read-box box)
               (or (>= (match-score (:text row) text) 0.72)
                   (and (re-matches #"[-=*_.]{3,}" (:text row))
                        (re-matches #"[-=*_.]{3,}" text)))))
        lines))

(defn output-tokens [model page]
  (letfn [(walk [{:keys [text at children]} inherited]
            (let [regions (if (seq at)
                            (reduce (fn [by-page [p x0 y0 x1 y1]]
                                      (let [{[width height] :px w :w h :h} (get (:pages model) (dec p))]
                                        (if (and width height (or (nil? page) (= p 1)))
                                          (update by-page (or page p) (fnil conj [])
                                                  [(* x0 (/ width w)) (* y0 (/ height h))
                                                   (* x1 (/ width w)) (* y1 (/ height h))])
                                          by-page)))
                                    {} at)
                            inherited)
                  tokens (if (re-matches #"[-=*_.]{3,}" (or text "")) ["::rule"] (words text))]
              (concat (map #(hash-map :token % :regions regions) tokens)
                      (mapcat #(walk % regions) children))))]
    (into [] (mapcat #(walk % {}) (cons (:body model) (:furniture model))))))

(defn output-candidates [tokens pieces row-index row coverage similarity]
  (let [expected (vec (if (re-matches #"[-=*_.]{3,}" (:text row)) ["::rule"] (words (:text row))))
        n (count expected)
        vocabulary (set expected)]
    (when (pos? n)
      (mapcat
       (fn [[page piece]]
         (when (pos? (get (:visible piece) row-index))
           (let [[_ y0 _ y1 :as box] (get (:boxes piece) row-index)
                 proximity (mapv (fn [token]
                                   (reduce (fn [best [_ a _ b :as region]]
                                             (if (and (y-intersects? box region)
                                                      (>= (- (min y1 b) (max y0 a))
                                                          (* 0.25 (min (- y1 y0) (- b a)))))
                                               (min best (/ (abs (- (+ y0 y1) (+ a b)))
                                                            (max 1 (- b a) (- y1 y0))))
                                               best))
                                           ##Inf (get (:regions token) page)))
                                 tokens)]
             (for [start (range (count tokens))
                   :when (and (vocabulary (:token (tokens start))) (< (proximity start) ##Inf))
                   end (take-while #(< (proximity (dec %)) ##Inf)
                                   (range (inc start) (inc (min (count tokens) (+ start n (max 1 (quot n 4)))))))
                   :when (vocabulary (:token (tokens (dec end))))
                   :let [span (subvec tokens start end)
                         length (- end start)]
                   :let [hits (common expected (map :token span))
                         score (/ (* 2.0 hits) (+ n length))]
                   :when (and (>= hits (* coverage n)) (>= score similarity))
                   :let [distance (/ (reduce + (subvec proximity start end)) length)]]
               {:row row-index :page page :start start :end end
                :score score :distance distance}))))
       pieces))))

(defn output-occurrences
  ([rows pieces tokens] (output-occurrences rows pieces tokens 0.75 0.8))
  ([rows pieces tokens coverage similarity]
   ;; At equal text similarity, give each physical row a reading before assigning a second
   ;; one on the same photo. This also handles equal rows in the same paragraph region.
   (let [candidates (sort-by (juxt (comp - :score) :distance :start :row :page)
                             (mapcat #(output-candidates tokens pieces %1 %2 coverage similarity) (range) rows))
         allocate (fn [result matches unique?]
                    (reduce (fn [{:keys [used assigned] :as current} {:keys [start end row page] :as match}]
                              (let [span (range start end)
                                    key [page row]]
                                (if (or (some used span) (and unique? (assigned key)))
                                  current
                                  (-> current
                                      (update :used into span)
                                      (update :assigned conj key)
                                      (update-in [:rows row] (fnil conj []) match)))))
                            result matches))]
     (:rows (reduce (fn [result same-score]
                      (allocate (allocate result same-score true) same-score false))
                    {:used #{} :assigned #{} :rows {}}
                    (partition-by :score candidates))))))

(defn vertical-cut? [piece row edge]
  (let [[_ top _ bottom] (get (:boxes piece) row)
        boundary (if (= edge :top) 0 (:height piece))]
    (and (pos? (get (:visible piece) row))
         (< top boundary bottom))))

(defn full-row? [piece row]
  (let [[_ top _ bottom] (get (:boxes piece) row)]
    (and (= 1.0 (double (get (:visible piece) row)))
         (<= 0 top bottom (:height piece)))))

(defn lost-rows [left right]
  (let [indices (range (count (:visible left)))
        last-left (last (filter #(pos? (get (:visible left) %)) indices))
        first-right (first (filter #(pos? (get (:visible right) %)) indices))]
    (filterv (fn [i]
               (and (not (or (full-row? left i) (full-row? right i)))
                    (or (vertical-cut? left i :bottom) (vertical-cut? right i :top)
                        (and last-left first-right (< last-left i first-right)))))
             indices)))

(defn shares-axis? [a b c d]
  (> (- (min b d) (max a c)) (* 0.25 (min (- b a) (- d c)))))

(defn removed-gold-row [rows piece {text :text [x0 y0 x1 y1] :box}]
  (let [tokens (words text)]
    (->> rows
         (keep-indexed
          (fn [i row]
            (let [[a b c d] (get (:boxes piece) i)
                  hits (common tokens (words (:text row)))]
              (when (and (pos? (get (:visible piece) i))
                         (shares-axis? x0 x1 a c) (shares-axis? y0 y1 b d)
                         (or (pos? hits)
                             (and (re-matches #"[-=*_.]{3,}" text)
                                  (re-matches #"[-=*_.]{3,}" (:text row)))))
                {:row i :hits hits :score (match-score (:text row) text)
                 :distance (/ (abs (- (+ y0 y1) (+ b d))) (max 1 (- y1 y0) (- d b)))}))))
         (sort-by (juxt (comp - :hits) (comp - :score) :distance :row))
         first :row)))

(defn false-merge-rows [case pieces dropped before after]
  ;; Count a missing gold row once, even when OCR emitted several removed segments.
  ;; Compare readings in the Models. Crop visibility and overlap marks cannot prove survival.
  (let [order (:order case)]
    (into (sorted-set)
          (keep (fn [{:keys [page] :as read}]
                  (when (and (<= 1 page (count order)) (or (not (:other case)) (= page 1)))
                    (let [p (order (dec page))
                          row (removed-gold-row (:rows case) (pieces p) read)]
                      (when (and (some #(= p (:page %)) (before row))
                                 (not (after row)))
                        row)))))
          dropped)))

(defn ordered-words? [expected tokens]
  (empty? (reduce (fn [remaining token]
                    (if (= (first remaining) token) (next remaining) remaining))
                  expected tokens)))

(defn omit-surviving-table-rows [missing case pieces model]
  ;; A Model table row can put the labels of two gold rows in one cell and
  ;; their amounts in another. The words may be separated in document order.
  (when (seq missing)
    (let [expected (mapv (comp words :text) (:rows case))
          unique (frequencies expected)
          table-rows (mapv #(output-tokens (assoc model :body % :furniture []) nil)
                           (filter #(= "row" (:kind %)) (model-nodes model)))]
      (remove
       (fn [i]
         (let [pattern (expected i)]
           (and (> (count pattern) 1)
                (= 1 (get unique pattern))
                (some (fn [[physical piece]]
                        (when (pos? (get (:visible piece) i))
                          (let [[x0 y0 x1 y1] (get (:boxes piece) i)
                                page (inc (.indexOf (:order case) physical))
                                projected? (fn [token]
                                             (some (fn [[a b c d]]
                                                     (and (shares-axis? x0 x1 a c)
                                                          (shares-axis? y0 y1 b d)))
                                                   (get (:regions token) page)))]
                            (some #(ordered-words? pattern (map :token (filter projected? %)))
                                  table-rows))))
                      pieces))))
       missing))))

(defn ambiguous-duplicate-rows [rows]
  (let [bags (mapv #(frequencies (words (:text %))) rows)]
    (set (keep-indexed (fn [i bag]
                         (when (and (seq bag)
                                    (some (fn [[j other]]
                                            (and (not= i j)
                                                 (every? (fn [[word n]] (>= (get other word 0) n)) bag)))
                                          (map-indexed vector bags)))
                           i))
                       bags))))

(defn duplicate-events [occurrences page-count]
  (group-by :seam
            (for [[row copies] occurrences
                  [a b] (partition 2 1 (sort-by (juxt :page :start) copies))
                  :let [left (:page a) right (:page b)]]
              {:row row :left left :right right :seam (min left (- page-count 2))})))

(defn seam-marks [order seams left right]
  (let [a (inc (.indexOf order left))
        b (inc (.indexOf order right))]
    (keep (fn [{:keys [left right kind]}]
            (when (or (and (= a left) (= b right)) (and (= a right) (= b left)))
              kind))
          seams)))

(defn nonadjacent-gap-counts [order seams]
  (->> seams
       (keep (fn [{:keys [left right kind]}]
               (when (#{"possible-gap" "seam-gap"} kind)
                 (let [[a b :as pair] (sort [(order (dec left)) (order (dec right))])]
                   (when (> (- b a) 1) pair)))))
       distinct
       (map first)
       frequencies))

(defn evidence-occurrences [case pieces reads]
  (into #{}
        (for [[i row] (map-indexed vector (:rows case))
              [page piece] pieces
              :when (and (pos? (get (:visible piece) i))
                         (row-read? row (get (:boxes piece) i) (reads page)))]
          i)))

(defn seam-score [case pieces i reads occurrences duplicates seams physical-order]
  (let [left (pieces i)
        right (pieces (inc i))
        shared (when-not (:other case) (:shared (seam-gold left right)))
        bucket (:bucket (seam-gold left right))
        evidence-dup (count (filter #(and (row-read? (get-in case [:rows %]) (get-in left [:boxes %]) (reads i))
                                         (row-read? (get-in case [:rows %]) (get-in right [:boxes %]) (reads (inc i))))
                                   shared))
        missing (when-not (:other case) (remove occurrences (lost-rows left right)))
        marks (seam-marks (:order case) seams i (inc i))
        gap? (some #{"possible-gap" "seam-gap"} marks)
        in-order? (= (inc (.indexOf physical-order i)) (.indexOf physical-order (inc i)))
        events (map (fn [{:keys [left right] :as copy}]
                      (assoc copy :marks (seam-marks (:order case) seams left right)))
                    duplicates)
        same-photo (filter #(= (:left %) (:right %)) events)
        cross-photo (remove #(= (:left %) (:right %)) events)
        unflagged (remove #(some #{"possible-duplicate"} (:marks %)) cross-photo)
        clipped-with-copy? (some (fn [row]
                                   (or (and (vertical-cut? left row :bottom) (full-row? right row))
                                       (and (vertical-cut? right row :top) (full-row? left row))))
                                 (range (count (:visible left))))]
    {:seams 1 :shared (count shared) :evidence_dup evidence-dup
     :same-photo-dup (count same-photo)
     :dup (count cross-photo) :unflagged (count unflagged)
     :matched-dup (count (filter #(some #{"overlap"} (:marks %)) cross-photo))
     :out-of-order-unflagged (count (filter (fn [{:keys [left right]}]
                                             (and (not= left right)
                                                  (not= (inc (.indexOf physical-order left))
                                                        (.indexOf physical-order right))))
                                           unflagged))
     :out-of-order-missed-gap (if (and (not in-order?) (seq missing) (not gap?)) 1 0)
     :lost-rows (count missing)
     :lost (if (seq missing) 1 0) :lost-flagged (if (and (seq missing) gap?) 1 0)
     :spurious-gap (if (and (empty? missing) gap?
                            (or (:careful case) (not (#{"partial" "gap"} bucket)) clipped-with-copy?))
                     1 0)}))

(defn case-document [case manifest tiff]
  (let [paths (mapv :path (:pieces manifest))
        order (:order case)]
    (if tiff [(:sequence manifest)]
      (into ["--images"] (if-let [other (:other case)]
                          [(paths (first order)) other]
                          (mapv paths order))))))

(defn score-case [case manifest separate scan actual control]
  (let [pieces (:pieces manifest)
        order (:order case)
        physical-order (if (:other case) [(first order)] order)
        physical-pieces (mapv (fn [i] [i (pieces i)]) physical-order)
        text-of #(model-text (:model %))
        concat-text (str/join " " (map text-of (mapv separate order)))
        final-text (if actual (text-of actual) concat-text)
        before (mapv line-reads separate)
        after (if actual
                (let [pages (group-by :page (line-reads actual))]
                  (mapv #(get pages (inc (.indexOf order %))) (range (count pieces))))
                before)
        seams (get-in actual [:evidence :seams])
        nonadjacent-gaps (nonadjacent-gap-counts order seams)
        source-order (get-in actual [:evidence :photoOrder] (mapv inc (range (count order))))
        joined-order (mapv #(order (dec %)) source-order)
        concat-tokens (into [] (mapcat #(output-tokens (:model (separate %)) %) physical-order))
        actual-tokens (when actual
                        (mapv (fn [t] (update t :regions
                                             #(into {} (for [[p box] % :when (<= p (count physical-order))]
                                                         [(order (dec p)) box]))))
                              (output-tokens (:model actual) nil)))
        concat-occ (output-occurrences (:rows case) physical-pieces concat-tokens)
        actual-occ (if actual (output-occurrences (:rows case) physical-pieces actual-tokens) concat-occ)
        ambiguous (ambiguous-duplicate-rows (:rows case))
        concat-dup (duplicate-events (apply dissoc concat-occ ambiguous) (count pieces))
        actual-dup (duplicate-events (apply dissoc actual-occ ambiguous) (count pieces))
        drops (seq (dropped-seam-reads (:model actual)))
        false-merge-rows (when drops
                           ;; A whole row can still be read with a few OCR substitutions or splits.
                           (let [missing (false-merge-rows case pieces drops
                                                           (output-occurrences (:rows case) physical-pieces concat-tokens 0.70 0.72)
                                                           (output-occurrences (:rows case) physical-pieces actual-tokens 0.70 0.72))]
                             (omit-surviving-table-rows missing case physical-pieces (:model actual))))
        scan-total (total-count (:total case) (text-of scan))
        concat-total (total-count (:total case) concat-text)
        actual-total (total-count (:total case) final-text)
        case-checks {:scan-total (if (pos? scan-total) 1 0)
                     :total-before concat-total
                     :total-after actual-total
                     :total-missing (if (and (pos? scan-total) (zero? actual-total)) 1 0)
                     :total-duplicates (if (pos? scan-total) (max 0 (- actual-total scan-total)) 0)
                     :missing-concat (- (count (:rows case)) (count concat-occ))
                     :missing-output (- (count (:rows case)) (count actual-occ))
                     :evidence_missing (- (count (:rows case))
                                          (count (evidence-occurrences case physical-pieces after)))
                     :evidence_missing_concat (- (count (:rows case))
                                                 (count (evidence-occurrences case physical-pieces before)))
                     :false-merges (count false-merge-rows)
                     :clean-gap (if (and (= "clean" (:kind case))
                                         (some #{"possible-gap" "seam-gap"} (map :kind seams))) 1 0)
                     :changed-clean (if (and control (some #{"clean" "different-pages"} [(:kind case)])
                                             (not= (:canonical actual) (:canonical control))) 1 0)
                     :changed-repeat (if (and control (some #{"identical" "repeat"} [(:kind case)])
                                              (not= (:canonical actual) (:canonical control))) 1 0)}]
    {:kind (:kind case)
     :order source-order
     :false-merge-rows (vec false-merge-rows)
     :output-duplicates (mapv (fn [event]
                                (assoc event :marks (vec (seam-marks order seams (:left event) (:right event)))))
                              (remove #(= (:left %) (:right %))
                                      (mapcat actual-dup (range (dec (count pieces))))))
     :words {:scan (score-words (:rows case) (text-of scan))
             :concat (score-words (:rows case) concat-text)
             :actual (score-words (:rows case) final-text)}
     :checks case-checks
     :seams (mapv (fn [i left right]
                    {:bucket (if (:careful case) (:kind case) (:bucket (seam-gold left right)))
                     :concat (seam-score case pieces i before concat-occ (concat-dup i) [] order)
                     :actual (update (seam-score case pieces i after actual-occ (actual-dup i) seams joined-order)
                                     :spurious-gap + (get nonadjacent-gaps i 0))})
                  (range) pieces (rest pieces))}))

(defn run-case [bin engine mode case manifest tiff]
  (let [document (case-document case manifest tiff)
        separate (mapv #(bundle bin engine [] [(:path %)]) (:pieces manifest))
        scan (bundle bin engine [] [(:scan manifest)])
        actual (when (= mode "gate") (bundle bin engine [] document))
        control (when (and actual (:careful case))
                  (bundle bin engine ["--capture=scan"] document))]
    (score-case case manifest separate scan actual control)))

(defn sum-maps [maps] (apply merge-with + {} maps))
(defn word-report [rows]
  (into {} (for [kind [:scan :concat :actual]
                 :let [{:keys [gold-words read-words matched-words]}
                       (sum-maps (map #(get-in % [:words kind]) rows))]]
             [kind {:precision (ratio matched-words read-words)
                    :recall (ratio matched-words gold-words)}])))

(defn report [rows]
  {:words (word-report (remove #(= "different-pages" (:kind %)) rows))
   :historical-words (word-report rows)
   :checks (sum-maps (map :checks rows))
   :buckets (into (sorted-map)
                  (for [[bucket seams] (group-by :bucket (mapcat :seams rows))]
                    [bucket {:concat (sum-maps (map :concat seams))
                             :actual (sum-maps (map :actual seams))}]))})

(defn clip-case [source]
  (let [rows (:rows source)
        target (some (fn [i]
                       (let [[x0 y0 x1 y1] (:box (rows i))]
                         (when (and (> (- x1 x0) 40) (> (- y1 y0) 9)) i)))
                     (sort-by #(abs (- % (quot (count rows) 2))) (range 3 (- (count rows) 3))))]
    (when-not target (fail (str "no suitable clipping target in " (:id source))))
    (assoc source :target target :kind "clipped")))

(defn clip-metrics [bin engine manifest]
  (into {}
        (for [[edge {:keys [path clipped]}] manifest
              :let [args (if (#{:left :right} edge) ["--capture=photos"] ["--capture=scan"])
                    {:keys [model evidence]} (bundle bin engine args [path])
                    {[w h] :px width :w height :h} (first (:pages model))
                    reads (:olines evidence)
                    flagged (for [{:keys [box flags]} reads
                                  :when (some #{"clipped"} flags)
                                  :let [[a b c d] box]]
                              [(* a (/ w width)) (* b (/ h height)) (* c (/ w width)) (* d (/ h height))])]]
          [edge {:reads (count reads)
                 :truth (count clipped)
                 :flagged (count flagged)
                 :true-flags (count (filter #(some (partial y-intersects? %) clipped) flagged))
                 :found (count (filter #(some (partial y-intersects? %) flagged) clipped))}])))

(defn clip-report [cases]
  (into (sorted-map)
        (for [[edge scores] (group-by key (mapcat seq cases))
              :let [{:keys [truth found flagged reads true-flags] :as counts}
                    (sum-maps (map val scores))]]
          [edge (assoc counts :precision (ratio true-flags flagged)
                              :recall (ratio found truth)
                              :flag-rate (if (pos? reads) (ratio flagged reads) 0.0))])))

(defn failed-cases [cases scores]
  (keep (fn [[case score]]
          (let [{:keys [unflagged lost lost-flagged spurious-gap] :as seams}
                (sum-maps (map :actual (:seams score)))
                disallowed-duplicate? (some #(and (not= "1" (:bucket %))
                                                   (pos? (get-in % [:actual :dup])))
                                            (:seams score))
                checks (select-keys (:checks score)
                                    [:changed-clean :clean-gap :changed-repeat :false-merges :total-missing :total-duplicates])]
            (when (or (pos? unflagged) disallowed-duplicate? (not= lost lost-flagged)
                      (pos? spurious-gap) (some pos? (vals checks)))
              {:id (:id case) :kind (:kind case) :seams seams :checks checks})))
        (map vector cases scores)))

(defn seam-cases [sets sources rng ci lo hi]
  (let [kinds (if ci (cycle buckets) (repeatedly #(nth buckets (between rng (count buckets)))))
        random-cases (mapv (fn [source kind]
                             (valid-design source rng kind (+ lo (between rng (inc (- hi lo))))))
                           sources kinds)
        trap-cases (mapv (fn [kind source]
                           (let [case (valid-design source rng kind 2)]
                             (if (= kind "different-pages")
                               (let [others (or (seq (remove #(= (:id source) (:id %)) sources))
                                                (remove #(= (:id source) (:id %))
                                                        (load-cases (first sets) rng 2)))]
                                 (assoc case :other (:source (last others))))
                               case)))
                         traps (cycle sources))]
    (into random-cases trap-cases)))

(def spec {:ci {:coerce :boolean} :sweep {:coerce :boolean}
           :seed {:coerce :long} :n {:coerce :long} :set {:coerce :string}
           :pieces {:coerce :string} :ocr-engine {:coerce :string} :report {:coerce :boolean}
           :keep {:coerce :boolean} :baseline {:coerce :boolean} :clipped {:coerce :boolean} :help {:coerce :boolean}
           :tiff {:coerce :boolean} :bin {:coerce :string} :output {:coerce :string}
           :ratchet {:coerce :string}
           ;; spool (tools/spool): :prepare DIR writes the crops and case designs; :prepared DIR scores one
           ;; :shard I/K of them into :output as EDN rows; :merge joins those row files into the normal report
           :prepare {:coerce :string} :prepared {:coerce :string} :shard {:coerce :string}
           :merge {:coerce :string}})

(defn driver [engine bin]
  (or bin (System/getenv "EMON_MULTISHOT_BIN")
      (let [env (case engine
                  "apple-vision" {"EMON_RS_VISION" "1"}
                  "ppocr" {"EMON_RS_LIVE" "1" "EMON_RS_PPOCR" "1"}
                  {"EMON_RS_LIVE" "1"})]
        (str/trim (:out (shell {:extra-env env :out :string} "kin/scripts/emon-rs-build"))))))

(defn counts [sets total]
  (let [q (quot total (count sets)) r (rem total (count sets))]
    (mapv #(+ q (if (< % r) 1 0)) (range (count sets)))))

(defn sweep-sources [sets desired rng]
  (let [total (reduce + desired)
        pools (mapv #(load-cases % rng total true) sets)
        first-pass (vec (mapcat take desired pools))
        remainder (- total (count first-pass))
        extras (take remainder (mapcat drop desired pools))]
    (when (< (count extras) remainder)
      (fail (str "only " (+ (count first-pass) (count extras)) " eligible receipts for a " total "-receipt sweep")))
    (into first-pass extras)))

(defn verify! [result]
  (let [buckets (:buckets result)
        {:keys [unflagged lost lost-flagged]} (sum-maps (map :actual (vals buckets)))
        disallowed-duplicate? (some (fn [[bucket score]]
                                      (and (not= "1" (name bucket)) (pos? (get-in score [:actual :dup]))))
                                    buckets)
        {:keys [changed-clean clean-gap changed-repeat total-missing total-duplicates false-merges]} (:checks result)
        {:keys [actual concat scan]} (:words result)]
    (when (or (pos? unflagged) disallowed-duplicate? (not= lost lost-flagged)
              (pos? changed-clean) (pos? clean-gap) (pos? changed-repeat) (pos? total-missing)
              (pos? total-duplicates) (pos? false-merges)
              (< (:recall actual) (:recall concat))
              (< (:recall actual) (- (:recall scan) 0.05))
              (< (:precision actual) (- (:precision scan) 0.04)))
      (fail "multishot acceptance failed (see per-bucket counts)"))))

(defn compare-sweep! [reference current]
  (when-not (= (:seam-metric reference) (:seam-metric current))
    (fail "sweep ratchet requires a reference scored with the same seam metric"))
  (when-not (= (select-keys reference [:seed :mode :input :cases])
               (select-keys current [:seed :mode :input :cases]))
    (fail "sweep ratchet requires the same seed, input mode and piece hashes"))
  (doseq [[engine old] (:engines reference)]
    (let [now (get-in current [:engines engine])]
      (when-not now (fail (str "sweep ratchet is missing engine " (name engine))))
      (doseq [metric [:precision :recall]]
        (when (< (get-in now [:words :actual metric]) (get-in old [:words :actual metric]))
          (fail (str "sweep ratchet: " (name engine) " word " (name metric) " fell"))))
      (doseq [[bucket old-scores] (:buckets old)
              metric [:dup :unflagged :spurious-gap]]
        (when (> (get-in now [:buckets bucket :actual metric]) (get-in old-scores [:actual metric]))
          (fail (str "sweep ratchet: " (name engine) " " (name bucket) " " (name metric) " rose")))))))

(defn score-cases [score cases manifests checkpoint]
  (if-not checkpoint
    (mapv score cases manifests)
    ;; Finish each pair before writing its scores or releasing the crop directory.
    (let [rows (into []
                     (mapcat (fn [batch]
                               (let [jobs (mapv (fn [[i case manifest]]
                                                  (future
                                                    (try {:index i :score (score case manifest)}
                                                         (catch Exception e {:index i :error (ex-message e)}))))
                                                batch)
                                     results (mapv deref jobs)]
                                 (doseq [row results] (checkpoint row))
                                 results)))
                     (partition-all 2 (map vector (range) cases manifests)))]
      (when-let [errors (seq (keep :error rows))]
        (fail (str (count errors) " sweep cases failed. First error: " (first errors))))
      (mapv :score rows))))

(defn crop-cases [python run-dir cases tiff]
  (let [jobs (mapv (fn [i case]
                     [(assoc case :tiff (boolean tiff))
                      (str (fs/create-dir (fs/path run-dir (str i))))])
                   (range) cases)
        input (str/join "\n" (map json/generate-string jobs))
        output (:out (shell {:in input :out :string :shutdown destroy}
                            python "tools/oracle/multishot/pieces.py" "--batch"))
        manifests (mapv #(json/parse-string % true) (str/split-lines output))]
    (when-not (= (count cases) (count manifests))
      (fail "crop generator returned the wrong number of manifests"))
    manifests))


;; ---- prepared cases (spool): crops are generated once, locally; the shards only need the driver ----
(defn rel-path [dir p] (str (fs/relativize (fs/absolutize dir) (fs/absolutize p))))

(defn write-prepared! [dir seed mode tiff selected cases manifests hashes]
  (fs/create-dirs dir)
  (let [dir (str dir)
        cases (vec (map-indexed
                    (fn [i case]
                      (cond-> (dissoc case :source)
                        (:other case) (assoc :other (let [from (:other case)
                                                          name (str "other-" i "." (fs/extension from))]
                                                      (fs/copy from (fs/path dir name) {:replace-existing true})
                                                      name))))
                    cases))
        ;; the crops live in the run directory, which is deleted when the run ends: they move into the prepared directory
        keep (fn [i from]
               (let [to (fs/path dir (str i) (fs/file-name from))]
                 (fs/create-dirs (fs/parent to))
                 (fs/copy from to {:replace-existing true})
                 (rel-path dir to)))
        manifests (vec (map-indexed
                        (fn [i m]
                          (cond-> (-> m (update :scan (partial keep i))
                                        (update :pieces (fn [ps] (mapv #(update % :path (partial keep i)) ps))))
                            (:sequence m) (update :sequence (partial keep i))))
                        manifests))]
    (spit (str (fs/path dir "cases.edn"))
          (pr-str {:seed seed :mode mode :tiff (boolean tiff) :engines selected
                   :cases cases :manifests manifests :hashes hashes}))))

(defn read-prepared [dir]
  (let [data (clojure.edn/read-string (slurp (str (fs/path dir "cases.edn"))))
        abs #(str (fs/absolutize (fs/path dir %)))]
    (-> data
        (update :cases (fn [cs] (mapv #(cond-> % (:other %) (update :other abs)) cs)))
        (update :manifests (fn [ms] (mapv (fn [m]
                                            (cond-> (-> m (update :scan abs)
                                                          (update :pieces (fn [ps] (mapv #(update % :path abs) ps))))
                                              (:sequence m) (update :sequence abs)))
                                          ms))))))

(defn run-shard! [prepared shard bin output]
  (let [{:keys [seed mode tiff engines cases manifests]} (read-prepared prepared)
        [index count] (mapv parse-long (str/split shard #"/"))
        engine (first engines)
        bin (driver engine bin)
        mine (filterv #(= index (mod % count)) (range (clojure.core/count cases)))]
    (when-not (and index count (< -1 index count) (= 1 (clojure.core/count engines)))
      (fail "a prepared shard needs --shard I/K and exactly one engine"))
    (spit output "")
    (score-cases (fn [case manifest]
                   (locking *err* (binding [*out* *err*] (println engine "seed" seed "case" (:id case) (:kind case))))
                   (run-case bin engine mode case manifest tiff))
                 (mapv cases mine) (mapv manifests mine)
                 (fn [{:keys [index score error]}]
                   (spit output (str (pr-str (cond-> {:index (nth mine index)}
                                               score (assoc :score score) error (assoc :error error))) "\n")
                         :append true)))
    (println "shard" shard "scored" (clojure.core/count mine) "of" (clojure.core/count cases) "cases")))

(defn merge-shards! [prepared files output]
  (let [{:keys [seed mode tiff engines cases hashes]} (read-prepared prepared)
        engine (first engines)
        rows (->> files
                  (mapcat #(str/split-lines (slurp (str %))))
                  (remove str/blank?)
                  (map clojure.edn/read-string)
                  (sort-by :index))]
    (when-not (= (range (count cases)) (map :index rows))
      (fail (str "shards cover " (count rows) " of " (count cases) " cases")))
    (let [scores (mapv :score rows)
          result (cond-> (multishot/report scores)
                   (not= mode "baseline") (assoc :failed-cases (vec (failed-cases cases scores))))
          data {:seed seed :mode mode :input (if tiff "tiff" "images") :seam-metric seam-metric
                :cases hashes :engines {engine result}}]
      (spit (str output) (str (json/generate-string data {:pretty true}) "\n"))
      (println (json/generate-string {engine result} {:pretty true}))
      (when (not= mode "baseline") (verify! result)))))

(defn main []
  (let [{:keys [ci sweep seed n set pieces ocr-engine report keep baseline clipped help tiff bin output ratchet prepare prepared shard merge]}
        (cli/parse-opts *command-line-args* {:spec spec :restrict true})
        result-file (if output (fs/path output) (fs/path scratch "report.json"))]
    (cond
      help (println "bb multishot [--ci | --sweep | --clipped] [--seed N] [--n N] [--set sroie,cord] [--pieces 2-5] [--ocr-engine tesseract|apple-vision|ppocr|all] [--baseline] [--report] [--keep] [--tiff] [--bin FILE] [--output FILE] [--ratchet FILE] (spool: --prepare DIR | --prepared DIR --shard I/K | --prepared DIR --merge FILE,..)")
      report (print (slurp (str result-file)))
      (and prepared shard) (run-shard! prepared shard bin (str result-file))
      (and prepared merge) (merge-shards! prepared (str/split merge #",") (str result-file))
      :else
      (let [reference (when ratchet (read-json ratchet))
            _ (when (and reference (not= seam-metric (:seam-metric reference)))
                (fail "sweep ratchet requires a reference scored with the same seam metric"))
            seed (or seed (:seed reference) (if ci 917 (.nextLong (java.util.Random.))))
            rng (java.util.Random. seed)
            sets (str/split (or set "sroie,cord") #",")
            [lo hi] (mapv parse-long (str/split (or pieces (if ci "2" "2-5")) #"-"))
            hi (or hi lo)
            selection (or ocr-engine (if sweep "all" "tesseract"))
            selected (if (= selection "all")
                       (cond-> ["tesseract"]
                         (clojure.string/starts-with? (System/getProperty "os.name") "Mac") (conj "apple-vision")
                         (System/getenv "EMON_PPOCR_LIB") (conj "ppocr"))
                       [selection])
            per-set (if (and ci (nil? n) (nil? set)) [12 4]
                        (counts sets (or n (if sweep 200 16))))]
        (when (or (> (count (filter identity [ci sweep clipped])) 1) (and clipped baseline)
                  (and ratchet (or (not sweep) baseline (= "baseline" (:mode reference))))
                  (some #(not (#{"sroie" "cord"} %)) sets)
                  (not (and lo hi (<= 2 lo hi 5))) (some #(not (some #{%} engines)) selected)
                  (some #(not (pos? %)) per-set))
          (fail "invalid mode, dataset, piece range, OCR engine or case count"))
        (let [sources (if sweep (sweep-sources sets per-set rng)
                          (vec (mapcat #(load-cases %1 rng %2) sets per-set)))
              cases (if clipped (mapv clip-case sources) (seam-cases sets sources rng ci lo hi))
              mode (cond clipped "clipped" baseline "baseline" :else "gate")]
          (fs/create-dirs scratch)
          (let [run-dir (fs/create-temp-dir {:dir scratch :prefix "run-"})
                python (or (System/getenv "EMON_MULTISHOT_PYTHON") "python3")
                checkpoint-file (str result-file ".cases.jsonl")]
            (try
              (println "multishot seed" seed "mode" mode "cases" (count cases) "engines" selected)
              (when sweep (spit checkpoint-file ""))
              (let [manifests (crop-cases python run-dir cases tiff)
                    hashes (mapv (fn [case manifest]
                                   {:id (:id case) :kind (:kind case) :order (:order case)
                                    :pieces (if clipped
                                              (into (sorted-map) (map (fn [[edge reading]] [edge (:sha256 reading)])) manifest)
                                              (mapv :sha256 (:pieces manifest)))})
                                 cases manifests)
                    results
                    (into {} (for [engine (if prepare [] selected)
                                   :let [bin (driver engine bin)
                                         rows (score-cases (fn [case manifest]
                                                      (locking *err*
                                                        (binding [*out* *err*]
                                                          (println engine "seed" seed "case" (:id case) (:kind case))))
                                                      (try
                                                        (if clipped
                                                          (clip-metrics bin engine manifest)
                                                          (run-case bin engine mode case manifest tiff))
                                                        (catch Exception e
                                                          (fail (str "seed " seed " case " (:id case) " " (:kind case) ": " (ex-message e))))))
                                                    cases manifests
                                                    (when sweep
                                                      (fn [{:keys [index] :as row}]
                                                        (spit checkpoint-file
                                                              (str (json/generate-string
                                                                    (assoc (dissoc row :index)
                                                                           :engine engine :seed seed
                                                                           :case (nth hashes index)))
                                                                   "\n")
                                                              :append true))))]]
                               [engine (if clipped
                                         (cond-> (clip-report rows) keep (assoc :case-scores rows))
                                         (cond-> (multishot/report rows)
                                           (not baseline) (assoc :failed-cases (vec (failed-cases cases rows)))
                                           keep (assoc :case-scores rows)))]))]
                (when prepare
                  (write-prepared! prepare seed mode tiff selected cases manifests hashes)
                  (println "prepared" (count cases) "cases in" prepare))
                (when-not prepare (let [data {:seed seed :mode mode :input (if tiff "tiff" "images")
                            :seam-metric seam-metric
                            :false-merge-metric false-merge-metric
                            :cases hashes :engines results}]
                  (spit (str result-file) (str (json/generate-string data {:pretty true}) "\n"))
                  (when reference (compare-sweep! reference (json/parse-string (json/generate-string data) true)))
                  (println (json/generate-string results {:pretty true}))))
                (when (and (or ci sweep) (not baseline) (not prepare))
                  (doseq [[engine summary] results]
                    (try (verify! summary)
                         (catch Exception e (fail (str "seed " seed " " engine ": " (ex-message e))))))))
              (finally
                (when-not keep (fs/delete-tree run-dir))))))))))

(main)
