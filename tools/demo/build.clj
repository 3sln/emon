#!/usr/bin/env bb
;; BUILD THE BROWSER DEMO into a static site.   tools/demo/build.clj   (bb demo)   (DEVLOG P11.4c, D535, D536)
;;
;;     bb demo [--out DIR] [--serve [PORT]] [--check]
;;
;;   --out DIR     the site (default .cache/demo/site)
;;   --serve       build, then serve it at http://127.0.0.1:8080/ (or PORT)
;;   --check       build, then drive it in headless Chrome (puppeteer-core): convert a born-digital sample and a sample that needs pdfium, and a scan that needs
;;                 Tesseract, on the page and with the OCR on the page's thread, and assert the text. NOT a gate (it needs Chrome and the demo's downloads)
;;
;; The site is files, no bundler (GitHub Pages serves it as it is):
;;
;;   index.html style.css app.js worker.js        demo/
;;   sdk/*.js                                     host/js/src (the SDK: `@3sln/emon`, `/tesseract`, `/pdfium`), relative imports
;;   emon.wasm                                    kin/scripts/emon-wasm-build (needs the pinned nightly; it is the module the SDK instantiates)
;;   vendor/pdfium/{pdfium.mjs,pdfium.wasm,...}   `bb pdfium-fetch --platform wasm` (the pinned build and the loader made of it) and its licences
;;   vendor/tesseract/{tesseract.esm.min.js,worker.min.js,core/,lang/}   host/js/node_modules (`npm ci --prefix host/js`): tesseract.js, the LSTM-only SIMD
;;                                                cores and the English model (4.0.0_best_int)
;;   samples/*                                    the few sources demo/app.js names
;;   LICENSE THIRD_PARTY_NOTICES.md build.json    the licence, the notices, and this build's versions and checksums
;;
;; SIZE BUDGETS are enforced on RAW sizes (what the server sends before any compression, D536): the first load (everything but vendor/ and samples/), the
;; pdfium group, the Tesseract group and the whole site. A build over a budget FAILS, and the measured sizes are printed either way.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell process]]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '(java.security MessageDigest))

(def args (vec *command-line-args*))
(defn opt [k] (let [i (.indexOf ^java.util.List args k)] (when (and (>= i 0) (< (inc i) (count args)) (not (str/starts-with? (nth args (inc i)) "--"))) (nth args (inc i)))))
(def out (or (opt "--out") ".cache/demo/site"))
(defn die [& m] (println "FAIL" (apply str m)) (System/exit 1))

(def MiB (* 1024 1024))
(def budgets {:first (* 6.5 MiB) :pdfium (* 6 MiB) :tesseract (* 11 MiB) :site (* 30 MiB)})

(defn sha256 ^String [^bytes bs] (apply str (map #(format "%02x" (bit-and % 255)) (.digest (MessageDigest/getInstance "SHA-256") bs))))
(defn copy! [from to]
  (when-not (fs/exists? from) (die "missing " from))
  (fs/create-dirs (fs/parent to))
  (fs/copy from to {:replace-existing true}))

(def pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def pdfium-dir (str ".cache/pdfium/" (str/replace (:version pin) "/" "-") "/wasm"))
(def nm "host/js/node_modules")
(def version (:version (edn/read-string (slurp "version.edn"))))

;; ----------------------------------------------------------------------------------------------------------------------- the pieces
(when-not (fs/exists? (str pdfium-dir "/lib/pdfium.mjs")) (die "pdfium's wasm build is not in " pdfium-dir " (bb pdfium-fetch --platform wasm)"))
(when-not (fs/exists? (str nm "/tesseract.js/dist/worker.min.js")) (die "tesseract.js is not in " nm " (npm ci --prefix host/js)"))

(shell {:out :string :err :string} "kin/scripts/emit")
(def wasm
  (let [r @(process {:out :string :err :string} "kin/scripts/emon-wasm-build")]
    (case (:exit r)
      0 (str/trim (:out r))
      3 (die "cannot build emon.wasm: " (str/trim (:err r)))
      (die "emon.wasm did not build:\n" (:err r)))))

(fs/delete-tree out)
(fs/create-dirs out)
(spit (str out "/.nojekyll") "")

;; the page
(doseq [f ["index.html" "style.css" "app.js" "worker.js"]] (copy! (str "demo/" f) (str out "/" f)))
;; the SDK, by relative path
(doseq [f (fs/glob "host/js/src" "*.js")] (copy! (str f) (str out "/sdk/" (fs/file-name f))))
(copy! wasm (str out "/emon.wasm"))
;; pdfium
(copy! (str pdfium-dir "/lib/pdfium.mjs") (str out "/vendor/pdfium/pdfium.mjs"))
(copy! (str pdfium-dir "/lib/pdfium.wasm") (str out "/vendor/pdfium/pdfium.wasm"))
(copy! (str pdfium-dir "/LICENSE") (str out "/vendor/pdfium/LICENSE"))
(doseq [f (fs/list-dir (str pdfium-dir "/licenses"))] (copy! (str f) (str out "/vendor/pdfium/licenses/" (fs/file-name f))))
;; tesseract.js: the loader, the worker, the two SIMD LSTM cores (every browser that has the exception handling emon needs has SIMD; relaxed SIMD where there is
;; one) and the English model
(doseq [f ["tesseract.esm.min.js" "worker.min.js" "tesseract.min.js.LICENSE.txt" "worker.min.js.LICENSE.txt"]] (copy! (str nm "/tesseract.js/dist/" f) (str out "/vendor/tesseract/" f)))
(doseq [f ["tesseract-core-relaxedsimd-lstm.wasm.js" "tesseract-core-simd-lstm.wasm.js" "LICENSE"]] (copy! (str nm "/tesseract.js-core/" f) (str out "/vendor/tesseract/core/" f)))
(copy! (str nm "/@tesseract.js-data/eng/4.0.0_best_int/eng.traineddata.gz") (str out "/vendor/tesseract/lang/eng.traineddata.gz"))
;; the samples app.js names
(def samples (distinct (map second (re-seq #"file: '([^']+)'" (slurp "demo/app.js")))))
(when (empty? samples) (die "demo/app.js names no samples"))
(doseq [s samples] (copy! (str "samples/" s) (str out "/samples/" s)))
;; the licence and the notices
(copy! "LICENSE" (str out "/LICENSE"))
(copy! "THIRD_PARTY_NOTICES.md" (str out "/THIRD_PARTY_NOTICES.md"))

;; ----------------------------------------------------------------------------------------------------------------------- the sizes
(defn files-under [dir] (sort (map str (filter fs/regular-file? (fs/glob dir "**" {:hidden true})))))
(defn rel [f] (subs f (inc (count (str (fs/absolutize out))))))
(def all (for [f (files-under out)] (let [abs (str (fs/absolutize f))] {:path (rel abs) :file f :size (fs/size f)})))
(defn group [pred] (reduce + (map :size (filter pred all))))
(def sizes
  {:first (group #(not (or (str/starts-with? (:path %) "vendor/") (str/starts-with? (:path %) "samples/"))))
   :pdfium (group #(str/starts-with? (:path %) "vendor/pdfium/"))
   :tesseract (group #(str/starts-with? (:path %) "vendor/tesseract/"))
   :samples (group #(str/starts-with? (:path %) "samples/"))
   :site (group (constantly true))})

;; ----------------------------------------------------------------------------------------------------------------------- build.json
(def commit (let [r @(process {:out :string :err :string} "git" "rev-parse" "HEAD")] (when (zero? (:exit r)) (str/trim (:out r)))))
(def lock (json/parse-string (slurp "host/js/package-lock.json")))
(defn npm-version [n] (get-in lock ["packages" (str "node_modules/" n) "version"]))
(spit (str out "/build.json")
      (json/generate-string
       {:emon {:version version :commit commit :module "emon.wasm"}
        :pdfium {:version (:version pin) :repo (:repo pin) :archive-sha256 (get-in pin [:platforms "wasm" :sha256]) :loader "vendor/pdfium/pdfium.mjs (tools/pdfium/fetch.clj wraps the archive's lib/pdfium.js)"}
        :tesseract {:tesseract.js (npm-version "tesseract.js") :tesseract.js-core (npm-version "tesseract.js-core") :tessdata "@tesseract.js-data/eng 4.0.0_best_int" :eng-data-package (npm-version "@tesseract.js-data/eng")}
        :files (into (sorted-map) (for [{:keys [path file size]} all :when (not= path "build.json")] [path {:size size :sha256 (sha256 (fs/read-all-bytes file))}]))}
       {:pretty true}))

(def total (+ (:site sizes) (fs/size (str out "/build.json"))))
(defn mib [n] (format "%.2f MiB" (/ n 1.0 MiB)))
(println "demo site:" out)
(doseq [[k label] [[:first "first load (page, SDK, emon.wasm)"] [:pdfium "pdfium group"] [:tesseract "Tesseract group"] [:samples "samples"]]]
  (println (format "  %-34s %12d bytes  %s%s" label (sizes k) (mib (sizes k)) (if-let [b (budgets k)] (str "  (budget " (mib b) ")") ""))))
(println (format "  %-34s %12d bytes  %s  (budget %s)" "whole site" total (mib total) (mib (:site budgets))))
(let [over (concat (for [k [:first :pdfium :tesseract] :when (> (sizes k) (budgets k))] (str (name k) " group is " (mib (sizes k)) ", over its budget of " (mib (budgets k))))
                   (when (> total (:site budgets)) [(str "the site is " (mib total) ", over its budget of " (mib (:site budgets)))]))]
  (when (seq over) (die (str/join "; " over))))
(println "  ok   the demo builds within its size budgets")

;; ----------------------------------------------------------------------------------------------------------------------- serve, check
(cond
  (some #{"--check"} args)
  (let [r @(process {:inherit true} "node" "tools/demo/check.mjs" "--site" out)]
    (System/exit (:exit r)))
  (some #{"--serve"} args)
  (let [port (or (opt "--serve") "8080")]
    (println (str "  http://127.0.0.1:" port "/"))
    (System/exit (:exit @(process {:inherit true} "node" "tools/demo/serve.mjs" out port)))))
