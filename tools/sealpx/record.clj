#!/usr/bin/env bb
;; Record the SEALS-FROM-PIXELS FIXTURES LIVE, with the pixels the page units read (DEVLOG P8.2, D196, D412-D418). NOT a gate: it needs Tesseract, and the pinned
;; pdfium for a born-digital fixture (`bb pdfium-fetch`).
;;
;;     bb sealpx-record [ID...]          the fixtures of test/sealpx/FIXTURES.edn (default: all)
;;
;; The Engine converts each document with the LIVE ports and records what they answered: the decode or the renders and every OCR answer, and the BITMAPS the page units
;; read (a transcript must carry every bitmap kin reads: the page's raster is what the tracer, the ink key and the polar unwarp read). What a kin-made view is OCR'd as
;; (a seal's strip, centre or crop) is in the request, by its pixel hash, and the view's pixels are not kept: they are a function of the page's, which kin makes again
;; on the replay, and a replay whose view came out with another pixel is `transcript-miss`. The transcript is replayed on Rust before it is kept, and the replay's bundle
;; must be the recording's byte for byte; the SHA-256 of each document's replay bundle is written to test/sealpx/replay/DIGESTS.edn, which `bb sealpx` holds both Engines to.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.edn :as edn]
         '[clojure.pprint]
         '[clojure.string :as str])

(def fixtures (edn/read-string (slurp "test/sealpx/FIXTURES.edn")))
(def flags (set (filter #(str/starts-with? % "--") *command-line-args*)))
(def args (vec (remove #(str/starts-with? % "--") *command-line-args*)))
(def digests-only? (contains? flags "--digests"))
(def ids (if (seq args) args (vec (sort (keys fixtures)))))
(def digests-file "test/sealpx/replay/DIGESTS.edn")
(def digests (atom (if (fs/exists? digests-file) (read-string (slurp digests-file)) {})))

(shell {:out :string :err :string} "kin/scripts/emit")
(defn build [env]
  (let [r (sh {:extra-env env :err :inherit} "kin/scripts/emon-rs-build")]
    (when-not (zero? (:exit r)) (println "FAIL a driver did not build") (System/exit 1))
    (str (fs/absolutize (str/trim (:out r))))))
(def scan-bin (delay (build {"EMON_RS_LIVE" "1"})))
(def born-bin (delay (do (shell "bb" "tools/pdfium/fetch.clj")
                         (build {"EMON_RS_PDFIUM" (str (fs/real-path ".cache/pdfium/current/lib"))}))))

(defn sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String s "UTF-8"))))))

(defn untargeted?
  "An entry whose bitmap kin reads: the page unit's render or decode (page-local ids 1-4) or the first OCR pass (16)."
  [req-id]
  (contains? #{1 2 3 4 16} (mod req-id 256)))

(defn strip-unread-bitmaps
  "Drop the `:bitmap` of every entry kin never reads (an OCR answer of a targeted pass), and its file."
  [dir]
  (let [f (str dir "/transcript.edn")
        t (slurp f)
        dropped (atom [])
        t2 (str/replace t #"\{:round (\d+) :kind :(\w+) :req \[([^\]]*)\] :ans \[([^\]]*)\] :bitmap \"([^\"]*)\"\}"
                        (fn [[whole round kind req ans bm]]
                          (let [id (parse-long (nth (str/split req #" ") 2))]
                            (if (untargeted? id)
                              whole
                              (do (swap! dropped conj bm)
                                  (str "{:round " round " :kind :" kind " :req [" req "] :ans [" ans "]}"))))))]
    (spit f t2)
    (doseq [b @dropped] (fs/delete-if-exists (str dir "/" b)))
    (count @dropped)))

;; --digests: no live engine; replay the committed transcripts with the plain driver and rewrite DIGESTS.edn (what a change of kin makes of the bundles)
(when digests-only?
  (let [bin (str (fs/absolutize (str/trim (:out (sh {:err :inherit} "kin/scripts/emon-rs-build")))))]
    (doseq [id ids]
      (let [{:keys [pdf]} (get fixtures id)
            rp (sh {:err :inherit} bin "--out=bundle" (str "--replay=" (fs/absolutize (str "test/sealpx/replay/" id))) (str (fs/absolutize pdf)))]
        (when-not (zero? (:exit rp)) (println "FAIL" id "does not replay") (System/exit 1))
        (swap! digests assoc id (sha256 (:out rp)))
        (println "digest" id)))
    (spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/sealpx/replay/ID, as `bb sealpx-record` made it (P8.2, D414).\n"
                            (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests)))))
    (System/exit 0)))

(doseq [id ids]
  (let [{:keys [pdf kind]} (get fixtures id)
        _ (when-not pdf (println "unknown fixture" id) (System/exit 2))
        born? (= kind :born)
        bin (if born? @born-bin @scan-bin)
        flags (if born? ["--pdfium" "--tesseract"] ["--tesseract"])
        out (str (fs/absolutize (str "test/sealpx/replay/" id)))
        t0 (System/currentTimeMillis)]
    (fs/delete-tree out)
    (fs/create-dirs out)
    (let [r (apply sh {:err :inherit} bin (concat flags ["--out=bundle" (str "--record=" out) (str (fs/absolutize pdf))]))]
      (when-not (zero? (:exit r)) (println "FAIL" id "exited" (:exit r)) (System/exit (:exit r)))
      (let [n (strip-unread-bitmaps out)
            rp (apply sh {:err :inherit} bin (concat (remove #{"--pdfium" "--tesseract"} flags) ["--out=bundle" (str "--replay=" out) (str (fs/absolutize pdf))]))]
        (when-not (and (zero? (:exit rp)) (= (:out r) (:out rp)))
          (println "FAIL" id "the replay is not the recording")
          (System/exit 1))
        (swap! digests assoc id (sha256 (:out rp)))
        (println (format "recorded %s: %d KB (%d unread bitmaps dropped), %d ms" id (quot (reduce + (map fs/size (fs/list-dir out))) 1024) n
                         (- (System/currentTimeMillis) t0)))))))

(spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/sealpx/replay/ID, as `bb sealpx-record` made it (P8.2, D414).\n"
                        (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests)))))
