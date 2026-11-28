#!/usr/bin/env bb
;; Integrated optional-port contracts: live Models and inference-free replays.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[clojure.string :as str]
         '[cheshire.core :as json] '[clojure.edn :as edn] '[ppocr.qualification :as qualification-path])
(def args (apply hash-map *command-line-args*))
(def out (str (fs/absolutize (get args "--out" (str (or (System/getenv "EMON_SCRATCH") ".cache/scratch") "/op2a/models")))))
(fs/create-dirs out)
(def env {"EMON_CS_SHARED_COMPILATION" "false" "NuGetAudit" "false"})
(defn run! [label argv & [extra]]
 (doseq [p ["."]]
  (assert (> (.getUsableSpace (java.io.File. p)) (* 10 1024 1024 1024)) (str "disk below 10 GiB: " p)))
 (let [log (str out "/" label) r @(process argv {:extra-env (merge env extra) :out log :err (str log ".err") :shutdown nil})]
  (assert (zero? (:exit r)) (str label " failed: " (:exit r) "; see " log ".err")) log))
(when-not (= "true" (get args "--skip-build"))
 (run! "rust-native-build.log" ["kin/scripts/emon-rs-build"] {"EMON_RS_LIVE" "1" "EMON_RS_PPOCR" "0"})
 (run! "go-build.log" ["kin/scripts/emon-go-build"] {"EMON_GO_LIVE" "1"})
 (run! "java-build.log" ["kin/scripts/emon-java-build"])
 (run! "csharp-build.log" ["kin/scripts/emon-cs-build"])
 (run! "c-build.log" ["kin/scripts/emon-c-build"] {"EMON_RS_LIVE" "1"})
 (run! "pod-build.log" ["kin/scripts/emon-pod-build"] {"EMON_RS_LIVE" "1"}))
(def rust ".cache/emon-rs-ppocr/target/release/emon-rs")
(def hosts [["rust" rust] ["go" ".cache/emon-go-live/emon-go"] ["java" ".cache/emon-java/emon-java"]
            ["csharp" ".cache/emon-cs/emon-cs"] ["c" ".cache/emon-c-live/emon-c"] ["pod" ".cache/emon-pod/emon-pod"] ["rust-native" ".cache/emon-rs-live/target/release/emon-rs"]])
