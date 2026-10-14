# emon for JavaScript (Node and the browser)

The npm package [`@3sln/emon`](https://www.npmjs.com/package/@3sln/emon): the Rust host compiled to WebAssembly and a small
SDK around it. It has no dependency. Install with `npm install @3sln/emon`. ESM only; types are in the package
(`src/index.d.ts`).

- **Node 22 or newer.** emon's module uses WebAssembly's standard exception handling, which Node below 25 still needs a flag
  for: run `node --experimental-wasm-exnref your.mjs`. Node 25 and newer need none.
- **Browsers**: Chrome 137, Firefox 131, Safari 18.4 or newer (exception handling, same reason). An older browser fails to
  compile the module (a `WebAssembly.CompileError`).
- The module imports nothing and touches no file, network or DOM: the SDK hands it the bytes it asks for.

## First conversion, in Node

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

`Emon.load` takes the module (`emon.wasm`, shipped in the package as `@3sln/emon/emon.wasm`) as bytes, a compiled
`WebAssembly.Module`, a `Response` or a URL; `emon.convert` takes the document and gives a `Model`; `model.html()` and
`model.json()` write it. **`model.dispose()` frees the Model's WebAssembly memory**: call it (or use `using model = ...`, since
`Model` has `[Symbol.dispose]`) when you are done with it. This program is run against the installed package by
`bb package --check`.

## In a browser

Serve `emon.wasm` as a static file and pass the fetch to `Emon.load`; hand `convert` the `File` or `Blob` as it is, and it
is read by range, never loaded whole:

<!-- unverified: runs in a browser. demo/worker.js is the same calls and `bb demo --check` runs it in headless Chrome, but that check is not in the fast core or the package gate -->
```js
import { Emon } from '@3sln/emon';

const emon = await Emon.load(fetch('/emon.wasm'));
fileInput.onchange = async () => {
  const model = await emon.convert(fileInput.files[0]);
  frame.srcdoc = model.html({ assets: 'inline' }); // an iframe: the HTML is a document, not a fragment to inject
  model.dispose();
};
```

