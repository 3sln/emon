# emon

emon converts PDFs and images into semantic HTML (and a JSON model of the same document) for scraping and
indexing: headings, paragraphs, lists, tables, figures, footnotes, reading order across columns, text in scans
by OCR. It is one specification implemented in Rust, Go, Java, C# and WebAssembly/JavaScript, generated from one
source tree, so every host gives the same bytes for the same input.

This package is one of them. The project, the documentation and the other hosts are at
<https://github.com/3sln/emon>; you can try it in your browser at <https://emon.3sln.com/>.

| Host | Registry | Install |
| --- | --- | --- |
| Rust | crates.io | `cargo add emon` |
| Go | Go modules | `go get github.com/3sln/emon/go` |
| Java | Maven Central | `com.3sln:emon` |
| C# | NuGet | `dotnet add package Emon` |
| JavaScript | npm | `npm install @3sln/emon` (and `@3sln/pdfium-wasm` for scans and figures) |
| C | GitHub Releases | `libemon` plus `emon.h`, with pdfium and Tesseract |
| babashka | GitHub Releases | the `emon-pod` archive, with pdfium and Tesseract |
| command line | GitHub Releases | `emon`, with pdfium and Tesseract bundled |

Born-digital PDFs (those with a real text layer) convert with nothing but the package. Drawing pages for scans, figures and OCR
needs pdfium, and reading scans needs Tesseract; neither is part of the Rust, Go, Java, C# and JavaScript packages, which take
them as ports you configure (an adapter for each is described in the host's page; for JavaScript pdfium is the npm package
`@3sln/pdfium-wasm`). The C library, the babashka pod and the command-line tool of the releases bundle both. See
<https://github.com/3sln/emon#what-each-host-needs-at-run-time>.

## Rust

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

## Go

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

## Java

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

## C#

<!-- snippet: docs/snippets/csharp/Quickstart.cs -->
```csharp
using System.Collections.Generic;
using System.IO;

var engine = new emon.Api.Engine(emon.Api.Options.Config.Defaults());
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("report.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("report.html", model.ToHtml(new emon.Api.Options.Html()));
```

## JavaScript

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

Node below 25 needs `node --experimental-wasm-exnref`.

## Licence

MIT. The third-party components these packages contain or load, and their licences, are listed in
`THIRD_PARTY_NOTICES.md`.
