import 'dart:io';

import 'package:emon_flutter/emon_flutter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('downloads follow Packs.events for a widget', () async {
    final emon = await EmonFlutter.open(packDirectory: '${_tmp()}/packs', packs: PackMode.never);
    final seen = <int>[];
    emon.downloads.addListener(() => seen.add(emon.downloads.all.length));
    expect(emon.downloads.isDownloading, isFalse);
    expect(emon.engine, OcrEngine.tesseract, reason: 'not Android: Tesseract');
    emon.engine = OcrEngine.appleVision;
    expect(emon.engine, OcrEngine.appleVision);
    emon.dispose();
  });
}

String _tmp() => Directory.systemTemp.createTempSync('emon-flutter-test-').path;
