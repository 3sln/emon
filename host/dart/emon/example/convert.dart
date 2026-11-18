// Convert a PDF or image to HTML with the engine of your choice.
//
//   dart run example/convert.dart [--engine tesseract|vision|ppocr|winocr] [--locale de-DE] [--json] INPUT [OUTPUT]
//
// Packs the engine needs are fetched on first use, with progress on stderr; `--offline` fetches nothing.
import 'dart:io';

import 'package:emon/emon.dart';

Future<void> main(List<String> args) async {
  OcrEngine? engine;
  String? locale;
  var json = false, offline = false;
  final rest = <String>[];
  for (var i = 0; i < args.length; i++) {
    switch (args[i]) {
      case '--engine':
        engine = switch (args[++i]) {
          'tesseract' => OcrEngine.tesseract,
          'vision' => OcrEngine.appleVision,
          'ppocr' => OcrEngine.ppocr,
          'winocr' => OcrEngine.windowsMediaOcr,
          final other => throw ArgumentError('unknown engine $other'),
        };
      case '--locale':
        locale = args[++i];
      case '--json':
        json = true;
      case '--offline':
        offline = true;
      default:
        rest.add(args[i]);
    }
  }
  if (rest.isEmpty) {
    stderr.writeln('usage: dart run example/convert.dart [--engine tesseract|vision|ppocr|winocr] [--locale de-DE] [--json] [--offline] INPUT [OUTPUT]');
    exit(2);
  }
  final emon = await openEmon(engine: engine, packs: offline ? PackMode.never : PackMode.auto);
  emon.packs.events.listen((e) {
    if (e.kind == PackEventKind.progress) {
      stderr.write('\r${e.pack}: ${(e.fraction * 100).toStringAsFixed(0)}%  ');
    } else if (e.kind == PackEventKind.done) {
      stderr.writeln('\r${e.pack}: fetched (${e.total} bytes)');
    }
  });
  final options = ConvertOptions(locale: locale);
  final prep = await emon.prepare(options);
  stderr.writeln('engine ${engine?.optionValue ?? defaultPlatformEngine.optionValue}: ${prep.verdict.name}${prep.missingPackIds.isEmpty ? '' : ' (missing ${prep.missingPackIds.join(', ')})'}');
  try {
    final input = EmonInput.file(rest.first);
    final text = json ? await emon.convertToJson(input, options, true) : await emon.convertToHtml(input, options);
    if (rest.length > 1) {
      File(rest[1]).writeAsStringSync(text);
    } else {
      stdout.write(text);
    }
    for (final n in await emon.notes()) {
      stderr.writeln('note: $n');
    }
  } on EmonError catch (e) {
    stderr.writeln('${e.name}: ${e.message}');
    exitCode = 1;
  } finally {
    await emon.close();
  }
}
