#!/usr/bin/env bb
;; Read only protobuf wire framing; no ONNX package or inference dependency.
(require '[babashka.fs :as fs] '[clojure.string :as str])
(import '[java.util Arrays])
(defn varint [data start]
  (loop [pos start shift 0 value 0]
    (assert (and (< pos (alength data)) (< shift 70)) "malformed protobuf varint")
    (let [b (bit-and 255 (aget data pos)) value (bit-or value (bit-shift-left (bit-and b 127) shift))]
      (if (< b 128) [value (inc pos)] (recur (inc pos) (+ shift 7) value)))))
(defn fields [data]
  (loop [pos 0 out []]
    (if (= pos (alength data)) out
      (let [[tag pos] (varint data pos) number (bit-shift-right tag 3) wire (bit-and tag 7)
            [value end] (if (= wire 0) (varint data pos)
                         (let [[n begin] (case wire 1 [8 pos] 5 [4 pos] 2 (varint data pos)
                                         (throw (ex-info "unsupported protobuf wire" {:wire wire})))
                               end (+ begin n)]
                           (assert (<= end (alength data)) "truncated protobuf field")
                           [(Arrays/copyOfRange data (int begin) (int end)) end]))]
        (recur end (conj out [number value]))))))
(def metadata (for [[n value] (fields (fs/read-all-bytes ".cache/ppocr/en_PP-OCRv5_rec_mobile.onnx"))
                    :when (= n 14) :let [entry (into {} (fields value))]
                    :when (= "character" (String. (get entry 1) "UTF-8"))] (get entry 2)))
(assert (= 1 (count metadata)) "exactly one character metadata entry")
(assert (Arrays/equals ^bytes (first metadata) ^bytes (fs/read-all-bytes "tools/ppocr/en-dictionary.txt")))
(assert (= 438 (+ 2 (count (str/split-lines (String. (first metadata) "UTF-8"))))))
(assert (= "0\n180\n" (slurp "tools/ppocr/orientation-labels.txt")))
(println "verified embedded English dictionary and classifier class order")
