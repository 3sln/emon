#!/usr/bin/env bb
;; Per-document CER and the fusion metrics of a fusion predictor against the no-engine `emon` (P7.3, D285-D290). NOT a gate.
;;
;;     bb tools/fusion/compare.clj [--predictor emon-fusion|emon-fusion-live] [--all] [ID...]
;;
;; Default predictor `emon-fusion` (the committed replay), default documents the fusion set (test/corpus/synth/FUSION); `--all` takes
;; every document of the pdf variant. Prints, per document, the CER with no engine and fused, `tl-override`, `src-ocr-share` and
;; `ghost-rate`, and the pooled CER of the set.
(require '[babashka.classpath :as cp] '[clojure.string :as str] '[babashka.fs :as fs])
(cp/add-classpath "tools")
(cp/add-classpath "tools/lib")
(require '[accuracy.accuracy :as A])
(let [args (vec *command-line-args*)
      pred (or (second (drop-while #(not= % "--predictor") args)) "emon-fusion")
      all? (some #{"--all"} args)
      ids (remove #(or (str/starts-with? % "--") (= % pred)) args)
      ids (cond (seq ids) ids
                all? nil
                :else (remove str/blank? (str/split-lines (slurp "test/corpus/synth/FUSION"))))]
  (alter-var-root #'A/*variants* (constantly #{"pdf"}))
  (alter-var-root #'A/*ids* (constantly (when ids (set ids))))
  (let [[eb base] (A/evaluate (A/PREDICTORS "emon"))
        [ef fused] (A/evaluate (A/PREDICTORS pred))
        f (fn [v] (if v (format "%.4f" (double v)) "  -   "))]
    (println (format "%-34s %8s %8s %9s %9s %9s" "document" "no-eng" "fused" "tl-over" "ocr-share" "ghost"))
    (doseq [k (sort (keys fused))
            :let [b (get base k) m (get fused k)]]
      (println (format "%-34s %8s %8s %9s %9s %9s%s" (subs k 4) (f (get b "cer")) (f (get m "cer")) (f (get m "tl-override"))
                       (f (get m "src-ocr-share")) (f (get m "ghost-rate"))
                       (if (> (or (get m "cer") 0) (+ (or (get b "cer") 0) 0.0005)) "   <-- WORSE" ""))))
    (println "pooled pdf/all: no engine" (get-in eb ["pdf/all" ":cer"]) "fused" (get-in ef ["pdf/all" ":cer"]))))
