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
