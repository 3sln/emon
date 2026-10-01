(ns synth.degrade-fusion
  "Colour and overlay scans of the FUSION set (DEVLOG P6.6, D209).

      bb -cp tools:tools/lib -m synth.degrade-fusion [stem ...]     scan-color/, scan-ovl/, gold-ovl/, scan-ovl/FACTS.json
      bb -cp tools:tools/lib -m synth.degrade-fusion --check        every gold-ovl is what FACTS.json and the gold say

  The FUSION SET is test/corpus/synth/FUSION, one stem a line. For each stem, from the PDF poppler rasterises at DPI
  in RGB (the committed `scan/` stays 150 dpi grey, the P6.5 baseline):

    scan-color/STEM.pdf   skewed by an angle in +-3 degrees from the stem's seed (per page, recorded in `facts.skew`),
                          light Gaussian noise, a slight blur, JPEG at QUALITY: one picture a page;
    scan-ovl/STEM.pdf     the same pages with OVERLAYS pasted on first (so they are skewed with the page):
                            * a light-grey diagonal `COPY` (lightness 200-215), composited with `darken`, so ink
                              stays black under it;
                            * one of SIX SEALS (rings, arc text, a word in the middle; blue, red and purple, drawn by
                              magick from the table SEALS below) pasted over the end of a body line of page 1;
                            * a pen SIGNATURE (a bezier scribble) over a body line of the last page;
                            * on `bank-statement` and `columns-two`, BLEED-THROUGH: the next page (the previous for the
                              last) mirrored at 10 % darkness;
                            * on `table-simple-ruled` page 1 and `h-numbered` page 2, the page TURNED 90 / 180 degrees
                              (`facts.turns`), after the overlays.
                          The gold of an overlay scan is gold-ovl/STEM.ovl.gold.json: the document's gold plus a
                          `watermark` ledger entry `COPY` for every page and a `seal` node (subkind seal / signature,
                          colour, the text it prints) after the paragraph whose line it covers. Its `facts` are dropped
                          (ids change, so frags and lines would not match; the layout claim goes with them).
  Everything is seeded from stem and page. `scan-ovl/FACTS.json` records what was done (skews, turns, every overlay's
  page and box in points, the seal used): build.clj copies it to MANIFEST.json `facts`, and `--check` re-derives the
  gold-ovl files from it. Tooling (poppler, ImageMagick); no gate runs the pixel steps, `bb unit` runs `--check`."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pyjson :as pj :refer [om oassoc]]
            [synth.degrade :as D]))

(defn- fmt [f & args] (String/format java.util.Locale/ROOT f (to-array args)))   ; not pyfmt: its %f fails on 0.0 here

