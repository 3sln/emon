;; Fetch the Adobe CJK resources that kin/scripts/cjkdata.clj turns into kin tables (DEVLOG P3.4, D134).
;;
;;     bb kin/scripts/cjkfetch.clj                 fetch what is missing, verify every file against the manifest
;;     bb kin/scripts/cjkfetch.clj --update        re-list the pinned commit, fetch everything, rewrite the manifest
;;
;; WHAT: the predefined CMaps (code -> CID) and `cid2code.txt` (CID -> Unicode) of the four Adobe character
;; collections, from https://github.com/adobe-type-tools/cmap-resources at ONE pinned commit (BSD-3-Clause;
;; the licence is fetched with them). The files are NOT committed (about 12 MB): they live in `.cache/cjk/`
;; (ignored), and the manifest `test/fonts/cjk-sources.edn` -- committed -- records the commit, and for every
;; file its size and SHA-256. A file whose checksum differs from the manifest is an ERROR, never silently
;; used: the generated tables are a function of exactly those bytes.
;;
;; WHICH: for each of Japan1, GB1, CNS1, Korea1: every CMap of the collection's `CMap/` directory except the
;; UTF-8 and UTF-32 ones (a PDF names a CMap that reads 1 to 4-byte codes, not UTF-8 or UTF-32; ISO 32000-1
;; Table 118 lists none of them), the `Adobe-<Ordering>-<n>` identity maps, and the `Hankaku`/`Hiragana`/`Katakana`/
;; `Roman` CID-to-CID transforms (not code maps); and `cid2code.txt`. Plus Identity-H/V (read for
;; completeness; the runtime handles them without tables).
(ns cjkfetch
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def repo "adobe-type-tools/cmap-resources")
(def commit "f5cf3bca7fdfeaceb77aa82847e974f2306c20b4")
;; The CFF standard strings, the CFF predefined charsets and encodings, MacExpertEncoding and the TrueType
;; standard Macintosh glyph order are in no file Adobe publishes in a form a script can read, so they are
;; taken from pdf.js (Apache-2.0), which typed them from the specifications, at one pinned commit, and are
;; checked by kin/scripts/fontextra.clj against the AGL and against the encodings emon derives from the AFMs.
(def pdfjs-repo "mozilla/pdf.js")
(def pdfjs-commit "81525fd446779f0a29003cf06e3ba33751954826")
(def pdfjs-files ["src/core/encodings.js" "src/core/charsets.js" "src/core/cff_parser.js" "src/core/fonts.js"])
(def collections ["Adobe-Japan1-7" "Adobe-GB1-6" "Adobe-CNS1-7" "Adobe-Korea1-2"])
(def manifest-path "test/fonts/cjk-sources.edn")
(def cache-dir ".cache/cjk")

(defn sha256 [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs)))))

(defn raw-url [p]
  (if (str/starts-with? p "pdfjs/")
    (str "https://raw.githubusercontent.com/" pdfjs-repo "/" pdfjs-commit "/" (subs p 6))
    (str "https://raw.githubusercontent.com/" repo "/" commit "/" p)))

(defn get-bytes [url]
  (let [r (http/get url {:as :bytes :throw false})]
    (when-not (= 200 (:status r))
      (throw (ex-info (str "fetch failed: " url " -> " (:status r)) {})))
    (:body r)))

(defn wanted? [p]
  (let [[dir sub nm] (str/split p #"/")]
    (cond
      (= p "LICENSE.md") true
      (= p "VERSIONS.txt") true
      (and (some #{dir} collections) (= sub "cid2code.txt")) true
      (and (some #{dir} collections) (= sub "CMap") nm
           (not (re-find #"-(UTF8|UTF32)-[HV]$" nm))
           (not (re-find #"^(Hankaku|Hiragana|Katakana|Roman)$" nm))
           (not (re-find #"^Adobe-[A-Za-z0-9]+-\d$" nm))) true
      (= p "Adobe-Identity-0/CMap/Identity-H") true
      (= p "Adobe-Identity-0/CMap/Identity-V") true
      :else false)))

(defn list-files []
  (let [r (http/get (str "https://api.github.com/repos/" repo "/git/trees/" commit "?recursive=1") {:throw false})
        t (json/parse-string (:body r) true)]
    (when (:truncated t) (throw (ex-info "tree listing truncated" {})))
    (->> (:tree t) (filter #(= "blob" (:type %))) (map :path) (filter wanted?)
         (concat (map #(str "pdfjs/" %) pdfjs-files)) sort vec)))

(defn cache-file [p] (io/file cache-dir (str/replace p "/" "__")))

(defn cache-path [p] (.getPath (cache-file p)))

(defn -main [& args]
  (let [update? (some #{"--update"} args)
        man (when (.exists (io/file manifest-path)) (edn/read-string (slurp manifest-path)))
        paths (if update? (list-files) (mapv :path (:files man)))
        want (into {} (map (juxt :path identity)) (:files man))]
    (when (and (not update?) (nil? man))
      (println "FAIL no manifest; run with --update") (System/exit 1))
    (when (and (not update?) (not= commit (:commit man)))
      (println "FAIL manifest commit differs from the script's") (System/exit 1))
    (io/make-parents (io/file cache-dir "x"))
    (let [rows
          (vec (for [p paths]
                 (let [f (cache-file p)
                       have (when (.exists f) (let [b (java.nio.file.Files/readAllBytes (.toPath f))] b))
                       exp (get want p)
                       ok? (and have exp (= (sha256 have) (:sha256 exp)))
                       bs (if (and ok? (not update?)) have (get-bytes (raw-url p)))
                       sum (sha256 bs)]
                   (when (and (not update?) exp (not= sum (:sha256 exp)))
                     (println "FAIL checksum mismatch for" p "expected" (:sha256 exp) "got" sum)
                     (System/exit 1))
                   (when (not= bs have) (with-open [o (io/output-stream f)] (.write o ^bytes bs)))
                   {:path p :size (alength ^bytes bs) :sha256 sum})))]
      (if update?
        (do (spit manifest-path
                  (str ";; GENERATED by kin/scripts/cjkfetch.clj --update. The Adobe CJK resources kin/scripts/cjkdata.clj reads.\n"
                       ";; BSD-3-Clause (LICENSE.md, fetched with them). Files live in .cache/cjk/ and are not committed.\n"
                       (with-out-str
                         (println "{:repo" (pr-str repo))
                         (println " :commit" (pr-str commit))
                         (println " :files [")
                         (doseq [r rows] (println "  " (pr-str r)))
                         (println " ]}"))))
            (println "  wrote" manifest-path "-" (count rows) "files"))
        (println "  ok   cjk sources:" (count rows) "files match" manifest-path)))))

(apply -main *command-line-args*)
