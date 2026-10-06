(ns accuracy.baselines
  "bb baselines: the field's numbers (P2.10, SPEC 14.4, D500-D503). NOT a gate, never in `bb gates`.

    bb baselines --setup [TOOL...]     create the pinned Python environments under .cache/ from tools/oracle/requirements-*.txt
    bb baselines [TOOL...]             extract (resumable, one process, nice), score, record under :references, report
    bb baselines --extract [TOOL...]   only run the extractors into .cache/baselines/TOOL/VARIANT/ID.json
    bb baselines --score [TOOL...]     only score what is extracted and record it in test/scoreboard.edn :references
    bb baselines --report              print emon against the recorded references (the DEVLOG's table)
    --force                            re-extract what is already extracted

TOOL is pdfplumber, pymupdf4llm, docling or tesseract (default: all four). Python is the third-party oracles' (D094): the extractors
live in tools/oracle/baselines.py and run in venvs under .cache/ (gitignored); everything else is babashka. The scoring is `bb accuracy
--predictor baseline-TOOL --update` (a reference: replaced, never ratcheted, never read by a gate)."
  (:require [babashka.fs :as fs]
            [babashka.process :as proc]
            [clojure.string :as str]
            [accuracy.accuracy :as A]))

(def ROOT A/ROOT)
(def TOOLS ["pdfplumber" "pymupdf4llm" "docling" "tesseract"])

;; which environment runs which tool: tesseract needs no packages (stdlib, poppler and tesseract on the PATH)
(def ENV {"pdfplumber" ["oracle-venv" "requirements-baselines.txt"]
          "pymupdf4llm" ["oracle-venv" "requirements-baselines.txt"]
          "docling" ["oracle-venv-docling" "requirements-docling.txt"]
          "tesseract" nil})

(def PYTHON "python3.12")

(defn- sh! [& args]
  (let [p (apply proc/shell {:dir ROOT :continue true} args)]
    (when-not (zero? (:exit p)) (println "FAIL" (str/join " " args)) (System/exit 1))))

(defn python-of [tool]
  (if-let [[venv _] (ENV tool)]
    (let [p (str (fs/path ROOT ".cache" venv "bin" "python"))]
      (when-not (fs/exists? p)
        (println (format "FAIL no environment for %s: run  bb baselines --setup %s" tool tool))
        (System/exit 1))
      p)
    "python3"))

(defn setup [tools]
  (doseq [[dir req] (distinct (keep ENV tools))]
    (let [d (str (fs/path ROOT ".cache" dir))]
      (when-not (fs/exists? (fs/path d "bin" "python"))
        (sh! "nice" PYTHON "-m" "venv" d))
      (sh! "nice" (str (fs/path d "bin" "pip")) "install" "-r" (str (fs/path ROOT "tools" "oracle" req))))))

(defn extract [tools force]
  (doseq [t tools]
    (apply sh! (concat ["nice" (python-of t) (str (fs/path ROOT "tools" "oracle" "baselines.py")) t] (when force ["--force"])))))

(defn score [tools]
  (doseq [t tools]
    (sh! "bb" "tools/accuracy/accuracy.clj" "--predictor" (str "baseline-" t) "--update")))

;; ------------------------------------------------------------------- report ----

(def REPORT-CATS ["all" "headings" "tables" "lists" "columns" "furniture" "footnotes" "scripts"])
(def REPORT-METRICS [":cer" ":wer" ":bow-f1" ":order-tau" ":block-heading-f1" ":heading-level-acc" ":block-paragraph-f1"
                     ":para-boundary-f1" ":block-list-f1" ":block-table-f1" ":table-teds-lite" ":table-teds-struct-lite"
                     ":furniture-f1"])

(defn fmt [v] (if (nil? v) "-" (A/fmt-f v 3)))

(defn report
  "emon (its committed ratchet entry `pred`) against the references recorded for `variant`."
  [tools variant pred]
  (let [board (A/load-board)
        emon (get-in board [":predictors" pred])
        refs (for [t tools :let [r (get-in board [":references" (str "baseline-" t)])]
                   :when (and r (contains? (set (str/split (get r ":variants" "") #",")) variant))]
               [t r])]
    (when (seq refs)
     (println (format "%s against the field, variant %s (%s: its committed ratchet entry; references: %s)"
                     pred variant pred (str/join ", " (map (fn [[_ r]] (get r ":version")) refs))))
     (doseq [cat REPORT-CATS]
      (let [k (str variant "/" cat)
            rows (for [m REPORT-METRICS
                       :let [vs (cons (get-in emon [k m]) (map (fn [[_ r]] (get-in r [":scores" k m])) refs))]
                       :when (some some? vs)]
                   (str (format "%-24s" m) (str/join "" (map #(format "%12s" (fmt %)) vs))))]
        (when (seq rows)
          (println)
          (println (format "%-24s%12s%s" k pred (str/join "" (map (fn [[t _]] (format "%12s" t)) refs))))
          (run! println rows)))))))

(defn report-all [tools]
  (report tools "pdf" "emon")
  (report tools "pdf-tagged" "emon")
  (report tools "scan" "emon-live"))

(defn -main [& argv]
  (let [flags (set (filter #(str/starts-with? % "--") argv))
        tools (let [ts (remove #(str/starts-with? % "--") argv)] (if (seq ts) (vec ts) TOOLS))
        bad (remove (set TOOLS) tools)]
    (when (seq bad) (println "unknown tool:" (str/join " " bad)) (System/exit 2))
    (cond
      (flags "--setup") (setup tools)
      (flags "--report") (report-all tools)
      :else
      (let [only-x (flags "--extract") only-s (flags "--score")]
        (when (or only-x (not only-s)) (extract tools (flags "--force")))
        (when (or only-s (not only-x)) (score tools))
        (when-not (or only-x only-s) (report-all tools))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
