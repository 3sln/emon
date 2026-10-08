// THE ENGINE API, Rust host (DEVLOG P1.10; SPEC 13, 13.1, 3.9; D058, D074, D075, D040, D089-D091, D117).
//
//     let engine = Engine::new(Config::default());
//     let model = engine.convert(Input::bytes(pdf), &Options::default())?;       // or Input::seekable(file)?
//     let json = model.to_json(&JsonOptions::default());
//     let html = model.to_html(&HtmlOptions::default());
//     for p in model.root().children() { println!("{}", p.text()); }
//
// WHAT THE SLICE DOES (P1.10 is the first vertical slice): a born-digital PDF's text layer -> lines ->
// paragraphs -> Model -> JSON/HTML. It does NOT yet do (each is an error or an absent field, never a
// quiet approximation; D1xx records where):
//   * OCR. `Options::ocr` is accepted and kept (D038: the default is `always`) but NOTHING OCRs yet: no
//     Renderer or Ocr port is configured, so a page with no text layer comes out empty and the Model's
//     header does not write an `ocr` setting (it would claim a run that did not happen).
//   * several inputs as one document (`convert` of more than one input is `Error::Unsupported`; `intake`
//     takes any number; P6.1 left image sets and PDF+image mixes to the follow-up, D187).
//
// IMAGES ARE READ (P6.1, D187-D190; this was `unsupported-format` in D144): a PNG, JPEG or TIFF (by
// signature, in kin) becomes one `image` page per frame, a multi-page TIFF becoming several. The host's
// `ImageDecode` port (`imgdec.rs`) is asked through the plan/fulfil loop, so `record` writes the pixels as
// canonical PNG and `replay` needs no codec. There is no OCR yet (P6.5): an image page has no text. What is
// not an image or a PDF is still `unsupported-format`; an image the port cannot read is `undecodable` (damaged)
// or `unsupported-image` (a coding it does not read: D187).
//   * `assets` `figures` (D117, P8.5): the asset unit runs after the document unit (D351) and renders or crops every other figure
//     (a `crop` through the Renderer; a raster input's frame cut); at `images` a figure or seal that is one embedded image gets its bytes or its pixels as PNG (P3.13),
//   * document metadata in the header (`source`, `meta`) and every structure above the paragraph.
//
// THE LOOP IS A MACHINE (P9.3, D356): `Run` below is one input's conversion a step at a time (the document pass, the asset unit,
// a unit per page, the document unit), over `plan::Machine`; `Engine::convert` drives it synchronously, the browser's WASM
// binding (`host/wasm`) lets its JS side own the loop and awaits what `step` says it needs (bytes, answers).
//
// THE LOOP IS WIRED (D058 revised, D089-D091). `convert` runs `plan::run` over one unit (everything kin
// does for one input: open, text layer, paragraphs, Model). The unit gets a fresh `Src` view and fresh
// arenas per attempt; when it answers `need-bytes` the loop fetches what the cache missed (doubling
// read-ahead, coalesced, `FETCH_ROUNDS`) and re-runs it; when it answers `requests` the loop starts them:
// a PDF issues ZERO requests in this slice, so no Renderer or Ocr is called, and an image issues `decode`
// requests only (the `ImageDecode` port). A sync source is
// read inline and never misses; `Config::simulate_async` makes the cache behave like a browser `Blob`
// (misses, fetch rounds), which is what `--chunk=N --async` of the driver runs, and the Model it keeps
// is the in-memory run's, byte for byte.
//
// NOTHING IN THIS FILE NAMES A FILESYSTEM (D058): inputs are `source::ByteSource`s.

use crate::emon::enctx::{Arenas, Cfg, Doc, EvArenas, Scratch};
use crate::emon::engine::{en_finish_pdf, en_finish_raster, en_seal};
use crate::emon::html::*;
use crate::emon::json::*;
use crate::emon::inline::il_fonts;
use crate::emon::asset::*;
use crate::emon::figclass::fc_images;
use crate::emon::model::*;
use crate::emon::pageunit::{pu_plan, pu_run_pdf};
use crate::emon::pdfannot::pa_run;
use crate::emon::rasterin::*;
use crate::emon::pdfcontent::*;
use crate::emon::pdfdoc::*;
use crate::emon::pdfmeta::pm_run;
use crate::emon::pdfstruct::{tg_join, tg_read};
use crate::emon::pdfx::px_index_build;
use crate::emon::gfuse::{gf_doc, gfx_mode};
use crate::emon::tlhealth::th_run;
use crate::imgdec;
use crate::pdfimg::{self, PdfCodecs};
use crate::plan::{self, Answer, Fulfiller, Misses, NoMisses, PlanError, Range, Replayer, Request, SyncFulfiller};
use crate::port::{self, cells_at, cells_len, cells_push, cells_set, BitmapSet, Buf, Cells, Src, SrcBacking};
use crate::render::{RenderFulfiller, RenderInput, Renderer};
use crate::source::{ByteSource, Input, Inputs};
use std::collections::{BTreeSet, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

pub use crate::plan::Transcript;

// ------------------------------------------------------------------ errors

#[derive(Debug, Clone, PartialEq)]
pub enum Error {
    // No input was given.
    NoInput,
    // A named bound (SPEC 7.4): `input-bytes`, `pages`, `objects`, ... or the loop's `max-rounds`, `fetch-rounds`.
    Limit { name: String, limit: i64, actual: i64 },
    // D040: the three encryption errors are distinct from each other and from every other failure.
    PasswordRequired { input: usize },
    PasswordIncorrect { input: usize },
    EncryptionUnsupported { input: usize },
    // Not a PDF (no `%PDF-` header) and not a PNG, JPEG or TIFF either.
    UnsupportedFormat { input: usize },
    // A PDF kin could not open (its named status: `no-startxref`, `bad-xref`, `no-root`, `no-pages`, ...), or an image the
    // host could not read (`undecodable`) or reads no coding of (`unsupported-image`).
    Damaged { input: usize, status: String },
    // A source that answered fewer bytes than its length said, or an error from its read.
    SourceChanged { input: usize },
    Io { input: usize, message: String },
    // Specified, not implemented in this slice.
    Unsupported(&'static str),
    // The plan/fulfil loop's own errors (a transcript miss, a bad record, ...).
    Plan(PlanError),
    // The Model kin built failed its own check, or kin panicked: a bug, never the document's fault.
    Internal(String),
}

impl Error {
    // The stable name a crawler routes on.
    pub fn name(&self) -> String {
        match self {
            Error::NoInput => "no-input".to_string(),
            Error::Limit { name, .. } => name.clone(),
            Error::PasswordRequired { .. } => "password-required".to_string(),
            Error::PasswordIncorrect { .. } => "password-incorrect".to_string(),
            Error::EncryptionUnsupported { .. } => "encryption-unsupported".to_string(),
            Error::UnsupportedFormat { .. } => "unsupported-format".to_string(),
            Error::Damaged { status, .. } => status.clone(),
            Error::SourceChanged { .. } => "source-changed".to_string(),
            Error::Io { .. } => "io".to_string(),
            Error::Unsupported(_) => "unsupported".to_string(),
            Error::Plan(p) => p.name().to_string(),
            Error::Internal(_) => "internal".to_string(),
        }
    }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            Error::NoInput => write!(f, "no-input: nothing to convert"),
            Error::Limit { name, limit, actual } => write!(f, "{}: the limit is {} and this input reached {}", name, limit, actual),
            Error::PasswordRequired { input } => write!(f, "password-required: input {} is encrypted and needs a password", input),
            Error::PasswordIncorrect { input } => write!(f, "password-incorrect: the password does not open input {}", input),
            Error::EncryptionUnsupported { input } => write!(f, "encryption-unsupported: input {} uses a security handler emon does not read", input),
            Error::UnsupportedFormat { input } => write!(f, "unsupported-format: input {} is not a PDF, PNG, JPEG or TIFF", input),
            Error::Damaged { input, status } => write!(f, "{}: input {} could not be opened", status, input),
            Error::SourceChanged { input } => write!(f, "source-changed: input {} gave fewer bytes than its length", input),
            Error::Io { input, message } => write!(f, "io: input {}: {}", input, message),
            Error::Unsupported(what) => write!(f, "unsupported: {}", what),
            Error::Plan(p) => write!(f, "{}", p),
            Error::Internal(m) => write!(f, "internal: {}", m),
        }
    }
}

impl std::error::Error for Error {}

impl From<PlanError> for Error {
    fn from(p: PlanError) -> Error {
        match p {
            PlanError::Limit { name, limit, actual } => Error::Limit { name, limit: limit as i64, actual: actual as i64 },
            other => Error::Plan(other),
        }
    }
}

// ----------------------------------------------------------------- options

// SPEC 13's OCR setting (D038). WIRED (P6.5, D211-D215; switched at P7.S, D200): `Never` asks for no OCR; `Auto` and `Always` OCR an image and a
// scanned PDF page; a born-digital page (a page with a text layer) is OCR'd and FUSED with its text layer under `Always`, and under `Auto` when its
// health, the images on it or hidden text call for it (`emon.rasterplan`, SPEC 8.3). With no `Renderer` or no `Ocr` every request is answered
// `unsupported` and the text layer stands alone.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OcrPolicy {
    Always,
    Auto,
    Never,
}

impl OcrPolicy {
    // kin's code (`emon.scan`, `emon.rasterin`): 0 never, 1 auto, 2 always.
    fn code(self) -> i32 {
        match self {
            OcrPolicy::Never => 0,
            OcrPolicy::Auto => 1,
            OcrPolicy::Always => 2,
        }
    }

    // The policy of kin's code (what a parsed `Cfg` holds).
    fn from_code(code: i32) -> OcrPolicy {
        match code {
            0 => OcrPolicy::Never,
            1 => OcrPolicy::Auto,
            _ => OcrPolicy::Always,
        }
    }
}

// THE `Ocr` PORT (SPEC 7.2; D008, D095, D211): fulfils one `ocr` request. A host brings its engine (Tesseract through
// `tess.rs` under the `tesseract` feature; anything else that answers in the layout of `emon.ocr`). It is LIVE only: a
// gate replays a transcript and never calls it. With no port a request is answered `unsupported`, a page
// then has no text, and nothing is guessed.
pub trait Ocr: Send + Sync {
    // The answer to `req` (whose bitmap is in `bitmaps`). `keep_binary`: the caller records, so the engine's
    // thresholded image, when it has one, goes in the answer's `bitmap` (the record says it has one either way).
    fn fulfil(&self, req: &plan::Request, bitmaps: &plan::Bitmaps, keep_binary: bool) -> Result<plan::Answer, PlanError>;
    // What this engine is, for the transcript's `:producer` (D214): name/value strings.
    fn producer(&self) -> Vec<(String, String)> {
        Vec::new()
    }
}

// An `Ocr` port as a `Config` holds it.
#[derive(Clone)]
pub struct OcrPort(pub Arc<dyn Ocr>);

impl std::fmt::Debug for OcrPort {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("OcrPort")
    }
}

// SPEC 13's `assets` (D117): `None` (the default: nothing runs, every output is what it was without the feature), `Images` (a
// figure or seal that is one embedded image gets it), `Figures` (also the others, cropped or rendered: P8.5, through the asset unit,
// D351).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Assets {
    None,
    Images,
    Figures,
}

impl Assets {
    // The level kin's `emon.asset` reads: 0 none, 1 images, 2 figures.
    pub(crate) fn level(&self) -> i32 {
        match self {
            Assets::None => 0,
            Assets::Images => 1,
            Assets::Figures => 2,
        }
    }

    fn from_level(level: i32) -> Assets {
        match level {
            1 => Assets::Images,
            2 => Assets::Figures,
            _ => Assets::None,
        }
    }
}

// SPEC 13's `furniture` (D017, D153): `Policy` drops what the output already says and keeps the rest as asides, `Keep` leaves
// every running header, footer and page number in the flow, `Drop` takes them all out (the ledger says what).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Furniture {
    Policy,
    Keep,
    Drop,
}

impl Furniture {
    // The `mode` of `kin/furniture.kin`'s `fu-find`.
    pub(crate) fn mode(&self) -> i32 {
        match self {
            Furniture::Policy => 0,
            Furniture::Keep => 1,
            Furniture::Drop => 2,
        }
    }

    fn from_mode(mode: i32) -> Furniture {
        match mode {
            1 => Furniture::Keep,
            2 => Furniture::Drop,
            _ => Furniture::Policy,
        }
    }
}

// What a conversion is asked to do. `Default` is SPEC 13's defaults.
#[derive(Debug, Clone)]
pub struct Options {
    // A BCP 47 hint, written to the Model's `settings.locale`; none by default. It decides nothing yet.
    pub locale: Option<String>,
    pub ocr: OcrPolicy,
    pub assets: Assets,
    // The settings that go with `assets` (SPEC 10.11), written to the Model's header when it is on: the render-crop resolution
    // (150, 36..600), pixels per asset (2048 squared), encoded bytes per asset (4 MiB) and per document (32 MiB), and whether a
    // handwritten signature's or initials' pixels are kept (off: they are a forgery kit).
    pub asset_dpi: i32,
    pub asset_pixels: i32,
    pub asset_bytes: i32,
    pub assets_bytes: i32,
    pub asset_signatures: bool,
    pub furniture: Furniture,
    // Keep the text layer's evidence dump (`Model::evidence`): a test and debugging aid.
    pub keep_evidence: bool,
    // SPEC 13's `ocr-scope` (P7.S, D200): under `ocr: always` a born-digital page is read whole with its text (`Full`, the default) or as the render
    // without its text plus witness lines of its text layer's fonts (`Witness`, a fraction of the OCR time). Written to the Model's `settings` when not `Full`.
    pub ocr_scope: OcrScope,
    // A HIDDEN TEST SETTING (P7.4): leave every pixel unread by the page units, as the Engine was before the ink layers and the targeted passes (a measurement's
    // baseline; an embedder never needs it). The output of a run with no Renderer and no OCR engine is the same either way.
    pub no_retarget: bool,
}

// SPEC 13's defaults are kin's (`emon.config`'s `cf-defaults`): the default Options are the parse of the empty text.
impl Default for Options {
    fn default() -> Options {
        options_of(&parse_cfg("").expect("the empty options text parses"))
    }
}

// SPEC 13's `ocr-scope` (D200): what `ocr: always` reads of a born-digital page. `Full` (the default) the whole render with its text; `Witness` the render
// without its text and a few witness lines of every font (a font whose witnesses disagree with its text escalates the page to `Full`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OcrScope {
    Full,
    Witness,
}

impl OcrScope {
    // The `scope` of kin's `rp-plan`: 0 full, 1 witness.
    pub(crate) fn code(&self) -> i32 {
        match self {
            OcrScope::Full => 0,
            OcrScope::Witness => 1,
        }
    }

    fn from_code(code: i32) -> OcrScope {
        if code == 1 {
            OcrScope::Witness
        } else {
            OcrScope::Full
        }
    }
}

