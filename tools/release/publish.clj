#!/usr/bin/env bb
;; Publish ONE registry, idempotently (P11.10, D524, D542, D547). The publish workflow calls this once per registry, each in its own job, after
;; `bb release-verify` passed and `bb package --host H` built the artifact of that registry into dist/ from the verified commit.
;;
;;   bb publish crates|npm|nuget|maven|go [--dry-run]
;;   bb publish --self-test
;;
;; Every registry follows the same four steps: (1) the credential it needs is set (it fails before doing anything if not); (2) is this version already
;; there? then it SKIPS and exits 0 (`outcome=already-published`), so a rerun of the whole workflow publishes only what is missing; (3) publish;
;; (4) print `outcome=published`. --dry-run does (1) and (2), then says what it would publish and does not (cargo and npm run their own --dry-run).
;; Each prints the outcome as `outcome=...` and appends it to $GITHUB_OUTPUT. Credentials come from the environment only and are never printed.
;;
;;   crates  CARGO_REGISTRY_TOKEN             cargo publish --locked, from dist/stage/rust (the tree `cargo package` verified)
;;   npm     NPM_TOKEN                        npm publish dist/js/3sln-pdfium-wasm-V.tgz and dist/js/3sln-emon-V.tgz (both, the peer first; each skipped when npm has it)
;;                                            --access public [--provenance in Actions]; prerelease versions get --tag next
;;   nuget   NUGET_API_KEY                    dotnet nuget push dist/csharp/Emon.V.nupkg (and .snupkg) --skip-duplicate
;;   maven   MAVEN_CENTRAL_USERNAME, MAVEN_CENTRAL_PASSWORD, MAVEN_GPG_PRIVATE_KEY, MAVEN_GPG_PASSPHRASE
;;           sign the four files of dist/java/maven/com/3sln/emon/V with the key, re-zip the bundle, upload it to the Central Portal
;;           (https://central.sonatype.com/api/v1/publisher, publishingType AUTOMATIC) and wait for PUBLISHED or FAILED
;;   go      (no registry) GITHUB_TOKEN or a pushing checkout: commit dist/stage/go/tree/go as go/ on a TAG-ONLY commit on top of HEAD and push the tag go/vV
;;           (D542); the Go proxy learns of it on first request, and this asks it once
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[release.lib :as rl])

(def ua {"User-Agent" "emon-release-tools (https://github.com/3sln/emon)"})
(defn- version [] (:version (edn/read-string (slurp "version.edn"))))
(defn- say [& xs] (println (apply str xs)))
(defn- outcome! [o] (rl/write-output! "outcome" o))

;; ---------------------------------------------------------------------------------------------------- pure parts
(def needs
  {"crates" ["CARGO_REGISTRY_TOKEN"]
   "npm" ["NPM_TOKEN"]
   "nuget" ["NUGET_API_KEY"]
   ;; MAVEN_GPG_PASSPHRASE is optional: the maintainer's signing key has no passphrase (2026-10-14).
   "maven" ["MAVEN_CENTRAL_USERNAME" "MAVEN_CENTRAL_PASSWORD" "MAVEN_GPG_PRIVATE_KEY"]
   "go" []})

(defn gpg-sign-args
  "The gpg arguments that detach-sign file f with key fpr; a passphrase is fed on fd 0 only when one is set."
  [passphrase fpr f]
  (into ["gpg" "--batch" "--yes" "--pinentry-mode" "loopback"]
        (concat (when-not (str/blank? passphrase) ["--passphrase-fd" "0"])
                ["-u" fpr "--armor" "--detach-sign" f])))

