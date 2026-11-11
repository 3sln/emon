#!/usr/bin/env bb
;; Separate experimental release archive. No corpus access. All build output stays here.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell process]]
         '[babashka.http-client :as http] '[clojure.edn :as edn]
         '[clojure.string :as str] '[clojure.java.io :as io])
(import '[java.security MessageDigest] '[java.util.zip ZipEntry ZipOutputStream])
(def options
  (loop [remaining *command-line-args* result {}]
    (if-let [arg (first remaining)]
      (if (= "--out" arg)
        (do (assert (second remaining) "--out needs a directory")
            (recur (nnext remaining) (assoc result :out (second remaining))))
        (do (assert (#{"--fetch" "--check" "--no-build" "--prepare"} arg) (str "unknown add-on option " arg))
            (recur (next remaining) (assoc result arg true)))) result)))
(defn args [name] (get options name))
(defn sha [p] (with-open [in (io/input-stream (str p))]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 1048576)]
    (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))
    (apply str (map #(format "%02x" (bit-and % 255)) (.digest md))))))
(defn run [& cmd] (apply shell {:shutdown nil :extra-env {"EMON_CS_SHARED_COMPILATION" "false"}} cmd))
(defn host-triple []
  (let [os (System/getProperty "os.name") arm (#{"aarch64" "arm64"} (System/getProperty "os.arch"))]
    (cond (str/includes? os "Mac") (if arm "aarch64-apple-darwin" "x86_64-apple-darwin")
          (str/includes? os "Linux") (if arm "aarch64-unknown-linux-gnu" "x86_64-unknown-linux-gnu")
          (str/includes? os "Windows") "x86_64-pc-windows-msvc")))
(def triple (host-triple))
(def pins (edn/read-string (slurp "tools/ppocr/runtimes.edn")))
(def runtime (get-in pins [:platforms triple]))
(assert runtime "unsupported PP add-on host")
(def root (str (fs/absolutize ".cache/ppocr")))
(def prefix (str (fs/absolutize (str ".cache/native/" triple))))
(def mac? (str/includes? triple "apple"))
(def win? (str/includes? triple "windows"))
(def ext (cond mac? "dylib" win? "dll" :else "so"))
(def adapter (str (if win? "" "lib") "emon_ppocr_adapter." ext))
(def adapter-file (str (fs/absolutize ".cache/ppocr-release") "/" adapter))
(def ort (str root "/" (:directory runtime)))
(defn space! []
  ;; Only the build directory: a machine-specific scratch volume has no place in repository tooling (CI has none).
  (doseq [p ["."]]
    (assert (> (.getUsableSpace (io/file p)) (* 10 1024 1024 1024)) (str "less than 10 GiB free: " p))))
(defn fetch! [{:keys [file url bytes sha256]}]
  (let [dest (str root "/" file) valid? #(and (fs/regular-file? %) (= bytes (fs/size %)) (= sha256 (sha %)))]
    (when-not (valid? dest)
      (assert (args "--fetch") (str "missing or changed " file "; run bb ppocr-addon --fetch"))
      (let [tmp (str dest ".download")]
        (try (with-open [in (:body (http/get url {:as :stream})) out (io/output-stream tmp)] (io/copy in out))
             (assert (valid? tmp) (str "pin mismatch " file))
             (fs/move tmp dest {:replace-existing true})
             (finally (fs/delete-if-exists tmp))))) dest))
(defn prepare! []
  (space!) (fs/create-dirs root)
  (let [assets (edn/read-string (slurp "tools/ppocr/assets.edn"))]
    (doseq [pin (:assets assets) :when (not (:target pin))] (fetch! pin))
    (fetch! runtime)
    (fs/delete-tree ort)
    (if win? (fs/unzip (str root "/" (:file runtime)) root {:replace-existing true})
        (run "tar" "-xzf" (str root "/" (:file runtime)) "-C" root))
    (doseq [pin [(:dictionary assets) (:orientation-dictionary assets)]]
      (assert (= (:sha256 pin) (sha (str "tools/ppocr/" (:file pin)))) "dictionary pin mismatch"))))
(defn build! []
  (assert (fs/exists? (str prefix "/link.rustflags")) "bb native must build this host first")
  (run "bb" "tools/ppocr/identity.clj")
  (let [dir (str (fs/absolutize ".cache/ppocr-release"))]
    (fs/create-dirs (str dir "/src/emon"))
    (doseq [f (fs/glob "gen/rust/emon" "*.rs")
            :let [stem (fs/strip-ext (fs/file-name f))]
            :when (not (re-find #"(test|probe)$" stem))]
      (fs/copy f (str dir "/src/emon/" (fs/file-name f)) {:replace-existing true}))
    (spit (str dir "/src/emon.rs") (apply str (for [f (sort (fs/glob (str dir "/src/emon") "*.rs"))]
      (str "pub mod " (fs/strip-ext (fs/file-name f)) ";\n"))))
    (doseq [f ["lib" "port" "plan" "source" "engine" "imgdec" "tess" "render" "pdfium" "pdfimg" "session" "par" "ppocr_native"
              "ppocr" "ppocr_ctc" "ppocr_image" "ppocr_pipeline" "ppocr_runtime" "ppocr_calibration" "ppocr_abi" "ppocr_tess_drop"]]
      (fs/copy (str "host/rust/src/" f ".rs") (str dir "/src/" f ".rs") {:replace-existing true}))
    (spit (str dir "/src/lib.rs") "\npub mod ppocr_abi;\n" :append true)
    (spit (str dir "/src/tess.rs") "\ninclude!(\"ppocr_tess_drop.rs\");\n" :append true)
    (fs/copy "tools/ppocr/en-dictionary.txt" (str dir "/src/ppocr_dictionary.txt") {:replace-existing true})
    (fs/copy "host/rust/Cargo.lock" (str dir "/Cargo.lock") {:replace-existing true})
    (spit (str dir "/Cargo.toml") (str/replace (slurp "release/pkg/rust/Cargo.toml")
      "[lib]" "[lib]\ncrate-type = [\"cdylib\"]"))
    ;; Only adapt ORT's platform pathname ABI; image/decoding policy stays byte-identical.
    (let [call "p->api->CreateSession(p->env, path, options, &p->session)"
          original (slurp "host/rust/src/ppocr_ort.c")
          source (if win? (str/replace original call "emon_create_session(p->api, p->env, path, options, &p->session)") original)
          helper (when win? (str "\n#include <windows.h>\nstatic OrtStatus *emon_create_session(const OrtApi *api, OrtEnv *env, const char *path, const OrtSessionOptions *opts, OrtSession **out) {\n"
            "int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);\n"
            "if (!n) return api->CreateStatus(ORT_INVALID_ARGUMENT, \"invalid UTF-8 path\");\n"
            "wchar_t *w = malloc(n * sizeof(wchar_t)); if (!w) return api->CreateStatus(ORT_FAIL, \"allocation\");\n"
            "MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, w, n);\n"
            "OrtStatus *s = api->CreateSession(env, w, opts, out); free(w); return s; }\n"))]
      (spit (str dir "/ppocr_ort.c") (str/replace source "typedef struct {" (str helper "\ntypedef struct {"))))
    (if win?
      (do (run "cl" "/nologo" "/c" "/O2" "/MT" (str "/I" ort "/include") (str dir "/ppocr_ort.c") (str "/Fo" dir "/ppocr_ort.obj"))
          (run "lib" "/nologo" (str "/OUT:" root "/emon_ppocr.lib") (str dir "/ppocr_ort.obj")))
      (do (run "cc" "-O2" "-fPIC" "-c" (str dir "/ppocr_ort.c") (str "-I" ort "/include") "-o" (str dir "/ppocr_ort.o"))
          (run "ar" "rcs" (str root "/libemon_ppocr.a") (str dir "/ppocr_ort.o"))))
    (let [link (str/split (str/trim (slurp (str prefix "/link.rustflags"))) #"\s+")
          flags (concat ["-A" "warnings" "--cfg" "ppocr" "-L" (str "native=" root) "-L" (str "native=" ort "/lib")]
                    link (when win? ["-C" "target-feature=+crt-static"])
                    (when-not win? ["-C" (str "link-arg=-Wl,-rpath," (if mac? "@loader_path" "$ORIGIN"))]))]
      (apply shell {:dir dir :shutdown nil :extra-env {"MACOSX_DEPLOYMENT_TARGET" "12.0" "EMON_CS_SHARED_COMPILATION" "false"}}
        (concat ["cargo" "rustc" "--locked" "--release" "--lib" "--features" "tesseract" "--"] flags)))
    (let [src (str dir "/target/release/" (if win? "emon.dll" (str "libemon." ext))) dest adapter-file]
      (fs/copy src dest {:replace-existing true})
      (when mac? (run "install_name_tool" "-id" (str "@rpath/" adapter) dest)
        (run "codesign" "--force" "--sign" "-" dest)))))
(defn package! []
  (let [version (:version (edn/read-string (slurp "version.edn"))) name (str "emon-ppocr-" version "-" triple)
        out (str (fs/absolutize (or (:out options) "dist/ppocr"))) stage (str out "/" name)
        archive (str out "/" name (if win? ".zip" ".tar.gz"))
        assets (edn/read-string (slurp "tools/ppocr/assets.edn"))]
    (fs/delete-tree stage) (fs/create-dirs (str stage "/lib")) (fs/create-dirs (str stage "/models"))
    (fs/copy adapter-file (str stage "/lib/" adapter))
    (doseq [f (fs/list-dir (str ort "/lib")) :when (and (fs/regular-file? f) (re-find #"(\.dylib$|\.so(\.|$)|\.dll$)" (str f)))]
      (let [dest (str stage "/lib/" (fs/file-name f))]
        (if (fs/sym-link? f)
          (fs/create-sym-link dest (java.nio.file.Files/readSymbolicLink (fs/path f)))
          (fs/copy f dest {:replace-existing true}))))
    (doseq [a (:assets assets) :when (not (:target a))] (fs/copy (str root "/" (:file a)) (str stage "/models/" (:file a))))
    (doseq [p [(:dictionary assets) (:orientation-dictionary assets)]] (fs/copy (str "tools/ppocr/" (:file p)) (str stage "/models/" (:file p))))
    (let [data (str prefix "/share/tessdata/eng.traineddata")]
      (assert (= "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2" (sha data)) "lexicon pin")
      (fs/copy data (str stage "/models/eng.traineddata")))
    (fs/create-dirs (str stage "/licenses"))
    (doseq [f (fs/list-dir "release/licenses") :when (fs/regular-file? f)] (fs/copy f (str stage "/licenses/" (fs/file-name f))))
    (doseq [file ["LICENSE" "ThirdPartyNotices.txt"] :when (fs/exists? (str ort "/" file))]
      (fs/copy (str ort "/" file) (str stage "/licenses/ORT-" file)))
    (doseq [[src dst] [["LICENSE" "LICENSE"] ["THIRD_PARTY_NOTICES.md" "THIRD_PARTY_NOTICES.md"] ["docs/ppocr.md" "README.md"] ["host/native/include/emon_ppocr.h" "emon_ppocr.h"]]]
      (fs/copy src (str stage "/" dst)))
    (spit (str stage "/SHA256SUMS") (apply str (for [f (sort (fs/glob stage "**")) :when (fs/regular-file? f)]
      (str (sha f) "  " (fs/relativize stage f) "\n"))))
    (if win? (with-open [zos (ZipOutputStream. (io/output-stream archive))]
       (doseq [f (sort (fs/glob stage "**")) :when (fs/regular-file? f)]
         (.putNextEntry zos (ZipEntry. (str/replace (str (fs/relativize out f)) "\\" "/")))
         (io/copy (io/file (str f)) zos) (.closeEntry zos)))
      (shell {:dir out :shutdown nil :extra-env {"COPYFILE_DISABLE" "1"}} "tar" "-czf" archive name))
    (println "PP add-on:" archive "bytes" (fs/size archive) "sha256" (sha archive))
    (when (args "--check") (run "bb" "tools/ppocr/addon_smoke.clj" archive))
    archive))
(when (= *file* (System/getProperty "babashka.file"))
  (prepare!)
  (when-not (args "--prepare") (when-not (args "--no-build") (build!)) (package!)))
