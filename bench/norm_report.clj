;; Print the P6.3 normalization table from bench/work/results-norm.txt and check that every arm computed the same layers,
;; skew and view hashes.
;;
;;     bb bench/norm_report.clj
;;
;; Times are each arm's best-of-N milliseconds; the ratio is to Rust's. Exits non-zero if any arm's `S`, `L` or `H` lines
;; differ from Rust's on the same page.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def phases ["gray" "hist" "layers" "mask" "sauv" "skew" "view" "refsauv"])

(defn load-results []
  (loop [lines (str/split-lines (slurp (io/file "bench" "work" "results-norm.txt")))
         page nil arm nil acc (array-map)]
    (if-let [l (first lines)]
      (cond
        (str/starts-with? l "== page ") (recur (rest lines) (second (str/split l #" ")) nil acc)
        (str/starts-with? l "== ") (let [[_ a p] (str/split l #" ")] (recur (rest lines) p a (assoc-in acc [p a] {:T {} :lines []})))
        (str/starts-with? l "T ") (let [[_ k v] (str/split l #" ")] (recur (rest lines) page arm (assoc-in acc [page arm :T k] (parse-double v))))
        (and arm (re-find #"^[SLHX] " l)) (recur (rest lines) page arm (update-in acc [page arm :lines] conj l))
        :else (recur (rest lines) page arm acc))
      acc)))

(let [res (load-results) ok (atom true)]
  (doseq [[page arms] res]
    (println (str "\n" page "   (ms, best of N; ratio to rust)"))
    (println (format "  %-8s %s" "arm" (str/join " " (map #(format "%10s" %) phases))))
    (let [base (get-in arms ["rust" :T])]
      (doseq [[arm {:keys [T]}] arms]
        (println (format "  %-8s %s" arm
                         (str/join " " (for [p phases] (format "%6.1f %s" (get T p 0.0)
                                                              (if (> (get base p 0.0) 0.5) (format "%3.1fx" (/ (get T p 0.0) (get base p))) "    "))))))))
    (let [want (->> (get-in arms ["rust" :lines]) (remove #(str/starts-with? % "X ")) vec)]
      (doseq [[arm {:keys [lines]}] arms
              :when (not= arm "rust")
              :let [got (vec (remove #(str/starts-with? % "X ") lines))]]
        (when (not= got want)
          (reset! ok false)
          (println (str "  FAIL " arm " disagrees with rust on " page ": " (pr-str (remove (set want) got))))))))
  (if @ok
    (println "\n  ok   every arm computed the same layers, skew and view hashes")
    (System/exit 1)))
