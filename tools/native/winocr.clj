#!/usr/bin/env bb
;; libemon_ocr_winocr: the Windows.Media.Ocr adapter library.   bb winocr [--check | --cargo-check | --smoke] [--triple T] [--out DIR]   (K8, D901)
;;
;;   bb winocr --cargo-check   TYPE-CHECK the adapter for Windows from any host (`cargo check --target x86_64-pc-windows-msvc --features winocr-adapter`; the target's standard library is
;;                             `rustup target add x86_64-pc-windows-msvc`). Nothing links and nothing runs: this is what can be shown off Windows, and what the CI job's build repeats
;;   bb winocr                 (Windows) build `emon_ocr_winocr.dll` (the C runtime linked statically, as emon.dll and emon.exe are: no VC++ redistributable) into DIR (default
;;                             .cache/winocr/lib/<triple>) and AUDIT it: exactly one export, `emon_ocr_adapter_v1`, and no import of a C or C++ runtime DLL (`dumpbin`, from the MSVC
;;                             environment the workflow sets up)
;;   bb winocr --smoke         (Windows) build the reference driver with the `winocr` feature (`EMON_RS_WINOCR=1 kin/scripts/emon-rs-build`) and convert samples/scan-plain.pdf with
;;                             `ocr-engine=windows-media-ocr`: the heading `Adaptive Sampling` must be in the HTML. It needs an English OCR language pack
;;   bb winocr --check         both, what the `winocr` job of .github/workflows/native.yml runs
;;
;; The adapter is Rust over the `windows` crate (host/rust/src/winocr.rs, winocr_adapter.rs): the system's own WinRT API, no model, no pack, no network. It cannot run anywhere but Windows
;; 10 or later, and its answers can only be recorded there: a recording is replayed on every host (`bb ocr`), so the engine is needed once, not by every conversion.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str]
         '[process-env :as process-env])

