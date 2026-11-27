import 'package:emon/emon.dart';
import 'package:emon_ocr_mlkit/emon_ocr_mlkit.dart';
import 'package:flutter/foundation.dart';

import 'app_dirs.dart';
import 'downloads.dart';

/// [Emon] with Flutter's defaults, and the state a UI needs.
///
/// * **Engines.** Android: ML Kit when it can run here (bundled models need no Play services), Tesseract otherwise. Everywhere else: Tesseract, with Apple Vision (iOS, macOS) and PP-OCR where
///   its library ships selectable. [engine] is the one conversions use unless their options name another; setting it notifies listeners (an engine picker).
/// * **Packs** are fetched on demand into the app's support directory, by `dart:io` (the platform's trust store), and [downloads] follows them.
class EmonFlutter extends ChangeNotifier {
  EmonFlutter._(this.emon, this._engine, this.mlkit, this.downloads);

  /// The engines, packs, conversions: everything of `package:emon`.
  final Emon emon;

  /// The ML Kit adapter on Android (null elsewhere, or when it is not usable here).
  final MlKitAdapter? mlkit;

  /// Every pack download, as a [ChangeNotifier].
  final PackDownloads downloads;

  OcrEngine _engine;

  /// The OCR engine conversions use unless their options name another.
  OcrEngine get engine => _engine;
  set engine(OcrEngine value) {
    if (value == _engine) return;
    _engine = value;
    notifyListeners();
  }

  /// Open emon for this app.
  ///
  /// [engine] overrides the platform's default. [packs] is how packs are fetched (default: on demand). [packDirectory] defaults to [appPackDirectory]. On Flutter web [web] says where the SDK, `emon.wasm` and the
  /// engines' files are served (see [WebAssets]; with Flutter assets, `assets/packages/emon_flutter/...` or your own `web/emon/`); the web backend fetches no pack and has no pack directory.
  static Future<EmonFlutter> open({
    OcrEngine? engine,
    PackMode packs = PackMode.auto,
    String? packDirectory,
    PackNeededHook? onPackNeeded,
    List<String>? packSources,
    WebAssets? web,
  }) async {
    final dir = packDirectory ?? appPackDirectory();
    MlKitAdapter? mlkit;
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      final candidate = MlKitAdapter();
      if (await candidate.isAvailable()) mlkit = candidate;
    }
    final chosen = engine ?? (mlkit != null ? OcrEngine.mlkitText : defaultPlatformEngine);
    final emon = await openEmon(
      engine: chosen,
      packs: packs,
      packDirectory: dir,
      onPackNeeded: onPackNeeded,
      packSources: packSources,
      adapters: [?mlkit],
      web: web,
    );
    return EmonFlutter._(emon, chosen, mlkit, PackDownloads(emon.packs));
  }

  /// The engines of this platform with their state (built-in, installed, downloadable with size, unavailable with the reason): the data of an engine picker.
  Future<List<EngineInfo>> engines() => emon.engines();

  /// Convert with [engine] unless [options] name another.
  Future<Model> convert(EmonInput input, [ConvertOptions options = const ConvertOptions()]) => emon.convert(input, options.ocrEngine == null ? options.copyWith(ocrEngine: _engine) : options);

  /// The packs a conversion with these options needs (nothing is fetched).
  Future<PrepareResult> prepare([ConvertOptions options = const ConvertOptions()]) => emon.prepare(options.ocrEngine == null ? options.copyWith(ocrEngine: _engine) : options);

  @override
  void dispose() {
    downloads.dispose();
    super.dispose();
    emon.close();
  }
}
