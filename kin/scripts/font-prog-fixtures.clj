;; The fixtures of P3.4 (D140, D141): fonts whose codes are decided by their EMBEDDED PROGRAM, Type 3 fonts,
;; MacExpert, composite fonts with predefined CJK CMaps, vertical metrics.
;;
;;     bb kin/scripts/font-prog-fixtures.clj            write test/fonts/progs.pdf, progs.txt and prog-*.bin
;;     bb kin/scripts/font-prog-fixtures.clj --check    fail when the committed files are not what this makes
;;
;; Everything is built by hand so that every expectation is KNOWN BY CONSTRUCTION: a Type 1 header that says
;; `dup 65 /Omega put`, a CFF whose charset and encoding tables are written here, a TrueType font whose cmap and
;; post tables are written here. The CJK rows take their expected CIDs from the Adobe CMap FILES (a regular
;; expression over the text, not the generator's parser), and their Unicode from cid2code.txt. The rows are
;; read by `kin/pdffonttest.kin` in the format of `test/fonts/fonts.txt` (font / code / len / cid / dw2 rows,
;; and `vm` rows for vertical metrics, `fn` rows for the font's warning bits), against `fonts/progs.pdf`.
;; The raw programs are written too (`prog-t1.bin`, `prog-cff.bin`, `prog-tt.bin`, `prog-otf.bin`): the
;; truncation law cuts each at every byte.
(ns font-prog-fixtures
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream)))

;; --------------------------------------------------------------- byte helpers

(defn bytes-of [& xs]
  (let [o (ByteArrayOutputStream.)]
    (doseq [x xs]
      (cond
        (nil? x) nil
        (bytes? x) (.write o ^bytes x)
        (string? x) (.write o (.getBytes ^String x "ISO-8859-1"))
        (integer? x) (.write o (int x))
        (sequential? x) (.write o ^bytes (apply bytes-of x))
        :else (throw (ex-info "bytes-of" {:x x}))))
    (.toByteArray o)))

(defn u8 [x] (byte-array [(unchecked-byte x)]))
(defn u16 [x] (byte-array [(unchecked-byte (bit-shift-right x 8)) (unchecked-byte x)]))
(defn u32 [x] (byte-array [(unchecked-byte (bit-shift-right x 24)) (unchecked-byte (bit-shift-right x 16))
                           (unchecked-byte (bit-shift-right x 8)) (unchecked-byte x)]))
(defn len [^bytes b] (alength b))

;; ------------------------------------------------------------------- Type 1