// The ports and bounds an Engine is made with. Limits are SPEC 7.4's `port::limit_of` kinds
// (0 input-bytes, 3 objects, 8 pages, ...): an entry here beats the environment's `EMON_LIMIT_*`.
#[derive(Debug, Clone)]
pub struct Config {
    pub limits: Vec<(i32, i32)>,
    // Chunk size of the source cache (SPEC 13.1: 64 KiB by default).
    pub chunk_size: usize,
    // Make the cache behave like an ASYNC source (a browser Blob): a read of a chunk that is not resident
    // misses, the unit stops with `need-bytes`, and the loop fetches and re-runs it. The output is
    // identical; the rounds and bytes fetched are in `RunStats`. Real Rust sources are sync.
    pub simulate_async: bool,
    pub max_rounds: i32,
    pub fetch_rounds: i32,
    // The `Ocr` port, if the embedder has one (D211). None: no live OCR; a replay needs none.
    pub ocr: Option<OcrPort>,
    // The `Renderer` port (D007, D127): what fulfils `render` and `crop` requests when the Engine converts
    // or records. `None` (the default): none is configured, and a request for one is answered `unsupported` (D199;
    // an Engine with no Renderer is a gate with no engine). A replay never calls it (D008). `pdfium::Pdfium` is the default engine (`--cfg pdfium`).
    pub renderer: Option<Arc<dyn Renderer>>,
    // The JPX and JBIG2 decoders of the `decode-img` request (P6.1a, D117, D336): what an embedder brings for the two PDF image
    // codecs no pure library here reads (`pdfcodecs::Codecs`, OpenJPEG and jbig2dec, under `--cfg codecs`). `None` (the default):
    // those two are answered `unsupported` and the image is `noasset: "codec"`. DCT and CCITT are the host's own. A replay never
    // calls it (D008).
    pub pdf_codecs: Option<Arc<dyn PdfCodecs>>,
}

impl Default for Config {
    fn default() -> Config {
        config_of(&parse_cfg("").expect("the empty options text parses"))
    }
}

// ------------------------------------------------------------------------------------------------ options from text (P10.10)
//
// ONE `key=value` GRAMMAR, PARSED BY KIN (`emon.config`) INTO A `Cfg`: the browser binding, the drivers, and later the C binding and the pod say
// what a conversion is asked to do in the same text. `Options` and `Config` stay the idiomatic typed structs; these functions are the thin wrapper
// that reads one back out of the `Cfg` kin filled. A key's name, a word's spelling, a default and a range are not known here.

// What a text asks for: the Options and the Config (limits, chunk size, the loop's bounds), and the browser binding's own switches.
#[derive(Debug, Clone)]
pub struct Parsed {
    pub options: Options,
    pub config: Config,
    // `record=1`: keep a transcript of the conversion
    pub record: bool,
    // `js-ocr=1`: the host owes the `ocr` requests
    pub js_ocr: bool,
    // `producer.NAME=VALUE`, in order: what the transcript says produced it
    pub producer: Vec<(String, String)>,
}

fn cells_of_str(s: &str) -> Cells {
    let mut c = Cells::new();
    for ch in s.chars() {
        cells_push(&mut c, ch as i32);
    }
    c
}

fn string_of_cells(c: &Cells, from: i32, n: i32) -> String {
    (from..from + n).map(|i| char::from_u32(cells_at(c, i) as u32).unwrap_or('\u{FFFD}')).collect()
}

fn parse_cfg(text: &str) -> Result<Cfg, String> {
    let mut t = cells_of_str(text);
    let mut c = Cfg::new();
    if crate::emon::config::cf_parse(&mut t, &mut c) != 0 {
        return Err(c.msg);
    }
    Ok(c)
}

fn options_of(c: &Cfg) -> Options {
    Options {
        locale: if c.haveloc == 1 { Some(string_of_cells(&c.lcl, 0, cells_len(&c.lcl))) } else { None },
        ocr: OcrPolicy::from_code(c.ocr),
        assets: Assets::from_level(c.level),
        asset_dpi: c.dpi,
        asset_pixels: c.pixels,
        asset_bytes: c.abyte,
        assets_bytes: c.tbyte,
        asset_signatures: c.sigs != 0,
        furniture: Furniture::from_mode(c.furn),
        keep_evidence: c.keep != 0,
        ocr_scope: OcrScope::from_code(c.scope),
        no_retarget: false,
    }
}

fn config_of(c: &Cfg) -> Config {
    let limits = (0..cells_len(&c.lims) / 2).map(|i| (cells_at(&c.lims, 2 * i), cells_at(&c.lims, 2 * i + 1))).collect();
    Config { limits, chunk_size: c.chunk as usize, simulate_async: false, max_rounds: c.maxr, fetch_rounds: c.fetchr, ocr: None, renderer: None, pdf_codecs: None }
}

// `key=value` lines, parsed by kin: the settings of SPEC 13 by their names, `limit.NAME=N` for the limits, the loop's bounds, and the ports the
// host has. An Err is the sentence a driver prints after `usage`.
pub fn parse_options(text: &str) -> Result<Parsed, String> {
    let c = parse_cfg(text)?;
    let mut producer = Vec::new();
    let mut at = 0;
    while at < cells_len(&c.prod) {
        let (kl, vl) = (cells_at(&c.prod, at), cells_at(&c.prod, at + 1));
        producer.push((string_of_cells(&c.prod, at + 2, kl), string_of_cells(&c.prod, at + 2 + kl, vl)));
        at += 2 + kl + vl;
    }
    Ok(Parsed { options: options_of(&c), config: config_of(&c), record: c.rec != 0, js_ocr: c.jsocr != 0, producer })
}

// Whether `name` (a driver's `--name=`) is a key of the grammar, which kin owns.
pub fn is_option_key(name: &str) -> bool {
    let mut t = cells_of_str(name);
    let n = cells_len(&t);
    crate::emon::config::cf_is_key(&mut t, 0, n) == 1
}

// JSON options (SPEC 4): the indented form or the compact one.
#[derive(Debug, Clone, Copy, Default)]
pub struct JsonOptions {
    pub pretty: bool,
}

// HTML options (SPEC 5.2, D133). `Default` is clean HTML: no ids, no geometry, milestones on, furniture
// templates kept, the ledger off. The option bits are `kin/html.kin`'s (`ho-*`).
#[derive(Debug, Clone)]
pub struct HtmlOptions {
    pub ids: bool,
    pub geometry: bool,
    // `data-emon-conf` on nodes whose confidence (0..=1000) is under this.
    pub confidence_below: Option<u32>,
    pub ledger: bool,
    pub pretty: bool,
    pub furniture_drop: bool,
    pub milestones: bool,
    pub initials_plain: bool,
    pub align_style: bool,
    // What the HTML does with the Model's assets (SPEC 10.11): `Inline` (the default) writes `data:` URIs, `External` writes
    // `asset_prefix` + the file name (the caller writes `Model::asset_files()` beside it), `Omit` writes the placeholders as if
    // no asset existed. A Model without assets gives the same HTML under all three.
    pub assets: HtmlAssets,
    pub asset_prefix: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HtmlAssets {
    Inline,
    External,
    Omit,
}

impl Default for HtmlOptions {
    fn default() -> HtmlOptions {
        HtmlOptions { ids: false, geometry: false, confidence_below: None, ledger: false, pretty: false, furniture_drop: false, milestones: true, initials_plain: false, align_style: false, assets: HtmlAssets::Inline, asset_prefix: String::new() }
    }
}

impl HtmlOptions {
    fn bits(&self) -> (i32, i32) {
        let mut o = 0;
        if self.ids { o |= ho_ids(); }
        if self.geometry { o |= ho_geometry(); }
        if self.confidence_below.is_some() { o |= ho_confidence(); }
        if self.ledger { o |= ho_ledger(); }
        if self.pretty { o |= ho_pretty(); }
        if self.furniture_drop { o |= ho_furniture_drop(); }
        if !self.milestones { o |= ho_no_milestones(); }
        if self.initials_plain { o |= ho_initials_plain(); }
        if self.align_style { o |= ho_align_style(); }
        match self.assets {
            HtmlAssets::Inline => {}
            HtmlAssets::External => o |= ho_assets_external(),
            HtmlAssets::Omit => o |= ho_assets_omit(),
        }
        (o, self.confidence_below.unwrap_or(0).min(1000) as i32)
    }
}

// An output format, for callers (and the driver) that choose one by value.
#[derive(Debug, Clone)]
pub enum Output {
    Json(JsonOptions),
    Html(HtmlOptions),
}

// ---------------------------------------------------------- the source cache
//
// A `SrcBacking` over a `ByteSource`: aligned chunks (SPEC 13.1), resident or not. SYNC: a read of a
// chunk that is not resident fills it inline and answers. SIMULATED ASYNC: it records the chunk as
// pending and misses; `fetch` (what the loop's `bytes` request does) makes the pending chunks resident
// with a read-ahead that doubles per round, coalescing a run of chunks into one read of the source.
// A read that fails, or returns fewer bytes than the length said, is remembered as `source-changed`.

struct CacheState {
    chunks: Vec<Option<Box<[u8]>>>,
    // 1 for each resident chunk, for kin's planner of the fetches (`fp-plan`)
    res: Cells,
    pending: BTreeSet<usize>,
    wanted: BTreeSet<usize>,
    failure: Option<String>,
    short: bool,
    bytes: u64,
    reads: u32,
    hints: u32,
}

pub struct ChunkCache {
    src: Arc<dyn ByteSource>,
    len: usize,
    chunk: usize,
    deferred: bool,
    st: Mutex<CacheState>,
}

impl ChunkCache {
    fn new(src: Arc<dyn ByteSource>, len: usize, chunk: usize, deferred: bool) -> ChunkCache {
        let n = (len + chunk - 1) / chunk;
        ChunkCache {
            src,
            len,
            chunk,
            deferred,
            st: Mutex::new(CacheState { chunks: vec![None; n], res: zeros(n), pending: BTreeSet::new(), wanted: BTreeSet::new(), failure: None, short: false, bytes: 0, reads: 0, hints: 0 }),
        }
    }

    fn nchunks(&self) -> usize {
        (self.len + self.chunk - 1) / self.chunk
    }

    // Read chunks first..=last from the source into the cache, in one read of the source.
    fn load(&self, st: &mut CacheState, first: usize, last: usize) -> bool {
        let lo = first * self.chunk;
        let hi = std::cmp::min((last + 1) * self.chunk, self.len);
        let mut buf = vec![0u8; hi - lo];
        let mut got = 0usize;
        while got < buf.len() {
            match self.src.read_at((lo + got) as u64, &mut buf[got..]) {
                Ok(0) => {
                    st.short = true;
                    st.failure = Some(format!("the source gave {} bytes at offset {} and its length said {}", got, lo, buf.len()));
                    return false;
                }
                Ok(n) => got += n,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Err(e) => {
                    st.failure = Some(e.to_string());
                    return false;
                }
            }
        }
        st.reads += 1;
        st.bytes += buf.len() as u64;
        for c in first..=last {
            let a = c * self.chunk - lo;
            let b = std::cmp::min(a + self.chunk, buf.len());
            st.chunks[c] = Some(buf[a..b].to_vec().into_boxed_slice());
            cells_set(&mut st.res, c as i32, 1);
        }
        true
    }

    // True when chunk c is resident now (a sync cache fills it here; an async one records the miss).
    fn touch(&self, st: &mut CacheState, c: usize) -> bool {
        if st.chunks[c].is_some() {
            return true;
        }
        if st.failure.is_some() {
            return false;
        }
        if self.deferred {
            st.pending.insert(c);
            return false;
        }
        self.load(st, c, c)
    }

    // The ranges to fetch for fetch round `round`: every pending (or hinted) chunk and 2^round - 1 chunks
    // each side of it, the resident ones left out, a run of chunks one range. THE PLAN IS KIN'S (`fp-plan`): the host says what
    // was missed and what is resident, and takes the ranges.
    pub fn plan_fetch(&self, input: i32, round: i32) -> Vec<Range> {
        let mut st = self.st.lock().unwrap();
        let mut pend = Cells::new();
        for &c in st.pending.iter() {
            cells_push(&mut pend, c as i32);
        }
        let mut hint = Cells::new();
        for &c in st.wanted.iter() {
            cells_push(&mut hint, c as i32);
        }
        st.pending.clear();
        st.wanted.clear();
        let mut out = Cells::new();
        crate::emon::planloop::fp_plan(&mut pend, &mut hint, &mut st.res, self.chunk as i32, self.len as i32, input, round, &mut out);
        (0..cells_len(&out) / 3).map(|i| Range { input: cells_at(&out, 3 * i), offset: cells_at(&out, 3 * i + 1), length: cells_at(&out, 3 * i + 2) }).collect()
    }

    // Make [offset, offset + length) resident: what a `bytes` request does. Err is the source's failure.
    pub fn fetch(&self, offset: i32, length: i32) -> Result<(), ()> {
        let mut st = self.st.lock().unwrap();
        if length <= 0 {
            return Ok(());
        }
        let first = offset as usize / self.chunk;
        let last = (offset as usize + length as usize - 1) / self.chunk;
        // runs of chunks that are not resident, each one read of the source
        let mut c = first;
        while c <= last {
            if st.chunks[c].is_some() {
                c += 1;
                continue;
            }
            let mut e = c;
            while e < last && st.chunks[e + 1].is_none() {
                e += 1;
            }
            if !self.load(&mut st, c, e) {
                return Err(());
            }
            c = e + 1;
        }
        Ok(())
    }

    pub fn failure(&self) -> Option<(bool, String)> {
        let st = self.st.lock().unwrap();
        st.failure.clone().map(|m| (st.short, m))
    }

    pub fn bytes_read(&self) -> u64 {
        self.st.lock().unwrap().bytes
    }
    pub fn reads(&self) -> u32 {
        self.st.lock().unwrap().reads
    }
    pub fn hints(&self) -> u32 {
        self.st.lock().unwrap().hints
    }

    // The input's length in bytes.
    pub fn length(&self) -> usize {
        self.len
    }

    // A PUSHED cache (P9.3, the browser): one nothing can read FROM. An embedder whose reads are promises cannot give the
    // engine a `read_at` that blocks, so the engine only ever misses (`deferred`), says what (`plan_fetch`, `missing`) and the
    // embedder pushes the bytes in (`provide`) before it runs the unit again: the host loop of D058, owned from outside.
    pub fn pushed(len: usize, chunk: usize) -> Arc<ChunkCache> {
        struct Nothing(u64);
        impl ByteSource for Nothing {
            fn len(&self) -> u64 {
                self.0
            }
            fn read_at(&self, _off: u64, _buf: &mut [u8]) -> std::io::Result<usize> {
                Err(std::io::Error::new(std::io::ErrorKind::WouldBlock, "a pushed source is read only where it was provided"))
            }
        }
        Arc::new(ChunkCache::new(Arc::new(Nothing(len as u64)), len, chunk.max(1), true))
    }

    // Make resident the chunks that `data` (the bytes at `offset`) covers whole. A range that starts or ends inside a chunk
    // leaves that chunk to a later read: the ranges `plan_fetch` and `missing` give are chunk-aligned (the last ends at the
    // length).
    pub fn provide(&self, offset: usize, data: &[u8]) {
        let mut st = self.st.lock().unwrap();
        if self.len == 0 || offset >= self.len {
            return;
        }
        st.reads += 1;
        st.bytes += data.len() as u64;
        let end = std::cmp::min(offset + data.len(), self.len);
        let first = (offset + self.chunk - 1) / self.chunk;
        for c in first..self.nchunks() {
            let lo = c * self.chunk;
            let hi = std::cmp::min(lo + self.chunk, self.len);
            if hi > end {
                break;
            }
            if st.chunks[c].is_none() {
                st.chunks[c] = Some(data[lo - offset..hi - offset].to_vec().into_boxed_slice());
                cells_set(&mut st.res, c as i32, 1);
            }
        }
    }

