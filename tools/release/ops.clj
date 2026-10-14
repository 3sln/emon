#!/usr/bin/env bb
;; The small steps of the prepare-release workflow and the publish summary (P11.9, P11.10, D524, D547). The workflows only call these; the logic is here so
;; it can be tried on a laptop (every step that changes something outside the working tree takes --dry-run, which says what it would do and does nothing).
;;
;;   bb release-ops propose (--bump major|minor|patch | --version X.Y.Z) [--base main] [--dry-run]
;;        validate the new version (newer than version.edn, no such tag, no such branch), `bb version set`, commit on release/vX.Y.Z, push it, open the PR.
;;   bb release-ops tag --tag vX.Y.Z [--sha SHA] [--dry-run]
;;        `bb version --check` the checked-out tree, then create the ANNOTATED tag at SHA (default HEAD) and push it. A tag already on SHA is fine (reruns);
;;        a tag anywhere else is an error. The Go module tag go/vX.Y.Z is NOT made here: it needs the assembled module tree, so the publish job makes it (D542).
;;   bb release-ops draft --tag vX.Y.Z --notes FILE [--dry-run]
;;        create the DRAFT GitHub release for the tag with those notes. A draft that exists is kept as it is (the maintainer may have edited the notes);
;;        a release that is already published is an error.
;;   bb release-ops attach --tag vX.Y.Z --dir DIR [--dir DIR ...] [--allow-missing] [--dry-run]
;;        collect the CLI, C library and pod archives (and .sha256) under the DIRs, require all 14 (5 CLI, 4 C, 4 pod, +SHA256SUMS), upload them to the draft.
;;   bb release-ops status --sha SHA --tag vX.Y.Z (--results gates=success,native=success,... | --state STATE) [--target-url URL] [--dry-run]
;;        post the commit status `emon/full-gates` on SHA: success only when EVERY named result is success.
;;   bb release-ops summary --tag vX.Y.Z --results crates=success:published,npm=failure,... [--dry-run]
;;        the publish job's report (Markdown to $GITHUB_STEP_SUMMARY and stdout); exit 1 if any registry did not succeed.
;;   bb release-ops --self-test
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[release.lib :as rl])

