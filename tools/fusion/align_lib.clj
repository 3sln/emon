;; Shared by `kin/scripts/align` (the gate) and `tools/fusion/record-align.clj` (live): building the Rust and Go alignment drivers
;; (test/fusion/align/{rust,go}) in a private temp dir.   (DEVLOG P7.1, D250-D252)
(ns fusion.align-lib
  (:require [babashka.fs :as fs]
            [babashka.process :refer [process]]
            [clojure.string :as str]))

(def ids ["h-numbered" "columns-two" "table-simple-ruled" "watermark-draft" "stamp-seal" "tl-broken-shift"])

;; Three more, recorded the same way and held to identity only (their numbers are the entry's): a text layer whose spaces and letters
;; are unmapped (one U+FFFD word a line), an older OCR layer hidden under a scan (the `hidden-text` source), and right-to-left Hebrew
;; that the English model cannot read (alignment by geometry in a right-to-left line).
(def extra-ids ["tl-broken-unmapped" "hidden-older-ocr" "align-rtl"])
(def all-ids (into ids extra-ids))

(defn abs [p] (str (fs/absolutize p)))

(defn exec [dir & cmd]
  (let [r @(apply process {:dir dir :out :string :err :string} cmd)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn gen-modules
  "The generated Rust modules a driver needs: every one that is not a law (`*test`) or a probe other than `alignprobe`."
  []
  (filter (fn [f] (let [n (str/replace (fs/file-name f) #"\.rs$" "")]
                    (and (not (str/ends-with? n "test"))
                         (or (not (str/ends-with? n "probe")) (= n "alignprobe")))))
          (fs/glob "gen/rust/emon" "*.rs")))

(defn build-rust
  "Build test/fusion/align/rust/main.rs in `work`. With `live` ({:pdfium-lib dir}) it is built `--cfg pdfium --cfg live`, linked
  with libpdfium and libtesseract. Answers [path-or-nil error-text]."
  [work live]
  (let [d (str work "/rust" (when live "-live"))
        mods (gen-modules)]
    (fs/create-dirs (str d "/emon"))
    (doseq [f mods] (fs/copy f (str d "/emon/" (fs/file-name f)) {:replace-existing true}))
    (doseq [f ["port.rs" "testport.rs" "plan.rs" "source.rs" "render.rs" "pdfium.rs" "tess.rs"]]
      (fs/copy (str "host/rust/src/" f) (str d "/" f) {:replace-existing true}))
    (spit (str d "/emon.rs")
          (apply str (for [f (sort (map fs/file-name mods))] (str "pub mod " (str/replace f #"\.rs$" "") ";\n"))))
    (fs/copy "test/fusion/align/rust/main.rs" (str d "/main.rs") {:replace-existing true})
    (let [r (apply exec d "rustc" "--edition" "2021" "-O" "-A" "warnings" "-o" "prog" "main.rs"
                   (when live
                     ["--cfg" "pdfium" "--cfg" "live" "-L" "native=/opt/homebrew/lib"
                      "-L" (str "native=" (:pdfium-lib live)) "-C" (str "link-arg=-Wl,-rpath," (:pdfium-lib live))]))]
      (if (zero? (:exit r)) [(str d "/prog") nil] [nil (:err r)]))))

(defn build-go
  "Build test/fusion/align/go/main.go in `work`. Answers [path-or-nil error-text]."
  [work]
  (let [d (str work "/go")]
    (fs/copy-tree "gen/go" d {:replace-existing true})
    (fs/create-dirs (str d "/port"))
    (doseq [f (fs/glob "host/go/port" "*.go")] (fs/copy f (str d "/port/" (fs/file-name f)) {:replace-existing true}))
    (fs/create-dirs (str d "/pngenc"))
    (fs/copy "host/go/pngenc/pngenc.go" (str d "/pngenc/pngenc.go") {:replace-existing true})
    (spit (str d "/go.mod") "module emon\n\ngo 1.21\n")
    (fs/copy "test/fusion/align/go/main.go" (str d "/main.go") {:replace-existing true})
    (let [r (exec d "go" "build" "-o" "prog" ".")]
      (if (zero? (:exit r)) [(str d "/prog") nil] [nil (:err r)]))))
