#!/usr/bin/env bb
;; The publish job's re-verification (P11.10, D524, D1500): nothing is published unless all three hold.
;;
;;   bb release-verify --tag vX.Y.Z [--repo OWNER/REPO] [--dry-run]
;;   bb release-verify --self-test
;;
;;   1. version  `bb version --check TAG`: every manifest of the CHECKED-OUT tree equals the tag.
;;   2. tag      the tag exists on GitHub and still points at the commit that is checked out (it was not moved after the gates ran).
;;   3. status   the NEWEST `emon/full-gates` commit status on that commit is `success`, and its description is the one prepare-release writes,
;;               `gated <sha> as <tag>`, naming this commit and this tag (a hand-made status does not match).
;; Exit 0 when all hold, 1 when one fails, 2 on usage. On success the verified commit is printed as `sha=...` and appended to $GITHUB_OUTPUT (in Actions),
;; and the publish jobs check THAT out, so a tag moved AFTER this step cannot change what is published.
;; --dry-run (local use): a tag that does not exist yet, or GitHub being unreachable, is reported as SKIP, not as a failure; the version check is real.
;; Reads GH_TOKEN / the gh login for the status; talks to nothing else; writes nothing.
(require '[clojure.string :as str]
         '[release.lib :as rl])

;; ---------------------------------------------------------------------------------------------------- the pure decision
(def status-description rl/status-description)

(defn newest-gate-status
  "The newest status of `statuses` (GitHub lists them newest first) whose context is emon/full-gates, or nil."
  [statuses]
  (first (filter #(= rl/status-context (:context %)) statuses)))

(defn evaluate
  "Decide. m: {:tag :dry-run? :version {:ok? :out} :head :local :remote :statuses}
   :remote and :statuses are the GitHub answers: a sha string / a seq of statuses, nil when the tag does not exist, or {::rl/error text} when the call failed.
   Returns [{:check :version|:tag|:status, :state :ok|:fail|:skip, :msg}]."
  [{:keys [tag dry-run? version head local remote statuses]}]
  (let [unreachable? (rl/error? remote)
        no-tag? (and (not unreachable?) (nil? remote))
        skip-or-fail (fn [check msg] {:check check :state (if dry-run? :skip :fail) :msg msg})
        v {:check :version
           :state (if (:ok? version) :ok :fail)
           :msg (if (:ok? version) (str/trim (:out version)) (str "the checked-out tree is not " tag ":\n" (str/trim (:out version))))}
        t (cond
            unreachable? (skip-or-fail :tag (str "cannot ask GitHub about the tag: " (::rl/error remote)))
            no-tag? (skip-or-fail :tag (str "no tag " tag " exists" (when dry-run? " (expected before prepare-release has run)")))
            (and local (not= local remote)) {:check :tag :state :fail :msg (str "tag " tag " is " remote " on GitHub but " local " locally: it was moved (or the clone is stale)")}
            (not= head remote) {:check :tag :state :fail :msg (str "tag " tag " is " remote " but the checked-out commit is " head)}
            :else {:check :tag :state :ok :msg (str tag " is " remote ", the checked-out commit")})
        s (cond
            (or unreachable? no-tag?) (skip-or-fail :status (str "no commit to read the status of"))
            (rl/error? statuses) (skip-or-fail :status (str "cannot read the commit statuses: " (::rl/error statuses)))
            :else
            (let [st (newest-gate-status statuses)
                  want (status-description remote tag)]
              (cond
                (nil? st) {:check :status :state :fail :msg (str "no " rl/status-context " status on " remote ": the full gates have not run on this commit")}
                (not= "success" (:state st)) {:check :status :state :fail :msg (str rl/status-context " on " remote " is " (:state st) " (" (:description st) ")")}
                (not= want (:description st)) {:check :status :state :fail :msg (str rl/status-context " is success but its description is " (pr-str (:description st)) ", not " (pr-str want))}
                :else {:check :status :state :ok :msg (str rl/status-context " is success on " remote " (" (:description st) ")")})))]
    [v t s]))

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

(defn gather [{:keys [tag repo dry-run?]}]
  (let [v (rl/run "bb" "tools/version.clj" "--check" tag)
        remote (remote-tag-sha repo tag)
        sha? (string? remote)]
    {:tag tag :dry-run? dry-run?
     :version {:ok? (zero? (:exit v)) :out (str (:out v) (:err v))}
     :head (let [r (rl/git "rev-parse" "HEAD")] (when (zero? (:exit r)) (str/trim (:out r))))
     :local (rl/peel-sha tag)
     :remote remote
     :statuses (when sha? (let [r (rl/gh-json (str "repos/" repo "/commits/" remote "/statuses?per_page=100"))] r))}))

(defn report! [results]
  (doseq [{:keys [check state msg]} results]
    (println (format "  %-4s %-8s %s" (case state :ok "ok" :fail "FAIL" :skip "SKIP") (name check) msg))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)
        sha "0123456789abcdef0123456789abcdef01234567" other "fedcba9876543210fedcba9876543210fedcba98"
        tag "v1.2.3"
        good-st [{:context "emon/full-gates" :state "success" :description (status-description sha tag)}]
        base {:tag tag :version {:ok? true :out "ok: every manifest is 1.2.3"} :head sha :local sha :remote sha :statuses good-st}
        states #(mapv :state (evaluate %))
        state-of (fn [m check] (:state (first (filter #(= check (:check %)) (evaluate m)))))]
    (t! "all three hold: ok ok ok" (= [:ok :ok :ok] (states base)))
    (t! "a manifest that differs fails the version check" (= :fail (state-of (assoc base :version {:ok? false :out "version mismatch"}) :version)))
    (t! "a tag moved on GitHub (local differs) fails" (= :fail (state-of (assoc base :remote other :statuses nil) :tag)))
    (t! "a checkout that is not the tag's commit fails" (= :fail (state-of (assoc base :head other) :tag)))
    (t! "no tag at all fails when it is not a dry run" (= [:ok :fail :fail] (states (assoc base :remote nil :local nil :statuses nil))))
    (t! "no tag at all is SKIP, SKIP in a dry run (the version check still runs)" (= [:ok :skip :skip] (states (assoc base :remote nil :local nil :statuses nil :dry-run? true))))
    (t! "a dry run does not excuse a tag that was moved" (= :fail (state-of (assoc base :head other :dry-run? true) :tag)))
    (t! "a dry run does not excuse a red status" (= :fail (state-of (assoc base :dry-run? true :statuses [{:context "emon/full-gates" :state "failure" :description "x"}]) :status)))
    (t! "no status on the commit fails" (= :fail (state-of (assoc base :statuses []) :status)))
    (t! "a status of another context is ignored" (= :fail (state-of (assoc base :statuses [{:context "ci/other" :state "success" :description (status-description sha tag)}]) :status)))
    (t! "a pending status fails" (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "pending" :description (status-description sha tag)}]) :status)))
    (t! "an error status fails" (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "error" :description "x"}]) :status)))
    (t! "the NEWEST status wins: a failure after a success fails"
        (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "failure" :description "later"} (first good-st)]) :status)))
    (t! "the NEWEST status wins: a success after a failure passes"
        (= :ok (state-of (assoc base :statuses [(first good-st) {:context "emon/full-gates" :state "failure" :description "earlier"}]) :status)))
    (t! "a success whose description names another commit fails"
        (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "success" :description (status-description other tag)}]) :status)))
    (t! "a success whose description names another tag fails"
        (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "success" :description (status-description sha "v9.9.9")}]) :status)))
    (t! "a hand-made success (any other description) fails"
        (= :fail (state-of (assoc base :statuses [{:context "emon/full-gates" :state "success" :description "looks fine"}]) :status)))
    (t! "GitHub unreachable: FAIL normally" (= [:ok :fail :fail] (states (assoc base :remote {::rl/error "no route"} :statuses nil))))
    (t! "GitHub unreachable: SKIP in a dry run" (= [:ok :skip :skip] (states (assoc base :remote {::rl/error "no route"} :statuses nil :dry-run? true))))
    (t! "statuses unreadable: FAIL" (= :fail (state-of (assoc base :statuses {::rl/error "403"}) :status)))
    (t! "failed? sees a FAIL and ignores SKIP" (and (failed? [{:state :ok} {:state :fail}]) (not (failed? [{:state :ok} {:state :skip}]))))
    (t! "the description fits the API's 140 characters" (<= (count (status-description sha "v100.200.300-rc.10")) 140))
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
        :else (do (println "verified: the version, the tag and the gates agree")
                  (rl/write-output! "sha" (:remote facts)))))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
