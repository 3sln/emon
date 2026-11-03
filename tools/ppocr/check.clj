#!/usr/bin/env bb
;; Native reference checks, deliberately outside gates. Does not download.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell]] '[clojure.edn :as edn])
(import '[java.security MessageDigest])
(shell {:shutdown nil} "bb" "tools/ppocr/identity.clj")
(shell {:shutdown nil} "bb" "tools/ppocr/test_fetch.clj")
(def root ".cache/ppocr")
(def pins (edn/read-string (slurp "tools/ppocr/assets.edn")))
(defn sha [p] (apply str (map #(format "%02x" (bit-and % 255))
                             (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes p)))))
(doseq [{:keys [file sha256 bytes]} (:assets pins)]
  (let [p (str root "/" file)]
    (when-not (and (fs/regular-file? p) (= bytes (fs/size p)) (= sha256 (sha p)))
      (throw (ex-info "PP-OCR asset absent or changed; run bb ppocr-fetch" {:file file})))))
(doseq [{:keys [file sha256 bytes]} [(:dictionary pins) (:orientation-dictionary pins)]
        :let [p (str "tools/ppocr/" file)]]
  (assert (= bytes (fs/size p)) "dictionary size")
  (assert (= sha256 (sha p)) "dictionary sha256"))
(shell {:shutdown nil} "bb" "tools/ppocr/dictionary.clj")
(shell {:shutdown nil} "bb" "tools/ppocr/check_calibration.clj")
(when-not (and (= "Mac OS X" (System/getProperty "os.name"))
               (= "aarch64" (System/getProperty "os.arch")))
  (throw (ex-info "PP-OCR reference runtime currently pinned for macOS arm64 only" {})))
(def runtime (str root "/onnxruntime-osx-arm64-1.22.0"))
;; Re-extract the verified archive so local library/header edits cannot bypass pins.
(shell {:shutdown nil} "tar" "-xzf" (str root "/onnxruntime-osx-arm64-1.22.0.tgz") "-C" root)
(shell {:shutdown nil} "cc" "-Wall" "-Wextra" "-Werror" "-c" "host/rust/src/ppocr_ort.c"
       (str "-I" runtime "/include") "-o" (str root "/ppocr_ort.o"))
(shell {:shutdown nil} "ar" "rcs" (str root "/libemon_ppocr.a") (str root "/ppocr_ort.o"))
(def link ["-L" root "-L" (str runtime "/lib") "-C"
           (str "link-arg=-Wl,-rpath," (fs/absolutize runtime) "/lib")])
(doseq [module ["ctc" "image" "runtime"]]
  (let [bin (str root "/" module "-tests")]
    (apply shell {:shutdown nil} "rustc" "--edition=2021" "--test" (str "host/rust/src/ppocr_" module ".rs") "-o" bin
           (if (= module "runtime") link []))
    (shell {:shutdown nil} bin "--nocapture")))
(apply shell {:shutdown nil} "rustc" "--edition=2021" "-O" "tools/ppocr/probe.rs" "-o" (str root "/probe") link)
(println "PP-OCR native reference checks passed; not integrated acceptance")
