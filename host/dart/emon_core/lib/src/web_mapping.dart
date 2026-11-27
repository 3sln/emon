/// The pure part of the web backend: how the Dart API maps onto the JS SDK's. No JS is touched here, so these run in the VM test too (DEVLOG D9xx).
library;

import 'dart:typed_data';

import 'engines.dart';
import 'errors.dart';
import 'ocr.dart';
import 'options.dart';

/// The JS SDK's `ConvertOptions` of [o], as nested maps and lists. [engine] is the engine used when [o] names none; the default, Tesseract, is never written (as the native
/// option text does not). `parallel`, `stepwise` and `chunk` have no meaning there beyond `chunk`: the JS loop always runs stepwise.
Map<String, Object?> webConvertOptions(ConvertOptions o, {OcrEngine? engine}) {
  final m = <String, Object?>{};
  void put(String k, Object? v) {
    if (v != null) m[k] = v;
  }

  put('locale', o.locale);
  put('ocr', o.ocr?.name);
  final e = o.ocrEngine ?? engine;
  if (e != null && e != OcrEngine.tesseract) put('ocrEngine', e.optionValue);
  put('furniture', o.furniture);
  put('assets', o.assets);
  put('assetDpi', o.assetDpi);
  put('assetPixels', o.assetPixels);
  put('assetBytes', o.assetBytes);
  put('assetsBytes', o.assetsBytes);
  put('assetSignatures', o.assetSignatures);
  if (o.keepEvidence) m['keepEvidence'] = true;
  put('ocrScope', o.ocrScope);
  put('maxRounds', o.maxRounds);
  put('fetchRounds', o.fetchRounds);
  if (o.limits.isNotEmpty) m['limits'] = Map<String, Object?>.of(o.limits);
  if (o.producer.isNotEmpty) m['producer'] = Map<String, Object?>.of(o.producer);
  put('chunk', o.chunk);
  if (o.record) m['record'] = true;
  return m;
}

/// The JS SDK's `HtmlOptions` of [o]: what the native comma list says, by name.
Map<String, Object?> webHtmlOptions(HtmlOptions o) {
  final m = <String, Object?>{};
  if (o.ids) m['ids'] = true;
  if (o.geometry) m['geometry'] = true;
  if (o.confidenceBelow != null) m['confidenceBelow'] = o.confidenceBelow;
  if (o.ledger) m['ledger'] = true;
  if (o.pretty) m['pretty'] = true;
  if (o.furnitureDrop) m['furnitureDrop'] = true;
  if (!o.milestones) m['milestones'] = false;
  if (o.initialsPlain) m['initialsPlain'] = true;
  if (o.alignStyle) m['alignStyle'] = true;
  if (o.assets != null) m['assets'] = o.assets;
  if (o.assetPrefix != null) m['assetPrefix'] = o.assetPrefix;
  return m;
}

/// The category the C ABI would give an error the JS SDK names only by [name] (the SDK carries no category). The names are SPEC's and the C header's.
EmonErrorCategory webErrorCategory(String name) {
  const limits = {
    'input-bytes', 'stream-bytes', 'total-bytes', 'objects', 'ref-depth', 'operators', 'form-depth', 'pattern-depth', 'pages', 'pixels', 'ocr-requests', 'note-bodies', 'note-refs',
    'asset-placements', 'fonts-per-doc', 'stage-ms', 'workers',
  };
  if (limits.contains(name)) return EmonErrorCategory.limit;
  return switch (name) {
    'usage' => EmonErrorCategory.usage,
    'no-input' => EmonErrorCategory.noInput,
    'password-required' => EmonErrorCategory.passwordRequired,
    'password-incorrect' => EmonErrorCategory.passwordIncorrect,
    'encryption-unsupported' => EmonErrorCategory.encryptionUnsupported,
    'unsupported-format' || 'unsupported-image' => EmonErrorCategory.unsupportedFormat,
    'no-header' || 'no-startxref' || 'bad-xref' || 'no-root' || 'no-pages' || 'undecodable' => EmonErrorCategory.damaged,
    'source-changed' => EmonErrorCategory.sourceChanged,
    'io' => EmonErrorCategory.io,
    'unsupported' => EmonErrorCategory.unsupported,
    final n when n.startsWith('transcript') || n.startsWith('plan') => EmonErrorCategory.plan,
    _ => EmonErrorCategory.internal,
  };
}

