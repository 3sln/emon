#!/usr/bin/env bb
;; ICDAR 2013 table competition, LIVE, against the graphics-fusion stage (DEVLOG P7.G, D1320-D1327; D182's numbers). NOT a gate: it needs corpus/tables/icdar2013 and corpus/_gold/icdar2013
;; (`bb corpus-pull`, `bb import icdar2013`), the pinned pdfium and Tesseract, and takes minutes. Nothing of the set is committed.
;;
;;     bb gfuse-icdar --record DIR [NAME...]      convert each document LIVE (pdfium renders, the tracer reads the pixels, Tesseract reads the page, the Engine's default settings)
;;                                                and record what the ports answered WITH the bitmaps, into DIR/NAME/ (default: every document with a gold)
;;     bb gfuse-icdar --score DIR [--driver LABEL=BIN ...] [--per-doc]
;;                                                replay the recordings through each driver (default: the plain driver `emon-rs-build` makes, as LABEL `after`) and score
;;                                                the tables against the gold: block-table P/R, table-teds-lite, table-adj-f1 (ICDAR 2013's adjacency relations), pooled
;;     bb gfuse-icdar --vector [--per-doc]        the vector source alone (`--ocr=never`, no tracer, no OCR), as D182 measured it
;;
;; BEFORE and AFTER of graphics fusion are two drivers over the SAME recordings: one built with `gf-fuse` skipped (kin/engine.kin, `en-finish-pdf`) and one built as the tree is. So the OCR
;; is read once and the comparison is the arbitration's alone.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(cp/add-classpath "tools")
(cp/add-classpath "tools/lib")
(require '[accuracy.score :as S])

(def args (vec *command-line-args*))
(defn opt [k] (second (drop-while #(not= % k) args)))
(def per-doc? (some #{"--per-doc"} args))
(def gold-dir "corpus/_gold/icdar2013")
(def names
  (let [named (vec (remove #(or (str/starts-with? % "--") (= % (opt "--record")) (= % (opt "--score")) (str/includes? % "=")) args))]
    (if (seq named)
      named
      (vec (sort (for [f (fs/glob gold-dir "*.gold.json")] (str/replace (str (fs/file-name f)) #"\.gold\.json$" "")))))))
(defn gold-of [n] (json/parse-string (slurp (str gold-dir "/" n ".gold.json"))))
(defn pdf-of [n] (str "corpus/" (get-in (gold-of n) ["provenance" "pdf"])))

(defn convert [bin n extra]
  (let [r (apply sh {:out :string :err :string} bin (concat extra [(pdf-of n)]))]
    (when (zero? (:exit r)) (json/parse-string (:out r)))))

(defn score-with [bin n extra]
  (when-let [m (convert bin n extra)]
    (get (S/score m (gold-of n) {:variant "pdf"}) "counts")))

(defn report [label per]
  (let [c (S/pool (map second per)) m (S/metrics c)]
    (println (format "%-8s %2d documents  block-table P %.3f R %.3f  table-teds-lite %.3f  struct %.3f  table-adj-f1 %.3f (P %.3f R %.3f)"
                     label (count per) (double (get m "block-table-p" 0)) (double (get m "block-table-r" 0)) (double (get m "table-teds-lite" 0))
                     (double (get m "table-teds-struct-lite" 0)) (double (get m "table-adj-f1" 0)) (double (get m "table-adj-p" 0)) (double (get m "table-adj-r" 0))))
    (when per-doc?
      (doseq [[n cs] per]
        (let [x (S/metrics cs)]
          (println (format "   %-22s teds %s adj %s block-table R %s" n (some-> (get x "table-teds-lite") (->> (format "%.3f"))) (some-> (get x "table-adj-f1") (->> (format "%.3f")))
                           (some-> (get x "block-table-r") (->> (format "%.3f"))))))))))

(cond
  (opt "--record")
  (let [dir (opt "--record")
        _ (shell {:out :string :err :string} "bb" "tools/pdfium/fetch.clj")
        live (str/trim (:out (sh {:err :inherit :extra-env {"EMON_RS_PDFIUM" (str (fs/real-path ".cache/pdfium/current/lib"))}} "kin/scripts/emon-rs-build")))]
    (fs/create-dirs dir)
    (doseq [n names :let [out (str (fs/absolutize (str dir "/" n)))]]
      (if (fs/exists? (str out "/transcript.edn"))
        (println "kept" n)
        (let [t0 (System/currentTimeMillis)
              r (sh {:out :string :err :string} live "--pdfium" "--tesseract" "--out=json" (str "--record=" out) (pdf-of n))]
          (println (if (zero? (:exit r)) "recorded" (str "FAILED(" (:exit r) ")")) n (- (System/currentTimeMillis) t0) "ms")
          (when-not (zero? (:exit r)) (fs/delete-tree out))))))

  (opt "--score")
  (let [dir (opt "--score")
        drivers (or (seq (for [a args :when (str/starts-with? a "--driver=")] (str/split (subs a 9) #"=" 2)))
                    [["after" (str/trim (:out (sh {:err :inherit} "kin/scripts/emon-rs-build")))]])
        have (filter #(fs/exists? (str dir "/" % "/transcript.edn")) names)]
    (println (count have) "recordings of" (count names) "documents")
    (doseq [[label bin] drivers]
      (report label (for [n have :let [c (score-with bin n [(str "--replay=" (fs/absolutize (str dir "/" n)))])] :when c] [n c]))))

  (some #{"--vector"} args)
  (let [bin (str/trim (:out (sh {:err :inherit} "kin/scripts/emon-rs-build")))]
    (report "vector" (for [n names :let [c (score-with bin n ["--ocr=never"])] :when c] [n c])))

  :else (println "usage: bb gfuse-icdar --record DIR [NAME...] | --score DIR [--driver=LABEL=BIN ...] [--per-doc] | --vector [--per-doc]"))
