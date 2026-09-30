(ns accuracy.predict-degraded
  "FIXTURE PREDICTOR \"degraded\": the gold, deliberately and deterministically
damaged, to prove that every metric moves the way it should (D077).

Same protocol as predict_oracle.clj. Damage is periodic, with no randomness, so the
scoreboard entry is reproducible; the `scan` variant is damaged harder:
  text       every Nth character of each text unit is replaced (CER/WER/BOW)
  headings   every 3rd heading's level is +1 (level accuracy), every 7th heading
             becomes a paragraph (detection P/R, paragraph FP)
  paragraphs every 2nd pair of adjacent paragraphs is merged (boundary recall)
  tables     the last cell of every table loses its text (TEDS-lite)
  furniture  running-title/page-number entries of page 2+ are dropped (furniture recall)
  watermark  a watermark's text is appended to the first paragraph (leak)
  initials   each initial is split into its own single-letter heading (D115)
  align      every `align` is dropped
  columns    every multi-fragment paragraph (gold `facts.frags`) is cut in two at
             its first fragment boundary (the middle word: the gold has no offsets)
Nothing here looks at the input file.

    bb -cp tools:tools/lib tools/accuracy/predict_degraded.clj ID VARIANT INPUT GOLD"
  (:require [accuracy.score :as S]
            [clojure.string :as str]
            [pyjson]))

(defn damage [s every]
  (if (empty? s)
    s
    (let [cps (vec (.toArray (.codePoints ^String s)))
          out (reduce (fn [v i] (assoc v i (if (not= (v i) (int \#)) (int \#) (int \@))))
                      cps (range (dec every) (count cps) every))]
      (String. (int-array out) 0 (count out)))))

(defn- kids [n] (or (get n "children") []))

(defn- map-children [n f]
  (if (contains? n "children") (assoc n "children" (mapv f (kids n))) n))

(defn- text-and-headings
  "Steps 1 and 2 in one pre-order pass: damage text, then heading levels and kinds."
  [body every]
  (let [hidx (atom -1)]
    ((fn go [n]
       (let [n (if (string? (get n "text"))
                 (-> n (assoc "text" (damage (get n "text") every)) (dissoc "inlines"))
                 n)
             n (if (= "heading" (get n "kind"))
                 (let [i (swap! hidx inc)
                       n (if (= 2 (mod i 3)) (assoc n "level" (inc (get n "level"))) n)]
                   (if (= 6 (mod i 7))
                     (-> n (assoc "kind" "paragraph") (dissoc "level" "number" "numbering"))
                     n))
                 n)]
         (map-children n go)))
     body)))

(defn- merge-paragraphs [n]
  (let [ch (kids n)
        n (if (seq ch)
            (assoc n "children"
                   (loop [ch ch, i 0, k 0, out []]
                     (if (>= i (count ch))
                       out
                       (if (and (< (inc i) (count ch))
                                (= "paragraph" (get (ch i) "kind"))
                                (= "paragraph" (get (ch (inc i)) "kind")))
                         (let [k (inc k)]
                           (if (even? k)
                             (recur ch (+ i 2) k (conj out (assoc (ch i) "text" (str (get (ch i) "text") " " (get (ch (inc i)) "text")))))
                             (recur ch (inc i) k (conj out (ch i)))))
                         (recur ch (inc i) k (conj out (ch i)))))))
            n)]
    (map-children n merge-paragraphs)))

(defn- blank-last-cell [t]
  (let [cells (filter #(= "cell" (get % "kind")) (S/walk t))]
    (if-let [target (last cells)]
      ((fn go [n] (if (identical? n target) (assoc n "text" "") (map-children n go))) t)
      t)))

(defn- tables [n]
  (let [n (if (= "table" (get n "kind")) (blank-last-cell n) n)]
    (map-children n tables)))

(defn- update-first [body pred f]
  (let [done (atom false)]
    ((fn go [n]
       (cond
         @done n
         (pred n) (do (reset! done true) (f n))
         :else (map-children n go)))
     body)))

(defn- split-initials
  "Each initial becomes a heading of its own letter before its paragraph, which loses the letter."
  [n]
  (let [n (map-children n (fn [c] c))
        ch (kids n)
        out (mapcat (fn [c]
                      (if-let [i (and (= "paragraph" (get c "kind")) (S/initial-of c))]
                        (let [t (S/norm (S/leaf-text i))
                              k (count (.toArray (.codePoints ^String t)))
                              rest-text (let [x (get c "text" "")] (.substring ^String x (.offsetByCodePoints ^String x 0 (min k (S/nlen x)))))]
                          [{"kind" "heading" "level" 2 "text" t}
                           (-> c (assoc "text" rest-text) (dissoc "inlines" "align"))])
                        [(split-initials c)]))
                    ch)]
    (if (contains? n "children") (assoc n "children" (vec out)) n)))

(defn- drop-align [n]
  (map-children (dissoc n "align") drop-align))

(defn- split-fragments
  "A paragraph with two or more gold fragments is cut into two paragraphs at the middle word."
  [n frags]
  (let [ch (kids n)
        out (mapcat (fn [c]
                      (let [ws (S/split-ws (get c "text" ""))]
                        (if (and (= "paragraph" (get c "kind")) (>= (count (get frags (str (get c "id")))) 2) (>= (count ws) 2))
                          (let [k (quot (count ws) 2)]
                            [(assoc c "text" (str/join " " (take k ws)))
                             (-> c (assoc "text" (str/join " " (drop k ws))) (dissoc "id"))])
                          [(split-fragments c frags)])))
                    ch)]
    (if (contains? n "children") (assoc n "children" (vec out)) n)))

(defn predict-gold
  "The damaged Model of a parsed gold map."
  [gold variant]
  (let [m (get gold "model")
        frags (S/frag-map (get gold "facts"))
        every (if (= variant "pdf") 40 15)            ; scan: harsher
        body (-> (get m "body") split-initials (text-and-headings every) merge-paragraphs tables drop-align
                 (split-fragments frags))
        dropped (vec (remove #(and (contains? #{"running-title" "page-number"} (get % "reason"))
                                   (>= (get % "page") 2))
                             (or (get m "dropped") [])))
        wm (filter #(= "watermark" (get % "reason")) dropped)
        body (if (and (seq wm) (seq (filter #(= "paragraph" (get % "kind")) (S/walk body))))
               (update-first body #(= "paragraph" (get % "kind"))
                             #(assoc % "text" (str (get % "text") " " (get (first wm) "text" ""))))
               body)]
    (assoc m "body" body "dropped" dropped)))

(defn predict
  "In-process form: (predict id variant input gold-path) -> the damaged Model map."
  [_id variant _input gold-path]
  (predict-gold (pyjson/parse-file gold-path) variant))

(when (= *file* (System/getProperty "babashka.file"))
  (let [[id variant inp gold] *command-line-args*]
    (print (pyjson/dumps (predict id variant inp gold) {:ascii true}))
    (flush)))
