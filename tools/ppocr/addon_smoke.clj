#!/usr/bin/env bb
;; Test the actual unpacked release payload from a fresh directory, outside the repo cwd.
(require '[babashka.fs :as fs] '[babashka.process :refer [process shell]]
         '[clojure.string :as str])
(let [[archive] *command-line-args*
      _ (assert (= 1 (count *command-line-args*)) "usage: addon_smoke.clj ARCHIVE")
      root (str (fs/absolutize "."))
      scratch (fs/create-temp-dir {:dir ".cache" :prefix "ppocr-addon-smoke-"})
      scratch (str (fs/absolutize scratch))
      cli-dir (str scratch "/cli") pp-dir (str scratch "/pp")
      version (:version (clojure.edn/read-string (slurp "version.edn")))
      name (str/replace (str (fs/file-name archive)) #"\.(tar\.gz|zip)$" "")
      triple (subs name (count (str "emon-ppocr-" version "-")))
      win? (str/includes? triple "windows") mac? (str/includes? triple "apple")
      cli-archive (str root "/.cache/cli/emon-" version "-" triple (if win? ".zip" ".tar.gz"))
      unpack (fn [a d] (fs/create-dirs d)
               (if win? (fs/unzip a d)
                 (shell {:shutdown nil} "tar" "-xzf" a "-C" d)))]
  (assert (fs/regular-file? cli-archive) "build bb cli --check before the add-on smoke")
  (try
    (unpack (str (fs/absolutize archive)) pp-dir) (unpack cli-archive cli-dir)
    (let [pp (str pp-dir "/" name) cli (str cli-dir "/emon-" version "-" triple)
          lib (str pp "/lib/" (if win? "emon_ppocr_adapter.dll" (str "libemon_ppocr_adapter." (if mac? "dylib" "so"))))
          env {"EMON_CS_SHARED_COMPILATION" "false" "EMON_PPOCR_LIB" lib
               "EMON_PPOCR_MODELS" (str pp "/models") "TESSDATA_PREFIX" (str pp "/models")
               "EMON_PDFIUM_LIB" "" "EMON_TESSDATA" ""}
          exe (str cli "/bin/emon" (when win? ".exe"))
          invoke (fn [extra] @(process [exe "convert" "--ocr-engine=ppocr" (str root "/test/raster/receipt-photo.png")]
                              {:dir scratch :extra-env (merge env extra) :out :string :err :string :shutdown nil}))
          r (invoke {}) expected (slurp "tools/ppocr/release-smoke.html")]
      (assert (zero? (:exit r)) (str "unpacked PP conversion failed: " (:err r)))
      (assert (= expected (:out r)) "unpacked PP result differs from committed reference")
      (let [convert (fn [flags] @(process (into [exe "convert" (str root "/samples/invoice.pdf")] flags)
                                  {:dir scratch :extra-env env :out :string :err :string :shutdown nil}))
            ordinary (convert []) experimental (convert ["--ocr-engine=ppocr"])]
        (assert (and (zero? (:exit ordinary)) (zero? (:exit experimental))) "born-digital smoke conversion failed")
        (assert (= (:out ordinary) (:out experimental)) "PP changed born-digital HTML"))
      (doseq [[extra error] [[{"EMON_PPOCR_LIB" (str scratch "/absent-library")} "ppocr-runtime-missing"]
                             [{"EMON_PPOCR_MODELS" (str scratch "/absent-models")} "ppocr-models-missing"]]]
        (let [r (invoke extra)]
          (assert (and (not (zero? (:exit r))) (str/includes? (:err r) error)) (str "missing named error: " error))))
      (println "PASS unpacked PP add-on + CLI receipt reference, born-digital equality and missing-input errors:" triple))
    (finally (fs/delete-tree scratch))))
