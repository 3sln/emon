#!/usr/bin/env bb
;; Record the TARGETED-ROUNDS FIXTURES LIVE, with the pixels the page units read (DEVLOG P7.4, D196, D347). NOT a gate: it needs Tesseract (and, for a
;; born-digital fixture, the pinned pdfium: `bb pdfium-fetch`).
;;
;;     bb retarget-record [ID...]          the fixtures of test/retarget/FIXTURES.edn (default: all)
;;     bb retarget-record --digests [ID...]   NO live engine (D488): replay the committed transcripts on Rust, require Go to give the same bundle, and
;;                                            rewrite DIGESTS.edn (what a refit or a node change does to the bundles)
;;
;; The Engine converts each document with the LIVE ports and records what they answered: the decode or the renders, every OCR answer, and (D196: kin
;; reads pixels from P6.3 on, so a transcript must carry every bitmap kin reads) the BITMAPS the page units read: the decode or the render, the
;; engine's thresholded image of the first OCR pass (xo 11 and 12's input). The thresholded images of the targeted passes are not read and are not kept
;; (they are most of a transcript's size). What a kin-made view is OCR'd as is in the request (its pixel hash) and the view's pixels are not kept at all.
;; The transcript is replayed on Rust before it is kept, and the replay's bundle must be the recording's, byte for byte; the SHA-256 of each document's
;; replay bundle is written to test/retarget/replay/DIGESTS.edn, which `bb retarget` holds both Engines to.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.edn :as edn]
         '[clojure.pprint]
         '[clojure.string :as str])

(def fixtures (edn/read-string (slurp "test/retarget/FIXTURES.edn")))
(def flags (set (filter #(str/starts-with? % "--") *command-line-args*)))
(def args (vec (remove #(str/starts-with? % "--") *command-line-args*)))
(def digests-only? (contains? flags "--digests"))
(def ids (cond (seq args) args
               ;; --digests replays what is committed: the fixtures that have a transcript, as `bb retarget` does
               digests-only? (vec (sort (filter #(fs/exists? (str "test/retarget/replay/" % "/transcript.edn")) (keys fixtures))))
               :else (vec (sort (keys fixtures)))))
(def digests-file "test/retarget/replay/DIGESTS.edn")
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
  "An entry whose bitmap kin reads: the page unit's render or decode (page-local ids 1-4), the first OCR pass (16) or the enlarged raster's (21, D1610: it stands in for the first pass)."
  [req-id]
  (contains? #{1 2 3 4 16 21} (mod req-id 256)))

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

;; --digests (D488): NO live engine. Replay the committed transcripts with the plain Rust driver (`--out=bundle`, the SHA-256 of its output is what
;; `bb retarget` holds both Engines to), require the Go driver to give the same bundle, and rewrite DIGESTS.edn. For what a change to kin (a refit, a
;; conf, a node) does to the bundles while the answers of pdfium and Tesseract are the same ones.
(when digests-only?
  (let [rs (build {})
        go (let [r (sh {:err :inherit} "kin/scripts/emon-go-build")]
             (when-not (zero? (:exit r)) (println "FAIL the Go driver did not build") (System/exit 1))
             (str (fs/absolutize (str/trim (:out r)))))]
    (doseq [id ids]
      (let [{:keys [pdf]} (get fixtures id)
            _ (when-not pdf (println "unknown fixture" id) (System/exit 2))
            run (fn [bin] (sh {:err :inherit} bin "--out=bundle" (str "--replay=" (fs/absolutize (str "test/retarget/replay/" id))) (str (fs/absolutize pdf))))
            rr (run rs)
            rg (run go)]
        (when-not (zero? (:exit rr)) (println "FAIL" id "does not replay on Rust") (System/exit 1))
        (when-not (and (zero? (:exit rg)) (= (:out rr) (:out rg)))
          (println "FAIL" id "the Rust and Go bundles differ")
          (System/exit 1))
        (swap! digests assoc id (sha256 (:out rr)))
        (println "digest" id)))
    (spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/retarget/replay/ID, as `bb retarget-record` made it (P7.4, D347).\n"
                            (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests)))))
    (System/exit 0)))

(doseq [id ids]
  (let [{:keys [pdf kind]} (get fixtures id)
        _ (when-not pdf (println "unknown fixture" id) (System/exit 2))
        born? (= kind :born)
        bin (if born? @born-bin @scan-bin)
        flags (if born? ["--pdfium" "--tesseract"] ["--tesseract"])
        out (str (fs/absolutize (str "test/retarget/replay/" id)))
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

(spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/retarget/replay/ID, as `bb retarget-record` made it (P7.4, D347).\n"
                        (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests)))))
