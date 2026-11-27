/// The native pack store: a directory of verified packs and the `dart:io` download of them (DEVLOG D885, D896).
library;

import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;

import 'errors.dart';
import 'packs.dart';

/// Where the packs of a process live and how they are fetched. One per [Emon].
class PackStore implements PackBackend {
  PackStore({required this.dir, this.mode = PackMode.auto, List<String>? sources, HttpClient? client})
      : sources = sources ?? defaultSources(),
        _client = client;

  /// `<dir>/<pack id>/<file>`.
  @override
  final String dir;
  @override
  final PackMode mode;

  /// Where files are downloaded from: a base (`<base>/sha256/<hex>`) or a template with `{sha}`, `{id}`, `{rev}`, `{file}`. In order.
  final List<String> sources;
  HttpClient? _client;
  final _events = StreamController<PackEvent>.broadcast();
  final _running = <CancelToken>{};

  @override
  Stream<PackEvent> get events => _events.stream;

  /// `$EMON_PACKS_URL` (a comma list), else the mirror, then the data release of the repository.
  static List<String> defaultSources() {
    final env = Platform.environment['EMON_PACKS_URL'];
    if (env != null && env.trim().isNotEmpty) {
      return env.split(',').map((s) => s.trim()).where((s) => s.isNotEmpty).toList();
    }
    return const ['https://emon-packs.3sln.com', 'https://github.com/3sln/emon/releases/download/packs/{id}@{rev}-{file}'];
  }

  @override
  bool get offline {
    final v = Platform.environment['EMON_OFFLINE'];
    return v != null && v.isNotEmpty && v != '0';
  }

  /// The mode that is in force: [PackMode.never] when `EMON_OFFLINE=1`.
  @override
  PackMode get effectiveMode => offline ? PackMode.never : mode;

  /// Whether every file of [pack] is in the store (and has its size; the C library verifies the bytes when it reads them).
  @override
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

  @override
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
  @override
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
  @override
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
      token.listen(abort);
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
        token.unlisten(abort);
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

