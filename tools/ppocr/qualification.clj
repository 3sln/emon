;; Qualification paths are relative to the artifact. Historical OP1b/OP1c
;; manifests are resolved under the restored qualification root first.
(ns ppocr.qualification
  (:require [babashka.fs :as fs])
  (:import [java.security MessageDigest]))

(defn bitmap-file [root row]
  (let [old (:file row)
        suffix (second (re-find #"/(?:op1b|op1c)/(.*)$" old))
        candidates (if (fs/absolute? old)
                     [(when suffix (fs/path root suffix)) old]
                     [(fs/path root old)])]
    (let [file (or (some #(when (and % (fs/regular-file? %)) %) candidates)
                   (throw (ex-info "qualification bitmap missing" {:id (:id row) :file old})))]
      (when-let [expected (:sha256 row)]
        (let [actual (apply str (map #(format "%02x" (bit-and % 255))
                                    (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes file))))]
          (assert (= expected actual) (str "qualification bitmap changed: " (:id row)))))
      (str file))))
