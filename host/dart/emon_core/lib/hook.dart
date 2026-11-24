/// For build hooks of the emon packages (`hook/build.dart`): fetch the prebuilt C library, verify it, and declare its files as code assets.
///
/// WHERE THE LIBRARIES COME FROM, in order:
///  1. a local directory (development, CI, offline): the hook user-define `native_dir` of the package (pubspec `hooks: user_defines:`), or the
///     environment variable `EMON_NATIVE_DIR`. It is the directory that holds the archives `bb package --host c` / `bb mobile` write
///     (`emon-c-<version>-<triple>.tar.gz`), or one such archive, or an unpacked archive (it has `lib/`).
///  2. the release: `https://github.com/3sln/emon/releases/download/v<version>/emon-c-<version>-<triple>.tar.gz`, verified against the SHA-256
///     table generated into the package at release time ([kNativeAssets]); an archive that does not match is never used. A table with no entry
///     for the target (a checkout, or a target a release does not ship) is an error that says so.
///
/// The user-define `native_dir` of the package whose hook runs wins over the environment, and a hook run by `flutter build` sees a stripped
/// environment, so an app that needs a local directory sets the user-define for each emon package (or the one they all share).
library;

import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:code_assets/code_assets.dart';
import 'package:crypto/crypto.dart';
import 'package:hooks/hooks.dart';
import 'package:path/path.dart' as p;

import 'src/native_assets.g.dart';

export 'src/native_assets.dart' show NativeArchive, NativeAssetTable;

/// One library to bundle.
class NativeLibrary {
  const NativeLibrary({required this.assetName, required this.stem, this.fileName, this.directory, this.optional = false});

  /// The code asset's name inside the package (`src/bindings.g.dart`): the asset id is `package:<package>/<assetName>`.
  final String assetName;

  /// The library without prefix or suffix: `emon`, `pdfium`, `emon_ocr_tesseract`.
  final String stem;

  /// Override the platform filename for a dependency with a versioned loader name.
  final String? fileName;

  /// Where it is inside the archive (`lib/emon/adapters`); null: `lib`.
  final String? directory;

  /// Skip silently when the archive has none for the target (pdfium on a target with no build of it).
  final bool optional;
}

/// The target triple of a code config (the names `bb native` and the release archives use), or null for a target emon does not ship a library for and an app can build anyway: the Intel
/// iOS simulator (DEVLOG D885: Flutter builds both simulator slices; the Apple-silicon one is what runs on a Mac of this decade). An app built only for it fails at run time, by name.
String? targetTriple(CodeConfig code) {
  final arch = code.targetArchitecture;
  String bad() => throw UnsupportedError('emon has no prebuilt library for ${code.targetOS} on $arch');
  switch (code.targetOS) {
    case OS.macOS:
      return switch (arch) {
        Architecture.arm64 => 'aarch64-apple-darwin',
        Architecture.x64 => 'x86_64-apple-darwin',
        _ => bad(),
      };
    case OS.linux:
      return switch (arch) {
        Architecture.x64 => 'x86_64-unknown-linux-gnu',
        Architecture.arm64 => 'aarch64-unknown-linux-gnu',
        _ => bad(),
      };
    case OS.windows:
      return switch (arch) {
        Architecture.x64 => 'x86_64-pc-windows-msvc',
        Architecture.arm64 => 'aarch64-pc-windows-msvc',
        _ => bad(),
      };
    case OS.android:
      final api = code.android.targetNdkApi;
      if (api < 24) throw UnsupportedError('emon needs Android API 24 or newer (targetNdkApi is $api)');
      return switch (arch) {
        Architecture.arm64 => 'aarch64-linux-android',
        Architecture.arm => 'armv7-linux-androideabi',
        Architecture.x64 => 'x86_64-linux-android',
        _ => bad(),
      };
    case OS.iOS:
      final sim = code.iOS.targetSdk == IOSSdk.iPhoneSimulator;
      if (arch == Architecture.arm64) return sim ? 'aarch64-apple-ios-sim' : 'aarch64-apple-ios';
      if (sim && arch == Architecture.x64) return null;
      return bad();
    default:
      return bad();
  }
}

String _fileName(OS os, String stem) {
  if (os == OS.windows) return '$stem.dll';
  final prefix = stem.startsWith('lib') ? '' : 'lib';
  return os == OS.macOS || os == OS.iOS ? '$prefix$stem.dylib' : '$prefix$stem.so';
}

/// Fetch (or find) the C library for the target of [input], and declare each of [libraries] as a bundled code asset of its package.
Future<void> addNativeAssets(BuildInput input, BuildOutputBuilder output, List<NativeLibrary> libraries) async {
  if (!input.config.buildCodeAssets) return;
  final code = input.config.code;
  final triple = targetTriple(code);
  if (triple == null) return;
  final root = await resolveNativeSource(input, output, triple);
  for (final lib in libraries) {
    final dirs = [lib.directory ?? 'lib', 'lib', 'bin', '.'];
    final names = lib.fileName != null
        ? [lib.fileName!]
        : [_fileName(code.targetOS, lib.stem), if (code.targetOS == OS.windows) 'lib${lib.stem}.dll'];
    File? found;
    for (final d in dirs) {
      for (final n in names) {
        final f = File(p.join(root, d, n));
        if (f.existsSync()) {
          found = f;
          break;
        }
      }
      if (found != null) break;
    }
    if (found == null) {
      if (lib.optional) continue;
      throw StateError('the archive for $triple has no ${names.first} (looked in ${dirs.join(', ')} of $root)');
    }
    output.assets.code.add(CodeAsset(
      package: input.packageName,
      name: lib.assetName,
      linkMode: DynamicLoadingBundled(),
      file: found.uri,
    ));
    output.dependencies.add(found.uri);
  }
}

