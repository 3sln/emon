// The web backend in a real browser (DEVLOG D9xx): the Dart API over emon's wasm build and the JS SDK, against the JS SDK driven directly.
//
//   bb dart --web-test           stages the files this needs (test/web_stage/) and runs `dart test -p chrome test/web`
//
// The parity claim is byte for byte: the Model's JSON, its HTML and its transcript from Dart equal what the SDK gives for the same input and options.
@TestOn('browser')
library;

import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:test/test.dart';

const stage = '../web_stage/';

Future<Uint8List?> fetchBytes(String path) async {
  final res = await (globalContext.callMethod<JSPromise<JSObject>>('fetch'.toJS, path.toJS)).toDart;
  if (!(res['ok'] as JSBoolean).toDart) return null;
  final buf = await (res.callMethod<JSPromise<JSArrayBuffer>>('arrayBuffer'.toJS)).toDart;
  return buf.toDart.asUint8List();
}

Future<Uint8List> sample(String name) async => (await fetchBytes('${stage}samples/$name'))!;

Map<String, Object?> jsConfig({required bool tess, required bool pdfium}) {
  final base = Uri.base.resolve('${stage}emon/').toString();
  return {
    'sdk': '${base}sdk/',
    'wasm': '${base}emon.wasm',
    if (tess)
      'tesseract': {
        'module': '${base}vendor/tesseract/tesseract.esm.min.js',
        'worker': '${base}vendor/tesseract/worker.min.js',
        'core': '${base}vendor/tesseract/core',
        'lang': '${base}vendor/tesseract/lang',
      },
    if (pdfium) 'pdfium': {'module': '${base}vendor/pdfium/pdfium.mjs', 'wasm': '${base}vendor/pdfium/pdfium.wasm'},
  };
}

const webAssets = WebAssets(
  sdk: '${stage}emon/sdk/',
  wasm: '${stage}emon/emon.wasm',
  tesseract: WebTesseract(
    module: '${stage}emon/vendor/tesseract/tesseract.esm.min.js',
    worker: '${stage}emon/vendor/tesseract/worker.min.js',
    core: '${stage}emon/vendor/tesseract/core',
    lang: '${stage}emon/vendor/tesseract/lang',
  ),
  pdfium: WebPdfium(module: '${stage}emon/vendor/pdfium/pdfium.mjs', wasm: '${stage}emon/vendor/pdfium/pdfium.wasm'),
);

/// The JS SDK's own result for [bytes].
Future<JSObject> reference(Uint8List bytes, Map<String, Object?> options, {required bool ocr, Map<String, Object?> html = const {}, Uint8List? password}) async {
  final mod = await importModule('${Uri.base.resolve('reference.mjs')}'.toJS).toDart;
  return await mod
      .callMethod<JSPromise<JSObject>>('reference'.toJS, jsConfig(tess: ocr, pdfium: ocr).jsify(), bytes.toJS, options.jsify(), {'html': html, if (password != null) 'password': utf8.decode(password)}.jsify())
      .toDart;
}

String str(JSObject o, String k) => (o[k] as JSString).toDart;

