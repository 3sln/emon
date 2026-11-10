# Experimental PP-OCR in JavaScript

PP-OCR is optional and off by default. The browser and Node adapter uses the
native Rust image preparation, DB geometry, orientation, CTC decoder, frozen
calibration and integer answer builder. Only ORT inference crosses into
JavaScript. Dictionary lookups call the pinned Tesseract wasm directly through
a small wasm shim using separate memories. The native fusion policy
and its documented receipt precision and CPU limitations remain unchanged.

Build the optional module from the repository root:

```sh
EMON_CS_SHARED_COMPILATION=false EMON_WASM_PPOCR=1 kin/scripts/emon-wasm-build
```

Install `host/js` development dependencies first. WABT 1.0.39 compiles the two
small `.wat` framing shims at build time; it is not a runtime dependency.
The optional module additionally requires WebAssembly multi-memory support.

Use the resulting `.cache/emon-wasm-ppocr/target/wasm32-unknown-unknown/release/emon.wasm`
for both `Emon.load` and the optional port. The ordinary build continues to
reject `ocr-engine=ppocr`. Node needs the same exception-handling support as
emon's existing wasm binding (`--experimental-wasm-exnref` on Node 24.6).

Install exactly `onnxruntime-web@1.22.0` and `tesseract.js-core@7.0.0` in your
application. Host their runtime files yourself. Pass model URLs or bytes for
the three filenames in `tools/ppocr/assets.edn`; the adapter checks their
SHA-256 pins before creating inference sessions. It has no model, runtime or
third-party CDN URL defaults. URLs are fetched only when explicitly supplied.

```js
import { Emon } from '@3sln/emon';
import { ppocrPort, ppocrLexicon } from '@3sln/emon/ppocr';

// Supplied by the application:
// optionalWasm: compiled WebAssembly.Module, bytes, or a hosted URL
// incumbent: the existing tesseractPort(...) fulfiller
// createCore: tesseract-core-simd-lstm.wasm.js's factory
// coreOptions: local locateFile or wasmBinary for that factory
// englishData: pinned tessdata_fast eng.traineddata URL or bytes
// modelSources: { 'ch_PP-OCRv5_det_mobile.onnx': URL_or_bytes, ... }
const lexicon = ppocrLexicon({ createCore, coreOptions, traineddata: englishData });
const ocr = ppocrPort({
  incumbent, wasm: optionalWasm, lexicon,
  loadOrt: () => import('onnxruntime-web/wasm'),
  wasmPaths: new URL('/vendor/ort-1.22.0/', location.href).href,
  ortWasm: new URL('/vendor/ort-1.22.0/ort-wasm-simd-threaded.wasm', location.href),
  models: modelSources,
});
const emon = await Emon.load(optionalWasm);
const model = await emon.convert(input, {
  ocrEngine: 'ppocr', ports: { ocr }, record: true,
  producer: ocr.producer,
});
// model.transcript() uses the existing portable recording format.
// Replay with ocrEngine: 'ppocr', transcript: files needs no OCR port or assets.
model.dispose();
await ocr.terminate();
```

In Node, supply filesystem bytes for the wasm modules, models and traineddata,
and a filesystem directory for `wasmPaths`; the adapter does not read files.
The Node and browser qualification drivers provide executable examples.
The dedicated dictionary instance never recognizes or learns document words.
Ordinary OCR requests continue to use the incumbent fulfiller.

ORT and its three sessions load on the first PP request. The configuration is
fixed to the hash-pinned ORT 1.22.0 ordinary SIMD wasm build, one thread,
sequential execution and graph optimization `all`. The distributed filename
contains `threaded`; `numThreads=1` prevents worker threads. Relaxed SIMD,
GPU providers and automatic runtime selection are not used.
Supply an ORT module/context whose backend has not been initialized by other
sessions; its wasm configuration flags are global to that module.

The three model downloads total 13,277,459 bytes. ORT wasm adds 11,210,254
bytes plus its JavaScript loader. The optional emon module is about 6 MiB;
the dedicated dictionary additionally needs the 4,113,088-byte English data
and the 2,857,601-byte Tesseract core, alongside any incumbent OCR resources.
These are uncompressed asset sizes, not total resident memory. Session
weights, tensor workspaces, decoded RGB images and two emon instances increase
memory use; it depends on page size. Default consumers do not import ORT or
download these models. Model weights retain Apache-2.0 licensing; ORT is MIT
(see `release/licenses/native-onnxruntime-1.22.0.txt` and its notices).

On macOS arm64 with Node 24.6.0, all 225 OP1c raw port answers were
byte-identical to the qualified native answers, whose hashes reproduced
OP1c. This single-process sweep peaked at 485 MiB RSS, measured by Node's
`resourceUsage().maxRSS`. It includes the PP pipeline, ORT sessions and
dedicated dictionary, and excludes the incumbent OCR worker and full emon
conversion. It is not a browser memory measurement or a per-conversion bound.
Chrome could not launch in the task sandbox; browser qualification remains
a coordinator check.

Reproduce the optional live comparison after preparing the pinned assets and
building the native oracle with `bb tools/ppocr/build_shared.clj`:

```sh
EMON_CS_SHARED_COMPILATION=false bb tools/ppocr/web_qualify.clj --out EXTERNAL-DIR
node --experimental-wasm-exnref tools/ppocr/web_browser.mjs EXTERNAL-DIR
EMON_CS_SHARED_COMPILATION=false bb tools/ppocr/web_qualify.clj --out EXTERNAL-DIR --compare-only true --candidate browser
```

The integrated recording/replay test additionally needs pinned English and OSD
traineddata (`bb tessdata-fetch --langs eng,osd`). Run
`EMON_CS_SHARED_COMPILATION=false bb tools/ppocr/web_interop.clj NEW-EXTERNAL-DIR`.
It records through each host and replays complete bundles on both hosts while
all three PP models and both traineddata files are temporarily absent, restoring
and checking every hash afterward.

The comparison checks all 225 retained OP1c qualification bitmaps and verifies
the oracle's answer hashes against OP1c. Detailed field differences stay in
external scratch. Qualification is required before release activation; a
kernel difference does not authorize changing the native integer policy.
