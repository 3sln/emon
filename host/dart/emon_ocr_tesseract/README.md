# emon_ocr_tesseract

Tesseract OCR for emon: the adapter library (libemon_ocr_tesseract), fetched for the target by a build hook, and its language packs on demand.

Every platform. It needs the model packs `tess-fast-eng` (and `tess-osd` for orientation and script detection); other languages are packs of their own, fetched when a conversion asks for them.

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon` (or `emon_flutter`), which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_tesseract/emon_ocr_tesseract.dart';

final emon = await Emon.open(nativeAdapters: [tesseractAdapter()], packMode: PackMode.auto);
```

The build hook downloads the library for the target from the GitHub release of the same version and checks its SHA-256 against a table in the package. In a checkout, point it at a
local directory of archives (`bb package --host c`): `hooks: user_defines: emon_ocr_tesseract: native_dir: <dir>` in the pubspec of the app.
