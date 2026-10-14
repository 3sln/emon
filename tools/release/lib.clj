;; Shared helpers of the release tools (P11.9, P11.10, D547): tools/release/{notes,verify,ops,publish}.clj. Process running, `gh api`, versions and tags.
;; Nothing here talks to a network by itself; every caller passes what it needs, and the pure parts are what the self-tests exercise.
(ns release.lib
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-repo "3sln/emon")
(def status-context "emon/full-gates")

(defn status-description
  "What prepare-release writes into the `emon/full-gates` status and release-verify requires (<= 140 characters, the API's limit)."
  [sha tag]
  (str "gated " sha " as " tag))

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
