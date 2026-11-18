#!/usr/bin/env bb
;; Replay all retained OP1c candidate recordings on every native replay API.
;; Compare stdout directly: large external-drive captures can contain sparse NUL holes.
(require '[babashka.fs :as fs] '[babashka.process :refer [process]]
         '[cheshire.core :as json] '[clojure.string :as str] '[clojure.java.io :as io]
         '[clojure.edn :as edn] '[ppocr.qualification :as qualification])
(import '[java.util.zip GZIPInputStream] '[java.security MessageDigest])
(def args (apply hash-map *command-line-args*))
(def qual (get args "--qualification" "corpus/_qual/ppocr-qual"))
(def record-reference (get args "--record-reference"))
(def out (get args "--out" (str (or (System/getenv "EMON_SCRATCH") ".cache/scratch") "/op2a/qualification-replays")))
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
   (qualification/compress-bundle! expected (qualification/file-sha256 expected))
   (let [p (process ["bb" "-e" "(print (apply str (repeat 2097152 \"x\")))"]
                    {:out :stream :err :string :shutdown nil})]
     (assert (equal-streams? expected (:out p)) "compressed reference differs")
     (assert (zero? (:exit @p)) "compressed comparison did not drain stdout"))
   (println "PASS replay stream equality, gzip references, early mismatch and shorter/longer streams drain child stdout")
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
(def cases
 (if (fs/regular-file? (str qual "/replays.json"))
   (mapv (fn [row] (-> row (update :recording #(str (fs/path qual %)))
                          (update :expected #(str (fs/path qual %)))))
         (json/parse-string (slurp (str qual "/replays.json")) true))
   (do
     (assert record-reference "historical recordings need --record-reference to re-qualify under OIL")
     (vec (concat (map #(assoc % :population "fixed") (recordings (str qual "/live/receipts-ppocr") false))
                  (map #(assoc % :population "control") (recordings (str qual "/live/controls") true))
                  (map #(assoc % :population "fresh") (recordings (str qual "/fresh/ppocr") false)))))))
(assert (= 198 (count cases)) "expected exactly 174 fixed and 24 fresh candidate recordings")
(def record-driver
 (when record-reference
  (let [pdfium (str (fs/absolutize ".cache/pdfium/current/lib"))
        library (str pdfium "/libpdfium.dylib")]
   (assert (fs/regular-file? library) "qualification control recordings need the pinned pdfium library")
   (let [r @(process ["kin/scripts/emon-rs-build"]
                     {:extra-env {"EMON_RS_PPOCR" "1" "EMON_RS_PDFIUM" pdfium "EMON_CS_SHARED_COMPILATION" "false"}
                      :out :string :err (str out "/reference-build.err") :shutdown nil})
         driver (str/trim (:out r))]
    (assert (zero? (:exit r)) (str "render-capable Rust reference build failed; see " out "/reference-build.err"))
    (assert (fs/regular-file? driver) "reference builder did not return a driver")
    (let [path (str record-reference "/PROVENANCE.edn")]
     (spit path (pr-str (assoc (edn/read-string (slurp path))
                              :reference-renderer {:engine "pdfium" :library-sha256 (qualification/file-sha256 library)}
                              :record-driver-sha256 (qualification/file-sha256 driver)
                              :live-reference-renderer-flag "--pdfium"))))
    driver))))
(def hosts [["rust-reference" ".cache/emon-rs-ppocr/target/release/emon-rs"]
            ["rust-native" ".cache/emon-rs-live/target/release/emon-rs"]
            ["go" ".cache/emon-go-live/emon-go"] ["java" ".cache/emon-java/emon-java"]
            ["csharp" ".cache/emon-cs/emon-cs"] ["c" ".cache/emon-c-live/emon-c"]])
(def results (atom []))
(def recorded (atom []))
(doseq [[i {:keys [id recording input expected flags population]}] (map-indexed vector cases)]
 (let [reference-recording (if record-reference (str record-reference "/recordings/" i ".replay") recording)
       reference-bundle (if record-reference (str record-reference "/recordings/" i ".bundle") expected)]
  (when record-reference
   (assert (fs/regular-file? (str record-reference "/PROVENANCE.edn")) "record into a qualified bitmap artifact")
   (assert (not (fs/exists? reference-recording)) "refusing to overwrite a reference recording")
   (fs/create-dirs (fs/parent reference-recording))
   (let [r @(process (into [record-driver "--pdfium" "--ocr-engine=ppocr" "--out=bundle"
                           (str "--record=" reference-recording)] (concat flags [input]))
                    {:extra-env {"EMON_CS_SHARED_COMPILATION" "false"}
                     :out reference-bundle :err (str out "/reference.err") :shutdown nil})]
    (assert (zero? (:exit r)) (str "Rust reference recording failed: " id))
    (qualification/check-reference-renders
      (edn/read-string (slurp (str recording "/transcript.edn")))
      (edn/read-string (slurp (str reference-recording "/transcript.edn"))))))
  (let [digest (sha reference-bundle)]
  (doseq [[host bin] hosts]
   (doseq [disk ["."]]
    (assert (> (.getUsableSpace (java.io.File. disk)) (* 10 1024 1024 1024)) (str "disk below 10 GiB: " disk)))
   (let [log (str out "/current.err")
         p (process (into [bin "--ocr-engine=ppocr" "--out=bundle" (str "--replay=" reference-recording)] (concat flags [input]))
                    {:extra-env {"EMON_CS_SHARED_COMPILATION" "false" "EMON_PPOCR_MODELS" "/nonexistent/op2a-models" "EMON_PPOCR_LIB" "/nonexistent/op2a-runtime"}
                     :out :stream :err log :shutdown nil})
         equal (equal-streams? reference-bundle (:out p))
         r @p]
    (assert (zero? (:exit r)) (str host " replay failed: " id "; see " log))
    (assert equal (str host " replay bytes differ: " id))
    (swap! results conj {:id id :host host :equal true :bundle-sha256 digest})
    (spit (str out "/summary.json") (json/generate-string {:complete (= (* 198 6) (count @results)) :recordings 198 :results @results} {:pretty true}))))
  (when record-reference
    (qualification/compress-bundle! reference-bundle (sha reference-bundle))
    (swap! recorded conj {:id id :input input :flags flags :population population
                         :recording (str "recordings/" i ".replay")
                         :expected (str "recordings/" i ".bundle")}))
  (println (inc i) "/ 198" id "six replay drivers identical") (flush))))
(assert (= 1188 (count @results)))
(assert (= (set (for [c cases [host _] hosts] [(:id c) host]))
           (set (map (juxt :id :host) @results))))
(println "all 198 candidate recordings replay byte-identically without model/runtime paths")
(when record-reference
  (spit (str record-reference "/replays.json") (json/generate-string @recorded {:pretty true}))
  (let [path (str record-reference "/PROVENANCE.edn")]
    (spit path (pr-str (-> (edn/read-string (slurp path))
                          (assoc-in [:counts :recordings] (count @recorded))
                          (assoc :replay-equality true))))))
