#!/usr/bin/env bb
(require '[babashka.process :refer [process]] '[cheshire.core :as json])
(let [normal @(process ["python3" "tools/oracle/ppocr/measure.py" "sh" "-c" "printf ok"] {:out :string :err :string :shutdown nil})
      start (System/nanoTime)
      capped @(process ["python3" "tools/oracle/ppocr/measure.py" "--timeout" "1" "sleep" "10"] {:out :string :err :string :shutdown nil})]
  (assert (= 0 (:exit normal)))
  (assert (= "ok" (:out normal)))
  (assert (= 124 (:exit capped)))
  (assert (< (/ (- (System/nanoTime) start) 1e9) 5))
  (doseq [r [normal capped]]
    (let [cost (json/parse-string (second (re-find #"emon-cost:([^\n]+)" (:err r))) true)]
      (assert (<= 0 (:cpu_seconds cost)))
      (assert (pos? (:peak_rss_bytes cost)))))
  (println "owned-child completion, CPU/RSS and timeout/reaping contracts passed"))
