#!/usr/bin/env bb
;; Portable requested Tesseract configuration: pinned model catalogue, reference adapter and kin wire/resolver policy.
(require '[babashka.fs :as fs] '[clojure.string :as str])
(import '[java.security MessageDigest])
(def digest (MessageDigest/getInstance "SHA-256"))
(doseq [file ["tools/tessdata/models.edn" "host/rust/src/tess.rs" "kin/ocrport.kin" "kin/ocrtess.kin" "kin/oil.kin" "kin/oilregistry.kin"]]
  (.update digest (.getBytes (str file \u0000) "UTF-8"))
  (.update digest (fs/read-all-bytes file)))
(def identity (str "tesseract;api=Init3;oem=default;choice=2;policy="
                   (apply str (map #(format "%02x" (bit-and % 255)) (.digest digest)))))
(def file "kin/ocridentity.kin")
(def content (slurp file))
(def want (str/join " " (map int identity)))
(if (= ["--write"] *command-line-args*)
  (spit file (str/replace-first content #":data \[[0-9 ]+\]" (str ":data [" want "]")))
  (do (assert (empty? *command-line-args*) "usage: identity.clj [--write]")
      (assert (= want (second (re-find #":data \[([0-9 ]+)\]" content))) "Tesseract requested identity drift")))
(println identity)
