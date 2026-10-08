;; OBJECT-LEVEL PDF MUTATORS for `bb fuzz` (DEVLOG P10.5, D384). Each works on a `fuzz.pdfobj` model, so the
;; file the case writes still has a correct cross-reference table and reaches the page tree, the filters, the
;; fonts and the content interpreter, which a byte-level mutant of a real file seldom does:
;;
;;   dictionary keys      renamed, misspelt, dropped, doubled, moved to another key
;;   dictionary values    any other PDF value: null, huge, negative, real, name, string, array, dictionary, a
;;                        reference to a missing object, to itself, to another object
;;   huge counts          /Count /Length /Width /Height /N /First /Size /Columns /Colors /FirstChar ...
;;   deep nesting         arrays and dictionaries thousands deep, in a dictionary or in a content stream
;;   cyclic references    a reference retargeted to its own object, its parent or another object; /Parent /Kids
;;                        /Contents /Resources /XObject /Font /ToUnicode /SMask /Length pointed back
;;   filters              /Filter and /DecodeParms replaced, chained, repeated; data that does not fit them
;;   stream data          bytes flipped, cut, grown; a Flate stream inflated, changed and deflated again
;;   content streams     operands made extreme, operators swapped, runs repeated, `q`/`BT` nested
;;   ToUnicode and CMaps  counts, ranges, hex widths, `usecmap`, begin/end keywords
;;   fonts                /Widths /FirstChar /LastChar /Encoding /Differences /Subtype /DescendantFonts /FontDescriptor
;;   image dictionaries   /Width /Height /BitsPerComponent /ColorSpace /Decode /ImageMask /SMask /Mask; inline images
;;   objects              copied, dropped, swapped, a page tree of thousands of leaves
(ns fuzz.pdfmut
  (:require [clojure.string :as str]
            [fuzz.mutate :as m]
            [fuzz.pdfobj :as po]))

(def ^:private below m/below)
(def ^:private pick m/pick)
(def ^:private between m/between)

(def key-pool
  ["Type" "Subtype" "Parent" "Kids" "Count" "Contents" "Resources" "MediaBox" "CropBox" "Rotate" "Font" "XObject" "Length"
   "Filter" "DecodeParms" "Width" "Height" "BitsPerComponent" "ColorSpace" "Decode" "ImageMask" "SMask" "Mask" "ToUnicode"
   "Encoding" "Differences" "FirstChar" "LastChar" "Widths" "BaseFont" "DescendantFonts" "W" "DW" "FontDescriptor" "FontFile2"
   "BBox" "Matrix" "CharProcs" "FontMatrix" "Annots" "Dest" "A" "First" "Last" "Next" "Prev" "K" "S" "P" "Pg" "StructTreeRoot"
   "Outlines" "Names" "Dests" "Lang" "Info" "Root" "Pages" "Predictor" "Columns" "Colors" "N" "Size" "Index" "UseCMap" "ExtGState"])

(def count-keys
  ["Count" "Length" "Width" "Height" "N" "First" "Size" "Columns" "Colors" "Predictor" "BitsPerComponent" "FirstChar" "LastChar"
   "Rotate" "DW" "Rows" "K" "EarlyChange" "Length1" "Length2" "Length3" "StructParents" "Supplement"])

(defn huge-num [r]
  (pick r ["2147483647" "2147483648" "4294967296" "65536" "16777216" "1000000000" "-1" "-2147483648" "99999999999999999999"
           "0" "32767" "32768" "255" "256" "100000" "9223372036854775807" "-0" "0.0" "1e5" "000000000012"]))

(defn nest [r]
  (let [d (pick r [8 100 300 1000 5000 40000])]
    (case (int (below r 5))
      0 (str (apply str (repeat d "[")) (apply str (repeat d "]")))
      1 (str (apply str (repeat d "<< /K ")) "1" (apply str (repeat d " >>")))
      2 (str (apply str (repeat d "[<< /K ")) "1" (apply str (repeat d " >>]")))
      3 (str (apply str (repeat d "[")) "1 0 R" (apply str (repeat d "]")))
      4 (str "[" (apply str (repeat (* d 4) "1 ")) "]"))))

