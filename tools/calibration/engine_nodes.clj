#!/usr/bin/env bb
;; The NODE-level rows of a platform engine, for `bb calibration --fit --engine N --rows FILE` (K8, D900; D883 ruling 3).
;;
;;     bb tools/calibration/engine_nodes.clj --engine apple-vision [--jobs 3] [--out FILE]
;;
;; The Model's node confidences (a heading, a paragraph, a cell: SPEC 9.6) are calibrated per kind and per source class by `kin/calibdata.kin`, whose rows now have an ENGINE column (D883
;; section 4): the OCR class is looked up for the primary engine of the page, then for any. This runs the engine LIVE through its predictor over the 55 scan variants of the synthetic corpus,
;; the same way `bb calibration` collects its rows for Tesseract, and writes the rows {:run :variant :cat :id :kind :class :engine :conf :y} (raw confidences: the engine's own map is not applied
;; to a Model made for this) to FILE (default $EMON_SCRATCH/calib-<engine>/node-rows.edn). `bb calibration --fit --engine 3 --rows FILE` fits the per-kind isotonic map, reports the held-out
;; (five-fold, by document) ECE and Brier per kind and writes test/calibration/engines/3/ and the engine's rows of kin/calibdata.kin; kinds with fewer than 8 nodes keep the any-engine row.
(require '[accuracy.accuracy :as A] '[accuracy.confrows :as CR] '[babashka.fs :as fs] '[babashka.process :as proc] '[clojure.string :as str] '[pyjson])

(def args (vec *command-line-args*))
(defn opt [f d] (or (second (drop-while #(not= f %) args)) d))
(def engines {"apple-vision" {:oen 3 :predictor "emon-live-vision"}})
(def cfg (or (get engines (opt "--engine" nil)) (do (println "FAIL usage: engine_nodes.clj --engine" (str/join "|" (keys engines))) (System/exit 1))))
(def scratch (str (or (System/getenv "EMON_SCRATCH") (str A/ROOT "/.cache/scratch")) "/calib-" (opt "--engine" nil)))
(def out (opt "--out" (str scratch "/node-rows.edn")))

(defn predict [cmd [doc-id variant inp gp]]
  (let [r (apply proc/sh (concat cmd [doc-id variant inp gp]))]
    (case (:exit r)
      0 (let [m (pyjson/parse (:out r))]
          (vec (for [[kind class conf y] (CR/rows m (pyjson/parse-file gp))]
                 {:run (:predictor cfg) :variant variant :cat (A/category doc-id) :id doc-id :kind kind :class class
                  :engine (if (#{"ocr" "fused"} class) (:oen cfg) -1) :conf conf :y y})))
      3 []
      (throw (ex-info (format "predictor exited %d on %s/%s: %s" (:exit r) variant doc-id (str/trim (:err r))) {})))))

(let [cmd (A/PREDICTORS (:predictor cfg))
      docs (vec (filter (fn [[_ v]] (= v "scan")) (A/corpus)))
      n (parse-long (opt "--jobs" "3"))
      done (atom 0)
      rows (vec (mapcat identity
                        (mapcat (fn [chunk] (mapv deref (mapv (fn [d] (future (let [r (predict cmd d)] (println (format "%d/%d %s: %d rows" (swap! done inc) (count docs) (first d) (count r))) (flush) r))) chunk)))
                                (partition-all n docs))))]
  (fs/create-dirs (fs/parent out))
  (spit out (pr-str rows))
  (println (format "wrote %s: %d rows from %d documents, %d of the engine's OCR class" out (count rows) (count docs) (count (filter #(= (:oen cfg) (:engine %)) rows)))))
