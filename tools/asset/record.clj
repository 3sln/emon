#!/usr/bin/env bb
;; Record the codec images of the asset fixtures LIVE: DCT and CCITT by the Rust host's own libraries, JPX by OpenJPEG and JBIG2 by
;; jbig2dec.                                                                          (DEVLOG P6.1a, D117, D336)
;;     bb asset-record [--lib=DIR] [--only STEM]
;;
;; NOT a gate: it needs libopenjp2 and libjbig2dec (Homebrew's `openjpeg` and `jbig2dec`; DIR is the directory of the libraries, default
;; /opt/homebrew/lib) and links the Rust Engine to them (`EMON_RS_CODECS=DIR kin/scripts/emon-rs-build`, `--cfg codecs`). It converts every
;; document of test/assets and the synthetic documents `bb asset` uses with `--assets=images --codecs --record=DIR` and keeps the
;; transcript in test/assets/transcripts/<stem>/ (transcript.edn and one PNG per decoded image) when the run asked the host for anything
;; (a `decode-img`: a DCT image that cannot pass through, any JPX, JBIG2 or CCITT image); a document that asks nothing has no transcript.
;;
;; A TRANSCRIPT NEVER HOLDS THE CODESTREAM: it holds the request (which names the bytes by SHA-256) and the answer (the pixels, as
;; canonical PNG), so what `bb asset`, `bb identity` and `bb unit` replay is what OpenJPEG and jbig2dec said, on Rust and on Go (whose own
;; live answer for JPX and JBIG2 is `unsupported`). `test/assets/transcripts/producer.txt` says which libraries and builds.
(require '[babashka.fs :as fs]
         '[babashka.process :as p :refer [shell]]
         '[clojure.string :as str])

(def args *command-line-args*)
(defn opt [k] (some #(when (str/starts-with? % (str k "=")) (subs % (inc (count k)))) args))
(def lib (or (opt "--lib") "/opt/homebrew/lib"))
(def only (some->> (map-indexed vector args) (some (fn [[i a]] (when (= a "--only") (nth args (inc i) nil))))))
(def out-root "test/assets/transcripts")

(doseq [l ["libopenjp2" "libjbig2dec"]]
  (when-not (seq (fs/glob lib (str l ".*")))
    (println "FAIL no" l "in" lib "(brew install openjpeg jbig2dec, or pass --lib=DIR)")
    (System/exit 1)))

(shell {:out :string :err :string} "kin/scripts/emit")
(def build
  (p/shell {:out :string :err :string :continue true :extra-env {"EMON_RS_CODECS" lib}} "kin/scripts/emon-rs-build"))
(when-not (zero? (:exit build))
  (println "FAIL the codecs build:\n" (:err build))
  (System/exit 1))
(def rust (str (fs/absolutize (str/trim (:out build)))))

(def docs
  (concat (sort (map str (fs/glob "test/assets" "*.pdf")))
          (map #(str "test/corpus/synth/pdf/" % ".pdf")
               ["figures-charts" "figures-logo-screenshot" "initial-image" "gfx-image-rules" "mixed-table-image-shaded"
                "mixed-table-image" "columns-span-figure"])))

(def kept (atom 0))
(def dropped (atom 0))
(fs/create-dirs out-root)
(doseq [pdf docs
        :let [stem (fs/strip-ext (fs/file-name pdf))]
        :when (and (fs/exists? pdf) (or (nil? only) (= only stem)))]
  (let [dir (str out-root "/" stem)
        tmp (str (fs/create-temp-dir {:prefix "assetrec"}))
        r (p/shell {:out :string :err :string :continue true} rust "--assets=images" "--codecs" "--out=json" (str "--record=" tmp "/t") pdf)]
    (fs/delete-tree dir)
    (cond
      (not (zero? (:exit r))) (println "FAIL" pdf (:err r))
      (and (fs/exists? (str tmp "/t/transcript.edn"))
           (re-find #":kind :decode-img" (slurp (str tmp "/t/transcript.edn"))))
      (do (fs/copy-tree (str tmp "/t") dir) (swap! kept inc))
      :else (swap! dropped inc))
    (fs/delete-tree tmp)))

(def versions
  (let [v (fn [& cmd] (try (str/trim (:out (apply p/shell {:out :string :err :string :continue true} cmd))) (catch Exception _ "")))]
    (str "OpenJPEG " (or (not-empty (v "pkg-config" "--modversion" "libopenjp2")) (str "(libopenjp2 in " lib ")")) "\n"
         "jbig2dec " (or (not-empty (v "pkg-config" "--modversion" "jbig2dec")) (str "(libjbig2dec in " lib ")")) "\n"
         "zune-jpeg 0.5.15 (DCT)\n"
         "fax 0.2.7 (CCITT code tables)\n")))
(spit (str out-root "/producer.txt") versions)
(println "recorded" @kept "transcripts into" out-root "(" @dropped "documents asked the host for nothing )")
