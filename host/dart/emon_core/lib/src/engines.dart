/// What engines exist and what a conversion needs (D896's `engines()` and `prepare()`).
library;

import 'packs.dart';

/// The state of an engine on this platform.
enum EngineState {
  /// It needs no pack: pdfium, an OS engine, an engine behind a plugin.
  builtIn('built-in'),

  /// Registered, and its packs are here.
  installed('installed'),

  /// Registered, its packs are missing and the pack mode may fetch them ([EngineInfo.downloadBytes]).
  downloadable('downloadable'),

  /// Not usable here ([EngineInfo.reason]).
  unavailable('unavailable');

  const EngineState(this.text);
  final String text;

  static EngineState parse(String s) => values.firstWhere((v) => v.text == s, orElse: () => unavailable);
}

/// A pack an engine reads with.
class EnginePack {
  const EnginePack({required this.id, required this.kind, required this.bytes, required this.licence, required this.installed});

  final String id;
  final String kind;
  final int bytes;
  final String licence;
  final bool installed;
}

/// One engine of kin's table that runs on this platform, and its state.
class EngineInfo {
  const EngineInfo({
    required this.name,
    required this.kind,
    required this.implementation,
    required this.state,
    required this.registered,
    required this.calibrated,
    required this.role,
    required this.needs,
    required this.downloadBytes,
    required this.reason,
  });

  /// `tesseract`, `ppocr`, `apple-vision`, `windows-media-ocr`, `mlkit-text`, `pdfium`.
  final String name;

  /// `ocr` or `render`.
  final String kind;

  /// `native-adapter`, `platform-plugin` or `built-in`.
  final String implementation;
  final EngineState state;
  final bool registered;

  /// Whether its confidences have a frozen reliability map (DEVLOG D883).
  final bool calibrated;
  final String role;

  /// The packs of a conversion with the default options (English).
  final List<EnginePack> needs;

  /// What fetching its missing packs comes to.
  final int downloadBytes;
  final String reason;

  /// Whether a conversion can use it now: [state] is built-in or installed.
  bool get usable => state == EngineState.builtIn || state == EngineState.installed;

  /// Whether it can be used after fetching packs.
  bool get usableAfterDownload => usable || state == EngineState.downloadable;

  factory EngineInfo.fromJson(Map<String, Object?> j) => EngineInfo(
        name: j['name'] as String,
        kind: j['kind'] as String? ?? 'ocr',
        implementation: j['impl'] as String? ?? '',
        state: EngineState.parse(j['state'] as String? ?? ''),
        registered: j['registered'] as bool? ?? false,
        calibrated: j['calibrated'] as bool? ?? false,
        role: j['role'] as String? ?? '',
        needs: [
          for (final n in (j['needs'] as List? ?? const []).cast<Map>())
            EnginePack(
              id: n['id'] as String,
              kind: n['kind'] as String? ?? '',
              bytes: (n['bytes'] as num?)?.toInt() ?? 0,
              licence: n['licence'] as String? ?? '',
              installed: n['installed'] as bool? ?? false,
            ),
        ],
        downloadBytes: (j['download_bytes'] as num?)?.toInt() ?? 0,
        reason: j['reason'] as String? ?? '',
      );

  EngineInfo copyWith({EngineState? state, bool? registered, String? reason}) => EngineInfo(
        name: name,
        kind: kind,
        implementation: implementation,
        state: state ?? this.state,
        registered: registered ?? this.registered,
        calibrated: calibrated,
        role: role,
        needs: needs,
        downloadBytes: downloadBytes,
        reason: reason ?? this.reason,
      );

  @override
  String toString() => 'EngineInfo($name, ${state.text})';
}

/// The verdict of [Emon.prepare].
enum PrepareVerdict {
  /// Nothing is missing.
  ready,

  /// Nothing is missing, but a language no pack reads: those pages are unread.
  partial,

  /// Packs are missing and the mode may fetch them.
  downloadable,

  /// Packs are missing and nothing may be fetched.
  blocked,
}

/// A pack a conversion needs.
class NeededPack {
  const NeededPack({required this.id, required this.installed, required this.engine, required this.kind, required this.bytes, required this.licence, required this.rev});

  final String id;
  final bool installed;
  final String engine;
  final String kind;
  final int bytes;
  final String licence;
  final int rev;

  PackInfo? get info => PackCatalog.byId(id);
}

/// What a conversion with some options needs (a preflight; nothing is fetched).
class PrepareResult {
  const PrepareResult({
    required this.verdict,
    required this.mode,
    required this.offline,
    required this.engines,
    required this.languages,
    required this.packs,
    required this.unsupported,
    required this.missing,
    required this.missingBytes,
  });

  final PrepareVerdict verdict;
  final String mode;
  final bool offline;
  final List<String> engines;
  final List<String> languages;
  final List<NeededPack> packs;

  /// (engine, language) pairs no pack reads: those pages are unread.
  final List<({String engine, String language})> unsupported;
  final int missing;
  final int missingBytes;

  /// The ids of the packs not installed.
  List<String> get missingPackIds => [for (final p in packs) if (!p.installed) p.id];

  factory PrepareResult.fromJson(Map<String, Object?> j) => PrepareResult(
        verdict: PrepareVerdict.values.firstWhere((v) => v.name == j['verdict'], orElse: () => PrepareVerdict.blocked),
        mode: j['mode'] as String? ?? '',
        offline: j['offline'] as bool? ?? false,
        engines: (j['engines'] as List? ?? const []).cast<String>(),
        languages: (j['languages'] as List? ?? const []).cast<String>(),
        packs: [
          for (final n in (j['packs'] as List? ?? const []).cast<Map>())
            NeededPack(
              id: n['id'] as String,
              installed: n['state'] == 'installed',
              engine: n['engine'] as String? ?? '',
              kind: n['kind'] as String? ?? '',
              bytes: (n['bytes'] as num?)?.toInt() ?? 0,
              licence: n['licence'] as String? ?? '',
              rev: (n['rev'] as num?)?.toInt() ?? 1,
            ),
        ],
        unsupported: [
          for (final n in (j['unsupported'] as List? ?? const []).cast<Map>()) (engine: n['engine'] as String, language: n['language'] as String)
        ],
        missing: (j['missing'] as num?)?.toInt() ?? 0,
        missingBytes: (j['missing_bytes'] as num?)?.toInt() ?? 0,
      );

  @override
  String toString() => 'PrepareResult(${verdict.name}, missing $missing packs, $missingBytes bytes)';
}
