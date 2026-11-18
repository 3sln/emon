#!/usr/bin/env bb
;; libemon for the mobile triples: Android (arm64-v8a, armeabi-v7a, x86_64) and iOS (device, simulator).   bb mobile   (DEVLOG P13.19, D897; D884, D885, D895)
;;
;;   bb mobile --triple T                build libemon over `bb native --triple T` and the pinned pdfium of that platform, assemble
;;                                       .cache/mobile/<T>/ and write dist/c/emon-c-<version>-<T>.tar.gz (the C archive of the release, D886)
;;   bb mobile --triple T --smoke        a C program (tools/native/mobile/smoke.c) built for T against the shipped library: LINKED for every triple;
;;                                       RUN where it can be: the iOS simulator (xcrun simctl), or a connected Android device or emulator of that ABI (adb)
;;   bb mobile --triple T --check        build, `bb native --audit`, smoke: what the CI jobs run
;;   bb mobile --list                    the mobile triples
;;
;; WHAT IT IS. The C library of docs/hosts/c.md (host/c, emon.h) for a phone (K2, D900): TWO dynamic libraries per triple, `libemon.so` / `libemon.dylib`, the CORE library (no OCR engine:
;; pdfium loaded at run time, the pack fetch), and `lib/emon/adapters/libemon_ocr_tesseract.*`, the Tesseract ADAPTER library with Tesseract and Leptonica (and their codecs) linked statically from
;; `bb native` and the C++ runtime statically too (Android: nothing is loaded but libc, libm and libdl, in either). An app that reads scans another way ships the core alone. pdfium is the pinned
;; pdfium-binaries library of the platform, shipped beside them and loaded at run time (host/c/src/native.rs: by file, then by name). The English and orientation models are PACKS in
;; share/emon/packs/<id>/<file>. There is NO libemon.a: Dart build hooks bundle dynamic libraries only (dart.dev/tools/hooks, code assets `DynamicLoadingBundled`), and a static Tesseract slice
;; would be a hundred megabytes of archive nobody can link a Flutter app against.
;;
;; SAME PIPELINE AS THE DESKTOP: the crate is assembled from the same sources as `bb package --host c` (tools/package.clj: gen/rust, host/rust/src, host/c/src) and
;; built with the same cfgs (`--cfg pdfium --cfg pdfium_dl`, the `tesseract` feature), the library getting the link flags ALONE (`cargo rustc --lib -- FLAGS`).
;; K2 (the split of libemon into a core and adapter libraries) adds artifacts per triple: `artifacts` below is the one list of what a triple ships.
;;
;; Needs: cargo of the rustup toolchain with the target installed (`rustup target add`), `bb native --triple T` done, `bb pdfium-fetch --platform <pdfium of T>`;
;; Android: ANDROID_NDK_HOME (r28+); iOS: Xcode. Nothing here touches the network.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint]
         '[clojure.string :as str])
