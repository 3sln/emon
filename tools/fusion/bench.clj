#!/usr/bin/env bb
;; BENCH: what OCR costs, by stage, and what each `ocr-scope` gives for it (DEVLOG P7.4, P7.S; SPEC 8.3, 9.8, 12.3; D200, D210). NOT a gate: it needs Tesseract and
;; the pinned pdfium (`bb pdfium-fetch`), and takes minutes.
;;
;;     bb fusion-bench [--scans[=VARIANT,...]] [--born] [--scopes] [--no-retarget] [--ocr-scope=S] [ID...]
;;
;; --born --scopes  THE TABLE OF D200: the fusion set (test/corpus/synth/FUSION, or the IDs) through the LIVE Rust Engine (pdfium and Tesseract) three ways,
;;                  `--no-retarget` (no pixel read), `--ocr-scope=full` and `--ocr-scope=witness`: per stage wall time a page (the ports' render and OCR time and the
;;                  page units' kin time, from `--stats`' `time-stats`; the document unit), requests and rounds a page, the peak memory of the process, and the accuracy
;;                  of `full` against `witness` (per-document CER and tl-override, and every pooled metric that moved, by `accuracy.accuracy`'s scorer). The OCR time of
;;                  `witness` is held against 30 % of `full`'s on the median page (SPEC 12.3).
;; --born           the born-digital set, one scope (`--ocr-scope=S` is passed on)
;; --scans          the scan variants (scan-ovl and scan-color by default), as P7.4 measured the targeted passes
;; --no-retarget    the baseline: the Engine with the page units reading no pixel
;;
;; The driver is `.cache/emon-rs-fusion` (built by `predict-emon-fusion-live.sh`), which has both pdfium and Tesseract.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [sh]]
         '[babashka.classpath :as cp]
         '[clojure.string :as str])

(cp/add-classpath "tools")
(cp/add-classpath "tools/lib")

