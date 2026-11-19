/// The category of an [EmonError] (the C ABI's `EMON_ERR_*`, DEVLOG D040).
enum EmonErrorCategory {
  usage(1),
  noInput(2),
  limit(3),
  passwordRequired(4),
  passwordIncorrect(5),
  encryptionUnsupported(6),
  unsupportedFormat(7),
  damaged(8),
  sourceChanged(9),
  io(10),
  unsupported(11),
  plan(12),
  internal(13);

  const EmonErrorCategory(this.code);

  /// The number of the C ABI.
  final int code;

  static EmonErrorCategory of(int code) {
    for (final c in values) {
      if (c.code == code) return c;
    }
    return EmonErrorCategory.internal;
  }
}

/// A named emon failure.
///
/// [name] is the stable name to route on, as in the JS SDK's `EmonError.code`: a PDF status such as `bad-xref`, a limit such as `pages`
/// or `input-bytes`, a plan error such as `transcript-miss`, or one of `password-required`, `password-incorrect`, `unsupported-format`,
/// `io`, `source-changed`, `internal`, `usage`; for engines and packs the refusal's (`adapter-runtime-missing`, `pack-missing:<id>`, ...).
/// [category] is the broad kind, one for each kind of error.
class EmonError implements Exception {
  EmonError(this.name, [this.message = '', this.category = EmonErrorCategory.internal]);

  /// The stable name to route on.
  final String name;

  /// The sentence.
  final String message;

  /// The kind of error.
  final EmonErrorCategory category;

  @override
  String toString() => message.isEmpty ? 'EmonError($name)' : 'EmonError($name): $message';
}
