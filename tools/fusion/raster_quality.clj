#!/usr/bin/env bb
;; LIVE synthetic raster-table law; needs a live Rust driver (pdfium + Tesseract).
;; bb tools/fusion/raster_quality.clj [--driver=PATH]
(require '[babashka.process :refer [process]] '[cheshire.core :as json] '[clojure.string :as str])
(def driver (or (some #(when (str/starts-with? % "--driver=") (subs % 9)) *command-line-args*)
                ".cache/emon-rs-fusion/target/release/emon-rs"))
(defn nodes [n] (cons n (mapcat nodes (:children n))))
(doseq [ext ["png" "jpg"]]
  (let [path (str "test/raster-quality/light-table." ext)
        r @(process [driver "--pdfium" "--tesseract" "--assets=figures" path] {:out :string :err :string})]
    (assert (zero? (:exit r)) (str path " conversion failed: " (:err r)))
    (let [model (json/parse-string (:out r) true)
          ns (nodes (:body model))
          tables (filter #(= "table" (:kind %)) ns)
          rows (filter #(= "row" (:kind %)) ns)
          cells (filter #(= "cell" (:kind %)) ns)]
      (assert (= 1 (count tables)) (str path ": one table"))
      (assert (= "ruled" (:profile (first tables))) (str path ": raster rules reach the lattice"))
      (assert (= 3 (:columns (first tables))) (str path ": three columns"))
      (assert (= 5 (count rows)) (str path ": five rows"))
      (assert (= 15 (count cells)) (str path ": fifteen cells"))
      (assert (not-any? #(= "|" (str/trim (or (:text %) ""))) ns) (str path ": no standalone rule ghost"))
      (println "ok" path "300 dpi, ruled table, 5 rows, 3 columns, 15 cells, no rule ghost"))))
