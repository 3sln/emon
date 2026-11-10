#!/usr/bin/env bb
;; Serial native/web qualification. Never fetches assets or changes policy.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str])
(def args (apply hash-map *command-line-args*))
(assert (every? #{"--out" "--limit" "--compare-only" "--candidate"} (keys args)) "unknown option")
(def out (get args "--out" "/Volumes/SSK SSD/emon-scratch/op2b/qualification"))
(def qual "/Volumes/SSK SSD/emon-scratch/op1c")
(defn space! []
  (doseq [p ["/System/Volumes/Data" "/Volumes/SSK SSD"]]
    (assert (> (.getUsableSpace (java.io.File. p)) (* 10 1024 1024 1024)) (str "disk below 10 GiB: " p))))
(space!)
(fs/create-dirs out)
(defn run! [argv opts]
  (space!)
  (let [r @(process argv (merge {:shutdown nil :extra-env {"EMON_CS_SHARED_COMPILATION" "false" "TESSDATA_PREFIX" (str (fs/absolutize ".cache/tessdata"))}} opts))]
    (assert (= 0 (:exit r)) (str "failed: " argv))))
(def rows (vec (concat (json/parse-string (slurp (str qual "/bitmaps.json")) true)
                       (json/parse-string (slurp (str qual "/fresh-bitmaps.json")) true))))
(assert (= 225 (count rows)))
(def rows (mapv (fn [row]
                 (let [file (if (fs/exists? (:file row)) (:file row)
                              (str/replace (:file row) "/op1b/" "/op1c/"))]
                   (assert (fs/exists? file) (str "qualification bitmap missing: " (:id row)))
                   (assoc row :file file))) rows))
(def rows (if-let [limit (get args "--limit")] (subvec rows 0 (parse-long limit)) rows))
(spit (str out "/manifest.json") (json/generate-string rows))
(def probe ".cache/emon-rs-ppocr/target/release/ppocr-hosts")
(def fixtures (mapv #(str out "/" % ".bin") (range (count rows))))
(when-not (= "true" (get args "--compare-only"))
  (doseq [[row fixture] (map vector rows fixtures)]
    (run! [probe "prepare" (:file row) fixture] {:out :string :err :inherit}))
  (try
    (run! [probe] {:in (str (str/join "\n" fixtures) "\n") :out (str out "/native.jsonl") :err (str out "/native.log")})
    (finally (doseq [f fixtures] (fs/delete-if-exists f))))
  (fs/delete-if-exists (str out "/web.jsonl"))
  (run! ["node" "--experimental-wasm-exnref" "tools/ppocr/web_probe.mjs" out]
        {:out (str out "/web.log") :err :out}))
(defn answers [name] (mapv #(vec (json/parse-string %)) (str/split-lines (slurp (str out "/" name ".jsonl")))))
(def native (answers "native"))
(def candidate (get args "--candidate" "web"))
(assert (#{"web" "browser"} candidate))
(def web (answers candidate))
(assert (= (count rows) (count native) (count web)))
(def historical (into {} (map (juxt :id :sha256)
                             (concat (json/parse-string (slurp (str qual "/repeats/summary.json")) true)
                                     (json/parse-string (slurp (str qual "/fresh-repeats/summary.json")) true)))))
(doseq [[row line] (map vector rows (str/split-lines (slurp (str out "/native.jsonl"))))]
  (let [hash (apply str (map #(format "%02x" (bit-and % 255))
                           (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes line "UTF-8"))))]
    (assert (= hash (historical (:id row))) (str "historical native answer changed: " (:id row)))))
(defn words [answer]
  (loop [i 5 remaining (answer 4) result []]
    (if (zero? remaining) result
      (let [n (answer (+ i 7))]
        (recur (+ i 8 n) (dec remaining)
               (conj result {:box (subvec answer i (+ i 4)) :confidence (answer (+ i 4))
                             :line (answer (+ i 5)) :block (answer (+ i 6))
                             :text (subvec answer (+ i 8) (+ i 8 n))}))))))
(defn extension [answer]
  (let [at (reduce + 5 (map #(+ 8 (count (:text %))) (words answer)))
        line-count (answer (+ at 9)) block-count (answer (+ at 10))
        lines (+ at 14) blocks (+ lines (* 14 line-count)) attrs (+ blocks (* 7 block-count))]
    (assert (= (count answer) (+ attrs (* 5 (answer 4)))) "PP answers have no symbols")
    {:extension-header (subvec answer at (+ at 9))
     :lines (vec (partition 14 (subvec answer lines blocks)))
     :blocks (vec (partition 7 (subvec answer blocks attrs)))
     :word-attributes (vec (partition 5 (subvec answer attrs)))}))
(def differences
  (mapv (fn [row a b]
          (let [wa (words a) wb (words b)]
            {:id (:id row) :equal (= a b) :native-words (count wa) :web-words (count wb)
             :fields (vec (concat
                           (when (not= (count wa) (count wb)) [:word-count])
                           (for [field [:box :confidence :line :block :text]
                                 :when (not= (mapv field wa) (mapv field wb))] field)
                           (for [field [:extension-header :lines :blocks :word-attributes]
                                 :when (not= (field (extension a)) (field (extension b)))] field)))
             :word-differences (into {} (for [field [:box :confidence :line :block :text]]
                                         [field (+ (abs (- (count wa) (count wb)))
                                                   (count (remove true? (map #(= (field %1) (field %2)) wa wb))))]))
             :different-cells (when (= (count a) (count b)) (count (filter false? (map = a b))))}))
        rows native web))
(spit (str out "/" candidate "-differences.json") (json/generate-string differences {:pretty true}))
(def summary {:answers (count rows) :equal (count (filter :equal differences))
              :different (count (remove :equal differences))
              :answers-by-field (frequencies (mapcat :fields differences))})
(spit (str out "/" candidate "-summary.json") (json/generate-string summary {:pretty true}))
(println (json/generate-string summary))
