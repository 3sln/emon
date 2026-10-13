#!/usr/bin/env bb
;; PARBENCH: what does `parallel=N` buy on a real document?   bb parbench   (DEVLOG P10.2, D1305; SPEC 12.1; nome 6)
;;
;; NOT A GATE. For each host (the Rust, Go, Java and C# drivers, built LIVE: Tesseract through each host's own binding, pdfium where the driver has it), each
;; document and each N, it runs the driver `--parallel=N` and measures the wall time and the peak resident set (macOS `/usr/bin/time -l`), the machine's load
;; average before the run, and the SHA-256 of what the driver printed: the bytes at every N must be the bytes at N=1 (the identity claim, on a real document, with the
;; real engines). Runs go one at a time, N interleaved within a repetition so that a drift of the machine's load falls on every N alike, and the median of the
;; repetitions is the figure; the load is printed beside it, because a shared machine is not a quiet one and the table is read with it.
;;
;;   bb parbench                                    the scan and the large born-digital document, every host there is a driver for, N = 1 2 4 8, 3 repetitions
;;   bb parbench --docs scan,born,fusion            which documents (default scan,born)
;;   bb parbench --hosts rust,java                  which hosts
;;   bb parbench --n 1,2,4 --reps 5
;;   bb parbench --out FILE                         also write the raw runs as EDN
;;
;; THE DOCUMENTS (the public corpus, never committed: `corpus/`):
;;   scan    a 6-page scanned bank statement (8.3 MB of JPEGs): one `decode` and one full-page OCR per page, then the targeted passes. Tesseract only.
;;   born    the 152-page Berkshire 10-K (a born-digital PDF), `--ocr=auto`: the text layers are healthy, so nothing is asked of any port.
;;   fusion  a 14-page born-digital paper under the DEFAULT `ocr=always`: two renders (pdfium) and an OCR of every page, fused with its text layer. Hosts with pdfium.
;;
;; THE DRIVERS are built by their own scripts: .cache/emon-rs-fusion (EMON_RS_PDFIUM=DIR kin/scripts/emon-rs-build), .cache/emon-go-live (EMON_GO_LIVE=1
;; kin/scripts/emon-go-build), .cache/emon-java and .cache/emon-cs (the libraries are found at run time: EMON_TESS_LIB, EMON_PDFIUM_LIB).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [process]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def args (vec *command-line-args*))
(defn opt [k] (some->> (map-indexed vector args) (some (fn [[i a]] (when (= a k) (get args (inc i)))))))
(def hosts (mapv keyword (str/split (or (opt "--hosts") "rust,go,java,csharp") #",")))
(def ns* (mapv parse-long (str/split (or (opt "--n") "1,2,4,8") #",")))
(def reps-opt (some-> (opt "--reps") parse-long))
(def doc-names (mapv keyword (str/split (or (opt "--docs") "scan,born") #",")))
(def out-file (opt "--out"))

(def pdfium-lib (str (fs/real-path ".cache/pdfium/current/lib")))
(def corpus "corpus")
(def docs
  {:scan   {:file (str corpus "/financial/agami-indian-bank-statements/train/India_Bank_Statement_Scanned_Type1/00010.pdf") :flags ["--tesseract"]
            :why "6 pages, scanned" :reps 3}
   :born   {:file (str corpus "/financial/annual-reports/berkshire-2023-annual-report-10k.pdf") :flags ["--tesseract" "--ocr=auto"]
            :why "152 pages, born-digital, ocr=auto" :reps 3}
   :fusion {:file (str corpus "/layout/arxiv/1409.1556.pdf") :flags ["--tesseract" "--pdfium"]
            :why "14 pages, born-digital, ocr=always (renders + OCR per page)" :hosts #{:rust :java :csharp} :reps 2}})

(def drivers
  {:rust   {:exe ".cache/emon-rs-fusion/target/release/emon-rs"}
   :go     {:exe ".cache/emon-go-live/emon-go"}
   :java   {:exe ".cache/emon-java/emon-java" :pdfium (str "--pdfium=" pdfium-lib)}
   :csharp {:exe ".cache/emon-cs/emon-cs" :pdfium (str "--pdfium=" pdfium-lib)}})

(defn flags-for [host doc]
  (mapv (fn [f] (if (and (= f "--pdfium") (:pdfium (drivers host))) (:pdfium (drivers host)) f)) (:flags doc)))

(defn loadavg []
  (let [s (str/trim (:out @(process {:out :string} "sysctl" "-n" "vm.loadavg")))]
    (parse-double (second (re-find #"\{\s*([\d.]+)" s)))))

(defn sha256 [^bytes b]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (subs (apply str (map #(format "%02x" %) (.digest md b))) 0 12)))

(defn run-driver [host doc n]
  (let [load (loadavg)
        cmd (concat ["/usr/bin/time" "-l" (:exe (drivers host))] (flags-for host doc) [(str "--parallel=" n) "--out=bundle" (:file doc)])
        t0 (System/nanoTime)
        r @(apply process {:out :bytes :err :string :extra-env {"OMP_THREAD_LIMIT" "1"}} cmd)
        wall (/ (- (System/nanoTime) t0) 1e9)
        rss (some->> (re-find #"(\d+)\s+maximum resident set size" (:err r)) second parse-long)]
    {:host host :n n :wall wall :rss-mb (when rss (/ rss 1048576.0)) :load load :exit (:exit r)
     :sha (sha256 (:out r)) :bytes (count (:out r))}))

(defn median [xs] (let [v (vec (sort xs)) n (count v)] (if (odd? n) (v (quot n 2)) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0))))

(defn available? [host] (fs/exists? (:exe (drivers host))))

(def raw (atom []))

(doseq [dn doc-names
        :let [doc (docs dn)]
        :when (fs/exists? (:file doc))]
  (let [hs (filterv #(and (available? %) (or (nil? (:hosts doc)) ((:hosts doc) %))) hosts)
        reps (or reps-opt (:reps doc))]
    (println)
    (println (str "== " (name dn) ": " (:why doc) " (" (:file doc) ")"))
    (println (str "   cores " (:out @(process {:out :string} "sysctl" "-n" "hw.ncpu")) "   hosts " (str/join "," (map name hs)) "   N " (str/join "," ns*) "   reps " reps))
    (doseq [host hs]
      (let [runs (vec (for [rep (range reps) n ns*] (assoc (run-driver host doc n) :doc dn :rep rep)))]
        (swap! raw into runs)
        (let [by-n (group-by :n runs)
              base (median (map :wall (by-n (first ns*))))
              base-sha (:sha (first (by-n (first ns*))))]
          (println)
          (println (format "  %-7s %2s  %9s  %7s  %9s  %8s  %-13s  %s" (name host) "N" "wall s" "x serial" "peak MB" "load" "bytes sha" "exit"))
          (doseq [n ns*]
            (let [rs (by-n n)
                  w (median (map :wall rs))
                  shas (distinct (map :sha rs))
                  same (and (= 1 (count shas)) (= (first shas) base-sha))]
              (println (format "  %-7s %2d  %9.2f  %7.2f  %9.0f  %4.1f-%4.1f  %-13s  %s" "" n w (/ base w) (median (keep :rss-mb rs))
                               (apply min (map :load rs)) (apply max (map :load rs))
                               (str (first shas) (if same " =serial" " DIFFERS"))
                               (str/join "," (distinct (map :exit rs))))))))))))

(when out-file
  (spit out-file (pr-str @raw))
  (println) (println "raw runs:" out-file))
