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
(require '[clojure.java.io :as io]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def sdir (str root "/samples"))
(def runner (str root "/tools/accuracy/predict-emon-fusion-live.sh"))

;; id, source file, kind (:pdf or :img), group, one line for the index, extra driver flags for an assets variant
(def samples
  [{:id "gao-report" :src "gao-report.pdf" :group "Real-world reports" :title "GAO report (excerpt, 8 pages)"
    :note "Born-digital government report: cover, headings, running furniture, footnote-style notes, tables. US Government work, trimmed from 45 pages."}
   {:id "irs-pub15-excerpt" :src "irs-pub15-excerpt.pdf" :group "Real-world reports" :title "IRS Publication 15 (pages 1-10)"
    :note "The large real-world stand-in (a 10-K was not redistributable): dense multi-column publication text, lists, boxes, tables. Public domain."}
   {:id "columns-two" :src "columns-two.pdf" :group "Born-digital layout" :title "Two columns"
    :note "Synthetic two-column article: reading order across columns and pages."}
   {:id "footnotes" :src "footnotes.pdf" :group "Born-digital layout" :title "Footnotes"
    :note "Per-page footnotes tied to their marks and kept out of the body."}
   {:id "dropcap" :src "dropcap.pdf" :group "Born-digital layout" :title "Drop cap / initial"
    :note "A large initial letter that must rejoin its word instead of becoming a stray paragraph."}
   {:id "align-justify" :src "align-justify.pdf" :group "Born-digital layout" :title "Alignment and hyphenation"
    :note "Justified, ragged and centred paragraphs with hyphenated line ends; data-emon-align on blocks."}
   {:id "figures-charts" :src "figures-charts.pdf" :group "Figures" :title "Figures, charts and captions" :assets true
    :note "Charts with captions as figure nodes. The .assets.html variant (assets=figures) inlines each figure as a PNG."}
   {:id "invoice" :src "invoice.pdf" :group "Business documents" :title "Invoice"
    :note "Header block, bill-to, line-item table with right-aligned amounts, totals, a footer."}
   {:id "bank-statement" :src "bank-statement.pdf" :group "Business documents" :title "Bank statement (3 pages)"
    :note "A transaction table over three pages with its header repeated on each: one table, one thead."}
   {:id "irs-form-w9" :src "irs-form-w9.pdf" :group "Business documents" :title "IRS Form W-9 (pages 1-2)"
    :note "A real US tax form: fields, checkboxes, numbered lines, boxed parts. Public domain. (Form 1040 hit an internal overflow error and is left out; see README.)"}
   {:id "table-multipage" :src "table-multipage.pdf" :group "Tables" :title "Table across four pages"
    :note "A ruled table whose header row repeats per page (thead) and whose body continues."}
   {:id "table-zebra" :src "table-zebra.pdf" :group "Tables" :title "Zebra-striped table"
    :note "Rows told apart by shading only, no ruling lines."}
   {:id "table-spans" :src "table-spans.pdf" :group "Tables" :title "Spanning cells"
    :note "Partial rowspan/colspan: cells that span rows or columns."}
   {:id "table-unruled" :src "table-unruled.pdf" :group "Tables" :title "Unruled (whitespace) table"
    :note "No lines at all: columns found from alignment of the text."}
   {:id "uscourts-table" :src "uscourts-table.pdf" :group "Tables" :title "US Courts statistical table (4 pages)"
    :note "A real, wide statistical table with spanning headers and footnotes. US Government work."}
   {:id "watermark-draft" :src "watermark-draft.pdf" :group "Artifacts" :title "DRAFT watermark"
    :note "A diagonal DRAFT watermark: it must be recorded as a watermark and stay out of the body."}
   {:id "seal-stamp" :src "seal-stamp.pdf" :group "Artifacts" :title "Round seal and stamp"
    :note "A vector stamp and a seal: each becomes a seal node, its words stay out of the body."}
   {:id "signature" :src "signature.pdf" :group "Artifacts" :title "Signature over a name"
    :note "A handwritten-style signature across a typed name: a seal node of subkind signature, name still read."}
   {:id "tagged-table" :src "tagged-table.pdf" :group "Born-digital layout" :title "Tagged PDF (table)"
    :note "A tagged PDF: the structure tree (Table/TR/TH/TD) is the source of the table."}
   {:id "broken-tounicode" :src "broken-tounicode.pdf" :group "OCR fusion" :title "Broken ToUnicode font"
    :note "The text layer is wrong (unmapped glyphs); OCR fusion replaces it with what the page shows."}
   {:id "scan-plain" :src "scan-plain.pdf" :group "Scans" :title "Plain scan (two columns)"
    :note "Black-and-white raster only, no text layer: everything is OCR."}
   {:id "scan-color-invoice" :src "scan-color-invoice.pdf" :group "Scans" :title "Colour scan of an invoice"
    :note "A colour raster with paper tone; table recovered from OCR geometry."}
   {:id "scan-stamp-overlay" :src "scan-stamp-overlay.pdf" :group "Scans" :title "Scan with a stamp overlay"
    :note "A stamp printed over the text: the stamp should become a seal node, the body text survive."}
   {:id "image-invoice" :src "image-invoice.jpg" :kind :img :group "Raster images" :title "JPEG input (invoice)"
    :note "A bare JPEG as input (synthetic katanaml invoice, MIT / CC BY 4.0): same pipeline as a scan."}
   {:id "image-statement" :src "image-statement.png" :group "Raster images" :title "PNG input (statement, low resolution)"
    :note "A bare PNG (synthetic Bankstatemently preview, MIT) with a faint BANK DOCUMENT watermark; low resolution on purpose."}
   {:id "arabic-rtl" :src "arabic-rtl.pdf" :group "Scripts" :title "Arabic (right to left)"
    :note "RTL text: dir attributes and logical order, not the visual order of the glyphs."}
   {:id "cjk" :src "cjk.pdf" :group "Scripts" :title "CJK"
    :note "Chinese/Japanese text: no inter-word spaces, ideographic punctuation, CJK font."}])

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
