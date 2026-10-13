#!/usr/bin/env bb
;; The graphics-fusion MEASURES of P7.G (DEVLOG D512-D519): what the tracer and OCR add to the vector source, from the committed transcripts of
;; test/fusion/graphics/ replayed by the Rust driver. A report, not a gate (`bb gfuse` is the gate).
;;
;;     bb gfuse-measure [--before BIN] [--per-doc] [--rows]
;;
;;   A. rule and fill P/R by length and area against the rule truth: the VECTOR source alone (`--ocr=never`), the TRACER alone (its candidates, before
;;      arbitration), and the FUSED run; pooled per input form (pdf, scan, scan-color), and per document with --per-doc
;;   B. TEDS-lite (`table-teds-lite`) and ICDAR 2013 adjacency (`table-adj-f1`) of the tables: BEFORE (the driver BIN: a build whose graphics fusion is off,
;;      vector source only; --before) against AFTER (the plain driver), and `twin-gap` per document (its `pdf` TEDS-lite minus its scan variant's)
;;   C. rule ghosts: body words that are only rule marks (| _ -), the ledger's `rule-ghost` entries, against the gold's own, BEFORE and AFTER
;;   D. the document unit's time from RunStats (`finish-us`) per page, vector source alone, live
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])
(cp/add-classpath "tools")
(cp/add-classpath "tools/lib")
(require '[fusion.gfuse-lib :as l])

(def args *command-line-args*)
(def before-bin (second (drop-while #(not= % "--before") args)))
(def per-doc? (some #{"--per-doc"} args))

(def bin (or (second (drop-while #(not= % "--bin") args)) (str/trim (:out (sh {:err :inherit} "kin/scripts/emon-rs-build")))))
(defn mean [xs] (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) 0.0))
(defn median [xs] (let [s (vec (sort xs)) n (count s)] (when (pos? n) (if (odd? n) (s (quot n 2)) (/ (+ (s (dec (quot n 2))) (s (quot n 2))) 2.0)))))

(println "fixtures:" (count l/ids))
(def runs
  (into {} (for [id l/ids]
             [id {:fused (l/run-fused bin id)
                  :vector (l/run-vector bin id)
                  :before (when before-bin (l/run-fused before-bin id))}])))
(doseq [[id r] runs] (when-not (:fused r) (println "FAIL no fused run for" id)))
(defn tracer-of [id] (when-let [f (:fused (runs id))] {:dump (l/tracer-dump (:dump f)) :model nil}))

;; ---------------------------------------------------------------- A
(println "\nA. rule and fill P/R, by length and area, against the rule truth (pooled per input form)")
(println (format "   %-10s %-7s %7s %7s %7s %7s   %s" "form" "source" "rule-P" "rule-R" "fill-P" "fill-R" "documents"))
(defn graphic-rows [src ids*]
  (for [id ids* :let [d (case src :vector (some-> (:vector (runs id)) :dump) :tracer (some-> (tracer-of id) :dump) :fused (some-> (:fused (runs id)) :dump))]
        :when (and d (l/graphic-counts id d))]
    [id (l/graphic-counts id d)]))
(def truth-ids (filter #(:rules (accuracy.accuracy/doc-graphics (l/doc-of %))) l/ids))
(doseq [form ["pdf" "scan" "scan-color"]
        :let [ids* (filter #(= form (l/variant-of %)) truth-ids)]
        :when (seq ids*)
        src [:vector :tracer :fused]]
  (let [rows (if (and (= src :vector) (not= form "pdf")) [] (graphic-rows src ids*))
        c (l/add-counts (or (seq rows) [[nil {}]]))
        r (l/prf c "gfx.rule") f (l/prf c "gfx.fill")]
    (println (format "   %-10s %-7s %7s %7s %7s %7s   %d" form (name src) (l/pr3 (:p r)) (l/pr3 (:r r)) (l/pr3 (:p f)) (l/pr3 (:r f)) (count rows)))))
(when per-doc?
  (println "   per document (fused; rule-P rule-R fill-P fill-R | vector rule-P rule-R fill-P fill-R | tracer rule-P rule-R fill-P fill-R)")
  (doseq [id truth-ids]
    (let [cell (fn [src] (when-let [x (first (graphic-rows src [id]))]
                           (let [c (second x) r (l/prf c "gfx.rule") f (l/prf c "gfx.fill")]
                             (format "%s %s %s %s" (l/pr3 (:p r)) (l/pr3 (:r r)) (l/pr3 (:p f)) (l/pr3 (:r f))))))]
      (println (format "   %-28s %s | %s | %s" id (cell :fused) (or (cell :vector) "-") (cell :tracer))))))

;; ---------------------------------------------------------------- B
(println "\nB. tables: TEDS-lite and ICDAR adjacency F1 (pooled), BEFORE (vector source only) and AFTER (fused)")
(defn model-counts [src id]
  (let [r (case src :before (:before (runs id)) :after (:fused (runs id)) :vector (:vector (runs id)))]
    (when r (l/score-run id r))))
(def teds (atom {}))
(defn teds-of [c] (let [m (l/metrics-of c)] m))
(doseq [form ["pdf" "scan" "scan-color"]
        :let [ids* (filter #(= form (l/variant-of %)) l/ids)]
        :when (seq ids*)
        src (if before-bin [:before :after] [:after])]
  (let [rows (keep (fn [id] (when-let [c (model-counts src id)] [id c])) ids*)
        m (l/pooled rows)]
    (println (format "   %-10s %-7s table-teds-lite %s  table-teds-struct-lite %s  table-adj-f1 %s  (%d documents)" form (name src)
                     (l/pr3 (get m "table-teds-lite" 0)) (l/pr3 (get m "table-teds-struct-lite" 0)) (l/pr3 (get m "table-adj-f1" 0)) (count rows)))))
(println "   per document table-teds-lite (before after) / adj-f1 (before after), and the twin-gap")
(def doc-teds
  (into {} (for [id l/ids
                 :let [a (some-> (model-counts :after id) l/metrics-of)
                       b (when before-bin (some-> (model-counts :before id) l/metrics-of))]]
             [id {:after (get a "table-teds-lite") :before (get b "table-teds-lite")
                  :adj-after (get a "table-adj-f1") :adj-before (get b "table-adj-f1")}])))
(doseq [id l/ids]
  (let [d (doc-teds id)]
    (println (format "   %-28s teds %s -> %s   adj %s -> %s" id (or (some-> (:before d) l/pr3) "-") (or (some-> (:after d) l/pr3) "-")
                     (or (some-> (:adj-before d) l/pr3) "-") (or (some-> (:adj-after d) l/pr3) "-")))))
(def gaps
  (for [id l/ids :when (not= "pdf" (l/variant-of id))
        :let [twin (l/doc-of id)
              p (:after (doc-teds twin))                         ; the pdf fixture of the same document, when recorded
              s (:after (doc-teds id))
              pb (:before (doc-teds twin)) sb (:before (doc-teds id))]
        :when (and p s)]
    {:id id :after (- p s) :before (when (and pb sb) (- pb sb))}))
(doseq [g gaps] (println (format "   twin-gap %-28s before %s after %s" (:id g) (or (some-> (:before g) l/pr3) "-") (l/pr3 (:after g)))))
(when (seq gaps)
  (println (format "   median twin-gap: before %s after %s"
                   (or (some-> (median (keep :before gaps)) l/pr3) "-") (l/pr3 (median (map :after gaps))))))

;; ---------------------------------------------------------------- C
(println "\nC. rule ghosts (body words that are only rule marks | _ -; ledger rule-ghost), per document where any differs")
(doseq [id l/ids
        :let [g (l/gold-of id)
              a (some-> (:fused (runs id)) :model (l/ghosts g))
              b (when before-bin (some-> (:before (runs id)) :model (l/ghosts g)))]
        :when (or (pos? (:body a 0)) (pos? (:ledger a 0)) (pos? (:body b 0)) (pos? (:gold a 0)))]
    (println (format "   %-28s gold %d  after: body %d ledger %d%s" id (:gold a) (:body a) (:ledger a)
                     (if b (format "   before: body %d ledger %d" (:body b) (:ledger b)) ""))))
(let [tot (fn [k] (reduce + (for [id l/ids] (get (some-> (:fused (runs id)) :model (l/ghosts (l/gold-of id))) k 0))))
      totb (fn [k] (reduce + (for [id l/ids] (get (some-> (:before (runs id)) :model (l/ghosts (l/gold-of id))) k 0))))]
  (println (format "   total body rule words: gold %d, after %d%s; ledger rule-ghost after %d" (tot :gold) (tot :body)
                   (if before-bin (str ", before " (totb :body)) "") (tot :ledger))))

;; ---------------------------------------------------------------- E
(when (some #{"--live"} args)
  (println "\nE. the document unit (finish-us) per page, RunStats of the LIVE fused conversion (pdfium, Tesseract, the tracer; graphics fusion included), once")
  (shell {:out :string :err :string} "bb" "tools/pdfium/fetch.clj")
  (let [live (str/trim (:out (sh {:err :inherit :extra-env {"EMON_RS_PDFIUM" (str (fs/real-path ".cache/pdfium/current/lib"))}} "kin/scripts/emon-rs-build")))
        worst (atom 0.0)]
    (doseq [id l/ids]
      (let [flags (if (= "pdf" (l/variant-of id)) ["--pdfium" "--tesseract"] ["--tesseract"])
            r (apply sh {:err :string :out :string} live (concat flags ["--stats" "--out=json" (l/input-of id)]))
            m (re-find #"finish-us=(\d+)" (:err r))
            pages (max 1 (count (get-in (:fused (runs id)) [:model "pages"])))
            ms (when m (/ (parse-long (second m)) 1000.0 pages))]
        (when ms (swap! worst max ms))
        (println (format "   %-34s finish %.3f ms a page (%d pages)" id (or ms -1.0) pages))))
    (println (format "   worst %.3f ms a page" @worst))))

;; ---------------------------------------------------------------- D
(println "\nD. the document unit (finish-us) per page, RunStats of a live --ocr=never conversion (vector source alone), best of 3")
(doseq [id (filter #(= "pdf" (l/variant-of %)) l/ids)]
  (let [one (fn [] (let [r (sh {:err :string} bin "--stats" "--ocr=never" "--out=json" (l/input-of id))
                         m (re-find #"finish-us=(\d+)" (:err r))]
                     (when m (parse-long (second m)))))
        best (apply min (keep identity (repeatedly 3 one)))
        pages (count (get-in (:model (:vector (runs id))) ["pages"]))]
    (println (format "   %-28s %.3f ms a page (%d pages)" id (/ best 1000.0 (max 1 pages)) pages))))
