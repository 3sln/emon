(ns ppocr.bundle
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [ppocr.ort :as ort]))

(def stem "emon_ocr_ppocr")
(def adapter-dir "lib/emon/adapters")
;; The iOS floor of the pinned onnxruntime-c 1.22.0 podspec.
(def ios-deployment "15.1")
(def rust-modules ["ppocr" "ppocr_abi" "ppocr_ctc" "ppocr_image" "ppocr_pipeline" "ppocr_runtime" "ppocr_tess_drop"])

(defn- replace-glue [path before after]
  (let [source (slurp path)]
    (when-not (str/includes? source before)
      (throw (ex-info "PP-OCR build glue no longer matches the source" {:file path :expected before})))
    (str/replace-first source before after)))

(defn rust-overrides [triple]
  ;; The qualified source stays intact. The bundled port gets its Tesseract lexicon from the registry's pack view.
  (cond-> {"src/lib.rs" (str (slurp "host/rust/src/lib.rs") "\nmod ppocr_abi;\n")
           "src/tess.rs" (str (slurp "host/rust/src/tess.rs") "\ninclude!(\"ppocr_tess_drop.rs\");\n")
           "src/ppocr.rs" (replace-glue "host/rust/src/ppocr.rs" "TessOcr::new(None)"
                                       "TessOcr::new(Some(dir.to_str().ok_or(\"invalid UTF-8 PP pack directory\")?))")}
    (str/includes? triple "apple-ios")
    (assoc "src/ppocr_runtime.rs" (replace-glue "host/rust/src/ppocr_runtime.rs" "#[link(name = \"onnxruntime\")]"
                                               "#[link(name = \"onnxruntime\", kind = \"static\", modifiers = \"-bundle\")]"))))

(defn ort-c-source [win?]
  (let [file "host/rust/src/ppocr_ort.c"]
    (if win?
      (str/replace-first
       (replace-glue file "p->api->CreateSession(p->env, path, options, &p->session)"
                     "emon_create_session(p->api, p->env, path, options, &p->session)")
       "typedef struct {"
       (str "#include <windows.h>\n"
            "static OrtStatus *emon_create_session(const OrtApi *api, OrtEnv *env, const char *path, const OrtSessionOptions *opts, OrtSession **out) {\n"
            "  int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);\n"
            "  if (!n) return api->CreateStatus(ORT_INVALID_ARGUMENT, \"invalid UTF-8 path\");\n"
            "  wchar_t *wide = malloc((size_t)n * sizeof(*wide));\n"
            "  if (!wide) return api->CreateStatus(ORT_FAIL, \"allocation\");\n"
            "  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, n);\n"
            "  OrtStatus *status = api->CreateSession(env, wide, opts, out);\n"
            "  free(wide);\n"
            "  return status;\n}\n\ntypedef struct {"))
      (slurp file))))

(defn adapter-name [triple]
  (cond (str/includes? triple "windows") (str stem ".dll")
        (str/includes? triple "apple") (str "lib" stem ".dylib")
        :else (str "lib" stem ".so")))

(defn runtime-entries
  "Bundle path -> installed pin path. Use the loader's name and ordinary files so Dart can extract and bundle them."
  [triple]
  (into {}
        (for [{:keys [to links]} (:libs (ort/platform triple))
              :when (not (re-find #"\.(a|lib)$" to))
              :let [loader-name (or (some #(when (str/ends-with? % ".so.1") %) links) to)]]
          [(str adapter-dir "/" (fs/file-name loader-name)) to])))

(defn entries [triple]
  (cons (str adapter-dir "/" (adapter-name triple)) (keys (runtime-entries triple))))

(defn artifacts [triple runtime adapter]
  (assoc (into {} (for [[path src] (runtime-entries triple)] [path (str runtime "/" src)]))
         (str adapter-dir "/" (adapter-name triple)) adapter))

(defn build-shim!
  "Compile the ORT C shim and return the rustc flags for the adapter. 'cc' is a target compiler command prefix."
  [run dir triple runtime cc ar]
  (let [win? (str/includes? triple "windows")
        ios? (str/includes? triple "apple-ios")
        mac? (str/includes? triple "apple-darwin")
        dir (str (fs/absolutize dir))
        runtime (str (fs/absolutize runtime))
        src (str dir "/ppocr_ort.c")
        obj (str dir (if win? "/ppocr_ort.obj" "/ppocr_ort.o"))]
    (when-not (= (get-in (edn/read-string (slurp "tools/ppocr/assets.edn")) [:dictionary :sha256])
                 (ort/sha-file "tools/ppocr/en-dictionary.txt"))
      (throw (ex-info "PP-OCR dictionary does not match its pin" {})))
    (spit src (ort-c-source win?))
    (if win?
      (do (run {} "cl" "/nologo" "/c" "/O2" "/MT" (str "/I" runtime "/include") src (str "/Fo" obj))
          (run {} "lib" "/nologo" (str "/OUT:" dir "/emon_ppocr.lib") obj))
      (do (apply run {} (concat cc ["-O2" "-fPIC" "-c" src "-I" (str runtime "/include") "-o" obj]))
          (run {} ar "rcs" (str dir "/libemon_ppocr.a") obj)))
    (concat ["--cfg" "ppocr" "--cfg" "ppocr_bundle" "--check-cfg=cfg(ppocr_bundle)"
             "-L" (str "native=" dir) "-L" (str "native=" runtime "/lib")]
            (cond
              ios? ["-l" "c++" "-l" "framework=Foundation" "-l" "framework=Accelerate"
                    "-C" "link-arg=-Wl,-weak_framework,CoreML" "-C" "link-arg=-Wl,-headerpad_max_install_names"]
              mac? ["-C" "link-arg=-Wl,-rpath,@loader_path" "-C" "link-arg=-Wl,-headerpad_max_install_names"]
              (not win?) ["-C" "link-arg=-Wl,-rpath,$ORIGIN"]))))
