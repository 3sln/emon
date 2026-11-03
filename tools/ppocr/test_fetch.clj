#!/usr/bin/env bb
(require '[babashka.fs :as fs] '[babashka.process :refer [process]] '[clojure.string :as str])
(def bb (str (fs/which "bb")))
(def fetch-source (str (fs/absolutize "tools/ppocr/fetch.clj")))
(fs/create-dirs ".cache/ppocr-fetch-tests")
(defn contract [f]
  (let [root (fs/create-temp-dir {:dir ".cache/ppocr-fetch-tests" :prefix "fetch-"})
        dest (fs/path root ".cache/ppocr/test.bin")
        curl (fn [s] (spit (str (fs/path root "bin/curl"))
                          (str "#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do\nif [ \"$1\" = --output ]; then shift; dest=$1; fi\nshift\ndone\nprintf " s " > \"$dest\"\n"))
                    (fs/set-posix-file-permissions (fs/path root "bin/curl") "rwxr-xr-x"))
        run #(-> (process [bb "tools/ppocr/fetch.clj"]
                          {:dir (str root) :out :string :err :string :shutdown nil
                           :extra-env {"PATH" (str (fs/absolutize (fs/path root "bin")) ":" (System/getenv "PATH"))}}) deref :exit)]
    (try
      (doseq [d ["tools/ppocr" ".cache/ppocr" "bin"]] (fs/create-dirs (fs/path root d)))
      (fs/copy fetch-source (fs/path root "tools/ppocr/fetch.clj"))
      (spit (str (fs/path root "tools/ppocr/assets.edn"))
            (pr-str {:assets [{:file "test.bin" :bytes 3 :sha256 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" :url "https://invalid.example/test"}]}))
      (curl "bad")
      (f root dest curl run)
      (finally (fs/delete-tree root)))))
(contract (fn [_ dest _ run]
            (spit (str dest) "old") (assert (not= 0 (run)))
            (assert (= "old" (slurp (str dest))))
            (assert (= ["test.bin"] (mapv #(str (fs/file-name %)) (fs/list-dir (fs/parent dest)))))))
(contract (fn [_ dest curl run]
            (spit (str dest) "abc") (assert (= 0 (run)))
            (spit (str dest) "old") (curl "abc") (assert (= 0 (run)))
            (assert (= "abc" (slurp (str dest))))))
(contract (fn [root dest _ run]
            (let [target (fs/absolutize (fs/path root "outside"))]
              (spit (str target) "abc") (fs/create-sym-link dest target)
              (assert (not= 0 (run))) (assert (= "abc" (slurp (str target)))))))
(println "three offline fetch contracts passed")
