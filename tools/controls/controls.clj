#!/usr/bin/env bb
;; The 54 CONTROLS under two OCR engines (K8, D901; D864's population): non-gate, live, macOS.
;;
;;     bb tools/controls/controls.clj [--jobs 3] [--limit N] [--list FILE] [--tesseract-bin FILE] [--vision-bin FILE] [--out FILE]
;;
;; Every document of test/controls/sweep3.txt (the corpus paths are listed there so the corpus shard keeps them) is converted twice with D864's command (`--pdfium --ocr=auto --assets=figures
;; --out=bundle`): once with live Tesseract (`.cache/emon-rs-fusion`, built by `EMON_RS_PDFIUM=<libdir> kin/scripts/emon-rs-build`) and once with Apple Vision (`.cache/emon-rs-vision`,
;; `EMON_RS_VISION=1 EMON_RS_PDFIUM=<libdir>`, `--ocr-engine=apple-vision`). No gold exists for these documents, so the comparison is of the two engines' Models with each other:
;; exit status; whether the bundles are byte for byte the same (a document with no raster page never asks the engine, so they must be); the pages that were read; the Model's words and
;; the numeric words among them, as multisets, and how many of Tesseract's numeric words Vision's Model keeps; wall time, CPU (user+sys) and peak resident set (`/usr/bin/time -l`).
;; Nothing of a document's text is written: counts and hashes only.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as json] '[clojure.string :as str])
(import '[java.security MessageDigest])

(def root (str (fs/canonicalize ".")))
(def args (vec *command-line-args*))
(defn opt [f d] (or (second (drop-while #(not= f %) args)) d))
(def jobs (parse-long (opt "--jobs" "3")))
(def out (opt "--out" (str (or (System/getenv "EMON_SCRATCH") (str root "/.cache/scratch")) "/controls.edn")))
(def engines {:tesseract {:bin (opt "--tesseract-bin" (str root "/.cache/emon-rs-fusion/target/release/emon-rs")) :flags ["--pdfium" "--tesseract"]}
              :vision {:bin (opt "--vision-bin" (str root "/.cache/emon-rs-vision/target/release/emon-rs")) :flags ["--pdfium" "--ocr-engine=apple-vision"]}})
(def sources (->> (str/split-lines (slurp (opt "--list" (str root "/test/controls/sweep3.txt")))) (remove #(or (str/blank? %) (str/starts-with? % "#"))) (take (parse-long (opt "--limit" "1000"))) vec))

(defn sha256 [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") b))))
(defn words [s] (remove str/blank? (str/split (str/lower-case (str s)) #"\s+")))
(defn node-text [n] (if (some? (:text n)) (:text n) (str/join " " (map node-text (:children n)))))
(defn numeric? [w] (boolean (re-find #"\d" w)))

(defn run-one [engine src]
  (let [{:keys [bin flags]} (engines engine)
        t0 (System/currentTimeMillis)
        r (apply p/shell {:out :bytes :err :string :continue true :timeout (* 30 60 1000)}
                 "/usr/bin/time" "-l" bin (concat flags ["--ocr=auto" "--assets=figures" "--out=bundle" (str root "/" src)]))
        wall (- (System/currentTimeMillis) t0)
        err (str (:err r))
        rss (some-> (re-find #"(\d+)\s+maximum resident set size" err) second parse-long)
        user (some-> (re-find #"([\d.]+)\s+user" err) second parse-double)
        sys (some-> (re-find #"([\d.]+)\s+sys" err) second parse-double)
        bytes ^bytes (:out r)
        txt (String. bytes "UTF-8")
        sections (into {} (map (fn [[_ k v]] [k v]) (re-seq #"(?s)== ([^\n]+)\n(.*?)(?=\n== |\z)" txt)))
        model (when (zero? (:exit r)) (try (json/parse-string (get sections "json") true) (catch Exception _ nil)))
        ws (when model (words (str/join " " (map node-text (cons (:body model) (:furniture model))))))
        pages (when model (frequencies (map :kind (:pages model))))]
    {:engine engine :src src :exit (:exit r) :wall-ms wall :cpu-s (when (and user sys) (+ user sys)) :rss-bytes rss
     :sha (sha256 bytes) :bytes (count bytes) :pages pages :words (count ws) :numeric (count (filter numeric? ws))
     :numeric-freq (when ws (frequencies (filter numeric? ws)))
     :error (when-not (zero? (:exit r)) (subs err 0 (min 300 (count err))))}))

(defn compare-one [{:keys [src tess vis]}]
  (let [kept (when (and (:numeric-freq tess) (:numeric-freq vis))
               (reduce + (for [[w n] (:numeric-freq tess)] (min n (get (:numeric-freq vis) w 0)))))]
    {:src src :tesseract (dissoc tess :numeric-freq) :vision (dissoc vis :numeric-freq)
     :identical (and (zero? (:exit tess)) (zero? (:exit vis)) (= (:sha tess) (:sha vis)))
     :numeric-kept kept :numeric-tess (:numeric tess)}))

(let [pairs (vec (mapcat identity (map (fn [chunk] (mapv deref (mapv (fn [s] (future (let [t (run-one :tesseract s) v (run-one :vision s)]
                                                                                        (println (format "%s tess exit %s %.0fs | vision exit %s %.0fs" (fs/file-name s) (:exit t) (/ (:wall-ms t) 1000.0) (:exit v) (/ (:wall-ms v) 1000.0))) (flush)
                                                                                        {:src s :tess t :vis v}))) chunk)))
                                       (partition-all jobs sources))))
      rows (mapv compare-one pairs)]
  (spit out (pr-str rows))
  (println "wrote" out (count rows) "controls"))