(defn missing-env "The names in `names` that `env` (a map) does not set to something non-blank." [names env]
  (vec (remove #(not (str/blank? (get env %))) names)))

(defn crates-url [v] (str "https://crates.io/api/v1/crates/emon/" v))
(defn nuget-index-url [] "https://api.nuget.org/v3-flatcontainer/emon/index.json")
(defn maven-pom-url [v] (str "https://repo1.maven.org/maven2/com/3sln/emon/" v "/emon-" v ".pom"))
(def central "https://central.sonatype.com/api/v1/publisher")

(defn nuget-has?
  "Does the flat-container index (JSON text or nil) list version v? NuGet lower-cases versions."
  [index-json v]
  (boolean (when index-json (some #{(str/lower-case v)} (map str/lower-case (:versions (json/parse-string index-json true)))))))

(defn npm-has? "`npm view PKG@V version` printed this: is the version there?" [out v] (= v (str/trim (str out))))

(defn prerelease? [v] (boolean (re-find #"-" v)))

(defn npm-args
  "npm publish arguments for a tarball. provenance? only in Actions with an OIDC token (the workflow grants id-token: write)."
  [tgz v provenance? userconfig]
  (vec (concat ["publish" tgz "--access" "public" "--userconfig" userconfig]
               (when provenance? ["--provenance"])
               (when (prerelease? v) ["--tag" "next"]))))

(defn central-auth "The Portal's bearer token: base64(user:password)." [user pass]
  (str "Bearer " (.encodeToString (java.util.Base64/getEncoder) (.getBytes (str user ":" pass) "UTF-8"))))

(defn central-state
  "Interpret a status response body (JSON text): :published :failed or :wait, with the Portal's own words."
  [body]
  (let [m (try (json/parse-string body true) (catch Exception _ {}))
        s (:deploymentState m)]
    {:state (case s "PUBLISHED" :published "FAILED" :failed :wait) :portal s :errors (:errors m)}))

(defn gpg-key-text
  "The key as armored text. MAVEN_GPG_PRIVATE_KEY may be the armored key itself or its base64 (a one-line secret)."
  [s]
  (let [t (str/trim (str s))]
    (if (str/starts-with? t "-----BEGIN")
      (str t "\n")
      (try (String. (.decode (java.util.Base64/getMimeDecoder) t) "UTF-8") (catch Exception _ t)))))

(defn parse-fingerprint "The first secret key's fingerprint from `gpg --list-secret-keys --with-colons`." [out]
  (second (re-find #"(?m)^sec[^\n]*\n(?:[^\n]*\n)*?fpr:+([0-9A-F]{40}):" (str out))))

(defn hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
(defn digest-hex [alg ^java.io.File f]
  (hex (.digest (doto (java.security.MessageDigest/getInstance alg) (.update (java.nio.file.Files/readAllBytes (.toPath f)))))))

(defn go-tag [v] (str "go/v" v))
(defn go-decision
  "What to do about the Go tag. remote: the sha the tag has on the remote or nil; parent: that commit's first parent (known only if remote); head: the verified commit."
  [{:keys [remote parent head]}]
  (cond (nil? remote) :create
        (= parent head) :exists
        :else :conflict))

;; ---------------------------------------------------------------------------------------------------- helpers
(defn- check-env! [registry dry?]
  (let [miss (missing-env (needs registry) (System/getenv))]
    (doseq [n (needs registry)] (say "  " (if (some #{n} miss) "MISSING" "set    ") " " n))
    (when (seq miss)
      (rl/die 1 (str "bb publish " registry ": " (str/join ", " miss) " is not set. Create it as a secret of the `release` environment (docs/releasing.md)"
                     (when dry? " -- the dry run fails on it so you find out before a release does"))))))

(defn- http-get [url] (try (http/get url {:throw false :headers ua :timeout 30000}) (catch Exception e {:status nil :body (ex-message e)})))

(defn- must-exist [f what] (when-not (fs/exists? f) (rl/die 1 (str "bb publish: " f " does not exist (" what "); run `bb package --host H` first"))))

(defn- run-live! "Run, echoing the tail of the output; die on failure." [opts & cmd]
  (let [r (apply rl/run opts cmd)]
    (when-not (str/blank? (:out r)) (println (str/trim (:out r))))
    (when-not (str/blank? (:err r)) (println (str/trim (:err r))))
    r))

;; ---------------------------------------------------------------------------------------------------- crates.io
(defn crates [dry?]
  (let [v (version) stage "dist/stage/rust"]
    (check-env! "crates" dry?)
    (must-exist (str stage "/Cargo.toml") "the staged crate")
    (let [st (:status (http-get (crates-url v)))]
      (cond
        (= 200 st) (do (say "emon " v " is already on crates.io: skipped") (outcome! "already-published"))
        (and st (not= 404 st)) (rl/die 1 "crates.io answered HTTP" st "when asked about emon" v)
        dry? (let [r (run-live! {} "cargo" "publish" "--locked" "--dry-run" "--manifest-path" (str stage "/Cargo.toml"))]
               (when-not (zero? (:exit r)) (rl/die 1 "cargo publish --dry-run failed"))
               (outcome! "dry-run"))
        :else (let [r (run-live! {} "cargo" "publish" "--locked" "--manifest-path" (str stage "/Cargo.toml"))]
                (cond (zero? (:exit r)) (do (say "published emon " v " to crates.io") (outcome! "published"))
                      (re-find #"already (exists|uploaded)" (str (:err r) (:out r))) (do (say "crates.io says it has it: skipped") (outcome! "already-published"))
                      :else (rl/die 1 "cargo publish failed")))))))

;; ---------------------------------------------------------------------------------------------------- npm
;; TWO PACKAGES (D1542): @3sln/emon and its optional peer @3sln/pdfium-wasm (pdfium's WebAssembly build), both at version.edn's version, each built by
;; `bb package --host js` into dist/js/. The peer goes first, so that @3sln/emon never names a version that is not there; each is skipped when its version
;; is already on npm, so a rerun publishes what is missing.
(defn npm-packages "[[package-name tarball] ...] for version v, in publishing order." [v]
  [["@3sln/pdfium-wasm" (str "dist/js/3sln-pdfium-wasm-" v ".tgz")] ["@3sln/emon" (str "dist/js/3sln-emon-" v ".tgz")]])

(defn npm-outcome "One outcome for the packages' (published / already-published / dry-run): published if any was." [outcomes]
  (cond (some #{"published"} outcomes) "published"
        (some #{"dry-run"} outcomes) "dry-run"
        :else "already-published"))

(defn- npm-one "Publish one package's tarball unless npm has its version; returns the outcome string." [pkg tgz v dry?]
  (let [view (rl/run "npm" "view" (str pkg "@" v) "version" "--registry" "https://registry.npmjs.org")]
    (if (npm-has? (:out view) v)
      (do (say pkg "@" v " is already on npm: skipped") "already-published")
      (let [rc (str (fs/path (fs/create-temp-dir {:prefix "emon-npm-"}) "npmrc"))
            _ (spit rc "//registry.npmjs.org/:_authToken=${NPM_TOKEN}\nregistry=https://registry.npmjs.org/\n")
            prov? (and (= "true" (System/getenv "GITHUB_ACTIONS")) (not (str/blank? (System/getenv "ACTIONS_ID_TOKEN_REQUEST_URL"))))
            args (cond-> (npm-args tgz v prov? rc) dry? (conj "--dry-run"))]
        (say "npm " (str/join " " (remove #{rc} args)) (when-not prov? "   (no OIDC token: published WITHOUT provenance)"))
        (let [r (apply run-live! {} "npm" args)]
          (cond (zero? (:exit r)) (do (say (if dry? "npm dry run ok" (str "published " pkg "@" v))) (if dry? "dry-run" "published"))
                (re-find #"cannot publish over|previously published" (str (:err r) (:out r))) (do (say "npm says it has it: skipped") "already-published")
                :else (rl/die 1 (str "npm publish of " pkg " failed"))))))))

(defn npm [dry?]
  (let [v (version) pkgs (npm-packages v)]
    (check-env! "npm" dry?)
    (doseq [[_ tgz] pkgs] (must-exist tgz "the npm tarball"))
    (outcome! (npm-outcome (doall (for [[pkg tgz] pkgs] (npm-one pkg tgz v dry?)))))))

;; ---------------------------------------------------------------------------------------------------- NuGet
(defn nuget [dry?]
  (let [v (version) pkgs [(str "dist/csharp/Emon." v ".nupkg") (str "dist/csharp/Emon." v ".snupkg")]]
    (check-env! "nuget" dry?)
    (doseq [p pkgs] (must-exist p "the NuGet package"))
    (let [r (http-get (nuget-index-url))
          st (:status r)]
      (cond
        (and (= 200 st) (nuget-has? (:body r) v)) (do (say "Emon " v " is already on NuGet: skipped") (outcome! "already-published"))
        (and st (not (#{200 404} st))) (rl/die 1 "NuGet answered HTTP" st "when asked about Emon")
        dry? (do (say "[dry run] would: dotnet nuget push " (str/join " and " pkgs) " --skip-duplicate") (outcome! "dry-run"))
        :else (do (doseq [p pkgs]
                    (let [r (run-live! {:extra-env {"DOTNET_CLI_TELEMETRY_OPTOUT" "1" "DOTNET_NOLOGO" "1"}}
                                       "dotnet" "nuget" "push" p "--api-key" (System/getenv "NUGET_API_KEY") "--source" "https://api.nuget.org/v3/index.json" "--skip-duplicate")]
                      (when-not (zero? (:exit r)) (rl/die 1 "dotnet nuget push failed for" p))))
                  (say "pushed Emon " v " to NuGet (it appears in the index in a few minutes)")
                  (outcome! "published"))))))

;; ---------------------------------------------------------------------------------------------------- Maven Central
(defn- sign-and-bundle!
  "Import the key into a private GNUPGHOME, sign the four files, write the checksums of the signatures and zip a new bundle. Returns the bundle path."
  [v]
  (let [mroot "dist/java/maven" dir (str mroot "/com/3sln/emon/" v) art (str "emon-" v)
        files (map #(str dir "/" art %) [".jar" ".pom" "-sources.jar" "-javadoc.jar"])
        home (str (fs/create-temp-dir {:prefix "emon-gnupg-"}))
        env {"GNUPGHOME" home}]
    (run! #(must-exist % "a Maven artifact") files)
    (fs/set-posix-file-permissions home "rwx------")
    (let [imp (rl/run {:in (gpg-key-text (System/getenv "MAVEN_GPG_PRIVATE_KEY")) :extra-env env} "gpg" "--batch" "--import")]
      (when-not (zero? (:exit imp)) (rl/die 1 "gpg could not import MAVEN_GPG_PRIVATE_KEY:" (str/trim (:err imp)))))
    (let [fpr (parse-fingerprint (:out (rl/run! {:extra-env env} "gpg" "--batch" "--list-secret-keys" "--with-colons")))]
      (when-not fpr (rl/die 1 "gpg: no secret key in MAVEN_GPG_PRIVATE_KEY"))
      (say "signing with key " (subs fpr 24))
      (doseq [f files]
        (fs/delete-if-exists (str f ".asc"))
        (let [pass (System/getenv "MAVEN_GPG_PASSPHRASE")
              r (apply rl/run {:in (if (str/blank? pass) "" (str pass "\n")) :extra-env env}
                       (gpg-sign-args pass fpr f))]
          (when-not (zero? (:exit r)) (rl/die 1 "gpg could not sign" f ":" (str/trim (:err r))))
          (doseq [[alg ext] [["MD5" ".md5"] ["SHA-1" ".sha1"] ["SHA-256" ".sha256"]]]
            (spit (str f ".asc" ext) (digest-hex alg (io/file (str f ".asc"))))))))
    (fs/delete-tree home)
    (let [bundle (str (fs/absolutize (str "dist/java/" art "-bundle-signed.zip")))]
      (fs/delete-if-exists bundle)
      (rl/run! {:dir mroot} "zip" "-X" "-D" "-q" "-r" bundle "com")
      bundle)))

(defn- central-upload! [bundle v]
  (let [auth (central-auth (System/getenv "MAVEN_CENTRAL_USERNAME") (System/getenv "MAVEN_CENTRAL_PASSWORD"))
        r (http/post (str central "/upload?name=" (java.net.URLEncoder/encode (str "emon-" v) "UTF-8") "&publishingType=AUTOMATIC")
                     {:throw false :headers (assoc ua "Authorization" auth)
                      :multipart [{:name "bundle" :content (io/file bundle) :file-name (str "emon-" v "-bundle.zip")}]})]
    (when-not (= 201 (:status r)) (rl/die 1 (str "the Central Portal refused the upload: HTTP " (:status r) " " (str/trim (str (:body r))))))
    [auth (str/trim (:body r))]))

(defn- central-wait! [auth id]
  (loop [n 0]
    (let [r (http/post (str central "/status?id=" id) {:throw false :headers (assoc ua "Authorization" auth)})
          {:keys [state portal errors]} (central-state (str (:body r)))]
      (say "  deployment " id ": " (or portal (str "HTTP " (:status r))))
      (case state
        :published :ok
        :failed (rl/die 1 (str "the Central Portal rejected deployment " id ": " (json/generate-string errors)))
        (if (< n 160) (do (Thread/sleep 15000) (recur (inc n)))
            (rl/die 1 (str "the Central Portal is still validating deployment " id " after 40 minutes; check https://central.sonatype.com/publishing/deployments, then re-run (it will not upload twice if the version appears)")))))))

(defn maven [dry?]
  (let [v (version)]
    (check-env! "maven" dry?)
    (must-exist (str "dist/java/emon-" v "-bundle.zip") "the Maven bundle")
    (let [st (:status (http-get (maven-pom-url v)))]
      (cond
        (= 200 st) (do (say "com.3sln:emon:" v " is already on Maven Central: skipped") (outcome! "already-published"))
        (and st (not= 404 st)) (rl/die 1 "Maven Central answered HTTP" st)
        dry? (do (say "[dry run] would sign the four files, zip dist/java/emon-" v "-bundle-signed.zip and upload it to " central "/upload (AUTOMATIC)") (outcome! "dry-run"))
        :else (let [bundle (sign-and-bundle! v)
                    [auth id] (central-upload! bundle v)]
                (say "uploaded; deployment id " id)
                (central-wait! auth id)
                (say "published com.3sln:emon:" v " (visible on repo1.maven.org within about 30 minutes)")
                (outcome! "published"))))))

;; ---------------------------------------------------------------------------------------------------- Go
(defn go [dry?]
  (let [v (version) tag (go-tag v) tree "dist/stage/go/tree/go" head (rl/head-sha)]
    (must-exist (str tree "/go.mod") "the assembled Go module")
    (when (fs/exists? "go") (rl/die 1 "bb publish go: ./go exists in the checkout; the module tree is committed as go/ and must not collide"))
    (let [ls (str/trim (:out (rl/git! "ls-remote" "origin" (str "refs/tags/" tag) (str "refs/tags/" tag "^{}"))))
          remote (when-not (str/blank? ls)
                   (let [rows (map #(str/split % #"\s+") (str/split-lines ls))]
                     (first (or (seq (for [[sha ref] rows :when (str/ends-with? ref "^{}")] sha)) (map first rows)))))
          parent (when remote
                   (rl/git! "fetch" "--no-tags" "origin" (str "+refs/tags/" tag ":refs/tags/" tag))
                   (let [r (rl/git "rev-parse" (str "refs/tags/" tag "^{commit}^"))] (when (zero? (:exit r)) (str/trim (:out r)))))]
      (say "the Go module is github.com/3sln/emon/go, tag " tag)
      (case (go-decision {:remote remote :parent parent :head head})
        :exists (do (say tag " already exists on the remote, as a tag-only commit on " head ": skipped") (outcome! "already-published"))
        :conflict (rl/die 1 (str tag " exists on the remote (" remote ") but is not a tag-only commit on " head ". A Go version is permanent once the proxy has it; release the next version."))
        :create (if dry?
                  (do (say "[dry run] would: copy " tree " to ./go, commit it on top of " head ", tag " tag " and push the tag only") (outcome! "dry-run"))
                  (do (fs/copy-tree tree "go")
                      (rl/git! "add" "-f" "go")
                      (rl/git! "-c" "user.name=github-actions[bot]" "-c" "user.email=41898282+github-actions[bot]@users.noreply.github.com"
                               "commit" "-q" "-m" (str "go module " v " (tag-only commit)\n\nThe assembled tree of `bb package --host go`, committed as go/ so that\n"
                                                      "github.com/3sln/emon/go@v" v " resolves (tag " tag "). Not on any branch."))
                      (rl/git! "tag" tag)
                      (rl/git! "push" "origin" (str "refs/tags/" tag))
                      (say "pushed " tag)
                      ;; tell the proxy now (best effort): it learns on the first request, and the first request should be ours
                      (let [url (str "https://proxy.golang.org/github.com/3sln/emon/go/@v/v" v ".info")]
                        (loop [n 0]
                          (let [st (:status (http-get url))]
                            (cond (= 200 st) (say "proxy.golang.org has " tag)
                                  (< n 3) (do (Thread/sleep 10000) (recur (inc n)))
                                  :else (say "NOTE proxy.golang.org answered HTTP " st " (not yet; it fetches on the first `go get`)")))))
                      (outcome! "published")))))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)]
    (t! "missing-env names the blank and absent ones" (= ["B" "C"] (missing-env ["A" "B" "C"] {"A" "x" "B" "  "})))
    (t! "every registry has a credential list; go needs none" (and (= 5 (count needs)) (empty? (needs "go"))))
    (t! "maven needs three secrets; the passphrase is optional"
        (= #{"MAVEN_CENTRAL_USERNAME" "MAVEN_CENTRAL_PASSWORD" "MAVEN_GPG_PRIVATE_KEY"} (set (needs "maven"))))
    (t! "gpg reads a passphrase only when one is set"
        (and (not (some #{"--passphrase-fd"} (gpg-sign-args "" "F" "a.jar")))
             (not (some #{"--passphrase-fd"} (gpg-sign-args nil "F" "a.jar")))
             (some #{"--passphrase-fd"} (gpg-sign-args "pw" "F" "a.jar"))))
    (t! "the crates url" (= "https://crates.io/api/v1/crates/emon/0.1.0" (crates-url "0.1.0")))
    (t! "the maven pom url" (= "https://repo1.maven.org/maven2/com/3sln/emon/0.1.0/emon-0.1.0.pom" (maven-pom-url "0.1.0")))
    (t! "nuget index: version present" (nuget-has? "{\"versions\":[\"0.0.9\",\"0.1.0\"]}" "0.1.0"))
    (t! "nuget index: version absent" (not (nuget-has? "{\"versions\":[\"0.0.9\"]}" "0.1.0")))
    (t! "nuget index: no body (never published)" (not (nuget-has? nil "0.1.0")))
    (t! "nuget index compares lower-case" (nuget-has? "{\"versions\":[\"0.1.0-RC.1\"]}" "0.1.0-rc.1"))
    (t! "npm view: the version is there" (npm-has? "0.1.0\n" "0.1.0"))
    (t! "npm view: empty means absent" (not (npm-has? "" "0.1.0")))
    (t! "npm view: another version is not this one" (not (npm-has? "0.1.1" "0.1.0")))
    (t! "npm args: public access, a private userconfig, no provenance outside Actions"
        (= ["publish" "a.tgz" "--access" "public" "--userconfig" "rc"] (npm-args "a.tgz" "0.1.0" false "rc")))
    (t! "npm packages: pdfium-wasm first, both tarballs named by version" (= [["@3sln/pdfium-wasm" "dist/js/3sln-pdfium-wasm-0.1.0.tgz"] ["@3sln/emon" "dist/js/3sln-emon-0.1.0.tgz"]] (npm-packages "0.1.0")))
    (t! "npm outcome: published when any package was, dry-run, else already-published"
        (and (= "published" (npm-outcome ["already-published" "published"])) (= "dry-run" (npm-outcome ["dry-run" "already-published"]))
             (= "already-published" (npm-outcome ["already-published" "already-published"]))))
    (t! "npm args: provenance when asked"(some #{"--provenance"} (npm-args "a.tgz" "0.1.0" true "rc")))
    (t! "npm args: a prerelease goes to the next tag" (= ["--tag" "next"] (take-last 2 (npm-args "a.tgz" "0.2.0-rc.1" false "rc"))))
    (t! "central auth is bearer base64(user:pass)" (= "Bearer dTpw" (central-auth "u" "p")))
    (t! "central state: PUBLISHED" (= :published (:state (central-state "{\"deploymentState\":\"PUBLISHED\"}"))))
    (t! "central state: FAILED carries the errors" (let [s (central-state "{\"deploymentState\":\"FAILED\",\"errors\":{\"x\":[\"bad\"]}}")] (and (= :failed (:state s)) (:errors s))))
    (t! "central state: VALIDATING waits" (= :wait (:state (central-state "{\"deploymentState\":\"VALIDATING\"}"))))
    (t! "central state: garbage waits (never a false success)" (= :wait (:state (central-state "<html>"))))
    (t! "gpg key text: armored passes through" (str/starts-with? (gpg-key-text "-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc\n-----END PGP PRIVATE KEY BLOCK-----") "-----BEGIN"))
    (t! "gpg key text: base64 is decoded"
        (str/starts-with? (gpg-key-text (.encodeToString (java.util.Base64/getEncoder) (.getBytes "-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc" "UTF-8"))) "-----BEGIN"))
    (t! "fingerprint: the first secret key's"
        (= (apply str (repeat 40 "A"))
           (parse-fingerprint (str "sec:u:255:22:1111111111111111:1:::u:::scESC:::+:::23::0:\nfpr:::::::::" (apply str (repeat 40 "A")) ":\ngrp:::::::::GRIP:\nssb:u:255:18:2222222222222222:1:::::e:::+:::23:\nfpr:::::::::" (apply str (repeat 40 "B")) ":\n"))))
    (t! "digest-hex of a known file"
        (let [f (fs/create-temp-file)]
          (spit (str f) "abc")
          (let [ok (and (= "900150983cd24fb0d6963f7d28e17f72" (digest-hex "MD5" (io/file (str f))))
                        (= "a9993e364706816aba3e25717850c26c9cd0d89d" (digest-hex "SHA-1" (io/file (str f)))))]
            (fs/delete f) ok)))
    (t! "go tag" (= "go/v0.1.0" (go-tag "0.1.0")))
    (t! "go: no remote tag -> create" (= :create (go-decision {:head "h"})))
    (t! "go: remote tag on a child of HEAD -> exists" (= :exists (go-decision {:remote "r" :parent "h" :head "h"})))
    (t! "go: remote tag elsewhere -> conflict" (= :conflict (go-decision {:remote "r" :parent "x" :head "h"})))
    (t! "go: remote tag whose parent is unknown -> conflict" (= :conflict (go-decision {:remote "r" :parent nil :head "h"})))
    (t! "prerelease?" (and (prerelease? "1.0.0-rc.1") (not (prerelease? "1.0.0"))))
    (done!)))

;; ---------------------------------------------------------------------------------------------------- main
(defn -main [args]
  (let [dry? (boolean (some #{"--dry-run"} args))
        [registry & more] (remove #{"--dry-run"} args)]
    (when (seq more) (rl/die 2 "usage: bb publish crates|npm|nuget|maven|go [--dry-run] | --self-test"))
    (say "bb publish " registry (when dry? " (dry run)") " " (version))
    (case registry
      "crates" (crates dry?) "npm" (npm dry?) "nuget" (nuget dry?) "maven" (maven dry?) "go" (go dry?)
      (rl/die 2 "usage: bb publish crates|npm|nuget|maven|go [--dry-run] | --self-test"))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