    // The runs of chunks that are not resident among those [offset, offset + length) touches, as ranges (the browser's
    // `decode` and `decode-img` requests name a span of the input the host must have before it can answer).
    pub fn missing(&self, input: i32, offset: u64, length: u64) -> Vec<Range> {
        let st = self.st.lock().unwrap();
        if length == 0 || offset >= self.len as u64 {
            return Vec::new();
        }
        let first = offset as usize / self.chunk;
        let last = (std::cmp::min(offset + length, self.len as u64) as usize - 1) / self.chunk;
        let mut out: Vec<Range> = Vec::new();
        let mut c = first;
        while c <= last {
            if st.chunks[c].is_some() {
                c += 1;
                continue;
            }
            let mut e = c;
            while e < last && st.chunks[e + 1].is_none() {
                e += 1;
            }
            let off = c * self.chunk;
            let end = std::cmp::min((e + 1) * self.chunk, self.len);
            out.push(Range { input, offset: off as i32, length: (end - off) as i32 });
            c = e + 1;
        }
        out
    }

    // SPEC 13.1: intake round 0 reads the first and the last chunk (the header, `startxref`, the trailer).
    pub fn prefetch_ranges(&self, input: i32) -> Vec<Range> {
        if self.len == 0 {
            return Vec::new();
        }
        let n = self.nchunks();
        let mut out = self.missing(input, 0, std::cmp::min(self.chunk, self.len) as u64);
        out.extend(self.missing(input, ((n - 1) * self.chunk) as u64, (self.len - (n - 1) * self.chunk) as u64));
        out.dedup();
        out
    }

    // The embedder's read failed (`short`: it gave fewer bytes than its length said, the `source-changed` of D058): the run ends
    // with that error, as it does when a native source fails.
    pub fn fail(&self, short: bool, message: &str) {
        let mut st = self.st.lock().unwrap();
        st.short = short;
        st.failure = Some(message.to_string());
    }
}

// What is resident, read in place: the decode requests' codecs read the input through this (a pushed cache), never through a
// source that could miss. A read that starts at a chunk that is not resident is `WouldBlock`.
impl ByteSource for ChunkCache {
    fn len(&self) -> u64 {
        self.len as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        if off >= self.len as u64 || buf.is_empty() {
            return Ok(0);
        }
        let st = self.st.lock().unwrap();
        let mut got = 0usize;
        let mut at = off as usize;
        while got < buf.len() && at < self.len {
            let c = at / self.chunk;
            match &st.chunks[c] {
                Some(ch) => {
                    let o = at - c * self.chunk;
                    let n = std::cmp::min(buf.len() - got, ch.len() - o);
                    buf[got..got + n].copy_from_slice(&ch[o..o + n]);
                    got += n;
                    at += n;
                }
                None => break,
            }
        }
        if got == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::WouldBlock, "that part of the source is not resident"));
        }
        Ok(got)
    }
}

impl SrcBacking for ChunkCache {
    fn len(&self) -> i32 {
        self.len as i32
    }
    fn at(&self, i: i32) -> i32 {
        let c = i as usize / self.chunk;
        let mut st = self.st.lock().unwrap();
        if !self.touch(&mut st, c) {
            return -1;
        }
        st.chunks[c].as_ref().unwrap()[i as usize - c * self.chunk] as i32
    }
    fn span(&self, s: i32, buf: &mut [u8]) -> bool {
        if buf.is_empty() {
            return true;
        }
        let mut st = self.st.lock().unwrap();
        let s = s as usize;
        let first = s / self.chunk;
        let last = (s + buf.len() - 1) / self.chunk;
        let mut ok = true;
        for c in first..=last {
            if self.touch(&mut st, c) {
                let ch = st.chunks[c].as_ref().unwrap();
                let lo = std::cmp::max(s, c * self.chunk);
                let hi = std::cmp::min(s + buf.len(), c * self.chunk + ch.len());
                buf[lo - s..hi - s].copy_from_slice(&ch[lo - c * self.chunk..hi - c * self.chunk]);
            } else {
                ok = false;
            }
        }
        ok
    }
    fn hint(&self, s: i32, e: i32) {
        let mut st = self.st.lock().unwrap();
        st.hints += 1;
        if self.deferred && e > s && self.len > 0 {
            let first = std::cmp::max(s, 0) as usize / self.chunk;
            let last = std::cmp::min((e - 1) as usize, self.len - 1) / self.chunk;
            for c in first..=last {
                if st.chunks[c].is_none() {
                    st.wanted.insert(c);
                }
            }
        }
    }
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

// An in-memory source is read in place: no cache, no copy, no miss.
struct SliceBacking(Arc<dyn ByteSource>);

impl SrcBacking for SliceBacking {
    fn len(&self) -> i32 {
        self.0.len() as i32
    }
    fn at(&self, i: i32) -> i32 {
        self.0.as_slice().unwrap()[i as usize] as i32
    }
    fn span(&self, s: i32, buf: &mut [u8]) -> bool {
        let s = s as usize;
        buf.copy_from_slice(&self.0.as_slice().unwrap()[s..s + buf.len()]);
        true
    }
    fn hint(&self, _s: i32, _e: i32) {}
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

struct CacheMisses {
    cache: Arc<ChunkCache>,
    input: i32,
}

impl Misses for CacheMisses {
    fn take(&mut self, round: i32) -> Vec<Range> {
        self.cache.plan_fetch(self.input, round)
    }
}

// ------------------------------------------------------------ raster intake
//
// An input that kin's `rs-sniff` says is a PNG, a JPEG or a TIFF (SPEC 2.0, D187) is not a PDF: its unit is
// `emon.rasterin`'s `rs-unit`, which asks the host's `ImageDecode` port (`imgdec.rs`) for each frame through
// the plan/fulfil loop and builds one `image` page per frame. THIS IS SEPARATE FROM `attempt()`, which is the
// PDF unit and is not called for an image. OCR is not wired (P6.5), so an image page has no text.

// An `imgdec::Source` over a span of an input's `ByteSource`: what the decode request names (input, offset,
// length), read in place; nothing is copied until the codec asks for a range.
struct Span {
    src: Arc<dyn ByteSource>,
    off: u64,
    len: u64,
}

impl imgdec::Source for Span {
    fn size(&self) -> u64 {
        self.len
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        if off >= self.len {
            return Ok(0);
        }
        let n = std::cmp::min(buf.len() as u64, self.len - off) as usize;
        self.src.read_at(self.off + off, &mut buf[..n])
    }
}

// OCR wall time, in microseconds, of the FIRST pass of a page (`pi-ocr`, page-local id 16) and of the TARGETED passes (P7.4: every other `ocr`
// request), summed over a process's requests: what `--stats` of the driver prints and `bb fusion-bench` reads. Counters, not a result: the Engine's
// output never depends on them.
pub static OCR_US: [AtomicU64; 2] = [AtomicU64::new(0), AtomicU64::new(0)];

// The LIVE ports' fulfiller (SPEC 7.2): `ImageDecode` (D187) answers `decode` requests for this input from the host's
// codecs; `Ocr` (D211) answers `ocr` requests from the embedder's engine, or `unsupported` when it has none. No
// Renderer is configured (P6.2). `keep` holds the pixels in a decode answer (a recording wants them, as canonical
// PNG, and so does a live OCR engine; a plain conversion needs only the numbers); `recording` also keeps the OCR
// engine's thresholded image, which only a transcript wants (D211).
pub fn port_fulfiller(source: Arc<dyn ByteSource>, idx: usize, keep: bool, recording: bool, ocr: Option<OcrPort>, codecs: Option<Arc<dyn PdfCodecs>>) -> impl FnMut(&Request, &plan::Bitmaps) -> Result<Answer, PlanError> {
    use crate::emon::plan as kp;
    move |req: &Request, bm: &plan::Bitmaps| -> Result<Answer, PlanError> {
        // WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); this host has no renderer
        // (D199: the Engine's own `Ports` sends a render to one when it has one)
        match plan::route(req.kind(), ocr.is_some(), false) {
            plan::Route::DecodeImg => return decode_img(req, &source, idx, codecs.as_deref()),
            plan::Route::Unsupported => return Ok(plan::unsupported_answer(req)),
            plan::Route::Ocr => {
                return match &ocr {
                    // an engine that panics on a page is a failed OCR of it, not a crashed process
                    Some(p) => match std::panic::catch_unwind(AssertUnwindSafe(|| {
                        let t0 = std::time::Instant::now();
                        let r = p.0.fulfil(req, bm, recording);
                        OCR_US[if req.id() % 256 == 16 { 0 } else { 1 }].fetch_add(t0.elapsed().as_micros() as u64, Ordering::Relaxed);
                        r
                    })) {
                        Ok(r) => r,
                        Err(_) => {
                            let mut c = Cells::new();
                            kp::an_fail(&mut c, plan::KIND_OCR, req.id(), plan::STATUS_FAILED);
                            Ok(Answer { rec: to_vec(&c), bitmap: None })
                        }
                    },
                    None => Ok(plan::unsupported_answer(req)),
                };
            }
            plan::Route::Decode => {}
            plan::Route::Render | plan::Route::None => return Err(PlanError::Fulfil("no port is configured for this request kind".to_string())),
        }
        let (input, off, len, filter, frame) =
            (req.field(kp::dc_input()), req.field(kp::dc_offset()), req.field(kp::dc_length()), req.field(kp::dc_filter()), req.field(kp::dc_frame()));
        if input != idx as i32 || off < 0 || len < 0 || off as u64 + len as u64 > source.len() {
            return Err(PlanError::Fulfil("a decode request names bytes the input does not have".to_string()));
        }
        let mut c = Cells::new();
        let fail = |c: &mut Cells, status: i32| {
            crate::emon::plan::an_fail(c, plan::KIND_DECODE, req.id(), status);
            Ok(Answer { rec: to_vec(c), bitmap: None })
        };
        // a DCT stream is a JPEG file: the span is read in place (a scanned PDF page's image, D212); the other PDF
        // image filters (JPX, JBIG2, CCITT) are P6.1a's
        if filter != crate::emon::plan::flt_none() && filter != crate::emon::plan::flt_dct() {
            return fail(&mut c, crate::emon::plan::ps_unsupported());
        }
        let span = Span { src: source.clone(), off: off as u64, len: len as u64 };
        // a codec that panics on damage is a damaged image, not a crashed process
        let decoded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| imgdec::decode(&span, frame, port::limit_of(9) as u64)))
            .unwrap_or_else(|_| Err(imgdec::Fail::Failed("the decoder panicked".to_string())));
        match decoded {
            Ok(d) => {
                crate::emon::plan::an_decode(&mut c, req.id(), d.w, d.h, d.ch, d.dpi100, d.bilevel as i32, d.frames);
                let bitmap = match (&d.pixels, keep && (filter != crate::emon::plan::flt_dct() || ocr.is_some())) {
                    (Some(px), true) => Some(Arc::new(port::Bitmap::from_bytes(d.w, d.h, d.ch, px))),
                    _ => None,
                };
                Ok(Answer { rec: to_vec(&c), bitmap })
            }
            Err(imgdec::Fail::Failed(_)) => fail(&mut c, plan::STATUS_FAILED),
            Err(imgdec::Fail::Unsupported(_)) => fail(&mut c, crate::emon::plan::ps_unsupported()),
            Err(imgdec::Fail::Limit(n)) => Ok(Answer { rec: vec![5, plan::KIND_DECODE, req.id(), crate::emon::plan::ps_limit(), std::cmp::min(n, i32::MAX as u64) as i32], bitmap: None }),
        }
    }
}

// A span of the input, whole or an error (the `decode-img` request names bytes of the input by offset and length).
fn read_span(source: &Arc<dyn ByteSource>, off: i64, len: i64) -> Result<Vec<u8>, PlanError> {
    if off < 0 || len < 0 || off as u64 + len as u64 > source.len() {
        return Err(PlanError::Fulfil("a decode-img request names bytes the input does not have".to_string()));
    }
    let mut v = vec![0u8; len as usize];
    let mut got = 0usize;
    while got < v.len() {
        let n = source.read_at(off as u64 + got as u64, &mut v[got..]).map_err(|e| PlanError::Fulfil(format!("a read of the source failed: {}", e)))?;
        if n == 0 {
            return Err(PlanError::Fulfil("the source is shorter than it said".to_string()));
        }
        got += n;
    }
    Ok(v)
}

// The bytes a `decode-img` request names (kin's `dq-where` / `dq-offset` / `dq-length`): a span of this input (where is its
// index), or of the unit's PAYLOAD (where -2); -1 names none (P6.1a, D291).
fn named_bytes(req: &Request, source: &Arc<dyn ByteSource>, idx: usize, at: (i32, i32, i32)) -> Result<Vec<u8>, PlanError> {
    let (wh, off, len) = (req.field(at.0), req.field(at.1), req.field(at.2));
    match wh {
        -1 => Ok(Vec::new()),
        -2 => {
            if off < 0 || len < 0 || off as u64 + len as u64 > req.payload.len() as u64 {
                return Err(PlanError::Fulfil("a decode-img request names payload bytes the unit did not make".to_string()));
            }
            Ok(req.payload[off as usize..(off + len) as usize].to_vec())
        }
        w if w == idx as i32 => read_span(source, off as i64, len as i64),
        _ => Err(PlanError::Fulfil("a decode-img request names an input this run does not have".to_string())),
    }
}

// The `decode-img` request (kind 6; P6.1a, D117, D291): one PDF image's codestream to its 8-bit pixels (`pdfimg`). The answer
// always carries the pixels (kin adopts them); a transcript stores them, never the codestream.
fn decode_img(req: &Request, source: &Arc<dyn ByteSource>, idx: usize, codecs: Option<&dyn PdfCodecs>) -> Result<Answer, PlanError> {
    use crate::emon::plan as kp;
    let f = |k: i32| req.field(k);
    let a = pdfimg::Args { filter: f(kp::dq_filter()), w: f(kp::dq_w()), h: f(kp::dq_h()), comps: f(kp::dq_comps()), bpc: f(kp::dq_bpc()), k: f(kp::dq_k()), columns: f(kp::dq_columns()), rows: f(kp::dq_rows()), flags: f(kp::dq_ccitt()) };
    let data = named_bytes(req, source, idx, (kp::dq_where(), kp::dq_offset(), kp::dq_length()))?;
    let globals = named_bytes(req, source, idx, (kp::dq_gwhere(), kp::dq_goffset(), kp::dq_glength()))?;
    let mut c = Cells::new();
    let fail = |c: &mut Cells, status: i32| {
        crate::emon::plan::an_fail(c, plan::KIND_DECODE_IMG, req.id(), status);
        Ok(Answer { rec: to_vec(c), bitmap: None })
    };
    // a codec that panics on damage is a damaged image, not a crashed process
    let decoded = std::panic::catch_unwind(AssertUnwindSafe(|| pdfimg::decode(&a, &data, &globals, codecs, port::limit_of(9) as u64)))
        .unwrap_or_else(|_| Err(pdfimg::CodecFail::Failed("the decoder panicked".to_string())));
    match decoded {
        Ok(p) => {
            crate::emon::plan::an_decode_img(&mut c, req.id(), p.w, p.h, p.ch, p.bilevel as i32);
            Ok(Answer { rec: to_vec(&c), bitmap: Some(Arc::new(port::Bitmap::from_bytes(p.w, p.h, p.ch, &p.px))) })
        }
        Err(pdfimg::CodecFail::Failed(_)) => fail(&mut c, plan::STATUS_FAILED),
        Err(pdfimg::CodecFail::Unsupported(_)) => fail(&mut c, crate::emon::plan::ps_unsupported()),
        Err(pdfimg::CodecFail::Limit(n)) => Ok(Answer { rec: vec![5, plan::KIND_DECODE_IMG, req.id(), crate::emon::plan::ps_limit(), std::cmp::min(n, i32::MAX as u64) as i32], bitmap: None }),
    }
}

