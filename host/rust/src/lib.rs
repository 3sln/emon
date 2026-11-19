// emon, the Rust host (DEVLOG P1.10). The crate is ASSEMBLED by `kin/scripts/emon-rs-build` from
//
//     gen/rust/emon/*.rs    what kin generates (every non-test, non-probe module), declared by the
//                           `emon.rs` the script writes
//     host/rust/src/        this directory: `port.rs` (the ports kin calls), `plan.rs` (the plan/fulfil
//                           loop and the transcript), `source.rs` (byte sources), `engine.rs` (the API)
//     host/wasm/src/        `wasm.rs`, the browser's C ABI (only in the wasm build; DEVLOG P9.3)
//
// and the library names no filesystem (D058): `source.rs` and `engine.rs` take bytes, a `Read + Seek` or a
// `Read`. `tess.rs` (the Tesseract adapter) and `testport.rs` (test support) are not part of it.
#[allow(warnings)]
#[doc(hidden)]
pub mod emon;
// Assembly switches this only for the native C/pod hosts (OP2a).
pub(crate) const PPOCR_NATIVE_HOST: bool = false;
pub mod engine;
mod imgdec;
// the PDF image codecs of the `decode-img` request (P6.1a): DCT and CCITT here, JPX and JBIG2 through the embedder's `PdfCodecs`;
// `pdfcodecs` is OpenJPEG and jbig2dec behind it, live only (`--cfg codecs`)
#[cfg(codecs)]
pub mod pdfcodecs;
pub mod pdfimg;
#[cfg(pdfium)]
pub mod pdfium;
// parallel execution (P10.2, D505): a pool of threads that fulfils a round's requests at once and the pages ahead of the loop's; the browser has none
#[cfg(not(target_arch = "wasm32"))]
pub mod par;
// the native OCR adapters (host/c/include/emon_adapter.h) and the engine registry (K2, D899): loading, routing, answering later; and the model packs they read
// with: found, verified against the catalogue's SHA-256, fetched (feature `packs-fetch`). Kin decides (`emon.adapters`); these are the mechanics. A build for the
// browser carries them and can load nothing.
pub mod adapter;
pub mod packs;
pub mod plan;
pub mod port;
pub mod render;
// the step session both bindings share (D481): the inverted API's state machine over `engine::Run`, so `wasm.rs` and `capi.rs` are only ABI
pub mod session;
pub mod source;
// the browser's binding (P9.3): a C ABI over the engine's `Run` for the JS SDK (`host/js/`); assembled in by
// `kin/scripts/emon-wasm-build` from `host/wasm/src/wasm.rs`, which the native crate never has
#[cfg(target_arch = "wasm32")]
pub mod wasm;
// the Tesseract adapter of the `Ocr` port (D211): live only, built under the `tesseract` feature (it links libtesseract)
#[cfg(feature = "tesseract")]
pub mod tess;
// the same Tesseract as a native adapter LIBRARY (K2, D899): `libemon_ocr_tesseract` exports `emon_ocr_adapter_v1` (emon_adapter.h). Assembled in only by the adapter's build.
#[cfg(feature = "tesseract-adapter")]
pub mod tess_adapter;

// Apple Vision as an `Ocr` port (K8, D901): macOS and iOS, built under the `vision` feature with the shim of host/vision linked in (tools/native/vision.clj), and as a native
// adapter LIBRARY, `libemon_ocr_vision` (`vision-adapter`).
#[cfg(any(feature = "vision", all(feature = "winocr", windows)))]
mod platform;
#[cfg(feature = "vision")]
pub mod vision;
#[cfg(feature = "vision-adapter")]
pub mod vision_adapter;
// Windows.Media.Ocr (K8, D901), through the `windows` crate; Windows only. The adapter library is `libemon_ocr_winocr` (`winocr-adapter`).
#[cfg(all(feature = "winocr", windows))]
pub mod winocr;
#[cfg(all(feature = "winocr-adapter", windows))]
pub mod winocr_adapter;

pub use engine::{is_option_key, parse_options, Asset, AssetFile, AssetUse, Assets, Attr, Config, Dropped, Engine, Error, Furniture, HtmlAssets, HtmlOptions, Intake, JsonOptions, Kind, Model, Node, Ocr, OcrEngine, OcrPolicy, OcrPort, OcrScope, Options, Output, Page, Parsed, Region, RunStats, Transcript};
pub use pdfimg::{CodecFail, PdfCodecs, Pix};
pub use render::{Clip, KindMux, RenderError, RenderFulfiller, RenderInput, RenderRequest, Renderer};
pub use source::{ByteSource, BytesSource, Input, Inputs, SeekSource, SpoolSource};

// Optional OP1 reference only; normal builds carry no ORT dependency.
#[cfg(ppocr)]
pub mod ppocr;
#[cfg(ppocr)]
mod ppocr_ctc;
#[cfg(ppocr)]
mod ppocr_image;
#[cfg(ppocr)]
mod ppocr_pipeline;
#[cfg(ppocr)]
mod ppocr_runtime;
