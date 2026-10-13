#!/usr/bin/env bb
;; Record the pdfium-wasm gate's transcripts LIVE, and the digests it holds them to.   tools/pdfium_wasm/record.clj   (bb pdfium-wasm-record)   (DEVLOG P11.4c)
;;
;;     bb pdfium-wasm-record [STEM ...]
;;     bb pdfium-wasm-record --digests [STEM ...]
;;
;; NOT a gate. The live form needs what the gate's live arm needs (Node 22+, the emon-wasm-build toolchain, `bb pdfium-fetch --platform wasm`): each document
;; of test/pdfium-wasm/FIXTURES.edn is converted at `assets=figures`, `ocr=never` by the JS SDK in Node with pdfium's WebAssembly build as `ports.render`,
;; RECORDING, and what it recorded is test/pdfium-wasm/replay/STEM/ (transcript.edn and the PNGs of the crops: the file format of every host). The render cases of
;; test/render/cases.edn are made of the live renderer too, for their pixel hashes. test/pdfium-wasm/RENDERS.edn names the SHA-256 of every page the renderer
;; made and every case it answered (arm B1 holds a live run to it). Then the digests: the committed transcripts replayed on the Rust and Go drivers, JSON,
;; HTML in three modes and every asset file, into test/pdfium-wasm/DIGESTS.edn, which arm A holds the replays to.
;;
;;   --digests   REPLAY ONLY, no pdfium, no Node (D488, `bb rederive`): the committed transcripts replay on Rust (Go must agree) and DIGESTS.edn is rewritten
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(cp/add-classpath "tools/lib")
(require '[pdfium-wasm :as pw])

(def args (vec *command-line-args*))
(def digests-only? (some #{"--digests"} args))
(def only (vec (remove #(str/starts-with? % "--") args)))
(def stems (vec (filter #(or (empty? only) (some #{%} only)) pw/stems)))
(defn die [& m] (println "FAIL" (apply str m)) (System/exit 1))

(shell {:out :string :err :string} "kin/scripts/emit")

(when-not digests-only?
  (let [env (pw/live-env)]
    (when (or (:skip env) (:fail env)) (die "cannot record live: " (or (:skip env) (:fail env))))
    (let [work (str (fs/create-temp-dir {:prefix "pdfium-wasm-rec."}))
          old (try (edn/read-string (slurp (str pw/root "/RENDERS.edn"))) (catch Exception _ {}))
          docs (into (or (:docs old) {})
                     (for [stem stems]
                       (let [out (str work "/" stem)
                             r (pw/live-convert env stem out)]
                         (when (:error r) (die stem ": " (:error r)))
                         (let [dir (str pw/root "/replay/" stem)]
                           (fs/delete-tree dir)
                           (fs/create-dirs dir)
                           (doseq [f (fs/list-dir (str out "/transcript"))] (fs/copy f (str dir "/" (fs/file-name f))))
                           (println (format "  %-26s %d render(s), %d asset(s), transcript %d file(s)" stem (:renders r) (:assets r) (count (fs/list-dir dir))))
                           [stem (mapv (fn [m] (into (sorted-map) m)) (json/parse-string (slurp (str out "/renders.json")) true))]))))
          cases-edn (edn/read-string (slurp "test/render/cases.edn"))
          cases (pw/node env "cases" "--pdfium" pw/pdfium-dir "--native" (get-in pw/fixtures [:cases :native])
                         "--pdf" (first (:pdfs cases-edn)) "--pdf" (second (:pdfs cases-edn)) "--pdf" (nth (:pdfs cases-edn) 2))]
      (when (:error cases) (die "the render cases: " (:error cases)))
      (when (seq (get-in cases [:compare :problems])) (die "the render cases differ from the native transcript: " (pr-str (get-in cases [:compare :problems]))))
      (pw/write-edn (str pw/root "/RENDERS.edn")
                    {:docs (into (sorted-map) docs)
                     :cases (mapv (fn [h] (into (sorted-map) h)) (:hashes cases))})
      (fs/delete-tree work)
      (println "recorded" (count stems) "documents and" (count (:hashes cases)) "render cases"))))

;; the digests, from the committed transcripts: Rust, and Go must say the same
(let [rust (pw/build-driver "kin/scripts/emon-rs-build" "Rust" die)
      go (pw/build-driver "kin/scripts/emon-go-build" "Go" die)
      file (str pw/root "/DIGESTS.edn")
      old (try (edn/read-string (slurp file)) (catch Exception _ {}))
      new (into (sorted-map)
                (for [stem stems
                      :let [pdf (:pdf (pw/docs stem)) t (str pw/root "/replay/" stem)
                            r (pw/replay-entry rust pdf t)
                            g (pw/replay-entry go pdf t)]]
                  (do (when (:error r) (die stem " does not replay on Rust: " (:error r)))
                      (when (not= r g) (die stem ": Rust and Go replay it to different digests"))
                      (println "digest" stem)
                      [stem r])))]
  (pw/write-edn file (merge (into (sorted-map) old) new))
  (println "wrote" file))
