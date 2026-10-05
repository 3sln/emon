#!/usr/bin/env bb
;; Record the render cases LIVE with pdfium, and measure what the hosts and the builds do.
;;                                                                                (DEVLOG P6.2, D127)
;;     bb render-record [--go] [--compare-build] [--scratch]
;;
;; NOT a gate: it needs the pinned pdfium (`bb pdfium-fetch`, a cache under .cache/pdfium/), rustc and,
;; for --go, a C compiler and cgo. What it does:
;;
;;   1. builds the Rust render driver against libpdfium and records test/render/cases.edn's requests into
;;      test/render/render/ (transcript.edn + PNGs) and test/render/render.dump (the driver's stdout, which
;;      `bb render` replays on Rust and Go and compares);
;;   2. --go: records the same requests on the GO host (cgo, host/go/pdfium) into a scratch directory and
;;      diffs its bitmaps against the Rust recording, pixel by pixel: the cross-host difference;
;;   3. --compare-build: records again on Rust against the PREVIOUS pdfium release (tools/pdfium/pdfium.edn
;;      :previous) into a scratch directory and diffs: the cross-BUILD difference, what a re-pin may move;
;;   4. --scratch: records into .cache/render-scratch/ instead of overwriting test/render/render/ (use it to
;;      look at a change before committing it).
;;
;; The committed transcript, not a re-render, is what every gate compares (D008): the numbers above say how
;; far a second engine may stray from it, and DEVLOG D127 records them.
(require '[babashka.fs :as fs]
         '[babashka.classpath :as cp]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(cp/add-classpath "tools")
(require '[render.lib :as l])

(def args (set *command-line-args*))
(def name* (:name l/cases))
(def out (if (args "--scratch") ".cache/render-scratch/render" (str "test/render/" name*)))
(def work (str (fs/absolutize (fs/create-temp-dir {:dir (do (fs/create-dirs ".cache") ".cache") :prefix "render-rec."}))))
(.addShutdownHook (Runtime/getRuntime) (Thread. (fn [] (fs/delete-tree work))))

(shell {:out :string :err :string} "kin/scripts/emit")
(shell "bb" "tools/pdfium/fetch.clj")
(def pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def cur (str (fs/real-path ".cache/pdfium/current")))
(println "pdfium" (:version pin) "at" cur)

(defn must-build [[prog err]]
  (when-not prog (println "FAIL build:\n" err) (System/exit 1))
  prog)

(defn record! [prog dir]
  (fs/delete-tree dir)
  (fs/create-dirs (fs/parent dir))
  (let [r (apply l/exec "." (l/abs prog) "record" (l/abs dir) (l/driver-args l/cases))]
    (when-not (zero? (:exit r)) (println "FAIL record: exit" (:exit r) (:out r) (:err r)) (System/exit 1))
    (print (:err r))
    (:out r)))

(def rust (must-build (l/build-rust work (str cur "/lib"))))
(def dump (record! rust out))
(when-not (args "--scratch")
  (spit (str "test/render/" name* ".dump") dump))
(println "recorded" out "(" (count (str/split-lines dump)) "lines of dump )")

(defn diff! [label a b]
  (let [r (l/exec "." (l/abs rust) "diff" (l/abs a) (l/abs b))]
    (println (str "--- " label))
    (print (:out r))
    (print (:err r))))

(when (args "--go")
  (let [go (must-build (l/build-go work cur))
        gout ".cache/render-scratch/go"
        gdump (record! go gout)]
    (println "go dump equals rust dump:" (= gdump dump))
    (diff! "rust vs go (same pdfium build)" out gout)))

(when (args "--compare-build")
  (shell "bb" "tools/pdfium/fetch.clj" "--previous")
  (let [{:keys [version platform]} (:previous pin)
        prev (str (fs/real-path (str ".cache/pdfium/" (str/replace version "/" "-") "/" platform)))
        prog (must-build (l/build-rust work (str prev "/lib")))
        pout ".cache/render-scratch/prev"]
    (record! prog pout)
    (diff! (str "pdfium " (:version pin) " vs " version) out pout)))
