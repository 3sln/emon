import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'bindings.g.dart' as c;
import 'engines.dart';
import 'errors.dart';
import 'mailbox.dart';
import 'native.dart';
import 'ocr.dart';
import 'options.dart';
import 'packs.dart';
import 'pdfium_binding.dart';
import 'worker.dart';

/// One document or image to convert: bytes or a file, and the password of an encrypted PDF.
class EmonInput {
  /// An input in memory.
  EmonInput.bytes(Uint8List bytes, {this.password})
      : _bytes = bytes,
        path = null;

  /// An input in a file (read when the conversion starts).
  EmonInput.file(String this.path, {this.password}) : _bytes = null;

  final Uint8List? _bytes;
  final String? path;

  /// The password of an encrypted PDF (UTF-8 bytes; never written to a Model, a dump or a transcript).
  final Uint8List? password;

  Future<Uint8List> _read() async => _bytes ?? await File(path!).readAsBytes();
}

/// The result of [Emon.intake]: what a file says it is, with no page read.
class IntakeResult {
  const IntakeResult({required this.format, required this.version, required this.pages, required this.encrypted, required this.bytes, required this.line});

  /// `pdf`, `png`, `jpeg`, `tiff`.
  final String format;
  final (int, int) version;
  final int pages;
  final bool encrypted;
  final int bytes;

  /// The line the library gave: `pdf 1.4 pages=3 encrypted=false bytes=1234`.
  final String line;
}

/// What the loop did: how many attempts, fetch rounds and requests the conversion took.
class ConvertStats {
  const ConvertStats(this._m);
  final Map<String, int> _m;

  int get attempts => _m['attempts'] ?? 0;
  int get fetchRounds => _m['fetchRounds'] ?? 0;
  int get bytesRequests => _m['bytesRequests'] ?? 0;
  int get requests => _m['requests'] ?? 0;
  int get sourceReads => _m['sourceReads'] ?? 0;
  int get bytesFetched => _m['bytesFetched'] ?? 0;
  int get inputBytes => _m['inputBytes'] ?? 0;
}

/// A picture of a [Model] (made with `assets: 'images'` or `'figures'`).
class Asset {
  Asset._(this._model, Map<String, Object?> m)
      : index = m['index'] as int,
        name = m['name'] as String,
        mediaType = m['mediaType'] as String,
        origin = m['origin'] as String,
        sha256 = m['sha256'] as String,
        width = m['width'] as int,
        height = m['height'] as int,
        size = m['size'] as int;

  final Model _model;

  /// The position in [Model.assets].
  final int index;

  /// The file name to write it as: `a0.png`, `a1.jpg`.
  final String name;

  /// `image/png` or `image/jpeg`.
  final String mediaType;

  /// `embedded`, `rendered`, ...
  final String origin;
  final String sha256;
  final int width, height;

  /// The encoded size in bytes.
  final int size;

  /// The encoded bytes.
  Future<Uint8List> bytes() async => await _model._emon._call('modelAsset', {'handle': _model._handle, 'index': index}) as Uint8List;
}

/// What a conversion made: emon's JSON and HTML, the assets and, when asked for, a transcript. Call [dispose] when done (a Model is also
/// released when it is garbage collected, but its memory is native).
class Model {
  Model._(this._emon, ModelResult r)
      : _handle = r.handle,
        stats = ConvertStats(r.stats),
        _hasTranscript = r.hasTranscript {
    assets = [for (final a in r.assets) Asset._(this, a)];
    _finalizer.attach(this, (_emon, _handle), detach: this);
  }

  static final _finalizer = Finalizer<(Emon, int)>((v) {
    final (emon, handle) = v;
    if (!emon._closed) unawaited(emon._call('modelFree', handle).catchError((_) => null));
  });

  final Emon _emon;
  final int _handle;
  final bool _hasTranscript;
  bool _disposed = false;

  /// What the loop did.
  final ConvertStats stats;

  /// The pictures.
  late final List<Asset> assets;

  /// emon JSON, as text (SPEC 4).
  Future<String> json({bool pretty = false}) async => await _emon._call('modelJson', {'handle': _handle, 'pretty': pretty}) as String;

  /// The JSON, parsed.
  Future<Object?> object() async => jsonDecode(await json());

  /// HTML (SPEC 5).
  Future<String> html([HtmlOptions options = const HtmlOptions()]) async => await _emon._call('modelHtml', {'handle': _handle, 'htmlOptions': options.toText()}) as String;

