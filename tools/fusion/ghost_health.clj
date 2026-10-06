#!/usr/bin/env bb
;; Do the OCR blocks over a watermark, a seal, a signature or blank paper score under the blocks of the page's text? (DEVLOG P7.2, D282)
;; NOT a gate: it needs Tesseract, rustc, poppler and ImageMagick, and the rasters and truth masks `bb ink` builds (run `bb ink` once).
;;
;;     bb ghost-health [--limit N] [--reocr] [--verbose]
;;
;; Per page of P6.6's overlay scans (`test/corpus/synth/scan-ovl/`, 130 dpi, the overlays ours, so the truth is exact):
;;   1. the scan's raster is read by the engine (the OCR driver of P2.6, which asks for 150 dpi whatever the raster is: the scans are 130,
;;      so Tesseract's resolution hint is 150, which changes nothing it measures in pixels; Tesseract through `tess.rs`),
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
(def WORD-TEXT-MIN 0.10)

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
          (doseq [f (if live? ["port.rs" "testport.rs" "plan.rs" "tess.rs"] ["port.rs" "testport.rs"])] (fs/copy (str "host/rust/src/" f) (str dir "/" f)))
          (spit (str dir "/emon.rs")
                (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                             (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
          (fs/copy main (str dir "/main.rs"))
          (apply shell {:dir dir} "rustc" "--edition" "2021" "-O" "-A" "warnings"
                 (concat (when live? ["--cfg" "live" "-L" lib]) ["-o" "prog" "main.rs"]))))
      (spit sf stamp))))

