#!/usr/bin/env bb
;; BENCH: what the targeted re-OCR rounds cost (DEVLOG P7.4, SPEC 8.3, 9.8).  NOT a gate: it needs Tesseract (and, for the born-digital set, the pinned pdfium).
;;
;;     bb fusion-bench [--scans[=VARIANT,...]] [--born] [--no-retarget] [ID...]
;;
;; Runs the LIVE Rust Engine over a set of documents with `--stats` and reads, from its stderr, what the page units asked and what the OCR cost:
;;
;;   requests a page     every page unit's requests (the raster or decode, the first OCR pass, the targeted ones): the median and the most
;;   rounds a page       the plan/fulfil rounds a page unit took (at most 5, D207)
;;   OCR time            wall time of the engine in the first pass (`pi-ocr`'s id) against all the targeted passes (every other `ocr` request)
;;
;; Without --scans or --born it does the scans (scan-ovl and scan-color, whose pages have the overlays the passes are for). --no-retarget runs the
;; baseline: the Engine with the page units reading no pixel, as it was before the ink layers and the targeted passes. The driver is the one
;; `predict-emon-live.sh` builds (.cache/emon-rs-live); the born-digital set uses the one `predict-emon-fusion-live.sh` builds.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [sh]]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(def no-retarget? (some #{"--no-retarget"} args))
(def born? (some #{"--born"} args))
(def scan-arg (some #(when (str/starts-with? % "--scans") %) args))
(def variants (if-let [v (and scan-arg (second (str/split scan-arg #"=")))] (str/split v #",") ["scan-ovl" "scan-color"]))
(def ids (vec (remove #(str/starts-with? % "--") args)))

(defn median [xs] (let [s (vec (sort xs)) n (count s)] (when (pos? n) (nth s (quot n 2)))))

(defn drive [live? pdf]
  (let [bin (if live? ".cache/emon-rs-live/target/release/emon-rs" ".cache/emon-rs-fusion/target/release/emon-rs")
        flags (concat (if live? ["--tesseract"] ["--pdfium" "--tesseract" "--ocr-born-digital"]) (when no-retarget? ["--no-retarget"]) ["--stats"])
        r (apply sh {:out :string :err :string} (str (fs/absolutize bin)) (concat flags [pdf]))
        line (first (filter #(str/starts-with? % "page-stats:") (str/split-lines (:err r))))]
    (when line
      (let [[_ rounds reqs first-us targ-us] (re-find #"rounds=\[([^\]]*)\] requests=\[([^\]]*)\] ocr-first-us=(\d+) ocr-targeted-us=(\d+)" line)
            nums (fn [s] (if (str/blank? s) [] (mapv parse-long (str/split s #",\s*"))))]
        {:rounds (nums rounds) :requests (nums reqs) :first (parse-long first-us) :targeted (parse-long targ-us)}))))

(def sets
  (if born?
    [["born" false (vec (remove str/blank? (str/split-lines (slurp "test/corpus/synth/FUSION"))))]]
    (for [v variants] [v true (vec (map #(str/replace (fs/file-name %) #"\.pdf$" "") (sort (fs/glob (str "test/corpus/synth/" v) "*.pdf"))))])))

(let [rows (atom [])]
  (doseq [[set-name live? all-ids] sets
          id (if (seq ids) (filter (set ids) all-ids) all-ids)]
    (let [pdf (if live? (str "test/corpus/synth/" set-name "/" id ".pdf") (str "test/corpus/synth/pdf/" id ".pdf"))
          r (drive live? pdf)]
      (if r
        (do (swap! rows conj (assoc r :id (str set-name "/" id)))
            (println (format "%-40s pages %d  requests %s  rounds %s  ocr first %.2fs targeted %.2fs"
                             (str set-name "/" id) (count (:requests r)) (:requests r) (:rounds r) (/ (:first r) 1e6) (/ (:targeted r) 1e6))))
        (println (str set-name "/" id ": no page stats (is the driver built, with Tesseract?)")))))
  (let [rs @rows
        reqs (mapcat :requests rs)
        rounds (mapcat :rounds rs)
        f (reduce + (map :first rs))
        t (reduce + (map :targeted rs))]
    (println)
    (println (format "%d documents, %d pages%s" (count rs) (count reqs) (if no-retarget? " (BASELINE: no ink layers, no targeted pass)" "")))
    (println (format "requests a page: median %s, most %s (rt-max 24 targeted, plus the raster and the first pass)" (median reqs) (apply max 0 reqs)))
    (println (format "rounds a page:   median %s, most %s (the limit is 5)" (median rounds) (apply max 0 rounds)))
    (println (format "OCR time: first pass %.1fs, targeted %.1fs: the targeted passes cost %.2f of the first" (/ f 1e6) (/ t 1e6) (if (pos? f) (/ (double t) f) 0.0)))))
