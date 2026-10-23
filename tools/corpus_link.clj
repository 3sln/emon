;; Keep the corpus on another volume: `corpus/` becomes a symlink to the directory named by the
;; local, untracked `local.edn` (see local.edn.example).
;;
;;   bb corpus-link [--dir PATH]     create or update the symlink  corpus -> PATH  (PATH, else :corpus-dir of local.edn)
;;   bb corpus-link --check          does `corpus` resolve, is the volume mounted, is the corpus index consistent
;;   bb corpus-guard                 the fail-fast every corpus-reading gate depends on (bb.edn :depends): silent
;;                                   when `corpus` is absent, a real directory or a live symlink; exit 1 with a
;;                                   readable message when it is a DANGLING symlink (the drive is unplugged)
;;
;; This tool never moves, copies or deletes corpus data. A real `corpus/` directory is refused, with the steps.
;; Pure babashka. See CONTRIBUTING.md, "Corpus on an external drive".
(ns corpus-link
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [corpus :as c]
            [babashka.process :as process]))

(def link-path (str (fs/path c/repo-root "corpus")))

(defn- die [& msg] (binding [*out* *err*] (println "corpus-link:" (apply str msg))) (System/exit 1))
(defn- say [& msg] (println (apply str msg)))

(defn- link-target
  "The raw target of the `corpus` symlink as a string, or nil when it is not a symlink."
  []
  (when (fs/sym-link? link-path) (str (java.nio.file.Files/readSymbolicLink (fs/path link-path)))))

