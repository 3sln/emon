;; End-to-end S3 round trip against a local MinIO.   bb corpus-test
;; Needs `minio` on PATH (brew install minio); skips loudly without it. NOT a gate (D077).
;; A temp fake corpus only -- the real corpus is never touched.
(ns corpus-roundtrip
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.data]
            [clojure.string :as str]
            [corpus :as c]))

(def fails (atom 0))
(defn check [ok? what] (println (if ok? "  ok  " "  FAIL") what) (when-not ok? (swap! fails inc)))

(defn free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(when-not (fs/which "minio")
  (println "SKIP corpus round trip: minio not on PATH (brew install minio)")
  (System/exit 0))

(def work (str (fs/create-temp-dir {:prefix "emon-s3-test-"})))
(def port (free-port))
(def creds {"EMON_S3_ACCESS_KEY_ID" "testkey" "EMON_S3_SECRET_ACCESS_KEY" "testsecret123"})
(def env-file (str (fs/path work "env")))
(spit env-file (str/join "\n" ["# test config"
                               (str "EMON_S3_ENDPOINT=http://127.0.0.1:" port)
                               "EMON_S3_REGION=us-east-1" "EMON_S3_BUCKET=emon-test" "EMON_S3_PREFIX=rt/corpus"
                               "EMON_S3_ACCESS_KEY_ID=testkey" "EMON_S3_SECRET_ACCESS_KEY=testsecret123" ""]))

(def server (p/process {:extra-env {"MINIO_ROOT_USER" "testkey" "MINIO_ROOT_PASSWORD" "testsecret123"}
                        :out (str (fs/path work "minio.log")) :err :out}
                       "minio" "server" (str (fs/path work "data")) "--address" (str "127.0.0.1:" port)))

(defn cli [corpus & args]
  (let [r (apply p/shell {:out :string :err :string :continue true
                          :extra-env {"EMON_ENV_FILE" env-file "EMON_TMP" (str (fs/path work "tmp"))}}
                 "bb" (str (fs/path c/repo-root "tools/corpus.clj")) (concat [(first args) "--corpus" corpus "--part-mb" "5"] (rest args)))]
    (assoc r :all (str (:out r) (:err r)))))

(defn tree [dir]
  (into (sorted-map)
        (for [f (file-seq (fs/file dir)) :when (and (.isFile f) (not (str/starts-with? (.getName f) ".emon-")))]
          [(str (fs/relativize dir f)) (c/file-sha256 f)])))

(try
  (let [cfg {:endpoint (str "http://127.0.0.1:" port) :region "us-east-1" :bucket "emon-test" :prefix "rt/corpus"
             :access-key "testkey" :secret-key "testsecret123" :path-style? true}]
    (loop [i 0] ; wait for the server, then make the bucket
      (let [r (try (c/s3-request (assoc cfg :prefix "") :put "" {}) (catch Exception _ nil))]
        (when-not (and r (<= 200 (:status r) 299))
          (if (< i 100) (do (Thread/sleep 200) (recur (inc i))) (throw (ex-info "minio did not come up" {}))))))
    (let [src (str (fs/path work "src")) dst (str (fs/path work "dst")) part (str (fs/path work "part"))
          rnd (java.util.Random. 7)
          rand-file (fn [f n] (fs/create-dirs (fs/parent f)) (let [b (byte-array n)] (.nextBytes rnd b) (fs/write-bytes f b)))]
      (fs/create-dirs (fs/path src "tools") (fs/path src "tables" "sub"))
      (fs/create-dirs (fs/path src "_dl"))
      (spit (str (fs/path src "MANIFEST.md")) "# fake manifest\n")
      (spit (str (fs/path src "fetch.sh")) "#!/bin/sh\necho hi\n")
      (rand-file (fs/path src "tables" "big random.bin") (* 12 1024 1024)) ; > 2 parts of 5 MiB, incompressible
      (rand-file (fs/path src "tables" "sub" "naïve+name.bin") 1000)
      (spit (str (fs/path src "tables" "sub" "t.txt")) (apply str (repeat 1000 "tabular ")))
      (rand-file (fs/path src "forms" "f1.bin") 4000)
      (spit (str (fs/path src "tools" "x.py")) "print(1)\n")
      (fs/create-dirs (fs/path src "_dl")) (spit (str (fs/path src "_dl" "junk")) "deletable")

      (println "dry-run push")
      (let [r (cli src "push" "--dry-run")]
        (check (zero? (:exit r)) "exit 0")
        (check (str/includes? (:all r) "would  zip tables") "lists tables")
        (check (nil? (c/get-string cfg "index.json")) "dry run wrote nothing"))

      (println "first push")
      (let [r (cli src "push")]
        (check (zero? (:exit r)) (str "exit 0 " (when-not (zero? (:exit r)) (:all r))))
        (check (str/includes? (:all r) "part 3") "tables went multipart")
        (check (= 4 (count (re-seq #"upload " (:all r)))) "4 archives uploaded (forms tables tools _root)")
        (check (not (str/includes? (:all r) "_dl")) "_dl excluded")
        (check (not (str/includes? (:all r) "testsecret")) "no secret printed"))
      (let [idx (cheshire.core/parse-string (c/get-string cfg "index.json"))]
        (check (= ["_root" "forms" "tables" "tools"] (map #(get % "name") (get idx "archives"))) "index names")
        (check (= 3 (get (first (filter #(= "tables" (get % "name")) (get idx "archives"))) "files")) "tables file count")
        (check (every? #(re-matches #"[0-9a-f]{64}" (get % "sha256")) (get idx "archives")) "sha256s")
        (check (and (get idx "pushed_at") (get idx "git_commit")) "time + commit recorded"))

      (println "re-push (nothing changed)")
      (let [r (cli src "push")]
        (check (zero? (:exit r)) "exit 0")
        (check (not (str/includes? (:all r) "upload ")) "nothing uploaded")
        (check (str/includes? (:all r) "index.json unchanged") "index untouched"))
      (println "re-push with lost local state (sha match against remote index)")
      (fs/delete (fs/path src ".emon-corpus-state.json"))
      (let [r (cli src "push")]
        (check (and (zero? (:exit r)) (not (str/includes? (:all r) "upload "))) "zipped, sha matched, nothing uploaded")
        (check (= 4 (count (re-seq #"remote already has" (:all r)))) "deterministic zips: all 4 skipped by sha"))

      (println "change one file")
      (spit (str (fs/path src "forms" "f1.bin")) "changed")
      (let [r (cli src "push")]
        (check (= ["upload forms.zip"] (map #(second (re-find #"(upload \S+)" %)) (filter #(str/includes? % "upload ") (str/split-lines (:all r))))) "only forms uploaded"))

      (println "pull into a fresh dir")
      (let [r (cli dst "pull" "--dry-run")]
        (check (= 4 (count (re-seq #"would  download" (:all r)))) "dry-run lists 4")
        (check (not (fs/exists? (fs/path dst "tables"))) "dry-run extracted nothing"))
      (let [r (cli dst "pull")]
        (check (zero? (:exit r)) (str "exit 0 " (when-not (zero? (:exit r)) (:all r)))))
      (let [expect (dissoc (tree src) "_dl/junk")]
        (check (= expect (tree dst)) "trees identical byte for byte (sha256 per file)")
        (when-not (= expect (tree dst)) (println "   diff:" (clojure.data/diff expect (tree dst)))))
      (check (fs/executable? (fs/path dst "fetch.sh")) "fetch.sh executable")
      (check (not-any? #(str/starts-with? (fs/file-name %) ".tmp-") (fs/list-dir dst)) "no temp dirs left")
      (let [r (cli dst "pull")]
        (check (not (str/includes? (:all r) "fetch ")) "second pull downloads nothing"))

      (println "partial pull")
      (spit (str (fs/path src "forms" "f1.bin")) "changed again")
      (cli src "push")
      (spit (str (fs/path dst "tables" "stale.txt")) "stale") ; must vanish only if tables re-pulled
      (let [r (cli dst "pull" "forms")]
        (check (= 1 (count (re-seq #"fetch  " (:all r)))) "only forms fetched")
        (check (= "changed again" (slurp (str (fs/path dst "forms" "f1.bin")))) "forms updated")
        (check (fs/exists? (fs/path dst "tables" "stale.txt")) "tables untouched"))
      (let [r (cli dst "pull" "tables" "--force")]
        (check (not (fs/exists? (fs/path dst "tables" "stale.txt"))) "forced tables pull replaced the dir"))

      (println "errors")
      (let [r (cli dst "pull" "nope")] (check (and (pos? (:exit r)) (str/includes? (:all r) "not in remote index")) "unknown category refused"))
      (let [bad (str (fs/path work "bad.env"))]
        (spit bad "EMON_S3_ENDPOINT=http://x\nEMON_S3_SECRET_ACCESS_KEY=hunter2\n")
        (let [r (p/shell {:out :string :err :string :continue true :extra-env {"EMON_ENV_FILE" bad}}
                         "bb" (str (fs/path c/repo-root "tools/corpus.clj")) "push" "--corpus" src)]
          (check (and (pos? (:exit r)) (str/includes? (:err r) "EMON_S3_REGION, EMON_S3_BUCKET, EMON_S3_ACCESS_KEY_ID")) "missing keys named")
          (check (not (str/includes? (str (:out r) (:err r)) "hunter2")) "secret not echoed")))))
  (finally
    (p/destroy-tree server)
    @(future (try (.waitFor (:proc server)) (catch Exception _)))
    (try (fs/delete-tree work) (catch Exception e (println "cleanup:" (ex-message e))))))

(println (if (zero? @fails) "corpus round trip: all ok" (str "corpus round trip: " @fails " FAILED")))
(System/exit (if (zero? @fails) 0 1))