// One attempt's end for a raster input: it missed, it asked for something, or it finished.
enum RStep {
    Miss,
    Requests,
    Done(Outcome),
    // the document pass is done and the page units are next (P7.U)
    Pdf(Box<PdfDoc>),
    Raster(Box<RasterDoc>),
}

// The locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none.
fn locale_cells(opts: &Options) -> Cells {
    let mut c = Cells::new();
    if let Some(l) = &opts.locale {
        for ch in l.chars() {
            cells_push(&mut c, ch as i32);
        }
    }
    c
}

// ONE ATTEMPT OF THE RASTER UNIT, from scratch on fresh arenas, as `attempt` is for a PDF. An image's pages are made
// by kin's `rs_unit` (decode, then OCR: P6.5), and its OCR lines are the text layer's lines to everything after (D213):
// the stages below are `attempt`'s, in its order, with the glyph-fed ones given empty arenas.
// What the raster document pass leaves for its page units and the document unit (P7.U).
struct RasterDoc {
    m: Arenas,
    ev: EvArenas,
    doc: Doc,
    info: Intake,
}

fn attempt_raster(view: &Src, answers: &mut Cells, rq: &mut Cells, idx: usize, fmt: i32, opts: &Options, intake_only: bool, len: u64) -> RStep {
    let mut m = Arenas::new();
    model_init(&mut m.nodes, &mut m.attrs, &mut m.regions, &mut m.text, &mut m.pages, &mut m.ledger, &mut m.layout);
    let mut mask = Cells::new();
    let status = rs_doc(view, idx as i32, fmt, answers, rq, &mut m.nodes, &mut m.attrs, &mut m.text, &mut m.pages, &mut mask);
    if view.missed() {
        return RStep::Miss;
    }
    match status {
        0 => {}
        1 => return RStep::Requests,
        s if s == crate::emon::planloop::ds_undecodable() || s == crate::emon::planloop::ds_unsupported_image() => return RStep::Done(Outcome::Failed(damaged(idx, s))),
        s => return RStep::Done(Outcome::Failed(Error::Internal(format!("the raster unit answered status {}", s)))),
    }
    let format = if fmt == rs_png() {
        "png"
    } else if fmt == rs_jpeg() {
        "jpeg"
    } else {
        "tiff"
    };
    let info = Intake { format, version: (0, 0), pages: page_count(&mut m.pages).max(0) as usize, encrypted: false, bytes: len };
    if intake_only {
        return RStep::Done(Outcome::Intake(info));
    }
    let mut doc = Doc::new();
    doc.mask = mask;
    doc.loc = locale_cells(opts);
    RStep::Raster(Box::new(RasterDoc { m, ev: EvArenas::new(), doc, info }))
}

// One frame's page unit (P7.U): its own requests and answers; it writes nothing until its answers are in.
fn unit_raster(d: &mut RasterDoc, f: usize, idx: usize, len: u64, opts: &Options, answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet) -> i32 {
    let mut scr = Cells::new();
    rs_page(
        f as i32, idx as i32, len as i32, opts.ocr.code(), answers, rq, &mut d.doc.loc, bms, &mut d.doc.mask,
        &mut d.ev.epages, &mut d.ev.views, &mut d.doc.et, &mut d.ev.feats, &mut d.ev.images, &mut d.ev.oruns, &mut d.ev.oblocks, &mut d.ev.olines, &mut d.ev.owords, &mut d.ev.osyms, &mut d.ev.oalts,
        &mut d.doc.wd, &mut d.doc.ln, &mut scr,
    )
}

// The raster document unit: stage 3 over the frames' OCR lines, in kin (`emon.engine`).
fn finish_raster(d: RasterDoc, opts: &Options) -> Outcome {
    let RasterDoc { mut m, mut ev, mut doc, info } = d;
    let rfrom = match info.format {
        "png" => afm_png(),
        "jpeg" => afm_jpeg(),
        _ => afm_tiff(),
    };
    let mut s = Scratch::new();
    let r = en_finish_raster(&mut cfg_of(opts), &mut doc, &mut ev, &mut m, &mut s);
    finished(r, doc, m, info, opts, rfrom)
}

// What kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3 `model-check`, 4 `model-check-assets`).
fn finished(r: i32, mut doc: Doc, m: Arenas, info: Intake, opts: &Options, rfrom: i32) -> Outcome {
    match r {
        0 | 1 => {
            let evidence = if opts.keep_evidence { Some(std::mem::take(&mut doc.evidence)) } else { None };
            // `figures` leaves the asset stage to the asset unit (D351): what it needs
            let pending = if r == 1 { Some(PendingAssets { cand: std::mem::take(&mut doc.cand), cbytes: std::mem::take(&mut doc.cbytes), rfrom }) } else { None };
            Outcome::Built(Built { arenas: m, evidence, intake: info, pending })
        }
        2 => Outcome::Failed(Error::Internal(format!("pl-check {}", doc.fcode))),
        3 => Outcome::Failed(Error::Internal(format!("model-check {} at node {}", doc.fcode, doc.fnode))),
        _ => Outcome::Failed(Error::Internal(format!("model-check-assets {} at {}", doc.fcode, doc.fnode))),
    }
}

// The Options the document unit reads (`emon.enctx`'s `Cfg`).
fn cfg_of(opts: &Options) -> Cfg {
    let mut c = Cfg::new();
    c.ocr = opts.ocr.code();
    c.scope = opts.ocr_scope.code();
    c.furn = opts.furniture.mode();
    c.level = opts.assets.level();
    c.dpi = opts.asset_dpi;
    c.pixels = opts.asset_pixels;
    c.abyte = opts.asset_bytes;
    c.tbyte = opts.assets_bytes;
    c.sigs = if opts.asset_signatures { 1 } else { 0 };
    c.keep = opts.keep_evidence as i32;
    if opts.locale.is_some() {
        c.haveloc = 1;
        c.lcl = locale_cells(opts);
    }
    c
}

// The checks of a finished Model: `model-check`, and `model-check-assets` when `assets` is on (kin's `en-seal`).
fn seal_arenas(m: &mut Arenas, opts: &Options) -> Option<Error> {
    let (mut chk, mut out) = (Cells::new(), Cells::new());
    match en_seal(m, &mut chk, &mut out, opts.assets.level()) {
        0 => None,
        1 => Some(Error::Internal(format!("model-check {} at node {}", cells_at(&out, 0), cells_at(&out, 1)))),
        _ => Some(Error::Internal(format!("model-check-assets {} at {}", cells_at(&out, 0), cells_at(&out, 1)))),
    }
}

// The unit of one input: a PNG, JPEG or TIFF (by kin's sniff) is read by the raster unit, anything else by the
// PDF one (`attempt`, which refuses what is not a PDF by name).
fn dispatch(view: &Src, pw: &Src, answers: &mut Cells, rq: &mut Cells, idx: usize, opts: &Options, intake_only: bool, len: u64) -> RStep {
    let fmt = rs_sniff(view);
    if view.missed() {
        return RStep::Miss;
    }
    if fmt != rs_none() {
        return attempt_raster(view, answers, rq, idx, fmt, opts, intake_only, len);
    }
    match attempt(view, pw, idx, opts, intake_only, len) {
        Step::Miss => RStep::Miss,
        Step::Requests => RStep::Requests,
        Step::Done(o) => RStep::Done(o),
        Step::Pdf(d) => RStep::Pdf(d),
    }
}

// ------------------------------------------------------------------- stats

// What the loop did, for the driver and the async gate. Not part of the Model (the Model is the same
// whichever way the bytes arrived).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RunStats {
    // the document pass's attempts, fetch rounds and bytes requests; `requests` counts every request of every unit
    pub attempts: i32,
    pub fetch_rounds: i32,
    pub bytes_requests: i32,
    pub requests: i32,
    pub source_reads: u32,
    pub bytes_fetched: u64,
    pub input_bytes: u64,
    // per page unit (P7.U): the rounds it took and the requests it made, in page order
    pub page_rounds: Vec<i32>,
    pub page_requests: Vec<i32>,
    // P7.S (D210, `bb fusion-bench`): wall time in microseconds. `fulfil_us` is what the host's ports took, summed over every request of the input, by request
    // kind (index: 1 render, 2 decode, 3 `ocr` of a page's first pass, 4 bytes, 5 crop, 6 decode-img; 7 the `ocr` requests of every later pass). `pass_us` is the
    // document pass (a loop of its own), `pages_us` every page unit with the ports' time in it, `finish_us` the document unit (stage 3), so the page units' kin
    // time is `pages_us` less the ports' time in them. Counters: the Model never depends on them.
    pub fulfil_us: [u64; 8],
    pub pass_us: u64,
    pub pages_us: u64,
    pub finish_us: u64,
}

// The LIVE port set: a request goes where kin says (`pl-route`, P10.8): a `render` or `crop` to the configured Renderer, everything
// else to the host's own fulfiller (`port_fulfiller`), which also answers `unsupported` where no port is configured (D199).
struct Ports {
    render: Option<RenderFulfiller>,
    local: Box<dyn Fulfiller>,
    ocr: bool,
    // true when the request in that place went to the renderer
    order: VecDeque<bool>,
}

impl Fulfiller for Ports {
    fn start(&mut self, req: &plan::Request, bitmaps: &plan::Bitmaps) -> Result<(), PlanError> {
        match self.render.as_mut() {
            Some(r) if plan::route(req.kind(), self.ocr, true) == plan::Route::Render => {
                r.start(req, bitmaps)?;
                self.order.push_back(true);
            }
            _ => {
                self.local.start(req, bitmaps)?;
                self.order.push_back(false);
            }
        }
        Ok(())
    }
    fn next(&mut self) -> Result<plan::Answer, PlanError> {
        match (self.order.pop_front(), self.render.as_mut()) {
            (Some(true), Some(r)) => r.next(),
            (Some(_), _) => self.local.next(),
            (None, _) => Err(PlanError::Fulfil("next without a started request".to_string())),
        }
    }
}

// The ports' fulfiller, timed by request kind (`RunStats::fulfil_us`).
struct TimedPorts<'a> {
    inner: &'a mut dyn Fulfiller,
    us: [u64; 8],
}

impl<'a> Fulfiller for TimedPorts<'a> {
    fn start(&mut self, req: &plan::Request, bitmaps: &plan::Bitmaps) -> Result<(), PlanError> {
        let t0 = std::time::Instant::now();
        let r = self.inner.start(req, bitmaps);
        let k = if req.kind() == plan::KIND_OCR && req.id() % 256 != 16 { 7 } else { (req.kind().max(0) as usize).min(6) };
        self.us[k] += t0.elapsed().as_micros() as u64;
        r
    }
    fn next(&mut self) -> Result<plan::Answer, PlanError> {
        self.inner.next()
    }
}

// ------------------------------------------------------------------- intake

// Stage 0, cheap: what the file says it is (SPEC 13). Opening it reads the header, the trailer and the
// cross reference and the page tree, no page.
#[derive(Debug, Clone, PartialEq)]
pub struct Intake {
    pub format: &'static str,
    pub version: (i32, i32),
    pub pages: usize,
    pub encrypted: bool,
    pub bytes: u64,
}

// ------------------------------------------------------------------- engine

pub struct Engine {
    cfg: Config,
}

// What the document pass leaves for the page units (P7.U).
enum DocKind {
    Pdf(Box<PdfDoc>),
    Raster(Box<RasterDoc>),
}

// The result of one input's unit, kept from the attempt that did not miss.
struct Built {
    arenas: Arenas,
    evidence: Option<String>,
    intake: Intake,
    // At `assets: figures` the document unit stops before the asset stage (D351): what the asset unit still needs. None otherwise.
    pending: Option<PendingAssets>,
}

// The asset unit's input at the level `figures` (SPEC 10.11, D351): the candidate rows and their bytes the document pass made, and
// `rfrom`, 0 for a PDF or the `afm` of a raster input's format.
#[derive(Clone)]
struct PendingAssets {
    cand: Cells,
    cbytes: Buf,
    rfrom: i32,
}

enum Outcome {
    // the unit has not finished (or stopped with a miss)
    None,
    Built(Built),
    Intake(Intake),
    Failed(Error),
}

// A document's status as the Engine names it (kin's `ds`): which of its errors it is, and for a damaged input its name.
fn damaged(input: usize, code: i32) -> Error {
    let mut status = String::new();
    crate::emon::planloop::ds_emit_name(&mut status, crate::emon::planloop::ds_known(code));
    Error::Damaged { input, status }
}

fn status_name(input: usize, status: i32) -> Error {
    match crate::emon::planloop::ds_class(status) {
        1 => Error::UnsupportedFormat { input },
        2 => Error::PasswordRequired { input },
        3 => Error::PasswordIncorrect { input },
        4 => Error::EncryptionUnsupported { input },
        _ => damaged(input, status),
    }
}

// A kin limit breach unwinds with a `port::LimitBreach` (kind, limit, actual), caught by its type; its name is the table's in kin (`emon.limits`). Any
// other panic is `internal`, with its message.
fn panic_error(p: Box<dyn std::any::Any + Send>) -> Error {
    if let Some(b) = p.downcast_ref::<port::LimitBreach>() {
        return Error::Limit { name: b.name(), limit: b.limit as i64, actual: b.actual as i64 };
    }
    Error::Internal(panic_message(p))
}

// The document unit under `catch_unwind`: what a panic in it becomes is what a panic in a page unit becomes (`panic_error`).
fn finish_guarded(f: impl FnOnce() -> Outcome) -> Outcome {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(o) => o,
        Err(p) => Outcome::Failed(panic_error(p)),
    }
}

fn panic_message(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else {
        "a panic".to_string()
    }
}

