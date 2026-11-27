# emon

PDF and image to semantic HTML and JSON, with OCR that works on this platform out of the box. The pure-Dart turnkey of [emon](https://github.com/3sln/emon): `emon_core`, plus the OCR adapters of each platform,
plus the defaults of emon's turnkey table. For Flutter apps use `emon_flutter` (it adds ML Kit on Android and a `ChangeNotifier` for pack downloads).

```dart
import 'package:emon/emon.dart';

final emon = await openEmon();
final model = await emon.convert(EmonInput.file('scan.pdf'));
print(await model.html());
await emon.close();
```

* Windows.Media.Ocr is the default engine on Windows (the OS reads, nothing is downloaded) and Tesseract elsewhere (`emon_flutter` makes ML Kit the Android default); Apple Vision (iOS, macOS), and Tesseract on Windows, are selected with `ConvertOptions(ocrEngine: ...)` or `openEmon(engine: ...)`.
* Language models are **packs**, fetched on first use over the platform's own HTTPS stack, verified against a SHA-256 catalogue, and cached. `emon.engines()`, `emon.prepare()`, `emon.packs.ensure()`,
  `onPackNeeded` and `emon.packs.events` let app code decide what is downloaded and show progress. `EMON_OFFLINE=1` or `packs: PackMode.never` fetches nothing.
* `dart run example/convert.dart --engine vision scan.pdf out.html`.

## On the web

The same API runs on Flutter web and Dart web over emon's wasm build and the JS SDK: pass `web: WebAssets(...)` (see `emon_core`'s README) to `openEmon`. Nothing is downloaded by the library on the web, and tesseract.js loads its own models from where you serve them.
