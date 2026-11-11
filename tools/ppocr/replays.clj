#!/usr/bin/env bb
;; Replay all retained OP1c candidate recordings on every native replay API.
;; Compare stdout directly: large external-drive captures can contain sparse NUL holes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str] '[clojure.java.io :as io])
(import '[java.util.zip GZIPInputStream] '[java.security MessageDigest])
(def args (apply hash-map *command-line-args*))
(def qual (get args "--qualification" "/Volumes/SSK SSD/emon-scratch/op1c"))
(def out (get args "--out" "/Volumes/SSK SSD/emon-scratch/op2a/qualification-replays"))
(fs/create-dirs out)
(defn expected-stream [p]
 (if (fs/exists? p) (io/input-stream p) (GZIPInputStream. (io/input-stream (str p ".gz")))))
(defn equal-streams? [expected actual]
 (with-open [a (expected-stream expected) b (io/input-stream actual)]
  (let [ab (byte-array 65536) bb (byte-array 65536)]
   (loop [equal true]
    (let [na (.readNBytes a ab 0 65536) nb (.readNBytes b bb 0 65536)]
     ;; Always drain stdout before awaiting the child. Returning at the first
     ;; mismatch can leave a large bundle blocked on its full output pipe.
     (if (and (zero? na) (zero? nb)) equal
       (recur (and equal (= na nb) (java.util.Arrays/equals ^bytes ab ^bytes bb)))))))))
(when (= "true" (get args "--self-test"))
 (let [dir (fs/create-temp-dir {:dir ".cache" :prefix "ppocr-replay-drain-"})
       expected (str dir "/expected")]
  (try
   (doseq [[size char equal] [[1048576 "y" true] [1048576 "x" false]
                             [4096 "x" false] [2097152 "x" false]]]
    (spit expected (apply str (repeat size char)))
    (let [p (process ["bb" "-e" "(print (apply str (repeat 1048576 \"y\")))"]
                     {:out :stream :err :string :shutdown nil})
          result (equal-streams? expected (:out p))]
     (assert (= equal result) "stream equality result differs")
     (assert (zero? (:exit @p)) "mismatched stdout was not drained")))
   (println "PASS replay stream equality, early mismatch and shorter/longer streams drain child stdout")
   (finally (fs/delete-tree dir)))))
(when (= "true" (get args "--self-test")) (System/exit 0))
(defn sha [expected]
 (with-open [in (expected-stream expected)]
  (let [h (MessageDigest/getInstance "SHA-256") b (byte-array 65536)]
   (loop [] (let [n (.read in b)] (when (pos? n) (.update h b 0 n) (recur))))
   (apply str (map #(format "%02x" (bit-and % 255)) (.digest h))))))
(def controls (vec (remove #(or (str/blank? %) (str/starts-with? % "#")) (str/split-lines (slurp "test/controls/sweep3.txt")))))
(defn recordings [dir controls?]
 (for [path (sort (filter #(str/ends-with? (str (fs/file-name %)) ".replay") (fs/list-dir dir))) :when (and (fs/directory? path) (not (str/starts-with? (str (fs/file-name path)) "._"))
                                         (or (not controls?) (str/includes? (str (fs/file-name path)) "-ppocr")))]
  (let [stem (str/replace (str path) #"\.replay$" "") name (str (fs/file-name path))
        ident (str/replace name #"\.replay$" "") dataset (if (str/starts-with? ident "sroie") "sroie" "cord")
        input (if controls? (controls (parse-long (subs name 0 2)))
                (str "corpus/" (get-in (json/parse-string (slurp (str "corpus/_gold/" dataset "/" ident ".gold.json")) true) [:provenance :variants :image])))]
   (assert (fs/regular-file? input) (str "replay source missing: " input))
   (assert (or (fs/regular-file? (str stem ".bundle")) (fs/regular-file? (str stem ".bundle.gz"))) (str "reference bundle missing: " stem))
   {:id name :recording (str path) :input input :expected (str stem ".bundle") :flags (if controls? ["--assets=figures" "--ocr=auto"] [])})))
(def cases (vec (concat (recordings (str qual "/live/receipts-ppocr") false)
                       (recordings (str qual "/live/controls") true)
                       (recordings (str qual "/fresh/ppocr") false))))
(assert (= 198 (count cases)) "expected exactly 174 fixed and 24 fresh candidate recordings")
(def hosts [["rust-reference" ".cache/emon-rs-ppocr/target/release/emon-rs"]
            ["rust-native" ".cache/emon-rs-live/target/release/emon-rs"]
            ["go" ".cache/emon-go-live/emon-go"] ["java" ".cache/emon-java/emon-java"]
            ["csharp" ".cache/emon-cs/emon-cs"] ["c" ".cache/emon-c-live/emon-c"]])
(def results (atom []))
(doseq [[i {:keys [id recording input expected flags]}] (map-indexed vector cases)]
 (let [digest (sha expected)]
  (doseq [[host bin] hosts]
   (doseq [disk ["/System/Volumes/Data" "/Volumes/SSK SSD"]]
    (assert (> (.getUsableSpace (java.io.File. disk)) (* 10 1024 1024 1024)) (str "disk below 10 GiB: " disk)))
   (let [log (str out "/current.err")
         p (process (into [bin "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" recording)] (concat flags [input]))
                    {:extra-env {"EMON_CS_SHARED_COMPILATION" "false" "EMON_PPOCR_MODELS" "/nonexistent/op2a-models" "EMON_PPOCR_LIB" "/nonexistent/op2a-runtime"}
                     :out :stream :err log :shutdown nil})
         equal (equal-streams? expected (:out p))
         r @p]
    (assert (zero? (:exit r)) (str host " replay failed: " id "; see " log))
    (assert equal (str host " replay bytes differ: " id))
    (swap! results conj {:id id :host host :equal true :bundle-sha256 digest})
    (spit (str out "/summary.json") (json/generate-string {:complete (= (* 198 6) (count @results)) :recordings 198 :results @results} {:pretty true}))))
  (println (inc i) "/ 198" id "six replay drivers identical") (flush)))
(assert (= 1188 (count @results)))
(assert (= (set (for [c cases [host _] hosts] [(:id c) host]))
           (set (map (juxt :id :host) @results))))
(println "all 198 candidate recordings replay byte-identically without model/runtime paths")