(import '[java.security MessageDigest])

(def root (str (fs/absolutize ".")))
(def args (vec *command-line-args*))
(defn flag? [f] (some #{f} args))
(defn opt [f] (second (drop-while #(not= f %) args)))
(def pins (edn/read-string (slurp "tools/native/native.edn")))
(def pdfium-pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def version (:version (edn/read-string (slurp "version.edn"))))

(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))
(defn say [& xs] (println (apply str xs)) (flush))
(defn mobile-triples [] (sort (keys (filter (fn [[_ c]] (#{:android :ios} (:os c))) (:triples pins)))))
(def triple (or (opt "--triple") (when-not (flag? "--list") (die "usage: bb mobile --triple T [--smoke | --check] | --list; T is one of " (str/join ", " (mobile-triples))))))
(def cfg (when triple (or (let [c (get-in pins [:triples triple])] (when (#{:android :ios} (:os c)) c))
                          (die triple " is not a mobile triple: " (str/join ", " (mobile-triples))))))
(def android? (= :android (:os cfg)))

(defn sh
  "Run a command; {:exit :out :err}. Throws (die) with the tail of the output unless :ok? false."
  [{:keys [dir env ok?] :or {ok? true}} & cmd]
  (let [cmd (map str cmd)
        r (apply p/shell (cond-> {:out :string :err :string :continue true} dir (assoc :dir (str dir)) env (assoc :extra-env env)) cmd)
        r {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}]
    (when (and ok? (not (zero? (:exit r))))
      (die (str/join " " (take 4 cmd)) " ... failed (exit " (:exit r) ")\n" (str/join "\n" (take-last 40 (str/split-lines (str (:out r) "\n" (:err r)))))))
    r))

(defn sha256 [f]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 65536)]
    (with-open [in (io/input-stream (io/file (str f)))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))
(defn human [n] (cond (< n 10240) (str n " B") (< n (* 10 1024 1024)) (format "%.0f KiB" (/ n 1024.0)) :else (format "%.1f MiB" (/ n 1048576.0))))

;; ------------------------------------------------------------------------------------------------ toolchains
(defn host-os [] (let [os (System/getProperty "os.name")] (cond (str/includes? os "Mac") :mac (str/includes? os "Linux") :linux :else :windows)))
(defn ndk-dir []
  (or (some #(when (and % (fs/directory? %)) %) [(System/getenv "ANDROID_NDK_HOME") (System/getenv "ANDROID_NDK_ROOT")])
      (when-let [sdk (some #(when (and % (fs/directory? (str % "/ndk"))) %) [(System/getenv "ANDROID_HOME") (System/getenv "ANDROID_SDK_ROOT")])]
        (last (sort (map str (fs/list-dir (str sdk "/ndk"))))))
      (die "no Android NDK: set ANDROID_NDK_HOME (r28 or newer)")))
(defn ndk-bin [] (str (ndk-dir) "/toolchains/llvm/prebuilt/" (case (host-os) :mac "darwin" :linux "linux" (die "the NDK is used from macOS or Linux")) "-x86_64/bin"))
(defn ndk-clang-triple [] (str (case (:arch cfg) "arm64" "aarch64-linux-android" "arm" "armv7a-linux-androideabi" "x86_64" "x86_64-linux-android") (:api cfg)))
(defn xcrun [& a] (str/trim (:out (apply sh {} "xcrun" a))))
(defn cargo-env-name [t] (str/upper-case (str/replace t "-" "_")))

(def ios-sdk (:sdk cfg))
(def target-dir (str root "/.cache/mobile/" triple))
(def stage-dir (str root "/.cache/mobile/stage/" triple))
(def adapter-stage-dir (str root "/.cache/mobile/stage/" triple "-adapter"))
(def native-prefix (str root "/.cache/native/" triple))
(def pdfium-dir (when cfg (str root "/.cache/pdfium/" (str/replace (:version pdfium-pin) "/" "-") "/" (:pdfium cfg))))
(def pdfium-file (when cfg (str pdfium-dir "/" (get-in pdfium-pin [:platforms (:pdfium cfg) :lib]))))
(def lib-name (if android? "libemon.so" "libemon.dylib"))
(def adapter-name (if android? "libemon_ocr_tesseract.so" "libemon_ocr_tesseract.dylib"))
(def pdfium-name (if android? "libpdfium.so" "libpdfium.dylib"))
(def archive-name (str "emon-c-" version "-" triple))
(def archive (str root "/dist/c/" archive-name ".tar.gz"))

;; ------------------------------------------------------------------------------------------------ the crate (as `bb package --host c` assembles it)
;; keep equal to `rust-host-files` of tools/package.clj (`--check-sources` below compares them)
(def rust-host-files ["lib" "port" "plan" "source" "engine" "imgdec" "tess" "render" "pdfium" "pdfimg" "session" "par" "adapter" "packs" "tess_adapter"])

(defn package-host-files [] (some->> (slurp "tools/package.clj") (re-find #"\(def rust-host-files (\[[^\]]*\])") second edn/read-string))

(defn listing [dir ext]
  (->> (fs/list-dir (str root "/" dir)) (filter fs/regular-file?) (map #(str (fs/file-name %))) (filter #(str/ends-with? % ext))
       (map #(subs % 0 (- (count %) (count ext)))) sort))
(defn law-or-probe-lower? [m] (boolean (re-find #"(test|probe)$" m)))

(defn put-text! [dst text] (fs/create-dirs (fs/parent dst)) (when-not (and (fs/exists? dst) (= text (slurp (str dst)))) (spit (str dst) text)))
(defn put-file! [dst src]
  (fs/create-dirs (fs/parent dst))
  (when-not (and (fs/exists? dst) (= (fs/size src) (fs/size dst)) (= (sha256 src) (sha256 dst))) (fs/copy src dst {:replace-existing true})))
(defn stamp [s] (str/replace s "0.0.0-dev" version))

;; K2 (D900): TWO crates per triple, from the same sources. The CORE library (libemon: emon.h, pdfium at run time, the pack fetch, no Tesseract) and the Tesseract ADAPTER library
;; (libemon_ocr_tesseract: Tesseract and Leptonica statically, exporting only `emon_ocr_adapter_v1`).
(defn stage-crate! [kind]
  (when-not (= (package-host-files) rust-host-files) (die "tools/package.clj's rust-host-files differ from tools/native/mobile.clj's: keep them equal"))
  (let [dir (if (= kind :core) stage-dir adapter-stage-dir)
        mods (remove law-or-probe-lower? (listing "gen/rust/emon" ".rs"))
        cargo (slurp "release/pkg/rust/Cargo.toml")
        lib "[lib]\nname = \"emon\"\npath = \"src/lib.rs\"\n"
        _ (when-not (str/includes? cargo lib) (die "release/pkg/rust/Cargo.toml has no [lib] table of the expected form"))
        cargo (if (= kind :core)
                (str/replace cargo lib (str lib "crate-type = [\"cdylib\"]\n"))
                (str/replace cargo lib "[lib]\nname = \"emon_ocr_tesseract\"\npath = \"src/lib.rs\"\ncrate-type = [\"cdylib\"]\n"))
        files (merge
               (into {} (for [m mods] [(str "src/emon/" m ".rs") {:file (str "gen/rust/emon/" m ".rs")}]))
               {"src/emon.rs" {:text (str (str/join "\n" (map #(str "pub mod " % ";") mods)) "\n")}}
               (into {} (for [f rust-host-files :when (not= f "lib")] [(str "src/" f ".rs") {:file (str "host/rust/src/" f ".rs")}]))
               (if (= kind :core)
                 {"src/lib.rs" {:text (str (str/replace (slurp "host/rust/src/lib.rs") "PPOCR_NATIVE_HOST: bool = false" "PPOCR_NATIVE_HOST: bool = true")
                                           "\n// the C binding (P9.4), assembled in by tools/native/mobile.clj from host/c/src/capi.rs\npub mod capi;\n")}
                  "src/capi.rs" {:file "host/c/src/capi.rs"} "src/native.rs" {:file "host/c/src/native.rs"}}
                 {"src/lib.rs" {:text (slurp "host/rust/src/lib.rs")}})
               {"Cargo.toml" {:text cargo} "Cargo.lock" {:file "host/rust/Cargo.lock"}})]
    (fs/create-dirs dir)
    (doseq [[rel src] files :let [dst (str dir "/" rel)]]
      (if (:text src) (put-text! dst (stamp (:text src)))
          (if (re-find #"\.(rs|toml|lock)$" rel) (put-text! dst (stamp (slurp (:file src)))) (put-file! dst (:file src)))))
    ;; the sources of the stage are exactly these (a removed module must not linger)
    (doseq [f (fs/glob (str dir "/src") "**") :when (fs/regular-file? f)
            :when (not (contains? files (str (fs/relativize dir f))))]
      (fs/delete f))))

;; ------------------------------------------------------------------------------------------------ the library
;; The rustc arguments of a library. COMMON to both: stripped, named, and (Android) 16 KB aligned with the archives' own symbols unexported. The CORE library has pdfium loaded at
;; run time and no link flags (it needs nothing but libc, libm and libdl). The ADAPTER has the static Tesseract, Leptonica and codecs of `bb native`, and, on Android, the NDK's static
;; C++ runtime named last (rustc links with -nodefaultlibs, so the driver's own `-static-libstdc++` adds nothing; the audit fails a library that leaves a C++ runtime symbol undefined).
(defn rustc-flags [kind]
  (let [link (str/split (str/trim (slurp (str native-prefix "/link.rustflags"))) #"\s+")
        so (if (= kind :core) "libemon" "libemon_ocr_tesseract")]
    (concat (if (= kind :core) ["--cfg" "pdfium" "--cfg" "pdfium_dl" "-C" "strip=symbols"] ["-C" "strip=symbols"])
            (if android?
              ["-C" (str "link-arg=-Wl,-soname," so ".so") "-C" "link-arg=-Wl,-z,max-page-size=16384" "-C" "link-arg=-Wl,-z,common-page-size=16384"
               "-C" "link-arg=-Wl,--exclude-libs,ALL" "-C" "link-arg=-Wl,--build-id=none"]
              ["-C" (str "link-arg=-Wl,-install_name,@rpath/" so ".dylib")])
            (when (= kind :adapter) link)
            (when (and android? (= kind :adapter)) ["-l" "static=c++_static" "-l" "static=c++abi"]))))

(defn build-crate! [kind]
  (let [need (fn [f why] (when-not (fs/exists? f) (die f " is missing: " why)))]
    (need (str native-prefix "/link.rustflags") (str "run `bb native --triple " triple "`"))
    (need (str pdfium-dir "/.verified") (str "run `bb pdfium-fetch --platform " (:pdfium cfg) "`"))
    (let [dir (if (= kind :core) stage-dir adapter-stage-dir)
          env (cond-> {"CARGO_TARGET_DIR" (str dir "/target")}
                android? (assoc (str "CARGO_TARGET_" (cargo-env-name (:rust cfg)) "_LINKER") (str (ndk-bin) "/" (ndk-clang-triple) "-clang++"))
                (not android?) (assoc "IPHONEOS_DEPLOYMENT_TARGET" (:ios-deployment cfg)))
          have (str/split-lines (:out (sh {} "rustup" "target" "list" "--installed")))]
      (when-not (some #{(:rust cfg)} (map str/trim have)) (die "the Rust target " (:rust cfg) " is not installed in the active toolchain: rustup target add " (:rust cfg)))
      (stage-crate! kind)
      (say "cargo rustc " (name kind) " --target " (:rust cfg) " (" (count (rustc-flags kind)) " rustc arguments)")
      (let [features (if (= kind :core) "packs-fetch" "tesseract-adapter")
            cmd (fn [offline] (concat ["cargo" "rustc" "--release" "--lib" "--locked" "--target" (:rust cfg) "--features" features] offline ["--"] (rustc-flags kind)))
            r (let [r (apply sh {:dir dir :env env :ok? false} (cmd ["--offline"]))] (if (zero? (:exit r)) r (apply sh {:dir dir :env env} (cmd []))))]
        (when-not (zero? (:exit r)) (die "cargo rustc failed")))
      (let [built (str dir "/target/" (:rust cfg) "/release/" (if (= kind :core) lib-name adapter-name))]
        (when-not (fs/exists? built) (die "cargo made no " built))
        built))))

(defn build-lib! [] (build-crate! :core))

;; ------------------------------------------------------------------------------------------------ what a triple ships
;; ONE list: {path in the archive -> source}. K2 (core + adapter libraries) adds its libraries here; the archive, the audit and the sizes follow.
(def packs-catalogue (delay (edn/read-string (slurp "release/packs/packs.edn"))))
(defn pack-dir-of [file]
  (or (some (fn [p] (when (and (= :tesseract (:engine p)) (some #(= file (:name %)) (:files p))) (:id p))) (:packs @packs-catalogue))
      (die "the pack catalogue has no Tesseract pack with the file " file)))

(defn artifacts [{:keys [lib adapter]}]
  (merge {(str "lib/" lib-name) lib
          (str "lib/emon/adapters/" adapter-name) adapter
          (str "lib/" pdfium-name) pdfium-file
          "include/emon.h" (str root "/host/c/include/emon.h")
          "include/emon_adapter.h" (str root "/host/c/include/emon_adapter.h")
          "LICENSE" (str root "/LICENSE")
          "THIRD_PARTY_NOTICES.md" (str root "/THIRD_PARTY_NOTICES.md")
          "README.md" (str root "/release/pkg/README.md")}
         ;; the models are PACKS (D885, D900): share/emon/packs/<id>/<file>, where emon_engines_defaults looks
         (into {} (for [d (:data pins)] [(str "share/emon/packs/" (pack-dir-of (:file d)) "/" (:file d)) (str native-prefix "/share/tessdata/" (:file d))]))))

(defn assemble! [libs]
  (fs/delete-tree target-dir)
  (doseq [[rel src] (artifacts libs) :when (re-find #"^(lib|include|share)/" rel)]
    (put-file! (str target-dir "/" rel) src))
  ;; Strip what the linker kept (rustc's `strip=symbols` leaves the section names of Android's .comment note): the audit and the sizes are of this file
  (when android? (doseq [f [(str "lib/" lib-name) (str "lib/emon/adapters/" adapter-name)]] (sh {} (str (ndk-bin) "/llvm-strip") "--strip-unneeded" (str target-dir "/" f))))
  (spit (str target-dir "/manifest.edn")
        (with-out-str
          (clojure.pprint/pprint
           {:triple triple :version version
            :toolchain (merge {:rustc (str/trim (:out (sh {} "rustc" "--version")))}
                              (if android? {:ndk (second (re-find #"(?m)^Pkg\.Revision\s*=\s*(\S+)" (slurp (str (ndk-dir) "/source.properties")))) :api (:api cfg) :abi (:abi cfg)}
                                  {:sdk ios-sdk :xcode (xcrun "--sdk" ios-sdk "--show-sdk-version") :ios-deployment (:ios-deployment cfg)}))
            :files (vec (for [f (sort (map str (filter fs/regular-file? (fs/glob target-dir "**"))))
                              :let [rel (str (fs/relativize target-dir f))] :when (not= rel "manifest.edn")]
                          {:file rel :bytes (fs/size f) :sha256 (sha256 f)}))}))))

(defn pack! [libs]
  (let [tmp (str root "/.cache/mobile/stage/" triple "-archive") d (str tmp "/" archive-name)]
    (fs/delete-tree tmp)
    (doseq [[rel src] (artifacts libs)]
      (fs/create-dirs (fs/parent (str d "/" rel)))
      (fs/copy src (str d "/" rel) {:replace-existing true :copy-attributes true}))
    (fs/create-dirs (fs/parent archive))
    (sh {:dir tmp} "tar" "-czf" archive archive-name)
    (fs/delete-tree tmp)
    archive))

(defn report-sizes! []
  (say "sizes of " triple ":")
  (doseq [f (sort (map str (filter fs/regular-file? (fs/glob target-dir "**"))))]
    (say (format "  %-34s %10s" (str (fs/relativize target-dir f)) (human (fs/size f)))))
  (say (format "  %-34s %10s" (str "dist/c/" archive-name ".tar.gz") (human (fs/size archive)))))

(defn build! []
  (let [t0 (System/currentTimeMillis)
        libs {:lib (build-crate! :core) :adapter (build-crate! :adapter)}]
    (assemble! libs)
    (pack! libs)
    (say (format "built %s in %.0f s" triple (/ (- (System/currentTimeMillis) t0) 1000.0)))
    (report-sizes!)))

;; ------------------------------------------------------------------------------------------------ the smoke test
(def smoke-src (str root "/tools/native/mobile/smoke.c"))
(def smoke-dir (str root "/.cache/mobile/smoke/" triple))
(def pdf-in (str root "/samples/align-justify.pdf"))
(def golden (str root "/samples/align-justify.html"))
(def scan-in (str root "/samples/scan-plain.pdf"))
(def scan-word "Adaptive Sampling")

(defn compile-smoke! []
  (fs/create-dirs smoke-dir)
  (let [exe (str smoke-dir "/smoke") std ["-std=c11" "-Wall" "-Werror" (str "-I" target-dir "/include")]]
    (if android?
      (apply sh {} (concat [(str (ndk-bin) "/" (ndk-clang-triple) "-clang")] std ["-o" exe smoke-src (str "-L" target-dir "/lib") "-lemon"]))
      (apply sh {} (concat ["xcrun" "--sdk" ios-sdk "clang" "-arch" (:arch cfg)
                            (if (= "iphoneos" ios-sdk) (str "-miphoneos-version-min=" (:ios-deployment cfg)) (str "-mios-simulator-version-min=" (:ios-deployment cfg)))]
                           std ["-o" exe smoke-src (str "-L" target-dir "/lib") "-lemon" (str "-Wl,-rpath," target-dir "/lib")])))
    exe))

(defn linked-ok! [exe]
  ;; the link itself resolved every emon_* symbol the program calls against the shipped library; say what it records
  (let [deps (if android? (:out (sh {} (str (ndk-bin) "/llvm-readelf") "-d" exe)) (:out (sh {} "otool" "-L" exe)))]
    (when-not (str/includes? deps "libemon") (die "the smoke program does not name libemon:\n" deps))
    (say "  linked   " triple ": smoke.c against " lib-name " (" (human (fs/size exe)) ")")))

(defn check-html! [what out golden-text]
  (when-not (fs/exists? out) (die what ": no output"))
  (when-not (= golden-text (slurp out)) (die what ": the HTML is not the golden's")))

;; --- the iOS simulator: boot a device of the newest iOS runtime, run the program inside it
(defn sim-runtime []
  (let [rs (-> (sh {} "xcrun" "simctl" "list" "runtimes" "available" "-j") :out (json/parse-string true) :runtimes)]
    (last (sort-by :version (filter #(and (str/starts-with? (str (:identifier %)) "com.apple.CoreSimulator.SimRuntime.iOS") (:isAvailable %)) rs)))))

(defn run-sim! [exe]
  (if-let [rt (sim-runtime)]
    (let [name (str "emon-k6-" (System/currentTimeMillis))
          types (->> (-> (sh {} "xcrun" "simctl" "list" "devicetypes" "-j") :out (json/parse-string true) :devicetypes)
                     (filter #(re-find #"iPhone" (:name %))) (map :identifier))
          udid (str/trim (:out (sh {} "xcrun" "simctl" "create" name (or (first (filter #(re-find #"iPhone-16$" %) types)) (first types)) (:identifier rt))))]
      (try
        (sh {} "xcrun" "simctl" "boot" udid)
        (sh {} "xcrun" "simctl" "bootstatus" udid)
        (say "  simulator " (:name rt) " (" (:buildversion rt) ") booted: " udid)
        (let [spawn (fn [& a] (apply sh {:ok? false} "xcrun" "simctl" "spawn" udid a))
              out (str smoke-dir "/align-justify.out.html")
              r (spawn exe pdf-in out)]
          (when-not (zero? (:exit r)) (die "the program failed in the simulator (exit " (:exit r) "): " (:out r) (:err r)))
          (say "  ran      " (str/trim (:out r)))
          (check-html! "born-digital sample in the simulator" out (slurp golden))
          (say "  ok       samples/align-justify.pdf -> HTML byte for byte the golden, inside the iOS simulator")
          (let [sout (str smoke-dir "/scan.out.html")
                r (spawn exe scan-in sout "native")]
            (when-not (zero? (:exit r)) (die "the native (pdfium + the Tesseract adapter) run failed in the simulator (exit " (:exit r) "): " (:out r) (:err r)))
            (say "  ran      " (str/trim (:out r)))
            (when-not (str/includes? (slurp sout) scan-word) (die "the scan's HTML lacks " (pr-str scan-word) ": pdfium or Tesseract did not read it"))
            (say "  ok       samples/scan-plain.pdf read by pdfium and Tesseract inside the iOS simulator: the heading " (pr-str scan-word) " is there")))
        true
        (finally (sh {:ok? false} "xcrun" "simctl" "shutdown" udid) (sh {:ok? false} "xcrun" "simctl" "delete" udid))))
    (do (say "  NOTE     no iOS simulator runtime is installed (xcodebuild -downloadPlatform iOS): the simulator program was linked, not run") false)))

;; --- a connected Android device or emulator of the right ABI (adb)
(defn adb [& a] (apply sh {:ok? false} (str (or (System/getenv "ANDROID_HOME") (System/getenv "ANDROID_SDK_ROOT")) "/platform-tools/adb") a))
(defn run-android! [exe]
  (let [serial (System/getenv "EMON_ADB_SERIAL")
        devs (->> (str/split-lines (:out (adb "devices"))) rest (keep #(second (re-find #"^(\S+)\s+device$" %))) vec)
        ser (or serial (first devs))]
    (if-not ser
      (do (say "  NOTE     no Android device or emulator is connected: the " (:abi cfg) " program was linked, not run") false)
      (let [abis (str/trim (:out (adb "-s" ser "shell" "getprop" "ro.product.cpu.abilist")))]
        (if-not (some #{(:abi cfg)} (str/split abis #","))
          (do (say "  NOTE     " ser " runs " abis ", not " (:abi cfg) ": linked, not run") false)
          (let [dir "/data/local/tmp/emon-k6"
                push (fn [src dst] (let [r (adb "-s" ser "push" (str src) dst)] (when-not (zero? (:exit r)) (die "adb push " src ": " (:out r) (:err r)))))
                shell (fn [cmd] (adb "-s" ser "shell" (str "cd " dir " && LD_LIBRARY_PATH=" dir "/lib " cmd "; echo EXIT=$?")))]
            (adb "-s" ser "shell" (str "rm -rf " dir " && mkdir -p " dir "/lib " dir "/share/emon"))
            (doseq [f ["libemon.so" "libpdfium.so"]] (push (str target-dir "/lib/" f) (str dir "/lib/")))
            (adb "-s" ser "shell" (str "mkdir -p " dir "/lib/emon"))
            (push (str target-dir "/lib/emon/adapters") (str dir "/lib/emon/"))
            (push (str target-dir "/share/emon/packs") (str dir "/share/emon/"))
            (push exe (str dir "/smoke")) (push pdf-in (str dir "/in.pdf")) (push scan-in (str dir "/scan.pdf"))
            (let [r (shell "./smoke in.pdf out.html")]
              (say "  ran      " (str/trim (str/replace (:out r) "EXIT=0" "")) " (" ser ", page size " (str/trim (:out (adb "-s" ser "shell" "getconf" "PAGE_SIZE"))) ")")
              (when-not (str/includes? (:out r) "EXIT=0") (die "the program failed on " ser ": " (:out r)))
              (adb "-s" ser "pull" (str dir "/out.html") (str smoke-dir "/android.out.html"))
              (check-html! "born-digital sample on the device" (str smoke-dir "/android.out.html") (slurp golden))
              (say "  ok       samples/align-justify.pdf -> HTML byte for byte the golden, on " ser " (" (:abi cfg) ")"))
            (let [r (shell "./smoke scan.pdf scan.html native")]
              (say "  ran      " (str/trim (:out r)))
              (when-not (str/includes? (:out r) "EXIT=0") (die "the native run failed on " ser ": " (:out r)))
              (adb "-s" ser "pull" (str dir "/scan.html") (str smoke-dir "/android.scan.html"))
              (when-not (str/includes? (slurp (str smoke-dir "/android.scan.html")) scan-word) (die "the scan's HTML lacks " (pr-str scan-word)))
              (say "  ok       samples/scan-plain.pdf read by pdfium and Tesseract on " ser))
            (adb "-s" ser "shell" (str "rm -rf " dir))
            true))))))

(defn smoke! []
  (when-not (fs/exists? (str target-dir "/lib/" lib-name)) (die target-dir "/lib/" lib-name " is not built: bb mobile --triple " triple))
  (let [exe (compile-smoke!)]
    (linked-ok! exe)
    (cond android? (run-android! exe)
          (= "iphonesimulator" ios-sdk) (run-sim! exe)
          :else (do (say "  NOTE     an iOS device library cannot run here: linked, not run") false))))

(cond
  (flag? "--list") (doseq [t (mobile-triples)] (println t "  " (select-keys (get-in pins [:triples t]) [:runner :pdfium])))
  (flag? "--smoke") (smoke!)
  (flag? "--check") (do (build!)
                        (let [r (sh {:ok? false} "bb" "tools/native/native.clj" "--audit" "--triple" triple)]
                          (print (:out r)) (print (:err r)) (flush)
                          (when-not (zero? (:exit r)) (die "bb native --audit failed on the library")))
                        (smoke!))
  :else (build!))
