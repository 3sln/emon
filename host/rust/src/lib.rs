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
pub mod engine;
mod imgdec;
// the PDF image codecs of the `decode-img` request (P6.1a): DCT and CCITT here, JPX and JBIG2 through the embedder's `PdfCodecs`;
// `pdfcodecs` is OpenJPEG and jbig2dec behind it, live only (`--cfg codecs`)
#[cfg(codecs)]
pub mod pdfcodecs;
pub mod pdfimg;
#[cfg(pdfium)]
pub mod pdfium;
pub mod plan;
pub mod port;
pub mod render;
pub mod source;
// the browser's binding (P9.3): a C ABI over the engine's `Run` for the JS SDK (`host/js/`); assembled in by
// `kin/scripts/emon-wasm-build` from `host/wasm/src/wasm.rs`, which the native crate never has
#[cfg(target_arch = "wasm32")]
pub mod wasm;
// the Tesseract adapter of the `Ocr` port (D211): live only, built under the `tesseract` feature (it links libtesseract)
#[cfg(feature = "tesseract")]
pub mod tess;

pub use engine::{Asset, AssetFile, AssetUse, Assets, Attr, Config, Dropped, Engine, Error, Furniture, HtmlAssets, HtmlOptions, Intake, JsonOptions, Kind, Model, Node, Ocr, OcrPolicy, OcrPort, OcrScope, Options, Output, Page, Region, RunStats, Transcript};
pub use pdfimg::{CodecFail, PdfCodecs, Pix};
pub use render::{Clip, KindMux, RenderError, RenderFulfiller, RenderInput, RenderRequest, Renderer};
pub use source::{ByteSource, BytesSource, Input, Inputs, SeekSource, SpoolSource};
