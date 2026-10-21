#!/usr/bin/env bb
;; Fetch only pinned, checked model bytes. No release bundle is changed.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell]]
         '[clojure.edn :as edn] '[clojure.string :as str])
(import '[java.security MessageDigest])
(def pin (edn/read-string (slurp "tools/tessdata/models.edn")))
(def args *command-line-args*)
(def langs
  (cond (empty? args) (sort (keys (:models pin)))
        (and (= "--langs" (first args)) (= 2 (count args))) (str/split (second args) #"[,+ ]+")
        :else (throw (ex-info "usage: bb tessdata-fetch [--langs eng,spa,...]" {}))))
;; Validate every name before fetching anything (also prevents path traversal).
(doseq [lang langs]
  (when-not (get-in pin [:models lang])
    (throw (ex-info (str "unlisted tessdata model: " lang) {}))))
(defn sha [path]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes path)))))
(fs/create-dirs ".cache/tessdata")
(doseq [lang langs]
  (let [dest (str ".cache/tessdata/" lang ".traineddata")
        want (get-in pin [:models lang :sha256])]
    (if (and (fs/exists? dest) (= want (sha dest)))
      (println "verified" lang)
      (let [tmp (fs/create-temp-file {:dir ".cache/tessdata" :prefix (str lang "-")})]
        (try
          (shell "curl" "--fail" "--location" "--silent" "--show-error" "--retry" "3"
                 "--output" (str tmp)
                 (str "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/" (:revision pin) "/" lang ".traineddata"))
          (when-not (= want (sha tmp))
            (throw (ex-info (str "sha256 mismatch for " lang) {})))
          (fs/move tmp dest {:replace-existing true})
          (println "fetched and verified" lang)
          (finally (fs/delete-if-exists tmp)))))))
