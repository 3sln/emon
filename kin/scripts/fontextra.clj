;; The tables a CFF or TrueType font program needs, as `kin/fontextra.kin` (DEVLOG P3.4, D135).
;;
;;     bb kin/scripts/fontextra.clj           write kin/fontextra.kin
;;     bb kin/scripts/fontextra.clj --check   regenerate and compare with what is committed
;;
;; THE SOURCES are the pdf.js files `kin/scripts/cjkfetch.clj` fetched into `.cache/cjk/` and checked against
;; `test/fonts/cjk-sources.edn`: the 391 CFF standard strings (Adobe TN 5176 appendix A), the CFF predefined
;; charsets Expert and ExpertSubset (appendix C), the CFF Expert encoding (appendix B), MacExpertEncoding
;; (ISO 32000-1 annex D.4) and the 258 standard Macintosh glyph names of a TrueType `post` table. Every name is
;; turned into a row of `emon.agl`'s glyph table (a glyph id, D081) HERE, so the runtime never compares names
;; with them; a name the AGL does not have is a failure of this script, except `.notdef` (-1). The Standard
;; encoding pdf.js carries must equal the one emon derived from the AFMs (`enc-gid` 0), or this fails too: a
;; second opinion on both.
(ns fontextra
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn sha256 [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs)))))

(def manifest (edn/read-string (slurp "test/fonts/cjk-sources.edn")))

