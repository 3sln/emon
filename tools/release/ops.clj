;; D886: prepare validates the selected main commit/version; release reuses or creates a published release after verification.
;; All public mutations support --dry-run; archive/verdict/fetch-archives/summary support verification and reporting.
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[release.lib :as rl])

(def triples-cli ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu" "x86_64-pc-windows-msvc"])
(def triples-lib ["aarch64-apple-darwin" "x86_64-apple-darwin" "x86_64-unknown-linux-gnu" "aarch64-unknown-linux-gnu"])
;; K5 (D905): the C bundle (core libemon, pdfium, the adapters, the packs it carries) is built for the five desktop triples (Windows too: the Dart hook fetches it) and the five mobile ones
(def triples-mobile ["aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android" "aarch64-apple-ios" "aarch64-apple-ios-sim"])
(def triples-c (vec (concat triples-cli triples-mobile)))
(def registries ["crates" "npm" "nuget" "maven" "go" "pub"])
(def sums-file "SHA256SUMS")
(def artifact-name "release-archives")

;; ---------------------------------------------------------------------------------------------------- pure parts
(defn plan-version
  "The version a release will have. {:current \"0.1.0\" :bump \"patch\" | :version \"0.2.0\" :tag-exists? fn}. Returns {:version v} or {:error text}."
  [{:keys [current bump version tag-exists?]}]
  (let [bump-of (fn [c part] (let [[a b p] (rl/parse-core c)]
                               (case part "major" (str (inc a) ".0.0") "minor" (str a "." (inc b) ".0") "patch" (str a "." b "." (inc p)) nil)))
        v (cond (and bump version) nil
                bump (bump-of current bump)
                version (rl/tag->version version))]
    (cond
      (and bump version) {:error "give --bump or --version, not both"}
      (and (not bump) (not version)) {:error "give --bump major|minor|patch or --version X.Y.Z"}
      (nil? v) {:error (str "not a bump (major|minor|patch) or a version (X.Y.Z): " (or bump version))}
      (not (rl/newer? v current)) {:error (str "version " v " is not newer than the current " current)}
      (and tag-exists? (tag-exists? (str "v" v))) {:error (str "tag v" v " already exists")}
      :else {:version v})))

(defn release-decision [{:keys [release tag-sha sha]}]
  (cond
    (and tag-sha (not= tag-sha sha)) {:action :refuse :why (str "the tag exists at " tag-sha ", not at " sha "; a tag is never moved")}
    (and release (not= (:target_commitish release) sha)) {:action :refuse :why "the existing release targets a different commit"}
    release {:action :reuse}
    :else {:action :create}))

(defn prepare-decision [{:keys [current version only event tag-sha sha]}]
  (let [selected (if (str/blank? only) #{} (set (str/split only #"," -1)))
        known (set (cons "assets" registries))]
    (cond
      (and (not (str/blank? version)) (not= version current)) {:error "input version must equal version.edn at the selected ref"}
      (and (not (empty? (str only))) (str/blank? only)) {:error "only must not contain whitespace"}
      (not-every? known selected) {:error "only must be a comma list of assets,crates,npm,nuget,maven,go,pub"}
      (and tag-sha (not= tag-sha sha)) {:error (str "tag v" current " exists at another commit; never retarget it")}
      :else {:run (not (and (= event "push") tag-sha)) :version current})))

(defn dart-asset "The stamped, publishable Dart package tree of a release (K5): made at release time from SHA256SUMS, not by verify.yml." [v] (str "emon-dart-" v ".tar.gz"))

(defn built-assets
  "Every file verify.yml builds and collects into `release-archives`, besides SHA256SUMS, by name: the CLI and the PP-OCR add-on (5 desktop triples), the C bundle (5 desktop and 5 mobile
   triples; .tar.gz everywhere, Windows included: the Dart hook reads only that) and the pod (4)."
  [v]
  (vec (concat (for [t triples-cli] (str "emon-ppocr-" v "-" t (if (str/includes? t "windows") ".zip" ".tar.gz")))
               (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext))
               (for [t triples-cli ext (if (str/includes? t "windows") ["zip"] ["tar.gz"])] (str "emon-" v "-" t "." ext ".sha256"))
               (for [t triples-c] (str "emon-c-" v "-" t ".tar.gz"))
               (for [t triples-lib] (str "emon-pod-" v "-" t ".tar.gz")))))

(defn expected-assets
  "Every file the release must carry besides SHA256SUMS, by name: what verify.yml built, and the Dart package tree `release-ops dart` adds."
  [v]
  (conj (built-assets v) (dart-asset v)))

;; ---------------------------------------------------------------------------------------------------- what a C bundle holds (K5)
(def bundle-ppocr?
  "Does the desktop C bundle carry the PP-OCR adapter (`lib/emon/adapters/libemon_ocr_ppocr.*`) and its runtime? Not yet: PP-OCR's ONNX Runtime pin is being replaced (`ortpin`), and until it lands the add-on
   archives (`emon-ppocr-*`) are the only PP-OCR files. Flip this when tools/package.clj puts the adapter in the bundle: the collector then refuses a desktop bundle without it, and the Dart hook
   of emon_ocr_ppocr (already `optional`) finds it."
  false)

