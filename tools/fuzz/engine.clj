;; THE ENGINE ARM of `bb fuzz` (DEVLOG P10.5, D384): every input also goes through the whole conversion, as a
;; process of each host's real driver (`emon-rs`, `emon-go`: `--out=bundle`, so the JSON, the HTML, the evidence
;; and the intake are all in the answer), and the browser's (`emon-wasm-run`) when its toolchain is there. Each run
;; is timed, its peak resident set measured (`/usr/bin/time`: `ru_maxrss`, which cannot be missed by a run that
;; ends before a poll), and judged:
;;
;;   hang    no answer within the wall limit: the process tree is killed (our own processes only)
;;   rss     peak resident set above the bound (default 1536 MiB for an input of a few MiB)
;;   panic   exit 1: the Engine's `Internal` error, which is what a panic or a bug in a host becomes
;;   crash   any other exit but 0 and 4: a stack overflow, an abort, a signal
;;   status  hosts disagree on the outcome: ok on one and an error on the other, or two error names
;;   output  both ok and the bundles differ (byte for byte)
;;   async   a host's run through a 512-byte async source differs from its in-memory run
;;
;; An answer is `[:ok digest]`, `[:err name]`, `[:panic]`, `[:crash exit]` or `[:hang]`. Only the error NAME is
;; compared between hosts (SPEC 7.4, D058): the message after it is a host's own words.
(ns fuzz.engine
  (:require [babashka.fs :as fs]
            [extra-hosts :as xh]
            [babashka.process :as p]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import (java.util.concurrent TimeUnit Semaphore)
           (java.security MessageDigest)))

(def mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))

(def time-cmd (if mac? ["/usr/bin/time" "-l"] ["/usr/bin/time" "-v"]))

(defn sha ^String [^bytes bs]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and 255 %)) (take 12 d)))))

