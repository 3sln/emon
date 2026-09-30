(ns import.import-all
  "Run every public-dataset importer and report counts and validation (DEVLOG P2.11).   bb import

      bb import [--limit N] [name ...]

  Each importer writes corpus/_gold/<name>/*.gold.json (outside git) and checks every gold: ids,
  page refs and regions here, schema/emon-gold-1.json by the jsonschema oracle
  (tools/oracle/gold_validate.py).  A dataset that is not in the local corpus is reported as MISSING
  (run corpus/fetch.sh), not as a failure; any invalid gold or an importer error fails the run.
  NOT part of `bb gates`: the corpus is not in the repository.  EMON_CORPUS / EMON_GOLD_OUT move the
  input and output trees."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj]
            [import.funsd] [import.xfund] [import.sroie] [import.cord] [import.doclaynet]
            [import.icdar2013] [import.ctdar] [import.fintabnet-otsl] [import.pdfa-eng-wds] [import.stamps]))

;; name, the importer's entry point, the path (under corpus/) that must exist
(def importers
  [["funsd" import.funsd/import! "forms/funsd/dataset"]
   ["xfund" import.xfund/import! "multilingual/xfund"]
   ["sroie" import.sroie/import! "financial/sroie2019"]
   ["cord" import.cord/import! "financial/cord-v2"]
   ["doclaynet" import.doclaynet/import! "layout/doclaynet-v1.2"]
   ["icdar2013" import.icdar2013/import! "tables/icdar2013"]
   ["ctdar" import.ctdar/import! "tables/icdar2019-ctdar"]
   ["fintabnet_otsl" import.fintabnet-otsl/import! "tables/fintabnet-otsl"]
   ["pdfa_eng_wds" import.pdfa-eng-wds/import! "layout/pdfa-eng-wds"]
   ["stamps" import.stamps/import! "seals/mapo80-stamps"]])

(defn- parse-args [argv]
  (loop [a argv limit 0 names []]
    (cond (empty? a) {:limit limit :names names}
          (= "--limit" (first a)) (recur (drop 2 a) (parse-long (second a)) names)
          :else (recur (rest a) limit (conj names (first a))))))

(defn -main [& argv]
  (let [{:keys [limit names]} (parse-args argv)
        rows (atom []) bad (atom 0)]
    (doseq [[name f need] importers
            :when (or (empty? names) (some #{name} names))]
      (if-not (fs/directory? (str C/corpus-dir "/" need))
        (swap! rows conj [name "MISSING" 0 0 0 (str need " not in the corpus (corpus/fetch.sh)")])
        (let [out (java.io.StringWriter.)
              rc (try (binding [*out* out] (f {:limit limit}))
                      (catch Throwable e (str "ERROR " (or (ex-message e) (str e)))))
              s (try (pj/parse (last (str/split-lines (str out)))) (catch Throwable _ nil))]
          (cond
            (or (string? rc) (nil? s))
            (do (swap! bad inc)
                (swap! rows conj [name "ERROR" 0 0 0 (subs (str (if (string? rc) rc "no output")) 0 (min 100 (count (str (if (string? rc) rc "no output")))))]))
            :else
            (let [ok (zero? rc)
                  note (str/join "; " (for [[k v] (get s "warnings")] (str k " x" v)))
                  note (if-let [ex (first (get s "invalid_examples"))] (str ex " | " note) note)]
              (when-not ok (swap! bad inc))
              (swap! rows conj [name (if ok "ok" "FAIL") (get s "written") (get s "invalid") (get s "skipped") note]))))))
    (println (format "%-16s %-7s %8s %8s %8s  %s" "dataset" "status" "written" "invalid" "skipped" "notes"))
    (doseq [r @rows] (println (apply format "%-16s %-7s %8d %8d %8d  %s" r)))
    (println (format "total %d gold files under %s; %s" (reduce + (map #(nth % 2) @rows))
                     (str (fs/relativize C/root C/out-dir)) (if (pos? @bad) "FAILED" "all valid")))
    (if (pos? @bad) 1 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
