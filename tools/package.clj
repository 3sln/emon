#!/usr/bin/env bb
;; `bb package` and the package gate (P11.4, DEVLOG D542; D524, D525, D526, D527).
;;
;;   bb package [--host H] [--out DIR]      build every registry artifact into DIR/<host>/ (default dist/, gitignored)
;;   bb package --check [--host H] [--out DIR] [--keep]
;;                                          the package gate (also `bb package-check`): build as above, then INSTALL each artifact into a clean temporary
;;                                          consumer project and convert one born-digital sample (samples/align-justify.pdf) with it. Every host's HTML
;;                                          must be byte for byte the same and equal samples/align-justify.html. A host whose toolchain is missing SKIPs,
;;                                          loudly. `--no-build` consumes what DIR already holds.
;;   bb package --list                      the hosts and what each produces
;;
;; HOSTS (D524's scope): rust (the crate, `cargo package`), go (the module zip, a file:// module proxy and the tree a tag is cut from), java (the Maven
;; bundle: jar, pom, sources, javadoc, checksums), csharp (`dotnet pack`), js (`npm pack`, with the WASM module), c (libemon + emon.h per host triple), pod
;; (the babashka pod per host triple).
;;
;; THE MANIFESTS are COMMITTED (release/pkg/<host>/, host/js/package.json), not templated: the file a reviewer reads is the file the packager uses, and
;; `bb version --check` and `bb release-audit` read them. What is ASSEMBLED, as the build scripts of kin/scripts/emon-*-build assemble the gates' trees, is
;; the source: gen/<lang> without laws and probes, the host trees without test support. The rules below restate those scripts' (names ending test/probe).
;;
;; NOTHING IS UPLOADED. This task never runs `cargo publish`, `npm publish`, `dotnet nuget push` or `mvn deploy`.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '(java.security MessageDigest))

(def root (str (fs/absolutize ".")))
(def version (:version (edn/read-string (slurp (str root "/version.edn")))))
(def all-hosts [:rust :go :java :csharp :js :c :pod])
(def sample "align-justify")

(def ^:dynamic *out* nil)

;; ------------------------------------------------------------------------------------------------------ small helpers

(defn say [& xs] (println (apply str xs)) (flush))
(defn- tail [s n] (str/join "\n" (take-last n (str/split-lines (str s)))))
(defn- skip! [why] (throw (ex-info why {:skip true})))
(defn- bad! [& xs] (throw (ex-info (apply str xs) {})))

(defn- run
  "Run a command (a seq of args after the options). Returns {:exit :out :err}; throws with the tail of the output unless :ok? false."
  [{:keys [dir env ok?] :or {ok? true}} & cmd]
  (let [cmd (map str cmd)
        r (apply p/shell (cond-> {:out :string :err :string :continue true}
                           dir (assoc :dir (str dir))
                           env (assoc :extra-env env))
                 cmd)
        r {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}]
    (when (and ok? (not (zero? (:exit r))))
      (bad! (str/join " " cmd) " failed (exit " (:exit r) ")\n" (tail (str (:out r) "\n" (:err r)) 40)))
    r))

(defn- have? [bin] (zero? (:exit (run {:ok? false} "sh" "-c" (str "command -v " bin)))))

(defn- bytes= [a b] (java.util.Arrays/equals ^bytes (fs/read-all-bytes a) ^bytes (fs/read-all-bytes b)))
(defn- put!
  "Copy src to dst unless dst already holds the same bytes (a build tool that goes by mtime sees no change)."
  [src dst]
  (fs/create-dirs (fs/parent dst))
  (when-not (and (fs/exists? dst) (= (fs/size src) (fs/size dst)) (bytes= src dst))
    (fs/copy src dst {:replace-existing true})))

(defn- put-text! [text dst]
  (fs/create-dirs (fs/parent dst))
  (when-not (and (fs/exists? dst) (= text (slurp (str dst)))) (spit (str dst) text)))

(defn- stage!
  "Make the files under `dir` exactly `files` (relative path -> a source path, or {:text s}) inside the `owned` subdirectories/files: others there are removed."
  [dir files owned]
  (fs/create-dirs dir)
  (doseq [[rel src] files]
    (if (map? src) (put-text! (:text src) (str dir "/" rel)) (put! (str root "/" src) (str dir "/" rel))))
  (doseq [o owned
          :let [d (str dir "/" o)]
          :when (fs/directory? d)
          f (fs/glob d "**" {:follow-links false})
          :let [f (str f)]
          :when (fs/regular-file? f)
          :when (not (contains? files (str (fs/relativize dir f))))]
    (fs/delete f)))

