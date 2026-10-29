(ns table.ab
  "bb tables-ab: two table engines on the same inputs, scored against gold (TR-design). NOT a gate: it needs corpus/, the pinned
  pdfium, Tesseract and the frozen inputs, and takes an hour or more. It is the acceptance instrument of the table redesign.

    bb tables-ab record [--pop P,..] [--jobs N]          record each `record` population LIVE once (pdfium renders and Tesseract
                                                          readings, with the bitmaps) under SCRATCH/rec/, so both engines replay the
                                                          SAME port answers and the comparison is the table stage's alone
    bb tables-ab run --label L [--bin BIN] [--args A] [--pop P,..] [--jobs N]
                                                          convert every population with one engine: replay where recorded (a replay
                                                          that misses, because the engine asked other questions, falls back to LIVE and
                                                          is counted), direct for the synthetic corpus, LIVE for the 54 controls
    bb tables-ab score --label L [--pop P,..]             score one run against gold: SCRATCH/runs/L/scores.json (numbers only)
    bb tables-ab baseline --label L [--write]             the frozen baseline: test/tables-ab/baseline.json (IDs and scores only);
                                                          without --write, compare L with the committed baseline (identity of engine A)
    bb tables-ab compare --a LA --b LB                    the pre-registered criteria of test/tables-ab/criteria.edn: ACCEPT or REJECT
    bb tables-ab ab [--bin BIN] [--jobs N]                the whole comparison: run and score A (`--tables=legacy`) and B (`--tables=scored`)
                                                          of ONE build, check A against the frozen baseline, compare

  THE SWITCH. Both engines are one build: the option `tables=legacy|scored` (kin/config.kin, driver `--tables=`), legacy the default
  until acceptance. --args gives an engine's extra driver arguments; `ab` passes `--tables=legacy` and `--tables=scored`. A driver
  without the option (today's) refuses `--tables=` with usage: `ab` then stops, and only `run`/`baseline` of the current engine work.

  SCRATCH is /Volumes/SSK SSD/emon-scratch/trd (EMON_TABLES_AB_SCRATCH overrides): Models, transcripts and logs stay there, never in
  the repository. Inputs are checked against their frozen sha256 before use."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [accuracy.score :as S]
            [table.abscore :as AS]))

