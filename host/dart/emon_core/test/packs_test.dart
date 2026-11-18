// The D896 surface over real engines: Tesseract's adapter library (from the unpacked C archive), a pack directory of our own for each test, and a local server standing in
// for the pack mirror. Every download here is made by dart:io and verified twice: by the fetcher and by the C library.
import 'dart:io';
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

import 'support.dart';

final scan = EmonInput.file(p.join(repo, 'samples/scan-plain.pdf'));
String? golden;
late PackServer server;
late Directory tmp;

Future<Emon> open({PackMode mode = PackMode.auto, PackNeededHook? hook, List<String>? sources}) async {
  final dir = Directory(p.join(tmp.path, 'packs-${DateTime.now().microsecondsSinceEpoch}'));
  return Emon.open(
    nativeAdapters: [NativeAdapter(name: 'tesseract', path: adapterLibrary('tesseract'))],
    pdfiumPath: pdfiumLibrary,
    packDirectory: dir.path,
    packMode: mode,
    packSources: sources ?? [server.base],
    onPackNeeded: hook,
  );
}

bool readsPage(String html) => html.contains('Adaptive Sampling in Sparse Fields');

void main() {
  setUpAll(() async {
    tmp = Directory.systemTemp.createTempSync('emon-packs-test-');
    server = await PackServer.start();
    golden = fixture('samples/scan-plain.html').readAsStringSync();
  });
  tearDownAll(() async {
    await server.stop();
    tmp.deleteSync(recursive: true);
  });

  test('engines() says what exists and what it needs; prepare() says what is missing', () async {
    final emon = await open();
    final engines = await emon.engines();
    final tess = engines.firstWhere((e) => e.name == 'tesseract');
    expect(tess.registered, isTrue);
    expect(tess.state, EngineState.downloadable, reason: 'registered, packs missing, and the mode may fetch them');
    expect(tess.downloadBytes, greaterThanOrEqualTo(4113088));
    expect(tess.needs.map((n) => n.id), containsAll(['tess-fast-eng', 'tess-osd']));
    expect(tess.needs.first.licence, 'Apache-2.0');
    expect(engines.firstWhere((e) => e.name == 'pdfium').state, EngineState.builtIn);
    expect(engines.firstWhere((e) => e.name == 'ppocr').usable, isFalse);

    final prep = await emon.prepare();
    expect(prep.verdict, PrepareVerdict.downloadable);
    expect(prep.missingPackIds, containsAll(['tess-fast-eng', 'tess-osd']));
    expect(prep.missingBytes, greaterThanOrEqualTo(tess.downloadBytes));
    final german = await emon.prepare(const ConvertOptions(locale: 'de-DE'));
    expect(german.missingPackIds, contains('tess-fast-deu'));
    final never = await emon.prepare(const ConvertOptions(ocr: OcrMode.never));
    expect(never.verdict, PrepareVerdict.ready);
    await emon.close();
  });

  test('packs.ensure fetches ahead of time with progress, and the conversion then needs no fetch', () async {
    final emon = await open();
    final events = <PackEvent>[];
    final sub = emon.packs.events.listen(events.add);
    final seen = <PackEvent>[];
    await emon.packs.ensure(['tess-fast-eng', 'tess-osd'], onProgress: seen.add);
    await sub.cancel();
    expect(emon.packs.isInstalled('tess-fast-eng'), isTrue);
    expect(seen.where((e) => e.kind == PackEventKind.start).map((e) => e.pack), ['tess-fast-eng', 'tess-osd']);
    expect(seen.where((e) => e.kind == PackEventKind.progress), isNotEmpty);
    expect(seen.where((e) => e.kind == PackEventKind.done).length, 2);
    expect(seen.every((e) => e.initiator == PackInitiator.app), isTrue);
    expect(events.length, seen.length, reason: 'the stream and the callback hear the same');
    final fetchedBefore = server.requests.length;
    expect((await emon.prepare()).verdict, PrepareVerdict.ready);
    final html = await emon.convertToHtml(scan);
    expect(html, golden, reason: 'Tesseract through the adapter library gives the golden of the samples');
    expect(server.requests.length, fetchedBefore, reason: 'nothing was fetched during the conversion');
    await emon.close();
  });

  test('with no hook a turnkey (auto) fetches what a conversion needs, on demand, and says so on packs.events', () async {
    final emon = await open();
    final events = <PackEvent>[];
    final sub = emon.packs.events.listen(events.add);
    final html = await emon.convertToHtml(scan);
    await sub.cancel();
    expect(readsPage(html), isTrue);
    expect(events.where((e) => e.kind == PackEventKind.done).map((e) => e.pack), contains('tess-fast-eng'));
    expect(events.every((e) => e.initiator == PackInitiator.library), isTrue);
    await emon.close();
  });

  test('onPackNeeded: decline leaves the page unread, with pack-missing in the notes, and a second conversion asks again', () async {
    final asked = <PackRequest>[];
    final requestsBefore = server.requests.length;
    final emon = await open(hook: (r) {
      asked.add(r);
      return const PackDecision.decline();
    });
    final html = await emon.convertToHtml(scan);
    expect(readsPage(html), isFalse);
    expect(asked, isNotEmpty);
    expect(asked.first.pack.id, 'tess-fast-eng');
    expect(asked.first.pack.bytes, 4113088);
    expect(asked.first.reason, 'conversion');
    expect(asked.first.requestEngine, 'tesseract');
    expect((await emon.notes()).join('\n'), contains('pack-missing:tess-fast-eng'));
    expect(server.requests.length, requestsBefore, reason: 'a decline fetches nothing');
    await emon.close();
  });

  test('onPackNeeded: the hook may take its time (it is async) and provide its own bytes, which are verified and used', () async {
    final eng = fixture('.cache/native/dl/eng.traineddata');
    final bytes = eng.existsSync() ? eng.readAsBytesSync() : File(p.join(unpackedArchive, 'share/emon/packs/tess-fast-eng/eng.traineddata')).readAsBytesSync();
    final osd = File(p.join(unpackedArchive, 'share/emon/packs/tess-osd/osd.traineddata')).readAsBytesSync();
    final provided = <String>[];
    final emon = await open(hook: (r) async {
      await Future<void>.delayed(const Duration(milliseconds: 200));
      provided.add(r.pack.id);
      return PackDecision.provide(bytes: r.pack.id == 'tess-fast-eng' ? bytes : osd);
    });
    final before = server.requests.length;
    final html = await emon.convertToHtml(scan);
    expect(readsPage(html), isTrue);
    expect(provided, contains('tess-fast-eng'));
    expect(server.requests.length, before, reason: 'the app provided every byte: nothing was fetched');
    expect(emon.packs.isInstalled('tess-fast-eng'), isTrue, reason: 'and the library kept them, verified');
    await emon.close();
  });

  test('provided bytes that are not the pack are refused: the page is unread, and nothing wrong is kept', () async {
    final emon = await open(hook: (r) => PackDecision.provide(bytes: Uint8List.fromList(List.filled(r.pack.bytes, 7))));
    final html = await emon.convertToHtml(scan);
    expect(readsPage(html), isFalse);
    expect(emon.packs.isInstalled('tess-fast-eng'), isFalse);
    await emon.close();
  });

  test('onPackNeeded: fetch (the default of auto) downloads through dart:io; substitute another engine leaves the page unread', () async {
    final emon = await open(hook: (r) => r.pack.id == 'tess-fast-eng' ? const PackDecision.fetch() : const PackDecision.decline());
    final html = await emon.convertToHtml(scan);
    expect(readsPage(html), isTrue);
    await emon.close();
    final sub = await open(hook: (r) => const PackDecision.substitute(OcrEngine.ppocr));
    expect(readsPage(await sub.convertToHtml(scan)), isFalse, reason: 'another engine would answer under its own name: the request stays unread');
    await sub.close();
  });

  test('mode never asks no hook and fetches nothing; cache uses what is there and never the network', () async {
    var asked = 0;
    final never = await open(mode: PackMode.never, hook: (r) {
      asked++;
      return const PackDecision.fetch();
    });
    expect(readsPage(await never.convertToHtml(scan)), isFalse);
    expect(asked, 0, reason: 'never wins over the hook');
    await expectLater(never.packs.ensure(['tess-fast-eng']), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'pack-fetch-unavailable')));
    await never.close();
    final before = server.requests.length;
    final cache = await open(mode: PackMode.cache);
    expect(readsPage(await cache.convertToHtml(scan)), isFalse);
    expect(server.requests.length, before);
    await cache.close();
  });

  test('a download that is not the pack is discarded: pack-hash-mismatch, the page unread, nothing kept', () async {
    final bad = await PackServer.start(corrupt: {'7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2'});
    final emon = await open(sources: [bad.base]);
    final events = <PackEvent>[];
    final sub = emon.packs.events.listen(events.add);
    await expectLater(emon.packs.ensure(['tess-fast-eng']), throwsA(isA<EmonError>().having((e) => e.name, 'name', 'pack-hash-mismatch:tess-fast-eng')));
    expect(events.map((e) => e.kind), contains(PackEventKind.failed));
    expect(emon.packs.isInstalled('tess-fast-eng'), isFalse);
    expect(readsPage(await emon.convertToHtml(scan)), isFalse);
    await sub.cancel();
    await emon.close();
    await bad.stop();
  });

  test('a download can be cancelled', () async {
    final slow = await PackServer.start(chunkDelay: const Duration(milliseconds: 40));
    final emon = await open(sources: [slow.base]);
    final token = CancelToken();
    final events = <PackEvent>[];
    final sub = emon.packs.events.listen((e) {
      events.add(e);
      if (e.kind == PackEventKind.progress) token.cancel();
    });
    await expectLater(emon.packs.ensure(['tess-osd'], cancel: token), throwsA(isA<EmonError>().having((e) => e.name, 'name', startsWith('pack-fetch-cancelled'))));
    expect(events.map((e) => e.kind), contains(PackEventKind.cancelled));
    expect(emon.packs.isInstalled('tess-osd'), isFalse);
    await sub.cancel();
    await emon.close();
    await slow.stop();
  });
}