(defn- listing
  "Sorted base names (without ext) of the regular files directly in `dir` ending in `ext`."
  [dir ext]
  (->> (fs/list-dir (str root "/" dir))
       (filter fs/regular-file?)
       (map #(str (fs/file-name %)))
       (filter #(str/ends-with? % ext))
       (map #(subs % 0 (- (count %) (count ext))))
       sort))

(defn- law-or-probe? [m] (boolean (re-find #"(test|probe|Test|Probe)$" m)))
(defn- law-or-probe-lower? [m] (boolean (re-find #"(test|probe)$" m)))

(defn- sha256 [path]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d (fs/read-all-bytes path))))))
(defn- digest-hex [alg path]
  (let [d (MessageDigest/getInstance alg)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d (fs/read-all-bytes path))))))

(defn- human [n] (cond (< n 10240) (str n " B") (< n (* 10 1024 1024)) (format "%.0f KiB" (/ n 1024.0)) :else (format "%.1f MiB" (/ n 1048576.0))))

(defn- host-triple []
  (let [r (run {} "rustc" "-vV")]
    (second (re-find #"(?m)^host: (\S+)" (:out r)))))

(defn- zip-list [f] (str/split-lines (:out (run {} "unzip" "-Z1" f))))
(defn- tar-list [f] (str/split-lines (:out (run {} "tar" "-tzf" f))))

(def common-files
  {"LICENSE" "LICENSE"
   "THIRD_PARTY_NOTICES.md" "THIRD_PARTY_NOTICES.md"
   "README.md" "release/pkg/README.md"})

(defn- out-dir [host] (str *out* "/" (name host)))
(defn- stage-dir [host] (str *out* "/stage/" (name host)))

;; ------------------------------------------------------------------------------------------------------ the Rust crate

(def rust-host-files ["lib" "port" "plan" "source" "engine" "imgdec" "tess" "render" "pdfium" "pdfimg" "session" "par"]) ; not pdfcodecs: it links jbig2dec (D526)

(defn- rust-src-files
  "The source of the crate: the generated modules (not laws or probes), the module list, and the host files."
  []
  (let [mods (remove law-or-probe-lower? (listing "gen/rust/emon" ".rs"))]
    (merge
      (into {} (for [m mods] [(str "src/emon/" m ".rs") (str "gen/rust/emon/" m ".rs")]))
      {"src/emon.rs" {:text (str (str/join "\n" (map #(str "pub mod " % ";") mods)) "\n")}}
      (into {} (for [f rust-host-files] [(str "src/" f ".rs") (str "host/rust/src/" f ".rs")])))))

(defn build-rust! []
  (when-not (have? "cargo") (skip! "no cargo"))
  (let [dir (stage-dir :rust)
        files (merge (rust-src-files) common-files
                     {"Cargo.toml" "release/pkg/rust/Cargo.toml" "Cargo.lock" "host/rust/Cargo.lock"})]
    (stage! dir files ["src"])
    (let [pk (fn [& flags] (apply run {:dir dir :ok? false} "cargo" "package" "--allow-dirty" "--locked" flags))
          r (let [r (pk "--offline")] (if (zero? (:exit r)) r (pk)))]
      (when-not (zero? (:exit r)) (bad! "cargo package failed\n" (tail (str (:out r) "\n" (:err r)) 40))))
    (let [crate (str dir "/target/package/emon-" version ".crate")
          dst (str (out-dir :rust) "/emon-" version ".crate")]
      (when-not (fs/exists? crate) (bad! "cargo package made no " crate))
      (fs/create-dirs (out-dir :rust))
      (fs/copy crate dst {:replace-existing true})
      (let [names (tar-list dst)]
        (when (some #(re-find #"pdfcodecs\.rs$|driver\.rs$" %) names) (bad! "the crate carries pdfcodecs.rs or the driver"))
        (when-not (some #(re-find #"/LICENSE$" %) names) (bad! "the crate has no LICENSE"))
        (when-not (some #(re-find #"/README\.md$" %) names) (bad! "the crate has no README.md")))
      (when (> (fs/size dst) (* 10 1024 1024)) (bad! "the crate is over crates.io's 10 MiB limit: " (human (fs/size dst))))
      [dst])))

;; ------------------------------------------------------------------------------------------------------ the Go module

(def go-module "github.com/3sln/emon/go")
(def go-public #{"port" "planrun" "pdfium"}) ; host packages an embedder names; every other package is generated or plumbing and goes under internal/

(defn- go-rewrite
  "The imports of the assembled module (`emon/X`) as the published module's."
  [text]
  (str/replace text #"\"emon/(\w+)\""
               (fn [[_ x]] (str "\"" go-module "/" (if (go-public x) x (str "internal/" x)) "\""))))

(defn- go-tree
  "dir -> the module tree: [rel-path text-or-source]."
  []
  (let [pkgs (remove law-or-probe-lower? (map #(str (fs/file-name %)) (filter fs/directory? (fs/list-dir (str root "/gen/go")))))
        gos (fn [d] (->> (fs/list-dir (str root "/" d)) (filter fs/regular-file?) (map #(str (fs/file-name %))) (filter #(str/ends-with? % ".go")) sort))
        mk (fn [rel src] [rel {:text (go-rewrite (slurp (str root "/" src)))}])]
    (merge
      (into {} (for [pk (sort pkgs), f (gos (str "gen/go/" pk))] (mk (str "internal/" pk "/" f) (str "gen/go/" pk "/" f))))
      (into {} (for [f (gos "host/go") :when (not (re-find #"(_test|testhooks)\.go$" f))] (mk f (str "host/go/" f))))
      (into {} (for [f (gos "host/go/port") :when (not= f "testport.go")] (mk (str "port/" f) (str "host/go/port/" f))))
      (into {} (for [f (gos "host/go/planrun")] (mk (str "planrun/" f) (str "host/go/planrun/" f))))
      (into {} (for [f (gos "host/go/pdfium")] (mk (str "pdfium/" f) (str "host/go/pdfium/" f))))
      (into {} (for [f (gos "host/go/pngenc")] (mk (str "internal/pngenc/" f) (str "host/go/pngenc/" f))))
      {"go.mod" "release/pkg/go/go.mod"}
      common-files)))

(defn build-go! []
  (when-not (have? "go") (skip! "no go"))
  (let [tag (str "v" version)
        tree (str (stage-dir :go) "/tree/go")
        files (go-tree)
        out (out-dir :go)
        modroot (str (stage-dir :go) "/zip/" go-module "@" tag)]
    ;; the tree a tag is cut from (the publish job, P11.10, commits it as go/ and tags go/vX.Y.Z)
    (stage! tree files ["internal" "port" "planrun" "pdfium"])
    (let [r (run {:dir tree :ok? false} "go" "build" "./...")]
      (when-not (zero? (:exit r)) (bad! "go build ./... of the module tree failed\n" (tail (str (:out r) (:err r)) 40))))
    (let [r (run {:dir tree :ok? false} "go" "vet" "." "./port" "./planrun")]
      (when-not (zero? (:exit r)) (bad! "go vet of the module tree failed\n" (tail (str (:out r) (:err r)) 40))))
    ;; the module zip: every entry under `module@version/`, no directory entries (what `go mod download` gives, golang.org/x/mod/zip)
    (fs/delete-tree (str (stage-dir :go) "/zip"))
    (fs/create-dirs modroot)
    (doseq [f (fs/glob tree "**" {:follow-links false})
            :let [f (str f)]
            :when (and (fs/regular-file? f) (not (re-find #"/(bin|obj)/" f)))]
      (let [rel (str (fs/relativize tree f))]
        (fs/create-dirs (fs/parent (str modroot "/" rel)))
        (fs/copy f (str modroot "/" rel) {:replace-existing true})))
    (fs/create-dirs out)
    (let [zip (str out "/emon-go-" tag ".zip")]
      (fs/delete-if-exists zip)
      (run {:dir (str (stage-dir :go) "/zip")} "zip" "-X" "-D" "-q" "-r" zip (str go-module "@" tag))
      (let [names (zip-list zip)]
        (when (some #(re-find #"testhooks\.go$|_test\.go$|testport\.go$|/cmd/" %) names) (bad! "the Go module zip carries test support"))
        (when-not (some #(re-find #"/internal/model/" %) names) (bad! "the Go module zip has no internal/model: the generated packages are not under internal/"))
        (when-not (some #(re-find #"@v[^/]*/LICENSE$" %) names) (bad! "the Go module zip has no LICENSE")))
      [zip])))

(defn- go-proxy!
  "A file:// module proxy holding the zip: GOPROXY=file://DIR resolves `go get github.com/3sln/emon/go@vX.Y.Z` like proxy.golang.org would."
  [zip proxy]
  (let [tag (str "v" version)
        d (str proxy "/" (str/lower-case go-module) "/@v")]
    (fs/create-dirs d)
    (fs/copy zip (str d "/" tag ".zip") {:replace-existing true})
    (spit (str d "/" tag ".info") (str "{\"Version\":\"" tag "\",\"Time\":\"2026-01-01T00:00:00Z\"}"))
    (spit (str d "/" tag ".mod") (slurp (str root "/release/pkg/go/go.mod")))
    (spit (str d "/list") (str tag "\n"))))

;; ------------------------------------------------------------------------------------------------------ the Maven bundle

(defn- find-jdk []
  (let [cands (remove str/blank?
                      [(System/getenv "EMON_JDK") "/opt/homebrew/opt/openjdk"
                       ;; macOS only: on Linux the program is not there, and starting it throws rather than answering with an exit code
                       (when (fs/exists? "/usr/libexec/java_home")
                         (let [r (run {:ok? false} "/usr/libexec/java_home")] (when (zero? (:exit r)) (str/trim (:out r)))))])]
    (or (first (for [j cands
                     :when (fs/exists? (str j "/bin/javac"))
                     :let [r (run {:ok? false} (str j "/bin/javac") "-version")]
                     :when (zero? (:exit r))
                     :let [v (parse-long (or (second (re-find #"javac (\d+)" (str (:out r) (:err r)))) "0"))]
                     :when (>= v 22)]
                 j))
        (skip! "no JDK 22 or newer (set EMON_JDK): java.lang.foreign is final from 22"))))

(defn- java-src-files []
  (let [gen (remove law-or-probe? (listing "gen/java/emon" ".java"))
        host (remove #{"Testport"} (listing "host/java/emon" ".java"))
        sub (fn [s] (listing (str "host/java/emon/" s) ".java"))]
    (merge
      (into {} (for [m gen] [(str "src/main/java/emon/" m ".java") (str "gen/java/emon/" m ".java")]))
      (into {} (for [m host] [(str "src/main/java/emon/" m ".java") (str "host/java/emon/" m ".java")]))
      (into {} (for [s ["api" "ffi"], m (sub s)] [(str "src/main/java/emon/" s "/" m ".java") (str "host/java/emon/" s "/" m ".java")])))))

(defn- sums! [f]
  (spit (str f ".md5") (digest-hex "MD5" f))
  (spit (str f ".sha1") (digest-hex "SHA-1" f))
  (spit (str f ".sha256") (digest-hex "SHA-256" f)))

(defn build-java! []
  (let [jdk (find-jdk)
        dir (stage-dir :java)
        files (merge (java-src-files)
                     {"pom.xml" "release/pkg/java/pom.xml"
                      "meta/META-INF/LICENSE" "LICENSE"
                      "meta/META-INF/THIRD_PARTY_NOTICES.md" "THIRD_PARTY_NOTICES.md"
                      "meta/META-INF/MANIFEST.MF"
                      {:text (str "Manifest-Version: 1.0\nAutomatic-Module-Name: emon\nEnable-Native-Access: ALL-UNNAMED\nImplementation-Title: emon\n"
                                  "Implementation-Version: " version "\nImplementation-Vendor: 3sln\n\n")}})
        repo (str (out-dir :java) "/maven/com/3sln/emon/" version)
        art (str "emon-" version)]
    (stage! dir files ["src" "meta"])
    ;; Central's requirements of a POM: coordinates, name, description, url, licence, developers, scm (and, below, sources and javadoc jars)
    (let [pom (slurp (str dir "/pom.xml"))]
      (doseq [[re what] [[#"<groupId>com\.3sln</groupId>" "groupId com.3sln"] [#"<artifactId>emon</artifactId>" "artifactId emon"]
                         [#"<version>[^<]+</version>" "version"] [#"<name>emon</name>" "name"] [#"<description>[^<]{20,}</description>" "description"]
                         [#"<url>https://github\.com/3sln/emon</url>" "url"] [#"<licenses>\s*<license>\s*<name>MIT License</name>\s*<url>[^<]+</url>" "licence with a url"]
                         [#"<developers>\s*<developer>\s*<id>raystubbs</id>" "developer"] [#"<scm>\s*<connection>[^<]+</connection>\s*<developerConnection>[^<]+</developerConnection>\s*<url>[^<]+</url>" "scm"]]]
        (when-not (re-find re pom) (bad! "pom.xml: no " what)))
      (when (have? "xmllint") (run {} "xmllint" "--noout" (str dir "/pom.xml"))))
    (fs/delete-tree (str dir "/classes"))
    (fs/delete-tree (str dir "/javadoc"))
    (fs/create-dirs (str dir "/classes"))
    (spit (str dir "/sources.txt") (str/join "\n" (sort (map #(str (fs/relativize dir %)) (fs/glob (str dir "/src/main/java") "**.java")))))
    (run {:dir dir} (str jdk "/bin/javac") "--release" "22" "-encoding" "UTF-8" "-nowarn" "-d" "classes" "@sources.txt")
    (fs/delete-tree repo)
    (fs/create-dirs repo)
    (let [jar (str repo "/" art ".jar")
          src (str repo "/" art "-sources.jar")
          doc (str repo "/" art "-javadoc.jar")
          pom (str repo "/" art ".pom")
          jar-bin (str jdk "/bin/jar")]
      (run {:dir dir} jar-bin "--create" "--file" jar "--manifest" "meta/META-INF/MANIFEST.MF" "--date" "2026-01-01T00:00:00Z" "-C" "classes" "." "-C" "meta" "META-INF/LICENSE" "-C" "meta" "META-INF/THIRD_PARTY_NOTICES.md")
      (run {:dir dir} jar-bin "--create" "--file" src "--date" "2026-01-01T00:00:00Z" "-C" "src/main/java" "." "-C" "meta" "META-INF/LICENSE")
      (let [r (run {:dir dir :ok? false} (str jdk "/bin/javadoc") "-quiet" "-Xdoclint:none" "-notimestamp" "-encoding" "UTF-8" "--release" "22" "-d" "javadoc"
                   "-sourcepath" "src/main/java" "-subpackages" "emon.api:emon.ffi" "-windowtitle" (str "emon " version))]
        (when-not (zero? (:exit r)) (bad! "javadoc failed\n" (tail (str (:out r) (:err r)) 30))))
      (run {:dir dir} jar-bin "--create" "--file" doc "--date" "2026-01-01T00:00:00Z" "-C" "javadoc" "." "-C" "meta" "META-INF/LICENSE")
      (fs/copy (str dir "/pom.xml") pom {:replace-existing true})
      (doseq [f [jar src doc pom]] (sums! f))
      ;; signatures: only with a key (the publish job's MAVEN_GPG_PRIVATE_KEY is imported by it, D524); an unsigned bundle is complete except for these
      (let [key (System/getenv "EMON_GPG_KEY")]
        (if (and key (have? "gpg"))
          (doseq [f [jar src doc pom]]
            (run {} "gpg" "--batch" "--yes" "--pinentry-mode" "loopback" "-u" key "--armor" "--detach-sign" f)
            (sums! (str f ".asc")))
          (say "  NOTE the Maven bundle is unsigned (set EMON_GPG_KEY to sign; the publish job signs, D524)")))
      (let [names (zip-list jar)]
        (when (some #(re-find #"Testport|TestHooks" %) names) (bad! "the jar carries test support"))
        (when-not (some #{"emon/api/Engine.class"} names) (bad! "the jar has no emon/api/Engine.class"))
        (when-not (some #{"META-INF/LICENSE"} names) (bad! "the jar has no META-INF/LICENSE")))
      (when-not (some #(str/ends-with? % "index.html") (zip-list doc)) (bad! "the javadoc jar has no index.html"))
      (let [bundle (str (out-dir :java) "/" art "-bundle.zip")
            mroot (str (out-dir :java) "/maven")]
        (fs/delete-if-exists bundle)
        (run {:dir mroot} "zip" "-X" "-D" "-q" "-r" bundle "com")
        [bundle jar pom src doc]))))

;; ------------------------------------------------------------------------------------------------------ the NuGet package

(defn- dotnet []
  (let [r (run {:ok? false} "kin/scripts/dotnet-sdk" )]
    (when-not (zero? (:exit r)) (skip! (str/trim (:err r))))
    (let [[path major] (str/split (str/trim (:out r)) #" ")] {:path path :major major})))

(defn- cs-src-files []
  (let [skipped? (fn [m] (re-find #"(test|probe|Test|Probe)$" m))
        gen (remove skipped? (listing "gen/csharp/Emon" ".cs"))
        host (remove #{"Testport"} (listing "host/csharp/Emon" ".cs"))]
    (merge
      (into {} (for [m gen] [(str "src/gen/" m ".cs") (str "gen/csharp/Emon/" m ".cs")]))
      (into {} (for [m host] [(str "src/host/" m ".cs") (str "host/csharp/Emon/" m ".cs")]))
      (into {} (for [s ["Api" "Ffi"], m (listing (str "host/csharp/Emon/" s) ".cs")] [(str "src/host/" s "/" m ".cs") (str "host/csharp/Emon/" s "/" m ".cs")])))))

(def dotnet-env {"DOTNET_CLI_TELEMETRY_OPTOUT" "1" "DOTNET_NOLOGO" "1" "DOTNET_SKIP_FIRST_TIME_EXPERIENCE" "1"})

(defn build-csharp! []
  (let [{:keys [path]} (dotnet)
        dir (stage-dir :csharp)
        out (out-dir :csharp)]
    (stage! dir (merge (cs-src-files) common-files {"Emon.csproj" "release/pkg/csharp/Emon.csproj"}) ["src"])
    (fs/delete-tree out)
    (fs/create-dirs out)
    (run {:dir dir :env dotnet-env} path "pack" "Emon.csproj" "-c" "Release" "-o" out "--nologo" "-v" "q" "-clp:NoSummary" "-p:ContinuousIntegrationBuild=true")
    (let [nupkg (str out "/Emon." version ".nupkg")]
      (when-not (fs/exists? nupkg) (bad! "dotnet pack made no " nupkg))
      (let [names (zip-list nupkg)]
        (when-not (some #(re-find #"^lib/net8\.0/Emon\.dll$" %) names) (bad! "the nupkg has no lib/net8.0/Emon.dll: " (str/join " " names)))
        (when-not (some #{"LICENSE" "README.md"} names) (bad! "the nupkg has no LICENSE or README.md")))
      (let [nuspec (:out (run {} "unzip" "-p" nupkg "Emon.nuspec"))]
        (when-not (re-find #"<license type=\"expression\">MIT</license>" nuspec) (bad! "the nuspec has no MIT licence expression")))
      (vec (filter fs/exists? [nupkg (str out "/Emon." version ".snupkg")])))))

;; ------------------------------------------------------------------------------------------------------ the npm tarball

(defn- js-exports
  "The names a JS module exports: `export function|class|const|let NAME` and `export { a, b as c }`."
  [text]
  (set (concat (map second (re-seq #"(?m)^export\s+(?:async\s+)?(?:function\*?|class|const|let|var)\s+([A-Za-z_$][\w$]*)" text))
               (mapcat (fn [[_ body]] (map #(last (str/split (str/trim %) #"\s+as\s+")) (str/split body #",")))
                       (re-seq #"(?m)^export\s*\{([^}]*)\}" text)))))

(defn- dts-exports [text]
  (set (map second (re-seq #"(?m)^export\s+(?:declare\s+)?(?:async\s+)?(?:function|class|interface|type|const|let|var)\s+([A-Za-z_$][\w$]*)" text))))

(defn- check-types!
  "Every name a module exports is declared by the .d.ts beside it (the types are hand-written, D542): a name added to index.js and not to
  index.d.ts fails here, and a declared name the module does not export does too."
  []
  (doseq [m ["index" "tesseract" "pdfium"]]
    (let [js (js-exports (slurp (str root "/host/js/src/" m ".js")))
          dts (dts-exports (slurp (str root "/host/js/src/" m ".d.ts")))
          ;; types and interfaces have no run-time name
          ts-only (set (map second (re-seq #"(?m)^export\s+(?:interface|type)\s+([A-Za-z_$][\w$]*)" (slurp (str root "/host/js/src/" m ".d.ts")))))]
      (when-let [missing (seq (remove dts js))]
        (bad! m ".js exports " (str/join ", " (sort missing)) " and " m ".d.ts does not declare " (if (next missing) "them" "it")))
      (when-let [extra (seq (remove (into js ts-only) dts))]
        (bad! m ".d.ts declares " (str/join ", " (sort extra)) ", which " m ".js does not export")))))

;; @3sln/pdfium-wasm (D553): pdfium's WebAssembly build, the one `bb pdfium-fetch --platform wasm` installs (pinned in tools/pdfium/pdfium.edn), as a package
;; of its own: `pdfium.mjs` (the ES-module wrapper `bb pdfium-fetch` makes), `pdfium.wasm`, pdfium-binaries' LICENSE and licenses/, a tiny index.js
;; (the factory, `wasmUrl` and `wasmBinary()`) and its types. A sub-artifact of the js host: `bb package --host js` builds both tarballs, `bb publish npm` publishes both.
(defn- build-pdfium-wasm!
  "dist/js/3sln-pdfium-wasm-V.tgz, or nil (with a loud NOTE) when the wasm build has not been fetched and the full pipeline is not required."
  []
  (let [pin (edn/read-string (slurp (str root "/tools/pdfium/pdfium.edn")))
        base (str ".cache/pdfium/" (str/replace (:version pin) "/" "-") "/wasm")
        need [(str base "/lib/pdfium.mjs") (str base "/lib/pdfium.wasm") (str base "/LICENSE") (str base "/licenses/pdfium.txt")]
        gone (remove #(fs/exists? (str root "/" %)) need)]
    (if (seq gone)
      (if (= "1" (System/getenv "EMON_PACKAGE_REQUIRE_NATIVE"))
        (bad! "EMON_PACKAGE_REQUIRE_NATIVE=1 and @3sln/pdfium-wasm cannot be built: no " (first gone) " (run `bb pdfium-fetch --platform wasm`)")
        (do (say "  NOTE js: @3sln/pdfium-wasm NOT built (no " (first gone) "; run `bb pdfium-fetch --platform wasm`): a release is built with it") nil))
      (let [src "release/pkg/pdfium-wasm"
            js-index (slurp (str root "/" src "/index.js"))
            dts (slurp (str root "/" src "/index.d.ts"))
            _ (doseq [n ["default" "wasmUrl" "wasmBinary"]]
                (when-not (str/includes? js-index n) (bad! "release/pkg/pdfium-wasm/index.js does not export " n))
                (when-not (str/includes? dts n) (bad! "release/pkg/pdfium-wasm/index.d.ts does not declare " n)))
            dir (stage-dir :js-pdfium)
            files (merge {"package.json" (str src "/package.json") "index.js" (str src "/index.js") "index.d.ts" (str src "/index.d.ts") "README.md" (str src "/README.md")
                          "pdfium.mjs" (str base "/lib/pdfium.mjs") "pdfium.wasm" (str base "/lib/pdfium.wasm")
                          "LICENSE" (str base "/LICENSE") "VERSION" (str base "/VERSION")}
                         (into {} (for [f (fs/list-dir (str root "/" base "/licenses")) :when (fs/regular-file? f)]
                                    [(str "licenses/" (fs/file-name f)) (str base "/licenses/" (fs/file-name f))])))
            out (out-dir :js)]
        (stage! dir files ["licenses"])
        (fs/create-dirs out)
        (let [r (run {:dir dir :env {"npm_config_audit" "false" "npm_config_fund" "false" "npm_config_update_notifier" "false"}}
                     "npm" "pack" "--ignore-scripts" "--pack-destination" out)
              tgz (str out "/3sln-pdfium-wasm-" version ".tgz")]
          (when-not (fs/exists? tgz) (bad! "npm pack made no " tgz "\n" (:out r)))
          (let [names (tar-list tgz)]
            (doseq [n ["package/package.json" "package/index.js" "package/index.d.ts" "package/pdfium.mjs" "package/pdfium.wasm" "package/LICENSE" "package/licenses/pdfium.txt" "package/README.md"]]
              (when-not (some #{n} names) (bad! "the pdfium-wasm tarball has no " n))))
          tgz)))))

(defn build-js! []
  (when-not (have? "npm") (skip! "no npm"))
  (check-types!)
  (let [r (run {:ok? false} "kin/scripts/emon-wasm-build")]
    (when (= 3 (:exit r)) (skip! (str/trim (:err r))))
    (when-not (zero? (:exit r)) (bad! "emon-wasm-build failed\n" (tail (:err r) 20)))
    (let [wasm (str/trim (:out r))
          dir (stage-dir :js)
          srcs (->> (fs/list-dir (str root "/host/js/src")) (filter fs/regular-file?) (map #(str (fs/file-name %))) sort)
          files (merge (into {} (for [s srcs] [(str "src/" s) (str "host/js/src/" s)]))
                       common-files
                       {"package.json" "host/js/package.json"
                        "emon.wasm" wasm})
          out (out-dir :js)]
      (stage! dir files ["src"])
      (fs/create-dirs out)
      (let [r (run {:dir dir :env {"npm_config_audit" "false" "npm_config_fund" "false" "npm_config_update_notifier" "false"}}
                   "npm" "pack" "--ignore-scripts" "--pack-destination" out)
            tgz (str out "/3sln-emon-" version ".tgz")]
        (when-not (fs/exists? tgz) (bad! "npm pack made no " tgz "\n" (:out r)))
        (let [names (tar-list tgz)]
          (doseq [need ["package/package.json" "package/emon.wasm" "package/src/index.js" "package/src/index.d.ts" "package/src/pdfium.js" "package/src/pdfium.d.ts"
                        "package/src/tesseract.js" "package/src/tesseract.d.ts" "package/LICENSE" "package/README.md"]]
            (when-not (some #{need} names) (bad! "the tarball has no " need))))
        ;; the optional peer's range names the version that is being published with it
        (when-not (str/includes? (slurp (str root "/host/js/package.json")) (str "\"@3sln/pdfium-wasm\": \"^" version "\"")) (bad! "host/js/package.json's peer range of @3sln/pdfium-wasm is not ^" version))
        (vec (remove nil? [tgz (build-pdfium-wasm!)]))))))

;; ------------------------------------------------------------------------------------------------------ the native library and the pod

(defn- native-lib-names []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond (str/includes? os "mac") {:dyn "libemon.dylib" :static "libemon.a" :pod "emon-pod"}
          (str/includes? os "linux") {:dyn "libemon.so" :static "libemon.a" :pod "emon-pod"}
          :else (skip! (str "the C library and the pod are packaged on macOS and Linux (this is " os ")")))))

(defonce native-built (atom nil))
(defonce native-pod-dir (atom nil)) ; the release directory holding emon-pod
(defonce native-full (atom nil)) ; native-inputs' map when the build in native-built has the full pipeline

;; THE FULL PIPELINE (D552; D541's recipe for the CLI): libemon and emon-pod are built with Tesseract and Leptonica linked STATICALLY from `bb native`
;; (EMON_NATIVE, .cache/native/<triple>/link.rustflags), the crate's `tesseract` feature, and pdfium LOADED AT RUN TIME (`--cfg pdfium --cfg pdfium_dl`,
;; `pdfium::load`). The archives carry the pinned pdfium (lib/) and the eng and osd models (share/emon/tessdata/), where the library and the pod look
;; for them. Without .cache/native or the pinned pdfium the build is the plain one (no ports; `emon_capabilities()` is 0) and says so loudly;
;; EMON_PACKAGE_REQUIRE_NATIVE=1 (the release workflow's) makes that a failure.
(def native-pins (edn/read-string (slurp (str root "/tools/native/native.edn"))))
(def pdfium-pin (edn/read-string (slurp (str root "/tools/pdfium/pdfium.edn"))))

(defn- native-inputs
  "{:prefix dir, :pdfium file, :data [{:file name :src path}]} when bb native's prefix and the pinned pdfium are here, else {:why s}."
  []
  (let [triple (host-triple)
        tcfg (get-in native-pins [:triples triple])
        prefix (str root "/.cache/native/" triple)
        pdfium (some->> tcfg :pdfium (str root "/.cache/pdfium/" (str/replace (:version pdfium-pin) "/" "-") "/"))
        pdfium (some-> pdfium (str "/" (get-in pdfium-pin [:platforms (:pdfium tcfg) :lib])))]
    (cond
      (nil? tcfg) {:why (str "no native pins for " triple)}
      (not (fs/exists? (str prefix "/link.rustflags"))) {:why (str "no .cache/native/" triple " (run `bb native`)")}
      (not (fs/exists? (str prefix "/share/tessdata/eng.traineddata"))) {:why (str ".cache/native/" triple " has no tessdata (run `bb native`)")}
      (not (and pdfium (fs/exists? pdfium))) {:why (str "no pinned pdfium (run `bb pdfium-fetch`): " pdfium)}
      :else {:prefix prefix :pdfium pdfium
             :data (vec (for [d (:data native-pins)] {:file (:file d) :src (str prefix "/share/tessdata/" (:file d))}))})))

(defn- build-native!
  "Build libemon (cdylib + staticlib) and emon-pod from ONE assembled crate, WITHOUT the test hooks. Returns the release directory."
  []
  (or @native-built
      (do
        (when-not (have? "cargo") (skip! "no cargo"))
        (let [dir (stage-dir :native)
              inputs (native-inputs)
              _ (if (:why inputs)
                  (if (= "1" (System/getenv "EMON_PACKAGE_REQUIRE_NATIVE"))
                    (bad! "EMON_PACKAGE_REQUIRE_NATIVE=1 and the full pipeline cannot be built: " (:why inputs))
                    (say "  NOTE c/pod: built WITHOUT pdfium and Tesseract (" (:why inputs) "): emon_capabilities() is 0 in this build; a release is built with them"))
                  (say "  c/pod: the full pipeline: static Tesseract and Leptonica from " (fs/relativize root (:prefix inputs)) ", pdfium loaded at run time"))
              cargo (slurp (str root "/release/pkg/rust/Cargo.toml"))
              lib "[lib]\nname = \"emon\"\npath = \"src/lib.rs\"\n"
              _ (when-not (str/includes? cargo lib) (bad! "release/pkg/rust/Cargo.toml has no [lib] table of the expected form"))
              cargo (str/replace cargo lib
                                 (str lib "crate-type = [\"cdylib\", \"staticlib\", \"rlib\"]\n\n[[bin]]\nname = \"emon-pod\"\npath = \"src/pod.rs\"\n"))
              files (-> (rust-src-files)
                        (dissoc "src/lib.rs")
                        (assoc "src/lib.rs" {:text (str (slurp (str root "/host/rust/src/lib.rs"))
                                                        "\n// the C binding (P9.4), assembled in by tools/package.clj from host/c/src/capi.rs\npub mod capi;\n")}
                               "src/capi.rs" "host/c/src/capi.rs"
                               "src/native.rs" "host/c/src/native.rs"
                               "src/pod.rs" "host/pod/src/main.rs"
                               "Cargo.toml" {:text cargo}
                               "Cargo.lock" "host/rust/Cargo.lock"))]
          (stage! dir files ["src"])
          ;; THE LIBRARY gets the flags ALONE (`cargo rustc --lib -- FLAGS`, not RUSTFLAGS): RUSTFLAGS reach every dependency, and each of the 18 crates of the
          ;; graph would then bundle its own copy of Tesseract and Leptonica into libemon.a (242 MiB instead of about 34). THE POD is a binary, which links
          ;; each static library once whatever the dependencies say, so it is built the way the CLI is (RUSTFLAGS, D541) into a target directory of its own.
          (let [full? (not (:why inputs))
                env (when full? {"MACOSX_DEPLOYMENT_TARGET" (or (System/getenv "MACOSX_DEPLOYMENT_TARGET") "12.0")})
                ;; Windows (D1580): the C runtime is linked statically (/MT), as `bb native` builds the C and C++ libraries: no VC++ redistributable
                crt (when (str/includes? (host-triple) "windows-msvc") ["-C" "target-feature=+crt-static"])
                flags (when full? (concat ["--cfg" "pdfium" "--cfg" "pdfium_dl"] crt (str/split (str/trim (slurp (str (:prefix inputs) "/link.rustflags"))) #"\s+")))
                ;; cargo's own flags, then (after `--`) rustc's; offline first, the registry only when a crate is not cached
                attempt (fn [env pre post]
                          (let [go (fn [offline] (apply run {:dir dir :ok? false :env env} (concat ["cargo"] pre ["--locked"] offline (when (seq post) (cons "--" post)))))
                                r (go ["--offline"])]
                            (if (zero? (:exit r)) r (go []))))
                ;; the shared library's own name is where it is found by: rustc's default is the ABSOLUTE path it was linked at (.../deps/libemon.dylib), which
                ;; works where it was built and nowhere else; @rpath/ (macOS) and a soname (Linux) make `-Wl,-rpath,DIR` the way it is found
                ident (if (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
                        ["-C" "link-arg=-Wl,-install_name,@rpath/libemon.dylib"]
                        ["-C" "link-arg=-Wl,-soname,libemon.so"])
                lib (attempt env (concat ["rustc" "--release" "--lib"] (when full? ["--features" "tesseract"])) (concat ident flags))
                pod (when (zero? (:exit lib))
                      (attempt (cond-> (assoc env "CARGO_TARGET_DIR" (str dir "/target-pod")) full? (assoc "RUSTFLAGS" (str/join " " flags)))
                               (concat ["build" "--release" "--bin" "emon-pod"] (when full? ["--features" "tesseract"])) []))
                r (if (zero? (:exit lib)) pod lib)]
            (when-not (zero? (:exit r)) (bad! "cargo build of the C library failed\n" (tail (str (:out r) "\n" (:err r)) 40)))
            (reset! native-pod-dir (str dir "/target-pod/release"))
            (reset! native-full (when full? inputs)))
          (reset! native-built (str dir "/target/release"))))))

(defn- native-extras
  "The archive entries the full pipeline adds under a prefix `lib-dir` (where libemon or the pod looks): pdfium in lib/, the models in share/emon/tessdata/."
  []
  (when-let [{:keys [pdfium data]} @native-full]
    (merge {(str "lib/" (fs/file-name pdfium)) pdfium}
           (into {} (for [{:keys [file src]} data] [(str "share/emon/tessdata/" file) src])))))

(defn- archive!
  "tar.gz of a directory NAME made of `files` ({entry path in archive -> source path}), into out/<host>/NAME.tar.gz."
  [host name files]
  (let [tmp (str (stage-dir host) "/archive")
        d (str tmp "/" name)
        tgz (str (out-dir host) "/" name ".tar.gz")]
    (fs/delete-tree tmp)
    (fs/create-dirs d)
    (doseq [[rel src] files]
      (fs/create-dirs (fs/parent (str d "/" rel)))
      (fs/copy src (str d "/" rel) {:replace-existing true :copy-attributes true}))
    (fs/create-dirs (out-dir host))
    (run {:dir tmp} "tar" "-czf" tgz name)
    tgz))

(defn build-c! []
  (let [{:keys [dyn static]} (native-lib-names)
        rel (build-native!)
        triple (host-triple)
        name (str "emon-c-" version "-" triple)
        tgz (archive! :c name (merge {"include/emon.h" (str root "/host/c/include/emon.h")
                                      (str "lib/" dyn) (str rel "/" dyn)
                                      (str "lib/" static) (str rel "/" static)
                                      "LICENSE" (str root "/LICENSE")
                                      "THIRD_PARTY_NOTICES.md" (str root "/THIRD_PARTY_NOTICES.md")
                                      "README.md" (str root "/release/pkg/README.md")}
                                     (native-extras)))
        names (tar-list tgz)]
    (when (some #(re-find #"test_hooks|emon_test" %) names) (bad! "the C archive carries a test header"))
    (when (str/includes? (slurp (str root "/host/c/include/emon.h")) "emon_test_panic") (bad! "emon.h declares emon_test_panic"))
    (let [nm (run {:ok? false} "nm" "-g" (str rel "/" dyn))]
      (when (str/includes? (:out nm) "emon_test_panic") (bad! "libemon exports emon_test_panic")))
    ;; the library is found by @rpath/ (macOS) or its soname (Linux), never by the absolute path it was linked at (this was the case before D552)
    (let [mac? (= "libemon.dylib" dyn)
          r (if mac? (run {:ok? false} "otool" "-D" (str rel "/" dyn)) (run {:ok? false} "sh" "-c" (str "objdump -p " rel "/" dyn " | grep SONAME")))]
      (when (and (zero? (:exit r)) (not (str/includes? (:out r) (if mac? "@rpath/libemon.dylib" "libemon.so"))))
        (bad! dyn " is named by an absolute path, not @rpath/ or its soname:\n" (:out r)))
      (when (and (zero? (:exit r)) (re-find #"/deps/" (:out r))) (bad! dyn " carries its build path in its name")))
    ;; bb native --audit's rule, held to the product (D540): no jbig2dec, JBIG-KIT or OpenJPEG symbol in either library, and no pdfium linked
    (when @native-full
      (doseq [f [dyn static]]
        (let [nm (run {:ok? false} "nm" (str rel "/" f))]
          (when (re-find #"(?m)\b_?(jbig2_[a-z_]+|jbg_[a-z_]+|opj_[a-z_]+|jbig2dec_[a-z_]+)\b" (:out nm)) (bad! f " has a jbig2dec, JBIG-KIT or OpenJPEG symbol"))
          (when (re-find #"(?m)^\S*\s+[Uu]\s+_?FPDF_InitLibrary" (:out nm)) (bad! f " links pdfium (it must be loaded at run time)")))))
    [tgz]))

(defn build-pod! []
  (let [{:keys [pod]} (native-lib-names)
        _ (build-native!)
        rel @native-pod-dir
        triple (host-triple)
        name (str "emon-pod-" version "-" triple)]
    [(archive! :pod name (merge {pod (str rel "/" pod)
                                 "LICENSE" (str root "/LICENSE")
                                 "THIRD_PARTY_NOTICES.md" (str root "/THIRD_PARTY_NOTICES.md")
                                 "README.md" (str root "/release/pkg/README.md")}
                                (native-extras)))]))

;; ------------------------------------------------------------------------------------------------------ build all

(def builders {:rust build-rust! :go build-go! :java build-java! :csharp build-csharp! :js build-js! :c build-c! :pod build-pod!})

(defn- artifact-line [f] (format "%-62s %10s  sha256 %s" (str (fs/relativize root f)) (human (fs/size f)) (subs (sha256 f) 0 16)))

(defn build-hosts!
  "Build each host; returns {host {:status :built|:skip|:fail, :files [...], :why s}}."
  [hosts]
  (into (array-map)
        (for [h hosts]
          (let [t0 (System/nanoTime)
                secs #(Math/round (/ (- (System/nanoTime) t0) 1e9))]
            (try
              (let [files ((builders h))]
                (say "  ok   " (name h) ": " (str/join ", " (map #(str (fs/file-name %) " (" (human (fs/size %)) ")") files)) " [" (secs) " s]")
                [h {:status :built :files files}])
              (catch Exception e
                (if (:skip (ex-data e))
                  (do (say "  SKIP " (name h) " -- " (ex-message e)) [h {:status :skip :why (ex-message e)}])
                  (do (say "  FAIL " (name h) ": " (ex-message e)) [h {:status :fail :why (ex-message e)}]))))))))

;; ------------------------------------------------------------------------------------------------------ the consumers

(def sample-pdf (str root "/samples/" sample ".pdf"))
(def sample-html (str root "/samples/" sample ".html"))

(defn- tmp-dir [label] (str (fs/create-temp-dir {:prefix (str "emon-pkg-" label "-")})))

;; THE QUICKSTARTS ARE THE CONSUMERS (P11.6, D549). Each host's consumer below is the file `docs/snippets/<host>/quickstart.*` byte for byte,
;; the very program README.md and docs/hosts/<host>.md print (`bb docs --check` holds the printed text to the file). It reads `report.pdf`
;; and writes `report.html` in its working directory, so the consumer's directory gets a copy of the sample as `report.pdf`, the program
;; runs there, and `report.html` is what is compared with samples/align-justify.html. A docs snippet that no longer compiles against the built
;; artifact, or no longer gives the sample's bytes, is a red package gate.
(defn- snippet-file [rel] (str root "/docs/snippets/" rel))

;; THE NATIVE SNIPPETS (docs/hosts/{go,java,csharp}.md: pdfium and Tesseract wired into the Config) always compile against the built artifact.
;; They run where this machine has both engines (a developer's, after `bb pdfium-fetch`; not a CI runner): they convert a scan and the
;; heading OCR reads must be in the HTML. Elsewhere the check says so and does not pretend.
(def scan-pdf (str root "/samples/scan-plain.pdf"))
(def scan-word "Adaptive Sampling") ; the sample's heading; the OCR reads it right
(defn- first-dir [cands pred] (first (filter #(and % (fs/directory? %) (pred %)) cands)))
(defn- has-lib? [dir stem] (boolean (seq (filter #(re-find (re-pattern (str "^" stem "\\.(dylib|so|dll)|^" stem "\\.so\\.")) (str (fs/file-name %))) (fs/list-dir dir)))))
(defn- live-ports
  "{env vars, :tess-lib dir} where pdfium, Tesseract and an English model are all here, else nil."
  []
  (let [env* #(System/getenv %)
        pdfium (first-dir [(env* "EMON_PDFIUM_LIB") (str root "/.cache/pdfium/current/lib")] #(or (has-lib? % "libpdfium") (fs/exists? (str % "/pdfium.dll"))))
        tlib (first-dir [(env* "EMON_TESS_LIB") "/opt/homebrew/lib" "/usr/local/lib" "/usr/lib/x86_64-linux-gnu" "/usr/lib/aarch64-linux-gnu" "/usr/lib"] #(has-lib? % "libtesseract"))
        tdata (first-dir [(env* "TESSDATA_PREFIX") (some-> (env* "TESSDATA_PREFIX") (str "/tessdata")) "/opt/homebrew/share/tessdata" "/usr/local/share/tessdata"
                          "/usr/share/tesseract-ocr/5/tessdata" "/usr/share/tessdata"] #(fs/exists? (str % "/eng.traineddata")))]
    (when (and pdfium tlib tdata)
      {:env {"EMON_PDFIUM_LIB" pdfium "EMON_TESS_LIB" tlib "TESSDATA_PREFIX" tdata} :tess-lib tlib})))
(defn- scan-html-ok! [host app]
  (let [f (str app "/scan.html")]
    (when-not (fs/exists? f) (bad! host ": the native snippet wrote no scan.html"))
    (when-not (str/includes? (slurp f) scan-word) (bad! host ": the native snippet's HTML does not hold " (pr-str scan-word) ": pdfium and Tesseract did not read the scan"))))
(defn- put-sample! [app] (fs/create-dirs app) (fs/copy sample-pdf (str app "/report.pdf") {:replace-existing true}))
(defn- report-html [app]
  (let [f (str app "/report.html")]
    (when-not (fs/exists? f) (bad! "the quickstart wrote no report.html in " app))
    (slurp f)))

(defn- consume-rust [{:keys [files]} tmp]
  (let [crate (first files)
        _ (run {} "tar" "-xzf" crate "-C" tmp)
        src (str tmp "/emon-" version)
        app (str tmp "/app")]
    (fs/create-dirs (str app "/src"))
    (spit (str app "/Cargo.toml")
          (str "[package]\nname = \"consumer\"\nversion = \"0.0.0\"\nedition = \"2021\"\n\n[dependencies]\nemon = { path = \"" src "\" }\n\n[workspace]\n"))
    (put-sample! app)
    (spit (str app "/src/main.rs") (slurp (snippet-file "rust/quickstart.rs")))
    (fs/create-dirs (str app "/src/bin"))
    (spit (str app "/src/bin/tour.rs") (slurp (snippet-file "rust/tour.rs")))
    ;; the hidden test setting must not exist on the published Options
    (spit (str app "/src/neg.rs") "fn _neg() { let mut o = emon::Options::default(); o.no_retarget = true; }\n")
    (let [env {"CARGO_TARGET_DIR" (str tmp "/target")}
          r (let [r (run {:dir app :env env :ok? false} "cargo" "build" "--release" "--offline" "-q")]
              (if (zero? (:exit r)) r (run {:dir app :env env} "cargo" "build" "--release" "-q")))
          neg (do (spit (str app "/src/main.rs") (str (slurp (str app "/src/main.rs")) "mod neg;\n"))
                  (run {:dir app :env env :ok? false} "cargo" "build" "--release" "--offline" "-q"))]
      (when (zero? (:exit neg)) (bad! "rust: Options::no_retarget compiles in the published crate"))
      (when-not (re-find #"no field `no_retarget`" (str (:out neg) (:err neg))) (bad! "rust: the negative build failed for another reason\n" (tail (str (:out neg) (:err neg)) 10)))
      (spit (str app "/src/main.rs") (str/replace (slurp (str app "/src/main.rs")) "mod neg;\n" ""))
      (run {:dir app :env env} "cargo" "build" "--release" "--offline" "-q")
      (run {:dir app :env env} (str tmp "/target/release/consumer"))
      (run {:dir app :env env} (str tmp "/target/release/tour"))
      ;; pdfium and Tesseract wired in (docs/hosts/rust.md): a project of its own, built with the crate's `tesseract` feature and --cfg pdfium, where the engines are
      (if-let [live (live-ports)]
        (let [napp (str tmp "/app-native")
              pd (get (:env live) "EMON_PDFIUM_LIB")
              nenv {"CARGO_TARGET_DIR" (str tmp "/target-native")
                    "RUSTFLAGS" (str "--cfg pdfium -L native=" pd " -L native=" (:tess-lib live) " -C link-arg=-Wl,-rpath," pd)}]
          (fs/create-dirs (str napp "/src"))
          (spit (str napp "/Cargo.toml")
                (str "[package]\nname = \"native\"\nversion = \"0.0.0\"\nedition = \"2021\"\n\n[dependencies]\nemon = { path = \"" src "\", features = [\"tesseract\"] }\n\n[workspace]\n"))
          (spit (str napp "/src/main.rs") (slurp (snippet-file "rust/native.rs")))
          (fs/copy scan-pdf (str napp "/scan.pdf") {:replace-existing true})
          (let [r (run {:dir napp :env nenv :ok? false} "cargo" "build" "--release" "--offline" "-q")]
            (when-not (zero? (:exit r)) (run {:dir napp :env nenv} "cargo" "build" "--release" "-q")))
          (run {:dir napp :env (merge nenv (:env live))} (str tmp "/target-native/release/native"))
          (scan-html-ok! "rust" napp))
        (say "  NOTE rust: pdfium or Tesseract is not installed here, so the native snippet was not built"))
      (report-html app))))

(defn- consume-go [{:keys [files]} tmp]
  (let [proxy (str tmp "/proxy")
        app (str tmp "/app")
        env {"GOPROXY" (str "file://" proxy) "GOFLAGS" "-mod=mod" "GOSUMDB" "off" "GONOSUMDB" "*" "GOMODCACHE" (str tmp "/modcache")
             "GOPATH" (str tmp "/gopath") "GO111MODULE" "on" "GOTOOLCHAIN" "local"}]
    (go-proxy! (first files) proxy)
    (fs/create-dirs app)
    (spit (str app "/go.mod") (str "module consumer\n\ngo 1.21\n\nrequire " go-module " v" version "\n"))
    (put-sample! app)
    (let [src (slurp (snippet-file "go/quickstart.go"))]
      (when-not (str/includes? src (str "emon \"" go-module "\"")) (bad! "docs/snippets/go/quickstart.go does not import " go-module))
      (spit (str app "/main.go") src))
    (run {:dir app :env env} "go" "build" "-o" "consumer" ".")
    (fs/create-dirs (str app "/tour"))
    (spit (str app "/tour/main.go") (slurp (snippet-file "go/tour.go")))
    (run {:dir app :env env} "go" "build" "-o" "tourbin" "./tour")
    ;; pdfium and Tesseract wired in: it compiles in the module's plain build (NewTessOcr then answers an error); with the engines here it is built with -tags tesseract and run
    (fs/create-dirs (str app "/native"))
    (spit (str app "/native/main.go") (slurp (snippet-file "go/native.go")))
    (run {:dir app :env env} "go" "build" "-o" "nativebin" "./native")
    (if-let [live (live-ports)]
      (let [env2 (merge env (:env live) {"CGO_LDFLAGS" (str "-L" (:tess-lib live))})]
        (fs/copy scan-pdf (str app "/scan.pdf") {:replace-existing true})
        (run {:dir app :env env2} "go" "build" "-tags" "tesseract" "-o" "nativebin" "./native")
        ;; macOS kills a cgo binary linked to Homebrew's Tesseract at launch until it is signed again, ad hoc (kin/scripts/emon-go-build does the same)
        (when (= "Darwin" (str/trim (:out (run {} "uname" "-s")))) (run {:ok? false} "codesign" "-s" "-" "-f" (str app "/nativebin")))
        (run {:dir app :env env2} (str app "/nativebin"))
        (scan-html-ok! "go" app))
      (say "  NOTE go: pdfium or Tesseract is not installed here, so the native snippet was compiled but not run"))
    ;; the generated packages are internal/: an importer outside the module is refused by the go tool
    (fs/create-dirs (str app "/neg"))
    (spit (str app "/neg/neg.go") (str "package neg\n\nimport _ \"" go-module "/internal/model\"\n"))
    (let [neg (run {:dir app :env env :ok? false} "go" "build" "./neg")]
      (when (zero? (:exit neg)) (bad! "go: the generated package internal/model is importable from outside the module"))
      (when-not (re-find #"internal" (str (:out neg) (:err neg))) (bad! "go: the negative import failed for another reason\n" (tail (str (:out neg) (:err neg)) 8))))
    (run {:dir app :env env} "go" "mod" "verify")
    (run {:dir app :env env} (str app "/consumer"))
    (run {:dir app :env env} (str app "/tourbin"))
    (report-html app)))

(defn- java-native! [jdk cp app]
  (if-let [live (live-ports)]
    (do (fs/copy scan-pdf (str app "/scan.pdf") {:replace-existing true})
        (run {:dir app :env (:env live)} (str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" cp "Native")
        (scan-html-ok! "java" app))
    (say "  NOTE java: pdfium or Tesseract is not installed here, so the native snippet was compiled but not run")))

(defn- consume-java [{:keys [files]} tmp]
  (let [jdk (find-jdk)
        bundle (first files)
        repo (str tmp "/m2")
        app (str tmp "/app")]
    (fs/create-dirs repo)
    (run {} "unzip" "-q" bundle "-d" repo)
    (let [jar (str repo "/com/3sln/emon/" version "/emon-" version ".jar")]
      (when-not (fs/exists? jar) (bad! "the bundle has no com/3sln/emon/" version "/emon-" version ".jar"))
      (put-sample! app)
      (spit (str app "/Quickstart.java") (slurp (snippet-file "java/Quickstart.java")))
      (spit (str app "/Tour.java") (slurp (snippet-file "java/Tour.java")))
      (spit (str app "/Native.java") (slurp (snippet-file "java/Native.java")))
      (spit (str app "/Neg.java") "import emon.api.*;\npublic class Neg { boolean f() { return Options.defaults().noRetarget; } }\n")
      (if (have? "mvn")
        ;; Maven resolves the artifact from the unpacked repository layout as a local repository
        (do (spit (str app "/pom.xml")
                  (str "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>consumer</groupId><artifactId>consumer</artifactId><version>0</version>"
                       "<properties><maven.compiler.release>22</maven.compiler.release></properties>"
                       "<dependencies><dependency><groupId>com.3sln</groupId><artifactId>emon</artifactId><version>" version "</version></dependency></dependencies></project>"))
            (run {:dir app :env {"JAVA_HOME" jdk}} "mvn" "-q" "-B" (str "-Dmaven.repo.local=" repo) "dependency:build-classpath" (str "-Dmdep.outputFile=" app "/cp.txt"))
            (let [cp (str (slurp (str app "/cp.txt")))]
              (run {:dir app} (str jdk "/bin/javac") "--release" "22" "-cp" cp "-d" app "Quickstart.java" "Tour.java" "Native.java")
              (let [neg (run {:dir app :ok? false} (str jdk "/bin/javac") "--release" "22" "-cp" cp "-d" app "Neg.java")]
                (when (zero? (:exit neg)) (bad! "java: Options.noRetarget is accessible in the published jar"))
                (when-not (re-find #"noRetarget" (str (:out neg) (:err neg))) (bad! "java: the negative compile failed for another reason\n" (tail (str (:out neg) (:err neg)) 8))))
              (run {:dir app} (str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" (str cp ":" app) "Quickstart")
              (run {:dir app} (str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" (str cp ":" app) "Tour")
              (java-native! jdk (str cp ":" app) app)
              (report-html app)))
        ;; no Maven here: the same repository layout, read by javac and java directly
        (do (say "  NOTE java: no mvn on the PATH, so the bundle's repository layout was unpacked and read by javac and java directly (Maven's own resolution was not exercised)")
            (run {:dir app} (str jdk "/bin/javac") "--release" "22" "-cp" jar "-d" app "Quickstart.java" "Tour.java" "Native.java")
            (let [neg (run {:dir app :ok? false} (str jdk "/bin/javac") "--release" "22" "-cp" jar "-d" app "Neg.java")]
              (when (zero? (:exit neg)) (bad! "java: Options.noRetarget is accessible in the published jar"))
              (when-not (re-find #"noRetarget" (str (:out neg) (:err neg))) (bad! "java: the negative compile failed for another reason\n" (tail (str (:out neg) (:err neg)) 8))))
            (run {:dir app} (str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" (str jar ":" app) "Quickstart")
            (run {:dir app} (str jdk "/bin/java") "--enable-native-access=ALL-UNNAMED" "-cp" (str jar ":" app) "Tour")
            (java-native! jdk (str jar ":" app) app)
            (report-html app))))))

(defn- consume-csharp [{:keys [files]} tmp]
  (let [{:keys [path major]} (dotnet)
        app (str tmp "/app")
        feed (fs/parent (first files))
        env (assoc dotnet-env "NUGET_PACKAGES" (str tmp "/nuget") "DOTNET_ROLL_FORWARD" "Major")]
    (fs/create-dirs app)
    (spit (str app "/nuget.config")
          (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<configuration><packageSources><clear /><add key=\"local\" value=\"" feed "\" /></packageSources></configuration>\n"))
    (spit (str app "/consumer.csproj")
          (str "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net" major ".0</TargetFramework>"
               "<ImplicitUsings>disable</ImplicitUsings><Nullable>disable</Nullable><UseAppHost>false</UseAppHost><InvariantGlobalization>true</InvariantGlobalization></PropertyGroup>"
               "<ItemGroup><PackageReference Include=\"Emon\" Version=\"" version "\" /></ItemGroup></Project>\n"))
    (put-sample! app)
    (spit (str app "/Program.cs") (slurp (snippet-file "csharp/Quickstart.cs")))
    (run {:dir app :env env} path "build" "-c" "Release" "--nologo" "-v" "q" "-clp:NoSummary" "-o" "out")
    ;; the hidden test setting is internal to the assembly
    (spit (str app "/Neg.cs") "class Neg { bool F() { return emon.Api.Options.Defaults().NoRetarget; } }\n")
    (let [neg (run {:dir app :env env :ok? false} path "build" "-c" "Release" "--nologo" "-v" "q" "-clp:NoSummary" "-o" "out2")]
      (when (zero? (:exit neg)) (bad! "csharp: Options.NoRetarget is accessible in the published package"))
      (when-not (re-find #"NoRetarget" (str (:out neg) (:err neg))) (bad! "csharp: the negative build failed for another reason\n" (tail (str (:out neg) (:err neg)) 8))))
    (fs/delete (str app "/Neg.cs"))
    (run {:dir app :env env} path "out/consumer.dll")
    ;; the tour is a program of its own (one file of top-level statements to a project): the same project, built again
    (let [html (report-html app)]
      (spit (str app "/Program.cs") (slurp (snippet-file "csharp/Tour.cs")))
      (run {:dir app :env env} path "build" "-c" "Release" "--nologo" "-v" "q" "-clp:NoSummary" "-o" "out3")
      (run {:dir app :env env} path "out3/consumer.dll")
      ;; pdfium and Tesseract wired in: built always, run where the engines are
      (spit (str app "/Program.cs") (slurp (snippet-file "csharp/Native.cs")))
      (run {:dir app :env env} path "build" "-c" "Release" "--nologo" "-v" "q" "-clp:NoSummary" "-o" "out4")
      (if-let [live (live-ports)]
        (do (fs/copy scan-pdf (str app "/scan.pdf") {:replace-existing true})
            (run {:dir app :env (merge env (:env live))} path "out4/consumer.dll")
            (scan-html-ok! "csharp" app))
        (say "  NOTE csharp: pdfium or Tesseract is not installed here, so the native snippet was compiled but not run"))
      html)))

(defn- consume-js [{:keys [files]} tmp]
  (let [tgz (first (filter #(str/includes? (str (fs/file-name %)) "3sln-emon-") files))
        pdfium-tgz (first (filter #(str/includes? (str (fs/file-name %)) "3sln-pdfium-wasm-") files)) ; nil when it was not built (no `bb pdfium-fetch --platform wasm`)
        app (str tmp "/app")
        major (parse-long (str/trim (:out (run {} "node" "-p" "process.versions.node.split('.')[0]"))))]
    (fs/create-dirs app)
    (spit (str app "/package.json") "{\"name\":\"consumer\",\"version\":\"0.0.0\",\"private\":true,\"type\":\"module\"}\n")
    ;; @3sln/emon alone first: its optional peer is not installed (nor fetched), the main package is dependency-free
    (run {:dir app :env {"npm_config_audit" "false" "npm_config_fund" "false" "npm_config_update_notifier" "false"}} "npm" "install" "--offline" "--ignore-scripts" tgz)
    (let [pkg (str app "/node_modules/@3sln/emon")]
      (when-not (fs/exists? (str pkg "/emon.wasm")) (bad! "the installed package has no emon.wasm"))
      (when (fs/exists? (str app "/node_modules/@3sln/pdfium-wasm")) (bad! "installing @3sln/emon alone installed its optional peer @3sln/pdfium-wasm")))
    (when pdfium-tgz
      (run {:dir app :env {"npm_config_audit" "false" "npm_config_fund" "false" "npm_config_update_notifier" "false"}} "npm" "install" "--offline" "--ignore-scripts" pdfium-tgz)
      (let [d (str app "/node_modules/@3sln/pdfium-wasm")]
        (doseq [f ["pdfium.mjs" "pdfium.wasm" "LICENSE" "licenses/pdfium.txt"]]
          (when-not (fs/exists? (str d "/" f)) (bad! "the installed @3sln/pdfium-wasm has no " f)))))
    (put-sample! app)
    (spit (str app "/quickstart.mjs") (slurp (snippet-file "js/quickstart.mjs")))
    (spit (str app "/tour.mjs") (slurp (snippet-file "js/tour.mjs")))
    ;; what the quickstart does not touch: the two adapters' subpath exports, and that a Model's handles are not enumerable
    (spit (str app "/exports.mjs")
          (str "import { readFile } from 'node:fs/promises';\n"
               "import { Emon } from '@3sln/emon';\n"
               "import { pdfiumRenderer } from '@3sln/emon/pdfium';\n"
               "import { tesseractPort } from '@3sln/emon/tesseract';\n"
               "if (typeof pdfiumRenderer !== 'function' || typeof tesseractPort !== 'function') throw new Error('subpath exports');\n"
               "const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));\n"
               "const model = await emon.convert(await readFile('report.pdf'));\n"
               "if (Object.keys(model).some((k) => k === 'w' || k === 'h')) throw new Error('the handles are enumerable');\n"
               "model.dispose();\n"))
    (let [flag (if (< major 25) "--experimental-wasm-exnref" "--no-warnings")]
      (run {:dir app} "node" flag "exports.mjs")
      (run {:dir app} "node" flag "quickstart.mjs")
      (run {:dir app} "node" flag "tour.mjs")
      ;; @3sln/pdfium-wasm is the default renderer (D553): `pdfiumRenderer()` with no arguments imports the INSTALLED package, and draws the figures of a
      ;; document (assets=figures crops are rendered by pdfium) with no other engine; the package's own exports are the factory and the .wasm's URL
      (if pdfium-tgz
        (do (fs/copy (str root "/test/assets/fig/fig-vector-chart.pdf") (str app "/figures.pdf") {:replace-existing true})
            (spit (str app "/default-renderer.mjs")
                  (str "import { readFile } from 'node:fs/promises';\n"
                       "import { Emon } from '@3sln/emon';\n"
                       "import { pdfiumRenderer } from '@3sln/emon/pdfium';\n"
                       "import pdfium, { wasmUrl, wasmBinary } from '@3sln/pdfium-wasm';\n"
                       "if (typeof pdfium !== 'function' || !(wasmUrl instanceof URL) || !((await wasmBinary()).length > 1000000)) throw new Error('the package exports a factory, wasmUrl and wasmBinary');\n"
                       "const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));\n"
                       "const render = pdfiumRenderer();\n"
                       "const model = await emon.convert(await readFile('figures.pdf'), { ocr: 'never', assets: 'figures', ports: { render } });\n"
                       "if (model.assets.length === 0) throw new Error('no figure was drawn by the default renderer');\n"
                       "for (const a of model.assets) { const b = await a.bytes(); if (!(b.length > 100)) throw new Error('an empty figure'); }\n"
                       "console.log(model.assets.length + ' figure(s) drawn by @3sln/pdfium-wasm');\n"
                       "model.dispose();\nawait render.terminate();\n"))
            (run {:dir app} "node" flag "default-renderer.mjs"))
        (say "  NOTE js: @3sln/pdfium-wasm was not built here (no `bb pdfium-fetch --platform wasm`), so the default renderer was not run"))
      ;; pdfium's WebAssembly build and tesseract.js wired in (docs/hosts/js.md): run where the package above is and `npm ci` in host/js was done
      (let [nm (or (System/getenv "EMON_JS_NODE_MODULES") (str root "/host/js/node_modules"))]
        (if (and pdfium-tgz (fs/exists? (str nm "/tesseract.js")) (fs/exists? (str nm "/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz")))
          (do (doseq [d (fs/list-dir nm) :let [n (str (fs/file-name d))] :when (not (fs/exists? (str app "/node_modules/" n)))]
                (fs/create-sym-link (str app "/node_modules/" n) d))
              (fs/copy scan-pdf (str app "/scan.pdf") {:replace-existing true})
              (spit (str app "/native.mjs") (slurp (snippet-file "js/native.mjs")))
              (run {:dir app} "node" flag "native.mjs")
              (scan-html-ok! "js" app))
          (say "  NOTE js: @3sln/pdfium-wasm or tesseract.js is not installed here, so the native snippet was not run"))))
    (report-html app)))

(defn- unpack [tgz tmp] (run {} "tar" "-xzf" tgz "-C" tmp) (let [d (first (fs/list-dir tmp))] (str d)))

(defn- consume-c [{:keys [files]} tmp]
  (let [dir (unpack (first files) tmp)
        app (str tmp "/app")
        {:keys [dyn]} (native-lib-names)
        cc (or (first (filter have? ["cc" "clang" "gcc"])) (skip! "no C compiler"))
        sys (if (= "Darwin" (str/trim (:out (run {} "uname" "-s")))) ["-framework" "Security" "-framework" "CoreFoundation" "-liconv" "-lm" "-lc++"] ["-lpthread" "-ldl" "-lm" "-lstdc++"])] ; the C++ runtime: libemon.a holds Tesseract (D552)
    (put-sample! app)
    (spit (str app "/main.c") (slurp (snippet-file "c/quickstart.c")))
    (spit (str app "/tour.c") (slurp (snippet-file "c/tour.c")))
    (spit (str app "/neg.c") "#include \"emon.h\"\nint f(emon_error *e) { return emon_test_panic(e); }\n")
    (let [flags ["-std=c11" "-Wall" "-Werror" (str "-I" dir "/include")]
          dynamic (concat [cc] flags ["-o" (str app "/c-dyn") (str app "/main.c") (str "-L" dir "/lib") "-lemon" (str "-Wl,-rpath," dir "/lib")])
          static (concat [cc] flags ["-o" (str app "/c-static") (str app "/main.c") (str dir "/lib/libemon.a")] sys)
          neg (concat [cc] flags ["-c" "-o" (str app "/neg.o") (str app "/neg.c")])]
      (apply run {:dir app} dynamic)
      (apply run {:dir app} static)
      (apply run {:dir app} (concat [cc] flags ["-o" (str app "/c-tour") (str app "/tour.c") (str "-L" dir "/lib") "-lemon" (str "-Wl,-rpath," dir "/lib")]))
      (run {:dir app} (str app "/c-tour"))
      ;; the step API (the session): the engine says what it needs, the caller reads the bytes; its HTML is the one-shot call's
      (spit (str app "/session.c") (slurp (snippet-file "c/session.c")))
      (apply run {:dir app} (concat [cc] flags ["-o" (str app "/c-session") (str app "/session.c") (str "-L" dir "/lib") "-lemon" (str "-Wl,-rpath," dir "/lib")]))
      (let [expected (do (run {:dir app} (str app "/c-dyn")) (report-html app))
            via-session (:out (run {:dir app} (str app "/c-session")))]
        (when-not (= expected via-session) (bad! "c: the step API gave other bytes than the one-shot call")))
      (let [n (apply run {:dir app :ok? false} neg)]
        (when (zero? (:exit n)) (bad! "c: emon_test_panic is declared in the installed emon.h"))
        (when-not (re-find #"emon_test_panic" (str (:out n) (:err n))) (bad! "c: the negative compile failed for another reason\n" (tail (str (:out n) (:err n)) 8))))
      (let [a (do (run {:dir app} (str app "/c-dyn")) (report-html app))
            b (do (fs/delete (str app "/report.html")) (run {:dir app} (str app "/c-static")) (report-html app))]
        (when-not (= a b) (bad! "c: the shared and the static library gave different bytes"))
        (when-not (fs/exists? (str dir "/lib/" dyn)) (bad! "the archive has no lib/" dyn))
        ;; the full pipeline (D552): a scan is drawn by the archive's pdfium and read by its static Tesseract, with NO EMON_* in the environment
        ;; (the shared library finds lib/libpdfium.* and ../share/emon/tessdata next to itself); the static link finds them through the environment
        ;; (the executable is not beside them). A plain archive (built without .cache/native) says so and skips this.
        (if (fs/exists? (str dir "/share/emon/tessdata/eng.traineddata"))
          (let [napp (str tmp "/app-native")
                clean {"EMON_PDFIUM_LIB" "" "EMON_TESSDATA" "" "TESSDATA_PREFIX" ""}]
            (fs/create-dirs napp)
            (fs/copy scan-pdf (str napp "/scan.pdf") {:replace-existing true})
            (spit (str napp "/native.c") (slurp (snippet-file "c/native.c")))
            (apply run {:dir napp} (concat [cc] flags ["-o" (str napp "/c-native") (str napp "/native.c") (str "-L" dir "/lib") "-lemon" (str "-Wl,-rpath," dir "/lib")]))
            (run {:dir napp :env clean} (str napp "/c-native"))
            (scan-html-ok! "c (shared)" napp)
            (fs/delete (str napp "/scan.html"))
            (apply run {:dir napp} (concat [cc] flags ["-o" (str napp "/c-native-static") (str napp "/native.c") (str dir "/lib/libemon.a")] sys))
            (run {:dir napp :env (assoc clean "EMON_PDFIUM_LIB" (str dir "/lib") "EMON_TESSDATA" (str dir "/share/emon/tessdata"))} (str napp "/c-native-static"))
            (scan-html-ok! "c (static)" napp))
          (say "  NOTE c: the archive was built without pdfium and Tesseract, so the native snippet was not run"))
        a))))

(defn- consume-pod [{:keys [files]} tmp]
  (let [dir (unpack (first files) tmp)
        app (str tmp "/app")
        script (str tmp "/bytes.clj")
        ;; the quickstart finds the pod on the PATH, as a user who put it there would have it
        env {"PATH" (str dir ":" (System/getenv "PATH"))}]
    (put-sample! app)
    (spit (str app "/quickstart.clj") (slurp (snippet-file "pod/quickstart.clj")))
    (spit (str app "/tour.clj") (slurp (snippet-file "pod/tour.clj")))
    ;; the other way in, which the quickstart does not use: the bytes, base64 in an EDN map
    (spit script
          (str "(require '[babashka.pods :as pods] '[babashka.fs :as fs])\n"
               "(import '(java.util Base64))\n"
               "(pods/load-pod [\"" dir "/emon-pod\"])\n"
               "(let [r (emon/convert {:bytes (.encodeToString (Base64/getEncoder) (fs/read-all-bytes (first *command-line-args*)))} \"\" {:html \"\"})]\n"
               "  (.write System/out (.getBytes ^String (:html r) \"UTF-8\"))\n  (.flush System/out))\n"))
    (let [by-bytes (:out (run {} "bb" script sample-pdf))]
      (run {:dir app :env env} "bb" "quickstart.clj")
      (let [t (run {:dir app :env env} "bb" "tour.clj")]
        (when-not (str/includes? (:out t) ":name \"pages\"") (bad! "pod: the tour's limit error did not carry its name\n" (:out t)))
        ;; a named limit is an answer, not a bug (D554): the tour breaches `limit.pages` and the pod's stderr stays clean (it printed a Rust panic notice)
        (when (re-find #"panicked|RUST_BACKTRACE|Box<dyn Any>" (:err t)) (bad! "pod: the tour's limit breach printed a panic notice on stderr\n" (:err t))))
      ;; the full pipeline (D552): pdfium from lib/ and the models from share/emon/tessdata/ beside the pod, none named, none in the environment
      (if (fs/exists? (str dir "/share/emon/tessdata/eng.traineddata"))
        (let [clean {"EMON_PDFIUM_LIB" "" "EMON_TESSDATA" "" "TESSDATA_PREFIX" ""}
              caps (:out (run {:dir app :env (merge env clean)} "bb" "-e" "(require '[babashka.pods :as pods]) (pods/load-pod \"emon-pod\") (prn (emon/capabilities))"))]
          (when-not (and (str/includes? caps ":pdfium true") (str/includes? caps ":tesseract true")) (bad! "pod: the archive's capabilities are not pdfium and Tesseract: " caps))
          (fs/copy scan-pdf (str app "/scan.pdf") {:replace-existing true})
          (spit (str app "/native.clj") (slurp (snippet-file "pod/native.clj")))
          (run {:dir app :env (merge env clean)} "bb" "native.clj")
          (scan-html-ok! "pod" app))
        (say "  NOTE pod: the archive was built without pdfium and Tesseract, so the native snippet was not run"))
      (let [html (report-html app)]
        (when-not (= html by-bytes) (bad! "pod: a path and the bytes gave different HTML"))
        html))))

(def consumers {:rust consume-rust :go consume-go :java consume-java :csharp consume-csharp :js consume-js :c consume-c :pod consume-pod})

(defn check-hosts!
  "Install each built artifact into a clean consumer and convert the sample. Returns {host html-or-nil}."
  [built keep?]
  (let [expected (slurp sample-html)
        results (atom {})]
    (doseq [[h r] built
            :when (= :built (:status r))]
      (let [tmp (tmp-dir (name h))
            t0 (System/nanoTime)]
        (try
          (let [html ((consumers h) r tmp)]
            (swap! results assoc h html)
            (say "  ok   " (name h) " consumer: installed " (fs/file-name (first (:files r))) " into a clean project, converted samples/" sample ".pdf: "
                 (count (.getBytes ^String html "UTF-8")) " bytes of HTML" (when (= html expected) ", equal to samples/") " [" (Math/round (/ (- (System/nanoTime) t0) 1e9)) " s]"))
          (catch Exception e
            (if (:skip (ex-data e))
              (say "  SKIP " (name h) " consumer -- " (ex-message e))
              (do (swap! results assoc h :error) (say "  FAIL " (name h) " consumer: " (ex-message e)))))
          ;; (Go's module cache is read-only)
          (finally (when-not keep? (run {:ok? false} "chmod" "-R" "u+w" tmp) (fs/delete-tree tmp))))))
    @results))

(defn- verdict [built results]
  (let [expected (slurp sample-html)
        ran (filter (fn [[_ v]] (string? v)) results)
        errors (filter (fn [[_ v]] (= :error v)) results)
        failed-build (filter (fn [[_ r]] (= :fail (:status r))) built)
        diff (remove (fn [[_ v]] (= v expected)) ran)]
    (doseq [[h _] diff] (say "  FAIL " (name h) ": its HTML differs from samples/" sample ".html"))
    (when (and (seq ran) (empty? diff) (empty? errors) (empty? failed-build))
      (say "  ok   " (count ran) " host(s) (" (str/join " " (map (comp name first) ran)) ") gave identical bytes, equal to samples/" sample ".html"))
    (when (and (empty? ran) (empty? failed-build))
      (say "  SKIP no host had a toolchain: nothing was installed"))
    (zero? (+ (count diff) (count errors) (count failed-build)))))

;; ------------------------------------------------------------------------------------------------------ main

(defn- die [code & xs] (binding [*out* *err*] (println (apply str xs))) (System/exit code))

(defn -main [args]
  (let [opts (loop [as args o {}]
               (if-let [a (first as)]
                 (case a
                   "--host" (let [h (second as)] (when-not (some #{(keyword h)} all-hosts) (die 2 "bb package: --host is one of " (str/join " " (map name all-hosts)))) (recur (nnext as) (update o :hosts (fnil conj []) (keyword h))))
                   "--out" (recur (nnext as) (assoc o :out (second as)))
                   "--check" (recur (next as) (assoc o :check true))
                   "--no-build" (recur (next as) (assoc o :no-build true))
                   "--keep" (recur (next as) (assoc o :keep true))
                   "--list" (recur (next as) (assoc o :list true))
                   (die 2 "usage: bb package [--host H] [--out DIR] | --check [--host H] [--out DIR] [--no-build] [--keep] | --list"))
                 o))
        hosts (or (:hosts opts) all-hosts)
        out (str (fs/absolutize (or (:out opts) "dist")))]
    (when (:list opts)
      (doseq [h all-hosts] (println (name h)))
      (System/exit 0))
    (binding [*out* out]
      (fs/create-dirs out)
      (let [built (if (:no-build opts)
                    (into (array-map)
                          (for [h hosts]
                            (let [files (->> (when (fs/directory? (out-dir h)) (fs/list-dir (out-dir h)))
                                             (filter #(and (fs/regular-file? %) (not (re-find #"\.(md5|sha1|sha256|asc)$" (str %)))))
                                             (map str) sort vec)]
                              [h (if (seq files) {:status :built :files (if (= h :java) (vec (reverse files)) files)} {:status :skip :why "nothing built in DIR"})])))
                    (build-hosts! hosts))]
        (if (:check opts)
          (let [results (check-hosts! built (:keep opts))]
            (if (verdict built results)
              (say "  ok   package check done")
              (System/exit 1)))
          (do
            (let [files (mapcat (comp :files val) built)]
              (when (seq files)
                (spit (str out "/SHA256SUMS") (str (str/join "\n" (for [f files] (str (sha256 f) "  " (fs/relativize out f)))) "\n"))
                (say "")
                (doseq [f files] (say "  " (artifact-line f)))))
            (when (some #(= :fail (:status (val %))) built) (System/exit 1))
            (say "  ok   bb package: " (count (filter #(= :built (:status (val %))) built)) " host(s) built into " (fs/relativize root out))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
