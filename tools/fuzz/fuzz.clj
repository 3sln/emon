#!/usr/bin/env bb
;; `bb fuzz`: mutate PDFs and images and run every mutant through the hosts. (DEVLOG P2.12, D106; P10.5, D700.)
;;
;;     bb fuzz                          the gate: self-test, every seed and fixture unmutated, 300 seeded mutants, the
;;                                      engine arm over 50 of them and every image mutant, every saved crasher
;;     bb fuzz-long [N] [--budget-s S] [--seed S]   the large run: N cases (default 20000) or S seconds, not a gate
;;     bb fuzz --seed S --cases N       any other size
;;     bb fuzz --seed S --only I --dump F     write case I of seed S to F (a reproducer)
;;     bb fuzz --replay FILE...         run named files through the hosts, no mutation
;;     options: --hosts rust,go,java,csharp|all   --chunk 512   --timeout-ms 10000   --no-minimize   --crashers DIR
;;              --budget-s S    stop starting batches after S seconds (the run then reports how many cases ran)
;;              --engine-every K  every K-th PDF mutant also goes through the whole conversion (default 6; 0: none)
;;              --no-engine --no-probe --no-wasm --workers 2 --wall-ms 20000 --rss-mb 1024 --limits fuzz|default
;;
;; TWO ARMS. THE PROBE (`emon.fuzzprobe`, kin/fuzzprobe.kin; the same kin unit on every host, in a per-host driver under
;; test/fuzz/<host>/ that reads file names on stdin and prints `name TAB in-memory-line TAB async-line`): open, list
;; the pages, decode every stream the cross-reference names, load every dictionary as a font and map every code,
;; interpret the pages' content. One line out; two hosts that agree on the line agree on all of it. THE ENGINE ARM
;; (`tools/fuzz/engine.clj`): the whole conversion as a process of each host's real driver (`emon-rs`, `emon-go`, and
;; the browser's when its toolchain is present), run under `time` for its peak resident set and a wall limit,
;; in memory and through a 512-byte async source. Images (PNG, JPEG, TIFF) go through the engine arm only: their
;; decoders are the hosts' own.
;;
;; THE VERDICTS (a failure is any of these):
;;   panic       a host threw (probe), or the Engine answered `internal` (engine arm)
;;   hang        no answer within the timeout (probe 10 s per case; engine 20 s per run): the process tree is killed
;;   crash       the process died (a stack overflow is not a panic in Rust or Go) or exited with something but 0 and 4
;;   rss         peak resident set above the bound (engine arm; 1024 MiB under the limits a fuzz run sets, D701)
;;   mismatch    two hosts gave different answers: `status-mismatch` when the status or error NAME differs,
;;               `detail-mismatch` / `output-mismatch` when only the rest does (every byte of the bundle)
;;   async       a host's async answer differs from its own in-memory one: a miss that surfaced as an answer
;;
;; A FAILING MUTANT is minimised (spans removed while the same verdicts hold) and saved under test/fuzz/crashers/ with
;; its seed and index (`NAME.pdf|png|jpg|tif` and `NAME.edn`). Every crasher is replayed on every run on both arms: one
;; whose `.edn` says `:status :fixed` must pass (a fixed bug stays fixed); one that says `:open` is reported, not gating.
;; `bb fuzz --selftest` checks the harness against fake hosts that hang, crash, panic, hog memory and disagree on demand.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[fuzz.mutate :as m]
         '[fuzz.gen :as g]
         '[fuzz.engine :as en]
         '[fuzz.imgmut :as im])
