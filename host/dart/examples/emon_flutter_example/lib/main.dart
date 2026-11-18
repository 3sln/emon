// Evaluate emon on a phone. Convert a bundled PDF or a photo of a receipt, see the HTML, pick the OCR engine (ML Kit, Tesseract, Apple Vision, PP-OCR where it ships), and watch the downloads.
//
// A self-test: `flutter run --dart-define=EMON_AUTORUN=scan:tesseract` converts one document with one engine at start and prints a line starting `EMON_RESULT` (documents: scan, born, receipt).
import 'dart:async';
import 'dart:convert';

import 'package:emon_flutter/emon_flutter.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

const autorun = String.fromEnvironment('EMON_AUTORUN');

void main() {
  runApp(const ExampleApp());
}

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'emon',
        theme: ThemeData(colorSchemeSeed: const Color(0xFF3B5BDB), useMaterial3: true),
        home: const Home(),
      );
}

enum Doc {
  scan('Scanned PDF (two pages of text)', 'assets/scan-plain.pdf', 'scan'),
  born('Born-digital PDF (no OCR needed)', 'assets/born-digital.pdf', 'born'),
  receipt('Photo of a receipt', 'assets/receipt.png', 'receipt');

  const Doc(this.label, this.asset, this.key);
  final String label;
  final String asset;
  final String key;
}

OcrEngine? engineByName(String name) => switch (name) {
      'tesseract' => OcrEngine.tesseract,
      'ppocr' => OcrEngine.ppocr,
      'apple-vision' || 'vision' => OcrEngine.appleVision,
      'mlkit-text' || 'mlkit' => OcrEngine.mlkitText,
      'windows-media-ocr' => OcrEngine.windowsMediaOcr,
      _ => null,
    };

class Home extends StatefulWidget {
  const Home({super.key});

  @override
  State<Home> createState() => _HomeState();
}

class _HomeState extends State<Home> {
  EmonFlutter? emon;
  List<EngineInfo> engines = const [];
  Doc doc = Doc.scan;
  String? html;
  String? error;
  String status = 'Starting...';
  bool busy = false;
  Duration? took;
  PrepareResult? prepared;

  @override
  void initState() {
    super.initState();
    unawaited(_open());
  }

  Future<void> _open() async {
    try {
      final e = await EmonFlutter.open(
        // the app decides about downloads: this one asks nothing and says so in the log; a real app could show a dialog here (the hook may be async)
        onPackNeeded: (r) {
          debugPrint('emon: fetching ${r.pack.id} (${r.pack.bytes} bytes, ${r.pack.licence}) for ${r.requestEngine}');
          return const PackDecision.fetch();
        },
      );
      e.addListener(() => setState(() {}));
      final list = await e.engines();
      setState(() {
        emon = e;
        engines = [for (final x in list) if (x.kind == 'ocr') x];
        status = 'Ready: ${e.engine.optionValue}';
      });
      await _prepare();
      if (autorun.isNotEmpty) unawaited(_autorun(e));
    } on Object catch (e) {
      setState(() => status = 'Could not open emon: $e');
    }
  }

  Future<void> _autorun(EmonFlutter e) async {
    final parts = autorun.split(':');
    final d = Doc.values.firstWhere((x) => x.key == parts[0], orElse: () => Doc.scan);
    final eng = parts.length > 1 ? engineByName(parts[1]) : null;
    if (eng != null) e.engine = eng;
    doc = d;
    await _convert(report: true);
  }

  Future<void> _prepare() async {
    final e = emon;
    if (e == null) return;
    try {
      final p = await e.prepare();
      if (mounted) setState(() => prepared = p);
    } on EmonError catch (x) {
      if (mounted) setState(() => error = '${x.name}: ${x.message}');
    }
  }

