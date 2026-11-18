#!/usr/bin/env bb
;; libemon_ocr_vision: the Apple Vision adapter library for an Apple triple.   bb tools/native/vision_adapter.clj --triple T [--out DIR]   (K8, D900)
;;
;; The adapter (host/rust/src/vision_adapter.rs, emon_adapter.h's one export `emon_ocr_adapter_v1`) over the Rust port (host/rust/src/vision.rs) and the Objective-C shim
;; (host/vision/emon_vision.m, tools/native/vision.clj). It needs no model, no pack and no third-party library: it links only the system's Vision, Foundation, CoreGraphics and ImageIO
;; frameworks, libobjc and libSystem. The crate is the one `bb package` and `bb mobile` assemble (the generated modules and host/rust/src, with the `vision-adapter` feature), staged
;; under `.cache/vision/stage/<T>`; the library gets the link flags ALONE (`cargo rustc --lib -- FLAGS`), like the Tesseract adapter's.
;;
;; Triples: aarch64-apple-darwin, x86_64-apple-darwin (the Intel build is cross-compiled from Apple silicon, `rustup target add x86_64-apple-darwin`), aarch64-apple-ios (device)
;; and aarch64-apple-ios-sim (the Apple-silicon simulator). Prints the path of the library. Needs the toolchain of `bb native` (Xcode; Rust 1.92.0 with the target installed).
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])
(import '[java.security MessageDigest])

(def root (str (fs/canonicalize ".")))
(def args (vec *command-line-args*))
(defn opt [f] (second (drop-while #(not= f %) args)))
(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))
(def pins (edn/read-string (slurp (str root "/tools/native/native.edn"))))
(def version (:version (edn/read-string (slurp (str root "/version.edn")))))
(def triple (or (opt "--triple") (die "usage: bb tools/native/vision_adapter.clj --triple T [--out DIR]")))
(def cfg (or (get-in pins [:triples triple]) (die "no such triple: " triple)))
(def ios? (= :ios (:os cfg)))
(when-not (#{:mac :ios} (:os cfg)) (die triple " is not an Apple triple"))
(def rust-target (or (:rust cfg) triple))
(def stage (str root "/.cache/vision/stage/" triple))
(def out (str (or (opt "--out") (str root "/.cache/vision/lib")) "/" triple))
(def lib-name "libemon_ocr_vision.dylib")

(defn sh [{:keys [dir env ok?] :or {ok? true}} & cmd]
  (let [r (apply p/shell (cond-> {:out :string :err :string :continue true} dir (assoc :dir (str dir)) env (assoc :extra-env env)) (map str cmd))]
    (when (and ok? (not (zero? (:exit r)))) (die (str/join " " (take 4 cmd)) " failed (exit " (:exit r) ")\n" (str/join "\n" (take-last 40 (str/split-lines (str (:out r) "\n" (:err r)))))))
    {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}))

(defn sha256 [f]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 65536)]
    (with-open [in (clojure.java.io/input-stream (clojure.java.io/file (str f)))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))
(defn put-text! [dst text] (fs/create-dirs (fs/parent dst)) (when-not (and (fs/exists? dst) (= text (slurp (str dst)))) (spit (str dst) text)))
(defn stamp [s] (str/replace s "0.0.0-dev" version))

;; the host files: the list `tools/package.clj` holds (and `tools/native/mobile.clj` copies), so a module added there reaches this crate
(def host-files (->> (slurp (str root "/tools/package.clj")) (re-find #"\(def rust-host-files (\[[^\]]*\])") second edn/read-string))
(defn listing [dir ext]
  (->> (fs/list-dir (str root "/" dir)) (filter fs/regular-file?) (map #(str (fs/file-name %))) (filter #(str/ends-with? % ext))
       (map #(subs % 0 (- (count %) (count ext)))) sort))

(defn stage-crate! []
  (let [mods (remove #(re-find #"(test|probe)$" %) (listing "gen/rust/emon" ".rs"))
        cargo (slurp (str root "/release/pkg/rust/Cargo.toml"))
        lib "[lib]\nname = \"emon\"\npath = \"src/lib.rs\"\n"
        _ (when-not (str/includes? cargo lib) (die "release/pkg/rust/Cargo.toml has no [lib] table of the expected form"))
        cargo (str/replace cargo lib "[lib]\nname = \"emon_ocr_vision\"\npath = \"src/lib.rs\"\ncrate-type = [\"cdylib\"]\n")
        files (merge (into {} (for [m mods] [(str "src/emon/" m ".rs") (str root "/gen/rust/emon/" m ".rs")]))
                     {"src/emon.rs" {:text (str (str/join "\n" (map #(str "pub mod " % ";") mods)) "\n")}}
                     (into {} (for [f host-files] [(str "src/" f ".rs") (str root "/host/rust/src/" f ".rs")]))
                     {"Cargo.toml" {:text cargo} "Cargo.lock" (str root "/host/rust/Cargo.lock")})]
    (fs/create-dirs stage)
    (doseq [[rel src] files :let [dst (str stage "/" rel)]]
      (put-text! dst (stamp (if (map? src) (:text src) (slurp src)))))
    (doseq [f (fs/glob (str stage "/src") "**") :when (and (fs/regular-file? f) (not (contains? files (str (fs/relativize stage f)))))] (fs/delete f))))

(defn build! []
  (let [shim-dir (str/trim (:out (sh {} "bb" "tools/native/vision.clj" "--triple" triple)))
        shim-flags (str/split (str/trim (slurp (str shim-dir "/link.rustflags"))) #"\s+")
        have (map str/trim (str/split-lines (:out (sh {} "rustup" "target" "list" "--installed"))))]
    (when-not (some #{rust-target} have) (die "the Rust target " rust-target " is not installed in the active toolchain: rustup target add " rust-target))
    (stage-crate!)
    (let [env (cond-> {"CARGO_TARGET_DIR" (str stage "/target")}
                ios? (assoc "IPHONEOS_DEPLOYMENT_TARGET" (:ios-deployment cfg))
                (not ios?) (assoc "MACOSX_DEPLOYMENT_TARGET" (:osx-deployment cfg)))
          flags (concat ["-C" "strip=symbols" "-C" "link-arg=-Wl,-install_name,@rpath/libemon_ocr_vision.dylib"] shim-flags)
          cmd (fn [offline] (concat ["cargo" "rustc" "--release" "--lib" "--locked" "--target" rust-target "--features" "vision-adapter"] offline ["--"] flags))
          r (let [r (apply sh {:dir stage :env env :ok? false} (cmd ["--offline"]))] (if (zero? (:exit r)) r (apply sh {:dir stage :env env} (cmd []))))
          built (str stage "/target/" rust-target "/release/" lib-name)]
      (when-not (fs/exists? built) (die "cargo made no " built (:err r)))
      (fs/create-dirs out)
      (fs/copy built (str out "/" lib-name) {:replace-existing true})
      (str out "/" lib-name))))

(defn exports [f]
  (->> (str/split-lines (:out (sh {:ok? false} "nm" "-gU" f))) (keep #(second (re-find #"\sT\s+_?(\S+)$" %))) set))
(defn needed [f]
  (->> (rest (str/split-lines (:out (sh {} "otool" "-L" f)))) (keep #(second (re-find #"^\s+(\S+)" %))) (remove #(str/ends-with? % (str "/" lib-name))) vec))

;; THE AUDIT (what `bb native --audit` holds libemon to, for this library): one architecture, the platform and minimum OS of the triple, the install name, only system libraries
;; loaded (the Vision framework is one), and exactly the one export of emon_adapter.h.
(defn audit [f]
  (let [ver (fn [v] (mapv #(Integer/parseInt %) (str/split (str v) #"\.")))
        arch (:arch cfg)
        archs (str/trim (:out (sh {} "lipo" "-archs" f)))
        vt (:out (sh {} "vtool" "-show-build" f))
        plat (second (re-find #"platform (\S+)" vt)) minos (second (re-find #"minos (\S+)" vt))
        want-plat (cond (not ios?) "MACOS" (= "iphoneos" (:sdk cfg)) "IOS" :else "IOSSIMULATOR")
        want-minos (if ios? (:ios-deployment cfg) (:osx-deployment cfg))
        idname (str/trim (second (str/split-lines (:out (sh {} "otool" "-D" f)))))
        ex (exports f) deps (needed f)]
    (when-not (= arch archs) (die f ": architectures " (pr-str archs) ", not " arch " alone"))
    (when-not (= want-plat plat) (die f ": platform " plat ", not " want-plat))
    (when (pos? (compare (ver minos) (ver want-minos))) (die f ": minos " minos " is above " want-minos))
    (when-not (= (str "@rpath/" lib-name) idname) (die f ": install name " (pr-str idname)))
    (when-not (= #{"emon_ocr_adapter_v1"} ex) (die f " exports " (pr-str (sort ex)) ", not exactly emon_ocr_adapter_v1"))
    (when-let [bad (seq (remove #(or (str/starts-with? % "/usr/lib/") (str/starts-with? % "/System/Library/")) deps))]
      (die f " depends on a library outside the system's: " (pr-str bad)))
    (when-not (some #(str/ends-with? % "/Vision") deps) (die f " does not load the Vision framework: " (pr-str deps)))
    (binding [*out* *err*]
      (println (str "  ok   " (fs/file-name f) ": " archs ", platform " plat ", minos " minos ", install name " idname ", exports exactly emon_ocr_adapter_v1, loads only "
                    (str/join ", " (map #(fs/file-name %) deps)))))))

(let [lib (build!)]
  (audit lib)
  (println lib))
