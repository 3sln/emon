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
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;

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

  void _listen(void Function() f) => _listeners.add(f);
  void _unlisten(void Function() f) => _listeners.remove(f);
}

// ------------------------------------------------------------------------------------------------ the store and the fetch

/// Where the packs of a process live and how they are fetched. One per [Emon].
class PackStore {
  PackStore({required this.dir, this.mode = PackMode.auto, List<String>? sources, HttpClient? client})
      : sources = sources ?? defaultSources(),
        _client = client;

  /// `<dir>/<pack id>/<file>`.
  final String dir;
  final PackMode mode;

  /// Where files are downloaded from: a base (`<base>/sha256/<hex>`) or a template with `{sha}`, `{id}`, `{rev}`, `{file}`. In order.
  final List<String> sources;
  HttpClient? _client;
  final _events = StreamController<PackEvent>.broadcast();
  final _running = <CancelToken>{};

  Stream<PackEvent> get events => _events.stream;

  /// `$EMON_PACKS_URL` (a comma list), else the mirror, then the data release of the repository.
  static List<String> defaultSources() {
    final env = Platform.environment['EMON_PACKS_URL'];
    if (env != null && env.trim().isNotEmpty) {
      return env.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    }
    return const ['https://emon-packs.3sln.com', 'https://github.com/3sln/emon/releases/download/packs/{id}@{rev}-{file}'];
  }

  static bool get offline {
    final v = Platform.environment['EMON_OFFLINE'];
    return v != null && v.isNotEmpty && v != '0';
  }

  /// The mode that is in force: [PackMode.never] when `EMON_OFFLINE=1`.
  PackMode get effectiveMode => offline ? PackMode.never : mode;

  /// Whether every file of [pack] is in the store (and has its size; the C library verifies the bytes when it reads them).
  bool isInstalled(PackInfo pack) {
    for (final f in pack.files) {
      final file = File(p.join(dir, pack.id, f.name));
      if (!file.existsSync() || file.lengthSync() != f.bytes) return false;
    }
    return true;
  }

  void emit(PackEvent e) {
    if (!_events.isClosed) _events.add(e);
  }

  void cancelAll() {
    for (final t in List.of(_running)) {
      t.cancel();
    }
  }

  Future<void> close() async {
    cancelAll();
    _client?.close(force: true);
    _client = null;
    await _events.close();
  }

  /// Download [pack] into a staging directory under the store, every file verified (size and SHA-256), and answer that directory.
  /// Failure is an [EmonError] named `pack-fetch-failed:<id>`, `pack-hash-mismatch:<id>` or `pack-fetch-cancelled:<id>`.
  Future<String> fetchToStaging(PackInfo pack, {required PackInitiator initiator, CancelToken? cancel}) async {
    final token = cancel ?? CancelToken();
    _running.add(token);
    final stage = Directory(p.join(dir, '.incoming', pack.id));
    try {
      if (stage.existsSync()) stage.deleteSync(recursive: true);
      stage.createSync(recursive: true);
      emit(PackEvent(PackEventKind.start, pack.id, pack.engine, 0, pack.bytes, initiator));
      var before = 0;
      for (final f in pack.files) {
        await _downloadFile(pack, f, File(p.join(stage.path, f.name)), initiator, token, before);
        before += f.bytes;
      }
      emit(PackEvent(PackEventKind.done, pack.id, pack.engine, pack.bytes, pack.bytes, initiator));
      return stage.path;
    } on EmonError catch (e) {
      emit(PackEvent(e.name.startsWith('pack-fetch-cancelled') ? PackEventKind.cancelled : PackEventKind.failed, pack.id, pack.engine, 0, pack.bytes, initiator, e.message));
      if (stage.existsSync()) stage.deleteSync(recursive: true);
      rethrow;
    } finally {
      _running.remove(token);
    }
  }

  /// Move a staged pack into the store (atomic per file).
  void install(PackInfo pack, String stagingDir) {
    final dest = Directory(p.join(dir, pack.id))..createSync(recursive: true);
    for (final f in pack.files) {
      final tmp = File(p.join(stagingDir, f.name));
      tmp.renameSync(p.join(dest.path, f.name));
    }
    final stage = Directory(stagingDir);
    if (stage.existsSync()) stage.deleteSync(recursive: true);
  }

