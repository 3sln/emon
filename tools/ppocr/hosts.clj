#!/usr/bin/env bb
;; Live, optional qualification. No fetching, policy changes or corpus writes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process shell]]
         '[cheshire.core :as json] '[clojure.string :as str])
(import '[java.security MessageDigest])
(def args (apply hash-map *command-line-args*))
(assert (every? #{"--qualification" "--out" "--limit" "--skip-build"} (keys args)) "unknown ppocr-hosts option")
(def qual (get args "--qualification" "/Volumes/SSK SSD/emon-scratch/op1c"))
(def out (str (fs/absolutize (get args "--out" "/Volumes/SSK SSD/emon-scratch/op2a/hosts"))))
(def env {"EMON_CS_SHARED_COMPILATION" "false" "NuGetAudit" "false"})
(defn space! []
 (doseq [p ["/System/Volumes/Data" "/Volumes/SSK SSD"]]
  (assert (> (.getUsableSpace (java.io.File. p)) (* 10 1024 1024 1024)) (str "less than 10 GiB free: " p))))
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
(run! "go-probe-build" ["go" "build" "-tags" "ppocr" "-o" "ppocr-hosts" "./cmd/ppocr-hosts"] {:dir ".cache/emon-go"})
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
(run! "c-probe-build" ["cc" "-Wall" "-Wextra" "-Werror" "tools/ppocr/hosts.c" "-Ihost/native/include" "-L.cache/ppocr" "-lemon_ppocr_adapter" (str "-Wl,-rpath," (fs/absolutize ".cache/ppocr")) "-o" ".cache/ppocr/hosts-c"])
(run! "c-contract-build" ["cc" "-Wall" "-Wextra" "-Werror" "tools/ppocr/contracts.c" "-Ihost/native/include" "-L.cache/ppocr" "-lemon_ppocr_adapter" (str "-Wl,-rpath," (fs/absolutize ".cache/ppocr")) "-o" ".cache/ppocr/contracts-c"])
(run! "c-contract" [".cache/ppocr/contracts-c"])
(def rows
 (mapv (fn [row]
   (let [old (:file row) remapped (str/replace old "/op1b/" "/op1c/")
         file (if (fs/regular-file? old) old remapped)]
    (assert (fs/regular-file? file) (str "qualification bitmap missing: " file))
    (assoc row :file file)))
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
 (def historical (into {} (map (juxt :id :sha256) (concat
    (json/parse-string (slurp (str qual "/repeats/summary.json")) true)
    (json/parse-string (slurp (str qual "/fresh-repeats/summary.json")) true)))))
 (def expected (atom nil))
 (def results (atom []))
 (doseq [[host argv] commands]
  (println "live" host (count selected) "bitmaps") (flush)
  (let [file (str out "/" host ".answers")
        result @(process argv {:extra-env env :in (fs/file manifest) :out file :err (str out "/" host ".err") :shutdown nil})]
   (assert (zero? (:exit result)) (str host " failed; see " out "/" host ".err"))
   (let [answers (mapv json/parse-string (str/split-lines (slurp file)))]
    (assert (= (count selected) (count answers)) (str host " incomplete answers"))
    (when @expected (doseq [k (range (count selected))] (assert (= (@expected k) (answers k)) (str host " answer differs: " (:id (selected k))))))
    (when-not @expected
      (doseq [[row answer] (map vector selected (str/split-lines (slurp file)))]
        (let [sha (apply str (map #(format "%02x" (bit-and % 255))
                       (.digest (MessageDigest/getInstance "SHA-256") (.getBytes answer "UTF-8"))))]
          (assert (= (historical (:id row)) sha) (str "qualified Rust answer changed: " (:id row)))))
      (reset! expected answers))
    (swap! results conj {:host host :bitmaps (count answers) :equal true})
    (spit (str out "/summary.json") (json/generate-string {:complete (= 6 (count @results)) :qualification (= 225 (count selected)) :results @results} {:pretty true})))))
 (finally (fs/delete-tree packed)))
(if (get args "--limit")
  (println "PP-OCR development transport smoke passed; full qualification was not requested.")
  (do
    (run! "integrated-models" ["bb" "tools/ppocr/models.clj" "--out" (str out "/models") "--qualification" qual])
    (println "PP-OCR live answers and integrated Model/replay contracts agree.")))