/// Width and height of a PNG, from its header.
({int width, int height}) pngSize(Uint8List png) {
  if (png.length < 24) return (width: 0, height: 0);
  final d = ByteData.sublistView(png);
  return (width: d.getUint32(16), height: d.getUint32(20));
}

List<int> _box(Box b) => [b.x, b.y, b.w, b.h];

/// An [OcrReading] of an engine written in Dart, in the form the JS SDK's OCR port answers with (`ocr.js`): blocks, lines and words with boxes, word confidence and word language.
///
/// This is a SUBSET of what the native path writes as OIL: the JS port's form has no place for line confidence or quads, so those are not carried; the SDK derives the capability bits
/// from what is present, and the library checks the answer as it checks every one.
Map<String, Object?> readingToWeb(OcrReading r, OcrAdapterInfo info) {
  final blocks = [for (final b in r.blocks) {'box': _box(b.box)}];
  final lines = <Map<String, Object?>>[];
  final words = <Map<String, Object?>>[];
  for (var li = 0; li < r.lines.length; li++) {
    final l = r.lines[li];
    lines.add({
      'box': _box(l.box),
      'block': blocks.isEmpty ? -1 : l.block,
      'type': 0,
      if (l.quad != null && l.quad!.length == 8) 'baseline': [l.quad![6], l.quad![7], l.quad![4], l.quad![5]],
    });
    for (final w in l.words) {
      words.add({'box': _box(w.box), 'conf': w.confidence < 0 ? 0 : w.confidence, 'line': li, 'text': w.text, if (w.language != null) 'lang': w.language});
    }
  }
  return {
    'engine': info.engine,
    'observed': info.observed,
    'granularity': info.granularity,
    'blocks': blocks,
    'lines': lines,
    'words': words,
  };
}

/// What the web backend says of the engines of kin's table (`Emon.engines`): the same rows as the native one, each with what this platform really has. [tesseract] and [pdfium]
/// say whether the app's [WebAssets] configure them; [dartEngines] are the engines written in Dart that were registered.
///
/// The web has no `ppocr` row as available: the JS SDK drives PP-OCR only through its optional wasm build and onnxruntime-web, which this backend does not wire, and the native adapters
/// (Apple Vision, Windows.Media.Ocr, ML Kit) are not there. Each is listed with its reason, never silently absent.
List<EngineInfo> webEngineList({required bool tesseract, required bool pdfium, required List<OcrAdapter> dartEngines}) {
  EngineInfo row(String name, String kind, String impl, EngineState state, String reason, {bool calibrated = false, String role = 'reader'}) => EngineInfo(
        name: name,
        kind: kind,
        implementation: impl,
        state: state,
        registered: state == EngineState.builtIn,
        calibrated: calibrated,
        role: role,
        needs: const [],
        downloadBytes: 0,
        reason: reason,
      );
  return [
    row('tesseract', 'ocr', 'js-port', tesseract ? EngineState.builtIn : EngineState.unavailable,
        tesseract ? 'tesseract.js; it loads its own models from WebTesseract.lang' : 'no WebAssets.tesseract: the app has not configured tesseract.js',
        calibrated: true, role: 'incumbent'),
    row('ppocr', 'ocr', 'js-port', EngineState.unavailable, 'the web backend does not wire the JS SDK\'s experimental PP-OCR port'),
    row('apple-vision', 'ocr', 'native-adapter', EngineState.unavailable, 'a native engine: not on the web'),
    row('windows-media-ocr', 'ocr', 'native-adapter', EngineState.unavailable, 'a native engine: not on the web'),
    row('mlkit-text', 'ocr', 'platform-plugin', EngineState.unavailable, 'a native engine: not on the web'),
    row('pdfium', 'render', 'js-port', pdfium ? EngineState.builtIn : EngineState.unavailable,
        pdfium ? 'pdfium\'s WebAssembly build' : 'no WebAssets.pdfium: scanned PDFs and figures cannot be rendered', role: 'renderer'),
  ].map((e) {
    for (final a in dartEngines) {
      if (a.name == e.name) return e.copyWith(state: EngineState.builtIn, registered: true, reason: '');
    }
    return e;
  }).toList()
    ..addAll([
      for (final a in dartEngines)
        if (![
          'tesseract', 'ppocr', 'apple-vision', 'windows-media-ocr', 'mlkit-text', 'pdfium'
        ].contains(a.name))
          row(a.name, 'ocr', 'platform-plugin', EngineState.builtIn, ''),
    ]);
}