  Future<void> _downloadFile(PackInfo pack, PackFile f, File out, PackInitiator initiator, CancelToken token, int before) async {
    final urls = [
      for (final s in sources)
        s.contains('{')
            ? s.replaceAll('{sha}', f.sha256).replaceAll('{id}', pack.id).replaceAll('{rev}', '${pack.rev}').replaceAll('{file}', f.name)
            : '${s.endsWith('/') ? s.substring(0, s.length - 1) : s}/sha256/${f.sha256}'
    ];
    var last = 'no source';
    _client ??= HttpClient()..connectionTimeout = const Duration(seconds: 20);
    for (final url in urls) {
      if (token.isCancelled) throw EmonError('pack-fetch-cancelled:${pack.id}', 'the download was cancelled', EmonErrorCategory.io);
      HttpClientRequest? req;
      void abort() => req?.abort();
      token._listen(abort);
      try {
        req = await _client!.getUrl(Uri.parse(url));
        final res = await req.close();
        if (res.statusCode != 200) {
          last = '$url answered ${res.statusCode}';
          await res.drain<void>();
          continue;
        }
        final sink = out.openWrite();
        final digestSink = _DigestSink();
        final hasher = sha256.startChunkedConversion(digestSink);
        var got = 0;
        var lastEmit = DateTime.fromMillisecondsSinceEpoch(0);
        await for (final chunk in res) {
          if (token.isCancelled) {
            await sink.close();
            if (out.existsSync()) out.deleteSync();
            throw EmonError('pack-fetch-cancelled:${pack.id}', 'the download was cancelled', EmonErrorCategory.io);
          }
          sink.add(chunk);
          hasher.add(chunk);
          got += chunk.length;
          final now = DateTime.now();
          if (now.difference(lastEmit).inMilliseconds >= 60) {
            lastEmit = now;
            emit(PackEvent(PackEventKind.progress, pack.id, pack.engine, before + got, pack.bytes, initiator));
          }
        }
        hasher.close();
        await sink.close();
        if (got != f.bytes || digestSink.hex != f.sha256) {
          out.deleteSync();
          last = '$url gave other bytes than the catalogue pins (${f.name}: $got bytes)';
          continue;
        }
        emit(PackEvent(PackEventKind.progress, pack.id, pack.engine, before + got, pack.bytes, initiator));
        return;
      } on EmonError {
        rethrow;
      } on Object catch (e) {
        if (token.isCancelled) throw EmonError('pack-fetch-cancelled:${pack.id}', 'the download was cancelled', EmonErrorCategory.io);
        last = '$url: $e';
        if (out.existsSync()) out.deleteSync();
      } finally {
        token._unlisten(abort);
      }
    }
    final mismatch = last.contains('other bytes');
    throw EmonError(mismatch ? 'pack-hash-mismatch:${pack.id}' : 'pack-fetch-failed:${pack.id}', last, EmonErrorCategory.io);
  }

  /// Verify a provided file or directory against the catalogue (the same check the library makes) and stage it, so the library can install it.
  static Future<String?> verifyProvided(PackInfo pack, {Uint8List? bytes, String? path, required String stagingRoot}) async {
    final stage = Directory(p.join(stagingRoot, '.provided', pack.id));
    if (stage.existsSync()) stage.deleteSync(recursive: true);
    stage.createSync(recursive: true);
    for (final f in pack.files) {
      Uint8List data;
      if (bytes != null) {
        if (pack.files.length != 1) return null;
        data = bytes;
      } else if (path != null) {
        final fs = FileSystemEntity.isDirectorySync(path) ? File(p.join(path, f.name)) : File(path);
        if (!fs.existsSync()) return null;
        data = fs.readAsBytesSync();
      } else {
        return null;
      }
      if (data.length != f.bytes || sha256.convert(data).toString() != f.sha256) return null;
      File(p.join(stage.path, f.name)).writeAsBytesSync(data);
    }
    return stage.path;
  }
}

class _DigestSink implements Sink<Digest> {
  String hex = '';
  @override
  void add(Digest data) => hex = data.toString();
  @override
  void close() {}
}

/// App control of the packs of an [Emon]: fetch ahead, and one stream of progress.
class Packs {
  Packs(this._store, this._ensureTargets);

  final PackStore _store;
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
    if (PackStore.offline) throw EmonError('pack-fetch-unavailable', 'EMON_OFFLINE=1: nothing is fetched', EmonErrorCategory.usage);
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
