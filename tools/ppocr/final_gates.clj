#!/usr/bin/env bb
;; Serial option-off verification; isolate the review index from the real index.
(require '[babashka.fs :as fs] '[babashka.process :refer [process shell]]
         '[cheshire.core :as json] '[clojure.string :as str])
(import '[java.security MessageDigest])
(def args *command-line-args*)
(assert (and (= "--out" (first args))
             (or (= 2 (count args)) (and (= 4 (count args)) (= "--only" (nth args 2)))))
        "usage: final_gates.clj --out DIR [--only comma-separated-command-labels]")
(def only-labels (when (= 4 (count args)) (set (str/split (nth args 3) #","))))
(def out (str (fs/absolutize (second args))))
(fs/create-dirs out)
(def root (str (fs/canonicalize ".")))
(def work (str (fs/absolutize ".cache/op1b-git-check")))
(def git (str (fs/which "git")))
(def bb (str (fs/which "bb")))
(defn capture [& argv] (str/trim (:out @(process (vec argv) {:out :string :err :string :shutdown nil}))))
(def common (str (fs/canonicalize (capture git "rev-parse" "--git-common-dir"))))
(def index (capture git "rev-parse" "--git-path" "index"))
(defn sha [p] (when (fs/exists? p) (vec (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes p)))))
(def before (sha index))
(fs/create-dirs (str work "/objects"))
(fs/create-dirs (str work "/bin"))
(fs/create-dirs ".cache/op1b-tmp")
(def view {"GIT_INDEX_FILE" (str work "/index") "GIT_OBJECT_DIRECTORY" (str work "/objects")
           "GIT_ALTERNATE_OBJECT_DIRECTORIES" (str common "/objects")})
(doseq [command [[git "-c" "core.fsmonitor=false" "read-tree" "HEAD"] [git "-c" "core.fsmonitor=false" "add" "-A"]]]
  (apply shell {:extra-env view :shutdown nil} command))
;; Probe the effective top-level, respecting Git's global options and nested
;; self-test repositories; never impose the review index on their own indexes.
(def wrapper (str work "/bin/git"))
(spit wrapper
      (str "#!" bb "\n"
           "(require '[babashka.process :refer [process]] '[clojure.string :as str])\n"
           "(def argv (vec *command-line-args*))\n"
           "(def prefix (loop [i 0] (if (and (< i (count argv)) (str/starts-with? (argv i) \"-\"))"
           " (recur (+ i (if (contains? #{\"-C\" \"-c\" \"--git-dir\" \"--work-tree\" \"--namespace\" \"--config-env\"} (argv i)) 2 1))) (subvec argv 0 i))))\n"
           "(def probe @(process (into [" (pr-str git) "] (concat prefix [\"rev-parse\" \"--show-toplevel\"])) {:out :string :err :string :shutdown nil}))\n"
           "(def env (if (and (= 0 (:exit probe)) (= " (pr-str root) " (str/trim (:out probe)))"
           " (not (System/getenv \"GIT_INDEX_FILE\"))) " (pr-str view) " {}))\n"
           "(System/exit (:exit @(process (into [" (pr-str git) "] argv) {:extra-env env :inherit true :shutdown nil})))\n"))
(fs/set-posix-file-permissions wrapper "rwxr-xr-x")
(def bb-wrapper (str work "/bin/bb"))
(spit bb-wrapper (str "#!" bb "\n(require '[babashka.process :refer [process]])\n"
                     "(System/exit (:exit @(process (into [" (pr-str bb) " "
                     (pr-str (str "-Djava.io.tmpdir=" (fs/absolutize ".cache/op1b-tmp")))
                     "] *command-line-args*) {:inherit true :shutdown nil})))\n"))
(fs/set-posix-file-permissions bb-wrapper "rwxr-xr-x")
(fs/create-dirs ".cache/op1b-xdg")
(def env {"EMON_CS_SHARED_COMPILATION" "false" "EMON_RS_PPOCR" "0" "PATH" (str work "/bin:" (System/getenv "PATH"))
          "TMPDIR" (str (fs/absolutize ".cache/op1b-tmp"))
          "npm_config_cache" (str (fs/absolutize ".cache/op1b-npm"))
          "XDG_CACHE_HOME" (str (fs/absolutize ".cache/op1b-xdg"))
          "NuGetAudit" "false"})
(def all-commands [["gates" "drift"] ["ci" "core"] ["rederive" "--check"] ["rederive"] ["accuracy"] ["samples"]
               ["identity"] ["repeat"] ["plan"] ["ocr"] ["render"] ["pdfium-wasm"] ["align"] ["fusion"] ["gfuse"]
               ["artifacts"] ["seals"] ["sealpx"] ["sigs"] ["retarget"] ["asset"] ["embedders"] ["unit"] ["package" "--check"]])
(def commands (if only-labels (filterv #(contains? only-labels (str/join "-" %)) all-commands) all-commands))
(assert (or (nil? only-labels) (= only-labels (set (map #(str/join "-" %) commands)))) "unknown command label in --only")
(def rows (atom []))
(try
  (doseq [command commands]
    (assert (> (.getUsableSpace (java.io.File. "/System/Volumes/Data")) (* 5 1024 1024 1024)) "internal disk below 5 GiB")
    (let [label (str/join "-" command) start (System/nanoTime) log (str out "/" label ".log")
          unit-source (when (= command ["unit"]) (System/getenv "EMON_UNIT_RESTORE_SOURCES"))
          run-env (if unit-source (assoc env "RestoreSources" unit-source) env)
          result @(process (into [bb (str "-Djava.io.tmpdir=" (env "TMPDIR"))] command)
                           {:extra-env run-env :out log :err :out :shutdown nil})
          same (if (= command ["rederive"])
                 (= 0 (:exit @(process [git "diff" "--exit-code"] {:extra-env view :out (str out "/rederive.diff") :err :out :shutdown nil}))) true)
          row {:command command :exit (:exit result) :seconds (/ (- (System/nanoTime) start) 1e9) :log log :no-worktree-diff same}]
      (swap! rows conj row) (spit (str out "/summary.json") (json/generate-string @rows {:pretty true}))
      (println label (:exit result))
      (assert (and (zero? (:exit result)) same) (str "gate failed: " label))))
  (finally (assert (= before (sha index)) "real index changed")
           (spit (str out "/index-check.json") (json/generate-string {:real-index-unchanged true}))))
