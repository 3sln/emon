/// Whether the page is read by OCR (SPEC 13, `ocr`).
enum OcrMode {
  /// Only the pages that need it (a scan, a page with hidden text, ...). The default.
  auto,

  /// Every page, born-digital ones too (`ocr-scope` says how much of it).
  always,

  /// Never: a scan is left unread (reported, never blank).
  never,
}

/// What the OCR engine of a conversion is (the option `ocr-engine`).
///
/// [tesseract] is the engine a turnkey package uses when you choose nothing. The platform engines are selected explicitly, and an app
/// that wants one by default says so ([ConvertOptions.ocrEngine]).
enum OcrEngine {
  /// Tesseract (the adapter library `emon_ocr_tesseract`).
  tesseract('tesseract'),

  /// PP-OCR beside Tesseract: a second reading that corroborates and rescues (the hybrid).
  ppocr('ppocr'),

  /// PP-OCR alone.
  ppocrAlone('ppocr-alone'),

  /// Apple Vision (iOS, macOS).
  appleVision('apple-vision'),

  /// Windows.Media.Ocr (Windows).
  windowsMediaOcr('windows-media-ocr'),

  /// ML Kit text recognition (Android).
  mlkitText('mlkit-text');

  const OcrEngine(this.optionValue);

  /// The value of the `ocr-engine` option.
  final String optionValue;

  /// The engine's name in kin's engine table.
  String get engineName => this == ppocrAlone ? 'ppocr' : optionValue;
}

/// SPEC 13's settings by their names (the JS SDK's `ConvertOptions`). Everything is optional: `null` leaves a setting to its default.
class ConvertOptions {
  const ConvertOptions({
    this.locale,
    this.ocr,
    this.ocrEngine,
    this.furniture,
    this.assets,
    this.assetDpi,
    this.assetPixels,
    this.assetBytes,
    this.assetsBytes,
    this.assetSignatures,
    this.ocrScope,
    this.keepEvidence = false,
    this.maxRounds,
    this.fetchRounds,
    this.limits = const {},
    this.producer = const {},
    this.chunk,
    this.parallel,
    this.record = false,
    this.transcript,
    this.stepwise = false,
  });

  /// A BCP-47 tag that decides the languages OCR reads in (`de-DE`).
  final String? locale;

  /// [OcrMode].
  final OcrMode? ocr;

  /// The OCR engine; `null`: the engine the [Emon] was opened with.
  final OcrEngine? ocrEngine;

  /// `policy`, `keep` or `drop`.
  final String? furniture;

  /// `none`, `images` or `figures`.
  final String? assets;
  final int? assetDpi;
  final int? assetPixels;
  final int? assetBytes;
  final int? assetsBytes;
  final bool? assetSignatures;

  /// `full` or `witness`.
  final String? ocrScope;

  /// Keep the text layer's evidence dump ([Model.evidence]).
  final bool keepEvidence;
  final int? maxRounds;
  final int? fetchRounds;

  /// kin's limits by name (`{'pages': 50}`).
  final Map<String, int> limits;

  /// What produced the inputs of the run (`producer.NAME=VALUE`), for a transcript.
  final Map<String, String> producer;

  /// The byte source's chunk size, for a stepwise conversion (default 64 KiB).
  final int? chunk;

  /// Worker threads for one conversion (one-shot conversions; the result is the serial run's, at any number).
  final int? parallel;

  /// Keep a transcript ([Model.transcript]). Implies a stepwise conversion.
  final bool record;

  /// Replay: the files of a transcript (any host's), by name. No engine is called.
  final Map<String, List<int>>? transcript;

  /// Convert through the step API (the caller feeds the input in chunks) rather than the one-shot call. The result is the same.
  final bool stepwise;

  /// kin's option text (`key=value` lines). [engine] is the engine the conversion uses when [ocrEngine] is null.
  String toText({OcrEngine? engine, Set<String> extra = const {}}) {
    final lines = <String>[];
    void put(String k, Object? v) {
      if (v != null) lines.add('$k=$v');
    }

    put('locale', locale);
    put('ocr', ocr?.name);
    final e = ocrEngine ?? engine;
    if (e != null && e != OcrEngine.tesseract) put('ocr-engine', e.optionValue);
    put('furniture', furniture);
    put('assets', assets);
    put('asset-dpi', assetDpi);
    put('asset-pixels', assetPixels);
    put('asset-bytes', assetBytes);
    put('assets-bytes', assetsBytes);
    if (assetSignatures != null) put('asset-signatures', assetSignatures! ? 'on' : 'off');
    if (keepEvidence) put('keep-evidence', 1);
    put('ocr-scope', ocrScope);
    put('max-rounds', maxRounds);
    put('fetch-rounds', fetchRounds);
    for (final k in limits.keys.toList()..sort()) {
      put('limit.$k', limits[k]);
    }
    for (final k in producer.keys.toList()..sort()) {
      put('producer.$k', producer[k]);
    }
    put('chunk', chunk);
    put('parallel', parallel);
    lines.addAll(extra);
    return lines.join('\n');
  }

  /// The same options with the given fields replaced.
  ConvertOptions copyWith({OcrEngine? ocrEngine, bool? record, bool? stepwise}) => ConvertOptions(
        locale: locale,
        ocr: ocr,
        ocrEngine: ocrEngine ?? this.ocrEngine,
        furniture: furniture,
        assets: assets,
        assetDpi: assetDpi,
        assetPixels: assetPixels,
        assetBytes: assetBytes,
        assetsBytes: assetsBytes,
        assetSignatures: assetSignatures,
        ocrScope: ocrScope,
        keepEvidence: keepEvidence,
        maxRounds: maxRounds,
        fetchRounds: fetchRounds,
        limits: limits,
        producer: producer,
        chunk: chunk,
        parallel: parallel,
        record: record ?? this.record,
        transcript: transcript,
        stepwise: stepwise ?? this.stepwise,
      );
}

/// What the HTML of a [Model] says (the JS SDK's `HtmlOptions`).
class HtmlOptions {
  const HtmlOptions({
    this.ids = false,
    this.geometry = false,
    this.confidenceBelow,
    this.ledger = false,
    this.pretty = false,
    this.furnitureDrop = false,
    this.milestones = true,
    this.initialsPlain = false,
    this.alignStyle = false,
    this.assets,
    this.assetPrefix,
  });

  final bool ids;
  final bool geometry;

  /// 0..1000
  final int? confidenceBelow;
  final bool ledger;
  final bool pretty;
  final bool furnitureDrop;
  final bool milestones;
  final bool initialsPlain;
  final bool alignStyle;

  /// `inline`, `external` or `omit`.
  final String? assets;

  /// With `external` assets the HTML names this prefix plus each asset's name.
  final String? assetPrefix;

  /// The comma list of the C ABI's `html_options`.
  String toText() {
    final l = <String>[];
    if (ids) l.add('ids');
    if (geometry) l.add('geometry');
    if (confidenceBelow != null) l.add('confidence=$confidenceBelow');
    if (ledger) l.add('ledger');
    if (pretty) l.add('pretty');
    if (furnitureDrop) l.add('drop-furniture');
    if (!milestones) l.add('no-milestones');
    if (initialsPlain) l.add('initials-plain');
    if (alignStyle) l.add('align-style');
    if (assets != null) l.add('assets=$assets');
    if (assetPrefix != null) l.add('prefix=$assetPrefix');
    return l.join(',');
  }
}
