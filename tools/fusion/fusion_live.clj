(ns fusion.fusion-live
  "bb fusion-live: the live fusion runs (NOT a gate; nothing from a live set is committed).

    bb fusion-live [--update] [--scope S]    P7.S: `emon-live` with the policy switched on over the pdf, pdf-tagged and scan sets, and D201's contract
    bb fusion-live --stamps [--dumps DIR]    the live metric `stamp-ghost` over mapo80 `val` (P6.6, D209; tools/fusion/live-sets.edn), for each val image with a dump DIR/STEM.json (evidence version 2)
    bb fusion-live --self-test               the pure function on hand-made words and boxes

  stamp-ghost = (OCR words whose centre lies in a labelled stamp box AND reach the body) over (OCR words whose
  centre lies in a labelled stamp box). A word REACHES THE BODY when its centre is in no `fa` box (an artifact
  fusion set aside). Words are the dump's `owords` (`box` [x0 y0 x1 y1] in points), the page size the dump's
  `epages`. A YOLO row `0 cx cy w h` is fractions of the image; the page is the image at 1 px = 1 pt (D088)."
  (:require [accuracy.fusion-report :as R]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn yolo->box
  "[x0 y0 x1 y1] in points of a YOLO row's fractions on a W x H page."
  [[cx cy w h] W H]
  [(* W (- cx (/ w 2))) (* H (- cy (/ h 2))) (* W (+ cx (/ w 2))) (* H (+ cy (/ h 2)))])

(defn- centre [[x0 y0 x1 y1]] [(/ (+ x0 x1) 2.0) (/ (+ y0 y1) 2.0)])
(defn- inside? [[x y] [x0 y0 x1 y1]] (and (<= x0 x x1) (<= y0 y y1)))

(defn stamp-ghost-counts
  "[ghosts in-stamp-words]: `words` OCR word boxes, `stamps` the labelled boxes, `aside` the boxes of the artifacts
  fusion set aside."
  [words stamps aside]
  (let [in-stamp (filter (fn [b] (some #(inside? (centre b) %) stamps)) words)
        reach (remove (fn [b] (some #(inside? (centre b) %) aside)) in-stamp)]
    [(count reach) (count in-stamp)]))

(defn- read-labels [f]
  (vec (for [l (str/split-lines (slurp f)) :when (seq (str/trim l))
             :let [[c & xs] (str/split (str/trim l) #"\s+")] :when (= c "0")]
         (mapv parse-double xs))))

(defn run-stamps [root dumps-dir]
  (let [{:keys [sets]} (edn/read-string (slurp (str root "/tools/fusion/live-sets.edn")))
        {:keys [images labels]} (:mapo80-val sets)
        imgs (when (fs/exists? (str root "/" images)) (sort (map str (fs/list-dir (str root "/" images)))))]
    (if (nil? imgs)
      (println (str "  SKIP fusion-live: " images " is not there (corpus/fetch.sh stamps); nothing measured"))
      (let [rows (for [img imgs
                       :let [stem (str/replace (str (fs/file-name img)) #"\.[^.]+$" "")
                             dump (str dumps-dir "/" stem ".json")]
                       :when (fs/exists? dump)
                       :let [d (R/parse-dump (slurp dump))
                             pg (first (get d "epages"))
                             W (get pg "width") H (get pg "height")
                             lf (str root "/" labels "/" stem ".txt")
                             stamps (mapv #(yolo->box % W H) (if (fs/exists? lf) (read-labels lf) []))]]
                   (stamp-ghost-counts (map #(get % "box") (get d "owords")) stamps (keep #(get % "box") (get d "fa"))))
            g (reduce + (map first rows))
            n (reduce + (map second rows))]
        (if (empty? rows)
          (println (str "  SKIP fusion-live: no evidence dumps in " dumps-dir " (the fusion driver of P7.S writes them); nothing measured"))
          (println (format "  stamp-ghost: %d of %d OCR words inside a labelled stamp reach the body = %s (%d images; lower is better, not gated)"
                           g n (if (pos? n) (format "%.4f" (/ (double g) n)) "n/a") (count rows))))))))

(defn self-test []
  (let [W 200 H 100
        box (yolo->box [0.5 0.5 0.2 0.4] W H)               ; centre (100,50), 40 x 40 pt: [80 30 120 70]
        ;; centres: word 0 (90,40) in the box; word 1 (105,45) in the box; word 2 (15,15) outside;
        ;; word 3 (120,64) on the box's right edge (x <= 120): inside. So 3 words in the stamp
        words [[85 35 95 45] [100 40 110 50] [10 10 20 20] [115 60 125 68]]
        aside [[80 30 100 50]]                              ; contains word 0's centre (90,40) only
        [g n] (stamp-ghost-counts words [box] aside)]
    (if (and (= [80.0 30.0 120.0 70.0] (mapv double box)) (= [2 3] [g n]))
      (println "  ok   fusion-live self-test: YOLO fractions to points; stamp-ghost = 2 of 3 on the hand-made words")
      (do (println (str "  FAIL fusion-live self-test " box " " [g n])) (System/exit 1)))))

;; ---------------------------------------------------------------- the live run of P7.S

(defn- a-var [s] (ns-resolve (find-ns 'accuracy.accuracy) s))

(defn run-live
  "`emon-live` with the policy switched on (every born-digital page OCR'd and fused: the Engine's defaults with pdfium and Tesseract) over every
  `pdf/`, `pdf-tagged/`, `scan/`, `scan-color/` and `scan-ovl/` document: the scoreboard's `emon-live` entry (with `update?`, ratcheted as `bb accuracy` does), and the
  born-digital contract of D201 against the committed `emon` entry: `pdf/all` CER no worse than emon's, every `pdf/` category and `pdf-tagged/all` within 0.001,
  `tl-override` 0 on every document but `tl-broken-*`, `ghost-rate` at most 0.001 and `watermark-leak` 0 on `pdf/`. Prints, for every category, `emon` against
  `emon-live`. `scope` (`full`/`witness`) passes `--ocr-scope` on; a `witness` run is never recorded."
  [update? scope]
  (require 'accuracy.accuracy)
  (let [PRED (var-get (a-var 'PREDICTORS))
        cmd (if scope ["env" (str "EMON_RS_FLAGS=--ocr-scope=" scope) "sh" (last (PRED "emon-live"))] (PRED "emon-live"))
        board ((var-get (a-var 'load-board)))
        fails (atom 0)
        ok (fn [& xs] (println (str "  ok   " (apply str xs))))
        bad (fn [& xs] (swap! fails inc) (println (str "  FAIL " (apply str xs))))]
    (with-bindings {(a-var '*variants*) #{"pdf" "pdf-tagged" "scan" "scan-color" "scan-ovl"}}
      (let [[entry per] ((var-get (a-var 'evaluate)) cmd)]
        (if (nil? entry)
          (println (str "  SKIP fusion-live: the live predictor is not available (" per ")"))
          (let [base (get-in board [":predictors" "emon"])
                m (fn [e k mm] (get-in e [k mm]))
                cats (sort (filter #(or (str/starts-with? % "pdf/") (str/starts-with? % "pdf-tagged/")) (keys entry)))]
            (println "  category                         metric      emon -> emon-live (the switch on)")
            (doseq [k cats mm [":cer" ":wer"]]
              (println (format "  %-32s %-10s %s -> %s" k mm (m base k mm) (m entry k mm))))
            (let [b (m base "pdf/all" ":cer") l (m entry "pdf/all" ":cer")]
              (if (and b l (<= l b)) (ok (format "pdf/all CER %s <= emon's %s" l b)) (bad (format "pdf/all CER %s is above emon's %s" l b))))
            (doseq [k cats :when (not= k "pdf/all")
                    :let [b (m base k ":cer") l (m entry k ":cer")] :when b]
              (if (and l (<= l (+ b 0.001))) (ok (format "%s CER %s within 0.001 of emon's %s" k l b)) (bad (format "%s CER %s is more than 0.001 above emon's %s" k l b))))
            (doseq [[k per-doc] per
                    :let [[v id] (str/split k #"/" 2)] :when (= v "pdf")
                    :let [t (get per-doc "tl-override")] :when (and t (not (str/starts-with? id "tl-broken")))]
              (if (zero? t) nil (bad (format "%s tl-override %s on a healthy document" id t))))
            (ok "tl-override is zero on every checked document that is not tl-broken-*" (if (zero? @fails) "" " (see FAIL lines)"))
            (let [g (m entry "pdf/all" ":ghost-rate") w (m entry "pdf/all" ":watermark-leak")]
              (if (or (nil? g) (<= g 0.001)) (ok "ghost-rate " g " <= 0.001 on pdf/") (bad "ghost-rate " g " > 0.001 on pdf/"))
              (if (or (nil? w) (zero? w)) (ok "watermark-leak " w " on pdf/") (bad "watermark-leak " w " on pdf/")))
            (when (and update? (nil? scope) (zero? @fails))
              (let [old (get-in board [":predictors" "emon-live"])
                    [reg _ gone] (if old ((var-get (a-var 'compare-boards)) old entry (get board ":tolerance" 0.002)) [[] [] []])]
                (if (or (seq reg) (seq gone))
                  (bad "emon-live regressed or lost metrics: " (count reg) " / " (count gone))
                  (do ((var-get (a-var 'save-board))
                       (assoc-in board [":predictors" "emon-live"] ((var-get (a-var 'merge-up)) (or old (array-map)) entry)))
                      (ok "scoreboard emon-live recorded (the ratchet moves toward better only)")))))))))
    (when (pos? @fails) (System/exit 1))))

(defn -main [& args]
  (cond
    (or (empty? args) (#{"--run" "--update" "--scope"} (first args)))
    (let [scope (second (drop-while #(not= % "--scope") args))]
      (run-live (boolean (some #{"--update"} args)) scope))
    (= args ["--self-test"]) (self-test)
    (= (first args) "--stamps")
    (let [dumps (second (drop-while #(not= % "--dumps") args))
          root (str (fs/canonicalize "."))
          {:keys [dumps-dir]} (edn/read-string (slurp (str root "/tools/fusion/live-sets.edn")))]
      (run-stamps root (or dumps (str root "/" dumps-dir))))
    :else (do (println "usage: bb fusion-live [--update] [--scope full|witness] | --stamps [--dumps DIR] | --self-test") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
