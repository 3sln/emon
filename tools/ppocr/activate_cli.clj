#!/usr/bin/env bb
;; Enable the existing dynamic binding, never a direct ORT link.
(require '[clojure.string :as str])
(let [[file] *command-line-args* source (slurp file)]
  (assert (str/includes? source "PPOCR_NATIVE_HOST: bool = false"))
  (spit file (str/replace source "PPOCR_NATIVE_HOST: bool = false" "PPOCR_NATIVE_HOST: bool = true")))
