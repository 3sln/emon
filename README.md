# emon

**PDFs and images in, semantic HTML out. Built for scraping.**

emon reads a PDF (or a PNG, JPEG or TIFF image of a page) and gives you what a scraper wants: the text in
reading order, headings and sections, lists, tables with their header rows and spans, figures with captions,
footnotes, links, and the pages and places everything came from. Scans are read with OCR. The result is either
semantic HTML or a JSON "Model" you can walk, with a confidence on every node.

It is one specification, written once and generated into Rust, Go, Java and C#; the browser (WebAssembly), the C
library, the babashka pod and the command-line tool are the Rust build. **Every host gives the same bytes for the
same input**, and the project's gates hold them to it.

[Try it in your browser](https://emon.3sln.com/demo/) (nothing is uploaded; it runs in WebAssembly) ·
[the samples](samples/) · [the side-by-side viewer](samples/index.html) · [the command-line tool](docs/cli.md)

## What you get

A PDF table with grouped headers (`samples/table-spans.pdf`). Plain text extraction (`pdftotext -layout`) gives:

```text
         Product                            2024                      2025
                                    Units          Revenue    Units          Revenue
 Alpha                              1000           $20,000    1100            $23,000
 Beta                               1120            $21,311   1190            $24,197
```

emon gives the table as a table, with the two header rows and their spans intact (the file is on one line; line
breaks and indentation are added here):

<!-- excerpt: samples/table-spans.html | <table> | </thead> -->
```html
<table>
  <thead>
    <tr>
      <th scope="col" rowspan="2">Product</th>
      <th scope="col" colspan="2">2024</th>
      <th scope="col" colspan="2">2025</th>
    </tr>
    <tr>
      <th scope="col">Units</th>
      <th scope="col">Revenue</th>
      <th scope="col">Units</th>
      <th scope="col">Revenue</th>
    </tr>
  </thead>
```

Footnotes become `<aside role="doc-footnote">` linked from `<a role="doc-noteref">`, columns are read in order,
running headers and page numbers are taken out of the flow (and listed in the ledger), drop caps stay whole words,
and a scan with no text layer is read with OCR. [docs/model.md](docs/model.md) says what the Model holds and how
HTML comes from it; [samples/](samples/) has nearly thirty real and synthetic documents next to what emon makes of
them, and [samples/index.html](samples/index.html) shows each source beside its HTML (open it from a checkout; no
server needed).

## Install and first conversion

Every host converts `report.pdf` to `report.html` in a few lines. These programs are the ones `bb package --check`
installs from the built artifacts and runs, so they work as printed (Go's is shown without its `package main` and
`func main() {}`, and C's without the code that reads the file).

### Command line

One archive per platform holds everything, pdfium and Tesseract included. Download
`emon-<version>-<platform>.tar.gz` (`.zip` on Windows) from the [GitHub releases](https://github.com/3sln/emon/releases)
and unpack it ([docs/cli.md](docs/cli.md) has the platforms, the checksums and more languages):

<!-- unverified: needs a release archive, which does not exist before the first release; docs/cli.md describes what `bb cli --check` runs -->
```sh
emon convert report.pdf -o report.html
emon convert report.pdf --format json -o report.json
emon convert scan.png -o scan.html
```

### Rust

`cargo add emon` ([docs/hosts/rust.md](docs/hosts/rust.md))

<!-- snippet: docs/snippets/rust/quickstart.rs -->
```rust
use emon::{Config, Engine, HtmlOptions, Input, Options, Output};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let engine = Engine::new(Config::default());
    let model = engine.convert(Input::bytes(std::fs::read("report.pdf")?), &Options::default())?;
    std::fs::write("report.html", model.serialize(&Output::Html(HtmlOptions::default())))?;
    Ok(())
}
```

### Go

`go get github.com/3sln/emon/go` ([docs/hosts/go.md](docs/hosts/go.md)). On macOS, a program built with `-tags tesseract` is killed
at launch unless it is built with `-ldflags=-w` (or signed again with `codesign -s - -f`); the Go page says why.

<!-- snippet: docs/snippets/go/quickstart.go -->
```go
import (
	"os"

	emon "github.com/3sln/emon/go"
)

data, err := os.ReadFile("report.pdf")
if err != nil {
	panic(err)
}
engine := emon.NewEngine(emon.DefaultConfig())
model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
if err != nil {
	panic(err) // *emon.Error: err.(*emon.Error).Name() is the stable name
}
err = os.WriteFile("report.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
```

### Java

Maven `com.3sln:emon`, JDK 22 or newer ([docs/hosts/java.md](docs/hosts/java.md))

<!-- snippet: docs/snippets/java/Quickstart.java -->
```java
import emon.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Quickstart {
    public static void main(String[] args) throws Exception {
        Engine engine = new Engine(Options.Config.defaults());
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("report.pdf")))), Options.defaults());
        Files.writeString(Path.of("report.html"), model.toHtml(new Options.Html()));
    }
}
```

### C#

`dotnet add package Emon`, .NET 8 or newer ([docs/hosts/csharp.md](docs/hosts/csharp.md))

<!-- snippet: docs/snippets/csharp/Quickstart.cs -->
```csharp
using System.Collections.Generic;
using System.IO;

var engine = new emon.Api.Engine(emon.Api.Options.Config.Defaults());
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("report.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("report.html", model.ToHtml(new emon.Api.Options.Html()));
```

### JavaScript, in Node and in the browser

`npm install @3sln/emon` ([docs/hosts/js.md](docs/hosts/js.md)). Node 22 or newer; below Node 25 run it with
`node --experimental-wasm-exnref`. Browsers need WebAssembly exception handling (Chrome 137, Firefox 131,
Safari 18.4). In a browser, `Emon.load(fetch('/emon.wasm'))` and `emon.convert(file)` with a `File` or `Blob` do the same.

<!-- snippet: docs/snippets/js/quickstart.mjs -->
```js
import { readFile, writeFile } from 'node:fs/promises';
import { Emon } from '@3sln/emon';

const wasm = await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm')));
const emon = await Emon.load(wasm);
const model = await emon.convert(await readFile('report.pdf'));
await writeFile('report.html', model.html());
model.dispose();
```

Scans and figures need the pdfium and Tesseract adapters, `@3sln/emon/pdfium` and `@3sln/emon/tesseract`. pdfium is a package of
its own, `npm install @3sln/pdfium-wasm` (an optional peer dependency: `@3sln/emon` itself has none, and imports it only when a
page has to be drawn); Tesseract is `tesseract.js`. See [docs/hosts/js.md](docs/hosts/js.md).

### C

The `emon-c-<version>-<triple>.tar.gz` archive of the [releases](https://github.com/3sln/emon/releases) holds
`include/emon.h`, `lib/libemon.{so,dylib,a}`, and pdfium and the English and orientation models beside them (`lib/libpdfium.*`,
`share/emon/tessdata/`), so a scan converts with the `EMON_NATIVE` flag. Link with `-lemon` ([docs/hosts/c.md](docs/hosts/c.md)).

<!-- snippet: docs/snippets/c/quickstart.c -->
```c
/* pdf, len: the document's bytes (emon copies them) */
emon_input in = {0};
in.data = pdf;
in.len = len;
emon_buf html = {0};
emon_error err = {0};
if (emon_convert_html(&in, NULL, 0, NULL, &html, &err) < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    return 1;
}
FILE *out = fopen("report.html", "wb");
fwrite(html.data, 1, html.len, out); /* html.data[html.len] is a NUL */
fclose(out);
emon_free_buf(&html);
```

### babashka pod

The `emon-pod-<version>-<triple>.tar.gz` archive of the [releases](https://github.com/3sln/emon/releases) holds the
`emon-pod` binary, with `lib/libpdfium.*` and `share/emon/tessdata/` beside it, so a scan converts with `{:native true}`
([docs/hosts/pod.md](docs/hosts/pod.md)).

<!-- snippet: docs/snippets/pod/quickstart.clj -->
```clojure
(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary
(spit "report.html" (:html (emon/convert "report.pdf")))
```

## What each host needs at run time

A **born-digital PDF** (one with a real text layer) converts with nothing but the library. Pages that have to be
**drawn** (scans, images, figures, and OCR of a text page) need a *renderer*, and reading a scan needs an *OCR
engine*. The engine asks for them through two ports and links neither, so a host without them still works and answers
`unsupported` for what needs them (the text layer then stands alone).

| Host | Renderer: pdfium | OCR: Tesseract | Where they come from |
|---|---|---|---|
| CLI | bundled | bundled (English and orientation models) | nothing to install |
| Rust | a `Renderer` you give it, or the `pdfium` adapter (`--cfg pdfium`, links `libpdfium`) | an `Ocr` you give it, or the `tesseract` feature (links `libtesseract`, `libleptonica`) | [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases), your package manager |
| Go | `github.com/3sln/emon/go/pdfium`, loaded at run time (needs cgo) | `-tags tesseract` (cgo, links Tesseract and Leptonica) | the same |
| Java | `emon.ffi.Pdfium.open`, loaded at run time (`--enable-native-access=ALL-UNNAMED`) | `emon.ffi.Tess.open` | the same |
| C# | `emon.Ffi.Pdfium.Open`, loaded at run time | `emon.Ffi.Tess.Open` | the same |
| JavaScript | `@3sln/emon/pdfium` over pdfium's WebAssembly build, `npm install @3sln/pdfium-wasm` | `@3sln/emon/tesseract` over [tesseract.js](https://github.com/naptha/tesseract.js) | `npm install @3sln/pdfium-wasm tesseract.js` |
| C | bundled (`lib/libpdfium.*`, loaded at run time), with the `EMON_NATIVE` flag | bundled (linked statically; English and orientation models) | nothing to install |
| pod | bundled (`lib/libpdfium.*`), with `:native true` | bundled (English and orientation models) | nothing to install |

The pdfium that emon is tested with is `chromium/8076` of
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries). Tesseract models are the
`.traineddata` files of [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast); the CLI, the C library and the pod
bundle `eng` and `osd`.

Fetch additional pinned, SHA-256 checked Apache-2.0 language models with
`bb tessdata-fetch` (all 20 supported models), or `bb tessdata-fetch --langs eng,osd,spa,fra`.
Use the downloaded directory with `emon convert scan.pdf --tessdata .cache/tessdata --lang spa+eng`.

The live Rust test driver accepts `--tessdata=.cache/tessdata --lang=spa+eng`.
The library hosts accept a tessdata directory; the OCR request's language or script list
selects installed models in kin. Default release bundles continue to carry English and orientation models.
Never in any build: JBIG2 and JPEG 2000 decoders (see below).

## Options, the Model, the API

- [docs/options.md](docs/options.md): every `key=value` option, limit and HTML word, with defaults and ranges; the error names.
- [docs/model.md](docs/model.md): the JSON Model ([schema/emon-1.json](schema/emon-1.json)) and how HTML is made from it.
- [docs/hosts/](docs/hosts/): one page per host: [Rust](docs/hosts/rust.md), [Go](docs/hosts/go.md),
  [Java](docs/hosts/java.md), [C#](docs/hosts/csharp.md), [JavaScript](docs/hosts/js.md), [C](docs/hosts/c.md),
  [pod](docs/hosts/pod.md): opening, converting, ports, errors, limits, and the full API index.
- [docs/cli.md](docs/cli.md): the command-line tool. [docs/releasing.md](docs/releasing.md): how a release is made.
- [CONTRIBUTING.md](CONTRIBUTING.md): building, testing, and the decision log.

The public API is frozen at 0.1.0 in `api/surface.edn` and held by `bb api-diff`: until 1.0 a minor release may
change it, with a note; a patch release may not.

## How accurate is it

The bar is **useful for scraping**: the words come out right and in reading order, the structure (headings, lists,
tables, notes) is mostly what a person would see, and where emon is unsure it says so (`conf`, `flags`) instead of
guessing quietly. It is not a visual reproduction and it is not perfect. The project measures itself against ground
truth on every change: character error rate and reading order for text; tree-edit similarity for tables;
precision and recall for headings, running furniture, figures and seals. The ratchet (`test/scoreboard.edn`) only
moves up, and the same corpora were scored for pdfplumber, pymupdf4llm, docling and Tesseract for reference.

What to expect, from the samples ([samples/README.md](samples/README.md) lists them all):

- **Born-digital PDFs are the strong case**: text, reading order across columns, headings, lists, ruled and unruled
  tables, footnotes, drop caps, watermarks (kept out of the body, listed in the ledger).
- **Scans are weaker**: OCR makes character errors (`friend` read `frlend`), and Tesseract has only the models you
  give it (`eng` and `osd` in the CLI). Ruled tables in scans lose some column boundaries; faint stamps are missed;
  a thumbnail-sized image gives little.
- **Headings in documents whose type sizes are all close** can over-nest (levels are capped at 6 in HTML).
- **Charts are pictures**: their labels are kept as runs of text, not as data.

### Not supported, or not yet

- **JBIG2 and JPEG 2000 images** inside PDFs are `unsupported` in every release (the JBIG2 decoder emon was tested with
  is AGPL and is not shipped, and no JPEG 2000 decoder ships either): the image has no pixels, which matters only for a
  *scan stored in one of those formats*; the rest of the page converts.
- **Drawn form boxes**: the fields of an AcroForm (checkboxes, radio buttons, text, combo and list fields) are in the
  Model as `field` inlines of the block that holds their label (D548), but a box that is only drawn, on a scan or
  as vector lines with no AcroForm behind it, is read as text and shapes, not as a field. XFA-only forms are not read.
- **Deferred until after the first release**: code blocks, block quotes and formulas (and MathML), initials read from
  ink on scans, GPU raster kernels, and the locale extras (per-locale keyword tables, hyphenation dictionaries,
  Chinese and Japanese segmentation). Hyphens at line ends are kept unless a `locale` hint allows joining.
- **Not goals**: signature *validation* (a digital signature is reported, never verified), exporters such as hOCR or
  Markdown, Word, Excel or EPUB inputs, and machine-learning models.

## Licence

emon is MIT licensed ([LICENSE](LICENSE)). The software it contains or loads has its own licences, listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md): pdfium (BSD-3-Clause, Apache-2.0, and others), Tesseract (Apache-2.0),
Leptonica (BSD-2-Clause), tesseract.js (Apache-2.0), and the Unicode and Adobe data emon's tables are generated from.
`emon licenses` prints them from the command-line tool. No GPL-family code is in any build.

Maintainer: [@raystubbs](https://github.com/raystubbs). Report problems at <https://github.com/3sln/emon/issues>.
