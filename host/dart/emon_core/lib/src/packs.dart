/// Model packs (DEVLOG D885, D896): the model files an OCR engine reads with, content-addressed and SHA-256-pinned.
///
/// A pack is never part of a package. The turnkey packages fetch what a conversion needs on first use, from a mirror, with the platform's own
/// HTTPS stack and trust store (`dart:io`), and the C library verifies every byte against the catalogue's SHA-256 before an engine reads it,
/// wherever it came from. App code can steer all of it: [Packs.ensure] fetches ahead of time, [PackNeededHook] decides what to do about a
/// pack a conversion lacks (fetch it, provide your own bytes, decline, substitute another engine), and [Packs.events] is one stream of
/// progress for every download.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'errors.dart';
import 'options.dart' show OcrEngine;
import 'packs.g.dart';

/// One file of a pack: its name and what it must hash to.
class PackFile {
  const PackFile(this.name, this.sha256, this.bytes);

  final String name;

  /// Lower-case hex.
  final String sha256;
  final int bytes;
}

/// A pack of the catalogue (`release/packs/packs.edn`).
class PackInfo {
  const PackInfo({
    required this.id,
    required this.rev,
    required this.engine,
    required this.kind,
    required this.languages,
    required this.scripts,
    required this.licence,
    required this.files,
  });

  /// `tess-fast-eng`.
  final String id;
  final int rev;

  /// The engine it is for: `tesseract`, `ppocr`.
  final String engine;

  /// `recognizer`, `osd`, `detector`, `classifier`, `lexicon`.
  final String kind;

  /// The languages (emon's codes) and scripts (ISO 15924) it reads.
  final List<String> languages;
  final List<String> scripts;
  final String licence;
  final List<PackFile> files;

  int get bytes => files.fold(0, (a, f) => a + f.bytes);

  @override
  String toString() => 'PackInfo($id, $bytes bytes)';
}

/// The catalogue the library verifies against, generated from `release/packs/packs.edn` (`bb dart --packs`).
abstract final class PackCatalog {
  static List<PackInfo> get all => kPackCatalogue;

  static PackInfo? byId(String id) {
    for (final pk in kPackCatalogue) {
      if (pk.id == id) return pk;
    }
    return null;
  }
}

/// The kind of a [PackEvent].
enum PackEventKind { start, progress, done, failed, cancelled }

/// Who asked for a download.
enum PackInitiator {
  /// A conversion needed the pack.
  library,

  /// App code: [Packs.ensure].
  app,
}

/// One progress report on a download (the same stream for the library's and the app's).
class PackEvent {
  const PackEvent(this.kind, this.pack, this.engine, this.done, this.total, this.initiator, [this.message = '']);

  final PackEventKind kind;
  final String pack;
  final String engine;

  /// Bytes so far, of the whole pack.
  final int done;
  final int total;
  final PackInitiator initiator;

  /// For a failure: why.
  final String message;

  /// 0..1.
  double get fraction => total == 0 ? 0 : done / total;

  @override
  String toString() => 'PackEvent(${kind.name} $pack $done/$total)';
}

/// What a conversion lacks (the argument of a [PackNeededHook]).
class PackRequest {
  const PackRequest({required this.pack, required this.reason, required this.requestEngine});

  /// The pack: its id, size and licence.
  final PackInfo pack;

  /// `conversion`: a conversion needs it. `ensure`: [Packs.ensure] does.
  final String reason;

  /// The engine whose request needs it.
  final String requestEngine;
}

/// What to do about a pack that is missing. Every pack, including bytes you provide, is verified against the catalogue before it is used.
sealed class PackDecision {
  const PackDecision();

  /// Download it (the platform's HTTPS stack; progress on [Packs.events]).
  const factory PackDecision.fetch() = PackFetch;

  /// Use these bytes (a single-file pack) or this path (the file, or a directory holding the pack's files by their names): from your own CDN or assets.
  const factory PackDecision.provide({Uint8List? bytes, String? path}) = PackProvide;

  /// Leave it missing: the page is unread (`pack-missing:<id>`), never blank.
  const factory PackDecision.decline() = PackDecline;

  /// Read with another implementation of the same OIL engine instead.
  const factory PackDecision.substitute(OcrEngine engine) = PackSubstitute;
}

final class PackFetch extends PackDecision {
  const PackFetch();
}

final class PackProvide extends PackDecision {
  const PackProvide({this.bytes, this.path});
  final Uint8List? bytes;
  final String? path;
}

final class PackDecline extends PackDecision {
  const PackDecline();
}

final class PackSubstitute extends PackDecision {
  const PackSubstitute(this.engine);
  final OcrEngine engine;
}

