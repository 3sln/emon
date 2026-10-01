(ns synth.outline
  "Heading text drawn as vector OUTLINES, made at development time from a system font (DEVLOG P6.6, D209).

      bb -cp tools:tools/lib -m synth.outline            (re)write tools/synth/outlines.edn

  `outlined-heading` has two headings that the PDF draws as filled paths, so the text layer has no word of
  them (the gold has their text): the case where only OCR (or the tracer) can read a heading. Chrome prints each
  heading's text in its font (a system font: Helvetica) to a one-line PDF, poppler's `pdftocairo -svg` turns
  its glyphs into SVG paths, and this keeps the glyph outlines and their placements. make_html.clj reads the
  result (outlines.edn), so regenerating the HTML needs neither Chrome nor poppler; only this script does.
  Tooling, never run by a gate. Needs Chrome (as print.sh finds it) and pdftocairo."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def OUT "tools/synth/outlines.edn")

;; name, text, font CSS: the headings of `outlined-heading` (and the font they were made from)
(def HEADINGS
  [["qr" "Quarterly Results" "bold 20pt Helvetica, Arial, sans-serif"]
   ["or" "Outlook and Risks" "bold 20pt Helvetica, Arial, sans-serif"]])

(defn- chrome []
  (or (System/getenv "CHROME")
      (first (filter #(and % (fs/exists? %))
                     ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                      "/Applications/Chromium.app/Contents/MacOS/Chromium"]))
      (throw (ex-info "no Chrome (set CHROME=...)" {}))))

(defn- sh! [& cmd]
  (let [r (apply p/sh cmd)] (when-not (zero? (:exit r)) (throw (ex-info (str "failed: " (str/join " " cmd) (:err r)) r))) (:out r)))

(defn- num [s] (Double/parseDouble s))
(defn- r2 [x] (/ (Math/round (* 100.0 x)) 100.0))

(defn outline [dir name text font]
  (let [html (str dir "/" name ".html") pdf (str dir "/" name ".pdf") svg (str dir "/" name ".svg")]
    (spit html (str "<!doctype html><meta charset=utf-8><style>@page{size:480px 40px;margin:0}body{margin:0}"
                    "div{font:" font ";line-height:40px;white-space:nowrap}</style><div>" text "</div>"))
    (p/sh (chrome) "--headless=new" "--disable-gpu" "--no-pdf-header-footer" "--disable-pdf-tagging"
          (str "--print-to-pdf=" pdf) (str "file://" (fs/canonicalize html)))
    (when-not (fs/exists? pdf) (throw (ex-info (str "Chrome wrote no PDF for " name) {})))
    (sh! "pdftocairo" "-svg" pdf svg)
    (let [s (slurp svg)
          [_ w h] (re-find #"<svg[^>]*width=\"([\d.]+)pt\"[^>]*height=\"([\d.]+)pt\"" s)
          glyphs (into {} (for [[_ id d] (re-seq #"<g id=\"(glyph[\w-]+)\">\s*<path d=\"([^\"]*)\"" s)] [id d]))
          uses (vec (for [[_ id x y] (re-seq #"<use xlink:href=\"#(glyph[\w-]+)\" x=\"([-\d.]+)\" y=\"([-\d.]+)\"" s)] [id (r2 (num x)) (r2 (num y))]))
          ids (distinct (map first uses))]
      (when (empty? uses) (throw (ex-info (str "no glyph uses in " svg) {})))
      {:name name :text text :font font :w (r2 (num w)) :h (r2 (num h))
       :defs (vec (for [id ids] [id (get glyphs id)]))
       :uses uses})))

(defn -main [& _]
  (let [dir (str (fs/create-temp-dir {:prefix "emon-outline"}))
        out (vec (for [[n t f] HEADINGS] (outline dir n t f)))]
    (spit OUT (str ";; Heading outlines of `outlined-heading` (P6.6, D209): made by tools/synth/outline.clj from "
                   (str/join "; " (map #(str (:font %)) out)) " (Chrome + pdftocairo).\n"
                   ";; {:name :text :font :w :h :defs [[glyph-id path-d]] :uses [[glyph-id x y]]}, in points.\n"
                   "[\n" (str/join "\n" (map pr-str out)) "\n]\n"))
    (fs/delete-tree dir)
    (println "wrote" OUT (count out) "outlines")))

(when (= *file* (System/getProperty "babashka.file")) (-main))
