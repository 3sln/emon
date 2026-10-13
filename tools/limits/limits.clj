#!/usr/bin/env bb
;; `bb limits`: EVERY BOUND, WHERE IT IS READ, WHAT EXERCISES IT (DEVLOG P10.4, D477; nome's `bb limits`, SPEC 7.4). A REPORT, NOT A GATE.
;;
;;     bb limits                  the report: every limit of kin/limits.kin with its name, environment variable, default, range, what happens at the bound
;;                                (breach / budget / reserved), where kin and the hosts read it, and which scenario or test exercises it; the numeric options
;;                                that bound something; the flags (a limit nothing reads, a limit nothing tests); the inventory's counts
;;     bb limits --inventory      also every bound of the inventory (tools/limits/inventory.edn): class, place, value, reason
;;     bb limits --run [--hosts rust,go,java,csharp] [--only ID,...]
;;                                also run the scenarios (test/limits/scenarios.edn) through each host's driver: NOT a gate either, but a scenario that
;;                                fails is a failure of this command (exit 1). It builds the drivers (cached under .cache/), so it takes a while.
;;
;; The table is READ from kin/limits.kin (tools/limits/table.clj), never typed here; "where it is read" is a search of kin/*.kin and host/ for
;; `(limit-of K)`, `(limit-breach K` and the hosts' own spellings; "tested" is a scenario (--run proves it still holds) or a named test that exists.
;; A limit is flagged when it is not reserved and nothing reads it (UNWIRED: this is how `total-bytes` and `ocr-requests` were found), when a budget calls
;; `limit-breach` or a breach is only ever read as a budget, when a reserved limit is read, and when nothing tests it (UNTESTED).
(ns limits.main
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [fuzz.engine :as en]
            [fuzz.seeds :as seeds]
            [limits.table :as lt]))

(def args (vec *command-line-args*))
(def run? (some #{"--run"} args))
(def inventory? (some #{"--inventory"} args))
(defn opt [name] (some->> args (drop-while #(not= % name)) second))
(def only (some-> (opt "--only") (str/split #",") set))
(def want-hosts (some-> (opt "--hosts") (str/split #",") vec))

(defn line [& xs] (println (apply str xs)))

;; ------------------------------------------------------------------------------------------ where a limit is read

(def kin-files (vec (remove #(re-find #"test\.kin$|/limits\.kin$" %) (map str (fs/glob "kin" "*.kin")))))

(defn grep-sites
  "[\"file:line\" ...] of the lines of `files` matching the pattern."
  [files re]
  (vec (for [f files
             [i l] (map-indexed vector (str/split-lines (slurp f)))
             :when (and (re-find re l) (not (re-find #"^\s*;;" l)))]
        (str (str/replace f #"^\./" "") ":" (inc i)))))

(def host-files
  ;; sources only: not a dependency tree (host/js/node_modules, a directory named like a file), not a build directory
  (vec (remove #(or (re-find #"/(node_modules|target|bin|obj)/" %) (fs/directory? %))
               (concat (map str (fs/glob "host" "**/*.{rs,go,java,cs}")) (map str (fs/glob "host" "**/*.js"))))))

(defn host-library-files [] (remove #(re-find #"(?i)test|/tests/|testport" %) host-files))

(defn kin-sites [k]
  {:reads (grep-sites kin-files (re-pattern (str "\\(limit-of " k "\\)")))
   :breaches (grep-sites kin-files (re-pattern (str "\\(limit-breach " k "[ )]")))})

(defn host-sites [k]
  (grep-sites (host-library-files)
              (re-pattern (str "(?:limit_of|limit_breach|LimitOf|LimitBreach|limitOf|limitBreach)\\(" k "[,)]"))))

(def loop-sites
  "limits the plan loop reads by the loop's own error (not a `limit-of` of the name's kind)."
  {"ocr-requests" (grep-sites ["kin/planloop.kin"] #"\(pe-ocr-requests\)")
   "stage-ms" (grep-sites ["kin/planloop.kin"] #"\(pe-stage-ms\)")
   "max-rounds" (grep-sites ["kin/planloop.kin"] #"\(pe-max-rounds\)")
   "fetch-rounds" (grep-sites ["kin/planloop.kin"] #"\(pe-fetch-rounds\)")})

;; ------------------------------------------------------------------------------------------ what exercises a limit

(def scenarios (edn/read-string (slurp "test/limits/scenarios.edn")))

(def tests
  "Tests that are not scenarios and that exercise a limit, each held to exist: [limit file pattern]."
  [["input-bytes" "host/rust/tests/engine.rs" "the_input_bytes_limit_is_checked_against_the_length_before_a_byte_is_read"]
   ["input-bytes" "host/go/engine_test.go" "TestTheInputBytesLimitIsCheckedAgainstTheSizeBeforeAByteIsRead"]
   ["input-bytes" "test/driver/wasm/check.mjs" "input-bytes limit is checked against the length before a byte is read"]
   ["pages" "host/rust/tests/engine.rs" "limits: vec!\\[\\(8, 1\\)\\]"]
   ["pages" "host/go/engine_test.go" "\\{\\{8, 1\\}\\}"]
   ["pixels" "host/rust/tests/engine.rs" "limits: vec!\\[\\(9, 1000\\)\\]"]
   ["pixels" "host/go/engine_test.go" "\\{9, 1000\\}"]
   ["objects" "host/rust/src/engine.rs" "a limit breach in the document unit is not a named limit error"]
   ["objects" "host/go/engine_test.go" "a limit breach in the document unit is"]
   ["objects" "kin/pdflextest.kin" "test-a-limit-on-objects-is-a-breach-not-a-status"]
   ["stream-bytes" "tools/fuzz/seeds.clj" "bomb-flate-100m"]
   ["stream-bytes" "tools/fuzz/engine.clj" "EMON_LIMIT_STREAM_BYTES"]
   ["ocr-requests" "kin/planlooptest.kin" "test-ocr-requests-is-a-named-limit-of-the-loop"]
   ["stage-ms" "kin/planlooptest.kin" "test-a-stages-wall-clock-is-kins-to-judge-and-0-is-off"]
   ["stage-ms" "kin/planlooptest.kin" "test-with-stage-ms-off-the-loop-ignores-the-clock"]
   ["fonts-per-doc" "kin/configtest.kin" "test-fonts-per-doc-cannot-be-raised-past-its-slab"]
   ;; `workers` (P10.2) is a budget on how the work is spread: kin's clamp, and each host holding that 1 is serial whatever `parallel` says
   ["workers" "kin/planlooptest.kin" "test-the-workers-are-the-option-served-within-the-limit-and-at-least-one"]
   ["workers" "host/rust/tests/engine.rs" "limits: vec!\\[\\(16, 1\\)\\]"]
   ["workers" "host/go/par_test.go" "\\{\\{16, 1\\}\\}"]
   ["workers" "test/driver/java/EngineTest.java" "\\{\\{16, 1\\}\\}"]
   ["workers" "test/driver/csharp/EngineTest.cs" "new\\[\\] \\{ 16, 1 \\}"]
   ["chunk" "kin/scripts/identity" "--chunk=512"]
   ["asset-dpi" "kin/scripts/asset-golden" "asset-dpi"]
   ["max-rounds" "kin/planlooptest.kin" "test-max-rounds-is-a-named-limit"]
   ["fetch-rounds" "kin/planlooptest.kin" "test-a-miss-is-not-a-round-and-fetch-rounds-is-a-named-limit"]
   ["form-depth" "tools/fuzz/seeds.clj" "deep-forms-300"]
   ["ref-depth" "tools/fuzz/seeds.clj" "cycle-length-chain"]])

(defn test-exists? [[_ file pat]]
  (and (fs/exists? file) (boolean (re-find (re-pattern pat) (slurp file)))))

(defn evidence [name]
  {:scenarios (vec (for [s scenarios :when (= (:limit s) name)] (:id s)))
   :tests (vec (for [[l f pat :as t] tests :when (= l name)] {:file f :pat pat :ok (test-exists? t)}))})

;; ------------------------------------------------------------------------------------------ the report

(def flags (atom []))
(defn flag! [& xs] (swap! flags conj (apply str xs)))

(defn range-str [{:keys [lo hi]}] (str lo ".." hi))

(defn report-limit [l]
  (let [{:keys [kind name env default on-hit-word on-hit]} l
        {:keys [reads breaches]} (kin-sites kind)
        hosts (host-sites kind)
        loops (get loop-sites name)
        ev (evidence name)
        tested? (or (seq (:scenarios ev)) (some :ok (:tests ev)))]
    (line (format "  %2d  %-17s %-29s default %-11s range %s" kind name env (str default) (range-str l)))
    (line "        at the bound: " on-hit-word
          (case on-hit 0 " (limit-breach: a typed payload on every host, the conversion's named error)"
                1 " (the work stops there and the conversion goes on)"
                2 " (named and ranged; nothing reads it yet)" ""))
    (line "        read by kin:  " (if (seq (concat reads loops)) (str/join ", " (concat reads loops)) "-")
          (when (seq breaches) (str "   breach: " (str/join ", " breaches))))
    (line "        read by host: " (if (seq hosts) (str/join ", " hosts) "-"))
    (line "        exercised by: "
          (if (seq (:scenarios ev)) (str "scenario " (str/join ", " (:scenarios ev))) "")
          (when (and (seq (:scenarios ev)) (seq (:tests ev))) "; ")
          (when (seq (:tests ev)) (str/join "; " (for [t (:tests ev)] (str (:file t) " `" (subs (:pat t) 0 (min 60 (count (:pat t)))) "`" (when-not (:ok t) " MISSING")))))
          (when-not (or (seq (:scenarios ev)) (seq (:tests ev))) "-"))
    (when (and (not= on-hit 2) (empty? (concat reads loops hosts)))
      (flag! "UNWIRED " name ": nothing reads kind " kind " (no `(limit-of " kind ")` in kin/*.kin, no limit_of(" kind ") in host/)"))
    (when (and (= on-hit 2) (seq (concat reads loops)))
      (flag! "RESERVED BUT READ " name ": `on-hit` is 2 and kin reads it"))
    (when (and (not= on-hit 0) (seq breaches))
      (flag! "BUDGET BREACHES " name ": `on-hit` is " on-hit-word " and kin calls limit-breach " kind " at " (str/join ", " breaches)))
    (when (and (= on-hit 0) (seq reads) (empty? breaches) (empty? loops) (empty? hosts))
      (flag! "BREACH NEVER RAISED " name ": `on-hit` is breach, kin reads it and nothing calls limit-breach " kind " (or a host raises it)"))
    (doseq [t (:tests ev) :when (not (:ok t))]
      (flag! "TEST MISSING " name ": " (:file t) " no longer holds `" (:pat t) "`"))
    (when (and (not= on-hit 2) (not tested?))
      (flag! "UNTESTED " name ": no scenario in test/limits/scenarios.edn and no test in tools/limits/limits.clj names it"))
    (when (and (= on-hit 2) (not tested?))
      (line "        (reserved: no test is wanted until something reads it)"))))

(def loop-limits
  [{:name "max-rounds" :where "kin/planloop.kin pl-requests"} {:name "fetch-rounds" :where "kin/planloop.kin pl-after"}])

(defn report-option [o]
  (let [ev (evidence (:name o))
        field {"asset-dpi" "dpi" "asset-pixels" "pixels" "asset-bytes" "abyte" "assets-bytes" "tbyte"}
        site (case (:name o)
               "max-rounds" (first (loop-sites "max-rounds"))
               "fetch-rounds" (first (loop-sites "fetch-rounds"))
               "chunk" "the hosts' source cache (Config.chunk_size)"
               (first (grep-sites (remove #{"kin/config.kin" "kin/enctx.kin"} kin-files) (re-pattern (str "\\(fd c " (field (:name o)) "\\)")))))
        tested? (or (seq (:scenarios ev)) (some :ok (:tests ev)))]
    (line (format "      %-14s default %-10s range %s..%s   read at %s   %s"
                  (:name o) (str (:default o)) (:lo o) (:hi o) (or site "-")
                  (cond (seq (:scenarios ev)) (str "scenario " (str/join ", " (:scenarios ev)))
                        (some :ok (:tests ev)) (str "test " (:file (first (:tests ev))))
                        :else "UNTESTED")))
    (when-not site (flag! "UNWIRED option " (:name o) ": kin reads no (fd c " (field (:name o)) ")"))
    (when-not tested? (flag! "UNTESTED option " (:name o)))))

(defn print-inventory [inv limits options]
  (let [by (group-by :class inv)
        a (+ (count limits) (count options))
        b (filter #(and (= (:class %) :b)) inv)
        wired (filter :wired b)]
    (line "")
    (line "INVENTORY (tools/limits/inventory.edn; the table and the options are (a), read from their sources)")
    (line (format "  (a) already named: %d  = %d limits of kin/limits.kin + %d numeric options of kin/config.kin" a (count limits) (count options)))
    (line (format "  (b) became a limit or began to be read: %d  (%d new rows, %d read for the first time, %d range corrected, %d copies of a default now read from the table)"
                  (count b) (count (remove #(or (:wired %) (:copy %) (= (:limit %) "form-depth")) b)) (count wired) (count (filter #(= (:limit %) "form-depth") b))
                  (count (filter :copy b))))
    (line (format "  (b-open) should be one and is not yet: %d" (count (by :b-open))))
    (line (format "  (c) stays fixed: %d   %s" (count (by :c))
                  (str/join ", " (for [[k v] (sort-by (comp str key) (group-by :why (by :c)))] (str (name k) " " (count v))))))
    (when inventory?
      (doseq [cls [:b :b-open :c]]
        (line "")
        (line "  " (name cls))
        (doseq [e (by cls)]
          (line (format "    %-8s %s  [%s]" (if-let [w (:why e)] (name w) "") (:where e) (:value e)))
          (line "             guards: " (:guards e))
          (line "             " (:reason e)))))))

;; ------------------------------------------------------------------------------------------ the scenarios

(defn materialise
  "A scenario's input as a file path: the file itself, or a seed written under `dir`."
  [input dir all-seeds]
  (if (str/starts-with? input "seed:")
    (let [s (or (first (filter #(= (:name %) input) all-seeds)) (throw (ex-info (str "no seed " input) {})))
          f (str dir "/" (str/replace (:name s) #"[:/]" "_"))]
      (when-not (fs/exists? f) (fs/write-bytes f (let [b (:bytes s)] (if (delay? b) @b b))))
      f)
    input))

(defn driver-for [h]
  (case h
    "rust" ["kin/scripts/emon-rs-build"] "go" ["kin/scripts/emon-go-build"]
    "java" ["kin/scripts/emon-java-build"] "csharp" ["kin/scripts/emon-cs-build"]))

(defn build-driver
  "[cmd...] of the host's driver, or nil (with the reason printed) when its toolchain is not here."
  [h]
  (let [r (apply p/shell {:out :string :err :string :continue true} "nice" "-n" "10" (driver-for h))]
    (case (:exit r)
      0 [(str/trim (:out r))]
      3 (do (line "  SKIP " h " -- " (str/trim (:err r))) nil)
      (do (line "  FAIL the " h " driver did not build:\n" (:err r)) nil))))

(defn answer-of [run] (en/answer run))

(defn run-scenarios! []
  (let [hosts (or want-hosts ["rust" "go" "java" "csharp"])
        drivers (into {} (for [h hosts :let [d (build-driver h)] :when d] [h d]))
        dir (str (fs/create-temp-dir {:prefix "limits-"}))
        all-seeds (seeds/seeds)
        fails (atom 0)
        table (into {} (map (juxt :name identity)) (lt/read-limits))]
    (line "")
    (line "SCENARIOS on " (str/join ", " (keys drivers)) " (test/limits/scenarios.edn)")
    (doseq [s scenarios :when (or (nil? only) (only (:id s)))]
      (let [file (materialise (:input s) dir all-seeds)
            run1 (fn [& extra]
                   (into {} (for [[h cmd] drivers]
                              [h (answer-of (en/run-driver cmd (vec (concat ["--out=bundle"] (:flags s) extra)) file 120000 {}))])))
            base (run1)
            low (run1 (:set s))
            dflt (run1 (:default s))
            same? #(= 1 (count (distinct (vals %))))
            problems (cond-> []
                       (not (same? base)) (conj (str "the hosts disagree with no override: " (pr-str base)))
                       (not= :ok (first (first (vals base)))) (conj (str "the input with no override is not ok: " (pr-str (first (vals base)))))
                       (not (same? low)) (conj (str "the hosts disagree with " (:set s) ": " (pr-str low)))
                       (not (same? dflt)) (conj (str "the hosts disagree with " (:default s) ": " (pr-str dflt)))
                       (not= (first (vals base)) (first (vals dflt))) (conj (str (:default s) " (the default said aloud) is not the unset run: " (pr-str (first (vals dflt)))))
                       (and (vector? (:expect s)) (not= (first (vals low)) (:expect s)))
                       (conj (str (:set s) " gave " (pr-str (first (vals low))) ", wanted " (pr-str (:expect s))))
                       (and (= :differs (:expect s)) (or (= (first (vals low)) (first (vals base))) (not= :ok (first (first (vals low))))))
                       (conj (str (:set s) " did not change the bundle: " (pr-str (first (vals low)))))
                       ;; a bound on HOW the work is spread (`workers`): the answer must be the same with it set low; that it is read is held by the host tests
                       (and (= :same (:expect s)) (not= (first (vals low)) (first (vals base))))
                       (conj (str (:set s) " changed the bundle, which a bound on how the work is spread must not: " (pr-str (first (vals low)))))
                       (and (vector? (:expect s)) (= :err (first (:expect s)))
                            (not (or (table (second (:expect s))) (#{"max-rounds" "fetch-rounds" "no-root"} (second (:expect s))))))
                       (conj (str "the error name " (second (:expect s)) " is not a name in kin/limits.kin")))]
        (if (empty? problems)
          (line (format "  ok   %-17s %s -> %s  (%d hosts agree; default and unset identical)" (:id s) (:set s) (pr-str (first (vals low))) (count drivers)))
          (do (swap! fails inc)
              (line (format "  FAIL %-17s %s" (:id s) (:set s)))
              (doseq [pr problems] (line "         " pr))))))
    (fs/delete-tree dir)
    @fails))

;; ------------------------------------------------------------------------------------------ main

(let [limits (lt/read-limits)
      options (lt/read-options)
      inv (edn/read-string (slurp "tools/limits/inventory.edn"))]
  (line "bb limits -- every bound, where it is read, what exercises it (P10.4, D477; SPEC 7.4). A report, not a gate.")
  (line "")
  (line "LIMITS (kin/limits.kin: " (count limits) " rows; `limit.NAME=N` in an options text, `EMON_LIMIT_NAME` in the environment, a host's Config.limits by kind)")
  (doseq [l limits] (report-limit l))
  (line "")
  (line "OPTIONS THAT BOUND SOMETHING (kin/config.kin; `NAME=N`, the driver's `--NAME=N`, the SDK's option)")
  (doseq [o options] (report-option o))
  (print-inventory inv limits options)
  (line "")
  (let [n-untested (count (filter #(str/starts-with? % "UNTESTED") @flags))]
    (line "FLAGS (" (count @flags) ")")
    (if (empty? @flags) (line "  none: every limit that is read has a scenario or a test, and nothing is named that nothing reads")
        (doseq [f @flags] (line "  " f)))
    (line "")
    (line (format "%d limits: %d read, %d reserved, %d untested" (count limits)
                  (count (filter #(and (not= 2 (:on-hit %)) (not (some (fn [f] (str/starts-with? f (str "UNWIRED " (:name %)))) @flags))) limits))
                  (count (filter #(= 2 (:on-hit %)) limits)) n-untested)))
  (when run?
    (let [fails (run-scenarios!)]
      (line "")
      (if (zero? fails)
        (line "  all scenarios hold")
        (do (line "  FAIL " fails " scenario(s)") (System/exit 1))))))
