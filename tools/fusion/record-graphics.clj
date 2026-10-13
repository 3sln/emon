#!/usr/bin/env bb
;; Record the GRAPHICS-FUSION FIXTURES LIVE, with the pixels the page units read (DEVLOG P7.G, D221-D224, D325-D331; the gate is `kin/scripts/gfuse`, D1320-D1327).
;; NOT a gate: it needs Tesseract and the pinned pdfium (`bb pdfium-fetch`).
;;
;;     bb gfuse-record [ID...]        the fixtures of test/fusion/graphics/FIXTURES.edn (default: all)
;;     bb gfuse-record --digests      no live engine: replay the committed transcripts with the plain driver and rewrite DIGESTS.edn (what a change of kin makes of the bundles)
;;
;; The Engine converts each document with its DEFAULT settings (`ocr: always`) and the live ports (pdfium for a born-digital page, a scan is decoded; Tesseract reads every
;; page) and records what they answered. Unlike the fusion replay set (recorded `--no-retarget`) the transcript keeps PIXELS: the tracer reads the page's raster and so
;; does every targeted round, and a replay whose pixel differs is `transcript-miss`. A transcript is then CUT DOWN to what a replay reads (compact, SPEC 17 (6): at most 3 MB
;; for the whole set): each bitmap is dropped in turn, largest first, and kept dropped when the replay of the cut transcript is byte for byte the recording's bundle.
;; The transcript is replayed before it is kept; the SHA-256 of each replay's bundle (JSON, HTML, evidence) goes to test/fusion/graphics/DIGESTS.edn, which `bb gfuse` holds
;; every Engine to.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.edn :as edn]
         '[clojure.pprint]
         '[clojure.string :as str])

(def gdir "test/fusion/graphics")
(def fixtures (edn/read-string (slurp (str gdir "/FIXTURES.edn"))))
(def flags (set (filter #(str/starts-with? % "--") *command-line-args*)))
(def args (vec (remove #(str/starts-with? % "--") *command-line-args*)))
(def digests-only? (contains? flags "--digests"))
(def ids (if (seq args) args (vec (sort (keys fixtures)))))
(def digests-file (str gdir "/DIGESTS.edn"))
(def digests (atom (select-keys (if (fs/exists? digests-file) (read-string (slurp digests-file)) {}) (keys fixtures))))

(defn input-of [id]
  (let [{:keys [variant doc]} (get fixtures id)]
    (when-not doc (println "unknown fixture" id) (System/exit 2))
    (str (fs/absolutize (str "test/corpus/synth/" variant "/" doc ".pdf")))))

(defn sha256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String s "UTF-8"))))))

(shell {:out :string :err :string} "kin/scripts/emit")
(defn build [env]
  (let [r (sh {:extra-env env :err :inherit} "kin/scripts/emon-rs-build")]
    (when-not (zero? (:exit r)) (println "FAIL a driver did not build") (System/exit 1))
    (str (fs/absolutize (str/trim (:out r))))))
(def plain-bin (delay (build {})))

(defn replay [bin out in]
  (sh {:err :inherit} bin "--out=bundle" (str "--replay=" out) in))

(defn cut-down
  "Drop each bitmap of the transcript in `out` that a replay does not read: the largest first, kept dropped when the replay's bundle is `want`. -> [kept dropped]"
  [bin out in want]
  (let [f (str out "/transcript.edn")
        names (sort-by #(- (fs/size (str out "/" %))) (map fs/file-name (fs/glob out "bitmap-*.png")))]
    (loop [ns names kept 0 dropped 0]
      (if-let [n (first ns)]
        (let [t (slurp f)
              t2 (str/replace t (str " :bitmap \"" n "\"") "")
              _ (spit f t2)
              ok? (let [r (replay bin out in)] (and (zero? (:exit r)) (= want (:out r))))]
          (if ok?
            (do (fs/delete (str out "/" n)) (recur (rest ns) kept (inc dropped)))
            (do (spit f t) (recur (rest ns) (inc kept) dropped))))
        [kept dropped]))))

(defn rewrite-digests []
  (spit digests-file (str ";; The SHA-256 of each replayed bundle (JSON, HTML, evidence) of test/fusion/graphics/ID, as `bb gfuse-record` made it (P7.G, D1320).\n"
                          (with-out-str (clojure.pprint/pprint (into (sorted-map) @digests))))))

(if digests-only?
  (let [bin @plain-bin]
    (doseq [id ids]
      (let [rp (replay bin (str (fs/absolutize (str gdir "/" id))) (input-of id))]
        (when-not (zero? (:exit rp)) (println "FAIL" id "does not replay") (System/exit 1))
        (swap! digests assoc id (sha256 (:out rp)))
        (println "digest" id)))
    (rewrite-digests))
  (let [pdfium (do (shell "bb" "tools/pdfium/fetch.clj") (str (fs/real-path ".cache/pdfium/current/lib")))
        live-bin (build {"EMON_RS_PDFIUM" pdfium})
        bin @plain-bin]
    (doseq [id ids]
      (let [{:keys [variant]} (get fixtures id)
            in (input-of id)
            out (str (fs/absolutize (str gdir "/" id)))
            live-flags (if (= variant "pdf") ["--pdfium" "--tesseract"] ["--tesseract"])
            t0 (System/currentTimeMillis)]
        (fs/delete-tree out)
        (fs/create-dirs out)
        (let [r (apply sh {:err :inherit} live-bin (concat live-flags ["--out=bundle" (str "--record=" out) in]))]
          (when-not (zero? (:exit r)) (println "FAIL" id "exited" (:exit r)) (System/exit (:exit r)))
          (let [before (reduce + (map fs/size (fs/list-dir out)))
                rp (replay bin out in)]
            (when-not (and (zero? (:exit rp)) (= (:out r) (:out rp)))
              (println "FAIL" id "the replay is not the recording")
              (System/exit 1))
            (let [[kept dropped] (cut-down bin out in (:out r))
                  after (reduce + (map fs/size (fs/list-dir out)))
                  rp2 (replay bin out in)]
              (when-not (and (zero? (:exit rp2)) (= (:out r) (:out rp2)))
                (println "FAIL" id "the cut-down replay is not the recording")
                (System/exit 1))
              (swap! digests assoc id (sha256 (:out rp2)))
              (println (format "recorded %s: %d KB (was %d KB: %d bitmaps kept, %d dropped), %d ms"
                               id (quot after 1024) (quot before 1024) kept dropped (- (System/currentTimeMillis) t0))))))))
    (rewrite-digests)))
