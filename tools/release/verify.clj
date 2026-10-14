#!/usr/bin/env bb
;; The publish job's re-verification (P11.10, D524, D547, D1530): nothing is published unless all four hold.
;;
;;   bb release-verify --tag vX.Y.Z [--repo OWNER/REPO] [--dry-run]
;;   bb release-verify --self-test
;;
;;   1. version  `bb version --check TAG`: every manifest of the CHECKED-OUT tree equals the tag.
;;   2. tag      the tag exists on GitHub and still points at the commit that is checked out (it was not moved after the verification ran).
;;   3. target   the release's `target_commitish` is a commit (a sha, as draft.yml sets it) and is the commit the tag points at: the draft was published as it was
;;               drafted, not retargeted at a branch or another commit by hand.
;;   4. status   the NEWEST `emon/verified` commit status on that commit is `success` (verify.yml's: the description starts `verified:` and the target is a workflow run).
;; Exit 0 when all hold, 1 when one fails, 2 on usage. On success the verified commit is printed as `sha=...` and appended to $GITHUB_OUTPUT (in Actions),
;; and the publish jobs check THAT out, so a tag moved AFTER this step cannot change what is published.
;; --dry-run (local use): a tag or release that does not exist yet, or GitHub being unreachable, is reported as SKIP, not as a failure; the version check is real.
;; Reads GH_TOKEN / the gh login for the status; talks to nothing else; writes nothing.
(require '[clojure.string :as str]
         '[release.lib :as rl])

;; ---------------------------------------------------------------------------------------------------- the pure decision
(defn evaluate
  "Decide. m: {:tag :dry-run? :version {:ok? :out} :head :local :remote :release :statuses}
   :remote, :release and :statuses are the GitHub answers: a sha string / a release map / a seq of statuses, nil when it does not exist, or {::rl/error text} when the call failed.
   Returns [{:check :version|:tag|:target|:status, :state :ok|:fail|:skip, :msg}]."
  [{:keys [tag dry-run? version head local remote release statuses]}]
  (let [unreachable? (rl/error? remote)
        no-tag? (and (not unreachable?) (nil? remote))
        skip-or-fail (fn [check msg] {:check check :state (if dry-run? :skip :fail) :msg msg})
        v {:check :version
           :state (if (:ok? version) :ok :fail)
           :msg (if (:ok? version) (str/trim (:out version)) (str "the checked-out tree is not " tag ":\n" (str/trim (:out version))))}
        t (cond
            unreachable? (skip-or-fail :tag (str "cannot ask GitHub about the tag: " (::rl/error remote)))
            no-tag? (skip-or-fail :tag (str "no tag " tag " exists" (when dry-run? " (expected before the draft is published)")))
            (and local (not= local remote)) {:check :tag :state :fail :msg (str "tag " tag " is " remote " on GitHub but " local " locally: it was moved (or the clone is stale)")}
            (not= head remote) {:check :tag :state :fail :msg (str "tag " tag " is " remote " but the checked-out commit is " head)}
            :else {:check :tag :state :ok :msg (str tag " is " remote ", the checked-out commit")})
        g (cond
            (or unreachable? no-tag?) (skip-or-fail :target "no commit to compare the release's target with")
            (rl/error? release) (skip-or-fail :target (str "cannot read the release: " (::rl/error release)))
            (nil? release) (skip-or-fail :target (str "no release for " tag ": a tag is made by publishing the draft that draft.yml created, not by hand"))
            (not (re-matches #"[0-9a-f]{40}" (str (:target_commitish release))))
            {:check :target :state :fail :msg (str "the release targets " (pr-str (:target_commitish release)) ", not a commit: it was retargeted after draft.yml set it")}
            (not= (:target_commitish release) remote)
            {:check :target :state :fail :msg (str "the release targets " (:target_commitish release) " but the tag points at " remote)}
            :else {:check :target :state :ok :msg (str "the release targets " remote ", where the tag points")})
        s (cond
            (or unreachable? no-tag?) (skip-or-fail :status "no commit to read the status of")
            (rl/error? statuses) (skip-or-fail :status (str "cannot read the commit statuses: " (::rl/error statuses)))
            :else
            (let [st (rl/newest rl/status-context statuses)]
              (cond
                (nil? st) {:check :status :state :fail :msg (str "no " rl/status-context " status on " remote ": verify.yml has not verified this commit")}
                (not= "success" (:state st)) {:check :status :state :fail :msg (str rl/status-context " on " remote " is " (:state st) " (" (:description st) ")")}
                (not (rl/verified-ok? st)) {:check :status :state :fail :msg (str rl/status-context " is success but is not verify.yml's (description " (pr-str (:description st)) ", target " (pr-str (:target_url st)) ")")}
                :else {:check :status :state :ok :msg (str rl/status-context " is success on " remote " (" (:description st) ")")})))]
    [v t g s]))

(defn failed? [results] (boolean (some #(= :fail (:state %)) results)))

;; ---------------------------------------------------------------------------------------------------- gathering
(defn- remote-tag-sha
  "The commit the tag points at on GitHub (annotated tags peeled): a sha, nil if there is no such tag, or {::rl/error ..}."
  [repo tag]
  (let [r (rl/gh-json (str "repos/" repo "/git/ref/tags/" tag))]
    (cond
      (and (rl/error? r) (re-find #"404|Not Found" (::rl/error r))) nil
      (rl/error? r) r
      :else
      (let [{:keys [type sha]} (:object r)]
        (if (= type "tag")
          (let [a (rl/gh-json (str "repos/" repo "/git/tags/" sha))]
            (if (rl/error? a) a (get-in a [:object :sha])))
          sha)))))

(defn- release-of
  "The PUBLISHED release of the tag (the API's by-tag route does not return drafts), nil if there is none, or {::rl/error ..}."
  [repo tag]
  (let [r (rl/gh-json (str "repos/" repo "/releases/tags/" tag))]
    (cond
      (and (rl/error? r) (re-find #"404|Not Found" (::rl/error r))) nil
      :else r)))

(defn gather [{:keys [tag repo dry-run?]}]
  (let [v (rl/run "bb" "tools/version.clj" "--check" tag)
        remote (remote-tag-sha repo tag)
        sha? (string? remote)]
    {:tag tag :dry-run? dry-run?
     :version {:ok? (zero? (:exit v)) :out (str (:out v) (:err v))}
     :head (let [r (rl/git "rev-parse" "HEAD")] (when (zero? (:exit r)) (str/trim (:out r))))
     :local (rl/peel-sha tag)
     :remote remote
     :release (when sha? (release-of repo tag))
     :statuses (when sha? (rl/statuses-of repo remote))}))

(defn report! [results]
  (doseq [{:keys [check state msg]} results]
    (println (format "  %-4s %-8s %s" (case state :ok "ok" :fail "FAIL" :skip "SKIP") (name check) msg))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)
        sha "0123456789abcdef0123456789abcdef01234567" other "fedcba9876543210fedcba9876543210fedcba98"
        tag "v1.2.3"
        green {:context "emon/verified" :state "success" :description "verified: 12 gates since 89abcde" :target_url "https://github.com/3sln/emon/actions/runs/777"}
        base {:tag tag :version {:ok? true :out "ok: every manifest is 1.2.3"} :head sha :local sha :remote sha :release {:target_commitish sha :draft false} :statuses [green]}
        states #(mapv :state (evaluate %))
        state-of (fn [m check] (:state (first (filter #(= check (:check %)) (evaluate m)))))
        with-status #(assoc base :statuses [(merge green %)])]
    (t! "all four hold: ok ok ok ok" (= [:ok :ok :ok :ok] (states base)))
    (t! "a manifest that differs fails the version check" (= :fail (state-of (assoc base :version {:ok? false :out "version mismatch"}) :version)))
    (t! "a tag moved on GitHub (local differs) fails" (= :fail (state-of (assoc base :remote other :statuses nil) :tag)))
    (t! "a checkout that is not the tag's commit fails" (= :fail (state-of (assoc base :head other) :tag)))
    (t! "no tag at all fails when it is not a dry run" (= [:ok :fail :fail :fail] (states (assoc base :remote nil :local nil :release nil :statuses nil))))
    (t! "no tag at all is SKIP, SKIP, SKIP in a dry run (the version check still runs)" (= [:ok :skip :skip :skip] (states (assoc base :remote nil :local nil :release nil :statuses nil :dry-run? true))))
    (t! "a dry run does not excuse a tag that was moved" (= :fail (state-of (assoc base :head other :dry-run? true) :tag)))
    (t! "the release targets the tag's commit: ok" (= :ok (state-of base :target)))
    (t! "a release retargeted at a branch fails" (= :fail (state-of (assoc base :release {:target_commitish "main"}) :target)))
    (t! "a release retargeted at another commit fails" (= :fail (state-of (assoc base :release {:target_commitish other}) :target)))
    (t! "a tag with no release at all fails (a tag made by hand)" (= :fail (state-of (assoc base :release nil) :target)))
    (t! "a dry run skips the release check when there is no release yet" (= :skip (state-of (assoc base :release nil :dry-run? true) :target)))
    (t! "a dry run does not excuse a retargeted release" (= :fail (state-of (assoc base :release {:target_commitish "main"} :dry-run? true) :target)))
    (t! "a dry run does not excuse a red status" (= :fail (state-of (assoc base :dry-run? true :statuses [{:context "emon/verified" :state "failure" :description "x"}]) :status)))
    (t! "no status on the commit fails" (= :fail (state-of (assoc base :statuses []) :status)))
    (t! "a status of another context is ignored" (= :fail (state-of (assoc base :statuses [(assoc green :context "ci/other")]) :status)))
    (t! "the old context (emon/full-gates) is not accepted" (= :fail (state-of (assoc base :statuses [(assoc green :context "emon/full-gates")]) :status)))
    (t! "a pending status fails" (= :fail (state-of (with-status {:state "pending"}) :status)))
    (t! "an error status fails" (= :fail (state-of (with-status {:state "error"}) :status)))
    (t! "the NEWEST status wins: a failure after a success fails"
        (= :fail (state-of (assoc base :statuses [{:context "emon/verified" :state "failure" :description "later"} green]) :status)))
    (t! "the NEWEST status wins: a success after a failure passes"
        (= :ok (state-of (assoc base :statuses [green {:context "emon/verified" :state "failure" :description "earlier"}]) :status)))
    (t! "a success that is not verify.yml's (any other description) fails" (= :fail (state-of (with-status {:description "looks fine"}) :status)))
    (t! "a success with no workflow run as its target fails" (= :fail (state-of (with-status {:target_url "https://example.com/x"}) :status)))
    (t! "GitHub unreachable: FAIL normally" (= [:ok :fail :fail :fail] (states (assoc base :remote {::rl/error "no route"} :release nil :statuses nil))))
    (t! "GitHub unreachable: SKIP in a dry run" (= [:ok :skip :skip :skip] (states (assoc base :remote {::rl/error "no route"} :release nil :statuses nil :dry-run? true))))
    (t! "statuses unreadable: FAIL" (= :fail (state-of (assoc base :statuses {::rl/error "403"}) :status)))
    (t! "failed? sees a FAIL and ignores SKIP" (and (failed? [{:state :ok} {:state :fail}]) (not (failed? [{:state :ok} {:state :skip}]))))
    ;; the status walk the plan and the publish job share
    (let [shas ["c" "b" "a"]
          st {"c" [] "b" [{:context "emon/verified" :state "failure" :description "x"} (assoc green :description "verified: 3 gates since a")] "a" [green]}
          st2 (assoc st "b" [{:context "emon/verified" :state "failure" :description "x"}])
          now (.toEpochMilli (java.time.Instant/parse "2026-10-20T00:00:00Z"))
          arch (fn [v run at] {:context "emon/archives" :state "success" :description (rl/archives-description v run "0123456789") :created_at at})]
      (t! "find-verified: the newest ancestor whose NEWEST verified status is green" (= {:sha "a" :walked 3} (rl/find-verified shas st2)))
      (t! "find-verified: a failure newer than the success hides it" (= "a" (:sha (rl/find-verified shas (assoc st "b" [{:context "emon/verified" :state "failure" :description "x"} green])))))
      (t! "find-verified: the nearest is taken" (= {:sha "b" :walked 2} (rl/find-verified shas {"c" [] "b" [green] "a" [green]})))
      (t! "find-verified: none verified -> no base" (= {:walked 3} (rl/find-verified shas {"c" [] "b" [] "a" []})))
      (t! "find-verified: GitHub cannot be asked -> an error, not a guess" (:error (rl/find-verified shas (constantly {::rl/error "401"}))))
      (t! "find-verified: a hand-made green with another description is not a base" (= {:walked 1} (rl/find-verified ["a"] {"a" [(assoc green :description "ok")]})))
      (t! "archives description parses" (= {:version "1.2.3" :run "55"} (rl/parse-archives-description (rl/archives-description "1.2.3" "55" "0123456789"))))
      (t! "archives description fits 140 characters" (<= (count (rl/archives-description "100.200.300-rc.10" "12345678901" "0123456789")) 140))
      (t! "valid-archives: this version, young" (= {:version "1.2.3" :run "55"} (rl/valid-archives (arch "1.2.3" "55" "2026-10-10T00:00:00Z") "1.2.3" now)))
      (t! "valid-archives: another version is not valid" (nil? (rl/valid-archives (arch "1.2.2" "55" "2026-10-10T00:00:00Z") "1.2.3" now)))
      (t! "valid-archives: older than the margin of the 90-day retention is not valid" (nil? (rl/valid-archives (arch "1.2.3" "55" "2026-07-01T00:00:00Z") "1.2.3" now)))
      (t! "valid-archives: a failed status is not valid" (nil? (rl/valid-archives (assoc (arch "1.2.3" "55" "2026-10-10T00:00:00Z") :state "failure") "1.2.3" now)))
      (t! "find-archives: the nearest verified commit with valid archives for the version"
          (= {:sha "b" :run "9" :version "1.2.3" :walked 2}
             (rl/find-archives shas {"c" [(arch "1.2.3" "1" "2026-10-10T00:00:00Z")] "b" [(arch "1.2.3" "9" "2026-10-10T00:00:00Z") green] "a" [green (arch "1.2.3" "3" "2026-10-10T00:00:00Z")]} "1.2.3" now)))
      (t! "find-archives: an unverified commit's archives are not used"
          (= "a" (:sha (rl/find-archives shas {"c" [(arch "1.2.3" "1" "2026-10-10T00:00:00Z")] "b" [] "a" [green (arch "1.2.3" "3" "2026-10-10T00:00:00Z")]} "1.2.3" now))))
      (t! "find-archives: archives of another version are skipped on the way to older ones"
          (= "a" (:sha (rl/find-archives shas {"c" [] "b" [green (arch "1.2.2" "9" "2026-10-10T00:00:00Z")] "a" [green (arch "1.2.3" "3" "2026-10-10T00:00:00Z")]} "1.2.3" now))))
      (t! "find-archives: none valid -> none" (= {:walked 3} (rl/find-archives shas {"c" [] "b" [green] "a" [green]} "1.2.3" now))))
    (done!)))

;; ---------------------------------------------------------------------------------------------------- main
(defn -main [args]
  (let [opts (loop [as args o {}]
               (if-let [a (first as)]
                 (case a
                   "--tag" (recur (nnext as) (assoc o :tag (second as)))
                   "--repo" (recur (nnext as) (assoc o :repo (second as)))
                   "--dry-run" (recur (next as) (assoc o :dry-run? true))
                   (rl/die 2 "usage: bb release-verify --tag vX.Y.Z [--repo OWNER/REPO] [--dry-run] | --self-test"))
                 o))
        tag (:tag opts)]
    (when-not (and tag (rl/tag->version tag)) (rl/die 2 "bb release-verify: --tag vX.Y.Z is required (got" (pr-str tag) ")"))
    (let [repo (or (:repo opts) (rl/repo))
          facts (gather {:tag tag :repo repo :dry-run? (:dry-run? opts)})
          results (evaluate facts)]
      (println (str "release-verify " tag " in " repo (when (:dry-run? opts) " (dry run)")))
      (report! results)
      (cond
        (failed? results) (do (println "NOT verified: nothing may be published") (System/exit 1))
        (some #(= :skip (:state %)) results) (println "dry run: the checks above marked SKIP could not be made yet; none failed")
        :else (do (println "verified: the version, the tag, the release's target and the gates agree")
                  (rl/write-output! "sha" (:remote facts)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
