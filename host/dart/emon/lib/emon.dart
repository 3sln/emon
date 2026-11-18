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
/// | Linux, Android | Tesseract | PP-OCR (where its library ships) |
/// | macOS, iOS | Tesseract | Apple Vision (`OcrEngine.appleVision`; the default after P13.21b) |
/// | Windows | Tesseract | Windows.Media.Ocr |
///
/// Packs (the language models an engine reads with) are fetched when a conversion first needs them, over the platform's own HTTPS stack. App code decides: [Emon.engines], [Emon.prepare],
/// [Packs.ensure], [PackNeededHook] and [Packs.events] (DEVLOG D896).
library;

import 'dart:io';

import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_ppocr/emon_ocr_ppocr.dart';
import 'package:emon_ocr_tesseract/emon_ocr_tesseract.dart';
import 'package:emon_ocr_vision/emon_ocr_vision.dart';
import 'package:emon_ocr_winocr/emon_ocr_winocr.dart';

export 'package:emon_core/emon_core.dart';

/// The native OCR adapters this platform has: Tesseract everywhere, PP-OCR where a release ships it, and the platform's own recogniser (Apple Vision, Windows.Media.Ocr).
List<NativeAdapter> platformAdapters() => [
      tesseractAdapter(),
      if (Platform.isMacOS || Platform.isIOS) visionAdapter(),
      if (Platform.isWindows) winocrAdapter(),
      if (ppocrAvailable) ppocrAdapter(),
    ];

/// The OCR engine a turnkey uses on this platform when the app chooses none: kin's turnkey table (Tesseract on every platform until a platform engine passes its acceptance).
OcrEngine get defaultPlatformEngine => OcrEngine.tesseract;

/// Open emon with the engines of this platform.
///
/// [engine] is the OCR engine conversions use unless their options name another (default: [defaultPlatformEngine]). [packs] says whether packs are fetched (default: on demand; core and
/// bare adapters never fetch). Everything else is [Emon.open]'s.
Future<Emon> openEmon({
  OcrEngine? engine,
  PackMode packs = PackMode.auto,
  String? packDirectory,
  PackNeededHook? onPackNeeded,
  List<String>? packSources,
  List<OcrAdapter> adapters = const [],
  List<NativeAdapter> nativeAdapters = const [],
  bool pdfium = true,
}) {
  return Emon.open(
    nativeAdapters: [...platformAdapters(), ...nativeAdapters],
    adapters: adapters,
    pdfium: pdfium,
    packDirectory: packDirectory,
    packMode: packs,
    packSources: packSources,
    onPackNeeded: onPackNeeded,
    defaultEngine: engine ?? defaultPlatformEngine,
  );
}
