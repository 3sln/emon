# emon as a babashka pod

`emon-pod` is the Rust host as a [babashka pod](https://github.com/babashka/pods): babashka starts it and talks to it
over stdin and stdout, and the namespace `emon` appears in your script. Get the archive `emon-pod-<version>-<triple>.tar.gz`
from the [GitHub releases](https://github.com/3sln/emon/releases) and put `emon-pod` on your `PATH` (or give
`pods/load-pod` its path). Arguments and results are EDN.

**The released pod has no pdfium, no Tesseract and no JPEG 2000 / JBIG2 decoder** (`(emon/capabilities)` says `false` for all
three). It converts born-digital PDFs on its own; for scans use the command-line tool ([docs/cli.md](../cli.md)), which bundles
both engines, or a language host with an adapter.

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
| `:native` | `true`, use the pod's own pdfium and Tesseract (the released pod has none) |

The result map has `:model`, `:html`, `:evidence`, `:assets` (each `{:id :name :media-type :origin :sha256 :width :height :size :bytes}`),
`:intake` and `:stats`.

## A tour

<!-- snippet: docs/snippets/pod/tour.clj -->
```clojure
(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary

(prn (emon/capabilities)) ; {:version "X.Y.Z", :pdfium false, :tesseract false, :codecs false}

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
[docs/options.md](../options.md#errors). A named limit that is reached (`limit.pages=0` above) also makes the pod print a Rust panic
notice on its standard error; the conversion's error is the exception, and the notice is only noise.

## Notes

- The conversion runs in the pod process, on a thread with a 32 MiB stack; nothing but the protocol goes to the pod's standard
  output, and diagnostics go to its standard error.
- Limits can also be set for the process with `EMON_LIMIT_*` in the pod's environment ([docs/options.md](../options.md#limits)).

<!-- bb docs:begin api-index -->
<details><summary>The public API of the pod host: 48 entries (generated from <code>api/surface.edn</code>)</summary>

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