  /// The text layer's evidence dump when the conversion said `keepEvidence` (empty otherwise).
  Future<String> evidence() async => await _emon._call('modelEvidence', {'handle': _handle}) as String;

  /// The transcript, when the conversion said `record`: files by name, replayable with `ConvertOptions.transcript` on any host.
  Future<Map<String, Uint8List>> transcript() async {
    if (!_hasTranscript) throw EmonError('usage', 'the conversion did not say record: true', EmonErrorCategory.usage);
    final m = await _emon._call('modelTranscript', _handle) as Map;
    return m.cast<String, Uint8List>();
  }

  /// Free the Model's native memory.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _finalizer.detach(this);
    if (!_emon._closed) await _emon._call('modelFree', _handle);
  }
}

/// The engines of a process: the C library, the OCR engines registered with it, pdfium, and where packs are.
///
/// ```dart
/// final emon = await Emon.open(adapters: [...]);
/// final model = await emon.convert(EmonInput.file('report.pdf'));
/// print(await model.html());
/// await emon.close();
/// ```
class Emon {
  Emon._(this._isolate, this._port, this._replyPort, this._store, this._dartAdapters, this._mailbox, this.defaultEngine, this.version, this._capabilities, List<String> failures, this._onPackNeeded) {
    _notes.addAll(failures);
    for (final a in _dartAdapters.values) {
      a.attach(_store.emit);
    }
    packs = Packs(_store, (ids) => [for (final id in ids) PackCatalog.byId(id) ?? (throw EmonError('usage', 'unknown pack $id', EmonErrorCategory.usage))]);
  }

  final Isolate _isolate;
  final SendPort _port;
  final PackStore _store;
  final Map<String, OcrAdapter> _dartAdapters;
  final PackMailbox? _mailbox;
  final PackNeededHook? _onPackNeeded;
  final int _capabilities;
  final _notes = <String>[];
  final _pending = <int, Completer<Object?>>{};
  var _nextId = 0;
  var _inflight = 0;
  bool _closed = false;

  /// The engine a conversion uses when its options name none.
  final OcrEngine? defaultEngine;

  /// The version of the C library.
  final String version;

  /// Control of packs: [Packs.ensure], [Packs.events].
  late final Packs packs;

  /// Open the engines.
  ///
  /// [nativeAdapters] are OCR engines that are libraries (the adapter packages' `TesseractAdapter` and the like); [adapters] are engines
  /// written in Dart. [pdfium] registers the bundled pdfium renderer (or the library at [pdfiumPath]). Packs live in [packDirectory]
  /// (default: the user's cache, `$EMON_PACK_CACHE` first). [packMode] says whether they are fetched: the default is [PackMode.never], as
  /// core and bare adapters never fetch; the turnkey packages choose [PackMode.auto]. `EMON_OFFLINE=1` is [PackMode.never] whatever you set.
  static Future<Emon> open({
    List<NativeAdapter> nativeAdapters = const [],
    List<OcrAdapter> adapters = const [],
    bool pdfium = true,
    String? pdfiumPath,
    String? packDirectory,
    PackMode packMode = PackMode.never,
    List<String>? packSources,
    PackNeededHook? onPackNeeded,
    OcrEngine? defaultEngine,
  }) async {
    final dir = packDirectory ?? defaultPackDirectory();
    Directory(dir).createSync(recursive: true);
    final store = PackStore(dir: dir, mode: packMode, sources: packSources);
    final mode = store.effectiveMode;
    final usable = <String, OcrAdapter>{};
    for (final a in adapters) {
      if (await a.isAvailable()) usable[a.name] = a;
    }
    final replies = ReceivePort();
    final (isolate, port) = await spawnWorker(replies.sendPort);
    final mailbox = PackMailbox();
    final native = <(String, String, String?)>[];
    final early = <String>[];
    for (final n in nativeAdapters) {
      final path = n.path ?? libraryPathOf(n.symbolAddress);
      if (path == null) {
        early.add('${n.name}: adapter-runtime-missing: the library was not found');
        continue;
      }
      native.add((n.name, loaderName(path), n.config));
    }
    String? pdfiumLib = pdfiumPath;
    if (pdfium && pdfiumLib == null) {
      final addr = pdfiumSymbolAddress();
      if (addr != 0) {
        final path = libraryPathOf(addr);
        // inside an APK: no file to name, the library is found by name (where emon looks for it last)
        pdfiumLib = path == null || path.contains('!/') ? null : path;
      }
    }
    final init = WorkerInit(
      native: native,
      pdfiumPath: pdfium ? (pdfiumLib ?? '') : null,
      packDir: dir,
      packMode: switch (mode) {
        PackMode.never => c.EMON_PACKS_NEVER,
        PackMode.cache => c.EMON_PACKS_CACHE,
        PackMode.auto => c.EMON_PACKS_AUTO,
      },
      mailbox: mailbox.address,
      dartAdapters: [for (final a in usable.values) DartAdapterSpec(a.name, a.info)],
    );
    // the replies come on `replies`; the worker's command port is `port`
    final emonHolder = <Emon>[];
    final ready = Completer<Map<String, Object?>>();
    replies.listen((Object? m) {
      final msg = m as List;
      if (emonHolder.isNotEmpty) {
        emonHolder.first._onReply(msg);
      } else if (msg[0] == 0) {
        if (msg[1] == true) {
          ready.complete((msg[2] as Map).cast<String, Object?>());
        } else {
          ready.completeError((msg[2] as WorkerFailure).toError());
        }
      }
    });
    port.send([0, 'init', init]);
    final Map<String, Object?> info;
    try {
      info = await ready.future;
    } on Object {
      isolate.kill();
      mailbox.free();
      replies.close();
      rethrow;
    }
    final emon = Emon._(isolate, port, replies, store, usable, mailbox, defaultEngine, info['version'] as String, info['capabilities'] as int, [...early, ...(info['failures'] as List).cast<String>()], onPackNeeded);
    emonHolder.add(emon);
    return emon;
  }

