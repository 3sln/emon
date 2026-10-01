(ns accuracy.fusion-report
  "bb fusion-report: what fusion did, from the evidence dumps (P6.6, D206, SPEC 9.7, 14.2). NOT a gate.

    bb fusion-report DUMP...        a dump file (version \"2\": JSON) or a driver's output (its `evidence: {...}` line)
    bb fusion-report --self-test    the hand-made dump below, with its hand-worked numbers

  Per document and pooled it prints
    * per-source WIN RATES: over the groups that had BOTH a text-layer candidate and an OCR candidate (a `cg`
      row with both runs non-empty), the share each source won, by the `fv` row's winning source;
    * per-RULE counts: the `fv` rows that each `fr` number decided, and the `fx` rows (the columns a rule read);
    * per-DEFENCE ledger counts and per-reason counts of the artifacts set aside (`fa`).
  These are DESCRIPTIONS, not qualities: a better fusion can lower the text layer's win rate legitimately, so
  they are recorded in DEVLOG entries and never ratcheted (D206; the scorer's `ghost-rate`, `seal-leak`,
  `src-ocr-share` and `tl-override` are what is gated).

  THE DUMP'S FIELD NAMES are the arena columns of SPEC 9.7, as the dump (version \"2\") writes every arena: an array
  of objects under the arena's name. Read here: `cg` rows (`text`, `ocr`: a candidate run, given as an array or a
  count), `fv` rows (`group`, `src`: a source name `text`, `hidden-text`, `ocr` or `trace`, `rule`), `fx` rows
  (`group`, `rule`), `fa` rows (`defence` 1-6 or 0, `reason`). P7.1 and P7.3 own the dump; when a name there differs
  from these, this one file changes (and its self-test, which is the format's only witness until fusion exists)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- present? [v] (cond (nil? v) false (number? v) (pos? v) (sequential? v) (boolean (seq v)) :else (boolean v)))

(defn parse-dump
  "A dump's text -> the map. A driver's output carries it on a line `evidence: {...}`."
  [text]
  (if-let [l (first (filter #(str/starts-with? % "evidence: ") (str/split-lines text)))]
    (json/parse-string (subs l (count "evidence: ")))
    (json/parse-string text)))

(defn analyse
  "One dump -> {:both n :wins {src n} :rules {rule n} :reads {rule n} :defences {d n} :reasons {r n}}."
  [dump]
  (let [both (set (for [[g row] (map-indexed vector (get dump "cg"))
                        :when (and (present? (get row "text")) (present? (get row "ocr")))]
                   g))
        fv (filter #(contains? both (get % "group")) (get dump "fv"))]
    {:both (count both)
     :decided (count fv)
     :wins (frequencies (map #(get % "src") fv))
     :rules (frequencies (map #(get % "rule") (get dump "fv")))
     :reads (frequencies (map #(get % "rule") (get dump "fx")))
     :defences (frequencies (map #(get % "defence" 0) (get dump "fa")))
     :reasons (frequencies (map #(get % "reason") (get dump "fa")))}))

(defn pool [analyses]
  (let [add (fn [k] (apply merge-with + {} (map k analyses)))]
    {:both (reduce + (map :both analyses))
     :decided (reduce + (map :decided analyses))
     :wins (add :wins) :rules (add :rules) :reads (add :reads)
     :defences (add :defences) :reasons (add :reasons)}))

(defn win-rates
  "{src rate} over the decided rows of groups with both candidates (nil when there are none)."
  [a]
  (when (pos? (:decided a))
    (into (sorted-map) (for [[s n] (:wins a)] [s (/ (double n) (:decided a))]))))

(defn- show-map [title m]
  (when (seq m)
    (println (str "    " title ": "
                  (str/join "  " (for [[k v] (sort-by (comp str key) m)] (str k "=" v)))))))

(defn print-analysis [name a]
  (println (str "  " name ": " (:both a) " group(s) with both candidates, " (:decided a) " decided"))
  (when-let [w (win-rates a)]
    (println (str "    win rates: " (str/join "  " (for [[s r] w] (format "%s %.3f" s r))))))
  (show-map "rules (fv)" (:rules a))
  (show-map "rule reads (fx)" (:reads a))
  (show-map "defences (fa)" (:defences a))
  (show-map "reasons (fa)" (:reasons a)))

;; ------------------------------------------------------------- self-test ----
;; Four groups. Group 0: text + OCR, text won (rule 12). Group 1: text + OCR, OCR won (rule 31, flagged
;; suspect). Group 2: text + OCR, OCR won (rule 31). Group 3: OCR only, OCR won (not counted: no text candidate).
;; Win rates over the 3 groups with both: text 1/3, ocr 2/3. Rules: 12 once, 31 three times. fx: rule 31 read
;; two columns for group 1 and one for group 2. fa: two artifacts, defence 1 (watermark) and defence 4 (watermark),
;; and one seal (defence 5).
(def SELF-DUMP
  {"emon-evidence" "2"
   "cg" [{"text" [0] "ocr" [0]} {"text" [1] "ocr" [1]} {"text" 1 "ocr" 1} {"text" [] "ocr" [2]}]
   "fv" [{"group" 0 "src" "text" "rule" 12} {"group" 1 "src" "ocr" "rule" 31}
         {"group" 2 "src" "ocr" "rule" 31} {"group" 3 "src" "ocr" "rule" 31}]
   "fx" [{"group" 1 "rule" 31 "feature" 4 "value" 100} {"group" 1 "rule" 31 "feature" 7 "value" 9}
         {"group" 2 "rule" 31 "feature" 4 "value" 80}]
   "fa" [{"defence" 1 "reason" "watermark"} {"defence" 4 "reason" "watermark"} {"defence" 5 "reason" "seal"}]})

(defn self-test []
  (let [a (analyse SELF-DUMP)
        p (pool [a a])
        ok (and (= 3 (:both a)) (= 3 (:decided a))
                (= {"text" 1 "ocr" 2} (:wins a))
                (= {12 1 31 3} (:rules a)) (= {31 3} (:reads a))
                (= {1 1 4 1 5 1} (:defences a)) (= {"watermark" 2 "seal" 1} (:reasons a))
                (= {"ocr" (/ 2.0 3) "text" (/ 1.0 3)} (into {} (win-rates a)))
                (= 6 (:both p)) (= {"text" 2 "ocr" 4} (:wins p))
                (= (:wins a) (:wins (analyse (parse-dump (str "log line\nevidence: " (json/generate-string SELF-DUMP)))))))]
    (if ok
      (println "  ok   fusion-report self-test: win rates, rule and defence counts of the hand-made dump, pooled")
      (do (println "  FAIL fusion-report self-test") (System/exit 1)))))

(defn -main [& args]
  (cond
    (= args ["--self-test"]) (self-test)
    (empty? args) (do (println "usage: bb fusion-report DUMP... | --self-test") (System/exit 2))
    :else
    (let [as (for [f args :let [a (analyse (parse-dump (slurp f)))]]
               (do (print-analysis f a) a))]
      (when (> (count as) 1) (print-analysis "pooled" (pool as))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
