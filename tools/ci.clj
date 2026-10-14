#!/usr/bin/env bb
;; CI helpers: the gate map, the PR plan, the fast core, and the checks that hold them (D266; P11.7, D528). NOT a gate.
;;   bb ci coverage                       every gate is in a shard of ci/gate-map.edn and a workflow job runs the shards, every bb task a workflow calls exists, the gate map covers the tree (map-coverage), and every replay gate has a re-derivation (`bb rederive --check`, D488)
;;   bb ci map-coverage [--self-test]     ci/gate-map.edn: every tracked file (git ls-files) matches an entry, every gate it names exists, no entry is dead; --self-test plants each failure
;;   bb ci plan --base REF [--head REF] [--format edn|json] [--files A,B] [--all]   the gates a change calls for (git diff --name-only BASE...HEAD, mapped); fails on a path no entry maps.
;;                                        A diff that only changes version strings (version.edn and the manifests of `bb version`, D550) plans nothing. --all: every gate the fast core does not run
;;   bb ci shards [--format edn|json]     the shards of the full run (every gate), as the matrix shape verify.yml consumes
;;   bb ci verify-plan --head SHA [--mode scoped|full] [--limit N] [--repo R] [--format edn|json]
;;                                        what verify.yml runs on main (D550): finds the base (the newest first-parent ancestor with a green `emon/verified` status, via gh api; none -> everything),
;;                                        plans the diff since it, and says whether the release archives must be rebuilt or the base's are inherited
;;   bb ci core                           the fast core, one command after another, each timed; fails if any failed
;;   bb ci laws-record ARM LOG DIR        write DIR/laws-ARM.txt: the law count `bb unit ARM` printed in LOG
;;   bb ci laws-compare DIR               every arm's count in DIR/**/laws-*.txt is the same, and all four arms are there
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell process]]
         '[cheshire.core :as json]
         '[clj-yaml.core :as yaml]
         '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[release.lib :as rl])