(def root (str (fs/canonicalize ".")))
(def args (vec *command-line-args*))
(defn opt [f d] (or (second (drop-while #(not= % f) args)) d))
(defn flag? [f] (some #{f} args))
(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))
(defn say [& xs] (println (apply str xs)) (flush))
(def version (:version (edn/read-string (slurp (str root "/version.edn")))))
(def triple (opt "--triple" "x86_64-pc-windows-msvc"))
(def windows? (str/includes? (System/getProperty "os.name") "Windows"))
(def stage (str root "/.cache/winocr/stage/" triple))
(def out (str (opt "--out" (str root "/.cache/winocr/lib")) "/" triple))
(def lib-name "emon_ocr_winocr.dll")

(defn sh [{:keys [dir env ok?] :or {ok? true}} & cmd]
  (let [r (apply p/shell (cond-> (merge {:out :string :err :string :continue true} (process-env/options env))
                           dir (assoc :dir (str dir)))
                 (map str cmd))]
    (when (and ok? (not (zero? (:exit r)))) (die (str/join " " (take 4 cmd)) " failed (exit " (:exit r) ")\n" (str/join "\n" (take-last 40 (str/split-lines (str (:out r) "\n" (:err r)))))))
    {:exit (:exit r) :out (str (:out r)) :err (str (:err r))}))

(defn put-text! [dst text] (fs/create-dirs (fs/parent dst)) (when-not (and (fs/exists? dst) (= text (slurp (str dst)))) (spit (str dst) text)))
(defn stamp [s] (str/replace s "0.0.0-dev" version))
(def host-files (->> (slurp (str root "/tools/package.clj")) (re-find #"\(def rust-host-files (\[[^\]]*\])") second edn/read-string))
(defn listing [dir ext]
  (->> (fs/list-dir (str root "/" dir)) (filter fs/regular-file?) (map #(str (fs/file-name %))) (filter #(str/ends-with? % ext))
       (map #(subs % 0 (- (count %) (count ext)))) sort))

;; the crate the packages assemble (gen/rust/emon, host/rust/src), with the adapter's library name and crate type
(defn stage-crate! []
  (let [mods (remove #(re-find #"(test|probe)$" %) (listing "gen/rust/emon" ".rs"))
        cargo (slurp (str root "/release/pkg/rust/Cargo.toml"))
        lib "[lib]\nname = \"emon\"\npath = \"src/lib.rs\"\n"
        _ (when-not (str/includes? cargo lib) (die "release/pkg/rust/Cargo.toml has no [lib] table of the expected form"))
        cargo (str/replace cargo lib "[lib]\nname = \"emon_ocr_winocr\"\npath = \"src/lib.rs\"\ncrate-type = [\"cdylib\", \"rlib\"]\n")
        files (merge (into {} (for [m mods] [(str "src/emon/" m ".rs") (str root "/gen/rust/emon/" m ".rs")]))
                     {"src/emon.rs" {:text (str (str/join "\n" (map #(str "pub mod " % ";") mods)) "\n")}}
                     (into {} (for [f host-files] [(str "src/" f ".rs") (str root "/host/rust/src/" f ".rs")]))
                     {"Cargo.toml" {:text cargo} "Cargo.lock" (str root "/host/rust/Cargo.lock")})]
    (fs/create-dirs stage)
    (doseq [[rel src] files :let [dst (str stage "/" rel)]] (put-text! dst (stamp (if (map? src) (:text src) (slurp src)))))
    (doseq [f (fs/glob (str stage "/src") "**") :when (and (fs/regular-file? f) (not (contains? files (str/replace (str (fs/relativize stage f)) "\\" "/"))))] (fs/delete f))))

(defn cargo! [& cmd-after]
  (let [go (fn [offline] (apply sh {:dir stage :env {"CARGO_TARGET_DIR" (str stage "/target")} :ok? false} (concat ["cargo"] (take 1 cmd-after) ["--locked"] offline (drop 1 cmd-after))))
        r (go ["--offline"])]
    (if (zero? (:exit r)) r (let [r2 (go [])] (when-not (zero? (:exit r2)) (die "cargo failed:\n" (str/join "\n" (take-last 60 (str/split-lines (str (:out r2) "\n" (:err r2))))))) r2))))

(defn cargo-check! []
  (when-not (some #{triple} (map str/trim (str/split-lines (:out (sh {} "rustup" "target" "list" "--installed")))))
    (die "the Rust target " triple " is not installed: rustup target add " triple))
  (stage-crate!)
  (cargo! "check" "--lib" "--target" triple "--features" "winocr-adapter")
  (say "  ok   winocr: libemon_ocr_winocr type-checks for " triple " (cargo check --features winocr-adapter)"))

(defn build! []
  (when-not windows? (die "bb winocr builds the DLL on Windows; --cargo-check type-checks it anywhere"))
  (stage-crate!)
  (let [r (sh {:dir stage :env {"CARGO_TARGET_DIR" (str stage "/target")} :ok? false}
              "cargo" "rustc" "--release" "--lib" "--locked" "--features" "winocr-adapter" "--" "-C" "target-feature=+crt-static")
        built (str stage "/target/release/" lib-name)]
    (when-not (zero? (:exit r)) (die "cargo rustc failed\n" (str/join "\n" (take-last 40 (str/split-lines (str (:out r) "\n" (:err r)))))))
    (when-not (fs/exists? built) (die "cargo made no " built))
    (fs/create-dirs out)
    (fs/copy built (str out "/" lib-name) {:replace-existing true})
    (str out "/" lib-name)))

;; THE AUDIT: one export, and no C or C++ runtime DLL among the imports (the runtime is linked in: /MT)
(defn audit! [f]
  (let [ex (:out (sh {} "dumpbin" "/exports" f))
        names (->> (str/split-lines ex) (keep #(second (re-find #"^\s+\d+\s+[0-9A-F]+\s+[0-9A-F]+\s+(\S+)" %))) set)
        deps (:out (sh {} "dumpbin" "/dependents" f))
        dlls (->> (str/split-lines deps) (keep #(second (re-find #"^\s+(\S+\.dll)\s*$" %))) (map str/lower-case) vec)
        bad (filter #(re-find #"^(vcruntime|msvcp|vcomp|concrt|msvcr|ucrtbased)" %) dlls)]
    (when-not (= #{"emon_ocr_adapter_v1"} names) (die f " exports " (pr-str (sort names)) ", not exactly emon_ocr_adapter_v1"))
    (when (seq bad) (die f " imports a C or C++ runtime DLL: " (pr-str bad) " (the runtime must be linked statically)"))
    (say "  ok   " (fs/file-name f) ": exports exactly emon_ocr_adapter_v1, imports only Windows' own DLLs (" (str/join ", " (take 12 dlls)) (when (> (count dlls) 12) ", ...") ")")))

;; THE SMOKE: a conversion of one fixture with ocr-engine=windows-media-ocr
(defn smoke! []
  (when-not windows? (die "the smoke runs on Windows"))
  (let [r (sh {:env {"EMON_RS_WINOCR" "1"}} "sh" "kin/scripts/emon-rs-build")
        bin (str/trim (:out r))
        html (str (fs/create-temp-dir {:prefix "emon-winocr."}) "/scan.html")
        c (sh {:ok? false} bin "--ocr-engine=windows-media-ocr" (str "--out=html") "samples/scan-plain.pdf")]
    (when-not (zero? (:exit c))
      (die "the conversion failed (exit " (:exit c) "): " (str/trim (:err c)) "\nIs an OCR language pack installed? Add-WindowsCapability -Online -Name Language.OCR~~~en-US~0.0.1.0"))
    (spit html (:out c))
    (when-not (str/includes? (:out c) "Adaptive Sampling") (die "the HTML does not hold the scan's heading \"Adaptive Sampling\": Windows.Media.Ocr did not read it"))
    (say "  ok   samples/scan-plain.pdf read by Windows.Media.Ocr (ocr-engine=windows-media-ocr): the heading \"Adaptive Sampling\" is in the HTML (" (count (:out c)) " bytes)")))

(cond
  (flag? "--cargo-check") (cargo-check!)
  (flag? "--smoke") (smoke!)
  (flag? "--check") (do (cargo-check!) (audit! (build!)) (smoke!))
  :else (let [f (build!)] (audit! f) (println f)))
