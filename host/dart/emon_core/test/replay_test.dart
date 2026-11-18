// A committed transcript replayed: no engine is called, whatever recorded it. The Tesseract transcript of a scan (test/ocr/scan/h-plain) gives the Model the Rust host gives, and a
// recording made here replays to the same bytes.
import 'dart:io';
import 'dart:typed_data';

import 'package:emon_core/emon_core.dart';
import 'package:path/path.dart' as p;
import 'package:test/test.dart';

import 'support.dart';

Map<String, Uint8List> filesOf(String dir) => {
      for (final f in Directory(p.join(repo, dir)).listSync().whereType<File>()) p.basename(f.path): f.readAsBytesSync(),
    };

void main() {
  late Emon emon;
  setUpAll(() async => emon = await Emon.open());
  tearDownAll(() => emon.close());

  test('a committed Tesseract transcript replays to the Rust host\'s HTML, with no engine', () async {
    final pdf = EmonInput.file(p.join(repo, 'test/corpus/synth/scan/h-plain.pdf'));
    final m = await emon.convert(pdf, ConvertOptions(transcript: filesOf('test/ocr/scan/h-plain')));
    expect(await m.html(), fixture('host/dart/emon_core/test/golden/replay-h-plain.html').readAsStringSync());
    await m.dispose();
  });

  test('a changed request is transcript-miss, not a guess', () async {
    final pdf = EmonInput.file(p.join(repo, 'test/corpus/synth/scan/h-plain.pdf'));
    await expectLater(
      emon.convert(pdf, ConvertOptions(transcript: filesOf('test/ocr/scan/h-plain'), locale: 'de-DE')),
      throwsA(isA<EmonError>().having((e) => e.name, 'name', 'transcript-miss')),
    );
  });

  test('the Apple Vision transcript replays under ocr-engine=apple-vision, and its evidence names the engine', () async {
    final pdf = EmonInput.file(p.join(repo, 'test/corpus/synth/scan/h-plain.pdf'));
    final m = await emon.convert(pdf, ConvertOptions(ocrEngine: OcrEngine.appleVision, transcript: filesOf('test/ocr/vision/h-plain'), keepEvidence: true));
    expect(await m.evidence(), contains('"engine":"apple-vision"'));
    expect(await m.html(), fixture('host/dart/emon_core/test/golden/replay-vision-h-plain.html').readAsStringSync());
    await m.dispose();
  });

  test('a recording made through the step API replays to the same Model', () async {
    final pdf = EmonInput.file(p.join(repo, 'test/corpus/synth/pdf/h-plain.pdf'));
    final live = await emon.convert(pdf, const ConvertOptions(record: true));
    final files = await live.transcript();
    expect(files.keys, contains('transcript.edn'));
    final replay = await emon.convert(pdf, ConvertOptions(transcript: files));
    expect(await replay.html(), await live.html());
    await live.dispose();
    await replay.dispose();
  });
}
