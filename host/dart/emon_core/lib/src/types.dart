/// The result types the native and the web backends share.
library;

/// The result of `Emon.intake`: what a file says it is, with no page read.
class IntakeResult {
  const IntakeResult({required this.format, required this.version, required this.pages, required this.encrypted, required this.bytes, required this.line});

  /// `pdf`, `png`, `jpeg`, `tiff`.
  final String format;
  final (int, int) version;
  final int pages;
  final bool encrypted;
  final int bytes;

  /// The line the library gave: `pdf 1.4 pages=3 encrypted=false bytes=1234`.
  final String line;
}

/// What the loop did: how many attempts, fetch rounds and requests the conversion took.
class ConvertStats {
  const ConvertStats(this._m);
  final Map<String, int> _m;

  int get attempts => _m['attempts'] ?? 0;
  int get fetchRounds => _m['fetchRounds'] ?? 0;
  int get bytesRequests => _m['bytesRequests'] ?? 0;
  int get requests => _m['requests'] ?? 0;
  int get sourceReads => _m['sourceReads'] ?? 0;
  int get bytesFetched => _m['bytesFetched'] ?? 0;
  int get inputBytes => _m['inputBytes'] ?? 0;
}
