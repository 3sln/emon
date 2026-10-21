#!/usr/bin/env bb
;; Print the VERSION "0" evidence dump (D056, P1.7's) of a version "1" dump (D096, SPEC 8.4.6): the inverse of the
;; migration, to prove that nothing the old format held was lost (DEVLOG P1.12 step 9). NOT a gate.
;;
;;     bb tools/evidence/v0.clj DUMP            a JSON dump (or a driver's output: only its `evidence: {...}` line
;;                                              is converted, every other line passes through)
;;
;; THE MAP is D096's "v0 -> v1 field map", read backwards:
;;   glyph   `page cp text how font size box` the same; `color` is the fill colour; `alpha` the fill alpha; `baseline` is
;;           `by0` (the segment's first y); `mcid` the innermost MARK with one, and `artifact` the D101 class of the nearest
;;           artifact mark (`watermark` a subtype, reached through the chain), both by walking `mark`'s parents;
;;   word    `page box glyphs text line src conf gap font size` the same; v0's `flags` was never written;
;;   line    `page box words dir rot src conf` the same; `baseline` is `by0`;
;;   path, image   the same names; `artifact` and `mcid` through the mark chain as for a glyph;
;;   component     the same names; `layer` is `ink`; v1's `view` and the rest are dropped;
;;   ocrword `page text box conf style`; `line` is the word's olines row less its run's first; `block` the ordinal of its
;;           line's paragraph's block among the run's blocks; `lang` the packed language (0 when the answer had an extension
;;           and no language); `baseline` the word's LINE's baseline taken back to pixels through the view and interpolated at the word's pixel centre, as v0 did (v0 interpolated in pixels
;;           and mapped: a centipoint of difference at most); `alts` the `oalts` of its symbols in order, `sym` the code-point
;;           offset of the symbol in the word and `cp` the first code point of the candidate's text;
;;   span    a `marks` row: `tag parent mcid actual alt exp lang glyphs flags stm`; `artifact` the class; `type` and
;;           `subtype` the PDF's names for the `ar` and `as` codes (a name that was none of them is gone).
;; THE ONLY DIFFERENCES D096 ALLOWS, then, are an OCR word's `baseline` (+-1 cpt) and the glyph `artifact` now reached
;; through the chain. v1 also has fields v0 had no room for (views, pages, fonts, features...): they are simply not printed.
(require '[cheshire.core :as json]
         '[clojure.string :as str])

;; ------------------------------------------------------------------ printing
(defn q [s]
  (str "\""
       (apply str (for [ch s]
                    (let [c (int ch)]
                      (cond (= ch \") "\\\""
                            (= ch \\) "\\\\"
                            (= ch \newline) "\\n"
                            (= ch \tab) "\\t"
                            (or (< c 0x20) (= c 0x2028) (= c 0x2029)) (format "\\u%04x" c)
                            :else (str ch)))))
       "\""))

(declare cpt-str)
(defn num-str
  "A JSON number as it was written: an integer as it is, a point value (the dump writes at most two decimals) through centipoints."
  [x]
  (if (float? x) (cpt-str (Math/round (* 100.0 (double x)))) (str x)))

(defn cpt-str
  "Integer centipoints as points, as the Model's serializer writes them (`72.5`, `-0.05`)."
  [v]
  (let [neg (neg? v) a (Math/abs (long v)) ip (quot a 100) fr (rem a 100)]
    (str (when neg "-") ip (when-not (zero? fr) (str "." (quot fr 10) (when-not (zero? (rem fr 10)) (rem fr 10)))))))

(defn cpt [x] (Math/round (* 100.0 (double x))))

(defn arr [xs] (str "[" (str/join "," xs) "]"))
(defn obj [& kvs]
  (str "{" (str/join "," (for [[k v] (partition 2 kvs) :when (some? v)] (str (q k) ":" v))) "}"))

(defn bits [xs] (when (seq xs) (arr (map q xs))))

;; ------------------------------------------------------------------ lookups
(defn chain
  "The marks from `m` outwards."
  [marks m]
  (loop [m m out []]
    (if (or (nil? m) (neg? m) (>= m (count marks))) out (recur (get (nth marks m) "parent" -1) (conj out (nth marks m))))))

(defn mcid-of [marks m]
  (first (filter #(>= % 0) (map #(get % "mcid" -1) (chain marks m)))))

(defn class-of
  "D101's class of an artifact mark row."
  [mk]
  (when (= "artifact" (get mk "kind"))
    (let [ty (get mk "type") su (get mk "subtype")]
      (cond (= su "watermark") "watermark"
            (= ty "background") "background"
            (or (= ty "pagination") (#{"header" "footer"} su)) "pagination"
            (= ty "layout") "layout"
            :else "other"))))

(defn artifact-of [marks m]
  (first (keep class-of (chain marks m))))

(def type-name {"pagination" "Pagination" "layout" "Layout" "background" "Background" "page" "Page"})
(def sub-name {"header" "Header" "footer" "Footer" "watermark" "Watermark"})

;; ------------------------------------------------------------------- arenas
(defn glyph [marks g]
  (let [m (get g "mark" -1)]
    (obj "page" (num-str (get g "page"))
         "cp" (when (contains? g "cp") (num-str (get g "cp")))
         "text" (when (contains? g "text") (q (get g "text")))
         "how" (when (contains? g "how") (q (get g "how")))
         "font" (num-str (get g "font" -1))
         "size" (num-str (get g "size"))
         "box" (arr (map num-str (get g "box")))
         "baseline" (num-str (second (get g "baseline")))
         "rot" (when (contains? g "rot") (num-str (get g "rot")))
         "color" (when (contains? g "color") (q (get g "color")))
         "alpha" (when (contains? g "alpha") (num-str (get g "alpha")))
         "mode" (when (contains? g "mode") (num-str (get g "mode")))
         "vis" (when (contains? g "vis") (q (get g "vis")))
         "artifact" (some-> (artifact-of marks m) q)
         "mcid" (some-> (mcid-of marks m) num-str)
         "word" (when (contains? g "word") (num-str (get g "word"))))))

(defn word [w]
  (obj "page" (num-str (get w "page"))
       "text" (when (contains? w "text") (q (get w "text")))
       "box" (arr (map num-str (get w "box")))
       "glyphs" (arr (map num-str (get w "glyphs")))
       "line" (when (contains? w "line") (num-str (get w "line")))
       "src" (bits (get w "src"))
       "conf" (when (contains? w "conf") (num-str (get w "conf")))
       "gap" (when (contains? w "gap") (num-str (get w "gap")))
       "font" (when (contains? w "font") (num-str (get w "font")))
       "size" (when (contains? w "size") (num-str (get w "size")))))

(defn line [l]
  (obj "page" (num-str (get l "page"))
       "box" (arr (map num-str (get l "box")))
       "words" (arr (map num-str (get l "words")))
       "baseline" (num-str (second (get l "baseline")))
       "dir" (when (contains? l "dir") (q (get l "dir")))
       "rot" (when (contains? l "rot") (num-str (get l "rot")))
       "src" (bits (get l "src"))
       "conf" (when (contains? l "conf") (num-str (get l "conf")))))

(defn path [marks p]
  (let [m (get p "mark" -1)]
    (obj "page" (num-str (get p "page"))
         "kind" (q (get p "kind"))
         "box" (arr (map num-str (get p "box")))
         "fill" (when (contains? p "fill") (q (get p "fill")))
         "stroke" (when (contains? p "stroke") (q (get p "stroke")))
         "width" (when (contains? p "width") (num-str (get p "width")))
         "alpha" (when (contains? p "alpha") (num-str (get p "alpha")))
         "flags" (bits (get p "flags"))
         "artifact" (some-> (artifact-of marks m) q)
         "mcid" (some-> (mcid-of marks m) num-str))))

(defn image [marks im]
  (let [m (get im "mark" -1)]
    (obj "page" (num-str (get im "page"))
         "box" (arr (map num-str (get im "box")))
         "obj" (num-str (get im "obj"))
         "px" (arr (map num-str (get im "px" [0 0])))
         "alpha" (when (contains? im "alpha") (num-str (get im "alpha")))
         "flags" (bits (get im "flags"))
         "artifact" (some-> (artifact-of marks m) q)
         "mcid" (some-> (mcid-of marks m) num-str))))

(defn component [c]
  (obj "page" (num-str (get c "page"))
       "kind" (when (contains? c "kind") (q (get c "kind")))
       "box" (arr (map num-str (get c "box")))
       "px" (when (contains? c "px") (arr (map num-str (get c "px"))))
       "area" (when (contains? c "area") (num-str (get c "area")))
       "fill" (when (contains? c "fill") (num-str (get c "fill")))
       "stroke" (when (contains? c "stroke") (num-str (get c "stroke")))
       "color" (when (contains? c "color") (q (get c "color")))
       "layer" (when (contains? c "ink") (num-str (get c "ink")))
       "conf" (when (contains? c "conf") (num-str (get c "conf")))
       "flags" (bits (get c "flags"))))

(defn pack-lang
  "An `eng` as the int v0 printed: the characters in the high bytes."
  [s]
  (reduce (fn [acc [i ch]] (bit-or acc (bit-shift-left (int ch) (- 24 (* 8 i))))) 0 (map-indexed vector s)))

(defn text-cps [s] (vec (.toArray (.codePoints ^String s))))

(defn block-ordinals
  "{oblocks-row ordinal} of the `block` rows of each run, in row order."
  [oblocks]
  (let [rows (map-indexed vector oblocks)]
    (first (reduce (fn [[m counts] [i b]]
                     (if (= "block" (get b "kind"))
                       (let [run (get b "run" -1)] [(assoc m i (get counts run 0)) (update counts run (fnil inc 0))])
                       [m counts]))
                   [{} {}] rows))))

(defn sym-len [s] (cond (contains? s "text") (count (text-cps (get s "text"))) (contains? s "cp") 1 :else 0))

(defn ocrword [ev i w]
  (let [{:strs [oruns oblocks olines osyms oalts]} ev
        run (get w "run" 0)
        run-row (nth oruns run)
        lrow (get w "line" -1)
        ext? (>= lrow 0)
        l0 (first (get run-row "lines" [0 0]))
        paragraph (when ext? (get (nth olines lrow) "par" -1))
        block (when (and ext? (>= paragraph 0)) (get (nth oblocks paragraph) "parent" -1))
        ord (get (block-ordinals oblocks) block)
        [sf sn] (get w "syms" [0 0])
        syms (subvec (vec osyms) sf (+ sf sn))
        offs (reductions + 0 (map sym-len syms))
        alts (for [[s off] (map vector syms offs)
                   :let [[af ac] (get s "alts" [0 0])]
                   a (subvec (vec oalts) af (+ af ac))]
               (obj "sym" (str off) "cp" (str (first (text-cps (get a "text")))) "conf" (when (contains? a "conf") (num-str (get a "conf")))))
        view (nth (get ev "views") (get run-row "view" 0))
        ;; the dump kept dots per inch x 100 until P12.6 (D611) and prints dots per inch since: a value over 2400 is the old one
        dpi100 (let [d (get view "dpi" 72)] (if (> d 2400) d (* 100 d)))
        ->px (fn [c] (Math/round (/ (* c dpi100) 720000.0)))
        [lbx0 lby0 lbx1 lby1] (when ext? (map (comp ->px cpt) (get (nth olines lrow) "baseline")))
        [pxx _ pxw _] (get w "px" [0 0 0 0])
        cx (+ pxx (quot pxw 2))
        base (when ext?
               (let [ypx (if (<= lbx1 lbx0) lby1 (+ lby0 (quot (* (- lby1 lby0) (- cx lbx0)) (- lbx1 lbx0))))]
                 (quot (* ypx 7200) (quot dpi100 100))))]
    (obj "page" (num-str (get w "page"))
         "text" (when (contains? w "text") (q (get w "text")))
         "box" (arr (map num-str (get w "box")))
         "conf" (when (contains? w "conf") (num-str (get w "conf")))
         "alts" (when (seq alts) (arr alts))
         "line" (when ext? (str (- lrow l0)))
         "block" (when ord (str ord))
         "baseline" (when (and base (not (zero? base))) (cpt-str base))
         "style" (bits (get w "style"))
         "lang" (when ext? (str (pack-lang (get w "lang" "")))))))

(defn span [m]
  (obj "page" (num-str (get m "page"))
       "tag" (when (contains? m "tag") (q (get m "tag")))
       "parent" (when (contains? m "parent") (num-str (get m "parent")))
       "mcid" (when (contains? m "mcid") (num-str (get m "mcid")))
       "artifact" (some-> (class-of m) q)
       "type" (when (= "artifact" (get m "kind")) (some-> (get type-name (get m "type")) q))
       "subtype" (when (= "artifact" (get m "kind")) (some-> (get sub-name (get m "subtype")) q))
       "actual" (when (contains? m "actual") (q (get m "actual")))
       "alt" (when (contains? m "alt") (q (get m "alt")))
       "exp" (when (contains? m "exp") (q (get m "exp")))
       "lang" (when (contains? m "lang") (q (get m "lang")))
       "glyphs" (arr (map num-str (get m "glyphs")))
       "flags" (bits (get m "flags"))
       "stm" (when (contains? m "stm") (num-str (get m "stm")))))

(defn convert [text]
  (let [ev (json/parse-string text false)
        marks (vec (get ev "marks"))]
    (str "{\"emon-evidence\":\"0\""
         ",\"glyphs\":" (arr (map #(glyph marks %) (get ev "glyphs")))
         ",\"words\":" (arr (map word (get ev "words")))
         ",\"lines\":" (arr (map line (get ev "lines")))
         ",\"paths\":" (arr (map #(path marks %) (get ev "paths")))
         ",\"images\":" (arr (map #(image marks %) (get ev "images")))
         ",\"components\":" (arr (map component (get ev "components")))
         ",\"ocrwords\":" (arr (map-indexed #(ocrword ev %1 %2) (get ev "owords")))
         ",\"spans\":" (arr (map span marks))
         "}")))

(defn -main [path]
  (let [s (slurp path)]
    (if (str/includes? s "\nevidence: ")
      (println (str/join "\n" (for [l (str/split s #"\n" -1)]
                                (if (str/starts-with? l "evidence: ")
                                  (str "evidence: " (convert (subs l (count "evidence: "))))
                                  l))))
      (println (convert (str/trim s))))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main (first *command-line-args*)))