(def triples-cli ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu" "x86_64-pc-windows-msvc"])
(def triples-lib ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu"])
(def registries ["crates" "npm" "nuget" "maven" "go" "pages"])

;; ---------------------------------------------------------------------------------------------------- pure parts
(defn plan-version
  "The version a release will have. {:current \"0.1.0\" :bump \"patch\" | :version \"0.2.0\" :tag-exists? fn}. Returns {:version v} or {:error text}."
  [{:keys [current bump version tag-exists?]}]
  (let [bump-of (fn [c part] (let [[a b p] (rl/parse-core c)]
                               (case part "major" (str (inc a) ".0.0") "minor" (str a "." (inc b) ".0") "patch" (str a "." b "." (inc p)) nil)))
        v (cond (and bump version) nil
                bump (bump-of current bump)
                version (rl/tag->version version))]
    (cond
      (and bump version) {:error "give --bump or --version, not both"}
      (and (not bump) (not version)) {:error "give --bump major|minor|patch or --version X.Y.Z"}
      (nil? v) {:error (str "not a bump (major|minor|patch) or a version (X.Y.Z): " (or bump version))}
      (not (rl/newer? v current)) {:error (str "version " v " is not newer than the current " current)}
      (and tag-exists? (tag-exists? (str "v" v))) {:error (str "tag v" v " already exists")}
      :else {:version v})))

(defn pr-body [version]
  (str "Release **v" version "**: `bb version set " version "` (every manifest, `version.edn`).\n\n"
       "Merging this PR starts the second half of the `prepare-release` workflow: it tags `v" version "` on the merge commit, creates the DRAFT release with notes, "
       "runs the full gates, builds the CLI, C library and pod archives for every platform, attaches them to the draft and posts the `emon/full-gates` status.\n\n"
       "Nothing is published by this PR or by that workflow. Publishing is the maintainer flipping the draft release to *published* (docs/releasing.md).\n"))

(defn tag-decision
  "What to do about the tag. remote-shas: the commits the tag points at on the remote (a set of 0 or 1; peeled)."
  [remote-shas sha]
  (cond (empty? remote-shas) :create
        (= #{sha} (set remote-shas)) :exists
        :else :conflict))

(defn draft-decision [{:keys [exists? draft?]}]
  (cond (not exists?) :create draft? :keep :else :refuse))

(defn expected-assets
  "Every file the draft must carry, by name."
  [v]
  (vec (concat (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext))
               (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext ".sha256"))
               (for [t triples-lib] (str "emon-c-" v "-" t ".tar.gz"))
               (for [t triples-lib] (str "emon-pod-" v "-" t ".tar.gz")))))

(defn gate-state
  "success only when every result is success; failure otherwise. results: {\"gates\" \"success\" ...}."
  [results]
  (if (and (seq results) (every? #(= "success" %) (vals results))) "success" "failure"))

(defn parse-results
  "\"a=success,b=failure:why\" -> [[\"a\" \"success\" nil] [\"b\" \"failure\" \"why\"]], order kept."
  [s]
  (vec (for [kv (str/split (str s) #",") :when (not (str/blank? kv))
             :let [[k v] (str/split kv #"=" 2) [r d] (str/split (str v) #":" 2)]]
         [k r d])))

(defn summary-md
  "The publish job's report. rows: parse-results output. Returns {:markdown :ok?}."
  [tag rows & [dry?]]
  (let [v (rl/tag->version tag)
        ok? (every? (fn [[_ r _]] (#{"success" "skipped"} r)) rows)
        failed (for [[k r _] rows :when (not (#{"success" "skipped"} r))] k)
        sb (StringBuilder.)
        line (fn [& xs] (.append sb (apply str xs)) (.append sb "\n"))]
    (line "## Publish " tag (when dry? " (DRY RUN: nothing was published)"))
    (line)
    (line "| Registry | Result | Detail |")
    (line "|---|---|---|")
    (doseq [[k r d] rows]
      (line "| " k " | " (case r "success" (if dry? "ok (dry run)" "done") "skipped" "skipped" "FAILED") (when-not (#{"success" "skipped"} r) (str " (" r ")")) " | " (or d "") " |"))
    (line)
    (if ok?
      (line (if dry? "Every check passed; a real release would publish " "Every registry is at ") v ". A rerun is safe: a registry that has the version is skipped.")
      (do (line "**NOT everything was published.** Failed: " (str/join ", " failed) ". The others are done and stay done; this is a partial publish.")
          (line)
          (line "1. Read the failed job's log; fix the cause (a credential, a registry outage). A registry never accepts the same version twice, so do not change the tag.")
          (line "2. Re-run the failed jobs (Actions > this run > Re-run failed jobs). Each step skips a version its registry already has.")
          (line "3. If the cause is in the code, the tag cannot move: release the next patch version instead (docs/releasing.md, \"Recovering from a partial publish\").")))
    {:markdown (str sb) :ok? ok?}))

;; ---------------------------------------------------------------------------------------------------- effects
(defn- say [& xs] (println (apply str xs)))
(defn- do-or-say [dry? what f] (if dry? (say "  [dry run] would: " what) (f)))
(defn- cur-version [] (:version (edn/read-string (slurp "version.edn"))))
(defn- bot [] ["-c" "user.name=github-actions[bot]" "-c" "user.email=41898282+github-actions[bot]@users.noreply.github.com"])

(defn- remote-tag-shas [tag]
  (let [r (rl/git! "ls-remote" "origin" (str "refs/tags/" tag) (str "refs/tags/" tag "^{}"))
        rows (for [l (str/split-lines (:out r)) :when (not (str/blank? l))] (str/split l #"\s+"))
        peeled (first (for [[sha ref] rows :when (str/ends-with? ref "^{}")] sha))
        plain (first (for [[sha ref] rows :when (not (str/ends-with? ref "^{}"))] sha))]
    (if-let [s (or peeled plain)] #{s} #{})))

(defn propose [{:keys [bump version base dry-run?]}]
  (let [current (cur-version)
        p (plan-version {:current current :bump bump :version version
                         :tag-exists? #(or (rl/peel-sha %) (seq (remote-tag-shas %)))})]
    (when (:error p) (rl/die 1 "release-ops propose:" (:error p)))
    (let [v (:version p) tag (str "v" v) branch (str "release/" tag) base (or base "main")]
      (say "propose: " current " -> " v " (tag " tag ", branch " branch ", PR into " base ")")
      (when (zero? (:exit (rl/git "ls-remote" "--exit-code" "--heads" "origin" branch)))
        (rl/die 1 "release-ops propose: branch" branch "already exists on the remote (an earlier proposal; merge or delete it)"))
      (rl/write-output! "version" v)
      (rl/write-output! "tag" tag)
      (rl/write-output! "branch" branch)
      (if dry-run?
        (say "  [dry run] would: bb version set " v ", commit, push " branch ", gh pr create --base " base)
        (do (rl/git! "switch" "-c" branch)
            (rl/run! "bb" "tools/version.clj" "set" v)
            (rl/run! "bb" "tools/version.clj" "--check" v)
            (rl/git! "add" "-u")
            (apply rl/git! (concat (bot) ["commit" "-m" (str "release: " tag "\n\nbb version set " v)]))
            (rl/git! "push" "origin" branch)
            (let [r (rl/run! "gh" "pr" "create" "--base" base "--head" branch "--title" (str "release: " tag) "--body" (pr-body v))]
              (say (str/trim (:out r)))))))))

(defn tag! [{:keys [tag sha dry-run?]}]
  (let [sha (or sha (rl/head-sha))
        v (rl/run "bb" "tools/version.clj" "--check" tag)]
    (when-not (zero? (:exit v)) (rl/die 1 "release-ops tag: the checked-out tree is not" tag "\n" (:out v) (:err v)))
    (when-not (= sha (rl/head-sha)) (rl/die 1 "release-ops tag: --sha" sha "is not the checked-out commit" (rl/head-sha)))
    (case (tag-decision (remote-tag-shas tag) sha)
      :exists (say "tag " tag " already points at " sha ": nothing to do")
      :conflict (rl/die 1 "release-ops tag:" tag "already exists on the remote at another commit; a tag is never moved. Release the next version instead.")
      :create (do-or-say dry-run? (str "tag " tag " at " sha " and push it")
                         #(do (apply rl/git! (concat (bot) ["tag" "-a" tag sha "-m" (str "emon " tag)]))
                              (rl/git! "push" "origin" (str "refs/tags/" tag))
                              (say "tagged " tag " at " sha))))
    (rl/write-output! "sha" sha)))

(defn draft! [{:keys [tag notes dry-run?]}]
  (let [v (rl/gh-json (str "repos/" (rl/repo) "/releases/tags/" tag))
        exists? (not (rl/error? v))
        d (draft-decision {:exists? exists? :draft? (:draft v)})]
    (when (and (rl/error? v) (not (re-find #"404|Not Found" (::rl/error v)))) (rl/die 1 "release-ops draft: cannot read releases:" (::rl/error v)))
    (case d
      :refuse (rl/die 1 "release-ops draft:" tag "is already a PUBLISHED release; nothing to draft")
      :keep (say "the draft release " tag " exists: kept as it is (its notes may have been edited)")
      :create (do-or-say dry-run? (str "gh release create " tag " --draft --verify-tag --notes-file " notes)
                         #(do (rl/run! "gh" "release" "create" tag "--draft" "--verify-tag" "--title" (str "emon " tag) "--notes-file" notes)
                              (say "created the draft release " tag))))))

(defn- collect-assets [dirs names]
  (let [want (set names)]
    (into (sorted-map)
          (for [d dirs, f (fs/glob d "**" {:follow-links false}) :when (and (fs/regular-file? f) (want (str (fs/file-name f))))]
            [(str (fs/file-name f)) (str f)]))))

(defn attach! [{:keys [tag dirs allow-missing? dry-run?]}]
  (let [v (rl/tag->version tag)
        names (expected-assets v)
        found (collect-assets dirs names)
        missing (remove found names)]
    (say "attach: " (count found) " of " (count names) " files found under " (str/join ", " dirs))
    (when (and (seq missing) (not allow-missing?))
      (rl/die 1 (str "release-ops attach: missing " (count missing) " file(s):\n  " (str/join "\n  " missing))))
    (let [sums (str (fs/create-temp-dir {:prefix "emon-assets-"}) "/SHA256SUMS")]
      (spit sums (str (str/join "" (for [[n f] found] (str (first (str/split (:out (rl/run! "shasum" "-a" "256" f)) #"\s+")) "  " n "\n")))))
      (doseq [[n _] found] (say "  " n))
      (do-or-say dry-run? (str "gh release upload " tag " (" (inc (count found)) " files) --clobber")
                 #(let [rel (rl/gh-json (str "repos/" (rl/repo) "/releases/tags/" tag))]
                    (when (rl/error? rel) (rl/die 1 "release-ops attach: no release" tag))
                    (when-not (:draft rel) (rl/die 1 "release-ops attach:" tag "is published; assets are attached to the DRAFT only"))
                    (apply rl/run! "gh" "release" "upload" tag "--clobber" sums (vals found))
                    (say "attached " (inc (count found)) " files to the draft " tag))))))

(defn status! [{:keys [sha tag results state target-url dry-run?]}]
  (let [rows (parse-results results)
        state (or state (gate-state (into {} (map (fn [[k r _]] [k r]) rows))))
        desc (rl/status-description sha tag)]
    (when-not (#{"success" "failure" "pending" "error"} state) (rl/die 2 "release-ops status: --state is success|failure|pending|error"))
    (say "status " rl/status-context " = " state " on " sha " (" desc "); results: " (or results "(--state given)"))
    (do-or-say dry-run? "POST the commit status"
               #(do (apply rl/run! "gh" "api" "-X" "POST" (str "repos/" (rl/repo) "/statuses/" sha)
                           "-f" (str "state=" state) "-f" (str "context=" rl/status-context) "-f" (str "description=" desc)
                           (when target-url ["-f" (str "target_url=" target-url)]))
                    (say "posted")))))

(defn summary! [{:keys [tag results dry-run?]}]
  (let [{:keys [markdown ok?]} (summary-md tag (parse-results results) dry-run?)]
    (print markdown)
    (when-let [f (not-empty (System/getenv "GITHUB_STEP_SUMMARY"))] (spit f markdown :append true))
    (when-not ok? (System/exit 1))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)
        exists #{"v0.2.0"}
        pv #(plan-version (merge {:current "0.1.0" :tag-exists? exists} %))]
    (t! "bump patch" (= {:version "0.1.1"} (pv {:bump "patch"})))
    (t! "bump minor" (= {:version "0.3.0"} (plan-version {:current "0.2.0" :bump "minor" :tag-exists? #{}})))
    (t! "bump minor onto an existing tag is refused" (:error (pv {:bump "minor"})))
    (t! "bump major" (= {:version "1.0.0"} (pv {:bump "major"})))
    (t! "an explicit version" (= {:version "0.3.0"} (pv {:version "0.3.0"})))
    (t! "an explicit version with a leading v" (= {:version "0.3.0"} (pv {:version "v0.3.0"})))
    (t! "a version that is not newer is refused" (:error (pv {:version "0.1.0"})))
    (t! "an older version is refused" (:error (pv {:version "0.0.9"})))
    (t! "a tag that exists is refused" (re-find #"already exists" (str (:error (pv {:version "0.2.0"})))))
    (t! "bump and version together are refused" (:error (pv {:bump "patch" :version "0.3.0"})))
    (t! "neither is refused" (:error (pv {})))
    (t! "a nonsense bump is refused" (:error (pv {:bump "huge"})))
    (t! "a nonsense version is refused" (:error (pv {:version "1.2"})))
    (t! "a pre-release of the next version is newer than the current" (= {:version "0.2.0-rc.1"} (pv {:version "0.2.0-rc.1"})))
    (t! "tag: none on the remote -> create" (= :create (tag-decision #{} "a")))
    (t! "tag: on this commit -> exists" (= :exists (tag-decision #{"a"} "a")))
    (t! "tag: on another commit -> conflict" (= :conflict (tag-decision #{"b"} "a")))
    (t! "draft: absent -> create" (= :create (draft-decision {:exists? false})))
    (t! "draft: a draft is kept (its notes may be edited)" (= :keep (draft-decision {:exists? true :draft? true})))
    (t! "draft: a published release is refused" (= :refuse (draft-decision {:exists? true :draft? false})))
    (let [a (expected-assets "0.1.0")]
      (t! "expected assets: 5 CLI + 5 sums + 4 C + 4 pod = 18" (= 18 (count a)))
      (t! "expected assets are distinct" (= (count a) (count (set a))))
      (t! "windows ships a zip" (some #{"emon-0.1.0-x86_64-pc-windows-msvc.zip"} a))
      (t! "C and pod archives are named as bb package names them" (and (some #{"emon-c-0.1.0-aarch64-apple-darwin.tar.gz"} a) (some #{"emon-pod-0.1.0-x86_64-unknown-linux-gnu.tar.gz"} a))))
    (t! "gate-state: all success" (= "success" (gate-state {"a" "success" "b" "success"})))
    (t! "gate-state: one failure" (= "failure" (gate-state {"a" "success" "b" "failure"})))
    (t! "gate-state: a skipped job is not success" (= "failure" (gate-state {"a" "success" "b" "skipped"})))
    (t! "gate-state: cancelled" (= "failure" (gate-state {"a" "cancelled"})))
    (t! "gate-state: no results is not success" (= "failure" (gate-state {})))
    (t! "parse-results" (= [["a" "success" nil] ["b" "failure" "why: not"]] (parse-results "a=success,b=failure:why: not")))
    (let [ok (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "success" "already published"]])
          bad (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "failure" nil] ["maven" "cancelled" nil]])]
      (t! "summary: all success is ok" (:ok? ok))
      (t! "summary: a failure is not ok" (not (:ok? bad)))
      (t! "summary names the failed registries and the recovery" (and (str/includes? (:markdown bad) "Failed: npm, maven") (str/includes? (:markdown bad) "Re-run failed jobs")))
      (t! "summary: a dry run says so, and never claims anything was published"
          (let [m (:markdown (summary-md "v1.2.3" [["npm" "success" "dry-run"]] true))] (and (str/includes? m "DRY RUN: nothing was published") (not (str/includes? m "published |")))))
      (t! "summary: a skipped registry is not a failure" (:ok? (summary-md "v1.2.3" [["pages" "skipped" nil]]))))
    (t! "pr body names the version and says nothing is published" (let [b (pr-body "1.2.3")] (and (str/includes? b "v1.2.3") (str/includes? b "Nothing is published"))))
    (done!)))

;; ---------------------------------------------------------------------------------------------------- main
(def ^:private value-flags
  {"--bump" :bump "--version" :version "--base" :base "--tag" :tag "--sha" :sha "--notes" :notes
   "--results" :results "--state" :state "--target-url" :target-url})

(defn- parse [args]
  (loop [as args o {}]
    (if-let [a (first as)]
      (cond
        (value-flags a) (recur (nnext as) (assoc o (value-flags a) (second as)))
        (= a "--dir") (recur (nnext as) (update o :dirs (fnil conj []) (second as)))
        (= a "--dry-run") (recur (next as) (assoc o :dry-run? true))
        (= a "--allow-missing") (recur (next as) (assoc o :allow-missing? true))
        :else (rl/die 2 "bb release-ops: unknown argument" a))
      o)))

(defn -main [[cmd & args]]
  (let [o (parse args)
        need (fn [& ks] (doseq [k ks] (when-not (get o k) (rl/die 2 (str "bb release-ops " cmd ": --" (name k) " is required")))))]
    (case cmd
      "propose" (propose o)
      "tag" (do (need :tag) (tag! o))
      "draft" (do (need :tag :notes) (draft! o))
      "attach" (do (need :tag :dirs) (attach! o))
      "status" (do (need :sha :tag) (when-not (or (:results o) (:state o)) (rl/die 2 "bb release-ops status: --results or --state is required")) (status! o))
      "summary" (do (need :tag :results) (summary! o))
      (rl/die 2 "usage: bb release-ops propose|tag|draft|attach|status|summary ... [--dry-run] | --self-test"))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
