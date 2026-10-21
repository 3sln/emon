#!/usr/bin/env bb
;; The small steps of the release workflows (P11.9, P11.10, D524, D547, D550). The workflows only call these; the logic is here so it can be tried on a laptop
;; (every step that changes something outside the working tree takes --dry-run, which says what it would do and does nothing).
;;
;;   bb release-ops next (--bump major|minor|patch | --version X.Y.Z)
;;        validate the version a release will have (newer than version.edn, no tag or release of that name) and print it; bump.yml then runs `bb version set`.
;;   bb release-ops draft --tag vX.Y.Z --sha SHA --notes FILE [--dry-run]
;;        the DRAFT GitHub release for the tag, `target_commitish` = SHA (the tag is made when the draft is published): created; or, when a draft exists, its target and notes
;;        are updated; a release that is already published is an error, and so is a tag that exists at another commit.
;;   bb release-ops archive --dir DIR [--dir DIR ...] --out OUT [--version X.Y.Z]
;;        collect the CLI, C library and pod archives under the DIRs into OUT (all 18 must be there) and write OUT/SHA256SUMS: what verify.yml uploads as `release-archives`.
;;   bb release-ops verdict --sha SHA --results plan=success,core=success,... --run-url URL --plan PLAN_JSON [--dry-run]
;;        post `emon/archives` and then `emon/verified` on SHA: verified only when `plan` succeeded and every other job succeeded or was skipped (a cancelled or failed job is not).
;;        PLAN_JSON is what `bb ci verify-plan` printed ({} if the plan job failed): it holds the description, the version and what to say of the archives.
;;   bb release-ops fetch-archives --sha SHA --version V --out DIR [--limit N] [--dry-run]
;;        find the archives for SHA: its own `emon/archives` status, else the nearest verified first-parent ancestor whose archives are valid for V (same version, inside the
;;        artifacts' retention), and download that run's `release-archives` artifact into DIR; check it is complete and matches SHA256SUMS. --dry-run: only find it and ask
;;        GitHub whether the artifact is still there.
;;   bb release-ops attach --tag vX.Y.Z --dir DIR [--dry-run]
;;        check DIR (all 18 files, SHA256SUMS matching) and upload it to the release with SHA256SUMS (--clobber: a rerun replaces them).
;;   bb release-ops summary --tag vX.Y.Z --results crates=success:published,npm=failure,... [--dry-run]
;;        the publish job's report (Markdown to $GITHUB_STEP_SUMMARY and stdout); exit 1 if any registry did not succeed.
;;   bb release-ops --self-test
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[release.lib :as rl])

(def triples-cli ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu" "x86_64-pc-windows-msvc"])
(def triples-lib ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu"])
(def registries ["crates" "npm" "nuget" "maven" "go"])
(def sums-file "SHA256SUMS")
(def artifact-name "release-archives")

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

(defn draft-decision
  "What to do about the draft of a tag. release: the release of that tag (a map with :draft) or nil; tag-sha: the commit the tag points at, if the tag exists; sha: the commit to release."
  [{:keys [release tag-sha sha]}]
  (cond
    (and release (not (:draft release))) {:action :refuse :why "a release for this tag is already PUBLISHED; a published version is never redrafted (release the next one)"}
    (and tag-sha (not= tag-sha sha)) {:action :refuse :why (str "the tag exists at " tag-sha ", not at " sha "; a tag is never moved")}
    release {:action :update}
    :else {:action :create}))

(defn expected-assets
  "Every file the release must carry besides SHA256SUMS, by name."
  [v]
  (vec (concat (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext))
               (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext ".sha256"))
               (for [t triples-lib] (str "emon-c-" v "-" t ".tar.gz"))
               (for [t triples-lib] (str "emon-pod-" v "-" t ".tar.gz")))))

(defn sums-text
  "The SHA256SUMS file: `<hash>  <name>` for every archive (not the .sha256 sidecars), sorted by name. hashes: name -> hex."
  [hashes]
  (apply str (for [[n h] (sort hashes) :when (not (str/ends-with? n ".sha256"))] (str h "  " n "\n"))))

