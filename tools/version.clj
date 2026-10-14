;; The one place the package version lives (P11.1): `version.edn`, and every manifest that must agree with it.
;;
;;   bb version                       print the version
;;   bb version set X.Y.Z             rewrite version.edn and every manifest
;;   bb version bump major|minor|patch
;;   bb version --check TAG           exit 1 unless every manifest (and version.edn) equals TAG (a leading v is allowed)
;;   bb version --self-test           set/bump/check on a temporary COPY of the manifests; the real tree is not touched
;;
;; Manifests are edited as TEXT: each target is a file and a regex whose group 1 is what comes before the version, group 2 the
;; version and group 3 what follows. Only group 2 is replaced, so every other byte of the file stays as it was (no TOML or JSON
;; is parsed and re-serialised). A target whose pattern does not match the expected number of times is an error, so a manifest
;; that was reformatted cannot silently stop being rewritten. Unknown arguments exit 2.
(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def semver-re #"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?")

;; The inventory (DEVLOG D525). :n = how many times the pattern must match in the file.
(def targets
  [{:file "host/js/package.json"
    :what "npm @3sln/emon (WASM/JS SDK)"
    :re #"(?m)(^  \"name\": \"@3sln/emon\",\n  \"version\": \")([^\"]+)(\")" :n 1}
   {:file "host/js/package-lock.json"
    :what "npm lock: the root package, twice"
    :re #"(\"name\": \"@3sln/emon\",\s*\"version\": \")([^\"]+)(\")" :n 2}
   ;; @3sln/pdfium-wasm (D1542) tracks emon's version; @3sln/emon's optional peer range names it (^X.Y.Z), in the manifest and in its lock
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
  "Every place whose version is not `want`: a seq of [file what found]."
  [root want]
  (let [cur (try (current root) (catch Exception e (str "unreadable (" (ex-message e) ")")))
        base (if (= cur want) [] [["version.edn" "the single source" cur]])]
    (into base
          (mapcat (fn [{:keys [file what n] :as t}]
                    (let [vs (find-versions root t)]
                      (cond
                        (nil? vs) [[file what "file missing"]]
                        (not= n (count vs)) [[file what (str "expected " n " version field(s), found " (count vs))]]
                        :else (for [v vs :when (not= v want)] [file what v])))))
          targets)))

(defn set-version!
  "Rewrite version.edn and every manifest to `v`, touching only the version bytes."
  [root v]
  (when-not (re-matches semver-re v)
    (throw (ex-info (str "not a version (X.Y.Z): " v) {:usage true})))
  ;; check every target BEFORE writing any
  (doseq [{:keys [file n] :as t} targets]
    (let [vs (find-versions root t)]
      (when-not (= n (count vs))
        (throw (ex-info (str file ": expected " n " version field(s), found " (count vs) " (was the file reformatted?)") {})))))
  (spit (path root "version.edn") (str "{:version " (pr-str v) "}\n"))
  (doseq [{:keys [file re]} targets]
    (let [p (path root file)
          s (slurp p)
          out (str/replace s re (fn [[_ a _ c]] (str a v c)))]
      (when-not (= s out) (spit p out)))))

(defn bump [v part]
  (let [[a b c] (map parse-long (str/split (re-find #"^\d+\.\d+\.\d+" v) #"\."))]
    (case part
      "major" (str (inc a) ".0.0")
      "minor" (str a "." (inc b) ".0")
      "patch" (str a "." b "." (inc c)))))

(defn check [root tag]
  (let [want (str/replace tag #"^v" "")]
    (when-not (re-matches semver-re want)
      (die 2 "bb version --check wants a tag like v1.2.3 or 1.2.3, got" (pr-str tag)))
    (let [bad (mismatches root want)]
      (if (empty? bad)
        (println (str "ok: every manifest is " want " (" n-files " files)"))
        (do (println (str "version mismatch, want " want ":"))
            (doseq [[f what found] bad] (println (str "  " f " (" what "): " found)))
            (System/exit 1))))))

;; ------------------------------------------------------------------------------------------------ the self-test

(defn- same-but-version?
  "`after` is `before` with only version digits changed (every non-version line is byte-identical)."
  [before after]
  (let [ls #(str/split % #"\n" -1)
        a (ls before) b (ls after)]
    (and (= (count a) (count b))
         (every? (fn [[x y]] (or (= x y) (and (re-find semver-re x) (re-find semver-re y)
                                              (= (str/replace x semver-re "V") (str/replace y semver-re "V")))))
                 (map vector a b)))))

(defn self-test []
  (let [real (str (fs/absolutize "."))
        tmp (str (fs/create-temp-dir {:prefix "emon-version-"}))
        files (distinct (cons "version.edn" (map :file targets)))
        snap #(into {} (map (fn [f] [f (slurp (path % f))]) files))
        fails (atom [])
        t! (fn [label ok] (when-not ok (swap! fails conj label)) (println (if ok "  ok  " "  FAIL") label))]
    (try
      (let [real-before (snap real)]
        (doseq [f files]
          (fs/create-dirs (fs/parent (path tmp f)))
          (fs/copy (path real f) (path tmp f)))
        (let [orig (snap tmp)]
          (set-version! tmp "9.8.7")
          (t! "set 9.8.7: check 9.8.7 passes" (empty? (mismatches tmp "9.8.7")))
          (t! "set 9.8.7: check v9.8.7 passes" (empty? (mismatches tmp (str/replace "v9.8.7" #"^v" ""))))
          (t! "set 9.8.7: check 0.1.0 reports every file" (= n-files (count (distinct (map first (mismatches tmp "0.1.0"))))))
          (t! "set changes only version bytes" (every? (fn [f] (same-but-version? (orig f) (slurp (path tmp f)))) files))
          (t! "bump patch" (= "9.8.8" (bump "9.8.7" "patch")))
          (t! "bump minor" (= "9.9.0" (bump "9.8.7" "minor")))
          (t! "bump major" (= "10.0.0" (bump "9.8.7" "major")))
          (set-version! tmp (bump (current tmp) "minor"))
          (t! "bump minor is written everywhere" (empty? (mismatches tmp "9.9.0")))
          ;; one manifest drifts: it is named
          (let [p (path tmp "host/c/src/capi.rs")]
            (spit p (str/replace (slurp p) "b\"9.9.0\\0\"" "b\"1.2.3\\0\""))
            (t! "a drifted manifest is the one named" (= [["host/c/src/capi.rs" "C binding: emon_version()" "1.2.3"]] (mismatches tmp "9.9.0"))))
          (set-version! tmp "0.1.0")
          (t! "set back to 0.1.0 restores every file byte for byte (when the real tree is 0.1.0)"
              (or (not= "0.1.0" (current real)) (seq (mismatches real "0.1.0")) (= orig (snap tmp)))))
        (t! "the real tree was not touched" (= real-before (snap real))))
      (finally (fs/delete-tree tmp)))
    (if (seq @fails)
      (do (println "self-test FAILED:" (str/join ", " @fails)) (System/exit 1))
      (println "self-test ok"))))

;; ------------------------------------------------------------------------------------------------ main

(defn -main [args]
  (let [root (str (fs/absolutize "."))
        [a b & more] args]
    (try
      (cond
        (empty? args) (println (current root))
        (and (= a "set") b (empty? more)) (do (set-version! root b) (println (str "set " b " in " n-files " files")))
        (and (= a "bump") (#{"major" "minor" "patch"} b) (empty? more))
        (let [v (bump (current root) b)] (set-version! root v) (println (str "bumped to " v)))
        (and (= a "--check") b (empty? more)) (check root b)
        (and (= a "--self-test") (nil? b)) (self-test)
        :else (die 2 "usage: bb version | set X.Y.Z | bump major|minor|patch | --check TAG | --self-test"))
      (catch clojure.lang.ExceptionInfo e
        (die (if (:usage (ex-data e)) 2 1) (ex-message e))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
