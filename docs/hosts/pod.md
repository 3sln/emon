# emon as a babashka pod

`emon-pod` is the Rust host as a [babashka pod](https://github.com/babashka/pods): babashka starts it and talks to it
over stdin and stdout, and the namespace `emon` appears in your script. Get the archive `emon-pod-<version>-<triple>.tar.gz`
from the [GitHub releases](https://github.com/3sln/emon/releases) and put `emon-pod` on your `PATH` (or give
`pods/load-pod` its path). Arguments and results are EDN.

The archive holds the whole pipeline, laid out so that the pod finds it where it is:

    emon-pod                           the pod (Tesseract 5.5.3 and Leptonica are linked into it)
    lib/libpdfium.dylib | .so          pdfium (chromium/8076), loaded at run time
    share/emon/tessdata/               the English and orientation models (eng, osd)

`(emon/capabilities)` says `:pdfium true, :tesseract true` when it finds them; there is no JPEG 2000 or JBIG2 decoder
(`:codecs false`). They are used when a call asks for `:native true` (below). Where they are looked for, in order (a place you
*name* that is wrong is an error, never replaced by the next): the call's `:pdfium` and `:tessdata`; `$EMON_PDFIUM_LIB` and
`$EMON_TESSDATA`; `lib/libpdfium.*` and `share/emon/tessdata` beside the pod (or one directory above it); for the models, last,
`$TESSDATA_PREFIX` and the system's usual places. Keep the archive's directories together and nothing needs to be set. On macOS a
downloaded pod is quarantined until you run `xattr -dr com.apple.quarantine <dir>` (the files are not signed).

## First conversion

<!-- snippet: docs/snippets/pod/quickstart.clj -->
```clojure
(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary
(spit "report.html" (:html (emon/convert "report.pdf")))
```

`(emon/convert "report.pdf")` reads the file lazily, a chunk at a time, and returns a map; `:html` is the HTML. This script
is loaded by babashka against the built pod archive and run by `bb package --check`.

## The vars

| Var | |
|---|---|
| `(emon/convert INPUT [OPTIONS [SPEC]])` | convert one document or image |
| `(emon/intake INPUT [OPTIONS])` | stage 0 alone: `{:format :version :pages :encrypted :bytes}` |
| `(emon/check-options OPTIONS)` | `true`, or an error carrying the engine's sentence |
| `(emon/option-key? NAME)` | whether `NAME` is a key of the options grammar |
| `(emon/capabilities)` | `{:version :pdfium :tesseract :codecs}` |

`INPUT` is a path string (read lazily), or a map `{:path P}` or `{:bytes BASE64}` with an optional `:password` (a pod carries EDN, not
byte arrays, so bytes travel as base64). `OPTIONS` is the `key=value` text of [docs/options.md](../options.md), lines separated by `\n`,
or `nil`.

`SPEC` says what to return; the default is `{:model :json :html true :assets :bytes}`, and a value you list gives back only that:

| Key | Values |
|---|---|
| `:model` | `:json`, `:json-pretty`, or `:edn` (the Model as EDN data, with keywords for names) |
| `:html` | `true` (clean HTML), or a string of the [HTML words](../options.md#html-words): `"ids,geometry"` |
| `:evidence` | `true`, the text layer's evidence dump (needs the option `keep-evidence=1`) |
| `:assets` | `:bytes` (with each asset's bytes as base64) or `:info` (without) |
| `:native` | `true`, use the pod's own pdfium and Tesseract: pages that need drawing are drawn and scans are read |
| `:pdfium`, `:tessdata` | where pdfium (a file or its directory) and the models (a directory) are, when they are not beside the pod |

The result map has `:model`, `:html`, `:evidence`, `:assets` (each `{:id :name :media-type :origin :sha256 :width :height :size :bytes}`),
`:intake` and `:stats`.

## A tour

<!-- snippet: docs/snippets/pod/tour.clj -->
```clojure
(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary

(prn (emon/capabilities)) ; {:version "X.Y.Z", :pdfium true, :tesseract true, :codecs false} from the release archive

;; Stage 0 alone is cheap: format, version, pages, encryption.
(prn (emon/intake "report.pdf")) ; {:format "pdf", :version "1.4", :pages 1, :encrypted false, :bytes 39570} for one sample

;; One text says what to do, limits included (docs/options.md); the third argument says what to return.
(let [r (emon/convert "report.pdf" "ocr=never\nlimit.pages=500"
                      {:model :edn :html "pretty" :assets :info})]
  (println (count (:html r)) "bytes of HTML," (count (:assets r)) "assets"))

;; A failure is an exception whose ex-data has the stable name to route on.
(try
  (emon/convert "report.pdf" "limit.pages=0")
  (catch Exception e
    (prn (ex-data e)))) ; {:name "pages", :code 3, :category "limit"}
```

A failure is an exception whose message is the engine's sentence and whose `ex-data` has `:name` (the stable name to route on),
`:code` (the category, the same numbers as the C library's `EMON_ERR_*`) and `:category`. The names and categories are in
[docs/options.md](../options.md#errors). A named limit that is reached (`limit.pages=0` above) is an answer and prints nothing on
the pod's standard error; `bb package --check` holds the tour to that.

## Scans and figures

<!-- snippet: docs/snippets/pod/native.clj -->
```clojure
(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary

;; pdfium and the English and orientation models travel in the archive (lib/ and share/emon/tessdata beside the pod).
(prn (emon/capabilities)) ; {:version "X.Y.Z", :pdfium true, :tesseract true, :codecs false}

;; :native true brings them in: pdfium draws the pages, Tesseract reads them.
(spit "scan.html" (:html (emon/convert "scan.pdf" nil {:html true :native true})))
```

`bb package --check` loads the built pod archive by babashka with no `EMON_*` variable and runs this on `samples/scan-plain.pdf`,
requiring the heading the OCR reads. It runs where `bb native` and `bb pdfium-fetch` have been run, and prints a NOTE and skips on a
machine that has neither.

## Notes

- The conversion runs in the pod process, on a thread with a 32 MiB stack; nothing but the protocol goes to the pod's standard
  output, and diagnostics go to its standard error.
- Limits can also be set for the process with `EMON_LIMIT_*` in the pod's environment ([docs/options.md](../options.md#limits)).

<!-- bb docs:begin api-index -->
<details><summary>The public API of the pod host: 50 entries (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `emon/capabilities` | `var` |
| `emon/capabilities :codecs` | `key` |
| `emon/capabilities :pdfium` | `key` |
| `emon/capabilities :tesseract` | `key` |
| `emon/capabilities :version` | `key` |
| `emon/check-options` | `var` |
| `emon/convert` | `var` |
| `emon/convert result :assets` | `key` |
| `emon/convert result :assets :bytes` | `key` |
| `emon/convert result :assets :height` | `key` |
| `emon/convert result :assets :id` | `key` |
| `emon/convert result :assets :media-type` | `key` |
| `emon/convert result :assets :name` | `key` |
| `emon/convert result :assets :origin` | `key` |
| `emon/convert result :assets :sha256` | `key` |
| `emon/convert result :assets :size` | `key` |
| `emon/convert result :assets :width` | `key` |
| `emon/convert result :evidence` | `key` |
| `emon/convert result :html` | `key` |
| `emon/convert result :intake` | `key` |
| `emon/convert result :model` | `key` |
| `emon/convert result :stats` | `key` |
| `emon/convert result :stats :attempts` | `key` |
| `emon/convert result :stats :bytes-fetched` | `key` |
| `emon/convert result :stats :bytes-requests` | `key` |
| `emon/convert result :stats :fetch-rounds` | `key` |
| `emon/convert result :stats :input-bytes` | `key` |
| `emon/convert result :stats :requests` | `key` |
| `emon/convert result :stats :source-reads` | `key` |
| `emon/convert spec :assets` | `key` |
| `emon/convert spec :evidence` | `key` |
| `emon/convert spec :html` | `key` |
| `emon/convert spec :model` | `key` |
| `emon/convert spec :native` | `key` |
| `emon/convert spec :pdfium` | `key` |
| `emon/convert spec :tessdata` | `key` |
| `emon/convert value :edn` | `word` |
| `emon/convert value :json` | `word` |
| `emon/convert value :json-pretty` | `word` |
| `emon/intake` | `var` |
| `emon/intake result :bytes` | `key` |
| `emon/intake result :encrypted` | `key` |
| `emon/intake result :format` | `key` |
| `emon/intake result :pages` | `key` |
| `emon/intake result :version` | `key` |
| `emon/option-key?` | `var` |
| `error ex-data :category` | `key` |
| `error ex-data :code` | `key` |
| `error ex-data :name` | `key` |
| `namespace emon` | `format edn` |

</details>
<!-- bb docs:end api-index -->
