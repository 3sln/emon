;; Heavy emon runs on the spool runner.   bb spool-multishot --help   (DEVLOG D9xx)
;;
;;   bb spool-multishot driver                     build (or reuse) the Linux x86_64 driver, cached by the hash of its inputs
;;   bb spool-multishot prepare [multishot flags] --shards K
;;                                                 driver + babashka + tessdata -> the toolchain layer, crops -> the cases layer
;;   (submit .cache/spool/shards/I/Taskfile.json for each I as its own task through the spool MCP, wait, and download each shard's
;;    out/shard.edn to .cache/spool/results/shard-I.edn)
;;   bb spool-multishot merge [--output FILE]      join the shard files into the normal sweep/gate report and verify it
;;
;; Tasks on spool have no network and no compiler, so the toolchain is built here, in a digest-pinned Docker container that
;; cross-compiles natively (no emulation), and shipped as a layer. The crops are cut here (pinned Pillow and NumPy), because
;; the runner has no Python; the shards only run the driver and score.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '(java.security MessageDigest))

(def pins (edn/read-string (slurp "tools/spool/pins.edn")))
(def work (fs/path ".cache/spool"))
(def args (vec *command-line-args*))
(defn die [& xs] (binding [*out* *err*] (println (str "spool: " (apply str xs)))) (System/exit 1))
(defn hex [bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
(defn sha256-file [f] (hex (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes f))))

(defn input-files []
  (->> (concat (fs/glob "gen/rust/emon" "*.rs") (fs/glob "host/rust/src" "*.rs") (fs/glob "tools/native" "*.{edn,clj,txt,cmake}")
               (map fs/path ["host/rust/Cargo.lock" "host/rust/tests/engine.rs" "host/rust/tests/adapters.rs" "test/driver/rust/main.rs"
                             "kin/scripts/emon-rs-build" "version.edn" "tools/spool/pins.edn" "tools/spool/build_driver.clj"]))
       (map str)
       (remove #(re-find #"(test|probe)\.rs$" %))
       sort))

(defn input-hash []
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [f (input-files)] (.update md (.getBytes (str f "\n" (sha256-file f) "\n"))))
    (subs (hex (.digest md)) 0 16)))

