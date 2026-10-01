// emon, the Rust host (DEVLOG P1.10). The crate is ASSEMBLED by `kin/scripts/emon-rs-build` from
//
//     gen/rust/emon/*.rs    what kin generates (every non-test, non-probe module), declared by the
//                           `emon.rs` the script writes
//     host/rust/src/        this directory: `port.rs` (the ports kin calls), `plan.rs` (the plan/fulfil
//                           loop and the transcript), `source.rs` (byte sources), `engine.rs` (the API)
//
// and the library names no filesystem (D058): `source.rs` and `engine.rs` take bytes, a `Read + Seek` or a
// `Read`. `tess.rs` (the Tesseract adapter) and `testport.rs` (test support) are not part of it.
#[allow(warnings)]
#[doc(hidden)]
pub mod emon;
pub mod engine;
mod imgdec;
pub mod plan;
pub mod port;
pub mod source;
// the Tesseract adapter of the `Ocr` port (D184): live only, built under the `tesseract` feature (it links libtesseract)
#[cfg(feature = "tesseract")]
pub mod tess;

pub use engine::{Assets, Attr, Config, Dropped, Engine, Error, HtmlOptions, Intake, JsonOptions, Kind, Model, Node, Ocr, OcrPolicy, OcrPort, Options, Output, Page, Region, RunStats, Transcript};
pub use source::{ByteSource, BytesSource, Input, Inputs, SeekSource, SpoolSource};
