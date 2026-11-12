#!/usr/bin/env bb
;; Compact only completed six-host comparisons in an in-progress artifact.
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[ppocr.qualification :as qualification])
(def args (apply hash-map *command-line-args*))
(assert (= #{"--qualification" "--summary"} (set (keys args))) "usage: --qualification DIR --summary FILE")
(def summary (json/parse-string (slurp (get args "--summary")) true))
(doseq [[i rows] (map-indexed vector (partition 6 (:results summary)))]
  (assert (= 1 (count (set (map :id rows)))) "mixed recording comparison group")
  (assert (= 6 (count (set (map :host rows)))) "missing replay host")
  (assert (every? :equal rows) "unqualified recording")
  (assert (= 1 (count (set (map :bundle-sha256 rows)))) "comparison digests differ")
  (let [file (str (get args "--qualification") "/recordings/" i ".bundle")]
    (when (fs/regular-file? file)
      (qualification/compress-bundle! file (:bundle-sha256 (first rows)))
      (println "compacted completed recording" i))))