(def k2-hosts #{"c" "pod" "rust-native" "go" "java" "csharp"}) ; D925: every host registers the PP adapter through the registry, so a missing pack or runtime is K2's refusal name everywhere
(def model-dir (str (fs/absolutize ".cache/ppocr")))
(def tessdata (str out "/tessdata"))
(let [pins (:models (edn/read-string (slurp "tools/tessdata/models.edn")))
      sources (remove str/blank? [(System/getenv "TESSDATA_PREFIX") ".cache/tessdata"
                                  "/opt/homebrew/share/tessdata" "/usr/local/share/tessdata"
                                  "/usr/share/tesseract-ocr/5/tessdata"])]
  (fs/create-dirs tessdata)
  (doseq [lang ["eng" "osd"]]
    (let [name (str lang ".traineddata")
          sha (get-in pins [lang :sha256])
          source (some (fn [dir]
                         (let [path (fs/path dir name)]
                           (when (and (fs/regular-file? path) (= sha (qualification-path/file-sha256 path)))
                             path)))
                       sources)]
      (assert source (str "missing pinned " name "; set TESSDATA_PREFIX or run bb tessdata-fetch --langs eng,osd"))
      (fs/copy source (fs/path tessdata name) {:replace-existing true}))))
(def ext (if (= "Mac OS X" (System/getProperty "os.name")) "dylib" "so"))
(def pp-adapter (str model-dir "/libemon_ppocr_adapter." ext))
(def tess-adapter (str (fs/absolutize ".cache/emon-rs-live/target/release") "/libemon_ocr_tesseract." ext))
(when-not (= "true" (get args "--skip-build"))
  (let [crate ".cache/emon-rs-live"]
    (fs/copy "host/rust/src/tess_adapter.rs" (fs/path crate "src/tess_adapter.rs") {:replace-existing true})
    (run! "tess-adapter-build.log"
          ["cargo" "rustc" "--manifest-path" (str crate "/Cargo.toml") "--offline" "--release"
           "--lib" "--crate-type" "cdylib" "--features" "tesseract-adapter,__test_hooks"]
          {"RUSTFLAGS" (str "-A warnings -L native=" (or (System/getenv "EMON_TESS_LIB") "/opt/homebrew/lib"))})
    (fs/copy (fs/path crate "target/release" (str "libemon." ext)) tess-adapter {:replace-existing true})
    (when (= ext "dylib")
      (run! "tess-adapter-install-name.log" ["install_name_tool" "-id" "@rpath/libemon_ocr_tesseract.dylib" tess-adapter])
      (run! "tess-adapter-sign.log" ["codesign" "--force" "--sign" "-" tess-adapter]))))
(defn live-env [packs models adapter]
  {"EMON_PACK_CACHE" (str out "/" packs)
   "EMON_PACKS_FETCH" "never"
   "EMON_OFFLINE" "1"
   "EMON_MODELS" (str models java.io.File/pathSeparator tessdata)
   "EMON_TESSDATA" tessdata
   ;; Go, Java and C# register Tesseract (the hybrid's primary) from the adapter library, as the C driver is told to (D925): none is wired in
   "EMON_ADAPTER_DIR" (str (fs/parent tess-adapter))
   "EMON_PPOCR_MODELS" models
   "EMON_PPOCR_LIB" adapter
   "TESSDATA_PREFIX" tessdata})
(def available (live-env "packs" model-dir pp-adapter))
(def absent-models (str out "/absent-models"))
(def absent-adapter (str out "/absent-adapter/libemon_ppocr_adapter." ext))
(assert (not (or (fs/exists? absent-models) (fs/exists? absent-adapter))) "absence paths must be empty")
(def unavailable (live-env "packs-without-pp" absent-models absent-adapter))
(defn live-command [host bin adapter]
  (cond-> [bin "--ocr-engine=ppocr"]
    (= host "pod") (conj "--native")
    (= host "c") (into [(str "--adapter=" tess-adapter) (str "--adapter=" adapter)])))
(def qualification (get args "--qualification" "corpus/_qual/ppocr-qual"))
(def receipt-bitmap (qualification-path/bitmap-file qualification
                     (first (json/parse-string (slurp (str qualification "/bitmaps.json")) true))))
(def files ["test/raster/receipt-photo.png" "test/raster/h-plain-72dpi.png" "test/raster/h-plain-rot180.png" "test/raster/receipt-empty.png" receipt-bitmap])
(def pp-recordings (atom 0))
(def rows (atom []))
(defn check-refusal! [host bin scenario adapter extra k2-error legacy-error]
  (let [expected (if (k2-hosts host) k2-error legacy-error)
        log (str out "/" host "-" scenario ".log")
        {:keys [exit err] stdout :out}
        @(process (conj (live-command host bin adapter) (first files))
                  {:extra-env (merge env extra) :out :string :err :string :shutdown nil})]
    (spit log err)
    (assert (and (not (zero? exit)) (empty? stdout)
                 (re-find (re-pattern (str "(?m)(?:^|:\\s+)" expected "(?:$|:)")) err))
            (str host " expected " expected " with no Model for " scenario "; exit " exit "; see " log))))
(doseq [[host bin] (rest hosts)]
  (check-refusal! host bin "models-absent" pp-adapter
                  (live-env "packs-without-pp" absent-models pp-adapter)
                  "pack-missing" "ppocr-models-missing")
  (check-refusal! host bin "adapter-absent" absent-adapter
                  (assoc available "EMON_PPOCR_LIB" absent-adapter)
                  "adapter-runtime-missing" "ppocr-runtime-missing"))
(def runtime-file ".cache/ppocr/onnxruntime-osx-arm64-1.22.0/lib/libonnxruntime.1.22.0.dylib")
(def runtime-away (str runtime-file ".op2a-absent"))
(def runtime-before (qualification-path/file-sha256 runtime-file))
(assert (not (fs/exists? runtime-away)) "unexpected prior runtime backup")
(fs/move runtime-file runtime-away)
(try
  (doseq [[host bin] (rest hosts)]
    (check-refusal! host bin "ort-absent" pp-adapter available
                    "adapter-runtime-missing" "ppocr-runtime-missing"))
  (finally (fs/move runtime-away runtime-file)))
(assert (= runtime-before (qualification-path/file-sha256 runtime-file)) "runtime bytes changed during absence contract")
(spit (str out "/runtime-restore.json") (json/generate-string {:actual-ort-absence-checked true :runtime-restored true :sha256-unchanged true}))
(defn model-json [path]
  (or (second (re-find #"(?s)\A== json\n(.*?)\n== json-pretty\n" (slurp path)))
      (throw (ex-info "bundle has no Model" {:file path}))))
(defn pp-reading? [{:keys [kind ans]}]
  ;; In a successful OIL v1 answer, cell 5 names the OCR engine.
  (and (= :ocr kind) (= 0 (get ans 3)) (= 1 (get ans 4)) (= 2 (get ans 5))))
(defn transcript-payload [path]
  (let [text (slurp path) host (get-in (edn/read-string text) [:producer :host])]
    (assert (string? host) "recording has no honest host provenance")
    (str/replace-first text (pr-str host) (pr-str "HOST-PROVENANCE"))))
(defn bytes= [a b] (java.util.Arrays/equals ^bytes (fs/read-all-bytes a) ^bytes (fs/read-all-bytes b)))
(doseq [[k file] (map-indexed vector files)]
 (let [dir (str out "/" k ".replay")
       unrecorded (run! (str k "-rust-unrecorded.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" file] available)
       bundle (run! (str k "-rust.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" (str "--record=" dir) file] available)
       self-replay (run! (str k "-rust-replay.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" dir) file]
                       unavailable)]
  (assert (= (model-json bundle) (model-json unrecorded)) (str "Rust recording changed the Model: " file))
  (assert (bytes= bundle self-replay) (str "Rust replay differs: " file))
  (when (some pp-reading? (:entries (edn/read-string (slurp (str dir "/transcript.edn")))))
    (swap! pp-recordings inc)
    (doseq [[host bin] hosts :when (not= host "pod")]
      (let [r @(process [bin "--ocr-engine=tesseract" "--out=json" (str "--replay=" dir) file]
                        {:extra-env env :out :string :err :string :shutdown nil})]
        (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) "transcript-miss"))
                (str host " PP-to-default replay mismatch was not transcript-miss")))))
  (swap! rows conj {:file file :host "rust" :live-model-equal true :replay-bundle-equal true})
  (doseq [[host bin] hosts :when (not= host "rust")]
   (let [recording (str out "/" k "-" host ".replay")
         record-flags (if (#{"go" "java" "csharp" "rust-native"} host) [(str "--record=" recording)] [])
         candidate (run! (str k "-" host "-live.bundle")
                         (into (conj (live-command host bin pp-adapter) "--out=bundle") (concat record-flags [file]))
                         available)]
    (assert (= (model-json bundle) (model-json candidate)) (str "live Model differs: " host " " file))
    (assert (bytes= (if (seq record-flags) bundle unrecorded) candidate) (str "live bundle differs in matching recording mode: " host " " file))
    (when (seq record-flags)
      (let [names #(set (remove (fn [name] (str/starts-with? (str name) ".")) (map fs/file-name (fs/list-dir %))))]
        (assert (= (names dir) (names recording)) (str "recording file set differs: " host))
        (doseq [name (names dir)]
          (assert (if (= (str name) "transcript.edn")
                    (= (transcript-payload (str dir "/" name)) (transcript-payload (str recording "/" name)))
                    (bytes= (str dir "/" name) (str recording "/" name)))
                  (str "recording payload differs: " host " " name)))))
    (when-not (= host "pod")
     (let [replay (run! (str k "-" host ".bundle") [bin "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" dir) file]
                    unavailable)]
      (assert (bytes= bundle replay) (str "replay bundle differs: " host " " file))))
    (swap! rows conj {:file file :host host :live-model-equal true :replay-bundle-equal (not= host "pod")})))
  (spit (str out "/summary.json") (json/generate-string @rows {:pretty true}))))
(assert (pos? @pp-recordings) "integrated fixtures never requested PP")
(println "live PP Models match on seven hosts; available replay APIs match complete bundles without model/runtime paths")
(run! "qualification-replays.log" (into ["bb" "tools/ppocr/replays.clj" "--qualification"
                                   qualification
                                   "--out" (str out "/qualification-replays")]
                                  (when-let [artifact (get args "--record-reference")]
                                    ["--record-reference" artifact])))
