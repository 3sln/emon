/// The archives of the C library a release publishes, and the SHA-256 table the build hooks verify them against (DEVLOG D884, D886).
library;

/// One archive: `emon-c-<version>-<triple>.tar.gz` and what it must hash to.
class NativeArchive {
  const NativeArchive(this.file, this.sha256);

  final String file;

  /// Lower-case hex.
  final String sha256;
}

/// The table of a release, by target triple. Generated at release time from the release's `SHA256SUMS` (`bb dart --sha-table`): the
/// committed table is empty, so a build from a checkout names a local directory of libraries instead ([resolveNativeSource]).
class NativeAssetTable {
  const NativeAssetTable({required this.version, required this.archives});

  final String version;
  final Map<String, NativeArchive> archives;

  /// Where the release's archive for [file] is downloaded from.
  Uri urlFor(String file, {String repository = 'https://github.com/3sln/emon'}) => Uri.parse('$repository/releases/download/v$version/$file');
}
