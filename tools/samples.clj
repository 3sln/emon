#!/usr/bin/env bb
;; The comparison samples (samples/README.md): every source in samples/ converted LIVE by the Rust driver (the pinned pdfium and
;; Tesseract, default options, `ocr=always`), one conversion at a time, into
;;
;;     samples/NAME.html          the semantic HTML, byte for byte as emon wrote it
;;     samples/NAME.json          the Model (canonical JSON, compact)
;;     samples/NAME.assets.html   (figures-charts only) the same with assets=figures, the images inlined
;;
;; and samples/index.html, the static side-by-side viewer (source left, generated HTML right, link to the JSON), rebuilt from
;; the table below. NOT a gate: it needs the pinned pdfium (`bb pdfium-fetch`), libtesseract and cargo, and takes minutes.
;;
;;     bb samples [NAME ...]        (no names: every sample; the index is rebuilt either way)
;;
;; The sources are committed beside the outputs, so nothing here reads corpus/. The viewer's light styling lives ONLY in
;; index.html (each generated page is shown through `srcdoc` with a style element spliced in); the output files are untouched.
(require '[clojure.edn]
         '[clojure.java.io :as io]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def sdir (str root "/samples"))
(def runner (str root "/tools/accuracy/predict-emon-fusion-live.sh"))

;; id, source file, kind (:pdf or :img), group, one line for the index, extra driver flags for an assets variant
(def samples (clojure.edn/read-string (slurp (str sdir "/catalog.edn"))))

(defn convert! [{:keys [id src]} ext env-out flags dest]
  (let [tmp (str dest ".tmp")
        t0 (System/currentTimeMillis)
        _ (fs/create-dirs (str root "/.cache/samples-logs"))
        r (shell {:out (io/file tmp) :err (io/file (str root "/.cache/samples-logs/" id ".txt")) :continue true
                  :extra-env {"EMON_OUT" env-out "EMON_RS_FLAGS" flags}}
                 runner "x" "x" (str sdir "/" src) "x")]
    (if (zero? (:exit r))
      (do (fs/move tmp dest {:replace-existing true})
          (println (format "  %-26s %-12s %5.1fs  %8d bytes" id ext (/ (- (System/currentTimeMillis) t0) 1000.0) (fs/size dest)))
          true)
      (do (fs/delete-if-exists tmp)
          (println (format "  FAIL %s %s exit %s (see .cache/samples-logs/%s.txt)" id ext (:exit r) id))
          false))))

;; ---- the viewer ---------------------------------------------------------------------------------------------------

(def viewer-css
  "html{font:15px/1.5 system-ui,sans-serif;color:#222;background:#fff}body{margin:1em 1.5em;max-width:60em}
h1,h2,h3,h4{line-height:1.25}table{border-collapse:collapse;margin:1em 0}th,td{border:1px solid #bbb;padding:.2em .5em;vertical-align:top}
th{background:#f0f0f0}aside[data-emon=furniture]{color:#888;font-size:.85em;border-left:3px solid #ddd;padding-left:.5em;margin:.5em 0}
figure{border:1px solid #ddd;padding:.5em;margin:1em 0}figcaption{color:#555;font-size:.9em}img{max-width:100%}
[data-emon-kind=seal],[data-emon=seal]{outline:1px dashed #c33}[hidden]{display:block!important;opacity:.6}")

(defn esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(defn srcdoc [html]
  (let [style (str "<style>" viewer-css "</style>")
        i (str/index-of html "<head>")
        spliced (if i
                  (str (subs html 0 (+ i 6)) style (subs html (+ i 6)))
                  (str style html))]
    (esc spliced)))

(defn source-pane [{:keys [src kind title]}]
  (if (= kind :img)
    (str "<div class=\"img\"><img loading=\"lazy\" src=\"" src "\" alt=\"" (esc title) "\"></div>")
    (str "<iframe loading=\"lazy\" src=\"" src "\" title=\"source: " (esc title) "\"></iframe>")))

(defn section [{:keys [id src title note assets] :as s}]
  (let [html-file (str sdir "/" id ".html")
        body (when (fs/exists? html-file) (slurp html-file))]
    (str "<section id=\"" id "\"><h2>" (esc title) "</h2><p class=note>" (esc note) "</p>"
         "<p class=links><a href=\"" src "\">source</a> <a href=\"" id ".html\">" id ".html</a> <a href=\"" id ".json\">" id ".json</a>"
         (when assets (str " <a href=\"" id ".assets.html\">" id ".assets.html</a>")) " <a href=\"#top\">top</a></p>"
         "<div class=pair><div><h3>Source</h3>" (source-pane s) "</div>"
         "<div><h3>emon HTML</h3>"
         (if body
           (str "<iframe loading=\"lazy\" sandbox title=\"emon: " (esc title) "\" srcdoc=\"" (srcdoc body) "\"></iframe>")
           "<p>not generated yet (bb samples)</p>")
         "</div></div></section>\n")))

(defn index-html []
  (let [groups (partition-by :group samples)]
    (str "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
         "<title>emon samples</title><style>"
         "html{font:15px/1.5 system-ui,sans-serif;color:#222;background:#fafafa}body{margin:0 auto;padding:1em 1.5em;max-width:110em}"
         "h1{margin-bottom:.2em}nav ul{columns:3 18em;list-style:none;padding:0}nav li{break-inside:avoid;margin:.1em 0}nav h3{margin:.6em 0 .1em;font-size:.95em;color:#555}"
         "section{margin:2.5em 0;padding-top:1em;border-top:2px solid #ccc}.note{margin:.2em 0}.links a{margin-right:1em;font-size:.9em}"
         ".pair{display:grid;grid-template-columns:1fr 1fr;gap:1em}.pair>div{min-width:0}.pair h3{margin:.3em 0;font-size:.9em;color:#555}"
         "iframe{width:100%;height:80vh;border:1px solid #bbb;background:#fff}.img{height:80vh;overflow:auto;border:1px solid #bbb;background:#fff}.img img{max-width:100%}"
         "@media(max-width:60em){.pair{grid-template-columns:1fr}}"
         "</style></head><body id=top><h1>emon samples</h1>"
         "<p>Each source (left) next to the semantic HTML emon generated for it (right) and the Model as JSON. Generated by "
         "<code>bb samples</code> with the pinned pdfium and Tesseract at default options; see <a href=README.md>README.md</a> for the commands, "
         "what to look at and the known weak spots. The right-hand pane carries light styling added by this page only; the "
         "<code>.html</code> files themselves are emon's bytes, unstyled.</p><nav><h2>Samples</h2><ul>"
         (apply str (for [g groups]
                      (str "<li><h3>" (esc (:group (first g))) "</h3><ul>"
                           (apply str (for [s g] (str "<li><a href=\"#" (:id s) "\">" (esc (:title s)) "</a></li>")))
                           "</ul></li>")))
         "</ul></nav>\n"
         (apply str (map section samples))
         "</body></html>\n")))

(defn -main [names]
  (when-not (fs/exists? (str root "/.cache/pdfium/current/lib"))
    (println "no pinned pdfium: run `bb pdfium-fetch` first") (System/exit 1))
  (let [todo (if (seq names) (filter #((set names) (:id %)) samples) samples)
        failed (atom 0)]
    (doseq [s todo]
      (let [p (str sdir "/" (:id s))]
        (when-not (and (convert! s "json" "json" "" (str p ".json"))
                       (convert! s "html" "html" "" (str p ".html")))
          (swap! failed inc))
        (when (:assets s)
          (when-not (convert! s "assets.html" "html" "--assets=figures" (str p ".assets.html"))
            (swap! failed inc)))))
    (spit (str sdir "/index.html") (index-html))
    (println "samples/index.html written;" (count todo) "sample(s)," @failed "failure(s)")
    (System/exit (if (zero? @failed) 0 1))))

(-main *command-line-args*)
