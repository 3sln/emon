#!/usr/bin/env bb
;; How well does emon's visibility classification (P3.6, D152-D157) agree with other readers of real PDFs?  (DEVLOG D157)
;;
;;     bb tools/vis/corpus-vis.clj                    a deterministic sample of 240 files of corpus/ (small ones)
;;     bb tools/vis/corpus-vis.clj --n 600 --max-kb 4000 --pages 3
;;     bb tools/vis/corpus-vis.clj --paths-only --n 5000 --max-kb 100000 --pages 1000      every file, the paths' column only
;;     bb tools/vis/corpus-vis.clj --files a.pdf b.pdf          or --files-from LIST (one path a line)
;;
;; AN ORACLE, NOT A GATE (it needs the untracked corpus/, the generated Rust through `kin/scripts/content-sweep`,
;; `pdftotext` and `pdftoppm`; babashka, as D094 says all scripting is). It sets emon's glyph-by-glyph verdict
;; (`vis`, and the invisible render modes 3 and 7) against two witnesses that do not share its code:
;;
;;   POPPLER'S TEXT   `pdftotext -bbox`: the words poppler extracts, with their boxes. Poppler drops what it cannot place on
;;                    the page (and some text whose rendering it deems invisible); it does not look at what is painted over
;;                    or clipped. For every glyph: is its centre inside a poppler word? An emon `visible` glyph that poppler
;;                    does not have is an emon over-claim (or poppler's gap); an emon `offpage` glyph that poppler does have is
;;                    the reverse. The other classes poppler does not reason about: it keeps them, and that is reported.
;;   POPPLER'S PAGE   `pdftoppm -r 72 -cropbox`: the rendered page. A glyph emon calls visible must leave ink in its box; one it
;;                    calls hidden for a reason the renderer can show (white, clipped, occluded, ocg-hidden, transparent) must
;;                    leave none. "Ink" is a spread of at least 8 gray levels in the box's pixels, which is what an
;;                    edge of any glyph makes and what a flat patch of colour does not.
;;
;;   PATHS (P3.6a, D219)  the same classes for painted paths (not clips, not shadings): a column of how many paths fall in
;;                    each class and how many of those are rule-like (D179's sizes: at most 4 pt thick, at least 5 pt long,
;;                    not near white); the renderer holds a sample of them to the same ink test as the glyphs.
;;
;; Only glyphs with a character that is not white space are counted. A glyph box that is not wholly on the rendered page
;; or a text glyph whose box is under 3 pixels either way is not judged by the renderer (it cannot show it).
(require '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[babashka.process :refer [shell process]])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParentFile .getPath))
(def args (vec *command-line-args*))
(defn opt [k dflt] (if-let [i (some (fn [j] (when (= (nth args j) k) j)) (range (count args)))] (nth args (inc i)) dflt))
(def n-files (parse-long (opt "--n" "240")))
(def max-kb (parse-long (opt "--max-kb" "2500")))
(def max-pages (parse-long (opt "--pages" "3")))
(def paths-render? (boolean (some #{"--paths-render"} args)))   ;; with --paths-only: hold the rule-like paths of the first --pages pages to poppler's render too
(def paths-only? (boolean (some #{"--paths-only"} args)))   ;; count the paths' classes only: no poppler, no renders
(def explicit (cond (some #{"--files"} args) (vec (rest (drop-while #(not= "--files" %) args)))
                    (some #{"--files-from"} args) (vec (remove str/blank? (str/split-lines (slurp (opt "--files-from" ""))))))) 
(def scratch (io/file root ".claude" "scratch-vis"))

(def vz-names {0 "visible" 1 "clipped" 2 "offpage" 3 "white" 4 "zero-size" 5 "occluded" 6 "ocg-hidden" 7 "transparent"})

(defn sample-files []
  (let [all (->> (file-seq (io/file root "corpus")) (filter #(and (.isFile %) (str/ends-with? (.getName %) ".pdf")
                                                                   (<= (.length %) (* 1024 max-kb))
                                                                   (not (str/includes? (.getPath %) "/_gold/"))))
                 (map #(.getPath %)) sort vec)
        k (max 1 (quot (count all) n-files))]
    (vec (take n-files (take-nth k all)))))

(defn sweep [files]
  (let [r @(process {:dir root :out :string :err :string :in (str/join "\n" files)} "kin/scripts/content-sweep" "vis")]
    (when-not (zero? (:exit r)) (println (:err r)) (System/exit 1))
    (loop [lines (str/split-lines (:out r)) pending [] paint [] paths [] out {}]
      (if-let [l (first lines)]
        (let [f (str/split l #"\t" -1)]
          (case (first f)
            "V" (recur (rest lines) (conj pending f) paint paths out)
            ;; I page vis vby vchk x0 y0 x1 y1 z: a picture; Q page kind flags fill stroke x0 y0 x1 y1 z clip: a path (a clip is not paint)
            "I" (recur (rest lines) pending (conj paint {:page (parse-long (nth f 1)) :x0 (parse-long (nth f 5)) :y0 (parse-long (nth f 6)) :x1 (parse-long (nth f 7)) :y1 (parse-long (nth f 8))}) paths out)
            ;; Q page kind flags fill stroke x0 y0 x1 y1 z clip vis vby vchk backdrop pcount
            "Q" (let [flags (parse-long (nth f 3))
                       p {:page (parse-long (nth f 1)) :flags flags :fill (parse-long (nth f 4)) :stroke (parse-long (nth f 5))
                          :x0 (parse-long (nth f 6)) :y0 (parse-long (nth f 7)) :x1 (parse-long (nth f 8)) :y1 (parse-long (nth f 9))
                          :vis (parse-long (nth f 12)) :backdrop (parse-long (nth f 15)) :pcount (parse-long (nth f 16))}]
                   (recur (rest lines) pending
                          (if (zero? (bit-and 4 flags)) (conj paint (select-keys p [:page :x0 :y0 :x1 :y1])) paint)
                          (if (zero? (bit-and 36 flags)) (conj paths p) paths) out))
            "F" (recur (rest lines) [] [] [] (assoc out (second f) {:glyphs pending :paint paint :paths paths :status (nth f 2)}))
            (recur (rest lines) pending paint paths out)))
        out))))

(defn parse-glyph [f]
  ;; V page g mode vis vby vchk x0 y0 x1 y1 fill stroke size cs cp
  (let [n (fn [i] (parse-long (nth f i)))]
    {:page (n 1) :mode (n 3) :vis (n 4) :x0 (n 7) :y0 (n 8) :x1 (n 9) :y1 (n 10) :size (n 13) :ch (nth f 15 "")}))

(defn poppler-words [pdf]
  (let [r (shell {:out :string :err :string :continue true} "pdftotext" "-bbox" "-l" (str max-pages) pdf "-")]
    (when (zero? (:exit r))
      (loop [ms (re-seq #"<page width=\"([\d.]+)\" height=\"([\d.]+)\">|<word xMin=\"([\d.]+)\" yMin=\"([\d.]+)\" xMax=\"([\d.]+)\" yMax=\"([\d.]+)\">" (:out r))
             page 0 out {}]
        (if-let [m (first ms)]
          (if (nth m 1)
            (recur (rest ms) (inc page) (assoc out (inc page) []))
            (recur (rest ms) page (update out page conj (mapv #(parse-double (nth m %)) [3 4 5 6]))))
          out)))))

(defn in-word? [words cx cy]
  (some (fn [[x0 y0 x1 y1]] (and (<= (- x0 1.5) cx (+ x1 1.5)) (<= (- y0 1.5) cy (+ y1 1.5)))) words))

(defn render-pgm [pdf page]
  (.mkdirs scratch)
  (let [base (str (.getPath scratch) "/c")
        r (shell {:out :string :err :string :continue true} "pdftoppm" "-r" "72" "-gray" "-cropbox" "-f" (str page) "-l" (str page) pdf base)]
    (when (zero? (:exit r))
      (let [f (first (filter #(.startsWith (.getName %) "c-") (.listFiles scratch)))
            bytes (with-open [in (io/input-stream f)] (.readAllBytes in))
            s (String. bytes "ISO-8859-1")
            m (re-find #"^P5\s+(\d+)\s+(\d+)\s+(\d+)\s" s)]
        (.delete f)
        (when m
          (let [w (parse-long (nth m 1)) h (parse-long (nth m 2)) off (count (nth m 0))]
            {:w w :h h :px (fn [x y] (bit-and 0xff (aget bytes (+ off (* y w) x))))}))))))

(defn spread [{:keys [w h px]} g & [min-side]]
  (let [x0 (int (Math/floor (/ (:x0 g) 100.0))) y0 (int (Math/floor (/ (:y0 g) 100.0)))
        x1 (int (Math/ceil (/ (:x1 g) 100.0))) y1 (int (Math/ceil (/ (:y1 g) 100.0)))]
    (when (and (>= x0 0) (>= y0 0) (< x1 w) (< y1 h) (>= (- x1 x0) (or min-side 3)) (>= (- y1 y0) (or min-side 3)))
      (let [vs (for [y (range y0 (inc y1)) x (range x0 (inc x1))] (px x y))]
        (- (apply max vs) (apply min vs))))))

(def tally (atom {}))
(defn bump! [& ks] (swap! tally update ks (fnil inc 0)))

(defn rule-like?
  "D179's sizes: a box at most 4 pt thick and at least 5 pt long, not near white."
  [{:keys [x0 y0 x1 y1 flags fill stroke]}]
  (let [w (- x1 x0) h (- y1 y0)
        c (if (pos? (bit-and flags 8)) fill stroke)
        lum (if (neg? c) 255 (quot (+ (bit-and (bit-shift-right c 16) 255) (bit-and (bit-shift-right c 8) 255) (bit-and c 255)) 3))]
    (and (<= (min w h) 400) (>= (max w h) 500) (< lum 240))))

(declare render-pgm spread)

(defn judge-paths-render
  "Rule-like paths against the rendered page: ink (a spread of 8 gray levels) one pixel round the box for a path emon calls
  visible, none inside the box for one it calls hidden for a reason the renderer can show."
  [pdf paths]
  (let [ps (filter #(and (<= (:page %) max-pages) (rule-like? %)) paths)
        imgs (into {} (for [p (sort (set (map :page ps)))] [p (render-pgm pdf p)]))]
    (doseq [p ps :let [img (get imgs (:page p)) cls (vz-names (:vis p))] :when (and img (not (#{"offpage" "zero-size"} cls)))]
      (let [grow (if (zero? (:vis p)) 100 0)
            sp (spread img {:x0 (- (:x0 p) grow) :y0 (- (:y0 p) grow) :x1 (+ (:x1 p) grow) :y1 (+ (:y1 p) grow)} 1)]
        (when sp
          (bump! :path-render cls (if (>= sp 8) :ink :flat))
          (when (and (not (zero? (:vis p))) (>= sp 8))
            (swap! tally update [:disagree cls] (fnil conj []) [pdf (:page p) (:x0 p) (:y0 p) (:x1 p) (:y1 p)])))))))

(defn judge-paths [pdf paths]
  (when paths-render? (judge-paths-render pdf paths))
  (doseq [p paths :when (<= (:page p) max-pages)]
    (let [cls (vz-names (:vis p))]
      (bump! :path cls)
      (when (rule-like? p) (bump! :rule cls))
      (when (>= (:backdrop p) 0) (bump! :backdrop-known cls))
      (when (pos? (bit-and 512 (:flags p))) (bump! :path-truncated cls)))))

(defn judge-file [pdf {:keys [glyphs paint paths status]}]
  (when (and (= status "ok") paths-only?)
    (bump! :files)
    (judge-paths pdf paths))
  (when (and (= status "ok") (not paths-only?))
    (judge-paths pdf paths)
    (let [gs (->> glyphs (map parse-glyph) (filter #(and (<= (:page %) max-pages) (not (str/blank? (:ch %)))
                                                         (not= "?" (:ch %)))))
          words (poppler-words pdf)
          pages (sort (set (map :page gs)))
          imgs (into {} (for [p pages] [p (render-pgm pdf p)]))
          ;; where something visible is drawn: a hidden glyph under a visible one (text redrawn over a patch, a second
          ;; layer) leaves ink that is the other glyph's, so the renderer cannot judge it
          cell 2000
          grid (reduce (fn [m g] (if (and (zero? (:vis g)) (not (#{3 7} (:mode g))))
                                   (reduce (fn [m k] (update m k (fnil conj []) g)) m
                                           (for [i (range (quot (:x0 g) cell) (inc (quot (:x1 g) cell)))
                                                 j (range (quot (:y0 g) cell) (inc (quot (:y1 g) cell)))] [(:page g) i j]))
                                   m))
                       {} gs)
          covered? (fn [g] (let [cx (quot (+ (:x0 g) (:x1 g)) 2) cy (quot (+ (:y0 g) (:y1 g)) 2)]
                             (or (some (fn [v] (and (<= (:x0 v) cx (:x1 v)) (<= (:y0 v) cy (:y1 v))))
                                       (get grid [(:page g) (quot cx cell) (quot cy cell)]))
                                 ;; or any picture or path whose box meets the glyph's: ink there may be the paint's own
                                 (some (fn [v] (and (= (:page v) (:page g)) (< (:x0 v) (:x1 g)) (< (:x0 g) (:x1 v))
                                                    (< (:y0 v) (:y1 g)) (< (:y0 g) (:y1 v))))
                                       paint))))]
      (bump! :files)
      (doseq [g gs]
        (let [cls (if (#{3 7} (:mode g)) (if (zero? (:vis g)) "hidden-text" (str "hidden-text+" (vz-names (:vis g)))) (vz-names (:vis g)))
              cx (/ (+ (:x0 g) (:x1 g)) 200.0) cy (/ (+ (:y0 g) (:y1 g)) 200.0)
              ws (get words (:page g))
              in-p (when ws (boolean (in-word? ws cx cy)))
              img (get imgs (:page g))
              sp (when img (spread img g))]
          (bump! :glyph cls)
          (when (some? in-p) (bump! :poppler-has cls (if in-p :yes :no)))
          (when (and (some? sp) (not (#{"hidden-text" "offpage" "zero-size"} cls))
                     (or (= cls "visible") (not (covered? g))))
            (bump! :render cls (if (>= sp 8) :ink :flat))))))))

(defn pct [a b] (if (zero? b) "n/a" (format "%.2f%%" (* 100.0 (/ a b)))))

(defn report []
  (let [t @tally g (fn [& ks] (get t (vec ks) 0))
        classes (sort (set (map (comp first rest) (filter #(= :glyph (first %)) (keys t)))))]
    (println (str "files judged: " (g :files) " (the first " max-pages " pages of each)"))
    (println "\nclass                       glyphs   poppler has it   render: ink / flat   (agreement with the claim)")
    (doseq [c classes]
      (let [n (g :glyph c) py (g :poppler-has c :yes) pn (g :poppler-has c :no)
            ri (g :render c :ink) rf (g :render c :flat)]
        (println (format "%-26s %8d   %7d / %-7d   %7d / %-7d" c n py (+ py pn) ri (+ ri rf)))))
    (println "\nPATHS (painted, not clips or shadings)    paths   rule-like   backdrop known   truncated")
    (doseq [c (sort (set (map second (filter #(= :path (first %)) (keys t)))))]
      (println (format "%-26s %14d %11d %16d %11d" c (g :path c) (g :rule c) (g :backdrop-known c) (g :path-truncated c))))
    (println (format "%-26s %14d %11d" "all" (reduce + (map #(g :path %) (map second (filter #(= :path (first %)) (keys t)))))
                     (reduce + (map #(g :rule %) (map second (filter #(= :path (first %)) (keys t)))))))
    (when paths-render?
      (println "\nRULE-LIKE PATHS on poppler's page (ink = a spread of 8 levels; visible should show ink, hidden should not)")
      (doseq [c (sort (set (map second (filter #(= :path-render (first %)) (keys t)))))]
        (println (format "%-26s ink %7d   flat %7d" c (g :path-render c :ink) (g :path-render c :flat))))
      (doseq [[k v] t :when (= :disagree (first k))]
        (println (str "  hidden but inked (" (second k) "): " (count v) " e.g. " (pr-str (take 4 v))))))
    (let [vis-py (g :poppler-has "visible" :yes) vis-pn (g :poppler-has "visible" :no)
          vis-ri (g :render "visible" :ink) vis-rf (g :render "visible" :flat)
          hidden-classes ["clipped" "white" "occluded" "ocg-hidden" "transparent"]
          hr-ink (reduce + (map #(g :render % :ink) hidden-classes)) hr-flat (reduce + (map #(g :render % :flat) hidden-classes))]
      (println)
      (println (str "VISIBLE glyphs that poppler's text also has: " (pct vis-py (+ vis-py vis-pn)) " (" vis-py " of " (+ vis-py vis-pn) ")"))
      (println (str "VISIBLE glyphs with ink in their box on poppler's page: " (pct vis-ri (+ vis-ri vis-rf)) " (" vis-ri " of " (+ vis-ri vis-rf) ")"))
      (println (str "HIDDEN (clipped, white, occluded, ocg-hidden, transparent) glyphs with NO ink on poppler's page: " (pct hr-flat (+ hr-ink hr-flat)) " (" hr-flat " of " (+ hr-ink hr-flat) ")"))
      (println (str "OFFPAGE glyphs that poppler's text also drops: " (pct (g :poppler-has "offpage" :no) (+ (g :poppler-has "offpage" :yes) (g :poppler-has "offpage" :no)))
                    " (" (g :poppler-has "offpage" :no) " of " (+ (g :poppler-has "offpage" :yes) (g :poppler-has "offpage" :no)) ")")))))

(defn -main []
  (.mkdirs scratch)
  (let [files (or explicit (sample-files))
        chunks (partition-all 40 files)]
    (println (str "sampling " (count files) " files"))
    (doseq [ch chunks]
      (let [res (sweep ch)]
        (doseq [f ch :let [r (get res f)] :when r]
          (judge-file f r))))
    (report)))

(-main)