(defn bad-value [r nobj self]
  (case (int (below r 26))
    0 "null" 1 "true" 2 "false" 3 "0" 4 "-1" 5 "1" 6 "2147483647" 7 "2147483648" 8 "-2147483648" 9 "99999999999999999999"
    10 "1.5" 11 "0.0000001" 12 "/Name" 13 "()" 14 (str "(" (apply str (repeat 1000 "x")) ")") 15 "[]" 16 "<< >>"
    17 (str (inc (below r (+ nobj 3))) " 0 R") 18 (str self " 0 R") 19 "99999 0 R" 20 "[ 1 2 3 4 5 6 7 8 9 10 11 12 ]"
    21 "<< /Type /Font /Subtype /Type0 >>" 22 (huge-num r) 23 (nest r) 24 (str (inc (below r (+ nobj 3))) " 65535 R")
    25 "[/DeviceRGB /DeviceGray]"))

(defn- nobj [model] (inc (reduce max 0 (map :n (:objs model)))))

(defn- numbers [model] (mapv :n (:objs model)))

(defn- idx-where
  "An index of an object satisfying pred, or nil: the first match scanning round from a random start."
  [r model pred]
  (let [objs (:objs model) n (count objs) s (below r (max 1 n))]
    (some (fn [k] (let [i (mod (+ s k) n)] (when (pred (nth objs i)) i))) (range n))))

(defn- upd [model i f] (update-in model [:objs i] f))

(defn- stream? [o] (some? (:data o)))

(defn- dict-ish [o] (str/includes? (:dict o) "<<"))

(defn- pairs-of [o] (po/top-pairs (:dict o)))

;; ---------------------------------------------------------------- dictionaries

