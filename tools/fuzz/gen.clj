;; THE CASE GENERATOR of `bb fuzz` (DEVLOG P10.5, D384): case i of seed S is a pure function of (S, i, the corpus).
;; It picks one corpus item (a committed fixture, a generated seed or an image) with the case's own SplitMix64,
;; and applies one to three mutations: object-level ones (`fuzz.pdfmut`, on a parsed model written back with a
;; correct cross-reference table), byte-level ones (`fuzz.mutate`: truncation, flips, splices, xref entries,
;; /Length, object headers, stream boundaries, trailer numbers), or, for an image, the format's own
;; (`fuzz.imgmut`). A case that mixes the families converts between a model and bytes as it goes.
(ns fuzz.gen
  (:require [babashka.fs :as fs]
            [fuzz.seeds :as seeds]
            [fuzz.mutate :as m]
            [fuzz.pdfobj :as po]
            [fuzz.pdfmut :as pm]
            [fuzz.imgmut :as im]))

(defn item-bytes ^bytes [item]
  (let [b (:bytes item)] (if (delay? b) @b b)))

(defn mutate-pdf
  "[bytes [descriptions]]: 1..3 mutations of a PDF, object-level 3 times in 5."
  [r ^bytes bs]
  (loop [k (m/between r 1 3) state {:bytes bs} descs []]
    (if (zero? k)
      [(if (:model state) (po/emit (:model state)) (:bytes state)) descs]
      (let [model-op? (< (m/below r 5) 3)]
        (if model-op?
          (let [model (or (:model state) (try (po/parse (:bytes state)) (catch Exception _ nil)))
                res (when model (pm/apply-one r model))]
            (if res
              (recur (dec k) {:model (first res)} (conj descs (second res)))
              ;; nothing to change at object level (or not parseable): a byte-level one instead
              (let [bs0 (if (:model state) (po/emit (:model state)) (:bytes state))
                    f (m/pick r m/mutators)
                    [bs' d] (or (f r bs0) (m/m-flip r bs0))]
                (recur (dec k) {:bytes bs'} (conj descs d)))))
          (let [bs0 (if (:model state) (po/emit (:model state)) (:bytes state))
                f (m/pick r m/mutators)
                [bs' d] (or (f r bs0) (m/m-flip r bs0))]
            (recur (dec k) {:bytes bs'} (conj descs d))))))))

(defn mutate-image
  "[bytes [descriptions]]: 1..3 mutations of a PNG, JPEG or TIFF: the format's own, or blind ones."
  [r ^bytes bs]
  (loop [k (m/between r 1 3) bs bs descs []]
    (if (zero? k)
      [bs descs]
      (let [[bs' d] (or (when (< (m/below r 10) 7) (try (im/structured r bs) (catch Exception _ nil)))
                        (let [f (m/pick r [m/m-truncate m/m-flip m/m-flip m/m-splice])] (f r bs)))]
        (recur (dec k) bs' (conj descs d))))))

(defn gen-case
  "Case i of a seed: {:i :fixture :kind :ops :bytes}. A pure function of (seed, i, corpus)."
  [seed i corpus]
  (let [r (m/rng (m/sm-at seed i))
        item (m/pick r corpus)
        bs (item-bytes item)
        [out ops] (if (= :image (:kind item)) (mutate-image r bs) (mutate-pdf r bs))]
    {:i i :fixture (:name item) :kind (:kind item) :ops ops :bytes out}))

(defn fixture-files []
  (->> (concat (fs/glob "test/pdf" "*.pdf") (fs/glob "test/pdf/p3" "*.pdf") (fs/glob "test/pdf/tagged" "*.pdf")
               (fs/glob "test/vis" "*.pdf") (fs/glob "test/corpus/synth" "**/*.pdf"))
       (map str) sort vec))

(defn image-files []
  (->> (fs/glob "test/image" "*.{png,jpg,tif}") (map str) sort vec))

(defn load-corpus
  "Every item a case can start from, in a fixed order: [{:name :kind (:pdf :seed :image) :bytes (bytes or delay)}].
   The committed PDF fixtures, the generated seeds (fuzz.seeds) and the committed images."
  []
  (vec (concat
        (for [f (fixture-files)] {:name f :kind :pdf :bytes (fs/read-all-bytes f)})
        (for [s (seeds/seeds)] (assoc s :kind :seed))
        (for [f (image-files)] {:name f :kind :image :bytes (fs/read-all-bytes f)}))))
