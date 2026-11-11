;; D886: version.edn is authoritative; tracked release fields are 0.0.0-dev.
;; --stamp FILE... and --stamp-copy SRC DST only operate on build/stage copies. set/bump touch version.edn alone.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def semver-re #"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?")

;; The inventory (DEVLOG D525). :n = how many times the pattern must match in the file.
(def targets
  [{:file "release/pkg/ppocr/package.json"
    :what "optional npm @3sln/emon-ppocr"
    :re #"(?m)(^  \"version\": \")([^\"]+)(\")" :n 1}
   {:file "release/pkg/ppocr/package.json"
    :what "optional PP package: matching SDK peer"
    :re #"(\"@3sln/emon\": \"\^)([^\"]+)(\")" :n 1}
   {:file "host/js/package.json"
    :what "npm @3sln/emon (WASM/JS SDK)"
    :re #"(?m)(^  \"name\": \"@3sln/emon\",\n  \"version\": \")([^\"]+)(\")" :n 1}
   {:file "host/js/package-lock.json"
    :what "npm lock: the root package, twice"
    :re #"(\"name\": \"@3sln/emon\",\s*\"version\": \")([^\"]+)(\")" :n 2}
   ;; @3sln/pdfium-wasm (D553) tracks emon's version; @3sln/emon's optional peer range names it (^X.Y.Z), in the manifest and in its lock
   {:file "release/pkg/pdfium-wasm/package.json"
    :what "npm @3sln/pdfium-wasm (pdfium's WebAssembly build)"
    :re #"(?m)(^  \"name\": \"@3sln/pdfium-wasm\",\n  \"version\": \")([^\"]+)(\")" :n 1}
   {:file "host/js/package.json"
    :what "npm @3sln/emon: the optional peer range of @3sln/pdfium-wasm (^X.Y.Z)"
    :re #"(\"@3sln/pdfium-wasm\": \"\^)([^\"]+)(\")" :n 1}
   {:file "host/js/package-lock.json"
    :what "npm lock: the same peer range"
    :re #"(\"@3sln/pdfium-wasm\": \"\^)([^\"]+)(\")" :n 1}
   {:file "kin/scripts/emon-rs-build"
    :what "Rust crate `emon` (Cargo.toml assembled here)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "host/cli/Cargo.toml"
    :what "Rust package `emon`, the command-line tool (P11.4b)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "kin/scripts/emon-bind-build"
    :what "Rust crate `emon` as C library and pod (Cargo.toml assembled here)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "kin/scripts/emon-wasm-build"
    :what "Rust crate `emon` for wasm32 (Cargo.toml assembled here)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "kin/scripts/unit"
    :what "Rust crate `emon` of the unit-test arm (Cargo.toml assembled here)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "host/rust/Cargo.lock"
    :what "Cargo.lock entry of the emon crate"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "host/c/src/capi.rs"
    :what "C binding: emon_version()"
    :re #"(static VERSION: &\[u8\] = b\")([^\"\\]+)(\\0\")" :n 1}
   {:file "host/pod/src/main.rs"
    :what "babashka pod: emon/capabilities :version"
    :re #"(\(\"version\", s\(\")([^\"]+)(\"\)\))" :n 1}
   ;; the published manifests of `bb package` (P11.4, D542), committed under release/pkg/. Go has none: its module version IS the tag
   {:file "release/pkg/rust/Cargo.toml"
    :what "crates.io crate `emon` (the published manifest)"
    :re #"(?m)(^name = \"emon\"\nversion = \")([^\"]+)(\")" :n 1}
   {:file "release/pkg/java/pom.xml"
    :what "Maven com.3sln:emon (the POM)"
    :re #"(<artifactId>emon</artifactId>\s*<version>)([^<]+)(</version>)" :n 1}
   {:file "release/pkg/csharp/Emon.csproj"
    :what "NuGet Emon (the project's <Version>)"
    :re #"(<Version>)([^<]+)(</Version>)" :n 1}])

;; how many DISTINCT files the targets and version.edn make (a file may hold two targets: host/js/package.json has its version and the peer range)
(def n-files (inc (count (distinct (map :file targets)))))

(defn- die [code & msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit code))

(defn- path [root f] (str (fs/path root f)))

(defn- find-versions
  "The versions a target's file holds: a seq of strings (one per match)."
  [root {:keys [file re]}]
  (let [p (path root file)]
    (when (fs/exists? p)
      (mapv #(nth % 2) (re-seq re (slurp p))))))

(defn current [root]
  (let [p (path root "version.edn")
        v (:version (edn/read-string (slurp p)))]
    (when-not (and (string? v) (re-matches semver-re v))
      (throw (ex-info (str "version.edn does not hold {:version \"X.Y.Z\"}: " (pr-str v)) {})))
    v))

(defn mismatches
  "Central version differs from want, or a tracked release field is not the placeholder: [file what found]."
  [root want]
  (let [cur (try (current root) (catch Exception e (str "unreadable (" (ex-message e) ")")))
        base (if (= cur want) [] [["version.edn" "the single source" cur]])]
    (into base
          (mapcat (fn [{:keys [file what n] :as t}]
                    (let [vs (find-versions root t)]
                      (cond
                        (nil? vs) [[file what "file missing"]]
                        (not= n (count vs)) [[file what (str "expected " n " version field(s), found " (count vs))]]
                        :else (for [v vs :when (not= v "0.0.0-dev")] [file what v])))))
          targets)))

(def placeholder "0.0.0-dev")

(defn stamp-text [text version]
  (str/replace text placeholder version))

(defn stamp-file! [file version]
  (let [before (slurp file) after (stamp-text before version)]
    (when-not (= before after) (spit file after))))

(defn stamp-copy! [src dst version]
  (let [after (stamp-text (slurp src) version)]
    (when-let [parent (fs/parent dst)] (fs/create-dirs parent))
    (when-not (and (fs/exists? dst) (= after (slurp dst))) (spit dst after))))

(defn set-version! [root v]
  (when-not (re-matches semver-re v)
    (throw (ex-info (str "not a version: " v) {:usage true})))
  (spit (path root "version.edn") (str "{:version " (pr-str v) "}\n")))

(defn bump [v part]
  (let [[a b c] (map parse-long (str/split (re-find #"^\d+\.\d+\.\d+" v) #"\."))]
    (case part
      "major" (str (inc a) ".0.0")
      "minor" (str a "." (inc b) ".0")
      "patch" (str a "." b "." (inc c)))))

(def staged-files
  {"release/pkg/rust/Cargo.toml" ["rust/Cargo.toml" "native/Cargo.toml"]
   "host/rust/Cargo.lock" ["rust/Cargo.lock" "native/Cargo.lock"]
   "host/c/src/capi.rs" ["native/src/capi.rs"]
   "host/pod/src/main.rs" ["native/src/pod.rs"]
   "release/pkg/java/pom.xml" ["java/pom.xml"]
   "release/pkg/csharp/Emon.csproj" ["csharp/Emon.csproj"]
   "host/js/package.json" ["js/package.json"]
   "release/pkg/pdfium-wasm/package.json" ["js-pdfium/package.json"]
   "release/pkg/ppocr/package.json" ["js-ppocr/package.json"]})

(defn staged-mismatches [stage want]
  (vec (for [{:keys [file re n what]} targets
             staged (get staged-files file)
             :let [p (path stage staged)] :when (fs/exists? p)
             :let [versions (map #(nth % 2) (re-seq re (slurp p)))]
             :when (or (not= n (count versions)) (some #(not= want %) versions))]
         [p what (str "staged version fields: " (pr-str versions) "; want " want)])))

(defn check [root tag & [source-only?]]
  (let [want (str/replace tag #"^v" "")]
    (when-not (re-matches semver-re want)
      (die 2 "bb version --check wants a tag like v1.2.3 or 1.2.3, got" (pr-str tag)))
    (let [bad (concat (mismatches root want) (when-not source-only? (staged-mismatches (path root "dist/stage") want)))]
      (if (empty? bad)
        (println (str "ok: templates are placeholders; version.edn is " want " (" n-files " files)"))
        (do (println (str "version mismatch, want " want ":"))
            (doseq [[f what found] bad] (println (str "  " f " (" what "): " found)))
            (System/exit 1))))))

;; Self-tests use a copy; a bump must leave every tracked template untouched.
(defn self-test []
  (let [tmp (str (fs/create-temp-dir {:prefix "emon-version-"}))
        files (distinct (cons "version.edn" (map :file targets)))
        snap (into {} (for [f files] [f (slurp f)]))]
    (try
      (doseq [[f text] snap]
        (fs/create-dirs (fs/parent (path tmp f))) (spit (path tmp f) text))
      (set-version! tmp "9.8.7-rc.1")
      (assert (empty? (mismatches tmp "9.8.7-rc.1")))
      (doseq [f (remove #{"version.edn"} files)] (assert (= (snap f) (slurp (path tmp f)))))
      (assert (= "9.9.0" (bump "9.8.7-rc.1" "minor")))
      (assert (= "9.8.8" (bump "9.8.7" "patch")))
      (assert (= "10.0.0" (bump "9.8.7" "major")))
      (assert (seq (mismatches tmp "9.8.8")))
      (let [f "host/c/src/capi.rs" p (path tmp f)]
        (spit p (str/replace (snap f) placeholder "1.2.3"))
        (assert (= [f] (mapv first (mismatches tmp (current tmp)))))
        (spit p (str/replace (snap f) "static VERSION" "static RENAMED"))
        (assert (= [f] (mapv first (mismatches tmp (current tmp)))))
        (spit p (snap f)))
      (assert (= "version=9.8.7-rc.1" (stamp-text "version=0.0.0-dev" (current tmp))))
      (doseq [f (distinct (map :file targets))] (stamp-file! (path tmp f) (current tmp)))
      (doseq [{:keys [n] :as target} targets]
        (assert (= (repeat n (current tmp)) (find-versions tmp target))))
      (let [f (path tmp "release/pkg/rust/Cargo.toml")]
        (stamp-file! f (current tmp))
        (assert (str/includes? (slurp f) "9.8.7-rc.1"))
        (let [stage (path tmp "dist/stage") staged (path stage "rust/Cargo.toml")]
          (fs/create-dirs (fs/parent staged)) (fs/copy f staged)
          (assert (empty? (staged-mismatches stage "9.8.7-rc.1")))
          (assert (seq (staged-mismatches stage "9.8.8")))
          (java.nio.file.Files/setLastModifiedTime (fs/path staged) (java.nio.file.attribute.FileTime/fromMillis 123000))
          (stamp-copy! "release/pkg/rust/Cargo.toml" staged (current tmp))
          (assert (= 123000 (.toMillis (fs/last-modified-time staged))))
          (stamp-copy! "release/pkg/rust/Cargo.toml" staged "9.8.8")
          (assert (empty? (staged-mismatches stage "9.8.8")))
          (spit staged (snap "release/pkg/rust/Cargo.toml"))
          (assert (seq (staged-mismatches stage "9.8.7-rc.1")))))
      (assert (= snap (into {} (for [f files] [f (slurp f)]))))
      (println "self-test ok: one-file bump, placeholders, staged stamping, prereleases, source isolation")
      (finally (fs/delete-tree tmp)))))

;; ------------------------------------------------------------------------------------------------ main

(defn -main [args]
  (let [root (str (fs/absolutize "."))
        [a b & more] args]
    (try
      (cond
        (empty? args) (println (current root))
        (and (= a "--stamp-copy") b (= 1 (count more))) (stamp-copy! b (first more) (current root))
        (= a "--stamp") (doseq [f (rest args)] (stamp-file! f (current root)))
        (and (= a "set") b (empty? more)) (do (set-version! root b) (println (str "set " b " in version.edn")))
        (and (= a "bump") (#{"major" "minor" "patch"} b) (empty? more))
        (let [v (bump (current root) b)] (set-version! root v) (println (str "bumped to " v)))
        (and (= a "--check") b (or (empty? more) (= ["--source-only"] (vec more)))) (check root b (seq more))
        (and (= a "--self-test") (nil? b)) (self-test)
        :else (die 2 "usage: bb version | set X.Y.Z | bump major|minor|patch | --check TAG | --self-test"))
      (catch clojure.lang.ExceptionInfo e
        (die (if (:usage (ex-data e)) 2 1) (ex-message e))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
