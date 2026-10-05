(ns synth.mixed
  "The three MIXED documents of P6.8 (D227): a born-digital page that holds a table drawn in an image.

      bb -cp tools:tools/lib tools/synth/mixed.clj image    make html/img/mixed-*.jpg (poppler + ImageMagick; dev-time)
      bb -cp tools:tools/lib tools/synth/mixed.clj          write html/mixed-*.html and gold/mixed-*.rules-gen.json
      bb -cp tools:tools/lib tools/synth/mixed.clj --check  fail if a committed HTML or rules-gen file is not what this writes

    mixed-table-image          prose, an <img> of `table-simple-ruled`'s table (poppler, 200 dpi, a 0.7 degree skew, noise,
                               JPEG quality 80), 60 % of the page width, then prose;
    mixed-table-image-shaded   the same with `table-zebra`'s table (page 2 of its print);
    mixed-two-tables           one vector table (Chrome) and the first image's table on one page.

  THE GOLD holds both tables: the image's table is a `display:none` <table> of the HTML (Chrome prints nothing for it,
  html2gold reads it where it stands). THE RULES of the image table are the source document's rule truth (`gold/SRC.rules.json`,
  tools/oracle/rules.py) written in the image's pixels, carried through the crop, the resample to 200 dpi and the skew; the
  oracle (rules.py) places them on the page through the image placement Chrome chose, which pdfplumber reads from the PDF.
  A filled band is carried as its centre and size (a 0.7 degree skew moves a band's corners by a few pixels, its area not)."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pyjson :as pj :refer [om]]
            [synth.make-html :as mh]))

(def CORPUS "test/corpus/synth")
(def DPI 200)
(def SKEW 0.7)
(def PAD 3.0)

(def IMAGES
  {"mixed-table-image" {:src "table-simple-ruled" :page 1 :seed 9101 :profile "ruled"}
   "mixed-table-image-shaded" {:src "table-zebra" :page 2 :seed 9201 :profile "shaded"}})

(defn sh! [& cmd]
  (let [r (apply p/sh cmd)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "command failed: " (str/join " " cmd) "\n" (:err r)) r)))
    (:out r)))

(defn truth [stem] (pj/parse-file (str CORPUS "/gold/" stem ".rules.json")))

