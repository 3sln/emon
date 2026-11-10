#!/usr/bin/env bb
;; Experimental audit of recorded eligibility and unchanged incumbent requests.
(require '[babashka.fs :as fs] '[clojure.edn :as edn] '[cheshire.core :as json])
(defn artifact-text [p]
  (if (fs/exists? p) (slurp p)
      (with-open [stream (java.util.zip.GZIPInputStream. (java.io.FileInputStream. (str p ".gz")))]
        (slurp stream))))

(defn pp-entry? [entry]
  (let [r (:req entry) n (count r)]
    (and (= :ocr (:kind entry))
         (some (fn [i] (and (= -3100 (nth r i)) (= 1 (nth r (inc i)))
                            (= (nth r (+ i 2)) (- n i 3))))
               (range 10 (- n 2))))))

(def directory (first *command-line-args*))
(def rows
  (vec (for [recording (sort (filter #(re-matches #"[0-9]+-ppocr\.replay" (str (fs/file-name %))) (fs/list-dir (str directory "/controls"))))
             :let [stem (str (fs/strip-ext recording))
                   transcript (edn/read-string (slurp (str recording "/transcript.edn")))
                   fast (edn/read-string (slurp (str (clojure.string/replace stem #"-ppocr$" "-tesseract") ".replay/transcript.edn")))
                   evidence (json/parse-string (artifact-text (str stem ".evidence")) true)
                   tagged (filter pp-entry? (:entries transcript))
                   raster-pages (set (map :page (filter #(some #{"raster"} (:flags %)) (:epages evidence))))
                   eligible (every? #(and (= 23 (mod (nth (:req %) 2) 256))
                                          (contains? raster-pages (quot (nth (:req %) 2) 256))) tagged)
                   tess (fn [entries] (into {} (for [entry entries :when (and (= :ocr (:kind entry)) (not (pp-entry? entry)))]
                                                     [(:req entry) (:ans entry)])))
                   unchanged (= (tess (:entries fast)) (tess (:entries transcript)))]]
         {:recording (str recording) :tagged (count tagged) :eligible eligible
          :incumbent-requests-and-answers-unchanged unchanged :raster-pages (vec (sort raster-pages))})))
(assert (seq rows) "no recordings found")
(println (json/generate-string rows {:pretty true}))
(assert (every? #(and (:eligible %) (:incumbent-requests-and-answers-unchanged %)) rows))