  final ReceivePort _replyPort;

  /// What `emon_capabilities` says: 1 pdfium renders here, 4 the JPX and JBIG2 codecs.
  int get capabilities => _capabilities;

  /// Whether pdfium is registered and renders (scanned PDFs can be read).
  bool get canRender => _capabilities & c.EMON_CAP_PDFIUM != 0;

  /// Why an adapter did not register, and what adapters and packs reported since: why a page was left unread, for your log.
  Future<List<String>> notes() async => [..._notes, ...((await _call('notes', null)) as List).cast<String>()];

  // ---------------------------------------------------------------------------------------------- calls

  void _onReply(List msg) {
    final id = msg[0] as int;
    if (id < 0) {
      // the worker asks this isolate something (an adapter, a pack)
      if (msg[2] == 'pack') {
        unawaited(_servePack((msg[3] as Map).cast<String, Object?>()));
      } else {
        unawaited(_serve(-id, msg[2] as String, msg[3]));
      }
      return;
    }
    final comp = _pending.remove(id);
    if (comp == null) return;
    if (msg[1] == true) {
      comp.complete(msg[2]);
    } else {
      comp.completeError((msg[2] as WorkerFailure).toError());
    }
  }

  Future<void> _serve(int callId, String what, Object? payload) async {
    Object? result;
    try {
      if (what.startsWith('adapter:')) {
        final a = _dartAdapters[what.substring(8)];
        if (a == null) throw EmonError('adapter-runtime-missing', 'no adapter $what');
        result = await a.read(payload as OcrRequest);
      }
    } on Object catch (e) {
      result = WorkerFailure('adapter-failed', '$e', 13);
    }
    if (!_closed) _port.send([callId, '#reply', result]);
  }

  Future<Object?> _call(String cmd, Object? arg) {
    if (_closed) throw EmonError('usage', 'the Emon is closed', EmonErrorCategory.usage);
    final id = ++_nextId;
    final comp = Completer<Object?>();
    _pending[id] = comp;
    _begin();
    _port.send([id, cmd, arg]);
    return comp.future.whenComplete(_end);
  }

  void _begin() => _inflight++;

  void _end() {
    if (--_inflight < 0) _inflight = 0;
    // what the library was handed for a pack has been installed by now: the staging directories are done with
    for (final d in _staged.splitAndClear()) {
      try {
        Directory(d).deleteSync(recursive: true);
      } on Object {
        // already gone
      }
    }
  }

  final _staged = _Stages();

  Future<void> _servePack(Map<String, Object?> req) async {
    final mb = _mailbox;
    if (mb == null) return;
    await _decide(mb, req);
  }

