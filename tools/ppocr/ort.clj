#!/usr/bin/env bb
;; The pinned ONNX Runtime of tools/ppocr/runtimes.edn (ORTPIN, D906): fetch Microsoft's package, verify it whole and again entry by entry, install into
;; .cache/ppocr/<directory>/ in the layout of the old release archives (lib/, include/, LICENSE, ThirdPartyNotices.txt). Never a gate; never anything unpinned.
;;
;;   bb tools/ppocr/ort.clj [--fetch] [--triple T | --host | --desktop | --mobile | --all]   install (default: this host). Without --fetch a package that is not
;;                                                                                          in .cache/ppocr already is an error, nothing is downloaded.
;;   bb tools/ppocr/ort.clj --check                                                          the pin file alone is well formed (offline; run by `bb native --check`)
(ns ppocr.ort
  (:require [babashka.fs :as fs] [babashka.process :refer [shell]] [clojure.edn :as edn] [clojure.string :as str]))
(import '[java.security MessageDigest] '[java.util.zip ZipFile])

(def pins (edn/read-string (slurp "tools/ppocr/runtimes.edn")))
(def root ".cache/ppocr")

(defn hex [bs] (apply str (map #(format "%02x" (bit-and % 255)) bs)))
(defn sha-bytes [bs] (hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes bs)))
(defn sha-file [p]
  (with-open [in (java.io.FileInputStream. (str p))]
    (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 1048576)]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))
      (hex (.digest md)))))

(defn host-triple []
  (let [os (System/getProperty "os.name") arm (#{"aarch64" "arm64"} (System/getProperty "os.arch"))]
    (cond (str/includes? os "Mac") (if arm "aarch64-apple-darwin" "x86_64-apple-darwin")
          (str/includes? os "Linux") (if arm "aarch64-unknown-linux-gnu" "x86_64-unknown-linux-gnu")
          (str/includes? os "Windows") "x86_64-pc-windows-msvc")))

(defn platform [triple] (or (get-in pins [:platforms triple]) (get-in pins [:mobile-platforms triple])))

(defn check-pins!
  "The pin file alone: shapes, https URLs, 64-hex digests, every platform's source exists, no two entries of a directory write the same path."
  []
  (let [bad (atom [])
        f! (fn [& m] (swap! bad conj (apply str m)))
        hex64? #(boolean (and (string? %) (re-matches #"[a-f0-9]{64}" %)))
        pin? (fn [p] (and (string? (:entry p)) (string? (:to p)) (pos-int? (:bytes p)) (hex64? (:sha256 p)) (not (str/includes? (:to p) ".."))))]
    (when-not (= "1.22.0" (:version pins)) (f! "version"))
    (doseq [[id s] (:sources pins)]
      (when-not (and (re-matches #"[A-Za-z0-9_.-]+" (:file s)) (str/starts-with? (:url s) "https://") (pos-int? (:bytes s)) (hex64? (:sha256 s)))
        (f! "source " id ": file, url, bytes or sha256"))
      (when (str/includes? (:url s) "github.com/microsoft/onnxruntime") (f! "source " id ": the dead GitHub URL"))
      (doseq [p (concat (:include s) (:notices s))] (when-not (pin? p) (f! "source " id ": bad entry pin " (:entry p)))))
    (doseq [[t p] (concat (:platforms pins) (:mobile-platforms pins))]
      (when-not (get-in pins [:sources (:source p)]) (f! t ": no source " (:source p)))
      (when-not (re-matches #"onnxruntime-[a-z0-9_-]+-1\.22\.0" (str (:directory p))) (f! t ": directory"))
      (when-not (seq (:libs p)) (f! t ": no libraries"))
      (doseq [l (:libs p)] (when-not (pin? l) (f! t ": bad library pin " (:entry l)))))
    (let [dirs (map :directory (vals (merge (:platforms pins) (:mobile-platforms pins))))]
      (when-not (= (count dirs) (count (set dirs))) (f! "two platforms share a directory")))
    (when (seq @bad) (throw (ex-info (str "runtimes.edn: " (str/join "; " @bad)) {})))
    (println "ort: pins well formed;" (count (:platforms pins)) "desktop and" (count (:mobile-platforms pins)) "mobile platforms, from"
             (str/join ", " (for [[id s] (:sources pins)] (str id " " (:file s)))))))

(defn fetch-source!
  "The package file of source ID in .cache/ppocr, verified whole (size and SHA-256); downloaded only when `fetch?`."
  [id fetch?]
  (let [{:keys [file url bytes sha256]} (get-in pins [:sources id])
        dest (str root "/" file)
        valid? #(and (fs/regular-file? %) (= bytes (fs/size %)) (= sha256 (sha-file %)))]
    (when (fs/sym-link? dest) (throw (ex-info "package must not be a symlink" {:file file})))
    (when-not (valid? dest)
      (when-not fetch? (throw (ex-info (str file " is missing or changed in " root "; run bb ppocr-fetch (or bb tools/ppocr/ort.clj --fetch)") {})))
      (let [tmp (str (fs/create-temp-file {:dir root :prefix "download-"}))]
        (try
          (shell {:shutdown nil} "curl" "--fail" "--location" "--silent" "--show-error" "--retry" "3" "--connect-timeout" "30" "--max-time" "900" "--output" tmp url)
          (when-not (valid? tmp) (throw (ex-info "package size or sha256 mismatch: the pin is never bypassed" {:file file :url url})))
          (fs/move tmp dest {:replace-existing true})
          (finally (fs/delete-if-exists tmp)))))
    dest))

(defn install!
  "Extract and verify TRIPLE's runtime from the pinned package. A packager owns its staging directory."
  ([triple fetch?] (install! triple fetch? root))
  ([triple fetch? out]
   (let [p (or (platform triple) (throw (ex-info (str "no ONNX Runtime pin for " triple) {})))
         src (get-in pins [:sources (:source p)])
         _ (when (fs/sym-link? root) (throw (ex-info "ppocr cache must not be a symlink" {})))
         _ (fs/create-dirs root)
         pkg (fetch-source! (:source p) fetch?)
         dir (str out "/" (:directory p))]
     (fs/delete-tree dir)
     (with-open [zf (ZipFile. ^String pkg)]
       (doseq [{:keys [entry to bytes sha256 links]} (concat (:include src) (:notices src) (:libs p))]
         (let [e (or (.getEntry zf entry) (throw (ex-info (str "package has no entry " entry) {})))
               b (with-open [in (.getInputStream zf e)] (.readAllBytes in))
               dest (str dir "/" to)]
           (when-not (and (= bytes (count b)) (= sha256 (sha-bytes b))) (throw (ex-info "entry size or sha256 mismatch" {:entry entry :triple triple})))
           (fs/create-dirs (fs/parent dest))
           (with-open [o (java.io.FileOutputStream. dest)] (.write o ^bytes b))
           (doseq [l links]
             (fs/create-sym-link (str dir "/" l) (fs/file-name to))))))
     (println "ort:" triple "->" dir "(" (:file src) ")")
     dir)))

(defn -main [args]
  (let [flag? (set args)
        triple (second (drop-while #(not= "--triple" %) args))
        fetch? (flag? "--fetch")
        triples (cond (flag? "--check") []
                      triple [triple]
                      (flag? "--all") (concat (keys (:platforms pins)) (keys (:mobile-platforms pins)))
                      (flag? "--desktop") (keys (:platforms pins))
                      (flag? "--mobile") (keys (:mobile-platforms pins))
                      :else [(host-triple)])]
    (check-pins!)
    (when-not (flag? "--check")
      (doseq [t triples] (install! t fetch?)))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