(defn read-src [p]
  (let [f (io/file ".cache/cjk" (str/replace p "/" "__"))
        e (some #(when (= p (:path %)) %) (:files manifest))]
    (when-not (and e (.exists f))
      (println "FAIL missing source" p "(run bb kin/scripts/cjkfetch.clj)") (System/exit 1))
    (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
      (when (not= (sha256 bs) (:sha256 e))
        (println "FAIL checksum mismatch for" p) (System/exit 1))
      (String. bs "UTF-8"))))

(defn js-array
  "The strings of `const|var NAME = [ ... ];`."
  [txt nm]
  (let [m (re-find (re-pattern (str "(?s)(?:const|var) " nm " = \\[(.*?)\\];")) txt)]
    (when-not m (println "FAIL no array" nm) (System/exit 1))
    (mapv second (re-seq #"\"([^\"]*)\"" (second m)))))

;; ------------------------------------------------------------------ the AGL

(defn kin-data [txt nm]
  (let [m (re-find (re-pattern (str "(?s)\\(defdata \\^:pub " nm "\\s+:emitter kin.lang/flat-array\\s+(?::stride \\d+\\s+)?:data \\[(.*?)\\]\\s+:accessors")) txt)]
    (mapv #(Long/parseLong %) (str/split (str/trim (second m)) #"\s+"))))

(def agl
  (let [txt (slurp "kin/agl.kin")
        pool (kin-data txt "agl-pool")
        rows (partition 5 (kin-data txt "agl-name"))]
    (into {} (map-indexed (fn [i [off len]]
                            [(apply str (map char (subvec pool off (+ off len)))) i]))
          rows)))

(def std-enc
  (let [txt (slurp "kin/encodings.kin")]
    (subvec (kin-data txt "enc-gid") 0 256)))

(def agl-cps
  "glyph name -> its first code point, from the glyph list as the AGL table of kin/agl.kin holds it."
  (let [txt (slurp "kin/agl.kin")
        pool (kin-data txt "agl-pool")
        rows (partition 5 (kin-data txt "agl-name"))
        uni (kin-data txt "agl-uni")]
    (into {} (keep (fn [[off len uoff cnt]]
                     (when (pos? cnt)
                       [(apply str (map char (subvec pool off (+ off len)))) (nth uni uoff)])))
          rows)))

(defn pua? [cp] (<= 0xE000 cp 0xF8FF))

(defn fold-expert
  "The code point a MacExpert glyph means as TEXT. The AGL gives the small capitals, old-style figures,
  superiors and the accents of the expert set code points of Adobe's private use area (`Asmall` is U+F761),
  which no reader can use: the glyph is the character it is a variant of, so `Asmall` is `a` (code 0x61 of
  MacExpertEncoding is Asmall: the small capital is what a lower-case letter is set as), `oneoldstyle` is `1`,
  `asuperior` `a`, `Acutesmall` the acute accent. A name the AGL gives a real code point keeps it."
  [nm]
  (let [direct (get agl-cps nm)]
    (if (and direct (not (pua? direct)))
      direct
      (let [m (re-matches #"(.+?)(small|oldstyle|superior|inferior|fitted)" nm)
            base (when m (second m))
            lower-first (when base (str (str/lower-case (subs base 0 1)) (subs base 1)))
            v (when m
                (first (remove #(or (nil? %) (pua? %))
                               [(when (and (= 1 (count base)) (= "small" (nth m 2))) (int (first (str/lower-case base))))
                                (get agl-cps base)
                                (get agl-cps lower-first)])))]
        (cond
          (and v (not (pua? v))) v
          (= nm "threequartersemdash") 0x2014
          (= nm "rupiah") [0x52 0x70]
          :else (do (println "FAIL no text value for MacExpert glyph" nm direct) (System/exit 1)))))))

(defn gid [nm]
  (cond
    (= nm "") -1
    (contains? #{".notdef" ".null" "nonmarkingreturn"} nm) -1
    :else (or (get agl nm)
              (do (println "FAIL name not in the AGL table:" nm) (System/exit 1)))))

;; ---------------------------------------------------------------- the tables

(defn build []
  (let [enc (read-src "pdfjs/src/core/encodings.js")
        cs (read-src "pdfjs/src/core/charsets.js")
        cff (read-src "pdfjs/src/core/cff_parser.js")
        fonts (read-src "pdfjs/src/core/fonts.js")
        std-strings (js-array cff "CFFStandardStrings")
        std-names (js-array enc "StandardEncoding")
        expert-enc (js-array enc "ExpertEncoding")
        mac-expert (js-array enc "MacExpertEncoding")
        iso (js-array cs "ISOAdobeCharset")
        expert-cs (js-array cs "ExpertCharset")
        expert-sub (js-array cs "ExpertSubsetCharset")
        mac-order (js-array fonts "MacStandardGlyphOrdering")]
    (assert (= 391 (count std-strings)) "CFF standard strings")
    (assert (= 229 (count iso)) "ISOAdobe charset")
    (assert (= (vec (take 229 std-strings)) iso) "ISOAdobe is the first 229 standard strings")
    (assert (= 258 (count mac-order)) "Macintosh glyph order")
    (assert (= 256 (count std-names)) "StandardEncoding")
    (assert (= 256 (count mac-expert)) "MacExpertEncoding")
    (assert (= 256 (count expert-enc)) "ExpertEncoding")
    (let [std (mapv gid std-names)]
      (when (not= std std-enc)
        (println "FAIL pdf.js's StandardEncoding differs from emon's (derived from the AFMs) at codes"
                 (vec (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector std std-enc))))
        (System/exit 1)))
    ;; SIDs 379..390 (`001.000` .. `Semibold`) are version and weight strings, not glyphs: no row, -1
    (assert (every? (fn [nm] (not (contains? agl nm))) (drop 379 std-strings)) "the last twelve standard strings are no glyphs")
    {:std-strings (vec (concat (map gid (take 379 std-strings)) (repeat 12 -1)))
     :expert-charset (mapv gid expert-cs)
     :expert-subset (mapv gid expert-sub)
     :expert-enc (mapv gid expert-enc)
     :mac-expert (mapv gid mac-expert)
     :mac-expert-cp (vec (mapcat (fn [nm] (if (= nm "") [-1 0] (let [v (fold-expert nm)] (if (vector? v) v [v 0])))) mac-expert))
     :mac-order (mapv gid mac-order)}))

(defn table [nm doc flat accessors]
  (str ";; " doc "\n"
       "(defdata ^:pub " nm "\n"
       "  :emitter kin.lang/flat-array\n"
       (when (= nm "mac-expert-cp") "  :stride 2\n")
       "  :data [" (str/join "\n         " (map #(str/join " " %) (partition-all (if (= nm "mac-expert-cp") 2 16) flat))) "]\n"
       "  :accessors {" (str/join "\n              " accessors) "})\n"))

(defn render []
  (let [d (build)
        t (fn [nm doc k]
            (table nm doc (get d k)
                   [(str "^I32 " nm "-count [^I32 ignored]")
                    (str "^I32 " nm "-at [^I32 ignored ^I32 i]")]))]
    (str ";; GENERATED by kin/scripts/fontextra.clj from the pdf.js sources of test/fonts/cjk-sources.edn -- do not edit.\n"
         ";;\n"
         ";; Glyph tables for the font programs `emon.fontprog` reads (P3.4, D135). Every value is a glyph id: a row of\n"
         ";; `emon.agl`'s glyph table, -1 for `.notdef` and for a code with no glyph.\n"
         "(ns emon.fontextra\n"
         "  (:require [emon.lang :refer [I32]]\n"
         "            [kin.lang :refer [defdata]]))\n\n"
         (t "cff-std-string" "the 391 standard strings of a CFF font, by SID (Adobe TN 5176 appendix A)" :std-strings)
         "\n"
         (t "cff-expert-charset" "the CFF predefined charset 1, Expert: glyph index -> glyph id" :expert-charset)
         "\n"
         (t "cff-expertsub-charset" "the CFF predefined charset 2, ExpertSubset: glyph index -> glyph id" :expert-subset)
         "\n"
         (t "cff-expert-enc" "the CFF predefined encoding 1, Expert: code -> glyph id" :expert-enc)
         "\n"
         (t "mac-expert-enc" "MacExpertEncoding (ISO 32000-1 D.4): code -> glyph id" :mac-expert)
         "\n"
         (table "mac-expert-cp" "MacExpertEncoding as text: per code two code points (-1: no glyph; 0: no second), the glyph's plain characters (kin/scripts/fontextra.clj, fold-expert)"
                (:mac-expert-cp d)
                ["^I32 mac-expert-cp-at [^I32 ignored ^I32 code ^I32 field]"])
         "\n"
         (t "mac-glyph-order" "the 258 standard Macintosh glyph names (a TrueType `post` table of format 1, and the first names of format 2)" :mac-order))))

(defn -main [& args]
  (let [text (render)]
    (if (some #{"--check"} args)
      (if (= text (slurp "kin/fontextra.kin"))
        (println "  ok   kin/fontextra.kin matches its sources")
        (do (println "FAIL kin/fontextra.kin differs from what fontextra.clj generates") (System/exit 1)))
      (do (spit "kin/fontextra.kin" text) (println "  wrote kin/fontextra.kin" (count text) "bytes")))))

(apply -main *command-line-args*)
