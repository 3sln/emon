(ns process-env
  (:require [babashka.fs :as fs]))

(defn options
  "Babashka process options for inheriting the parent environment and applying overrides."
  ([overrides]
   (options (fs/windows?) (System/getenv) overrides))
  ([windows? parent overrides]
   (if windows?
     ;; Pass the full environment block, including SystemRoot and the MSVC setup.
     ;; Windows treats Path and PATH as the same variable.
     {:env (into (into (sorted-map-by #(.compareToIgnoreCase ^String %1 ^String %2)) parent)
                 overrides)}
     {:extra-env overrides})))