// How the loop runs: live (no transcript), recording one, or replaying one.
enum Mode<'a> {
    Live,
    Record(&'a mut Transcript),
    Replay(&'a Transcript),
}

// One attempt's end: it missed (discard it, fetch, re-run), asked for something (OCR of a scanned page: the loop
// fulfils it and re-runs the unit on the answers) or it finished.
enum Step {
    Miss,
    Requests,
    Done(Outcome),
    // the document pass is done; the page units are next (P7.U)
    Pdf(Box<PdfDoc>),
}

// A residency map of `n` chunks, none resident.
fn zeros(n: usize) -> Cells {
    let mut c = Cells::new();
    for _ in 0..n {
        cells_push(&mut c, 0);
    }
    c
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

fn text_of(cells: &mut Cells, start: i32, len: i32) -> String {
    let mut s = String::new();
    for i in start..start + len {
        s.push(char::from_u32(text_at(cells, i) as u32).unwrap_or('\u{FFFD}'));
    }
    s
}

// What the PDF document pass leaves for its page units and the document unit (P7.U, D195): the text layer's arenas, the
// catalogue's extras, the plan of what each page's unit will ask, and the evidence arenas the units fill.
struct PdfDoc {
    ev: EvArenas,
    doc: Doc,
    info: Intake,
}

// ONE ATTEMPT OF THE DOCUMENT PASS for one input: everything kin does that READS THE INPUT, from scratch, on a fresh view
// and fresh arenas: the text layer, the catalogue and the tagged tree, and the plan of the page units (which pages are
// scanned, where their image streams are). A miss at any checkpoint ends it at once (`Step::Miss`): what a missed attempt
// computed is never looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1). The page
// units (`unit_pdf`) and the document unit (`finish_pdf`) follow and never read the input.
fn attempt(view: &Src, pw: &Src, idx: usize, opts: &Options, intake_only: bool, len: u64) -> Step {
    let mut ar = Cells::new();
    let mut dc = Cells::new();
    let mut a = Cells::new();
    let mut b = Cells::new();
    let mut st = Cells::new();
    let mut pg = Cells::new();
    let ostatus = pd_open_pw(view, pw, &mut ar, &mut dc, &mut a, &mut b, &mut st);
    if view.missed() || ostatus == pd_need_bytes() {
        return Step::Miss;
    }
    if ostatus != 0 {
        return Step::Done(Outcome::Failed(status_name(idx, ostatus)));
    }
    let intake = |np: i32, dc: &mut Cells| Intake {
        format: "pdf",
        version: (pd_version_major(dc), pd_version_minor(dc)),
        pages: np.max(0) as usize,
        encrypted: pd_encrypted(dc) == 1,
        bytes: len,
    };
    if intake_only {
        let np = pd_pages(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg);
        if view.missed() {
            return Step::Miss;
        }
        if np < 0 {
            return Step::Done(Outcome::Failed(damaged(idx, crate::emon::planloop::ds_no_pages())));
        }
        return Step::Done(Outcome::Intake(intake(np, &mut dc)));
    }
    let mut cx = Cells::new();
    let mut ox = Cells::new();
    let mut cs = Cells::new();
    let mut out = Cells::new();
    let mut f0 = Cells::new();
    let mut f1 = Cells::new();
    let mut f2 = Cells::new();
    let mut f3 = Cells::new();
    let mut gl = Cells::new();
    let mut et = Cells::new();
    let mut mk = Cells::new();
    let mut fnt = Cells::new();
    let mut wd = Cells::new();
    let mut ln = Cells::new();
    let mut pa = Cells::new();
    let mut im = Cells::new();
    let mut pts = Cells::new();
    let mut gx = Cells::new();
    let status = pc_document(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut cx, &mut ox, &mut cs, &mut out, &mut f0, &mut f1, &mut f2, &mut f3, &mut gl, &mut et, &mut mk, &mut fnt, &mut pg, &mut wd, &mut ln, &mut pa, &mut im, &mut pts, &mut gx);
    if view.missed() || status == pd_need_bytes() {
        return Step::Miss;
    }
    if status != 0 {
        return Step::Done(Outcome::Failed(damaged(idx, crate::emon::planloop::ds_content_failed())));
    }
    let np = pd_pg_count(&mut pg);
    let info = intake(np, &mut dc);
    // THE PLAN OF THE PAGE UNITS (P6.5, D212, P7.U): a page with no text layer and one JPEG image that covers it is a scanned
    // page (a `decode` of the stream, then an `ocr` of its bitmap, in its own unit); its stream is located here, the one place
    // that reads the input. A page with a text layer is left as the text layer made it (fusion, P7.3, is what weighs a second
    // reading against it).
    // The text layer's own triage and health (P7.0, D195, D208): `epages`, the stage-1 features, the flow COPIED into `fw`/`fl`
    // without the words that failed a P3.6 check (set aside in `fa`, written to the ledger after `fu_write`), and the font health
    // in `hl`. Stage 3 reads `fw`/`fl` from here on, never `wd`/`ln`; the scanned pages' lines join them after the page units.
    let (mut tl_epages, mut tl_feats, mut fw, mut fl, mut fa, mut hl, mut hsc) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    th_run(&mut pg, &mut gl, &mut et, &mut mk, &mut fnt, &mut wd, &mut ln, &mut im, &mut tl_epages, &mut tl_feats, &mut fw, &mut fl, &mut fa, &mut hl, &mut hsc);
    let nl0 = cells_len(&ln) / 16;
    let (mut plan, mut sp) = (Cells::new(), Cells::new());
    let (mut rs1, mut rs2) = (Cells::new(), Cells::new());
    pu_plan(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut im, &mut wd, opts.ocr.code(), opts.ocr_scope.code(), &mut fw, &mut fl, &mut et, &mut hl, &mut sp, &mut plan, &mut rs1, &mut rs2);
    if view.missed() {
        return Step::Miss;
    }
    // The fused graphics (P5.5a, D270): the vector source's rules and fills of every page, which the table stage reads instead of `paths`
    // (until P7.3 moves it into the page units, SPEC 9.9).
    let (mut grl, mut gfl, mut gcand, mut gmeas) = (Cells::new(), Cells::new(), Cells::new(), Cells::new());
    gf_doc(&mut pa, &mut pts, &mut mk, &mut im, np, gfx_mode(), &mut grl, &mut gfl, &mut gcand, &mut gmeas);
    // P3.13 (SPEC 10.11): the asset candidates are made by the ASSET UNIT (`asset_unit`, P6.1a), which runs after this pass and reads
    // the input again, because a codec image's pixels are the host's and ask the plan/fulfil loop; the placements it works from are kept.
    let (cand, cbytes) = (Cells::new(), Buf::new());
    let aim = if opts.assets != Assets::None { im.clone() } else { Cells::new() };
    // P8.1 (SPEC 10.6): what each image placement's dictionary says, which the figures' kinds are read from (one more read of the input)
    let (mut fi, mut fo, mut fr) = (Cells::new(), Cells::new(), Cells::new());
    fc_images(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut im, &mut fo, &mut fr, &mut fi);
    if view.missed() {
        return Step::Miss;
    }
    let snap = if opts.keep_evidence { Some((gl.clone(), mk.clone(), fnt.clone())) } else { None };
    // With fusion on (every policy but `never`), the text layer's `epages` and `feats` are the evidence's own: the OCR of a born-digital page adds its
    // feature rows to the same arena, and alignment reads both (P7.3). Without it they are dropped, as before, so the dump is the one it always was.
    let fusion = opts.ocr.code() != 0;
    let mut ev0 = EvArenas::new();
    if fusion {
        ev0.epages = tl_epages;
        ev0.feats = tl_feats;
    }
    // The catalogue's extras and the tagged tree (P3.9, P3.7): the Info /Title and /PageLabels, and the tree's trust
    // (D076), which the furniture stage weighs the tree's /Artifact marks by.
    let (mut idx, mut txt, mut w, mut seen) = (Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut md, mut xm, mut ol, mut lb, mut nd, mut nt) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut ss, mut se, mut sk, mut sc, mut so, mut sa, mut wk) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut ha, mut hb, mut hh) = (Cells::new(), Cells::new(), Cells::new());
    px_index_build(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx);
    pm_run(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx, &mut txt, &mut w, &mut seen, &mut md, &mut xm, &mut ol, &mut lb, &mut nd, &mut nt);
    tg_read(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx, &mut txt, &mut w, &mut seen, &mut ss, &mut se, &mut sk, &mut sc, &mut so, &mut wk, &mut md);
    tg_join(&mut ss, &mut se, &mut sk, &mut sc, &mut so, &mut sa, &mut gl, &mut mk, &mut fnt, &mut pg, &mut ha, &mut hb, &mut hh);
    // inline styles and links (P4.7): what each font says of weight and slant, and the link annotations (P3.8)
    let (mut ift, mut fs, mut fm, mut an, mut aq, mut fd, mut sg) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    il_fonts(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut fnt, &mut et, &mut ift);
    pa_run(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx, &mut txt, &mut w, &mut fs, &mut seen, &mut fm, &mut an, &mut aq, &mut fd, &mut sg);
    if view.missed() {
        return Step::Miss;
    }
    let mut mask = Cells::new();
    for _ in 0..np.max(0) {
        cells_push(&mut mask, 0);
    }
    let mut doc = Doc::new();
    doc.hl = hl;
    doc.et = et;
    doc.gl = gl;
    doc.mk = mk;
    doc.fnt = fnt;
    doc.wd = wd;
    doc.ln = ln;
    doc.fw = fw;
    doc.fl = fl;
    doc.fa = fa;
    doc.nl0 = nl0;
    doc.pa = pa;
    doc.im = im;
    doc.grl = grl;
    doc.gfl = gfl;
    doc.ift = ift;
    doc.an = an;
    doc.pts = if fusion { pts } else { Cells::new() };
    doc.pg = pg;
    doc.txt = txt;
    doc.md = md;
    doc.lb = lb;
    doc.ol = ol;
    doc.ss = ss;
    doc.se = se;
    doc.sc = sc;
    doc.plan = plan;
    doc.mask = mask;
    doc.loc = locale_cells(opts);
    if let Some((g, k, f)) = snap {
        doc.snapg = g;
        doc.snapk = k;
        doc.snapf = f;
        doc.hassnap = 1;
    }
    doc.cand = cand;
    doc.cbytes = cbytes;
    doc.aim = aim;
    doc.fi = fi;
    Step::Pdf(Box::new(PdfDoc { ev: ev0, doc, info }))
}

// THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil
// loop. It re-opens the document (the document pass's arenas are gone) and runs `as_collect` over the placements the document
// pass kept: a codec image whose pixels are the host's is asked for, with the bytes kin made itself in `payload`; the unit is
// run again with the answers and makes the candidates. Answers 0 (the candidates are in `d.cand`, `d.cbytes`), 1 (it asked) or
// 7 (the source missed).
fn asset_unit(view: &Src, pw: &Src, idx: usize, d: &mut PdfDoc, opts: &Options, answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet, payload: &mut Buf) -> i32 {
    let (mut ar, mut dc, mut a, mut b, mut st) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let ostatus = pd_open_pw(view, pw, &mut ar, &mut dc, &mut a, &mut b, &mut st);
    if view.missed() || ostatus == pd_need_bytes() {
        return 7;
    }
    if ostatus != 0 {
        return 100 + ostatus;
    }
    let (mut aout, mut arec, mut amrec, mut asmp, mut amsmp, mut adata, mut arows, mut atmp, mut aws) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut glob, mut words, mut hs) = (Cells::new(), Cells::new(), Cells::new());
    let mut az = Buf::new();
    d.doc.cbytes = Buf::new();
    let rc = as_collect(view, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut d.doc.aim, &mut d.doc.pa, &mut d.doc.gl, &mut d.doc.pg, opts.asset_pixels, opts.assets_bytes, opts.assets.level(), &mut Cells::new(), &mut d.doc.cand, &mut d.doc.cbytes, &mut aout, &mut arec, &mut amrec, &mut asmp, &mut amsmp, &mut adata, &mut arows, &mut atmp, &mut aws, &mut az, idx as i32, answers, rq, payload, bms, &mut glob, &mut words, &mut hs);
    if view.missed() || rc == 12 {
        return 7;
    }
    rc
}

// One page's unit (P7.U, D195): its own requests and answers and its `BitmapSet`; a scanned page is read, any other is
// left as the text layer made it. It writes nothing until its answers are in, and never reads the input.
fn unit_pdf(d: &mut PdfDoc, i: usize, idx: usize, opts: &Options, answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet) -> i32 {
    let mut scr = Cells::new();
    let x = &mut d.doc;
    pu_run_pdf(
        i as i32, idx as i32, opts.ocr.code(), &mut x.plan, answers, rq, &mut x.loc, bms, &mut x.mask, &mut scr,
        &mut d.ev.epages, &mut d.ev.views, &mut x.et, &mut d.ev.feats, &mut d.ev.images, &mut d.ev.oruns, &mut d.ev.oblocks, &mut d.ev.olines, &mut d.ev.owords, &mut d.ev.osyms, &mut d.ev.oalts,
        &mut x.wd, &mut x.ln,
        2 * opts.no_retarget as i32, &mut x.fw, &mut x.fl, &mut x.hl, &mut d.ev.refs, &mut x.cg, &mut d.ev.tlines,
        &mut x.xs0, &mut x.xs1, &mut x.xs2, &mut x.xs3, &mut x.xs4, &mut x.xs5,
        &mut d.ev.inks, &mut d.ev.comps, &mut d.ev.rules, &mut d.ev.prims, &mut d.ev.pts,
        &mut x.ys0, &mut x.ys1, &mut x.ys2, &mut x.ys3, &mut x.ys4, &mut x.ys5, &mut x.ys6, &mut x.ys7, &mut x.ys8, &mut x.ys9, &mut x.ys10, &mut x.ys11, &mut x.ys12, &mut x.ys13, &mut x.ys14, &mut x.ys15, &mut x.ys16, &mut x.ys17, &mut x.ys18,
    )
}

// The document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture, tables, headings,
// paragraphs and columns, the Model. All of it is kin's (`emon.engine`).
fn finish_pdf(d: PdfDoc, opts: &Options) -> Outcome {
    let PdfDoc { mut ev, mut doc, info } = d;
    let mut m = Arenas::new();
    let mut s = Scratch::new();
    let r = en_finish_pdf(&mut cfg_of(opts), &mut doc, &mut ev, &mut m, &mut s);
    finished(r, doc, m, info, opts, 0)
}

impl ChunkCache {
    // SPEC 13.1: intake round 0 prefetches the first and the last chunk (the header, `startxref`, the trailer).
    fn prefetch_ends(&self) {
        if self.len == 0 {
            return;
        }
        let n = self.nchunks();
        let _ = self.fetch(0, std::cmp::min(self.chunk, self.len) as i32);
        let _ = self.fetch(((n - 1) * self.chunk) as i32, (self.len - (n - 1) * self.chunk) as i32);
    }
}

impl Engine {
    pub fn new(cfg: Config) -> Engine {
        Engine { cfg }
    }

    // Stage 0 of each input: format, version, page count, whether it is encrypted. Any number of inputs.
    pub fn intake(&self, inputs: impl Into<Inputs>) -> Result<Vec<Intake>, Error> {
        let inputs = inputs.into().0;
        if inputs.is_empty() {
            return Err(Error::NoInput);
        }
        let mut out = Vec::new();
        for (i, input) in inputs.iter().enumerate() {
            match self.run_input(i, input, &Options::default(), true, Mode::Live)? {
                (Outcome::Intake(x), _) => out.push(x),
                _ => return Err(Error::Internal("intake gave no intake".to_string())),
            }
        }
        Ok(out)
    }

    // Stages 0-3 for the inputs: a Model. Several inputs are not yet one document (see the header).
    pub fn convert(&self, inputs: impl Into<Inputs>, opts: &Options) -> Result<Model, Error> {
        self.convert_stats(inputs, opts).map(|(m, _)| m)
    }

    // The same, and what the loop did (attempts, fetch rounds, bytes fetched).
    pub fn convert_stats(&self, inputs: impl Into<Inputs>, opts: &Options) -> Result<(Model, RunStats), Error> {
        self.convert_with(inputs.into().0, opts, Mode::Live)
    }