(def map-file "ci/gate-map.edn")
(def wf-dir (str ".git" "hub/workflows"))
(def wf (str wf-dir "/verify.yml"))
(def arms #{"go" "rust" "java" "csharp"})

(defn die [& m] (println "  FAIL" (apply str m)) (System/exit 1))

(defn script-text [] (slurp "kin/scripts/gates"))
(defn script-list [var]
  (when-let [s (second (re-find (re-pattern (str "(?m)^" var "=\"([^\"]*)\"")) (script-text)))]
    (vec (remove str/blank? (str/split s #"\s+")))))
(defn script-gates [] (set (script-list "gates")))
(defn script-fast-gates [] (set (script-list "fast_gates")))

(defn bb-tasks []
  (set (map second (re-seq #"(?m)^[ {]*([a-z][a-z-]*) *\{:doc" (slurp "bb.edn")))))

;; ------------------------------------------------------------------------------------------------------------------ globs
(defn glob->regex
  "`**/` any directories (or none), `**` anything, `*` within a name, `?` one character, `{a,b}` either. Matched against a whole git path."
  [g]
  (let [n (count g)]
    (loop [i 0, sb (StringBuilder. "^"), brace false]
      (if (>= i n)
        (re-pattern (str (.append sb "$")))
        (let [c (.charAt g i)
              nxt (when (< (inc i) n) (.charAt g (inc i)))]
          (cond
            (and (= c \*) (= nxt \*))
            (if (and (< (+ i 2) n) (= (.charAt g (+ i 2)) \/))
              (recur (+ i 3) (.append sb "(?:.*/)?") brace)
              (recur (+ i 2) (.append sb ".*") brace))
            (= c \*) (recur (inc i) (.append sb "[^/]*") brace)
            (= c \?) (recur (inc i) (.append sb "[^/]") brace)
            (= c \{) (recur (inc i) (.append sb "(?:") true)
            (and brace (= c \})) (recur (inc i) (.append sb ")") false)
            (and brace (= c \,)) (recur (inc i) (.append sb "|") brace)
            :else (recur (inc i) (.append sb (java.util.regex.Pattern/quote (str c))) brace)))))))

(defn expand-gates
  "Gate names of an entry's :gates, a `@group` replaced by the group's gates."
  [groups gs]
  (vec (distinct (mapcat (fn [g] (if (str/starts-with? g "@") (get groups (subs g 1) [g]) [g])) gs))))

(defn compile-map [m]
  (mapv (fn [e] (assoc e :rx (glob->regex (:glob e)))) (:map m)))

(defn matches [compiled path] (filter #(re-matches (:rx %) path) compiled))

;; ------------------------------------------------------------------------------------------------------------------ the map's own checks
(defn map-problems
  "-> a vector of strings. Pure: the map (as read), the tracked files, the gates, the fast gates and the bb tasks are arguments."
  [{:keys [m files gates fast-gates tasks version-files] :as args}]
  (let [groups (:groups m)
        entries (:map m)
        compiled (compile-map m)
        shard-gates (mapcat :gates (:shards m))]
    (vec
     (concat
      (for [e entries :when (or (not (string? (:glob e))) (str/blank? (:glob e)))] (str "an entry has no :glob: " (pr-str e)))
      (for [e entries :when (not (vector? (:gates e)))] (str "entry " (pr-str (:glob e)) " has no :gates vector (use [] for a path that needs none)"))
      (for [e entries :when (str/blank? (:why e))] (str "entry " (pr-str (:glob e)) " has no :why"))
      (for [[g n] (frequencies (map :glob entries)) :when (> n 1)] (str "glob " (pr-str g) " is listed " n " times"))
      (for [e entries, g (:gates e)
            :when (if (str/starts-with? g "@") (not (contains? groups (subs g 1))) (not (contains? gates g)))]
        (str "entry " (pr-str (:glob e)) " names " (if (str/starts-with? g "@") "the group" "the gate") " `" g "`, which does not exist"))
      (for [e entries, h (:hosts e) :when (not (contains? #{"go" "java" "csharp" "c" "pod" "wasm"} h))]
        (str "entry " (pr-str (:glob e)) " names host `" h "`: want go java csharp c pod wasm"))
      (for [[gname gs] groups, g gs :when (not (contains? gates g))] (str "group `" gname "` names `" g "`, which is not a gate (kin/scripts/gates)"))
      (for [g (sort gates) :when (not (some #{g} shard-gates))] (str "gate `" g "` is in no shard of " map-file))
      (for [[g n] (frequencies shard-gates) :when (> n 1)] (str "gate `" g "` is in " n " shards"))
      (for [g (distinct shard-gates) :when (not (contains? gates g))] (str "a shard names `" g "`, which is not a gate (kin/scripts/gates)"))
      (for [g (sort (keys (:fast m))) :when (not (contains? gates g))] (str ":fast names `" g "`, which is not a gate"))
      (let [declared (set (keys (:fast m)))]
        (concat (for [g (sort (set/difference fast-gates declared))] (str "kin/scripts/gates lists `" g "` in fast_gates; " map-file " :fast has no entry for it"))
                (for [g (sort (set/difference declared fast-gates))] (str map-file " :fast has `" g "`; kin/scripts/gates does not list it in fast_gates"))))
      (for [c (:core m) :let [t (second (:cmd c))]
            :when (and (= "bb" (first (:cmd c))) (not (str/includes? (str t) "/")) (not (contains? tasks t)))]
        (str "core step `" (:id c) "` runs bb " t ", which is not a bb.edn task"))
      (for [c (:core m) :when (and (:gate c) (not (contains? gates (:gate c))))] (str "core step `" (:id c) "` claims gate `" (:gate c) "`, which does not exist"))
      ;; D550: the globs of the files the release archives are built from (a change to one rebuilds them), and the manifests whose version-only diff plans nothing
      (for [g (:archives m) :when (not-any? #(re-matches (glob->regex g) %) files)] (str ":archives glob " (pr-str g) " matches no tracked file (dead: remove it, or fix the glob)"))
      (when (contains? args :version-files)
        (let [declared (set (:version-files m))]
          (concat (for [f (sort (set/difference version-files declared))] (str "tools/version.clj rewrites " f "; " map-file " :version-files does not list it"))
                  (for [f (sort (set/difference declared version-files))] (str map-file " :version-files lists " f ", which tools/version.clj does not rewrite")))))
      (for [f (sort files) :when (empty? (matches compiled f))] (str "no entry maps " f))
      (for [e compiled :when (and (string? (:glob e)) (not-any? #(re-matches (:rx e) %) files))]
        (str "entry " (pr-str (:glob e)) " matches no tracked file (dead: remove it, or fix the glob)"))))))

(defn tracked-files []
  (let [r (shell {:out :string} "git" "ls-files")]
    (vec (remove str/blank? (str/split-lines (:out r))))))

(defn read-map [] (edn/read-string (slurp map-file)))

;; ------------------------------------------------------------------------------------------------------------------ version-only diffs (D550)
(defn version-targets
  "The manifests `bb version` rewrites, [{:file :re ..}], read from tools/version.clj itself (loaded into a namespace of its own: its `-main` does not run), plus version.edn."
  []
  (let [ns (create-ns 'emon.versiontool)]
    (binding [*ns* ns] (refer-clojure) (load-file "tools/version.clj"))
    (into [{:file "version.edn" :re #"(\{:version \")([^\"]+)(\"\})"}] @(ns-resolve ns 'targets))))

(defn mask-version
  "The text with the version (group 2 of the target's pattern) replaced by V everywhere the pattern matches."
  [{:keys [re]} text]
  (str/replace text re (fn [[_ a _ c]] (str a "V" c))))

(defn version-only?
  "Do `before` and `after` of one manifest differ ONLY in the version strings the target's pattern finds (its own context: `name = \"emon\"` then `version = `, not any
   dependency's version)? A file that is new or gone is not."
  [target before after]
  (boolean (and target (string? before) (string? after) (not= before after)
                (= (mask-version target before) (mask-version target after)))))

(defn version-only-paths
  "Pure: the changed paths whose diff is a version-only change. blobs: path -> [before after] (nil for a missing side)."
  [m targets paths blobs]
  (let [by-file (into {} (map (juxt :file identity)) targets)
        listed (set (:version-files m))]
    (set (for [p paths :when (and (listed p) (by-file p)) :let [[b a] (blobs p)] :when (version-only? (by-file p) b a)] p))))

(defn git-blobs
  "A blobs function over git: the file at the merge base of `base` and `head`, and at `head`."
  [base head]
  (let [mb (let [r (shell {:out :string :err :string :continue true} "git" "merge-base" base head)] (if (zero? (:exit r)) (str/trim (:out r)) base))
        show (fn [ref p] (let [r (shell {:out :string :err :string :continue true} "git" "show" (str ref ":" p))] (when (zero? (:exit r)) (:out r))))]
    (fn [p] [(show mb p) (show head p)])))

(defn map-coverage []
  (let [m (read-map)
        files (tracked-files)
        ps (map-problems {:m m :files files :gates (script-gates) :fast-gates (script-fast-gates) :tasks (bb-tasks)
                          :version-files (set (map :file (version-targets)))})]
    (doseq [p (take 40 ps)] (println "  FAIL" p))
    (when (> (count ps) 40) (println "  FAIL ... and" (- (count ps) 40) "more"))
    (when (seq ps) (System/exit 1))
    (println "  ok   gate map:" (count files) "tracked files, each matched by an entry of" map-file
             "(" (count (:map m)) "entries, none dead);" (count (script-gates)) "gates in" (count (:shards m)) "shards,"
             (count (:fast m)) "with a --fast variant")))

;; ------------------------------------------------------------------------------------------------------------------ the plan
(defn shard-include
  "The shards of the full run that hold a gate `pred` accepts, in the shape verify.yml consumes: [{:name :gates \"a b\" :corpus bool}]. The own-job shard (unit) is not part of it."
  [m pred]
  (vec (for [s (:shards m) :when (not (:own-job s)) :let [gs (filter pred (:gates s))] :when (seq gs)]
         {:name (:name s) :gates (str/join " " gs) :corpus (boolean (:corpus s))})))

(defn archives-hit?
  "Does any changed path match a glob of :archives (the files the release archives are built from)?"
  [m paths]
  (let [rxs (map glob->regex (:archives m))]
    (boolean (some (fn [p] (some #(re-matches % p) rxs)) paths))))

(defn plan-of
  "Pure: the map, the changed paths -> {:files :unmapped [paths] :gates [..] :hosts [..] :matrix {:include [..]} ...}.
   opts: :version-only (paths whose diff is a version-only change: they plan nothing, D550), :all? (every gate the fast core does not run, whatever changed)."
  ([m paths] (plan-of m paths {}))
  ([m paths {:keys [version-only all?]}]
   (let [version-only (set version-only)
         real (vec (remove version-only paths))
         compiled (compile-map m)
         groups (:groups m)
         per (for [p real] [p (matches compiled p)])
         unmapped (vec (for [[p es] per :when (empty? es)] p))
         core-gates (set (keep :gate (:core m)))
         wanted (if all?
                  (->> (:shards m) (mapcat :gates) (remove core-gates) set)
                  (->> per (mapcat second) (mapcat #(expand-gates groups (:gates %))) distinct (remove core-gates) set))
         hosts (->> per (mapcat second) (mapcat :hosts) distinct sort vec)
         fast? (set (keys (:fast m)))
         include (vec (for [s (:shards m)
                            :let [gs (filter wanted (:gates s))]
                            :when (seq gs)]
                        {:name (:name s)
                         :fast (str/join " " (filter fast? gs))
                         :full (str/join " " (remove fast? gs))}))
         shards (shard-include m wanted)]
     {:files (count paths)
      :unmapped unmapped
      :gates (vec (filter wanted (mapcat :gates (:shards m))))
      :hosts hosts
      :docs-only (boolean (and (seq paths) (empty? wanted)))
      :has-gates (boolean (seq include))
      :matrix {:include include}
      ;; D550, for verify.yml on main: the planned shards in the shape `bb ci shards` has, whether `unit` (its own jobs) is planned, whether any shard reads the corpus
      :shards {:include shards}
      :unit (contains? wanted "unit")
      :corpus (boolean (some :corpus shards))
      :version-only (vec (sort version-only))
      :archives (boolean (or all? (archives-hit? m real)))})))

(defn opt [args k] (some->> (map-indexed vector args) (some (fn [[i a]] (when (= a k) (get (vec args) (inc i)))))))

(defn out-plan [x fmt]
  (if (= fmt "json")
    (println (json/generate-string (update-keys x #(str/replace (name %) "-" "_"))))
    (prn x)))

(defn diff-paths [base head]
  (let [r (shell {:out :string :err :string :continue true} "git" "diff" "--name-only" "--no-renames" (str base "..." head))]
    (when-not (zero? (:exit r)) (die "git diff " base "..." head " failed: " (str/trim (:err r))))
    (vec (remove str/blank? (str/split-lines (:out r))))))

(defn plan [args]
  (let [base (opt args "--base")
        head (or (opt args "--head") "HEAD")
        fmt (or (opt args "--format") "edn")
        files (opt args "--files")
        all? (boolean (some #{"--all"} args))]
    (when-not (or base files all?) (die "usage: bb ci plan --base REF [--head REF] [--format edn|json] | --files A,B | --all"))
    (when-not (#{"edn" "json"} fmt) (die "--format is edn or json"))
    (let [paths (cond
                  files (vec (remove str/blank? (str/split files #",")))
                  base (diff-paths base head)
                  :else [])
          m (read-map)
          vonly (if (and base (not files)) (version-only-paths m (version-targets) paths (git-blobs base head)) #{})
          p (plan-of m paths {:version-only vonly :all? all?})]
      (when (seq (:unmapped p))
        (binding [*out* *err*]
          (doseq [u (:unmapped p)] (println "  FAIL no entry of" map-file "maps" u))
          (println "  FAIL a changed path maps to no gate: add an entry to" map-file "(docs map to :gates [])"))
        (System/exit 1))
      (out-plan (assoc p :base base :head head :fast-core (mapv :id (:core m))) fmt))))

(defn shards [args]
  (let [m (read-map)
        fmt (or (opt args "--format") "json")
        include (shard-include m (constantly true))]
    (out-plan {:matrix {:include include}} fmt)))

;; ------------------------------------------------------------------------------------------------------------------ what verify.yml runs on main (D550)
(defn verify-plan-of
  "Pure. What a run of verify.yml on `head` does.
   in: {:m map, :mode :scoped|:full, :paths changed paths since the base, :version-only set, :base sha|nil, :reason why not scoped (when it is not),
        :head sha, :version head's version, :inherited the base's valid archives status as {:version :run :sha} or nil}.
   The archives are rebuilt when the run is full, when no valid archives come with the base, when the version is not the one they were built for (the archives carry
   it, in their names and in the binaries), and when a changed path is one the archives are built from. Otherwise the base's stay valid and are recorded again."
  [{:keys [m mode paths version-only base reason head version inherited]}]
  (let [full? (or (= mode :full) (nil? base))
        p (plan-of m (if full? [] paths) {:version-only version-only :all? full?})
        inherit? (and (not full?) inherited (= version (:version inherited)))
        build? (or full? (not inherit?) (:archives p))
        n (count (:gates p))
        short (fn [s] (when s (subs s 0 (min 7 (count s)))))]
    {:mode (if full? "full" "scoped")
     :reason (or reason (when full? "a full run was asked for") "")
     :sha head
     :short (short head)
     :base (or base "")
     :base_short (or (short base) "")
     :version version
     :files (:files p)
     :version_only (count (:version-only p))
     :gates (:gates p)
     :n_gates n
     :unit (:unit p)
     :shards (:shards p)
     :has_shards (boolean (seq (get-in p [:shards :include])))
     :corpus (:corpus p)
     :unmapped (:unmapped p)
     :build_archives (boolean build?)
     :archives_run (if (and (not build?) inherit?) (str (:run inherited)) "")
     :archives_from (if (and (not build?) inherit?) (or (:sha inherited) "") "")
     :archives_desc (if (and (not build?) inherit?) (or (:description inherited) "") "")
     :description (if full?
                    (str "verified: all " n " gates, full run")
                    (str "verified: " n " gates since " (short base)))}))

(defn verify-plan [args]
  (let [head (or (opt args "--head") (str/trim (:out (shell {:out :string} "git" "rev-parse" "HEAD"))))
        head (str/trim (:out (shell {:out :string} "git" "rev-parse" head)))
        mode (keyword (or (opt args "--mode") "scoped"))
        limit (parse-long (or (opt args "--limit") "60"))
        repo (or (opt args "--repo") (rl/repo))
        fmt (or (opt args "--format") "json")
        m (read-map)
        version (:version (edn/read-string (:out (shell {:out :string} "git" "show" (str head ":version.edn")))))
        ancestors (vec (rest (str/split-lines (:out (shell {:out :string} "git" "rev-list" "--first-parent" "-n" (str (inc limit)) head)))))
        lookup (memoize (fn [sha] (rl/statuses-of repo sha)))
        found (when (= mode :scoped) (rl/find-verified ancestors lookup))
        base (:sha found)
        reason (cond (= mode :full) nil
                     (:error found) (str "cannot read the commit statuses (" (:error found) "): everything runs")
                     (nil? base) (str "no ancestor within " limit " commits carries a green " rl/status-context ": everything runs")
                     :else nil)
        inherited (when base
                    (let [st (rl/newest rl/archives-context (lookup base))]
                      (when-let [a (rl/valid-archives st version (System/currentTimeMillis))]
                        (assoc a :sha base :description (:description st)))))
        paths (when base (diff-paths base head))
        vonly (when base (version-only-paths m (version-targets) paths (git-blobs base head)))
        out (verify-plan-of {:m m :mode mode :paths paths :version-only vonly :base base :reason reason :head head :version version :inherited inherited})]
    (when (seq (:unmapped out))
      (binding [*out* *err*]
        (doseq [u (:unmapped out)] (println "  FAIL no entry of" map-file "maps" u))
        (println "  FAIL a changed path maps to no gate: add an entry to" map-file))
      (System/exit 1))
    (when (seq (:reason out)) (binding [*out* *err*] (println "  note" (:reason out))))
    (if (= fmt "json") (println (json/generate-string out)) (prn out))))

;; ------------------------------------------------------------------------------------------------------------------ the fast core
(defn core []
  (let [m (read-map)
        version (str/trim (:out (shell {:out :string} "bb" "version")))
        results (doall
                 (for [{:keys [id cmd]} (:core m)]
                   (let [t0 (System/currentTimeMillis)
                         cmd (mapv #(if (= % "@version") version %) cmd)
                         _ (println (str "  ---- " id ": " (str/join " " cmd)))
                         r (apply shell {:continue true} cmd)
                         secs (/ (- (System/currentTimeMillis) t0) 1000.0)]
                     {:id id :ok (zero? (:exit r)) :secs secs})))]
    (println "  fast core:")
    (doseq [{:keys [id ok secs]} results]
      (println (format "    %-5s %-14s %7.1f s" (if ok "ok" "FAIL") id secs)))
    (println (format "    total %.1f s" (reduce + (map :secs results))))
    (when-not (every? :ok results) (die "the fast core: " (str/join " " (map :id (remove :ok results))) " failed"))
    (println "  ok   the fast core:" (count results) "steps")))

;; ------------------------------------------------------------------------------------------------------------------ coverage
(defn coverage []
  (let [m (read-map)
        yml (yaml/parse-string (slurp wf))
        jobs (vals (:jobs yml))
        unit-arms (set (for [j jobs, a (get-in j [:strategy :matrix :arm])] a))
        text (str/replace (slurp wf) #"(?m)^\s*#.*$" "")
        shard-gates (set (mapcat :gates (:shards m)))
        missing (remove shard-gates (script-gates))
        extra (remove (script-gates) shard-gates)
        wf-files (map str (fs/glob wf-dir "*.yml"))
        called (set (for [f wf-files, [_ t] (re-seq #"\bbb ([a-z][a-z-]*)" (str/replace (slurp f) #"(?m)^\s*#.*$" ""))] t))
        unknown (remove (bb-tasks) called)]
    (when (seq missing) (die "gates in no shard of " map-file ": " (str/join " " missing)))
    (when (seq extra) (die "shards run non-gates as gates: " (str/join " " extra)))
    (when-not (contains? shard-gates "unit") (die "the `unit` gate is in no shard"))
    (when-not (= arms unit-arms) (die "verify.yml runs the unit arms " (pr-str (sort unit-arms)) ", want " (pr-str (sort arms))))
    (when-not (str/includes? text "bb ci verify-plan") (die "verify.yml does not take its plan from `bb ci verify-plan`: the shards of " map-file " would not run"))
    (when-not (str/includes? (slurp (str wf-dir "/pr.yml")) "bb ci plan") (die "pr.yml does not call `bb ci plan`"))
    (when (seq unknown) (die "workflows call bb tasks that do not exist: " (str/join " " unknown)))
    (println "  ok   every gate is in a shard of" map-file "(" (count shard-gates) "gates), verify.yml runs the planned shards and the" (count unit-arms)
             "unit arms, and every bb task the" (count wf-files) "workflows call exists")
    (map-coverage)
    ;; D488: and every replay gate has a re-derivation in `bb rederive`
    (let [r (shell {:continue true} "bb" "tools/rederive.clj" "--check")]
      (when-not (zero? (:exit r)) (System/exit 1)))))

;; ------------------------------------------------------------------------------------------------------------------ the self-test
(defn self-test []
  (let [fails (atom 0)
        ok! (fn [what pass?] (if pass? (println "  ok  " what) (do (swap! fails inc) (println "  FAIL" what))))
        gates #{"unit" "slice" "retarget" "gfuse" "hosts"}
        base {:groups {"conv" ["unit" "slice"]}
              :fast {"unit" {} "slice" {}}
              :core [{:id "hosts" :cmd ["bb" "hosts"] :gate "hosts"}]
              :shards [{:name "unit" :gates ["unit"] :own-job true} {:name "replay" :gates ["slice" "retarget" "gfuse" "hosts"]}]
              :map [{:glob "kin/retarget*.kin" :gates ["unit" "retarget"] :why "x"}
                    {:glob "kin/{gfuse,gvec}*.kin" :gates ["@conv" "gfuse"] :hosts ["java"] :why "x"}
                    {:glob "docs/**" :gates [] :why "docs"}
                    {:glob "host/**/*.rs" :gates ["@conv"] :why "x"}]}
        files ["kin/retarget.kin" "kin/retargettest.kin" "kin/gfuse.kin" "kin/gvectest.kin" "docs/a.md" "docs/x/y/z.md" "host/rust/src/a.rs" "host/b.rs"]
        args {:m base :files files :gates gates :fast-gates #{"unit" "slice"} :tasks #{"hosts"}}
        probs (fn [& kvs] (map-problems (merge args (apply hash-map kvs))))
        has? (fn [ps s] (boolean (some #(str/includes? % s) ps)))]
    ;; the globs
    (let [t (fn [g p] (boolean (re-matches (glob->regex g) p)))]
      (ok! "glob: ** crosses directories, * does not" (and (t "a/**" "a/b/c") (t "a/**/c" "a/c") (t "a/**/c" "a/b/d/c") (t "a/*.x" "a/b.x") (not (t "a/*.x" "a/b/c.x"))))
      (ok! "glob: braces, ?, and a dot is a dot" (and (t "k/{a,b}*.kin" "k/bz.kin") (not (t "k/{a,b}*.kin" "k/c.kin")) (t "a?c" "abc") (not (t "a.c" "abc")))))
    (ok! "a consistent map has no problem" (empty? (probs)))
    (ok! "a file no entry maps is named" (has? (probs :files (conj files "new/thing.txt")) "no entry maps new/thing.txt"))
    (ok! "a file outside every glob is named (host/b.rs is under host/**/*.rs, host/c.go is not)" (has? (probs :files (conj files "host/c.go")) "no entry maps host/c.go"))
    ;; and the REAL map and tree: a planted new path in each top-level directory is named, and the tree as it is passes
    (let [m (read-map) real (tracked-files)
          real-args {:m m :gates (script-gates) :fast-gates (script-fast-gates) :tasks (bb-tasks)}]
      (ok! "the real map passes over the real tree" (empty? (map-problems (assoc real-args :files real))))
      (doseq [p ["kin/newmodule.kin" "host/rust/src/x.rs" "tools/newtool/x.clj" "test/newgate/x.edn" "kin/scripts/newgate" "newdir/x" "gen/rust/x.rs"]
              :let [ps (map-problems (assoc real-args :files (conj real p)))
                    mapped-by-glob? (some #(re-matches (glob->regex (:glob %)) p) (:map m))]]
        (ok! (str "the real map: a new " p (if mapped-by-glob? " is mapped by a glob (a directory glob is a claim, D528)" " is named unmapped"))
             (if mapped-by-glob? (empty? ps) (has? ps (str "no entry maps " p))))))
    (ok! "an unknown gate in an entry is named" (has? (probs :m (assoc-in base [:map 0 :gates] ["unit" "nope"])) "`nope`, which does not exist"))
    (ok! "an unknown group is named" (has? (probs :m (assoc-in base [:map 1 :gates] ["@nogroup"])) "the group `@nogroup`"))
    (ok! "an unknown host is named" (has? (probs :m (assoc-in base [:map 1 :hosts] ["fortran"])) "host `fortran`"))
    (ok! "a dead entry is named" (has? (probs :files (vec (remove #(str/starts-with? % "docs/") files))) "matches no tracked file"))
    (ok! "an entry with no :why is named" (has? (probs :m (assoc-in base [:map 2 :why] "")) "has no :why"))
    (ok! "a gate in no shard is named" (has? (probs :gates (conj gates "fuzz")) "gate `fuzz` is in no shard"))
    (ok! "a gate in two shards is named" (has? (probs :m (update-in base [:shards 1 :gates] conj "unit")) "`unit` is in 2 shards"))
    (ok! "a fast gate the script lacks is named" (has? (probs :fast-gates #{"unit"}) "`slice`; kin/scripts/gates does not list it"))
    (ok! "a fast_gates entry the map lacks is named" (has? (probs :fast-gates #{"unit" "slice" "gfuse"}) "`gfuse` in fast_gates"))
    (ok! "a core step that is no bb task is named" (has? (probs :tasks #{}) "not a bb.edn task"))
    (ok! "a duplicate glob is named" (has? (probs :m (update base :map conj (first (:map base)))) "listed 2 times"))
    ;; the plan
    (let [p (fn [& paths] (plan-of base (vec paths)))]
      (ok! "plan: a docs-only change needs no gate" (let [x (p "docs/a.md")] (and (empty? (:gates x)) (:docs-only x) (not (:has-gates x)) (empty? (:unmapped x)))))
      (ok! "plan: kin/retarget.kin -> unit and retarget, not gfuse" (let [x (p "kin/retarget.kin")] (and (= ["unit" "retarget"] (:gates x)) (= [] (:hosts x)))))
      (ok! "plan: gates are the union, in shard order, a group expanded; hosts follow the entry"
           (let [x (p "kin/retargettest.kin" "kin/gvec.kin")] (and (= ["unit" "slice" "retarget" "gfuse"] (:gates x)) (= ["java"] (:hosts x)))))
      (ok! "plan: the matrix splits fast and full per shard"
           (= [{:name "unit" :fast "unit" :full ""} {:name "replay" :fast "slice" :full "retarget gfuse"}] (get-in (p "kin/retarget.kin" "kin/gfuse.kin") [:matrix :include])))
      (ok! "plan: a gate the core runs is not planned again" (let [m (assoc-in base [:map 2 :gates] ["hosts"]) x (plan-of m ["docs/a.md"])] (empty? (:gates x))))
      (ok! "plan: an unmapped changed path is reported" (= ["zzz/new"] (:unmapped (p "docs/a.md" "zzz/new")))))
    ;; D550: a version-only diff plans nothing; --all plans everything the core does not run; the archives rule
    (let [vbase (assoc base :version-files ["manifest.toml"] :archives ["host/**"])
          pv (fn [opts & paths] (plan-of vbase (vec paths) opts))]
      (ok! "plan: a path that is only a version change plans nothing (and is not 'unmapped')"
           (let [x (pv {:version-only #{"kin/retarget.kin"}} "kin/retarget.kin")] (and (empty? (:gates x)) (empty? (:unmapped x)) (:docs-only x) (= ["kin/retarget.kin"] (:version-only x)))))
      (ok! "plan: a version-only path next to a real change plans only the real change"
           (= ["unit" "slice" "retarget" "gfuse"] (:gates (pv {:version-only #{"kin/retarget.kin"}} "kin/retarget.kin" "kin/gfuse.kin" "kin/retargettest.kin" "kin/gvec.kin"))))
      (ok! "plan: --all is every gate the core does not run, whatever changed"
           (let [x (pv {:all? true})] (and (= ["unit" "slice" "retarget" "gfuse"] (:gates x)) (:unit x) (:archives x))))
      (ok! "plan: the shards of the plan are the planned gates in the shape of `bb ci shards`, without the unit shard"
           (= [{:name "replay" :gates "slice retarget gfuse" :corpus false}] (get-in (pv {} "kin/gfuse.kin" "kin/retarget.kin") [:shards :include])))
      (ok! "plan: with --all the shards are all of them, less the gates the core runs" (= (shard-include vbase #(not= % "hosts")) (get-in (pv {:all? true}) [:shards :include])))
      (ok! "plan: a change under :archives marks the archives, a document does not"
           (and (:archives (pv {} "host/b.rs")) (not (:archives (pv {} "docs/a.md"))) (not (:archives (pv {:version-only #{"host/b.rs"}} "host/b.rs"))))))
    ;; the version-only rule on the REAL manifests: every one of them, bumped by `bb version`'s own patterns, is version-only; a second change (a dependency, a new line) is not
    (let [targets (version-targets)
          bump (fn [t s] (str/replace s (:re t) (fn [[_ a _ c]] (str a "9.9.9" c))))
          m (read-map)]
      (ok! "version-only: the manifests of the gate map are the manifests of bb version" (= (set (:version-files m)) (set (map :file targets))))
      (doseq [t targets :let [before (slurp (:file t)) after (bump t before)]]
        (ok! (str "version-only: " (:file t) " with only its version changed") (version-only? t before after))
        (ok! (str "version-only: " (:file t) " with one more line is not") (not (version-only? t before (str after "# one more line\n"))))
        (ok! (str "version-only: " (:file t) " unchanged is not a change") (not (version-only? t before before))))
      (ok! "version-only: another crate's version in the same file is not the package's"
           (let [t (some #(when (= "host/cli/Cargo.toml" (:file %)) %) targets)
                 before "[package]\nname = \"emon\"\nversion = \"0.1.0\"\n[dependencies]\nfoo = \"1.2.3\"\n"]
             (and (version-only? t before "[package]\nname = \"emon\"\nversion = \"0.1.1\"\n[dependencies]\nfoo = \"1.2.3\"\n")
                  (not (version-only? t before "[package]\nname = \"emon\"\nversion = \"0.1.1\"\n[dependencies]\nfoo = \"1.2.4\"\n")))))
      (ok! "version-only: the set is every listed manifest that is version-only, nothing else"
           (let [blobs {"version.edn" ["{:version \"0.1.0\"}\n" "{:version \"0.1.1\"}\n"]
                        "host/c/src/capi.rs" ["static VERSION: &[u8] = b\"0.1.0\\0\";\n" "static VERSION: &[u8] = b\"0.1.1\\0\";\nfn x() {}\n"]
                        "host/rust/src/lib.rs" ["a" "b"]}]
             (= #{"version.edn"} (version-only-paths m targets (keys blobs) blobs))))
      ;; THE case: a real bump (every manifest `bb version set` writes) plans no gate, and does not ask for a single gate in any shard
      (let [paths (map :file targets)
            blobs (into {} (for [t targets] [(:file t) [(slurp (:file t)) (bump t (slurp (:file t)))]]))
            vonly (version-only-paths m targets paths blobs)
            x (plan-of m (vec paths) {:version-only vonly})]
        (ok! "plan: the version bump itself (version.edn and every manifest) plans no gate" (and (= (set paths) vonly) (empty? (:gates x)) (empty? (:unmapped x)) (not (:has-gates x)) (not (:unit x))))
        (ok! "plan: the same paths without the rule would plan gates (host/**, release/pkg/**)" (seq (:gates (plan-of m (vec paths)))))))
    ;; what verify.yml runs
    (let [m (read-map)
          sha1 "1111111111111111111111111111111111111111" sha2 "2222222222222222222222222222222222222222"
          inh {:version "0.1.0" :run "42" :sha sha1}
          vp (fn [o] (verify-plan-of (merge {:m m :mode :scoped :paths ["DEVLOG.md"] :version-only #{} :base sha1 :head sha2 :version "0.1.0" :inherited inh} o)))]
      (ok! "verify-plan: a document change: no gate, the base's archives are inherited"
           (let [x (vp {})] (and (= 0 (:n_gates x)) (not (:build_archives x)) (= "42" (:archives_run x)) (= "verified: 0 gates since 1111111" (:description x)) (= "scoped" (:mode x)))))
      (ok! "verify-plan: a change the archives are built from rebuilds them" (let [x (vp {:paths ["host/cli/src/main.rs"]})] (and (:build_archives x) (= "" (:archives_run x)))))
      (ok! "verify-plan: a kin change plans gates and rebuilds the archives" (let [x (vp {:paths ["kin/retarget.kin"]})] (and (pos? (:n_gates x)) (:unit x) (:build_archives x) (:has_shards x))))
      (ok! "verify-plan: the bump commit plans no gate but rebuilds the archives (they carry the version)"
           (let [x (vp {:paths ["version.edn" "host/cli/Cargo.toml"] :version-only #{"version.edn" "host/cli/Cargo.toml"} :version "0.1.1"})]
             (and (= 0 (:n_gates x)) (empty? (get-in x [:shards :include])) (:build_archives x) (= 2 (:version_only x)))))
      (ok! "verify-plan: no archives came with the base: they are built" (:build_archives (vp {:inherited nil})))
      (ok! "verify-plan: no verified base: everything runs, archives built"
           (let [x (vp {:base nil :reason "none"})] (and (= "full" (:mode x)) (:unit x) (:build_archives x) (= "none" (:reason x)) (pos? (:n_gates x)) (re-find #"full run" (:description x)))))
      (ok! "verify-plan: a full run asked for is full, even with a base" (let [x (vp {:mode :full})] (and (= "full" (:mode x)) (:build_archives x) (:unit x))))
      (ok! "verify-plan: the full run runs the shards of `bb ci shards` minus what the core runs"
           (let [all (set (str/split (str/join " " (map :gates (get-in (vp {:mode :full}) [:shards :include]))) #" "))
                 core (set (keep :gate (:core m)))]
             (= all (set/difference (set (mapcat :gates (remove :own-job (:shards m)))) core)))))
    (if (zero? @fails)
      (println "  ok   ci map-coverage self-test: the glob rules, every planted map failure named, and the plan")
      (die @fails " self-test case(s) failed"))))

;; ------------------------------------------------------------------------------------------------------------------ the law counts (the full run's summary)
(defn laws-record [arm log dir]
  (let [line (->> (str/split-lines (slurp log)) (filter #(re-find #"^  ok +\S+ +\d+ laws" %)) first)
        n (some->> line (re-find #"(\d+) laws") second)]
    (when-not n (die "no `ok " arm " N laws` line in " log))
    (fs/create-dirs dir)
    (spit (str dir "/laws-" arm ".txt") (str n "\n"))
    (println "  ok   " arm n "laws recorded")))

(defn laws-compare [dir]
  (let [counts (into {} (for [f (fs/glob dir "**/laws-*.txt")]
                          [(second (re-find #"laws-(.*)\.txt" (str (fs/file-name f)))) (str/trim (slurp (str f)))]))]
    (when (not= arms (set (keys counts)))
      (die "law counts present for " (pr-str (sort (keys counts))) ", want " (pr-str (sort arms))))
    (when (> (count (set (vals counts))) 1)
      (die "the arms ran different numbers of laws: " (pr-str counts)))
    (println "  ok   one law set, every arm, one count:" (first (vals counts)))))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "coverage" (coverage)
    "map-coverage" (if (some #{"--self-test"} args) (self-test) (map-coverage))
    "plan" (plan args)
    "shards" (shards args)
    "verify-plan" (verify-plan args)
    "core" (core)
    "laws-record" (apply laws-record args)
    "laws-compare" (apply laws-compare args)
    (die "usage: bb ci coverage | map-coverage [--self-test] | plan --base REF [--head REF] [--format edn|json] [--all] | shards | verify-plan --head SHA [--mode scoped|full] | core | laws-record ARM LOG DIR | laws-compare DIR")))
