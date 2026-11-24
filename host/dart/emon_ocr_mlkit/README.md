# emon_ocr_mlkit

ML Kit text recognition for emon on Android: a Flutter plugin that answers emon's OCR requests with Google's on-device recogniser, maps them into emon's engine-neutral OCR answer with honest capabilities, and records its identity.

Android API 24 or newer. It is the turnkey default on Android in `emon_flutter`; select it explicitly with `ocrEngine: OcrEngine.mlkitText`. Its confidences are uncalibrated until a fitted map lands, and it has no orientation detection.

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon_flutter`, which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_mlkit/emon_ocr_mlkit.dart';

final emon = await Emon.open(adapters: [MlKitAdapter()], packMode: PackMode.auto);
```

Two Gradle properties of the app (`android/gradle.properties`) choose what ships:

- `emonMlkitVariant=bundled | unbundled`. `bundled` (the default) puts the models in the app: deterministic and offline, no Play services. `unbundled` leaves them to Google Play services: about 260 KB in the app, the model downloaded when first needed (reported through `packs.events`).
- `emonMlkitScripts=latin,chinese,japanese,korean,devanagari` (default `latin`): the recognisers to include.
