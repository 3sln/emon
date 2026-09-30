(ns nfc
  "Unicode NFC (UAX #15) for the tooling ports.  babashka does not ship java.text.Normalizer, so this
  implements the algorithm over the UCD data emon already vendors: the tables in kin/nftable.kin
  (generated from test/normalize/ by kin/scripts/ucd.py; canonical data only).  `normalize` is
  Python's unicodedata.normalize('NFC', s) for every code point those tables cover."
  (:require [clojure.java.io :as io]))

(defn- read-tables []
  (let [f (or (first (filter #(.exists ^java.io.File %) [(io/file "kin/nftable.kin") (io/file "../emon/kin/nftable.kin")]))
              (throw (ex-info "nfc: kin/nftable.kin not found (run from the repository root)" {})))
        rdr (java.io.PushbackReader. (io/reader f))]
    (loop [m {}]
      (let [form (read {:eof ::eof} rdr)]
        (cond (= form ::eof) m
              (and (seq? form) (= (first form) 'defdata))
              (let [[_ nm & opts] form
                    o (apply hash-map opts)]
                (recur (assoc m (keyword (name nm)) {:stride (or (:stride o) 1) :data (:data o)})))
              :else (recur m))))))

(def ^:private tables
  (delay
    (let [t (read-tables)
          rows (fn [k] (let [{:keys [stride data]} (t k)] (partition stride data)))
          ccc (into (sorted-map) (mapcat (fn [[a b c]] (for [cp (range a (inc b))] [cp c])) (rows :nf-ccc)))
          pool (vec (:data (t :nf-pool)))
          dec (into {} (map (fn [[cp off n]] [cp (subvec pool off (+ off n))]) (rows :nf-dec)))
          comp (into {} (map (fn [[a b c]] [[a b] c]) (rows :nf-comp)))]
      {:ccc (into {} ccc) :dec dec :comp comp})))

(def ^:private S-BASE 44032) (def ^:private L-BASE 4352) (def ^:private V-BASE 4449)
(def ^:private T-BASE 4519) (def ^:private L-COUNT 19) (def ^:private V-COUNT 21)
(def ^:private T-COUNT 28) (def ^:private N-COUNT (* 21 28)) (def ^:private S-COUNT (* 19 21 28))

(defn- ccc [cp] (get (:ccc @tables) cp 0))

(defn- decompose [cps]
  (let [dec (:dec @tables)]
    (mapcat (fn [cp]
              (cond
                (and (>= cp S-BASE) (< cp (+ S-BASE S-COUNT)))
                (let [si (- cp S-BASE) tail (rem si T-COUNT)]
                  (cond-> [(+ L-BASE (quot si N-COUNT)) (+ V-BASE (quot (rem si N-COUNT) T-COUNT))]
                    (pos? tail) (conj (+ T-BASE tail))))
                :else (or (get dec cp) [cp])))
            cps)))

(defn- order [cps]
  ;; stable sort of each run of non-starters by combining class
  (loop [in (seq cps) out [] run []]
    (cond
      (nil? in) (into out (sort-by ccc run))        ; sort-by is stable
      (zero? (ccc (first in))) (recur (next in) (conj (into out (sort-by ccc run)) (first in)) [])
      :else (recur (next in) out (conj run (first in))))))

(defn- compose-pair [a b]
  (cond
    (and (<= L-BASE a (dec (+ L-BASE L-COUNT))) (<= V-BASE b (dec (+ V-BASE V-COUNT))))
    (+ S-BASE (* (+ (* (- a L-BASE) V-COUNT) (- b V-BASE)) T-COUNT))
    (and (<= S-BASE a (dec (+ S-BASE S-COUNT))) (zero? (rem (- a S-BASE) T-COUNT))
         (< T-BASE b (+ T-BASE T-COUNT)))
    (+ a (- b T-BASE))
    :else (get (:comp @tables) [a b])))

(defn- compose [cps]
  (let [buf (object-array cps)
        n (alength buf)]
    (if (< n 2)
      (vec cps)
      (let [starter (volatile! (aget buf 0))
            starter-pos (volatile! 0)
            last-class (volatile! (let [c (ccc @starter)] (if (zero? c) 0 256)))
            comp-pos (volatile! 1)]
        (loop [i 1]
          (when (< i n)
            (let [ch (aget buf i)
                  cc (ccc ch)
                  composite (compose-pair @starter ch)]
              (if (and composite (or (< @last-class cc) (zero? @last-class)))
                (do (aset buf @starter-pos composite)
                    (vreset! starter composite))
                (do (when (zero? cc)
                      (vreset! starter-pos @comp-pos)
                      (vreset! starter ch))
                    (vreset! last-class cc)
                    (aset buf @comp-pos ch)
                    (vswap! comp-pos inc))))
            (recur (inc i))))
        (vec (take @comp-pos buf))))))

(defn normalize
  "NFC of a string."
  [^String s]
  (if (every? #(< (int %) 0x300) s)
    s                                   ; nothing below U+0300 decomposes or composes
    (let [cps (vec (.toArray (.codePoints s)))]
      (apply str (map #(new String (Character/toChars %)) (compose (order (decompose cps))))))))
