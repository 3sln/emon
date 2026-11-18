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

* Tesseract is the default engine everywhere; Apple Vision (iOS, macOS) and Windows.Media.Ocr are selected with `ConvertOptions(ocrEngine: ...)` or `openEmon(engine: ...)`.
* Language models are **packs**, fetched on first use over the platform's own HTTPS stack, verified against a SHA-256 catalogue, and cached. `emon.engines()`, `emon.prepare()`, `emon.packs.ensure()`,
  `onPackNeeded` and `emon.packs.events` let app code decide what is downloaded and show progress. `EMON_OFFLINE=1` or `packs: PackMode.never` fetches nothing.
* `dart run example/convert.dart --engine vision scan.pdf out.html`.
