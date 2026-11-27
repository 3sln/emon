/// emon, turnkey: PDF and image to semantic HTML and JSON, with OCR that works on this platform out of the box.
///
/// ```dart
/// import 'package:emon/emon.dart';
///
/// final emon = await openEmon();                                      // engines of the platform, packs fetched on first use
/// final model = await emon.convert(EmonInput.file('scan.pdf'));
/// print(await model.html());
/// ```
///
/// This package is [emon_core] plus the OCR adapters of each platform and the defaults of kin's turnkey table (DEVLOG D898):
///
/// | platform | default engine | selectable explicitly |
/// |---|---|---|
/// | Linux, Android | Tesseract (`emon_flutter` makes ML Kit the Android default) | PP-OCR (where its library ships) |
/// | macOS, iOS | Tesseract | Apple Vision (`OcrEngine.appleVision`, with Tesseract as the fallback of the pages kin sends to it, P13.21b D907; the default once the maintainer accepts it) |
/// | Windows | Windows.Media.Ocr (no download: the OS reads) | Tesseract, PP-OCR |
/// | web (Dart web, Flutter web) | tesseract.js, when [WebAssets] configure it (the JS SDK's engine; it loads its own models) | engines written in Dart |
///
/// On the web the same calls run over emon's wasm build and the JS SDK, through `dart:js_interop` (conditional imports: no code changes per platform); pass `web: WebAssets(...)` to say where
/// the SDK, `emon.wasm` and the engines' files are served. Packs (the language models an engine reads with) are fetched when a conversion first needs them, over the platform's own HTTPS stack. App code decides: [Emon.engines], [Emon.prepare],
/// [Packs.ensure], [PackNeededHook] and [Packs.events] (DEVLOG D896).
library;

export 'package:emon_core/emon_core.dart';

export 'src/turnkey_io.dart' if (dart.library.js_interop) 'src/turnkey_web.dart';
