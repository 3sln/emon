#!/usr/bin/env bb
;; Integrated optional-port contracts: live Models and inference-free replays.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[clojure.string :as str]
         '[cheshire.core :as json] '[clojure.edn :as edn])
(def args (apply hash-map *command-line-args*))
(def out (str (fs/absolutize (get args "--out" "/Volumes/SSK SSD/emon-scratch/op2a/models"))))
(fs/create-dirs out)
(def env {"EMON_CS_SHARED_COMPILATION" "false" "NuGetAudit" "false"})
(defn run! [label argv & [extra]]
 (doseq [p ["/System/Volumes/Data" "/Volumes/SSK SSD"]]
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
(def qualification (get args "--qualification" "/Volumes/SSK SSD/emon-scratch/op1c"))
(def first-bitmap (:file (first (json/parse-string (slurp (str qualification "/bitmaps.json")) true))))
(def receipt-bitmap (if (fs/regular-file? first-bitmap) first-bitmap (str/replace first-bitmap "/op1b/" "/op1c/")))
(def files ["test/raster/receipt-photo.png" "test/raster/h-plain-72dpi.png" "test/raster/h-plain-rot180.png" "test/raster/receipt-empty.png" receipt-bitmap])
(def pp-recordings (atom 0))
(def rows (atom []))
(doseq [[host bin] (rest hosts)
        [label extra] [["ppocr-models-missing" {"EMON_PPOCR_MODELS" "/nonexistent/op2a-models"}]
                       ["ppocr-runtime-missing" {"EMON_PPOCR_LIB" "/nonexistent/op2a-runtime"}]]]
 (let [r @(process [bin "--ocr-engine=ppocr" (first files)]
                  {:extra-env (merge env extra) :out :string :err :string :shutdown nil})]
  (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) label))
          (str host " missing named error " label))
  (spit (str out "/" host "-" label ".log") (str (:out r) (:err r)))))
(def runtime-file ".cache/ppocr/onnxruntime-osx-arm64-1.22.0/lib/libonnxruntime.1.22.0.dylib")
(def runtime-away (str runtime-file ".op2a-absent"))
(defn runtime-sha []
  (vec (.digest (java.security.MessageDigest/getInstance "SHA-256") (fs/read-all-bytes runtime-file))))
(def runtime-before (runtime-sha))
(assert (not (fs/exists? runtime-away)) "unexpected prior runtime backup")
(fs/move runtime-file runtime-away)
(try
  (doseq [[host bin] (rest hosts)]
    (let [r @(process [bin "--ocr-engine=ppocr" (first files)]
                      {:extra-env env :out :string :err :string :shutdown nil})]
      (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) "ppocr-runtime-missing"))
              (str host " missing ORT dependency was not ppocr-runtime-missing"))
      (spit (str out "/" host "-ort-absent.log") (str (:out r) (:err r)))))
  (finally (fs/move runtime-away runtime-file)))
(assert (= runtime-before (runtime-sha)) "runtime bytes changed during absence contract")
(spit (str out "/runtime-restore.json") (json/generate-string {:actual-ort-absence-checked true :runtime-restored true :sha256-unchanged true}))
(defn model-json [path]
  (second (re-find #"(?s)\A== json\n(.*?)\n== json-pretty\n" (slurp path))))
(defn transcript-payload [path]
  (let [text (slurp path) host (get-in (edn/read-string text) [:producer :host])]
    (assert (string? host) "recording has no honest host provenance")
    (str/replace-first text (pr-str host) (pr-str "HOST-PROVENANCE"))))
(defn bytes= [a b] (java.util.Arrays/equals ^bytes (fs/read-all-bytes a) ^bytes (fs/read-all-bytes b)))
(doseq [[k file] (map-indexed vector files)]
 (let [dir (str out "/" k ".replay")
       unrecorded (run! (str k "-rust-unrecorded.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" file])
       bundle (run! (str k "-rust.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" (str "--record=" dir) file])
       self-replay (run! (str k "-rust-replay.bundle") [rust "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" dir) file]
                       {"EMON_PPOCR_MODELS" "/nonexistent/op2a-models" "EMON_PPOCR_LIB" "/nonexistent/op2a-runtime"})]
  (assert (= (model-json bundle) (model-json unrecorded)) (str "Rust recording changed the Model: " file))
  (assert (bytes= bundle self-replay) (str "Rust replay differs: " file))
  (when (some #(some #{-3100} (:req %)) (:entries (edn/read-string (slurp (str dir "/transcript.edn")))))
    (swap! pp-recordings inc)
    (doseq [[host bin] hosts :when (not= host "pod")]
      (let [r @(process [bin "--ocr-engine=tesseract" "--out=json" (str "--replay=" dir) file]
                        {:extra-env env :out :string :err :string :shutdown nil})]
        (assert (and (not (zero? (:exit r))) (str/includes? (str (:out r) (:err r)) "transcript-miss"))
                (str host " PP-to-default replay mismatch was not transcript-miss")))))
  (swap! rows conj {:file file :host "rust" :live-model-equal true :replay-bundle-equal true})
  (doseq [[host bin] hosts :when (not= host "rust")]
   (let [native (if (#{"c" "pod"} host) ["--native"] [])
         recording (str out "/" k "-" host ".replay")
         record-flags (if (#{"go" "java" "csharp" "rust-native"} host) [(str "--record=" recording)] [])
         candidate (run! (str k "-" host "-live.bundle") (into [bin "--ocr-engine=ppocr" "--out=bundle"] (concat native record-flags [file])))]
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
                    {"EMON_PPOCR_MODELS" "/nonexistent/op2a-models" "EMON_PPOCR_LIB" "/nonexistent/op2a-runtime"})]
      (assert (bytes= bundle replay) (str "replay bundle differs: " host " " file))))
    (swap! rows conj {:file file :host host :live-model-equal true :replay-bundle-equal (not= host "pod")})))
  (spit (str out "/summary.json") (json/generate-string @rows {:pretty true}))))
(assert (pos? @pp-recordings) "integrated fixtures never requested PP")
(println "live PP Models match on six hosts; available replay APIs match complete bundles without model/runtime paths")
(run! "qualification-replays.log" ["bb" "tools/ppocr/replays.clj" "--qualification"
                                   (get args "--qualification" "/Volumes/SSK SSD/emon-scratch/op1c")
                                   "--out" (str out "/qualification-replays")])
