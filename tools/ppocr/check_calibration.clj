#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.string :as str] '[clojure.set :as set])
(import '[java.security MessageDigest])
(def r (json/parse-string (slurp "tools/ppocr/calibration.json") true))
(def lookup (mapv parse-long (re-seq #"\d+" (second (re-find #"vec!\[([0-9,\s]+)\]" (slurp "host/rust/src/ppocr_calibration.rs"))))))
(assert (= lookup (:confidence r)))
(assert (= 10 (count lookup)))
(assert (= (:model_pins_sha256 r)
           (apply str (map #(format "%02x" (bit-and % 255)) (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes "tools/ppocr/assets.edn"))))))
(assert (= (:original_model_pins_sha256 r)
           (apply str (map #(format "%02x" (bit-and % 255))
                           (.digest (MessageDigest/getInstance "SHA-256")
                                    (.getBytes (str/replace (slurp "tools/ppocr/assets.edn") "D843" "D3100") "UTF-8"))))))
(def baseline (json/parse-string (slurp "test/receipts/baseline.json") true))
(def excluded (set (map first (mapcat :rows (vals (:sets baseline))))))
(def ids (mapv :id (:documents r)))
(assert (= 80 (count ids) (count (set ids))))
(assert (empty? (set/intersection (set ids) excluded)))
(assert (every? #(str/starts-with? % "sroie-train-") ids))
(doseq [[i {:keys [n correct]}] (map-indexed vector (get-in r [:fit :bins]))]
  (assert (<= 0 correct n))
  (assert (= (lookup i) (if (pos? n) (quot (+ (* 1000 correct) (quot n 2)) n) 0))))
(doseq [split [:fit :validation]]
  (let [rows (get-in r [split :bins]) n (reduce + (map :n rows))
        brier (/ (reduce + (for [{:keys [bin n correct]} rows :let [p (/ (lookup bin) 1000.0)]]
                            (+ (* correct (- 1 p) (- 1 p)) (* (- n correct) p p)))) n)
        ece (/ (reduce + (for [{:keys [bin n correct]} rows :when (pos? n)]
                          (* n (abs (- (/ (lookup bin) 1000.0) (/ correct n)))))) n)]
    (assert (= n (get-in r [split :words])))
    (assert (< (abs (- brier (get-in r [split :calibrated_brier]))) 1e-12))
    (assert (< (abs (- ece (get-in r [split :calibrated_ece]))) 1e-12))))
(assert (= (reduce + (map :aligned (take 40 (:documents r)))) (get-in r [:fit :words])))
(assert (= (reduce + (map :aligned (drop 40 (:documents r)))) (get-in r [:validation :words])))
(println "verified frozen confidence lookup, reliability arithmetic and disjoint calibration IDs")
