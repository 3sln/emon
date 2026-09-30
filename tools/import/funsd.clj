(ns import.funsd
  "FUNSD -> emon gold (DEVLOG P2.11).   bb import funsd [--limit N]

  Native ground truth: annotations/<id>.json, {\"form\": [entity...]}; an entity is
  {id, box [x0,y0,x1,y1] in IMAGE PIXELS (origin top-left, y down), text, label
  question|answer|header|other, words [{box,text}], linking [[a,b],...]}.  The images
  are PNG, no resolution.  Coordinates need NO transform (already top-left pixels);
  the page is the image at a nominal 1 px = 1 pt (tools/import/common.clj).

  MAPPING (decisions, D0xx):
    header entity                 -> heading   (no level: FUNSD has none)
    question entity               -> field     name = its text, value = the texts of the
                                     answer entities linked to it (joined by a space, top-to-bottom),
                                     `at` = the union of the question and its answers
    answer entity not linked to a question, other entity
                                  -> paragraph (not claimed)
    entities with empty text are dropped (FUNSD has a few empty boxes).

  CLAIMS: `field` and `heading` presence and absence; field name/value/at; heading text/at.
  NOT claimed: paragraphs, reading order (body order is a top-to-bottom sort), heading levels.
  Known noise: FUNSD's own labels and links contain errors and are a noisy-scan benchmark.

  `form-gold` is shared with xfund.clj (XFUND uses the same entity format)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [pyjson :as pj :refer [om]]))

(defn form-gold [gid entities page fmt lang provenance]
  (let [by (into {} (map (juxt #(get % "id") identity) entities))
        links (reduce
               (fn [links e]
                 (reduce
                  (fn [links [a b]]
                    (reduce (fn [links [x y]]
                              (if (and (contains? by x) (contains? by y)
                                       (= "question" (get (by x) "label")) (= "answer" (get (by y) "label")))
                                (update links x (fn [v] (if (some #{y} v) v (conj (or v []) y))))
                                links))
                            links [[a b] [b a]]))
                  links (or (get e "linking") [])))
               {} entities)
        used (set (mapcat val links))
        reg (fn [& es]
              (let [[x0 y0 x1 y1] (C/union (map #(get % "box") es))] (C/region 1 x0 y0 x1 y1)))
        keyed
        (vec
         (for [e entities
               :let [text (C/norm (get e "text"))]
               :when (seq text)
               :let [box (get e "box")
                     k [(nth box 1) (nth box 0)]
                     lab (get e "label")]
               :let [n (cond
                         (= lab "header") (C/node "heading" {:at (reg e) :text text})
                         (= lab "question")
                         (let [ans (sort-by (fn [a] [(nth (get a "box") 1) (nth (get a "box") 0)])
                                            (for [y (get links (get e "id"))
                                                  :let [a (by y)] :when (seq (C/norm (get a "text")))] a))
                               f (C/node "field" {:at (apply reg e ans) :fields (om "name" text)})]
                           (if (seq ans)
                             (C/add-field f "value" (C/norm (str/join " " (map #(get % "text") ans))))
                             f))
                         (and (= lab "answer") (contains? used (get e "id"))) nil
                         :else (C/node "paragraph" {:at (reg e) :text text}))]
               :when n]
           [k n]))
        ann (C/annotated {:structure ["heading" "field"]
                          :attributes (om "heading" ["text" "at"] "field" ["name" "value" "at"])
                          :pages ["n" "px" "kind"]})]
    (C/gold gid ann [page] (map second (sort-by first keyed)) fmt :lang lang :provenance provenance)))

(defn items [corpus st]
  (let [base (str corpus "/forms/funsd/dataset")]
    (for [split ["training_data" "testing_data"]
          :let [d (str base "/" split "/annotations")]
          fname (sort (map #(str (fs/file-name %)) (fs/list-dir d)))
          :when (str/ends-with? fname ".json")
          :let [stem (subs fname 0 (- (count fname) 5))
                img (str base "/" split "/images/" stem ".png")
                ann (get (pj/parse-file (str d "/" fname)) "form")
                [page fmt] (C/raster-page img)]]
      (do
        (doseq [e ann
                :let [[x0 y0 x1 y1] (get e "box")]]
          (when (or (> x1 (get page "w")) (> y1 (get page "h")) (< x0 0) (< y0 0))
            (C/warn! st "box outside the image")))
        (let [prov (om "producer" (format "FUNSD (Jaume et al. 2019), human-annotated; annotations/%s.json" stem)
                       "variants" (om "image" (C/rel img)))]
          [(str "funsd-" stem) (form-gold (str "funsd-" stem) ann page fmt "en" prov)])))))

(defn import! [opts] (C/run "funsd" items opts))