  Future<void> _convert({bool report = false}) async {
    final e = emon;
    if (e == null || busy) return;
    setState(() {
      busy = true;
      error = null;
      html = null;
      status = 'Converting with ${e.engine.optionValue}...';
    });
    final started = DateTime.now();
    try {
      final bytes = (await rootBundle.load(doc.asset)).buffer.asUint8List();
      final model = await e.convert(EmonInput.bytes(Uint8List.fromList(bytes)));
      final out = await model.html();
      final text = out.replaceAll(RegExp(r'<[^>]+>'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
      await model.dispose();
      final ms = DateTime.now().difference(started).inMilliseconds;
      final notes = await e.emon.notes();
      if (!mounted) return;
      setState(() {
        html = out;
        took = DateTime.now().difference(started);
        status = 'Done in ${(ms / 1000).toStringAsFixed(1)} s with ${e.engine.optionValue}';
      });
      if (report) {
        final head = text.length > 90 ? text.substring(0, 90) : text;
        // ignore: avoid_print
        print('EMON_RESULT ok=true doc=${doc.key} engine=${e.engine.optionValue} ms=$ms chars=${text.length} words=${text.split(' ').length} head=${jsonEncode(head)} notes=${jsonEncode(notes)}');
      }
    } on Object catch (x) {
      if (!mounted) return;
      setState(() {
        error = '$x';
        status = 'Failed';
      });
      if (report) {
        // ignore: avoid_print
        print('EMON_RESULT ok=false doc=${doc.key} engine=${e.engine.optionValue} error=${jsonEncode('$x')}');
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
    await _prepare();
  }

  @override
  Widget build(BuildContext context) {
    final e = emon;
    return Scaffold(
      appBar: AppBar(title: const Text('emon')),
      body: e == null
          ? Center(child: Text(status))
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text('Document', style: Theme.of(context).textTheme.titleMedium),
                RadioGroup<Doc>(
                  groupValue: doc,
                  onChanged: (v) {
                    if (!busy && v != null) setState(() => doc = v);
                  },
                  child: Column(children: [for (final d in Doc.values) RadioListTile<Doc>(title: Text(d.label), value: d, enabled: !busy)]),
                ),
                const SizedBox(height: 8),
                Text('OCR engine', style: Theme.of(context).textTheme.titleMedium),
                RadioGroup<OcrEngine>(
                  groupValue: e.engine,
                  onChanged: (v) {
                    if (!busy && v != null) e.engine = v;
                  },
                  child: Column(children: [for (final x in engines) _engineTile(e, x)]),
                ),
                const SizedBox(height: 8),
                _downloads(e),
                if (prepared != null) _prepareText(prepared!),
                const SizedBox(height: 8),
                Row(children: [
                  FilledButton.icon(onPressed: busy ? null : _convert, icon: const Icon(Icons.play_arrow), label: const Text('Convert')),
                  const SizedBox(width: 12),
                  OutlinedButton(onPressed: busy ? null : () => _ensure(e), child: const Text('Download packs')),
                ]),
                const SizedBox(height: 12),
                Text(status),
                if (error != null) Text(error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                if (html != null) ...[
                  const SizedBox(height: 12),
                  Text('HTML (${html!.length} characters${took == null ? '' : ', ${(took!.inMilliseconds / 1000).toStringAsFixed(1)} s'})', style: Theme.of(context).textTheme.titleMedium),
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor)),
                    child: SelectableText(html!, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
                  ),
                ],
              ],
            ),
    );
  }

  Widget _engineTile(EmonFlutter e, EngineInfo x) {
    final eng = engineByName(x.name);
    final usable = eng != null && x.usableAfterDownload;
    final size = x.downloadBytes > 0 ? ', downloads ${(x.downloadBytes / 1048576).toStringAsFixed(1)} MB' : '';
    return RadioListTile<OcrEngine>(
      title: Text(x.name),
      subtitle: Text('${x.state.text}$size${x.reason.isEmpty ? '' : ' - ${x.reason}'}${x.calibrated ? '' : ' - confidences uncalibrated'}'),
      value: eng ?? OcrEngine.tesseract,
      enabled: !busy && usable,
    );
  }

  Widget _downloads(EmonFlutter e) => ListenableBuilder(
        listenable: e.downloads,
        builder: (context, _) {
          final d = e.downloads;
          if (!d.isDownloading && d.lastFailure == null) return const SizedBox.shrink();
          return Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                for (final p in d.active) ...[Text('${p.pack}: ${(p.fraction * 100).toStringAsFixed(0)}% of ${(p.total / 1048576).toStringAsFixed(1)} MB'), LinearProgressIndicator(value: p.fraction)],
                if (d.lastFailure != null) Text('Failed: ${d.lastFailure!.pack} ${d.lastFailure!.message}', style: TextStyle(color: Theme.of(context).colorScheme.error)),
                if (d.isDownloading) TextButton(onPressed: d.cancel, child: const Text('Cancel')),
              ]),
            ),
          );
        },
      );

  Widget _prepareText(PrepareResult p) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Text(p.missing == 0 ? 'Packs: ${p.verdict.name}' : 'Packs: ${p.verdict.name}, ${p.missing} missing (${(p.missingBytes / 1048576).toStringAsFixed(1)} MB)'),
      );

  Future<void> _ensure(EmonFlutter e) async {
    final p = await e.prepare();
    try {
      await e.emon.packs.ensure(p.missingPackIds);
    } on EmonError catch (x) {
      setState(() => error = '${x.name}: ${x.message}');
    }
    await _prepare();
  }
}