    // Convert and RECORD the plan/fulfil transcript (SPEC 13). This slice issues no requests, so it has no
    // entries, but it has the input's index and length, which a replay is checked against.
    pub fn record(&self, inputs: impl Into<Inputs>, opts: &Options) -> Result<(Model, Transcript), Error> {
        let mut t = Transcript::default();
        let (m, _) = self.convert_with(inputs.into().0, opts, Mode::Record(&mut t))?;
        Ok((m, t))
    }

    // Convert from a transcript: no Renderer or Ocr is called; a request it has no answer for is an error.
    pub fn replay(&self, inputs: impl Into<Inputs>, opts: &Options, transcript: &Transcript) -> Result<Model, Error> {
        self.convert_with(inputs.into().0, opts, Mode::Replay(transcript)).map(|(m, _)| m)
    }

    fn convert_with(&self, inputs: Vec<Input>, opts: &Options, mode: Mode) -> Result<(Model, RunStats), Error> {
        if inputs.is_empty() {
            return Err(Error::NoInput);
        }
        if inputs.len() > 1 {
            return Err(Error::Unsupported("several inputs as one document are not wired yet (`intake` takes any number; convert one at a time)"));
        }
        match self.run_input(0, &inputs[0], opts, false, mode)? {
            (Outcome::Built(b), stats) => Ok((Model { a: Mutex::new(b.arenas), evidence: b.evidence, intake: b.intake }, stats)),
            _ => Err(Error::Internal("the unit finished with no Model".to_string())),
        }
    }

    // The loop over one input: limits first, a `Src` over its source, the unit run until an attempt
    // does not miss, and the stats.
    fn run_input(&self, idx: usize, input: &Input, opts: &Options, intake_only: bool, mode: Mode) -> Result<(Outcome, RunStats), Error> {
        port::with_limits(&self.cfg.limits, || self.run_input_bounded(idx, input, opts, intake_only, mode))
    }

    // The synchronous driver of a `Run` (the native hosts' loop; the browser's is `host/wasm`).
    fn run_input_bounded(&self, idx: usize, input: &Input, opts: &Options, intake_only: bool, mode: Mode) -> Result<(Outcome, RunStats), Error> {
        let mut run = self.make_run(idx, input, opts, intake_only)?;
        let len = run.len;
        let this_input = vec![(idx as i32, len as i32)];
        let mut replayer: Option<Replayer> = None;
        // The live port set (D127, D211): `render` and `crop` go to the configured Renderer, reading the same input
        // through the same byte source; with none they, like the other kinds, fall to the host's own fulfiller, which
        // answers them `unsupported` (D199). Pixels are kept in the answers when a transcript is being recorded or an
        // OCR engine will read them.
        let is_recording = matches!(mode, Mode::Record(_));
        let nop = SyncFulfiller::new(port_fulfiller(input.source.clone(), idx, is_recording || self.cfg.ocr.is_some(), is_recording, self.cfg.ocr.clone(), self.cfg.pdf_codecs.clone()));
        let render = self.cfg.renderer.as_ref().map(|r| RenderFulfiller::for_input(r.clone(), idx, RenderInput { source: input.source.clone(), password: input.password.clone() }));
        let mut live = Ports { render, local: Box::new(nop), ocr: self.cfg.ocr.is_some(), order: VecDeque::new() };
        let mut recording: Option<&mut Transcript> = None;
        match mode {
            Mode::Live => {}
            Mode::Record(t) => {
                t.inputs = this_input.clone();
                if let Some(p) = &self.cfg.ocr {
                    t.producer = p.0.producer();
                }
                recording = Some(t);
            }
            Mode::Replay(t) => {
                t.check_inputs(&this_input)?;
                replayer = Some(Replayer::new(t));
            }
        }
        let inner: &mut dyn Fulfiller = match replayer.as_mut() {
            Some(r) => r,
            None => &mut live,
        };
        // THE LOOP: the document pass (everything that reads the input; its misses fetch and re-run it), the asset unit, one unit
        // per page, then the document unit: `Run::step` walks them, and what it needs of this host is bytes and answers. The
        // wall time of each stretch is kept for `RunStats` (P7.S; the Run itself reads no clock, the browser has none).
        let mut ports = TimedPorts { inner, us: [0; 8] };
        let (mut pass_us, mut pages_us) = (0u64, 0u64);
        loop {
            let t0 = std::time::Instant::now();
            let in_pass = run.in_document_pass();
            let next = run.step()?;
            let mut done = false;
            match next {
                plan::Next::Done => done = true,
                plan::Next::Fetch(reqs) => {
                    for r in &reqs {
                        run.fetch_from_source(r.field(crate::emon::plan::bq_offset()), r.field(crate::emon::plan::bq_length()))?;
                    }
                }
                plan::Next::Requests(..) => {
                    let entries = run.fulfil(&mut ports)?;
                    if let Some(t) = recording.as_deref_mut() {
                        t.entries.extend(entries);
                    }
                }
            }
            let us = t0.elapsed().as_micros() as u64;
            if in_pass {
                pass_us += us;
            } else {
                pages_us += us;
            }
            if done {
                break;
            }
        }
        run.set_timing(pass_us, pages_us, ports.us);
        if let Some(r) = replayer.as_mut() {
            r.check_done().map_err(Error::Plan)?;
        }
        run.finish()
    }

    // A `Run` over `input`: the input-bytes bound is checked against the LENGTH before any byte is read (SPEC 13.1), then a `Src`
    // over the source (read in place when it is in memory, through the chunk cache otherwise).
    fn make_run(&self, idx: usize, input: &Input, opts: &Options, intake_only: bool) -> Result<Run, Error> {
        port::with_limits(&self.cfg.limits, || {
            let len = input.source.len();
            let limit = std::cmp::min(port::limit_of(0) as i64, i32::MAX as i64);
            if len as i64 > limit || len > i32::MAX as u64 {
                return Err(Error::Limit { name: "input-bytes".to_string(), limit, actual: len as i64 });
            }
            let ulen = len as usize;
            let simulate = self.cfg.simulate_async;
            let (backing, cache): (Arc<dyn SrcBacking>, Option<Arc<ChunkCache>>) = if !simulate && input.source.as_slice().is_some() {
                (Arc::new(SliceBacking(input.source.clone())), None)
            } else {
                let c = Arc::new(ChunkCache::new(input.source.clone(), ulen, self.cfg.chunk_size.max(1), simulate));
                (c.clone(), Some(c))
            };
            if simulate {
                if let Some(c) = &cache {
                    c.prefetch_ends();
                }
            }
            Ok(self.run_over(idx, backing, cache, len, input.password.clone(), opts, intake_only))
        })
    }

    // A `Run` over a PUSHED cache (P9.3, the browser): the embedder gives the bytes the cache missed (`ChunkCache::provide`)
    // between `step`s, and owns the loop.
    pub fn start_pushed(&self, idx: usize, cache: Arc<ChunkCache>, password: Option<Vec<u8>>, opts: &Options, intake_only: bool) -> Result<Run, Error> {
        port::with_limits(&self.cfg.limits, || {
            let len = cache.len as u64;
            let limit = std::cmp::min(port::limit_of(0) as i64, i32::MAX as i64);
            if len as i64 > limit || len > i32::MAX as u64 {
                return Err(Error::Limit { name: "input-bytes".to_string(), limit, actual: len as i64 });
            }
            Ok(self.run_over(idx, cache.clone(), Some(cache), len, password, opts, intake_only))
        })
    }

    fn run_over(&self, idx: usize, backing: Arc<dyn SrcBacking>, cache: Option<Arc<ChunkCache>>, len: u64, password: Option<Vec<u8>>, opts: &Options, intake_only: bool) -> Run {
        let misses: Box<dyn Misses> = match &cache {
            Some(c) => Box::new(CacheMisses { cache: c.clone(), input: idx as i32 }),
            None => Box::new(NoMisses),
        };
        let pcfg = plan::Config { max_rounds: self.cfg.max_rounds, fetch_rounds: self.cfg.fetch_rounds };
        Run {
            idx,
            opts: opts.clone(),
            intake_only,
            len,
            limits: self.cfg.limits.clone(),
            machine: plan::Machine::new(&pcfg),
            base: Src::new(backing),
            pw: Src::of_bytes(password.unwrap_or_default()),
            cache,
            misses,
            outcome: Outcome::None,
            docslot: None,
            failed: None,
            fig_pend: None,
            fig_done: None,
            stats: RunStats { input_bytes: len, ..RunStats::default() },
        }
    }
}

// ----------------------------------------------------------- the run, a step at a time (P9.3)
//
// One input's conversion as a MACHINE: `step` runs kin until it needs something of the host, and says what (bytes the `Src`
// missed, or requests to fulfil); the host does it, however it likes (a native host inside the call, a browser after an
// `await`), reports back (`ChunkCache::provide` / `fetch_from_source`, `answered`) and steps again. The phases are the ones the
// loop always had: the document pass (everything that reads the input: it misses, is discarded and re-run), the asset unit
// (it reads the input too), one unit per page (P7.U, D195: its own answers, requests and `BitmapSet`, dropped when the unit
// ends, so a conversion holds one page's bitmaps; a page unit never reads the input), then the document unit, stage 3.

// THE SCHEDULE IS KIN'S (`en-next`, P10.6 S4, P10.8): which unit runs next is `Plan::phase` (`ph-doc`, `ph-asset`, `ph-pages` and the page,
// `ph-finish`, `ph-figures`, `ph-done`), advanced by the Machine, and this Run is the driver of each unit.

pub struct Run {
    idx: usize,
    opts: Options,
    intake_only: bool,
    len: u64,
    limits: Vec<(i32, i32)>,
    machine: plan::Machine,
    base: Src,
    pw: Src,
    cache: Option<Arc<ChunkCache>>,
    misses: Box<dyn Misses>,
    outcome: Outcome,
    docslot: Option<DocKind>,
    failed: Option<Error>,
    fig_pend: Option<PendingAssets>,
    fig_done: Option<Arenas>,
    stats: RunStats,
}

impl Run {
    // Whether the next `step` is still the document pass (P7.S's timing; a host that keeps a clock asks).
    pub fn in_document_pass(&self) -> bool {
        self.machine.phase() == crate::emon::planloop::ph_doc()
    }

    // The wall time a host measured (`RunStats`): the document pass, every later stretch (the document unit's own time, which
    // the Run took itself on a native build, is taken out of the pages'), and the ports' time by request kind.
    pub fn set_timing(&mut self, pass_us: u64, pages_us: u64, fulfil_us: [u64; 8]) {
        self.stats.pass_us = pass_us;
        self.stats.pages_us = pages_us.saturating_sub(self.stats.finish_us);
        self.stats.fulfil_us = fulfil_us;
    }

    // The input's length (what the source said, which a pushed cache was made with).
    pub fn len(&self) -> u64 {
        self.len
    }

    // A source that failed is the answer, whatever the loop made of the miss it caused.
    fn source_failure(&self) -> Option<Error> {
        let c = self.cache.as_ref()?;
        let (short, msg) = c.failure()?;
        Some(if short { Error::SourceChanged { input: self.idx } } else { Error::Io { input: self.idx, message: msg } })
    }

    // The source's failure, when it has failed (a pushed cache's host said its read failed or came up short).
    pub fn source_error(&self) -> Option<Error> {
        self.source_failure()
    }

    // An error of the loop, or the source's failure when it has one.
    pub fn error_of(&self, e: PlanError) -> Error {
        self.source_failure().unwrap_or_else(|| e.into())
    }

    // Read what the cache missed from the source it was made over (a native host's fetch, inside its loop).
    pub fn fetch_from_source(&mut self, offset: i32, length: i32) -> Result<(), Error> {
        if let Some(c) = &self.cache {
            if c.fetch(offset, length).is_err() {
                return Err(self.error_of(PlanError::Fulfil("a read of the source failed".to_string())));
            }
        }
        Ok(())
    }

    // The requests of the round in progress (what the last `step` handed back), sorted by id.
    pub fn requests(&self) -> &[plan::Request] {
        self.machine.requests()
    }

    // A bitmap any request of the round may name: answered so far, or one kin made.
    pub fn bitmap(&self, id: i32) -> Option<Arc<crate::port::Bitmap>> {
        self.machine.bitmap(id)
    }

    // Every bitmap the round's fulfillers may name.
    pub fn bitmaps(&self) -> &plan::Bitmaps {
        self.machine.bitmaps()
    }

    // The answers to the requests of the last `step`, one each, in any order; the entries a transcript records, in id order.
    pub fn answered(&mut self, got: Vec<Answer>) -> Result<Vec<plan::Entry>, Error> {
        let r = self.machine.answered(got);
        r.map_err(|e| self.error_of(e))
    }

    // Start every request of the round with `f` and take every completion: the round is answered.
    pub fn fulfil(&mut self, f: &mut dyn Fulfiller) -> Result<Vec<plan::Entry>, Error> {
        let r = self.machine.fulfil(f);
        r.map_err(|e| self.error_of(e))
    }

    // One answer to the round, whenever it arrives (a host that awaits offers each as it comes), and `commit` when every request has one.
    pub fn offer(&mut self, a: Answer) -> Result<(), Error> {
        let r = self.machine.offer(a);
        r.map_err(|e| self.error_of(e))
    }

    pub fn commit(&mut self) -> Result<Vec<plan::Entry>, Error> {
        let r = self.machine.commit();
        r.map_err(|e| self.error_of(e))
    }

    // Whether the round has an answer to request `id`.
    pub fn has_answer(&mut self, id: i32) -> bool {
        self.machine.has(id)
    }

    // What the document pass built, for the schedule: 0 nothing, 1 a PDF, 2 a raster input; and its page count.
    fn doc_kind(&self) -> (i32, usize) {
        match &self.docslot {
            Some(DocKind::Pdf(d)) => (1, d.info.pages),
            Some(DocKind::Raster(d)) => (2, d.info.pages),
            None => (0, 0),
        }
    }

    // The unit that just finished is folded into the totals, and the schedule moves on.
    fn advance(&mut self) {
        let (kind, np) = self.doc_kind();
        let assets = self.opts.assets != Assets::None;
        self.machine.end_unit();
        self.machine.next_phase(kind, assets, false, np);
    }

    // Run kin until it needs something. `Done`: every unit has finished, `finish` is next.
    pub fn step(&mut self) -> Result<plan::Next, Error> {
        let limits = self.limits.clone();
        port::with_limits(&limits, || self.step_inner())
    }