/// Decides what to do about a pack a conversion lacks. Called on the isolate that opened the [Emon]; the conversion waits for the answer.
typedef PackNeededHook = FutureOr<PackDecision> Function(PackRequest request);

/// Whether and when packs are fetched (DEVLOG D885, D896). `EMON_OFFLINE=1` in the environment is [never] whatever you set.
enum PackMode {
  /// Fetch nothing and ask no hook: a missing pack leaves the page unread.
  never,

  /// Use what is cached; ask the hook (it may provide); never touch the network.
  cache,

  /// Fetch on demand (with no hook, or when the hook says fetch).
  auto,
}

/// A way to stop a download.
class CancelToken {
  bool _cancelled = false;
  final _listeners = <void Function()>[];

  bool get isCancelled => _cancelled;

  void cancel() {
    if (_cancelled) return;
    _cancelled = true;
    for (final l in List.of(_listeners)) {
      l();
    }
  }

  /// For a [PackBackend]: call [f] when the token is cancelled.
  void listen(void Function() f) => _listeners.add(f);

  /// For a [PackBackend]: stop calling [f].
  void unlisten(void Function() f) => _listeners.remove(f);
}

/// Where the packs of a process live and how they are fetched: the native [PackStore] (a directory, `dart:io`), or the web's, which has no packs
/// (the JS engines load their own models, DEVLOG D9xx).
abstract class PackBackend {
  /// Progress of every download.
  Stream<PackEvent> get events;

  /// The directory packs are kept in (empty where there is none).
  String get dir;

  /// The mode asked for.
  PackMode get mode;

  /// The mode in force: [PackMode.never] when the environment says offline, or where the platform fetches nothing.
  PackMode get effectiveMode;

  /// Whether the environment says offline (`EMON_OFFLINE=1`).
  bool get offline;

  /// Whether every file of [pack] is in the store.
  bool isInstalled(PackInfo pack);

  /// Download [pack] into a staging directory, verified.
  Future<String> fetchToStaging(PackInfo pack, {required PackInitiator initiator, CancelToken? cancel});

  /// Move a staged pack into the store.
  void install(PackInfo pack, String stagingDir);

  /// Stop the downloads that are running.
  void cancelAll();
}

/// App control of the packs of an [Emon]: fetch ahead, and one stream of progress.
class Packs {
  Packs(this._store, this._ensureTargets);

  final PackBackend _store;
  final List<PackInfo> Function(Iterable<String> ids) _ensureTargets;

  /// Progress of every download: the library's (a conversion needed the pack) and the app's ([ensure]). Also the platform's model
  /// downloads where the platform exposes them (ML Kit through Play services), when the plugin reports them.
  Stream<PackEvent> get events => _store.events;

  /// The directory packs are kept in.
  String get directory => _store.dir;

  PackMode get mode => _store.effectiveMode;

  /// Whether [id] is in the store.
  bool isInstalled(String id) {
    final pk = PackCatalog.byId(id);
    return pk != null && _store.isInstalled(pk);
  }

  /// Fetch packs now. [onProgress] hears the same events as [events], for these downloads. Stops at the first failure
  /// (`pack-fetch-failed:<id>`, `pack-hash-mismatch:<id>`, `pack-fetch-cancelled:<id>`, `pack-fetch-unavailable:<id>`).
  ///
  /// Only in [PackMode.auto], and never with `EMON_OFFLINE=1`.
  Future<void> ensure(Iterable<String> ids, {void Function(PackEvent event)? onProgress, CancelToken? cancel}) async {
    if (_store.offline) throw EmonError('pack-fetch-unavailable', 'EMON_OFFLINE=1: nothing is fetched', EmonErrorCategory.usage);
    if (_store.mode != PackMode.auto) throw EmonError('pack-fetch-unavailable', 'the pack mode is ${_store.mode.name}: nothing is fetched', EmonErrorCategory.usage);
    final sub = onProgress == null ? null : _store.events.listen(onProgress);
    try {
      for (final pk in _ensureTargets(ids)) {
        if (_store.isInstalled(pk)) continue;
        final staged = await _store.fetchToStaging(pk, initiator: PackInitiator.app, cancel: cancel);
        _store.install(pk, staged);
      }
      // the stream delivers its events a microtask after they are made: let the last one reach this listener before it is removed
      await Future<void>.delayed(Duration.zero);
    } finally {
      await sub?.cancel();
    }
  }

  /// Stop the downloads that are running.
  void cancel() => _store.cancelAll();
}

/// A JSON object (what the C library reports) read as a Map.
Map<String, Object?> jsonObject(String text) => (jsonDecode(text) as Map).cast<String, Object?>();