(def CORPUS D/CORPUS)
(def DPI 130)
(def QUALITY 45)
(def FONT "/System/Library/Fonts/Supplemental/Arial Bold.ttf")
(def FACTS-FILE (str CORPUS "/scan-ovl/FACTS.json"))
(def BLEED #{"bank-statement" "columns-two"})
(def TURNS {"table-simple-ruled" {1 90} "h-numbered" {2 180}})

(def SEALS
  [{:id 0 :rgb [31 63 159] :name "blue" :arc "HARBOR COUNTY CLERK" :center "SEAL"}
   {:id 1 :rgb [192 32 31] :name "red" :arc "CITY OF RIVERTON" :center "NOTARY"}
   {:id 2 :rgb [96 40 140] :name "purple" :arc "UNIVERSITY REGISTRAR" :center "OFFICIAL"}
   {:id 3 :rgb [31 63 159] :name "blue" :arc "DEPARTMENT OF STATE" :center "FILED"}
   {:id 4 :rgb [192 32 31] :name "red" :arc "CERTIFIED TRUE RECORD" :center "VERIFIED"}
   {:id 5 :rgb [96 40 140] :name "purple" :arc "BOARD OF EDUCATION" :center "APPROVED"}])

(defn seal-text [s] (str (:arc s) " " (:center s)))
(defn- rgb [[r g b]] (fmt "rgb(%d,%d,%d)" r g b))

(defn sh! [& cmd] (apply D/sh! cmd))

(defn fusion-stems []
  (vec (remove str/blank? (map str/trim (str/split-lines (slurp (str CORPUS "/FUSION")))))))

;; ------------------------------------------------------------------ lines of a page (poppler)
(def LINE #"<line xMin=\"([\d.]+)\" yMin=\"([\d.]+)\" xMax=\"([\d.]+)\" yMax=\"([\d.]+)\">|<word [^>]*>([^<]*)</word>")

(defn page-lines
  "[{:x0 :y0 :x1 :y1 :words n :text}] of one page's printed lines."
  [pdf page]
  (let [out (sh! "pdftotext" "-f" (str page) "-l" (str page) "-bbox-layout" pdf "-")
        acc (volatile! []) cur (volatile! nil)
        flush! (fn [] (when-let [l @cur] (vswap! acc conj (assoc l :words (count (:ws l)) :text (str/join " " (:ws l)))) (vreset! cur nil)))]
    (doseq [[_ x0 y0 x1 y1 w] (re-seq LINE out)]
      (if x0
        (do (flush!) (vreset! cur {:x0 (parse-double x0) :y0 (parse-double y0) :x1 (parse-double x1) :y1 (parse-double y1) :ws []}))
        (vswap! cur update :ws conj (-> w (str/replace "&amp;" "&") (str/replace "&lt;" "<") (str/replace "&gt;" ">")))))
    (flush!)
    (mapv #(dissoc % :ws) @acc)))

;; ------------------------------------------------------------------ gold: where an overlay goes
(defn- strip-ws [s] (str/replace (or s "") #"[\s ​]+" ""))

(defn- paragraph-slots
  "[[path node]] of every paragraph that is a direct child of a section or the document: where a seal may follow."
  [body]
  (letfn [(go [n path]
            (mapcat (fn [[i c]]
                      (let [p (conj path "children" i)]
                        (concat (when (= "paragraph" (get c "kind")) [[p c]])
                                (when (contains? #{"section"} (get c "kind")) (go c p)))))
                    (map-indexed vector (get n "children"))))]
    (go body [])))

(defn anchor
  "The path of the paragraph whose text holds the printed line, found once; nil when it is not found exactly once."
  [body line-text]
  (let [t (strip-ws line-text)
        hits (when (>= (count t) 12)
               (filter (fn [[_ c]] (str/includes? (strip-ws (get c "text")) t)) (paragraph-slots body)))]
    (when (= 1 (count hits)) (ffirst hits))))

(defn- candidates
  "Seeded order over the lines of a page that can anchor: wide lines of at least five words."
  [lines sd]
  (let [ok (vec (filter #(and (>= (:words %) 5) (> (- (:x1 %) (:x0 %)) 200)) lines))
        n (count ok)]
    (when (pos? n) (vec (for [k (range n)] (nth ok (mod (+ sd (* 7 k)) n)))))))

;; ------------------------------------------------------------------ the overlay pixels
(def PX (/ DPI 72.0))

(defn- letter-args
  "magick args that draw `text` letter by letter along the top arc of a circle (centre cx cy, radius r, pixels)."
  [text cx cy r size]
  (let [n (count text) step (min 13.0 (/ 230.0 (max 1 (dec n))))
        w (* 0.62 size) h (* 0.72 size)]
    (vec (mapcat
          (fn [i ch]
            (let [a (* step (- i (/ (dec n) 2.0)))          ; degrees clockwise from the top
                  rad (Math/toRadians a)
                  px (+ cx (* r (Math/sin rad))) py (- cy (* r (Math/cos rad)))
                  vx (/ w 2) vy (- (/ h 2))
                  ox (- px (- (* vx (Math/cos rad)) (* vy (Math/sin rad))))
                  oy (- py (+ (* vx (Math/sin rad)) (* vy (Math/cos rad))))]
              (if (= ch \space) [] ["-annotate" (fmt "%.2fx%.2f+%.1f+%.1f" a a ox oy) (str ch)])))
          (range) text))))

(defn seal-args [s cx cy r]
  (let [c (rgb (:rgb s)) size (* 0.30 r)]
    (vec (concat ["-gravity" "NorthWest" "-stroke" c "-strokewidth" (str (max 2 (* 0.045 r))) "-fill" "none"
                  "-draw" (fmt "circle %.1f,%.1f %.1f,%.1f" cx cy (+ cx r) cy)
                  "-strokewidth" (str (max 1 (* 0.02 r)))
                  "-draw" (fmt "circle %.1f,%.1f %.1f,%.1f" cx cy (+ cx (* 0.64 r)) cy)
                  "-stroke" "none" "-fill" c "-font" FONT "-pointsize" (str size)]
                 (letter-args (:arc s) cx cy (* 0.80 r) size)
                 ["-pointsize" (str (* 0.34 r))
                  "-annotate" (fmt "0x0+%.1f+%.1f" (- cx (* 0.5 0.62 0.34 r (count (:center s)))) (+ cy (* 0.12 r)))
                  (:center s)]))))

(defn signature-args [x y w h]
  (let [pt (fn [fx fy] (fmt "%.1f,%.1f" (+ x (* fx w)) (+ y (* fy h))))]
    ["-gravity" "NorthWest" "-stroke" "rgb(31,63,159)" "-strokewidth" "3" "-fill" "none"
     "-draw" (str "path 'M " (pt 0.03 0.75) " C " (pt 0.12 0.05) " " (pt 0.2 1.1) " " (pt 0.3 0.45)
                  " S " (pt 0.5 0.0) " " (pt 0.56 0.55) " S " (pt 0.78 1.0) " " (pt 0.86 0.3)
                  " S " (pt 0.95 0.2) " " (pt 0.99 0.4) "'")
     "-draw" (str "path 'M " (pt 0.1 0.95) " C " (pt 0.4 0.82) " " (pt 0.7 1.0) " " (pt 0.97 0.8) "'")]))

;; ------------------------------------------------------------------ one document
(defn- png-size [f] (mapv parse-long (str/split (str/trim (sh! "magick" "identify" "-format" "%w %h" f)) #"\s+")))

(defn- finish!
  "skew + noise + blur + JPEG of a page image."
  [src out angle sd w h]
  (sh! "magick" src "-background" "white" "-rotate" (fmt "%.3f" angle) "-gravity" "center" "-crop" (fmt "%sx%s+0+0" w h) "+repage"
       "-seed" (str sd) "-attenuate" "0.03" "+noise" "Gaussian" "-blur" "0x0.4"
       "-strip" "-sampling-factor" "4:2:0" "-quality" (str QUALITY) out))

(defn skew [sd] (/ (- (mod sd 6001) 3000) 1000.0))

(defn degrade-stem!
  "-> the FACTS entry of the stem (and writes scan-color/STEM.pdf and scan-ovl/STEM.pdf)."
  [stem tmp]
  (let [src (str CORPUS "/pdf/" stem ".pdf")
        _ (sh! "pdftoppm" "-r" (str DPI) "-png" src (str tmp "/p"))
        pages (vec (sort (filter #(str/ends-with? % ".png") (.list (io/file tmp)))))
        np (count pages)
        gold (pj/oparse (slurp (str CORPUS "/gold/" stem ".gold.json") :encoding "UTF-8"))
        body (get-in gold ["model" "body"])
        seal (nth SEALS (mod (D/seed stem 0) 6))
        used (volatile! #{})
        ;; where the seal goes (page 1) and where the signature goes (last page)
        pick (fn [page seed-off avoid]
               (let [lines (page-lines src page)
                     cs (candidates lines (+ (D/seed stem (+ page seed-off)) 0))]
                 (first (for [l cs :let [path (anchor body (:text l))] :when (and path (not (contains? avoid path)))]
                          (assoc l :path path)))))
        seal-at (pick 1 100 #{})
        sig-page (if (> np 1) np 1)
        sig-at (pick sig-page 200 (if (= sig-page 1) #{(:path seal-at)} #{}))
        ovl (vec (concat
                  (when seal-at [{"kind" "seal" "page" 1 "seal" (:id seal) "path" (:path seal-at)
                                  "box" (let [cx (- (:x1 seal-at) 60) cy (/ (+ (:y0 seal-at) (:y1 seal-at)) 2) r 34]
                                          (mapv #(Math/round (double %)) [(- cx r) (- cy r) (+ cx r) (+ cy r)]))}])
                  (when sig-at [{"kind" "signature" "page" sig-page "path" (:path sig-at)
                                 "box" (let [x (+ (:x0 sig-at) 120) y (- (/ (+ (:y0 sig-at) (:y1 sig-at)) 2) 16)]
                                         (mapv #(Math/round (double %)) [x y (+ x 130) (+ y 34)]))}])))
        turns (get TURNS stem {})
        skews-c (vec (for [i (range 1 (inc np))] (skew (D/seed stem i))))
        skews-o (vec (for [i (range 1 (inc np))] (skew (D/seed (str stem "/ovl") i))))
        wm-l (+ 200 (mod (D/seed stem 9) 16))]
    (doseq [[i f] (map-indexed (fn [i f] [(inc i) f]) pages)]
      (let [img (str tmp "/" f) [w h] (png-size img)
            cj (fmt "%s/c%03d.jpg" tmp i) oj (fmt "%s/o%03d.jpg" tmp i)
            ovl-img (fmt "%s/ov%03d.png" tmp i)
            nxt (when (> np 1) (str tmp "/" (nth pages (if (< i np) i (- np 2)))))]
        (finish! img cj (nth skews-c (dec i)) (D/seed stem i) w h)
        ;; overlays, in the page's own (unskewed) pixels
        (let [
              args (vec (concat
                         [img]
                         (when (and (BLEED stem) (> np 1))
                           ["(" nxt "-flop" "-fill" "white" "-colorize" "90%" ")" "-compose" "darken" "-composite"])
                         ["(" "-size" (fmt "%dx%d" w h) "xc:white" "-font" FONT "-pointsize" (str (Math/round (* 140 PX)))
                          "-fill" (fmt "rgb(%d,%d,%d)" wm-l wm-l wm-l) "-gravity" "center" "-annotate" "-35x-35+0+0" "COPY" ")"
                          "-compose" "darken" "-composite" "-compose" "over"]
                         (when (and seal-at (= i 1))
                           (let [[x0 y0 x1 y1] (get (first (filter #(= "seal" (get % "kind")) ovl)) "box")]
                             (seal-args seal (* PX (/ (+ x0 x1) 2.0)) (* PX (/ (+ y0 y1) 2.0)) (* PX 34))))
                         (when (and sig-at (= i sig-page))
                           (let [[x0 y0 x1 y1] (get (first (filter #(= "signature" (get % "kind")) ovl)) "box")]
                             (signature-args (* PX x0) (* PX y0) (* PX (- x1 x0)) (* PX (- y1 y0)))))
                         [ovl-img]))]
          (apply sh! "magick" args))
        (let [turned (if-let [t (get turns i)]
                       (let [f2 (fmt "%s/t%03d.png" tmp i)] (sh! "magick" ovl-img "-rotate" (str t) f2) f2)
                       ovl-img)
              [w2 h2] (png-size turned)]
          (finish! turned oj (nth skews-o (dec i)) (D/seed (str stem "/ovl") i) w2 h2))))
    (doseq [[dir pre] [["scan-color" "c"] ["scan-ovl" "o"]]]
      (let [out (str CORPUS "/" dir "/" stem ".pdf")]
        (.mkdirs (io/file CORPUS dir))
        (apply sh! "magick" (concat (for [i (range 1 (inc np))] (fmt "%s/%s%03d.jpg" tmp pre i))
                                    ["-density" (str DPI) "-units" "PixelsPerInch" out]))))
    (println (str "  fusion scans of " stem " (" np " pages, seal " (:id seal) ")"))
    (om "pages" np "skew" skews-c "skew-ovl" skews-o
        "turns" (apply om (mapcat (fn [[k v]] [(str k) v]) turns))
        "bleed" (boolean (BLEED stem))
        "watermark" (om "text" "COPY" "lightness" wm-l)
        "seal" (:id seal)
        "overlays" ovl)))

;; ------------------------------------------------------------------ gold-ovl
(defn- renumber [body]
  (let [n (volatile! -1)]
    ((fn go [x]
       (let [x (if (contains? x "id") (oassoc x "id" (vswap! n inc)) x)]
         (if (contains? x "children") (oassoc x "children" (mapv go (get x "children"))) x)))
     body)))

(defn- insert-after [body path node]
  (let [parent-path (vec (drop-last 2 path)) i (last path)
        upd (fn [parent] (oassoc parent "children"
                                 (vec (concat (take (inc i) (get parent "children")) [node] (drop (inc i) (get parent "children"))))))]
    (if (empty? parent-path) (upd body) (update-in body parent-path upd))))

(defn ovl-gold
  "The gold of an overlay scan, from the document's gold and its FACTS entry."
  [gold stem facts]
  (let [model (get gold "model")
        seal (nth SEALS (get facts "seal"))
        npages (get facts "pages")
        ;; insert later paths first so earlier paths stay valid: both overlays are paragraph-level children, so sort
        ;; by path descending
        ovls (sort-by #(vec (get % "path")) #(compare %2 %1) (get facts "overlays"))
        body (reduce (fn [b o]
                       (insert-after b (get o "path")
                                     (if (= "seal" (get o "kind"))
                                       (om "id" 0 "kind" "seal" "subkind" "seal" "color" (:name seal) "text" (seal-text seal))
                                       (om "id" 0 "kind" "seal" "subkind" "signature" "color" "blue" "text" ""))))
                     (get model "body") ovls)
        body (renumber body)
        nbody (count (filter #(contains? % "id") (tree-seq map? #(get % "children") body)))
        furniture (vec (map-indexed (fn [i f] (oassoc f "id" (+ nbody i))) (get model "furniture")))
        dropped (vec (sort-by (fn [d] [(get d "page") (let [i (.indexOf ["page-number" "running-title" "watermark" "duplicate-header-row"] (get d "reason"))] (if (neg? i) 9 i))])
                              (concat (get model "dropped")
                                      (for [p (range 1 (inc npages))] (om "reason" "watermark" "page" p "text" "COPY")))))
        model (-> model (oassoc "body" body) (oassoc "furniture" furniture) (oassoc "dropped" dropped))
        ann (-> (get gold "annotated") (dissoc "layout"))
        prov (oassoc (dissoc (get gold "provenance") "variants") "variants" (om "scan-ovl" (str "scan-ovl/" stem ".pdf")))]
    (-> (om "emon-gold" (get gold "emon-gold") "id" (get gold "id"))
        (oassoc "provenance" prov) (oassoc "annotated" ann) (oassoc "model" model))))

(defn gold-text [g] (str (pj/dumps g {:indent 1}) "\n"))

(defn- read-facts [] (pj/oparse (slurp FACTS-FILE :encoding "UTF-8")))

(defn check []
  (let [facts (read-facts)
        bad (vec (for [stem (fusion-stems)
                       :let [g (pj/oparse (slurp (str CORPUS "/gold/" stem ".gold.json") :encoding "UTF-8"))
                             want (gold-text (ovl-gold g stem (get facts stem)))
                             f (io/file CORPUS "gold-ovl" (str stem ".ovl.gold.json"))]
                       :when (or (not (.exists f)) (not= want (slurp f :encoding "UTF-8")))]
                   stem))
        ok-files (every? #(and (.exists (io/file CORPUS "scan-color" (str % ".pdf"))) (.exists (io/file CORPUS "scan-ovl" (str % ".pdf")))) (fusion-stems))]
    (if (and (empty? bad) ok-files (= (set (fusion-stems)) (set (keys facts))))
      (println (format "  ok   degrade-fusion: %d fusion documents have scan-color, scan-ovl and a gold-ovl equal to what FACTS.json and the gold say (%d seals used)"
                       (count (fusion-stems)) (count (distinct (map #(get % "seal") (vals facts))))))
      (do (println (str "  FAIL degrade-fusion: gold-ovl differs or files missing: " (str/join " " bad))) (System/exit 1)))))

(defn -main [& args]
  (if (= args ["--check"])
    (check)
    (let [stems (if (seq args) args (fusion-stems))
          facts (if (.exists (io/file FACTS-FILE)) (read-facts) (om))
          facts (reduce
                 (fn [facts stem]
                   (let [tmp (str (java.nio.file.Files/createTempDirectory "emon-fusion" (make-array java.nio.file.attribute.FileAttribute 0)))
                         f (try (degrade-stem! stem tmp)
                                (finally (doseq [x (.listFiles (io/file tmp))] (.delete x)) (.delete (io/file tmp))))
                         g (pj/oparse (slurp (str CORPUS "/gold/" stem ".gold.json") :encoding "UTF-8"))]
                     (.mkdirs (io/file CORPUS "gold-ovl"))
                     (spit (str CORPUS "/gold-ovl/" stem ".ovl.gold.json") (gold-text (ovl-gold g stem f)) :encoding "UTF-8")
                     (oassoc facts stem f)))
                 facts stems)
          facts (apply om (mapcat (fn [k] [k (get facts k)]) (sort (keys facts))))]
      (.mkdirs (io/file CORPUS "scan-ovl"))
      (spit FACTS-FILE (str (pj/dumps facts {:indent 1}) "\n") :encoding "UTF-8")
      (println (D/magick-version)))))