    fn step_inner(&mut self) -> Result<plan::Next, Error> {
        use crate::emon::planloop as lp;
        loop {
            let phase = self.machine.phase();
            if phase == lp::ph_doc() {
                let (idx, len, intake_only) = (self.idx, self.len, self.intake_only);
                let mut unit = |answers: &mut Cells, rq: &mut Cells, _bms: &BitmapSet, _payload: &mut Buf| -> i32 {
                    let view = self.base.view();
                    match catch_unwind(AssertUnwindSafe(|| dispatch(&view, &self.pw, answers, rq, idx, &self.opts, intake_only, len))) {
                        Ok(RStep::Miss) => plan::UNIT_NEED_BYTES,
                        Ok(RStep::Requests) => plan::UNIT_REQUESTS,
                        Ok(RStep::Done(o)) => {
                            self.outcome = o;
                            plan::UNIT_DONE
                        }
                        Ok(RStep::Pdf(d)) => {
                            self.docslot = Some(DocKind::Pdf(d));
                            plan::UNIT_DONE
                        }
                        Ok(RStep::Raster(d)) => {
                            self.docslot = Some(DocKind::Raster(d));
                            plan::UNIT_DONE
                        }
                        Err(p) => {
                            if view.missed() {
                                plan::UNIT_NEED_BYTES
                            } else {
                                self.outcome = Outcome::Failed(panic_error(p));
                                plan::UNIT_DONE
                            }
                        }
                    }
                };
                let r = self.machine.attempt(&mut unit, &mut *self.misses);
                let next = r.map_err(|e| self.error_of(e))?;
                if let Some(e) = self.source_failure() {
                    return Err(e);
                }
                if !matches!(next, plan::Next::Done) {
                    return Ok(next);
                }
                // THE ASSET UNIT follows a PDF with `assets` on (P6.1a, D117): its image placements become asset candidates, and a codec
                // image's pixels are asked of the host (`decode-img`, one round for them all)
                self.advance();
            } else if phase == lp::ph_asset() {
                let idx = self.idx;
                let mut unit_a = |answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet, payload: &mut Buf| -> i32 {
                    let view = self.base.view();
                    let doc = match &mut self.docslot {
                        Some(DocKind::Pdf(doc)) => doc,
                        _ => {
                            self.outcome = Outcome::Failed(Error::Internal("the asset unit has no document".to_string()));
                            return plan::UNIT_DONE;
                        }
                    };
                    match catch_unwind(AssertUnwindSafe(|| asset_unit(&view, &self.pw, idx, doc, &self.opts, answers, rq, bms, payload))) {
                        Ok(0) => plan::UNIT_DONE,
                        Ok(1) => plan::UNIT_REQUESTS,
                        Ok(_) if view.missed() => plan::UNIT_NEED_BYTES,
                        Ok(7) => plan::UNIT_NEED_BYTES,
                        Ok(s) => {
                            self.outcome = Outcome::Failed(Error::Internal(format!("the asset unit answered status {}", s)));
                            plan::UNIT_DONE
                        }
                        Err(p) => {
                            if view.missed() {
                                plan::UNIT_NEED_BYTES
                            } else {
                                self.outcome = Outcome::Failed(panic_error(p));
                                plan::UNIT_DONE
                            }
                        }
                    }
                };
                let r = self.machine.attempt(&mut unit_a, &mut *self.misses);
                let next = r.map_err(|e| self.error_of(e))?;
                if let Some(e) = self.source_failure() {
                    return Err(e);
                }
                if !matches!(next, plan::Next::Done) {
                    return Ok(next);
                }
                self.advance();
                if let Outcome::Failed(e) = std::mem::replace(&mut self.outcome, Outcome::None) {
                    return Err(e);
                }
            } else if phase == lp::ph_pages() {
                let i = self.machine.page();
                let (idx, len) = (self.idx, self.len);
                let mut unit_p = |answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet, _payload: &mut Buf| -> i32 {
                    let r = catch_unwind(AssertUnwindSafe(|| match &mut self.docslot {
                        Some(DocKind::Pdf(doc)) => unit_pdf(doc, i, idx, &self.opts, answers, rq, bms),
                        Some(DocKind::Raster(doc)) => unit_raster(doc, i, idx, len, &self.opts, answers, rq, bms),
                        None => 99,
                    }));
                    match r {
                        Ok(0) => plan::UNIT_DONE,
                        Ok(1) => plan::UNIT_REQUESTS,
                        Ok(s) if s == lp::ds_undecodable() || s == lp::ds_unsupported_image() => {
                            self.failed = Some(damaged(idx, s));
                            plan::UNIT_DONE
                        }
                        Ok(s) => {
                            self.failed = Some(Error::Internal(format!("a page unit answered status {}", s)));
                            plan::UNIT_DONE
                        }
                        Err(p) => {
                            self.failed = Some(panic_error(p));
                            plan::UNIT_DONE
                        }
                    }
                };
                let mut no_misses = NoMisses;
                let next = self.machine.attempt(&mut unit_p, &mut no_misses).map_err(Error::from)?;
                if !matches!(next, plan::Next::Done) {
                    return Ok(next);
                }
                if let Some(e) = self.failed.take() {
                    return Err(e);
                }
                self.advance();
            } else if phase == lp::ph_finish() {
                #[cfg(not(target_arch = "wasm32"))]
                let t_fin = std::time::Instant::now();
                // the document unit is kin too: a panic in it (a bug, or a limit breach) is the Engine's named error, never the embedder's (P10.5, D386)
                let opts = &self.opts;
                self.outcome = match self.docslot.take() {
                    Some(DocKind::Pdf(doc)) => finish_guarded(|| finish_pdf(*doc, opts)),
                    Some(DocKind::Raster(doc)) => finish_guarded(|| finish_raster(*doc, opts)),
                    None => Outcome::None,
                };
                #[cfg(not(target_arch = "wasm32"))]
                {
                    self.stats.finish_us = t_fin.elapsed().as_micros() as u64;
                }
                // THE FIGURES UNIT (P8.5, D351): a Built Model with assets still pending goes on to it.
                let mut pending = false;
                if let Outcome::Built(b) = &mut self.outcome {
                    if let Some(pend) = b.pending.take() {
                        self.fig_pend = Some(pend);
                        pending = true;
                    }
                }
                self.machine.next_phase(0, false, pending, 0);
            } else if phase == lp::ph_figures() {
                let (idx, len) = (self.idx, self.len);
                let mut unit_f = |answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet, _payload: &mut Buf| -> i32 {
                    let (base, pend) = match (&self.outcome, &self.fig_pend) {
                        (Outcome::Built(b), Some(p)) => (&b.arenas, p),
                        _ => {
                            self.failed = Some(Error::Internal("the figures unit has no Model".to_string()));
                            return plan::UNIT_DONE;
                        }
                    };
                    let opts = &self.opts;
                    let r = catch_unwind(AssertUnwindSafe(|| {
                        let mut t = base.clone();
                        let mut p = pend.clone();
                        asset_stage_figures(&mut t, &mut p, opts, idx as i32, len as i32, answers, rq, bms);
                        t
                    }));
                    match r {
                        Ok(t) => {
                            if cells_len(rq) > 0 {
                                plan::UNIT_REQUESTS
                            } else {
                                self.fig_done = Some(t);
                                plan::UNIT_DONE
                            }
                        }
                        Err(p) => {
                            self.failed = Some(panic_error(p));
                            plan::UNIT_DONE
                        }
                    }
                };
                let mut no_misses = NoMisses;
                let next = self.machine.attempt(&mut unit_f, &mut no_misses).map_err(Error::from)?;
                if !matches!(next, plan::Next::Done) {
                    return Ok(next);
                }
                if let Some(e) = self.failed.take() {
                    return Err(e);
                }
                self.advance();
                self.fig_pend = None;
                match (self.fig_done.take(), &mut self.outcome) {
                    (Some(a), Outcome::Built(b)) => b.arenas = a,
                    _ => return Err(Error::Internal("the figures unit finished with no Model".to_string())),
                }
                if let Outcome::Built(b) = &mut self.outcome {
                    if let Some(e) = seal_arenas(&mut b.arenas, &self.opts) {
                        return Err(e);
                    }
                }
            } else {
                return Ok(plan::Next::Done);
            }
        }
    }

    // The end: the Model (or the intake) the units built, and what the loop did.
    fn finish(mut self) -> Result<(Outcome, RunStats), Error> {
        let t = self.machine.totals();
        self.stats.attempts = t.attempts;
        self.stats.fetch_rounds = t.fetch_rounds;
        self.stats.bytes_requests = t.bytes_requests;
        self.stats.requests = t.requests;
        self.stats.page_rounds = t.page_rounds;
        self.stats.page_requests = t.page_requests;
        self.stats.source_reads = self.cache.as_ref().map(|c| c.reads()).unwrap_or(0);
        self.stats.bytes_fetched = self.cache.as_ref().map(|c| c.bytes_read()).unwrap_or(0);
        match self.outcome {
            Outcome::Failed(e) => Err(e),
            Outcome::None => Err(Error::Internal("the unit finished with nothing".to_string())),
            o => Ok((o, self.stats)),
        }
    }

    // The Model of a finished `step` loop of a conversion.
    pub fn into_model(self) -> Result<(Model, RunStats), Error> {
        match self.finish()? {
            (Outcome::Built(b), stats) => Ok((Model { a: Mutex::new(b.arenas), evidence: b.evidence, intake: b.intake }, stats)),
            _ => Err(Error::Internal("the unit finished with no Model".to_string())),
        }
    }

    // The stage-0 answer of a finished `step` loop of an intake.
    pub fn into_intake(self) -> Result<(Intake, RunStats), Error> {
        match self.finish()? {
            (Outcome::Intake(x), stats) => Ok((x, stats)),
            _ => Err(Error::Internal("intake gave no intake".to_string())),
        }
    }
}

// ------------------------------------------------------------------- Model
//
// The Model is the seven arenas of SPEC 3.8, owned; `Node` is a (Model, index) pair and never copies
// (SPEC 3.9). Every accessor is a kin one (`node-kind`, `node-first-child`, ...): this layer renames
// and iterates, it decides nothing. kin's Rust read parameters are `&mut Cells`, so the arenas sit
// behind a lock; a `Model` is `Send + Sync` and may be read from several threads.

pub struct Model {
    a: Mutex<Arenas>,
    evidence: Option<String>,
    intake: Intake,
}

// A node's kind, by kin's own table (`emon.model` `nk`): `name()` is the JSON `kind`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Kind(pub i32);

impl Kind {
    pub fn name(&self) -> String {
        let mut s = String::new();
        nk_emit_name(&mut s, self.0);
        s
    }
    // The kind with this JSON name (`"paragraph"`), if there is one.
    pub fn named(name: &str) -> Option<Kind> {
        (1..=nk_initial()).map(Kind).find(|k| k.name() == name)
    }
}


// P8.5 (SPEC 10.11, D351): the asset stage at the level `figures`, one attempt of the asset unit, on copies the caller made. It writes
// the Model's settings and every figure's and seal's asset or reason, and any `crop` or `decode` request it has no answer to into `rq`
// (then its results are meaningless: the loop answers and runs it again).
fn asset_stage_figures(m: &mut Arenas, p: &mut PendingAssets, opts: &Options, input: i32, nbytes: i32, ans: &mut Cells, rq: &mut Cells, bms: &BitmapSet) {
    let sigs = if opts.asset_signatures { 1 } else { 0 };
    as_settings(&mut m.nodes, &mut m.attrs, &mut m.text, opts.assets.level(), opts.asset_dpi, opts.asset_pixels, opts.asset_bytes, opts.assets_bytes, sigs);
    let (mut ws, mut rows, mut tmp, mut rec, mut bx) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let mut z = Buf::new();
    as_run_figures(
        &mut m.nodes, &mut m.attrs, &mut m.regions, &mut m.pages, &mut p.cand, &mut p.cbytes, &mut m.assets, &mut m.uses, &mut m.abytes,
        opts.asset_bytes, opts.assets_bytes, sigs, opts.asset_dpi, opts.asset_pixels, input, nbytes, p.rfrom, ans, rq, bms,
        &mut ws, &mut z, &mut rows, &mut tmp, &mut rec, &mut bx,
    );
}

// A page of the Model's page table (SPEC 3.7). Sizes are in centipoints (1/100 point), as displayed.
#[derive(Debug, Clone, PartialEq)]
pub struct Page {
    pub number: i32,
    pub label: Option<String>,
    pub width: i32,
    pub height: i32,
    pub rotation: i32,
    pub kind: String,
}

// A region of a node: a page and a box in centipoints, origin top left of the displayed page.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Region {
    pub page: i32,
    pub x0: i32,
    pub y0: i32,
    pub x1: i32,
    pub y1: i32,
}

// An attribute's typed value (kin's `av-*`).
#[derive(Debug, Clone, PartialEq)]
pub enum Attr {
    Int(i32),
    Text(String),
    // another node, by index
    Node(i32),
    Bool(bool),
}

// An entry of the ledger of dropped content (SPEC 3.7).
#[derive(Debug, Clone, PartialEq)]
pub struct Dropped {
    pub reason: String,
    pub page: i32,
    pub region: Region,
    pub text: String,
}

impl Model {
    fn with<R>(&self, f: impl FnOnce(&mut Arenas) -> R) -> R {
        f(&mut self.a.lock().unwrap())
    }

    pub fn root(&self) -> Node<'_> {
        Node { m: self, n: 0 }
    }

    // What stage 0 said of the input this Model was made from.
    pub fn source(&self) -> &Intake {
        &self.intake
    }

    pub fn node_count(&self) -> usize {
        self.with(|a| node_count(&mut a.nodes) as usize)
    }

    pub fn pages(&self) -> Vec<Page> {
        self.with(|a| {
            (0..page_count(&mut a.pages))
                .map(|p| {
                    let ls = page_label_start(&mut a.pages, p);
                    let ll = page_label_len(&mut a.pages, p);
                    let mut kind = String::new();
                    pk_emit_name(&mut kind, page_kind(&mut a.pages, p));
                    Page {
                        number: page_number(&mut a.pages, p),
                        label: if ls >= 0 { Some(text_of(&mut a.text, ls, ll)) } else { None },
                        width: page_w(&mut a.pages, p),
                        height: page_h(&mut a.pages, p),
                        rotation: page_rot(&mut a.pages, p),
                        kind,
                    }
                })
                .collect()
        })
    }

    // The furniture templates (SPEC 3.7): the top-level `furniture` nodes, in order.
    pub fn furniture(&self) -> Vec<Node<'_>> {
        let ids: Vec<i32> = self.with(|a| (0..node_count(&mut a.nodes)).filter(|&n| node_kind(&mut a.nodes, n) == nk_furniture() && node_parent(&mut a.nodes, n) == -1).collect());
        ids.into_iter().map(|n| Node { m: self, n }).collect()
    }

    // The ledger of dropped content.
    pub fn dropped(&self) -> Vec<Dropped> {
        self.with(|a| {
            (0..ledger_count(&mut a.ledger))
                .map(|l| {
                    let mut reason = String::new();
                    dr_emit_name(&mut reason, ledger_reason(&mut a.ledger, l));
                    let ts = ledger_text_start(&mut a.ledger, l);
                    let tl = ledger_text_len(&mut a.ledger, l);
                    Dropped {
                        reason,
                        page: ledger_page(&mut a.ledger, l),
                        region: Region { page: ledger_page(&mut a.ledger, l), x0: ledger_x0(&mut a.ledger, l), y0: ledger_y0(&mut a.ledger, l), x1: ledger_x1(&mut a.ledger, l), y1: ledger_y1(&mut a.ledger, l) },
                        text: if ts >= 0 { text_of(&mut a.text, ts, tl) } else { String::new() },
                    }
                })
                .collect()
        })
    }

    // Every node of a kind, in document order (the arena is in preorder: `model-check` says so).
    pub fn find(&self, kind: Kind) -> Vec<Node<'_>> {
        let ids: Vec<i32> = self.with(|a| (0..node_count(&mut a.nodes)).filter(|&n| node_kind(&mut a.nodes, n) == kind.0).collect());
        ids.into_iter().map(|n| Node { m: self, n }).collect()
    }

    // The text layer's evidence dump, when `Options::keep_evidence` asked for it.
    pub fn evidence(&self) -> Option<&str> {
        self.evidence.as_deref()
    }

    // SPEC 4: canonical JSON.
    pub fn to_json(&self, o: &JsonOptions) -> String {
        self.with(|a| {
            let mut t = String::new();
            js_emit(&mut t, if o.pretty { 1 } else { 0 }, &mut a.nodes, &mut a.attrs, &mut a.regions, &mut a.text, &mut a.pages, &mut a.ledger, &mut a.layout, &mut a.assets, &mut a.uses);
            t
        })
    }

    // SPEC 5: HTML for crawlers.
    pub fn to_html(&self, o: &HtmlOptions) -> String {
        let (bits, below) = o.bits();
        let mut prefix = Cells::new();
        for ch in o.asset_prefix.chars() {
            cells_push(&mut prefix, ch as i32);
        }
        self.with(|a| {
            let mut t = String::new();
            html_emit(&mut t, bits, below, &mut a.nodes, &mut a.attrs, &mut a.regions, &mut a.text, &mut a.pages, &mut a.ledger, &mut a.layout, &mut a.assets, &mut a.uses, &mut a.abytes, &mut prefix);
            t
        })
    }

    // The same, by an `Output` value.
    pub fn serialize(&self, o: &Output) -> String {
        match o {
            Output::Json(j) => self.to_json(j),
            Output::Html(h) => self.to_html(h),
        }
    }

    // SPEC 3.9 / 10.11: the asset table, in admission order; empty unless `Options::assets` was on.
    pub fn assets(&self) -> Vec<Asset<'_>> {
        let n = self.with(|a| asset_count(&mut a.assets));
        (0..n).map(|id| Asset { m: self, id }).collect()
    }

    // The asset with this id, if there is one.
    pub fn asset(&self, id: i32) -> Option<Asset<'_>> {
        if id >= 0 && id < self.with(|a| asset_count(&mut a.assets)) { Some(Asset { m: self, id }) } else { None }
    }

    // The sidecar: every asset's (file name, media type, bytes), for the caller to write beside the HTML (`external`).
    pub fn asset_files(&self) -> Vec<AssetFile> {
        self.assets().iter().map(|a| AssetFile { name: a.name(), media_type: a.media_type(), bytes: a.bytes() }).collect()
    }
}

