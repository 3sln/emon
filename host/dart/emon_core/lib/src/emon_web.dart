/// The web backend: the same API as `emon_io.dart`, over emon's wasm build and the JS SDK (`@3sln/emon`), through `dart:js_interop` (DEVLOG D9xx).
///
/// The loop is the SDK's: it runs the engine, awaits what the engine asks for (bytes, OCR, renders) and runs it again, on the page's thread (the wasm module is synchronous; the
/// heavy engines, tesseract.js and pdfium, run in workers or in their own wasm). Nothing here fetches: the SDK reads what the app hands it, tesseract.js and pdfium load from the
/// files [WebAssets] names, and a pack is never fetched by this library (D913).
library;

import 'dart:async';
import 'dart:convert';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'dart:typed_data';

import 'package:crypto/crypto.dart' as crypto;

import 'engines.dart';
import 'errors.dart';
import 'ocr.dart';
import 'options.dart';
import 'pack_store_web.dart';
import 'packs.dart';
import 'types.dart';
import 'web_assets.dart';
import 'web_mapping.dart';

export 'types.dart' show ConvertStats, IntakeResult;

// ------------------------------------------------------------------------------------------------ the SDK's shapes

extension type _JsEmon._(JSObject _) implements JSObject {
  external JSPromise<_JsModel> convert(JSAny input, JSObject options);
  external JSPromise<JSArray<JSObject>> intake(JSAny inputs, JSObject options);
}

extension type _JsModel._(JSObject _) implements JSObject {
  external JSString json(JSObject options);
  external JSString html(JSObject options);
  external JSString evidence();
  external JSObject get stats;
  external JSArray<_JsAsset> get assets;
  external JSObject transcript();
  external void dispose();
}

extension type _JsAsset._(JSObject _) implements JSObject {
  external JSString get name;
  external JSString get type;
  external JSNumber get size;
  external JSUint8Array bytes();
}

JSObject _global() => globalContext;

JSObject _object([Map<String, JSAny?> fields = const {}]) {
  final o = JSObject();
  fields.forEach((k, v) => o.setProperty(k.toJS, v));
  return o;
}

/// A JS error, or whatever a promise was rejected with, as an [EmonError] (the SDK's carries the name in `code`).
EmonError _jsError(Object e) {
  if (e is EmonError) return e;
  if (e.isA<JSObject>()) {
    final o = e as JSObject;
    final code = o['code'];
    final msg = o['message'];
    final name = code.isA<JSString>() ? (code as JSString).toDart : 'internal';
    final text = msg.isA<JSString>() ? (msg as JSString).toDart : '$e';
    return EmonError(name, text, webErrorCategory(name));
  }
  return EmonError('internal', '$e', EmonErrorCategory.internal);
}

Future<T> _guard<T>(Future<T> Function() f) async {
  try {
    return await f();
  } on EmonError {
    rethrow;
  } on Object catch (e) {
    throw _jsError(e);
  }
}

String _absolute(String url) => Uri.base.resolve(url).toString();

// ------------------------------------------------------------------------------------------------ input

/// One document or image to convert: bytes, and the password of an encrypted PDF.
///
/// On the web there is no file system: [EmonInput.file] is accepted so code compiles everywhere, and fails with a `usage` error when it is converted. Read the file the browser gave you
/// (a picker, a drop, `fetch` of your own) and pass the bytes.
class EmonInput {
  /// An input in memory.
  EmonInput.bytes(Uint8List bytes, {this.password})
      : _bytes = bytes,
        path = null;

  /// Not available on the web (see the class comment).
  EmonInput.file(String this.path, {this.password}) : _bytes = null;

  final Uint8List? _bytes;
  final String? path;

  /// The password of an encrypted PDF (UTF-8 bytes; never written to a Model or a transcript).
  final Uint8List? password;