/// The root of an unpacked archive for [triple]: a local directory (see the library comment), else the release's, downloaded and verified.
Future<String> resolveNativeSource(BuildInput input, BuildOutputBuilder output, String triple) async {
  final local = input.userDefines.path('native_dir')?.toFilePath() ?? _nonEmpty(Platform.environment['EMON_NATIVE_DIR']);
  final shared = Directory.fromUri(input.outputDirectoryShared.resolve('emon-native/'))..createSync(recursive: true);
  if (local != null) {
    final source = await _localSource(local, triple);
    if (source.isDir) {
      output.dependencies.add(Directory(source.path).uri);
      return source.path;
    }
    output.dependencies.add(File(source.path).uri); // a rebuilt archive runs the hook again
    return _unpack(File(source.path), shared, 'local-$triple');
  }
  final entry = kNativeAssets.archives[triple];
  if (entry == null) {
    throw StateError(
      'emon has no verified prebuilt library for $triple in this package (version ${kNativeAssets.version}): a build from a checkout names a local '
      'directory of libraries: hooks: user_defines: <package>: native_dir: <dir with emon-c-*-$triple.tar.gz> in pubspec.yaml, or EMON_NATIVE_DIR.',
    );
  }
  final archive = File(p.join(shared.path, entry.file));
  if (!archive.existsSync() || _sha256(archive) != entry.sha256) {
    final bytes = await _download(kNativeAssets.urlFor(entry.file));
    final got = sha256.convert(bytes).toString();
    if (got != entry.sha256) {
      throw StateError('${entry.file} downloaded from ${kNativeAssets.urlFor(entry.file)} does not match the SHA-256 this package pins ($got, not ${entry.sha256}): refusing to use it');
    }
    archive.writeAsBytesSync(bytes);
  }
  return _unpack(archive, shared, entry.sha256.substring(0, 16));
}

String? _nonEmpty(String? s) => s == null || s.isEmpty ? null : s;

Future<({bool isDir, String path})> _localSource(String local, String triple) async {
  final type = FileSystemEntity.typeSync(local);
  if (type == FileSystemEntityType.file) return (isDir: false, path: local);
  if (type != FileSystemEntityType.directory) throw StateError('native_dir / EMON_NATIVE_DIR is not a file or directory: $local');
  if (Directory(p.join(local, 'lib')).existsSync()) return (isDir: true, path: local);
  // a directory of archives: the newest one for the triple
  final archives = Directory(local)
      .listSync()
      .whereType<File>()
      .where((f) => RegExp('^emon-c-.*-${RegExp.escape(triple)}\\.tar\\.gz\$').hasMatch(p.basename(f.path)))
      .toList()
    ..sort((a, b) => a.lastModifiedSync().compareTo(b.lastModifiedSync()));
  if (archives.isEmpty) throw StateError('no emon-c-*-$triple.tar.gz in $local (build one with `bb package --host c` or `bb mobile --triple $triple`)');
  return (isDir: false, path: archives.last.path);
}

String _sha256(File f) => sha256.convert(f.readAsBytesSync()).toString();

Future<List<int>> _download(Uri url) async {
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 30);
  try {
    final req = await client.getUrl(url);
    final res = await req.close();
    if (res.statusCode != 200) throw StateError('$url answered ${res.statusCode}');
    final out = BytesBuilder(copy: false);
    await for (final c in res) {
      out.add(c);
    }
    return out.takeBytes();
  } finally {
    client.close(force: true);
  }
}

// Unpack the archive once per content into `<shared>/<key>/`; the root is its single top-level directory.
String _unpack(File archive, Directory shared, String key) {
  final stamp = '${archive.lengthSync()}-${archive.lastModifiedSync().millisecondsSinceEpoch}';
  final dest = Directory(p.join(shared.path, 'unpacked-$key'));
  final marker = File(p.join(dest.path, '.unpacked'));
  if (!(marker.existsSync() && marker.readAsStringSync() == stamp)) {
    if (dest.existsSync()) dest.deleteSync(recursive: true);
    dest.createSync(recursive: true);
    final tar = TarDecoder().decodeBytes(GZipDecoder().decodeBytes(archive.readAsBytesSync()));
    for (final f in tar) {
      final path = p.joinAll(f.name.split('/').where((s) => s.isNotEmpty));
      if (path.isEmpty || p.isAbsolute(path) || p.split(path).contains('..')) continue;
      final out = p.join(dest.path, path);
      if (f.isFile) {
        File(out)
          ..createSync(recursive: true)
          ..writeAsBytesSync(f.content as List<int>);
        if (f.mode & 0x40 != 0 && !Platform.isWindows) Process.runSync('chmod', ['+x', out]);
      } else {
        Directory(out).createSync(recursive: true);
      }
    }
    marker.writeAsStringSync(stamp);
  }
  final entries = dest.listSync().where((e) => !p.basename(e.path).startsWith('.')).toList();
  return entries.length == 1 && entries.single is Directory ? entries.single.path : dest.path;
}