  // The library asked about a missing pack: the app's hook decides (or the default: fetch in auto mode), and a fetch is made here, by dart:io.
  Future<void> _decide(PackMailbox mb, Map<String, Object?> req) async {
    final id = req['pack'] as String;
    final info = PackCatalog.byId(id);
    if (info == null) {
      mb.answer(c.EMON_PACK_DECLINE);
      return;
    }
    PackDecision decision;
    try {
      final r = PackRequest(pack: info, reason: req['reason'] as String? ?? 'conversion', requestEngine: req['requestEngine'] as String? ?? '');
      decision = _onPackNeeded != null ? await _onPackNeeded(r) : (_store.effectiveMode == PackMode.auto ? const PackDecision.fetch() : const PackDecision.decline());
    } on Object {
      decision = const PackDecision.decline();
    }
    try {
      switch (decision) {
        case PackFetch():
          if (_store.effectiveMode != PackMode.auto) {
            mb.answer(c.EMON_PACK_DECLINE);
            return;
          }
          final staged = await _store.fetchToStaging(info, initiator: PackInitiator.library);
          _staged.add(staged);
          mb.answer(c.EMON_PACK_PROVIDE, path: staged);
        case PackProvide(:final bytes, :final path):
          final staged = await PackStore.verifyProvided(info, bytes: bytes, path: path, stagingRoot: _store.dir);
          if (staged == null) {
            _store.emit(PackEvent(PackEventKind.failed, info.id, info.engine, 0, info.bytes, PackInitiator.library, 'provided bytes do not match the catalogue'));
            mb.answer(c.EMON_PACK_DECLINE);
          } else {
            _staged.add(staged);
            mb.answer(c.EMON_PACK_PROVIDE, path: staged);
          }
        case PackSubstitute(:final engine):
          mb.answer(c.EMON_PACK_SUBSTITUTE, engine: engine.engineName);
        case PackDecline():
          mb.answer(c.EMON_PACK_DECLINE);
      }
    } on Object {
      mb.answer(c.EMON_PACK_DECLINE);
    }
  }

  // ---------------------------------------------------------------------------------------------- conversion

  OcrEngine? _engineOf(ConvertOptions o) => o.ocrEngine ?? defaultEngine;

  bool _usesDartEngine(ConvertOptions o) {
    final e = _engineOf(o);
    return e != null && _dartAdapters.containsKey(e.engineName);
  }

  /// The option text and whether the conversion has to go through the step API.
  (String, bool) _plan(ConvertOptions o) {
    final dart = _usesDartEngine(o);
    final stepwise = o.stepwise || o.record || o.transcript != null || dart;
    var text = o.toText(engine: defaultEngine);
    if (stepwise) {
      if (o.record) text = text.isEmpty ? 'record=1' : '$text\nrecord=1';
      if (dart) text = withJsOcr(text);
    }
    return (text, stepwise);
  }

  Future<Map<String, Object?>> _inputArgs(EmonInput input) async {
    final bytes = await input._read();
    return {'bytes': TransferableTypedData.fromList([bytes]), if (input.password != null) 'password': input.password};
  }

  /// Convert one input to a [Model]. The work runs on a background isolate.
  Future<Model> convert(EmonInput input, [ConvertOptions options = const ConvertOptions()]) async {
    final (text, stepwise) = _plan(options);
    final args = await _inputArgs(input);
    args['options'] = text;
    args['stepwise'] = stepwise;
    args['record'] = options.record;
    if (options.chunk != null) args['chunk'] = options.chunk;
    if (options.transcript != null) {
      args['transcript'] = {for (final e in options.transcript!.entries) e.key: TransferableTypedData.fromList([Uint8List.fromList(e.value)])};
    }
    final r = await _call('convert', args) as ModelResult;
    return Model._(this, r);
  }

  /// The common case in one call: the HTML of [input], with no Model to keep. (A one-shot conversion; use [convert] for the step API,
  /// a transcript, or an engine written in Dart.)
  Future<String> convertToHtml(EmonInput input, [ConvertOptions options = const ConvertOptions(), HtmlOptions html = const HtmlOptions()]) async {
    if (_plan(options).$2) {
      final m = await convert(input, options);
      try {
        return await m.html(html);
      } finally {
        await m.dispose();
      }
    }
    final args = await _inputArgs(input);
    args['options'] = options.toText(engine: defaultEngine);
    args['format'] = 'html';
    args['htmlOptions'] = html.toText();
    return await _call('convertText', args) as String;
  }

  /// The canonical JSON of [input] (compact, or indented when [pretty]).
  Future<String> convertToJson(EmonInput input, [ConvertOptions options = const ConvertOptions(), bool pretty = false]) async {
    if (_plan(options).$2) {
      final m = await convert(input, options);
      try {
        return await m.json(pretty: pretty);
      } finally {
        await m.dispose();
      }
    }
    final args = await _inputArgs(input);
    args['options'] = options.toText(engine: defaultEngine);
    args['format'] = 'json';
    args['pretty'] = pretty;
    return await _call('convertText', args) as String;
  }