  JSAny _toJs() {
    final b = _bytes;
    if (b == null) throw EmonError('usage', 'the web has no file system: EmonInput.file($path) cannot be read; pass the bytes', EmonErrorCategory.usage);
    if (password == null) return b.toJS;
    return _object({'source': b.toJS, 'password': password!.toJS});
  }
}

// ------------------------------------------------------------------------------------------------ results

/// A picture of a [Model] (made with `assets: 'images'` or `'figures'`).
///
/// The wasm binding reports a picture's name, type and size; the SHA-256 and the dimensions are read from its bytes here, and [origin] is empty (the binding does not say).
class Asset {
  Asset._(this._js, this.index, this.name, this.mediaType, this.size, this.sha256, this.width, this.height);

  final _JsAsset _js;

  /// The position in [Model.assets].
  final int index;

  /// The file name to write it as: `a0.png`, `a1.jpg`.
  final String name;

  /// `image/png` or `image/jpeg`.
  final String mediaType;

  /// Empty on the web.
  String get origin => '';
  final String sha256;
  final int width, height;

  /// The encoded size in bytes.
  final int size;

  /// The encoded bytes.
  Future<Uint8List> bytes() async => _js.bytes().toDart;
}

(int, int) _imageSize(Uint8List b) {
  if (b.length > 24 && b[0] == 0x89 && b[1] == 0x50) {
    final s = pngSize(b);
    return (s.width, s.height);
  }
  // JPEG: the first SOFn marker
  var i = 2;
  while (i + 9 < b.length) {
    if (b[i] != 0xFF) {
      i++;
      continue;
    }
    final m = b[i + 1];
    if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return ((b[i + 7] << 8) | b[i + 8], (b[i + 5] << 8) | b[i + 6]);
    i += 2 + ((b[i + 2] << 8) | b[i + 3]);
  }
  return (0, 0);
}

/// What a conversion made: emon's JSON and HTML, the assets and, when asked for, a transcript. Call [dispose] when done (the wasm heap holds it).
class Model {
  Model._(this._js, this.stats, this._hasTranscript, this.assets);

  static Future<Model> _wrap(_JsModel js) async {
    final s = js.stats;
    final m = <String, int>{};
    for (final k in const ['attempts', 'fetchRounds', 'bytesRequests', 'requests', 'sourceReads', 'bytesFetched', 'inputBytes']) {
      final v = s[k];
      if (v.isA<JSNumber>()) m[k] = (v as JSNumber).toDartInt;
    }
    final assets = <Asset>[];
    final list = js.assets.toDart;
    for (var i = 0; i < list.length; i++) {
      final a = list[i];
      final data = a.bytes().toDart;
      final (w, h) = _imageSize(data);
      assets.add(Asset._(a, i, a.name.toDart, a.type.toDart, a.size.toDartInt, crypto.sha256.convert(data).toString(), w, h));
    }
    var transcript = false;
    try {
      js.transcript();
      transcript = true;
    } on Object {
      // no transcript: the conversion did not say record
    }
    return Model._(js, ConvertStats(m), transcript, assets);
  }

  final _JsModel _js;
  final bool _hasTranscript;
  bool _disposed = false;

  /// What the loop did.
  final ConvertStats stats;

  /// The pictures.
  final List<Asset> assets;

  /// emon JSON, as text (SPEC 4).
  Future<String> json({bool pretty = false}) => _guard(() async => _js.json(webMap({'pretty': pretty})).toDart);

  /// The JSON, parsed.
  Future<Object?> object() async => jsonDecode(await json());

  /// HTML (SPEC 5).
  Future<String> html([HtmlOptions options = const HtmlOptions()]) => _guard(() async => _js.html(webMap(webHtmlOptions(options))).toDart);

  /// The text layer's evidence dump when the conversion said `keepEvidence` (empty otherwise).
  Future<String> evidence() => _guard(() async => _js.evidence().toDart);

