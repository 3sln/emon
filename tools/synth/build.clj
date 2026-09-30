(ns synth.build
  "Facts, manifest and gold for the synthetic corpus (DEVLOG P2.8, D063).

      bb -cp tools:tools/lib -m synth.build

  Run by print.sh after the PDFs exist.  For every test/corpus/synth/html/STEM.html:

    1. LAYOUT FACTS from pdf/STEM.pdf (poppler's pdfinfo and pdftotext): the page count and sizes, and
       for each `<table data-multipage>` the page every row landed on (found by the row's first cell,
       which the corpus keeps unique).  These are the only things the HTML cannot say, and they are
       committed in MANIFEST.json so that nothing downstream needs a PDF tool;
    2. MANIFEST.json: producer, tool versions, sha256 and size of every file;
    3. gold/STEM.gold.json: html2gold/convert(html, facts).

  kin/scripts/gold-check.py re-derives (3) from (HTML + MANIFEST facts) (by calling
  `bb -m synth.html2gold check`) and requires it to equal the committed gold."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pyfmt]
            [pyjson :as pj :refer [om oassoc]]
            [synth.html2gold :as h2g])
  (:import (java.security MessageDigest)))

(def CORPUS "test/corpus/synth")

(defn sha [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (java.nio.file.Files/readAllBytes (.toPath (io/file path))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn run [& cmd]
  (let [r (apply p/sh cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "command failed: " (str/join " " cmd)) r)))
    (:out r)))

(defn page-sizes [pdf]
  (let [out (run "pdfinfo" "-f" "1" "-l" "9999" pdf)
        sizes (vec (for [[_ _ w h] (re-seq #"(?m)^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts" out)]
                     [(pyfmt/round-half-even (parse-double w) 2) (pyfmt/round-half-even (parse-double h) 2)]))
        n (parse-long (second (re-find #"(?m)^Pages:\s+(\d+)" out)))
        producer (second (re-find #"(?m)^Producer:\s+(.*)$" out))]
    (assert (= n (count sizes)) (str pdf " " n " " (count sizes)))
    [sizes producer]))

(defn norm [s] (str/replace s #"(?U)\s+" " "))

(defn row-pages
  "{table id: [page of each <tr> in document order]} for data-multipage tables."
  [html pdf npages]
  (let [root (h2g/parse html)
        texts (atom {})
        text (fn [pg] (or (get @texts pg)
                          (let [t (norm (run "pdftotext" "-f" (str pg) "-l" (str pg) "-raw" pdf "-"))]
                            (swap! texts assoc pg t)
                            t)))]
    (reduce
     (fn [out t]
       (if (or (not= (h2g/etag t) "table") (nil? (h2g/eget t "data-multipage")))
         out
         (let [cur (volatile! 1)
               pages (vec (for [tr (h2g/walk t) :when (= (h2g/etag tr) "tr")]
                            (let [first-cell (first (filter #(and (h2g/el? %) (contains? #{"td" "th"} (h2g/etag %))) (h2g/ekids tr)))
                                  key (h2g/text-content first-cell)
                                  re (re-pattern (str "(?U)(?<![\\w-])" (java.util.regex.Pattern/quote key) "(?![\\w-])"))
                                  found (first (filter #(re-find re (text %)) (range @cur (inc npages))))]
                              (when-not found
                                (throw (ex-info (str "row " (pr-str key) " of table " (h2g/eget t "id") " not found in " pdf) {})))
                              (vreset! cur found)
                              found)))]
           (oassoc out (h2g/eget t "id") pages))))
     (om) (h2g/walk root))))

(defn tool-version [& cmd]
  (let [r (apply p/sh cmd)]
    (first (str/split-lines (str/trim (str (:out r) (:err r)))))))

(defn -main [& _]
  (let [stems (sort (for [f (.list (io/file CORPUS "html")) :when (str/ends-with? f ".html")] (subs f 0 (- (count f) 5))))
        producer (pj/oparse (slurp (str CORPUS "/PRODUCER.json") :encoding "UTF-8"))
        docs (volatile! (om))
        _ (.mkdirs (io/file CORPUS "gold"))]
    (doseq [stem stems]
      (let [hp (str CORPUS "/html/" stem ".html")
            pp (str CORPUS "/pdf/" stem ".pdf")
            html (slurp hp :encoding "UTF-8")
            [sizes prod] (page-sizes pp)
            rp (row-pages html pp (count sizes))
            facts (cond-> (om "pages" sizes) (seq rp) (assoc "rows" rp))
            rel (fn [d & [ext]] (str d "/" stem (or ext ".pdf")))
            files (cond-> (om "html" (rel "html" ".html") "pdf" (rel "pdf"))
                    (.exists (io/file CORPUS (rel "pdf-tagged"))) (assoc "pdf-tagged" (rel "pdf-tagged"))
                    (.exists (io/file CORPUS (rel "scan"))) (assoc "scan" (rel "scan")))
            entry (om "files" (apply om (mapcat (fn [[k v]] [k (om "path" v "sha256" (sha (str CORPUS "/" v))
                                                                  "bytes" (.length (io/file CORPUS v)))]) files))
                      "pdf-producer" prod "facts" facts)
            prov (om "html" (files "html") "pdf" (files "pdf") "producer" (str (get producer "version") "; " prod))
            variants (into (om) (filter (fn [[k _]] (#{"pdf-tagged" "scan"} k)) files))
            prov (if (seq variants) (assoc prov "variants" variants) prov)
            gold (h2g/convert html (pj/parse (pj/dumps facts)) stem prov)]
        (vswap! docs oassoc stem entry)
        (spit (str CORPUS "/gold/" stem ".gold.json") (h2g/gold-text gold) :encoding "UTF-8")))
    (let [manifest (om "format" 1
                       "note" "Committed so that no gate needs a producer (D041). Regenerate with tools/synth/print.sh."
                       "producer" producer
                       "tools" (om "pdfinfo" (tool-version "pdfinfo" "-v") "magick" (tool-version "magick" "-version"))
                       "docs" @docs)]
      (spit (str CORPUS "/MANIFEST.json") (h2g/gold-text manifest) :encoding "UTF-8"))
    (println (str "  built " (count stems) " documents: MANIFEST.json and gold/*.gold.json"))))