(defn type1 [encoding-text]
  (bytes-of "%!PS-AdobeFont-1.0: TestFont 001.000\n"
            "11 dict begin\n/FontName /TestFont def\n/PaintType 0 def\n/FontType 1 def\n"
            "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n"
            encoding-text
            "/FontBBox {0 -200 1000 800} readonly def\ncurrentdict end\ncurrentfile eexec\n"
            ;; the encrypted part: never read
            (byte-array (map #(unchecked-byte (+ 17 (* 31 %))) (range 96)))
            "\n" (apply str (repeat 8 "0000000000000000000000000000000000000000000000000000000000000000\n"))
            "cleartomark\n"))

(def type1-encoding
  (str "/Encoding 256 array\n0 1 255 {1 index exch /.notdef put} for\n"
       "dup 65 /Omega put\ndup 66 /uni0416 put\ndup 67 /fi put\ndup 8#104 /G12 put\ndup 69 /a1 put\n"
       "dup 70 /f_f_i put\ndup 71 /A.swash put\ndup 72 /u1F600 put\nreadonly def\n"))

;; ----------------------------------------------------------------------- CFF

(defn cff-index [objs]
  (if (empty? objs)
    (u16 0)
    (let [total (reduce + (map len objs))
          osz (cond (< (inc total) 256) 1 (< (inc total) 65536) 2 :else 3)
          offs (reductions + 1 (map len objs))
          w (fn [v] (case osz 1 (u8 v) 2 (u16 v) 3 (bytes-of (u8 (bit-shift-right v 16)) (u16 (bit-and v 0xFFFF)))))]
      (bytes-of (u16 (count objs)) (u8 osz) (map w offs) objs))))

(defn dict-int [v] (bytes-of (u8 29) (u32 (bit-and v 0xFFFFFFFF))))   ;; a 5-byte integer operand: sizes do not move

(defn cff
  "A name-keyed CFF font. :charset is a vector of SIDs for glyphs 1.., :charset-fmt 0, 1 or a predefined id;
  :encoding nil (0 Standard), :expert (1), {:fmt 0 :codes [...]} or {:fmt 1 :ranges [[first nleft]...] :sups [[code sid]...]}."
  [{:keys [strings charset charset-fmt encoding nglyphs]}]
  (let [hdr (bytes-of 1 0 4 1)
        name-idx (cff-index [(bytes-of "TestCFF")])
        string-idx (cff-index (map #(bytes-of %) strings))
        gsubr (cff-index [])
        charstrings (cff-index (repeat nglyphs (u8 14)))
        charset-bytes (cond
                        (integer? charset-fmt) nil
                        (= charset-fmt :f0) (bytes-of (u8 0) (map u16 charset))
                        (= charset-fmt :f1) (bytes-of (u8 1) (map (fn [[first nleft]] (bytes-of (u16 first) (u8 nleft))) charset))
                        (= charset-fmt :f2) (bytes-of (u8 2) (map (fn [[first nleft]] (bytes-of (u16 first) (u16 nleft))) charset)))
        enc-bytes (cond
                    (nil? encoding) nil
                    (= encoding :expert) nil
                    (= 0 (:fmt encoding)) (bytes-of (u8 (if (:sups encoding) 0x80 0)) (u8 (count (:codes encoding))) (map u8 (:codes encoding))
                                                    (when (:sups encoding)
                                                      (bytes-of (u8 (count (:sups encoding)))
                                                                (map (fn [[c sid]] (bytes-of (u8 c) (u16 sid))) (:sups encoding)))))
                    (= 1 (:fmt encoding)) (bytes-of (u8 (if (:sups encoding) 0x81 1)) (u8 (count (:ranges encoding)))
                                                    (map (fn [[f n]] (bytes-of (u8 f) (u8 n))) (:ranges encoding))
                                                    (when (:sups encoding)
                                                      (bytes-of (u8 (count (:sups encoding)))
                                                                (map (fn [[c sid]] (bytes-of (u8 c) (u16 sid))) (:sups encoding))))))
        ;; the Top DICT: charset (15), Encoding (16), CharStrings (17); operands are 5 bytes each, so its size
        ;; is known before the offsets are
        top-size (+ (* 3 6))
        top-idx-size (len (cff-index [(byte-array top-size)]))
        base (+ (len hdr) (len name-idx) top-idx-size (len string-idx) (len gsubr))
        charset-off (if (integer? charset-fmt) charset-fmt base)
        after-charset (+ base (if charset-bytes (len charset-bytes) 0))
        enc-off (cond (nil? encoding) 0 (= encoding :expert) 1 :else after-charset)
        after-enc (+ after-charset (if (and enc-bytes (map? encoding)) (len enc-bytes) 0))
        top (bytes-of (dict-int charset-off) 15 (dict-int enc-off) 16 (dict-int after-enc) 17)
        top-idx (cff-index [top])]
    (assert (= (len top-idx) top-idx-size))
    (bytes-of hdr name-idx top-idx string-idx gsubr (or charset-bytes (byte-array 0))
              (if (map? encoding) enc-bytes (byte-array 0)) charstrings)))

;; --------------------------------------------------------------------- sfnt

(defn cmap-fmt4 [segs]
  ;; segs: [[start end delta]], rangeOffset 0; the last segment 0xFFFF is added
  (let [segs (concat segs [[0xFFFF 0xFFFF 1]])
        n (count segs)]
    (bytes-of (u16 4) (u16 (+ 16 (* 8 n))) (u16 0) (u16 (* 2 n)) (u16 0) (u16 0) (u16 0)
              (map (fn [[_ e _]] (u16 e)) segs) (u16 0)
              (map (fn [[s _ _]] (u16 s)) segs)
              (map (fn [[_ _ d]] (u16 (bit-and d 0xFFFF))) segs)
              (map (fn [_] (u16 0)) segs))))

(defn cmap-fmt6 [first glyphs]
  (bytes-of (u16 6) (u16 (+ 10 (* 2 (count glyphs)))) (u16 0) (u16 first) (u16 (count glyphs)) (map u16 glyphs)))

(defn cmap-fmt0 [m]
  (bytes-of (u16 0) (u16 262) (u16 0) (map (fn [c] (u8 (get m c 0))) (range 256))))

(defn cmap-fmt12 [groups]
  (bytes-of (u16 12) (u16 0) (u32 (+ 16 (* 12 (count groups)))) (u32 0) (u32 (count groups))
            (map (fn [[s e g]] (bytes-of (u32 s) (u32 e) (u32 g))) groups)))

(defn cmap-table [subs]
  ;; subs: [[platform encoding bytes]]
  (let [hdr (+ 4 (* 8 (count subs)))
        offs (reductions + hdr (map #(len (nth % 2)) subs))]
    (bytes-of (u16 0) (u16 (count subs))
              (map (fn [[p e _] o] (bytes-of (u16 p) (u16 e) (u32 o))) subs offs)
              (map #(nth % 2) subs))))

(defn post-fmt2 [name-indices names]
  (bytes-of (u32 0x00020000) (byte-array 28) (u16 (count name-indices)) (map u16 name-indices)
            (map (fn [n] (bytes-of (u8 (count n)) n)) names)))

(defn post-fmt3 [] (bytes-of (u32 0x00030000) (byte-array 28)))

(defn sfnt [version tables]
  ;; tables: [[tag bytes]]; four-byte aligned
  (let [n (count tables)
        pad (fn [b] (bytes-of b (byte-array (mod (- 4 (mod (len b) 4)) 4))))
        dir (+ 12 (* 16 n))
        padded (map (fn [[t b]] [t (pad b) (len b)]) tables)
        offs (reductions + dir (map #(len (second %)) padded))]
    (bytes-of version (u16 n) (u16 0) (u16 0) (u16 0)
              (map (fn [[t b l] o] (bytes-of t (u32 0) (u32 o) (u32 l))) padded offs)
              (map second padded))))

;; ----------------------------------------------------------------- PDF writer

(defn pdf-file [objs]
  ;; objs: {num body-bytes}, a body being everything between `N 0 obj` and `endobj`
  (let [o (ByteArrayOutputStream.)
        offs (atom {})
        w (fn [x] (.write o ^bytes (bytes-of x)))]
    (w "%PDF-1.7\n%âãÏÓ\n")
    (doseq [[n body] (sort objs)]
      (swap! offs assoc n (.size o))
      (w (str n " 0 obj\n")) (w body) (w "\nendobj\n"))
    (let [xref (.size o)
          mx (apply max (keys objs))]
      (w (str "xref\n0 " (inc mx) "\n"))
      (w "0000000000 65535 f \n")
      (doseq [n (range 1 (inc mx))]
        (w (format "%010d 00000 n \n" (get @offs n 0))))
      (w (str "trailer\n<< /Size " (inc mx) " /Root 1 0 R >>\nstartxref\n" xref "\n%%EOF\n")))
    (.toByteArray o)))

(defn stream-body [dict ^bytes data]
  (bytes-of "<< " dict " /Length " (len data) " >>\nstream\n" data "\nendstream"))

;; ------------------------------------------------------ CJK expectations, from the files

(def manifest (edn/read-string (slurp "test/fonts/cjk-sources.edn")))

(defn src-text [p]
  (let [f (io/file ".cache/cjk" (str/replace p "/" "__"))]
    (when-not (.exists f) (println "FAIL missing source" p "(run bb kin/scripts/cjkfetch.clj)") (System/exit 1))
    (String. (java.nio.file.Files/readAllBytes (.toPath f)) "ISO-8859-1")))

(defn file-cid
  "The CID a code has in a CMap FILE (one without usecmap), by a regular expression over its text."
  [path code hexlen]
  (let [txt (src-text path)
        h (fn [s] (Long/parseLong s 16))
        rng (re-seq (re-pattern (str "<([0-9a-fA-F]{" hexlen "})>\\s*<([0-9a-fA-F]{" hexlen "})>\\s*(\\d+)")) txt)
        chr (re-seq (re-pattern (str "<([0-9a-fA-F]{" hexlen "})>\\s*(\\d+)\\s*\n")) txt)]
    (or (some (fn [[_ a b c]] (when (<= (h a) code (h b)) (+ (Long/parseLong c) (- code (h a))))) (reverse rng))
        (some (fn [[_ a c]] (when (= (h a) code) (Long/parseLong c))) chr)
        (throw (ex-info "no cid" {:path path :code code})))))

(defn file-uni
  "The code point cid2code.txt gives a CID in a UTF-32 column."
  [dir col cid]
  (let [lines (remove #(str/starts-with? % "#") (str/split-lines (src-text (str dir "/cid2code.txt"))))
        hdr (str/split (first lines) #"\t")
        ci (.indexOf ^java.util.List (vec hdr) col)
        row (some #(let [f (str/split % #"\t")] (when (= (first f) (str cid)) f)) (rest lines))]
    ;; a CID several code points draw (U+2F00 KANGXI RADICAL ONE and U+4E00): the ideograph, not the radical
    (let [cands (map #(Long/parseLong (str/replace % #"v$" "") 16) (str/split (nth row ci) #","))]
      (or (first (remove #(<= 0x2E80 % 0x2FDF) cands)) (first cands)))))

;; -------------------------------------------------------------------- fixtures

(defn js-names [file nm]
  (let [txt (String. (java.nio.file.Files/readAllBytes (.toPath (io/file ".cache/cjk" file))) "UTF-8")
        m (re-find (re-pattern (str "(?s)(?:const|var) " nm " = \\[(.*?)\\];")) txt)]
    (mapv second (re-seq #"\"([^\"]*)\"" (second m)))))

(def mac-order (js-names "pdfjs__src__core__fonts.js" "MacStandardGlyphOrdering"))
(def mac-expert (js-names "pdfjs__src__core__encodings.js" "MacExpertEncoding"))
(defn mac-idx [nm] (.indexOf ^java.util.List mac-order nm))
(defn expert-code [nm] (.indexOf ^java.util.List mac-expert nm))

(def rows (atom []))
(defn row [& xs] (swap! rows conj (str/join "\t" xs)))
(defn hex [v] (format "%X" v))
(defn cps [& xs] (str/join "+" (map #(format "%04X" %) xs)))

(def objs (atom {}))
(def programs-t1 (atom nil)) (def programs-cff (atom nil)) (def programs-tt (atom nil)) (def programs-otf (atom nil))
(defn put! [n body] (swap! objs assoc n body))

(defn simple-font
  "A simple font dictionary at object `n`: subtype, flags, the program stream object, encoding text."
  [n {:keys [subtype base flags file-key file-obj encoding widths first-char tounicode]
      :or {first-char 0 widths (vec (repeat 256 500))}}]
  (let [desc (+ n 100)]
    (put! n (str "<< /Type /Font /Subtype /" subtype " /BaseFont /" base " /FirstChar " first-char
                 " /LastChar " (+ first-char (dec (count widths))) " /Widths [" (str/join " " widths) "]"
                 (when encoding (str " /Encoding " encoding))
                 (when tounicode (str " /ToUnicode " tounicode))
                 " /FontDescriptor " desc " 0 R >>"))
    (put! desc (str "<< /Type /FontDescriptor /FontName /" base " /Flags " flags
                    " /FontBBox [0 -200 1000 800] /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80"
                    (when file-key (str " /" file-key " " file-obj " 0 R")) " >>"))))

(defn font-row [n kind warn & {:keys [asc desc vertical s14] :or {asc 800 desc -200 vertical 0 s14 -1}}]
  (row "font" "fonts/progs.pdf" n 0 kind asc desc warn vertical s14))

(defn build []
  (reset! objs {}) (reset! rows [])
  (put! 1 "<< /Type /Catalog /Pages 2 0 R >>")
  (put! 2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
  (put! 3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")
  (let [t1 (type1 type1-encoding)
        t1-std (type1 "/Encoding StandardEncoding def\n")
        cff-a (cff {:strings ["uni0416" "G7" "Omega.alt"] :nglyphs 5 :charset-fmt :f0 :charset [34 391 392 393]
                    :encoding {:fmt 0 :codes [0x41 0x42 0x43 0x44] :sups [[0x45 68] [0x46 392]]}})
        cff-b (cff {:strings [] :nglyphs 6 :charset-fmt :f1 :charset [[34 4]] :encoding {:fmt 1 :ranges [[0x50 2] [0x60 1]]}})
        cff-c (cff {:strings [] :nglyphs 4 :charset-fmt 0 :encoding nil})
        tt-sym (sfnt (u32 0x00010000)
                     [["cmap" (cmap-table [[3 0 (cmap-fmt4 [[0xF041 0xF043 (- 1 0xF041)] [0xF045 0xF045 (- 4 0xF045)]])]
                                           [3 1 (cmap-fmt4 [[0x0416 0x0416 (- 1 0x0416)] [0x042F 0x042F (- 2 0x042F)]
                                                            [0x3042 0x3042 (- 4 0x3042)]])]])]
                      ["post" (post-fmt2 [0 1 2 258 4] ["Omega"])]])
        tt-mac (sfnt (u32 0x00010000)
                     [["cmap" (cmap-table [[1 0 (cmap-fmt6 0x61 [1 2 3])]
                                           [3 10 (cmap-fmt12 [[0x1F600 0x1F600 1] [0x0416 0x0416 2] [0x00E9 0x00E9 3]])]])]])
        tt-post (sfnt (u32 0x00010000)
                      [["cmap" (cmap-table [[3 0 (cmap-fmt4 [[0xF030 0xF032 (- 1 0xF030)]])]])]
                       ["post" (post-fmt2 [0 (mac-idx "one") (mac-idx "dollar") 258] ["uni20AC"])]])
        tt-bare (sfnt (u32 0x00010000)
                      [["cmap" (cmap-table [[3 0 (cmap-fmt4 [[0xF041 0xF042 (- 1 0xF041)]])]])]
                       ["post" (post-fmt3)]])
        tt-win (sfnt (u32 0x00010000)
                     [["cmap" (cmap-table [[3 1 (cmap-fmt4 [[0x20 0x7E 0]])]])]])
        otf (sfnt "OTTO" [["CFF " cff-a]])]
    ;; the programs as files
    (reset! programs-t1 t1) (reset! programs-cff cff-a) (reset! programs-tt tt-sym) (reset! programs-otf otf)

    ;; 10..19: programs
    (put! 10 (stream-body "/Length1 600" t1))
    (put! 11 (stream-body "/Length1 50" t1-std))
    (put! 12 (stream-body "/Subtype /Type1C" cff-a))
    (put! 13 (stream-body "/Subtype /Type1C" cff-b))
    (put! 14 (stream-body "/Subtype /Type1C" cff-c))
    (put! 15 (stream-body "/Length1 400" tt-sym))
    (put! 16 (stream-body "/Length1 400" tt-mac))
    (put! 17 (stream-body "/Length1 400" tt-post))
    (put! 18 (stream-body "/Length1 400" tt-bare))
    (put! 19 (stream-body "/Subtype /OpenType" otf))
    (put! 9 (stream-body "/Length1 400" tt-win))

    ;; ---- 21 Type 1, symbolic, its own /Encoding
    (simple-font 21 {:subtype "Type1" :base "TestOne" :flags 4 :file-key "FontFile" :file-obj 10})
    (font-row 21 "simple" 0)
    (row "code" "41" (cps 0x2126) "encoding" 50000)   ;; Omega: the AGL says U+2126 (OHM SIGN)
    (row "code" "42" (cps 0x0416) "encoding" 50000)
    (row "code" "43" (cps 0xFB01) "encoding" 50000)
    (row "code" "44" "-" "unknown" 50000)               ;; G12: a name, no Unicode: and the guess is withdrawn
    (row "code" "45" "-" "unknown" 50000)               ;; a1: ZapfDingbats' name, nothing in any other font
    (row "code" "46" (cps 0x66 0x66 0x69) "encoding" 50000)
    (row "code" "47" (cps 0x41) "encoding" 50000)       ;; A.swash: the suffix goes
    (row "code" "48" (cps 0x1F600) "encoding" 50000)    ;; u1F600
    (row "code" "30" "-" "unknown" 50000)               ;; .notdef
    ;; ---- 22 Type 1, non-symbolic, StandardEncoding
    (simple-font 22 {:subtype "Type1" :base "TestTwo" :flags 32 :file-key "FontFile" :file-obj 11})
    (font-row 22 "simple" 0)
    (row "code" "27" (cps 0x2019) "encoding" 50000)
    (row "code" "41" (cps 0x41) "encoding" 50000)
    ;; ---- 23 Type 1, /Encoding dictionary with /Differences and no /BaseEncoding: the program is the base
    (simple-font 23 {:subtype "Type1" :base "TestThree" :flags 4 :file-key "FontFile" :file-obj 10
                     :encoding "<< /Type /Encoding /Differences [65 /Euro] >>"})
    (font-row 23 "simple" 0)
    (row "code" "41" (cps 0x20AC) "glyphname" 50000)
    (row "code" "42" (cps 0x0416) "encoding" 50000)
    ;; ---- 24 Type 1 with a named base encoding: the program is not asked
    (simple-font 24 {:subtype "Type1" :base "TestFour" :flags 32 :file-key "FontFile" :file-obj 10
                     :encoding "/WinAnsiEncoding"})
    (font-row 24 "simple" 0)
    (row "code" "41" (cps 0x41) "encoding" 50000)
    (row "code" "80" (cps 0x20AC) "encoding" 50000)
    ;; ---- 25 CFF, custom encoding format 0 with supplements, charset format 0, a String INDEX
    (simple-font 25 {:subtype "Type1" :base "TestCffA" :flags 4 :file-key "FontFile3" :file-obj 12})
    (font-row 25 "simple" 0)
    (row "code" "41" (cps 0x41) "encoding" 50000)       ;; gid 1, SID 34: `A`
    (row "code" "42" (cps 0x0416) "encoding" 50000)     ;; gid 2, SID 391: `uni0416`
    (row "code" "43" "-" "unknown" 50000)               ;; gid 3, SID 392: `G7`
    (row "code" "44" (cps 0x2126) "encoding" 50000)     ;; gid 4, SID 393: `Omega.alt`, the suffix goes
    (row "code" "45" (cps 0x63) "encoding" 50000)       ;; supplement: SID 68 `c`
    (row "code" "46" "-" "unknown" 50000)               ;; supplement: SID 392 `G7`
    (row "code" "47" "-" "unknown" 50000)
    ;; ---- 26 CFF, encoding format 1 (ranges), charset format 1
    (simple-font 26 {:subtype "Type1" :base "TestCffB" :flags 4 :file-key "FontFile3" :file-obj 13})
    (font-row 26 "simple" 0)
    (row "code" "50" (cps 0x41) "encoding" 50000)
    (row "code" "51" (cps 0x42) "encoding" 50000)
    (row "code" "52" (cps 0x43) "encoding" 50000)
    (row "code" "60" (cps 0x44) "encoding" 50000)
    (row "code" "61" (cps 0x45) "encoding" 50000)
    (row "code" "62" "-" "unknown" 50000)
    ;; ---- 27 CFF, predefined Standard encoding and ISOAdobe charset
    (simple-font 27 {:subtype "Type1" :base "TestCffC" :flags 32 :file-key "FontFile3" :file-obj 14})
    (font-row 27 "simple" 0)
    (row "code" "27" (cps 0x2019) "encoding" 50000)
    (row "code" "41" (cps 0x41) "encoding" 50000)
    ;; ---- 28 OpenType (CFF table) in FontFile3
    (simple-font 28 {:subtype "Type1" :base "TestOtf" :flags 4 :file-key "FontFile3" :file-obj 19})
    (font-row 28 "simple" 0)
    (row "code" "42" (cps 0x0416) "encoding" 50000)
    ;; ---- 29 TrueType, symbolic: the (3,0) cmap at 0xF000 + code, the glyph named by the Unicode cmap read backwards
    (simple-font 29 {:subtype "TrueType" :base "TestTt" :flags 4 :file-key "FontFile2" :file-obj 15})
    (font-row 29 "simple" 256)
    (row "code" "41" (cps 0x0416) "encoding" 50000)     ;; F041 -> gid 1 <- U+0416
    (row "code" "42" (cps 0x042F) "encoding" 50000)
    (row "code" "43" (cps 0x2126) "glyphname" 50000)    ;; gid 3: no Unicode, post name `Omega`
    (row "code" "45" (cps 0x3042) "encoding" 50000)     ;; F045 -> gid 4 <- U+3042
    (row "code" "44" (cps 0x44) "unknown" 50000)        ;; no glyph: the guess (WinAnsi) stays, as a guess
    ;; ---- 30 TrueType, symbolic, the (1,0) cmap (format 6) and a Unicode cmap of format 12
    (simple-font 30 {:subtype "TrueType" :base "TestTtMac" :flags 4 :file-key "FontFile2" :file-obj 16})
    (font-row 30 "simple" 256)
    (row "code" "61" (cps 0x1F600) "encoding" 50000)
    (row "code" "62" (cps 0x0416) "encoding" 50000)
    (row "code" "63" (cps 0xE9) "encoding" 50000)
    ;; ---- 31 TrueType, symbolic, glyph names only (post format 2 with a standard and a custom name)
    (simple-font 31 {:subtype "TrueType" :base "TestTtPost" :flags 4 :file-key "FontFile2" :file-obj 17})
    (font-row 31 "simple" 256)
    (row "code" "30" (cps 0x31) "glyphname" 50000)      ;; F030 -> gid 1 -> the Macintosh name `one`
    (row "code" "31" (cps 0x24) "glyphname" 50000)      ;; gid 2 -> `dollar`
    (row "code" "32" (cps 0x20AC) "glyphname" 50000)    ;; gid 3 -> index 258 (the first custom name): `uni20AC`
    ;; ---- 32 TrueType, symbolic, no names at all: nothing is known, a guess stays
    (simple-font 32 {:subtype "TrueType" :base "TestTtBare" :flags 4 :file-key "FontFile2" :file-obj 18})
    (font-row 32 "simple" (+ 256 1024))
    (row "code" "41" (cps 0x41) "unknown" 50000)
    ;; ---- 33 TrueType, NON-symbolic, no /Encoding: WinAnsi, the program is not asked
    (simple-font 33 {:subtype "TrueType" :base "TestTtWin" :flags 32 :file-key "FontFile2" :file-obj 9})
    (font-row 33 "simple" 0)
    (row "code" "80" (cps 0x20AC) "encoding" 50000)
    ;; ---- 34 a program that is no font at all: the guess stays, and the warning says so
    (put! 8 (stream-body "" (bytes-of "this is not a font program")))
    (simple-font 34 {:subtype "Type1" :base "TestBroken" :flags 4 :file-key "FontFile" :file-obj 8})
    (font-row 34 "simple" (+ 256 1024 2048))
    (row "code" "41" (cps 0x41) "unknown" 50000)

    ;; ---- 40 MacExpertEncoding, no program
    (simple-font 40 {:subtype "Type1" :base "TestExpert" :flags 4 :encoding "/MacExpertEncoding"})
    (font-row 40 "simple" 0)
    (row "code" (hex (expert-code "Asmall")) (cps 0x61) "encoding" 50000)       ;; Asmall: the small capital is a letter
    (row "code" (hex (expert-code "zerooldstyle")) (cps 0x30) "encoding" 50000)
    (row "code" (hex (expert-code "ff")) (cps 0xFB00) "encoding" 50000)
    (row "code" (hex (expert-code "fi")) (cps 0xFB01) "encoding" 50000)
    (row "code" (hex (expert-code "oneoldstyle")) (cps 0x31) "encoding" 50000)
    (row "code" (hex (expert-code "Acutesmall")) (cps 0xB4) "encoding" 50000)
    (row "code" "41" "-" "unknown" 50000)               ;; no glyph
    ;; ---- 41 Type 3: names of drawings are not text unless they are text names
    (put! 41 "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 100 100] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /A 42 0 R >> /Encoding << /Type /Encoding /Differences [65 /A /uni0042 /a3 /g4 /Gamma] >> /FirstChar 65 /LastChar 69 /Widths [500 500 500 500 500] >>")
    (put! 42 (stream-body "" (bytes-of "500 0 d0")))
    (row "font" "fonts/progs.pdf" 41 0 "simple" 100 0 0 0 -1)
    (row "code" "41" (cps 0x41) "glyphname" 50000)
    (row "code" "42" (cps 0x42) "glyphname" 50000)
    (row "code" "43" "-" "unknown" 50000)               ;; a3: ZapfDingbats' name, a drawing here
    (row "code" "44" "-" "unknown" 50000)               ;; g4
    (row "code" "45" (cps 0x0393) "glyphname" 50000)
    ;; ---- 43 a Type 3 font that names nothing: no mapping at all
    (put! 43 "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 100 100] /FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /a1 42 0 R >> /Encoding << /Type /Encoding /Differences [1 /a1 /a2 /a3] >> /FirstChar 1 /LastChar 3 /Widths [500 500 500] >>")
    (row "font" "fonts/progs.pdf" 43 0 "simple" 100 0 1024 0 -1)
    (row "code" "1" "-" "unknown" 50000)

    ;; ---- CJK: composite fonts with predefined CMaps, from the Adobe files
    (let [cid-font (fn [n enc ordering & {:keys [w tounicode w2 dw2 base]}]
                     (let [d (+ n 100) fd (+ n 200)]
                       (put! n (str "<< /Type /Font /Subtype /Type0 /BaseFont /" (or base "TestCJK") " /Encoding /" enc
                                    " /DescendantFonts [" d " 0 R]" (when tounicode (str " /ToUnicode " tounicode)) " >>"))
                       (put! d (str "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /TestCJK /CIDSystemInfo << /Registry (Adobe) /Ordering (" ordering ") /Supplement 2 >> /FontDescriptor " fd " 0 R /DW 1000"
                                    (when w (str " /W [" w "]"))
                                    (when dw2 (str " /DW2 [" dw2 "]"))
                                    (when w2 (str " /W2 [" w2 "]")) " >>"))
                       (put! fd "<< /Type /FontDescriptor /FontName /TestCJK /Flags 4 /FontBBox [0 -200 1000 800] /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>")))
          j1 "Adobe-Japan1-7" g1 "Adobe-GB1-6" c1 "Adobe-CNS1-7" k1 "Adobe-Korea1-2"
          cmapf (fn [dir nm] (str dir "/CMap/" nm))
          uni1 (fn [dir col cid] (file-uni dir col cid))]
      ;; 50: 90ms-RKSJ-H, Japan1: 82A0 = あ
      (let [cid (file-cid (cmapf j1 "90ms-RKSJ-H") 0x82A0 4)
            cidA (file-cid (cmapf j1 "90ms-RKSJ-H") 0x41 2)
            cp (uni1 j1 "UniJIS-UTF32" cid)]
        (cid-font 50 "90ms-RKSJ-H" "Japan1" :w (str cid " [777] 1 [333]"))
        (row "font" "fonts/progs.pdf" 50 0 "composite" 800 -200 0 0 -1)
        (row "len" "82A0" 2) (row "len" "41" 1)
        (row "cid" "82A0" cid) (row "cid" "41" cidA)
        (row "code" "82A0" (cps cp) "cid" 77700))
      ;; 51: UniJIS-UCS2-H: the code IS Unicode; the CID is the CMap's; widths by CID
      (let [cid (file-cid (cmapf j1 "UniJIS-UCS2-H") 0x3042 4)]
        (cid-font 51 "UniJIS-UCS2-H" "Japan1" :w (str cid " [888]"))
        (row "font" "fonts/progs.pdf" 51 0 "composite" 800 -200 0 0 -1)
        (row "len" "3042" 2)
        (row "cid" "3042" cid)
        (row "code" "3042" (cps 0x3042) "encoding" 88800))
      ;; 52: UniJIS-UTF16-H: a surrogate pair is one four-byte code (a signed 32-bit number)
      (let [cid (file-cid (cmapf j1 "UniJIS-UTF16-H") 0xD840DC0B 8)]
        (cid-font 52 "UniJIS-UTF16-H" "Japan1" :w (str cid " [555]"))
        (row "font" "fonts/progs.pdf" 52 0 "composite" 800 -200 0 0 -1)
        (row "len" "D840DC0B" 4) (row "len" "3042" 2)
        (row "cidd" (- 0xD840DC0B 0x100000000) cid)
        (row "coded" (- 0xD840DC0B 0x100000000) (cps 0x2000B) "encoding" 55500))
      ;; 53: GBK-EUC-H, GB1
      (let [cid (file-cid (cmapf g1 "GBK-EUC-H") 0xB0A1 4)
            cp (uni1 g1 "UniGB-UTF32" cid)]
        (cid-font 53 "GBK-EUC-H" "GB1")
        (row "font" "fonts/progs.pdf" 53 0 "composite" 800 -200 0 0 -1)
        (row "len" "B0A1" 2) (row "len" "41" 1)
        (row "cid" "B0A1" cid)
        (row "code" "B0A1" (cps cp) "cid" 100000))
      ;; 54: ETen-B5-H, CNS1
      (let [cid (file-cid (cmapf c1 "ETen-B5-H") 0xA440 4)
            cp (uni1 c1 "UniCNS-UTF32" cid)]
        (cid-font 54 "ETen-B5-H" "CNS1")
        (row "font" "fonts/progs.pdf" 54 0 "composite" 800 -200 0 0 -1)
        (row "cid" "A440" cid)
        (row "code" "A440" (cps cp) "cid" 100000))
      ;; 55: KSCms-UHC-H, Korea1
      (let [cid (file-cid (cmapf k1 "KSCms-UHC-H") 0xB0A1 4)
            cp (uni1 k1 "UniKS-UTF32" cid)]
        (cid-font 55 "KSCms-UHC-H" "Korea1")
        (row "font" "fonts/progs.pdf" 55 0 "composite" 800 -200 0 0 -1)
        (row "cid" "B0A1" cid)
        (row "code" "B0A1" (cps cp) "cid" 100000))
      ;; 56: Identity-H with a Japan1 CIDSystemInfo and no ToUnicode: the CID is the Adobe-Japan1 CID
      (let [cp (uni1 j1 "UniJIS-UTF32" 843)]
        (cid-font 56 "Identity-H" "Japan1")
        (row "font" "fonts/progs.pdf" 56 0 "composite" 800 -200 0 0 -1)
        (row "cid" (hex 843) 843)
        (row "code" (hex 843) (cps cp) "cid" 100000))
      ;; 57: Identity-H, an ordering that says nothing (Identity): no mapping at all
      (cid-font 57 "Identity-H" "Identity")
      (row "font" "fonts/progs.pdf" 57 0 "composite" 800 -200 1024 0 -1)
      (row "code" (hex 843) "-" "unknown" 100000)
      ;; 58: a vertical CMap, and /W2 and /DW2
      ;; the vertical CMap's own file overrides some codes (the small kana have vertical forms); the others are the
      ;; horizontal CMap's (usecmap)
      (let [vcid (fn [code] (try (file-cid (cmapf j1 "90ms-RKSJ-V") code 4)
                                 (catch Exception _ (file-cid (cmapf j1 "90ms-RKSJ-H") code 4))))
            cid (vcid 0x82A0)
            cid2 (vcid 0x82A2)]
        (cid-font 58 "90ms-RKSJ-V" "Japan1" :w (str cid " [800]") :dw2 "850 -900"
                  :w2 (str cid " [-1100 400 800] " cid2 " " (+ cid2 2) " -700 300 750"))
        (row "font" "fonts/progs.pdf" 58 0 "composite" 800 -200 0 1 -1)
        (row "vm" "82A0" 0 -110000) (row "vm" "82A0" 1 40000) (row "vm" "82A0" 2 80000)
        (row "vm" "82A2" 0 -70000) (row "vm" "82A2" 1 30000) (row "vm" "82A2" 2 75000)
        (row "vm" "8140" 0 -90000) (row "vm" "8140" 2 85000))
      ;; 59: ToUnicode wins over the Adobe tables
      (put! 60 (stream-body "" (bytes-of "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n1 beginbfchar <82A0> <0041> endbfchar\nendcmap end end\n")))
      (cid-font 59 "90ms-RKSJ-H" "Japan1" :tounicode "60 0 R")
      (row "font" "fonts/progs.pdf" 59 0 "composite" 800 -200 0 0 -1)
      (row "code" "82A0" (cps 0x41) "tounicode" 100000)
      ;; 61: a Chrome-style ToUnicode that names glyphs by the Kangxi radical and CJK radical code points: the font
      ;; layer reports what the font says (evidence keeps it, D142); `emon.normalize` folds it in the text
      (put! 62 (stream-body "" (bytes-of "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <0000> <FFFF> endcodespacerange\n2 beginbfchar <0001> <2F00> <0002> <2EA6> endbfchar\nendcmap end end\n")))
      (cid-font 61 "Identity-H" "Identity" :tounicode "62 0 R")
      (row "font" "fonts/progs.pdf" 61 0 "composite" 800 -200 0 0 -1)
      (row "code" "1" (cps 0x2F00) "tounicode" 100000)
      (row "code" "2" (cps 0x2EA6) "tounicode" 100000)))
  (let [pdf (pdf-file @objs)
        text (str "# Written by kin/scripts/font-prog-fixtures.clj; read by kin/pdffonttest.kin (DEVLOG P3.4, D140, D141).\n"
                  "# The format of fonts.txt, against fonts/progs.pdf; `cidd`/`coded` take a four-byte code as the signed\n"
                  "# 32-bit decimal the content interpreter builds; `vm`: hex code, which (0 w1, 1 vx, 2 vy), the value in\n"
                  "# 1/100000 em.\n"
                  (str/join "\n" @rows) "\n")]
    {"test/fonts/progs.pdf" pdf
     "test/fonts/progs.txt" (.getBytes ^String text "UTF-8")
     "test/fonts/prog-t1.bin" @programs-t1
     "test/fonts/prog-cff.bin" @programs-cff
     "test/fonts/prog-tt.bin" @programs-tt
     "test/fonts/prog-otf.bin" @programs-otf}))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        files (build)]
    (if check?
      (let [bad (remove (fn [[p b]] (and (.exists (io/file p))
                                         (java.util.Arrays/equals ^bytes b (java.nio.file.Files/readAllBytes (.toPath (io/file p))))))
                        files)]
        (if (seq bad)
          (do (println "FAIL" (str/join ", " (map first bad)) "differ from what font-prog-fixtures.clj makes") (System/exit 1))
          (println "  ok   test/fonts/progs.pdf, progs.txt and the prog-*.bin programs are what font-prog-fixtures.clj makes")))
      (doseq [[p b] files]
        (with-open [o (io/output-stream p)] (.write o ^bytes b))
        (println "  wrote" p (alength ^bytes b) "bytes")))))

(apply -main *command-line-args*)
