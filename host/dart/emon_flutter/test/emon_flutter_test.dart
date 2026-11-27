import 'dart:io';

import 'package:emon_flutter/emon_flutter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // flutter_test's default platform is Android: the ML Kit probe runs, finds no plugin and the engine falls back to Tesseract
  TestWidgetsFlutterBinding.ensureInitialized();

  test('downloads follow Packs.events for a widget', () async {
    final emon = await EmonFlutter.open(packDirectory: '${_tmp()}/packs', packs: PackMode.never);
    final seen = <int>[];
    emon.downloads.addListener(() => seen.add(emon.downloads.all.length));
    expect(emon.downloads.isDownloading, isFalse);
    expect(emon.engine, Platform.isMacOS || Platform.isIOS ? OcrEngine.appleVision : OcrEngine.tesseract, reason: 'not Android: the platform default');
    emon.engine = OcrEngine.ppocr;
    expect(emon.engine, OcrEngine.ppocr);
    emon.dispose();
  });
}

String _tmp() => Directory.systemTemp.createTempSync('emon-flutter-test-').path;
