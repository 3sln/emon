(ns synth.wm-pdf
  "Hand-edited PDFs of the fusion corpus (DEVLOG P6.6, D209): bytes written by babashka, like tagged_pair.clj.

      bb -cp tools:tools/lib tools/synth/wm_pdf.clj patch     (print.sh runs it after Chrome) edit/make the six files
      bb -cp tools:tools/lib tools/synth/wm_pdf.clj check     (bb unit) every committed file is what `patch` makes
      bb -cp tools:tools/lib tools/synth/wm_pdf.clj words     re-read the word boxes of hidden-older-ocr (pdftotext)

  EVERY FILE IS ITS SOURCE PLUS ONE APPENDED INCREMENTAL UPDATE (new objects, a new xref section, a `/Prev`
  trailer): the source's bytes stay a prefix of the file, so `check` recovers the source from the file itself
  (the bytes up to its first %%EOF) and requires `patch` to give the committed bytes back.

    wm-marked          SPECIMEN, grey, 45 degrees, in marked content `/Artifact <</Subtype /Watermark>> BDC .. EMC`;
    wm-ocg             the same in an optional content group named `Watermark`, off for viewing and on for printing
                       (the catalog's `/AS` print event);
    wm-annot           the same in the appearance of a Watermark annotation (`/FixedPrint`);
    tl-broken-shift    the body font's (Georgia) ToUnicode rewritten so every letter maps to the NEXT letter (a to b,
                       z to a): plausible garbage, every glyph still `how` = tounicode;
    tl-broken-unmapped the same font's ToUnicode removed: its glyphs have no code point (`how` unknown);
    hidden-older-ocr   scan/h-plain.pdf (pages that are one picture) plus a render-mode-3 text layer of the gold
                       text with about 5 % of the letters substituted by a seeded rule (a worse previous OCR), word
                       boxes from tools/synth/hidden_ocr_words.edn.
  The first five are Chrome's prints of html/STEM.html (print.sh); the last has no Chrome print."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)
           (java.util Random)
           (java.util.zip Inflater)))

(def CORPUS "test/corpus/synth")
(def WORDS-EDN "tools/synth/hidden_ocr_words.edn")
(def CHROME-STEMS ["wm-marked" "wm-ocg" "wm-annot" "tl-broken-shift" "tl-broken-unmapped"])
(def STEMS (conj CHROME-STEMS "hidden-older-ocr"))

(def L1 java.nio.charset.StandardCharsets/ISO_8859_1)
(defn ->s [^bytes b] (String. b L1))
(defn ->b [^String s] (.getBytes s L1))
(defn slurp-bytes [f] (java.nio.file.Files/readAllBytes (.toPath (io/file f))))

