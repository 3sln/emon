# Experimental: PP-OCR engine

PP-OCRv5 is an experimental opt-in for Latin script, qualified on macOS arm64.
Tesseract fast remains the default. Linux x64/arm64, macOS x64 and Windows x64
archives are built and smoke-tested on their own CI runners; they have not been
verified locally on the qualifying machine. They are not a broader quality claim.
The pinned macOS arm64 ORT binary requires macOS 13.3 or newer.

Download `emon-ppocr-<version>-<triple>.tar.gz` (`.zip` on Windows) from the
same GitHub release as your CLI or library. Verify its entry in the release's
`SHA256SUMS`, then extract it somewhere permanent. The five triples are
`aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`,
`aarch64-unknown-linux-gnu` and `x86_64-pc-windows-msvc`.
The add-on contains ORT 1.22.0 CPU, the versioned emon PP C ABI adapter,
three pinned ONNX graphs, English and orientation dictionaries, the pinned
English lexicon, and licence texts. It is separate from every default archive.
Nothing downloads automatically at conversion time.

## Native setup

On macOS, set these variables to the extracted add-on directory (replace `/opt/pp`
with your own absolute path):

```sh
export EMON_PPOCR_LIB=/opt/pp/lib/libemon_ppocr_adapter.dylib
export EMON_PPOCR_MODELS=/opt/pp/models
export TESSDATA_PREFIX=/opt/pp/models
emon convert --ocr-engine=ppocr input.png
```

On Linux use `libemon_ppocr_adapter.so` for `EMON_PPOCR_LIB`. On Windows,
set `EMON_PPOCR_LIB` to the absolute path of `lib\emon_ppocr_adapter.dll`,
`EMON_PPOCR_MODELS` to `models`, and `TESSDATA_PREFIX` to `models` in your
process environment. Keep the ORT shared libraries beside the adapter; the
loader resolves them there. macOS archives are unsigned; see [CLI setup](cli.md).
Use the ordinary CLI archive alongside this add-on: it supplies the incumbent
Tesseract engines, orientation data and PDF renderer. `TESSDATA_PREFIX` above
selects the add-on's pinned English lexicon for the adapter only; pass the CLI's
`--tessdata` explicitly if you need another incumbent language configuration.

The C library and babashka pod use the same environment variables and
`ocr-engine=ppocr` conversion option. Use their native-capability builds.
Rust embedders can wrap their incumbent with `emon::ppocr_native::PpOcr::open`
and set the typed `Options::ocr_engine` to `OcrEngine::PpOcr`; the existing
`tesseract` feature supplies the incumbent, with no ORT link dependency.
Go, Java and C# gain no required package dependency. Set the variables before
starting your application, then wrap its incumbent OCR port with Go `NewPpOcr`
(cgo and build tag `ppocr`), Java `emon.ffi.PpOcr.open` (JDK 22+ FFM with native
access enabled), or C# `emon.Ffi.PpOcr.Open`. Supply the model directory to that
wrapper if your API takes it, select the PP engine in the conversion options,
and close/dispose the wrapper after conversions finish. Retain the incumbent
Tesseract port: PP adds a reading to it. See the [native implementation guide](../tools/ppocr/README.md).
The Go wrapper currently supports cgo on macOS and Linux; its Windows stub
retains `ppocr-runtime-missing`. Java and C# users on Windows should also add
the add-on's `lib` directory to the process `PATH` before startup, so their
host loaders can resolve ORT's dependent DLLs.
Missing models report `ppocr-models-missing`; an unavailable adapter or ORT
reports `ppocr-runtime-missing`. Replaying a PP recording requires its PP
selector but no models, libraries or inference.

## JavaScript and npm

The optional package is `@3sln/emon-ppocr`; `@3sln/emon` retains its default
wasm and has no ORT dependency or bundled PP graphs. Install the optional
package explicitly, plus exactly `onnxruntime-web@1.22.0` and
`tesseract.js-core@7.0.0`. Import the PP port from `@3sln/emon-ppocr` and use
its exported `./emon.wasm` for both `Emon.load` and the port. Supply your own
ORT loader, runtime paths and model URLs or bytes. Model files can be served
from the extracted release add-on at a URL you control; the adapter verifies
their SHA-256 pins. GitHub release URLs may require a same-origin proxy for
browser CORS. There is no default CDN or automatic model acquisition.
See [browser and Node configuration](ppocr-web.md) for the full example.

## Size, memory and measured tradeoffs

The ONNX graphs total 13,277,459 bytes uncompressed; the pinned English
lexicon adds 4,113,088 bytes. Native runtime and adapter sizes differ by
platform; the build prints the exact compressed download size for each
archive. The locally built macOS arm64 add-on is about 25 MiB compressed.
The other four archive sizes are reported by their CI builds and have not
been measured locally. Budget tens of MiB for download and more for extraction.
For JS, ORT wasm adds 11,210,254 bytes plus its loader, optional emon wasm
about 6 MiB, and the dedicated dictionary core 2,857,601 bytes.
Full native qualification peaked at 2.17 GiB process RSS; the Node raw-port
sweep peaked at 485 MiB, excluding incumbent OCR and full conversion.
Neither is a memory bound. Image size, sessions and workspaces matter.

Receipt recall improves, with precision and CPU tradeoffs. The frozen fixed
receipt candidate improved SROIE precision/recall from .682075/.704562 to
.701436/.756857 and CORD from .422360/.562829 to .469098/.696764.
Both recall floors passed; both precision floors failed.
Net matched-word gains were 368/1156 and 178/394 of the registered targets.
Five single-page raster controls exceeded 3× incumbent CPU; the worst was
13.42×. These failed acceptance criteria remain visible: experimental
shipping does not make PP the default or establish comprehensive correctness.

PP never substitutes for born-digital text. All eight required born-digital
qualification bundles, including evidence, remained byte-identical. The PP
reading enters the existing guarded fusion: calibrated integer confidence,
format and numeric protection, independent agreement or dictionary support,
no incumbent deletion, bounded sum closure and competing-candidate refusal.
It can still miss words or insert incorrect text. The engine is Latin-script
only; English calibration and lexicon do not establish other-language quality.
macOS arm64 is the quality qualification platform. Browser and Node each
matched all 225 native raw answers there; other native targets remain CI
portability checks, not receipt-quality qualification.

## Building and checking a release

`bb native` prepares pinned static Tesseract and codecs. `bb cli --check`
builds the ordinary CLI. `bb ppocr-addon --fetch --check` builds the separate
archive, extracts it into a fresh worktree-local directory, loads it through
the CLI, converts the committed fixture and compares with the committed
reference. `bb package --host ppocr --check` uses the same builder and smoke;
`bb package --host js-ppocr --check` builds and imports the optional npm package.
The ordinary `bb package --check` does not fetch optional PP assets.

`native.yml` runs the add-on smoke on all five runners, uploads it alongside
the CLI archive, and `verify.yml` collects all five through `emon/archives`.
`bb release-ops archive` requires those assets, writes `SHA256SUMS`, and
`bb release-ops attach --dry-run` validates the set before describing upload.
Nothing in this work publishes a package or release.