  /// Stage 0 of [input], cheap: what it says it is.
  Future<IntakeResult> intake(EmonInput input, [ConvertOptions options = const ConvertOptions()]) async {
    final args = await _inputArgs(input);
    args['options'] = options.toText(engine: defaultEngine);
    final r = await _call('intake', args) as Map;
    final m = parseIntake(r['line'] as String);
    final v = (m['version'] as List?)?.cast<int>() ?? const [0, 0];
    return IntakeResult(
      format: m['format'] as String,
      version: (v[0], v[1]),
      pages: (m['pages'] as int?) ?? 0,
      encrypted: (m['encrypted'] as bool?) ?? false,
      bytes: (m['bytes'] as int?) ?? 0,
      line: m['line'] as String,
    );
  }

  // ---------------------------------------------------------------------------------------------- engines() and prepare()

  /// Every engine of kin's table that runs on this platform, with its state: built-in, installed, downloadable (with size and licence) or
  /// unavailable (D896).
  Future<List<EngineInfo>> engines() async {
    final j = jsonObject(await _call('describe', null) as String);
    final out = <EngineInfo>[];
    for (final e in (j['engines'] as List).cast<Map>()) {
      var info = EngineInfo.fromJson(e.cast<String, Object?>());
      final dart = _dartAdapters[info.name];
      if (dart != null) {
        info = info.copyWith(state: EngineState.builtIn, registered: true, reason: '');
      }
      out.add(info);
    }
    // an engine written in Dart that kin's table does not list for this platform (ML Kit on a desktop) is still the app's to use, so it is listed
    for (final a in _dartAdapters.values) {
      if (out.every((e) => e.name != a.name)) {
        out.add(EngineInfo(
          name: a.name,
          kind: 'ocr',
          implementation: 'platform-plugin',
          state: EngineState.builtIn,
          registered: true,
          calibrated: false,
          role: 'reader',
          needs: const [],
          downloadBytes: 0,
          reason: '',
        ));
      }
    }
    return out;
  }

  /// A preflight: the packs a conversion with [options] needs and which are missing. Nothing is fetched.
  Future<PrepareResult> prepare([ConvertOptions options = const ConvertOptions()]) async {
    final text = options.toText(engine: defaultEngine, extra: const {});
    final engine = _engineOf(options);
    final j = jsonObject(await _call('prepare', text) as String);
    var r = PrepareResult.fromJson(j);
    if (engine != null && _dartAdapters.containsKey(engine.engineName)) {
      // an engine behind a plugin needs no pack, and kin's resolution says so
      r = PrepareResult.fromJson(j);
    }
    return r;
  }

  /// Close the engines and the worker.
  Future<void> close() async {
    if (_closed) return;
    try {
      await _call('close', null);
    } on Object {
      // closing is best effort
    }
    _closed = true;
    for (final a in _dartAdapters.values) {
      await a.close();
    }
    _isolate.kill();
    _replyPort.close();
    _mailbox?.free();
    await _store.close();
  }
}

/// `$EMON_PACK_CACHE`, else the user's cache directory (the same places the C library uses), else a directory in the temporary one.
String defaultPackDirectory() {
  final env = Platform.environment;
  String? nonEmpty(String? v) => v == null || v.isEmpty ? null : v;
  final explicit = nonEmpty(env['EMON_PACK_CACHE']);
  if (explicit != null) return explicit;
  final home = nonEmpty(env['HOME']);
  String? candidate;
  if (Platform.isMacOS || Platform.isIOS) {
    if (home != null) candidate = '$home/Library/Caches/emon/packs';
  } else if (Platform.isWindows) {
    final local = nonEmpty(env['LOCALAPPDATA']);
    if (local != null) candidate = '$local\\emon\\packs';
  } else {
    final xdg = nonEmpty(env['XDG_CACHE_HOME']);
    if (xdg != null) {
      candidate = '$xdg/emon/packs';
    } else if (home != null) {
      candidate = '$home/.cache/emon/packs';
    }
  }
  if (candidate != null) {
    try {
      Directory(candidate).createSync(recursive: true);
      return candidate;
    } on Object {
      // not a store: the temporary directory is
    }
  }
  return '${Directory.systemTemp.path}${Platform.pathSeparator}emon-packs';
}

class _Stages {
  final _dirs = <String>[];
  void add(String d) => _dirs.add(d);
  List<String> splitAndClear() {
    final out = List<String>.of(_dirs);
    _dirs.clear();
    return out;
  }
}
