(ns native.exports
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.set :as set]
            [clojure.string :as str]))

(def adapter-abi #{"emon_ocr_adapter_v1"})

(defn header-functions []
  (let [header (str/replace (slurp "host/c/include/emon.h") #"(?s)/\*.*?\*/" " ")]
    (into (sorted-set) (map second) (re-seq #"\b(emon_[a-z0-9_]+)\s*\(" header))))

;; Rust supplies its own cdylib version script. Localize symbols from static C/C++ archives
;; without supplying a second script (GNU ld rejects two anonymous version scripts).
(def elf-link-flags ["-C" "link-arg=-Wl,--exclude-libs,ALL"])

(defn problems [file expected]
  (let [mac? (str/ends-with? (str file) ".dylib")
        flags (if mac? ["-gjU"] ["-D" "-g" "--defined-only" "--format=just-symbols"])
        result (apply p/shell {:out :string :err :string :continue true :shutdown nil} "nm" (conj flags (str file)))
        name (fs/file-name file)]
    (if-not (zero? (:exit result))
      [(str name ": cannot read exports: " (str/trim (:err result)))]
      (let [actual (into #{} (comp (remove str/blank?)
                                   (map #(if mac? (str/replace-first % #"^_" "") (first (str/split % #"@" 2)))))
                         (str/split-lines (:out result)))
            missing (sort (set/difference expected actual))
            extra (sort (set/difference actual expected))]
        (cond-> []
          (seq missing) (conj (str name ": missing exports: " (str/join " " missing)))
          (seq extra) (conj (str name ": unexpected exports: " (str/join " " extra))))))))
