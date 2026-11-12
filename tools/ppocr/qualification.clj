;; Qualification paths are relative to the artifact. Historical OP1b/OP1c
;; manifests are resolved under the restored qualification root first.
(ns ppocr.qualification
  (:require [babashka.fs :as fs] [clojure.edn :as edn] [clojure.string :as str] [clojure.java.io :as io])
  (:import [java.security MessageDigest] [java.util.zip GZIPInputStream GZIPOutputStream]))

(defn file-sha256 [file]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (MessageDigest/getInstance "SHA-256") (fs/read-all-bytes file)))))

(defn gzip-sha256 [file]
  (with-open [in (GZIPInputStream. (io/input-stream (str file)))]
    (let [digest (MessageDigest/getInstance "SHA-256") buffer (byte-array 65536)]
      (loop [] (let [n (.read in buffer)] (when (pos? n) (.update digest buffer 0 n) (recur))))
      (apply str (map #(format "%02x" (bit-and % 255)) (.digest digest))))))

(defn compress-bundle! [file expected-sha]
  ;; Publish the gzip atomically, verify its decompressed bytes, then unlink
  ;; the raw capture. A failed compression/checksum always preserves the raw.
  (let [file (str file) compressed (str file ".gz")]
    (when (fs/regular-file? file)
      (when-not (fs/exists? compressed)
        (let [temporary (str (fs/create-temp-file {:dir (str (fs/parent file)) :prefix ".bundle-" :suffix ".gz"}))]
          (try
            (with-open [in (io/input-stream file) out (GZIPOutputStream. (io/output-stream temporary))]
              (io/copy in out))
            (assert (= expected-sha (gzip-sha256 temporary)) "compressed bundle checksum differs")
            (fs/move temporary compressed)
            (finally (fs/delete-if-exists temporary)))))
      (assert (= expected-sha (gzip-sha256 compressed)) "compressed bundle checksum differs")
      (fs/delete file))
    compressed))

(defn bitmap-file [root row]
  (let [old (:file row)
        suffix (second (re-find #"/(?:op1b|op1c)/(.*)$" old))
        candidates (if (fs/absolute? old)
                     [(when suffix (fs/path root suffix)) old]
                     [(fs/path root old)])]
    (let [file (or (some #(when (and % (fs/regular-file? %)) %) candidates)
                   (throw (ex-info "qualification bitmap missing" {:id (:id row) :file old})))]
      (when-let [expected (:sha256 row)]
        (let [actual (file-sha256 file)]
          (assert (= expected actual) (str "qualification bitmap changed: " (:id row)))))
      (str file))))

(defn reference-lines [root]
  (let [provenance (edn/read-string (slurp (str root "/PROVENANCE.edn")))
        file (str root "/reference.answers")]
    (assert (true? (:transport-equality provenance)) "native transport qualification is incomplete")
    (assert (= (:reference-sha256 provenance) (file-sha256 file)) "OIL reference checksum differs from provenance")
    (let [lines (str/split-lines (slurp file))]
      (assert (= 225 (count lines)) "qualification reference needs exactly 225 answers")
      lines)))

(defn successful-renders [transcript]
  (count (filter #(and (= :render (:kind %)) (= 0 (get-in % [:ans 3]))) (:entries transcript))))

(defn check-reference-renders [historical recorded]
  ;; A renderer-less reference silently records unsupported render answers.
  ;; The selected controls must still exercise successful render fulfilment.
  (when (pos? (successful-renders historical))
    (assert (pos? (successful-renders recorded)) "new reference lost successful render fulfilment")))

(defn oil-answer [answer]
  ;; Qualification compares complete canonical native envelopes, including
  ;; capability-strided rows and sealed sidecars. No retired-record reader.
  (assert (and (vector? answer) (>= (count answer) 28)
               (every? #(and (integer? %) (<= Integer/MIN_VALUE % Integer/MAX_VALUE)) answer)
               (= (count answer) (answer 0)) (= 3 (answer 1))
               (= 0 (answer 3)) (= 1 (answer 4)) (= 2 (answer 5)))
          "expected a successful PP OIL-v1 answer envelope")
  answer)

(defn answer-difference [reference candidate]
  (oil-answer reference)
  (oil-answer candidate)
  {:equal (= reference candidate)
   :reference-cells (count reference) :candidate-cells (count candidate)
   :header-equal (= (subvec reference 4 28) (subvec candidate 4 28))
   :first-different-cell (or (first (keep-indexed (fn [i [a b]] (when (not= a b) i))
                                                 (map vector reference candidate)))
                             (when (not= (count reference) (count candidate))
                               (min (count reference) (count candidate))))})
