;; A first CER: OCR evidence against a synthetic gold document (DEVLOG P2.6, D095).
;;
;;     bb -cp tools tools/ocr/cer.clj DUMP GOLD        DUMP = a driver's stdout
;;
;; Babashka only. The real scorer is `bb accuracy` (D077-D079, python); this is the
;; smallest thing that says something true about one page: the OCR words in the
;; engine's reading order, joined by single spaces, against the gold's text in
;; document order (a heading is its number, a space and its text), both
;; whitespace-normalised (no NFC: bb has no Normalizer; the page is ASCII), character error rate = Levenshtein / gold length.
(ns ocr.cer
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn norm [s]
  (->> (str/split (or s "") #"\s+")
       (remove str/blank?)
       (str/join " ")))

(defn lev
  "Levenshtein distance of two strings (two-row dynamic programme)."
  [^String a ^String b]
  (let [n (count b)]
    (loop [i 0 prev (vec (range (inc n)))]
      (if (= i (count a))
        (peek prev)
        (let [ca (.charAt a i)
              row (reduce (fn [row j]
                            (conj row (min (inc (peek row))
                                           (inc (prev (inc j)))
                                           (+ (prev j) (if (= ca (.charAt b j)) 0 1)))))
                          [(inc i)] (range n))]
          (recur (inc i) row))))))

(defn gold-text
  "The text of the gold body in document order."
  [body]
  (let [own (when (:text body)
              (if (:number body) (str (:number body) " " (:text body)) (:text body)))]
    (->> (concat (when own [own]) (mapcat gold-text (:children body)))
         (remove nil?))))

(defn ocr-text [evidence]
  (str/join " " (map :text (:owords evidence))))

(defn dump-evidence [dump-text]
  (let [line (first (filter #(str/starts-with? % "evidence: ") (str/split-lines dump-text)))]
    (json/parse-string (subs line (count "evidence: ")) true)))

(defn cer [dump-text gold-file]
  (let [ev (dump-evidence dump-text)
        gold (json/parse-string (slurp gold-file) true)
        g (norm (str/join " " (gold-text (get-in gold [:model :body]))))
        o (norm (ocr-text ev))
        d (lev g o)]
    {:gold-chars (count g) :ocr-chars (count o) :distance d
     :cer (/ (double d) (count g)) :words (count (:owords ev))
     :gold g :ocr o}))

(when (= *file* (System/getProperty "babashka.file"))
  (let [[dump gold] *command-line-args*
        r (cer (slurp dump) gold)]
    (println (format "CER %.4f  (%d edits over %d gold chars; %d OCR words)"
                     (:cer r) (:distance r) (:gold-chars r) (:words r)))
    (when (System/getenv "SHOW")
      (println "GOLD:" (:gold r))
      (println "OCR: " (:ocr r)))))
