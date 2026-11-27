// The API mapping of the web backend (DEVLOG D9xx), as pure Dart: runs on the VM, needs no browser and no library.
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:emon_core/src/web_mapping.dart';
import 'package:test/test.dart';

void main() {
  hashTests();
  group('ConvertOptions', () {
    test('default options map to nothing', () => expect(webConvertOptions(const ConvertOptions()), isEmpty));

    test('every setting has the SDK\'s name', () {
      final m = webConvertOptions(const ConvertOptions(
        locale: 'de',
        ocr: OcrMode.always,
        furniture: 'keep',
        assets: 'images',
        assetDpi: 150,
        assetPixels: 1000,
        assetBytes: 2000,
        assetsBytes: 3000,
        assetSignatures: false,
        keepEvidence: true,
        ocrScope: 'witness',
        maxRounds: 5,
        fetchRounds: 6,
        limits: {'pages': 3},
        producer: {'host': 'dart'},
        chunk: 512,
        record: true,
      ));
      expect(m, {
        'locale': 'de',
        'ocr': 'always',
        'furniture': 'keep',
        'assets': 'images',
        'assetDpi': 150,
        'assetPixels': 1000,
        'assetBytes': 2000,
        'assetsBytes': 3000,
        'assetSignatures': false,
        'keepEvidence': true,
        'ocrScope': 'witness',
        'maxRounds': 5,
        'fetchRounds': 6,
        'limits': {'pages': 3},
        'producer': {'host': 'dart'},
        'chunk': 512,
        'record': true,
      });
    });

    test('Tesseract, the default engine, is never written; another is', () {
      expect(webConvertOptions(const ConvertOptions(ocrEngine: OcrEngine.tesseract)), isEmpty);
      expect(webConvertOptions(const ConvertOptions(), engine: OcrEngine.tesseract), isEmpty);
      expect(webConvertOptions(const ConvertOptions(), engine: OcrEngine.mlkitText), {'ocrEngine': 'mlkit-text'});
      expect(webConvertOptions(const ConvertOptions(ocrEngine: OcrEngine.ppocrAlone), engine: OcrEngine.mlkitText), {'ocrEngine': 'ppocr-alone'});
    });
  });

  test('HtmlOptions map to the SDK\'s names, defaults to nothing', () {
    expect(webHtmlOptions(const HtmlOptions()), isEmpty);
    expect(
      webHtmlOptions(const HtmlOptions(ids: true, geometry: true, confidenceBelow: 400, ledger: true, pretty: true, furnitureDrop: true, milestones: false, initialsPlain: true, alignStyle: true, assets: 'external', assetPrefix: 'x/')),
      {
        'ids': true,
        'geometry': true,
        'confidenceBelow': 400,
        'ledger': true,
        'pretty': true,
        'furnitureDrop': true,
        'milestones': false,
        'initialsPlain': true,
        'alignStyle': true,
        'assets': 'external',
        'assetPrefix': 'x/',
      },
    );
  });

  test('error names carry the category the C ABI gives them', () {
    expect(webErrorCategory('password-required'), EmonErrorCategory.passwordRequired);
    expect(webErrorCategory('password-incorrect'), EmonErrorCategory.passwordIncorrect);
    expect(webErrorCategory('input-bytes'), EmonErrorCategory.limit);
    expect(webErrorCategory('pages'), EmonErrorCategory.limit);
    expect(webErrorCategory('bad-xref'), EmonErrorCategory.damaged);
    expect(webErrorCategory('unsupported-format'), EmonErrorCategory.unsupportedFormat);
    expect(webErrorCategory('source-changed'), EmonErrorCategory.sourceChanged);
    expect(webErrorCategory('transcript-miss'), EmonErrorCategory.plan);
    expect(webErrorCategory('io'), EmonErrorCategory.io);
    expect(webErrorCategory('something-new'), EmonErrorCategory.internal);
  });

  test('a PNG\'s size is read from its header', () {
    final png = Uint8List(33);
    png.buffer.asByteData()
      ..setUint32(16, 640)
      ..setUint32(20, 480);
    expect(pngSize(png), (width: 640, height: 480));
    expect(pngSize(Uint8List(3)), (width: 0, height: 0));
  });

  group('an OcrReading in the SDK\'s port form', () {
    const info = OcrAdapterInfo(engine: 4, caps: 0, native: 0, config: 'x', observed: 'fake;1', granularity: 2);
    final reading = OcrReading(
      blocks: const [OcrBlock(Box(0, 0, 100, 40))],
      lines: const [
        OcrLine(
          [OcrWord('Hello', Box(0, 0, 40, 20), confidence: 900, language: 'eng'), OcrWord('world', Box(50, 0, 40, 20))],
          box: Box(0, 0, 100, 20),
          block: 0,
          quad: [0, 0, 100, 0, 100, 20, 0, 20],
        ),
      ],
    );

    test('blocks, lines and words, boxes as x y w h', () {
      final m = readingToWeb(reading, info);
      expect(m['engine'], 4);
      expect(m['observed'], 'fake;1');
      expect(m['blocks'], [
        {'box': [0, 0, 100, 40]}
      ]);
      expect(m['lines'], [
        {'box': [0, 0, 100, 20], 'block': 0, 'type': 0, 'baseline': [0, 20, 100, 20]}
      ]);
      expect(m['words'], [
        {'box': [0, 0, 40, 20], 'conf': 900, 'line': 0, 'text': 'Hello', 'lang': 'eng'},
        {'box': [50, 0, 40, 20], 'conf': 0, 'line': 0, 'text': 'world'},
      ]);
    });

    test('a line has no block when the reading has none', () {
      final m = readingToWeb(OcrReading(lines: [reading.lines.first]), info);
      expect((m['lines'] as List).single, containsPair('block', -1));
    });
  });

  group('engines on the web', () {
    test('what is configured is built in; the native engines say why not', () {
      final e = {for (final x in webEngineList(tesseract: true, pdfium: false, dartEngines: const [])) x.name: x};
      expect(e['tesseract']!.state, EngineState.builtIn);
      expect(e['tesseract']!.usable, isTrue);
      expect(e['pdfium']!.state, EngineState.unavailable);
      expect(e['pdfium']!.reason, contains('WebAssets.pdfium'));
      for (final n in ['apple-vision', 'windows-media-ocr', 'mlkit-text', 'ppocr']) {
        expect(e[n]!.usable, isFalse, reason: n);
        expect(e[n]!.reason, isNotEmpty, reason: n);
      }
    });

    test('with nothing configured nothing is usable', () {
      expect(webEngineList(tesseract: false, pdfium: false, dartEngines: const []).where((e) => e.usable), isEmpty);
    });
  });
}

// The hash that makes an observed identity two header cells must be the same integer on every platform (FNV-1a 64, kin's `oil-obs-hash`); these are the values the 64-bit
// native-int implementation gave before it was written in halves.
void hashTests() {
  test('observedHash is FNV-1a 64', () {
    expect(observedHash(''), (0xcbf29ce4.toSigned(32), 0x84222325.toSigned(32)));
    // FNV-1a 64 of "a" is af63dc4c8601ec8c, of "foobar" 85944171f73967e8 (the published vectors)
    expect(observedHash('a'), (0xaf63dc4c.toSigned(32), 0x8601ec8c.toSigned(32)));
    expect(observedHash('foobar'), (0x85944171.toSigned(32), 0xf73967e8.toSigned(32)));
  });
}