(def root (str (fs/canonicalize ".")))
(def scratch (or (System/getenv "EMON_TABLES_AB_SCRATCH") "/Volumes/SSK SSD/emon-scratch/trd"))
(def pops-file "test/tables-ab/populations.json")
(def manifest (delay (json/parse-string (slurp pops-file))))
(def args (vec *command-line-args*))
(defn opt [k] (second (drop-while #(not= % k) args)))
(defn flag? [k] (some #{k} args))

(defn default-bin []
  (str root "/.cache/emon-rs-fusion/target/release/emon-rs"))

(defn safe [id] (str/replace id #"[/ ]" "__"))

(defn abs-input [path] (if (str/starts-with? path "/") path (str root "/" path)))

(defn sha256 [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 1048576)]
    (with-open [in (java.io.FileInputStream. (str path))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn free-gb [] (/ (.getUsableSpace (java.io.File. "/System/Volumes/Data")) 1e9))
(defn guard-disk! []
  (when (< (free-gb) 5.0)
    (println "  FAIL under 5 GB free on the internal drive: stopping") (System/exit 3)))

(defn pop-names []
  (let [sel (some-> (opt "--pop") (str/split #","))
        all ["synth" "icdar2013" "fintabnet" "ctdar" "ledgers" "named" "heldout" "controls"]]
    (if sel (filterv (set sel) all) all)))

(defn pop [n] (get-in @manifest ["populations" n]))
(defn flags-of [n]
  (let [f (get (pop n) "flags")]
    (get-in @manifest ["flags" (or f (if (= n "synth") "synth" "live"))])))

(defn time-run
  "Run CMD with stdout to OUT and stderr to ERR under /usr/bin/time -l: {:exit :user :sys :rss}."
  [cmd out err env]
  (let [tfile (str err ".time")
        r @(p/process (into ["/usr/bin/time" "-l" "-o" tfile] cmd)
                      {:out (fs/file out) :err (fs/file err) :extra-env env})
        t (if (fs/exists? tfile) (slurp tfile) "")
        num (fn [re] (some-> (re-find re t) second parse-double))]
    (fs/delete-if-exists tfile)
    {:exit (:exit r) :user (num #"([\d.]+) user") :sys (num #"([\d.]+) sys")
     :rss (some-> (re-find #"(\d+)\s+maximum resident set size" t) second parse-long)}))

(defn parallel
  "Apply f to every item on `jobs` workers, in order of the items; returns the results in that order."
  [jobs f items]
  (let [q (java.util.concurrent.LinkedBlockingQueue. ^java.util.Collection (vec (map-indexed vector items)))
        out (atom {})
        ws (doall (for [_ (range jobs)]
                    (future (loop []
                              (when-let [[i x] (.poll q)]
                                (swap! out assoc i (f x))
                                (recur))))))]
    (run! deref ws)
    (mapv @out (range (count items)))))

(defn verify-input! [item]
  (when-let [want (get item "sha256")]
    (let [got (sha256 (abs-input (get item "input")))]
      (when (not= want got)
        (throw (ex-info (str "input digest differs: " (get item "id")) {:id (get item "id")}))))))

;; ------------------------------------------------------------------ record

(defn rec-dir [n item] (str scratch "/rec/" n "/" (safe (get item "id"))))

(defn record! []
  (let [bin (or (opt "--bin") (default-bin))
        jobs (or (some-> (opt "--jobs") parse-long) 2)]
    (doseq [n (pop-names) :when (= "record" (get (pop n) "mode"))]
      (let [items (get (pop n) "items")
            res (parallel jobs
                          (fn [item]
                            (let [dir (rec-dir n item)]
                              (if (fs/exists? (str dir "/transcript.edn"))
                                :kept
                                (do (guard-disk!)
                                    (verify-input! item)
                                    (fs/create-dirs (fs/parent dir))
                                    (let [log (str scratch "/logs/rec-" n "-" (safe (get item "id")))
                                          _ (fs/create-dirs (fs/parent log))
                                          r (time-run (into [bin] (concat (flags-of n) [(str "--record=" dir) (abs-input (get item "input"))]))
                                                      "/dev/null" (str log ".err") {"OMP_THREAD_LIMIT" "1"})]
                                      (if (zero? (:exit r))
                                        (do (println "recorded" n (get item "id") (:user r) "s") :recorded)
                                        (do (fs/delete-tree dir) (println "FAILED record" n (get item "id") (:exit r)) :failed)))))))
                          items)]
        (println "RECORD" n (frequencies res))))))

;; ------------------------------------------------------------------ run

(defn run-dir [label] (str scratch "/runs/" label))

(defn convert!
  "One conversion of one item with one engine; writes MODEL.json and MODEL.cost.json. Returns the cost map."
  [label bin extra n item]
  (let [dir (str (run-dir label) "/" n)
        base (str dir "/" (safe (get item "id")))
        out (str base ".json") err (str base ".err")
        mode (get (pop n) "mode")
        input (abs-input (get item "input"))
        rec (rec-dir n item)
        replay? (and (= mode "record") (fs/exists? (str rec "/transcript.edn")))
        env (if (= mode "live") {"OMP_THREAD_LIMIT" "1"} {"OMP_THREAD_LIMIT" "1"})
        cmd (fn [how] (into [bin] (concat (flags-of n) extra (when (= how :replay) [(str "--replay=" rec)]) [input])))]
    (fs/create-dirs dir)
    (if (and (fs/exists? (str base ".cost.json")) (fs/exists? out) (not (flag? "--force")))
      (json/parse-string (slurp (str base ".cost.json")) true)
      (do
        (guard-disk!)
        (verify-input! item)
        (let [r1 (when replay? (time-run (cmd :replay) out err env))
              [r how] (if (and r1 (zero? (:exit r1)))
                        [r1 "replay"]
                        [(time-run (cmd :live) out err env) (if replay? "replay-miss-live" (if (= mode "direct") "direct" "live"))])
              pages (when (zero? (:exit r)) (try (count (get (json/parse-string (slurp out)) "pages")) (catch Exception _ nil)))
              cost (assoc r :how how :pages pages)]
          (spit (str base ".cost.json") (json/generate-string cost))
          (when-not (zero? (:exit r)) (println "FAILED" label n (get item "id") "exit" (:exit r)))
          cost)))))

(defn goldens!
  "The test/table goldens (`kin/scripts/table-goldens` flags): how many the engine reproduces byte for byte."
  [label bin extra]
  (let [dir (str (run-dir label) "/goldens")]
    (fs/create-dirs dir)
    (vec (for [g (get (pop "synth") "goldens")]
           (let [out (:out (p/sh (into [bin] (concat ["--pretty" "--furniture=keep"] extra [(abs-input (get g "input"))]))))]
             {"id" (get g "id") "same" (= (str out "\n") (slurp (abs-input (get g "golden"))))})))))

(defn run! [label bin extra]
  (let [jobs (or (some-> (opt "--jobs") parse-long) 2)]
    (spit (str (doto (run-dir label) fs/create-dirs) "/engine.json")
          (json/generate-string {"bin" bin "bin-sha256" (sha256 bin) "args" extra}))
    (doseq [n (pop-names)]
      (let [items (get (pop n) "items")
            ;; the controls run serially (heavy, live, and their CPU is a measured cost)
            costs (parallel (if (= n "controls") 1 jobs) #(convert! label bin extra n %) items)]
        (println "RUN" label n (count items) "items" (frequencies (map :how costs)))
        (when (= n "synth")
          (spit (str (run-dir label) "/goldens.json") (json/generate-string (goldens! label bin extra))))))))

;; ------------------------------------------------------------------ score

(defn model-of [label n item]
  (let [f (str (run-dir label) "/" n "/" (safe (get item "id")) ".json")]
    (when (fs/exists? f) (try (json/parse-string (slurp f)) (catch Exception _ nil)))))

(defn cost-of [label n item]
  (let [f (str (run-dir label) "/" n "/" (safe (get item "id")) ".cost.json")]
    (when (fs/exists? f) (json/parse-string (slurp f) true))))

(defn score! [label]
  (let [named-exp (json/parse-string (slurp "test/tables-ab/named.json"))
        out (into (sorted-map)
                  (for [n (pop-names)]
                    (let [items (get (pop n) "items")
                          docs (vec (for [item items]
                                      (let [m (model-of label n item)
                                            c (cost-of label n item)]
                                        ;; a failed conversion scores as an empty Model: its gold still counts, as misses
                                        (assoc (cond-> (AS/score-doc n item (or m {"pages" [] "body" {"id" 0 "kind" "document"}}) named-exp)
                                                 (nil? m) (assoc "failed" true))
                                               "id" (get item "id") "part" (get item "part")
                                               "cost" (into {} (for [[k v] (select-keys c [:user :sys :rss :pages :how])] [(name k) v]))))))]
                      [n {"docs" docs "pooled" (AS/pool n docs)}])))
        out (cond-> out
              (some #{"named"} (pop-names)) (assoc-in ["named" "ownership"] (AS/ownership (str (run-dir label) "/named") (get-in @manifest ["populations" "named"])))
              (some #{"controls"} (pop-names)) (assoc-in ["controls" "words"] (AS/control-words (str (run-dir label) "/controls") (get (pop "controls") "items")))
              (and (some #{"synth"} (pop-names)) (fs/exists? (str (run-dir label) "/goldens.json")))
              (assoc-in ["synth" "goldens"] (json/parse-string (slurp (str (run-dir label) "/goldens.json")))))
        f (str (run-dir label) "/scores.json")
        ;; a partial score (--pop) replaces only its populations
        all (merge (when (fs/exists? f) (json/parse-string (slurp f))) out)]
    (spit f (json/generate-string (into (sorted-map) all) {:pretty true}))
    (AS/print-summary label out)
    out))

;; ------------------------------------------------------------------ baseline and compare

(defn read-scores [label] (json/parse-string (slurp (str (run-dir label) "/scores.json"))))

(defn baseline! [label]
  (let [s (read-scores label)
        eng (json/parse-string (slurp (str (run-dir label) "/engine.json")))
        b (AS/baseline-of s eng)]
    (if (flag? "--write")
      (do (spit "test/tables-ab/baseline.json" (str (json/generate-string b {:pretty true}) "\n"))
          (println "  ok wrote test/tables-ab/baseline.json"))
      (let [old (json/parse-string (slurp "test/tables-ab/baseline.json"))
            diffs (AS/baseline-diff old b)]
        (if (empty? diffs)
          (println "  ok" label "reproduces the frozen baseline (every population's headline and every document score)")
          (do (doseq [d (take 40 diffs)] (println "  DIFF" d))
              (println "  FAIL" label "differs from the frozen baseline in" (count diffs) "scores")))
        (count diffs)))))

(defn compare! [la lb]
  (let [crit (edn/read-string (slurp "test/tables-ab/criteria.edn"))
        r (AS/compare-runs crit (read-scores la) (read-scores lb)
                           (str (run-dir la) "/controls") (str (run-dir lb) "/controls")
                           (get (pop "controls") "items"))]
    (spit (str scratch "/compare-" la "-" lb ".json") (json/generate-string r {:pretty true}))
    (AS/print-compare r)
    (when-not (:accept r) (System/exit 1))))

(defn supports-switch? [bin]
  (let [r (p/sh [bin "--tables=legacy" "/dev/null"])]
    (not (str/includes? (str (:err r)) "usage"))))

(let [cmd (first args)]
  (case cmd
    "record" (record!)
    "run" (run! (or (opt "--label") (throw (ex-info "--label" {}))) (or (opt "--bin") (default-bin))
                (vec (remove str/blank? (str/split (or (opt "--args") "") #"\s+"))))
    "score" (score! (opt "--label"))
    "baseline" (baseline! (opt "--label"))
    "compare" (compare! (opt "--a") (opt "--b"))
    "ab" (let [bin (or (opt "--bin") (default-bin))]
           (if-not (supports-switch? bin)
             (do (println "  FAIL" bin "has no `--tables=` switch: the scored engine is not built yet (run `bb tables-ab run --label NAME` for the current engine)")
                 (System/exit 2))
             (do (run! "A" bin ["--tables=legacy"]) (score! "A")
                 (run! "B" bin ["--tables=scored"]) (score! "B")
                 ;; the legacy path of the new build must still be the frozen engine: a switch that moves A is not a switch
                 (let [moved (baseline! "A")]
                   (when (pos? moved) (println "  FAIL engine A moved off the frozen baseline: the comparison below is void"))
                   (compare! "A" "B")
                   (when (pos? moved) (System/exit 1))))))
    (println (:doc (meta (the-ns 'table.ab))))))
