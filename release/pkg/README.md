# emon

emon converts PDFs and images into semantic HTML (and a JSON model of the same document) for scraping and
indexing: headings, paragraphs, lists, tables, figures, footnotes, reading order across columns, text in scans
by OCR. It is one specification implemented in Rust, Go, Java, C# and WebAssembly/JavaScript, generated from one
source tree, so every host gives the same bytes for the same input.

This package is one of them. The project, the specification and the other hosts are at
<https://github.com/3sln/emon>.

| Host | Registry | Install |
| --- | --- | --- |
| Rust | crates.io | `cargo add emon` |
| Go | Go modules | `go get github.com/3sln/emon/go` |
| Java | Maven Central | `com.3sln:emon` |
| C# | NuGet | `dotnet add package Emon` |
| JavaScript | npm | `npm install @3sln/emon` |
| C | GitHub Releases | `libemon` plus `emon.h` |
| babashka | GitHub Releases | the `emon-pod` archive |

Rendering pages for OCR and figures needs pdfium, and OCR needs Tesseract; neither is part of these packages.
Born-digital PDFs convert without them. The Go, Java, C#, C and pod hosts load pdfium and Tesseract at run time
when they are present; the JavaScript SDK takes them as ports (`@3sln/emon/pdfium`, `@3sln/emon/tesseract`).

## Rust

```rust
use emon::{Config, Engine, HtmlOptions, Input, Options, Output};

let engine = Engine::new(Config::default());
let model = engine.convert(Input::bytes(std::fs::read("report.pdf")?), &Options::default())?;
let html = model.serialize(&Output::Html(HtmlOptions::default()));
```

## Go

```go
import emon "github.com/3sln/emon/go"

engine := emon.NewEngine(emon.DefaultConfig())
model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
html := model.ToHTML(emon.DefaultHTMLOptions())
```

## Java

```java
Engine engine = new Engine(Options.Config.defaults());
Model model = engine.convert(List.of(Input.bytes(pdf)), Options.defaults());
String html = model.toHtml(new Options.Html());
```

## C#

```csharp
var engine = new emon.Api.Engine(emon.Api.Options.Config.Defaults());
var model = engine.Convert(new List<emon.Api.Input> { emon.Api.Input.Bytes(pdf) }, emon.Api.Options.Defaults());
string html = model.ToHtml(new emon.Api.Options.Html());
```

## JavaScript

```js
import { Emon } from '@3sln/emon';
import { readFile } from 'node:fs/promises';

const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));
const model = await emon.convert(await readFile('report.pdf'));
console.log(model.html());
model.dispose();
```

## Licence

MIT. The third-party components these packages contain or load, and their licences, are listed in
`THIRD_PARTY_NOTICES.md`.
