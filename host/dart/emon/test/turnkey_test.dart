// The turnkey on a Mac or a Linux box: the platform's adapters are registered, Tesseract reads a scan to the samples' golden, and (on a Mac) Apple Vision reads it when asked.
import 'dart:ffi';
import 'dart:io';

import 'package:emon/emon.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

final repo = p.normalize(p.absolute('../../..'));

// the packs of the C archive, as a store: `<id>/<file>`
Directory storeWithPacks() {
  final dist = Directory(p.join(repo, 'dist', 'c'));
  final arch = dist.listSync().whereType<File>().where((f) => f.path.endsWith('${Abi.current() == Abi.macosArm64 ? 'aarch64-apple-darwin' : 'x86_64-unknown-linux-gnu'}.tar.gz')).first;
  final tmp = Directory.systemTemp.createTempSync('emon-turnkey-');
  Process.runSync('tar', ['-xzf', arch.path, '-C', tmp.path]);
  final root = tmp.listSync().whereType<Directory>().first;
  return Directory(p.join(root.path, 'share', 'emon', 'packs'));
}

void main() {
  late Emon emon;
  setUpAll(() async => emon = await openEmon(packs: PackMode.never, packDirectory: storeWithPacks().path));
  tearDownAll(() => emon.close());

  test('the platform adapters are registered; Vision is the default on a Mac, Tesseract elsewhere', () async {
    final engines = await emon.engines();
    expect(engines.firstWhere((e) => e.name == 'tesseract').usable, isTrue);
    if (Platform.isMacOS) expect(engines.firstWhere((e) => e.name == 'apple-vision').usable, isTrue);
    expect(emon.defaultEngine, Platform.isMacOS ? OcrEngine.appleVision : OcrEngine.tesseract);
    expect(emon.canRender, isTrue);
  });

  test('a scan through Tesseract is the samples golden', () async {
    final html = await emon.convertToHtml(EmonInput.file(p.join(repo, 'samples/scan-plain.pdf')), const ConvertOptions(ocrEngine: OcrEngine.tesseract));
    expect(html, File(p.join(repo, 'samples/scan-plain.html')).readAsStringSync());
  });

  test('on a Mac a conversion that names no engine reads with Vision (the turnkey default)', () async {
    if (!Platform.isMacOS) return;
    final html = await emon.convertToHtml(EmonInput.file(p.join(repo, 'samples/scan-plain.pdf')));
    expect(html, contains('Adaptive Sampling'));
  }, timeout: const Timeout(Duration(minutes: 3)));

  test('prepare() with no engine named is ready on a Mac: Vision is built in, Tesseract\'s packs are conditional', () async {
    if (!Platform.isMacOS) return;
    final prepared = await emon.prepare(const ConvertOptions());
    expect(prepared.missingPackIds, isEmpty);
    expect(prepared.verdict, PrepareVerdict.ready);
  });

  test('Apple Vision, selected explicitly, reads it too, with Tesseract registered as its fallback', () async {
    if (!Platform.isMacOS) return;
    final html = await emon.convertToHtml(EmonInput.file(p.join(repo, 'samples/scan-plain.pdf')), const ConvertOptions(ocrEngine: OcrEngine.appleVision));
    expect(html, contains('Adaptive Sampling'));
  }, timeout: const Timeout(Duration(minutes: 3)));

  test('prepare() for Apple Vision calls Tesseract\'s packs conditional, not missing', () async {
    if (!Platform.isMacOS) return;
    final prepared = await emon.prepare(const ConvertOptions(ocrEngine: OcrEngine.appleVision));
    expect(prepared.missingPackIds, isEmpty, reason: 'Vision needs no pack; the fallback\'s are conditional');
    expect(prepared.verdict, PrepareVerdict.ready);
  });
}
