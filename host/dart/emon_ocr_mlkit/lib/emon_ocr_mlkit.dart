/// ML Kit text recognition for emon, on Android.
///
/// ```dart
/// final emon = await Emon.open(adapters: [MlKitAdapter()]);
/// final model = await emon.convert(EmonInput.file(path), const ConvertOptions(ocrEngine: OcrEngine.mlkitText));
/// ```
///
/// WHAT IT SAYS IT IS (DEVLOG D883, D898). ML Kit gives, for each text block, line and word, a box (and a rotated quad for lines), a confidence for lines and words, and the language of each. The adapter
/// states exactly those capabilities: blocks, line quads, line and word confidence, word language. It states no orientation and script detection, no baselines or row metrics, no dictionary flag,
/// no symbols: a page turned a quarter is not detected (kin's own policy handles orientation), and an engine with no such evidence is never an incumbent. Its confidences are uncalibrated.
///
/// WHICH RECOGNISERS. Latin is on by default. It is the script of the languages of most documents, it is the smallest model (about 4 MB per ABI, bundled), and it works without Google Play services. Chinese,
/// Japanese, Korean and Devanagari are optional dependencies (about 4 to 10 MB each per ABI): name the ones an app wants in `android/gradle.properties` with
/// `emonMlkitScripts=latin,chinese,japanese`. A page in a script whose recogniser is not included is UNREAD (`lang-unavailable`), never blank.
///
/// BUNDLED OR PLAY SERVICES. The default variant bundles the models in the app: deterministic (the version is the app's), offline, no Play services. `emonMlkitVariant=unbundled` in `gradle.properties`
/// uses the models Google Play services keeps on the device instead: about 260 KB per recogniser in the app, but the models download when first needed and are updated silently. Those downloads are
/// reported on `Packs.events` (pack ids `mlkit-text-<script>`), where Play services reports them.
library;

import 'dart:async';

import 'package:emon_core/emon_core.dart';
import 'package:flutter/services.dart';

import 'src/scripts.dart';

export 'src/scripts.dart' show mlkitLanguageOfEmon, mlkitScriptFor;

/// The recognisers an app may include (the `emonMlkitScripts` of `gradle.properties`).
const List<String> mlkitScripts = ['latin', 'chinese', 'japanese', 'korean', 'devanagari'];

/// ML Kit text recognition as an emon OCR engine.
class MlKitAdapter extends OcrAdapter {
  MlKitAdapter({MethodChannel? channel, EventChannel? events})
      : _channel = channel ?? const MethodChannel('com.3sln.emon/mlkit'),
        _events = events ?? const EventChannel('com.3sln.emon/mlkit/events');

  final MethodChannel _channel;
  final EventChannel _events;
  StreamSubscription<dynamic>? _eventSub;
  Map<String, Object?> _native = const {};
  bool _available = false;

  @override
  String get name => 'mlkit-text';

  /// Declared capabilities: blocks, line quads, line and word confidence, word language. Nothing is claimed that ML Kit does not measure here.
  static const int capabilities = OilCap.blocks | OilCap.quad | OilCap.lconf | OilCap.wconf | OilCap.wlang;

  @override
  OcrAdapterInfo get info => OcrAdapterInfo(
        engine: 4, // EMON_OEN_MLKIT_TEXT
        caps: capabilities,
        native: capabilities, // ML Kit measures all of them
        config: 'mlkit-text;TextRecognition;recognizer=by-script;lang=request-else-latin',
        observed: observedIdentity,
      );

  /// What produced the readings: the library, its variant and version, the recognisers included, the device's Android. Stable for the life of the adapter.
  String get observedIdentity {
    final n = _native;
    return 'mlkit-text; ${n['library'] ?? 'com.google.mlkit:text-recognition'} ${n['version'] ?? 'unknown'}; variant=${n['variant'] ?? 'unknown'}; scripts=${(n['scripts'] as List?)?.join('+') ?? ''}; android=${n['sdk'] ?? ''}';
  }

