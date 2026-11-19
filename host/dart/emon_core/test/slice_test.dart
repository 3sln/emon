// The slice fixtures (test/corpus/synth/pdf, the seven documents `bb slice` holds every host to): the Dart API gives the bytes of the Rust goldens, one-shot and through the
// step API (in 512-byte chunks, so the lazy path is the one that ran).
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

import 'support.dart';

const docs = ['h-plain', 'h-keyword', 'h-inverted-numbered', 'h-numbered', 'initial-image', 'initial-vector', 'furn-informative'];
const full = HtmlOptions(ids: true, geometry: true, confidenceBelow: 950, ledger: true, pretty: true);

void main() {
  late Emon emon;
  late Emon lazy;

  setUpAll(() async {
    emon = await Emon.open();
    // no pdfium: a stepwise conversion then reads the input by the ranges it touches
    lazy = await Emon.open(pdfium: false);
  });
  tearDownAll(() async {
    await emon.close();
    await lazy.close();
  });

  test('the library is the one of this repository', () async {
    expect(emon.version, startsWith('0.'));
    expect(emon.canRender, isTrue, reason: 'pdfium is bundled beside libemon');
  });

  for (final d in docs) {
    group(d, () {
      late EmonInput input;
      late String goldenJson;
      late String goldenHtml;
      late String goldenFull;

      setUpAll(() {
        input = EmonInput.bytes(Uint8List.fromList(fixture('test/corpus/synth/pdf/$d.pdf').readAsBytesSync()));
        goldenJson = fixture('test/para/$d.json').readAsStringSync();
        goldenHtml = fixture('test/slice/$d.html').readAsStringSync();
        goldenFull = fixture('test/slice/$d.full.html').readAsStringSync();
      });

      test('one-shot: JSON, HTML and the full HTML are the goldens', () async {
        expect('${await emon.convertToJson(input, const ConvertOptions(), true)}\n', goldenJson);
        expect(await emon.convertToHtml(input), goldenHtml);
        expect(await emon.convertToHtml(input, const ConvertOptions(), full), goldenFull);
      });

      test('a Model gives the same bytes, and says what the loop did', () async {
        final m = await emon.convert(input);
        expect('${await m.json(pretty: true)}\n', goldenJson);
        expect(await m.html(), goldenHtml);
        expect(m.stats.inputBytes, fixture('test/corpus/synth/pdf/$d.pdf').lengthSync());
        await m.dispose();
      });

      test('the step API, 512 bytes at a time, gives the same bytes', () async {
        final m = await lazy.convert(input, const ConvertOptions(stepwise: true, chunk: 512));
        expect('${await m.json(pretty: true)}\n', goldenJson);
        expect(await m.html(), goldenHtml);
        expect(m.stats.sourceReads, greaterThan(1), reason: 'the input came in pieces');
        await m.dispose();
      });

      test('a file input gives the same bytes', () async {
        final m = await emon.convert(EmonInput.file(p.join(repo, 'test/corpus/synth/pdf/$d.pdf')));
        expect(await m.html(), goldenHtml);
        await m.dispose();
      });
    });
  }

  test('errors are named', () async {
    final notPdf = EmonInput.bytes(Uint8List.fromList('not a pdf at all'.codeUnits));
    await expectLater(emon.convert(notPdf), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'unsupported-format').having((e) => e.category, 'category', EmonErrorCategory.unsupportedFormat)));
    final encrypted = EmonInput.file(p.join(repo, 'test/pdf/p3/crypt-aes-128-user.pdf'));
    await expectLater(emon.convert(encrypted), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'password-required')));
    await expectLater(emon.convert(EmonInput.file(p.join(repo, 'test/pdf/p3/crypt-aes-128-user.pdf'), password: Uint8List.fromList('wrong'.codeUnits))), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'password-incorrect')));
    await expectLater(emon.convert(input0, const ConvertOptions(limits: {'input-bytes': 100})), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'input-bytes').having((e) => e.category, 'category', EmonErrorCategory.limit)));
  });

  test('intake says what a file is, cheaply', () async {
    final r = await emon.intake(EmonInput.file(p.join(repo, 'test/corpus/synth/pdf/h-plain.pdf')));
    expect(r.format, 'pdf');
    expect(r.pages, 2);
    expect(r.encrypted, isFalse);
  });

  test('assets of a document with pictures', () async {
    final m = await emon.convert(EmonInput.file(p.join(repo, 'test/assets/dct-rgb.pdf')), const ConvertOptions(assets: 'images'));
    expect(m.assets, isNotEmpty);
    final a = m.assets.first;
    final bytes = await a.bytes();
    expect(bytes.length, a.size);
    expect(sha256Hex(bytes), a.sha256);
    await m.dispose();
  });

  test('keep-evidence and the options text', () async {
    expect(const ConvertOptions(ocr: OcrMode.never, limits: {'pages': 50}, assetSignatures: true).toText(), 'ocr=never\nasset-signatures=on\nlimit.pages=50');
    expect(const HtmlOptions(ids: true, confidenceBelow: 900, assets: 'external', assetPrefix: 'a/').toText(), 'ids,confidence=900,assets=external,prefix=a/');
    final m = await emon.convert(EmonInput.file(p.join(repo, 'test/corpus/synth/pdf/h-plain.pdf')), const ConvertOptions(keepEvidence: true));
    expect(await m.evidence(), contains('emon-evidence'));
    await m.dispose();
  });
}

final input0 = EmonInput.bytes(Uint8List(200)..setAll(0, '%PDF-1.4 '.codeUnits));
