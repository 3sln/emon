# @3sln/pdfium-wasm

[pdfium](https://pdfium.googlesource.com/pdfium/)'s WebAssembly build, packaged for [emon](https://github.com/3sln/emon): the
renderer behind `@3sln/emon/pdfium`, which draws the pages emon has to read as pictures (scans, figures) in the browser and in
Node. It is **not emon's code**: it is the `pdfium-wasm` build of
[bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) `chromium/8076` (pdfium 156.0.8076.0), unchanged, with
one wrapper: that build's `pdfium.js` is a classic script, and `pdfium.mjs` is the same text inside
`export default function pdfium(Module, node) { ... }`, so it can be imported.

    npm install @3sln/emon @3sln/pdfium-wasm

`@3sln/emon` finds this package by itself. With nothing else to pass, `pdfiumRenderer()` imports it on the first render:

```js
import { Emon } from '@3sln/emon';
import { pdfiumRenderer } from '@3sln/emon/pdfium';

const render = pdfiumRenderer();                       // imports @3sln/pdfium-wasm lazily
const model = await emon.convert(scan, { ports: { render } });
await render.terminate();
```

Without emon, the package gives the factory and the URL of the module:

```js
import pdfium, { wasmBinary } from '@3sln/pdfium-wasm';
const m = await pdfium({ wasmBinary: await wasmBinary() });
```

| File | |
|---|---|
| `index.js` | `export default` the factory; `wasmUrl`, the URL of `pdfium.wasm`; `wasmBinary()`, its bytes (read from disk in Node, fetched in a browser) |
| `pdfium.mjs` | the factory (`./pdfium.mjs`) |
| `pdfium.wasm` | the build (5.3 MB) (`./pdfium.wasm`) |
| `LICENSE`, `licenses/` | pdfium-binaries' licence and the licences of pdfium and the libraries inside it (abseil, AGG, fast_float, FreeType, ICU, Little CMS, libjpeg-turbo, OpenJPEG, libpng, simdutf, zlib) |

The package's version is emon's version, not pdfium's: the one pdfium revision it holds (`chromium/8076`) is in `VERSION` and in
`package.json`'s description, and a new emon release publishes the package again even when the build did not change.
