#!/usr/bin/env bb
;; bb perf -- one comparator per bet (P10.3, SPEC 12.3, D092, D409). NOT a gate.
;;
;;   bb perf [--quick] [--reps N] [--only BET[,BET...]] [--no-pdf] [--checked] [--update] [--json FILE]
;;   bb perf --check [--margin M] [--reps N]        OPTIONAL regression check; wired into nothing
;;
;; Each bet runs the kin-generated Rust (gen/rust/emon, over the host's port.rs) AND a native reference on the SAME
;; fixed input, in one process, interleaved (kin, ref, kin, ref ...), single-threaded, under `nice`, one bench process
;; at a time; the MEDIAN of N repetitions of each is reported with their ratio (kin / reference), the ratio of the
;; minimums, whether the two answers agreed, and the machine's load average before and after the bet (the numbers
;; of a loaded machine are loaded numbers; the ratio of interleaved runs is what survives that).
;;
;; BETS (tools/perf/src/main.rs): inflate, deflate (the deterministic writer), png-write (filter choice + deflate),
;; png-unfilter (inflate + the PNG predictor), png-transcript (the transcript bitmap reader, P10.11: kin against the Rust host's retired reader and the png crate), lzw, ccitt (Group 4 against the fax crate, D447), raster (Otsu, Sauvola, two-pass union-find labelling; bulk = what
;; ships, port = per-pixel through bitmap-u8, D092), tracer (runs + union-find over runs + component table), and
;; content (the content interpreter and text layer, whole conversion of 8 synthetic PDFs through the Rust driver against
;; `pdftotext -bbox-layout`, indicative only: a process-level reference doing less than the Engine). Crypto is not a bet:
;; its primitives are the host's `Crypto` port (D006, D097), native by construction.
;;
;; Over 2x on a PRIMARY row is flagged with what D092's rule implies. `--check` runs the quick (synthetic, small) inputs
;; and fails only when a primary row's ratio AND its ratio of minimums are both more than --margin (default 2.5) times
;; the committed ones in tools/perf/baseline.edn; `--update` rewrites that file from this run (quick or full).
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])

