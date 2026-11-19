# emon for Dart and Flutter

A pure-Dart API over the [C library](c.md), shaped like the [JS SDK](js.md), with prebuilt libraries fetched by Dart build hooks (Dart 3.10 / Flutter 3.38 or newer). Targets: macOS, Linux, Windows,
Android (arm64, armv7, x86_64) and iOS (device, Apple-silicon simulator). Web is not covered.

| package | what it is |
|---|---|
| `emon_core` | the API, the C library and pdfium (a build hook), engines, packs. **No OCR engine.** MIT only |
| `emon_ocr_tesseract`, `emon_ocr_ppocr`, `emon_ocr_vision`, `emon_ocr_winocr` | one OCR adapter library each, bundled by a hook; depend only on `emon_core` |
| `emon_ocr_mlkit` | ML Kit text recognition, an Android Flutter plugin |
| `emon` | the pure-Dart turnkey: `emon_core` + the platform's adapters, packs fetched on demand |
| `emon_flutter` | the Flutter turnkey: `emon` + ML Kit on Android + a `ChangeNotifier` for pack downloads |

## First conversion

<!-- snippet: docs/snippets/dart/quickstart.dart -->
```dart
import 'dart:io';

import 'package:emon_core/emon_core.dart';

Future<void> main() async {
  final emon = await Emon.open();
  final model = await emon.convert(EmonInput.file('report.pdf'));
  File('report.html').writeAsStringSync(await model.html());
  await model.dispose();
  await emon.close();
}
```

Conversions run on a background isolate. A `Model` gives `json()`, `html()`, `evidence()`, `assets` and, with `ConvertOptions(record: true)`, `transcript()` (replayable on any host with `ConvertOptions(transcript: ...)`).
Errors are `EmonError`, with the stable `name` to route on (`password-required`, `input-bytes`, `transcript-miss`, ...).

## OCR out of the box

<!-- snippet: docs/snippets/dart/native.dart -->
```dart
import 'dart:io';

import 'package:emon/emon.dart';

Future<void> main() async {
  // the turnkey: Tesseract through its adapter library, pdfium, and the packs it reads with (fetched on first use; here they are already beside the app)
  final emon = await openEmon(packs: PackMode.never);
  final html = await emon.convertToHtml(EmonInput.file('scan.pdf'));
  File('scan.html').writeAsStringSync(html);
  await emon.close();
}
```

`openEmon` (package `emon`) and `EmonFlutter.open` (package `emon_flutter`) register the adapters of the platform and fetch packs on demand. The default engine is Windows.Media.Ocr on Windows and Tesseract elsewhere (kin's turnkey table; Apple Vision becomes Apple's default with P13.21b);
Apple Vision, Tesseract on Windows and PP-OCR are chosen with `ConvertOptions(ocrEngine: ...)`. `emon_flutter` makes ML Kit the Android default (kin's table says the same for Android) (`emon_ocr_mlkit`: Latin bundled; Chinese, Japanese, Korean and Devanagari
by `emonMlkitScripts` in `gradle.properties`) and falls back to Tesseract when it cannot run.

## Engines and packs are the app's to steer (DEVLOG D896)

<!-- unverified: shown for shape; the package's own tests (host/dart/emon_core/test/packs_test.dart) run each call against real engines -->
```dart
final engines = await emon.engines();                      // every engine: built-in, installed, downloadable (size, licence) or unavailable (reason)
final plan = await emon.prepare(const ConvertOptions(locale: 'de-DE'));   // the packs a conversion needs, and which are missing
await emon.packs.ensure(plan.missingPackIds, onProgress: print);          // fetch ahead of time; cancel with a CancelToken
emon.packs.events.listen(print);                           // every download: the library's, the app's, ML Kit's through Play services
// onPackNeeded: for each pack a conversion lacks the app answers fetch, provide (bytes or a path), decline or substitute
final emon2 = await Emon.open(onPackNeeded: (r) async => await askUser(r.pack) ? const PackDecision.fetch() : const PackDecision.decline());
```

Packs are downloaded by `dart:io` (the platform's trust store, including a device's managed roots), never by the native library; the C library verifies each against the SHA-256 catalogue before an engine reads it.
Core and bare adapters never fetch (`PackMode.never` is `Emon.open`'s default); `EMON_OFFLINE=1` always wins over a hook.

## Where the libraries come from

A published package's hook downloads `emon-c-<version>-<triple>.tar.gz` from the GitHub release of the same version and checks it against a SHA-256 table generated into the package at release time (`bb dart --sha-table`).
In a checkout, point each package at a local directory of archives (`bb package --host c`, `bb mobile --triple T`): in the app's `pubspec.yaml`, `hooks: user_defines: <package>: native_dir: <dir>`.

## Development

`bb dart --bindings --check` (ffigen over `emon.h`), `bb dart --packs --check`, `bb dart --analyze`, `bb dart --test` (needs `dist/c`), `bb package --host dart --check` (stamped archives and a clean consumer).
