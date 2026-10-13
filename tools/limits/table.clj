;; THE TABLE OF LIMITS, READ FROM ITS SOURCE (P10.4, D1100): `kin/limits.kin` holds the names, defaults, ranges and `on-hit` of every limit as `defdata`
;; (`lm-chars`, `lm-rows`). `bb limits` and `bb embedders` read it here, the one place that knows how the file is laid out, so a name or a default is
;; never typed in a tool. (No compiler is run: the data is text.)
(ns limits.table
  (:require [clojure.string :as str]))

(defn- data-of
  "The vector after `:data` in `(defdata ^:pub NAME ...)`, comments removed."
  [text name]
  (let [i (or (str/index-of text (str "(defdata ^:pub " name "\n")) (throw (ex-info (str "no defdata " name) {})))
        j (str/index-of text ":data [" i)
        start (+ j 6)
        end (loop [k (inc start) depth 1]
              (let [c (.charAt ^String text k)]
                (cond (= c \[) (recur (inc k) (inc depth))
                      (= c \]) (if (= depth 1) (inc k) (recur (inc k) (dec depth)))
                      :else (recur (inc k) depth))))
        body (subs text start end)]
    (read-string (str/join "\n" (map #(first (str/split % #";;" 2)) (str/split-lines body))))))

(def on-hit-words {0 "breach" 1 "budget" 2 "reserved"})

(defn read-limits
  "[{:kind :name :env :default :lo :hi :on-hit :on-hit-word}] in kind order, from kin/limits.kin."
  ([] (read-limits "kin/limits.kin"))
  ([file]
   (let [text (slurp file)
         chars (data-of text "lm-chars")
         rows (partition 6 (data-of text "lm-rows"))]
     (vec (for [[kind [dflt lo hi off len hit]] (map-indexed vector rows)
                :let [nm (apply str (map char (subvec chars off (+ off len))))]]
            {:kind kind :name nm :env (str "EMON_LIMIT_" (str/replace (str/upper-case nm) "-" "_"))
             :default dflt :lo lo :hi hi :on-hit hit :on-hit-word (on-hit-words hit)})))))

(defn read-options
  "The numeric options of kin/config.kin that bound something, [{:name :default :lo :hi}]: the key table's range and `cf-defaults`' value."
  []
  (let [text (slurp "kin/config.kin")
        chars (data-of text "ck-chars")
        rows (partition 5 (data-of text "ck-rows"))
        field {"asset-dpi" "dpi" "asset-pixels" "pixels" "asset-bytes" "abyte" "assets-bytes" "tbyte"
               "max-rounds" "maxr" "fetch-rounds" "fetchr" "chunk" "chunk"}]
    (vec (for [[shape lo hi off len] rows
               :let [nm (apply str (map char (subvec chars off (+ off len))))]
               :when (and (= shape 1) (field nm))
               :let [m (re-find (re-pattern (str "\\(set \\(fd c " (field nm) "\\) (\\d+)\\)")) text)]]
           {:name nm :default (some-> m second parse-long) :lo lo :hi hi}))))
