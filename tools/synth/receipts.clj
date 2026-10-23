(ns synth.receipts
  "Deterministic born-digital receipt fixtures; HTML is semantic gold, pdfw fixes geometry."
  (:require [synth.pdfw :as pdf] [synth.html2gold :as gold] [synth.build :as build]
            [pyjson :as pj :refer [om]] [clojure.string :as str]))
(def root "test/corpus/synth/")
(def variants ["mono" "proportional" "leaders" "modifiers" "card" "long" "two-column" "europe"])
(def widths
  (into {} (for [[_ code width] (re-seq #"(?m)^C (\d+) ; WX ([\d.]+) ;"
                                      (slurp "test/fonts/Helvetica.afm"))]
             [(parse-long code) (parse-double width)])))
(defn amount-width [v amount]
  (if (= v "mono") (* 4.8 (count amount))
      (* 0.008 (reduce + (map #(get widths (int %) 556) amount)))))
(defn lines [v page]
  (vec (concat [(str "Receipt " v " page " page) "Market Kitchen" "2026-10-22"]
    (if (= v "long") (for [i (range 18)] (format "Item %02d  1 x 2.00  2.00" (+ (* (dec page) 18) i)))
      [(if (= v "leaders") "Bread ............... 3.00" "Bread  1 x 3.00  3.00")
       "Soup  2 × 4.00  8.00"])
    (when (= v "modifiers") ["+ extra cheese 1.50"])
    (cond
      (= v "long") (when (= page 3) ["Discount -1.00" "Subtotal 107.00" "Tax 8.56" "Tip 2.00" "Total $117.56"])
      (= v "modifiers") ["Discount -1.00" "Subtotal 11.50" "Tax 0.92" "Tip 2.00" "Total $14.42"]
      (= v "europe") ["Discount -1.00" "Subtotal 10,00" "Tax 0,80" "Tip 2,00" "Total EUR 12,80"]
      :else ["Discount -1.00" "Subtotal 10.00" "Tax 0.80" "Tip 2.00" "Total $12.80"])
    (when (= v "europe") ["EUR 12,80" "VAT 0,80"])
    (when (or (= v "card") (and (= v "long") (= page 3))) ["VISA **** 1234" "APPROVED" "Signature __________"]))))
(defn -main [& _]
  (doseq [v variants]
    (let [stem (str "receipt-" v) np (if (= v "long") 3 1) w (if (= v "two-column") 454 226.77) h 650
          pages (mapv #(lines v %) (range 1 (inc np)))
          html (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" stem "</title></head><body>"
                    (str/join "" (for [ls pages t ls] (str "<p>" t "</p>"))) "</body></html>\n")
          objects (into {1 "<< /Type /Catalog /Pages 2 0 R >>"
                         2 (str "<< /Type /Pages /Kids [" (str/join " " (for [i (range np)] (str (+ 3 (* i 2)) " 0 R"))) "] /Count " np " >>")
                         100 (str "<< /Type /Font /Subtype /Type1 /BaseFont /" (if (= v "mono") "Courier" "Helvetica") " /Encoding /WinAnsiEncoding >>")}
                    (mapcat (fn [[i ls]]
                      (let [pn (+ 3 (* i 2)) cn (inc pn)
                            content (str/join "\n" (concat
                              (map-indexed (fn [j t]
                                (let [col (if (and (= v "two-column") (>= j 7)) 1 0)
                                      row (if (pos? col) (- j 7) j)]
                                  (let [x (+ 12 (* col 227)) y (- 625 (* row 17))
                                      parts (re-matches #"(.*\s)([-$]?[0-9]+[.,][0-9]{2})" t)]
                                  (if parts
                                    (str (pdf/t :F1 8 x y (nth parts 1)) "\n"
                                         (pdf/t :F1 8 (- (+ x 198) (amount-width v (nth parts 2))) y (nth parts 2)))
                                    (pdf/t :F1 8 x y t))))) ls)
                              ;; EAN-13 zero payload (including check digit 0), no text annotation.
                              (let [bits (str "101" (apply str (repeat 6 "0001101")) "01010"
                                              (apply str (repeat 6 "1110010")) "101")]
                                (for [j (range (count bits)) :when (= \1 (nth bits j))]
                                  (str (+ 12 j) " 25 1 24 re f")))))]
                        [[pn (str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " w " " h "] /Resources << /Font << /F1 100 0 R >> >> /Contents " cn " 0 R >>")]
                         [cn [:stream "" content]]])) (map-indexed vector pages)))
          facts (om "pages" (vec (repeat np [w h]))) producer "emon synth.pdfw receipts v1"
          truth (gold/gold-text (gold/convert html facts stem (om "html" (str "html/" stem ".html") "pdf" (str "pdf/" stem ".pdf") "producer" producer)))
          hp (str root "html/" stem ".html") pp (str root "pdf/" stem ".pdf") gp (str root "gold/" stem ".gold.json")]
      (spit hp html) (pdf/write-bytes! pp (pdf/build-pdf objects)) (spit gp truth)
      (let [mp (str root "MANIFEST.json") manifest (pj/oparse (slurp mp))
            file (fn [kind ext] (let [rel (str kind "/" stem ext) path (str root rel)]
                                 (om "path" rel "sha256" (build/sha path) "bytes" (.length (java.io.File. path)))))
            entry (om "files" (om "html" (file "html" ".html") "pdf" (file "pdf" ".pdf")) "pdf-producer" producer "facts" facts)]
        (spit mp (gold/gold-text (pj/oassoc manifest "docs" (pj/oassoc (get manifest "docs") stem entry)))))
      (println "receipt fixture" stem))))
(apply -main *command-line-args*)
