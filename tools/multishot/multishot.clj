(ns multishot
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [process destroy shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def engines ["tesseract" "apple-vision" "ppocr"])
(def buckets ["gap" "abut" "partial" "1" "2-3" "4+"])
(def traps ["clean" "identical" "asymmetric" "repeat" "separator" "different-pages"])
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
(defn model-text [model]
  (str/join " " (keep :text (mapcat nodes (cons (:body model) (:furniture model))))))

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
        child (process command {:out :string :err :string :extra-env {"OMP_THREAD_LIMIT" "1"}})
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

(defn false-merges [case pieces dropped]
  (let [order (:order case)]
    (count (remove (fn [{:keys [page] :as row}]
                     (when (> page 1)
                       (let [left (pieces (order (- page 2)))
                             right (pieces (order (dec page)))
                             shared (:shared (seam-gold left right))]
                         (some #(row-read? (get-in case [:rows %]) (get-in right [:boxes %]) [row]) shared))))
                   dropped))))

(defn seam-score [rows {:keys [shared lost]} left-piece right-piece left right marks]
  (let [duplicates (filter #(and (row-read? (rows %) (get-in left-piece [:boxes %]) left)
                                 (row-read? (rows %) (get-in right-piece [:boxes %]) right)) shared)
        gap? (some #{"possible-gap" "seam-gap" "spliced"} marks)]
    {:seams 1 :shared (count shared) :dup (count duplicates)
     :unflagged (if (some #{"possible-duplicate"} marks) 0 (count duplicates))
     :lost (if (seq lost) 1 0) :lost-flagged (if (and (seq lost) gap?) 1 0)
     :spurious-gap (if (and (empty? lost) gap?) 1 0)}))

(defn run-case [bin engine mode case manifest]
  (let [pieces (:pieces manifest)
        paths (mapv :path pieces)
        order (:order case)
        separate (mapv #(bundle bin engine [] [%]) paths)
        scan (bundle bin engine [] [(:scan manifest)])
        actual (when (= mode "gate") (bundle bin engine [] [(:sequence manifest)]))
        control (when (and actual (:careful case))
                  (bundle bin engine ["--capture=scan"] [(:sequence manifest)]))
        text-of #(model-text (:model %))
        concat-text (str/join " " (map text-of (mapv separate order)))
        final-text (if actual (text-of actual) concat-text)
        before (mapv line-reads separate)
        after (if actual
                (let [pages (group-by :page (line-reads actual))]
                  (mapv #(get pages (inc (.indexOf order %))) (range (count pieces))))
                before)
        seams (get-in actual [:evidence :seams])
        scan-total (total-count (:total case) (text-of scan))
        concat-total (total-count (:total case) concat-text)
        actual-total (total-count (:total case) final-text)
        case-checks {:scan-total (if (pos? scan-total) 1 0)
                     :total-before concat-total
                     :total-after actual-total
                     :total-missing (if (and (pos? scan-total) (zero? actual-total)) 1 0)
                     :total-duplicates (if (pos? scan-total) (max 0 (- actual-total scan-total)) 0)
                     :false-merges (false-merges case pieces (dropped-seam-reads (:model actual)))
                     :changed-clean (if (and control (some #{"clean" "different-pages"} [(:kind case)])
                                             (not= (:canonical actual) (:canonical control))) 1 0)
                     :changed-repeat (if (and control (some #{"identical" "repeat"} [(:kind case)])
                                              (not= (:canonical actual) (:canonical control))) 1 0)}]
    {:words {:scan (score-words (:rows case) (text-of scan))
             :concat (score-words (:rows case) concat-text)
             :actual (score-words (:rows case) final-text)}
     :checks case-checks
     :seams (mapv (fn [i left right]
                    (let [gold (if (:other case) {:bucket "different-pages" :shared [] :lost []}
                                   (seam-gold left right))
                          a (inc (.indexOf order i))
                          b (inc (.indexOf order (inc i)))
                          marks (keep :kind (filter #(and (= a (:left %)) (= b (:right %))) seams))]
                      {:bucket (if (:careful case) (:kind case) (:bucket gold))
                       :concat (seam-score (:rows case) gold left right (before i) (before (inc i)) [])
                       :actual (seam-score (:rows case) gold left right (after i) (after (inc i)) marks)}))
                  (range) pieces (rest pieces))}))

(defn sum-maps [maps] (apply merge-with + {} maps))
(defn report [rows]
  {:words (into {} (for [kind [:scan :concat :actual]
                         :let [{:keys [gold-words read-words matched-words]}
                               (sum-maps (map #(get-in % [:words kind]) rows))]]
                     [kind {:precision (ratio matched-words read-words)
                            :recall (ratio matched-words gold-words)}]))
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

(defn failed-cases [cases scores strict?]
  (keep (fn [[case score]]
          (let [{:keys [dup unflagged lost lost-flagged spurious-gap] :as seams}
                (sum-maps (map :actual (:seams score)))
                checks (select-keys (:checks score)
                                    [:changed-clean :changed-repeat :false-merges :total-missing :total-duplicates])]
            (when (or (pos? unflagged) (and strict? (pos? dup)) (not= lost lost-flagged)
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
           :bin {:coerce :string}})

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

(defn verify! [result strict?]
  (let [buckets (map :actual (vals (:buckets result)))
        {:keys [unflagged dup lost lost-flagged spurious-gap]} (sum-maps buckets)
        {:keys [changed-clean changed-repeat total-missing total-duplicates false-merges]} (:checks result)
        {:keys [actual scan]} (:words result)]
    (when (or (pos? unflagged) (and strict? (pos? dup)) (not= lost lost-flagged)
              (pos? spurious-gap) (pos? changed-clean) (pos? changed-repeat) (pos? total-missing)
              (pos? total-duplicates) (pos? false-merges)
              (< (:recall actual) (- (:recall scan) 0.05))
              (< (:precision actual) (- (:precision scan) 0.04)))
      (fail "multishot acceptance failed (see per-bucket counts)"))))

(defn main []
  (let [{:keys [ci sweep seed n set pieces ocr-engine report keep baseline clipped help bin]}
        (cli/parse-opts *command-line-args* {:spec spec :restrict true})
        result-file (fs/path scratch "report.json")]
    (cond
      help (println "bb multishot [--ci | --sweep | --clipped] [--seed N] [--n N] [--set sroie,cord] [--pieces 2-5] [--ocr-engine tesseract|apple-vision|ppocr|all] [--baseline] [--report] [--keep] [--bin FILE]")
      report (print (slurp (str result-file)))
      :else
      (let [seed (or seed (if ci 917 (.nextLong (java.util.Random.))))
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
                python (or (System/getenv "EMON_MULTISHOT_PYTHON") "python3")]
            (try
              (println "multishot seed" seed "mode" mode "cases" (count cases) "engines" selected)
              (let [manifests
                    (mapv (fn [i case]
                            (let [dir (fs/create-dir (fs/path run-dir (str i)))]
                              (json/parse-string
                               (:out (shell {:in (json/generate-string case) :out :string}
                                            python "tools/oracle/multishot/pieces.py" (str dir)))
                               true)))
                          (range) cases)
                    hashes (mapv (fn [case manifest]
                                   {:id (:id case) :kind (:kind case) :order (:order case)
                                    :pieces (if clipped
                                              (into (sorted-map) (map (fn [[edge reading]] [edge (:sha256 reading)])) manifest)
                                              (mapv :sha256 (:pieces manifest)))})
                                 cases manifests)
                    results
                    (into {} (for [engine selected
                                   :let [bin (driver engine bin)
                                         rows (mapv (fn [case manifest]
                                                      (binding [*out* *err*]
                                                        (println engine "seed" seed "case" (:id case) (:kind case)))
                                                      (try
                                                        (if clipped
                                                          (clip-metrics bin engine manifest)
                                                          (run-case bin engine mode case manifest))
                                                        (catch Exception e
                                                          (fail (str "seed " seed " case " (:id case) " " (:kind case) ": " (ex-message e))))))
                                                    cases manifests)]]
                               [engine (if clipped
                                         (clip-report rows)
                                         (cond-> (multishot/report rows)
                                           (not baseline) (assoc :failed-cases (vec (failed-cases cases rows ci)))))]))]
                (let [output (json/generate-string {:seed seed :mode mode :cases hashes :engines results} {:pretty true})]
                  (spit (str result-file) (str output "\n"))
                  (println (json/generate-string results {:pretty true})))
                (when (and (or ci sweep) (not baseline))
                  (doseq [[engine summary] results]
                    (try (verify! summary ci)
                         (catch Exception e (fail (str "seed " seed " " engine ": " (ex-message e))))))))
              (finally
                (when-not keep (fs/delete-tree run-dir))))))))))

(main)
