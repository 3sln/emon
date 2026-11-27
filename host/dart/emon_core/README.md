# emon_core

PDF and image to semantic HTML and JSON, over the [emon](https://github.com/3sln/emon) C library: a pure-Dart API with the shape of the JS SDK.

```dart
import 'dart:io';
import 'package:emon_core/emon_core.dart';

Future<void> main() async {
  final emon = await Emon.open();                       // no OCR engine: born-digital PDFs and images with text
  final model = await emon.convert(EmonInput.file('report.pdf'));
  File('report.html').writeAsStringSync(await model.html());
  await model.dispose();
  await emon.close();
}
```

This is the core: **no OCR engine and no model packs**. OCR engines are adapters (`emon_ocr_tesseract`, `emon_ocr_vision`, `emon_ocr_winocr`, `emon_ocr_ppocr`, and `emon_ocr_mlkit` for Android), or one you write in
Dart ([OcrAdapter]). The turnkey packages `emon` and `emon_flutter` choose adapters for the platform and fetch packs on demand.

* **Conversions run on a background isolate**: `convert`, `convertToHtml`, `convertToJson`, `intake`; a `Model` gives `json()`, `html()`, `evidence()`, `assets`, `transcript()`.
* **Typed options** (`ConvertOptions`, `HtmlOptions`), **errors by name** (`EmonError.name`: `password-required`, `input-bytes`, `bad-xref`, ...).
* **Engines and packs under app control** (DEVLOG D896): `emon.engines()`, `emon.prepare(options)`, `emon.packs.ensure(ids)`, `emon.packs.events`, and the `onPackNeeded` hook (fetch, provide, decline, substitute).
  Packs are downloaded with the platform's own HTTPS stack (`dart:io`), and every one is verified against a SHA-256 catalogue before an engine reads it.
* **A build hook** (`hook/build.dart`) fetches the prebuilt C library and pdfium for the target from the GitHub release and checks them against a SHA-256 table in the package. In a checkout, point it at a local
  directory (`bb package --host c`): `hooks: user_defines: emon_core: native_dir: <dir>`.

## On the web

The same API compiles for Dart web and Flutter web (conditional imports: the app's code does not change per platform). There the engine is emon's wasm build and the JS SDK (`@3sln/emon`), through
`dart:js_interop`; the app serves their files and says where (`WebAssets`):

```dart
final emon = await Emon.open(                          // or openEmon() of package:emon, EmonFlutter.open() of emon_flutter
  web: WebAssets(
    sdk: 'emon/sdk/',                                  // the `src/` of the `@3sln/emon` npm package
    wasm: 'emon/emon.wasm',                            // and its emon.wasm
    tesseract: WebTesseract(module: 'emon/vendor/tesseract/tesseract.esm.min.js', worker: 'emon/vendor/tesseract/worker.min.js',
        core: 'emon/vendor/tesseract/core', lang: 'emon/vendor/tesseract/lang'),   // tesseract.js: OCR (optional)
    pdfium: WebPdfium(module: 'emon/vendor/pdfium/pdfium.mjs', wasm: 'emon/vendor/pdfium/pdfium.wasm'),  // renders scans and figures (optional)
  ),
);
final model = await emon.convert(EmonInput.bytes(bytes));   // the bytes the browser gave you
```

* **Engines are what the JS SDK has**: tesseract.js and pdfium's WebAssembly build, when configured, plus engines you write in Dart (`OcrAdapter`; the JS port form carries their blocks, lines, words, word confidence
  and language, not line confidence or quads). `engines()` lists the rest (Apple Vision, Windows.Media.Ocr, ML Kit, PP-OCR) as unavailable, with the reason. Without `tesseract`, a page that needs OCR is unread, never blank.
* **No HTTP client in the library, and no packs**: tesseract.js loads its models from `lang`, a place the app serves; `packs.ensure` says `pack-fetch-unavailable`, `onPackNeeded` is not asked, `packs.mode` is `never`.
* **Inputs are bytes**: `EmonInput.file` fails with a `usage` error (no file system). The output is byte-identical to the SDK's (JSON, HTML, transcripts); `Emon.version` is `wasm`; an `EmonError`'s category is derived from its name.
* Get the files from the `@3sln/emon` package (`npm pack @3sln/emon`, or `bb dart --web-stage DIR` in a checkout).
