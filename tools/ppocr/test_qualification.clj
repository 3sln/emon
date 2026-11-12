#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[ppocr.qualification :as q]
         '[babashka.process :refer [process]] '[cheshire.core :as json]
         '[clojure.test :refer [deftest is run-tests]])

(deftest portable-and-restored-bitmap-paths
  (let [root (fs/create-temp-dir {:prefix "emon-qualification-"})]
    (try
      (fs/create-dirs (fs/path root "live/case.replay"))
      (let [file (fs/path root "live/case.replay/bitmap.png")]
        (spit (str file) "bitmap")
        (doseq [name ["live/case.replay/bitmap.png"
                     "/old/op1b/live/case.replay/bitmap.png"
                     "/old/op1c/live/case.replay/bitmap.png"]]
          (is (= (str file) (q/bitmap-file root {:id "case" :file name}))))
        (is (thrown-with-msg? AssertionError #"qualification bitmap changed"
                             (q/bitmap-file root {:id "changed" :file "live/case.replay/bitmap.png"
                                                  :sha256 (apply str (repeat 64 "0"))})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualification bitmap missing"
                             (q/bitmap-file root {:id "missing" :file "missing.png"}))))
      (finally (fs/delete-tree root)))))

(deftest canonical-oil-answer-comparison
  (let [answer (into [28 3 2 0 1 2] (repeat 22 0))]
    (is (:equal (q/answer-difference answer answer)))
    (is (= 17 (:first-different-cell (q/answer-difference answer (assoc answer 17 1)))))
    (is (false? (:header-equal (q/answer-difference answer (assoc answer 17 1)))))
    (is (thrown-with-msg? AssertionError #"OIL-v1" (q/oil-answer [5 3 2 1 0])))
    (is (thrown-with-msg? AssertionError #"OIL-v1" (q/oil-answer (assoc answer 0 29))))
    (is (thrown-with-msg? AssertionError #"OIL-v1" (q/oil-answer (assoc answer 4 2))))
    (is (thrown-with-msg? AssertionError #"OIL-v1" (q/oil-answer (assoc answer 5 1))))))

(deftest control-reference-must-still-fulfil-render-requests
  (let [rendered {:entries [{:kind :render :ans [8 1 1 0]}]}
        unsupported {:entries [{:kind :render :ans [4 1 1 1]}]}]
    (is (= 1 (q/successful-renders rendered)))
    (is (= 0 (q/successful-renders unsupported)))
    (is (nil? (q/check-reference-renders rendered rendered)))
    (is (thrown-with-msg? AssertionError #"lost successful render"
                         (q/check-reference-renders rendered unsupported)))
    (is (nil? (q/check-reference-renders {:entries []} {:entries []})))))

(deftest bundle-compression-verifies-before-removing-the-raw-capture
  (let [root (fs/create-temp-dir {:prefix "emon-bundle-compression-"}) file (str root "/case.bundle")]
    (try
      (spit file (apply str (repeat 1000 "qualification bundle\n")))
      (let [digest (q/file-sha256 file) compressed (q/compress-bundle! file digest)]
        (is (not (fs/exists? file)))
        (is (= digest (q/gzip-sha256 compressed)))
        (spit file "preserve this raw capture")
        (is (thrown-with-msg? AssertionError #"checksum differs"
                             (q/compress-bundle! file (q/file-sha256 file))))
        (is (= "preserve this raw capture" (slurp file))))
      (finally (fs/delete-tree root)))))

(deftest web-comparison-rejects-cell-and-serialization-drift
  (let [root (fs/create-temp-dir {:prefix "emon-web-qualification-"})
        answer (into [28 3 2 0 1 2] (repeat 22 0))
        line (json/generate-string answer)]
    (try
      (spit (str root "/bitmap.png") "path-resolution-fixture")
      (spit (str root "/bitmaps.json")
            (json/generate-string (mapv #(hash-map :id (str %) :file "bitmap.png") (range 225))))
      (spit (str root "/fresh-bitmaps.json") "[]")
      (spit (str root "/reference.answers") (apply str (repeat 225 (str line "\n"))))
      (spit (str root "/PROVENANCE.edn")
            (pr-str {:transport-equality true :reference-sha256 (q/file-sha256 (str root "/reference.answers"))}))
      (doseq [[candidate succeeds?] [[line true]
                                    [(json/generate-string (assoc answer 17 1)) false]
                                    [(str " " line) false]]]
        (spit (str root "/web.jsonl") (str candidate "\n"))
        (let [result @(process ["bb" "tools/ppocr/web_qualify.clj" "--qualification" (str root)
                               "--out" (str root) "--limit" "1" "--compare-only" "true"]
                              {:out :string :err :string :shutdown nil})]
          (is (= succeeds? (zero? (:exit result))) (:err result))
          (when-not succeeds?
            (is (re-find #"answer bytes differ" (:err result))))))
      (is (= 225 (count (q/reference-lines root))))
      (spit (str root "/reference.answers") "[]\n")
      (is (thrown-with-msg? AssertionError #"checksum differs" (q/reference-lines root)))
      (spit (str root "/PROVENANCE.edn") "{:transport-equality false}")
      (is (thrown-with-msg? AssertionError #"qualification is incomplete" (q/reference-lines root)))
      (finally (fs/delete-tree root)))))

(let [result (run-tests)]
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
