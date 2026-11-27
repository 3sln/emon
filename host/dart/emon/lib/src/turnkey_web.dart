/// The web turnkey: the same calls as the native one, over emon's wasm build and the JS SDK (see `package:emon/emon.dart`).
library;

import 'package:emon_core/emon_core.dart';

/// There are no native adapters on the web (the JS engines are [WebAssets]'s).
List<NativeAdapter> platformAdapters() => const [];

/// The engine a turnkey uses on the web when the app chooses none: tesseract.js, the SDK's engine.
OcrEngine get defaultPlatformEngine => OcrEngine.tesseract;

/// Open emon on this page.
///
/// [web] says where the SDK, `emon.wasm` and tesseract.js and pdfium's files are (see [WebAssets]; default: `emon/sdk/` and `emon/emon.wasm` next to the page, and no OCR engine, so a page that
/// needs OCR is unread, reported and never blank). The other parameters are the native ones', accepted so one call works everywhere: no pack is fetched on the web, and [onPackNeeded] is not asked.
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
    nativeAdapters: nativeAdapters,
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
