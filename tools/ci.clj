#!/usr/bin/env bb
;; CI helpers for the manually triggered gates workflow (D266). NOT a gate.
;;   bb ci coverage                       every gate of kin/scripts/gates is in a workflow job, and bb.edn has every task the workflow calls; and every replay gate has a re-derivation (`bb rederive --check`, D1200)
;;   bb ci laws-record ARM LOG DIR        write DIR/laws-ARM.txt: the law count `bb unit ARM` printed in LOG
;;   bb ci laws-compare DIR               every arm's count in DIR/**/laws-*.txt is the same, and all four arms are there
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clj-yaml.core :as yaml]
         '[clojure.string :as str])

(def wf (str ".git" "hub/workflows/gates.yml"))
(def arms #{"go" "rust" "java" "csharp"})

(defn die [& m] (println "  FAIL" (apply str m)) (System/exit 1))

(defn script-gates []
  (let [s (slurp "kin/scripts/gates")]
    (set (str/split (second (re-find #"(?m)^gates=\"([^\"]*)\"" s)) #"\s+"))))

(defn bb-tasks []
  (set (map second (re-seq #"(?m)^[ {]*([a-z][a-z-]*) *\{:doc" (slurp "bb.edn")))))

(defn coverage []
  (let [y (yaml/parse-string (slurp wf))
        jobs (vals (:jobs y))
        shard-gates (set (for [j jobs
                               inc (get-in j [:strategy :matrix :include])
                               g (str/split (str (:gates inc)) #"\s+")
                               :when (seq g)]
                           g))
        unit-arms (set (for [j jobs, a (get-in j [:strategy :matrix :arm])] a))
        covered (cond-> shard-gates (= arms unit-arms) (conj "unit"))
        missing (remove covered (script-gates))
        extra (remove (script-gates) shard-gates)
        called (set (map second (re-seq #"\bbb ([a-z][a-z-]*)" (str/replace (slurp wf) #"(?m)^\s*#.*$" ""))))
        unknown (remove (bb-tasks) called)]
    (when (seq missing) (die "gates in no workflow job: " (str/join " " missing)))
    (when (seq extra) (die "workflow runs non-gates as gates: " (str/join " " extra)))
    (when (seq unknown) (die "workflow calls bb tasks that do not exist: " (str/join " " unknown)))
    (println "  ok   every gate is in a gates.yml job (" (count covered) "covered) and every bb task it calls exists")
    ;; D1200: and every replay gate has a re-derivation in `bb rederive`
    (let [r (shell {:continue true} "bb" "tools/rederive.clj" "--check")]
      (when-not (zero? (:exit r)) (System/exit 1)))))

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
    "laws-record" (apply laws-record args)
    "laws-compare" (apply laws-compare args)
    (die "usage: bb ci coverage | laws-record ARM LOG DIR | laws-compare DIR")))
