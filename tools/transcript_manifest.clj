#!/usr/bin/env bb
;; The list of every committed transcript under test/: test/transcript/manifest.txt (P10.9).
;;
;;     bb tools/transcript_manifest.clj            write the list
;;     bb tools/transcript_manifest.clj --check    fail if the committed list is not the tree's
;;
;; `kin/transcripttest.kin` reads each file the list names and writes it again through `emon.transcript` on all four targets: the same bytes. kin has
;; no directory listing, so the list is a file, and this holds it to the tree (a transcript added without it, or removed with it still named, fails
;; the unit gate). The paths are relative to test/, one a line, sorted. Babashka, as D094 says all scripting is.
(require '[babashka.fs :as fs] '[clojure.string :as str])

(def out "test/transcript/manifest.txt")

(defn listing []
  (->> (fs/glob "test" "**/transcript.edn")
       (map #(str (fs/relativize "test" %)))
       sort
       (map #(str % "\n"))
       (apply str)))

(let [want (listing)
      check? (some #{"--check"} *command-line-args*)]
  (cond
    (and check? (= want (when (fs/exists? out) (slurp out))))
    (println (str "  ok   " (count (str/split-lines want)) " transcripts listed in " out))

    check?
    (do (println (str "FAIL " out " is not the transcripts under test/ (bb tools/transcript_manifest.clj writes it)"))
        (System/exit 1))

    :else
    (do (fs/create-dirs "test/transcript")
        (spit out want)
        (println (str "wrote " out ": " (count (str/split-lines want)) " transcripts")))))
