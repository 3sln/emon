(ns pdfreverse
  "Rewrite a classic-xref PDF with its objects in REVERSE file order (DEVLOG P2.4, `bb repeat`).

  The Engine reads a whole document in one unit today: there is no host-side page submission to reverse. What
  can be reversed is the order in which the document's parts are laid out in the file, so that every page's
  objects, and the page tree above them, are met last-first. The logical document is the same: each object's
  bytes are copied untouched, a fresh cross-reference table points at the new offsets, and the trailer keeps
  /Root, /Info, /ID and /Encrypt (an encrypted file's keys are per object number, not per offset). What the
  reader must not do is let the file order leak into the Model.

  `(reverse-objects bytes)` answers the new bytes, or nil when the file is not of the shape this can rewrite
  (no single classic table: xref streams, hybrid or incremental files, a broken table)."
  (:require [clojure.string :as str]))

(defn- latin1 [^bytes b] (String. b "ISO-8859-1"))
(defn- bytes-of [^String s] (.getBytes s "ISO-8859-1"))

(defn reverse-objects [^bytes pdf]
  (let [s (latin1 pdf)
        sx (some->> (re-seq #"startxref\s+(\d+)" s) last second parse-long)]
    (when (and sx (< sx (count s)) (str/starts-with? (subs s sx) "xref"))
      (let [tail (subs s sx)
            ti (str/index-of tail "trailer")]
        (when (and ti (not (re-find #"/Prev|/XRefStm" tail)))
          (let [table (subs tail 4 ti)
                lines (->> (str/split-lines table) (map str/trim) (remove str/blank?))
                ;; sections "start count" then count entries "oooooooooo ggggg n|f"
                entries (loop [ls lines out []]
                          (if (empty? ls)
                            out
                            (let [[a b] (str/split (first ls) #"\s+")
                                  start (parse-long a) n (parse-long b)]
                              (when (and start n)
                                (let [es (take n (rest ls))]
                                  (recur (drop (inc n) ls)
                                         (into out (map-indexed (fn [i e] (let [[o g t] (str/split e #"\s+")]
                                                                            [(+ start i) (parse-long o) (parse-long g) t]))
                                                                es))))))))
                used (when entries (filter #(= "n" (nth % 3)) entries))]
            (when (and (seq used)
                       ;; every entry must point at ITS object: a table that lies (a repaired file) is not rewritten
                       (every? (fn [[n o g _]]
                                 (and o (< o sx)
                                      (re-find (re-pattern (str "^" n "[ \\t\\r\\n]+" g "[ \\t\\r\\n]+obj")) (subs s o (min (count s) (+ o 40))))))
                               used))
              (let [by-off (sort-by second used)
                    offs (conj (vec (map second by-off)) sx)
                    head (subs s 0 (second (first by-off)))
                    ext (map (fn [[n o g _] nxt] [n g (subs s o nxt)]) by-off (rest offs))
                    rev (reverse ext)
                    size (inc (apply max (map first entries)))
                    sb (StringBuilder. ^String head)
                    pos (atom {})
                    _ (doseq [[n g text] rev] (swap! pos assoc n [(.length sb) g]) (.append sb ^String text))
                    xr (.length sb)
                    trailer-dict (let [t (subs tail (+ ti (count "trailer")))
                                       e (str/index-of t "startxref")]
                                   (str/trim (subs t 0 e)))]
                (.append sb (str "xref\n0 " size "\n"))
                (doseq [i (range size)]
                  (if-let [[o g] (@pos i)]
                    (.append sb (format "%010d %05d n \n" o g))
                    (.append sb (if (zero? i) "0000000000 65535 f \n" "0000000000 00000 f \n"))))
                (.append sb (str "trailer\n" trailer-dict "\nstartxref\n" xr "\n%%EOF\n"))
                (bytes-of (str sb))))))))))