(defn page-truth [stem n] (first (filter #(= n (get % "n")) (get (truth stem) "pages"))))

(defn crop-box
  "The source table's box in points (rules and fills of its page, padded)."
  [{:keys [src page]}]
  (let [pg (page-truth src page)
        xs (concat (mapcat (fn [[x0 _ x1]] [x0 x1]) (get pg "rules")) (mapcat (fn [[x0 _ x1]] [x0 x1]) (get pg "fills")))
        ys (concat (mapcat (fn [[_ y0 _ y1]] [y0 y1]) (get pg "rules")) (mapcat (fn [[_ y0 _ y1]] [y0 y1]) (get pg "fills")))
        c #(/ % 100.0)]
    [(- (c (apply min xs)) PAD) (- (c (apply min ys)) PAD) (+ (c (apply max xs)) PAD) (+ (c (apply max ys)) PAD)]))

(defn jpg-size
  "[width height] from the JPEG's start-of-frame marker."
  [path]
  (let [b (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
        u (fn [i] (bit-and (aget b i) 0xff))]
    (loop [i 2]
      (let [m (u (inc i)) len (+ (* 256 (u (+ i 2))) (u (+ i 3)))]
        (if (#{0xc0 0xc1 0xc2} m)
          [(+ (* 256 (u (+ i 7))) (u (+ i 8))) (+ (* 256 (u (+ i 5))) (u (+ i 6)))]
          (recur (+ i 2 len)))))))

(defn make-image! [name {:keys [src page seed] :as spec}]
  (let [[x0 y0 x1 y1] (crop-box spec)
        k (/ DPI 72.0)
        tmp (str ".cache/mixed-" name)
        _ (.mkdirs (io/file tmp))
        _ (sh! "pdftoppm" "-r" (str DPI) "-f" (str page) "-l" (str page) "-png" (str CORPUS "/pdf/" src ".pdf") (str tmp "/p"))
        png (first (sort (filter #(str/ends-with? % ".png") (map str (.listFiles (io/file tmp))))))
        w (long (Math/ceil (* k (- x1 x0)))) h (long (Math/ceil (* k (- y1 y0))))
        out (str CORPUS "/html/img/" name ".jpg")]
    (.mkdirs (io/file CORPUS "html/img"))
    (sh! "magick" png "-crop" (format "%dx%d+%d+%d" w h (long (Math/floor (* k x0))) (long (Math/floor (* k y0)))) "+repage"
         "-background" "white" "-rotate" (str SKEW) "-gravity" "center" "-crop" (format "%dx%d+0+0" w h) "+repage"
         "-seed" (str seed) "-attenuate" "0.35" "+noise" "Gaussian" "-blur" "0x0.5" "-strip" "-quality" "80" out)
    (doseq [f (.listFiles (io/file tmp))] (.delete f))
    (.delete (io/file tmp))
    (println "  image" out (.length (io/file out)) "bytes" (str w "x" h))))

(defn image-truth
  "The source's rules and fills of its page in the image's pixels, after the skew: {:rules [[x0 y0 x1 y1 t]] :fills [[x0 y0 x1 y1 \"#hex\"]]}."
  [name {:keys [src page] :as spec}]
  (let [[bx by] (crop-box spec)
        k (/ DPI 72.0)
        [w h] (jpg-size (str CORPUS "/html/img/" name ".jpg"))
        cx (/ w 2.0) cy (/ h 2.0)
        th (Math/toRadians SKEW) c (Math/cos th) s (Math/sin th)
        ;; ImageMagick's positive angle turns the picture clockwise: in y-down pixels (dx, dy) -> (dx c - dy s, dx s + dy c)
        rot (fn [x y] (let [dx (- x cx) dy (- y cy)] [(+ cx (- (* dx c) (* dy s))) (+ cy (+ (* dx s) (* dy c)))]))
        px (fn [x y] [(* k (- (/ x 100.0) bx)) (* k (- (/ y 100.0) by))])
        r1 #(/ (Math/round (* 100.0 %)) 100.0)
        pg (page-truth src page)]
    {:rules (vec (for [[x0 y0 x1 y1 t] (get pg "rules")
                       :let [[a b] (apply rot (px x0 y0)) [c2 d] (apply rot (px x1 y1))]]
                   [(r1 a) (r1 b) (r1 c2) (r1 d) (r1 (* k (/ t 100.0)))]))
     :fills (vec (for [[x0 y0 x1 y1 col] (get pg "fills")
                       :let [[a b] (px x0 y0) [c2 d] (px x1 y1)
                             [mx my] (rot (/ (+ a c2) 2) (/ (+ b d) 2))
                             hw (/ (- c2 a) 2) hh (/ (- d b) 2)]]
                   [(r1 (- mx hw)) (r1 (- my hh)) (r1 (+ mx hw)) (r1 (+ my hh)) col]))
     :px [w h]}))

(defn gen-json [name]
  (let [spec (IMAGES name)
        t (image-truth name spec)]
    (str (pj/dumps (om "images" [(om "src" (str "img/" name ".jpg") "px" (:px t) "rules" (:rules t) "fills" (:fills t))]) {:indent 1}) "\n")))

(defn source-table [stem profile]
  (let [html (slurp (str CORPUS "/html/" stem ".html") :encoding "UTF-8")
        t (re-find #"(?s)<table .*?</table>" html)]
    (str/replace-first t #"<table data-profile=\"[a-z]+\"" (str "<table style=\"display:none\" data-profile=\"" profile "\""))))

(def PROSE-1 "The chart below was scanned from last year's printed report; the figures were not available in electronic form.")
(def PROSE-2 "Compare the totals with the ledger that follows before relying on them.")
(def IMG-CSS "table{border-collapse:collapse;font:10pt Helvetica,Arial,sans-serif} th,td{padding:4px 8px;text-align:left;border:1px solid #000} img{display:block;margin:0 auto 1em;width:60%}")

(defn html-of [name]
  (let [img (fn [n] (str "<img src=\"img/" n ".jpg\" alt=\"\">"))]
    (case name
      "mixed-table-image"
      (mh/doc "Printed Sales Chart" (str "<h1>Printed Sales Chart</h1><p>" PROSE-1 "</p>" (img name)
                                         (source-table "table-simple-ruled" "ruled") "<p>" PROSE-2 "</p>") {:css IMG-CSS})
      "mixed-table-image-shaded"
      (mh/doc "Printed Status Chart" (str "<h1>Printed Status Chart</h1><p>" PROSE-1 "</p>" (img name)
                                          (source-table "table-zebra" "shaded") "<p>" PROSE-2 "</p>") {:css IMG-CSS})
      "mixed-two-tables"
      (mh/doc "Ledger And Printed Chart"
              (str "<h1>Ledger And Printed Chart</h1><p>The current ledger is typed below.</p>"
                   "<table data-profile=\"ruled\"><thead><tr><th>Account</th><th>Owner</th><th>Balance</th></tr></thead><tbody>"
                   "<tr><td>Operating</td><td>Ana</td><td>12,400</td></tr><tr><td>Payroll</td><td>Ben</td><td>8,150</td></tr>"
                   "<tr><td>Reserve</td><td>Chen</td><td>31,000</td></tr></tbody></table>"
                   "<p>The printed chart of last year follows.</p>" (img "mixed-table-image")
                   (source-table "table-simple-ruled" "ruled") "<p>" PROSE-2 "</p>")
              {:css (str IMG-CSS " table{width:100%;margin:0 0 1em}")}))))

(defn outputs []
  (into {}
        (concat
         (for [n ["mixed-table-image" "mixed-table-image-shaded" "mixed-two-tables"]]
           [(str CORPUS "/html/" n ".html") (.getBytes ^String (html-of n) "UTF-8")])
         (for [n ["mixed-table-image" "mixed-table-image-shaded"]]
           [(str CORPUS "/gold/" n ".rules-gen.json") (.getBytes ^String (gen-json n) "UTF-8")])
         [[(str CORPUS "/gold/mixed-two-tables.rules-gen.json")
           (.getBytes (gen-json "mixed-table-image") "UTF-8")]])))

(defn -main [& args]
  (cond
    (= "image" (first args))
    (doseq [[n spec] IMAGES] (make-image! n spec))
    :else
    (let [outs (outputs)
          check? (some #{"--check"} args)
          bad (remove (fn [[pth ^bytes bs]] (let [f (io/file pth)] (and (.exists f) (java.util.Arrays/equals bs (java.nio.file.Files/readAllBytes (.toPath f)))))) outs)]
      (if check?
        (if (empty? bad)
          (println (str "  ok   mixed: " (count outs) " committed files are what tools/synth/mixed.clj writes"))
          (do (doseq [[pth _] bad] (println "  FAIL" pth "is not what tools/synth/mixed.clj writes")) (System/exit 1)))
        (do (doseq [[pth ^bytes bs] outs] (io/make-parents pth) (with-open [o (io/output-stream pth)] (.write o bs)))
            (println "wrote" (count outs) "files"))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
