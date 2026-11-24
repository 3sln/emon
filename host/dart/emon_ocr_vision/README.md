# emon_ocr_vision

Apple Vision text recognition for emon on iOS and macOS: the adapter library (libemon_ocr_vision, 13 KB of Objective-C over the OS) and nothing to download.

iOS 13 and macOS 10.15 or newer. It reads with the OS's own recogniser and needs no model pack. Its confidences are quantized, it has no orientation detection and no blocks: it is the turnkey default on Apple platforms (with Tesseract as its page fallback; Tesseract's pack is a conditional, on-demand download, used only for fallback pages) and can be selected explicitly with `ocrEngine: OcrEngine.appleVision`.

Part of [emon](https://github.com/3sln/emon). Depends only on `emon_core`; most apps use the turnkey `emon` (or `emon_flutter`), which chooses adapters for the platform.

```dart
import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_vision/emon_ocr_vision.dart';

final emon = await Emon.open(nativeAdapters: [visionAdapter()], packMode: PackMode.auto);
```

The build hook downloads the library for the target from the GitHub release of the same version and checks its SHA-256 against a table in the package. In a checkout, point it at a
local directory of archives (`bb package --host c`): `hooks: user_defines: emon_ocr_vision: native_dir: <dir>` in the pubspec of the app.
