#!/usr/bin/env bb
;; `bb fuzz`: mutate the PDF fixtures and run every mutant through the hosts.
;; (DEVLOG P2.12, D106.)
;;
;;     bb fuzz                          the SMOKE subset: seed 1, 300 cases, Rust and Go (a gate)
;;     bb fuzz --long N [--seed S]      N cases, every host present, no gate
;;     bb fuzz --seed S --cases N       any other size
;;     bb fuzz --seed S --only I --dump F     write case I of seed S to F (a reproducer)
;;     bb fuzz --replay FILE...         run named PDFs through the hosts, no mutation
;;     options: --hosts rust,go,java,csharp|all  --chunk 512  --timeout-ms 10000
;;              --no-minimize  --crashers DIR
;;
;; WHAT RUNS. `emon.fuzzprobe` (kin/fuzzprobe.kin) is the same kin unit on every
;; host: open, list the pages, decode every stream the cross-reference names, load
;; every dictionary as a font and map every code. It answers one line per file. A
;; per-host DRIVER (test/fuzz/<host>/) reads file names on stdin and prints
;; `name TAB in-memory-line TAB async-line`, flushing after each, where the async
;; line is the same pass through an async source of `--chunk` bytes that misses and
;; is re-run until it does not (D058). This script is the harness: it feeds each
;; host a batch, reads the lines back under a per-case timeout, and judges:
;;
;;   panic      a host threw (Rust panic, Go panic, Java Throwable, C# Exception)
;;   hang       no answer within the timeout: the host is killed, the batch resumes
;;   crash      the host process died (a stack overflow is not a panic in Rust or Go)
;;   mismatch   two hosts gave different lines: `status-mismatch` when the `pd-status`
;;              name differs, `detail-mismatch` when only the rest does
;;   async      a host's async line differs from its own in-memory line: a miss that
;;              surfaced as an answer (D058/D075)
;;
;; A FAILING MUTANT is minimised (chunks removed while the same verdict holds) and
;; saved under test/fuzz/crashers/ with its seed and index (`NAME.pdf` and `NAME.edn`).
;; Every crasher is replayed on every run: one whose `.edn` says `:status :fixed`
;; must pass (a regression corpus: a fixed bug stays fixed); one that says `:open`
;; is a known failure, reported and not gating. `bb fuzz --selftest` checks the
;; harness itself against a fake host (tools/fuzz/fakehost.clj) that hangs, crashes,
;; panics and disagrees on demand; the smoke run starts with it.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[fuzz.mutate :as m])
(import '(java.util.concurrent LinkedBlockingQueue TimeUnit))

(def root (str (fs/canonicalize ".")))

;; ---------------------------------------------------------------- options

(defn parse-args [args]
  (loop [a args o {:seed 1 :cases 300 :hosts "rust,go" :chunk 512 :timeout-ms 10000
                   :minimize true :crashers "test/fuzz/crashers" :open "test/fuzz/open"
                   :replay []}]
    (if (empty? a)
      o
      (let [[k & more] a]
        (case k
          "--seed" (recur (rest more) (assoc o :seed (parse-long (first more))))
          "--cases" (recur (rest more) (assoc o :cases (parse-long (first more))))
          "--long" (recur (rest more) (assoc o :cases (parse-long (first more)) :long true
                                             :hosts (if (:hosts-set o) (:hosts o) "all")))
          "--only" (recur (rest more) (assoc o :only (parse-long (first more))))
          "--dump" (recur (rest more) (assoc o :dump (first more)))
          "--hosts" (recur (rest more) (assoc o :hosts (first more) :hosts-set true))
          "--chunk" (recur (rest more) (assoc o :chunk (parse-long (first more))))
          "--timeout-ms" (recur (rest more) (assoc o :timeout-ms (parse-long (first more))))
          "--crashers" (recur (rest more) (assoc o :crashers (first more)))
          "--no-minimize" (recur more (assoc o :minimize false))
          "--selftest" (recur more (assoc o :selftest true))
          "--no-selftest" (recur more (assoc o :no-selftest true))
          "--replay" (recur (rest more) (assoc o :replay (vec more) :replay-mode true))
          (do (println "  FAIL unknown option" k) (System/exit 2)))))))

;; ------------------------------------------------------------------ builds

(def work (str (fs/create-temp-dir {:prefix "emon-fuzz."})))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (fs/delete-tree work))))

