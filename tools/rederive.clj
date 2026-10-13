#!/usr/bin/env bb
;; RE-DERIVE every committed digest and golden that a legitimate change of outputs moves (D488; D398, D470, D487). NOT a gate.
;;
;;     bb rederive                 run every step below, in order, then list what changed (git diff --stat over test/ and gen/)
;;     bb rederive --check         the guard: every replay gate has a re-derivation registered here (see `exempt`), and nothing a step names is missing
;;     bb rederive --list          the steps, in order
;;     bb rederive --only ID,ID    run only these steps
;;
;; A calibration refit (`bb calibration --refit`), a `conf` value, a node change: each moves the bundles that the replay gates hold and the goldens that carry
;; a confidence. Each used to be re-derived by its own command, and the one nobody listed (`bb retarget`, D487) sat red on main across several merges. This
;; runs them all, REPLAY ONLY: no pdfium, no Tesseract, nothing live (the transcripts and bitmaps are the committed ones and are never rewritten). On a tree that
;; is consistent it changes nothing. It does NOT record improvements into the scoreboard (`bb accuracy --update` is a decision) and does NOT refit
;; (`bb calibration --refit`): it re-baselines only the calibration rows (`conf-ece-*`, `conf-brier-*`) that read worse than the entry, as D398 says.
;; The two heading goldens with a list or inline styles and `paratest`'s `conf` literal (D470) are set by the `heading` and `para` steps (D488).
;;
;; THE GUARD. A gate whose script replays transcripts (`--replay`) or reads a DIGESTS file must be COVERED by a step (`:covers`) or be in `exempt` with a
;; reason, and every `DIGESTS.edn` under test/ must be written by a step (`:writes`) or be in `exempt-files`. A new replay gate cannot be added without one.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn die [& m] (println "  FAIL" (apply str m)) (System/exit 1))