Run the conversion in a Worker, as the [demo](https://emon.3sln.com/) does (`demo/worker.js`), so a big document does not
block the page.

## The shape of the API

| You | JavaScript |
|---|---|
| load | `const emon = await Emon.load(wasm, { chunk })` (`chunk`: the byte-source chunk size, default 64 KiB) |
| give a document | `convert(input)` and `intake(inputs)` take an `ArrayBuffer`, a typed array, a `Blob` or `File`, a URL (a `URL` or a string with a scheme: read by `Range` requests, the validator pinned), a `ReadableStream` (spooled into memory), or any `{ size, read(offset, length) -> Promise<Uint8Array> }`; with a password, `{ source, password }`. `convert` takes one document; `intake` takes one or an array |
| say what to do | the options object of `convert(input, options)`: `ocr`, `ocrScope`, `furniture`, `assets`, `assetDpi`, `assetPixels`, `assetBytes`, `assetsBytes`, `assetSignatures`, `locale`, `keepEvidence`, `maxRounds`, `fetchRounds`, `limits: { pages: 500 }`, `producer`, `lazy`, `ports`, and for transcripts `record` and `transcript` (the names of [docs/options.md](../options.md) in camelCase; there is no `parallel`: WebAssembly has no threads here) |
| read the result | `model.object()` is the JSON, parsed ([docs/model.md](../model.md)); `model.stats`; `model.assets` (`Asset`: `index`, `name`, `type`, `size`, `bytes()`, `blob()`) |
| write the result | `model.html(options)`, `model.json({ pretty })`, `model.htmlWithBlobURLs(options)` (assets as `blob:` URLs, with `revoke()`), `model.evidence()` |
| route on a failure | `e instanceof EmonError`: `e.code` is the stable name; a source that changed or came up short is `SourceChangedError` (`code` `source-changed`) |

The adapters of the sources are exported too: `bytesSource(buf, { lazy })`, `blobSource(blob)`, `urlSource(url, { fetch, headers,
signal, fetchOptions })`, `streamSource(stream, { max })`, `toSource(x)`. An in-memory source is handed to the engine whole; a
`Blob` or a URL is read a chunk at a time, only where the engine looks, with read-ahead that doubles each round.

## A tour

<!-- snippet: docs/snippets/js/tour.mjs -->
```js
import { readFile } from 'node:fs/promises';
import { Emon, EmonError } from '@3sln/emon';

const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));
const pdf = await readFile('report.pdf');

// An input is a byte source: bytes, a Blob or File, a URL (read by Range requests), a stream, or your own { size, read }.
// A Blob is read lazily, a chunk at a time, only where the engine looks. Stage 0 alone is cheap: format, version, pages, encryption.
for (const intake of await emon.intake(new Blob([pdf]))) console.log(intake.line);

try {
  // The options are an object; limits are named as in docs/options.md.
  const model = await emon.convert(pdf, { ocr: 'never', limits: { pages: 500 } });
  // Walk the Model: every heading, with its page.
  const walk = function* (n) { yield n; for (const c of n.children ?? []) yield* walk(c); };
  for (const n of walk(model.object().body)) {
    if (n.kind === 'heading') console.log(`page ${n.at?.[0]?.[0] ?? 0}: ${n.text}`);
  }
  console.log(`${model.object().pages.length} pages, ${model.stats.requests} port requests`);
  model.dispose();
} catch (e) {
  if (!(e instanceof EmonError)) throw e;
  // e.code is the stable name to route on: "password-required", "pages", "bad-xref", ...
  console.error(`${e.code}: ${e.message}`);
  process.exit(1);
}
```

`model.object()` is the tree of [docs/model.md](../model.md): `body.children`, each node with `kind`, `text`, `at` (points, not
centipoints), `src`, `conf`. Limits are in `limits` (`limit.pages=500` in the text form).

## Pages that need drawing and reading: pdfium and Tesseract

The module draws nothing and reads no scan. A page that needs it asks for a *render* or an *OCR reading*, and the SDK answers
from the ports you give it in `ports`; with none, the request is answered `unsupported` and the text layer stands alone, so a
born-digital PDF needs neither. Two adapters ship in the package, and you hand them the engines (neither engine is a dependency
of `@3sln/emon`, so the code you do not use is never downloaded):

- `@3sln/emon/pdfium`: `pdfiumRenderer({ module, wasm, maxPixels })`. **With no `module`, it uses the npm package
  [`@3sln/pdfium-wasm`](https://www.npmjs.com/package/@3sln/pdfium-wasm)** (`npm install @3sln/pdfium-wasm`): pdfium's
  WebAssembly build, `pdfium.mjs` and `pdfium.wasm`, with pdfium's licences. It is an *optional peer dependency*: `@3sln/emon`
  itself has no dependency, and imports the package only on the first render, so a document that asks for no raster never loads
  it (5 MiB of WebAssembly). Its version is emon's. In Node and in a bundler (which must resolve the `new URL('./pdfium.wasm',
  import.meta.url)` inside the package) `pdfiumRenderer()` is all there is to it; a browser page that does not bundle serves
  the two files itself and passes them, as below. Or pass your own build: `module` is pdfium's factory (a function, a promise, or
  `() => import('./pdfium.mjs')`, called once, on the first render); `wasm` is `pdfium.wasm` as bytes, a URL or a `Response`.
  `maxPixels` bounds a page (default 64 Mpx, lower than the native 268 Mpx). The build is pdfium's WebAssembly build from
  [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases) (`chromium/8076`, the archive
  `pdfium-wasm.tgz`), whose `pdfium.js` is a classic script: emon's adapter wants it wrapped as an ES module,
  `export default function pdfium(Module, node) { ... }` around the same text, which is what the package holds. In a clone of
  this repository, `bb pdfium-fetch --platform wasm` downloads the archive, checks its hash and writes
  `.cache/pdfium/chromium-8076/wasm/lib/pdfium.mjs` and `pdfium.wasm` (and `bb package` makes the npm package from them).
  Reading the file whole once, on the first render, is the one place the SDK does not read by range. `render.terminate()`
  frees it.
- `@3sln/emon/tesseract`: `tesseractPort({ createWorker, languages, oem, workerOptions, workers })`. `createWorker` is
  [tesseract.js](https://github.com/naptha/tesseract.js)'s (`npm install tesseract.js`); `languages` the models (default `['eng']`);
  `workerOptions` is tesseract.js's (`langPath`, `corePath`, `workerPath`, `gzip`, `cacheMethod`), which is where the models and
  the engine's WebAssembly are found. `ocr.terminate()` frees the workers.

In Node, after `npm install @3sln/pdfium-wasm tesseract.js @tesseract.js-data/eng` (the English model; a `langPath` of your own
works as well), this converts a scan. `bb package --check` runs it against the built packages on a machine that has pdfium's
WebAssembly build (`bb pdfium-fetch --platform wasm`) and the `npm ci` of `host/js` done, and prints a NOTE and skips it on one that
does not, as a CI runner does:

<!-- snippet: docs/snippets/js/native.mjs -->
```js
import { readFile, writeFile } from 'node:fs/promises';
import { createWorker } from 'tesseract.js';
import { Emon } from '@3sln/emon';
import { pdfiumRenderer } from '@3sln/emon/pdfium';
import { tesseractPort } from '@3sln/emon/tesseract';

const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));