(def args (vec *command-line-args*))
(defn flag? [f] (some #{f} args))
(defn opt [f dflt] (if-let [i (first (keep-indexed (fn [i a] (when (= a f) i)) args))] (nth args (inc i) dflt) dflt))

(def check? (flag? "--check"))
(def update? (flag? "--update"))
(def quick? (or (flag? "--quick") check?))
(def reps (parse-long (opt "--reps" (if check? "5" "7"))))
(def margin (parse-double (opt "--margin" "2.5")))
(def only (some-> (opt "--only" nil) (str/split #",")))
(def no-pdf? (or (flag? "--no-pdf") check? quick?))
(def checked? (flag? "--checked"))
(def json-out (opt "--json" nil))

(def perf-dir "tools/perf")
(def baseline-file (str perf-dir "/baseline.edn"))
(def target-dir (if checked? "target-checked" "target"))
(def bin (str perf-dir "/" target-dir "/release/emon-perf"))

(defn sh-out [& cmd] (let [r (apply p/shell {:out :string :err :string :continue true} cmd)] (str/trim (:out r))))
(defn loadavg [] (let [s (sh-out "sysctl" "-n" "vm.loadavg")] (mapv parse-double (re-seq #"\d+\.\d+" s))))
(defn now-ms [] (/ (System/nanoTime) 1e6))

;; ------------------------------------------------------------------ build and fixed inputs

(defn build! []
  (println "== build (cargo, release, offline, locked; pinned deps in tools/perf/Cargo.toml)")
  (let [env (merge (into {} (System/getenv)) (when checked? {"CARGO_PROFILE_RELEASE_OVERFLOW_CHECKS" "true"}))
        r (p/shell {:env env :continue true :out :string :err :string}
                   "nice" "-n" "10" "cargo" "build" "--release" "--offline" "--locked"
                   "--manifest-path" (str perf-dir "/Cargo.toml") "--target-dir" (str perf-dir "/" target-dir))]
    (when-not (zero? (:exit r)) (println (:err r)) (System/exit 3))))

(defn find-page
  "The P2.7 bench page (2481x3508 gray, made from the corpus by `bb bench/mkpage.clj`), here or in the main checkout."
  []
  (when-not quick?
    (let [common (try (sh-out "git" "rev-parse" "--git-common-dir") (catch Exception _ nil))
          main-root (when (and common (not (str/blank? common))) (str (fs/parent (fs/absolutize common))))
          cands (remove nil? [(System/getenv "PERF_PAGE") "bench/data/page.gray" (when main-root (str main-root "/bench/data/page.gray"))])]
      (when-let [f (first (filter fs/exists? cands))]
        (let [sz (str f ".size")]
          (when (fs/exists? sz)
            (let [[w h] (map parse-long (str/split (str/trim (slurp sz)) #"\s+"))]
              [(str f) w h])))))))

;; ------------------------------------------------------------------ the Rust bets

(def rust-bets ["inflate" "deflate" "png-write" "png-unfilter" "png-transcript" "lzw" "ccitt" "raster" "tracer"])

(defn run-bet! [bet page]
  (let [l0 (loadavg)
        cmd (concat ["nice" "-n" "10" bin "--only" bet "--reps" (str reps)]
                    (when quick? ["--quick"])
                    (when page ["--page" (first page) (str (second page)) (str (nth page 2))]))
        r (apply p/shell {:out :string :err :string :continue true} cmd)
        l1 (loadavg)]
    (when-not (zero? (:exit r)) (println "FAILED" bet (:err r)) (System/exit 4))
    (let [rows (keep #(when (str/starts-with? % "{") (json/parse-string % true)) (str/split-lines (:out r)))]
      {:inputs (:inputs (first (filter :inputs rows)))
       :rows (mapv #(assoc % :load0 (first l0) :load1 (first l1)) (filter :bet rows))})))

;; ------------------------------------------------------------------ the content-interpreter / text-layer bet

(def pdfs ["table-multipage-thead" "table-multipage-no-thead" "furn-odd-even" "columns-continue"
           "bank-statement" "lists-long-multipage" "furn-fixed-dom" "footnotes-page"])
(def driver ".cache/emon-rs/target/release/emon-rs")

(defn median [xs] (let [s (vec (sort xs)) n (count s)] (when (pos? n) (if (odd? n) (nth s (quot n 2)) (/ (+ (nth s (dec (quot n 2))) (nth s (quot n 2))) 2.0)))))

(defn pdf-bet! []
  (cond
    (not (fs/exists? driver)) (println "  SKIP content: the Rust driver is not built (sh kin/scripts/emon-rs-build)")
    (not (fs/which "pdftotext")) (println "  SKIP content: pdftotext (poppler) is not installed")
    :else
    (let [l0 (first (loadavg))
          startup (median (for [_ (range (* 2 reps))]
                            (let [t (now-ms)] (p/shell {:out :string :err :string :continue true} "nice" "-n" "10" "pdftotext" "-v") (- (now-ms) t))))
          per (for [stem pdfs
                    :let [f (str "test/corpus/synth/pdf/" stem ".pdf")
                          k (for [_ (range reps)]
                              (let [r (p/shell {:out :string :err :string :continue true} "nice" "-n" "10" driver "--out=json" "--stats" f)
                                    tl (first (filter #(str/starts-with? % "time-stats:") (str/split-lines (:err r))))
                                    g (fn [k] (parse-long (second (re-find (re-pattern (str k "=(\\d+)")) tl))))]
                                (/ (+ (g "pass-us") (g "pages-us") (g "finish-us")) 1000.0)))
                          rf (for [_ (range reps)]
                               (let [t (now-ms)] (p/shell {:out :string :err :string :continue true} "nice" "-n" "10" "pdftotext" "-bbox-layout" f "-") (- (now-ms) t)))]]
                {:stem stem :kin (median k) :ref-wall (median rf)})
          kin (reduce + (map :kin per))
          wall (reduce + (map :ref-wall per))
          net (max 1.0 (- wall (* (count per) startup)))
          pages (reduce + (for [s pdfs] (parse-long (sh-out "qpdf" "--show-npages" (str "test/corpus/synth/pdf/" s ".pdf")))))
          l1 (first (loadavg))]
      {:bet "content" :input (str (count pdfs) " synthetic PDFs, " pages " pages")
       :ref "pdftotext -bbox-layout (poppler; process wall less startup)" :bytes 0 :reps reps
       :kin_ms kin :ref_ms net :ratio (/ kin net) :min_ratio (/ kin net) :kin_min kin :ref_min net
       :check "n/a" :load0 l0 :load1 l1
       :note (format "Engine: whole JSON conversion in-process (time-stats pass+pages+finish; it also does structure, tables, furniture); reference: raw wall %.1f ms, startup %.1f ms x %d subtracted. Indicative: the reference does less." wall startup (count per))})))

;; ------------------------------------------------------------------ primary references and the 2x flag

(defn primary? [{:keys [bet input ref]}]
  (case bet
    "inflate" (= ref "flate2(miniz_oxide)")
    "deflate" (= ref "flate2(miniz_oxide,6)")
    "png-write" (= ref "png(balanced,adaptive)")
    "png-unfilter" true
    "png-transcript" (str/starts-with? ref "legacy")
    "lzw" true
    "ccitt" (str/starts-with? input "text-page")
    ("raster-binarise" "raster-label" "raster-sauvola") (str/ends-with? input "/bulk")
    "tracer-core" true
    "content" true
    false))

(defn row-key [r] (str (:bet r) "|" (:input r) "|" (:ref r)))

(defn implication [r]
  (case (:bet r)
    ("inflate" "deflate" "png-write" "png-unfilter" "png-transcript" "lzw" "ccitt")
    "a bit-stream codec with state, not a pure whole-buffer map/reduce: D092 gives it no port form; optimise in kin (or amend D092 to admit a codec port, a decision, not a measurement)"
    ("raster-binarise" "raster-label" "raster-sauvola" "tracer-core") "inside D092: keep, or a bulk form only if it is a pure map/reduce over 2x"
    "content" "keep, measure the text layer alone before acting (the reference does less)"
    ""))

;; ------------------------------------------------------------------ report

(defn fmt [x d] (if (number? x) (format (str "%." d "f") (double x)) (str x)))

(defn print-table [rows baseline]
  (println)
  (println (format "%-16s %-28s %-34s %10s %10s %7s %7s %-8s %s" "bet" "input" "reference" "kin ms" "ref ms" "ratio" "min-r" "answers" "vs committed"))
  (doseq [r rows]
    (let [b (get baseline (row-key r))
          base (get b (if quick? :quick :full))
          flag (when (and (primary? r) (> (:ratio r) 2.0)) " OVER 2x")]
      (println (format "%-16s %-28s %-34s %10s %10s %7s %7s %-8s %s%s"
                       (:bet r) (subs (:input r) 0 (min 28 (count (:input r)))) (subs (:ref r) 0 (min 34 (count (:ref r))))
                       (fmt (:kin_ms r) 1) (fmt (:ref_ms r) 1) (fmt (:ratio r) 2) (fmt (:min_ratio r) 2)
                       (:check r) (if base (str "(was " (fmt (:ratio base) 2) ")") "") (or flag ""))))))

(defn print-flags [rows]
  (let [over (filter #(and (primary? %) (> (:ratio %) 2.0)) rows)]
    (println)
    (if (empty? over)
      (println "No primary bet is over 2x.")
      (do (println "Primary bets over 2x (SPEC 12.3, D092):")
          (doseq [r over]
            (println (format "  %-16s %-26s %6.2fx (min-ratio %.2f)  %s" (:bet r) (:input r) (:ratio r) (:min_ratio r) (implication r))))))))

;; ------------------------------------------------------------------ main

(def baseline (if (fs/exists? baseline-file) (edn/read-string (slurp baseline-file)) {}))

(defn machine []
  {:cpu (sh-out "sysctl" "-n" "machdep.cpu.brand_string") :ncpu (sh-out "sysctl" "-n" "hw.ncpu")
   :rustc (sh-out "rustc" "--version") :date (str (java.time.LocalDate/now))
   :overflow-checks (if checked? "on (as the shipped host crate)" "off (as P2.7 / D092)")})

(when-not (fs/exists? (str perf-dir "/Cargo.toml")) (println "run from the repository root") (System/exit 2))
(build!)
(let [page (find-page)
      m (machine)
      _ (println (str "== machine: " (:cpu m) ", " (:ncpu m) " cpus, " (:rustc m) ", overflow-checks " (:overflow-checks m)))
      _ (println (str "== load average at start: " (loadavg) "; reps " reps (when quick? ", QUICK (small synthetic inputs)")))
      _ (println (str "== page: " (if page (str (first page) " " (second page) "x" (nth page 2)) "synthetic")))
      results (doall (for [bet rust-bets :when (or (nil? only) (some #{bet} only))]
                       (do (println (str "== " bet " (load " (first (loadavg)) ")"))
                           (run-bet! bet page))))
      inputs (:inputs (first (filter :inputs results)))
      rows (vec (concat (mapcat :rows results)
                        (when (and (not no-pdf?) (or (nil? only) (some #{"content"} only)))
                          (when-let [r (pdf-bet!)] [r]))))]
  (println (str "== inputs: " (pr-str inputs)))
  (print-table rows baseline)
  (print-flags rows)
  (println (str "\n== load average at end: " (loadavg)))
  (when json-out (spit json-out (json/generate-string {:machine m :inputs inputs :rows rows} {:pretty true})))
  (when update?
    (let [k (if quick? :quick :full)
          new (reduce (fn [acc r] (assoc-in acc [(row-key r) k] (select-keys r [:ratio :min_ratio :kin_ms :ref_ms]))) baseline rows)]
      (spit baseline-file (with-out-str (pp/pprint (into (sorted-map) new))))
      (println "wrote" baseline-file k)))
  (when (some #(= "MISMATCH" (:check %)) rows)
    (println "\nFAIL: a kin answer and its reference's differ (the comparator measures something else):")
    (doseq [r rows :when (= "MISMATCH" (:check r))] (println "  " (row-key r)))
    (System/exit 1))
  (when check?
    (let [bad (for [r rows :when (primary? r)
                    :let [b (get-in baseline [(row-key r) :quick])]
                    :when (and b (> (:ratio r) (* margin (:ratio b))) (> (:min_ratio r) (* margin (:min_ratio b))))]
                [r b])
          unknown (for [r rows :when (and (primary? r) (nil? (get-in baseline [(row-key r) :quick])))] (row-key r))]
      (doseq [k unknown] (println "  note: no committed ratio for" k "(bb perf --check --update to record)"))
      (if (seq bad)
        (do (println (format "\nFAIL: %d bet(s) regressed by more than %.1fx against tools/perf/baseline.edn:" (count bad) margin))
            (doseq [[r b] bad]
              (println (format "  %s: ratio %.2f (committed %.2f), min-ratio %.2f (committed %.2f)" (row-key r) (:ratio r) (:ratio b) (:min_ratio r) (:min_ratio b))))
            (System/exit 1))
        (println (format "\nok: no primary bet is more than %.1fx its committed ratio" margin))))))