;; ------------------------------------------------------------------ reading a PDF's last section
(defn- startxref [s] (parse-long (second (re-find #"startxref\s+(\d+)\s+%%EOF\s*$" s))))

(defn- xref-offsets
  "{object number offset} of the classic xref table at `at` (one section: Skia's and ImageMagick's)."
  [s at]
  (let [[_ first-n n] (re-find #"xref\s+(\d+)\s+(\d+)\s" (subs s at (min (count s) (+ at 40))))
        body (subs s (+ at (.indexOf ^String (subs s at) "\n") 1))
        ents (re-seq #"(\d{10}) (\d{5}) ([nf])" body)]
    (into {} (for [[i [_ off _ t]] (map-indexed vector (take (parse-long n) ents)) :when (= t "n")]
               [(+ (parse-long first-n) i) (parse-long off)]))))

(defn parse-pdf [^bytes b]
  (let [s (->s b) at (startxref s)
        trailer (subs s (.lastIndexOf s "trailer"))]
    {:s s :len (count s) :prev at :offs (xref-offsets s at)
     :size (parse-long (second (re-find #"/Size (\d+)" trailer)))
     :root (parse-long (second (re-find #"/Root (\d+) 0 R" trailer)))
     :info (some-> (re-find #"/Info (\d+) 0 R" trailer) second parse-long)}))

(defn obj-dict
  "The text of object n between `obj` and `endobj` (objects without a stream, or the dictionary of one)."
  [{:keys [s offs]} n]
  (let [o (offs n) a (+ (.indexOf ^String s "obj" (int o)) 3) e (.indexOf ^String s "endobj" (int a))]
    (subs s a e)))

(defn stream-data
  "[dict bytes] of a stream object with a direct /Length."
  [{:keys [s offs] :as pdf} n]
  (let [d (obj-dict pdf n) len (parse-long (second (re-find #"/Length (\d+)" d)))
        k (+ (.indexOf ^String s "stream" (int (offs n))) 6)
        k (if (= \return (.charAt ^String s k)) (+ k 2) (+ k 1))]
    [d (subs s k (+ k len))]))

(defn inflate [^String data]
  (let [inf (Inflater.) out (ByteArrayOutputStream.) buf (byte-array 4096)]
    (.setInput inf (->b data))
    (while (not (.finished inf))
      (let [n (.inflate inf buf)] (when (and (zero? n) (.needsInput inf)) (throw (ex-info "short flate stream" {}))) (.write out buf 0 n)))
    (->s (.toByteArray out))))

(defn page-objects [{:keys [offs] :as pdf}]
  (vec (for [n (sort (keys offs)) :when (re-find #"/Type /Page(?![s\w])" (obj-dict pdf n))] n)))

;; ------------------------------------------------------------------ writing the update
(defn stream-obj [dict-extra body] (str "<<" dict-extra "/Length " (count body) ">>\nstream\n" body "\nendstream"))

(defn append-update
  "prefix bytes + objects {n body-text} (a body is what stands between `obj` and `endobj`) + xref + trailer."
  [^bytes prefix {:keys [size root info prev]} objs]
  (let [out (StringBuilder. (->s prefix))
        offs (doall (for [[n body] (sort-by key objs)]
               (let [o (.length out)]
                 (.append out (str n " 0 obj\n" body "\nendobj\n"))
                 [n o])))
        xo (.length out)
        size' (max size (inc (apply max (keys objs))))]
    (.append out "xref\n")
    (doseq [[n o] offs] (.append out (format "%d 1\n%010d 00000 n \n" n o)))
    (.append out (str "trailer\n<</Size " size' " /Root " root " 0 R" (when info (str " /Info " info " 0 R")) " /Prev " prev ">>\nstartxref\n" xo "\n%%EOF\n"))
    (->b (str out))))

;; ------------------------------------------------------------------ the watermark's pieces
(def FONT "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding>>")
(def SPEC-TEXT "BT /FWM 90 Tf 0.82 g 0.7071 0.7071 -0.7071 0.7071 110 230 Tm (SPECIMEN) Tj ET")

(defn- splice [d a b] (if (str/includes? d a) (str/replace-first d a b) (throw (ex-info (str "no " a " in " d) {}))))

(defn- splice-re [d re rep] (if (re-find re d) (str/replace-first d re rep) (throw (ex-info (str "no " re " in " d) {}))))
(defn- splice* [d a b] (if (instance? java.util.regex.Pattern a) (splice-re d a b) (splice d a b)))

(defn patch-watermark [^bytes prefix kind]
  (let [pdf (parse-pdf prefix) pages (page-objects pdf)
        base (:size pdf)
        ;; objects: 0 font (shared), 1 OCG / annotation form, then per page: content (n), (n+1 is the font ref)
        font-n base
        ocg-n (inc base)
        first-page (+ base 2)]
    (let [objs (reduce
                (fn [m [i pn]]
                  (let [n (+ first-page (* 3 i))     ; content stream; n+1 = unused number reserved for the font ref
                        content (case kind
                                  :marked (str "q /Artifact <</Subtype /Watermark>> BDC " SPEC-TEXT " EMC Q")
                                  :ocg (str "q /OC /MC0 BDC " SPEC-TEXT " EMC Q")
                                  :annot "")
                        d (obj-dict pdf pn)]
                    (case kind
                      :annot
                      (let [form n ann (+ n 1) ]
                        (-> m
                            (assoc form (stream-obj (str "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Resources <</ExtGState <</G <</ca 0.3>>>> /Font <</FWM " font-n " 0 R>>>>") (str "q /G gs 0.4 g " SPEC-TEXT " Q")))
                            (assoc ann (str "<</Type /Annot /Subtype /Watermark /Rect [0 0 612 792] /F 4 /AP <</N " form " 0 R>> /FixedPrint <</Type /FixedPrint /Matrix [1 0 0 1 0 0] /H 0 /V 0>>>>"))
                            (assoc pn (splice* d #"^\s*<<" (str "<</Annots [" ann " 0 R] ")))))
                      (-> m
                          (assoc n (stream-obj "" content))
                          (assoc pn (-> d
                                        (splice* #"/Contents (\d+) 0 R" (str "/Contents [" n " 0 R $1 0 R]"))
                                        (splice* "/Font <<" (str "/Font <</FWM " font-n " 0 R "))
                                        (cond-> (= kind :ocg) (splice* "/Resources <<" (str "/Resources <</Properties <</MC0 " ocg-n " 0 R>> "))))))))
                )
                {font-n FONT}
                (map-indexed vector pages))
          objs (case kind
                 :ocg (-> objs
                          (assoc ocg-n "<</Type /OCG /Name (Watermark) /Usage <</Print <</PrintState /ON>> /View <</ViewState /OFF>>>>>>")
                          (assoc (:root pdf) (splice* (obj-dict pdf (:root pdf)) #"^\s*<<"
                                                      (str "<</OCProperties <</OCGs [" ocg-n " 0 R] /D <</Name (Default) /OFF [" ocg-n " 0 R] /AS [<</Event /Print /Category [/Print] /OCGs [" ocg-n " 0 R]>>]>>>> "))))
                 objs)]
      (append-update prefix pdf objs))))

;; ------------------------------------------------------------------ the broken text layers
(defn- cmap-pairs
  "[[code-hex dst-hex]] of every bfchar and bfrange entry of a CMap's text."
  [cmap]
  (concat
   (apply concat
          (for [[_ sec] (re-seq #"(?s)beginbfchar(.*?)endbfchar" cmap)]
            (for [[_ a b] (re-seq #"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>" sec)] [a b])))
   (apply concat
          (for [[_ sec] (re-seq #"(?s)beginbfrange(.*?)endbfrange" cmap)]
            (apply concat
                   (for [[_ a b c] (re-seq #"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>" sec)]
                     (let [lo (Integer/parseInt a 16) hi (Integer/parseInt b 16) w (count a)
                           base (Integer/parseInt c 16) cw (count c)]
                       (for [k (range (inc (- hi lo)))]
                         [(format (str "%0" w "X") (+ lo k)) (format (str "%0" cw "X") (+ base k))]))))))))

(defn- shift-letter [^long cp]
  (cond (<= (int \a) cp (dec (int \z))) (inc cp) (= cp (int \z)) (int \a)
        (<= (int \A) cp (dec (int \Z))) (inc cp) (= cp (int \Z)) (int \A)
        :else cp))

(defn- shift-dst [hex]
  (apply str (for [p (partition 4 hex)] (format "%04X" (shift-letter (Integer/parseInt (apply str p) 16))))))

(defn- cmap-text [pairs]
  (str "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
       "/CIDSystemInfo <</Registry (Adobe) /Ordering (UCS) /Supplement 0>> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
       "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
       (apply str (for [chunk (partition-all 100 pairs)]
                    (str (count chunk) " beginbfchar\n" (apply str (for [[a b] chunk] (str "<" a "> <" b ">\n"))) "endbfchar\n")))
       "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend"))

(defn- body-font
  "[font object number, ToUnicode object number] of the Georgia body font."
  [{:keys [offs] :as pdf}]
  (let [f (first (for [n (sort (keys offs))
                       :let [d (obj-dict pdf n)]
                       :when (and (re-find #"/BaseFont /\w+\+Georgia\s" d) (str/includes? d "/Subtype /Type0"))]
                   [n (parse-long (second (re-find #"/ToUnicode (\d+) 0 R" d)))]))]
    (or f (throw (ex-info "no Georgia Type0 font with a ToUnicode" {})))))

(defn patch-shift [^bytes prefix]
  (let [pdf (parse-pdf prefix) [_ tu] (body-font pdf)
        [_ data] (stream-data pdf tu)
        pairs (cmap-pairs (inflate data))]
    (when (< (count pairs) 20) (throw (ex-info "implausibly few ToUnicode entries" {})))
    (append-update prefix pdf {tu (stream-obj "" (cmap-text (for [[a b] pairs] [a (shift-dst b)])))})))

(defn patch-unmapped [^bytes prefix]
  (let [pdf (parse-pdf prefix) [fn_ _] (body-font pdf)]
    (append-update prefix pdf {fn_ (splice* (obj-dict pdf fn_) #"\s*/ToUnicode \d+ 0 R" "")})))

;; ------------------------------------------------------------------ hidden-older-ocr
(def CONFUSE {\a \o \e \c \i \l \l \i \o \0 \n \h \h \b \u \v \s \5 \t \f \r \n \c \e \d \a \m \n})

(defn substitute
  "About 5 % of the letters substituted by a seeded rule (a worse OCR's confusions); every other character kept."
  [words]
  (let [rnd (Random. 20260930)]
    (for [w words]
      (apply str (for [c w]
                   (if (Character/isLetter ^char c)
                     (let [hit (zero? (.nextInt rnd 20))] (if (and hit (CONFUSE c)) (CONFUSE c) c))
                     c))))))

(defn- pdf-str [w] (str "(" (str/replace (apply str (map #(if (< 31 (int %) 127) % \?) w)) #"([()\\])" "\\\\$1") ")"))

(defn ocr-layer-stream [page-words H]
  (let [texts (vec (substitute (map :t page-words)))]
    (str "q BT 3 Tr /FHO 10 Tf\n"
         (apply str
                (for [[i {:keys [x0 y0 x1 y1]}] (map-indexed vector page-words)
                      :let [t (texts i) h (- y1 y0) size (format "%.2f" (* 0.9 h))
                            tz (format "%.1f" (/ (* 100.0 (- x1 x0)) (max 1.0 (* 0.5 0.9 h (count t)))))]]
                  (format "/FHO %s Tf %s Tz 1 0 0 1 %.2f %.2f Tm %s Tj\n" size tz x0 (+ (- H y1) (* 0.2 h)) (pdf-str t))))
         "ET Q")))

(defn patch-hidden [^bytes prefix]
  (let [pdf (parse-pdf prefix) pages (page-objects pdf)
        words (:pages (edn/read-string (slurp WORDS-EDN)))
        base (:size pdf) font-n base]
    (when (not= (count pages) (count words)) (throw (ex-info "page count differs from the word boxes" {})))
    (let [objs (reduce
                (fn [m [i pn]]
                  (let [n (+ base 1 i) d (obj-dict pdf pn)]
                    (-> m
                        (assoc n (stream-obj "" (ocr-layer-stream (nth words i) 792)))
                        (assoc pn (-> d
                                      (splice* #"/Contents (\d+) 0 R" (str "/Contents [" n " 0 R $1 0 R]"))
                                      (splice* "/Resources <<" (str "/Resources <</Font <</FHO " font-n " 0 R>> ")))))))
                {font-n "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding>>"}
                (map-indexed vector pages))]
      (append-update prefix pdf objs))))

(defn regen-words! []
  (let [out (:out (p/sh "pdftotext" "-bbox" (str CORPUS "/pdf/h-plain.pdf") "-"))
        pages (for [[_ pg] (re-seq #"(?s)<page [^>]*>(.*?)</page>" out)]
                (vec (for [[_ x0 y0 x1 y1 t] (re-seq #"<word xMin=\"([\d.]+)\" yMin=\"([\d.]+)\" xMax=\"([\d.]+)\" yMax=\"([\d.]+)\">([^<]*)</word>" pg)]
                       {:x0 (parse-double x0) :y0 (parse-double y0) :x1 (parse-double x1) :y1 (parse-double y1)
                        :t (-> t (str/replace "&amp;" "&") (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"") (str/replace "&#39;" "'"))})))]
    (spit WORDS-EDN (str ";; word boxes of pdf/h-plain.pdf (pdftotext -bbox, points, y down): the layout of hidden-older-ocr's text layer (P6.6)\n"
                         (pr-str {:pages (vec pages)}) "\n"))
    (println "wrote" WORDS-EDN (count (apply concat pages)) "words")))

;; ------------------------------------------------------------------ driver
(defn- first-eof [^bytes b]
  (let [s (->s b) i (.indexOf s "%%EOF")]
    (let [j (+ i 5)] (-> (if (and (< j (count s)) (= \newline (.charAt s j))) (inc j) j) (min (count s))))))

(defn make [stem ^bytes prefix]
  (case stem
    "wm-marked" (patch-watermark prefix :marked)
    "wm-ocg" (patch-watermark prefix :ocg)
    "wm-annot" (patch-watermark prefix :annot)
    "tl-broken-shift" (patch-shift prefix)
    "tl-broken-unmapped" (patch-unmapped prefix)
    "hidden-older-ocr" (patch-hidden prefix)))

(defn source-of
  "The source bytes of a stem: Chrome's print (the committed file up to its first %%EOF, so an already patched file
  patches again to itself), or scan/h-plain.pdf."
  [stem]
  (if (= stem "hidden-older-ocr")
    (slurp-bytes (str CORPUS "/scan/h-plain.pdf"))
    (let [b (slurp-bytes (str CORPUS "/pdf/" stem ".pdf"))] (java.util.Arrays/copyOf b (first-eof b)))))

(defn -main [& [cmd]]
  (case cmd
    "words" (regen-words!)
    "patch" (doseq [s STEMS]
              (let [out (make s (source-of s))]
                (with-open [o (io/output-stream (str CORPUS "/pdf/" s ".pdf"))] (.write o ^bytes out))
                (println "  patched" s (count out) "bytes")))
    "check" (let [bad (for [s STEMS
                            :let [committed (slurp-bytes (str CORPUS "/pdf/" s ".pdf"))
                                  made (make s (source-of s))]
                            :when (not (java.util.Arrays/equals committed ^bytes made))]
                        s)
                  ;; and the facts each file exists for, by reading the committed bytes back
                  facts (for [s ["wm-marked" "wm-ocg" "wm-annot"]]
                          (str/includes? (->s (slurp-bytes (str CORPUS "/pdf/" s ".pdf"))) "(SPECIMEN)"))
                  tu (let [b (->s (slurp-bytes (str CORPUS "/pdf/tl-broken-unmapped.pdf")))
                           i (.lastIndexOf ^String b "trailer")]
                       (pos? i))]
              (if (and (empty? bad) (every? true? facts) tu)
                (println (format "  ok   wm-pdf: %d hand-edited PDFs are what wm_pdf.clj makes of their sources (watermark as marked content, OCG and annotation; two broken ToUnicode; a hidden older OCR layer)" (count STEMS)))
                (do (println (str "  FAIL wm-pdf: differ from wm_pdf.clj: " (str/join " " bad))) (System/exit 1))))
    (do (println "usage: wm_pdf.clj patch|check|words") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