(defn parse-sums [text]
  (into (sorted-map) (for [l (str/split-lines (str text)) :when (not (str/blank? l)) :let [[h n] (str/split l #"\s+" 2)]] [n h])))

(defn check-archives
  "Problems with a set of archives: files = name -> hex sha256 of what is on disk, sums = the text of its SHA256SUMS (or nil). [] when it is complete and consistent."
  [version files sums]
  (let [want (expected-assets version)
        listed (when sums (parse-sums sums))
        archives (remove #(str/ends-with? % ".sha256") want)]
    (vec (concat
          (for [n want :when (not (contains? files n))] (str "missing " n))
          (cond (nil? sums) ["missing SHA256SUMS"]
                :else (concat (for [n archives :when (not (contains? listed n))] (str "SHA256SUMS does not list " n))
                              (for [[n h] listed :when (and (contains? files n) (not= h (get files n)))] (str n " does not match SHA256SUMS"))
                              (for [[n _] listed :when (not (some #{n} archives))] (str "SHA256SUMS lists " n ", which is no archive of " version))))))))

(defn verdict-state
  "success only when `plan` succeeded and every job ended success or skipped. rows: parse-results output."
  [rows]
  (let [m (into {} (map (fn [[k r _]] [k r]) rows))]
    (if (and (= "success" (get m "plan")) (every? #{"success" "skipped"} (vals m))) "success" "failure")))

(defn verdict-description
  "<= 140 characters. The planned description when verified, else which jobs ended badly."
  [state planned rows]
  (if (= "success" state)
    planned
    (let [bad (for [[k r _] rows :when (not (#{"success" "skipped"} r))] k)
          s (str "NOT verified: " (if (seq bad) (str/join "," bad) "plan did not succeed") " (" planned ")")]
      (subs s 0 (min 140 (count s))))))

(defn archives-status
  "The `emon/archives` status of a verified run: built here (this run's id) or inherited (the base's description and run, verbatim)."
  [{:keys [build? version sha run-url repo archives-run archives-desc]}]
  (if build?
    {:description (rl/archives-description version (second (re-find #"/runs/(\d+)" (str run-url))) sha) :target-url run-url}
    {:description archives-desc :target-url (str "https://github.com/" repo "/actions/runs/" archives-run)}))

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

(defn- sha256-hex [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 1048576)]
    (with-open [in (java.io.FileInputStream. (str f))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- remote-tag-sha [tag]
  (let [r (rl/git! "ls-remote" "origin" (str "refs/tags/" tag) (str "refs/tags/" tag "^{}"))
        rows (for [l (str/split-lines (:out r)) :when (not (str/blank? l))] (str/split l #"\s+"))
        peeled (first (for [[sha ref] rows :when (str/ends-with? ref "^{}")] sha))
        plain (first (for [[sha ref] rows :when (not (str/ends-with? ref "^{}"))] sha))]
    (or peeled plain)))

(defn- find-release
  "The release (draft or published) with this tag name, or nil. The by-tag route of the API does not show drafts, so the list is read."
  [tag]
  (let [r (rl/run "gh" "api" "--paginate" (str "repos/" (rl/repo) "/releases?per_page=100")
                  "--jq" (str ".[] | select(.tag_name == " (json/generate-string tag) ") | {id, draft, prerelease, target_commitish, tag_name, html_url}"))]
    (when-not (zero? (:exit r)) (rl/die 1 "release-ops: cannot list the releases:" (str/trim (str (:err r) (:out r)))))
    (first (for [l (str/split-lines (:out r)) :when (not (str/blank? l))] (json/parse-string l true)))))

(defn next! [{:keys [bump version]}]
  (let [current (cur-version)
        p (plan-version {:current current :bump bump :version version
                         :tag-exists? #(or (rl/peel-sha %) (remote-tag-sha %) (find-release %))})]
    (when (:error p) (rl/die 1 "release-ops next:" (:error p)))
    (say "release: " current " -> " (:version p))
    (rl/write-output! "version" (:version p))
    (rl/write-output! "tag" (str "v" (:version p)))))

(defn draft! [{:keys [tag sha notes dry-run?]}]
  (let [v (rl/tag->version tag)
        _ (when-not (re-matches #"[0-9a-f]{40}" (str sha)) (rl/die 2 "release-ops draft: --sha must be a full commit sha, got" (pr-str sha)))
        release (find-release tag)
        d (draft-decision {:release release :tag-sha (remote-tag-sha tag) :sha sha})
        fields ["-f" (str "tag_name=" tag) "-f" (str "target_commitish=" sha) "-f" (str "name=emon " tag)
                "-F" (str "body=@" notes) "-F" "draft=true" "-F" (str "prerelease=" (boolean (str/includes? v "-")))]]
    (case (:action d)
      :refuse (rl/die 1 "release-ops draft:" tag (:why d))
      :update (do-or-say dry-run? (str "update the draft " tag ": target " sha ", notes " notes)
                         #(do (apply rl/run! "gh" "api" "-X" "PATCH" (str "repos/" (rl/repo) "/releases/" (:id release)) fields)
                              (say "updated the draft release " tag " (target " sha "): " (:html_url release))))
      :create (do-or-say dry-run? (str "create the draft " tag " targeting " sha)
                         #(let [r (apply rl/run! "gh" "api" "-X" "POST" (str "repos/" (rl/repo) "/releases") "--jq" ".html_url" fields)]
                            (say "created the draft release " tag " (target " sha "; the tag is made when it is published): " (str/trim (:out r))))))
    (rl/write-output! "action" (name (:action d)))))

(defn- files-of [dirs]
  (into (sorted-map)
        (for [d dirs, f (fs/glob d "**" {:follow-links false}) :when (fs/regular-file? f)]
          [(str (fs/file-name f)) (str f)])))

(defn archive! [{:keys [dirs out version]}]
  (let [v (or version (cur-version))
        found (files-of dirs)
        want (expected-assets v)
        missing (remove found want)]
    (say "archive: " (count (filter (set want) (keys found))) " of " (count want) " files found under " (str/join ", " dirs))
    (when (seq missing) (rl/die 1 (str "release-ops archive: missing " (count missing) " file(s):\n  " (str/join "\n  " missing))))
    (fs/create-dirs out)
    (doseq [n want] (fs/copy (get found n) (str out "/" n) {:replace-existing true}))
    (let [hashes (into {} (for [n want] [n (sha256-hex (str out "/" n))]))]
      (spit (str out "/" sums-file) (sums-text hashes))
      (say "wrote " (inc (count want)) " files to " out " (with " sums-file ")"))))

(defn- check-dir [dir version]
  (let [fs* (files-of [dir])
        hashes (into {} (for [n (expected-assets version) :when (contains? fs* n)] [n (sha256-hex (get fs* n))]))
        sums (when (contains? fs* sums-file) (slurp (get fs* sums-file)))]
    (check-archives version hashes sums)))

(defn plan-fields
  "What the verdict needs from the JSON `bb ci verify-plan` printed ({} when the plan job failed): its description, the version, whether this run built the archives and, if not,
   the run and description it inherited."
  [plan-json]
  (let [p (try (json/parse-string (str plan-json) true) (catch Exception _ nil))
        p (if (map? p) p {})]
    {:description (or (not-empty (:description p)) "plan failed")
     :version (or (not-empty (:version p)) "unknown")
     :build-archives (str (boolean (:build_archives p)))
     :archives-run (str (:archives_run p))
     :archives-desc (str (:archives_desc p))}))

(defn verdict! [{:keys [sha results plan run-url dry-run?]}]
  (let [{:keys [description version build-archives archives-run archives-desc]} (plan-fields plan)
        rows (parse-results results)
        state (verdict-state rows)
        build? (= "true" build-archives)
        desc (verdict-description state description rows)
        a (archives-status {:build? build? :version version :sha sha :run-url run-url :repo (rl/repo) :archives-run archives-run :archives-desc archives-desc})
        post (fn [ctx st d url]
               (apply rl/run! "gh" "api" "-X" "POST" (str "repos/" (rl/repo) "/statuses/" sha)
                      "-f" (str "state=" st) "-f" (str "context=" ctx) "-f" (str "description=" d)
                      (when (not (str/blank? url)) ["-f" (str "target_url=" url)])))]
    (say "verdict: " state " on " sha "; results: " results)
    (when (and (= "success" state) (not build?) (str/blank? archives-desc)) (rl/die 1 "release-ops verdict: archives are inherited but --archives-desc is empty"))
    (when (= "success" state)
      (do-or-say dry-run? (str "POST " rl/archives-context " success: " (:description a)) #(do (post rl/archives-context "success" (:description a) (:target-url a)) (say "posted " rl/archives-context))))
    (do-or-say dry-run? (str "POST " rl/status-context " " state ": " desc) #(do (post rl/status-context state desc run-url) (say "posted " rl/status-context)))
    (when (not= "success" state) (System/exit 1))))

(defn fetch-archives! [{:keys [sha version out limit dry-run?]}]
  (let [repo (rl/repo)
        shas (rl/first-parent-shas sha (inc (or (some-> limit parse-long) 60)))
        _ (when (empty? shas) (rl/die 1 "release-ops fetch-archives: cannot walk the history of" sha "(a shallow clone? fetch-depth: 0)"))
        found (rl/find-archives shas #(rl/statuses-of repo %) version (System/currentTimeMillis))]
    (when (:error found) (rl/die 1 "release-ops fetch-archives: cannot read the commit statuses:" (:error found)))
    (when-not (:sha found)
      (rl/die 1 (str "release-ops fetch-archives: no verified commit among the " (count shas) " nearest to " sha " has valid archives for " version
                     " (younger than " rl/archives-max-age-days " days, built for exactly this version). Run verify.yml again for the commit with mode=full (docs/releasing.md).")))
    (say "archives for " version ": run " (:run found) ", recorded on " (:sha found) (when (not= sha (:sha found)) (str " (an ancestor of " sha ")")))
    (let [arts (rl/gh-json (str "repos/" repo "/actions/runs/" (:run found) "/artifacts?per_page=100"))
          art (when-not (rl/error? arts) (first (filter #(= artifact-name (:name %)) (:artifacts arts))))]
      (when (rl/error? arts) (rl/die 1 "release-ops fetch-archives: cannot list the artifacts of run" (:run found) ":" (::rl/error arts)))
      (when-not art (rl/die 1 (str "release-ops fetch-archives: run " (:run found) " has no artifact " artifact-name)))
      (when (:expired art) (rl/die 1 (str "release-ops fetch-archives: the artifact of run " (:run found) " has expired; run verify.yml again for the commit with mode=full")))
      (say "artifact " artifact-name " of run " (:run found) ": " (:size_in_bytes art) " bytes, expires " (:expires_at art))
      (rl/write-output! "run" (:run found))
      (rl/write-output! "from" (:sha found))
      (do-or-say dry-run? (str "download it into " out)
                 #(do (rl/run! "gh" "run" "download" (:run found) "--repo" repo "-n" artifact-name "-D" out)
                      (let [problems (check-dir out version)]
                        (when (seq problems) (rl/die 1 (str "release-ops fetch-archives: the artifact is not a good set:\n  " (str/join "\n  " problems))))
                        (say "the 18 archives and " sums-file " are complete and consistent")))))))

(defn attach! [{:keys [tag dir dry-run?]}]
  (let [v (rl/tag->version tag)
        problems (check-dir dir v)]
    (when (seq problems) (rl/die 1 (str "release-ops attach: " dir " is not a good set:\n  " (str/join "\n  " problems))))
    (let [names (conj (expected-assets v) sums-file)
          paths (for [n names] (str dir "/" n))]
      (doseq [n names] (say "  " n))
      (do-or-say dry-run? (str "gh release upload " tag " (" (count names) " files) --clobber")
                 #(let [rel (find-release tag)]
                    (when-not rel (rl/die 1 "release-ops attach: no release" tag))
                    (when (:draft rel) (rl/die 1 "release-ops attach:" tag "is still a draft; the publish workflow attaches to the published release"))
                    (apply rl/run! "gh" "release" "upload" tag "--clobber" "--repo" (rl/repo) paths)
                    (say "attached " (count names) " files to " tag))))))

(defn summary! [{:keys [tag results dry-run?]}]
  (let [{:keys [markdown ok?]} (summary-md tag (parse-results results) dry-run?)]
    (print markdown)
    (when-let [f (not-empty (System/getenv "GITHUB_STEP_SUMMARY"))] (spit f markdown :append true))
    (when-not ok? (System/exit 1))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)
        exists #{"v0.2.0"}
        pv #(plan-version (merge {:current "0.1.0" :tag-exists? exists} %))
        sha "0123456789abcdef0123456789abcdef01234567" other "fedcba9876543210fedcba9876543210fedcba98"]
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
    (t! "draft: absent -> create" (= :create (:action (draft-decision {:release nil :sha sha}))))
    (t! "draft: a draft exists -> update its target and notes" (= :update (:action (draft-decision {:release {:draft true :id 1} :sha sha}))))
    (t! "draft: a draft whose tag exists at the same commit -> update" (= :update (:action (draft-decision {:release {:draft true} :tag-sha sha :sha sha}))))
    (t! "draft: a published release is refused, clearly" (let [d (draft-decision {:release {:draft false} :sha sha})] (and (= :refuse (:action d)) (str/includes? (:why d) "PUBLISHED"))))
    (t! "draft: a tag at another commit is refused" (= :refuse (:action (draft-decision {:release nil :tag-sha other :sha sha}))))
    (let [a (expected-assets "0.1.0")]
      (t! "expected assets: 5 CLI + 5 sums + 4 C + 4 pod = 18" (= 18 (count a)))
      (t! "expected assets are distinct" (= (count a) (count (set a))))
      (t! "windows ships a zip" (some #{"emon-0.1.0-x86_64-pc-windows-msvc.zip"} a))
      (t! "C and pod archives are named as bb package names them" (and (some #{"emon-c-0.1.0-aarch64-apple-darwin.tar.gz"} a) (some #{"emon-pod-0.1.0-x86_64-unknown-linux-gnu.tar.gz"} a)))
      (let [hashes (into {} (for [n a] [n (str (hash n))]))
            sums (sums-text hashes)]
        (t! "SHA256SUMS lists the 13 archives and not the .sha256 sidecars" (= 13 (count (parse-sums sums))))
        (t! "a complete set matches its sums" (= [] (check-archives "0.1.0" hashes sums)))
        (t! "a missing file is named" (= ["missing emon-c-0.1.0-aarch64-apple-darwin.tar.gz"]
                                         (filterv #(str/starts-with? % "missing emon-c") (check-archives "0.1.0" (dissoc hashes "emon-c-0.1.0-aarch64-apple-darwin.tar.gz") sums))))
        (t! "a file that differs from its sum is named" (some #(str/includes? % "does not match SHA256SUMS") (check-archives "0.1.0" (assoc hashes "emon-pod-0.1.0-x86_64-unknown-linux-gnu.tar.gz" "0") sums)))
        (t! "no SHA256SUMS is a problem" (some #{"missing SHA256SUMS"} (check-archives "0.1.0" hashes nil)))
        (t! "archives of another version are all missing" (= 18 (count (filter #(str/starts-with? % "missing emon") (check-archives "0.2.0" hashes sums)))))))
    (t! "verdict: plan and every job success -> success" (= "success" (verdict-state (parse-results "plan=success,core=success,gates=success"))))
    (t! "verdict: skipped jobs are fine" (= "success" (verdict-state (parse-results "plan=success,core=success,gates=skipped,unit=skipped,archives=skipped"))))
    (t! "verdict: one failure" (= "failure" (verdict-state (parse-results "plan=success,core=failure,gates=success"))))
    (t! "verdict: cancelled" (= "failure" (verdict-state (parse-results "plan=success,gates=cancelled"))))
    (t! "verdict: the plan skipped or failed is not success" (and (= "failure" (verdict-state (parse-results "plan=failure,core=skipped"))) (= "failure" (verdict-state (parse-results "core=success")))))
    (t! "verdict description: verified keeps the plan's" (= "verified: 0 gates since 89abcde" (verdict-description "success" "verified: 0 gates since 89abcde" [])))
    (t! "verdict description: a failure names the jobs, fits 140"
        (let [d (verdict-description "failure" "verified: 12 gates since 89abcde" (parse-results "plan=success,core=failure,gates=failure"))] (and (str/starts-with? d "NOT verified: core,gates") (<= (count d) 140))))
    (t! "verdict description: never starts with `verified:` when it failed" (not (str/starts-with? (verdict-description "failure" "verified: 1 gates since a" (parse-results "plan=failure")) "verified:")))
    (t! "archives status: built in this run" (= {:description "archives 0.1.0: run 99 (built at 0123456)" :target-url "https://github.com/3sln/emon/actions/runs/99"}
                                                (archives-status {:build? true :version "0.1.0" :sha sha :run-url "https://github.com/3sln/emon/actions/runs/99" :repo "3sln/emon"})))
    (t! "archives status: inherited verbatim, pointing at the run that built them"
        (= {:description "archives 0.1.0: run 42 (built at 89abcde)" :target-url "https://github.com/3sln/emon/actions/runs/42"}
           (archives-status {:build? false :repo "3sln/emon" :archives-run "42" :archives-desc "archives 0.1.0: run 42 (built at 89abcde)"})))
    (t! "plan fields: a built plan" (= {:description "verified: 3 gates since 89abcde" :version "0.1.0" :build-archives "true" :archives-run "" :archives-desc ""}
                                       (plan-fields "{\"description\":\"verified: 3 gates since 89abcde\",\"version\":\"0.1.0\",\"build_archives\":true,\"archives_run\":\"\",\"archives_desc\":\"\"}")))
    (t! "plan fields: an inherited plan" (let [f (plan-fields "{\"description\":\"verified: 0 gates since 89abcde\",\"version\":\"0.1.0\",\"build_archives\":false,\"archives_run\":\"42\",\"archives_desc\":\"archives 0.1.0: run 42 (built at 89abcde)\"}")]
                                           (and (= "false" (:build-archives f)) (= "42" (:archives-run f)) (str/starts-with? (:archives-desc f) "archives 0.1.0"))))
    (t! "plan fields: no plan (the plan job failed) gives a description that is not a green one" (let [f (plan-fields "{}")] (and (= "plan failed" (:description f)) (= "unknown" (:version f)))))
    (t! "plan fields: unparsable text is the same as no plan" (= "plan failed" (:description (plan-fields "not json"))))
    (t! "parse-results" (= [["a" "success" nil] ["b" "failure" "why: not"]] (parse-results "a=success,b=failure:why: not")))
    (let [ok (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "success" "already published"]])
          bad (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "failure" nil] ["maven" "cancelled" nil]])]
      (t! "summary: all success is ok" (:ok? ok))
      (t! "summary: a failure is not ok" (not (:ok? bad)))
      (t! "summary names the failed registries and the recovery" (and (str/includes? (:markdown bad) "Failed: npm, maven") (str/includes? (:markdown bad) "Re-run failed jobs")))
      (t! "summary: a dry run says so, and never claims anything was published"
          (let [m (:markdown (summary-md "v1.2.3" [["npm" "success" "dry-run"]] true))] (and (str/includes? m "DRY RUN: nothing was published") (not (str/includes? m "published |")))))
      (t! "summary: a skipped registry is not a failure" (:ok? (summary-md "v1.2.3" [["crates" "skipped" nil]]))))
    (done!)))

;; ---------------------------------------------------------------------------------------------------- main
(def ^:private value-flags
  {"--bump" :bump "--version" :version "--tag" :tag "--sha" :sha "--notes" :notes "--out" :out "--limit" :limit
   "--results" :results "--run-url" :run-url "--plan" :plan})

(defn- parse [args]
  (loop [as args o {}]
    (if-let [a (first as)]
      (cond
        (value-flags a) (recur (nnext as) (assoc o (value-flags a) (second as)))
        (= a "--dir") (recur (nnext as) (-> o (update :dirs (fnil conj []) (second as)) (assoc :dir (second as))))
        (= a "--dry-run") (recur (next as) (assoc o :dry-run? true))
        :else (rl/die 2 "bb release-ops: unknown argument" a))
      o)))

(defn -main [[cmd & args]]
  (let [o (parse args)
        need (fn [& ks] (doseq [k ks] (when-not (get o k) (rl/die 2 (str "bb release-ops " cmd ": --" (name k) " is required")))))]
    (case cmd
      "next" (next! o)
      "draft" (do (need :tag :sha :notes) (draft! o))
      "archive" (do (need :dirs :out) (archive! o))
      "verdict" (do (need :sha :results :run-url :plan) (verdict! o))
      "fetch-archives" (do (need :sha :version :out) (fetch-archives! o))
      "attach" (do (need :tag :dir) (attach! o))
      "summary" (do (need :tag :results) (summary! o))
      (rl/die 2 "usage: bb release-ops next|draft|archive|verdict|fetch-archives|attach|summary ... [--dry-run] | --self-test"))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