(defn bundle-entries
  "The entries a C bundle of this triple must hold, relative to its top directory `emon-c-<version>-<triple>/`: the headers, the core library, pdfium, each adapter library (Tesseract everywhere; Apple Vision on
   macOS and iOS; Windows.Media.Ocr on Windows; PP-OCR on a desktop when `bundle-ppocr?`), the licence. Packs are checked apart (`share/emon/packs/`)."
  [triple]
  (let [os (cond (str/includes? triple "windows") :windows (str/includes? triple "apple-darwin") :mac (str/includes? triple "apple-ios") :ios
                 (str/includes? triple "android") :android :else :linux)
        lib (fn [stem] (case os :windows (str stem ".dll") (:mac :ios) (str "lib" stem ".dylib") (str "lib" stem ".so")))
        ad (fn [stem] (str "lib/emon/adapters/" (lib stem)))]
    (vec (concat ["include/emon.h" "include/emon_adapter.h" "LICENSE" (str "lib/" (lib "emon")) (str "lib/" (lib "pdfium")) (ad "emon_ocr_tesseract")]
                 (when (#{:mac :ios} os) [(ad "emon_ocr_vision")])
                 (when (= os :windows) [(ad "emon_ocr_winocr")])
                 (when (and bundle-ppocr? (#{:mac :linux :windows} os)) [(ad "emon_ocr_ppocr")])))))

(defn bundle-problems
  "Problems of one C bundle: `entries` is its `tar -tzf` listing. [] when every entry of `bundle-entries` is there under the right top directory, and at least one pack is."
  [version triple entries]
  (let [top (str "emon-c-" version "-" triple "/")
        names (remove str/blank? entries)
        rel (set (for [n names :when (str/starts-with? n top)] (subs n (count top))))]
    (vec (concat (for [n names :when (not (str/starts-with? n top))] (str "the bundle of " triple " holds " n ", outside " top))
                 (for [e (bundle-entries triple) :when (not (contains? rel e))] (str "the bundle of " triple " has no " e))
                 (when-not (some #(str/starts-with? % "share/emon/packs/") rel) [(str "the bundle of " triple " has no pack under share/emon/packs/")])))))

(defn sums-text
  "The SHA256SUMS file: `<hash>  <name>` for every archive (not the .sha256 sidecars), sorted by name. hashes: name -> hex."
  [hashes]
  (apply str (for [[n h] (sort hashes) :when (not (str/ends-with? n ".sha256"))] (str h "  " n "\n"))))

(defn parse-sums [text]
  (into (sorted-map) (for [l (str/split-lines (str text)) :when (not (str/blank? l)) :let [[h n] (str/split l #"\s+" 2)]] [n h])))

(defn check-archives
  "Problems with a set of archives: files = name -> hex sha256 of what is on disk, sums = the text of its SHA256SUMS (or nil). [] when it is complete and consistent.
   `want` is the file list the set must have: `built-assets` for what verify.yml collected, `expected-assets` (the default) for what a release carries."
  ([version files sums] (check-archives version files sums (expected-assets version)))
  ([version files sums want]
  (let [listed (when sums (parse-sums sums))
        archives (remove #(str/ends-with? % ".sha256") want)]
    (vec (concat
          (for [n want :when (not (contains? files n))] (str "missing " n))
          (cond (nil? sums) ["missing SHA256SUMS"]
                :else (concat (for [n archives :when (not (contains? listed n))] (str "SHA256SUMS does not list " n))
                              (for [[n h] listed :when (and (contains? files n) (not= h (get files n)))] (str n " does not match SHA256SUMS"))
                              (for [[n _] listed :when (not (some #{n} archives))] (str "SHA256SUMS lists " n ", which is no archive of " version)))))))))

(defn verdict-state
  "success only when `plan` succeeded and every job ended success or skipped. rows: parse-results output."
  [rows]
  (let [m (into {} (map (fn [[k r _]] [k r]) rows))]
    (if (and (= "success" (get m "plan")) (every? #{"success" "skipped"} (vals m))) "success" "failure")))

(defn verdict-description
  "<= 140 characters. The planned description when verified, else which jobs ended badly."
  [state planned rows]
  (if (= "success" state)
    planned
    (let [bad (for [[k r _] rows :when (not (#{"success" "skipped"} r))] k)
          s (str "NOT verified: " (if (seq bad) (str/join "," bad) "plan did not succeed") " (" planned ")")]
      (subs s 0 (min 140 (count s))))))

(defn archives-status
  "The `emon/archives` status of a verified run: built here (this run's id) or inherited (the base's description and run, verbatim)."
  [{:keys [build? version sha run-url repo archives-run archives-desc]}]
  (if build?
    {:description (rl/archives-description version (second (re-find #"/runs/(\d+)" (str run-url))) sha) :target-url run-url}
    {:description archives-desc :target-url (str "https://github.com/" repo "/actions/runs/" archives-run)}))

(defn parse-results
  "\"a=success,b=failure:why\" -> [[\"a\" \"success\" nil] [\"b\" \"failure\" \"why\"]], order kept."
  [s]
  (vec (for [kv (str/split (str s) #",") :when (not (str/blank? kv))
             :let [[k v] (str/split kv #"=" 2) [r d] (str/split (str v) #":" 2)]]
         [k r d])))

(defn summary-md
  "The publish job's report. rows: parse-results output. Returns {:markdown :ok?}."
  [tag rows & [dry?]]
  (let [v (rl/tag->version tag)
        ok? (every? (fn [[_ r _]] (#{"success" "skipped"} r)) rows)
        failed (for [[k r _] rows :when (not (#{"success" "skipped"} r))] k)
        sb (StringBuilder.)
        line (fn [& xs] (.append sb (apply str xs)) (.append sb "\n"))]
    (line "## Publish " tag (when dry? " (DRY RUN: nothing was published)"))
    (line)
    (line "| Registry | Result | Detail |")
    (line "|---|---|---|")
    (doseq [[k r d] rows]
      (line "| " k " | " (case r "success" (if dry? "ok (dry run)" "done") "skipped" "skipped" "FAILED") (when-not (#{"success" "skipped"} r) (str " (" r ")")) " | " (or d "") " |"))
    (line)
    (if ok?
      (line (if dry? "Every check passed; a real release would publish " "Selected release steps completed for ") v ". A rerun is safe: a registry that has the version is skipped.")
      (do (line "**Release incomplete.** Failed: " (str/join ", " failed) ".")
          (let [completed (for [[k r _] rows :when (and (some #{k} registries) (= r "success"))] k)]
            (line (if (seq completed) (str (if dry? "Dry-run registry checks completed: " "Partial publish: registry jobs completed: ") (str/join ", " completed) ".")
                      "No registry job completed successfully; inspect the logs before retrying.")))
          (line)
          (line "1. Read the failed job's log; fix the cause (a credential, a registry outage). A registry never accepts the same version twice, so do not change the tag.")
          (line "2. Re-run the failed jobs (Actions > this run > Re-run failed jobs). Each step skips a version its registry already has.")
          (line "3. If the cause is in the code, the tag cannot move: release the next patch version instead (docs/releasing.md, \"Recovering from a partial publish\").")))
    {:markdown (str sb) :ok? ok?}))

;; ---------------------------------------------------------------------------------------------------- effects
(defn- say [& xs] (println (apply str xs)))
(defn- do-or-say [dry? what f] (if dry? (say "  [dry run] would: " what) (f)))
(defn- cur-version [] (:version (edn/read-string (slurp "version.edn"))))

(defn- sha256-hex [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 1048576)]
    (with-open [in (java.io.FileInputStream. (str f))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- remote-tag-sha [tag]
  (let [r (rl/git! "ls-remote" "origin" (str "refs/tags/" tag) (str "refs/tags/" tag "^{}"))
        rows (for [l (str/split-lines (:out r)) :when (not (str/blank? l))] (str/split l #"\s+"))
        peeled (first (for [[sha ref] rows :when (str/ends-with? ref "^{}")] sha))
        plain (first (for [[sha ref] rows :when (not (str/ends-with? ref "^{}"))] sha))]
    (or peeled plain)))

(defn- find-release
  "The release (draft or published) with this tag name, or nil. The by-tag route of the API does not show drafts, so the list is read."
  [tag]
  (let [r (rl/run "gh" "api" "--paginate" (str "repos/" (rl/repo) "/releases?per_page=100")
                  "--jq" (str ".[] | select(.tag_name == " (json/generate-string tag) ") | {id, draft, prerelease, target_commitish, tag_name, html_url}"))]
    (when-not (zero? (:exit r)) (rl/die 1 "release-ops: cannot list the releases:" (str/trim (str (:err r) (:out r)))))
    (first (for [l (str/split-lines (:out r)) :when (not (str/blank? l))] (json/parse-string l true)))))

(defn next! [{:keys [bump version]}]
  (let [current (cur-version)
        p (plan-version {:current current :bump bump :version version
                         :tag-exists? #(or (rl/peel-sha %) (remote-tag-sha %) (find-release %))})]
    (when (:error p) (rl/die 1 "release-ops next:" (:error p)))
    (say "release: " current " -> " (:version p))
    (rl/write-output! "version" (:version p))
    (rl/write-output! "tag" (str "v" (:version p)))))

(defn- files-of [dirs]
  (into (sorted-map)
        (for [d dirs, f (fs/glob d "**" {:follow-links false}) :when (fs/regular-file? f)]
          [(str (fs/file-name f)) (str f)])))

(defn- bundle-problems-in
  "The problems of every C bundle (`emon-c-*`) among `files` (name -> path): each is listed with `tar -tzf` and held to `bundle-problems`."
  [version files]
  (vec (mapcat (fn [t]
                 (let [n (str "emon-c-" version "-" t ".tar.gz")]
                   (when-let [f (get files n)]
                     (let [r (rl/run "tar" "-tzf" f)]
                       (if (zero? (:exit r)) (bundle-problems version t (str/split-lines (:out r))) [(str "cannot list " n ": " (str/trim (:err r)))])))))
               triples-c)))

(defn archive! [{:keys [dirs out version]}]
  (let [v (or version (cur-version))
        found (files-of dirs)
        want (built-assets v)
        missing (remove found want)]
    (say "archive: " (count (filter (set want) (keys found))) " of " (count want) " files found under " (str/join ", " dirs))
    (when (seq missing) (rl/die 1 (str "release-ops archive: missing " (count missing) " file(s):\n  " (str/join "\n  " missing))))
    (let [bad (bundle-problems-in v found)]
      (when (seq bad) (rl/die 1 (str "release-ops archive: " (count bad) " problem(s) in the C bundles:\n  " (str/join "\n  " bad)))))
    (fs/create-dirs out)
    (doseq [n want] (fs/copy (get found n) (str out "/" n) {:replace-existing true}))
    (let [hashes (into {} (for [n want] [n (sha256-hex (str out "/" n))]))]
      (spit (str out "/" sums-file) (sums-text hashes))
      (say "wrote " (inc (count want)) " files to " out " (with " sums-file ")"))))

(defn- check-dir
  "Problems of a directory of release files against `want` (default: all of `expected-assets`)."
  ([dir version] (check-dir dir version (expected-assets version)))
  ([dir version want]
   (let [fs* (files-of [dir])
         hashes (into {} (for [n want :when (contains? fs* n)] [n (sha256-hex (get fs* n))]))
         sums (when (contains? fs* sums-file) (slurp (get fs* sums-file)))]
     (check-archives version hashes sums want))))

;; K5 (D905): the Dart package tree of the release. Made here, from the verified set, and not by verify.yml: its hook table is the SHA-256 of the C bundles, which is SHA256SUMS.
(defn dart! [{:keys [dir version]}]
  (let [v (or version (cur-version))
        dir (str dir)
        sums-path (str dir "/" sums-file)
        asset (dart-asset v)
        top (str "emon-dart-" v)]
    ;; a rerun over a directory that already has the tree: its own line is not part of what is checked
    (when (fs/exists? sums-path)
      (spit sums-path (apply str (for [l (str/split-lines (slurp sums-path)) :when (not (str/ends-with? l (str "  " asset)))] (str l "\n")))))
    (let [problems (check-dir dir v (built-assets v))]
      (when (seq problems) (rl/die 1 (str "release-ops dart: " dir " is not the verified set:\n  " (str/join "\n  " problems)))))
    (let [tmp (str (fs/create-temp-dir {:prefix "emon-dart-"}))]
      (try
        (rl/run! "bb" "tools/dart.clj" "--stamp" (str tmp "/" top) "--publishable" "--sums" sums-path)
        (rl/run! "tar" "-czf" (str dir "/" asset) "-C" tmp top)
        (finally (fs/delete-tree tmp))))
    (let [hashes (into {} (for [n (expected-assets v)] [n (sha256-hex (str dir "/" n))]))]
      (spit sums-path (sums-text hashes))
      (say "wrote " asset " and rewrote " sums-file " (" (count (parse-sums (slurp sums-path))) " archives)"))))

(defn prepare! [{:keys [version only event]}]
  (let [sha (str/trim (:out (rl/git! "rev-parse" "HEAD")))
        _ (when-not (zero? (:exit (rl/git "merge-base" "--is-ancestor" sha "origin/main")))
            (rl/die 1 "release: selected commit is not on main"))
        current (cur-version)
        decision (prepare-decision {:current current :version version :only only :event event
                                    :tag-sha (remote-tag-sha (str "v" current)) :sha sha})]
    (when (:error decision) (rl/die 1 (:error decision)))
    (rl/run! "bb" "tools/version.clj" "--check" (str "v" current))
    (doseq [[k v] {"sha" sha "version" current "run" (str (boolean (:run decision)))}] (rl/write-output! k v))))

(defn release! [{:keys [tag sha notes dir only dry-run?]}]
  (let [v (rl/tag->version tag)
        _ (when-not (and (= v (cur-version)) (re-matches #"[0-9a-f]{40}" (str sha))
                         (= sha (str/trim (:out (rl/git! "rev-parse" "HEAD")))))
            (rl/die 1 "release: version or checkout does not equal the requested release"))
        statuses (rl/statuses-of (rl/repo) sha)
        _ (when (or (rl/error? statuses) (not (rl/verified-ok? (rl/newest rl/status-context statuses))))
            (rl/die 1 "release: commit has no successful emon/verified status"))
        problems (check-dir dir v)
        _ (when (seq problems) (rl/die 1 (str/join "\n" problems)))
        rel (find-release tag)
        tag-sha (remote-tag-sha tag)
        d (release-decision {:release rel :tag-sha tag-sha :sha sha})
        assets? (or (str/blank? only) (some #{"assets"} (str/split only #",")))]
    (when (= :refuse (:action d)) (rl/die 1 (:why d)))
    (when (and (not assets?) (or (nil? rel) (nil? tag-sha) (:draft rel)))
      (rl/die 1 "release: selected registries need an existing published release; retry with assets included"))
    (when assets?
      (do-or-say dry-run? (str "create/reuse tag " tag " at " sha " and publish release with archives")
        #(do
           (when-not tag-sha
             (rl/run! "gh" "api" "-X" "POST" (str "repos/" (rl/repo) "/git/refs")
                      "-f" (str "ref=refs/tags/" tag) "-f" (str "sha=" sha)))
           (if rel
             (do
               (apply rl/run! "gh" "release" "upload" tag "--repo" (rl/repo) "--clobber"
                      (for [n (conj (expected-assets v) sums-file)] (str dir "/" n)))
               (when (:draft rel)
                 (rl/run! "gh" "api" "-X" "PATCH" (str "repos/" (rl/repo) "/releases/" (:id rel)) "-F" "draft=false")))
             (apply rl/run! "gh" "release" "create" tag "--repo" (rl/repo) "--verify-tag" "--target" sha
                    "--title" (str "emon " tag) "--notes-file" notes
                    (concat (when (str/includes? v "-") ["--prerelease"])
                            (for [n (conj (expected-assets v) sums-file)] (str dir "/" n))))))))
    (rl/write-output! "outcome" (if dry-run? "dry-run" "release and assets ready"))))

;; K5 (D885, D905): pub.dev accepts automated publishing only from a workflow run that a TAG PUSH started, and a push made with GITHUB_TOKEN starts none. So once the GitHub release has its
;; assets (the Dart tree among them), the release run pushes the tag `emon-dart-v<version>` with a repository DEPLOY KEY (the secret PUB_DEPLOY_KEY, which `actions/checkout` installs as the
;; checkout's SSH credential); pub.yml starts from that tag.
(defn pub-tag-name [v] (str "emon-dart-v" v))

(defn pub-tag-decision
  "What to do about the tag: :push (absent), :exists (at this commit already: nothing to do, pub.yml has run or is running), :retrigger (at this commit, and the caller asked for pub
   alone: delete it and push it again, which starts pub.yml again; every package it already published is skipped) or :refuse (at another commit: a tag is never moved)."
  [{:keys [tag-sha sha retrigger?]}]
  (cond
    (and tag-sha (not= tag-sha sha)) {:action :refuse :why (str "the tag exists at " tag-sha ", not at " sha "; a tag is never moved")}
    (and tag-sha retrigger?) {:action :retrigger}
    tag-sha {:action :exists}
    :else {:action :push}))

(defn- release-has-asset?
  "Is the release `tag` published and does it carry the asset `name`?"
  [tag name]
  (let [rel (find-release tag)]
    (when (and rel (not (:draft rel)))
      (let [r (rl/gh-json (str "repos/" (rl/repo) "/releases/" (:id rel)))]
        (and (not (rl/error? r)) (boolean (some #(= name (:name %)) (:assets r))))))))

(defn pub-tag! [{:keys [tag sha only dry-run?]}]
  (let [v (rl/tag->version tag)
        name (pub-tag-name v)
        _ (when-not (and v (= v (cur-version)) (re-matches #"[0-9a-f]{40}" (str sha)) (= sha (str/trim (:out (rl/git! "rev-parse" "HEAD")))))
            (rl/die 1 "release-ops pub-tag: version or checkout does not equal the requested release"))
        retrigger? (boolean (some #{"pub"} (str/split (str only) #",")))
        tag-sha (remote-tag-sha name)
        d (pub-tag-decision {:tag-sha tag-sha :sha sha :retrigger? retrigger?})]
    (when (= :refuse (:action d)) (rl/die 1 (:why d)))
    (when-not dry-run?
      (when-not (release-has-asset? tag (dart-asset v))
        (rl/die 1 (str "release-ops pub-tag: " tag " is not a published release with " (dart-asset v) "; run release.yml with assets selected first"))))
    (case (:action d)
      :exists (do (say name " is already at " sha ": nothing to push (select only=pub to push it again)") (rl/write-output! "outcome" "already-pushed"))
      (do-or-say dry-run? (str (if (= :retrigger (:action d)) "delete and push " "push ") name " at " sha " with the deploy key")
        #(do
           (when (= :retrigger (:action d)) (rl/run! "git" "push" "origin" (str ":refs/tags/" name)))
           (rl/run! "git" "tag" "-f" name sha)
           (rl/run! "git" "push" "origin" (str "refs/tags/" name))
           (say "pushed " name " (pub.yml starts from it)")))
      )
    (when-not (= :exists (:action d)) (rl/write-output! "outcome" (if dry-run? "dry-run" "tag pushed")))))

(defn plan-fields
  "What the verdict needs from the JSON `bb ci verify-plan` printed ({} when the plan job failed): its description, the version, whether this run built the archives and, if not,
   the run and description it inherited."
  [plan-json]
  (let [p (try (json/parse-string (str plan-json) true) (catch Exception _ nil))
        p (if (map? p) p {})]
    {:description (or (not-empty (:description p)) "plan failed")
     :version (or (not-empty (:version p)) "unknown")
     :build-archives (str (boolean (:build_archives p)))
     :archives-run (str (:archives_run p))
     :archives-desc (str (:archives_desc p))}))

(defn verdict! [{:keys [sha results plan run-url dry-run?]}]
  (let [{:keys [description version build-archives archives-run archives-desc]} (plan-fields plan)
        rows (parse-results results)
        state (verdict-state rows)
        build? (= "true" build-archives)
        desc (verdict-description state description rows)
        a (archives-status {:build? build? :version version :sha sha :run-url run-url :repo (rl/repo) :archives-run archives-run :archives-desc archives-desc})
        post (fn [ctx st d url]
               (apply rl/run! "gh" "api" "-X" "POST" (str "repos/" (rl/repo) "/statuses/" sha)
                      "-f" (str "state=" st) "-f" (str "context=" ctx) "-f" (str "description=" d)
                      (when (not (str/blank? url)) ["-f" (str "target_url=" url)])))]
    (say "verdict: " state " on " sha "; results: " results)
    (when (and (= "success" state) (not build?) (str/blank? archives-desc)) (rl/die 1 "release-ops verdict: archives are inherited but --archives-desc is empty"))
    (when (= "success" state)
      (do-or-say dry-run? (str "POST " rl/archives-context " success: " (:description a)) #(do (post rl/archives-context "success" (:description a) (:target-url a)) (say "posted " rl/archives-context))))
    (do-or-say dry-run? (str "POST " rl/status-context " " state ": " desc) #(do (post rl/status-context state desc run-url) (say "posted " rl/status-context)))
    (when (not= "success" state) (System/exit 1))))

(defn fetch-archives! [{:keys [sha version out limit dry-run?]}]
  (let [repo (rl/repo)
        shas (rl/first-parent-shas sha (inc (or (some-> limit parse-long) 60)))
        _ (when (empty? shas) (rl/die 1 "release-ops fetch-archives: cannot walk the history of" sha "(a shallow clone? fetch-depth: 0)"))
        found (rl/find-archives shas #(rl/statuses-of repo %) version (System/currentTimeMillis))]
    (when (:error found) (rl/die 1 "release-ops fetch-archives: cannot read the commit statuses:" (:error found)))
    (when-not (:sha found)
      (rl/die 1 (str "release-ops fetch-archives: no verified commit among the " (count shas) " nearest to " sha " has valid archives for " version
                     " (younger than " rl/archives-max-age-days " days, built for exactly this version). Run verify.yml again for the commit with mode=full (docs/releasing.md).")))
    (say "archives for " version ": run " (:run found) ", recorded on " (:sha found) (when (not= sha (:sha found)) (str " (an ancestor of " sha ")")))
    (let [arts (rl/gh-json (str "repos/" repo "/actions/runs/" (:run found) "/artifacts?per_page=100"))
          art (when-not (rl/error? arts) (first (filter #(= artifact-name (:name %)) (:artifacts arts))))]
      (when (rl/error? arts) (rl/die 1 "release-ops fetch-archives: cannot list the artifacts of run" (:run found) ":" (::rl/error arts)))
      (when-not art (rl/die 1 (str "release-ops fetch-archives: run " (:run found) " has no artifact " artifact-name)))
      (when (:expired art) (rl/die 1 (str "release-ops fetch-archives: the artifact of run " (:run found) " has expired; run verify.yml again for the commit with mode=full")))
      (say "artifact " artifact-name " of run " (:run found) ": " (:size_in_bytes art) " bytes, expires " (:expires_at art))
      (rl/write-output! "run" (:run found))
      (rl/write-output! "from" (:sha found))
      (do-or-say dry-run? (str "download it into " out)
                 #(do (rl/run! "gh" "run" "download" (:run found) "--repo" repo "-n" artifact-name "-D" out)
                      (let [problems (concat (check-dir out version (built-assets version)) (bundle-problems-in version (files-of [out])))]
                        (when (seq problems) (rl/die 1 (str "release-ops fetch-archives: the artifact is not a good set:\n  " (str/join "\n  " problems))))
                        (say "the " (count (built-assets version)) " assets and " sums-file " are complete and consistent")))))))

(defn attach! [{:keys [tag dir dry-run?]}]
  (let [v (rl/tag->version tag)
        problems (check-dir dir v)]
    (when (seq problems) (rl/die 1 (str "release-ops attach: " dir " is not a good set:\n  " (str/join "\n  " problems))))
    (let [names (conj (expected-assets v) sums-file)
          paths (for [n names] (str dir "/" n))]
      (doseq [n names] (say "  " n))
      (do-or-say dry-run? (str "gh release upload " tag " (" (count names) " files) --clobber")
                 #(let [rel (find-release tag)]
                    (when-not rel (rl/die 1 "release-ops attach: no release" tag))
                    (when (:draft rel) (rl/die 1 "release-ops attach:" tag "is not published; run release.yml with assets selected"))
                    (apply rl/run! "gh" "release" "upload" tag "--clobber" "--repo" (rl/repo) paths)
                    (say "attached " (count names) " files to " tag))))))

(defn summary! [{:keys [tag results dry-run?]}]
  (let [{:keys [markdown ok?]} (summary-md tag (parse-results results) dry-run?)]
    (print markdown)
    (when-let [f (not-empty (System/getenv "GITHUB_STEP_SUMMARY"))] (spit f markdown :append true))
    (when-not ok? (System/exit 1))))

;; ---------------------------------------------------------------------------------------------------- self-test
(defn self-test []
  (let [[t! done!] (rl/selftest-runner)
        exists #{"v0.2.0"}
        pv #(plan-version (merge {:current "0.1.0" :tag-exists? exists} %))
        sha "0123456789abcdef0123456789abcdef01234567" other "fedcba9876543210fedcba9876543210fedcba98"]
    (t! "bump patch" (= {:version "0.1.1"} (pv {:bump "patch"})))
    (t! "bump minor" (= {:version "0.3.0"} (plan-version {:current "0.2.0" :bump "minor" :tag-exists? #{}})))
    (t! "bump minor onto an existing tag is refused" (:error (pv {:bump "minor"})))
    (t! "bump major" (= {:version "1.0.0"} (pv {:bump "major"})))
    (t! "an explicit version" (= {:version "0.3.0"} (pv {:version "0.3.0"})))
    (t! "an explicit version with a leading v" (= {:version "0.3.0"} (pv {:version "v0.3.0"})))
    (t! "a version that is not newer is refused" (:error (pv {:version "0.1.0"})))
    (t! "an older version is refused" (:error (pv {:version "0.0.9"})))
    (t! "a tag that exists is refused" (re-find #"already exists" (str (:error (pv {:version "0.2.0"})))))
    (t! "bump and version together are refused" (:error (pv {:bump "patch" :version "0.3.0"})))
    (t! "neither is refused" (:error (pv {})))
    (t! "a nonsense bump is refused" (:error (pv {:bump "huge"})))
    (t! "a nonsense version is refused" (:error (pv {:version "1.2"})))
    (t! "a pre-release of the next version is newer than the current" (= {:version "0.2.0-rc.1"} (pv {:version "0.2.0-rc.1"})))
    (t! "release: absent -> create" (= :create (:action (release-decision {:sha sha}))))
    (t! "release: same tag and release reused" (= :reuse (:action (release-decision {:sha sha :tag-sha sha :release {:target_commitish sha}}))))
    (t! "release: tag at another commit refused" (= :refuse (:action (release-decision {:sha sha :tag-sha other}))))
    (t! "release: different target refused" (= :refuse (:action (release-decision {:sha sha :release {:target_commitish other}}))))
    (let [p #(prepare-decision (merge {:current "1.2.3" :sha sha :event "push"} %))]
      (t! "new version (tag absent) triggers" (:run (p {})))
      (t! "released version push skips" (false? (:run (p {:tag-sha sha}))))
      (t! "manual retry reuses tag" (:run (p {:event "workflow_dispatch" :tag-sha sha})))
      (t! "wrong-commit tag refused even on push" (:error (p {:tag-sha other})))
      (t! "input version mismatch refused" (:error (p {:version "1.2.4"})))
      (t! "whitespace selection refused" (:error (p {:only " "})))
      (t! "invalid registry refused" (:error (p {:only "npm,typo"})))
      (t! "selected registries accepted" (:run (p {:only "npm,assets"}))))
    (let [a (expected-assets "0.1.0") built (built-assets "0.1.0")]
      (t! "built assets: 5 CLI + 5 PP + 5 sums + 10 C + 4 pod = 29; the release adds the Dart tree = 30" (and (= 29 (count built)) (= 30 (count a)) (= (conj built "emon-dart-0.1.0.tar.gz") a)))
      (t! "expected assets are distinct" (= (count a) (count (set a))))
      (t! "windows ships a zip" (some #{"emon-0.1.0-x86_64-pc-windows-msvc.zip"} a))
      (t! "C and pod archives are named as bb package names them" (and (some #{"emon-c-0.1.0-aarch64-apple-darwin.tar.gz"} a) (some #{"emon-pod-0.1.0-x86_64-unknown-linux-gnu.tar.gz"} a)))
      (t! "the C bundle is built for Windows (a .tar.gz: the Dart hook reads only that) and for the five mobile triples, the pod is not"
          (and (some #{"emon-c-0.1.0-x86_64-pc-windows-msvc.tar.gz"} a)
               (every? (set a) (for [t triples-mobile] (str "emon-c-0.1.0-" t ".tar.gz")))
               (not-any? #(str/includes? % "emon-pod-0.1.0-x86_64-pc-windows") a)
               (not-any? #(re-find #"emon-pod-.*(android|ios)" %) a)))
      (t! "the Dart tree is one file of the release, named by version" (= ["emon-dart-0.1.0.tar.gz"] (filter #(str/starts-with? % "emon-dart-") a)))
      (let [hashes (into {} (for [n a] [n (str (hash n))]))
            built-hashes (select-keys hashes built)
            sums (sums-text hashes)]
        (t! "SHA256SUMS lists the 25 archives and not the .sha256 sidecars" (= 25 (count (parse-sums sums))))
        (t! "a complete set matches its sums" (= [] (check-archives "0.1.0" hashes sums)))
        (t! "what verify.yml collects (without the Dart tree) is complete against its own list, and not against the release's"
            (and (= [] (check-archives "0.1.0" built-hashes (sums-text built-hashes) built))
                 (= ["missing emon-dart-0.1.0.tar.gz"] (filterv #(str/starts-with? % "missing") (check-archives "0.1.0" built-hashes (sums-text built-hashes))))))
        (t! "a missing file is named" (= ["missing emon-c-0.1.0-aarch64-apple-darwin.tar.gz"]
                                         (filterv #(str/starts-with? % "missing emon-c") (check-archives "0.1.0" (dissoc hashes "emon-c-0.1.0-aarch64-apple-darwin.tar.gz") sums))))
        (t! "a missing mobile bundle is named" (= ["missing emon-c-0.1.0-aarch64-linux-android.tar.gz"]
                                                  (filterv #(str/starts-with? % "missing emon-c") (check-archives "0.1.0" (dissoc hashes "emon-c-0.1.0-aarch64-linux-android.tar.gz") sums))))
        (t! "a file that differs from its sum is named" (some #(str/includes? % "does not match SHA256SUMS") (check-archives "0.1.0" (assoc hashes "emon-pod-0.1.0-x86_64-unknown-linux-gnu.tar.gz" "0") sums)))
        (t! "no SHA256SUMS is a problem" (some #{"missing SHA256SUMS"} (check-archives "0.1.0" hashes nil)))
        (t! "archives of another version are all missing" (= 30 (count (filter #(str/starts-with? % "missing emon") (check-archives "0.2.0" hashes sums)))))))
    ;; the C bundles: what each triple must hold
    (let [entries (fn [triple v] (let [top (str "emon-c-" v "-" triple "/")]
                                   (concat [top (str top "lib/") (str top "share/emon/packs/tess-fast-eng/eng.traineddata")] (map #(str top %) (bundle-entries triple)))))]
      (t! "a bundle of every triple holding exactly what the table says has no problem" (every? #(= [] (bundle-problems "0.1.0" % (entries % "0.1.0"))) triples-c))
      (t! "Apple triples (macOS and iOS) carry the Vision adapter, Windows carries Windows OCR, Linux and Android neither"
          (let [has (fn [t suffix] (boolean (some #(str/ends-with? % suffix) (bundle-entries t))))]
            (and (has "aarch64-apple-darwin" "libemon_ocr_vision.dylib") (has "x86_64-apple-darwin" "libemon_ocr_vision.dylib") (has "aarch64-apple-ios" "libemon_ocr_vision.dylib") (has "aarch64-apple-ios-sim" "libemon_ocr_vision.dylib")
                 (has "x86_64-pc-windows-msvc" "emon_ocr_winocr.dll") (not (has "x86_64-pc-windows-msvc" "libemon_ocr_vision.dylib"))
                 (not-any? #(has % "libemon_ocr_vision.dylib") ["x86_64-unknown-linux-gnu" "aarch64-linux-android" "armv7-linux-androideabi" "x86_64-linux-android"])
                 (not-any? #(has % "emon_ocr_winocr.dll") (remove #{"x86_64-pc-windows-msvc"} triples-c)))))
      (t! "every triple carries core, pdfium and the Tesseract adapter, named for its OS"
          (and (some #{"lib/emon.dll"} (bundle-entries "x86_64-pc-windows-msvc")) (some #{"lib/pdfium.dll"} (bundle-entries "x86_64-pc-windows-msvc"))
               (some #{"lib/emon/adapters/emon_ocr_tesseract.dll"} (bundle-entries "x86_64-pc-windows-msvc"))
               (some #{"lib/libemon.so"} (bundle-entries "aarch64-linux-android")) (some #{"lib/libpdfium.so"} (bundle-entries "armv7-linux-androideabi"))
               (some #{"lib/emon/adapters/libemon_ocr_tesseract.dylib"} (bundle-entries "aarch64-apple-ios-sim"))
               (some #{"lib/libemon.dylib"} (bundle-entries "x86_64-apple-darwin")) (some #{"lib/emon/adapters/libemon_ocr_tesseract.so"} (bundle-entries "aarch64-unknown-linux-gnu"))))
      (t! "PP-OCR is not required in a bundle until bundle-ppocr? says so" (not-any? #(some (fn [e] (str/includes? e "ppocr")) (bundle-entries %)) triples-c))
      (t! "a missing adapter is named"
          (= ["the bundle of aarch64-apple-darwin has no lib/emon/adapters/libemon_ocr_vision.dylib"]
             (bundle-problems "0.1.0" "aarch64-apple-darwin" (remove #(str/ends-with? % "libemon_ocr_vision.dylib") (entries "aarch64-apple-darwin" "0.1.0")))))
      (t! "a bundle without its pdfium or without a pack is refused"
          (and (some #(str/includes? % "lib/libpdfium.so") (bundle-problems "0.1.0" "x86_64-linux-android" (remove #(str/ends-with? % "libpdfium.so") (entries "x86_64-linux-android" "0.1.0"))))
               (some #(str/includes? % "no pack") (bundle-problems "0.1.0" "x86_64-linux-android" (remove #(str/includes? % "share/emon/packs") (entries "x86_64-linux-android" "0.1.0"))))))
      (t! "a bundle of another version or another top directory is refused" (seq (bundle-problems "0.2.0" "x86_64-linux-android" (entries "x86_64-linux-android" "0.1.0")))))
    ;; the tag that starts pub.yml
    (let [d #(pub-tag-decision (merge {:sha sha} %))]
      (t! "pub tag: absent -> push" (= :push (:action (d {}))))
      (t! "pub tag: at this commit -> nothing to do" (= :exists (:action (d {:tag-sha sha}))))
      (t! "pub tag: at this commit and only=pub -> delete and push again, which starts pub.yml again" (= :retrigger (:action (d {:tag-sha sha :retrigger? true}))))
      (t! "pub tag: at another commit -> refused, whatever was asked" (and (= :refuse (:action (d {:tag-sha other}))) (= :refuse (:action (d {:tag-sha other :retrigger? true}))))))
    (t! "pub tag names the version, a pre-release included" (and (= "emon-dart-v0.3.0-preview.1" (pub-tag-name "0.3.0-preview.1")) (= "0.3.0-preview.1" (rl/tag->version "v0.3.0-preview.1"))))
    (t! "pub is a selectable registry" (and (some #{"pub"} registries) (:run (prepare-decision {:current "1.2.3" :sha sha :event "workflow_dispatch" :only "assets,pub"}))))
    (t! "verdict: plan and every job success -> success" (= "success" (verdict-state (parse-results "plan=success,core=success,gates=success"))))
    (t! "verdict: skipped jobs are fine" (= "success" (verdict-state (parse-results "plan=success,core=success,gates=skipped,unit=skipped,archives=skipped"))))
    (t! "verdict: one failure" (= "failure" (verdict-state (parse-results "plan=success,core=failure,gates=success"))))
    (t! "verdict: cancelled" (= "failure" (verdict-state (parse-results "plan=success,gates=cancelled"))))
    (t! "verdict: the plan skipped or failed is not success" (and (= "failure" (verdict-state (parse-results "plan=failure,core=skipped"))) (= "failure" (verdict-state (parse-results "core=success")))))
    (t! "verdict description: verified keeps the plan's" (= "verified: 0 gates since 89abcde" (verdict-description "success" "verified: 0 gates since 89abcde" [])))
    (t! "verdict description: a failure names the jobs, fits 140"
        (let [d (verdict-description "failure" "verified: 12 gates since 89abcde" (parse-results "plan=success,core=failure,gates=failure"))] (and (str/starts-with? d "NOT verified: core,gates") (<= (count d) 140))))
    (t! "verdict description: never starts with `verified:` when it failed" (not (str/starts-with? (verdict-description "failure" "verified: 1 gates since a" (parse-results "plan=failure")) "verified:")))
    (t! "archives status: built in this run" (= {:description "archives 0.1.0: run 99 (built at 0123456)" :target-url "https://github.com/3sln/emon/actions/runs/99"}
                                                (archives-status {:build? true :version "0.1.0" :sha sha :run-url "https://github.com/3sln/emon/actions/runs/99" :repo "3sln/emon"})))
    (t! "archives status: inherited verbatim, pointing at the run that built them"
        (= {:description "archives 0.1.0: run 42 (built at 89abcde)" :target-url "https://github.com/3sln/emon/actions/runs/42"}
           (archives-status {:build? false :repo "3sln/emon" :archives-run "42" :archives-desc "archives 0.1.0: run 42 (built at 89abcde)"})))
    (t! "plan fields: a built plan" (= {:description "verified: 3 gates since 89abcde" :version "0.1.0" :build-archives "true" :archives-run "" :archives-desc ""}
                                       (plan-fields "{\"description\":\"verified: 3 gates since 89abcde\",\"version\":\"0.1.0\",\"build_archives\":true,\"archives_run\":\"\",\"archives_desc\":\"\"}")))
    (t! "plan fields: an inherited plan" (let [f (plan-fields "{\"description\":\"verified: 0 gates since 89abcde\",\"version\":\"0.1.0\",\"build_archives\":false,\"archives_run\":\"42\",\"archives_desc\":\"archives 0.1.0: run 42 (built at 89abcde)\"}")]
                                           (and (= "false" (:build-archives f)) (= "42" (:archives-run f)) (str/starts-with? (:archives-desc f) "archives 0.1.0"))))
    (t! "plan fields: no plan (the plan job failed) gives a description that is not a green one" (let [f (plan-fields "{}")] (and (= "plan failed" (:description f)) (= "unknown" (:version f)))))
    (t! "plan fields: unparsable text is the same as no plan" (= "plan failed" (:description (plan-fields "not json"))))
    (t! "parse-results" (= [["a" "success" nil] ["b" "failure" "why: not"]] (parse-results "a=success,b=failure:why: not")))
    (let [ok (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "success" "already published"]])
          bad (summary-md "v1.2.3" [["crates" "success" "published"] ["npm" "failure" nil] ["maven" "cancelled" nil]])]
      (t! "summary: all success is ok" (:ok? ok))
      (t! "summary: a failure is not ok" (not (:ok? bad)))
      (t! "summary names the failed registries and the recovery" (and (str/includes? (:markdown bad) "Failed: npm, maven") (str/includes? (:markdown bad) "Re-run failed jobs")))
      (t! "summary: a dry run says so, and never claims anything was published"
          (let [m (:markdown (summary-md "v1.2.3" [["npm" "success" "dry-run"]] true))] (and (str/includes? m "DRY RUN: nothing was published") (not (str/includes? m "published |")))))
      (t! "summary: a skipped registry is not a failure" (:ok? (summary-md "v1.2.3" [["crates" "skipped" nil]]))))
    ;; collect real (tiny) archives: ten C bundles that are tarballs with the entries the table asks for, the rest plain files, through `archive!` and `dart!`
    (let [v (cur-version) tmp (str (fs/create-temp-dir {:prefix "emon-collect-test-"}))
          in (str tmp "/in") out (str tmp "/out")
          mk-bundle! (fn [t drop-entry]
                       (let [top (str "emon-c-" v "-" t) d (str tmp "/src/" top)]
                         (fs/delete-tree d)
                         (doseq [e (concat (remove #{drop-entry} (bundle-entries t)) ["share/emon/packs/tess-fast-eng/eng.traineddata"])]
                           (fs/create-dirs (fs/parent (str d "/" e))) (spit (str d "/" e) (str t " " e)))
                         (rl/run! "tar" "-czf" (str in "/" top ".tar.gz") "-C" (str tmp "/src") top)))]
      (try
        (fs/create-dirs in)
        (doseq [t triples-c] (mk-bundle! t nil))
        (doseq [n (built-assets v) :when (not (str/starts-with? n "emon-c-"))] (spit (str in "/" n) n))
        (archive! {:dirs [in] :out out :version v})
        (t! "archive collects the 29 files and writes SHA256SUMS of the 24 archives" (and (= 30 (count (files-of [out]))) (= 24 (count (parse-sums (slurp (str out "/SHA256SUMS")))))))
        (dart! {:dir out :version v})
        (let [sums (parse-sums (slurp (str out "/SHA256SUMS")))
              tree (str "emon-dart-" v)
              table (:out (rl/run! "tar" "-xzOf" (str out "/" (dart-asset v)) (str tree "/emon_core/lib/src/native_assets.g.dart")))
              pubspec (:out (rl/run! "tar" "-xzOf" (str out "/" (dart-asset v)) (str tree "/emon/pubspec.yaml")))
              names (str/split-lines (:out (rl/run! "tar" "-tzf" (str out "/" (dart-asset v)))))]
          (t! "dart: the tree is a release asset and SHA256SUMS lists it (and its hash is right)"
              (and (= 25 (count sums)) (= (get sums (dart-asset v)) (sha256-hex (str out "/" (dart-asset v)))) (= [] (check-dir out v))))
          (t! "dart: the hook table has the SHA-256 of every C bundle (desktop, Windows and mobile) as SHA256SUMS lists them"
              (every? (fn [t] (let [n (str "emon-c-" v "-" t ".tar.gz")] (and (str/includes? table (str "'" t "': NativeArchive('" n "', '" (get sums n) "')"))))) triples-c))
          (t! "dart: stamped with the version and publishable (a hosted dependency, no publish_to: none)"
              (and (str/includes? pubspec (str "version: " v)) (str/includes? pubspec (str "emon_core: ^" v)) (not (str/includes? pubspec "publish_to: none")) (not (str/includes? pubspec "path: ../"))))
          (t! "dart: every package of pub.yml is in the tree" (every? (fn [pkg] (some #{(str tree "/" pkg "/pubspec.yaml")} names)) ["emon_core" "emon_ocr_tesseract" "emon_ocr_ppocr" "emon_ocr_vision" "emon_ocr_winocr" "emon_ocr_mlkit" "emon" "emon_flutter"])))
        (dart! {:dir out :version v})
        (t! "dart: a rerun over its own output is the same set" (= [] (check-dir out v)))
        ;; a bundle without its adapter is refused by the collector
        (mk-bundle! "aarch64-apple-darwin" "lib/emon/adapters/libemon_ocr_vision.dylib")
        (t! "archive: a bundle that lacks an adapter library is named and refused"
            (= ["the bundle of aarch64-apple-darwin has no lib/emon/adapters/libemon_ocr_vision.dylib"] (bundle-problems-in v (files-of [in]))))
        (finally (fs/delete-tree tmp))))
    ;; Exercise effect wiring with synthetic archives and mocked GitHub; never call a registry.
    (let [tmp (str (fs/create-temp-dir {:prefix "emon-release-test-"}))
          v "1.2.3-rc.1" tag (str "v" v)
          calls (atom []) release (atom nil)
          green {:context rl/status-context :state "success" :description "verified: full gates"
                 :target_url "https://github.com/3sln/emon/actions/runs/99"}]
      (try
        (doseq [n (expected-assets v)] (spit (str tmp "/" n) n))
        (spit (str tmp "/SHA256SUMS")
              (sums-text (into {} (for [n (expected-assets v)] [n (sha256-hex (str tmp "/" n))]))))
        (with-redefs [cur-version (constantly v) remote-tag-sha (constantly nil)
                      find-release (fn [_] @release) rl/statuses-of (fn [& _] [green])
                      rl/git! (fn [& _] {:out sha :exit 0})
                      rl/run! (fn [& args] (swap! calls conj (vec args)) {:exit 0 :out ""})]
          (release! {:tag tag :sha sha :dir tmp :notes "notes.md" :dry-run? true})
          (t! "release dry-run makes no public mutations" (empty? @calls))
          (release! {:tag tag :sha sha :dir tmp :notes "notes.md"})
          (t! "release creates tag at verified sha before release"
              (and (= "api" (second (first @calls)))
                   (some #{(str "sha=" sha)} (first @calls))
                   (= ["gh" "release" "create"] (subvec (second @calls) 0 3))))
          (t! "release create carries all archives and prerelease flag"
              (and (some #{"--prerelease"} (second @calls))
                   (every? (set (second @calls)) (for [n (conj (expected-assets v) sums-file)] (str tmp "/" n)))))
          (reset! calls []) (reset! release {:id 1 :target_commitish sha})
          (with-redefs [remote-tag-sha (constantly sha)]
            (release! {:tag tag :sha sha :dir tmp :notes "notes.md"})
            (t! "retry reuses tag/release and replaces assets"
                (and (= 1 (count @calls)) (= ["gh" "release" "upload"] (subvec (first @calls) 0 3)))))
          (reset! calls [])
          (with-redefs [remote-tag-sha (constantly sha)]
            (release! {:tag tag :sha sha :dir tmp :notes "notes.md" :only "npm"})
            (t! "registry-only retry does not mutate existing release" (empty? @calls))))
        (finally (fs/delete-tree tmp))))
    (done!)))

;; ---------------------------------------------------------------------------------------------------- main
(def ^:private value-flags
  {"--only" :only "--event" :event "--bump" :bump "--version" :version "--tag" :tag "--sha" :sha "--notes" :notes "--out" :out "--limit" :limit
   "--results" :results "--run-url" :run-url "--plan" :plan})

(defn- parse [args]
  (loop [as args o {}]
    (if-let [a (first as)]
      (cond
        (value-flags a) (recur (nnext as) (assoc o (value-flags a) (second as)))
        (= a "--dir") (recur (nnext as) (-> o (update :dirs (fnil conj []) (second as)) (assoc :dir (second as))))
        (= a "--dry-run") (recur (next as) (assoc o :dry-run? true))
        :else (rl/die 2 "bb release-ops: unknown argument" a))
      o)))

(defn -main [[cmd & args]]
  (let [o (parse args)
        need (fn [& ks] (doseq [k ks] (when-not (get o k) (rl/die 2 (str "bb release-ops " cmd ": --" (name k) " is required")))))]
    (case cmd
      "next" (next! o)
      "prepare" (prepare! o)
      "release" (do (need :tag :sha :notes :dir) (release! o))
      "archive" (do (need :dirs :out) (archive! o))
      "verdict" (do (need :sha :results :run-url :plan) (verdict! o))
      "fetch-archives" (do (need :sha :version :out) (fetch-archives! o))
      "attach" (do (need :tag :dir) (attach! o))
      "dart" (do (need :dir) (dart! o))
      "pub-tag" (do (need :tag :sha) (pub-tag! o))
      "summary" (do (need :tag :results) (summary! o))
      (rl/die 2 "usage: bb release-ops prepare|release|next|archive|verdict|fetch-archives|attach|dart|pub-tag|summary ... [--dry-run] | --self-test"))))

(when (= *file* (System/getProperty "babashka.file"))
  (if (= ["--self-test"] (vec *command-line-args*))
    (self-test)
    (-main *command-line-args*)))
