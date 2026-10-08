#!/usr/bin/env bb
;; `stamp-ghost` on mapo80's labelled stamps, LIVE (P8.2, D209, D806): of the OCR words whose centre lies in a labelled stamp box, the share that REACH THE BODY (are in a
;; body node, not a seal's). Lower is better; the first target is 0.2 (DEVLOG P7.7, P8.2). NOT a gate and never committed: mapo80 is an aggregate of community sets with
;; unverified licences (D209), and it needs corpus/seals/mapo80-stamps (`corpus/fetch.sh stamps`), ImageMagick and a LIVE Engine driver (Tesseract).
;;
;;     bb stamp-ghost [--n N] [--bin PATH] [--set val|test] [--pages-dir DIR] [--keep]
;;
;; Each sampled image (every k-th of the labelled ones, so that the sample is the same every run) is wrapped in a one-page PDF whose page is the image's size in points (one
;; pixel a point, D088) with the image as the one DCT image of the page (a scan: the page unit decodes it, reads the ink layers and the tracer's primitives, asks the seals' strips),
;; converted with the driver `--bin` (default: the live Rust driver `kin/scripts/emon-rs-build` makes with EMON_RS_LIVE=1) and scored from its evidence and Model:
;;   a word is an OCR word of the first pass (evidence `owords` of the run whose pass is `first`) whose centre lies in a labelled box (YOLO `0 cx cy w h`, fractions of the image);
;;   it REACHES THE BODY when a node of the body that is not a seal has the word's letters and digits (case folded) among its own and a region that holds the word's centre.
;; A page's own text under a labelled stamp counts in the denominator as the stamp's words do: it is what the metric has always been (P6.6, `bb fusion-live --stamps`).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [sh shell]]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn opt [k d] (if-let [i (some #(when (= (nth (vec *command-line-args*) % nil) k) %) (range (count *command-line-args*)))] (nth (vec *command-line-args*) (inc i)) d))
(def N (parse-long (opt "--n" "80")))
(def SET (opt "--set" "val"))
(def ROOT "corpus/seals/mapo80-stamps")
(def BIN (or (opt "--bin" nil)
             (let [r (sh {:extra-env {"EMON_RS_LIVE" "1"} :err :inherit} "kin/scripts/emon-rs-build")]
               (when-not (zero? (:exit r)) (println "FAIL the live driver did not build") (System/exit 1))
               (str (fs/absolutize (str/trim (:out r)))))))
(def PAGES (opt "--pages-dir" (str (fs/create-temp-dir {:prefix "emon-stamps."}))))
(fs/create-dirs PAGES)

(def imgs (->> (fs/list-dir (str ROOT "/images/" SET)) (map str) sort
               (remove #(str/includes? % "/neg_"))
               (filter #(fs/exists? (str ROOT "/labels/" SET "/" (fs/strip-ext (fs/file-name %)) ".txt")))
               vec))
(def sample (let [k (max 1 (quot (count imgs) N))] (vec (take N (take-nth k imgs)))))

(defn labels [img]
  (for [l (str/split-lines (slurp (str ROOT "/labels/" SET "/" (fs/strip-ext (fs/file-name img)) ".txt"))) :when (seq (str/trim l))
        :let [[_ cx cy w h] (map parse-double (str/split (str/trim l) #"\s+"))]]
    [cx cy w h]))

(defn size [img]
  (mapv parse-long (str/split (str/trim (:out (sh {:out :string} "magick" "identify" "-format" "%w %h" (str img "[0]")))) #" ")))

(defn key* [s] (str/join (re-seq #"[\p{L}\p{N}]" (str/lower-case (or s "")))))

(defn walk [n] (when (map? n) (cons n (mapcat walk (get n "children")))))

(defn run1 [img]
  (let [id (fs/strip-ext (fs/file-name img))
        pdf (str PAGES "/" id ".pdf")
        [w h] (size img)]
    (when-not (fs/exists? pdf)
      (sh "magick" (str img "[0]") "-type" "TrueColor" "-quality" "92" "-units" "PixelsPerInch" "-density" "72" "-compress" "jpeg" pdf))
    (let [ev (sh {:out :string :err :string} BIN "--tesseract" "--out=evidence" pdf)
          js (sh {:out :string :err :string} BIN "--tesseract" "--out=json" pdf)]
      (when (and (zero? (:exit ev)) (zero? (:exit js)))
        (let [ed (json/parse-string (:out ev))
              m (json/parse-string (:out js))
              boxes (for [[cx cy bw bh] (labels img)] [(* w (- cx (/ bw 2))) (* h (- cy (/ bh 2))) (* w (+ cx (/ bw 2))) (* h (+ cy (/ bh 2)))])
              first-runs (keep-indexed (fn [i r] (when (= "first" (get r "pass")) r)) (get ed "oruns"))
              ow (get ed "owords")
              words (for [r first-runs :let [[w0 n] (get r "words" [0 0])] i (range w0 (+ w0 n))] (nth ow i))
              in-box? (fn [[x0 y0 x1 y1] [px py]] (and (<= x0 px x1) (<= y0 py y1)))
              body (for [n (walk (get m "body")) :when (and (not= "seal" (get n "kind")) (string? (get n "text")) (seq (get n "at")))] n)
              seal-nodes (count (filter #(= "seal" (get % "kind")) (walk (get m "body"))))
              inside (for [wd words
                           :let [[x0 y0 x1 y1] (get wd "box") c [(/ (+ x0 x1) 2) (/ (+ y0 y1) 2)]]
                           :when (and (seq (key* (get wd "text"))) (some #(in-box? % c) boxes))]
                       [wd c])
              reach (filter (fn [[wd [cx cy]]]
                              (some (fn [n] (and (some (fn [[_ x0 y0 x1 y1]] (and (<= x0 cx x1) (<= y0 cy y1))) (get n "at"))
                                                 (str/includes? (key* (get n "text")) (key* (get wd "text")))))
                                    body))
                            inside)]
          {:id id :in (count inside) :reach (count reach) :seals seal-nodes :labelled (count boxes)})))))

(let [rs (vec (keep run1 sample))
      in (reduce + (map :in rs))
      reach (reduce + (map :reach rs))
      by (group-by #(first (str/split (:id %) #"_\d")) rs)]
  (println (format "stamp-ghost (mapo80 %s, %d of %d labelled images, one page each): %d of %d OCR words inside a labelled stamp reach the body = %.4f"
                   SET (count rs) (count imgs) reach in (if (pos? in) (/ (double reach) in) 0.0)))
  (doseq [[k v] (sort-by key by)]
    (let [i (reduce + (map :in v)) r (reduce + (map :reach v))]
      (println (format "  %-28s %3d images  %4d words in stamps  %4d reach the body  %.3f   seal nodes %d for %d labelled stamps"
                       k (count v) i r (if (pos? i) (/ (double r) i) 0.0) (reduce + (map :seals v)) (reduce + (map :labelled v))))))
  (when-not (some #{"--keep"} *command-line-args*) (when-not (opt "--pages-dir" nil) (fs/delete-tree PAGES))))
