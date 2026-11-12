#!/usr/bin/env bb
;; D883: source policy is closed by the registry, with executable fallback laws.
(require '[babashka.fs :as fs] '[clojure.edn :as edn] '[clojure.string :as str])

(def consumers
  {[1 3] {:site ['emon.scriptcheck 'sk-unread] :subject 3 :fallback 1
          :law ['emon.scriptchecktest 'test-source-model-overrides-language-and-absence-keeps-the-fallback]}
   [2 2] {:site ['emon.upscale 'up-turns] :subject 2 :fallback 2
          :law ['emon.upscaletest 'test-crop-reversal-precedes-enlargement-and-absence-keeps-page-fallback]}})
(def guard-form '(if (== (oil-key-usable answer at ns key) 0) (return -1)))
(def source-site ['emon.oilsrc 'oxs-policy-value])
(def relevant
  (re-pattern (str "(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %)
                                  (concat ["oxs-get" "oxs-policy-value" "oil-source-value" "oil-keys"]
                                          (map #(name (second (:law %))) (vals consumers))))) ")")))
(defn forms [path] (edn/read-string (str "[" (slurp (str path)) "]")))
(defn calls [form name]
  (filter #(and (seq? %) (symbol? (first %)) (= (clojure.core/name name) (clojure.core/name (first %))))
          (tree-seq coll? seq form)))
(defn snapshot []
  (reduce
    (fn [state file]
      ;; Large font/encoding data files contain no source-policy code.
      (if-not (re-find relevant (slurp (str file))) state
      (let [all (forms file) ns-name (second (first (filter #(= 'ns (first %)) all)))]
        (reduce (fn [s form]
                  (case (first form)
                    defn (assoc-in s [:functions [ns-name (second form)]] form)
                    deftest (update s :laws conj [ns-name (second form)])
                    defdata (if (= 'oil-keys (second form))
                              (assoc s :registry (mapv vec (partition 8 (:data (apply hash-map (drop 2 form)))))) s)
                    s)) state all))))
    {:functions {} :laws #{} :registry []} (sort (fs/glob "kin" "*.kin"))))
(defn problems [{:keys [functions laws registry]}]
  (let [registered (set (for [row registry :when (= 1 (row 6))] (subvec row 0 2)))
        actual (for [[site form] functions
                     :when (not (str/ends-with? (str (first site)) "test"))
                     call (calls form 'oxs-policy-value)]
                 {:site site :key [(nth call 6 nil) (nth call 7 nil)] :subject (nth call 4 nil)})]
    (vec
      (concat
        (when (not= registered (set (keys consumers))) ["policy registry and consumer declarations differ"])
        (for [[key declared] consumers
              :let [row (first (filter #(= key (subvec % 0 2)) registry))]
              :when (and row (or (not= (:subject declared) (row 4)) (not= (:fallback declared) (row 7))))]
          (str "consumer subject/fallback differs from registry " key))
        (for [[key {:keys [site law]}] consumers
              :when (not (some #(and (= site (:site %)) (= key (:key %))) actual))]
          (str "missing consumer " key " " site))
        (for [[key {:keys [law]}] consumers :when (not (contains? laws law))]
          (str "missing present/absent law " key " " law))
        (for [{:keys [site key subject]} actual
              :let [declared (get consumers key)]
              :when (or (nil? declared) (not= site (:site declared)) (not= subject (:subject declared)))]
          (str "unregistered policy call " site " " key " subject " subject))
        (when (not (some #{guard-form} (get functions source-site))) ["source policy guard missing"])
        (for [[site form] functions
              :when (not (str/ends-with? (str (first site)) "test"))
              call (calls form 'oxs-get) :when (not= source-site site)]
          (str "unguarded direct source read " site))
        (for [[site form] functions
              :when (and (not (str/ends-with? (str (first site)) "test"))
                         (not (#{'emon.oil 'emon.oilsrc} (first site))))
              name ['oil-source-value 'oil-source-value-from]
              call (calls form name)]
          (str "wire source policy bypass " site))))))
(def state (snapshot))
(def failures (problems state))
(doseq [failure failures] (println "FAIL" failure))
(assert (empty? failures) "OIL source-consumer contract")

;; Plant violations so a vacuous scanner cannot pass.
(let [unlisted (assoc-in state [:functions ['emon.upscale 'unlisted]]
                         '(defn unlisted [] (oxs-policy-value a 0 0 3 0 1 3 s)))
      direct (assoc-in state [:functions ['emon.upscale 'unlisted]]
                       '(defn unlisted [] (oxs-get s 0 3 0 1 3)))
      unguarded (update-in state [:functions source-site] #(apply list (remove #{guard-form} %)))
      missing-law (update state :laws disj (:law (get consumers [1 3])))
      wrong-registry (update state :registry #(mapv (fn [row] (if (= [1 3] (subvec row 0 2)) (assoc row 6 0) row)) %))
      bypass (assoc-in state [:functions ['emon.upscale 'unlisted]]
                       '(defn unlisted [] (oil-source-value a 0 3 0 1 3)))
      qualified (assoc-in state [:functions ['emon.upscale 'unlisted]]
                          '(defn unlisted [] (source/oxs-get s 0 3 0 1 3)))
      wrong-fallback (update state :registry #(mapv (fn [row] (if (= [1 3] (subvec row 0 2)) (assoc row 7 2) row)) %))]
  (doseq [planted [unlisted direct unguarded missing-law wrong-registry bypass qualified wrong-fallback]]
    (assert (seq (problems planted)) "source audit missed a planted violation")))
(println "  ok   OIL: two registered policy consumers, present/absent laws and registry fallbacks, guarded source reads; eight planted violations caught")