// ------------------------------------------------------------------ assets (P3.13, SPEC 10.11, D117)
//
// An asset is a picture the Model carries beside its tree: a `figure` or `seal` whose content is one image the document embeds
// (its original JPEG bytes, or its pixels as the canonical PNG). The bytes live in the Model (kin's `abytes`); `bytes()` copies
// them out (the arenas sit behind a lock, so a borrow cannot leave it). Nothing here exists unless `Options::assets` was on.

// One use of an asset: the node and the page region its pixels map to.
#[derive(Debug, Clone, PartialEq)]
pub struct AssetUse {
    pub node: i32,
    pub region: Region,
}

// One asset as a file for the caller to write beside the HTML: its name, media type and bytes.
#[derive(Debug, Clone, PartialEq)]
pub struct AssetFile {
    pub name: String,
    pub media_type: String,
    pub bytes: Vec<u8>,
}

// An asset: a view of the Model's table (an index, like a `Node`).
#[derive(Clone, Copy)]
pub struct Asset<'a> {
    m: &'a Model,
    id: i32,
}

impl<'a> std::fmt::Debug for Asset<'a> {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "Asset({} {})", self.id, self.name())
    }
}

impl<'a> Asset<'a> {
    // The asset's id: its index in `Model::assets()`, in admission order.
    pub fn id(&self) -> i32 {
        self.id
    }
    // The file name, `a<id>.jpg` or `a<id>.png`.
    pub fn name(&self) -> String {
        let jpg = self.m.with(|a| asset_type(&mut a.assets, self.id)) == amt_jpeg();
        format!("a{}.{}", self.id, if jpg { "jpg" } else { "png" })
    }
    // `image/jpeg` or `image/png`.
    pub fn media_type(&self) -> String {
        let mut s = String::new();
        amt_emit_name(&mut s, self.m.with(|a| asset_type(&mut a.assets, self.id)));
        s
    }
    // `embedded` or `decoded` (`cropped` and `rendered` arrive with the `figures` level).
    pub fn origin(&self) -> String {
        let mut s = String::new();
        ao_emit_name(&mut s, self.m.with(|a| asset_origin(&mut a.assets, self.id)));
        s
    }
    // What the pixels were before: `dct`, `flate`, `lzw`, `runlength`, `raw`, ...
    pub fn from(&self) -> String {
        let mut s = String::new();
        afm_emit_name(&mut s, self.m.with(|a| asset_from(&mut a.assets, self.id)));
        s
    }
    // The image XObject's object number, when it has one.
    pub fn object(&self) -> Option<i32> {
        let o = self.m.with(|a| asset_object(&mut a.assets, self.id));
        if o >= 0 { Some(o) } else { None }
    }
    // The SHA-256 of the encoded bytes, 64 lower-case hex digits.
    pub fn sha256(&self) -> String {
        self.m.with(|a| {
            let mut s = String::new();
            for i in 0..8 {
                s.push_str(&format!("{:08x}", asset_sha(&mut a.assets, self.id, i) as u32));
            }
            s
        })
    }
    pub fn width(&self) -> i32 {
        self.m.with(|a| asset_w(&mut a.assets, self.id))
    }
    pub fn height(&self) -> i32 {
        self.m.with(|a| asset_h(&mut a.assets, self.id))
    }
    // The render's resolution; only a rendered asset has one.
    pub fn dpi(&self) -> Option<i32> {
        let d = self.m.with(|a| asset_dpi(&mut a.assets, self.id));
        if d > 0 { Some(d) } else { None }
    }
    // `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap`.
    pub fn flags(&self) -> Vec<String> {
        let bits = self.m.with(|a| asset_flags(&mut a.assets, self.id));
        (0..6)
            .map(|b| 1 << b)
            .filter(|bit| bits & bit != 0)
            .map(|bit| {
                let mut s = String::new();
                afg_emit_name(&mut s, bit);
                s
            })
            .collect()
    }
    // Every node that shows it, with the page region its pixels map to.
    pub fn uses(&self) -> Vec<AssetUse> {
        self.m.with(|a| {
            let mut out = Vec::new();
            let mut u = asset_use_head(&mut a.assets, self.id);
            while u >= 0 {
                out.push(AssetUse {
                    node: use_node(&mut a.uses, u),
                    region: Region { page: use_page(&mut a.uses, u), x0: use_x0(&mut a.uses, u), y0: use_y0(&mut a.uses, u), x1: use_x1(&mut a.uses, u), y1: use_y1(&mut a.uses, u) },
                });
                u = use_next(&mut a.uses, u);
            }
            out
        })
    }
    // The encoded size in bytes (`bytes().len()` without the copy); the browser binding's (`host/wasm`), so unused natively.
    #[allow(dead_code)]
    pub(crate) fn len(&self) -> usize {
        self.m.with(|a| asset_blen(&mut a.assets, self.id)) as usize
    }
    // The encoded bytes (a copy: the Model's arenas sit behind a lock).
    pub fn bytes(&self) -> Vec<u8> {
        self.m.with(|a| {
            let (s, n) = (asset_bstart(&mut a.assets, self.id), asset_blen(&mut a.assets, self.id));
            a.abytes.0[s as usize..(s + n) as usize].to_vec()
        })
    }
}


// A node: the Model and an index. It borrows, it never copies.
#[derive(Clone, Copy)]
pub struct Node<'a> {
    m: &'a Model,
    n: i32,
}

impl<'a> std::fmt::Debug for Node<'a> {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "Node({} {})", self.n, self.kind().name())
    }
}

impl<'a> Node<'a> {
    fn num(&self, f: impl FnOnce(&mut Cells, i32) -> i32) -> i32 {
        self.m.with(|a| f(&mut a.nodes, self.n))
    }

    // The node's index in the Model (kin's `node-*` accessors take it).
    pub fn index(&self) -> usize {
        self.n as usize
    }
    // The JSON `id`: the index. Inline nodes carry none in JSON (D052) but have an index here.
    pub fn id(&self) -> i32 {
        self.n
    }
    pub fn kind(&self) -> Kind {
        Kind(self.num(|c, n| node_kind(c, n)))
    }
    // The confidence, 0..=1000, or -1 when the node has none.
    pub fn conf(&self) -> i32 {
        self.num(|c, n| node_conf(c, n))
    }
    // Provenance (SPEC 3.5), as the JSON `src` names, in kin's bit order.
    pub fn sources(&self) -> Vec<String> {
        let bits = self.num(|c, n| node_src(c, n));
        (0..9)
            .map(|b| 1 << b)
            .filter(|bit| bits & bit != 0)
            .map(|bit| {
                let mut s = String::new();
                sr_emit_name(&mut s, bit);
                s
            })
            .collect()
    }
    // Warning bits (SPEC 3.3), raw: kin's `fl-*`.
    pub fn flags(&self) -> i32 {
        self.num(|c, n| node_flags(c, n))
    }
    pub fn parent(&self) -> Option<Node<'a>> {
        let p = self.num(|c, n| node_parent(c, n));
        if p < 0 { None } else { Some(Node { m: self.m, n: p }) }
    }
    // Every child in document order: blocks and inlines alike (`inlines` and `blocks` split them).
    pub fn children(&self) -> Vec<Node<'a>> {
        let mut out = Vec::new();
        let mut c = self.num(|c, n| node_first_child(c, n));
        while c >= 0 {
            out.push(Node { m: self.m, n: c });
            c = self.m.with(|a| node_next(&mut a.nodes, c));
        }
        out
    }
    pub fn is_inline(&self) -> bool {
        self.m.with(|a| node_is_inline(&mut a.nodes, self.n)) == 1
    }
    pub fn inlines(&self) -> Vec<Node<'a>> {
        self.children().into_iter().filter(|c| c.is_inline()).collect()
    }
    pub fn blocks(&self) -> Vec<Node<'a>> {
        self.children().into_iter().filter(|c| !c.is_inline()).collect()
    }
    // The node's plain text: its own run when it has one, else (D052, as the JSON's `text`) the text of
    // its inline children, a line break as a newline, a span or link by descent. Empty for a node with neither.
    pub fn text(&self) -> String {
        let mut s = String::new();
        if self.num(|c, n| node_has_text(c, n)) == 1 {
            let (st, ln) = (self.num(|c, n| node_text_start(c, n)), self.num(|c, n| node_text_len(c, n)));
            return self.m.with(|a| text_of(&mut a.text, st, ln));
        }
        self.derive(&mut s);
        s
    }
    fn derive(&self, s: &mut String) {
        for c in self.children() {
            let k = c.kind();
            if k.0 == nk_linebreak() {
                s.push('\n');
            }
            if nk_is_inline(k.0) == 1 {
                if c.num(|x, n| node_has_text(x, n)) == 1 {
                    let (st, ln) = (c.num(|x, n| node_text_start(x, n)), c.num(|x, n| node_text_len(x, n)));
                    s.push_str(&self.m.with(|a| text_of(&mut a.text, st, ln)));
                }
                c.derive(s);
            }
        }
    }
    // The regions the node covers, one per page (or column fragment): centipoints.
    pub fn regions(&self) -> Vec<Region> {
        self.m.with(|a| {
            let mut out = Vec::new();
            let mut r = node_region_head(&mut a.nodes, self.n);
            for _ in 0..node_region_count(&mut a.nodes, self.n) {
                out.push(Region { page: region_page(&mut a.regions, r), x0: region_x0(&mut a.regions, r), y0: region_y0(&mut a.regions, r), x1: region_x1(&mut a.regions, r), y1: region_y1(&mut a.regions, r) });
                r = region_next(&mut a.regions, r);
            }
            out
        })
    }

    // The asset this `figure` or `seal` shows, if it has one (SPEC 10.11).
    pub fn asset(&self) -> Option<Asset<'a>> {
        match self.attr("asset") {
            Some(Attr::Int(id)) => self.m.asset(id),
            _ => None,
        }
    }
    // Why it has no asset (`withheld`, `part`, `vector`, `composite`, `colorspace`, `codec`, `render`, `too-large`,
    // `budget`), when `Options::assets` was on and it has none.
    pub fn noasset(&self) -> Option<String> {
        self.m.with(|a| {
            let at = attr_find(&mut a.attrs, node_attr_head(&mut a.nodes, self.n), fk_noasset());
            if at < 0 {
                return None;
            }
            let mut s = String::new();
            nz_emit_name(&mut s, attr_value(&mut a.attrs, at));
            Some(s)
        })
    }
    // An attribute by its JSON name (`level`, `align`, `locale`, ...), typed; None when absent.
    pub fn attr(&self, name: &str) -> Option<Attr> {
        self.m.with(|a| {
            let mut at = node_attr_head(&mut a.nodes, self.n);
            while at >= 0 {
                let mut key = String::new();
                fk_emit_name(&mut key, attr_key(&mut a.attrs, at));
                if key == name {
                    let (v, l) = (attr_value(&mut a.attrs, at), attr_len(&mut a.attrs, at));
                    return Some(match attr_vkind(&mut a.attrs, at) {
                        k if k == av_text() => Attr::Text(text_of(&mut a.text, v, l)),
                        k if k == av_node() => Attr::Node(v),
                        k if k == av_bool() => Attr::Bool(v != 0),
                        _ => Attr::Int(v),
                    });
                }
                at = attr_next(&mut a.attrs, at);
            }
            None
        })
    }
}

// The document unit is kin too, and a panic in it is the Engine's named error, as in a page unit (P10.5, D386): a bug is `internal`, a kin limit
// breach is a `limit` error with its name, and neither is the embedder's panic. (`cargo test --lib`: the integration tests cannot reach it.)
#[cfg(test)]
mod finish_guard_tests {
    use super::*;

    #[test]
    fn a_panic_in_the_document_unit_is_the_engines_named_error() {
        std::panic::set_hook(Box::new(|_| {}));
        match finish_guarded(|| panic!("attempt to add with overflow")) {
            Outcome::Failed(Error::Internal(m)) => assert!(m.contains("overflow")),
            _ => panic!("a panic in the document unit is not an internal error"),
        }
        // a breach is a typed payload (kind, limit, actual): the bound in force is the override's, the name is kin's table's
        let breached = port::with_limits(&[(3, 10)], || {
            finish_guarded(|| {
                port::limit_breach(3, 11);
                Outcome::None
            })
        });
        match breached {
            Outcome::Failed(Error::Limit { name, limit, actual }) => assert_eq!((name.as_str(), limit, actual), ("objects", 10, 11)),
            _ => panic!("a limit breach in the document unit is not a named limit error"),
        }
        // the words of a message are nothing: a panic that only says so is `internal`
        match finish_guarded(|| panic!("the objects limit is 10 and this input reached 11. Raise it with EMON_LIMIT_OBJECTS")) {
            Outcome::Failed(Error::Internal(_)) => {}
            _ => panic!("a message is not a breach"),
        }
        assert!(matches!(finish_guarded(|| Outcome::None), Outcome::None));
    }
}
