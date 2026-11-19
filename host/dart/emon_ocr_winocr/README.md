# emon_ocr_winocr

Windows.Media.Ocr for emon on Windows: the adapter library (emon_ocr_winocr.dll) and nothing to download.

Windows 10 or newer, with the language pack of the language to read installed. No confidence at all, no orientation detection. Select it explicitly with `ocrEngine: OcrEngine.windowsMediaOcr`.

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon` (or `emon_flutter`), which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_winocr/emon_ocr_winocr.dart';

final emon = await Emon.open(nativeAdapters: [winocrAdapter()], packMode: PackMode.auto);
```

The build hook downloads the library for the target from the GitHub release of the same version and checks its SHA-256 against a table in the package. In a checkout, point it at a
local directory of archives (`bb package --host c`): `hooks: user_defines: emon_ocr_winocr: native_dir: <dir>` in the pubspec of the app.