(defn opt [flag] (second (drop-while #(not= flag %) args)))

;; ---- the Linux driver, cached by the hash of its inputs ----
(defn driver-dir [] (fs/path work "driver" (input-hash)))

(defn clean-build!
  "The container builds as root, so its files cannot be deleted from here; it removes them."
  [build]
  (when (fs/exists? build)
    (shell {:continue true :out :nil :err :nil} "docker" "run" "--rm" "-v" (str (fs/absolutize build) ":/build") (:builder-image pins)
           "sh" "-c" "rm -rf /build/* /build/.[!.]*")
    (fs/delete-tree build)))

(defn build-driver! []
  (let [out (driver-dir)]
    (if (fs/exists? (fs/path out "emon-rs"))
      (println "driver up to date:" (str out))
      (let [build (fs/path work "build") dl (fs/path work "native-dl")
            main-dl (fs/path ".cache/native/dl")]
        (when-not (zero? (:exit (shell {:continue true :out :string :err :string} "docker" "info")))
          (die "Docker is not running"))
        (clean-build! build)
        (fs/create-dirs build) (fs/create-dirs dl) (fs/create-dirs out)
        (when (fs/exists? main-dl) (fs/copy-tree main-dl dl {:replace-existing true}))
        (doseq [d ["tools" "kin/scripts" "gen/rust" "test/driver/rust" "release"] :when (fs/exists? d)]
          (fs/create-dirs (fs/parent (fs/path build d)))
          (fs/copy-tree d (fs/path build d)))
        (fs/create-dirs (fs/path build "host/rust"))
        (fs/copy-tree "host/rust" (fs/path build "host/rust"))
        (fs/copy "version.edn" (fs/path build "version.edn"))
        ;; the container needs no kin: the repo's own bb.edn would fetch it with git and a JVM
        (spit (str (fs/path build "bb.edn")) "{:paths [\"tools\" \"tools/lib\"]}\n")
        (let [bb-pin (get-in pins [:babashka :linux-aarch64])
              bb-tgz (fs/path work "dl" (:file bb-pin))]
          (fs/create-dirs (fs/path work "dl"))
          (when-not (and (fs/exists? bb-tgz) (= (:sha256 bb-pin) (sha256-file bb-tgz)))
            (shell "curl" "-fsSL" "-o" (str bb-tgz)
                   (-> (:babashka-url pins) (str/replace "@VERSION@" (get-in pins [:babashka :version])) (str/replace "@FILE@" (:file bb-pin)))))
          (when-not (= (:sha256 bb-pin) (sha256-file bb-tgz)) (die (:file bb-pin) ": sha256 is not the pinned one"))
          (fs/create-dirs (fs/path build "bin"))
          (shell "tar" "-xzf" (str bb-tgz) "-C" (str (fs/path build "bin"))))
        (println "building the driver in" (:builder-image pins) "(first time: several minutes)")
        (shell "docker" "run" "--rm"
               "-v" (str (fs/absolutize build) ":/build")
               "-v" (str (fs/absolutize out) ":/out")
               "-v" (str (fs/absolutize dl) ":/build/.cache/native/dl")
               "-e" "PATH=/build/bin:/usr/local/cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
               (:builder-image pins) "bb" "-f" "/build/tools/spool/build_driver.clj")
        (clean-build! build)))
    out))

;; ---- layers ----
(defn stage-toolchain! [driver]
  (let [tc (fs/path work "toolchain" "spool") bb-pin (get-in pins [:babashka :linux-amd64])
        bb-tgz (fs/path work "dl" (:file bb-pin))]
    (fs/delete-tree (fs/path work "toolchain"))
    (fs/create-dirs (fs/path tc "bin")) (fs/create-dirs (fs/path tc "tessdata")) (fs/create-dirs (fs/path work "dl"))
    (when-not (and (fs/exists? bb-tgz) (= (:sha256 bb-pin) (sha256-file bb-tgz)))
      (shell "curl" "-fsSL" "-o" (str bb-tgz)
             (-> (:babashka-url pins) (str/replace "@VERSION@" (get-in pins [:babashka :version])) (str/replace "@FILE@" (:file bb-pin)))))
    (when-not (= (:sha256 bb-pin) (sha256-file bb-tgz)) (die (:file bb-pin) ": sha256 is not the pinned one"))
    ;; the runner refuses a layer file of about 16 MB or more, so the two binaries ship gzipped and cut into 10 MB parts; the task command joins them
    (shell "tar" "-xzf" (str bb-tgz) "-C" (str (fs/path tc "bin")))
    (fs/copy (fs/path driver "emon-rs") (fs/path tc "bin" "emon-rs") {:replace-existing true})
    (doseq [f ["bb" "emon-rs"]]
      (shell {:out (str (fs/file (fs/path tc "bin" (str f ".gz"))))} "gzip" "-9nc" (str (fs/path tc "bin" f)))
      (fs/delete (fs/path tc "bin" f))
      (shell {:dir (str (fs/path tc "bin"))} "split" "-b" "10m" (str f ".gz") (str f ".gz.part-"))
      (fs/delete (fs/path tc "bin" (str f ".gz"))))
    ;; tessdata: the files native.edn pins (the driver reads osd for orientation and eng for text), from the build's own verified downloads
    (doseq [{:keys [file sha256]} (:data (edn/read-string (slurp "tools/native/native.edn")))
            :let [from (fs/path work "native-dl" file)]]
      (when-not (and (fs/exists? from) (= sha256 (sha256-file from))) (die file ": missing or not the pinned sha256 in " (str from)))
      (fs/copy from (fs/path tc "tessdata" file) {:replace-existing true}))))

(def layer-bytes
  "A layer the MCP will build stays under about 32 MB (a 23 MB one went up, a 42 MB one was refused); cases are packed into layers of at most this."
  (* 20 1024 1024))

(defn tree-bytes [p] (reduce + (map fs/size (filter fs/regular-file? (file-seq (fs/file p))))))

(defn write-case-layers!
  "Per shard I of K (cases with index mod K = I): .cache/spool/shards/I/layers.json cuts that shard's cases into layers of at most
  layer-bytes, and .cache/spool/shards/I/Taskfile.json is the shared task with --shard I/K. A task needs only its own cases, so a
  sweep of any size fits (16 layers a task, 2 GB); shards are submitted as separate tasks."
  [cases shards]
  (let [n (count (:cases (edn/read-string (slurp (str (fs/path cases "cases.edn"))))))
        cost (fn [i] (+ (tree-bytes (fs/path cases (str i)))
                        (reduce + (map fs/size (fs/glob cases (str "other-" i ".*"))))))
        tmpl (json/parse-string (slurp "tools/spool/Taskfile.json"))]
    (fs/delete-tree (fs/path work "shards"))
    (doseq [s (range shards)]
      (let [mine (filter #(= s (mod % shards)) (range n))
            groups (reduce (fn [gs i]
                             (let [c (cost i) cur (peek gs)]
                               (if (and cur (<= (+ (:bytes cur) c) layer-bytes))
                                 (conj (pop gs) (-> cur (update :bytes + c) (update :cases conj i)))
                                 (conj gs {:bytes c :cases [i]}))))
                           [] mine)
            dir (fs/path work "shards" (str s))]
        (when (> (count groups) 13)
          (die "shard " s " needs " (count groups) " layers and a task has 16 in all: use more --shards"))
        (fs/create-dirs dir)
        (spit (str (fs/path dir "layers.json"))
              (str (json/generate-string
                    {:version "0.1" :kind "base"
                     :layers (vec (map-indexed
                                   (fn [k {:keys [cases]}]
                                     {:id (str "cases-" k) :kind "directory" :path "../../cases"
                                      :include (vec (concat (when (zero? k) ["cases/cases.edn"])
                                                            (mapcat (fn [i] [(str "cases/" i "/**") (str "cases/other-" i ".*")]) cases)))})
                                   groups))}
                    {:pretty true}) "\n"))
        (spit (str (fs/path dir "Taskfile.json"))
              (str (json/generate-string
                    (-> tmpl
                        (assoc "extends" ["../../../../tools/spool/base.json" "layers.json"])
                        (update-in ["command" "argv" 2] str/replace "\"$SPOOL_SHARD_INDEX/$SPOOL_SHARD_COUNT\"" (str s "/" shards)))
                    {:pretty true}) "\n"))))
    (println shards "shard tasks under" (str (fs/path work "shards")))))

(defn prepare! []
  (let [shards (some-> (opt "--shards") parse-long)
        _ (when-not (and shards (<= 2 shards 64)) (die "prepare needs --shards K (2 to 64)"))
        driver (build-driver!)
        passthrough (loop [xs args out []] (cond (empty? xs) out
                                                 (= "--shards" (first xs)) (recur (drop 2 xs) out)
                                                 (#{"--prepare" "--prepared" "--shard" "--merge" "--bin"} (first xs)) (die (first xs) " is set by spool-multishot")
                                                 :else (recur (rest xs) (conj out (first xs)))))
        cases (fs/path work "cases" "cases")]
    (stage-toolchain! driver)
    (fs/delete-tree (fs/path work "cases"))
    (fs/delete-tree (fs/path work "results"))
    (fs/create-dirs (fs/path work "results"))
    (apply shell "bb" "--config" "tools/multishot/bb.edn" "tools/multishot/multishot.clj"
           (concat (rest passthrough) ["--ocr-engine" "tesseract" "--prepare" (str cases)]))
    (write-case-layers! cases shards)
    (spit (str (fs/path work "run.edn")) (pr-str {:shards shards :cases (str cases)}))
    (println (str "\nPrepared. Next: submit each " work "/shards/I/Taskfile.json (I in 0.." (dec shards) ") as its own task through the spool MCP, wait_task each, download_artifact out/shard.edn of shard I to "
                  work "/results/shard-I.edn, then: bb spool-multishot merge"))))

(defn merge! []
  (let [{:keys [shards cases]} (edn/read-string (slurp (str (fs/path work "run.edn"))))
        files (mapv #(str (fs/path work "results" (str "shard-" % ".edn"))) (range shards))
        missing (remove fs/exists? files)]
    (when (seq missing) (die "missing shard results: " (str/join ", " missing)))
    (shell "bb" "--config" "tools/multishot/bb.edn" "tools/multishot/multishot.clj"
           "--prepared" (str (fs/path cases)) "--merge" (str/join "," files)
           "--output" (or (opt "--output") (str (fs/path (or (System/getenv "EMON_SCRATCH") ".cache/scratch") "multishot" "report.json"))))))

(case (first args)
  "driver" (build-driver!)
  "layers" (let [{:keys [shards cases]} (edn/read-string (slurp (str (fs/path work "run.edn"))))] (write-case-layers! (fs/path cases) shards))
  "stage" (stage-toolchain! (build-driver!))
  "prepare" (prepare!)
  "merge" (merge!)
  (println "bb spool-multishot driver | stage | layers | prepare [multishot flags: --ci | --sweep, --seed N, --n N ...] --shards K | merge [--output FILE]"))
