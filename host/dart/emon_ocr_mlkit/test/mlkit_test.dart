import 'package:emon_core/emon_core.dart';
import 'package:emon_ocr_mlkit/emon_ocr_mlkit.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('which recogniser reads which languages', () {
    expect(mlkitScriptFor(const []), 'latin');
    expect(mlkitScriptFor(const ['eng']), 'latin');
    expect(mlkitScriptFor(const ['deu', 'fra']), 'latin');
    expect(mlkitScriptFor(const ['chs']), 'chinese');
    expect(mlkitScriptFor(const ['jpn']), 'japanese');
    expect(mlkitScriptFor(const ['kor']), 'korean');
    expect(mlkitScriptFor(const ['hin']), 'devanagari');
    expect(mlkitScriptFor(const ['Latn']), 'latin');
    expect(mlkitScriptFor(const ['rus']), isNull, reason: 'no Cyrillic recogniser: the page is unread, never blank');
    expect(mlkitScriptFor(const ['ara', 'heb']), isNull);
    expect(mlkitLanguageOfEmon('en'), 'eng');
    expect(mlkitLanguageOfEmon('zh-Hant'), 'cht');
    expect(mlkitLanguageOfEmon('und'), isNull);
  });

  test('the adapter says what it is and reads through the channel', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('com.3sln.emon/mlkit');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'info') {
        return {'available': true, 'variant': 'bundled', 'version': '16.0.1', 'scripts': ['latin'], 'sdk': 35};
      }
      return {
        'blocks': [
          {'box': [10, 10, 100, 30]},
        ],
        'lines': [
          {
            'box': [10, 10, 100, 30],
            'quad': [10, 10, 110, 10, 110, 40, 10, 40],
            'conf': 910,
            'block': 0,
            'words': [
              {'text': 'Hello', 'box': [10, 10, 50, 30], 'conf': 950, 'lang': 'en'},
            ],
          },
        ],
        'lang': 'en',
      };
    });
    final a = MlKitAdapter();
    expect(await a.isAvailable(), isTrue);
    expect(a.info.engine, 4);
    expect(a.info.caps, MlKitAdapter.capabilities);
    expect(a.observedIdentity, contains('variant=bundled'));
    expect(a.info.config, startsWith('mlkit-text;'));
    final r = await a.read(OcrRequest(id: 1, psm: 3, flags: 0, dpi: 150, region: null, languages: const [], png: Uint8List(0), width: 200, height: 100));
    expect(r!.lines.single.words.single.text, 'Hello');
    expect(r.lines.single.words.single.language, 'eng');
    expect(r.lines.single.confidence, 910);
    expect(await a.read(OcrRequest(id: 2, psm: 3, flags: 0, dpi: 150, region: null, languages: const ['rus'], png: Uint8List(0), width: 200, height: 100)), isNull);
  });
}