(defn- rss-of
  "Peak resident set of the child in bytes, from the text `time` wrote; nil when it is not there."
  [^String err]
  (if mac?
    (some-> (re-find #"(\d+)\s+maximum resident set size" err) second parse-long)
    (some-> (re-find #"Maximum resident set size \(kbytes\): (\d+)" err) second parse-long (* 1024))))

(defn- strip-time
  "The driver's own stderr: what is before the `time` report."
  [^String err]
  (if mac?
    (let [m (re-matcher #"(?m)^\s+[\d.]+ real\s+[\d.]+ user" err)]
      (if (.find m) (subs err 0 (.start m)) err))
    (if-let [i (str/index-of err "\tCommand being timed")] (subs err 0 i) err)))

(defn- kill-tree! [^Process p]
  (let [h (.toHandle p)]
    (doseq [d (iterator-seq (.iterator (.descendants h)))] (.destroyForcibly ^java.lang.ProcessHandle d))
    (.destroyForcibly p)))

(defn run-driver
  "Run `cmd... args... file` (cmd: a vector) under nice and time, with `env` (a map) added to the environment.
   {:exit :out (bytes) :err (the driver's) :rss (bytes) :ms :hang}."
  [cmd args file wall-ms env]
  (let [outf (fs/create-temp-file {:prefix "fz-out."})
        errf (fs/create-temp-file {:prefix "fz-err."})
        t0 (System/nanoTime)
        pb (doto (ProcessBuilder. ^java.util.List (vec (concat ["nice" "-n" "10"] time-cmd cmd args [file])))
             (.redirectOutput (.toFile outf)) (.redirectError (.toFile errf)))
        _ (doseq [[k v] env] (.put (.environment pb) (str k) (str v)))
        pr (.start pb)
        done (.waitFor pr wall-ms TimeUnit/MILLISECONDS)
        ms (quot (- (System/nanoTime) t0) 1000000)]
    (when-not done (kill-tree! pr) (.waitFor pr 5 TimeUnit/SECONDS))
    (let [err (slurp (.toFile errf))
          res {:exit (if done (.exitValue pr) -1) :hang (not done) :ms ms
               :out (fs/read-all-bytes outf) :err (strip-time err) :rss (rss-of err)}]
      (fs/delete-if-exists outf) (fs/delete-if-exists errf)
      res)))

(defn answer
  "[kind detail] of a run."
  [{:keys [exit hang out err]}]
  (cond
    hang [:hang]
    (= 0 exit) [:ok (sha out)]
    (= 4 exit) [:err (or (second (re-find #"(?m)^error: (\S+)" err)) "?")]
    (= 1 exit) [:panic (let [l (or (second (re-find #"(?m)^error: (.*)$" err)) "")] (subs l 0 (min 120 (count l))))]
    :else [:crash (str exit)]))

;; ------------------------------------------------------------------- the wasm arm

(defn wasm-module
  "The path of the built wasm module, or nil (with the reason printed) when this machine has no toolchain for it."
  []
  (let [r (p/shell {:out :string :err :string :continue true} "kin/scripts/emon-wasm-run" "--check")]
    (case (:exit r)
      0 (str/trim (:out r))
      3 (do (println (str "  " (str/trim (:err r)) " (the wasm arm is left out)")) nil)
      (do (println "  FAIL the wasm module did not build:\n" (:err r)) nil))))

(defn- sdk-limits
  "The browser's spelling of the EMON_LIMIT_* pokes a native driver is given: `EMON_LIMIT_STREAM_BYTES` -> {\"stream-bytes\" n} (the SDK's `limits`)."
  [env]
  (into {} (for [[k v] env :let [m (re-find #"^EMON_LIMIT_(.+)$" (str k))] :when m]
             [(str/lower-case (str/replace (second m) "_" "-")) (if (string? v) (parse-long v) v)])))

(defn run-wasm
  "Every file through the browser, array adapter: {file {:exit :out :err}}. One headless Chrome for the batch. `env` is the limits environment the
   native drivers are run with (D385): the browser is given the same bounds as the SDK's `limits`, so the same breach is named (a file outside the
   repository is staged by the runner: D1241)."
  [wasm files work & [env]]
  (when (seq files)
    (let [base (str work "/wasm-" (System/nanoTime))
          outd (str base "/out")
          _ (fs/create-dirs base)
          limits (sdk-limits env)
          jobs (vec (for [[i f] (map-indexed vector files)] (cond-> {:id (str i) :path f :adapter "array"} (seq limits) (assoc :limits limits))))
          jf (str base "/jobs.json")]
      (spit jf (json/generate-string jobs))
      (let [r (p/shell {:out :string :err :string :continue true :extra-env {"EMON_WASM_MODULE" wasm}}
                       "nice" "-n" "10" "kin/scripts/emon-wasm-run" "--jobs" jf "--out" outd)]
        (if (zero? (:exit r))
          (into {} (for [[i f] (map-indexed vector files)]
                     [f {:exit (parse-long (str/trim (slurp (str outd "/" i ".exit"))))
                         :err (slurp (str outd "/" i ".err"))
                         :out (.getBytes ^String (slurp (str outd "/" i ".out")) "UTF-8")}]))
          (do (println "  FAIL the browser driver failed:" (subs (:err r) 0 (min 400 (count (:err r))))) nil))))))

;; --------------------------------------------------------------------- judging

(defn failures
  "runs: {[host arm] run}. -> [[kind host detail]], the same shape as the probe arm's, kinds prefixed `engine-`."
  [runs rss-bytes & [{:keys [jpeg?]}]]
  (let [out (atom []) add! #(swap! out conj %)
        ans (into {} (for [[k r] runs] [k (answer r)]))]
    (doseq [[[h arm] r] runs :let [a (ans [h arm])]]
      (case (first a)
        :hang (add! [:engine-hang (str h "/" arm) (str (:ms r) " ms")])
        :panic (add! [:engine-panic (str h "/" arm) (second a)])
        :crash (add! [:engine-crash (str h "/" arm) (str "exit " (second a) ": " (subs (:err r) 0 (min 200 (count (:err r)))))])
        nil)
      (when (and (:rss r) (> (:rss r) rss-bytes))
        (add! [:engine-rss (str h "/" arm) (str (quot (:rss r) 1048576) " MiB > " (quot rss-bytes 1048576) " MiB")])))
    ;; hosts against each other, on the in-memory arm; a failed run is already reported
    (let [good (for [[[h arm] a] ans :when (and (= arm "bytes") (#{:ok :err} (first a)))] [h a])]
      (when (> (count (distinct (map second good))) 1)
        (let [kinds (distinct (map #(let [[k d] (second %)] (if (= k :err) [k d] [k])) good))]
          ;; KNOWN, AND NOT A FAILURE (D190, D389): two JPEG decoders (zune-jpeg, image/jpeg) forgive different damage, so a damaged JPEG
          ;; that one reads and the other calls `undecodable` is counted as `engine-known-jpeg`, not judged
          (add! [(cond (and jpeg? (= (set kinds) #{[:ok] [:err "undecodable"]})) :engine-known-jpeg
                       (> (count kinds) 1) :engine-status-mismatch
                       :else :engine-output-mismatch) "*"
                 (str/join " | " (for [[h a] good] (str h ": " (pr-str a))))]))))
    ;; a host against itself: async source vs memory
    (doseq [h (distinct (map ffirst runs))]
      (let [a (ans [h "bytes"]) b (ans [h "async"])]
        (when (and b (#{:ok :err} (first a)) (#{:ok :err} (first b)) (not= a b))
          (add! [:engine-async h (str "memory " (pr-str a) " vs async " (pr-str b))]))))
    @out))

(def default-arms [["bytes" ["--out=bundle"]] ["async" ["--out=bundle" "--chunk=512" "--async"]]])

;; THE LIMITS A FUZZ RUN SETS (D385): the Engine's defaults let one stream decode to 256 MiB, which is 1 GiB of 4-byte
;; cells before the breach is named (and 3.6 GiB of Go heap): a bomb is then a memory test of the machine, not of the
;; reader. `EMON_LIMIT_*` is the host's own poke (`port::limit_of`), so the same breach is named on both hosts and the
;; bound on the resident set means something. `bb fuzz-long --limits default` runs with the defaults and a looser bound.
(def fuzz-limits {"EMON_LIMIT_STREAM_BYTES" 33554432 "EMON_LIMIT_TOTAL_BYTES" 134217728 "EMON_LIMIT_PIXELS" 33554432})

(defn run-case
  "Every host's arms over one file: {[host arm] run}. `ctx` has :drivers {host [cmd...]}, :arms [[name flags]], :wall-ms, :env, and :host-arms
   {host [[name flags]]} for a host that runs fewer arms than the rest (the sampled hosts of D1241: the in-memory arm only)."
  [{:keys [drivers arms host-arms wall-ms env]} file]
  (into {} (for [[h cmd] drivers [arm flags] (get host-arms h arms)] [[h arm] (run-driver cmd flags file wall-ms env)])))

(defn run-many
  "Files through the drivers, `(:workers ctx)` at a time (each worker runs its case's processes one after another, so at
   most that many processes run at once). {file {[host arm] run}}."
  [ctx files]
  (let [sem (Semaphore. (:workers ctx))
        fs* (doall (for [f files]
                     [f (future (.acquire sem)
                                (try (run-case ctx f) (finally (.release sem))))]))]
    (into {} (for [[f fu] fs*] [f @fu]))))

(defn build-drivers
  "{host [exe]} for the hosts named: rust and go, built by the repository's own scripts (cached under .cache/)."
  [names]
  (into {}
        (for [h names :let [script (case h "rust" "kin/scripts/emon-rs-build" "go" "kin/scripts/emon-go-build" nil)]
              :when script
              :let [r (p/shell {:out :string :err :string :continue true} "nice" "-n" "10" script)]]
          (if (zero? (:exit r))
            [h [(str/trim (:out r))]]
            (do (println "  FAIL the" h "driver did not build:\n" (:err r)) nil)))))

(defn build-extra-drivers
  "{host [exe]} for the sampled hosts of P9.5 (D1241): java, csharp, c and pod, built by the repository's own scripts, each LEFT OUT LOUDLY without its
   toolchain (`SKIP java -- why`, `EMON_SKIP_JAVA=1` on purpose). They run the in-memory arm only, on a sample of the engine arm's files."
  []
  (into {}
        (for [{:keys [host drv]} (xh/engines {:hosts [:java :csharp :c :pod] :on-fail (fn [& m] (println "  FAIL" (apply str m)))})]
          [(name host) [drv]])))

(def extra-arms [["bytes" ["--out=bundle"]]])

(defn base-flags [] ["--out=bundle"])
