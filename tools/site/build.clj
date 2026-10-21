#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[cheshire.core :as json] '[clojure.edn :as edn] '[clojure.string :as str])
(def args (vec *command-line-args*))
(defn has? [x] (boolean (some #{x} args)))
(defn opt [x] (let [i (.indexOf args x)] (when (and (<= 0 i) (< (inc i) (count args)) (not (str/starts-with? (args (inc i)) "--"))) (args (inc i)))))
(defn run! [& cmd] (let [r @(apply process {:inherit true} cmd)] (when-not (zero? (:exit r)) (System/exit (:exit r)))))
(defn read-edn [f fallback] (if (fs/exists? f) (edn/read-string (slurp f)) fallback))
(def out (str (fs/absolutize (or (opt "--out") ".cache/site/out"))))
(defn dependencies-current? []
 (try (let [lock (json/parse-string (slurp "site/package-lock.json")) installed (json/parse-string (slurp "site/node_modules/.package-lock.json"))]
   (every? (fn [[package details]] (or (= package "") (= (get details "version") (get-in installed ["packages" package "version"])))) (get lock "packages")))
  (catch Exception _ false)))
(when-not (dependencies-current?) (run! "npm" "ci" "--prefix" "site"))
(cond
 (has? "--self-test") (run! "node" "tools/site/check.mjs" "--self-test")
 (has? "--extract") (run! "node" "tools/site/i18n.mjs" "--extract")
 :else
 (do
  (fs/create-dirs ".cache/site")
  (let [commit (str/trim (:out @(process {:out :string :err :string} "git" "rev-parse" "HEAD")))
        languages (read-edn "site/i18n/languages.edn" [{:code "en" :path "" :name "English" :dir "ltr"}])]
   (spit ".cache/site/data.json" (json/generate-string {:out out :commit commit :version (:version (read-edn "version.edn" {})) :languages languages :samples (read-edn "samples/catalog.edn" []) :install (mapv (fn [item] (if-let [snippet (:snippet item)] (assoc item :code (let [text (slurp snippet)] (if-let [[_ body] (re-find #"(?s)docs:begin[^\n]*\n(.*?)\n[^\n]*docs:end" text)] body text))) item)) (read-edn "site/install.edn" [])) :noDemo (has? "--no-demo")})))
  (fs/delete-tree out)
  (fs/create-dirs out)
  (if (has? "--no-demo") (println "SKIP demo -- --no-demo") (run! "bb" "demo" "--out" (str out "/demo")))
  (if (has? "--no-demo") (do (println "SKIP renders -- --no-demo") (spit ".cache/site/renders.json" "{}")) (when (fs/exists? "tools/site/renders.mjs") (run! "node" "tools/site/renders.mjs" "--out" out "--data" ".cache/site/data.json")))
  (run! "node" "tools/site/render.mjs" "--all")
  (when (has? "--check")
   (run! "bb" "docs" "--check")
   (run! "node" "tools/site/i18n.mjs" "--check")
   (run! "node" "tools/site/check.mjs" "--site" out)
   (when-not (has? "--no-demo") (run! "node" "tools/demo/check.mjs" "--site" out "--path" "/demo/")))
  (when (has? "--vnu")
   (let [jar (or (System/getenv "VNU_JAR") ".cache/vnu/vnu.jar")]
    (if (fs/exists? jar) (run! "java" "-jar" jar "--skip-non-html" out)
     (println "SKIP vnu -- Nu validator jar not found (set VNU_JAR)"))))
  (when (has? "--external") (run! "node" "tools/site/check.mjs" "--site" out "--external"))
  (when (has? "--serve") (run! "node" "tools/demo/serve.mjs" out (or (opt "--serve") "8080")))))
