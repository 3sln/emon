(ns synth.table-model
  "P12.10 table models: make_html authors the HTML, pdfw writes fixed geometry,
   and html2gold derives the truth. No browser or external PDF library is needed."
  (:require [synth.make-html :as mh]
            [synth.pdfw :as pdf]
            [synth.html2gold :as gold]
            [synth.build :as build]
            [pyjson :as pj :refer [om]]
            [clojure.string :as str]))

(def root "test/corpus/synth/")
(def widths
  (into {} (for [[bold name] [[false "Helvetica"] [true "Helvetica-Bold"]]]
             [bold (into {} (for [[_ code width] (re-seq #"(?m)^C (\d+) ; WX ([\d.]+) ;" (slurp (str "test/fonts/" name ".afm")))]
                              [(parse-long code) (parse-double width)]))])))
(defn text-at [bold x y text right?]
  (let [width (* 0.01 (reduce + (map #(get (widths bold) (int %) 0) text)))]
    (pdf/t (if bold :F2 :F1) 10 (if right? (- x width) x) y text)))
(defn sparse-pdf-bytes []
  (let [headers ["Date" "Details" "Deposit" "Withdrawal" "Balance"]
        xs [40 81.22 331 405 549.28]
        row (fn [bold y cells]
              (map-indexed #(text-at bold (xs %1) y %2 (>= %1 2)) cells))
        content (str/join "\n"
                  (concat [(pdf/t :F1 18 40 750 "Sparse ledger")]
                          (row true 690 headers)
                          (mapcat (fn [r]
                                    (row false (- 674 (* r 16))
                                         [(str (inc r) " Sep")
                                          (if (even? r) "Transfer" "Electronic transfer") ""
                                          (if (even? r) "181.04" "1.00")
                                          (if (even? r) "149,818.96" "999.99")]))
                                  (range 8))))]
    (pdf/build-pdf
      {1 "<< /Type /Catalog /Pages 2 0 R >>"
       2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
       3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /Contents 4 0 R >>"
       4 [:stream "" content]
       8 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
       9 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"})))


(defn mixed-pdf-bytes []
  (let [content (str/join "\n"
                  (concat [(pdf/t :F1 18 40 750 "Summary and ledger")]
                          (mapcat (fn [[y label amount]]
                                    [(text-at false 76 y label false) (text-at false 430 y amount true)])
                                  [[690 "Subtotal" "31.00"] [674 "Tax" "6.20"] [658 "Total" "37.20"]])
                          [(text-at true 76 642 "Date" false)
                           (text-at true 150 642 "Details" false)
                           (text-at true 430 642 "Amount" true)]
                          (mapcat (fn [[y date detail amount]]
                                    [(text-at false 76 y date false) (text-at false 150 y detail false)
                                     (text-at false 430 y amount true)])
                                  [[626 "01Jan" "Rent" "1.00"] [610 "02Jan" "Electricity" "20.00"]
                                   [594 "03Jan" "Service fee" "300.00"]])))]
    (pdf/build-pdf
      {1 "<< /Type /Catalog /Pages 2 0 R >>"
       2 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
       3 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 8 0 R /F2 9 0 R >> >> /Contents 4 0 R >>"
       4 [:stream "" content] 8 (pdf/font-objs 8) 9 (pdf/font-objs 9)})))

(def fixtures {"table-ledger-sparse" sparse-pdf-bytes "table-summary-and-ledger" mixed-pdf-bytes})

(defn -main [& args]
  (doseq [[stem make-pdf] (sort-by key fixtures)]
    (let [html (get (mh/build-table-model) stem)
          hp (str root "html/" stem ".html") pp (str root "pdf/" stem ".pdf")
          gp (str root "gold/" stem ".gold.json") facts (om "pages" [[612.0 792.0]])
          producer (get build/TABLE-MODEL-PRODUCERS stem)
          truth (gold/gold-text (gold/convert html facts stem
                                 (om "html" (str "html/" stem ".html")
                                     "pdf" (str "pdf/" stem ".pdf") "producer" producer)))]
      (if (= args ["--check"])
        (do (assert (= html (slurp hp)) (str stem " HTML is current"))
            (assert (pdf/same-bytes? pp (make-pdf)) (str stem " PDF is current"))
            (assert (= truth (slurp gp)) (str stem " html2gold is current")))
        (do (spit hp html) (pdf/write-bytes! pp (make-pdf)) (spit gp truth)
            (let [mp (str root "MANIFEST.json") manifest (pj/oparse (slurp mp))
                  file (fn [kind ext]
                         (let [rel (str kind "/" stem ext) path (str root rel)]
                           (om "path" rel "sha256" (build/sha path) "bytes" (.length (java.io.File. path)))))
                  entry (om "files" (om "html" (file "html" ".html") "pdf" (file "pdf" ".pdf"))
                            "pdf-producer" producer "facts" facts)]
              (spit mp (gold/gold-text (pj/oassoc manifest "docs" (pj/oassoc (get manifest "docs") stem entry)))))))
      (println "  ok" stem "HTML/PDF/html2gold"))))

(apply -main *command-line-args*)
