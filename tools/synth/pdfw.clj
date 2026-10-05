(ns synth.pdfw
  "A byte-for-byte PDF writer for the hand-made fixtures (P6.8, D227): the writer of `tagged_pair.clj` (D076), made a
  namespace so that `gfx_pdf.clj` and `tagged_tables.clj` share it. Classic file (one cross-reference table), Latin-1
  strings, the standard 14 fonts. No PDF library; babashka only (D094)."
  (:require [clojure.string :as str]))

(defn latin1 ^bytes [^String s] (.getBytes s "ISO-8859-1"))

(defn build-pdf
  "objs: {num body}. A body is a string (an object) or [:stream dict-string data-string]."
  [objs]
  (let [buf (java.io.ByteArrayOutputStream.)
        offs (atom {})
        w (fn [s] (.write buf (latin1 s)))]
    (w "%PDF-1.7\n%âãÏÓ\n")
    (doseq [n (sort (keys objs))]
      (swap! offs assoc n (.size buf))
      (let [b (objs n)]
        (w (str n " 0 obj\n"))
        (if (vector? b)
          (let [[_ dict data] b]
            (w (str "<< " dict " /Length " (count (latin1 data)) " >>\nstream\n"))
            (w data)
            (w "\nendstream"))
          (w b))
        (w "\nendobj\n")))
    (let [xref (.size buf)
          n (inc (apply max (keys objs)))]
      (w (str "xref\n0 " n "\n0000000000 65535 f \n"))
      (doseq [i (range 1 n)]
        (w (if-let [o (@offs i)] (format "%010d 00000 n \n" o) "0000000000 65535 f \n")))
      (w (str "trailer\n<< /Size " n " /Root 1 0 R >>\nstartxref\n" xref "\n%%EOF\n")))
    (.toByteArray buf)))

(defn pstr [s] (str "(" (str/replace s #"([\\()])" "\\\\$1") ")"))

(defn num*
  "A number as short PDF text (two decimals at most)."
  [x]
  (let [s (format "%.2f" (double x))]
    (if (str/includes? s ".")
      (-> s (str/replace #"0+$" "") (str/replace #"\.$" ""))
      s)))

(defn t
  "A text object: font key (:F1 regular, :F2 bold), size, x, y, one line."
  [font size x y line]
  (str "BT /" (name font) " " (num* size) " Tf " (num* x) " " (num* y) " Td " (pstr line) " Tj ET"))

(def font-objs
  {8 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
   9 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"})

(defn write-bytes! [path ^bytes bs]
  (with-open [o (java.io.FileOutputStream. (str path))] (.write o bs)))

(defn same-bytes? [path ^bytes bs]
  (let [f (java.io.File. (str path))]
    (and (.exists f) (java.util.Arrays/equals bs (java.nio.file.Files/readAllBytes (.toPath f))))))