(defn mut-key [r model]
  (when-let [i (idx-where r model #(and (dict-ish %) (seq (pairs-of %))))]
    (let [o (nth (:objs model) i) d (:dict o) ps (pairs-of o) p (pick r ps) k (po/key-of d p)
          [ks ke vs ve] p]
      (case (int (below r 6))
        0 [(upd model i #(assoc % :dict (str (subs d 0 (inc ks)) (pick r key-pool) (subs d ke)))) (str "key@" (:n o) " /" k " renamed")]
        1 [(upd model i #(assoc % :dict (str (subs d 0 (inc ks)) (let [t (subs d (inc ks) ke)] (if (> (count t) 1) (subs t 0 (dec (count t))) (str t "x"))) (subs d ke))))
           (str "key@" (:n o) " /" k " misspelt")]
        2 [(upd model i #(assoc % :dict (str (subs d 0 ks) (subs d ve)))) (str "key@" (:n o) " /" k " dropped")]
        3 [(upd model i #(assoc % :dict (str (subs d 0 ve) " " (subs d ks ve) (subs d ve)))) (str "key@" (:n o) " /" k " doubled")]
        4 (let [[_ _ vs2 ve2 :as p2] (pick r ps)]
            [(upd model i #(assoc % :dict (po/put-pair d k (subs d vs2 ve2)))) (str "key@" (:n o) " /" k " takes another value")])
        5 [(upd model i #(assoc % :dict (po/put-pair d (pick r key-pool) (subs d vs ve)))) (str "key@" (:n o) " /" k " copied to another key")]))))

(defn mut-value [r model]
  (when-let [i (idx-where r model #(and (dict-ish %) (seq (pairs-of %))))]
    (let [o (nth (:objs model) i) d (:dict o) [_ _ vs ve :as p] (pick r (pairs-of o)) k (po/key-of d p)
          v (bad-value r (nobj model) (:n o))]
      [(upd model i #(assoc % :dict (str (subs d 0 vs) v (subs d ve)))) (str "value@" (:n o) " /" k " = " (subs v 0 (min 24 (count v))))])))

(defn mut-count [r model]
  (when-let [i (idx-where r model #(and (dict-ish %) (some (set count-keys) (map (partial po/key-of (:dict %)) (pairs-of %)))))]
    (let [o (nth (:objs model) i) d (:dict o)
          p (pick r (filter #(some #{(po/key-of d %)} count-keys) (pairs-of o)))
          [_ _ vs ve] p v (huge-num r)]
      [(upd model i #(assoc % :dict (str (subs d 0 vs) v (subs d ve)))) (str "count@" (:n o) " /" (po/key-of d p) " = " v)])))

(defn mut-nest [r model]
  (when-let [i (idx-where r model dict-ish)]
    (let [o (nth (:objs model) i) v (nest r) k (pick r ["Fuzz" "Resources" "Kids" "Contents" "Widths" "Differences" "ColorSpace" "Decode" "Annots" "Names" "K"])]
      [(upd model i #(assoc % :dict (po/put-pair (:dict %) k v))) (str "nest@" (:n o) " /" k " " (count v))])))

(defn mut-cycle [r model]
  (when-let [i (idx-where r model dict-ish)]
    (let [o (nth (:objs model) i) d (:dict o) ns (numbers model)
          target (case (int (below r 4)) 0 (:n o) 1 (:n o) 2 (pick r ns) 3 1)]
      (if (and (seq (po/refs d)) (zero? (below r 2)))
        (let [[s e _] (pick r (po/refs d))]
          [(upd model i #(assoc % :dict (str (subs d 0 s) target " 0 R" (subs d e)))) (str "cycle@" (:n o) " ref -> " target)])
        (let [k (pick r ["Parent" "Kids" "Contents" "Resources" "XObject" "Font" "ToUnicode" "DescendantFonts" "SMask" "Length" "FontDescriptor" "Encoding" "Next" "First" "P" "K" "UseCMap"])
              v (case (int (below r 3)) 0 (str target " 0 R") 1 (str "[" target " 0 R " (pick r ns) " 0 R]") 2 (str "<< /X " target " 0 R /Y " (:n o) " 0 R >>"))]
          [(upd model i #(assoc % :dict (po/put-pair (:dict %) k v))) (str "cycle@" (:n o) " /" k " -> " target)])))))

;; --------------------------------------------------------------------- filters

(def filter-pool ["/FlateDecode" "/LZWDecode" "/ASCII85Decode" "/ASCIIHexDecode" "/RunLengthDecode" "/CCITTFaxDecode" "/DCTDecode"
                  "/JBIG2Decode" "/JPXDecode" "/Crypt" "/Fl" "/AHx" "/A85" "/LZW" "/RL" "/CCF" "/DCT" "/Bogus" "/FlateDecode /FlateDecode"])

(defn- parms [r]
  (str "<< " (str/join " " (for [_ (range (between r 1 4))]
                             (str "/" (pick r ["Predictor" "Columns" "Colors" "BitsPerComponent" "EarlyChange" "K" "Rows" "BlackIs1" "EncodedByteAlign" "JBIG2Globals" "ColorTransform"])
                                  " " (case (int (below r 3)) 0 (huge-num r) 1 (str (below r 20)) 2 (pick r ["true" "false" "null" "/x" "[]"])))))
       " >>"))

(defn mut-filter [r model]
  (when-let [i (idx-where r model stream?)]
    (let [o (nth (:objs model) i)]
      (case (int (below r 5))
        0 [(upd model i #(assoc % :dict (po/put-pair (:dict %) "Filter" (pick r filter-pool)))) (str "filter@" (:n o) " replaced")]
        1 (let [k (pick r [2 8 50 200 1000]) f (pick r ["/FlateDecode" "/ASCIIHexDecode" "/LZWDecode" "/ASCII85Decode" "/RunLengthDecode"])]
            [(upd model i #(assoc % :dict (po/put-pair (:dict %) "Filter" (str "[" (str/join " " (repeat k f)) "]")))) (str "filter@" (:n o) " chain x" k " " f)])
        2 [(upd model i #(assoc % :dict (po/put-pair (:dict %) "DecodeParms" (parms r)))) (str "filter@" (:n o) " parms")]
        3 [(upd model i #(assoc % :dict (po/put-pair (:dict %) "DecodeParms" (str "[" (str/join " " (repeatedly (between r 1 4) (fn [] (if (zero? (below r 2)) "null" (parms r))))) "]")))) (str "filter@" (:n o) " parms array")]
        4 [(upd model i #(assoc % :dict (po/drop-pair (po/drop-pair (:dict %) "Filter") "DecodeParms"))) (str "filter@" (:n o) " dropped")]))))

;; ---------------------------------------------------------------- stream data

(defn- flate-plain? [o]
  (let [d (:dict o)]
    (and (stream? o) (re-find #"/Filter\s*(?:/FlateDecode|/Fl\b|\[\s*/FlateDecode\s*\])" d) (not (re-find #"/DecodeParms" d)))))

(defn decoded
  "[bytes mode] for a stream whose bytes we can read as they are meant: mode :raw (no filter) or :flate; else nil."
  [o]
  (cond
    (not (stream? o)) nil
    (not (re-find #"/Filter" (:dict o))) [(:data o) :raw]
    (flate-plain? o) (when-let [b (po/inflate (:data o) 4000000)] [b :flate])
    :else nil))

(defn- encode
  "o with new decoded bytes: Flate-compressed again, or stored raw with the filter keys dropped."
  [o ^bytes bs mode]
  (if (= mode :flate)
    (assoc o :data (po/deflate bs))
    (assoc o :data bs :dict (po/drop-pair (po/drop-pair (:dict o) "Filter") "DecodeParms"))))

(defn- byte-edit
  "A byte-level edit of bs."
  [r ^bytes bs]
  (let [n (alength bs)]
    (case (int (below r 6))
      0 (m/sub bs 0 (below r (inc n)))
      1 (let [out (aclone bs)] (when (pos? n) (dotimes [_ (between r 1 6)] (aset-byte out (below r n) (unchecked-byte (below r 256))))) out)
      2 (let [at (below r (inc n))] (m/splice bs at (min n (+ at (between r 1 40))) (byte-array 0)))
      3 (let [at (below r (inc n))] (m/splice bs at at (byte-array (repeatedly (between r 1 40) #(unchecked-byte (below r 256))))))
      4 (let [at (below r (inc n)) len (min (- n at) (between r 1 64))] (m/splice bs at at (apply po/cat-bytes (repeat (between r 2 200) (m/sub bs at (+ at len))))))
      5 (po/cat-bytes bs (byte-array (below r 2000))))))

(defn mut-stream [r model]
  (when-let [i (idx-where r model stream?)]
    (let [o (nth (:objs model) i)
          dec (decoded o)]
      (if (and dec (zero? (below r 2)))
        [(upd model i #(encode % (byte-edit r (first dec)) (second dec))) (str "stream@" (:n o) " decoded edit")]
        [(upd model i #(assoc % :data (byte-edit r (:data %)))) (str "stream@" (:n o) " raw edit")]))))

;; --------------------------------------------------------------- content streams

(def ops-pool
  ["Tj" "TJ" "Td" "TD" "Tm" "T*" "Tf" "q" "Q" "cm" "re" "m" "l" "c" "v" "y" "h" "S" "s" "f" "F" "f*" "B" "B*" "b" "b*" "W" "W*" "n" "BT" "ET"
   "Do" "BI" "ID" "EI" "BDC" "BMC" "EMC" "DP" "MP" "sh" "gs" "d0" "d1" "Tr" "Ts" "Tz" "Tc" "Tw" "TL" "'" "\"" "w" "J" "j" "M" "d" "i" "ri"
   "rg" "RG" "g" "G" "k" "K" "cs" "CS" "sc" "SC" "scn" "SCN" "BX" "EX"])

(defn- content-ish? [^bytes bs]
  (let [s (po/->s (m/sub bs 0 (min 4000 (alength bs))))]
    (boolean (re-find #"\b(?:BT|Tj|TJ|Do|re|cm|Tf)\b" s))))

(defn- token-edit
  "A mutation of a content stream's text."
  [r ^String s]
  (let [toks (vec (re-seq #"\S+" s))
        n (count toks)]
    (if (zero? n)
      (str s " q q q BT")
      (let [i (below r n) t (nth toks i)
            numeric? (re-matches #"-?[\d.]+" t)
            rebuild (fn [ts] (str/join " " ts))]
        (case (int (below r 12))
          0 (rebuild (assoc toks i (if numeric? (huge-num r) (str (pick r ["-1" "0" "99999999999" "1e9" ".5" "-.5" "2147483647"]))))) ;; an extreme operand
          1 (rebuild (concat (subvec toks 0 i) (subvec toks (inc i))))
          2 (let [k (between r 1 (min 30 (- n i))) times (pick r [2 10 100 2000])]
              (rebuild (concat (subvec toks 0 i) (apply concat (repeat times (subvec toks i (+ i k)))) (subvec toks (+ i k)))))
          3 (if (< (inc i) n) (rebuild (assoc toks i (nth toks (inc i)) (inc i) t)) s)
          4 (rebuild (assoc toks i (pick r ops-pool)))
          5 (rebuild (concat (subvec toks 0 i) [(pick r ["(" ")" "[" "]" "<<" ">>" "<" ">" "/" "%" "\\"])] (subvec toks i)))
          6 (rebuild (concat (subvec toks 0 i) (repeat (pick r [10 1000 20000]) (pick r ["q" "Q" "BT" "ET" "BMC" "EMC" "/Span BMC" "[" "(" "1 0 0 1 0 0 cm" "0 0 0 0 0 0 cm"])) (subvec toks i)))
          7 (rebuild (concat (subvec toks 0 i) ["BI /W 8 /H 8 /BPC 8 /CS /G ID" (apply str (repeat (below r 80) "x")) "EI"] (subvec toks i)))
          8 (rebuild (concat (subvec toks 0 i) (repeat (pick r [1 4 12]) (huge-num r)) [(pick r ["Tf" "Tm" "cm" "Td" "re" "Tz" "TL" "Tc" "w" "d" "scn"])] (subvec toks i)))
          9 (rebuild (concat (subvec toks 0 i) [(str "/" (pick r ["F1" "F2" "Fm1" "Im1" "X" "P1" "Sh1" "GS1"])) (pick r ["Do" "Tf" "sh" "gs" "scn" "BDC"])] (subvec toks i)))
          10 (rebuild (concat (subvec toks 0 i) ["["] (repeat (pick r [10 5000]) "(a) 100") ["] TJ"] (subvec toks i)))
          11 (str s (apply str (repeat (pick r [1 50]) (str " " (pick r ["q" "BT" "(" "<" "[" "/Fm1 Do" "/F1 Tf"]))))))))))

(defn mut-content [r model]
  (when-let [i (idx-where r model #(and (decoded %) (content-ish? (first (decoded %)))))]
    (let [o (nth (:objs model) i) [bs mode] (decoded o)
          s (token-edit r (po/->s bs))
          mode (if (and (= mode :raw) (zero? (below r 4))) :flate mode)
          mode (if (and (= mode :flate) (zero? (below r 5))) :raw mode)]
      [(upd model i #(encode % (po/->b s) mode)) (str "content@" (:n o) " tokens")])))

;; ----------------------------------------------------------- CMaps and fonts

(defn- cmap? [^bytes bs] (str/includes? (po/->s (m/sub bs 0 (min 6000 (alength bs)))) "begincmap"))

(defn- cmap-edit [r ^String s]
  (let [hexes (vec (re-seq #"<[0-9A-Fa-f]*>" s))
        counts (vec (re-seq #"\d+ begin(?:bfchar|bfrange|codespacerange|cidrange|cidchar|notdefrange)" s))]
    (case (int (below r 9))
      0 (if (seq hexes) (let [h (pick r hexes)] (str/replace-first s h (pick r ["<>" "<0>" "<00000000>" "<FFFFFFFF>" "<D800>" "<DC00>" "<D800DC00>" "<FFFE>" (str "<" (apply str (repeat (pick r [40 4000]) "F")) ">")]))) s)
      1 (if (seq counts) (let [c (pick r counts)] (str/replace-first s c (str (pick r ["0" "-1" "2147483647" "99999999999" "5"]) " " (second (re-find #"\d+ (.*)" c))))) s)
      2 (str/replace-first s (pick r ["endbfchar" "endbfrange" "endcodespacerange" "endcmap"]) "")
      3 (str/replace-first s (pick r ["beginbfchar" "beginbfrange" "begincodespacerange"]) (pick r ["beginbfrange" "beginbfchar" "begincidrange" "beginnotdefrange" "usecmap"]))
      4 (str/replace-first s "endcmap" (str "1 beginbfrange <0000> <FFFF> <0000> endbfrange\nendcmap"))
      5 (str/replace-first s "endcmap" (str "1 beginbfrange <00> <03> [" (apply str (repeat (pick r [2 5000]) "<0041> ")) "] endbfrange\nendcmap"))
      6 (str "/Identity-H usecmap\n" s)
      7 (str/replace-first s "endcmap" (str "1 begincodespacerange <" (apply str (repeat (pick r [1 5 9 40]) "00")) "> <" (apply str (repeat (pick r [1 5 9 40]) "FF")) "> endcodespacerange\nendcmap"))
      8 (str s "\n" (apply str (repeat (pick r [5 500]) "1 beginbfchar <0041> <0042> endbfchar\n"))))))

(defn mut-cmap [r model]
  (when-let [i (idx-where r model #(and (decoded %) (cmap? (first (decoded %)))))]
    (let [o (nth (:objs model) i) [bs mode] (decoded o)]
      [(upd model i #(encode % (po/->b (cmap-edit r (po/->s bs))) mode)) (str "cmap@" (:n o))])))

(defn- font? [o] (and (dict-ish o) (re-find #"/Type\s*/Font\b|/BaseFont|/Subtype\s*/(?:Type0|Type1|TrueType|Type3|CIDFont)" (:dict o))))

(defn mut-font [r model]
  (when-let [i (idx-where r model font?)]
    (let [o (nth (:objs model) i)
          n (pick r [1 5 300 100000])
          [k v] (case (int (below r 12))
                  0 ["Widths" (str "[" (str/join " " (repeatedly n #(pick r ["500" "0" "-1" "2147483647" "/a" "()" "1.5"]))) "]")]
                  1 ["FirstChar" (huge-num r)]
                  2 ["LastChar" (huge-num r)]
                  3 ["Encoding" (pick r ["/WinAnsiEncoding" "/MacRomanEncoding" "/MacExpertEncoding" "/Identity-H" "/Identity-V" "/Bogus" "<< /Type /Encoding /BaseEncoding /Bogus /Differences [0 /a /b 255 /c 256 /d -1 /e] >>" "<< /Differences [2147483647 /a] >>" "6 0 R" "1 0 R"])]
                  4 ["Subtype" (pick r ["/Type0" "/Type1" "/MMType1" "/TrueType" "/Type3" "/CIDFontType0" "/CIDFontType2" "/Bogus" "/Image" "/Form"])]
                  5 ["DescendantFonts" (pick r [(str (:n o) " 0 R") (str "[" (:n o) " 0 R]") "[]" "[null]" "[1 0 R 1 0 R 1 0 R]" "<< >>"])]
                  6 ["FontDescriptor" (pick r [(str (:n o) " 0 R") "<< /Ascent 2147483647 /Descent -2147483647 /FontBBox [2147483647 -2147483647 2147483647 2147483647] /Flags -1 /CapHeight -5 /StemV 0 /MissingWidth 2147483647 >>" "null" "[]"])]
                  7 ["FontMatrix" (pick r ["[0 0 0 0 0 0]" "[1e9 0 0 1e9 0 0]" "[0.001]" "[-1 0 0 -1 0 0]" "[0.001 0 0 0.001 2147483647 2147483647]" "null"])]
                  8 ["ToUnicode" (pick r [(str (:n o) " 0 R") "null" "/Identity-H" "<< >>" "1 0 R"])]
                  9 ["CharProcs" (str "<< /a " (:n o) " 0 R /b 1 0 R >>")]
                  10 ["W" (pick r ["[0 [1000 2000] 5 10 500 20 [] 100 90 -4]" "[0 2147483647 500]" "[2147483647 [1 2 3]]" "[1 [" "[[[[[[]]]]]]" (str "[0 [" (apply str (repeat 50000 "500 ")) "]]")])]
                  11 ["CIDToGIDMap" (pick r ["/Identity" "99999 0 R" "null" (str (:n o) " 0 R")])])]
      [(upd model i #(assoc % :dict (po/put-pair (:dict %) k v))) (str "font@" (:n o) " /" k)])))

(defn mut-image [r model]
  (when-let [i (idx-where r model #(and (dict-ish %) (re-find #"/Subtype\s*/Image" (:dict %))))]
    (let [o (nth (:objs model) i)
          [k v] (case (int (below r 11))
                  0 ["Width" (huge-num r)]
                  1 ["Height" (huge-num r)]
                  2 ["BitsPerComponent" (pick r ["0" "1" "2" "4" "8" "16" "17" "32" "-1" "255" "2147483647"])]
                  3 ["ColorSpace" (pick r ["/DeviceCMYK" "/DeviceRGB" "/DeviceGray" "/Pattern" "[/Indexed /DeviceRGB 2147483647 <00>]" "[/Indexed /DeviceRGB -1 ()]" "[/ICCBased 1 0 R]" "[/ICCBased null]" "[/CalRGB << >>]" "[/Lab << /WhitePoint [0 0 0] >>]" "[/Separation /A /DeviceRGB 1 0 R]" "[/DeviceN [/A /B] /DeviceRGB 1 0 R]" "/Bogus" "[]" (nest r)])]
                  4 ["Decode" (pick r ["[0 1]" "[1 0 1 0 1 0]" "[]" "[0]" "[1e9 -1e9]" "[0 1 0 1 0 1 0 1 0 1]" "null" "[2147483647 -2147483647]"])]
                  5 ["ImageMask" (pick r ["true" "false" "null" "1" "/true"])]
                  6 ["SMask" (pick r [(str (:n o) " 0 R") "1 0 R" "null" "<< >>" (str (inc (below r (nobj model))) " 0 R")])]
                  7 ["Mask" (pick r [(str (:n o) " 0 R") "[0 0]" "[255 0 0 255]" "[]" "[1 2 3 4 5 6 7 8 9 10 11 12]" "null" (str (inc (below r (nobj model))) " 0 R")])]
                  8 ["Intent" "/Bogus"]
                  9 ["Filter" (pick r filter-pool)]
                  10 ["Interpolate" "null"])]
      [(upd model i #(assoc % :dict (po/put-pair (:dict %) k v))) (str "image@" (:n o) " /" k)])))

;; ---------------------------------------------------------------- whole objects

(defn mut-object [r model]
  (let [objs (:objs model) n (count objs) nx (nobj model)]
    (case (int (below r 7))
      0 (let [i (below r n) o (nth objs i)]
          [(update model :objs conj (assoc o :n nx)) (str "object " (:n o) " copied to " nx)])
      1 (when (> n 2) (let [i (below r n)] [(update model :objs #(into (subvec % 0 i) (subvec % (inc i)))) (str "object " (:n (nth objs i)) " dropped")]))
      2 (when (> n 2) (let [i (below r n) j (below r n) a (nth objs i) b (nth objs j)]
                        [(-> model (assoc-in [:objs i :n] (:n b)) (assoc-in [:objs j :n] (:n a))) (str "objects " (:n a) " and " (:n b) " swapped")]))
      3 (when-let [i (idx-where r model #(re-find #"/Kids\s*\[" (:dict %)))]
          (let [o (nth objs i) d (:dict o) [_ _ vs ve] (po/find-pair d "Kids")
                refs (when vs (po/refs (subs d vs ve)))]
            (when (seq refs)
              (let [k (pick r [100 5000 30000]) ref (str (nth (pick r refs) 2) " 0 R")]
                [(upd model i #(assoc % :dict (po/put-pair d "Kids" (str "[" (subs d (inc vs) (dec ve)) " " (str/join " " (repeat k ref)) "]")))) (str "kids@" (:n o) " x" k)]))))
      4 (let [i (below r n)] [(update model :objs #(into (subvec % 0 i) (reverse (subvec % i)))) "objects reversed"])
      5 (let [i (below r n) o (nth objs i)]
          [(upd model i #(assoc % :dict (po/put-pair (:dict %) "Fuzz" (str (:n o) " 0 R")))) (str "object " (:n o) " refers to itself")])
      6 [(update model :objs into (for [k (range (pick r [50 500 5000]))] {:n (+ nx k) :g 0 :dict (str "<< /Fuzz " (+ nx (mod (inc k) 50)) " 0 R >>") :data nil}))
         "junk objects added"])))

(defn mut-trailer [r model]
  (case (int (below r 3))
    0 [(assoc model :root (str (pick r (numbers model)) " 0 R")) "root retargeted"]
    1 [(assoc model :extra-trailer (str " /Size " (huge-num r))) "trailer /Size duplicated"]
    2 [(assoc model :extra-trailer (str " /Prev " (huge-num r) " /Encrypt " (pick r ["null" "<< >>" "1 0 R" "<< /Filter /Standard /V 4 /R 4 /O () /U () /P -1 >>"]))) "trailer /Prev /Encrypt"]))

(def model-mutators
  [mut-key mut-key mut-value mut-value mut-count mut-count mut-nest mut-cycle mut-cycle mut-filter mut-filter mut-stream mut-stream
   mut-content mut-content mut-content mut-cmap mut-cmap mut-font mut-font mut-image mut-image mut-object mut-object mut-trailer])

(defn apply-one
  "Apply one model mutator picked at random; falls through the list when a mutator finds nothing to change."
  [r model]
  (loop [tries 0]
    (when (< tries 6)
      (or (let [f (pick r model-mutators)]
            ;; a mutator that cannot cope with this model's shape is a miss, not a failure of the run
            (try (f r model) (catch Exception _ nil)))
          (recur (inc tries))))))