// pdfium's WebAssembly build: with no arguments, the adapter imports the package @3sln/pdfium-wasm (npm install @3sln/pdfium-wasm) on the first render
const render = pdfiumRenderer();

// tesseract.js, with the English model from the npm package @tesseract.js-data/eng
const ocr = tesseractPort({
  createWorker,
  languages: ['eng'],
  workerOptions: { langPath: './node_modules/@tesseract.js-data/eng/4.0.0_best_int', gzip: true, cacheMethod: 'none' },
});

const model = await emon.convert(await readFile('scan.pdf'), { ports: { render, ocr } });
await writeFile('scan.html', model.html());
model.dispose();
await render.terminate();
await ocr.terminate();
```

In a browser that does not bundle, serve the package's two files (`node_modules/@3sln/pdfium-wasm/pdfium.mjs` and `pdfium.wasm`) and
the same two calls take URLs: `module: () => import('/vendor/pdfium/pdfium.mjs')`, `wasm: '/vendor/pdfium/pdfium.wasm'`,
and `workerOptions: { workerPath, corePath, langPath, gzip: true, workerBlobURL: false }` pointing at tesseract.js's files you
host. `demo/worker.js` is the worked example, including the fall-back to OCR on the page's thread where a Worker cannot start
Workers of its own. To bring your own engine, a renderer is `{ open(doc, { pixels }) -> { render({ page, dpi, layer }) -> { width,
height, rgb }, close() } }` and an OCR port is `async (request) => result`; see `src/index.d.ts` and the headers of
`src/pdfium.js` and `src/ocr.js`. JBIG2 and JPEG 2000 images are not decoded.

## Notes

- A conversion is asynchronous: the engine runs until it needs bytes or a port answer, says what, the SDK awaits it, and the engine
  runs again. The output is byte for byte the native hosts'.
- `model.json()` and `model.html()` are strings; `model.object()` is the JSON, parsed.
- The WebAssembly instance, a Model's handle and the constructors of `Emon`, `Model` and `Asset` are not public API.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the js host: 55 entries (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `@3sln/emon/pdfium::pdfiumRenderer` | `function({ module, wasm, maxPixels = 64 * 2 ** 20 } = {})` |
| `@3sln/emon/tesseract::tesseractPort` | `function({ createWorker, languages = ['eng'], oem = 1, workerOptions = {}, workers = 1 })` |
| `@3sln/emon::Asset` | `class` |
| `@3sln/emon::Asset#blob` | `()` |
| `@3sln/emon::Asset#bytes` | `()` |
| `@3sln/emon::Asset#index` | `property` |
| `@3sln/emon::Asset#name` | `property` |
| `@3sln/emon::Asset#size` | `property` |
| `@3sln/emon::Asset#type` | `property` |
| `@3sln/emon::Emon` | `class` |
| `@3sln/emon::Emon#convert` | `async (input, options = {})` |
| `@3sln/emon::Emon#defaults` | `property` |
| `@3sln/emon::Emon#intake` | `async (inputs, options = {})` |
| `@3sln/emon::Emon.load` | `static async (wasm, defaults = {})` |
| `@3sln/emon::EmonError` | `class extends Error` |
| `@3sln/emon::EmonError#code` | `property` |
| `@3sln/emon::EmonError#constructor` | `(code, message)` |
| `@3sln/emon::EmonError#name` | `property` |
| `@3sln/emon::Model` | `class` |
| `@3sln/emon::Model#[Symbol.dispose]` | `()` |
| `@3sln/emon::Model#assets` | `get accessor` |
| `@3sln/emon::Model#dispose` | `()` |
| `@3sln/emon::Model#evidence` | `()` |
| `@3sln/emon::Model#html` | `(o = {})` |
| `@3sln/emon::Model#htmlWithBlobURLs` | `(o = {})` |
| `@3sln/emon::Model#json` | `({ pretty = false } = {})` |
| `@3sln/emon::Model#object` | `()` |
| `@3sln/emon::Model#stats` | `property` |
| `@3sln/emon::Model#transcript` | `()` |
| `@3sln/emon::SourceChangedError` | `class extends Error` |
| `@3sln/emon::SourceChangedError#code` | `property` |
| `@3sln/emon::SourceChangedError#constructor` | `(message)` |
| `@3sln/emon::SourceChangedError#name` | `property` |
| `@3sln/emon::blobSource` | `function(blob)` |
| `@3sln/emon::bytesSource` | `function(buf, { lazy = false } = {})` |
| `@3sln/emon::streamSource` | `async function(stream, { max = 2 ** 31 - 1 } = {})` |
| `@3sln/emon::toSource` | `async function(x, opts = {})` |
| `@3sln/emon::urlSource` | `async function(url, { fetch: f = globalThis.fetch.bind(globalThis), headers = {}, signal, fetchOptions = {} } = {})` |
| `@3sln/pdfium-wasm package.json#exports[./package.json]` | `./package.json` |
| `@3sln/pdfium-wasm package.json#exports[./pdfium.mjs]` | `./pdfium.mjs` |
| `@3sln/pdfium-wasm package.json#exports[./pdfium.wasm]` | `./pdfium.wasm` |
| `@3sln/pdfium-wasm package.json#exports[.]` | `default=./index.js,types=./index.d.ts` |
| `@3sln/pdfium-wasm package.json#main` | `index.js` |
| `@3sln/pdfium-wasm package.json#name` | `@3sln/pdfium-wasm` |
| `@3sln/pdfium-wasm::default` | `async function(Module, node)` |
| `@3sln/pdfium-wasm::wasmBinary` | `async function()` |
| `@3sln/pdfium-wasm::wasmUrl` | `value` |
| `package.json#exports[./emon.wasm]` | `./emon.wasm` |
| `package.json#exports[./package.json]` | `./package.json` |
| `package.json#exports[./pdfium]` | `./src/pdfium.js` |
| `package.json#exports[./tesseract]` | `./src/tesseract.js` |
| `package.json#exports[.]` | `./src/index.js` |
| `package.json#main` | `src/index.js` |
| `package.json#name` | `@3sln/emon` |
| `package.json#peerDependencies[@3sln/pdfium-wasm]` | `optional` |

</details>
<!-- bb docs:end api-index -->