(defn ocr-ints!
  "The engine's answer for one raster, cached in `ocr-file`: `magick-in` is the ImageMagick arguments that read it."
  [magick-in ocr-file pdf]
  (when (or reocr? (not (fs/exists? ocr-file)) (zero? (fs/size ocr-file)))
    (let [pgm (str work "/page.pgm") tdir (str work "/t")]
      (fs/delete-tree tdir)
      (apply shell {:err :string} "magick" (concat magick-in ["-colorspace" "Gray" (str "pgm:" pgm)]))
      (let [r (sh {:err :string :out :string} (str work "/ocr/prog") "record" tdir pdf pgm "150")]
        (when-not (zero? (:exit r)) (throw (ex-info (str "ocr failed: " (:err r)) {}))))
      (let [m (re-find #":kind :ocr :req \[[^\]]*\] :ans \[([^\]]*)\]" (slurp (str tdir "/transcript.edn")))]
        (spit ocr-file (or (second m) ""))))))

(defn overlay-boxes
  "[kind [[x0 y0 x1 y1] ...]] for each overlay of a page that has one: the boxes of the connected strokes of its mask (watermark,
   seal, signature) after a dilation of 6 px, which is where an engine that read the overlay in pieces would put its words. A
   diagonal watermark's letters are separate pieces; the union box of a diagonal word is mostly the text under it."
  [dir page]
  (vec (for [kind ["wm" "seal" "sig"]
             :let [m (str dir "/m-" kind page ".png")]
             :when (fs/exists? m)
             :let [out (:out (sh {:out :string :err :string} "magick" m "-morphology" "Dilate" "Disk:6" "-define" "connected-components:verbose=true"
                                 "-connected-components" "8" "null:"))
                   bs (for [l (str/split-lines out)
                            :let [mm (re-find #"^\s*\d+: (\d+)x(\d+)\+(\d+)\+(\d+) \S+ (\S+) gray\w*\(255" l)]
                            :when (and mm (>= (parse-double (nth mm 5)) 300.0))]
                        (let [[w h x y] (map parse-long (rest (take 5 mm)))] [x y (+ x w) (+ y h)]))]
             :when (seq bs)]
         [kind (vec bs)])))

(defn drive
  "One page raster, one OCR answer, ghost boxes (a file or \"-\") and the truth masks through the Rust harness:
   {:blocks [...] :words [...] :ghost-blocks [...] :ghost-words [...]}."
  [rec ocr-file ghosts-file]
  (let [{:keys [stem page o w h truths]} rec]
    (do
      (let [r (apply sh {:err :string :out :string} (str work "/health/prog") o (str w) (str h) "3" (str DPI) ocr-file ghosts-file (map second truths))]
        (when-not (zero? (:exit r)) (throw (ex-info (str stem " p" page ": " (:err r)) {})))
        (let [names (map first truths)
              box (fn [x0 y0 x1 y1] (max 1 (* (- x1 x0) (- y1 y0))))
              shares (fn [tm area] {:tshare (/ (double (get tm :body 0)) area) :dshare (/ (double (get tm :doc 0)) area)
                                    :ovl (/ (double (+ (get tm :wm 0) (get tm :seal 0) (get tm :sig 0))) area)})]
          {:ms (some->> (str/split-lines (:out r)) (filter #(str/starts-with? % "T hl ")) first (#(subs % 5)) parse-double)
           :words (vec (for [l (str/split-lines (:out r)) :when (str/starts-with? l "W ")]
                         (let [[_ wi score x0 y0 x1 y1 inkpx gray & ts] (mapv parse-long (str/split l #" "))
                               area (box x0 y0 x1 y1)]
                           (merge {:stem stem :page page :word wi :score score :area area :inkpx inkpx :gray gray} (shares (zipmap names ts) area)))))
           :ghost-words (vec (for [l (str/split-lines (:out r)) :when (str/starts-with? l "V ")]
                               (let [[_ wi score x0 y0 x1 y1 inkpx gray & ts] (mapv parse-long (str/split l #" "))
                                     area (box x0 y0 x1 y1)]
                                 (merge {:stem stem :page page :word wi :score score :area area :inkpx inkpx :gray gray} (shares (zipmap names ts) area)))))
           :ghost-blocks (vec (for [l (str/split-lines (:out r)) :when (str/starts-with? l "G ")]
                                (let [[b score ink plaus script body seal art x0 y0 x1 y1 nwords inkpx & ts] (mapv parse-long (rest (str/split l #" ")))
                                      area (box x0 y0 x1 y1)]
                                  (merge {:stem stem :page page :block b :score score :ink ink :plaus plaus :body body :seal seal :art art
                                          :words nwords :area area :inkpx inkpx}
                                         (shares (zipmap names ts) area)))))
           :blocks (vec (for [l (str/split-lines (:out r)) :when (str/starts-with? l "B ")]
                          (let [[b score ink plaus script body seal art x0 y0 x1 y1 nwords inkpx & ts] (mapv parse-long (rest (str/split l #" ")))
                                area (box x0 y0 x1 y1)]
                            (merge {:stem stem :page page :block b :score score :ink ink :plaus plaus :body body :seal seal :art art
                                    :words nwords :area area :inkpx inkpx}
                                   (shares (zipmap names ts) area)))))})))))

(defn run-page! [rec]
  (let [{:keys [stem page o w h]} rec
        dir (str (fs/parent o))
        pdf (str "test/corpus/synth/scan-ovl/" stem ".pdf")
        ocr-file (str dir "/o" page ".ocr")
        gf (str dir "/o" page ".ghosts")
        boxes (overlay-boxes dir page)]
    (ocr-ints! ["-size" (format "%dx%d" w h) "-depth" "8" (str "rgb:" o)] ocr-file pdf)
    (spit gf (str/join "\n" (for [[_ bs] boxes b bs] (str/join " " b))))
    (let [res (drive rec ocr-file (if (seq boxes) gf "-"))]
      (assoc res :ghosts-kinds (mapv first boxes)))))

(defn classify-word [w]
  (cond (>= (:tshare w) WORD-TEXT-MIN) :text
        (and (< (:tshare w) GHOST-MAX) (< (:dshare w) GHOST-MAX)) :ghost
        :else :other))

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
(def pages-out (mapv run-page! recs))
(def blocks (vec (mapcat :blocks pages-out)))
(def words (vec (mapcat :words pages-out)))
;; what an engine that read each overlay as a word would have put there: one word (and block) per overlay, over the box of its ink
(def ghost-blocks (vec (mapcat :ghost-blocks pages-out)))
(def ghost-words (vec (mapcat :ghost-words pages-out)))
(when-let [f (second (drop-while #(not= % "--dump") args))] (spit f (pr-str {:blocks blocks :words words :ghost-blocks ghost-blocks :ghost-words ghost-words})))
(def by-page (group-by (juxt :stem :page) blocks))

(defn mean [xs] (if (seq xs) (/ (double (reduce + xs)) (count xs)) Double/NaN))

(let [ms (keep :ms pages-out)]
  (println (format "\n  hl-page per page (rust, %d pages, ~%.0f OCR words each): mean %.2f ms, max %.2f ms"
                   (count ms) (/ (count words) (double (max 1 (count ms)))) (mean ms) (apply max ms))))
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
  (let [wcls (group-by classify-word words)
        wby (group-by (juxt :stem :page) words)
        wpages (for [[k ws] wby :let [t (filter #(= :text (classify-word %)) ws) g (filter #(= :ghost (classify-word %)) ws)] :when (and (seq t) (seq g))] {:t t :g g})
        wpairs (for [p wpages g (:g p) t (:t p)] (compare (:score t) (:score g)))
        wps (if (seq wpairs) (/ (double (count (filter pos? wpairs))) (count wpairs)) Double/NaN)
        wties (if (seq wpairs) (/ (double (count (filter zero? wpairs))) (count wpairs)) Double/NaN)
        under (fn [ws] (/ (double (count (filter #(< (:score %) 700) ws))) (max 1 (count ws))))]
    (println (format "\n  %d words: %d text, %d ghost (no true body or document ink under them), %d other"
                     (count words) (count (wcls :text)) (count (wcls :ghost)) (count (wcls :other))))
    (println (format "  mean word score: text %.0f, ghost %.0f; under tl-trusted (700): text %.3f, ghost %.3f"
                     (mean (map :score (wcls :text))) (mean (map :score (wcls :ghost))) (under (wcls :text)) (under (wcls :ghost))))
    (println (format "  (ghost, text) word pairs of one page: ghost scores lower %.3f, tied %.3f   [%d pages with both]" wps wties (count wpages))))
  (let [text-by (group-by (juxt :stem :page) (filter #(= :text (classify %)) blocks))
        gh-by (group-by (juxt :stem :page) ghost-blocks)
        pgs (for [[k gs] gh-by :let [ts (get text-by k)] :when (seq ts)] {:page k :max-ghost (apply max (map :score gs)) :min-text (apply min (map :score ts)) :g gs :t ts})
        pok (filter #(< (:max-ghost %) (:min-text %)) pgs)
        pairs (for [p pgs g (:g p) t (:t p)] (compare (:score t) (:score g)))
        wtext-by (group-by (juxt :stem :page) (filter #(= :text (classify-word %)) words))
        wgh-by (group-by (juxt :stem :page) ghost-words)
        wpairs (for [[k gs] wgh-by :let [ts (get wtext-by k)] :when (seq ts) g gs t ts] (compare (:score t) (:score g)))
        share (fn [xs] (if (seq xs) (/ (double (count (filter pos? xs))) (count xs)) Double/NaN))]
    (println (format "\n  OVERLAY GHOSTS (one word over the ink of each watermark, seal and signature, as if an engine had read it): %d blocks, %d words on %d pages"
                     (count ghost-blocks) (count ghost-words) (count gh-by)))
    (println (format "  mean score: ghost blocks %.0f, text blocks %.0f; ghost words %.0f, text words %.0f"
                     (mean (map :score ghost-blocks)) (mean (map :score (filter #(= :text (classify %)) blocks)))
                     (mean (map :score ghost-words)) (mean (map :score (filter #(= :text (classify-word %)) words)))))
    (println (format "  %s pages where EVERY ghost block scores under EVERY text block of the page: %d of %d = %.3f (target >= 0.95)"
                     (if (>= (/ (count pok) (max 1.0 (count pgs))) 0.95) "ok  " "MISS") (count pok) (count pgs) (/ (double (count pok)) (max 1 (count pgs)))))
    (println (format "      (ghost, text) block pairs of one page ordered right: %.3f; word pairs: %.3f (%d pairs)" (share pairs) (share wpairs) (count wpairs)))
    (when verbose?
      (doseq [p (sort-by :page pgs)]
        (println (format "  %-24s p%d ghost blocks %d max %4d | text blocks %d min %4d %s" (first (:page p)) (second (:page p)) (count (:g p)) (:max-ghost p)
                         (count (:t p)) (:min-text p) (if (< (:max-ghost p) (:min-text p)) "" "<-- overlap"))))))
  (when verbose?
    (doseq [p (sort-by :page pages)]
      (println (format "  %-26s p%d min-text %4d max-ghost %4d %s" (first (:page p)) (second (:page p)) (:min-text p) (:max-ghost p)
                       (if (< (:max-ghost p) (:min-text p)) "" "<-- overlap"))))
    (doseq [b (sort-by (juxt :stem :page :block) (filter #(= :ghost (classify %)) blocks))]
      (println (format "  ghost %-22s p%d block %3d score %4d ink %4d plaus %4d body %4d ovl %.2f tshare %.3f words %d"
                       (:stem b) (:page b) (:block b) (:score b) (:ink b) (:plaus b) (:body b) (:ovl b) (:tshare b) (:words b))))))
