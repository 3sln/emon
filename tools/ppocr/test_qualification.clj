#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[ppocr.qualification :as q]
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

(let [result (run-tests)]
  (when (pos? (+ (:fail result) (:error result))) (System/exit 1)))
