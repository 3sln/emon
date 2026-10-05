#!/usr/bin/env bb
;; Do the OCR blocks over a watermark, a seal, a signature or blank paper score under the blocks of the page's text? (DEVLOG P7.2, D250)
;; NOT a gate: it needs Tesseract, rustc, poppler and ImageMagick, and the rasters and truth masks `bb ink` builds (run `bb ink` once).
;;
;;     bb ghost-health [--limit N] [--reocr] [--verbose]
;;
;; Per page of P6.6's overlay scans (`test/corpus/synth/scan-ovl/`, 130 dpi, the overlays ours, so the truth is exact):
;;   1. the scan's raster is read by the engine (the OCR driver of `bb tools/fusion/record_health.clj`, Tesseract through `tess.rs`),
;;      the answer cached beside the raster (`.cache/ink/STEM/oN.ocr`);
;;   2. `bench/mains/health.rs` runs kin's `nm-view` on the raster, `sc-answer`, `nm-feat-ocr` on every word and `hl-page`, and prints a
;;      line a block: its score, its components, its box and, per truth mask of `bb ink` (body, watermark, document ink, seal, signature),
;;      the pixels of the mask inside the box;
;;   3. a block is TEXT when at least `text-min` (6 %) of its box is true text ink (the clean render's body and document ink) and is a
;;      GHOST when under `ghost-max` (2 %) of its box is, which is what a block over blank paper, a watermark's light ink, a seal or a
;;      signature has: the engine read something where the page has no text. The truth is the corpus's, never the engine's.
;;   4. per page with at least one of each: does every ghost block score under every text block? Pooled: the share of ghost blocks under
;;      the lowest text block of their page, the mean scores of the two classes, and the per-component means.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(def args (vec *command-line-args*))
(def limit (some->> (second (drop-while #(not= % "--limit") args)) parse-long))
(def reocr? (some #{"--reocr"} args))
(def verbose? (some #{"--verbose"} args))
(def DPI 130)
(def TEXT-MIN 0.06)
(def GHOST-MAX 0.02)

(def work (str (fs/create-dirs ".cache/ghost-health")))

(defn build! []
  (shell {:out :string :err :string} "kin/scripts/emit")
  (let [lib (or (System/getenv "EMON_TESS_LIB") "/opt/homebrew/lib")
        stamp (str/join (map #(str (fs/size %)) (concat (fs/glob "gen/rust/emon" "*.rs") ["bench/mains/health.rs" "test/ocr/rust/main.rs"])))
        sf (str work "/stamp")]
    (when-not (and (fs/exists? sf) (= (slurp sf) stamp) (fs/exists? (str work "/ocr/prog")) (fs/exists? (str work "/health/prog")))
      (doseq [[d main live?] [["ocr" "test/ocr/rust/main.rs" true] ["health" "bench/mains/health.rs" false]]]
        (let [dir (str work "/" d)]
          (fs/delete-tree dir)
          (fs/create-dirs (str dir "/emon"))
          (doseq [f (fs/glob "gen/rust/emon" "*.rs")] (fs/copy f (str dir "/emon/" (fs/file-name f))))
          (doseq [f (if live? ["port.rs" "testport.rs" "plan.rs" "tess.rs"] ["port.rs"])] (fs/copy (str "host/rust/src/" f) (str dir "/" f)))
          (spit (str dir "/emon.rs")
                (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                             (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
          (fs/copy main (str dir "/main.rs"))
          (apply shell {:dir dir} "rustc" "--edition" "2021" "-O" "-A" "warnings"
                 (concat (when live? ["--cfg" "live" "-L" lib]) ["-o" "prog" "main.rs"]))))
      (spit sf stamp))))

(defn ocr-ints! [rgb w h ocr-file pdf]
  (when (or reocr? (not (fs/exists? ocr-file)))
    (let [pgm (str work "/page.pgm") tdir (str work "/t")]
      (fs/delete-tree tdir)
      (shell {:err :string} "magick" "-size" (format "%dx%d" w h) "-depth" "8" (str "rgb:" rgb) "-colorspace" "Gray" (str "pgm:" pgm))
      (let [r (sh {:err :string :out :string} (str work "/ocr/prog") "record" tdir pdf pgm (str DPI))]
        (when-not (zero? (:exit r)) (throw (ex-info (str "ocr failed: " (:err r)) {}))))
      (let [m (re-find #":kind :ocr :req \[[^\]]*\] :ans \[([^\]]*)\]" (slurp (str tdir "/transcript.edn")))]
        (spit ocr-file (second m))))))

(defn run-page! [rec]
  (let [{:keys [stem page o w h truths]} rec
        ocr-file (str (fs/parent o) "/o" page ".ocr")
        pdf (str "test/corpus/synth/scan-ovl/" stem ".pdf")]
    (ocr-ints! o w h ocr-file pdf)
    (let [r (apply sh {:err :string :out :string} (str work "/health/prog") o (str w) (str h) "3" (str DPI) ocr-file (map second truths))]
      (when-not (zero? (:exit r)) (throw (ex-info (str stem " p" page ": " (:err r)) {})))
      (let [names (map first truths)]
        (vec (for [l (str/split-lines (:out r)) :when (str/starts-with? l "B ")]
               (let [v (mapv parse-long (rest (str/split l #" ")))
                     [b score ink plaus script body seal art x0 y0 x1 y1 nwords inkpx & ts] v
                     area (max 1 (* (- x1 x0) (- y1 y0)))
                     tm (zipmap names ts)]
                 {:stem stem :page page :block b :score score :ink ink :plaus plaus :body body :seal seal :art art :words nwords
                  :area area :inkpx inkpx :tshare (/ (double (get tm :body 0)) area) :dshare (/ (double (get tm :doc 0)) area)
                  :wmshare (/ (double (get tm :wm 0)) area)
                  :ovl (/ (double (+ (get tm :wm 0) (get tm :seal 0) (get tm :sig 0))) area)})))))))

(defn classify [b]
  (cond (>= (:tshare b) TEXT-MIN) :text
        (and (< (:tshare b) GHOST-MAX) (< (:dshare b) GHOST-MAX)) :ghost
        :else :other))

(def stems (cond->> (sort (map #(str (fs/file-name %)) (fs/list-dir ".cache/ink"))) limit (take limit)))
(def recs
  (vec (mapcat (fn [s] (let [f (str ".cache/ink/" s "/done.edn")]
                         (if (fs/exists? f) (edn/read-string (slurp f)) (do (println "  (no .cache/ink for" s ": run `bb ink`)") []))))
               stems)))
(when (empty? recs) (println "no pages: run `bb ink` first") (System/exit 2))
(build!)
(def blocks (vec (mapcat run-page! recs)))
(def by-page (group-by (juxt :stem :page) blocks))

(defn mean [xs] (if (seq xs) (/ (double (reduce + xs)) (count xs)) Double/NaN))

(let [cls (group-by classify blocks)
      pages (for [[k bs] by-page
                  :let [t (filter #(= :text (classify %)) bs) g (filter #(= :ghost (classify %)) bs)]
                  :when (and (seq t) (seq g))]
              {:page k :min-text (apply min (map :score t)) :max-ghost (apply max (map :score g)) :t t :g g})
      ok (filter #(< (:max-ghost %) (:min-text %)) pages)
      ghost-under (let [gs (mapcat (fn [p] (map #(< (:score %) (:min-text p)) (:g p))) pages)] (if (seq gs) (/ (double (count (filter true? gs))) (count gs)) Double/NaN))
      pairs (for [p pages g (:g p) t (:t p)] (compare (:score t) (:score g)))
      pair-share (if (seq pairs) (/ (double (count (filter pos? pairs))) (count pairs)) Double/NaN)
      comp (fn [k bs] (mean (filter #(>= % 0) (map k bs))))]
  (println (format "\n  %d blocks on %d pages: %d text, %d ghost, %d other" (count blocks) (count by-page)
                   (count (cls :text)) (count (cls :ghost)) (count (cls :other))))
  (println (format "  mean score: text %.0f, ghost %.0f" (mean (map :score (cls :text))) (mean (map :score (cls :ghost)))))
  (println (format "  mean components (text | ghost): ink %.0f | %.0f, plausible %.0f | %.0f, body-share %.0f | %.0f"
                   (comp :ink (cls :text)) (comp :ink (cls :ghost)) (comp :plaus (cls :text)) (comp :plaus (cls :ghost))
                   (comp :body (cls :text)) (comp :body (cls :ghost))))
  (println (format "  %s pages with both kinds where EVERY ghost block scores under EVERY text block: %d of %d = %.3f (target >= 0.95)"
                   (if (>= (/ (count ok) (max 1.0 (count pages))) 0.95) "ok  " "MISS") (count ok) (count pages) (/ (double (count ok)) (max 1 (count pages)))))
  (println (format "      ghost blocks under the lowest text block of their page: %.3f; (ghost, text) pairs of one page ordered right: %.3f"
                   ghost-under pair-share))
  (when verbose?
    (doseq [p (sort-by :page pages)]
      (println (format "  %-26s p%d min-text %4d max-ghost %4d %s" (first (:page p)) (second (:page p)) (:min-text p) (:max-ghost p)
                       (if (< (:max-ghost p) (:min-text p)) "" "<-- overlap"))))
    (doseq [b (sort-by (juxt :stem :page :block) (filter #(= :ghost (classify %)) blocks))]
      (println (format "  ghost %-22s p%d block %3d score %4d ink %4d plaus %4d body %4d ovl %.2f tshare %.3f words %d"
                       (:stem b) (:page b) (:block b) (:score b) (:ink b) (:plaus b) (:body b) (:ovl b) (:tshare b) (:words b))))))
