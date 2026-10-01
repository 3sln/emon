#!/usr/bin/env bb
;; Generate kin/pdfxnames.kin: the PDF names the catalogue readers (P3.8 annotations and
;; forms, P3.9 outlines, labels and metadata) compare against, by BYTES.
;;
;;     bb tools/pdfxnames.clj            write kin/pdfxnames.kin
;;     bb tools/pdfxnames.clj --check    fail if the committed file is stale
;;
;; kin has no string values (see kin/pdfnames.kin). `emon.pdfnames` interns its names in
;; the arena, which holds 192 and is shared with every other reader, so these are NOT
;; interned: a reader compares the bytes of a name object with a row of this table
;; (`px-is ar h (px-uri)`), and looks a key up by walking a dictionary (`px-get`). The
;; id of a name is its position here; appending is safe, nothing else is. Babashka, as
;; D094 says all scripting is; no Python.
(require '[clojure.string :as str])

;; a name, or [name symbol-suffix] where the automatic suffix would collide (a / A, r / R)
(def names
  ["Annots" "Annot" "Rect" "A" "S" "URI" "GoTo" "GoToR" "GoToE" "Launch" "Named" "JavaScript"
   "SubmitForm" "Dest" "D" "Dests" "Link" "Stamp" "Watermark" "Widget" "Text" "FreeText"
   "Line" "Square" "Circle" "Polygon" "PolyLine" "Highlight" "Underline" "Squiggly"
   "StrikeOut" "Caret" "Ink" "Popup" "FileAttachment" "Sound" "Movie" "Screen"
   "PrinterMark" "TrapNet" "Redact" "RichMedia"
   "XYZ" "Fit" "FitH" "FitV" "FitR" "FitB" "FitBH" "FitBV"
   "FT" "Ff" "T" "TU" "TM" "V" "DV" "Opt" "AP" "AS" "Btn" "Tx" "Ch" "Sig" "Off"
   "MaxLen" "Q" "DA" "NeedAppearances" "SigFlags" "XFA"
   "NM" "Name" "M" "Reason" "Location" "ContactInfo" "ByteRange" "SubFilter" "Cert"
   "DocTimeStamp" "Perms" "DocMDP" "FixedPrint"
   "Last" "Next" "C" "Nums" "Limits" "P" "St" ["R" "r-upper"] ["r" "r-lower"] ["a" "a-lower"]
   "UF" "FS" "ViewerPreferences" "DisplayDocTitle" "QuadPoints" "CA" "OC" "Trapped"
   ;; P3.5, P3.6 (graphics state, colour spaces, functions, optional content, images)
   "CalGray" "CalRGB" "Lab" "ICCBased" "Indexed" "Separation" "DeviceN" "Alternate"
   "WhitePoint" "BlackPoint" "Gamma" "Range" "None" "All"
   "G" "RGB" "CMYK" "I" "CS" "IM" "BPC" "H" "ImageMask" "Mask" "SMask" "SMaskInData"
   "FunctionType" "Domain" "C0" "C1" "Functions" "Bounds" "Encode" "Decode" "BitsPerSample" "Order"
   ["BM" "bm"] ["LW" "lw"] ["BC" "bc"] "Normal" "Compatible" "Multiply" "Overlay"
   "Darken" "Lighten" "ColorDodge" "ColorBurn" "HardLight" "SoftLight" "Difference"
   "Exclusion" "Hue" "Saturation" "Color" "Luminosity" "Alpha"
   ["OCProperties" "oc-properties"] ["OCGs" "ocgs"] "OCG" "OCMD" "BaseState" ["ON" "on-upper"] ["OFF" "off-upper"]
   "Event" "Category" "Usage" "View" "ViewState" "Print" "PrintState" "Export" "ExportState"
   "AllOn" "AnyOn" "AnyOff" "AllOff" ["VE" "ve"] "And" "Or" "Not" "Unchanged"
   "ShadingType" "PatternType" "Extend"])

;; XMP (P3.9): namespace URIs and the local names the reader meets, as [symbol string].
(def xstrings
  [["xs-ns-dc" "http://purl.org/dc/elements/1.1/"]
   ["xs-ns-pdf" "http://ns.adobe.com/pdf/1.3/"]
   ["xs-ns-xmp" "http://ns.adobe.com/xap/1.0/"]
   ["xs-ns-xmp-mm" "http://ns.adobe.com/xap/1.0/mm/"]
   ["xs-ns-pdfuaid" "http://www.aiim.org/pdfua/ns/id/"]
   ["xs-ns-pdfaid" "http://www.aiim.org/pdfa/ns/id/"]
   ["xs-ns-rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"]
   ["xs-rdf" "RDF"] ["xs-description" "Description"] ["xs-seq" "Seq"] ["xs-bag" "Bag"]
   ["xs-alt" "Alt"] ["xs-li" "li"] ["xs-title" "title"] ["xs-creator" "creator"]
   ["xs-description-l" "description"] ["xs-subject" "subject"] ["xs-language" "language"]
   ["xs-format" "format"] ["xs-rights" "rights"] ["xs-producer" "Producer"]
   ["xs-keywords" "Keywords"] ["xs-pdf-version" "PDFVersion"]
   ["xs-creator-tool" "CreatorTool"] ["xs-create-date" "CreateDate"]
   ["xs-modify-date" "ModifyDate"] ["xs-metadata-date" "MetadataDate"]
   ["xs-document-id" "DocumentID"] ["xs-instance-id" "InstanceID"]
   ["xs-original-document-id" "OriginalDocumentID"] ["xs-part" "part"]
   ["xs-conformance" "conformance"] ["xs-rev" "rev"] ["xs-amd" "amd"]
   ["xs-about" "about"] ["xs-parse-type" "parseType"] ["xs-lang" "lang"]
   ["xs-xmlns" "xmlns"] ["xs-xml" "xml"] ["xs-rdf-l" "rdf"]
   ["xs-trapped" "Trapped"] ["xs-publisher" "publisher"] ["xs-date" "date"]
   ["xs-identifier" "identifier"] ["xs-label" "Label"] ["xs-rendition" "RenditionClass"]])

(defn nm [x] (if (vector? x) (first x) x))
(defn sym [x]
  (if (vector? x)
    (str "px-" (second x))
    (str "px-" (-> x
                   (str/replace #"(?<=[a-z0-9])(?=[A-Z])" "-")
                   (str/replace #"(?<=[A-Z])(?=[A-Z][a-z])" "-")
                   str/lower-case))))

(defn rows [xs w] (str/join "\n         " (map #(str/join " " %) (partition-all w xs))))

(defn render []
  (let [syms (mapv sym names)
        _ (assert (= (count syms) (count (set syms))) (str "duplicate symbol " (frequencies syms)))
        ns' (mapv nm names)
        _ (assert (= (count ns') (count (set ns'))) "duplicate name")
        chars (vec (mapcat #(map int %) ns'))
        xs' (mapv second xstrings)
        _ (assert (= (count xs') (count (set xs'))) "duplicate xmp string")
        xchars (vec (mapcat #(map int %) xs'))
        xspans (loop [o 0 xs xs' acc []]
                 (if (seq xs)
                   (recur (+ o (count (first xs))) (rest xs) (conj acc o (count (first xs))))
                   acc))
        spans (loop [o 0 xs ns' acc []]
                (if (seq xs)
                  (recur (+ o (count (first xs))) (rest xs) (conj acc o (count (first xs))))
                  acc))]
    (str ";; GENERATED by tools/pdfxnames.clj -- do not edit.\n;;\n"
         ";; The " (count names) " PDF names the catalogue readers compare by BYTES (P3.8, P3.9): not\n"
         ";; interned in the object arena (that holds 192 and is shared), so a reader walks a\n"
         ";; dictionary and compares a key's bytes with a row of this table. The id of a name is\n"
         ";; its position; appending is safe, nothing else is.\n"
         "(ns emon.pdfxnames\n  (:require [emon.lang :refer [I32 defenum]]\n"
         "            [kin.lang :refer [defdata]]))\n\n"
         "(defenum ^:pub xs\n  ["
         (str/join "\n   " (map-indexed (fn [i [s n]] (str "[" s " " i " \"" n "\"]")) xstrings))
         "])\n\n"
         ";; the bytes of every XMP string, back to back\n"
         "(defdata ^:pub xs-chars\n  :emitter kin.lang/flat-array\n  :data [" (rows xchars 16) "]\n"
         "  :accessors {^I32 xs-chars-at [^I32 ignored ^I32 i]})\n\n"
         ";; start offset into xs-chars, length\n"
         "(defdata ^:pub xs-span\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows xspans 16) "]\n"
         "  :accessors {^I32 xs-span-count [^I32 ignored]\n"
         "              ^I32 xs-span-at [^I32 ignored ^I32 row ^I32 field]})\n\n"
         "(defenum ^:pub px\n  ["
         (str/join "\n   " (map-indexed (fn [i [s n]] (str "[" s " " i " \"" n "\"]")) (map vector syms ns')))
         "])\n\n"
         ";; the bytes of every name, back to back\n"
         "(defdata ^:pub px-chars\n  :emitter kin.lang/flat-array\n  :data [" (rows chars 16) "]\n"
         "  :accessors {^I32 px-chars-at [^I32 ignored ^I32 i]})\n\n"
         ";; start offset into px-chars, length\n"
         "(defdata ^:pub px-span\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows spans 16) "]\n"
         "  :accessors {^I32 px-span-count [^I32 ignored]\n"
         "              ^I32 px-span-at [^I32 ignored ^I32 row ^I32 field]})\n")))

(let [path (str (-> *file* java.io.File. .getCanonicalFile .getParentFile .getParent) "/kin/pdfxnames.kin")
      text (render)]
  (if (some #{"--check"} *command-line-args*)
    (if (and (.exists (java.io.File. path)) (= text (slurp path)))
      (println "  ok   kin/pdfxnames.kin is current")
      (do (println "  FAIL kin/pdfxnames.kin is stale; run bb tools/pdfxnames.clj") (System/exit 1)))
    (spit path text)))
