(ns accuracy.teds
  "Ordered, labelled tree edit distance (Zhang–Shasha), babashka only.
  A tree is [label children]. Deleting a node promotes its children; insertions
  and deletions cost one, and rename supplies a cost in [0,1].")

(defn- index-tree [tree]
  (let [nodes (atom [nil]) left (atom [0])]
    (letfn [(visit [[label children]]
              (let [ids (mapv visit children)
                    i (count @nodes)]
                (swap! nodes conj label)
                (swap! left conj (if (seq ids) (get @left (first ids)) i))
                i))]
      (visit tree)
      {:nodes @nodes :left @left
       :roots (sort (vals (reduce (fn [m i] (assoc m (get @left i) i))
                                 {} (range 1 (count @nodes)))))})))

(defn distance [a b rename]
  (let [{an :nodes al :left ar :roots} (index-tree a)
        {bn :nodes bl :left br :roots} (index-tree b)
        width (count bn)
        td (double-array (* (count an) width))
        at (fn [i j] (+ (* i width) j))]
    (doseq [i ar j br]
      (let [a0 (dec (al i)) b0 (dec (bl j))
            w (inc (- j b0))
            fd (double-array (* (inc (- i a0)) w))
            ix (fn [x y] (+ (* (- x a0) w) (- y b0)))]
        (doseq [x (range (inc a0) (inc i))]
          (aset-double fd (ix x b0) (double (- x a0))))
        (doseq [y (range (inc b0) (inc j))]
          (aset-double fd (ix a0 y) (double (- y b0))))
        (doseq [x (range (inc a0) (inc i)) y (range (inc b0) (inc j))]
          (let [del (inc (aget fd (ix (dec x) y)))
                ins (inc (aget fd (ix x (dec y))))]
            (if (and (= (al x) (al i)) (= (bl y) (bl j)))
              (let [v (min del ins (+ (aget fd (ix (dec x) (dec y)))
                                      (double (rename (an x) (bn y)))))]
                (aset-double fd (ix x y) v)
                (aset-double td (at x y) v))
              (aset-double fd (ix x y)
                           (min del ins (+ (aget fd (ix (dec (al x)) (dec (bl y))))
                                           (aget td (at x y))))))))))
    {:distance (aget td (at (dec (count an)) (dec (count bn))))
     :size (max (dec (count an)) (dec (count bn)))}))