  /// The transcript, when the conversion said `record`: files by name, replayable with `ConvertOptions.transcript` on any host.
  Future<Map<String, Uint8List>> transcript() async {
    if (!_hasTranscript) throw EmonError('usage', 'the conversion did not say record: true', EmonErrorCategory.usage);
    final entries = (_global()['Array'] as JSObject).callMethod<JSArray<JSArray<JSAny?>>>('from'.toJS, _js.transcript().callMethod<JSAny>('entries'.toJS));
    return {
      for (final e in entries.toDart) (e.toDart[0] as JSString).toDart: (e.toDart[1] as JSUint8Array).toDart,
    };
  }

  /// Free the Model's memory.
  Future<void> dispose() async {
    if (_disposed) return;
    _disposed = true;
    _js.dispose();
  }
}

JSObject webMap(Map<String, Object?> m) => (m.jsify() as JSObject?) ?? JSObject();

// ------------------------------------------------------------------------------------------------ the engines

/// The engines of a page: the SDK, the wasm module, and the ports (OCR, render) the app's [WebAssets] and Dart engines make.
class Emon {
  Emon._(this._js, this._store, this._dartAdapters, this._ports, this._terminate, this._web, this.defaultEngine, this._notes) {
    for (final a in _dartAdapters.values) {
      a.attach(_store.emit);
    }
    packs = Packs(_store, (ids) => [for (final id in ids) PackCatalog.byId(id) ?? (throw EmonError('usage', 'unknown pack $id', EmonErrorCategory.usage))]);
  }

  final _JsEmon _js;
  final PackStore _store;
  final Map<String, OcrAdapter> _dartAdapters;
  final JSObject _ports;
  final List<JSObject> _terminate;
  final WebAssets _web;
  final List<String> _notes;
  bool _closed = false;

  /// The engine a conversion uses when its options name none.
  final OcrEngine? defaultEngine;

  /// There is no C library on the web: the wasm build, the module the SDK loaded.
  final String version = 'wasm';

  /// Control of packs. The web has none (the JS engines load their own models, [WebTesseract.lang]): [Packs.ensure] says `pack-fetch-unavailable`.
  late final Packs packs;