;; ------------------------------------------------------------------------------------------------------------------ what the steps need to know
(defn dirs-of [d] (if (fs/exists? d) (vec (sort (map fs/file-name (filter fs/directory? (fs/list-dir d))))) []))
(defn with-transcript [d] (vec (filter #(fs/exists? (str d "/" % "/transcript.edn")) (dirs-of d))))
(defn px-doc [d] (if (str/includes? d "--") (str/split d #"--" 2) ["scan" d]))

(defn accuracy [pred & more]
  (into ["bb" "tools/accuracy/accuracy.clj" "--predictor" pred "--rebaseline-conf"] more))

;; The same documents each gate's own ratchet scores (kin/scripts/ocr, fusion, sealpx, sigs, asset-check), so the entry that is re-baselined is the entry that is held.
(defn sealpx-scan-ids []
  (let [fx (edn/read-string (slurp "test/sealpx/FIXTURES.edn"))
        have (set (with-transcript "test/sealpx/replay"))]
    (vec (for [[k v] (sort fx) :when (and (= :scan (:kind v)) (contains? have k))] (:gold-id v)))))

;; ------------------------------------------------------------------------------------------------------------------ the steps, in the order that is right
;; :cmd is a vector, or a function giving one; :covers the replay gates it re-derives; :writes the committed files or directories it may change.
(def steps
  [{:id "emit" :why "the generated tree follows kin/ (a refit writes kin/calibdata.kin; every driver below is built from gen/)"
    :cmd ["kin/scripts/emit"] :quiet true :writes ["gen"]}
   ;; goldens that carry a confidence, from the references and the Rust driver
   {:id "json" :why "test/json goldens, written by the second implementation (json-check.py), which also holds them to the schema"
    :cmd ["python3" "kin/scripts/json-check.py" "--write"] :writes ["test/json"]}
   {:id "html" :why "test/html goldens, written by the second implementation (html-check.py) from the JSON goldens"
    :cmd ["python3" "kin/scripts/html-check.py" "--write"] :writes ["test/html"]}
   {:id "para" :why "test/para goldens from the Rust driver" :cmd ["bb" "kin/scripts/para-goldens"] :writes ["test/para"]}
   {:id "heading" :why "test/heading goldens from the Rust driver" :cmd ["bb" "kin/scripts/heading-goldens"] :writes ["test/heading"]}
   {:id "table" :why "test/table goldens from the Rust driver" :cmd ["bb" "kin/scripts/table-goldens"] :writes ["test/table"]}
   {:id "slice" :why "test/slice HTML goldens (after para: `bb slice` holds its JSON to test/para); only the Rust and Go hosts are needed to write them"
    :cmd ["bb" "slice" "--update"] :env {"EMON_SKIP_JAVA" "1" "EMON_SKIP_CSHARP" "1" "EMON_SKIP_C" "1" "EMON_SKIP_POD" "1"} :writes ["test/slice"]}
   ;; the replay sets' digests: the committed transcripts through the Rust driver
   {:id "fusion" :why "the fusion replay set's digests" :covers ["fusion"]
    :cmd ["bb" "fusion-record" "--digests"] :writes ["test/fusion/replay/DIGESTS.edn"]}
   {:id "artifacts" :why "the watermark set's digests" :covers ["artifacts"]
    :cmd ["bb" "fusion-record" "--artifacts" "--digests"] :writes ["test/artifacts/replay/DIGESTS.edn"]}
   {:id "seals" :why "the seal, stamp and signature set's digests" :covers ["seals"]
    :cmd ["bb" "fusion-record" "--seals" "--digests"] :writes ["test/seals/replay/DIGESTS.edn"]}
   {:id "sealpx" :why "the seals-from-pixels set's digests" :covers ["sealpx"]
    :cmd ["bb" "sealpx-record" "--digests"] :writes ["test/sealpx/replay/DIGESTS.edn"]}
   {:id "sigs" :why "the signatures set's digests" :covers ["sigs"]
    :cmd ["bb" "sigs-record" "--digests"] :writes ["test/signatures/replay/DIGESTS.edn"]}
   {:id "retarget" :why "the targeted-rounds set's digests (Rust and Go must agree; D487)" :covers ["retarget"]
    :cmd ["bb" "retarget-record" "--digests"] :writes ["test/retarget/replay/DIGESTS.edn"]}
   {:id "asset-figures" :why "the figures-level asset set's digests: JSON, HTML in three modes, asset files" :covers ["asset"]
    :cmd ["bb" "asset-fig-record" "--digests"] :writes ["test/asset/figures/DIGESTS.edn"]}
   ;; the scoreboard's calibration rows of the entries the replay gates hold (D398)
   {:id "board-emon" :why "scoreboard `emon` conf rows (the `accuracy` gate's entry)"
    :cmd (fn [] (accuracy "emon")) :writes ["test/scoreboard.edn"]}
   {:id "board-fusion" :why "scoreboard `emon-fusion` conf rows" :covers ["fusion"]
    :cmd (fn [] (accuracy "emon-fusion" "--variants" "pdf" "--ids" (str/join "," (with-transcript "test/fusion/replay")))) :writes ["test/scoreboard.edn"]}
   {:id "board-sealpx" :why "scoreboard `emon-sealpx` conf rows" :covers ["sealpx"]
    :cmd (fn [] (accuracy "emon-sealpx" "--variants" "scan-ovl" "--ids" (str/join "," (sealpx-scan-ids)))) :writes ["test/scoreboard.edn"]}
   {:id "board-sigs" :why "scoreboard `emon-sigs` conf rows" :covers ["sigs"]
    :cmd (fn [] (accuracy "emon-sigs")) :writes ["test/scoreboard.edn"]}
   {:id "board-ocr-scans" :why "ocr section 5: scoreboard `emon-replay` conf rows (the slim scan transcripts)" :covers ["ocr"]
    :cmd (fn [] (accuracy "emon-replay" "--variants" "scan" "--ids" (str/join "," (dirs-of "test/ocr/scan")))) :writes ["test/scoreboard.edn"]}
   {:id "board-ocr-px" :why "ocr section 6: scoreboard `emon-replay-px` conf rows (the whole pixel transcripts)" :covers ["ocr"]
    :cmd (fn [] (accuracy "emon-replay-px" "--docs" (str/join "," (map #(let [[v i] (px-doc %)] (str v "/" i)) (dirs-of "test/ocr/scan-px"))))) :writes ["test/scoreboard.edn"]}
   {:id "board-figures" :why "scoreboard `emon-figures` conf rows" :covers ["asset"]
    :cmd (fn [] (accuracy "emon-figures")) :writes ["test/scoreboard.edn"]}])

;; Gates that replay or read digests and need NO re-derivation, with the reason. A stale entry (not a replay gate any more) fails the check.
(def exempt
  {"identity" "compares the hosts with each other over transcripts it records or replays; it holds no digest or golden of its own"
   "png-check" "test/png/digests.txt is the SHA-256 of what the PNG encoder writes (no replay, no confidence): `bb png-check --update-digests` after an encoder change"})
;; Digest files that no step writes, with the reason.
(def exempt-files
  {"test/png/digests.txt" "the PNG encoder's own digests (see png-check above)"})

;; The gate scripts that are not kin/scripts/NAME.
(def gate-script {"asset" "kin/scripts/asset-check"})

;; ------------------------------------------------------------------------------------------------------------------ the guard
(defn gates []
  (let [s (slurp "kin/scripts/gates")]
    (vec (str/split (second (re-find #"(?m)^gates=\"([^\"]*)\"" s)) #"\s+"))))

(defn bb-tasks []
  (set (map second (re-seq #"(?m)^[ {]*([a-z][a-z-]*) *\{:doc" (slurp "bb.edn")))))

(defn replay-gate?
  "Does this gate's script replay transcripts or read a DIGESTS file?"
  [script-text]
  (boolean (re-find #"--replay|DIGESTS" script-text)))

(defn problems
  "-> a vector of strings. Pure: the gates, the text of each gate's script, the registry, the exemptions, the digest files and the bb tasks are arguments."
  [{:keys [gates script-texts steps exempt exempt-files digest-files tasks]}]
  (let [covered (set (mapcat :covers steps))
        written (set (mapcat :writes steps))
        replay (set (for [g gates :let [t (get script-texts g)] :when (and t (replay-gate? t))] g))]
    (vec
     (concat
      (for [g (sort replay) :when (not (or (covered g) (contains? exempt g)))]
        (str "replay gate `" g "` has no re-derivation: add a step with :covers [\"" g "\"] to tools/rederive.clj (or, if it holds no digest or golden, an `exempt` entry with the reason)"))
      (for [g (sort (keys exempt)) :when (not (replay g))]
        (str "`" g "` is exempt but is not a replay gate (or not a gate): remove it from `exempt`"))
      (for [g (sort (keys exempt)) :when (covered g)]
        (str "`" g "` is both covered by a step and exempt"))
      (for [g (sort covered) :when (not (some #{g} gates))]
        (str "a step covers `" g "`, which is not a gate (kin/scripts/gates)"))
      (for [g (sort covered) :when (and (some #{g} gates) (not (replay g)))]
        (str "a step covers `" g "`, whose script neither replays nor reads a DIGESTS file"))
      (for [f (sort digest-files) :when (not (or (some #(or (= f %) (str/starts-with? f (str % "/"))) written) (contains? exempt-files f)))]
        (str f " is written by no step: add it to a step's :writes (or `exempt-files`)"))
      (for [s steps :let [c (:cmd s) c (when (vector? c) (if (= "python3" (first c)) (vec (cons "bb" (rest c))) c))] :when c
            :let [[a b] c]
            :when (if (= a "bb")
                    (if (str/includes? (str b) "/") (not (fs/exists? b)) (not (contains? tasks b)))
                    (not (fs/exists? a)))]
        (str "step `" (:id s) "` runs " (pr-str (vec (take 2 c))) ", which does not exist"))
      (for [s steps :when (str/blank? (:why s))] (str "step `" (:id s) "` has no :why"))
      (let [ids (map :id steps)] (for [[i n] (frequencies ids) :when (> n 1)] (str "step id `" i "` is used twice")))))))

(defn current-problems []
  (let [gs (gates)]
    (problems {:gates gs
               :script-texts (into {} (for [g gs :let [f (get gate-script g (str "kin/scripts/" g))] :when (fs/exists? f)] [g (slurp f)]))
               :steps steps :exempt exempt :exempt-files exempt-files
               :digest-files (map str (fs/glob "test" "**/DIGESTS.edn"))
               :tasks (bb-tasks)})))

(defn check! []
  ;; the guard guards: a planted replay gate with no step, and a planted DIGESTS file with none, must both be reported
  (let [base {:gates ["fusion" "newgate"] :script-texts {"fusion" "--replay" "newgate" "reads --replay and DIGESTS"}
              :steps [{:id "f" :why "x" :cmd ["kin/scripts/gates"] :covers ["fusion"] :writes ["test/fusion/replay/DIGESTS.edn"]}]
              :exempt {} :exempt-files {} :digest-files ["test/fusion/replay/DIGESTS.edn" "test/new/replay/DIGESTS.edn"] :tasks #{}}
        got (problems base)]
    (when-not (and (= 2 (count got)) (some #(str/includes? % "`newgate` has no re-derivation") got) (some #(str/includes? % "test/new/replay/DIGESTS.edn is written by no step") got))
      (die "the guard's self-test did not report the planted gate and digest file: " (pr-str got))))
  (let [ps (current-problems)]
    (doseq [p ps] (println "  FAIL" p))
    (when (seq ps) (System/exit 1))
    (println "  ok   rederive: every replay gate has a re-derivation (" (count (set (mapcat :covers steps))) "covered:" (str/join " " (sort (set (mapcat :covers steps))))
             "; exempt:" (str/join " " (sort (keys exempt))) "), and every DIGESTS.edn is written by a step")))

;; ------------------------------------------------------------------------------------------------------------------ the run
(defn changed [] (str/trim (:out (shell {:out :string :err :string :continue true} "git" "status" "--porcelain" "--" "test" "gen"))))

(defn run-steps [only]
  (let [chosen (if only (filter #(contains? only (:id %)) steps) steps)
        _ (when-let [bad (seq (remove (set (map :id steps)) only))] (die "no such step: " (str/join " " bad)))
        before (changed)
        failed (atom [])]
    (when (seq before) (println "  note: test/ and gen/ already differ from HEAD in" (count (str/split-lines before)) "path(s); the list at the end includes them"))
    (doseq [s chosen]
      (let [c (if (fn? (:cmd s)) ((:cmd s)) (:cmd s))
            t0 (System/currentTimeMillis)]
        (println (str "\n== " (:id s) ": " (:why s) "\n   $ " (str/join " " c)))
        (let [r (try (apply shell (cond-> {:continue true :extra-env (or (:env s) {})} (:quiet s) (assoc :out :string :err :string)) c)
                     (catch Exception e (println "  " (ex-message e)) {:exit 127}))]
          (if (zero? (:exit r))
            (println (format "   done %s (%.0f s)" (:id s) (/ (- (System/currentTimeMillis) t0) 1000.0)))
            (do (println "  FAIL step" (:id s) "exited" (:exit r)) (swap! failed conj (:id s)))))))
    (println "\n== what changed (git diff --stat over test/ and gen/, against HEAD)")
    (shell {:continue true} "git" "diff" "--stat" "HEAD" "--" "test" "gen")
    (let [untracked (filter #(str/starts-with? % "??") (str/split-lines (changed)))]
      (when (seq untracked) (println "  untracked:") (doseq [u untracked] (println "   " u))))
    (when (str/blank? (changed)) (println "  nothing changed: every digest and golden is what the replays give"))
    (if (seq @failed)
      (die "steps failed: " (str/join " " @failed))
      (println "  ok   rederive: " (count chosen) "step(s) ran" (if (str/blank? (changed)) "and changed nothing" "(review the diff above)")))))

(let [args (vec *command-line-args*)
      only (when-let [i (.indexOf args "--only")] (when (>= i 0) (set (str/split (nth args (inc i)) #","))))]
  (cond
    (some #{"--check"} args) (check!)
    (some #{"--list"} args) (doseq [s steps] (println (format "%-16s %-12s %s" (:id s) (str/join "," (:covers s)) (:why s))))
    :else (do (check!) (run-steps only))))
