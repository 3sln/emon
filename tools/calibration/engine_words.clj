#!/usr/bin/env bb
;; Fit a platform engine's WORD calibration map on a held-out split (K8, D900; D883 ruling 3).   bb tools/calibration/engine_words.clj --engine apple-vision [--n 60] [--bin FILE]
;;
;;   --collect   run the engine LIVE over the fit and held-out populations and keep the observations (raw word confidence and whether the word is in the gold) in
;;               $EMON_SCRATCH/calib-<engine>/obs.edn. Nothing of a document's text is kept: a word is a (confidence, right or wrong) pair
;;   --fit       fit the map on the FIT part, apply it to the HELD-OUT part, and write test/calibration/engines/<oen>/ (word-fit.edn, word-reliability.csv, word-summary.csv,
;;               word-reliability.svg) and the rows of kin/ocalibdata.kin (`bb tools/calibration/ocr_words.clj --write` joins the engines' rows; this runs it)
;;   --check     the committed files are what the committed fit record says (a gate's: no engine runs)
;;
;; THE POPULATIONS are disjoint from the acceptance population. The fixed 60 + 60 receipts of `bb receipts` (the ids of test/receipts/baseline.json) are never used; of the other
;; SROIE and CORD gold documents, ordered by the SHA-256 of their id, the first N of each dataset are the FIT part and the next N the HELD-OUT part. A word is RIGHT when it is a word
;; of the document's gold and has not already been matched by an earlier word of the same reading (a multiset match, per OCR run, so a second pass over the page is not scored
;; against the words the first one used).
;;
;; THE MAP is the weighted isotonic fit of D845/D883 ruling 6 (pool adjacent violators, Laplace-smoothed: (right + 1) / (n + 2), a thousand right of a thousand is 999) from the engine's
;; raw confidence, per mille, to the probability the word is right. An engine whose raw confidence says nothing (every word 1000: Vision at its accurate level with language correction,
;; dart1 section 3) gets one step, the base rate: honest, and the report says so.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str]
         '[accuracy.calib :as C] '[calibration.calibration :as calibration] '[clojure.set])