(defn- volume-of
  "-> [mount-point name] for a /Volumes/NAME/... path (macOS), else nil."
  [p]
  (when-let [[_ n] (re-matches #"/Volumes/([^/]+)(?:/.*)?" (str p))]
    [(str "/Volumes/" n) n]))

(defn- why-missing
  "A sentence for a path that does not exist: the unmounted volume if it is one, else the first missing ancestor."
  [p]
  (let [[mp n] (volume-of p)]
    (if (and mp (not (fs/exists? mp)))
      (str "the volume \"" n "\" is not mounted (" mp " does not exist): plug the drive in, or wait for it to mount")
      (let [first-missing (->> (iterate fs/parent (fs/absolutize p))
                               (take-while some?)
                               (take-while #(not (fs/exists? %)))
                               last)]
        (str (or first-missing p) " does not exist")))))

(defn- dangling-message []
  (let [t (link-target)]
    (str "corpus/ is a symlink to a path that is not there:\n"
         "    corpus -> " t "\n"
         "  " (why-missing (if (fs/absolute? t) t (fs/path c/repo-root t))) ".\n"
         "  Plug the external drive in and retry (`bb corpus-link --check` says what is wrong). To work without\n"
         "  the corpus instead, remove the link:  rm corpus   (it removes only the link, never the data).")))

(defn dangling? [] (and (fs/sym-link? link-path) (not (fs/exists? link-path))))

;; ---------------------------------------------------------------- guard

(defn guard! []
  (when (dangling?)
    (binding [*out* *err*] (println (str "  FAIL " (dangling-message))))
    (System/exit 1)))

;; ---------------------------------------------------------------- config

(defn- expand-home [p] (if (str/starts-with? p "~/") (str (System/getProperty "user.home") (subs p 1)) p))

(defn- configured-dir [opt]
  (let [d (or opt (:corpus-dir (c/local-config)))]
    (when (seq d) (str (fs/absolutize (expand-home d))))))

;; ---------------------------------------------------------------- link

(defn link! [opt]
  (let [dir (or (configured-dir opt)
                (die "no directory given: pass --dir PATH, or set :corpus-dir in local.edn (copy local.edn.example)"))]
    (cond
      (and (fs/exists? link-path) (not (fs/sym-link? link-path)) (fs/directory? link-path))
      (die "corpus/ is a real directory, with data in it; refusing to touch it.\n"
           "  Moving 12 GB is yours to do, in this order:\n"
           "    1. copy it:    rsync -a corpus/ \"" dir "/\"   (cp -R works too; the target must be a mounted, writable volume)\n"
           "    2. verify it:  rsync -anc --delete corpus/ \"" dir "/\"   (prints nothing when the copy is complete)\n"
           "    3. remove the local copy:  mv corpus corpus.old   (then, when all is well, rm -rf corpus.old)\n"
           "    4. link:       bb corpus-link")

      (and (fs/exists? link-path) (not (fs/sym-link? link-path)))
      (die "corpus is a file, not a directory or a symlink; move it away first")

      (not (fs/exists? dir))
      (die "the corpus directory " dir " does not exist: " (why-missing dir))

      (not (fs/directory? dir))
      (die dir " is not a directory")

      :else
      (let [old (link-target)]
        (when (fs/sym-link? link-path) (fs/delete link-path))   ; removes only the link
        (fs/create-sym-link link-path dir)
        (say "  ok   corpus -> " dir (cond (nil? old) "  (created)" (= old dir) "  (already so)" :else (str "  (updated, was " old ")")))
        (say "       check it with:  bb corpus-link --check")))))

;; ---------------------------------------------------------------- check

(defn- state-problems
  "Cheap, local: the pull state names categories; each must be a directory in the corpus. (Only the top level is listed.)"
  [dir]
  (let [st (c/read-state dir)
        missing (remove #(or (= % c/root-name) (fs/directory? (fs/path dir %))) (keys st))
        on-disk (set (c/categories dir))
        untracked (remove (set (keys st)) on-disk)]
    {:state st :missing (vec (sort missing)) :untracked (vec (sort untracked)) :n (count on-disk)}))

(defn- remote-index
  "One small GET of index.json (what `bb corpus-index-sha` hashes), or a keyword saying why not."
  []
  (try
    (with-redefs [c/die (fn [& m] (throw (ex-info (apply str m) {:cfg true})))]
      (let [cfg (c/load-config)
            idx (c/read-index cfg)]
        (or idx :no-index)))
    (catch clojure.lang.ExceptionInfo e (if (:cfg (ex-data e)) :no-config :unreachable))
    (catch Exception _ :unreachable)))

(defn check! [opt]
  (let [bad (atom 0)
        ok (fn [& m] (say "  ok   " (apply str m)))
        fail (fn [& m] (swap! bad inc) (say "  FAIL " (apply str m)))
        note (fn [& m] (say "  note " (apply str m)))
        want (configured-dir opt)]
    (cond
      (dangling?) (fail (dangling-message))
      (not (fs/exists? link-path))
      (fail "corpus/ does not exist"
            (if want (str "; link it with:  bb corpus-link" (when opt (str " --dir " opt))) "; pull it with bb corpus-pull, or link it (bb corpus-link)"))
      :else
      (let [real (str (fs/real-path link-path))
            t (link-target)]
        (if t
          (ok "corpus -> " t (when (not= t real) (str "  (resolves to " real ")")))
          (ok "corpus/ is a real directory (not linked)"))
        (when-let [[mp n] (volume-of real)]
          (if (fs/exists? mp) (ok "volume \"" n "\" is mounted") (fail "volume \"" n "\" is not mounted")))
        (when (and want t (not= (str (fs/real-path want)) real))
          (fail "corpus points to " real " but local.edn says " want "; run bb corpus-link to update it"))
        (when (and want (not t))
          (note "local.edn names " want " but corpus/ is a real directory: bb corpus-link explains how to move it"))
        (if-not (fs/directory? link-path)
          (fail "corpus does not resolve to a directory")
          (let [{:keys [state missing untracked n]} (state-problems link-path)]
            (cond
              (empty? state) (note "no .emon-corpus-state.json in the corpus: nothing was pulled here, so the index is not comparable (n=" n " categories)")
              (seq missing) (fail "the pull state names categories that are not in the corpus: " (str/join ", " missing) "  (run bb corpus-pull)")
              :else (ok "every pulled category is present (" (count state) " of " n " directories)"))
            (when (seq untracked)
              (note "not from a pull (local or new): " (str/join ", " untracked)))
            (when (seq state)
              (let [idx (remote-index)]
                (cond
                  (= idx :no-config) (note "remote index not compared: no S3 settings (.env, EMON_S3_*)")
                  (= idx :no-index) (note "remote index not compared: nothing pushed to the bucket yet")
                  (= idx :unreachable) (note "remote index not compared: the store is unreachable")
                  :else
                  (let [behind (for [a (get idx "archives") :let [nm (get a "name") s (get-in state [nm "sha256"])]
                                     :when (not= s (get a "sha256"))] nm)]
                    (if (seq behind)
                      (note (count behind) " archive(s) differ from the remote index: " (str/join ", " behind) "  (bb corpus-pull)")
                      (ok "the corpus matches the remote index (" (count (get idx "archives")) " archives)")))))))))
      )
    (if (pos? @bad)
      (do (say "corpus-link --check: " @bad " problem(s)") (System/exit 1))
      (say "corpus-link --check: ok"))))

;; ---------------------------------------------------------------- CLI

(defn -main [& args]
  (let [args (vec args)
        _ (when (some #{"--guard"} args)
            (guard!)
            (when-let [task (second (drop-while #(not= % "--task") args))]
              (apply process/shell "python3" "tools/shards/corpus.py" "require" task
                     (drop 2 (drop-while #(not= % "--task") args))))
            (System/exit 0))
        opt (second (drop-while #(not= % "--dir") args))]
    (when (and (some #{"--dir"} args) (not (seq opt))) (die "--dir needs a path"))
    (doseq [a args :when (and (str/starts-with? a "--") (not (#{"--check" "--guard" "--dir"} a)))]
      (die "unknown option " a "   usage: bb corpus-link [--check] [--dir PATH]"))
    (cond (some #{"--guard"} args) (guard!)
          (some #{"--check"} args) (check! opt)
          :else (link! opt))))

(when (= (System/getProperty "babashka.file") *file*)
  (apply -main *command-line-args*))
