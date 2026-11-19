# emon_ocr_ppocr

PP-OCRv5 (mobile) for emon: the adapter library (libemon_ocr_ppocr) and its model packs, an additional reading beside Tesseract.

PP-OCR runs on ONNX Runtime and needs the packs `pp5-det-mobile`, `pp5-cls-mobile` and `pp5-rec-en-mobile`. Its library is shipped only where a release has one for the target; where it has none, `isAvailable` is false and `ocrEngine: OcrEngine.ppocr` leaves pages unread (never blank).

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon` (or `emon_flutter`), which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_ppocr/emon_ocr_ppocr.dart';

final emon = await Emon.open(nativeAdapters: [ppocrAdapter()], packMode: PackMode.auto);
```

The build hook downloads the library for the target from the GitHub release of the same version and checks its SHA-256 against a table in the package. In a checkout, point it at a
local directory of archives (`bb package --host c`): `hooks: user_defines: emon_ocr_ppocr: native_dir: <dir>` in the pubspec of the app.
