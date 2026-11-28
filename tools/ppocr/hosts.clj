#!/usr/bin/env bb
;; Live, optional qualification. No fetching, policy changes or corpus writes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process shell]]
         '[cheshire.core :as json] '[clojure.string :as str] '[clojure.edn :as edn]
         '[ppocr.qualification :as qualification])
(import '[java.security MessageDigest])
(def args (apply hash-map *command-line-args*))
(assert (every? #{"--qualification" "--out" "--limit" "--skip-build" "--record-qualification"} (keys args)) "unknown ppocr-hosts option")
(def qual (get args "--qualification" "corpus/_qual/ppocr-qual"))
(def out (str (fs/absolutize (get args "--out" (str (or (System/getenv "EMON_SCRATCH") ".cache/scratch") "/op2a/hosts")))))
(def env {"EMON_CS_SHARED_COMPILATION" "false" "NuGetAudit" "false"})
(def artifact (get args "--record-qualification"))
(assert (not (and artifact (get args "--limit"))) "a partial run cannot record qualification")
(when artifact
  (assert (not (fs/exists? artifact)) "refusing to overwrite a qualification artifact"))
(defn sha256 [bytes]
  (apply str (map #(format "%02x" (bit-and % 255))
                 (.digest (MessageDigest/getInstance "SHA-256") bytes))))
(defn space! []
 (doseq [p ["."]]
  (let [gib (parse-long (or (System/getenv "EMON_MIN_FREE_GIB") "10"))] (assert (> (.getUsableSpace (java.io.File. p)) (* gib 1024 1024 1024)) (str "less than " gib " GiB free: " p)))))
(space!)
(fs/create-dirs out)
(defn run! [label argv & [opts]]
 (space!)
 (let [result @(process argv (merge {:extra-env env :out (str out "/" label ".log") :err :out :shutdown nil} opts))]
  (assert (zero? (:exit result)) (str label " failed; see " out "/" label ".log"))))
(when-not (= "true" (get args "--skip-build"))
 (run! "shared-build" ["bb" "tools/ppocr/build_shared.clj"])
 (run! "go-build" ["kin/scripts/emon-go-build"])
 (run! "java-build" ["kin/scripts/emon-java-build"])
 (run! "csharp-build" ["kin/scripts/emon-cs-build"]))
(def probe (str (fs/absolutize ".cache/emon-rs-ppocr/target/release/ppocr-hosts")))
(def jdk (or (System/getenv "EMON_JDK") "/opt/homebrew/opt/openjdk"))
(fs/create-dirs ".cache/emon-go/cmd/ppocr-hosts")
(fs/copy "tools/ppocr/hosts.go" ".cache/emon-go/cmd/ppocr-hosts/main.go" {:replace-existing true})
(run! "go-probe-build" ["go" "build" "-o" "ppocr-hosts" "./cmd/ppocr-hosts"] {:dir ".cache/emon-go"})
(when (= "Mac OS X" (System/getProperty "os.name")) (run! "go-probe-sign" ["codesign" "--force" "--sign" "-" ".cache/emon-go/ppocr-hosts"]))
(run! "java-probe-build" [(str jdk "/bin/javac") "-cp" ".cache/emon-java/classes" "-d" ".cache/emon-java/classes" "tools/ppocr/Hosts.java"])
(fs/create-dirs ".cache/ppocr-hosts-cs")
(def csproj (slurp ".cache/emon-cs/emon-cs.csproj"))
(def framework (second (re-find #"<TargetFramework>([^<]+)</TargetFramework>" csproj)))
(spit ".cache/ppocr-hosts-cs/probe.csproj"
 (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>" framework "</TargetFramework><OutputType>Exe</OutputType><UseSharedCompilation>false</UseSharedCompilation></PropertyGroup><ItemGroup><Reference Include=\"emon-cs\"><HintPath>../emon-cs/out/emon-cs.dll</HintPath></Reference></ItemGroup></Project>"))
(fs/copy "tools/ppocr/Hosts.cs" ".cache/ppocr-hosts-cs/Hosts.cs" {:replace-existing true})
(def dotnet (first (str/split (str/trim (:out @(process ["kin/scripts/dotnet-sdk"] {:out :string :shutdown nil}))) #" ")))
(run! "csharp-probe-build" [dotnet "build" ".cache/ppocr-hosts-cs/probe.csproj" "-c" "Release" "-o" ".cache/ppocr-hosts-cs/out" "-p:NuGetAudit=false"])
(def ext (if (= "Mac OS X" (System/getProperty "os.name")) "dylib" "so"))
(when (= ext "dylib") (run! "adapter-install-name" ["install_name_tool" "-id" "@rpath/libemon_ppocr_adapter.dylib" ".cache/ppocr/libemon_ppocr_adapter.dylib"]))
(when (= ext "dylib") (run! "adapter-sign" ["codesign" "--force" "--sign" "-" ".cache/ppocr/libemon_ppocr_adapter.dylib"]))
(run! "c-probe-build" ["cc" "-Wall" "-Wextra" "-Werror" "tools/ppocr/hosts.c" "-Ihost/c/include" "-L.cache/ppocr" "-lemon_ppocr_adapter" (str "-Wl,-rpath," (fs/absolutize ".cache/ppocr")) "-o" ".cache/ppocr/hosts-c"])
(run! "c-contract-build" ["cc" "-Wall" "-Wextra" "-Werror" "tools/ppocr/contracts.c" "-Ihost/c/include" "-L.cache/ppocr" "-lemon_ppocr_adapter" (str "-Wl,-rpath," (fs/absolutize ".cache/ppocr")) "-o" ".cache/ppocr/contracts-c"])
(run! "c-contract" [".cache/ppocr/contracts-c"])
(def rows
 (mapv (fn [row]
   (assoc row :file (qualification/bitmap-file qual row)))
  (concat (json/parse-string (slurp (str qual "/bitmaps.json")) true)
          (json/parse-string (slurp (str qual "/fresh-bitmaps.json")) true))))
(assert (= 225 (count rows)) "qualification must contain exactly 225 bitmaps")
(assert (= 225 (count (set (map :file rows)))) "duplicate qualification bitmap")
(def selected (if-let [limit (get args "--limit")] (subvec rows 0 (parse-long limit)) rows))
(def packed (str out "/packed"))
(fs/create-dirs packed)
(try
 (doseq [[k row] (map-indexed vector selected)]
  (run! (str "prepare-" k) [probe "prepare" (:file row) (str packed "/" k ".raw")]))
 (def manifest (str out "/inputs.txt"))
 (spit manifest (str (str/join "\n" (map #(str packed "/" % ".raw") (range (count selected)))) "\n"))
 (def commands [["rust" [probe "reference"]]
                ["go" [(str (fs/absolutize ".cache/emon-go/ppocr-hosts"))]]
                ["java" [(str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" ".cache/emon-java/classes" "Hosts"]]
                ["csharp" [dotnet ".cache/ppocr-hosts-cs/out/probe.dll"]]
                ["c" [(str (fs/absolutize ".cache/ppocr/hosts-c"))]]
                ["c-pod-native" [probe "native"]]])
 (def reference-file (str qual "/reference.answers"))
 (when-not (or artifact (get args "--limit"))
   (assert (fs/regular-file? reference-file)
           "OIL qualification needs reference.answers; record a new Rust reference with --record-qualification"))
 (def expected (atom nil))
 (def results (atom []))
 (doseq [[host argv] commands]
  (println "live" host (count selected) "bitmaps") (flush)
  (let [file (str out "/" host ".answers")
        result @(process argv {:extra-env env :in (fs/file manifest) :out file :err (str out "/" host ".err") :shutdown nil})]
   (assert (zero? (:exit result)) (str host " failed; see " out "/" host ".err"))
   (let [answer-bytes (fs/read-all-bytes file)
         answers (mapv json/parse-string (str/split-lines (slurp file)))]
    (assert (= (count selected) (count answers)) (str host " incomplete answers"))
    (when @expected
      (assert (java.util.Arrays/equals ^bytes (fs/read-all-bytes (str out "/rust.answers")) ^bytes answer-bytes)
              (str host " answer bytes differ from Rust")))
    (when-not @expected
      (when-not (or artifact (get args "--limit"))
        (assert (java.util.Arrays/equals ^bytes (fs/read-all-bytes reference-file) ^bytes answer-bytes)
                "Rust answer bytes differ from the OIL qualification reference"))
      (reset! expected answers))
    (swap! results conj {:host host :bitmaps (count answers) :equal true})
    (spit (str out "/summary.json") (json/generate-string {:complete (= 6 (count @results)) :qualification (and (not (get args "--limit")) (= 225 (count selected))) :results @results} {:pretty true})))))
 (when-not (get args "--limit")
   (let [repeat-manifest (str out "/repeat-inputs.txt")
         reference (slurp (str out "/rust.answers"))]
     (spit repeat-manifest (apply str (repeat 3 (slurp manifest))))
     (doseq [process-index (range 3)]
       (let [path (str out "/rust-repeat-" process-index ".answers")]
         (run! (str "rust-repeat-" process-index) [probe "reference"]
               {:in (fs/file repeat-manifest) :out path :err (str path ".err")})
         (assert (= (apply str (repeat 3 reference)) (slurp path))
                 (str "Rust 3x3 determinism differs in process " process-index))))))
 (when artifact
  (let [destination artifact artifact (str out "/qualification-artifact")]
   (assert (not (fs/exists? artifact)) "qualification staging directory already exists")
   (fs/create-dirs (str artifact "/inputs"))
   (let [copied (mapv (fn [k row]
                        (let [name (str "inputs/" k ".png")]
                          (fs/copy (:file row) (str artifact "/" name))
                          (assoc row :file name :sha256 (sha256 (fs/read-all-bytes (:file row))))))
                      (range) selected)
         pins (edn/read-string (slurp "tools/ppocr/assets.edn"))
         graphs (filter #(str/ends-with? (:file %) ".onnx") (:assets pins))
         commit (str/trim (:out @(process ["git" "rev-parse" "HEAD"] {:out :string :shutdown nil})))
         identity (second (re-find #":data \[([0-9 ]+)\]" (slurp "kin/ocrengine.kin")))
         policy (apply str (map #(char (parse-long %)) (str/split identity #" +")))]
     (doseq [{:keys [file] expected-sha :sha256} graphs]
       (assert (= expected-sha (sha256 (fs/read-all-bytes (str ".cache/ppocr/" file))))
               (str "qualification graph differs from pin: " file)))
     (spit (str artifact "/bitmaps.json") (json/generate-string (subvec copied 0 (count (json/parse-string (slurp (str qual "/bitmaps.json"))))) {:pretty true}))
     (spit (str artifact "/fresh-bitmaps.json") (json/generate-string (subvec copied (count (json/parse-string (slurp (str qual "/bitmaps.json"))))) {:pretty true}))
     (fs/copy (str out "/rust.answers") (str artifact "/reference.answers"))
     (spit (str artifact "/PROVENANCE.edn")
           (pr-str {:schema 1 :emon-commit commit
                    :worktree-dirty (not (str/blank? (:out @(process ["git" "status" "--porcelain"] {:out :string :shutdown nil}))))
                    :graphs (mapv #(select-keys % [:file :sha256]) graphs)
                    :ort-version (:version (edn/read-string (slurp "tools/ppocr/runtimes.edn")))
                    :policy-identity policy :counts {:bitmaps (count copied) :transports 6}
                    :source-corpus-shard-ids
                    (get-in (json/parse-string (slurp "test/corpus/shards.json") true)
                            [:consumers (keyword "test/controls/sweep3.txt")])
                    :source {:qualification :op1c :selection "all bitmaps.json then fresh-bitmaps.json, in listed order"}
                    :reference-sha256 (sha256 (fs/read-all-bytes (str artifact "/reference.answers")))
                    :command (into ["bb" "ppocr-hosts"] *command-line-args*)
                    :determinism {:processes 3 :readings 3 :bitmaps (count copied)}
                    :transport-equality true :accuracy-accepted false :complete false})))
   (fs/move artifact destination)))
 (finally (fs/delete-tree packed)))
(if (get args "--limit")
  (println "PP-OCR development transport smoke passed; full qualification was not requested.")
  (do
    (run! "integrated-models" (into ["bb" "tools/ppocr/models.clj" "--out" (str out "/models") "--qualification" qual]
                                  (when artifact ["--record-reference" artifact])))
    (run! "fixed-receipt-accuracy" ["python3" "tools/oracle/ppocr/oil_accuracy.py" "--qualification" (or artifact qual)])
    (when artifact
      (let [path (str artifact "/PROVENANCE.edn")]
        (spit path (pr-str (assoc (edn/read-string (slurp path)) :accuracy-accepted true :complete true)))))
    (println "PP-OCR live answers and integrated Model/replay contracts agree.")))
