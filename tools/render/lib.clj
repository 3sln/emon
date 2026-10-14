;; Shared by `kin/scripts/render` (the gate) and `tools/render/record.clj` (live): the case list, and building
;; the Rust and Go render drivers in a private temp dir.   (DEVLOG P6.2, D127)
(ns render.lib
  (:require [babashka.fs :as fs]
            [babashka.process :refer [process shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def cases (edn/read-string (slurp "test/render/cases.edn")))

(defn abs [p] (str (fs/absolutize p)))

(defn exec [dir & cmd]
  (let [r @(apply process {:dir dir :out :string :err :string} cmd)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn driver-args
  "The driver's `--pdf`/`--req` arguments for a named case set."
  [{:keys [pdfs reqs]}]
  (concat (mapcat (fn [p] ["--pdf" (abs p)]) pdfs)
          (mapcat (fn [r] ["--req" r]) reqs)))

(defn build-rust
  "Build test/render/rust/main.rs in `work`; with `pdfium-lib` (a dir) it is built `--cfg pdfium` and linked
  with libpdfium there. Answers [path-or-nil error-text]."
  [work pdfium-lib]
  (let [d (str work "/rust" (when pdfium-lib (str "-live-" (Math/abs (hash pdfium-lib)))))]
    (fs/create-dirs (str d "/emon"))
    (doseq [f (fs/glob "gen/rust/emon" "*.rs")] (fs/copy f (str d "/emon/" (fs/file-name f)) {:replace-existing true}))
    (doseq [f ["port.rs" "testport.rs" "plan.rs" "source.rs" "render.rs" "pdfium.rs"]]
      (fs/copy (str "host/rust/src/" f) (str d "/" f) {:replace-existing true}))
    (spit (str d "/emon.rs")
          (apply str (for [f (sort (map fs/file-name (fs/glob "gen/rust/emon" "*.rs")))]
                       (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
    (fs/copy "test/render/rust/main.rs" (str d "/main.rs") {:replace-existing true})
    (let [r (apply exec d "rustc" "--edition" "2021" "-O" "-A" "warnings" "-o" "prog" "main.rs"
                   (when pdfium-lib
                     ["--cfg" "pdfium" "-L" (str "native=" pdfium-lib) "-C" (str "link-arg=-Wl,-rpath," pdfium-lib)]))]
      (if (zero? (:exit r)) [(str d "/prog") nil] [nil (:err r)]))))

(defn build-go
  "Build test/render/go/main.go in `work`; with `pdfium-dir` (the unpacked archive) it is built with the
  `pdfium` tag (the binding loads the library at run time: P11.x1, D1450)."
  [work pdfium-dir]
  (let [d (str work "/go" (when pdfium-dir (str "-live-" (Math/abs (hash pdfium-dir)))))]
    (fs/copy-tree "gen/go" d {:replace-existing true})
    (fs/create-dirs (str d "/port"))
    (doseq [f (fs/glob "host/go/port" "*.go")] (fs/copy f (str d "/port/" (fs/file-name f)) {:replace-existing true}))
    (fs/create-dirs (str d "/pngenc"))
    (fs/copy "host/go/pngenc/pngenc.go" (str d "/pngenc/pngenc.go") {:replace-existing true})
    (fs/create-dirs (str d "/planrun"))
    (doseq [f (fs/glob "host/go/planrun" "*.go")] (fs/copy f (str d "/planrun/" (fs/file-name f)) {:replace-existing true}))
    (fs/create-dirs (str d "/pdfium"))
    (doseq [f (fs/glob "host/go/pdfium" "*.go")] (fs/copy f (str d "/pdfium/" (fs/file-name f)) {:replace-existing true}))
    (spit (str d "/go.mod") "module emon\n\ngo 1.21\n")
    (doseq [f (fs/glob "test/render/go" "*.go")] (fs/copy f (str d "/" (fs/file-name f)) {:replace-existing true}))
    ;; P11.x1, D1450: pdfium is LOADED at run time (host/go/pdfium: cgo for dlopen only), so nothing is linked and no header is read;
    ;; the program finds the library as the other hosts do (EMON_PDFIUM_LIB, else .cache/pdfium/current/lib from the repository root).
    (let [r (if pdfium-dir
              @(process {:dir d :out :string :err :string :extra-env {"CGO_ENABLED" "1"}}
                        "go" "build" "-tags" "pdfium" "-o" "prog" ".")
              (exec d "go" "build" "-o" "prog" "."))]
      ;; Go's linker rewrites the image after cgo's ad-hoc signature on macOS, and the kernel then kills the
      ;; binary (exit 137): sign it again.
      (when (and pdfium-dir (zero? (:exit r)) (str/includes? (System/getProperty "os.name") "Mac"))
        (exec d "codesign" "-f" "-s" "-" "prog"))
      (if (zero? (:exit r)) [(str d "/prog") nil] [nil (:err r)]))))