(import '[java.security MessageDigest])

(def root (str (fs/canonicalize ".")))
(def args (vec *command-line-args*))
(defn opt [f d] (or (second (drop-while #(not= f %) args)) d))
(defn flag? [f] (some #{f} args))
(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))

(def engines
  {"apple-vision" {:oen 3 :class 0 :driver ".cache/emon-rs-vision/target/release/emon-rs" :flag "--ocr-engine=apple-vision"
                   :config "apple-vision;VNRecognizeTextRequest;rev=latest;level=accurate;lc=1;lang=request-else-latin"}})
(def engine-name (opt "--engine" nil))
(def cfg (or (get engines engine-name) (die "usage: engine_words.clj --engine " (str/join "|" (keys engines)) " [--collect | --fit | --check]")))
(def oen (:oen cfg))
(def n-per (parse-long (opt "--n" "60")))
(def scratch (str (or (System/getenv "EMON_SCRATCH") (str root "/.cache/scratch")) "/calib-" engine-name))
(def out-dir (str root "/test/calibration/engines/" oen))
(def bin (opt "--bin" (str root "/" (:driver cfg))))

(defn sha256 [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String s "UTF-8"))))))
;; case-folded whitespace tokens (babashka has no java.text.Normalizer, so no NFC: gold and reading go through the same function, and the receipts are ASCII)
(defn words [s] (remove str/blank? (str/split (str/lower-case (str s)) #"\s+")))
(defn node-text [n]
  (if (some? (:text n)) (:text n) (str/join " " (map node-text (:children n)))))

;; ------------------------------------------------------------------------------------------------ the populations
(def acceptance-ids (set (let [b (json/parse-string (slurp (str root "/test/receipts/baseline.json")) true)]
                           (for [d (vals (:sets b)) r (:rows d)] (first r)))))
(defn gold-files [dataset]
  (->> (fs/glob (str root "/corpus/_gold/" dataset) "*.gold.json")
       (map str) (remove #(str/includes? % "/._")) (filter #(or (not= dataset "sroie") (str/includes? % "-test-"))) sort))
(defn gold-id [f] (str/replace (str (fs/file-name f)) #"\.gold\.json$" ""))
(defn populations []
  (into {} (for [ds ["sroie" "cord"]
                 :let [cands (->> (gold-files ds) (remove #(acceptance-ids (gold-id %))) (sort-by #(sha256 (gold-id %))))]]
             [ds {:fit (vec (take n-per cands)) :heldout (vec (take n-per (drop n-per cands)))}])))

;; ------------------------------------------------------------------------------------------------ collection
(defn read-gold [f]
  (let [g (json/parse-string (slurp f) true)
        variants (get-in g [:provenance :variants :image])]
    {:id (:id g) :image (str root "/corpus/" variants) :gold (words (node-text (get-in g [:model :body])))}))

(defn observations-of [{:keys [id image gold]}]
  (let [r (p/shell {:out :string :err :string :continue true :extra-env {"EMON_CS_SHARED_COMPILATION" "false"}} bin (:flag cfg) "--out=evidence" image)]
    (when-not (zero? (:exit r)) (die id ": the driver failed: " (subs (str (:err r)) 0 (min 300 (count (str (:err r)))))))
    (let [ev (json/parse-string (:out r) true)
          runs (vec (:oruns ev))
          ws (:owords ev)]
      (vec (mapcat
            (fn [[ri run]]
              (when (= oen (:engine run))
                (let [[w0 n] (:words run)
                      avail (atom (frequencies gold))]
                  (for [w (subvec (vec ws) w0 (+ w0 n)) :when (not (:from w))
                        :let [t (first (words (:text w)))
                              right (when t (if (pos? (get @avail t 0)) (do (swap! avail update t dec) 1) 0))]
                        :when t]
                    {:id id :run ri :conf (:conf w) :y (* 1000 right)}))))
            (map-indexed vector runs))))))

(defn pmap-n
  "`pmap` with at most `n` conversions in flight (each is a process of its own; the engine's cost is the machine's, not this script's)."
  [n f coll]
  (->> (partition-all n coll) (mapcat (fn [chunk] (mapv deref (mapv #(future (f %)) chunk)))) vec))

(defn collect! []
  (when-not (fs/exists? bin) (die bin " is not built: EMON_RS_VISION=1 kin/scripts/emon-rs-build"))
  (fs/create-dirs scratch)
  (let [pops (populations)
        jobs (vec (for [[ds {:keys [fit heldout]}] (sort-by key pops) [split files] [[:fit fit] [:heldout heldout]] f files] [ds split f]))
        total (count jobs)
        done (atom 0)
        obs (vec (mapcat identity
                         (pmap-n (parse-long (opt "--jobs" "3"))
                                 (fn [[ds split f]]
                                   (let [d (read-gold f) o (observations-of d)]
                                     (println (format "%d/%d %s %s %s: %d words" (swap! done inc) total ds (name split) (:id d) (count o))) (flush)
                                     (mapv #(assoc % :dataset ds :split split) o)))
                                 jobs)))]
    (spit (str scratch "/obs.edn") (pr-str obs))
    (println "kept" (count obs) "observations in" scratch)
    obs))

;; ------------------------------------------------------------------------------------------------ the fit
(defn smoothed
  "Laplace: (right + 1) / (n + 2), per mille, `sy` the sum of the outcomes per mille (as `calibration.clj`'s fit does)."
  [n sy]
  (long (Math/round (* 1000.0 (/ (+ (/ (double sy) 1000.0) 1.0) (+ n 2.0))))))

(defn fit-steps
  "[[raw-lo probability n] ...]: pool adjacent violators over the distinct raw confidences, each step Laplace-smoothed, then pooled once more, as `calibration.clj`'s `fit-points`."
  [rows]
  (let [pts (for [[raw rs] (sort-by key (group-by :conf rows))] [raw (count rs) (reduce + 0 (map :y rs))])
        shrunk (for [[lo _hi n sy] (C/pav pts)] [lo n (* n (smoothed n sy))])]
    (vec (for [[lo _hi n sy] (C/pav shrunk)] [lo (long (Math/round (/ (double sy) n))) n]))))

(defn apply-steps [steps raw] (second (or (last (filter #(<= (first %) raw) steps)) (first steps))))

(defn report-rows [rows tag steps]
  (mapv (fn [r] {:run tag :cat "receipt" :id (:id r) :kind "word" :class "ocr" :conf (if steps (apply-steps steps (:conf r)) (:conf r)) :y (:y r)}) rows))

(defn fit! [obs]
  (let [fit-rows (filterv #(= :fit (:split %)) obs)
        held (filterv #(= :heldout (:split %)) obs)
        steps (fit-steps fit-rows)
        cal-fit (report-rows fit-rows "fit" steps)
        cal-held (report-rows held "heldout" steps)
        raw-held (report-rows held "heldout-raw" nil)
        counts (fn [rows] (C/row-counts (map (juxt :kind :class :conf :y) rows)))
        stat (fn [rows] (let [c (counts rows)] {:n (C/unit-n c "word") :ece (C/ece c "word") :brier (C/brier c "word")}))
        base-rate (let [r (reduce + 0 (map :y fit-rows))] (/ (double r) (* 1000.0 (count fit-rows))))
        record {:engine oen :engine-name engine-name :config (:config cfg) :class (:class cfg) :method :weighted-pava-laplace
                :populations {:fit-docs (vec (sort (distinct (map :id fit-rows)))) :heldout-docs (vec (sort (distinct (map :id held))))
                              :disjoint-from "test/receipts/baseline.json"}
                :raw-confidence-values (vec (sort (distinct (map :conf obs))))
                :words {:fit (count fit-rows) :heldout (count held)}
                :base-rate-fit base-rate
                :rows (mapv (fn [[lo prob _]] [oen (:class cfg) 1 lo prob]) steps)
                :steps (mapv (fn [[lo prob n]] {:raw-lo lo :probability prob :n n}) steps)
                :fit-fit (stat cal-fit) :heldout-raw (stat raw-held) :heldout-calibrated (stat cal-held)
                :per-dataset (into {} (for [ds ["sroie" "cord"]
                                            :let [h (filterv #(= ds (:dataset %)) held)]]
                                        [ds {:words (count h) :calibrated (stat (report-rows h "h" steps)) :raw (stat (report-rows h "h" nil))}]))}]
    (fs/create-dirs out-dir)
    (spit (str out-dir "/word-fit.edn") (str (pr-str record) "\n"))
    (spit (str out-dir "/word-reliability.csv") ((ns-resolve 'calibration.calibration 'reliability-csv) cal-held))
    (spit (str out-dir "/word-summary.csv") ((ns-resolve 'calibration.calibration 'summary-csv) cal-held nil))
    (spit (str out-dir "/word-reliability.svg") ((ns-resolve 'calibration.calibration 'svg) cal-held (str engine-name " word calibration, held-out split")))
    (println "base rate (fit)" (format "%.4f" base-rate) "raw values" (:raw-confidence-values record))
    (println "steps" (:steps record))
    (println "held-out, raw        " (stat raw-held))
    (println "held-out, calibrated " (stat cal-held))
    (println "wrote" out-dir)
    record))

(defn check! []
  (let [f (str out-dir "/word-fit.edn")]
    (when-not (fs/exists? f) (die f " is missing: bb tools/calibration/engine_words.clj --engine " engine-name " --fit"))
    (let [r (edn/read-string (slurp f))]
      (when-not (and (= oen (:engine r)) (= (:config cfg) (:config r))) (die f " is not the fit of " engine-name " under " (:config cfg)))
      (when-not (apply <= (map #(nth % 4) (:rows r))) (die f ": the steps are not monotone"))
      (when-not (every? #(<= 0 (nth % 4) 1000) (:rows r)) (die f ": a probability is out of range"))
      (when-not (empty? (clojure.set/intersection (set (get-in r [:populations :fit-docs])) acceptance-ids)) (die f ": the fit population overlaps the acceptance population"))
      (when-not (empty? (clojure.set/intersection (set (get-in r [:populations :fit-docs])) (set (get-in r [:populations :heldout-docs])))) (die f ": the fit and held-out populations overlap"))
      (println (format "  ok   %s word map: %d steps, held-out ECE %.4f (raw %.4f), Brier %.4f" engine-name (count (:rows r))
                       (get-in r [:heldout-calibrated :ece]) (get-in r [:heldout-raw :ece]) (get-in r [:heldout-calibrated :brier]))))))

(cond
  (flag? "--check") (check!)
  (flag? "--collect") (collect!)
  (flag? "--fit") (let [obs (if (and (flag? "--cached") (fs/exists? (str scratch "/obs.edn"))) (edn/read-string (slurp (str scratch "/obs.edn"))) (collect!))]
                    (fit! obs)
                    (let [r (p/shell {:continue true} "bb" "tools/calibration/ocr_words.clj" "--write")]
                      (when-not (zero? (:exit r)) (die "ocr_words.clj --write failed"))))
  :else (die "say what to do: --collect, --fit [--cached] or --check"))
