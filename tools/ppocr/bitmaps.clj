#!/usr/bin/env bb
;; Base page bitmaps recorded by the measured native runs, not OCR binary images.
(require '[clojure.edn :as edn] '[babashka.fs :as fs] '[cheshire.core :as json])
(def rows
  (vec (mapcat
        (fn [dir]
          (let [transcript (edn/read-string (slurp (str dir "/transcript.edn")))]
            (for [entry (:entries transcript)
                  :when (and (#{:decode :render} (:kind entry)) (:bitmap entry))]
              {:id (str (fs/file-name dir) "/" (:bitmap entry))
               :file (str (fs/absolutize (fs/path dir (:bitmap entry))))})))
        *command-line-args*)))
(println (json/generate-string rows))
