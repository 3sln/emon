#!/usr/bin/env bb
;; Rewrite test/ocr/ID.dump from the COMMITTED transcript, without recording anything (DEVLOG P1.12, D096).
;; NOT a gate, and needs no Tesseract: the transcript's answer is what it was; only the evidence the unit makes
;; of it changed (the dump's version, arenas and fields), so the dump is what a replay prints now.
;;
;;     bb tools/ocr/redump.clj [ID]        default h-keyword
;;
;; `bb ocr` then holds both hosts to it. Run it only after a change of the evidence schema or of
;; `emon.ocrprobe`, and read the diff: the transcript (`test/ocr/ID/`) is never touched.
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell sh]]
         '[clojure.string :as str])

(def id (or (first *command-line-args*) "h-keyword"))
(def pdf (str "test/corpus/synth/scan/" id ".pdf"))
(def tdir (str "test/ocr/" id))
(def work (str (fs/create-temp-dir {:prefix "emon-ocrredump."})))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (fs/delete-tree work))))

(shell {:out :string :err :string} "kin/scripts/emit")
(fs/create-dirs (str work "/emon"))
(doseq [f (fs/glob "gen/rust/emon" "*.rs")] (fs/copy f (str work "/emon/" (fs/file-name f))))
(doseq [f ["port.rs" "testport.rs" "plan.rs"]] (fs/copy (str "host/rust/src/" f) (str work "/" f)))
(spit (str work "/emon.rs")
      (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                   (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
(fs/copy "test/ocr/rust/main.rs" (str work "/main.rs"))
(shell {:dir work} "rustc" "--edition" "2021" "-O" "-A" "warnings" "-o" "prog" "main.rs")
(let [r (sh {:err :inherit} (str work "/prog") "replay" (str (fs/absolutize tdir)) (str (fs/absolutize pdf)))]
  (when-not (zero? (:exit r)) (System/exit (:exit r)))
  (spit (str "test/ocr/" id ".dump") (:out r)))
(println "rewrote" (str "test/ocr/" id ".dump") "from" tdir "(the transcript is untouched)")