void main() {
  late Emon bare;
  late bool haveEngines;
  late bool optedOut;

  setUpAll(() async {
    haveEngines = await fetchBytes('${stage}emon/vendor/tesseract/tesseract.esm.min.js') != null;
    // a missing engine is a FAILURE (the gate, `bb dart-web`); only the deliberate local opt-out (`--no-vendor`, which stages this marker) skips the OCR and render cases
    optedOut = await fetchBytes('${stage}emon/no-engines') != null;
    if (!haveEngines && !optedOut) {
      fail('the tesseract.js and pdfium vendor files are not staged: run `bb dart --web-test` (it prepares the pinned set), or pass --no-vendor to skip the OCR and render cases locally');
    }
    bare = await Emon.open(web: const WebAssets(sdk: '${stage}emon/sdk/', wasm: '${stage}emon/emon.wasm'));
  });
  tearDownAll(() => bare.close());

  group('a born-digital PDF (no engine is asked for)', () {
    test('the Model equals the JS SDK\'s, byte for byte: JSON, pretty JSON, HTML', () async {
      final bytes = await sample('invoice.pdf');
      final ref = await reference(bytes, {}, ocr: false);
      final model = await bare.convert(EmonInput.bytes(bytes));
      expect(await model.json(), str(ref, 'json'));
      expect(await model.json(pretty: true), str(ref, 'jsonPretty'));
      expect(await model.html(), str(ref, 'html'));
      expect(model.stats.inputBytes, bytes.length);
      await model.dispose();
    });

    test('HtmlOptions reach the SDK', () async {
      final bytes = await sample('invoice.pdf');
      final ref = await reference(bytes, {}, ocr: false, html: {'ids': true, 'geometry': true, 'pretty': true});
      final html = await bare.convertToHtml(EmonInput.bytes(bytes), const ConvertOptions(), const HtmlOptions(ids: true, geometry: true, pretty: true));
      expect(html, str(ref, 'html'));
    });

    test('convertToJson and the parsed object', () async {
      final bytes = await sample('invoice.pdf');
      final ref = await reference(bytes, {}, ocr: false);
      expect(await bare.convertToJson(EmonInput.bytes(bytes)), str(ref, 'json'));
      final m = await bare.convert(EmonInput.bytes(bytes));
      expect(await m.object(), isA<Map>());
      await m.dispose();
    });

    test('a recording equals the SDK\'s, and replays to the same Model', () async {
      final bytes = await sample('invoice.pdf');
      final ref = await reference(bytes, {'record': true}, ocr: false);
      final model = await bare.convert(EmonInput.bytes(bytes), const ConvertOptions(record: true));
      final t = await model.transcript();
      final jsT = ref['transcript'] as JSObject;
      final jsEntries = (globalContext['Array'] as JSObject).callMethod<JSArray<JSArray<JSAny?>>>('from'.toJS, jsT.callMethod<JSAny>('entries'.toJS)).toDart;
      expect(t.keys.toSet(), {for (final e in jsEntries) (e.toDart[0] as JSString).toDart});
      for (final e in jsEntries) {
        expect(t[(e.toDart[0] as JSString).toDart], (e.toDart[1] as JSUint8Array).toDart, reason: (e.toDart[0] as JSString).toDart);
      }
      final replay = await bare.convert(EmonInput.bytes(bytes), ConvertOptions(transcript: t));
      expect(await replay.json(), await model.json());
      expect(await replay.html(), await model.html());
      await model.dispose();
      await replay.dispose();
    });

    test('intake says what the file is', () async {
      final bytes = await sample('invoice.pdf');
      final r = await bare.intake(EmonInput.bytes(bytes));
      expect(r.format, 'pdf');
      expect(r.pages, greaterThan(0));
      expect(r.encrypted, isFalse);
      expect(r.bytes, bytes.length);
      expect(r.line, startsWith('pdf '));
    });

    test('transcript() without record is a usage error', () async {
      final m = await bare.convert(EmonInput.bytes(await sample('invoice.pdf')));
      await expectLater(m.transcript(), throwsA(isA<EmonError>().having((e) => e.category, 'category', EmonErrorCategory.usage)));
      await m.dispose();
    });
  });

  group('errors are named, as on the native backend', () {
    test('an encrypted PDF without its password', () async {
      final bytes = await sample('crypt-aes-128-user.pdf');
      await expectLater(
        bare.convert(EmonInput.bytes(bytes)),
        throwsA(isA<EmonError>().having((e) => e.name, 'name', 'password-required').having((e) => e.category, 'category', EmonErrorCategory.passwordRequired)),
      );
    });

    test('not a document', () async {
      await expectLater(
        bare.convert(EmonInput.bytes(Uint8List.fromList(utf8.encode('this is not a PDF or an image at all, just some words')))),
        throwsA(isA<EmonError>().having((e) => e.category, 'category', EmonErrorCategory.unsupportedFormat)),
      );
    });

    test('a limit is named', () async {
      await expectLater(
        bare.convert(EmonInput.bytes(await sample('invoice.pdf')), const ConvertOptions(limits: {'input-bytes': 100})),
        throwsA(isA<EmonError>().having((e) => e.name, 'name', 'input-bytes').having((e) => e.category, 'category', EmonErrorCategory.limit)),
      );
    });

    test('a file path cannot be read on the web', () async {
      await expectLater(bare.convert(EmonInput.file('/x.pdf')), throwsA(isA<EmonError>().having((e) => e.category, 'category', EmonErrorCategory.usage)));
    });
  });

  group('engines and packs say what the web has', () {
    test('engines() mirrors the SDK: only what is configured is usable', () async {
      final e = {for (final x in await bare.engines()) x.name: x};
      expect(e['tesseract']!.usable, isFalse);
      expect(e['tesseract']!.reason, contains('WebAssets.tesseract'));
      expect(e['pdfium']!.usable, isFalse);
      expect(e['apple-vision']!.usable, isFalse);
      expect(bare.canRender, isFalse);
      final p = await bare.prepare();
      expect(p.verdict, PrepareVerdict.blocked);
    });

    test('packs: nothing is fetched or stored; ensure says so', () async {
      expect(bare.packs.mode, PackMode.never);
      expect(bare.packs.isInstalled('tess-fast-eng'), isFalse);
      await expectLater(bare.packs.ensure(['tess-fast-eng']), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'pack-fetch-unavailable')));
    });

    test('native adapters are reported as not running here', () async {
      final e = await Emon.open(web: const WebAssets(sdk: '${stage}emon/sdk/', wasm: '${stage}emon/emon.wasm'), nativeAdapters: const [NativeAdapter(name: 'tesseract')]);
      expect((await e.notes()).single, contains('native adapters do not run on the web'));
      await e.close();
    });
  });

  group('with tesseract.js and pdfium', () {
    late Emon full;
    setUpAll(() async {
      if (!haveEngines) return;
      full = await Emon.open(web: webAssets, defaultEngine: OcrEngine.tesseract);
    });
    tearDownAll(() async {
      if (haveEngines) await full.close();
    });

    test('engines() lists them as built in', () async {
      if (!haveEngines) {
        markTestSkipped('no engines staged (--no-vendor)');
        return;
      }
      final e = {for (final x in await full.engines()) x.name: x};
      expect(e['tesseract']!.state, EngineState.builtIn);
      expect(e['pdfium']!.state, EngineState.builtIn);
      expect(full.canRender, isTrue);
      expect((await full.prepare()).verdict, PrepareVerdict.ready);
    });

    for (final name in ['image-statement.png', 'scan-plain.pdf']) {
      test('$name (OCR) equals the JS SDK\'s, byte for byte', () async {
        if (!haveEngines) {
          markTestSkipped('no engines staged (--no-vendor)');
          return;
        }
        final bytes = await sample(name);
        final ref = await reference(bytes, {}, ocr: true);
        final model = await full.convert(EmonInput.bytes(bytes));
        expect(await model.json(), str(ref, 'json'));
        expect(await model.html(), str(ref, 'html'));
        // the page was read: words, not an unread page
        expect(await model.html(), contains('<p'));
        await model.dispose();
      }, timeout: const Timeout(Duration(minutes: 5)));
    }

    test('an engine written in Dart answers through the same port', () async {
      final fake = _FakeAdapter();
      final e = await Emon.open(web: webAssets, adapters: [fake], defaultEngine: OcrEngine.mlkitText);
      final m = await e.convert(EmonInput.bytes(await sample('image-statement.png')), const ConvertOptions(ocr: OcrMode.always));
      final html = await m.html();
      expect(fake.requests, greaterThan(0));
      expect(html, contains('Dart engine says hello'));
      expect(((await e.engines()).firstWhere((x) => x.name == 'mlkit-text')).state, EngineState.builtIn);
      await m.dispose();
      await e.close();
    }, timeout: const Timeout(Duration(minutes: 2)));
  });
}

class _FakeAdapter extends OcrAdapter {
  int requests = 0;

  @override
  String get name => 'mlkit-text';

  @override
  OcrAdapterInfo get info => const OcrAdapterInfo(engine: 4, caps: OilCap.blocks | OilCap.wconf, native: OilCap.wconf, config: 'x', observed: 'fake-dart-engine;1');

  @override
  Future<OcrReading?> read(OcrRequest request) async {
    requests++;
    final w = request.width, h = request.height;
    final words = [for (final t in 'Dart engine says hello'.split(' ')) t];
    var x = 10;
    final ws = <OcrWord>[];
    for (final t in words) {
      ws.add(OcrWord(t, Box(x, 10, 10 * t.length, 20), confidence: 900, language: 'eng'));
      x += 10 * t.length + 10;
    }
    return OcrReading(
      blocks: [Box(10, 10, x - 20, 20)].map(OcrBlock.new).toList(),
      lines: [OcrLine(ws, box: Box(10, 10, (x - 20).clamp(1, w), 20.clamp(1, h)), block: 0)],
    );
  }
}
