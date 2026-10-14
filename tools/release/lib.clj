;; Shared helpers of the release tools (P11.9, P11.10, D547): tools/release/{notes,verify,ops,publish}.clj. Process running, `gh api`, versions and tags.
;; Nothing here talks to a network by itself; every caller passes what it needs, and the pure parts are what the self-tests exercise.
(ns release.lib
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-repo "3sln/emon")
;; D550: verify.yml posts these two on a commit of main. `emon/verified` says the gates passed (a description starting `verified:`, the target the run);
;; `emon/archives` says which run holds the release archives that are valid for the commit (`archives X.Y.Z: run ID (built at SHA)`).
(def status-context "emon/verified")
(def archives-context "emon/archives")
(def archives-max-age-days
  "Workflow artifacts are kept 90 days; archives older than this are not inherited (a margin for the time between a verification and a publish)."
  80)

(defn short-sha [sha] (when sha (subs sha 0 (min 7 (count sha)))))

(defn archives-description
  "<= 140 characters, the API's limit."
  [version run-id built-sha]
  (str "archives " version ": run " run-id " (built at " (short-sha built-sha) ")"))

(defn parse-archives-description
  "`archives 0.1.0: run 123 (built at abc1234)` -> {:version \"0.1.0\" :run \"123\"}, or nil."
  [d]
  (when-let [[_ v run] (re-matches #"archives (\S+): run (\d+)(?: .*)?" (str d))] {:version v :run run}))

(defn repo [](or (not-empty (System/getenv "GITHUB_REPOSITORY")) default-repo))

(defn die [code & msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit code))

(defn run
  "Run a command, capture it. Never throws: {:exit :out :err}. Options (first arg, a map): :dir, :in, :env (merged over the process env)."
  [opts & cmd]
  (let [[opts cmd] (if (map? opts) [opts cmd] [{} (cons opts cmd)])
        r (apply p/shell (merge {:out :string :err :string :continue true} (select-keys opts [:dir :in :extra-env])) (map str cmd))]
    (select-keys r [:exit :out :err])))

(defn run! "Like run, but a non-zero exit dies with the command's output." [opts & cmd]
  (let [[opts cmd] (if (map? opts) [opts cmd] [{} (cons opts cmd)])
        r (apply run opts cmd)]
    (when-not (zero? (:exit r))
      (die 1 (str "FAIL " (str/join " " cmd) "\n" (str/trim (str (:out r) (:err r))))))
    r))

(defn git [& args] (apply run "git" args))
(defn git! [& args] (apply run! "git" args))

(defn gh-json
  "`gh api PATH [args]` as parsed JSON (keyword keys), or {::error text} when gh fails or is absent."
  [path & args]
  (let [r (try (apply run "gh" "api" path args) (catch Exception e {:exit 127 :err (ex-message e)}))]
    (if (zero? (:exit r))
      (let [o (str/trim (:out r))] (if (str/blank? o) {} (json/parse-string o true)))
      {::error (str/trim (str (:err r) (:out r)))})))

(defn error? [x] (and (map? x) (contains? x ::error)))

;; ---------------------------------------------------------------------------------------------------- commit statuses (D550)
(defn statuses-of
  "The commit statuses of a sha, newest first (the API's order), or {::error text}."
  [repo sha]
  (gh-json (str "repos/" repo "/commits/" sha "/statuses?per_page=100")))

(defn newest
  "The newest status of `statuses` (newest first) whose context is `ctx`, or nil."
  [ctx statuses]
  (when (sequential? statuses) (first (filter #(= ctx (:context %)) statuses))))

(defn run-url? [url] (boolean (re-find #"/actions/runs/\d+" (str url))))

(defn verified-ok?
  "Is this `emon/verified` status a green one that verify.yml wrote: success, a description that starts `verified:` and the URL of a workflow run?
   (A status made by hand with `gh api` would have to copy all three; the point is not to be unforgeable but not to be satisfied by accident.)"
  [st]
  (boolean (and st (= "success" (:state st)) (str/starts-with? (str (:description st)) "verified:") (run-url? (:target_url st)))))

(defn- instant-ms [s] (try (.toEpochMilli (java.time.Instant/parse (str s))) (catch Exception _ nil)))

(defn valid-archives
  "The archives an `emon/archives` status vouches for, {:version :run}, if it is a success for exactly this version, names a run and is younger than the artifacts' retention
   (archives-max-age-days). Otherwise nil."
  [st version now-ms]
  (when (and st (= "success" (:state st)))
    (let [{v :version :as a} (parse-archives-description (:description st))
          made (instant-ms (:created_at st))]
      (when (and a (= v version)
                 (or (nil? made) (< (- now-ms made) (* archives-max-age-days 24 3600 1000))))
        a))))

(defn find-verified
  "The first of `shas` (newest first) whose newest `emon/verified` status is green, asking `lookup` (sha -> statuses). {:sha s :walked n}, {:walked n} when none,
   or {:error text :walked n} when GitHub could not be asked (the caller then verifies everything: never less)."
  [shas lookup]
  (loop [[sha & more] shas n 0]
    (if-not sha
      {:walked n}
      (let [sts (lookup sha)]
        (cond
          (error? sts) {:error (::error sts) :walked (inc n)}
          (verified-ok? (newest status-context sts)) {:sha sha :walked (inc n)}
          :else (recur more (inc n)))))))

(defn find-archives
  "The first of `shas` (newest first) that is verified AND has valid archives for `version`: {:sha :run :version :walked}, {:walked n} when none, {:error ..} as above."
  [shas lookup version now-ms]
  (loop [[sha & more] shas n 0]
    (if-not sha
      {:walked n}
      (let [sts (lookup sha)]
        (cond
          (error? sts) {:error (::error sts) :walked (inc n)}
          (and (verified-ok? (newest status-context sts)) (valid-archives (newest archives-context sts) version now-ms))
          (assoc (valid-archives (newest archives-context sts) version now-ms) :sha sha :walked (inc n))
          :else (recur more (inc n)))))))

(defn first-parent-shas
  "`git rev-list --first-parent -n N REF`, newest first (REF itself is first)."
  [ref n]
  (let [r (git "rev-list" "--first-parent" "-n" (str n) ref)]
    (if (zero? (:exit r)) (vec (remove str/blank? (str/split-lines (:out r)))) [])))

;; ---------------------------------------------------------------------------------------------------- versions and tags
(def semver-re #"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?")

(defn tag->version "v1.2.3 -> 1.2.3 (the leading v is optional); nil when it is not a version." [tag]
  (let [v (str/replace (str tag) #"^v" "")] (when (re-matches semver-re v) v)))

(defn parse-core [v]
  (when-let [[_ a b c] (re-matches #"(\d+)\.(\d+)\.(\d+)(?:-.*)?" (str v))] (mapv parse-long [a b c])))

(defn newer?
  "Is version a strictly newer than b (numeric X.Y.Z; a pre-release suffix sorts before the release of the same core)?"
  [a b]
  (let [ca (parse-core a) cb (parse-core b)
        pre? #(boolean (re-find #"-" (str %)))]
    (cond (pos? (compare ca cb)) true
          (neg? (compare ca cb)) false
          :else (and (not (pre? a)) (pre? b)))))

(defn peel-sha
  "The commit a tag name resolves to in the LOCAL repository (annotated tags peeled), or nil."
  [tag]
  (let [r (git "rev-parse" "--verify" "--quiet" (str "refs/tags/" tag "^{commit}"))]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn head-sha [] (str/trim (:out (git! "rev-parse" "HEAD"))))

(defn write-output!
  "Append key=value to $GITHUB_OUTPUT when running in Actions (always prints it too)."
  [k v]
  (println (str k "=" v))
  (when-let [f (not-empty (System/getenv "GITHUB_OUTPUT"))]
    (spit f (str k "=" v "\n") :append true)))

(defn http-status
  "The HTTP status of a GET of url (no body read), or nil on a network failure."
  [url]
  (try (:status ((requiring-resolve 'babashka.http-client/get) url {:throw false :headers {"User-Agent" "emon-release-tools (https://github.com/3sln/emon)"} :timeout 30000}))
       (catch Exception _ nil)))

(defn selftest-runner
  "Returns [t! done!]: t! records a labelled check, done! prints the verdict and exits 1 on any failure."
  []
  (let [fails (atom [])]
    [(fn [label ok] (when-not ok (swap! fails conj label)) (println (if ok "  ok  " "  FAIL") label))
     (fn [] (if (seq @fails)
              (do (println "self-test FAILED:" (str/join ", " @fails)) (System/exit 1))
              (println "self-test ok")))]))