  /// The recognisers this build has.
  List<String> get scripts => (_native['scripts'] as List?)?.cast<String>() ?? const [];

  /// 'bundled' (models in the app) or 'unbundled' (models in Google Play services).
  String get variant => (_native['variant'] as String?) ?? 'unknown';

  /// Whether Google Play services is on the device (matters only to the unbundled variant).
  bool get playServices => (_native['playServices'] as bool?) ?? false;

  @override
  Future<bool> isAvailable() async {
    try {
      final r = await _channel.invokeMapMethod<String, Object?>('info');
      if (r == null) return false;
      _native = r;
      _available = r['available'] == true;
    } on PlatformException {
      _available = false;
    } on MissingPluginException {
      _available = false;
    }
    return _available;
  }

  @override
  void attach(void Function(PackEvent event) events) {
    _eventSub?.cancel();
    _eventSub = _events.receiveBroadcastStream().listen((Object? m) {
      if (m is! Map) return;
      final kind = switch (m['kind']) {
        'start' => PackEventKind.start,
        'progress' => PackEventKind.progress,
        'done' => PackEventKind.done,
        'cancelled' => PackEventKind.cancelled,
        _ => PackEventKind.failed,
      };
      events(PackEvent(kind, m['pack'] as String? ?? 'mlkit-text', 'mlkit-text', (m['done'] as num?)?.toInt() ?? 0, (m['total'] as num?)?.toInt() ?? 0, PackInitiator.library, m['message'] as String? ?? ''));
    }, onError: (Object _) {});
  }

  /// Download the recognisers' models now (the unbundled variant: Play services fetches them; progress is on [Packs.events]). The bundled variant has them already.
  Future<void> install([List<String>? scripts]) async {
    await _channel.invokeMethod<void>('install', {'scripts': scripts ?? const ['latin']});
  }

  @override
  Future<OcrReading?> read(OcrRequest request) async {
    final script = mlkitScriptFor(request.languages);
    if (script == null || !scripts.contains(script)) return null; // `lang-unavailable`: unread, never blank
    final r = request.region;
    final Map<Object?, Object?>? out = await _channel.invokeMapMethod<Object?, Object?>('recognize', {
      'png': request.png,
      'region': r == null ? null : [r.x, r.y, r.w, r.h],
      'script': script,
      'single': request.isSingle,
    });
    if (out == null || out['unsupported'] != null) return null;
    final reading = readingOfMap(out);
    // a re-read of one cell or word that found nothing did not read: unread, not a blank claim
    if (reading.lines.isEmpty && (request.region != null || request.isSingle)) return null;
    return reading;
  }

  @override
  Future<void> close() async {
    await _eventSub?.cancel();
    _eventSub = null;
  }
}

/// ML Kit's answer as a reading (what the Kotlin side sends): boxes `[x, y, w, h]`, quads of 8 numbers, confidences 0..1000, languages as BCP-47 tags.
OcrReading readingOfMap(Map<Object?, Object?> m) {
  Box box(Object? b) {
    final l = (b as List).map((e) => (e as num).round()).toList();
    return Box(l[0], l[1], l[2], l[3]);
  }

  final blocks = [for (final b in (m['blocks'] as List? ?? const []).cast<Map>()) OcrBlock(box(b['box']))];
  final lines = <OcrLine>[];
  for (final l in (m['lines'] as List? ?? const []).cast<Map>()) {
    final words = [
      for (final w in (l['words'] as List? ?? const []).cast<Map>())
        OcrWord(w['text'] as String, box(w['box']), confidence: (w['conf'] as num?)?.round() ?? -1, language: mlkitLanguageOfEmon(w['lang'] as String?)),
    ];
    final quad = (l['quad'] as List?)?.map((e) => (e as num).round()).toList();
    lines.add(OcrLine(words, box: box(l['box']), confidence: (l['conf'] as num?)?.round() ?? -1, quad: quad, block: (l['block'] as num?)?.toInt() ?? -1));
  }
  return OcrReading(blocks: blocks, lines: lines, language: mlkitLanguageOfEmon(m['lang'] as String?));
}
