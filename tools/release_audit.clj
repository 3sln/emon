;; The release licence audit (P11.3, DEVLOG D526): is the tree one we may publish under MIT?
;;
;;   bb release-audit                 run every check; exit 1 and name each failure when any fails
;;   bb release-audit --write         regenerate THIRD_PARTY_NOTICES.md from release/third-party.edn (and the texts in release/licenses/)
;;   bb release-audit --import-texts  copy the licence text of each Rust crate without one from ~/.cargo/registry into release/licenses/
;;                                    (offline: nothing is downloaded)
;;   bb release-audit --self-test     plant each failure in a temporary COPY of the tree and require the audit to catch it
;;
;; WHAT IT HOLDS THE TREE TO (every line below is a check; a failure prints `  FAIL ...`, a pass `  ok   ...`):
;;   1. LICENSE exists and is MIT, "Copyright (c) 2026 3sln".
;;   2. release/third-party.edn lists every third-party component a release artifact could carry or link: every crate of
;;      host/rust/Cargo.lock (and every [dependencies] name of the Cargo.toml heredocs in kin/scripts/*), every package of
;;      host/js/package-lock.json (and package.json), at the locked version. An entry the lock no longer has is stale.
;;   3. Every licence in it is in the allowlist (below); an OR is satisfied by one allowed arm, an AND needs all; a GPL, LGPL or
;;      AGPL licence anywhere fails, even as one arm of an OR; MPL-2.0 needs a note saying it is used at file level only.
;;   4. Excluded libraries (jbig2dec, anything GPL or AGPL) appear NOWHERE: not in the inventory, not in Cargo.lock, not on a
;;      link line of a build script, not in a `#[link(name = ..)]`, `#cgo LDFLAGS`, DllImport, dlopen, LoadLibrary or
;;      library-file name of a host source. The one allowed exception is a source file the inventory declares
;;      `:dev-only-sources`, and only while it is compiled out of every build that does not ask for it (checked: the cfg
;;      gate is still in the crate root, and the cfg flag is set only by the two named scripts).
;;   5. THIRD_PARTY_NOTICES.md is exactly what `--write` generates from the inventory, and every licence text the inventory
;;      names exists.
;;   6. No font or ICC profile is tracked under host/ or release/ (the inventory says none is bundled).
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

;; ------------------------------------------------------------------------------------------------ policy

;; FTL, AGG 2.3, libtiff and Adobe's Core 14 AFM notice (pdfium's FreeType and AGG, Leptonica's TIFF codec, the font
;; metrics) are permissive, attribution-only licences the SPEC list did not name; the maintainer allowed them by signing
;; off D526 (2026-10-13).
(def allowed-exact
  #{"MIT" "Apache-2.0" "BSD-2-Clause" "BSD-3-Clause" "ISC" "Zlib" "BSL-1.0" "MPL-2.0"
    "FTL" "LicenseRef-AGG-2.3" "libtiff" "LicenseRef-Adobe-Core14-AFM"})
(def allowed-prefix ["libpng" "IJG" "Unicode-"])

;; Licences awaiting a maintainer's decision: passed with a NOTE until one is made. Empty since D526's sign-off.
(def pending-decision #{})

(def deny-re #"(?i)jbig2dec(?!ode)|(?<![a-z])[al]?gpl(?![a-z])|gnu (?:affero|general|lesser)")
(def gpl-re #"(?i)(?<![a-z])[al]?gpl(?![a-z])|gnu (?:affero|general|lesser)")

(def font-icc-re #"(?i)\.(ttf|otf|ttc|woff2?|pfb|pfa|icc|icm)$")

(def inventory-path "release/third-party.edn")
(def notices-path "THIRD_PARTY_NOTICES.md")
(def texts-dir "release/licenses")

;; ------------------------------------------------------------------------------------------------ small helpers

(defn- path [root f] (str (fs/path root f)))
(defn- rd [root f] (let [p (path root f)] (when (fs/exists? p) (slurp p))))
;; A comment line of a shell script, a Clojure file or a host source. `#[link(..)]` (Rust) and `#cgo` (Go, in the cgo preamble) are code.
(defn- no-comment? [line]
  (or (boolean (re-find #"^\s*(?://\s*)?#cgo" line))
      (not (re-find #"^\s*(?:#(?!\[)|;;|//|\*|/\*)" line))))

(defn- tokens [expr]
  (re-seq #"\(|\)|[^\s()]+" expr))

(defn parse-expr
  "An SPDX expression to a tree: [:atom \"MIT\"] [:with atom exception] [:or a b ..] [:and a b ..]."
  [expr]
  (let [ts (vec (tokens expr))
        pos (atom 0)
        peek* #(get ts @pos)
        next* #(let [t (get ts @pos)] (swap! pos inc) t)
        parse-or (fn parse-or []
                   (let [parse-and (fn parse-and []
                                     (let [parse-term (fn parse-term []
                                                        (let [t (next*)]
                                                          (cond
                                                            (nil? t) (throw (ex-info (str "empty licence expression: " (pr-str expr)) {}))
                                                            (= t "(") (let [e (parse-or)]
                                                                        (when-not (= ")" (next*)) (throw (ex-info (str "unbalanced parenthesis in " (pr-str expr)) {})))
                                                                        e)
                                                            (#{")" "AND" "OR" "WITH"} t) (throw (ex-info (str "unexpected " t " in " (pr-str expr)) {}))
                                                            (= "WITH" (peek*)) (do (next*) [:with t (next*)])
                                                            :else [:atom t])))]
                                       (loop [xs [(parse-term)]]
                                         (if (= "AND" (peek*)) (do (next*) (recur (conj xs (parse-term)))) (if (= 1 (count xs)) (first xs) (into [:and] xs))))))]
                     (loop [xs [(parse-and)]]
                       (if (= "OR" (peek*)) (do (next*) (recur (conj xs (parse-and)))) (if (= 1 (count xs)) (first xs) (into [:or] xs))))))
        tree (parse-or)]
    (when (< @pos (count ts)) (throw (ex-info (str "trailing tokens in licence expression " (pr-str expr)) {})))
    tree))

(defn atoms [tree]
  (case (first tree)
    :atom [(second tree)]
    :with [(second tree)]
    (mapcat atoms (rest tree))))

(defn satisfied? [tree ok?]
  (case (first tree)
    :atom (boolean (ok? (second tree)))
    :with (boolean (ok? (second tree)))
    :or (boolean (some #(satisfied? % ok?) (rest tree)))
    :and (every? #(satisfied? % ok?) (rest tree))))

(defn allowed-atom? [a]
  (boolean (or (allowed-exact a) (some #(str/starts-with? a %) allowed-prefix))))
(defn pending-atom? [a] (boolean (pending-decision a)))

;; ------------------------------------------------------------------------------------------------ the inventory

(defn read-inventory [root]
  (let [s (rd root inventory-path)]
    (when s (edn/read-string s))))

(defn text-files
  "The licence text files (names under release/licenses/) a component's notice quotes."
  [c]
  (cond
    (vector? (:text c)) (:text c)
    (= :crate (:kind c)) [(str "cargo-" (:name c) "-" (:version c) ".txt")]
    :else []))

(defn text-state [c]
  (cond (vector? (:text c)) :files
        (= :crate (:kind c)) :files
        (#{:todo :not-shipped} (:text c)) (:text c)
        :else :bad))

;; ------------------------------------------------------------------------------------------------ the checks

(defn- cargo-lock-crates [root]
  (when-let [s (rd root "host/rust/Cargo.lock")]
    (for [[_ n v] (re-seq #"(?m)^name = \"([^\"]+)\"\nversion = \"([^\"]+)\"" s)
          :when (not= n "emon")]
      [n v])))

(defn- npm-packages [root]
  (when-let [s (rd root "host/js/package-lock.json")]
    (for [[k v] (:packages (json/parse-string s true))
          :let [nm (subs (str k) 1)]
          :when (seq nm)
          :let [pkg (last (str/split nm #"node_modules/"))]]
      [pkg (:version v)])))

(defn- heredoc-deps
  "Names under a [dependencies] table in the Cargo.toml heredocs of the build scripts."
  [root]
  (for [f (concat (sort (map str (fs/glob (path root "kin/scripts") "*")))
                  ;; the published crate's manifest (P11.4, D542) is a Cargo.toml like them
                  [(path root "release/pkg/rust/Cargo.toml")])
        :when (fs/regular-file? f)
        :let [s (slurp f)]
        :when (str/includes? s "[dependencies]")
        sect (rest (str/split s #"\[dependencies\]"))
        line (str/split-lines (first (str/split sect #"\n\s*\n|\n\[")))
        :let [m (re-find #"^([A-Za-z0-9_-]+)\s*=" line)]
        :when m]
    [(fs/file-name f) (second m)]))

(defn- source-files [root]
  (let [h (path root "host")]
    (when (fs/exists? h)
      (for [f (fs/glob h "**" {:follow-links false})
            :let [s (str f)]
            :when (and (fs/regular-file? s)
                       (re-find #"\.(rs|go|java|cs|c|h|js|mjs)$" s)
                       (not (re-find #"/(node_modules|target)/" s)))]
        (str (fs/relativize root s))))))

(defn- link-names
  "[line-no what name] for every native library name a host source file hands to the linker or loader."
  [text]
  (let [lines (map-indexed vector (str/split-lines text))]
    (for [[i line] lines
          :when (no-comment? line)
          [what re] [[:link #"#\[link\(\s*name\s*=\s*\"([^\"]+)\""]
                     [:cgo #"#cgo\s+[A-Z]*LDFLAGS:\s*(.*)$"]
                     [:call #"(?:dlopen|LoadLibrary\w*|DllImport|libraryLookup|NativeLibrary\.\w+|System\.loadLibrary|System\.load)\(\s*\"([^\"]+)\""]
                     [:file #"\"((?:lib)?[\w.+-]*\.(?:so|dylib|dll|a|lib)(?:\.[0-9]+)*)\""]]
          m (re-seq re line)]
      [(inc i) what (if (vector? m) (second m) m)])))

(defn- link-lines
  "Non-comment lines of a build script that name a library for the linker."
  [text]
  (for [[i line] (map-indexed vector (str/split-lines text))
        :when (and (no-comment? line)
                   (re-find #"(?:^|\s)-l\s*[A-Za-z_]|link-lib|link-arg|rustc-link|LDFLAGS|-Wl,|#\[link|\.dylib|\.so\b|\.dll\b|\.a\b|-framework|pkg-config" line))]
    [(inc i) line]))

(defn audit
  "Every finding for the tree at `root`: a seq of [:fail|:note|:ok message]."
  [root]
  (let [out (atom [])
        fail! #(swap! out conj [:fail (apply str %&)])
        note! #(swap! out conj [:note (apply str %&)])
        ok! #(swap! out conj [:ok (apply str %&)])
        n-before #(count (filter (comp #{:fail} first) @out))]
    ;; 1. LICENSE
    (let [b (n-before) s (rd root "LICENSE")]
      (cond
        (nil? s) (fail! "LICENSE is missing")
        (not (and (re-find #"\A\s*MIT License\s*\n" s)
                  (str/includes? s "Copyright (c) 2026 3sln")
                  (str/includes? s "Permission is hereby granted, free of charge, to any person obtaining a copy")
                  (str/includes? s "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND")))
        (fail! "LICENSE is not the MIT licence with \"Copyright (c) 2026 3sln\""))
      (when (= b (n-before)) (ok! "LICENSE is MIT, Copyright (c) 2026 3sln")))
    ;; manifests that exist carry the licence field
    (let [b (n-before)]
      (when-let [s (rd root "host/js/package.json")]
        (when-not (= "MIT" (:license (json/parse-string s true))) (fail! "host/js/package.json has no \"license\": \"MIT\"")))
      (doseq [f ["kin/scripts/emon-rs-build" "kin/scripts/emon-bind-build" "kin/scripts/emon-wasm-build"]]
        (when-let [s (rd root f)]
          (when-not (re-find #"(?m)^name = \"emon\"\nversion = \"[^\"]+\"\nlicense = \"MIT\"$" s)
            (fail! f ": the [package] table of its Cargo.toml has no `license = \"MIT\"` after the version"))))
      ;; the published manifests of `bb package` (P11.4, D542): every one says MIT in its registry's own field
      (doseq [[f re what] [["release/pkg/rust/Cargo.toml" #"(?m)^license = \"MIT\"$" "`license = \"MIT\"`"]
                           ["release/pkg/java/pom.xml" #"<licenses>\s*<license>\s*<name>MIT License</name>" "a <license> named MIT License"]
                           ["release/pkg/csharp/Emon.csproj" #"<PackageLicenseExpression>MIT</PackageLicenseExpression>" "<PackageLicenseExpression>MIT"]]]
        (if-let [s (rd root f)]
          (when-not (re-find re s) (fail! f ": no " what))
          (fail! f ": the published manifest is missing")))
      (when-let [s (rd root "host/js/package.json")]
        (let [j (json/parse-string s true)]
          (when (:private j) (fail! "host/js/package.json is \"private\": true: npm would refuse to publish it"))
          (when-not (get-in j [:exports (keyword "./pdfium")]) (fail! "host/js/package.json does not export ./pdfium"))))
      (when-let [s (rd root "release/pkg/go/go.mod")]
        (when-not (re-find #"(?m)^module github\.com/3sln/emon/go$" s) (fail! "release/pkg/go/go.mod is not `module github.com/3sln/emon/go`")))
      (when (= b (n-before)) (ok! "the manifests that exist (package.json, three Cargo.toml heredocs, the published Cargo.toml, POM and csproj) say MIT")))
    ;; the inventory
    (let [inv (try (read-inventory root) (catch Exception e (fail! inventory-path ": unreadable (" (ex-message e) ")") nil))
          comps (:components inv)]
      (if-not inv
        (when-not (rd root inventory-path) (fail! inventory-path " is missing"))
        (let [by-id (into {} (map (juxt :id identity)) comps)
              artifacts (set (keys (:artifacts inv)))]
          ;; shape
          (let [b (n-before)]
            (when-not (= (count comps) (count by-id)) (fail! inventory-path ": duplicate component ids"))
            (doseq [c comps]
              (let [id (:id c)]
                (doseq [k [:id :kind :name :version :licence :url :status :carried-by]]
                  (when (nil? (get c k)) (fail! id ": missing " k)))
                (when-not (#{:shipped :planned :dev-only :optional} (:status c)) (fail! id ": status must be :shipped :planned :dev-only or :optional"))
                (doseq [a (concat (:carried-by c) (:loaded-by c))]
                  (when-not (artifacts a) (fail! id ": unknown artifact " a)))
                (when (and (#{:shipped :planned} (:status c)) (empty? (:carried-by c))) (fail! id ": status " (:status c) " but carried by nothing"))
                (when (= :bad (text-state c)) (fail! id ": :text must be a vector of files, :todo or :not-shipped"))))
            (when (= b (n-before)) (ok! (str "the inventory is well formed (" (count comps) " components, " (count artifacts) " artifacts)"))))
          ;; coverage: Rust
          (let [b (n-before)
                crates (cargo-lock-crates root)
                want (set (map (fn [[n v]] (str "cargo/" n "@" v)) crates))
                have (set (map :id (filter #(= :crate (:kind %)) comps)))
                names (set (map :name (filter #(= :crate (:kind %)) comps)))]
            (when (nil? crates) (fail! "host/rust/Cargo.lock is missing"))
            (doseq [id (sort (remove have want))] (fail! id " is in host/rust/Cargo.lock and not in " inventory-path))
            (doseq [id (sort (remove want have))] (fail! id " is in " inventory-path " and not in host/rust/Cargo.lock (stale)"))
            (doseq [[f n] (heredoc-deps root)]
              (when-not (names n) (fail! "crate `" n "` (Cargo.toml heredoc of kin/scripts/" f ") is not in " inventory-path)))
            (when (= b (n-before)) (ok! (str "every Cargo.lock crate (" (count crates) ") and heredoc dependency is in the inventory"))))
          ;; coverage: npm
          (let [b (n-before)
                pk (npm-packages root)
                want (set (map (fn [[n v]] (str "npm/" n "@" v)) pk))
                have (set (map :id (filter #(= :npm (:kind %)) comps)))
                names (set (map :name (filter #(= :npm (:kind %)) comps)))]
            (when (nil? pk) (fail! "host/js/package-lock.json is missing"))
            (doseq [id (sort (remove have want))] (fail! id " is in host/js/package-lock.json and not in " inventory-path))
            (doseq [id (sort (remove want have))] (fail! id " is in " inventory-path " and not in host/js/package-lock.json (stale)"))
            (when-let [s (rd root "host/js/package.json")]
              (let [p (json/parse-string s true)]
                (doseq [n (map #(subs (str %) 1) (keys (merge (:dependencies p) (:devDependencies p))))]
                  (when-not (names n) (fail! "npm `" n "` (host/js/package.json) is not in " inventory-path)))))
            (when (= b (n-before)) (ok! (str "every npm package (" (count pk) ") is in the inventory"))))
          ;; Go
          (when-let [s (rd root "kin/scripts/emon-go-build")]
            (when (re-find #"(?m)^\s*(?:go get|go mod tidy)" s)
              (fail! "kin/scripts/emon-go-build fetches Go modules; they must be listed in " inventory-path " (and this audit taught to read go.mod)")))
          ;; licences
          (let [b (n-before) pending (atom #{})]
            (doseq [c comps]
              (let [id (:id c)
                    tree (try (parse-expr (str (:licence c))) (catch Exception e (fail! id ": " (ex-message e)) nil))]
                (when tree
                  (let [as (atoms tree)]
                    (doseq [a as]
                      (when (re-find gpl-re a) (fail! id ": licence " a " is GPL-family; none may be in the inventory")))
                    (cond
                      (some #(re-find gpl-re %) as) nil
                      (not (satisfied? tree #(or (allowed-atom? %) (pending-atom? %))))
                      (fail! id ": licence \"" (:licence c) "\" is outside the allowlist")
                      (not (satisfied? tree allowed-atom?))
                      (swap! pending conj [id (:licence c)]))
                    (when (some #{"MPL-2.0"} as)
                      (when-not (and (string? (:mpl-note c)) (re-find #"(?i)file" (:mpl-note c)))
                        (fail! id ": MPL-2.0 is allowed at file level only; :mpl-note must say how it is used (unmodified files, kept separate)")))
                    (when-let [e (:elected c)]
                      (when-not (some #{e} as) (fail! id ": :elected " e " is not an arm of " (:licence c)))
                      (when-not (or (allowed-atom? e) (pending-atom? e)) (fail! id ": :elected " e " is outside the allowlist"))
                      (when (re-find gpl-re e) (fail! id ": :elected " e " is GPL-family")))))))
            (when (seq @pending)
              (note! (str (count @pending) " component(s) carry a licence outside the SPEC list and await the maintainer's sign-off of D526: "
                          (str/join ", " (map (fn [[i l]] (str i " (" l ")")) (sort @pending))))))
            (when (= b (n-before)) (ok! (str "every listed licence is in the allowlist (" (count comps) " components)"))))
          ;; exclusions
          (let [b (n-before)
                inv-text (or (rd root inventory-path) "")
                dev (:dev-only-sources inv)
                dev-paths (set (map :path dev))]
            (when-let [m (re-find deny-re inv-text)] (fail! inventory-path ": names an excluded library (" m ")"))
            (when-let [s (rd root "host/rust/Cargo.lock")]
              (when-let [m (re-find deny-re s)] (fail! "host/rust/Cargo.lock: names an excluded library (" m ")")))
            (doseq [f (sort (map str (fs/glob (path root "kin/scripts") "*")))
                    :when (fs/regular-file? f)
                    :let [s (slurp f)]
                    [i line] (link-lines s)
                    :when (re-find deny-re line)]
              (fail! "kin/scripts/" (fs/file-name f) ":" i ": a link line names an excluded library: " (str/trim line)))
            (doseq [f (sort (source-files root))
                    :let [s (slurp (path root f))]
                    [i what nm] (link-names s)
                    :let [nm (str nm)]
                    :when (re-find deny-re nm)]
              (if (dev-paths f)
                (note! (str f ":" i ": " (name what) " `" nm "` is in a source declared :dev-only-sources (compiled out of every release build; see D526)"))
                (fail! f ":" i ": a " (name what) " name `" nm "` is an excluded library")))
            ;; the dev-only exception is only an exception while it stays compiled out
            (doseq [{:keys [gate-file gate-re cfg-flag set-only-in] dev-path :path} dev]
              (let [g (rd root gate-file)]
                (when-not (and g (re-find (re-pattern gate-re) g))
                  (fail! dev-path ": declared :dev-only-sources but " gate-file " no longer gates it (" gate-re ")")))
              (doseq [f (sort (map str (fs/glob (path root "kin/scripts") "*")))
                      :when (fs/regular-file? f)
                      :let [nm (fs/file-name f)]
                      :when (and (not ((set set-only-in) nm)) (str/includes? (slurp f) cfg-flag))]
                (fail! "kin/scripts/" nm ": sets `" cfg-flag "`, which only " (str/join " and " set-only-in) " may (the gate of " dev-path ")")))
            (when (= b (n-before)) (ok! "no excluded library (jbig2dec, any GPL-family) in the inventory, Cargo.lock, build-script link lines or host library names")))
          ;; fonts and ICC profiles
          (let [b (n-before)]
            (doseq [d ["host" "release"]
                    :when (fs/exists? (path root d))
                    f (fs/glob (path root d) "**")
                    :let [s (str f)]
                    :when (and (fs/regular-file? s) (re-find font-icc-re s) (not (re-find #"/node_modules/" s)))]
              (fail! (str (fs/relativize root s)) ": a font or ICC profile; list it in " inventory-path " and teach this check about it"))
            (when (= b (n-before)) (ok! "no font or ICC profile is tracked under host/ or release/")))
          ;; texts
          (let [b (n-before)]
            (doseq [c comps :when (not= :bad (text-state c)) f (text-files c)]
              (when-not (fs/exists? (path root (str texts-dir "/" f)))
                (fail! (:id c) ": licence text " texts-dir "/" f " is missing (bb release-audit --import-texts for a crate)")))
            (when (= b (n-before)) (ok! "every licence text the inventory names is in release/licenses/")))
          ;; the browser demo's vendored files (P11.4c): every file `bb demo` vendors maps to a component the demo carries
          (let [b (n-before)
                df (:demo-files inv)
                build (rd root "tools/demo/build.clj")
                lits (when build (distinct (map second (re-seq #"\"/(vendor/[^\"]*)\"" build))))]
            (doseq [[prefix ids] df
                    id ids
                    :let [c (by-id id)]]
              (cond (nil? c) (fail! inventory-path " :demo-files " prefix ": no component " id)
                    (not (some #{:browser-demo} (:carried-by c))) (fail! inventory-path " :demo-files " prefix ": " id " is not carried by the browser demo")))
            (when (and build (empty? df)) (fail! inventory-path " has no :demo-files, and tools/demo/build.clj vendors files"))
            (doseq [lit lits
                    :when (not (some #(str/starts-with? lit %) (keys df)))]
              (fail! "tools/demo/build.clj vendors `" lit "` and no :demo-files prefix of " inventory-path " covers it"))
            (when (and build (seq df) (empty? lits)) (fail! "tools/demo/build.clj names no /vendor/ file: this check reads nothing"))
            (when (= b (n-before)) (ok! (str "every file the demo vendors (" (count lits) " paths in tools/demo/build.clj) maps to components the browser demo carries (" (count df) " prefixes)"))))
          ;; the native pins (P11.5, D540): tools/native/native.edn is what `bb native` builds; the inventory must say the same, and the build must keep
          ;; JPEG 2000 and JBIG off
          (when-let [ns (rd root "tools/native/native.edn")]
            (let [b (n-before)
                  pins (try (edn/read-string ns) (catch Exception e (fail! "tools/native/native.edn: unreadable (" (ex-message e) ")") nil))
                  used (set (mapcat text-files comps))]
              (when pins
                (doseq [s (:sources pins)]
                  (let [c (by-id (:inventory s))]
                    (cond (nil? c) (fail! "tools/native/native.edn: " (:id s) " names the inventory entry " (:inventory s) ", which is not in " inventory-path)
                          (not= (:version s) (:version c)) (fail! "tools/native/native.edn pins " (:id s) " " (:version s) " but " (:id c) " says " (:version c))
                          (= :dev-only (:status c)) (fail! (:id c) ": built by tools/native/native.edn and :dev-only"))
                    (when (re-find deny-re (str (:id s) " " (:name s) " " (:url s)))
                      (fail! "tools/native/native.edn: source " (:id s) " is an excluded library"))))
                (let [cmake (fn [id] (set (:cmake (first (filter #(= id (:id %)) (:sources pins))))))]
                  (when-not (contains? (cmake "leptonica") "-DENABLE_OPENJPEG=OFF") (fail! "tools/native/native.edn: Leptonica is not built with -DENABLE_OPENJPEG=OFF (JPX stays unsupported, D524)"))
                  (when-not (contains? (cmake "libtiff") "-Djbig=OFF") (fail! "tools/native/native.edn: libtiff is not built with -Djbig=OFF (JBIG-KIT is GPL)"))
                  (when-not (contains? (cmake "leptonica") "-DSTRICT_CONF=ON") (fail! "tools/native/native.edn: Leptonica is not built with -DSTRICT_CONF=ON (a missing codec must fail, not be left out)")))
                (doseq [{:keys [out]} (concat (:licence-texts pins) (:texts (:pdfium-licences pins)))]
                  (when-not (used out) (fail! "release/licenses/" out " is vendored by tools/native/native.edn and no inventory entry's :text names it")))
                (doseq [{:keys [inventory]} (:data pins)]
                  (when-not (by-id inventory) (fail! "tools/native/native.edn: data entry names " inventory ", which is not in " inventory-path))))
              (when (= b (n-before)) (ok! "tools/native/native.edn agrees with the inventory (versions, licence texts) and builds Leptonica without OpenJPEG and libtiff without JBIG"))))
          nil)))
    @out))

;; ------------------------------------------------------------------------------------------------ the notices

(defn- fence [s] (str "````text\n" (str/trimr s) "\n````"))

(defn- carried [inv c]
  (let [nm #(get-in inv [:artifacts %] (name %))
        a (seq (:carried-by c))
        l (seq (:loaded-by c))]
    (str (if a (str "carried by " (str/join ", " (map nm a))) "carried by no release artifact")
         (when l (str "; loaded at run time (not carried) by " (str/join ", " (map nm l)))))))

(defn- used-under [c]
  (cond (:elected c) (str (:licence c) " (used under " (:elected c) ")")
        :else (:licence c)))

(def sections
  [[:crate "Rust crates" "Compiled into the Rust, C, pod and WASM artifacts (host/rust/Cargo.lock)."]
   [:native "Native libraries" "Not carried by the library packages (the Go, Java and C# hosts and the C library and pod load pdfium and Tesseract from the user's system at run time); carried by the per-platform CLI and the browser demo."]
   [:data "Data the generated code embeds" "Tables built into every artifact by kin's generators from these sources."]
   [:npm "npm packages" "Development dependencies of host/js (the browser test harness and the Tesseract.js adapter); `files` is `src` only, so none is in the npm package. The browser demo ships tesseract.js, tesseract.js-core, the English model's package and what tesseract.js bundles (`bb demo` copies their dist files)."]])

(defn notices
  "THIRD_PARTY_NOTICES.md for the inventory, with licence texts read from `root`'s release/licenses/."
  [root inv]
  (let [comps (:components inv)
        texts (into {} (for [c comps f (text-files c)
                             :let [p (path root (str texts-dir "/" f))]]
                         [f (if (fs/exists? p) (slurp p) (str "MISSING " f))]))
        ;; one entry per distinct text, listing the components that quote it
        groups (reduce (fn [m c] (reduce (fn [m f] (update m (get texts f) (fnil conj []) c)) m (text-files c)))
                       (array-map) comps)
        ids (into {} (map-indexed (fn [i [t _]] [t (inc i)]) groups))]
    (str
     "# Third-party notices\n\n"
     "emon is released under the MIT licence (see `LICENSE`). It includes, links or can load the third-party components below. "
     "This file is generated from `release/third-party.edn` by `bb release-audit --write`; do not edit it by hand "
     "(`bb release-audit` fails when it is stale). A licence text marked TODO is not yet vendored; P11.5 adds it with the build of the library.\n\n"
     (:summary inv) (when (:summary inv) "\n\n")
     (str/join
      "\n"
      (for [[kind title blurb] sections
            :let [cs (filter #(= kind (:kind %)) comps)]
            :when (seq cs)]
        (str "## " title "\n\n" blurb "\n\n"
             (str/join
              "\n"
              (for [c cs]
                (str "- **" (:name c) "** " (:version c) " - " (used-under c) " - " (:url c) "\n"
                     "  " (carried inv c) "."
                     (when (:note c) (str " " (:note c)))
                     "\n  "
                     (case (text-state c)
                       :files (str "Licence text: "
                                   (str/join ", " (map (fn [f] (str "[" (ids (get texts f)) "](#licence-text-" (ids (get texts f)) ")")) (text-files c))) ".")
                       :todo "Licence text: TODO: vendored at P11.5."
                       :not-shipped "Licence text: not carried by any artifact; the upstream package holds it."
                       "")
                     "\n")))
             "\n")))
     "## Licence texts\n\n"
     (str/join
      "\n"
      (for [[t cs] groups]
        (str "### Licence text " (ids t) "\n\nUsed by: " (str/join ", " (map #(str (:name %) " " (:version %)) cs)) ".\n\n" (fence t) "\n")))
     )))

;; ------------------------------------------------------------------------------------------------ text import

(defn- registry-dir [n v]
  (let [home (System/getProperty "user.home")
        base (str home "/.cargo/registry/src")]
    (when (fs/exists? base)
      (first (filter fs/exists? (map #(str % "/" n "-" v) (map str (fs/list-dir base))))))))

(defn- pick-text-file [dir elected]
  (let [tok (case elected "Apache-2.0" "apache" (str/lower-case (str elected)))
        names (map fs/file-name (fs/list-dir dir))
        by #(first (sort (filter (fn [f] (re-find % f)) names)))]
    (or (by (re-pattern (str "(?i)^licen[sc]e[-_.]" tok)))
        (by #"(?i)^licen[sc]e(?:\.md|\.txt)?$")
        (by #"(?i)^licen[sc]e"))))

(defn import-texts! [root]
  (let [inv (read-inventory root)]
    (fs/create-dirs (path root texts-dir))
    (doseq [c (:components inv) :when (= :crate (:kind c))
            :let [dest (path root (str texts-dir "/" (first (text-files c))))]
            :when (not (fs/exists? dest))]
      (if-let [dir (registry-dir (:name c) (:version c))]
        (if-let [f (pick-text-file dir (or (:elected c) (:licence c)))]
          (do (spit dest (slurp (str dir "/" f))) (println "  imported" (:id c) "<-" f))
          (println "  FAIL no licence file in" dir))
        (println "  FAIL" (:id c) "is not in ~/.cargo/registry/src (fetch it with cargo, or vendor its text by hand)")))))

;; ------------------------------------------------------------------------------------------------ the run

(defn stale-notices [root]
  (let [inv (read-inventory root)
        have (rd root notices-path)]
    (cond (nil? inv) [[:fail (str inventory-path " is missing")]]
          (nil? have) [[:fail (str notices-path " is missing (bb release-audit --write)")]]
          (not= have (notices root inv)) [[:fail (str notices-path " is stale relative to " inventory-path " or release/licenses/ (bb release-audit --write)")]]
          :else [[:ok (str notices-path " is what " inventory-path " generates")]])))

(defn run-all [root]
  (let [a (audit root)
        inv-ok? (try (some? (read-inventory root)) (catch Exception _ false))
        n (if inv-ok?
            (try (stale-notices root) (catch Exception e [[:fail (str "notices: " (ex-message e))]]))
            [])]
    (into (vec a) n)))

(defn report! [findings]
  (doseq [[lvl m] findings]
    (println (case lvl :ok "  ok  " :note "  NOTE" :fail "  FAIL") m))
  (count (filter #(= :fail (first %)) findings)))

;; ------------------------------------------------------------------------------------------------ the self-test

(def copy-roots ["LICENSE" "THIRD_PARTY_NOTICES.md" "release" "host" "kin/scripts" "tools/demo" "tools/native"])

(defn- copy-tree! [from to]
  (doseq [r copy-roots
          :let [src (path from r)]
          :when (fs/exists? src)]
    (if (fs/directory? src)
      (doseq [f (fs/glob src "**" {:follow-links false})
              :let [s (str f)]
              :when (and (fs/regular-file? s) (not (re-find #"/(node_modules|target)/" s)))]
        (let [d (path to (str (fs/relativize from s)))]
          (fs/create-dirs (fs/parent d))
          (fs/copy s d {:replace-existing true})))
      (do (fs/create-dirs (fs/parent (path to r)))
          (fs/copy src (path to r) {:replace-existing true})))))

(defn self-test []
  (let [real (str (fs/absolutize "."))
        tmp (str (fs/create-temp-dir {:prefix "emon-release-audit-"}))
        fails (atom [])
        t! (fn [label ok] (when-not ok (swap! fails conj label)) (println (if ok "  ok  " "  FAIL") label))
        fresh! (fn [] (fs/delete-tree tmp) (fs/create-dirs tmp) (copy-tree! real tmp))
        edit! (fn [f g] (let [p (path tmp f)] (spit p (g (slurp p)))))
        caught? (fn [re] (some (fn [[l m]] (and (= :fail l) (re-find re m))) (run-all tmp)))
        case! (fn [label re mutate]
                (fresh!) (mutate)
                (t! (str "caught: " label) (boolean (caught? re))))]
    (try
      (fresh!)
      (t! "the untouched copy passes" (zero? (count (filter #(= :fail (first %)) (run-all tmp)))))
      ;; regeneration is idempotent
      (t! "the notices regenerate byte for byte" (= (slurp (path tmp notices-path)) (notices tmp (read-inventory tmp))))
      ;; 1. LICENSE
      (case! "LICENSE missing" #"LICENSE is missing" #(fs/delete (path tmp "LICENSE")))
      (case! "LICENSE not MIT" #"LICENSE is not the MIT" #(spit (path tmp "LICENSE") "Apache License\nVersion 2.0\n"))
      (case! "LICENSE with another holder" #"LICENSE is not the MIT" #(edit! "LICENSE" (fn [s] (str/replace s "3sln" "someone else"))))
      (case! "package.json without the licence field" #"package.json has no" #(edit! "host/js/package.json" (fn [s] (str/replace s "\"license\": \"MIT\"," ""))))
      (case! "a Cargo.toml heredoc without the licence field" #"emon-wasm-build: the \[package\]" #(edit! "kin/scripts/emon-wasm-build" (fn [s] (str/replace s "license = \"MIT\"\n" ""))))
      (case! "the published Cargo.toml without the licence field" #"release/pkg/rust/Cargo.toml: no `license" #(edit! "release/pkg/rust/Cargo.toml" (fn [s] (str/replace s "license = \"MIT\"\n" ""))))
      (case! "the POM without the licence" #"release/pkg/java/pom.xml: no a <license>" #(edit! "release/pkg/java/pom.xml" (fn [s] (str/replace s "<name>MIT License</name>" "<name>Other</name>"))))
      (case! "the csproj without the licence" #"Emon.csproj: no <PackageLicenseExpression>" #(edit! "release/pkg/csharp/Emon.csproj" (fn [s] (str/replace s "<PackageLicenseExpression>MIT</PackageLicenseExpression>" ""))))
      (case! "package.json still private" #"is \"private\": true" #(edit! "host/js/package.json" (fn [s] (str/replace s "\"license\": \"MIT\"," "\"license\": \"MIT\",\n  \"private\": true,"))))
      (case! "package.json without the ./pdfium export" #"does not export \./pdfium" #(edit! "host/js/package.json" (fn [s] (str/replace s "\"./pdfium\": \"./src/pdfium.js\"," ""))))
      (case! "the Go module under another path" #"is not `module github.com/3sln/emon/go`" #(edit! "release/pkg/go/go.mod" (fn [s] (str/replace s "emon/go" "emon"))))
      (case! "a dependency of the published Cargo.toml missing from the inventory" #"crate `serde` \(Cargo.toml heredoc of kin/scripts/Cargo.toml\)"
             #(edit! "release/pkg/rust/Cargo.toml" (fn [s] (str/replace s "[dependencies]\n" "[dependencies]\nserde = \"1\"\n"))))
      ;; 2. coverage
      (case! "a Cargo.lock crate missing from the inventory" #"cargo/zlib-rs@0.6.8 is in host/rust/Cargo.lock and not in"
             #(edit! inventory-path (fn [s] (str/replace s #"(?m)^\s*\{:id \"cargo/zlib-rs@0\.6\.8\".*\n" ""))))
      (case! "a crate at another version" #"cargo/png@0.18.1 is in"
             #(edit! "host/rust/Cargo.lock" (fn [s] (str/replace s "name = \"png\"\nversion = \"0.18.1\"" "name = \"png\"\nversion = \"0.18.2\""))))
      (case! "a stale crate entry" #"is in release/third-party.edn and not in host/rust/Cargo.lock"
             #(edit! "host/rust/Cargo.lock" (fn [s] (str/replace s "name = \"pxfm\"" "name = \"pxfm-renamed\""))))
      (case! "a heredoc dependency missing from the inventory" #"crate `serde` \(Cargo.toml heredoc"
             #(edit! "kin/scripts/emon-rs-build" (fn [s] (str/replace s "[dependencies]\n" "[dependencies]\nserde = \"1\"\n"))))
      (case! "an npm package missing from the inventory" #"npm/ws@8.22.0 is in host/js/package-lock.json and not in"
             #(edit! inventory-path (fn [s] (str/replace s #"(?m)^\s*\{:id \"npm/ws@8\.22\.0\".*\n" ""))))
      (case! "a new devDependency missing from the inventory" #"npm `left-pad` \(host/js/package.json\)"
             #(edit! "host/js/package.json" (fn [s] (str/replace s "\"devDependencies\": {" "\"devDependencies\": {\n    \"left-pad\": \"1.3.0\","))))
      ;; 3. licences
      (case! "a licence outside the allowlist" #"licence \"Proprietary\" is outside the allowlist"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"Proprietary\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "an unparsable licence" #"unbalanced parenthesis"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"(MIT\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "a GPL component" #"GPL-3.0-only is GPL-family"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"GPL-3.0-only\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "an AGPL arm of an OR with an allowed arm" #"AGPL-3.0-or-later is GPL-family"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"MIT OR AGPL-3.0-or-later\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "an LGPL component" #"LGPL-2.1-only is GPL-family"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"LGPL-2.1-only\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "MPL-2.0 without a file-level note" #"MPL-2.0 is allowed at file level only"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"MPL-2.0\" :url \"https://crates.io/crates/simd-adler32\""))))
      (case! "an :elected licence that is not an arm" #":elected Zlib is not an arm"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"MIT\" :url \"https://crates.io/crates/simd-adler32\"" "\"MIT\" :elected \"Zlib\" :url \"https://crates.io/crates/simd-adler32\""))))
      ;; 4. exclusions
      (case! "jbig2dec in the inventory" #"third-party.edn: names an excluded library"
             #(edit! inventory-path (fn [s] (str/replace-first s "\n :components" "\n :note \"links libjbig2dec\"\n :components"))))
      (case! "jbig2dec in Cargo.lock" #"Cargo.lock: names an excluded library"
             #(edit! "host/rust/Cargo.lock" (fn [s] (str s "\n[[package]]\nname = \"jbig2dec-sys\"\nversion = \"0.1.0\"\n"))))
      (case! "jbig2dec on a build script's link line" #"emon-rs-build:\d+: a link line names an excluded library"
             #(edit! "kin/scripts/emon-rs-build" (fn [s] (str s "\nflags=\"$flags -C link-arg=-ljbig2dec\"\n"))))
      (case! "a GPL library on a build script's link line" #"emon-bind-build:\d+: a link line names an excluded library"
             #(edit! "kin/scripts/emon-bind-build" (fn [s] (str s "\nflags=\"$flags -l:libfoo-gpl.so\"\n"))))
      (case! "jbig2dec in a Rust #[link]" #"host/rust/src/pdfium.rs:\d+: a link name `jbig2dec`"
             #(edit! "host/rust/src/pdfium.rs" (fn [s] (str s "\n#[link(name = \"jbig2dec\")]\nextern \"C\" {}\n"))))
      (case! "jbig2dec in a Java library lookup" #"Bogus.java:\d+: a call name `libjbig2dec.so`"
             #(spit (path tmp "host/java/emon/ffi/Bogus.java") "class Bogus { void f() { SymbolLookup.libraryLookup(\"libjbig2dec.so\", arena); } }\n"))
      (case! "jbig2dec in a Java library file list" #"Bogus.java:\d+: a file name `libjbig2dec.dylib`"
             #(spit (path tmp "host/java/emon/ffi/Bogus.java") "class Bogus { String[] n = {\"libjbig2dec.dylib\"}; }\n"))
      (case! "jbig2dec in a C# DllImport" #"Bogus.cs:\d+: a call name `jbig2dec`"
             #(spit (path tmp "host/csharp/Emon/Ffi/Bogus.cs") "class Bogus { [DllImport(\"jbig2dec\")] static extern int f(); }\n"))
      (case! "a GPL dlopen in C" #"bogus.c:\d+: a call name `libfoo-gpl.so.1`"
             #(spit (path tmp "host/c/src/bogus.c") "void f(void) { dlopen(\"libfoo-gpl.so.1\", 0); }\n"))
      (case! "jbig2dec in a Go cgo LDFLAGS" #"bogus.go:\d+: a cgo name"
             #(spit (path tmp "host/go/bogus.go") "package emon\n// #cgo LDFLAGS: -ljbig2dec\nimport \"C\"\n"))
      (case! "the dev-only file no longer gated" #"declared :dev-only-sources but host/rust/src/lib.rs no longer gates it"
             #(edit! "host/rust/src/lib.rs" (fn [s] (str/replace s "#[cfg(codecs)]\npub mod pdfcodecs;" "pub mod pdfcodecs;"))))
      (case! "the dev-only cfg flag set by another script" #"emon-wasm-build: sets `--cfg codecs`"
             #(edit! "kin/scripts/emon-wasm-build" (fn [s] (str s "\nflags=\"$flags --cfg codecs\"\n"))))
      ;; 5b. the demo's vendored files (P11.4c)
      (case! "a vendored file that maps to no component" #"tools/demo/build.clj vendors `vendor/bogus/x.js`"
             #(edit! "tools/demo/build.clj" (fn [s] (str s "\n(copy! \"x\" (str out \"/vendor/bogus/x.js\"))\n"))))
      (case! "a demo component that the demo does not carry" #":demo-files vendor/pdfium/: native/pdfium-llvm-libc is not carried by the browser demo"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"native/pdfium-simdutf\"" "\"native/pdfium-llvm-libc\" \"native/pdfium-simdutf\""))))
      ;; 6. fonts and ICC
      (case! "a font under host/" #"host/js/src/bogus.ttf: a font or ICC profile"
             #(spit (path tmp "host/js/src/bogus.ttf") "x"))
      ;; 5. the notices
      (case! "the notices stale after an edit" #"THIRD_PARTY_NOTICES.md is stale"
             #(edit! notices-path (fn [s] (str s "\nsomeone's hand edit\n"))))
      (case! "the notices stale after an inventory edit" #"THIRD_PARTY_NOTICES.md is stale"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"https://crates.io/crates/simd-adler32\"" "\"https://example.invalid/simd-adler32\""))))
      (case! "the notices missing" #"THIRD_PARTY_NOTICES.md is missing"
             #(fs/delete (path tmp notices-path)))
      (case! "a licence text missing" #"licence text release/licenses/cargo-adler2-2.0.1.txt is missing"
             #(fs/delete (path tmp (str texts-dir "/cargo-adler2-2.0.1.txt"))))
      ;; 7. the native pins (P11.5)
      (case! "a native pin whose version the inventory does not say" #"pins zlib 1\.3\.1 but native/leptonica-zlib says"
             #(edit! inventory-path (fn [s] (str/replace-first s ":name \"zlib (Leptonica's Flate codec)\" :version \"1.3.1\"" ":name \"zlib (Leptonica's Flate codec)\" :version \"1.3.0\""))))
      (case! "Leptonica built with OpenJPEG" #"Leptonica is not built with -DENABLE_OPENJPEG=OFF"
             #(edit! "tools/native/native.edn" (fn [s] (str/replace-first s "-DENABLE_OPENJPEG=OFF" "-DENABLE_OPENJPEG=ON"))))
      (case! "libtiff built with JBIG" #"libtiff is not built with -Djbig=OFF"
             #(edit! "tools/native/native.edn" (fn [s] (str/replace-first s "\"-Djbig=OFF\"" "\"-Djbig=ON\""))))
      (case! "a jbig2dec source in the native pins" #"source jbig2dec is an excluded library"
             #(edit! "tools/native/native.edn" (fn [s] (str/replace-first s "{:id \"giflib\" :name \"giflib\"" "{:id \"jbig2dec\" :name \"giflib\""))))
      (case! "a native licence text no inventory entry names" #"native-zlib-1\.3\.1\.txt is vendored by tools/native/native.edn and no inventory"
             #(edit! inventory-path (fn [s] (str/replace-first s "\"native-zlib-1.3.1.txt\"" "\"generic-apache-2.0.txt\""))))
      (fresh!)
      (t! "regenerating after an inventory edit makes it pass again"
          (do (edit! inventory-path (fn [s] (str/replace-first s "\"https://crates.io/crates/simd-adler32\"" "\"https://example.invalid/simd-adler32\"")))
              (spit (path tmp notices-path) (notices tmp (read-inventory tmp)))
              (zero? (count (filter #(= :fail (first %)) (run-all tmp))))))
      (finally (fs/delete-tree tmp)))
    (if (seq @fails)
      (do (println "self-test FAILED:" (str/join "; " @fails)) (System/exit 1))
      (println "  ok   self-test: every failure case was caught on a temporary copy, the real tree was not touched"))))

;; ------------------------------------------------------------------------------------------------ main

(defn -main [args]
  (let [root (str (fs/absolutize "."))]
    (cond
      (empty? args)
      (let [fails (report! (run-all root))]
        (if (pos? fails)
          (do (println (str "release-audit FAILED: " fails " finding(s)")) (System/exit 1))
          (println "  ok   release-audit passed")))
      (= ["--write"] args)
      (let [inv (read-inventory root)]
        (spit (path root notices-path) (notices root inv))
        (println "  wrote" notices-path))
      (= ["--import-texts"] args) (import-texts! root)
      (= ["--self-test"] args) (self-test)
      :else (do (binding [*out* *err*] (println "usage: bb release-audit | --write | --import-texts | --self-test"))
                (System/exit 2)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
