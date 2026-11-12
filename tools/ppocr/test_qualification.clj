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
        answer (assoc (into [28 3 2 0 1 2] (repeat 22 0)) 8 24928)
        policy "fixture frozen PP policy"
        [hi lo] (q/observed-hash (str policy " runtime=" q/web-runtime))
        web (assoc answer 6 hi 7 lo)
        line (json/generate-string answer)]
    (try
      (spit (str root "/bitmap.png") "path-resolution-fixture")
      (spit (str root "/bitmaps.json")
            (json/generate-string (mapv #(hash-map :id (str %) :file "bitmap.png") (range 225))))
      (spit (str root "/fresh-bitmaps.json") "[]")
      (spit (str root "/reference.answers") (apply str (repeat 225 (str line "\n"))))
      (spit (str root "/PROVENANCE.edn")
            (pr-str {:transport-equality true :policy-identity policy
                     :reference-sha256 (q/file-sha256 (str root "/reference.answers"))}))
      (doseq [[candidate repeat-line succeeds?]
              [[(json/generate-string web) (json/generate-string web) true]
               [(json/generate-string (assoc web 17 1)) (json/generate-string (assoc web 17 1)) false]
               [(str " " (json/generate-string web)) (str " " (json/generate-string web)) false]
               [(json/generate-string web) (str " " (json/generate-string web)) false]
               [(json/generate-string web) line false]]]
        (spit (str root "/web.jsonl") (str candidate "\n"))
        (spit (str root "/web-repeat.jsonl") (str repeat-line "\n"))
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

(deftest web-tolerance-is-restricted-to-registered-score-cells
  (let [header (assoc (into [0 3 2 0 1 2] (repeat 22 0))
                      8 24928 23 1 24 1 25 1)
        line [0 0 10 10 -1 1 0 0 10 0 10 10 0 10 0]
        word [0 0 10 10 0 900 1701734144 1 0 1 65]
        sidecar [8 2 1 2 0 1 0 800
                 9 2 2 2 0 1 0 1 900]
        a (vec (concat header line word sidecar))
        a (assoc a 0 (count a) 25 2)
        hash (q/observed-hash "test runtime")
        b (assoc a 6 (first hash) 7 (second hash))]
    (is (:accepted (q/web-answer-difference a b hash)))
    (doseq [cell [48 61] delta [-1 1]]
      (is (:accepted (q/web-answer-difference a (update b cell + delta) hash))))
    (doseq [cell (remove #{6 7 48 61} (range (count a)))]
      (is (try (not (:accepted (q/web-answer-difference a (update b cell inc) hash)))
               (catch Exception _ true) (catch AssertionError _ true))))
    (doseq [cell [48 61]]
      (is (not (:accepted (q/web-answer-difference a (update b cell + 2) hash)))))
    (is (not (:accepted (q/web-answer-difference a b [0 0]))))))

(let [result (run-tests)]
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
