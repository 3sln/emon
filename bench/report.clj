;; Print the P2.7 ratio table from bench/work/results-<mode>.txt and check that
;; every arm computed the same threshold, component count and label statistics.
;;
;;     bb bench/report.clj [port|bulk|skip|port-clean ...]     (default: port)
;;
;; Ratios are each arm's best-of-N milliseconds over the hand-written Rust
;; comparator's (from the same page's `port` results: the comparator has no
;; other mode). Exits non-zero if any arm's `S` line differs from the
;; comparator's.
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def otsu-phases ["hist" "otsu" "bin" "alloc" "label" "total"])
(def sauv-phases ["integ" "sauv" "alloc2" "label2" "total2"])

(defn load-arms [mode]
  (loop [lines (str/split-lines (slurp (io/file "bench" "work" (str "results-" mode ".txt"))))
         cur nil
         arms (array-map)]
    (if-let [line (first lines)]
      (cond
        (str/starts-with? line "== ")
        (let [nm (first (str/split (subs line 3) #" "))]
          (recur (rest lines) nm (assoc arms nm {:T {} :S nil})))
        (and cur (str/starts-with? line "T "))
        (let [[_ k v] (str/split line #" ")]
          (recur (rest lines) cur (assoc-in arms [cur :T k] (parse-double v))))
        (and cur (str/starts-with? line "S2 "))
        (recur (rest lines) cur (assoc-in arms [cur :S2] (subs line 3)))
        (and cur (str/starts-with? line "S "))
        (recur (rest lines) cur (assoc-in arms [cur :S] (subs line 2)))
        :else (recur (rest lines) cur arms))
      arms)))

(defn cell [v base]
  (format "%9.2f [%s]" v
          (if (> base 0.05) (format "%5.2fx" (/ v base)) "  n/a ")))

(let [modes (or (seq *command-line-args*) ["port"])
      ok (atom true)]
  (doseq [mode modes]
    (let [suffix (if (str/includes? mode "-") (subs mode (str/index-of mode "-")) "")
          native (get (load-arms (str "port" suffix)) "rust-native")
          arms (load-arms mode)]
      (println (format "\n== mode: %s   (ms, best of N; ratio to rust-native in brackets)" mode))
      (doseq [phases [otsu-phases sauv-phases]]
        (println (format "%-20s" "arm") (apply str (map #(format "%19s" %) phases)))
        (doseq [nm (cons "rust-native" (remove #{"rust-native"} (keys arms)))]
          (let [a (if (= nm "rust-native") native (get arms nm))]
            (when (get-in a [:T (first phases)])
              (println (format "%-20s" nm)
                       (apply str (map #(format "%19s" (cell (get-in a [:T %]) (get-in native [:T %]))) phases)))))))
      (doseq [[nm a] arms k [:S :S2]]
        (when (and (get a k) (not= (get a k) (get native k)))
          (reset! ok false)
          (println "MISMATCH" nm k (get a k) "!= native" (get native k))))
      (println "stats, Otsu path (threshold, components, ink, max area, provisional labels, checksum hi/lo, sum-sq-area hi/lo):")
      (println " " (:S native))
      (println "stats, Sauvola path (ink, then the same):")
      (println " " (:S2 native))))
  (println (if @ok "all arms agree" "ARMS DISAGREE"))
  (System/exit (if @ok 0 1)))
