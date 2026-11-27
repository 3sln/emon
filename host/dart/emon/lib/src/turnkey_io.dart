/// The native turnkey: the platform's adapters and defaults (see `package:emon/emon.dart`).
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

/// The OCR engine a turnkey uses on this platform when the app chooses none: kin's turnkey table (Apple Vision on macOS and iOS with Tesseract as its page fallback, maintainer 2026-11-23, P13.21b D907; Windows.Media.Ocr on Windows, K5 D905; Tesseract elsewhere, and
/// Android's default, ML Kit, is a Flutter plugin, so the pure-Dart `emon` keeps Tesseract there and `emon_flutter` chooses ML Kit).
OcrEngine get defaultPlatformEngine => Platform.isWindows
    ? OcrEngine.windowsMediaOcr
    : (Platform.isMacOS || Platform.isIOS)
        ? OcrEngine.appleVision
        : OcrEngine.tesseract;

/// Open emon with the engines of this platform.
///
/// [engine] is the OCR engine conversions use unless their options name another (default: [defaultPlatformEngine]). [packs] says whether packs are fetched (default: on demand; core and
/// bare adapters never fetch). [web] is where the web backend's files are (unused natively). Everything else is [Emon.open]'s.
Future<Emon> openEmon({
  OcrEngine? engine,
  PackMode packs = PackMode.auto,
  String? packDirectory,
  PackNeededHook? onPackNeeded,
  List<String>? packSources,
  List<OcrAdapter> adapters = const [],
  List<NativeAdapter> nativeAdapters = const [],
  bool pdfium = true,
  WebAssets? web,
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
    web: web,
  );
}