(import '(java.util.concurrent LinkedBlockingQueue TimeUnit))

(def root (str (fs/canonicalize ".")))

;; ---------------------------------------------------------------- options

(defn parse-args [args]
  (loop [a args o {:seed 1 :cases 300 :hosts "rust,go" :chunk 512 :timeout-ms 10000
                   :minimize true :crashers "test/fuzz/crashers"
                   :engine-every 10 :workers 2 :wall-ms 20000 :rss-mb 1024 :limits "fuzz"
                   :replay []}]
    (if (empty? a)
      o
      (let [[k & more] a]
        (case k
          "--seed" (recur (rest more) (assoc o :seed (parse-long (first more))))
          "--cases" (recur (rest more) (assoc o :cases (parse-long (first more))))
          "--long" (if (and (first more) (re-matches #"\d+" (first more)))
                     (recur (rest more) (assoc o :cases (parse-long (first more)) :long true))
                     (recur more (assoc o :cases 20000 :long true)))
          "--budget-s" (recur (rest more) (assoc o :budget-s (parse-long (first more))))
          "--only" (recur (rest more) (assoc o :only (parse-long (first more))))
          "--dump" (recur (rest more) (assoc o :dump (first more)))
          "--hosts" (recur (rest more) (assoc o :hosts (first more) :hosts-set true))
          "--chunk" (recur (rest more) (assoc o :chunk (parse-long (first more))))
          "--timeout-ms" (recur (rest more) (assoc o :timeout-ms (parse-long (first more))))
          "--wall-ms" (recur (rest more) (assoc o :wall-ms (parse-long (first more))))
          "--rss-mb" (recur (rest more) (assoc o :rss-mb (parse-long (first more))))
          "--workers" (recur (rest more) (assoc o :workers (min 2 (parse-long (first more)))))
          "--limits" (recur (rest more) (assoc o :limits (first more)))
          "--engine-every" (recur (rest more) (assoc o :engine-every (parse-long (first more))))
          "--no-engine" (recur more (assoc o :engine-every 0 :no-engine true))
          "--no-probe" (recur more (assoc o :no-probe true))
          "--no-wasm" (recur more (assoc o :no-wasm true))
          "--no-base" (recur more (assoc o :no-base true))
          "--min" (recur more (assoc o :min true))
          "--crashers" (recur (rest more) (assoc o :crashers (first more)))
          "--no-minimize" (recur more (assoc o :minimize false))
          "--selftest" (recur more (assoc o :selftest true))
          "--no-selftest" (recur more (assoc o :no-selftest true))
          "--replay" (recur [] (assoc o :replay (vec more) :replay-mode true))
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
      (let [r (sh d "nice" "-n" "10" "rustc" "--edition" "2021" "-O" "-C" "debug-assertions=on" "-C" "overflow-checks=on"
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
      (let [r (sh d "nice" "-n" "10" "go" "build" "-o" "prog" ".")]
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

(defn build-hosts
  "The probe drivers, one after another: a build is the machine's whole share of CPU."
  [names]
  (into (sorted-map)
        (for [h names
              :let [cmd (try ((builders h)) (catch Exception e (println "  FAIL build" h (ex-message e)) nil))]]
          (if cmd [h cmd] (do (println (str "  SKIP " h " -- no toolchain")) nil)))))

(def ^:dynamic *grace-ms* 30000)   ;; extra time for a host's first answer (a JVM, dotnet)

;; ----------------------------------------------------------------- running (probe)

(defn run-once
  "Run the driver over `paths` until it finishes, one case hangs, or the host dies.
   Answers [{path verdict} consumed]; a verdict is {:mem :async} | {:hang true} | {:crash why}."
  [cmd paths timeout-ms first? env]
  (let [errf (fs/create-temp-file {:prefix "fuzz-err."})
        pb (doto (ProcessBuilder. ^java.util.List (vec (concat ["nice" "-n" "10"] cmd))) (.redirectError (.toFile errf)))
        _ (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
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

(defn run-batch [cmd paths timeout-ms env]
  (loop [remaining (vec paths) res {} first? true]
    (if (empty? remaining)
      res
      (let [[r k] (run-once cmd remaining timeout-ms first? env)]
        (recur (subvec remaining k) (merge res r) false)))))

(defn run-hosts
  "{host {path verdict}} over every host in parallel (one process each: two hosts, two processes)."
  [hosts paths chunk timeout-ms env]
  (let [fs* (for [[h cmd] hosts]
              [h (future (run-batch (concat cmd (when (pos? chunk) [(str "--chunk=" chunk)])) paths timeout-ms env))])]
    (into {} (for [[h f] fs*] [h @f]))))

;; ----------------------------------------------------------------- judging (probe)

(defn panic? [s] (and s (str/starts-with? s "PANIC")))

(defn limit-of-panic
  "A kin limit breach is a NAMED outcome (SPEC 7.4), not a panic: `PANIC the stream bytes limit is N and ...` is
   `LIMIT-stream-bytes`, whatever the host's place in the code."
  [s]
  (when (panic? s)
    (when-let [[_ nm] (re-find #"^PANIC the (.+?) limit is \d+ and this input reached" s)]
      (str "LIMIT-" (str/replace nm " " "-")))))

(defn norm-line [s] (or (limit-of-panic s) s))

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
        (let [{:keys [mem async]} v
              mem (norm-line mem) async (norm-line async)]
          (when (panic? mem) (add! [:panic h (str "mem " mem)]))
          ;; a panic (or a limit breach) in an async round that MISSED is a miss, never reported (D058, SPEC 13.1): the probe's
          ;; loop is kin's, so the panic reaches the driver whole. It is the same panic as the memory run's when it is real,
          ;; and the engine arm (which does catch it per attempt) holds the async contract for the rest (D704).
          (when (and (panic? async) (panic? mem)) (add! [:panic h (str "async " async)]))
          (when (and async (not (panic? mem)) (not (panic? async)) (not= mem async)
                     (not (and (str/starts-with? async "LIMIT-") (not (str/starts-with? mem "LIMIT-")))))
            (add! [:async h (str "memory `" mem "` vs async `" async "`")])))))
    (let [clean (for [[h v] by-host
                      :let [mem (norm-line (:mem v))]
                      :when (and (:mem v) (not (panic? mem)) (not= "IOERROR" mem))]
                  [h mem])]
      (when (> (count (distinct (map second clean))) 1)
        (let [ss (distinct (map (comp status-of second) clean))]
          (add! [(if (> (count ss) 1) :status-mismatch :detail-mismatch) "*"
                 (str/join " | " (for [[h l] clean] (str h ": " l)))]))))
    @out))

(defn signature
  "What a failure IS, for the minimiser: the same set of kinds on the same hosts."
  [fails]
  (into (sorted-set)
        (for [[k h d] fails
              :let [host (first (str/split h #"/"))]]
          (str (name k) (when-not (#{:status-mismatch :detail-mismatch :engine-status-mismatch :engine-output-mismatch} k) (str ":" host))
               (when (#{:panic} k) (if (str/starts-with? d "mem") ":mem" ":async"))))))

(defn analyze [results path]
  (failures (into {} (for [[h rs] results] [h (get rs path)]))))

;; ------------------------------------------------------------ the engine arm

(defn limits-env [o] (when (= "fuzz" (:limits o)) en/fuzz-limits))

(defn engine-ctx [o drivers & {:keys [arms]}]
  {:drivers drivers :arms (or arms en/default-arms) :wall-ms (:wall-ms o) :workers (:workers o) :env (limits-env o)})

(defn rss-bytes [o] (* (:rss-mb o) 1048576))

(defn wasm-merge
  "Add the browser's answer to each file's runs: [host \"bytes\"] = [\"wasm\" \"bytes\"]."
  [runs-by-file wasm-runs]
  (into {} (for [[f runs] runs-by-file]
             [f (if-let [w (get wasm-runs f)] (assoc runs ["wasm" "bytes"] w) runs)])))

(def known-jpeg (atom 0))

(defn jpeg-file? [path]
  (boolean (and path (fs/exists? path)
                (with-open [in (io/input-stream path)]
                  (let [b (byte-array 3)] (and (= 3 (.read in b)) (= [-1 -40 -1] (vec b))))))))

(defn engine-fails
  "Failures of one file's runs, in the probe's [kind host detail] shape. A damaged JPEG that one host reads and the other
   calls `undecodable` is a known class (D705): counted, not a failure."
  [o runs & [path]]
  (let [fl (en/failures runs (rss-bytes o) {:jpeg? (jpeg-file? path)})]
    (swap! known-jpeg + (count (filter #(= :engine-known-jpeg (first %)) fl)))
    (vec (remove #(= :engine-known-jpeg (first %)) fl))))

;; ---------------------------------------------------------------- fixtures

(defn ext-of [^bytes bs kind]
  (if (= kind :image)
    (case (im/kind-of bs) :png "png" :jpeg "jpg" :tiff "tif" "bin")
    "pdf"))

;; -------------------------------------------------------------- minimising

(defn eval-bytes
  "Signatures of each byte array, through the hosts (the probe arm, or the engine arm for a target that names it)."
  [ctx bss tag engine?]
  (let [dir (str work "/min-" tag)
        _ (fs/create-dirs dir)
        paths (vec (for [[k bs] (map-indexed vector bss)
                         :let [p (format "%s/%05d.%s" dir k (ext-of bs (if (:image? ctx) :image :pdf)))]]
                     (do (fs/write-bytes p bs) p)))]
    (if engine?
      (let [res (en/run-many (:engine ctx) paths)]
        (mapv #(signature (engine-fails (:o ctx) (get res %) %)) paths))
      (let [results (run-hosts (:hosts ctx) paths (:chunk ctx) (:timeout-ms ctx) (:env ctx))]
        (mapv #(signature (analyze results %)) paths)))))

(defn offsets [n size cap]
  (if (<= (quot n size) cap)
    (range 0 n size)
    (let [step (quot n cap)] (range 0 n step))))

(defn minimize
  "Remove spans while the same failure holds. A budget in ms bounds it."
  [ctx ^bytes bs target budget-ms]
  (let [t0 (System/currentTimeMillis)
        pass (atom 0)
        engine? (boolean (some #(str/starts-with? % "engine-") target))]
    (loop [bs bs size (max 1 (quot (alength bs) 2))]
      (if (or (zero? size) (> (- (System/currentTimeMillis) t0) budget-ms) (<= (alength bs) 1))
        bs
        (let [n (alength bs)
              cands (vec (concat
                          (for [o (offsets n size (if engine? 60 300))] (m/splice bs o (+ o size) (byte-array 0)))
                          (when (> n 8) (for [k (range 1 5)] (m/sub bs 0 (max 0 (- n (* k size))))))))
              sigs (eval-bytes ctx cands (swap! pass inc) engine?)
              hit (first (keep-indexed (fn [i s] (when (= s target) i)) sigs))]
          (if hit
            (recur (nth cands hit) (min size (max 1 (quot (alength ^bytes (nth cands hit)) 2))))
            (recur bs (quot size 2))))))))

;; ----------------------------------------------------------------- crashers

(defn crasher-files [dir]
  (when (fs/exists? dir)
    (sort (map str (remove #(str/ends-with? (str %) ".edn") (fs/list-dir dir))))))

(defn edn-of [f] (str/replace f #"\.[a-z]+$" ".edn"))

(defn crasher-status [f]
  (let [e (edn-of f)]
    (if (fs/exists? e) (:status (read-string (slurp e))) :fixed)))

(defn save-crasher! [dir seed c fails sig minimized]
  (fs/create-dirs dir)
  (let [nm (str (str/join "+" (map #(str/replace % #"[:/]" "-") sig)) "-s" seed "-" (if (:i c) (str "i" (:i c)) (str "seed-" (str/replace (:fixture c) #"[^A-Za-z0-9]+" "-"))))
        f (str dir "/" nm "." (ext-of minimized (:kind c)))]
    (fs/write-bytes f minimized)
    (spit (edn-of f)
          (with-out-str
            (println ";; Saved by `bb fuzz` (D106, D700). Reproduce the unminimised mutant with")
            (println (if (:i c)
                       (str ";;   bb fuzz --seed " seed " --only " (:i c) " --dump /tmp/mutant")
                       (str ";;   the generated seed " (:fixture c) " (tools/fuzz/seeds.clj), unmutated")))
            (println ";; `:status :open` = a known failure (reported, not gating); once fixed, change it to `:fixed` (must pass).")
            (prn {:seed seed :index (:i c) :fixture (:fixture c) :ops (:ops c)
                  :signature (vec sig) :original-bytes (alength ^bytes (:bytes c))
                  :bytes (alength ^bytes minimized) :status :open
                  :detail (mapv (fn [[k h d]] (str (name k) " " h ": " d)) fails)})))
    f))

;; ---------------------------------------------------------------- selftest

(defn selftest
  "The harness against hosts that misbehave on demand (tools/fuzz/fakehost.clj, fakedriver.clj)."
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
          res (run-hosts hosts paths 512 1500 nil)
          bad (atom 0)]
      (doseq [p paths]
        (let [k (str/replace (fs/file-name p) #"\.txt$" "")
              got (set (signature (analyze res p)))]
          (when (not= got (want k))
            (swap! bad inc)
            (println (str "  FAIL selftest: `" k "` judged " (pr-str got) ", wanted " (pr-str (want k)))))))
      ;; a kin limit breach is a named outcome, not a panic; a panic with a place is still a panic
      (let [lim (fn [s] (norm-line s))]
        (when (not= "LIMIT-stream-bytes" (lim "PANIC the stream bytes limit is 33554432 and this input reached 40000000. Raise it with EMON_LIMIT_STREAM_BYTES at emon/pdffilter.rs:80"))
          (swap! bad inc) (println "  FAIL selftest: a limit breach is not recognised"))
        ;; a panic or limit breach in an async round that missed is a miss (D058, D704): the probe's async line alone is not judged for it
        (when (seq (failures {"A" {:mem "ok 1" :async "PANIC the stream bytes limit is 1 and this input reached 2. x"}}))
          (swap! bad inc) (println "  FAIL selftest: a limit breach in a missed async round is reported"))
        (when (seq (failures {"A" {:mem "ok 1" :async "PANIC attempt to add with overflow at emon/x.rs:1"}}))
          (swap! bad inc) (println "  FAIL selftest: a panic in a missed async round is reported"))
        (when (limit-of-panic "PANIC attempt to add with overflow at emon/x.rs:1")
          (swap! bad inc) (println "  FAIL selftest: an overflow is taken for a limit breach")))
      (let [ctx {:hosts hosts :chunk 512 :timeout-ms 1500 :env nil}
            mn (minimize ctx (.getBytes "aaaaaaaaXbbbbbbbbbbbbbbbbbbbbbbbbcccc" "UTF-8")
                         (sorted-set "panic:A:mem" "panic:B:mem" "panic:A:async" "panic:B:async") 60000)]
        (when (not= "X" (String. ^bytes mn "UTF-8"))
          (swap! bad inc)
          (println (str "  FAIL selftest: the minimiser left " (pr-str (String. ^bytes mn "UTF-8")) ", wanted \"X\""))))
      ;; the engine arm: a fake driver that does what the file says
      (let [fake {"A" ["bb" "tools/fuzz/fakedriver.clj" "A"] "B" ["bb" "tools/fuzz/fakedriver.clj" "B"]}
            wantE {"ok" #{} "hang" #{:engine-hang} "crash" #{:engine-crash} "panic" #{:engine-panic} "named" #{} "limit" #{}
                   "rss" #{:engine-rss} "status" #{:engine-status-mismatch} "name" #{:engine-status-mismatch}
                   "output" #{:engine-output-mismatch} "async" #{:engine-async}}
            ctx {:drivers fake :arms [["bytes" ["--out=bundle"]] ["async" ["--out=bundle" "--chunk=512" "--async"]]] :wall-ms 2500 :workers 2 :env nil}
            files (vec (for [k (sort (keys wantE))] (let [p (str dir "/e-" k ".txt")] (spit p k) p)))
            ;; hang, crash and panic need no async arm: one run each
            res (en/run-many ctx files)]
        (doseq [p files]
          (let [k (str/replace (fs/file-name p) #"^e-|\.txt$" "")
                got (set (map first (en/failures (get res p) (* 200 1048576))))]
            (when (not= got (wantE k))
              (swap! bad inc)
              (println (str "  FAIL selftest: engine `" k "` judged " (pr-str got) ", wanted " (pr-str (wantE k))))))))
      (when (pos? @bad) (System/exit 1))
      (println "  ok   selftest: hang, crash, panic, status and detail mismatch and async-miss are each caught on the probe and on the engine arm (and an out-of-bound resident set, a limit breach named as one), the batch resumes after a kill, the minimiser reaches one byte"))))

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
        engine? (and (not (:no-engine o)) (pos? (:engine-every o)))
        probe? (not (:no-probe o))
        hosts (when probe? (build-hosts host-names))
        edrivers (when engine? (en/build-drivers (filter #{"rust" "go"} host-names)))
        wasm (when (and engine? (not (:no-wasm o))) (en/wasm-module))
        penv (limits-env o)
        fails-total (atom 0)]
    (when (and probe? (empty? hosts))
      (println "  FAIL no host toolchain found (rustc, go, javac, dotnet)")
      (System/exit 1))
    (let [hn (str/join "," (keys hosts))
          modes (str "memory + async " (:chunk o) "-byte source")
          t-build (- (System/currentTimeMillis) t0)
          case-dir (str work "/cases")
          _ (fs/create-dirs case-dir)
          crashers (crasher-files (:crashers o))
          corpus (g/load-corpus)
          ctx {:hosts hosts :chunk (:chunk o) :timeout-ms (:timeout-ms o) :env penv :o o
               :engine (engine-ctx o edrivers)}
          ctx-base (assoc ctx :engine (engine-ctx o edrivers :arms [["bytes" ["--out=bundle"]]]))
          item-name #(str (:name %))]

      ;; ---- replay mode: named files, no mutation --------------------------
      (if (:replay-mode o)
        (let [pdfs (filterv #(str/ends-with? % ".pdf") (:replay o))
              pres (when (and probe? (seq pdfs)) (run-hosts hosts pdfs (:chunk o) (:timeout-ms o) penv))
              eres (when (and engine? (seq (:replay o))) (en/run-many (:engine ctx) (:replay o)))]
          (doseq [p (:replay o)]
            (let [f (concat (when (and pres (some #{p} pdfs)) (analyze pres p)) (when eres (engine-fails o (get eres p) p)))]
              (println (if (seq f) "  FAIL" "  ok  ") p)
              (doseq [[k h d] f] (println "       " (name k) h d))
              (doseq [[h r] pres] (println "       " h (pr-str (get r p))))
              (doseq [[k r] (get eres p)] (println "       " k (pr-str (en/answer r)) (:ms r) "ms" (some-> (:rss r) (quot 1048576)) "MiB"))
              (when (and (seq f) (:min o))
                (let [sig (signature f)
                      mn (minimize (assoc ctx :image? (not (str/ends-with? p ".pdf"))) (fs/read-all-bytes p) sig 150000)]
                  (fs/write-bytes (str p ".min") mn)
                  (println "       minimised to" (alength ^bytes mn) "bytes:" (str p ".min") (str/join "," sig))))
              (when (seq f) (swap! fails-total inc))))
          (System/exit (if (pos? @fails-total) 1 0)))

        (let [only (:only o)
              _ (when (and only (:dump o))
                  (let [c (g/gen-case (:seed o) only corpus)]
                    (fs/write-bytes (:dump o) (:bytes c))
                    (println "  dumped case" only "of seed" (:seed o) "->" (:dump o))
                    (println "       " (:fixture c) (pr-str (:ops c)))))
              ;; every committed fixture and generated seed (probe), every seed and image (engine): unmutated
              bases (if (or only (:no-base o)) [] (vec (for [it corpus]
                                       {:i nil :fixture (:name it) :kind (:kind it) :ops ["unmutated"] :item it
                                        :probe? (and probe? (#{:pdf :seed} (:kind it)))
                                        :engine? (and engine? (#{:seed :image} (:kind it)))
                                        :base? true})))
              k (:engine-every o)
              mk (fn [i] (let [c (g/gen-case (:seed o) i corpus)]
                           (assoc c :probe? (and probe? (not= :image (:kind c)))
                                  :engine? (and engine? (or (and (= :image (:kind c)) (or (:long o) (zero? (mod i 3)))) (zero? (mod i k)))))))
              statuses (atom {}) opkinds (atom {}) estat (atom {}) badv (atom []) done (atom 0)
              probe-files (atom 0) engine-files (atom 0) engine-runs (atom 0) wasm-files (atom 0)
              peak (atom {}) slowest (atom [0 nil])
              first-host (first (keys hosts))
              budget-ms (some-> (:budget-s o) (* 1000))
              t-run (System/currentTimeMillis)
              run-batch! (fn [cases]
                           (let [named (vec (map-indexed
                                             (fn [n c]
                                               (let [bs (or (:bytes c) (g/item-bytes (:item c)))]
                                                 (assoc c :bytes bs :path (format "%s/%06d.%s" case-dir n (ext-of bs (:kind c))))))
                                             cases))]
                             (doseq [c named] (fs/write-bytes (:path c) (:bytes c)))
                             (let [pc (filterv :probe? named)
                                   ec (filterv :engine? named)
                                   pres (when (seq pc) (run-hosts hosts (mapv :path pc) (:chunk o) (:timeout-ms o) penv))
                                   ;; unmutated items run the in-memory arm only; mutants run both
                                   eres (when (seq ec)
                                          (merge (en/run-many (:engine ctx-base) (mapv :path (filter :base? ec)))
                                                 (en/run-many (:engine ctx) (mapv :path (remove :base? ec)))))
                                   wres (when (and wasm (seq ec))
                                          (let [fs* (mapv :path (take (if (:long o) 100000 24) ec))]
                                            (swap! wasm-files + (count fs*))
                                            (en/run-wasm wasm fs* work)))
                                   eres (if wres (wasm-merge eres wres) eres)]
                               (swap! probe-files + (count pc))
                               (swap! engine-files + (count ec))
                               (doseq [c named]
                                 (let [f (concat (when (:probe? c) (analyze pres (:path c)))
                                                 (when (:engine? c) (engine-fails o (get eres (:path c)) (:path c))))]
                                   (when (and (:probe? c) first-host)
                                     (swap! statuses update (status-of (norm-line (:mem (get-in pres [first-host (:path c)])))) (fnil inc 0)))
                                   (when (:engine? c)
                                     (doseq [[[h arm] r] (get eres (:path c))]
                                       (swap! engine-runs inc)
                                       (when (= arm "bytes") (swap! estat update [h (let [a (en/answer r)] (if (= :err (first a)) (str "error " (second a)) (name (first a))))] (fnil inc 0)))
                                       (when (and (:rss r) (> (:rss r) (get @peak h 0))) (swap! peak assoc h (:rss r)))
                                       (when (> (:ms r) (first @slowest)) (reset! slowest [(:ms r) (str h "/" arm " " (:fixture c))]))))
                                   (when (:i c) (doseq [d (:ops c)] (swap! opkinds update (op-kind d) (fnil inc 0))))
                                   (when (seq f)
                                     (swap! badv conj (-> c (dissoc :bytes :path :item) (assoc :fails f :orig-item (:item c)))))))
                               (doseq [c named] (fs/delete (:path c))))))
              ;; the unmutated corpus first, then the mutants in batches until the cases or the time are done
              _ (doseq [part (partition-all 150 bases)] (run-batch! part))
              batch (if (:long o) 200 1000)
              ran (atom 0)
              idxs (if only [only] (range (:cases o)))
              _ (doseq [part (partition-all batch idxs)
                        :while (or (nil? budget-ms) (< (- (System/currentTimeMillis) t-run) budget-ms))]
                  (run-batch! (mapv mk part))
                  (swap! ran + (count part))
                  (when (:long o)
                    (println (str "  ... " @ran " mutants, " (count @badv) " failing, " (quot (- (System/currentTimeMillis) t-run) 1000) "s"))
                    (flush)))
              bad @badv
              nbase (count bases)]

          ;; ---- the regression corpus: every saved crasher, on both arms ---------
          (let [pdfc (filterv #(str/ends-with? % ".pdf") crashers)
                cp (when (and probe? (seq pdfc)) (run-hosts hosts pdfc (:chunk o) (:timeout-ms o) penv))
                ce (when (and engine? (seq crashers)) (en/run-many (:engine ctx) (vec crashers)))]
            (doseq [p crashers]
              (let [f (concat (when (and cp (some #{p} pdfc)) (analyze cp p)) (when ce (engine-fails o (get ce p) p)))
                    st (crasher-status p)]
                (cond
                  (and (= st :fixed) (seq f))
                  (do (swap! fails-total inc)
                      (println "  FAIL regression: fixed crasher fails again:" p)
                      (doseq [[kk h d] f] (println "       " (name kk) h d)))
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
                (doseq [[kk h d] (:fails c)]
                  (println "       " (name kk) h (subs d 0 (min 400 (count d)))))
                (doseq [c2 (take 6 (rest cs))]
                  (println (str "        also case " (:i c2) " of " (:fixture c2) " "
                                (str/join "; " (for [[kk h d] (:fails c2)] (str (name kk) " " h " " (subs d 0 (min 160 (count d)))))))))
                (when (:minimize o)
                  (let [orig (if (:i c) (:bytes (g/gen-case (:seed o) (:i c) corpus)) (g/item-bytes (:orig-item c)))]
                    (when (or (:i c) (= :seed (:kind c)) (= :image (:kind c)))
                      (let [mctx (assoc ctx :image? (= :image (:kind c)))
                            mn (minimize mctx orig sig 120000)
                            pdf (save-crasher! (:crashers o) (:seed o) (assoc c :bytes orig) (:fails c) sig mn)]
                        (println (str "       minimised " (alength ^bytes orig) " -> " (alength ^bytes mn)
                                      " bytes, saved " pdf)))))))))
          (when (> (count (group-by (comp signature :fails) bad)) 12)
            (println "  note more than 12 distinct failures; only the 12 largest groups are listed"))

          ;; ---- summary ----------------------------------------------------
          (let [secs (/ (- (System/currentTimeMillis) t-run) 1000.0)
                mut (if only 1 @ran)]
            (println (str "  cases: " mut " mutants (seed " (:seed o) ") of " (count corpus) " corpus items ("
                          (frequencies (map :kind corpus)) "), " nbase " unmutated, " (count crashers) " saved crashers; build "
                          (quot t-build 1000) "s run " (format "%.1f" secs) "s"))
            (when probe?
              (println (str "  probe arm: " @probe-files " PDFs x " (count hosts) " host(s) (" hn "), " modes " = "
                            (* 2 (count hosts) @probe-files) " runs; statuses (" first-host "): "
                            (str/join ", " (for [[s n] (sort-by (comp - val) @statuses)] (str s " " n))))))
            (when engine?
              (println (str "  engine arm: " @engine-files " files, " @engine-runs " conversions ("
                            (str/join "," (keys edrivers)) (when wasm ",wasm") ", in memory and through a 512-byte async source)"
                            (when wasm (str ", wasm on " @wasm-files " files"))
                            "; limits " (:limits o) "; peak RSS "
                            (str/join ", " (for [[h b] (sort @peak)] (str h " " (quot b 1048576) " MiB"))) " (bound " (:rss-mb o) " MiB); slowest "
                            (first @slowest) " ms " (second @slowest)))
              (println (str "  engine outcomes (in memory): "
                            (str/join ", " (for [[[h s] n] (sort-by (comp - val) @estat)] (str h " " s " " n))
                                      ) "; known and not judged: " @known-jpeg " damaged JPEGs one host reads and the other calls undecodable (D190, D705)")))
            (println (str "  mutations: "
                          (str/join ", " (for [[s n] (sort-by (comp - val) @opkinds)] (str s " " n)))))
            (println (str "  failing cases: " (count bad) " in " (count (group-by (comp signature :fails) bad)) " kind(s)")))
          (if (zero? @fails-total)
            (println (str "  ok   fuzz: " (+ (if only 1 @ran) nbase) " inputs on " (str/join "," (concat (keys hosts) (when engine? ["engine"]))) ": no panic, no hang, resident set under "
                          (:rss-mb o) " MiB, hosts agree (error name or byte-identical output), async = memory"))
            (System/exit 1)))))))

(-main *command-line-args*)
