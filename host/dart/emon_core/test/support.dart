// Shared by the tests: where the repository is, the unpacked C archive (adapters, packs), and a local server for packs.
import 'dart:ffi';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:emon_core/emon_core.dart';
import 'package:path/path.dart' as p;

final repo = p.normalize(p.absolute('../../..'));

String get triple {
  final arch = Abi.current();
  if (Platform.isMacOS) return arch == Abi.macosArm64 ? 'aarch64-apple-darwin' : 'x86_64-apple-darwin';
  if (Platform.isLinux) return arch == Abi.linuxArm64 ? 'aarch64-unknown-linux-gnu' : 'x86_64-unknown-linux-gnu';
  throw UnsupportedError('these tests run on macOS and Linux');
}

String get libExt => Platform.isMacOS ? 'dylib' : 'so';

/// The archive `bb package --host c` wrote, unpacked once per test run: its root.
final String unpackedArchive = () {
  final dist = Directory(p.join(repo, 'dist', 'c'));
  final found = dist.existsSync() ? dist.listSync().whereType<File>().where((f) => f.path.endsWith('-$triple.tar.gz')).toList() : <File>[];
  if (found.isEmpty) throw StateError('no dist/c/emon-c-*-$triple.tar.gz: run `bb package --host c`');
  found.sort((a, b) => a.lastModifiedSync().compareTo(b.lastModifiedSync()));
  final dest = Directory(p.join(Directory.systemTemp.path, 'emon-dart-test-${pid}'))..createSync(recursive: true);
  final r = Process.runSync('tar', ['-xzf', found.last.path, '-C', dest.path]);
  if (r.exitCode != 0) throw StateError('tar: ${r.stderr}');
  return dest.listSync().whereType<Directory>().first.path;
}();

String adapterLibrary(String name) => p.join(unpackedArchive, 'lib', 'emon', 'adapters', 'libemon_ocr_$name.$libExt');

String get pdfiumLibrary => p.join(unpackedArchive, 'lib', 'libpdfium.$libExt');

File fixture(String relative) => File(p.join(repo, relative));

/// A server that answers `/sha256/<hex>` with the pack files of the unpacked archive (what the mirror of D885 does), counting the requests.
class PackServer {
  PackServer._(this._server, this.requests);

  final HttpServer _server;
  final List<String> requests;

  String get base => 'http://127.0.0.1:${_server.port}';

  static Future<PackServer> start({Set<String> corrupt = const {}, Duration chunkDelay = Duration.zero}) async {
    final byHash = <String, File>{};
    for (final pk in PackCatalog.all) {
      for (final f in pk.files) {
        final file = File(p.join(unpackedArchive, 'share', 'emon', 'packs', pk.id, f.name));
        if (file.existsSync()) byHash[f.sha256] = file;
      }
    }
    final requests = <String>[];
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((req) async {
      requests.add(req.uri.path);
      final hex = req.uri.pathSegments.length == 2 ? req.uri.pathSegments[1] : '';
      final file = byHash[hex];
      if (file == null) {
        req.response.statusCode = 404;
        await req.response.close();
        return;
      }
      final bytes = file.readAsBytesSync();
      req.response.contentLength = bytes.length;
      if (corrupt.contains(hex)) {
        bytes[0] ^= 0xff;
      }
      // in pieces, so progress has something to report
      for (var i = 0; i < bytes.length; i += 256 * 1024) {
        req.response.add(bytes.sublist(i, i + 256 * 1024 > bytes.length ? bytes.length : i + 256 * 1024));
        await req.response.flush();
        if (chunkDelay > Duration.zero) await Future<void>.delayed(chunkDelay);
      }
      await req.response.close();
    });
    return PackServer._(server, requests);
  }

  Future<void> stop() => _server.close(force: true);
}

String sha256Hex(List<int> bytes) => sha256.convert(bytes).toString();