(defn sh [dir & cmd]
  (let [r (apply shell {:dir dir :out :string :err :string :continue true} cmd)]
    r))

(def jdk (let [j (or (System/getenv "EMON_JDK") "/opt/homebrew/opt/openjdk")]
           (if (fs/exists? (str j "/bin/javac"))
             j
             (try (str/trim (:out (shell {:out :string :err :string :continue true}
                                         "/usr/libexec/java_home")))
                  (catch Exception _ nil)))))

(defn java-ok? []
  (and jdk (fs/exists? (str jdk "/bin/javac"))
       (zero? (:exit (sh "." (str jdk "/bin/javac") "-version")))))

(defn build-rust []
  (when (fs/which "rustc")
    (let [d (str work "/rust")]
      (fs/create-dirs (str d "/emon"))
      (doseq [f (fs/glob "gen/rust/emon" "*.rs")] (fs/copy f (str d "/emon/" (fs/file-name f))))
      (doseq [f ["port.rs" "testport.rs"]] (fs/copy (str "host/rust/src/" f) (str d "/" f)))
      (spit (str d "/emon.rs")
            (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                         (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
      (fs/copy "test/fuzz/rust/main.rs" (str d "/main.rs"))
      ;; overflow and bounds checks ON, like kin/scripts/pdf-sweep
      (let [r (sh d "rustc" "--edition" "2021" "-O" "-C" "debug-assertions=on" "-C" "overflow-checks=on"
                  "-A" "warnings" "-o" "prog" "main.rs")]
        (if (zero? (:exit r))
          [(str d "/prog")]
          (do (println "  FAIL rust driver did not build:\n" (:err r)) nil))))))

(defn build-go []
  (when (fs/which "go")
    (let [d (str work "/go")]
      (fs/copy-tree "gen/go" d)
      (fs/create-dirs (str d "/port"))
      (doseq [f (fs/glob "host/go/port" "*.go")] (fs/copy f (str d "/port/" (fs/file-name f))))
      (spit (str d "/go.mod") "module emon\n\ngo 1.21\n")
      (fs/copy "test/fuzz/go/main.go" (str d "/main.go"))
      (let [r (sh d "go" "build" "-o" "prog" ".")]
        (if (zero? (:exit r))
          [(str d "/prog")]
          (do (println "  FAIL go driver did not build:\n" (:err r)) nil))))))

(defn build-java []
  (when (java-ok?)
    (let [d (str work "/java")]
      (fs/create-dirs (str d "/emon"))
      (doseq [f (concat (fs/glob "gen/java/emon" "*.java") (fs/glob "host/java/emon" "*.java"))]
        (fs/copy f (str d "/emon/" (fs/file-name f))))
      (fs/copy "test/fuzz/java/Main.java" (str d "/Main.java"))
      (let [r (apply sh d (str jdk "/bin/javac") "-encoding" "UTF-8" "-d" "."
                     "Main.java" (map #(str "emon/" (fs/file-name %)) (fs/glob (str d "/emon") "*.java")))]
        (if (zero? (:exit r))
          [(str jdk "/bin/java") "-XX:+UseSerialGC" "-XX:TieredStopAtLevel=1" "-Xss8m" "-cp" d "Main"]
          (do (println "  FAIL java driver did not build:\n" (:err r)) nil))))))

(defn build-csharp []
  (when (fs/which "dotnet")
    (let [d (str work "/cs")]
      (fs/create-dirs d)
      (doseq [f (concat (fs/glob "gen/csharp/Emon" "*.cs") (fs/glob "host/csharp/Emon" "*.cs"))]
        (fs/copy f (str d "/" (fs/file-name f))))
      (fs/copy "test/fuzz/cs/Program.cs" (str d "/Program.cs"))
      (spit (str d "/cs.csproj")
            (str "<Project Sdk=\"Microsoft.NET.Sdk\">\n  <PropertyGroup>\n"
                 "    <OutputType>Exe</OutputType>\n    <TargetFramework>net10.0</TargetFramework>\n"
                 "    <Nullable>disable</Nullable>\n    <Optimize>true</Optimize>\n"
                 "    <InvariantGlobalization>true</InvariantGlobalization>\n"
                 "  </PropertyGroup>\n</Project>\n"))
      (let [r (sh d "dotnet" "build" "-c" "Release" "-v" "q" "-o" "out")]
        (if (zero? (:exit r))
          ["dotnet" (str d "/out/cs.dll")]
          (do (println "  FAIL csharp driver did not build:\n" (:out r) (:err r)) nil))))))

(def builders {"rust" build-rust "go" build-go "java" build-java "csharp" build-csharp})

(defn build-hosts [names]
  (let [fs* (into {} (for [h names] [h (future (try ((builders h)) (catch Exception e (println "  FAIL build" h (ex-message e)) nil)))]))]
    (into (sorted-map)
          (for [[h f] fs*
                :let [cmd @f]]
            (if cmd [h cmd] (do (println (str "  SKIP " h " -- no toolchain")) nil))))))


(def ^:dynamic *grace-ms* 30000)   ;; extra time for a host's first answer (a JVM, dotnet)

;; ----------------------------------------------------------------- running

(defn run-once
  "Run the driver over `paths` until it finishes, one case hangs, or the host dies.
   Answers [{path verdict} consumed]; a verdict is {:mem :async} | {:hang true} | {:crash why}."
  [cmd paths timeout-ms first?]
  (let [errf (fs/create-temp-file {:prefix "fuzz-err."})
        pb (doto (ProcessBuilder. ^java.util.List (vec cmd)) (.redirectError (.toFile errf)))
        p (.start pb)
        q (LinkedBlockingQueue.)
        n (count paths)
        cleanup (fn [] (.destroyForcibly p) (fs/delete-if-exists errf))]
    (future (try (with-open [w (io/writer (.getOutputStream p))]
                   (doseq [x paths] (.write w (str x "\n")))
                   (.flush w))
                 (catch Exception _ nil)))
    (future (try (with-open [rd (io/reader (.getInputStream p))]
                   (loop [] (when-let [l (.readLine rd)] (.put q l) (recur))))
                 (catch Exception _ nil)
                 (finally (.put q :eof))))
    (loop [i 0 res {}]
      (if (= i n)
        (do (cleanup) [res n])
        (let [l (.poll q (if (and first? (zero? i)) (+ timeout-ms *grace-ms*) timeout-ms) TimeUnit/MILLISECONDS)
              path (nth paths i)]
          (cond
            (nil? l) (do (cleanup) [(assoc res path {:hang true}) (inc i)])
            (= l :eof) (let [_ (.waitFor p 2 TimeUnit/SECONDS)
                             err (try (let [s (slurp (.toFile errf))] (subs s 0 (min 300 (count s))))
                                      (catch Exception _ ""))
                             ec (if (.isAlive p) "?" (.exitValue p))]
                         (cleanup)
                         [(assoc res path {:crash (str "exit " ec ": " (str/replace err #"\s+" " "))}) (inc i)])
            :else (let [[_ mem async] (str/split l #"\t" 3)]
                    (recur (inc i) (assoc res path {:mem mem :async async})))))))))

(defn run-batch [cmd paths timeout-ms]
  (loop [remaining (vec paths) res {} first? true]
    (if (empty? remaining)
      res
      (let [[r k] (run-once cmd remaining timeout-ms first?)]
        (recur (subvec remaining k) (merge res r) false)))))

(defn run-hosts
  "{host {path verdict}} over every host in parallel."
  [hosts paths chunk timeout-ms]
  (let [fs* (for [[h cmd] hosts]
              [h (future (run-batch (concat cmd (when (pos? chunk) [(str "--chunk=" chunk)])) paths timeout-ms))])]
    (into {} (for [[h f] fs*] [h @f]))))

;; ----------------------------------------------------------------- judging

(defn panic? [s] (and s (str/starts-with? s "PANIC")))
(defn status-of [line] (first (str/split (or line "") #" ")))

(defn failures
  "The verdicts of one file on every host -> a vector of [kind host detail]."
  [by-host]
  (let [out (atom [])
        add! #(swap! out conj %)]
    (doseq [[h v] by-host]
      (cond
        (:hang v) (add! [:hang h ""])
        (:crash v) (add! [:crash h (:crash v)])
        (= "IOERROR" (:mem v)) (add! [:crash h "IOERROR"])
        :else
        (let [{:keys [mem async]} v]
          (when (panic? mem) (add! [:panic h (str "mem " mem)]))
          (when (panic? async) (add! [:panic h (str "async " async)]))
          (when (and async (not (panic? mem)) (not (panic? async)) (not= mem async))
            (add! [:async h (str "memory `" mem "` vs async `" async "`")])))))
    (let [clean (for [[h v] by-host :when (and (:mem v) (not (panic? (:mem v))) (not= "IOERROR" (:mem v)))]
                  [h (:mem v)])]
      (when (> (count (distinct (map second clean))) 1)
        (let [ss (distinct (map (comp status-of second) clean))]
          (add! [(if (> (count ss) 1) :status-mismatch :detail-mismatch) "*"
                 (str/join " | " (for [[h l] clean] (str h ": " l)))]))))
    @out))

(defn signature
  "What a failure IS, for the minimiser: the same set of kinds on the same hosts."
  [fails]
  (into (sorted-set)
        (for [[k h d] fails]
          (str (name k) (when-not (#{:status-mismatch :detail-mismatch} k) (str ":" h))
               (when (#{:panic} k) (if (str/starts-with? d "mem") ":mem" ":async"))))))

(defn analyze [results path]
  (failures (into {} (for [[h rs] results] [h (get rs path)]))))

;; ---------------------------------------------------------------- fixtures

(defn fixture-files []
  (->> (concat (fs/glob "test/pdf" "*.pdf") (fs/glob "test/pdf/p3" "*.pdf") (fs/glob "test/corpus/synth" "**/*.pdf"))
       (map str) sort vec))

(defn gen-case
  "Case i of a seed: {:fixture :ops :bytes}. A pure function of (seed, i, fixtures)."
  [seed i fixtures]
  (let [r (m/rng (m/sm-at seed i))
        fx (m/pick r fixtures)
        [bs ops] (m/mutate r (:bytes fx))]
    {:i i :fixture (:name fx) :ops ops :bytes bs}))

(defn load-fixtures []
  (vec (for [f (fixture-files)] {:name f :bytes (fs/read-all-bytes f)})))

;; -------------------------------------------------------------- minimising

(defn eval-bytes
  "Signatures of each byte array, through the hosts."
  [ctx bss tag]
  (let [dir (str work "/min-" tag)
        _ (fs/create-dirs dir)
        paths (vec (for [[k bs] (map-indexed vector bss)
                         :let [p (format "%s/%05d.pdf" dir k)]]
                     (do (fs/write-bytes p bs) p)))
        results (run-hosts (:hosts ctx) paths (:chunk ctx) (:timeout-ms ctx))]
    (mapv #(signature (analyze results %)) paths)))

(defn offsets [n size cap]
  (if (<= (quot n size) cap)
    (range 0 n size)
    (let [step (quot n cap)] (range 0 n step))))

(defn minimize
  "Remove spans while the same failure holds. A budget in ms bounds it."
  [ctx ^bytes bs target budget-ms]
  (let [t0 (System/currentTimeMillis)
        pass (atom 0)]
    (loop [bs bs size (max 1 (quot (alength bs) 2))]
      (if (or (zero? size) (> (- (System/currentTimeMillis) t0) budget-ms) (<= (alength bs) 1))
        bs
        (let [n (alength bs)
              cands (vec (concat
                          (for [o (offsets n size 300)] (m/splice bs o (+ o size) (byte-array 0)))
                          (when (> n 8) (for [k (range 1 5)] (m/sub bs 0 (max 0 (- n (* k size))))))))
              sigs (eval-bytes ctx cands (swap! pass inc))
              hit (first (keep-indexed (fn [i s] (when (= s target) i)) sigs))]
          (if hit
            (recur (nth cands hit) (min size (max 1 (quot (alength ^bytes (nth cands hit)) 2))))
            (recur bs (quot size 2))))))))

;; ----------------------------------------------------------------- crashers

(defn crasher-files [dir] (when (fs/exists? dir) (sort (map str (fs/glob dir "*.pdf")))))

(defn edn-of [pdf] (str/replace pdf #"\.pdf$" ".edn"))

(defn crasher-status [pdf]
  (let [e (edn-of pdf)]
    (if (fs/exists? e) (:status (read-string (slurp e))) :fixed)))

(defn save-crasher! [dir seed c fails sig minimized]
  (fs/create-dirs dir)
  (let [nm (str (str/join "+" (map #(str/replace % ":" "-") sig)) "-s" seed "-i" (:i c))
        pdf (str dir "/" nm ".pdf")]
    (fs/write-bytes pdf minimized)
    (spit (edn-of pdf)
          (with-out-str
            (println ";; Saved by `bb fuzz` (D106). Reproduce the unminimised mutant with")
            (println (str ";;   bb fuzz --seed " seed " --only " (:i c) " --dump /tmp/mutant.pdf"))
            (println ";; `:status :open` = a known failure (reported, not gating); once fixed, change it to `:fixed` (must pass).")
            (prn {:seed seed :index (:i c) :fixture (:fixture c) :ops (:ops c)
                  :signature (vec sig) :original-bytes (alength ^bytes (:bytes c))
                  :bytes (alength ^bytes minimized) :status :open
                  :detail (mapv (fn [[k h d]] (str (name k) " " h ": " d)) fails)})))
    pdf))

;; ---------------------------------------------------------------- selftest

(defn selftest
  "The harness against a host that misbehaves on demand (tools/fuzz/fakehost.clj)."
  []
  (binding [*grace-ms* 4000]
    (let [dir (str work "/self")
          _ (fs/create-dirs dir)
          hosts {"A" ["bb" "tools/fuzz/fakehost.clj" "A"] "B" ["bb" "tools/fuzz/fakehost.clj" "B"]}
          want {"ok" #{} "hang" #{"hang:A" "hang:B"} "crash" #{"crash:A" "crash:B"}
                "panic" #{"panic:A:mem" "panic:B:mem" "panic:A:async" "panic:B:async"} "mismatch" #{"detail-mismatch"}
                "status" #{"status-mismatch"} "async" #{"async:A" "async:B"} "ok-again" #{}}
          content (assoc (into {} (for [k (keys want)] [k k])) "ok" "fine" "ok-again" "fine2")
          paths (vec (for [k ["ok" "hang" "crash" "panic" "mismatch" "status" "async" "ok-again"]]
                       (let [p (str dir "/" k ".txt")] (spit p (content k)) p)))
          res (run-hosts hosts paths 512 1500)
          bad (atom 0)]
      (doseq [p paths]
        (let [k (str/replace (fs/file-name p) #"\.txt$" "")
              got (set (signature (analyze res p)))]
          (when (not= got (want k))
            (swap! bad inc)
            (println (str "  FAIL selftest: `" k "` judged " (pr-str got) ", wanted " (pr-str (want k)))))))
      (let [ctx {:hosts hosts :chunk 512 :timeout-ms 1500}
            mn (minimize ctx (.getBytes "aaaaaaaaXbbbbbbbbbbbbbbbbbbbbbbbbcccc" "UTF-8")
                         (sorted-set "panic:A:mem" "panic:B:mem" "panic:A:async" "panic:B:async") 60000)]
        (when (not= "X" (String. ^bytes mn "UTF-8"))
          (swap! bad inc)
          (println (str "  FAIL selftest: the minimiser left " (pr-str (String. ^bytes mn "UTF-8")) ", wanted \"X\""))))
      (when (pos? @bad) (System/exit 1))
      (println "  ok   selftest: hang, crash, panic, status and detail mismatch and async-miss are each caught, the batch resumes after a kill, the minimiser reaches one byte"))))

;; -------------------------------------------------------------------- main

(defn- op-kind [d] (first (str/split d #"[@ \[]")))

(defn -main [args]
  (let [o (parse-args args)
        _ (when (or (:selftest o) (not (or (:long o) (:only o) (:replay-mode o) (:no-selftest o))))
            (selftest)
            (when (:selftest o) (System/exit 0)))
        t0 (System/currentTimeMillis)
        _ (shell {:out :string :err :string} "kin/scripts/emit")
        host-names (if (= "all" (:hosts o)) ["rust" "go" "java" "csharp"] (str/split (:hosts o) #","))
        hosts (build-hosts host-names)
        fails-total (atom 0)
        ctx (assoc o :hosts hosts)]
    (when (empty? hosts)
      (println "  FAIL no host toolchain found (rustc, go, javac, dotnet)")
      (System/exit 1))
    (let [hn (str/join "," (keys hosts))
          modes (str "memory + async " (:chunk o) "-byte source")
          t-build (- (System/currentTimeMillis) t0)
          case-dir (str work "/cases")
          _ (fs/create-dirs case-dir)
          crashers (concat (crasher-files (:crashers o)))
          fixtures (load-fixtures)]

      ;; ---- replay mode: named files, no mutation --------------------------
      (if (:replay-mode o)
        (let [res (run-hosts hosts (:replay o) (:chunk o) (:timeout-ms o))]
          (doseq [p (:replay o)]
            (let [f (analyze res p)]
              (println (if (seq f) "  FAIL" "  ok  ") p)
              (doseq [[k h d] f] (println "       " (name k) h d))
              (doseq [[h r] res] (println "       " h (pr-str (get r p))))
              (when (seq f) (swap! fails-total inc))))
          (System/exit (if (pos? @fails-total) 1 0)))

        (let [;; ---- the cases: fixtures as they are, then the mutants ------
              ;; generated and run in batches of 1000, so a long run holds one batch of
              ;; bytes at a time; a failing case is regenerated from (seed, index)
              idxs (if (:only o) [(:only o)] (range (:cases o)))
              _ (when (and (:only o) (:dump o))
                  (let [c (gen-case (:seed o) (:only o) fixtures)]
                    (fs/write-bytes (:dump o) (:bytes c))
                    (println "  dumped case" (:only o) "of seed" (:seed o) "->" (:dump o))
                    (println "       " (:fixture c) (pr-str (:ops c)))))
              nbase (if (:only o) 0 (count fixtures))
              items (concat (for [k (range nbase)] [:base k]) (for [i idxs] [:mut i]))
              make (fn [[kind i]]
                     (if (= kind :base)
                       (let [f (nth fixtures i)]
                         {:i nil :fixture (:name f) :ops ["unmutated"] :bytes (:bytes f)})
                       (gen-case (:seed o) i fixtures)))
              first-host (first (keys hosts))
              statuses (atom {})
              opkinds (atom {})
              badv (atom [])
              done (atom 0)
              t-run (System/currentTimeMillis)
              _ (doseq [part (partition-all 1000 items)]
                  (let [named (vec (map-indexed (fn [k c] (assoc c :path (format "%s/%06d.pdf" case-dir k)))
                                                (map make part)))]
                    (doseq [c named] (fs/write-bytes (:path c) (:bytes c)))
                    (let [res (run-hosts hosts (mapv :path named) (:chunk o) (:timeout-ms o))]
                      (doseq [c named]
                        (let [f (analyze res (:path c))]
                          (swap! statuses update (status-of (:mem (get-in res [first-host (:path c)]))) (fnil inc 0))
                          (when (:i c) (doseq [d (:ops c)] (swap! opkinds update (op-kind d) (fnil inc 0))))
                          (when (seq f)
                            (swap! badv conj (-> c (dissoc :bytes :path) (assoc :fails f)))))))
                    (doseq [c named] (fs/delete (:path c)))
                    (when (:long o)
                      (println (str "  ... " (swap! done + (count named)) " of " (count items) " files, "
                                    (count @badv) " failing, " (quot (- (System/currentTimeMillis) t-run) 1000) "s"))
                      (flush))))
              bad @badv
              statuses @statuses
              opkinds @opkinds
              cases idxs
              base (range nbase)]

          ;; ---- the regression corpus: every saved crasher -----------------
          (let [cres (when (seq crashers) (run-hosts hosts (vec crashers) (:chunk o) (:timeout-ms o)))]
            (doseq [p crashers]
              (let [f (analyze cres p)
                    st (crasher-status p)]
                (cond
                  (and (= st :fixed) (seq f))
                  (do (swap! fails-total inc)
                      (println "  FAIL regression: fixed crasher fails again:" p)
                      (doseq [[k h d] f] (println "       " (name k) h d)))
                  (and (= st :open) (empty? f))
                  (println "  note open crasher now passes (mark :fixed):" p)
                  (= st :open)
                  (println "  known open crasher" (fs/file-name p) "still fails:" (str/join "," (signature f)))))))

          ;; ---- failures: group by signature, minimise one of each ---------
          (let [groups (group-by (comp signature :fails) bad)]
            (doseq [[sig cs] (take 12 (sort-by (comp - count val) groups))]
              (swap! fails-total inc)
              (let [c (first cs)]
                (println (str "  FAIL " (str/join "," sig) " x" (count cs) "; first: seed " (:seed o) " case "
                              (if (:i c) (:i c) "unmutated") " of " (:fixture c) " " (pr-str (:ops c))))
                (doseq [[k h d] (:fails c)]
                  (println "       " (name k) h (subs d 0 (min 400 (count d)))))
                (when (and (:minimize o) (:i c))
                  (let [orig (:bytes (gen-case (:seed o) (:i c) fixtures))
                        mn (minimize ctx orig sig 90000)
                        dir (:crashers o)
                        pdf (save-crasher! dir (:seed o) (assoc c :bytes orig) (:fails c) sig mn)]
                    (println (str "       minimised " (alength ^bytes orig) " -> " (alength ^bytes mn)
                                  " bytes, saved " pdf)))))))
          (when (> (count (group-by (comp signature :fails) bad)) 12)
            (println "  note more than 12 distinct failures; only the 12 largest groups are listed"))

          ;; ---- summary ----------------------------------------------------
          (let [secs (/ (- (System/currentTimeMillis) t-run) 1000.0)]
            (println (str "  cases: " (count cases) " mutants of " (count fixtures) " fixtures (seed " (:seed o)
                          "), " (count base) " unmutated fixtures, " (count crashers) " saved crashers; hosts " hn
                          "; " modes "; build " (quot t-build 1000) "s run " (format "%.1f" secs) "s"))
            (println (str "  statuses (" first-host "): "
                          (str/join ", " (for [[s n] (sort-by (comp - val) statuses)] (str s " " n)))))
            (println (str "  mutations: "
                          (str/join ", " (for [[s n] (sort-by (comp - val) opkinds)] (str s " " n)))))
            (println (str "  failing mutants: " (count bad) " in " (count (group-by (comp signature :fails) bad)) " kind(s)")))
          (if (zero? @fails-total)
            (println (str "  ok   fuzz: " (+ (count cases) (count base)) " files x " (count hosts) " host(s) ("
                          hn "), memory and " (:chunk o) "-byte async: no panic, no hang, hosts agree, async = memory"))
            (System/exit 1)))))))

(-main *command-line-args*)
