#!/usr/bin/env bb
;; `bb docs`: the user documentation's GENERATED parts, and the checks that hold the rest (P11.6, DEVLOG D549). NOT a gate: a step of the fast core.
;;
;;   bb docs            rewrite the generated parts of README.md, CONTRIBUTING.md, docs/*.md and docs/hosts/*.md
;;   bb docs --check    fail (exit 1) when a committed file differs from what `bb docs` would write, or a check below fails; changes nothing
;;   bb docs --self-test  the checks catch what they are for, on strings in memory
;;
;; WHAT IS GENERATED, AND FROM WHAT (a marked region of a Markdown file is replaced; everything outside the markers is the author's):
;;   <!-- bb docs:begin NAME -->  ...  <!-- bb docs:end NAME -->     NAME is one of `generators` below:
;;       options-table, limits-table, html-options-table, outputs   api/surface.edn (the frozen `key=value` options, limits, --html words, formats)
;;                                                                  and kin/config.kin's `cf-defaults` (the defaults a parse of the empty text gives)
;;       model-*                                                    schema/emon-1.json (every property of every definition, its type, its values)
;;       api-index                                                  api/surface.edn (the :public and :provisional entries of the host the file is for)
;;   <!-- snippet: docs/snippets/HOST/FILE -->  + a fenced block    the block is the file (or its `docs:begin`/`docs:end` regions). `bb package --check`
;;                                                                  COMPILES AND RUNS that same file against the built artifact (tools/package.clj).
;;   <!-- excerpt: samples/X.html | START | END -->  + a fence     the text of a committed sample from START to END, with line breaks and indentation added.
;;   <!-- unverified: WHY -->  + a fence                           a code block nothing compiles or runs; WHY says what would be needed.
;;
;; THE CHECKS (all of them also run without --check, and print FAIL lines):
;;   * every fenced block of code (rust go java csharp js c clojure sh toml xml) is preceded by one of the three markers above;
;;   * every relative link of the documentation points at a file that exists;
;;   * every flag of `emon convert` in api/surface.edn is in docs/cli.md; every `emon-X.Y.Z-` in the documentation is the version of version.edn;
;;   * every `data-emon-*` attribute and every element of samples/*.html is named in docs/model.md; the README links every page of the documentation;
;;   * every option key, limit and HTML word of the surface has a description here (a new one fails until it is documented);
;;     a Model property or kind without one is a WARN line, not a failure (the table still lists it, marked "not described yet").
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def hosts [:rust :go :java :csharp :js :c :pod])
(def demo-url "https://emon.3sln.com/demo/") ; the browser demo (GitHub Pages, custom domain)
(def code-langs #{"rust" "go" "java" "csharp" "js" "c" "clojure" "sh" "toml" "xml"})
(def md-files (fn [] (vec (concat ["README.md" "CONTRIBUTING.md" "docs/model.md" "docs/options.md" "release/pkg/README.md"]
                                  (map #(str "docs/hosts/" (name %) ".md") hosts)))))
(def link-files (fn [] (vec (concat (md-files) ["docs/cli.md" "docs/releasing.md"]))))

(def problems (atom []))
(defn fail! [& xs] (swap! problems conj (apply str xs)))
(def warns (atom []))
(defn warn! [& xs] (swap! warns conj (apply str xs)))

;; ------------------------------------------------------------------------------------------------------------------ inputs
(def surface (delay (edn/read-string (slurp "api/surface.edn"))))
(def schema (delay (json/parse-string (slurp "schema/emon-1.json"))))
(def version (delay (:version (edn/read-string (slurp "version.edn")))))

(defn rows
  "The [tier id sig] rows of the surface for one host (a keyword)."
  [host]
  (for [[h tier id sig] @surface :when (= h host)] [tier id sig]))

(defn cell [s] (-> (str s) (str/replace "|" "\\|") (str/replace "\n" " ")))
(defn code [s] (if (str/blank? (str s)) "" (str "`" (str/replace (str s) "`" "'") "`")))
(defn table
  "A GitHub table; rows are vectors of already-escaped cells."
  [header body]
  (str/join "\n" (concat [(str "| " (str/join " | " header) " |")
                          (str "|" (str/join "|" (repeat (count header) "---")) "|")]
                         (map #(str "| " (str/join " | " %) " |") body))))

;; ------------------------------------------------------------------------------------------------------------------ options and limits
(def option-docs
  {"locale" "A BCP 47 language hint (`en`, `de`, `fr-CA`), written to the Model's `settings.locale`. It decides how a drop cap joins its first word when the letter could be a word of its own."
   "ocr" "When pages are read with OCR. `always`: every page, born-digital ones too (the text layer and the reading are fused). `auto`: only a page whose text layer is unhealthy, that carries images, or that hides text. `never`: no OCR. With no renderer or no OCR engine configured a request is answered `unsupported` and the text layer stands alone."
   "furniture" "What to do with running headers, footers and page numbers. `policy`: drop what the output already says and keep the rest as asides. `keep`: leave all of it in the flow. `drop`: take all of it out (the ledger says what)."
   "assets" "Pixels for figures, seals and signatures. `none`: no pixels. `images`: a figure or seal that is one embedded image gets it. `figures`: also the others, cropped from a render (needs a renderer)."
   "asset-dpi" "Resolution of the renders cropped for `assets=figures`."
   "asset-pixels" "Most pixels one asset may have (the default is 2048 x 2048)."
   "asset-bytes" "Most encoded bytes one asset may have (the default is 4 MiB)."
   "assets-bytes" "Most encoded bytes all the assets of a document may have (the default is 32 MiB)."
   "asset-signatures" "`on` keeps the pixels of handwritten signatures and initials as assets; `off` leaves them out."
   "keep-evidence" "Keep the text layer's evidence dump, `Model.evidence()`: a debugging aid."
   "ocr-scope" "What `ocr=always` reads of a born-digital page. `full`: the whole render, text included. `witness`: the render without its text plus a few witness lines of each font (a fraction of the OCR time; a font whose witnesses disagree with its text escalates the page to `full`)."
   "max-rounds" "Most plan/fulfil rounds one page unit may take (the engine asks the ports for renders and OCR in rounds)."
   "fetch-rounds" "Most rounds of range reads a unit may take on a source that answers asynchronously (a browser Blob, a URL). Reaching it is the error `fetch-rounds`."
   "record" "Keep a transcript of the conversion's port answers, so it can be replayed without the ports. A session option: the one-shot calls of the C library refuse it."
   "js-ocr" "A binding switch, not for the native hosts: the caller owes the `ocr` requests and answers them itself (the browser SDK and the C session API)."
   "js-render" "The same for `render` and `crop` requests (the browser's pdfium, the C session API)."
   "chunk" "Chunk size, in bytes, of the cache the engine reads a byte source through (it reads a source in aligned chunks, only the ones it touches)."
   "limit." "`limit.NAME=N` sets a named limit for this conversion (`limit.pages=50`); see the limits below. May be given for several names."
   "producer." "`producer.NAME=VALUE` is written into a recorded transcript to say what produced it. The engine does not read it."
   "parallel" "How many workers one conversion may spread its fulfilment over. 1 is serial. The limit `workers` caps it. The Model is byte-identical at any N."})

;; the field of kin's `cf-defaults` each option key is set by
(def default-field
  {"ocr" "ocr" "ocr-scope" "scope" "furniture" "furn" "assets" "level" "asset-dpi" "dpi" "asset-pixels" "pixels" "asset-bytes" "abyte"
   "assets-bytes" "tbyte" "asset-signatures" "sigs" "keep-evidence" "keep" "chunk" "chunk" "max-rounds" "maxr" "fetch-rounds" "fetchr"
   "record" "rec" "js-ocr" "jsocr" "js-render" "jsrender" "parallel" "par"})

(def limit-docs
  {"input-bytes" "Size of one input, in bytes."
   "stream-bytes" "Decoded size of one PDF stream."
   "total-bytes" "Decoded bytes of all the streams of a document."
   "objects" "PDF objects in one file."
   "ref-depth" "How deep a chain of indirect references is followed; a deeper one reads as absent."
   "operators" "Content-stream operators run for a document."
   "form-depth" "How deep form XObjects nest (at most 32); a deeper form is skipped, with a warning."
   "pattern-depth" "Named for a later feature; nothing reads it yet."
   "pages" "Pages in a document."
   "pixels" "Pixels of one decoded image or rendered page; also bounds what a renderer may draw."
   "ocr-requests" "OCR requests in one conversion."
   "note-bodies" "Footnote and endnote bodies kept; later ones are dropped."
   "note-refs" "Note references followed; later ones are dropped."
   "asset-placements" "Image placements considered for assets; later ones are dropped."
   "fonts-per-doc" "Fonts tracked in a document (at most 512); a font past it has no evidence id and its page is flagged."
   "stage-ms" "Milliseconds one unit of the plan loop may run; 0 is off."
   "workers" "Most workers `parallel=N` may use; a larger N is served with this many."})

(def html-docs
  {"ids" "`id=\"emon-N\"` on every node."
   "geometry" "`data-emon-at` (the regions) on nodes, and `data-emon-col=\"page:band:col\"` on blocks."
   "confidence=N" "`data-emon-conf` on every node whose confidence is below N (0..1000)."
   "ledger" "The dropped items, as inert `<template data-emon=\"dropped\">` elements."
   "pretty" "Indented, one element to a line."
   "drop-furniture" "Leave out the running headers and footers kept as asides."
   "no-milestones" "Leave out the empty page-milestone `<span>`s."
   "initials-plain" "Write a drop cap as the bare text, without its `<span data-emon=\"initial\">`."
   "align-style" "Also write `style=\"text-align:…\"` and `dir=\"auto\"` next to `data-emon-align`."
   "assets=inline" "Assets as `data:` URIs in `<img src>` (the default)."
   "assets=external" "`<img src>` is `prefix=` plus the asset's file name; the caller writes the files."
   "assets=omit" "Figures as if no asset existed."
   "prefix=TEXT" "The prefix for `assets=external` (default: none)."})

(def output-docs
  {"json" "The Model as canonical JSON (compact, or indented)."
   "html" "The Model as semantic HTML."
   "evidence" "The text layer's evidence dump (with `keep-evidence=1`)."
   "intake" "Stage 0 alone: format, version, page count, encryption, size. Cheap."})

(defn cf-defaults []
  (let [t (slurp "kin/config.kin")
        body (re-find #"(?s)\(defn \^:pub \^I32 cf-defaults.*?\(return 0\)\)" t)]
    (into {} (for [[_ f v] (re-seq #"\(set \(fd c (\w+)\) (-?\d+)\)" (or body ""))] [f (parse-long v)]))))

(defn option-rows []
  (let [opts (for [[_ id sig] (rows :options) :when (str/starts-with? id "option ")] [(subs id 7) sig])
        keys* (for [[n sig] opts :when (not (str/includes? n "="))] [n sig])
        words (fn [k] (->> opts
                           (keep (fn [[n sig]] (when-let [[_ w] (re-matches (re-pattern (str (java.util.regex.Pattern/quote k) "=(.*)")) n)]
                                                 [w (parse-long (second (re-find #"code (\d+)" sig)))])))
                           (sort-by second >)))
        defaults (cf-defaults)]
    (for [[k sig] keys*]
      (let [ws (words k)
            field (default-field k)
            dv (when field (get defaults field))]
        (when (and field (nil? dv)) (fail! "docs: no default for option `" k "` in kin/config.kin's cf-defaults (tools/docs/docs.clj default-field)"))
        (when (and (nil? field) (not (str/ends-with? k ".")) (not= k "locale")) (fail! "docs: option `" k "` has no default-field entry (tools/docs/docs.clj)"))
        (when-not (get option-docs k) (fail! "docs: option `" k "` is in api/surface.edn but has no description (tools/docs/docs.clj option-docs)"))
        {:key k :sig sig :words ws :default dv :field field}))))

(defn options-table []
  (table ["Key" "Value" "Default" "Meaning"]
         (for [{:keys [key sig words default]} (option-rows)]
           [(code (if (str/ends-with? key ".") (str key "NAME") key))
            (cond
              (seq words) (str/join ", " (map (comp code first) words))
              (str/starts-with? sig "number") (str "integer " (second (re-find #"number (.*)" sig)))
              (= sig "flag") "`1` or `0`"
              (= sig "string") "text"
              (str/starts-with? sig "prefix") (code (second (re-find #"prefix: (.*)" sig)))
              :else sig)
            (cond
              (seq words) (or (some-> (first (filter #(= default (second %)) words)) first code) "")
              (= sig "flag") (code (str default))
              (= sig "string") "none"
              (str/starts-with? sig "prefix") "none"
              :else (code (str default)))
            (cell (option-docs key))])))

(defn limits-table []
  (table ["Limit" "Default" "Range" "At the bound" "Meaning"]
         (for [[_ id sig] (rows :options) :when (str/starts-with? id "limit ")
               :let [n (subs id 6)
                     [_ kind dflt lo hi hit] (re-find #"kind (\d+) default (\d+) range (\d+)\.\.(\d+) (\w+)" sig)]]
           (do (when-not (get limit-docs n) (fail! "docs: limit `" n "` is in api/surface.edn but has no description (tools/docs/docs.clj limit-docs)"))
               [(code (str "limit." n))
                (code dflt)
                (str lo ".." hi)
                (case hit
                  "breach" (str "error " (code n))
                  "budget" "the work stops there, the conversion goes on"
                  "reserved" "named, nothing reads it yet"
                  hit)
                (cell (limit-docs n))]))))

(defn html-options-table []
  (table ["Word" "Effect"]
         (for [[_ id _] (rows :options) :when (str/starts-with? id "html-option ")
               :let [w (subs id 12)
                     w (str/replace w #"confidence=N" "confidence=N")]]
           (do (when-not (get html-docs w) (fail! "docs: HTML option `" w "` is in api/surface.edn but has no description (tools/docs/docs.clj html-docs)"))
               [(code w) (cell (html-docs w))]))))

(defn outputs-table []
  (table ["Format" "What it is"]
         (for [[_ id _] (rows :options) :when (str/starts-with? id "output ")
               :let [w (subs id 7)]]
           (do (when-not (get output-docs w) (fail! "docs: output `" w "` is in api/surface.edn but has no description (tools/docs/docs.clj output-docs)"))
               [(code w) (cell (output-docs w))]))))

(def error-docs
  {"EMON_ERR_USAGE" ["`usage`" "A bad argument or option text."]
   "EMON_ERR_NO_INPUT" ["`no-input`" "Nothing was given to convert."]
   "EMON_ERR_LIMIT" ["the limit's name: `input-bytes`, `pages`, `objects`, `pixels`, `fetch-rounds`, ..." "A named limit was reached; the error carries the limit and the value reached."]
   "EMON_ERR_PASSWORD_REQUIRED" ["`password-required`" "The PDF is encrypted and no password was given."]
   "EMON_ERR_PASSWORD_INCORRECT" ["`password-incorrect`" "The password does not open the PDF."]
   "EMON_ERR_ENCRYPTION_UNSUPPORTED" ["`encryption-unsupported`" "A security handler emon does not read."]
   "EMON_ERR_UNSUPPORTED_FORMAT" ["`unsupported-format`" "Not a PDF, PNG, JPEG or TIFF."]
   "EMON_ERR_DAMAGED" ["a PDF status: `no-startxref`, `bad-xref`, `no-root`, `no-pages`, ...; for an image `undecodable` or `unsupported-image`" "A PDF emon could not open, or an image it could not decode."]
   "EMON_ERR_SOURCE_CHANGED" ["`source-changed`" "The byte source gave fewer bytes than its length said (or, for a URL, changed under the read)."]
   "EMON_ERR_IO" ["`io`" "The byte source's read failed."]
   "EMON_ERR_UNSUPPORTED" ["`unsupported`" "Specified, not implemented."]
   "EMON_ERR_PLAN" ["`transcript-miss`, a bad transcript, ..." "The plan/fulfil loop's own errors, met in replays and with custom ports."]
   "EMON_ERR_INTERNAL" ["`internal`" "A bug: the Model failed its own check or the engine panicked. Never the document's fault; please report it."]})

(defn errors-table []
  (table ["Category" "Code" "Name to route on" "Meaning"]
         (for [[_ id sig] (sort-by (fn [[_ id sig]] (parse-long (second (re-find #"(\d+)" sig)))) (filter #(str/starts-with? (second %) "EMON_ERR_") (rows :c)))
               :let [[nm meaning] (get error-docs id)]]
           (do (when-not nm (fail! "docs: error category `" id "` is in api/surface.edn but has no description (tools/docs/docs.clj error-docs)"))
               [(code id) (second (re-find #"(\d+)" sig)) (cell (or nm "")) (cell (or meaning ""))]))))

;; ------------------------------------------------------------------------------------------------------------------ the Model
(def model-descr
  {"root.emon" "Schema version of the document, `1.0` or `1.1`. A reader must ignore keys it does not know: a later minor version only adds."
   "root.generator" "What produced the document, when given."
   "root.source" "What the input was: `format`, `version`, `pages`, `tagged`, `encrypted`, `lang`."
   "root.settings" "The settings the conversion ran with: `locale` always; `ocr`, `ocr-scope`, `detail`, `assets` and the `asset-*` ones only when they are not the default."
   "root.conf" "Always `calibrated`: every `conf` in the document is on the calibrated 0..1000 scale."
   "root.meta" "The PDF's own metadata (`title`, `author`, `subject`, `keywords`, `creator`, `producer`, `created`, `modified`), whichever it has."
   "root.pages" "One entry per page, numbered across all the inputs in the order they were given."
   "root.body" "The `document` node: the tree of blocks, in reading order."
   "root.furniture" "Running headers, footers and margin items kept by the furniture policy, each with the pages it recurs on."
   "root.dropped" "The ledger: what the pipeline saw and left out of the body, and why."
   "root.assets" "Present only when `assets` is on: one entry per pixel asset (metadata only; the bytes travel out of band)."
   "page.n" "Page number, from 1, counted across all inputs."
   "page.label" "The printed page label (`iii`, `A-4`), when the document has one."
   "page.w" "Page width in points, as displayed (rotation applied)."
   "page.h" "Page height in points, as displayed."
   "page.rot" "The page's rotation in degrees."
   "page.kind" "How the page was read: `vector` (a text layer), `scanned` (pixels only), `mixed`, `image` (an image input)."
   "page.health" "Confidence, 0..1000, that the page's text layer can be trusted."
   "page.dpi" "Resolution of a scanned page or an image, when known."
   "page.px" "Pixel size `[width, height]` of a scanned page or an image."
   "page.layout" "The column map reading order was decided over: one cell per column fragment (`band`, `col`, `role`, and `box` as `[x0, y0, x1, y1]`). Absent when the page is one plain column."
   "block.id" "Integer, unique in the Model, assigned in document order. Stable for the same input and settings, so ids can be cited across runs and hosts."
   "block.kind" "What the node is: see the table of kinds."
   "block.text" "The block's plain text. When the block has `inlines`, the concatenation of their text."
   "block.inlines" "The rich form of the text: runs, spans, links, note references, line breaks, drop caps, page milestones. Present only when it carries information (a style, a link, a note reference, a line break, a drop cap)."
   "block.children" "Child blocks, in reading order."
   "block.at" "Where the node is: regions `[page, x0, y0, x1, y1]` in points, top-left origin, y down. A node spanning pages or columns has one region per fragment."
   "block.src" "Which sources contributed: `text` (the PDF text layer), `hidden-text`, `ocr`, `trace` (pixels), `vector` (drawn rules and fills), `struct` (the tag tree), `annot`, `outline`, `infer`."
   "block.conf" "Confidence that the node is right, 0..1000, calibrated."
   "block.flags" "Warnings, from a closed set: `low-conf`, `ocr-only`, `text-layer-suspect`, `reconstructed`, `dpi-unknown`, `rotated`, `vertical`, `struct-disagree`, `tag-span-conflict`, `in-table-image`, `script-unread`."
   "block.level" "`section` and `heading`: the depth, from 1. The HTML writer stops at `h6` and adds `aria-level` beyond it."
   "block.number" "`heading`: the section number as printed (`3.2.1`)."
   "block.numbering" "`heading`: the numbering scheme."
   "block.align" "`paragraph`, `heading`, `caption`, `cell`: logical alignment, written only when it was detected with confidence."
   "block.ordered" "`list`: whether the items are numbered."
   "block.scheme" "`list`: the marker scheme (`decimal`, `lower-alpha`, `upper-roman`, `bullet`, ...)."
   "block.start" "`list`: the first number."
   "block.marker" "`item`, `note`: the marker as printed."
   "block.value" "`item`: the item's number; `field`: a text field's value."
   "block.profile" "`table`: how the table was found: `ruled`, `partial`, `shaded`, `whitespace`, `form`, `struct`."
   "block.continued" "`table`: how many pages it spans, when more than one."
   "block.columns" "`table`: the number of columns."
   "block.role" "`rowgroup`: `head`, `body`, `foot`. `note`: `footnote`, `endnote`, `margin`, `sidebar`. `furniture`: `header`, `footer`, `margin`."
   "block.pages" "`furniture`: the pages the recurring item appears on."
   "block.repeated" "`row`: a recurring header row that was folded into one."
   "block.rowspan" "`cell`: rows spanned."
   "block.colspan" "`cell`: columns spanned."
   "block.header" "`cell`: `col` or `row` for a header cell."
   "block.empty" "`cell`: the cell holds nothing."
   "block.label" "`caption`, `figure`: the label (`Table 3`, `Figure 2`)."
   "block.target" "`caption`: the id of the table or figure it describes."
   "block.subkind" "`figure`: `chart`, `diagram`, `photo`, `logo`, `map`, `illustration`, `screenshot`, `qr`, `barcode`, `unknown`. `seal`: `seal`, `stamp`, `signature`, `initials`, `digital-signature`."
   "block.alt" "`figure`: the alternative text the PDF gave it."
   "block.labels" "`figure`: text found inside the picture."
   "block.asset" "`figure`, `seal`: the id of the asset holding its pixels (only when `assets` is on)."
   "block.noasset" "`figure`, `seal`: why it has none, when `assets` is on."
   "block.name" "`seal`: the name on it (or the signer); `field`: the field's name."
   "block.date" "`seal`: the date on it."
   "block.color" "`seal`: its colour."
   "block.signed" "`seal` (`digital-signature`): whether the PDF carries a signature value. Nothing is cryptographically verified."
   "block.reason" "`seal` (`digital-signature`): the signature's stated reason."
   "block.location" "`seal` (`digital-signature`): the signature's stated location."
   "block.display" "`formula`: `block` or `inline`."
   "block.type" "`field`: the kind of form field."
   "block.checked" "`field`: whether a checkbox or radio button is on."
   "block.page" "`break`: the page number the milestone starts."
   "inline.kind" "What the run is: see the table of inline kinds."
   "inline.text" "The run's text."
   "inline.children" "Nested inlines (a `span`, `link` or `initial` wrapping runs)."
   "inline.style" "`span`: the styles in force: `bold`, `italic`, `underline`, `strike`, `sub`, `sup`, `mono`, `smallcaps`."
   "inline.href" "`link`: an external URI."
   "inline.target" "`link`, `ref`: the id of the node it points to."
   "inline.marker" "`ref`: the note marker as printed."
   "inline.form" "`initial`: `drop`, `raised` or `margin`."
   "inline.lines" "`initial`: how many lines a drop or margin initial spans."
   "inline.label" "`break`: the printed page label."
   "inline.page" "`break`: the page number."
   "inline.at" "Regions of the run (only at the finest detail); on a `field`, the widget's rectangle `[page, x0, y0, x1, y1]`."
   "inline.src" "As for a block; a `field` read from an AcroForm is `annot`."
   "inline.conf" "As for a block; a `field`'s says how well the value and the widget's appearance agree."
   "inline.name" "`field`: the form field's full name."
   "inline.type" "`field`: `checkbox`, `radio`, `text`, `combo` or `list`."
   "inline.value" "`field`: a text, combo or list field's value; for a checkbox or radio button, its export value."
   "inline.checked" "`field`: whether a checkbox or radio button is on."
   "inline.flags" "As for a block."
   "document.id" "Always 0."
   "document.kind" "Always `document`."
   "document.children" "The document's blocks and sections, in reading order."
   "document.text" "Reserved; absent today."
   "document.inlines" "Reserved; absent today."
   "document.at" "As for a block."
   "document.src" "As for a block."
   "document.conf" "As for a block."
   "document.flags" "As for a block."
   "furnitureItem.id" "As for a block."
   "furnitureItem.role" "`header`, `footer` or `margin`."
   "furnitureItem.pages" "The pages it recurs on."
   "furnitureItem.children" "The recurring content, as blocks."
   "furnitureItem.template" "The recurring text with its page number replaced by a placeholder."
   "furnitureItem.at" "As for a block."
   "furnitureItem.src" "As for a block."
   "furnitureItem.conf" "As for a block."
   "furnitureItem.flags" "As for a block."
   "droppedItem.reason" "Why it was left out: `watermark`, `background`, `page-number`, `running-title`, `bleed-through`, `duplicate-header-row`, `ocr-ghost`, `clipped`, `offpage`, `invisible`, `rule-ghost`, `unwitnessed-uncertain-paint`."
   "droppedItem.page" "The page it was on."
   "droppedItem.at" "Its region `[page, x0, y0, x1, y1]`."
   "droppedItem.text" "Its text, when it had any."
   "droppedItem.conf" "How sure the pipeline was."
   "droppedItem.src" "As for a block."
   "asset.id" "Asset id (what a block's `asset` names)."
   "asset.name" "The file name to write it as: `a<id>.jpg` or `a<id>.png`."
   "asset.type" "Media type of the encoded bytes."
   "asset.origin" "`embedded` (the PDF's own image, passed through), `decoded` (decoded and re-encoded), `cropped` (cut from a render), `rendered` (a render of a region)."
   "asset.from" "What it came from: the PDF image filter (`dct`, `flate`, `ccitt`, `jpx`, `jbig2`, `lzw`, `runlength`, `raw`), an image format (`png`, `jpeg`, `tiff`) or `render`."
   "asset.bytes" "Size of the encoded bytes."
   "asset.sha256" "SHA-256 of the encoded bytes, hex."
   "asset.w" "Width in pixels."
   "asset.h" "Height in pixels."
   "asset.dpi" "Effective resolution, when known."
   "asset.object" "The PDF object number it came from."
   "asset.flags" "Notes on how it was made: `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap`."
   "asset.uses" "Where it is placed: `node` (a block id) and `at` (a region)."})

;; kind -> [children, key fields, HTML]; hand-written from SPEC 3.2 and 5.1, checked against the schema's enum
(def kind-docs
  {"document" ["sections, blocks" "" "`<main>` in a full page"]
   "section" ["`heading`, blocks, nested sections" "`level`" "`<section>`"]
   "heading" ["inlines" "`level`, `number`, `numbering`, `align`" "`<h1>` to `<h6>`; level over 6 is `<h6 aria-level=\"n\">`"]
   "paragraph" ["inlines" "`align`" "`<p>`"]
   "list" ["`item`s" "`ordered`, `scheme`, `start`" "`<ul>`, or `<ol start type>`"]
   "item" ["inlines, nested blocks" "`marker`, `value`" "`<li value>`; the literal marker as `data-emon-marker`"]
   "table" ["`caption`, `rowgroup`s" "`profile`, `continued`, `columns`" "`<table>` with `<thead>`, `<tbody>`, `<tfoot>`"]
   "rowgroup" ["`row`s" "`role`" "`<thead>` / `<tbody>` / `<tfoot>`"]
   "row" ["`cell`s" "`repeated`" "`<tr>`"]
   "cell" ["inlines or blocks" "`header`, `rowspan`, `colspan`, `empty`, `align`" "`<th scope>` or `<td rowspan colspan>`"]
   "caption" ["inlines" "`label`, `target`, `align`" "`<caption>` in a table, `<figcaption>` in a figure"]
   "figure" ["`caption`" "`subkind`, `label`, `alt`, `labels`, `asset`, `noasset`" "`<figure data-emon-kind role=\"img\" aria-label>`; an `<img>` only when it has an asset"]
   "seal" ["" "`subkind`, `name`, `text`, `date`, `color`, `asset`, `signed`, `reason`, `location`" "`<figure data-emon-kind=\"stamp\" data-emon-name role=\"img\">`"]
   "formula" ["" "`text`, `display`" "`<figure data-emon-kind=\"formula\">` with the linear text in `aria-label`"]
   "code" ["inlines" "" "`<pre><code>`"]
   "quote" ["blocks" "" "`<blockquote>`"]
   "note" ["blocks" "`role`, `marker`" "`<aside role=\"doc-footnote\" id=\"fn-N\">`; a margin or sidebar note is `<aside data-emon-role>`"]
   "field" ["inlines" "`name`, `type`, `value`, `checked`" "`<span data-emon=\"field\" data-emon-name>`; never a live `<input>`"]
   "furniture" ["blocks" "`role`, `pages`" "`<aside data-emon=\"furniture\" data-emon-role>`"]
   "break" ["" "`page`, `label`" "an empty `<span data-emon-page data-emon-page-label>`"]})

(def inline-kind-docs
  {"text" ["a run of characters" "`text`" "the text, escaped"]
   "span" ["a styled run" "`style`" "`<b>`, `<i>`, `<u>`, `<s>`, `<sub>`, `<sup>`, `<code>`; small caps as `<span data-emon-style=\"smallcaps\">`"]
   "link" ["a link" "`href` or `target`" "`<a href>`; an internal target is `#emon-ID`"]
   "ref" ["a note reference" "`marker`, `target`" "`<a role=\"doc-noteref\" href=\"#fn-N\">`"]
   "linebreak" ["a meaningful hard line break" "" "`<br>`"]
   "initial" ["a drop cap or a kin of it" "`form`, `lines`" "`<span data-emon=\"initial\" data-emon-form data-emon-lines>`; the text is kept whole"]
   "field" ["an AcroForm field in the block that holds its label" "`name`, `type`, `value`, `checked`" "`<span data-emon=\"field\" data-emon-name data-emon-type>` with the value as content; a checkbox or radio button is an empty span with `role`, `aria-checked`, `data-emon-checked` and `data-emon-value`"]
   "break" ["a page milestone inside a paragraph" "`page`, `label`" "an empty `<span data-emon-page>`"]})

(defn ref-name [r] (last (str/split r #"/")))

(defn enum-values [p]
  (cond (get p "enum") (get p "enum")
        (get p "anyOf") (mapcat enum-values (get p "anyOf"))
        (= "array" (get p "type")) (some-> (get p "items") enum-values)
        :else nil))

(defn ptype [p]
  (cond
    (contains? p "const") (str "constant " (code (json/generate-string (get p "const"))))
    (get p "enum") "one of"
    (get p "anyOf") (if (every? #(get % "enum") (get p "anyOf")) "one of" (str/join " or " (map ptype (get p "anyOf"))))
    (get p "$ref") (case (ref-name (get p "$ref"))
                     "cpt" "number (points)"
                     "conf" "integer 0..1000"
                     "at" "list of regions"
                     "region" "region `[page, x0, y0, x1, y1]`"
                     "src" "list of sources"
                     "flags" "list of flags"
                     (ref-name (get p "$ref")))
    :else (case (get p "type")
            "integer" (let [mn (get p "minimum") mx (get p "maximum")]
                        (cond (and mn mx) (str "integer " mn ".." mx) mn (str "integer >= " mn) :else "integer"))
            "number" "number"
            "string" "string"
            "boolean" "boolean"
            "array" (str "array of " (if-let [i (get p "items")] (if (get i "enum") "values" (ptype i)) "values"))
            "object" "object"
            (json/generate-string p))))

(defn def-table
  "The property table of `props`; `prefix` keys the descriptions in model-descr; `required` a set of names."
  [prefix props required]
  (table ["Field" "Required" "Type" "Values" "Meaning"]
         (for [k (sort-by (fn [k] [(if (and (contains? #{"id" "kind"} k) (contains? required k)) 0 1) k]) (keys props))
               :let [p (get props k)
                     vs (distinct (enum-values p))
                     d (get model-descr (str prefix "." k))]]
           (do (when-not d (warn! "docs: Model property `" prefix "." k "` (schema/emon-1.json) has no description (tools/docs/docs.clj model-descr)"))
               [(code k) (if (contains? required k) "yes" "") (cell (ptype p))
                (if (seq vs) (str/join ", " (map #(code (if (string? %) % (json/generate-string %))) vs)) "")
                (cell (or d "not described yet"))]))))

(defn model-top [] (def-table "root" (get @schema "properties") (set (get @schema "required"))))
(defn defs [] (get @schema "$defs"))
(defn model-def [n] (let [d (get (defs) n)] (def-table n (get d "properties") (set (get d "required")))))

(defn kinds-table [enum-path doc-map label]
  (let [kinds (get-in @schema enum-path)]
    (doseq [k (sort (remove (set kinds) (keys doc-map)))] (warn! "docs: " label " `" k "` is described in tools/docs/docs.clj but not in schema/emon-1.json"))
    (table ["Kind" "Children / what" "Key fields" "HTML"]
           (for [k kinds
                 :let [[ch fl html] (get doc-map k)]]
             (do (when-not (get doc-map k) (warn! "docs: " label " `" k "` (schema/emon-1.json) has no description (tools/docs/docs.clj)"))
                 [(code k) (cell (or ch "not described yet")) (cell (or fl "")) (cell (or html ""))])))))

(defn model-kinds [] (kinds-table ["$defs" "block" "properties" "kind" "enum"] kind-docs "block kind"))
(defn model-inline-kinds [] (kinds-table ["$defs" "inline" "properties" "kind" "enum"] inline-kind-docs "inline kind"))

(defn model-versions []
  (str "Versions " (str/join " and " (map code (get-in @schema ["properties" "emon" "enum"]))) " (schema id " (code (get @schema "$id")) ")."))

;; ------------------------------------------------------------------------------------------------------------------ the API index of a host
(defn api-index [host]
  (let [rs (rows host)
        pub (remove #(re-find #"\(re-export\)$" (second %)) (filter #(= :public (first %)) rs))
        prov (filter #(= :provisional (first %)) rs)
        tbl (fn [xs] (table ["Name" "Signature"] (for [[_ id sig] xs] [(code id) (cell (if (str/blank? sig) "" (code sig)))])))]
    (str "<details><summary>The public API of the " (name host) " host: " (count pub) " entries"
         (when (seq prov) (str ", and " (count prov) " provisional")) " (generated from <code>api/surface.edn</code>)</summary>\n\n"
         "Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.\n\n"
         (tbl pub)
         (when (seq prov) (str "\n\n**Provisional** (shipped, not frozen: live adapters and driver flags)\n\n" (tbl prov)))
         "\n\n</details>")))

(defn maven-dependency []
  (str "```xml\n<dependency>\n  <groupId>com.3sln</groupId>\n  <artifactId>emon</artifactId>\n  <version>" @version "</version>\n</dependency>\n```"))

(def generators
  {"maven-dependency" maven-dependency
   "options-table" options-table
   "limits-table" limits-table
   "html-options-table" html-options-table
   "outputs" outputs-table
   "errors-table" errors-table
   "model-top" model-top
   "model-version" model-versions
   "model-kinds" model-kinds
   "model-block" #(model-def "block")
   "model-inline-kinds" model-inline-kinds
   "model-inline" #(model-def "inline")
   "model-page" #(model-def "page")
   "model-asset" #(model-def "asset")
   "model-dropped" #(model-def "droppedItem")
   "model-furniture" #(model-def "furnitureItem")
   "model-document" #(model-def "document")})

(defn generate [name path]
  (if-let [h (and (= name "api-index") (second (re-find #"^docs/hosts/([a-z]+)\.md$" path)))]
    (api-index (keyword h))
    (if-let [g (get generators name)]
      (g)
      (do (fail! path ": unknown generator `" name "` (tools/docs/docs.clj generators)") "(unknown generator)"))))

;; ------------------------------------------------------------------------------------------------------------------ snippets and excerpts
(defn dedent [lines]
  (let [ind (fn [l] (count (re-find #"^[ \t]*" l)))
        m (apply min (or (seq (map ind (remove str/blank? lines))) [0]))]
    (mapv #(if (str/blank? %) "" (subs % (min m (count %)))) lines)))

(defn trim-blank [lines]
  (->> lines (drop-while str/blank?) reverse (drop-while str/blank?) reverse vec))

(defn snippet-lines [text]
  (let [ls (str/split text #"\n" -1)
        ls (if (str/blank? (peek ls)) (pop ls) ls)
        has (fn [l tag] (str/includes? l (str "docs:" tag)))]
    (if-not (some #(has % "begin") ls)
      (vec ls)
      (loop [ls ls, in? false, cur [], regions []]
        (if-let [l (first ls)]
          (cond (has l "begin") (recur (rest ls) true [] regions)
                (has l "end") (recur (rest ls) false [] (conj regions cur))
                in? (recur (rest ls) true (conj cur l) regions)
                :else (recur (rest ls) false cur regions))
          (vec (rest (mapcat #(cons "" (trim-blank (dedent %))) regions))))))))

(defn pretty-html
  "Line breaks and indentation added to a fragment of HTML; nothing else changes."
  [frag]
  (let [toks (re-seq #"<[^>]+>|[^<]+" frag)
        open? #(and (str/starts-with? % "<") (not (str/starts-with? % "</")) (not (str/starts-with? % "<!")) (not (str/ends-with? % "/>")))
        close? #(str/starts-with? % "</")
        name-of #(second (re-find #"^</?([a-zA-Z0-9]+)" %))]
    (loop [ts toks, depth 0, out []]
      (if (empty? ts)
        out
        (let [[a b c] ts
              pad (apply str (repeat (* 2 depth) " "))]
          (cond
            (and (open? a) b (not (str/starts-with? b "<")) c (close? c) (= (name-of a) (name-of c)))
            (recur (drop 3 ts) depth (conj out (str pad a b c)))
            (and (open? a) b (close? b) (= (name-of a) (name-of b)))
            (recur (drop 2 ts) depth (conj out (str pad a b)))
            (open? a) (recur (rest ts) (inc depth) (conj out (str pad a)))
            (close? a) (let [d (max 0 (dec depth))] (recur (rest ts) d (conj out (str (apply str (repeat (* 2 d) " ")) a))))
            :else (recur (rest ts) depth (conj out (str pad a)))))))))

(defn excerpt-lines [path start end]
  (let [t (slurp path)
        i (str/index-of t start)
        j (when i (str/index-of t end i))]
    (if-not (and i j)
      (do (fail! "excerpt: " path " has no " (pr-str start) " ... " (pr-str end)) ["(missing)"])
      (pretty-html (subs t i (+ j (count end)))))))

;; ------------------------------------------------------------------------------------------------------------------ the Markdown pass
(def re-begin #"^<!-- bb docs:begin ([a-z-]+) -->$")
(def re-end #"^<!-- bb docs:end ([a-z-]+) -->$")
(def re-snippet #"^<!-- snippet: (\S+) -->$")
(def re-excerpt #"^<!-- excerpt: (\S+) \| (.+?) \| (.+?) -->$")
(def re-unverified #"^<!-- unverified: .+ -->$")
(def re-fence #"^```(\w*)\s*$")

(defn process
  "-> the text with its generated parts written. Records problems (fail!) for a misuse of a marker, an unmarked code block, a missing file."
  [path text]
  (let [lines (str/split text #"\n" -1)]
    (loop [ls lines, out [], marker nil]            ; marker: the last non-blank line's marker, or nil
      (if (empty? ls)
        (str/join "\n" out)
        (let [l (first ls)]
          (cond
            (re-matches re-begin l)
            (let [name (second (re-matches re-begin l))
                  [inner after] (split-with #(not (= % (str "<!-- bb docs:end " name " -->"))) (rest ls))]
              (if (empty? after)
                (do (fail! path ": `bb docs:begin " name "` has no matching end") (recur [] (into out ls) nil))
                (recur (rest after)
                       (into out (concat [l] (str/split (generate name path) #"\n" -1) [(first after)]))
                       nil)))

            (re-matches re-snippet l)
            (let [[_ rel] (re-matches re-snippet l)
                  nxt (second ls)]
              (cond
                (not (and nxt (re-matches re-fence nxt))) (do (fail! path ": snippet marker for " rel " is not followed by a code fence") (recur (rest ls) (conj out l) nil))
                (not (fs/exists? rel)) (do (fail! path ": snippet " rel " does not exist") (recur (rest ls) (conj out l) nil))
                :else (let [body (snippet-lines (slurp rel))
                            [_ after] (split-with #(not (re-matches #"^```\s*$" %)) (drop 2 ls))]
                        (recur (rest after) (into out (concat [l nxt] body [(first after)])) nil))))

            (re-matches re-excerpt l)
            (let [[_ rel start end] (re-matches re-excerpt l)
                  nxt (second ls)]
              (if-not (and nxt (re-matches re-fence nxt))
                (do (fail! path ": excerpt marker for " rel " is not followed by a code fence") (recur (rest ls) (conj out l) nil))
                (let [body (excerpt-lines rel start end)
                      [_ after] (split-with #(not (re-matches #"^```\s*$" %)) (drop 2 ls))]
                  (recur (rest after) (into out (concat [l nxt] body [(first after)])) nil))))

            (re-matches re-unverified l) (recur (rest ls) (conj out l) :unverified)

            (re-matches re-fence l)
            (let [lang (second (re-matches re-fence l))
                  [inner after] (split-with #(not (re-matches #"^```\s*$" %)) (rest ls))]
              (when (and (contains? code-langs lang) (not= marker :unverified))
                (fail! path ": a `" lang "` code block is not marked: put `<!-- snippet: docs/snippets/... -->` (compiled by `bb package --check`) or `<!-- unverified: why -->` on the line before it"))
              (recur (rest after) (into out (concat [l] inner [(first after)])) nil))

            (str/blank? l) (recur (rest ls) (conj out l) marker)
            :else (recur (rest ls) (conj out l) nil)))))))

;; ------------------------------------------------------------------------------------------------------------------ the other checks
(defn check-links [path text]
  (doseq [[_ target] (re-seq #"\]\(([^)\s]+)\)" text)
          :when (not (re-find #"^(https?:|mailto:|#)" target))
          :let [p (first (str/split target #"#"))
                f (str (fs/normalize (fs/path (or (fs/parent path) ".") p)))]]
    (when-not (fs/exists? f) (fail! path ": link to " target " (no such file: " f ")"))))

(defn check-version [path text]
  (doseq [[_ v] (re-seq #"emon-(\d+\.\d+\.\d+)-" text)]
    (when (not= v @version) (fail! path ": mentions emon-" v "-..., version.edn says " @version))))

(defn check-cli-flags []
  (let [cli (slurp "docs/cli.md")]
    (doseq [[_ id _] (rows :options) :let [m (re-matches #"emon convert (--[a-z-]+|-o)" id)] :when m]
      (when-not (str/includes? cli (second m)) (fail! "docs/cli.md: does not mention `emon convert " (second m) "` (api/surface.edn)")))))

(defn check-samples-named []
  (let [model (slurp "docs/model.md")
        samples (filter #(str/ends-with? (str %) ".html") (fs/list-dir "samples"))
        txt (apply str (map (comp slurp str) (remove #(str/ends-with? (str %) "index.html") samples)))
        attrs (set (map second (re-seq #"(data-emon[a-z-]*)=" txt)))
        tags (set (map second (re-seq #"<([a-z][a-z0-9]*)[ >]" txt)))
        ignorable #{"html" "head" "meta" "title" "body" "main" "img" "span" "div" "a"}]
    (doseq [a (sort attrs) :when (not (str/includes? model a))]
      (fail! "docs/model.md: does not mention the attribute `" a "`, which samples/*.html uses"))
    (doseq [t (sort tags) :when (and (not (ignorable t)) (not (str/includes? model (str "<" t))))]
      (fail! "docs/model.md: does not mention the element `<" t ">`, which samples/*.html uses"))))

(defn check-readme-links [readme]
  (doseq [p (concat ["CONTRIBUTING.md" "docs/model.md" "docs/options.md" "docs/cli.md" "docs/releasing.md" "samples/" "LICENSE" "THIRD_PARTY_NOTICES.md"]
                    (map #(str "docs/hosts/" (name %) ".md") hosts))]
    (when-not (str/includes? readme (str "](" p)) (fail! "README.md: does not link " p)))
  (when-not (str/includes? readme demo-url) (fail! "README.md: does not link the browser demo, " demo-url))
  (when (str/includes? readme "3sln.github.io") (fail! "README.md: the browser demo is at " demo-url ", not at github.io")))

;; ------------------------------------------------------------------------------------------------------------------ main
(defn run-all []
  (let [expected (into (sorted-map)
                       (for [f (md-files) :when (fs/exists? f)]
                         [f (process f (slurp f))]))]
    (doseq [f (md-files) :when (not (fs/exists? f))] (fail! f ": missing"))
    (doseq [f (link-files) :when (fs/exists? f)] (check-links f (get expected f (slurp f))) (check-version f (get expected f (slurp f))))
    (when (fs/exists? "docs/cli.md") (check-cli-flags))
    (when (fs/exists? "docs/model.md") (check-samples-named))
    (when (fs/exists? "README.md") (check-readme-links (get expected "README.md")))
    expected))

(defn self-test []
  (reset! problems []) (reset! warns [])
  (let [case! (fn [label thunk pred]
                (reset! problems [])
                (let [r (thunk)]
                  (when-not (pred r @problems) (println "  FAIL self-test:" label "->" (pr-str r) (pr-str @problems)) (System/exit 1))
                  (println "  ok   self-test:" label)))]
    (case! "a stale generated table is rewritten"
           #(process "docs/options.md" "<!-- bb docs:begin limits-table -->\nstale\n<!-- bb docs:end limits-table -->")
           (fn [r p] (and (empty? p) (str/includes? r "| `limit.pages` |") (not (str/includes? r "stale")))))
    (case! "a stale snippet is rewritten"
           #(process "README.md" "<!-- snippet: docs/snippets/rust/quickstart.rs -->\n```rust\nold\n```\nafter")
           (fn [r p] (and (empty? p) (str/includes? r "Engine::new(Config::default())") (str/ends-with? r "```\nafter"))))
    (case! "an unmarked code block is a problem"
           #(process "README.md" "text\n\n```rust\nfn main() {}\n```\n")
           (fn [_ p] (some #(str/includes? % "is not marked") p)))
    (case! "an unverified block passes"
           #(process "README.md" "<!-- unverified: needs pdfium -->\n```rust\nfn main() {}\n```\n")
           (fn [_ p] (empty? p)))
    (case! "an output block needs no marker"
           #(process "README.md" "```text\nhello\n```\n")
           (fn [_ p] (empty? p)))
    (case! "an unknown generator is a problem"
           #(process "README.md" "<!-- bb docs:begin nope -->\n<!-- bb docs:end nope -->")
           (fn [_ p] (some #(str/includes? % "unknown generator") p)))
    (case! "a link to nothing is a problem"
           #(check-links "README.md" "see [x](docs/nothing.md)")
           (fn [_ p] (some #(str/includes? % "no such file") p)))
    (case! "a stale version in an archive name is a problem"
           #(check-version "docs/cli.md" "emon-0.0.1-aarch64.tar.gz")
           (fn [_ p] (some #(str/includes? % "version.edn") p)))
    (case! "the pretty printer puts one element on a line"
           #(pretty-html "<table><thead><tr><th>A</th></tr></thead></table>")
           (fn [r _] (= r ["<table>" "  <thead>" "    <tr>" "      <th>A</th>" "    </tr>" "  </thead>" "</table>"]))))
  (println "  ok   bb docs self-test"))

(defn -main [args]
  (let [check? (some #{"--check"} args)]
    (when (some #{"--self-test"} args) (self-test) (System/exit 0))
    (when-let [bad (seq (remove #{"--check"} args))]
      (println "usage: bb docs [--check | --self-test]") (System/exit 2))
    (let [expected (run-all)
          differ (vec (for [[f t] expected :when (not= t (slurp f))] f))]
      (doseq [w (distinct @warns)] (println "  WARN" w))
      (cond
        (seq @problems) (do (doseq [p (distinct @problems)] (println "  FAIL" p)) (System/exit 1))
        check? (if (seq differ)
                 (do (doseq [f differ] (println "  FAIL" f "differs from what `bb docs` generates: run `bb docs` and commit the result"))
                     (System/exit 1))
                 (println "  ok   docs:" (count expected) "files are what `bb docs` generates; every code block is a compiled snippet or marked unverified; links, versions, flags and samples agree"
                          (str "(" (count (distinct @warns)) " warnings)")))
        :else (do (doseq [f differ] (spit f (get expected f)) (println "  wrote" f))
                  (println "  ok   docs:" (count differ) "file(s) written," (- (count expected) (count differ)) "unchanged"))))))

(-main *command-line-args*)
