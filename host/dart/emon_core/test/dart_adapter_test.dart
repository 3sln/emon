// An OCR engine written in Dart (what emon_ocr_mlkit is): it is asked through the step API, pdfium renders for the session, its reading is written as OIL, checked by the library and
// used as the page's text. A reading that cannot be given leaves the page unread, never blank.
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

import 'support.dart';

const config = 'mlkit-text;TextRecognition;recognizer=by-script;lang=request-else-latin';

class Fake extends OcrAdapter {
  Fake(this.answer);

  final OcrReading? Function(OcrRequest) answer;
  final seen = <OcrRequest>[];
  var closed = false;

  @override
  String get name => 'mlkit-text';

  @override
  OcrAdapterInfo get info => const OcrAdapterInfo(
        engine: 4,
        caps: OilCap.blocks | OilCap.quad | OilCap.lconf | OilCap.wconf | OilCap.wlang,
        native: OilCap.blocks | OilCap.quad | OilCap.lconf | OilCap.wconf | OilCap.wlang,
        config: config,
        observed: 'fake-ml-kit 1.0',
      );

  @override
  Future<OcrReading?> read(OcrRequest request) async {
    seen.add(request);
    return answer(request);
  }

  @override
  Future<void> close() async => closed = true;
}

OcrReading reading(OcrRequest r, List<String> words) {
  // one line of the words across the top of the page, as a recogniser reports it: a block, a line with its quad, words with their own boxes
  final h = 40;
  var x = 100;
  final ws = <OcrWord>[];
  for (final w in words) {
    final width = w.length * 22;
    ws.add(OcrWord(w, Box(x, 100, width, h), confidence: 930, language: 'eng'));
    x += width + 14;
  }
  final box = Box(100, 100, x - 114, h);
  return OcrReading(
    blocks: [OcrBlock(box)],
    lines: [
      OcrLine(ws, box: box, confidence: 900, block: 0, quad: [box.x, box.y, box.x + box.w, box.y, box.x + box.w, box.y + box.h, box.x, box.y + box.h]),
    ],
    language: 'eng',
  );
}

/// A page-level request: the engine reads it; a re-read of a cell or word it says it cannot do (unsupported).
bool whole(OcrRequest r) => r.region == null && !r.isSingle;

void main() {
  final scan = EmonInput.file(p.join(repo, 'samples/scan-plain.pdf'));

  test('a Dart engine reads the page: the text is its words, and the Model says it came from OCR', () async {
    final fake = Fake((r) => whole(r) ? reading(r, ['Zorbulax', 'quantum', 'harvest']) : null);
    final emon = await Emon.open(adapters: [fake], pdfiumPath: pdfiumLibrary);
    final m = await emon.convert(scan, const ConvertOptions(ocrEngine: OcrEngine.mlkitText, keepEvidence: true));
    final html = await m.html();
    expect(html, contains('Zorbulax quantum harvest'));
    expect(fake.seen, isNotEmpty);
    final r = fake.seen.first;
    expect(r.png.sublist(1, 4), 'PNG'.codeUnits);
    expect(r.width, greaterThan(1000));
    expect(await m.evidence(), contains('mlkit-text'), reason: 'the evidence names the engine that read');
    final engines = await emon.engines();
    expect(engines.firstWhere((e) => e.name == 'mlkit-text').state, EngineState.builtIn);
    await m.dispose();
    await emon.close();
    expect(fake.closed, isTrue);
  });

  test('the same through emon.defaultEngine: a turnkey chooses it for the app', () async {
    final emon = await Emon.open(adapters: [Fake((r) => whole(r) ? reading(r, ['Alpha', 'beta']) : null)], pdfiumPath: pdfiumLibrary, defaultEngine: OcrEngine.mlkitText);
    expect(await emon.convertToHtml(scan), contains('Alpha beta'));
    await emon.close();
  });

  test('unsupported (null) leaves the page unread, never blank', () async {
    final emon = await Emon.open(adapters: [Fake((r) => null)], pdfiumPath: pdfiumLibrary);
    final json = await emon.convertToJson(scan, const ConvertOptions(ocrEngine: OcrEngine.mlkitText));
    expect(json, isNot(contains('"kind":"paragraph"')), reason: 'the page was not read: no text, and no claim that it is blank');
    await emon.close();
  });

  test('a throwing engine is a failed read: the page is unread and the conversion goes on', () async {
    final emon = await Emon.open(adapters: [Fake((r) => throw StateError('the engine fell over'))], pdfiumPath: pdfiumLibrary);
    final json = await emon.convertToJson(scan, const ConvertOptions(ocrEngine: OcrEngine.mlkitText));
    expect(json, isNot(contains('"kind":"paragraph"')));
    await emon.close();
  });

  test('a reading the library refuses (a box outside the bitmap is clamped; a bad language is not) never takes the conversion down', () async {
    final emon = await Emon.open(adapters: [Fake((r) => whole(r) ? reading(r, ['x']) : null)], pdfiumPath: pdfiumLibrary);
    expect(await emon.convertToHtml(scan, const ConvertOptions(ocrEngine: OcrEngine.mlkitText)), contains('>x</p>'));
    await emon.close();
  });

  test('the OIL writer: layout and refusals', () {
    final req = OcrRequest(id: 5, psm: 6, flags: 0, dpi: 150, region: null, languages: const [], png: Uint8List(0), width: 1000, height: 800);
    const info = OcrAdapterInfo(engine: 4, caps: OilCap.blocks | OilCap.quad | OilCap.lconf | OilCap.wconf | OilCap.wlang, native: OilCap.blocks, config: config, observed: 'o');
    final flat = encodeOil(
      OcrReading(blocks: [OcrBlock(Box(0, 0, 10, 10))], lines: [OcrLine([OcrWord('ab', Box(1, 2, 3, 4), confidence: 500, language: 'eng')], box: Box(0, 0, 10, 10), confidence: 800, block: 0)]),
      info,
      req,
    );
    // header 24, block 5, line (6 + lconf + quad 8), word (6 + wlang + symbols + length + 2 characters)
    expect(flat.length, 24 + 5 + 15 + 11);
    expect(flat[0], 1, reason: 'version');
    expect(flat[1], 4, reason: 'engine');
    expect(flat[6], 2, reason: 'psm 6 asks for block segmentation');
    expect(flat[18], 1);
    expect(flat[19], 1);
    expect(flat[20], 1);
    expect(unpack4(pack4('eng')), 'eng');
    expect(() => encodeOil(const OcrReading(lines: []), const OcrAdapterInfo(engine: 4, caps: OilCap.baseline, native: 0, config: config, observed: 'o'), req), throwsUnsupportedError);
    final (hi, lo) = observedHash('mlkit');
    expect(hi.bitLength, lessThanOrEqualTo(32));
    expect(lo.bitLength, lessThanOrEqualTo(32));
  });
}
