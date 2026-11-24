# emon_ocr_ppocr

PP-OCRv5 (mobile) for emon: the adapter library (libemon_ocr_ppocr) and its model packs, an additional reading beside Tesseract.

PP-OCR uses three model packs (`pp5-det-mobile`, `pp5-cls-mobile`, `pp5-rec-en-mobile`) and the English Tesseract pack (`tess-fast-eng`) for its lexicon. Use `ppocrAvailable` to check for the native adapter and `emon.packs.ensure(ppocrPacks)` to fetch the packs ahead of time.

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon` (or `emon_flutter`), which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_ppocr/emon_ocr_ppocr.dart';

final emon = await Emon.open(nativeAdapters: [ppocrAdapter()], packMode: PackMode.auto);
```

The build hook downloads the target's C archive from the GitHub release of the same version, verifies its SHA-256, and bundles the adapter with ONNX Runtime. On iOS the runtime is linked into the adapter. In a checkout, set `hooks: user_defines: emon_ocr_ppocr: native_dir: <dir>` in the app's pubspec to use the directory of archives built by `bb package --host c` or `bb mobile`.
