#!/usr/bin/env bb
;; Download the pinned pdfium into the ignored cache, verified.   bb pdfium-fetch [--platform P] [--previous] [--force]
;;                                                                                       (DEVLOG P6.2, D127)
;; NOT a gate. The live Renderer (host/rust/src/pdfium.rs, host/go/render/pdfium.go) links the library this
;; installs; gates replay committed transcripts and never need it.
;;
;;   .cache/pdfium/<version>/<platform>/{lib,include,licenses,...}     the unpacked archive
;;   .cache/pdfium/current -> <version>/<platform>                      the one the build scripts use
;;                                                                      (not for `--platform wasm` or `--no-current`, P11.4c)
;;
;; The archive is checked against the SHA-256 in tools/pdfium/pdfium.edn BEFORE it is unpacked. On macOS the
;; dylib's install name is `./libpdfium.dylib`, which a linked binary would look for in its working
;; directory; it is rewritten to `@rpath/libpdfium.dylib` and ad-hoc re-signed (arm64 refuses a modified,
;; unsigned image). That changes the dylib, not the archive: the recorded hash is of the download.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '[java.security MessageDigest])

(def pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def args (set *command-line-args*))
(def platform
  (or (second (drop-while #(not= "--platform" %) *command-line-args*))
      (let [os (System/getProperty "os.name") arch (System/getProperty "os.arch")
            a (if (#{"aarch64" "arm64"} arch) "arm64" "x64")]
        (cond (str/includes? os "Mac") (str "mac-" a)
              (str/includes? os "Linux") (str "linux-" a)
              (str/includes? os "Windows") (str "win-" a)
              :else (throw (ex-info (str "unknown platform " os arch) {}))))))

(defn sha256 [^bytes bs]
  (let [d (.digest (doto (MessageDigest/getInstance "SHA-256") (.update bs)))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn fetch! [version plat want]
  (let [dir (str ".cache/pdfium/" (str/replace version "/" "-") "/" plat)
        tgz (str dir ".tgz")]
    (when (or (contains? args "--force") (not (fs/exists? (str dir "/.verified"))))
      (fs/create-dirs (fs/parent dir))
      (let [url (format (:url pin) version plat)]
        (println "downloading" url)
        (shell "curl" "-fsSL" "-o" tgz url))
      (let [got (sha256 (fs/read-all-bytes tgz))]
        (when-not (= got want)
          (fs/delete tgz)
          (println (str "FAIL pdfium " version " " plat ": sha256 " got " is not the pinned " want))
          (System/exit 1)))
      (fs/delete-tree dir)
      (fs/create-dirs dir)
      (shell "tar" "-xzf" tgz "-C" dir)
      (when (str/starts-with? plat "mac-")
        (let [lib (str dir "/lib/libpdfium.dylib")]
          (shell "install_name_tool" "-id" "@rpath/libpdfium.dylib" lib)
          (shell "codesign" "-f" "-s" "-" lib)))
      (spit (str dir "/.verified") (str want "\n"))
      (fs/delete tgz))
    dir))

(if (contains? args "--previous")
  (let [{:keys [version platform sha256]} (:previous pin)
        d (fetch! version platform sha256)]
    (println "previous build at" d))
  (let [p (get-in pin [:platforms platform])]
    (when-not p (println "FAIL no pinned pdfium for" platform) (System/exit 1))
    (let [d (fetch! (:version pin) platform (:sha256 p))
          cur ".cache/pdfium/current"]
      ;; The browser build (P11.4c) is not a library a host links: it must never become `current`.
      (when-not (or (contains? args "--no-current") (= platform "wasm"))
        (fs/delete-if-exists cur)
        (fs/create-sym-link cur (str/replace d ".cache/pdfium/" "")))
      (println "pdfium" (:version pin) platform "at" d))))
