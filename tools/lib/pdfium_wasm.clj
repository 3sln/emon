;; THE PDFIUM-WASM GATE'S SHARED CODE (DEVLOG P11.4c): the fixtures, the drivers, the digests of a replay, and the live arm's environment and runs. Used by
;; `kin/scripts/pdfium-wasm` (the gate, `bb pdfium-wasm`) and `tools/pdfium_wasm/record.clj` (`bb pdfium-wasm-record`, not a gate).
;;
;;     (require '[pdfium-wasm :as pw])
(ns pdfium-wasm
  (:require [babashka.fs :as fs]
            [babashka.process :refer [process]]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def root "test/pdfium-wasm")
(def fixtures (edn/read-string (slurp (str root "/FIXTURES.edn"))))
(def docs (:docs fixtures))
(def stems (vec (sort (keys docs))))
(def pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def pdfium-dir (str ".cache/pdfium/" (str/replace (:version pin) "/" "-") "/wasm"))

(defn sha256 ^String [^bytes bs] (apply str (map #(format "%02x" (bit-and % 255)) (.digest (MessageDigest/getInstance "SHA-256") bs))))
(defn sha256-str [^String s] (sha256 (.getBytes s "UTF-8")))
(defn run [& cmd] @(apply process {:out :bytes :err :string} cmd))
(defn out-str [r] (String. ^bytes (:out r) "UTF-8"))

;; ------------------------------------------------------------------------------------------------ the native drivers (arm A)
(defn build-driver [script what on-fail]
  (let [r @(process {:out :string :err :string} script)]
    (if (zero? (:exit r))
      (str (fs/absolutize (str/trim (:out r))))
      (do (on-fail "the " what " driver did not build:\n" (:err r)) nil))))

(defn files-sha [dir]
  (if (fs/exists? dir) (into (sorted-map) (for [f (fs/list-dir dir)] [(fs/file-name f) (sha256 (fs/read-all-bytes f))])) (sorted-map)))

(defn replay-entry
  "What a driver makes of a document replaying a transcript at `--assets=figures --ocr=never`, as test/asset/figures/DIGESTS.edn digests it: the JSON, the HTML
  in three modes and every asset file. nil (and the failure text in :error) when it does not replay."
  [exe pdf transcript]
  (let [d (str (fs/create-temp-dir {:prefix "pdfium-wasm-assets."}))
        rep (fn [& more] (apply run exe "--ocr=never" "--assets=figures" (str "--replay=" transcript) (concat more [pdf])))
        r (rep (str "--asset-dir=" d))]
    (try
      (if-not (zero? (:exit r))
        {:error (:err r)}
        (let [html (fn [mode] (:out (rep "--out=html" mode)))]
          {:pdf pdf
           :json (sha256 (:out r))
           :html-inline (sha256 (html "--html=assets=inline"))
           :html-external (sha256 (html "--html=assets=external,prefix=img/"))
           :html-omit (sha256 (html "--html=assets=omit"))
           :assets (files-sha d)}))
      (finally (fs/delete-tree d)))))

;; ------------------------------------------------------------------------------------------------ the live arm (B)
(defn node-major []
  (try (parse-long (str/trim (:out @(process {:out :string :err :string} "node" "-p" "process.versions.node.split('.')[0]")))) (catch Exception _ nil)))

(defn live-env
  "The live arm's environment: {:wasm PATH :node [flags]} or {:skip WHY}. Node 22+, the emon-wasm-build toolchain, and pdfium's wasm build in .cache."
  []
  (let [major (node-major)]
    (cond
      (System/getenv "EMON_SKIP_WASM") {:skip "EMON_SKIP_WASM is set"}
      (nil? major) {:skip "no node on the PATH (the live arm is JavaScript)"}
      (< major 22) {:skip (str "the live arm needs Node 22 or newer; this is " major)}
      (not (and (fs/exists? (str pdfium-dir "/lib/pdfium.mjs")) (fs/exists? (str pdfium-dir "/lib/pdfium.wasm"))))
      {:skip (str "pdfium's wasm build is not in " pdfium-dir " (bb pdfium-fetch --platform wasm)")}
      :else
      (let [r @(process {:out :string :err :string} "kin/scripts/emon-wasm-build")]
        (case (:exit r)
          0 {:wasm (str (fs/absolutize (str/trim (:out r)))) :node (if (< major 25) ["--experimental-wasm-exnref"] [])}
          3 {:skip (str/trim (:err r))}
          {:fail (str "the wasm build failed:\n" (:err r))})))))

(defn node
  "Run test/driver/wasm/node.mjs; -> the parsed last line of its stdout, or {:error text}."
  [env & args]
  (let [r @(apply process {:out :string :err :string} "node" (concat (:node env) ["test/driver/wasm/node.mjs"] (map str args)))]
    (if (zero? (:exit r))
      (json/parse-string (last (str/split-lines (str/trim (:out r)))) true)
      {:error (str (:err r) (:out r))})))

(defn live-convert
  "One document converted live in Node (pdfium-wasm as `ports.render`, recording) into `out`; `native-args` add the comparison with the native run."
  [env stem out & native-args]
  (apply node env "convert" "--wasm" (:wasm env) "--pdfium" pdfium-dir "--pdf" (:pdf (docs stem)) "--out" out native-args))

(defn write-edn [file data]
  (spit file (with-out-str (clojure.pprint/pprint data))))
