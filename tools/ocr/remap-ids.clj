#!/usr/bin/env bb
;; Re-key the committed SCAN transcripts of test/ocr/scan/ from D211's request ids to D207's per-page-unit ids (P7.U), WITHOUT the engine.
;;
;;     bb tools/ocr/remap-ids.clj ID...        each ID is a directory test/ocr/scan/ID (its `transcript.edn`)
;;
;; D211 asked the PDF page `i` (0-based) as decode `2i + 1` and OCR `2i + 2`; D207 asks them as decode `(i + 1) * 256 + 4` and OCR
;; `(i + 1) * 256 + 16`, and each page is its own plan/fulfil loop, so the entries are in page order, each page's decode (round 0)
;; then its OCR (round 1). The engine's ANSWERS are what the transcript is (SPEC 7.3, D214), and an answer does not depend on the id
;; it is given: only the id cell of the request, of the answer, and the OCR request's `bitmap` (the decode's id) change, so a
;; re-key is exactly what a fresh recording would be, without running Tesseract again (whose answer a new build may move, D211).
;; Anything else in a file (the producer, the inputs) is kept. A transcript already in the new ids is left as it is.
(require '[clojure.string :as str])

(defn new-id [old] (let [i (quot (dec old) 2)] (if (odd? old) (+ (* (inc i) 256) 4) (+ (* (inc i) 256) 16))))

(defn remap [text]
  (let [[head tail] (str/split text #"\n :entries \[" 2)
        entries (re-seq #"\{:round (\d+) :kind :(\w+) :req \[([^\]]*)\] :ans \[([^\]]*)\]\}" tail)
        parse (fn [s] (mapv parse-long (str/split (str/trim s) #"\s+")))
        mapped (for [[_ round kind req ans] entries]
                 (let [req (parse req) ans (parse ans)
                       id (nth req 2)
                       nid (new-id id)
                       req (cond-> (assoc req 2 nid)
                             (= kind "ocr") (assoc 3 (new-id (nth req 3))))
                       ans (assoc ans 2 nid)]
                   {:page (quot (dec nid) 256) :id nid :round round :kind kind :req req :ans ans}))
        sorted (sort-by (juxt :page :id) mapped)
        fmt (fn [{:keys [round kind req ans]}]
              (str "\n  {:round " round " :kind :" kind " :req [" (str/join " " req) "] :ans [" (str/join " " ans) "]}"))]
    (str head "\n :entries [" (str/join "" (map fmt sorted)) "]}\n")))

(doseq [id *command-line-args*]
  (let [f (str "test/ocr/scan/" id "/transcript.edn")
        t (slurp f)]
    (if (str/includes? t ":req [8 2 1 ")
      (do (spit f (remap t)) (println "re-keyed" f))
      (println "already in D207's ids:" f))))
