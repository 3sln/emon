#!/usr/bin/env bb
;; Offline integrity checks for the model fetcher, using a fake curl and synthetic model bytes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[clojure.string :as str] '[clojure.edn :as edn])
(import '[java.security MessageDigest])
(let [pin (edn/read-string (slurp "tools/tessdata/models.edn"))
      inv (into {} (map (juxt :id identity) (:components (edn/read-string (slurp "release/third-party.edn")))))]
  (doseq [[lang model] (:models pin)]
    (assert (re-matches #"[0-9a-f]{64}" (:sha256 model)) (str lang ": pinned SHA-256"))
    (assert (pos? (:bytes model)) (str lang ": pinned size"))
    (let [row (get inv (str "native/tessdata-" lang))]
      (assert (= "Apache-2.0" (:licence row)) (str lang ": recorded Apache-2.0 licence"))
      (assert (str/includes? (:version row) (:sha256 model)) (str lang ": inventory follows the model pin")))))
(def work (str (fs/create-temp-dir {:prefix "emon-tessdata-check-"})))
(def script (str (fs/absolutize "tools/tessdata/fetch.clj")))
(def bb (str (fs/which "bb")))
(defn sha [s]
  (apply str (map #(format "%02x" (bit-and % 255)) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))
(try
  (doseq [dir ["tools/tessdata" ".cache/tessdata" "bin"]] (fs/create-dirs (str work "/" dir)))
  (spit (str work "/bin/curl") "#!/bin/sh\nwhile [ $# -gt 0 ]; do\n  if [ \"$1\" = --output ]; then shift; dest=$1; fi\n  shift\ndone\nprintf '%s' synthetic-model > \"$dest\"\n")
  (fs/set-posix-file-permissions (str work "/bin/curl") "rwx------")
  (let [dest (str work "/.cache/tessdata/eng.traineddata")
        pin (str work "/tools/tessdata/models.edn")
        run (fn [& args] @(process (into [bb script] args) {:dir work :extra-env {"PATH" (str work "/bin:" (System/getenv "PATH"))} :out :string :err :string}))
        set-pin (fn [hash] (spit pin (pr-str {:revision "synthetic" :models {"eng" {:sha256 hash :bytes 15}}})))]
    (set-pin (sha "expected-model"))
    (spit dest "old-model")
    (assert (not (zero? (:exit (run "--langs" "eng")))) "bad downloaded bytes must fail")
    (assert (= "old-model" (slurp dest)) "a failed check must preserve the old model")
    (assert (= 1 (count (fs/list-dir (str work "/.cache/tessdata")))) "temporary download must be removed")
    (set-pin (sha "synthetic-model"))
    (assert (zero? (:exit (run "--langs" "eng"))) "checked bytes must install")
    (assert (= "synthetic-model" (slurp dest)) "only checked bytes are installed")
    (assert (zero? (:exit (run "--langs" "eng"))) "a checked cached model must be reusable")
    (assert (not (zero? (:exit (run "--langs" "../outside")))) "unknown names must fail before fetching")
    (println "ok: model hashes, failed-download preservation, cleanup, cached verification, and name validation"))
  (finally (fs/delete-tree work)))
