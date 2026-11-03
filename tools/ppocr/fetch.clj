#!/usr/bin/env bb
;; Optional assets only: never run by a gate, never a corpus fetch.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell]]
         '[clojure.edn :as edn])
(import '[java.security MessageDigest])
(def pins (edn/read-string (slurp "tools/ppocr/assets.edn")))
(when (seq *command-line-args*)
  (throw (ex-info "usage: bb ppocr-fetch" {})))
(def root ".cache/ppocr")
(when (fs/sym-link? root) (throw (ex-info "ppocr cache must not be a symlink" {})))
(defn sha [p]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes p)))))
(fs/create-dirs root)
(doseq [{:keys [file url sha256 bytes]} (:assets pins)]
  (when-not (and (re-matches #"[A-Za-z0-9_.-]+" file)
                 (re-matches #"[a-f0-9]{64}" sha256)
                 (pos-int? bytes) (.startsWith url "https://"))
    (throw (ex-info "invalid asset pin" {:file file})))
  (let [dest (str root "/" file)
        valid? #(and (fs/regular-file? %) (= bytes (fs/size %)) (= sha256 (sha %)))]
    (when (fs/sym-link? dest) (throw (ex-info "asset must not be a symlink" {:file file})))
    (if (valid? dest)
      (println "verified" file)
      (let [tmp (fs/create-temp-file {:dir root :prefix "download-"})]
        (try
          (shell {:shutdown nil} "curl" "--fail" "--location" "--silent" "--show-error" "--retry" "3"
                 "--connect-timeout" "30" "--max-time" "600" "--output" (str tmp) url)
          (when-not (valid? tmp) (throw (ex-info "asset size or sha256 mismatch" {:file file})))
          (fs/move tmp dest {:replace-existing true})
          (println "fetched and verified" file)
          (finally (fs/delete-if-exists tmp)))))))