  /// Open the engines on this page.
  ///
  /// Loads the SDK and the wasm module named by [web] (default: `emon/sdk/index.js` and `emon/emon.wasm` next to the page). OCR is [WebAssets.tesseract] (tesseract.js) and the engines
  /// written in Dart ([adapters]); [pdfium] is true to use [WebAssets.pdfium] when it is configured. [nativeAdapters] cannot run on the web and are reported by [notes];
  /// [packDirectory], [packMode], [packSources], [onPackNeeded] and [pdfiumPath] are accepted and unused: no pack is fetched, asked about or stored here.
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
    WebAssets? web,
  }) =>
      _guard(() async {
        final assets = web ?? const WebAssets();
        final base = _absolute(assets.sdk);
        final sdk = await importModule('${base}index.js'.toJS).toDart;
        final emon = await ((sdk['Emon'] as JSObject).callMethod<JSPromise<_JsEmon>>('load'.toJS, _absolute(assets.wasm).toJS)).toDart;
        final notes = <String>[
          for (final n in nativeAdapters) '${n.name}: adapter-runtime-missing: native adapters do not run on the web',
        ];
        final usable = <String, OcrAdapter>{};
        for (final a in adapters) {
          if (await a.isAvailable()) usable[a.name] = a;
        }
        final terminate = <JSObject>[];
        final ports = _object();
        JSFunction? tess;
        final t = assets.tesseract;
        if (t != null) {
          final mod = await importModule('${base}tesseract.js'.toJS).toDart;
          final createWorker = ((JSAny? a, JSAny? b, JSAny? c) => (() async {
                final lib = await importModule(_absolute(t.module).toJS).toDart;
                final holder = lib.has('createWorker') ? lib : lib['default'] as JSObject;
                return await (holder.callMethod<JSPromise<JSAny?>>('createWorker'.toJS, a, b, c)).toDart;
              })()
                  .toJS).toJS;
          final port = (mod.callMethod<JSFunction>('tesseractPort'.toJS, _object({
            'createWorker': createWorker,
            'languages': t.languages.jsify(),
            'workers': t.workers.toJS,
            'workerOptions': _object({
              'workerPath': _absolute(t.worker).toJS,
              'corePath': _absolute(t.core).toJS,
              'langPath': _absolute(t.lang).toJS,
              'cacheMethod': 'none'.toJS,
              'gzip': t.gzip.toJS,
              'workerBlobURL': false.toJS,
            }),
          })));
          tess = port;
          terminate.add(port);
        }
        final ocr = _ocrPort(usable, tess);
        if (ocr != null) ports.setProperty('ocr'.toJS, ocr);
        final p = assets.pdfium;
        if (pdfium && p != null) {
          final mod = await importModule('${base}pdfium.js'.toJS).toDart;
          final renderer = mod.callMethod<JSObject>('pdfiumRenderer'.toJS, _object({
            'module': (() => importModule(_absolute(p.module).toJS)).toJS,
            'wasm': _absolute(p.wasm).toJS,
          }));
          ports.setProperty('render'.toJS, renderer);
          terminate.add(renderer);
        }
        return Emon._(emon, PackStore(dir: packDirectory ?? '', mode: packMode), usable, ports, terminate, assets, defaultEngine, notes);
      });

  /// The port the SDK calls for an OCR request: an engine written in Dart when the request names its engine, else tesseract.js.
  static JSFunction? _ocrPort(Map<String, OcrAdapter> adapters, JSFunction? tess) {
    if (adapters.isEmpty && tess == null) return null;
    final byCode = {for (final a in adapters.values) a.info.engine: a};
    final fn = ((JSObject req) {
      return (() async {
        final raw = req['engine'];
        final code = raw.isA<JSNumber>() ? (raw as JSNumber).toDartInt : null;
        final adapter = code == null ? null : byCode[code];
        if (adapter != null) {
          final png = (req.callMethod<JSUint8Array>('bitmap'.toJS)).toDart;
          final (:width, :height) = pngSize(png);
          final rect = req['rect'];
          Box? region;
          if (rect.isA<JSObject>()) {
            final r = rect as JSObject;
            int n(String k) => (r[k] as JSNumber).toDartInt;
            region = Box(n('x'), n('y'), n('w'), n('h'));
          }
          final langs = (req['langs'] as JSArray<JSString>).toDart.map((s) => s.toDart).toList();
          final reading = await adapter.read(OcrRequest(
            id: (req['id'] as JSNumber).toDartInt,
            psm: (req['psm'] as JSNumber).toDartInt,
            flags: (req['flags'] as JSNumber).toDartInt,
            dpi: (req['dpi'] as JSNumber).toDartInt,
            region: region,
            languages: langs,
            png: png,
            width: width,
            height: height,
          ));
          return reading == null ? null : readingToWeb(reading, adapter.info).jsify();
        }
        if (tess != null) return await (tess.callAsFunction(null, req) as JSPromise<JSAny?>).toDart;
        return null;
      })().toJS;
    }).toJS;
    final producer = tess == null ? null : (tess as JSObject)['producer'];
    if (producer != null) fn.setProperty('producer'.toJS, producer);
    return fn;
  }

  /// Why an adapter did not register, and what was reported since.
  Future<List<String>> notes() async => List.of(_notes);

  /// What `emon_capabilities` says: 1 pdfium renders here. (The JPX and JBIG2 codecs, bit 4, are in the wasm build.)
  int get capabilities => (_web.pdfium != null ? 1 : 0) | 4;

  /// Whether a renderer is configured (scanned PDFs can be read).
  bool get canRender => _web.pdfium != null;

  OcrEngine? _engineOf(ConvertOptions o) => o.ocrEngine ?? defaultEngine;

  JSObject _options(ConvertOptions o) {
    final m = webMap(webConvertOptions(o, engine: defaultEngine));
    if (o.transcript != null) {
      final map = (_global()['Map'] as JSFunction).callAsConstructor<JSObject>();
      for (final e in o.transcript!.entries) {
        map.callMethod<JSAny?>('set'.toJS, e.key.toJS, Uint8List.fromList(e.value).toJS);
      }
      m.setProperty('transcript'.toJS, map);
    } else {
      m.setProperty('ports'.toJS, _ports);
    }
    return m;
  }

  /// Convert one input to a [Model].
  Future<Model> convert(EmonInput input, [ConvertOptions options = const ConvertOptions()]) {
    if (_closed) throw EmonError('usage', 'the Emon is closed', EmonErrorCategory.usage);
    return _guard(() async {
      final js = await _js.convert(input._toJs(), _options(options)).toDart;
      return Model._wrap(js);
    });
  }

  /// The common case in one call: the HTML of [input], with no Model to keep.
  Future<String> convertToHtml(EmonInput input, [ConvertOptions options = const ConvertOptions(), HtmlOptions html = const HtmlOptions()]) async {
    final m = await convert(input, options);
    try {
      return await m.html(html);
    } finally {
      await m.dispose();
    }
  }

  /// The canonical JSON of [input] (compact, or indented when [pretty]).
  Future<String> convertToJson(EmonInput input, [ConvertOptions options = const ConvertOptions(), bool pretty = false]) async {
    final m = await convert(input, options);
    try {
      return await m.json(pretty: pretty);
    } finally {
      await m.dispose();
    }
  }

  /// Stage 0 of [input], cheap: what it says it is.
  Future<IntakeResult> intake(EmonInput input, [ConvertOptions options = const ConvertOptions()]) => _guard(() async {
        final rs = (await _js.intake(input._toJs(), webMap(webConvertOptions(options, engine: defaultEngine))).toDart).toDart;
        final r = rs.first;
        final v = (r['version'] as JSArray<JSNumber>).toDart;
        return IntakeResult(
          format: (r['format'] as JSString).toDart,
          version: (v[0].toDartInt, v[1].toDartInt),
          pages: (r['pages'] as JSNumber).toDartInt,
          encrypted: (r['encrypted'] as JSBoolean).toDart,
          bytes: (r['bytes'] as JSNumber).toDartInt,
          line: (r['line'] as JSString).toDart,
        );
      });

  /// Every engine of kin's table with what this page really has: tesseract.js and pdfium when [WebAssets] configure them, the engines written in Dart, and the native ones listed
  /// as unavailable with the reason.
  Future<List<EngineInfo>> engines() async => webEngineList(tesseract: _web.tesseract != null, pdfium: _web.pdfium != null, dartEngines: _dartAdapters.values.toList());

  /// A preflight. On the web no pack is ever needed by this library: the verdict is `ready` when the engine the options choose can run, else `blocked` with the engine named.
  Future<PrepareResult> prepare([ConvertOptions options = const ConvertOptions()]) async {
    final engine = (_engineOf(options) ?? OcrEngine.tesseract).engineName;
    final usable = (await engines()).any((e) => e.name == engine && e.usable);
    return PrepareResult(
      verdict: usable ? PrepareVerdict.ready : PrepareVerdict.blocked,
      mode: 'never',
      offline: false,
      engines: [engine],
      languages: const [],
      packs: const [],
      unsupported: usable ? const [] : [(engine: engine, language: '*')],
      missing: 0,
      missingBytes: 0,
    );
  }

  /// Close the engines: terminate tesseract.js's workers and pdfium.
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    for (final t in _terminate) {
      try {
        await (t.callMethod<JSPromise<JSAny?>>('terminate'.toJS)).toDart;
      } on Object {
        // closing is best effort
      }
    }
    for (final a in _dartAdapters.values) {
      await a.close();
    }
    await _store.close();
  }
}

/// There is no cache directory on the web.
String defaultPackDirectory() => '';
