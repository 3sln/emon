# emon for Rust

The crate [`emon`](https://crates.io/crates/emon) is the reference host: every other host is held to its bytes.
Rust 2021 edition. The default build has no native dependency; it converts born-digital PDFs on its own, and the
optional adapters below draw and read the rest. Install with `cargo add emon`.

## First conversion

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

`Engine::new(Config)` is made once and reused; `convert` takes an input and an `Options` and gives a `Model`; the
Model is serialised with `serialize(&Output::Html(..))` or `to_html` / `to_json`. This program is compiled and run
against the built crate by `bb package --check`.

## The shape of the API

| You | Rust |
|---|---|
| say what to do | `Options` (what a conversion does) and `Config` (ports, limits, chunk size); or one `key=value` text through `parse_options(text) -> Result<Parsed, String>`, where `Parsed { options, config, .. }` ([docs/options.md](../options.md)) |
| give a document | `Input`: `Input::bytes(Vec<u8>)`, `Input::seekable(R: Read + Seek)` (read lazily), `Input::spooled(R: Read, max)` (read into memory up to `max`), `Input::new(impl ByteSource)`, then `.with_password(..)`. `convert` and `intake` take anything `Into<Inputs>`: one `Input`, a `Vec<Input>` (several inputs make one document), a `Vec<u8>` or a `&[u8]` |
| convert | `Engine::convert(inputs, &Options) -> Result<Model, Error>`; `convert_stats` also gives the `RunStats`; `intake` is stage 0 alone (format, version, page count, encryption); `record` and `replay` keep and re-run a transcript of the port answers |
| read the result | `Model::root()`, `find(Kind)`, `pages()`, `furniture()`, `dropped()`, `assets()`; a `Node` has `kind()`, `text()`, `children()`, `inlines()`, `regions()`, `sources()`, `conf()`, `flags()`, `attr(name)`, `parent()` |
| write the result | `Model::to_html(&HtmlOptions)`, `to_json(&JsonOptions)`, `serialize(&Output)`; `Model::evidence()` with `keep_evidence` |
| route on a failure | `Error::name()`, a `match` on the variants of `Error` |

Coordinates in `Region` and `Page` are integers in centipoints (1/100 point), top-left origin; the JSON and the HTML
write points. A byte source is any `ByteSource` (`len`, `read_at`); the library never opens a path or touches the
network.

## A tour

<!-- snippet: docs/snippets/rust/tour.rs -->
```rust
use emon::{parse_options, Engine, Input, Kind};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // One text says what to do, limits included (docs/options.md).
    let parsed = parse_options("ocr=never\nlimit.pages=500")?;
    let engine = Engine::new(parsed.config);

    // An input is a byte source. A file (Read + Seek) is read lazily, a chunk at a time, only where the engine looks.
    // Stage 0 alone is cheap: format, version, pages, encryption.
    let file = Input::seekable(std::fs::File::open("report.pdf")?)?;
    for intake in engine.intake(file)? {
        println!("{} {}.{}, {} pages", intake.format, intake.version.0, intake.version.1, intake.pages);
    }

    // A failure has a stable name to route on: "password-required", "pages", "bad-xref", ...
    let bytes = std::fs::read("report.pdf")?; // or the bytes you already have
    let model = match engine.convert(Input::bytes(bytes), &parsed.options) {
        Ok(model) => model,
        Err(e) => {
            eprintln!("{}: {}", e.name(), e);
            std::process::exit(1);
        }
    };

    // Walk the Model: every heading, with its page.
    for h in model.find(Kind::named("heading").unwrap()) {
        let page = h.regions().first().map_or(0, |r| r.page);
        println!("page {}: {}", page, h.text());
    }
    println!("{} pages, {} nodes, {} dropped", model.pages().len(), model.node_count(), model.dropped().len());
    Ok(())
}
```

An `Error` is an enum: `NoInput`, `Limit { name, limit, actual }`, `PasswordRequired { input }`,
`PasswordIncorrect { input }`, `EncryptionUnsupported { input }`, `UnsupportedFormat { input }`,
`Damaged { input, status }`, `SourceChanged { input }`, `Io { input, message }`, `Unsupported(..)`, `Plan(..)`,
`Internal(..)`. `Error::name()` is the stable name of [docs/options.md](../options.md#errors); `input` is the
position of the input in the list. Limits are in the options text (`limit.pages=500`) or, as `(kind, value)` pairs,
in `Config::limits`.

## Pages that need drawing and reading: pdfium and Tesseract

A page is drawn by a `Renderer` and read by an `Ocr`; both are traits in `Config` (`renderer: Option<Arc<dyn Renderer>>`,
`ocr: Option<OcrPort>`). With neither, a request for them is answered `unsupported` and the text layer stands alone,
so a born-digital PDF needs neither. The crate ships two adapters, which are **provisional** (outside the freeze of
`api/surface.edn`) and compiled only when asked:

- `emon::pdfium::Pdfium`, under `RUSTFLAGS="--cfg pdfium"`, linking `libpdfium` (the `-L` path is yours).
  `--cfg pdfium_dl` loads the library at run time instead, with `emon::pdfium::load("/path/to/libpdfium.so")`; the
  command-line tool uses that.
- `emon::tess::TessOcr`, under the cargo feature `tesseract` (`emon = { version = "0.1", features = ["tesseract"] }`),
  linking `libtesseract` and `libleptonica`. `TessOcr::new(None)` finds the models in `$TESSDATA_PREFIX` and the usual
  places; `TessOcr::with_languages(dir, &langs)` takes the models to read with, a `&[String]` such as `eng`, `deu`.

This program converts a scan with both. It is built and run against the built crate by `bb package --check` on a machine
that has pdfium and Tesseract installed (it prints a NOTE and skips them on one that does not, as a CI runner does):

<!-- snippet: docs/snippets/rust/native.rs -->
```rust
use emon::{pdfium::Pdfium, tess::TessOcr, Config, Engine, HtmlOptions, Input, OcrPort, Options, Output};
use std::sync::Arc;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut config = Config::default();
    config.renderer = Some(Arc::new(Pdfium::new())); // built with --cfg pdfium: links libpdfium
    config.ocr = Some(OcrPort(Arc::new(TessOcr::new(None)?))); // built with --features tesseract: links libtesseract and libleptonica
    let engine = Engine::new(config);
    let model = engine.convert(Input::bytes(std::fs::read("scan.pdf")?), &Options::default())?;
    std::fs::write("scan.html", model.serialize(&Output::Html(HtmlOptions::default())))?;
    Ok(())
}
```

Build it with `RUSTFLAGS="--cfg pdfium -L native=/path/to/pdfium/lib -L native=/opt/homebrew/lib"`
(adjust the paths; on macOS add `-C link-arg=-Wl,-rpath,/path/to/pdfium/lib`). pdfium builds are at
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases) (emon is tested with `chromium/8076`);
Tesseract and Leptonica from your package manager, with the `eng` and `osd` models. To bring your own engine instead,
implement `Renderer` (`render(source, password, &RenderRequest) -> Result<Bitmap, RenderError>`, RGB pixels) or `Ocr`
(its answers are the flat records of `emon.ocrport`; prefer the adapter). JBIG2 and JPEG 2000 images are not decoded:
`Config::pdf_codecs` is the hook, and nothing ships to fill it.

## Notes

- `Engine`, the ports and the byte sources are `Send + Sync` by their trait bounds; give each thread a conversion of its own.
  `parallel=N` spreads the work of one conversion over N threads of emon's own (the Model is the serial run's bytes).
- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a value in `Config` or
  in the options text beats it.
- The `no_retarget` test setting of `Options` is not in the published crate (`bb package --check` proves it).

<!-- bb docs:begin api-index -->
<details><summary>The public API of the rust host: 323 entries, and 41 provisional (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `emon::Asset` | `struct<'a> derive(Clone,Copy) +private` |
| `emon::Asset::bytes` | `fn (&self) -> Vec<u8>` |
| `emon::Asset::dpi` | `fn (&self) -> Option<i32>` |
| `emon::Asset::flags` | `fn (&self) -> Vec<String>` |
| `emon::Asset::from` | `fn (&self) -> String` |
| `emon::Asset::height` | `fn (&self) -> i32` |
| `emon::Asset::id` | `fn (&self) -> i32` |
| `emon::Asset::impl std::fmt::Debug` | `impl` |
| `emon::Asset::media_type` | `fn (&self) -> String` |
| `emon::Asset::name` | `fn (&self) -> String` |
| `emon::Asset::object` | `fn (&self) -> Option<i32>` |
| `emon::Asset::origin` | `fn (&self) -> String` |
| `emon::Asset::sha256` | `fn (&self) -> String` |
| `emon::Asset::uses` | `fn (&self) -> Vec<AssetUse>` |
| `emon::Asset::width` | `fn (&self) -> i32` |
| `emon::AssetFile` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::AssetFile::bytes` | `field Vec<u8>` |
| `emon::AssetFile::media_type` | `field String` |
| `emon::AssetFile::name` | `field String` |
| `emon::AssetUse` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::AssetUse::node` | `field i32` |
| `emon::AssetUse::region` | `field Region` |
| `emon::Assets` | `enum derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::Assets::Figures` | `variant` |
| `emon::Assets::Images` | `variant` |
| `emon::Assets::None` | `variant` |
| `emon::Attr` | `enum derive(Clone,Debug,PartialEq)` |
| `emon::Attr::Bool` | `variant (bool)` |
| `emon::Attr::Int` | `variant (i32)` |
| `emon::Attr::Node` | `variant (i32)` |
| `emon::Attr::Text` | `variant (String)` |
| `emon::ByteSource` | `trait: Send + Sync` |
| `emon::ByteSource::as_slice` | `fn provided (&self) -> Option<&[u8]>` |
| `emon::ByteSource::len` | `fn required (&self) -> u64` |
| `emon::ByteSource::read_at` | `fn required (&self, u64, &mut [u8]) -> io::Result<usize>` |
| `emon::BytesSource` | `struct (Vec<u8>)` |
| `emon::BytesSource::impl ByteSource` | `impl` |
| `emon::BytesSource::new` | `fn (&[u8]) -> BytesSource` |
| `emon::Clip` | `struct derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::Clip::h` | `field i32` |
| `emon::Clip::w` | `field i32` |
| `emon::Clip::x` | `field i32` |
| `emon::Clip::y` | `field i32` |
| `emon::CodecFail` | `enum derive(Clone,Debug,PartialEq)` |
| `emon::CodecFail::Failed` | `variant (String)` |
| `emon::CodecFail::Limit` | `variant (u64)` |
| `emon::CodecFail::Unsupported` | `variant (String)` |
| `emon::Config` | `struct derive(Clone,Debug)` |
| `emon::Config::chunk_size` | `field usize` |
| `emon::Config::fetch_rounds` | `field i32` |
| `emon::Config::impl Default` | `impl` |
| `emon::Config::limits` | `field Vec<(i32,i32)>` |
| `emon::Config::max_rounds` | `field i32` |
| `emon::Config::ocr` | `field Option<OcrPort>` |
| `emon::Config::parallel` | `field i32` |
| `emon::Config::pdf_codecs` | `field Option<Arc<dyn PdfCodecs>>` |
| `emon::Config::renderer` | `field Option<Arc<dyn Renderer>>` |
| `emon::Config::simulate_async` | `field bool` |
| `emon::Dropped` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::Dropped::page` | `field i32` |
| `emon::Dropped::reason` | `field String` |
| `emon::Dropped::region` | `field Region` |
| `emon::Dropped::text` | `field String` |
| `emon::Engine` | `struct +private` |
| `emon::Engine::convert` | `fn (&self, impl Into<Inputs>, &Options) -> Result<Model, Error>` |
| `emon::Engine::convert_stats` | `fn (&self, impl Into<Inputs>, &Options) -> Result<(Model, RunStats), Error>` |
| `emon::Engine::intake` | `fn (&self, impl Into<Inputs>) -> Result<Vec<Intake>, Error>` |
| `emon::Engine::new` | `fn (Config) -> Engine` |
| `emon::Engine::record` | `fn (&self, impl Into<Inputs>, &Options) -> Result<(Model, Transcript), Error>` |
| `emon::Engine::replay` | `fn (&self, impl Into<Inputs>, &Options, &Transcript) -> Result<Model, Error>` |
| `emon::Error` | `enum derive(Clone,Debug,PartialEq)` |
| `emon::Error::Damaged` | `variant { input: usize,status: String }` |
| `emon::Error::EncryptionUnsupported` | `variant { input: usize }` |
| `emon::Error::Internal` | `variant (String)` |
| `emon::Error::Io` | `variant { input: usize,message: String }` |
| `emon::Error::Limit` | `variant { name: String,limit: i64,actual: i64 }` |
| `emon::Error::NoInput` | `variant` |
| `emon::Error::PasswordIncorrect` | `variant { input: usize }` |
| `emon::Error::PasswordRequired` | `variant { input: usize }` |
| `emon::Error::Plan` | `variant (PlanError)` |
| `emon::Error::SourceChanged` | `variant { input: usize }` |
| `emon::Error::Unsupported` | `variant (&'static str)` |
| `emon::Error::UnsupportedFormat` | `variant { input: usize }` |
| `emon::Error::impl From<PlanError>` | `impl` |
| `emon::Error::impl std::error::Error` | `impl` |
| `emon::Error::impl std::fmt::Display` | `impl` |
| `emon::Error::name` | `fn (&self) -> String` |
| `emon::Furniture` | `enum derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::Furniture::Drop` | `variant` |
| `emon::Furniture::Keep` | `variant` |
| `emon::Furniture::Policy` | `variant` |
| `emon::HtmlAssets` | `enum derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::HtmlAssets::External` | `variant` |
| `emon::HtmlAssets::Inline` | `variant` |
| `emon::HtmlAssets::Omit` | `variant` |
| `emon::HtmlOptions` | `struct derive(Clone,Debug)` |
| `emon::HtmlOptions::align_style` | `field bool` |
| `emon::HtmlOptions::asset_prefix` | `field String` |
| `emon::HtmlOptions::assets` | `field HtmlAssets` |
| `emon::HtmlOptions::confidence_below` | `field Option<u32>` |
| `emon::HtmlOptions::furniture_drop` | `field bool` |
| `emon::HtmlOptions::geometry` | `field bool` |
| `emon::HtmlOptions::ids` | `field bool` |
| `emon::HtmlOptions::impl Default` | `impl` |
| `emon::HtmlOptions::initials_plain` | `field bool` |
| `emon::HtmlOptions::ledger` | `field bool` |
| `emon::HtmlOptions::milestones` | `field bool` |
| `emon::HtmlOptions::pretty` | `field bool` |
| `emon::Input` | `struct derive(Clone)` |
| `emon::Input::bytes` | `fn (Vec<u8>) -> Input` |
| `emon::Input::impl From<&[u8]>` | `impl` |
| `emon::Input::impl From<Vec<u8>>` | `impl` |
| `emon::Input::impl std::fmt::Debug` | `impl` |
| `emon::Input::new` | `fn (impl ByteSource + 'static) -> Input` |
| `emon::Input::password` | `field Option<Vec<u8>>` |
| `emon::Input::seekable` | `fn <R: Read + Seek + Send + 'static>(R) -> io::Result<Input>` |
| `emon::Input::source` | `field Arc<dyn ByteSource>` |
| `emon::Input::spooled` | `fn <R: Read>(R, u64) -> io::Result<Input>` |
| `emon::Input::with_password` | `fn (mut self, impl Into<Vec<u8>>) -> Input` |
| `emon::Inputs` | `struct (pub Vec<Input>)` |
| `emon::Inputs::impl From<&[u8; N]>` | `impl` |
| `emon::Inputs::impl From<&[u8]>` | `impl` |
| `emon::Inputs::impl From<Input>` | `impl` |
| `emon::Inputs::impl From<Vec<Input>>` | `impl` |
| `emon::Inputs::impl From<Vec<u8>>` | `impl` |
| `emon::Intake` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::Intake::bytes` | `field u64` |
| `emon::Intake::encrypted` | `field bool` |
| `emon::Intake::format` | `field &'static str` |
| `emon::Intake::pages` | `field usize` |
| `emon::Intake::version` | `field (i32,i32)` |
| `emon::JsonOptions` | `struct derive(Clone,Copy,Debug,Default)` |
| `emon::JsonOptions::pretty` | `field bool` |
| `emon::Kind` | `struct (pub i32) derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::Kind::name` | `fn (&self) -> String` |
| `emon::Kind::named` | `fn (&str) -> Option<Kind>` |
| `emon::KindMux` | `struct +private` |
| `emon::KindMux::impl Default` | `impl` |
| `emon::KindMux::impl Fulfiller` | `impl` |
| `emon::KindMux::new` | `fn () -> KindMux` |
| `emon::KindMux::with` | `fn (mut self, &[i32], Box<dyn Fulfiller>) -> KindMux` |
| `emon::Model` | `struct +private` |
| `emon::Model::asset` | `fn (&self, i32) -> Option<Asset<'_>>` |
| `emon::Model::asset_files` | `fn (&self) -> Vec<AssetFile>` |
| `emon::Model::assets` | `fn (&self) -> Vec<Asset<'_>>` |
| `emon::Model::dropped` | `fn (&self) -> Vec<Dropped>` |
| `emon::Model::evidence` | `fn (&self) -> Option<&str>` |
| `emon::Model::find` | `fn (&self, Kind) -> Vec<Node<'_>>` |
| `emon::Model::furniture` | `fn (&self) -> Vec<Node<'_>>` |
| `emon::Model::node_count` | `fn (&self) -> usize` |
| `emon::Model::pages` | `fn (&self) -> Vec<Page>` |
| `emon::Model::root` | `fn (&self) -> Node<'_>` |
| `emon::Model::serialize` | `fn (&self, &Output) -> String` |
| `emon::Model::source` | `fn (&self) -> &Intake` |
| `emon::Model::to_html` | `fn (&self, &HtmlOptions) -> String` |
| `emon::Model::to_json` | `fn (&self, &JsonOptions) -> String` |
| `emon::Node` | `struct<'a> derive(Clone,Copy) +private` |
| `emon::Node::asset` | `fn (&self) -> Option<Asset<'a>>` |
| `emon::Node::attr` | `fn (&self, &str) -> Option<Attr>` |
| `emon::Node::blocks` | `fn (&self) -> Vec<Node<'a>>` |
| `emon::Node::children` | `fn (&self) -> Vec<Node<'a>>` |
| `emon::Node::conf` | `fn (&self) -> i32` |
| `emon::Node::flags` | `fn (&self) -> i32` |
| `emon::Node::id` | `fn (&self) -> i32` |
| `emon::Node::impl std::fmt::Debug` | `impl` |
| `emon::Node::index` | `fn (&self) -> usize` |
| `emon::Node::inlines` | `fn (&self) -> Vec<Node<'a>>` |
| `emon::Node::is_inline` | `fn (&self) -> bool` |
| `emon::Node::kind` | `fn (&self) -> Kind` |
| `emon::Node::noasset` | `fn (&self) -> Option<String>` |
| `emon::Node::parent` | `fn (&self) -> Option<Node<'a>>` |
| `emon::Node::regions` | `fn (&self) -> Vec<Region>` |
| `emon::Node::sources` | `fn (&self) -> Vec<String>` |
| `emon::Node::text` | `fn (&self) -> String` |
| `emon::Ocr` | `trait: Send + Sync` |
| `emon::Ocr::fulfil` | `fn required (&self, &plan::Request, &plan::Bitmaps, bool) -> Result<plan::Answer, PlanError>` |
| `emon::Ocr::producer` | `fn provided (&self) -> Vec<(String, String)>` |
| `emon::OcrPolicy` | `enum derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::OcrPolicy::Always` | `variant` |
| `emon::OcrPolicy::Auto` | `variant` |
| `emon::OcrPolicy::Never` | `variant` |
| `emon::OcrPort` | `struct (pub Arc<dyn Ocr>) derive(Clone)` |
| `emon::OcrPort::impl std::fmt::Debug` | `impl` |
| `emon::OcrScope` | `enum derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::OcrScope::Full` | `variant` |
| `emon::OcrScope::Witness` | `variant` |
| `emon::Options` | `struct derive(Clone,Debug)` |
| `emon::Options::asset_bytes` | `field i32` |
| `emon::Options::asset_dpi` | `field i32` |
| `emon::Options::asset_pixels` | `field i32` |
| `emon::Options::asset_signatures` | `field bool` |
| `emon::Options::assets` | `field Assets` |
| `emon::Options::assets_bytes` | `field i32` |
| `emon::Options::furniture` | `field Furniture` |
| `emon::Options::impl Default` | `impl` |
| `emon::Options::keep_evidence` | `field bool` |
| `emon::Options::locale` | `field Option<String>` |
| `emon::Options::ocr` | `field OcrPolicy` |
| `emon::Options::ocr_scope` | `field OcrScope` |
| `emon::Output` | `enum derive(Clone,Debug)` |
| `emon::Output::Html` | `variant (HtmlOptions)` |
| `emon::Output::Json` | `variant (JsonOptions)` |
| `emon::Page` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::Page::height` | `field i32` |
| `emon::Page::kind` | `field String` |
| `emon::Page::label` | `field Option<String>` |
| `emon::Page::number` | `field i32` |
| `emon::Page::rotation` | `field i32` |
| `emon::Page::width` | `field i32` |
| `emon::Parsed` | `struct derive(Clone,Debug)` |
| `emon::Parsed::config` | `field Config` |
| `emon::Parsed::js_ocr` | `field bool` |
| `emon::Parsed::js_render` | `field bool` |
| `emon::Parsed::options` | `field Options` |
| `emon::Parsed::producer` | `field Vec<(String,String)>` |
| `emon::Parsed::record` | `field bool` |
| `emon::PdfCodecs` | `trait: Send + Sync + std::fmt::Debug` |
| `emon::PdfCodecs::jbig2` | `fn required (&self, &[u8], &[u8], u64) -> Result<Pix, CodecFail>` |
| `emon::PdfCodecs::jpx` | `fn required (&self, &[u8], u64) -> Result<Pix, CodecFail>` |
| `emon::Pix` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::Pix::bilevel` | `field bool` |
| `emon::Pix::ch` | `field i32` |
| `emon::Pix::h` | `field i32` |
| `emon::Pix::px` | `field Vec<u8>` |
| `emon::Pix::w` | `field i32` |
| `emon::Region` | `struct derive(Clone,Copy,Debug,PartialEq)` |
| `emon::Region::page` | `field i32` |
| `emon::Region::x0` | `field i32` |
| `emon::Region::x1` | `field i32` |
| `emon::Region::y0` | `field i32` |
| `emon::Region::y1` | `field i32` |
| `emon::RenderError` | `enum derive(Clone,Debug,Eq,PartialEq)` |
| `emon::RenderError::Failed` | `variant (String)` |
| `emon::RenderError::Unsupported` | `variant (String)` |
| `emon::RenderError::impl std::error::Error` | `impl` |
| `emon::RenderError::impl std::fmt::Display` | `impl` |
| `emon::RenderFulfiller` | `struct +private` |
| `emon::RenderFulfiller::for_input` | `fn (Arc<dyn Renderer>, usize, RenderInput) -> RenderFulfiller` |
| `emon::RenderFulfiller::fulfil` | `fn (&self, &Request) -> Result<Answer, PlanError>` |
| `emon::RenderFulfiller::impl Fulfiller` | `impl` |
| `emon::RenderFulfiller::new` | `fn (Arc<dyn Renderer>, Vec<RenderInput>) -> RenderFulfiller` |
| `emon::RenderInput` | `struct derive(Clone)` |
| `emon::RenderInput::password` | `field Option<Vec<u8>>` |
| `emon::RenderInput::source` | `field Arc<dyn ByteSource>` |
| `emon::RenderRequest` | `struct derive(Clone,Copy,Debug,Eq,PartialEq)` |
| `emon::RenderRequest::clip` | `field Option<Clip>` |
| `emon::RenderRequest::dpi` | `field i32` |
| `emon::RenderRequest::layer` | `field i32` |
| `emon::RenderRequest::page` | `field i32` |
| `emon::Renderer` | `trait: Send + Sync + std::fmt::Debug` |
| `emon::Renderer::exact_crop` | `fn provided (&self) -> bool` |
| `emon::Renderer::layers` | `fn provided (&self) -> u32` |
| `emon::Renderer::render` | `fn required (&self, &dyn ByteSource, Option<&[u8]>, &RenderRequest) -> Result<Bitmap, RenderError>` |
| `emon::RunStats` | `struct derive(Clone,Debug,Default,PartialEq)` |
| `emon::RunStats::attempts` | `field i32` |
| `emon::RunStats::bytes_fetched` | `field u64` |
| `emon::RunStats::bytes_requests` | `field i32` |
| `emon::RunStats::fetch_rounds` | `field i32` |
| `emon::RunStats::finish_us` | `field u64` |
| `emon::RunStats::fulfil_us` | `field [u64; 8]` |
| `emon::RunStats::input_bytes` | `field u64` |
| `emon::RunStats::page_requests` | `field Vec<i32>` |
| `emon::RunStats::page_rounds` | `field Vec<i32>` |
| `emon::RunStats::pages_us` | `field u64` |
| `emon::RunStats::pass_us` | `field u64` |
| `emon::RunStats::requests` | `field i32` |
| `emon::RunStats::source_reads` | `field u32` |
| `emon::SeekSource` | `struct<R: Read + Seek + Send> +private` |
| `emon::SeekSource::impl ByteSource` | `impl` |
| `emon::SeekSource::new` | `fn (R) -> io::Result<SeekSource<R>>` |
| `emon::SpoolSource` | `struct (Vec<u8>)` |
| `emon::SpoolSource::impl ByteSource` | `impl` |
| `emon::SpoolSource::spool` | `fn <R: Read>(R, u64) -> io::Result<SpoolSource>` |
| `emon::Transcript` | `struct derive(Clone,Debug,Default)` |
| `emon::Transcript::check_inputs` | `fn (&self, &[(i32, i32)]) -> Result<(), PlanError>` |
| `emon::Transcript::entries` | `field Vec<Entry>` |
| `emon::Transcript::from_files` | `fn (&BTreeMap<String, Vec<u8>>) -> Result<Transcript, PlanError>` |
| `emon::Transcript::inputs` | `field Vec<(i32,i32)>` |
| `emon::Transcript::producer` | `field Vec<(String,String)>` |
| `emon::Transcript::to_files` | `fn (&self) -> Vec<(String, Vec<u8>)>` |
| `emon::is_option_key` | `fn (&str) -> bool` |
| `emon::parse_options` | `fn (&str) -> Result<Parsed, String>` |
| `emon::plan::Answer` | `struct derive(Clone,Debug)` |
| `emon::plan::Answer::bitmap` | `field Option<Arc<Bitmap>>` |
| `emon::plan::Answer::id` | `fn (&self) -> i32` |
| `emon::plan::Answer::kind` | `fn (&self) -> i32` |
| `emon::plan::Answer::rec` | `field Vec<i32>` |
| `emon::plan::Answer::status` | `fn (&self) -> i32` |
| `emon::plan::Bitmaps` | `type = BTreeMap<i32,Arc<Bitmap>>` |
| `emon::plan::Entry` | `struct derive(Clone,Debug)` |
| `emon::plan::Entry::ans` | `field Vec<i32>` |
| `emon::plan::Entry::bitmap` | `field Option<Arc<Bitmap>>` |
| `emon::plan::Entry::req` | `field Vec<i32>` |
| `emon::plan::Entry::round` | `field i32` |
| `emon::plan::Fulfiller` | `trait` |
| `emon::plan::Fulfiller::next` | `fn required (&mut self) -> Result<Answer, PlanError>` |
| `emon::plan::Fulfiller::start` | `fn required (&mut self, &Request, &Bitmaps) -> Result<(), PlanError>` |
| `emon::plan::PlanError` | `enum derive(Clone,Debug,PartialEq)` |
| `emon::plan::PlanError::Bad` | `variant (String)` |
| `emon::plan::PlanError::Fulfil` | `variant (String)` |
| `emon::plan::PlanError::Limit` | `variant { name: String,limit: i32,actual: i32 }` |
| `emon::plan::PlanError::Stalled` | `variant` |
| `emon::plan::PlanError::TranscriptBad` | `variant (String)` |
| `emon::plan::PlanError::TranscriptMiss` | `variant (Vec<i32>)` |
| `emon::plan::PlanError::Unit` | `variant (i32)` |
| `emon::plan::PlanError::impl std::fmt::Display` | `impl` |
| `emon::plan::PlanError::name` | `fn (&self) -> String` |
| `emon::plan::Request` | `struct derive(Clone,Debug,PartialEq)` |
| `emon::plan::Request::arg` | `fn (&self, usize) -> i32` |
| `emon::plan::Request::field` | `fn (&self, i32) -> i32` |
| `emon::plan::Request::id` | `fn (&self) -> i32` |
| `emon::plan::Request::kind` | `fn (&self) -> i32` |
| `emon::plan::Request::new` | `fn (Vec<i32>) -> Request` |
| `emon::plan::Request::payload` | `field Arc<Vec<u8>>` |
| `emon::plan::Request::rec` | `field Vec<i32>` |
| `emon::port::Bitmap` | `struct derive(Clone,Debug,PartialEq) +private` |
| `emon::port::Bitmap::channels` | `fn (&self) -> i32` |
| `emon::port::Bitmap::from_bytes` | `fn (i32, i32, i32, &[u8]) -> Bitmap` |
| `emon::port::Bitmap::height` | `fn (&self) -> i32` |
| `emon::port::Bitmap::impl Sync` | `impl` |
| `emon::port::Bitmap::new` | `fn (i32, i32, i32) -> Bitmap` |
| `emon::port::Bitmap::to_bytes` | `fn (&self) -> Vec<u8>` |
| `emon::port::Bitmap::width` | `fn (&self) -> i32` |

**Provisional** (shipped, not frozen: live adapters and driver flags)

| Name | Signature |
|---|---|
| `mod par` | `pub mod #[cfg(not(target_arch = "wasm32"))]` |
| `mod pdfcodecs` | `pub mod #[cfg(codecs)]` |
| `mod pdfium` | `pub mod #[cfg(pdfium)]` |
| `mod tess` | `pub mod #[cfg(feature = "tesseract")]` |
| `par::Exec` | `struct` |
| `par::Exec::local` | `field Box<dyn Fn(&Request,&Bitmaps) -> Result<Answer,PlanError> + Send + Sync>` |
| `par::Exec::ocr` | `field bool` |
| `par::Exec::render` | `field Option<RenderFulfiller>` |
| `par::Exec::run` | `fn (&self, &Request, &Bitmaps) -> Result<Answer, PlanError>` |
| `par::Job` | `struct +private` |
| `par::Look` | `trait` |
| `par::Look::dry` | `fn required (&mut self, usize, &mut Cells, &mut Cells, &BitmapSet, &mut Buf) -> i32` |
| `par::Look::page` | `fn required (&self) -> Option<usize>` |
| `par::Look::pages` | `fn required (&self) -> usize` |
| `par::Par` | `struct +private` |
| `par::Par::impl Drop` | `impl` |
| `par::Par::new` | `fn (Exec, usize, usize, Vec<(i32, i32)>, PlanConfig) -> Par` |
| `par::RoundFulfiller` | `struct<'a> +private` |
| `par::RoundFulfiller::impl Fulfiller` | `impl` |
| `par::RoundFulfiller::new` | `fn (&'a mut Par, &'a mut dyn Look) -> RoundFulfiller<'a>` |
| `pdfcodecs::Codecs` | `struct derive(Clone,Copy,Debug,Default)` |
| `pdfcodecs::Codecs::impl PdfCodecs` | `impl` |
| `pdfcodecs::Codecs::new` | `fn () -> Codecs` |
| `pdfium::Pdfium` | `struct derive(Debug)` |
| `pdfium::Pdfium::impl Default` | `impl` |
| `pdfium::Pdfium::impl Renderer` | `impl` |
| `pdfium::Pdfium::new` | `fn () -> Pdfium` |
| `tess::Tess` | `struct +private` |
| `tess::Tess::dpi` | `field i32` |
| `tess::Tess::fulfil` | `fn (&mut self, &Request, &Bitmaps, bool) -> Result<Answer, PlanError>` |
| `tess::Tess::impl Send` | `impl` |
| `tess::Tess::new` | `fn (&str, i32) -> Tess` |
| `tess::Tess::notes` | `field Vec<String>` |
| `tess::Tess::only` | `field Option<Vec<String>>` |
| `tess::Tess::producer` | `fn (&self) -> Vec<(String, String)>` |
| `tess::TessOcr` | `struct +private` |
| `tess::TessOcr::impl crate::engine::Ocr` | `impl` |
| `tess::TessOcr::new` | `fn (Option<&str>) -> Result<TessOcr, String>` |
| `tess::TessOcr::with_languages` | `fn (&str, &[String]) -> Result<TessOcr, String>` |
| `tess::leptonica_version` | `fn () -> String` |
| `tess::version` | `fn () -> String` |

</details>
<!-- bb docs:end api-index -->