(def args (vec *command-line-args*))
(def no-retarget? (some #{"--no-retarget"} args))
(def born? (some #{"--born"} args))
(def scopes? (some #{"--scopes"} args))
(def scan-arg (some #(when (str/starts-with? % "--scans") %) args))
(def variants (if-let [v (and scan-arg (second (str/split scan-arg #"=")))] (str/split v #",") ["scan-ovl" "scan-color"]))
(def ids (vec (remove #(str/starts-with? % "--") args)))
(def extra-flags (vec (filter #(str/starts-with? % "--ocr-scope=") args)))

(defn median [xs] (let [s (vec (sort xs)) n (count s)] (when (pos? n) (nth s (quot n 2)))))
(defn sum [xs] (reduce + 0 xs))
(defn secs [us] (/ (double us) 1e6))

(def bin (str (fs/absolutize ".cache/emon-rs-fusion/target/release/emon-rs")))

(defn ensure-driver! []
  (let [r (sh {:out :string :err :string} "sh" "tools/accuracy/predict-emon-fusion-live.sh" "x" "pdf" "test/corpus/synth/pdf/h-plain.pdf" "x")]
    (when-not (fs/exists? bin) (println "no driver:" (:err r)) (System/exit 3))))

(defn num-of [line k] (or (some-> (re-find (re-pattern (str k "=(\\d+)")) line) second parse-long) 0))

(defn drive
  "One conversion with --stats under /usr/bin/time -l: the stats of the page units and the ports, and the peak resident set (bytes)."
  [pdf flags]
  (let [r (apply sh {:out :string :err :string} "/usr/bin/time" "-l" bin "--pdfium" "--tesseract" (concat flags ["--stats" pdf]))
        err (:err r)
        pl (first (filter #(str/starts-with? % "page-stats:") (str/split-lines err)))
        tl (first (filter #(str/starts-with? % "time-stats:") (str/split-lines err)))
        rss (some-> (re-find #"(\d+)\s+maximum resident set size" err) second parse-long)
        nums (fn [s] (if (str/blank? s) [] (mapv parse-long (str/split s #",\s*"))))]
    (when (and pl tl)
      (let [[_ rounds reqs] (re-find #"rounds=\[([^\]]*)\] requests=\[([^\]]*)\]" pl)]
        {:rounds (nums rounds) :requests (nums reqs)
         :render (num-of tl "render-us") :crop (num-of tl "crop-us") :decode (num-of tl "decode-us")
         :ocr-first (num-of tl "ocr-first-us") :ocr-later (num-of tl "ocr-later-us")
         :pass (num-of tl "pass-us") :pages (num-of tl "pages-us") :finish (num-of tl "finish-us") :rss (or rss 0)}))))

(def fusion-ids (vec (remove str/blank? (str/split-lines (slurp "test/corpus/synth/FUSION")))))

(defn run-scope [doc-ids flags]
  (vec (for [id doc-ids
             :let [r (drive (str "test/corpus/synth/pdf/" id ".pdf") flags)]
             :when r]
         (assoc r :id id :n (count (:requests r))))))

(defn report [label rs]
  (let [pages (max 1 (sum (map :n rs)))
        ports (+ (sum (map :render rs)) (sum (map :ocr-first rs)) (sum (map :ocr-later rs)) (sum (map :crop rs)) (sum (map :decode rs)))
        kin (- (sum (map :pages rs)) ports)
        ocr (+ (sum (map :ocr-first rs)) (sum (map :ocr-later rs)))]
    (println (format "%-14s %2d docs %3d pages | render %.3fs, OCR %.3fs (first %.3f, later %.3f), page units' kin %.3fs, document unit %.4fs, document pass %.4fs (all a page) | requests/page median %s, rounds median %s | peak RSS max %.0f MB"
                     label (count rs) pages
                     (/ (secs (sum (map :render rs))) pages) (/ (secs ocr) pages)
                     (/ (secs (sum (map :ocr-first rs))) pages) (/ (secs (sum (map :ocr-later rs))) pages)
                     (/ (secs kin) pages) (/ (secs (sum (map :finish rs))) pages) (/ (secs (sum (map :pass rs))) pages)
                     (median (mapcat :requests rs)) (median (mapcat :rounds rs))
                     (/ (apply max 0 (map :rss rs)) 1048576.0)))
    {:pages pages :ocr ocr :kin kin}))

(defn accuracy-of
  "Per-document metrics and the pooled board of `emon-fusion-live` with `scope`, by the scorer of `bb accuracy`."
  [doc-ids scope]
  (require 'accuracy.accuracy)
  (let [A (find-ns 'accuracy.accuracy)
        evaluate (ns-resolve A 'evaluate)
        variants-var (ns-resolve A '*variants*)
        ids-var (ns-resolve A '*ids*)]
    (with-bindings {variants-var #{"pdf"} ids-var (set doc-ids)}
      (evaluate ["env" (str "EMON_RS_FLAGS=--ocr-scope=" scope) "sh" "tools/accuracy/predict-emon-fusion-live.sh"]))))

(cond
  (and born? scopes?)
  (do (ensure-driver!)
      (let [doc-ids (if (seq ids) ids fusion-ids)
            base (run-scope doc-ids ["--no-retarget"])
            full (run-scope doc-ids ["--ocr-scope=full"])
            wit (run-scope doc-ids ["--ocr-scope=witness"])]
        (println "COST, per page (A4, 300 dpi), Rust host, live (SPEC 12.3's table; D210):")
        (report "no pixel read" base)
        (let [f (report "scope full" full)
              w (report "scope witness" wit)
              pm (fn [rs] (median (map #(/ (+ (:ocr-first %) (:ocr-later %)) (max 1 (:n %))) rs)))
              fm (pm full) wm (pm wit)]
          (println (format "OCR time a page, the median document's: full %.3fs, witness %.3fs: witness is %.2f of full (SPEC 12.3: 0.30 or less); pooled %.2f"
                           (secs fm) (secs wm) (/ (double wm) (max 1 fm)) (/ (double (:ocr w)) (max 1 (:ocr f)))))
          (println (format "OCR time over the %d pages: full %.1fs, witness %.1fs" (:pages f) (secs (:ocr f)) (secs (:ocr w))))))
      (println "ACCURACY, full against witness (the live Engine, scored by `bb accuracy`'s scorer; CER and the fusion metrics):")
      (let [[bf pf] (accuracy-of (if (seq ids) ids fusion-ids) "full")
            [bw pw] (accuracy-of (if (seq ids) ids fusion-ids) "witness")
            m (fn [per id k] (get (get per (str "pdf/" id)) k))]
        (println (format "%-34s %9s %9s  %s" "document" "CER full" "witness" "tl-override full/witness"))
        (doseq [id (if (seq ids) ids fusion-ids)]
          (println (format "%-34s %9.4f %9.4f  %s / %s" id (double (or (m pf id "cer") -1)) (double (or (m pw id "cer") -1))
                           (m pf id "tl-override") (m pw id "tl-override"))))
        (let [moved (for [k (sort (keys bf)) :let [a (get bf k) b (get bw k)] m (sort (keys a)) :when (not= (get a m) (get b m))]
                      [k m (get a m) (get b m)])]
          (println (format "%d pooled metrics differ between full and witness:" (count moved)))
          (doseq [[k m a b] (take 60 moved)] (println (format "  %-26s %-28s full %s  witness %s" k m a b))))))

  :else
  (do (ensure-driver!)
      (let [sets (if born?
                   [["born" (if (seq ids) ids fusion-ids)]]
                   (for [v variants] [v (vec (map #(str/replace (fs/file-name %) #"\.pdf$" "") (sort (fs/glob (str "test/corpus/synth/" v) "*.pdf"))))]))
            rows (atom [])]
        (doseq [[set-name all-ids] sets
                id (if (and (seq ids) (not born?)) (filter (set ids) all-ids) all-ids)]
          (let [pdf (if born? (str "test/corpus/synth/pdf/" id ".pdf") (str "test/corpus/synth/" set-name "/" id ".pdf"))
                r (drive pdf (concat extra-flags (when no-retarget? ["--no-retarget"])))]
            (if r
              (do (swap! rows conj (assoc r :id (str set-name "/" id) :n (count (:requests r))))
                  (println (format "%-40s pages %d  requests %s  rounds %s  ocr first %.2fs later %.2fs" (str set-name "/" id) (count (:requests r)) (:requests r) (:rounds r)
                                   (secs (:ocr-first r)) (secs (:ocr-later r)))))
              (println (str set-name "/" id ": no stats")))))
        (println)
        (report (cond no-retarget? "no-retarget" (seq extra-flags) (first extra-flags) :else "default") @rows))))
