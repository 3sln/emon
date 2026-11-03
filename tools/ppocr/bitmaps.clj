#!/usr/bin/env bb
;; Base page bitmaps recorded by the measured native runs, not OCR binary images.
(require '[clojure.edn :as edn] '[babashka.fs :as fs] '[cheshire.core :as json])

(defn pp-entry? [entry]
  (let [r (:req entry) n (count r)]
    (and (= :ocr (:kind entry))
         (some (fn [i] (and (= -3100 (nth r i)) (= 1 (nth r (inc i)))
                            (= (nth r (+ i 2)) (- n i 3))))
               (range 10 (- n 2))))))

(def rows
  (vec (mapcat
        (fn [dir]
          (let [transcript (edn/read-string (slurp (str dir "/transcript.edn")))
                pp-bitmaps (set (for [entry (:entries transcript)
                                      :when (pp-entry? entry)]
                                  (nth (:req entry) 3)))]
            (for [entry (:entries transcript)
                  :when (and (#{:decode :render} (:kind entry)) (:bitmap entry) (contains? pp-bitmaps (nth (:req entry) 2)))]
              {:id (str (fs/file-name dir) "/" (:bitmap entry))
               :page (quot (nth (:req entry) 2) 256) :request (nth (:req entry) 2)
               :file (str (fs/absolutize (fs/path dir (:bitmap entry))))})))
        *command-line-args*)))
(println (json/generate-string rows))
