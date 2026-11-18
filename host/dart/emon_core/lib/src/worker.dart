// The isolate that owns the C library's handles and makes every blocking call. The API in emon.dart talks to it through ports.
//
// WHY ONE ISOLATE. A conversion blocks its caller for as long as it takes (seconds, for a scan). The isolate that opened an [Emon] stays
// free, and every handle (the engines, a session) is used from one thread at a time, as the C library asks. The callbacks the library makes
// from its own thread (the pack hook) go to the owner through memory both can see (mailbox.dart).
import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

import 'bindings.g.dart' as c;
import 'errors.dart';
import 'mailbox.dart';
import 'native.dart';
import 'oil.dart';
import 'ocr.dart';

/// What the worker needs to know of an adapter written in Dart: [OcrAdapter.info] and its name. The adapter itself stays on the owner's isolate.
class DartAdapterSpec {
  const DartAdapterSpec(this.name, this.info);
  final String name;
  final OcrAdapterInfo info;
}

class WorkerInit {
  const WorkerInit({
    required this.native,
    required this.pdfiumPath,
    required this.packDir,
    required this.packMode,
    required this.mailbox,
    required this.dartAdapters,
  });

  /// Adapter libraries to register: (name, path, config).
  final List<(String, String, String?)> native;
  final String? pdfiumPath;
  final String packDir;
  final int packMode;

  /// The address of the pack mailbox, or 0 for no hook.
  final int mailbox;
  final List<DartAdapterSpec> dartAdapters;
}

/// What a worker command answers.
class ModelResult {
  const ModelResult({required this.handle, required this.stats, required this.assets, required this.hasTranscript});
  final int handle;
  final Map<String, int> stats;
  final List<Map<String, Object?>> assets;
  final bool hasTranscript;
}

/// The reason a worker command failed, as plain data (an exception does not cross an isolate with its type).
class WorkerFailure {
  const WorkerFailure(this.name, this.message, this.category);
  final String name;
  final String message;
  final int category;

  EmonError toError() => EmonError(name, message, EmonErrorCategory.of(category));
}

/// Start the worker; the first message it sends is its command port.
Future<(Isolate, SendPort)> spawnWorker(SendPort replies) async {
  final first = ReceivePort();
  final isolate = await Isolate.spawn(_workerMain, (first.sendPort, replies), errorsAreFatal: false);
  final port = await first.first as SendPort;
  first.close();
  return (isolate, port);
}

void _workerMain((SendPort, SendPort) args) {
  final (ready, replies) = args;
  final commands = ReceivePort();
  final w = _Worker(replies);
  commands.listen((Object? m) {
    final msg = m as List;
    final id = msg[0] as int;
    final cmd = msg[1] as String;
    // a reply to a call the worker made to the owner (an adapter)
    if (cmd == '#reply') {
      w.adapterReply(id, msg[2]);
      return;
    }
    unawaited(w.handle(id, cmd, msg.length > 2 ? msg[2] : null));
  });
  ready.send(commands.sendPort);
}

class _Worker {
  _Worker(this.replies);

  final SendPort replies;
  Pointer<c.emon_engines> engines = nullptr;
  NativeCallable<c.emon_pack_needed_fnFunction>? hook;
  PackMailbox? mailbox;
  List<DartAdapterSpec> dartAdapters = const [];
  bool hasRenderer = false;
  final notes = <String>[];
  final transcripts = <int, Map<String, Uint8List>>{};
  final _adapterCalls = <int, Completer<Object?>>{};
  var _callId = 0;

  Future<void> handle(int id, String cmd, Object? arg) async {
    try {
      final Object? result = switch (cmd) {
        'init' => init(arg as WorkerInit),
        'convert' => await convert(arg as Map<String, Object?>),
        'convertText' => convertText(arg as Map<String, Object?>),
        'intake' => intake(arg as Map<String, Object?>),
        'describe' => describe(),
        'prepare' => prepare(arg as String),
        'notes' => engineNotes(),
        'modelJson' => modelText(arg as Map<String, Object?>, 'json'),
        'modelHtml' => modelText(arg as Map<String, Object?>, 'html'),
        'modelEvidence' => modelText(arg as Map<String, Object?>, 'evidence'),
        'modelAsset' => modelAsset(arg as Map<String, Object?>),
        'modelTranscript' => transcripts[arg as int] ?? const <String, Uint8List>{},
        'modelFree' => modelFree(arg as int),
        'close' => close(),
        _ => throw EmonError('internal', 'unknown worker command $cmd'),
      };
      replies.send([id, true, result]);
    } on EmonError catch (e) {
      replies.send([id, false, WorkerFailure(e.name, e.message, e.category.code)]);
    } on Object catch (e, st) {
      replies.send([id, false, WorkerFailure('internal', '$e\n$st', 13)]);
    }
  }

  // ---------------------------------------------------------------------------------------------- the engines

  Map<String, Object?> init(WorkerInit i) {
    dartAdapters = i.dartAdapters;
    return withErr((err) {
      final out = calloc<Pointer<c.emon_engines>>();
      try {
        err.check(c.emon_engines_new(out, err.ptr));
        engines = out.value;
      } finally {
        calloc.free(out);
      }
      final dir = i.packDir.toNativeUtf8();
      try {
        err.check(c.emon_engines_packs(engines, dir.cast(), i.packMode, err.ptr));
      } finally {
        calloc.free(dir);
      }
      if (i.mailbox != 0) {
        mailbox = PackMailbox.at(i.mailbox);
        hook = NativeCallable<c.emon_pack_needed_fnFunction>.isolateLocal(onPackNeeded, exceptionalReturn: 1);
        err.check(c.emon_on_pack_needed(engines, hook!.nativeFunction, nullptr, err.ptr));
      }
      final failures = <String>[];
      for (final (name, path, config) in i.native) {
        final p = path.toNativeUtf8();
        final cfg = config == null ? nullptr : config.toNativeUtf8();
        try {
          final r = c.emon_engines_load(engines, p.cast(), cfg.cast(), err.ptr);
          if (r < 0) {
            final e = err.take(r);
            failures.add('$name: ${e.name}: ${e.message}');
          }
        } finally {
          calloc.free(p);
          if (cfg != nullptr) calloc.free(cfg);
        }
      }
      if (i.pdfiumPath != null) {
        final p = i.pdfiumPath!.toNativeUtf8();
        try {
          final r = c.emon_engines_pdfium(engines, p.cast(), err.ptr);
          if (r < 0) {
            final e = err.take(r);
            failures.add('pdfium: ${e.name}: ${e.message}');
          } else {
            hasRenderer = true;
          }
        } finally {
          calloc.free(p);
        }
      }
      notes.addAll(failures);
      return <String, Object?>{'failures': failures, 'capabilities': c.emon_capabilities(), 'version': c.emon_version().cast<Utf8>().toDartString()};
    });
  }

  List<String> engineNotes() {
    final out = [...notes];
    if (engines == nullptr) return out;
    final buf = calloc<c.emon_buf>();
    final err = ErrBox();
    try {
      if (c.emon_engines_notes(engines, buf, err.ptr) >= 0) {
        final t = takeBufText(buf);
        out.addAll(t.split('\n').where((l) => l.isNotEmpty));
      }
    } finally {
      err.free();
      calloc.free(buf);
    }
    return out;
  }

  String describe() => withErr((err) {
        final buf = calloc<c.emon_buf>();
        try {
          err.check(c.emon_engines_describe(engines, buf, err.ptr));
          return takeBufText(buf);
        } finally {
          calloc.free(buf);
        }
      });

  String prepare(String options) => withErr((err) {
        final buf = calloc<c.emon_buf>();
        final o = options.toNativeUtf8();
        try {
          err.check(c.emon_prepare(engines, o.cast(), buf, err.ptr));
          return takeBufText(buf);
        } finally {
          calloc.free(buf);
          calloc.free(o);
        }
      });

  // The library asks what to do about a missing pack, on this isolate's own thread (the C library serves the app's callbacks there). The owner decides (the app's hook,
  // a download) and this thread waits for it in the mailbox: the isolate's event loop is inside the call and cannot.
  int onPackNeeded(Pointer<Void> ctx, Pointer<c.emon_pack_request> req, Pointer<c.emon_pack_decision> out) {
    final mb = mailbox;
    if (mb == null) return 1;
    String s(Pointer<Char> p) => p == nullptr ? '' : p.cast<Utf8>().toDartString();
    final r = req.ref;
    mb.ask();
    replies.send([
      -(++_callId),
      true,
      'pack',
      {'pack': s(r.pack), 'rev': r.rev, 'engine': s(r.engine), 'kind': s(r.kind), 'licence': s(r.licence), 'bytes': r.bytes, 'reason': s(r.reason), 'requestEngine': s(r.request_engine)},
    ]);
    final (action, path, engine) = mb.waitDecision();
    // the strings must live until the library has copied them, which is when this returns: native memory that is freed on the next call
    _freeDecision();
    out.ref.action = action;
    out.ref.data = nullptr;
    out.ref.len = 0;
    if (path.isNotEmpty) {
      _decisionPath = path.toNativeUtf8();
      out.ref.path = _decisionPath!.cast();
    }
    if (engine.isNotEmpty) {
      _decisionEngine = engine.toNativeUtf8();
      out.ref.engine = _decisionEngine!.cast();
    }
    return 0;
  }

  int _pngCacheId = -1;
  Uint8List? _pngCache;

  Pointer<Utf8>? _decisionPath;
  Pointer<Utf8>? _decisionEngine;

  void _freeDecision() {
    if (_decisionPath != null) calloc.free(_decisionPath!);
    if (_decisionEngine != null) calloc.free(_decisionEngine!);
    _decisionPath = null;
    _decisionEngine = null;
  }

  Object? close() {
    _freeDecision();
    hook?.close();
    hook = null;
    if (engines != nullptr) {
      c.emon_engines_free(engines);
      engines = nullptr;
    }
    return null;
  }

  // ---------------------------------------------------------------------------------------------- inputs

  // The input as native memory for one call: bytes (copied once), the password.
  Uint8List inputBytes(Map<String, Object?> a) {
    final t = a['bytes'];
    if (t is TransferableTypedData) return t.materialize().asUint8List();
    throw EmonError('no-input', 'no input', EmonErrorCategory.noInput);
  }

  // ---------------------------------------------------------------------------------------------- one-shot

  Object? convertText(Map<String, Object?> a) {
    final bytes = inputBytes(a);
    final options = a['options'] as String;
    final json = a['format'] == 'json';
    return withErr((err) {
      final data = calloc<Uint8>(bytes.isEmpty ? 1 : bytes.length)..asTypedList(bytes.length).setAll(0, bytes);
      final input = calloc<c.emon_input>();
      final pw = _password(a);
      final o = options.toNativeUtf8();
      final h = ((a['htmlOptions'] as String?) ?? '').toNativeUtf8();
      final buf = calloc<c.emon_buf>();
      try {
        input.ref
          ..data = data
          ..len = bytes.length
          ..source = nullptr
          ..password = pw.$1
          ..password_len = pw.$2;
        if (json) {
          err.check(c.emon_convert_json(input, o.cast(), engines, (a['pretty'] as bool? ?? false) ? 1 : 0, buf, err.ptr));
        } else {
          err.check(c.emon_convert_html(input, o.cast(), engines, h.cast(), buf, err.ptr));
        }
        return takeBufText(buf);
      } finally {
        calloc.free(data);
        calloc.free(input);
        if (pw.$1 != nullptr) calloc.free(pw.$1);
        calloc.free(o);
        calloc.free(h);
        calloc.free(buf);
      }
    });
  }

  (Pointer<Uint8>, int) _password(Map<String, Object?> a) {
    final pw = a['password'] as Uint8List?;
    if (pw == null || pw.isEmpty) return (nullptr, 0);
    final p = calloc<Uint8>(pw.length)..asTypedList(pw.length).setAll(0, pw);
    return (p, pw.length);
  }

  Map<String, Object?> intake(Map<String, Object?> a) {
    final bytes = inputBytes(a);
    final options = a['options'] as String;
    return withErr((err) {
      final data = calloc<Uint8>(bytes.isEmpty ? 1 : bytes.length)..asTypedList(bytes.length).setAll(0, bytes);
      final input = calloc<c.emon_input>();
      final pw = _password(a);
      final o = options.toNativeUtf8();
      final buf = calloc<c.emon_buf>();
      try {
        input.ref
          ..data = data
          ..len = bytes.length
          ..source = nullptr
          ..password = pw.$1
          ..password_len = pw.$2;
        err.check(c.emon_intake(input, o.cast(), buf, err.ptr));
        return {'line': takeBufText(buf)};
      } finally {
        calloc.free(data);
        calloc.free(input);
        if (pw.$1 != nullptr) calloc.free(pw.$1);
        calloc.free(o);
        calloc.free(buf);
      }
    });
  }

  // ---------------------------------------------------------------------------------------------- convert to a Model

  Future<ModelResult> convert(Map<String, Object?> a) async {
    final bytes = inputBytes(a);
    final options = a['options'] as String;
    final stepwise = a['stepwise'] as bool;
    if (!stepwise) {
      return withErr((err) {
        final data = calloc<Uint8>(bytes.isEmpty ? 1 : bytes.length)..asTypedList(bytes.length).setAll(0, bytes);
        final input = calloc<c.emon_input>();
        final pw = _password(a);
        final o = options.toNativeUtf8();
        final out = calloc<Pointer<c.emon_model>>();
        try {
          input.ref
            ..data = data
            ..len = bytes.length
            ..source = nullptr
            ..password = pw.$1
            ..password_len = pw.$2;
          err.check(c.emon_convert(input, o.cast(), engines, out, err.ptr));
          return _modelResult(out.value, null);
        } finally {
          calloc.free(data);
          calloc.free(input);
          if (pw.$1 != nullptr) calloc.free(pw.$1);
          calloc.free(o);
          calloc.free(out);
        }
      });
    }
    return convertStepwise(a, bytes, options);
  }

  ModelResult _modelResult(Pointer<c.emon_model> model, Map<String, Uint8List>? transcript) {
    final stats = calloc<c.emon_stats>();
    final err = ErrBox();
    try {
      final stat = <String, int>{};
      if (c.emon_model_stats(model, stats) == 0) {
        stat.addAll({
          'attempts': stats.ref.attempts,
          'fetchRounds': stats.ref.fetch_rounds,
          'bytesRequests': stats.ref.bytes_requests,
          'requests': stats.ref.requests,
          'sourceReads': stats.ref.source_reads,
          'bytesFetched': stats.ref.bytes_fetched,
          'inputBytes': stats.ref.input_bytes,
        });
      }
      final n = c.emon_model_asset_count(model);
      final assets = <Map<String, Object?>>[];
      for (var i = 0; i < n; i++) {
        final info = calloc<c.emon_asset_info>();
        try {
          if (c.emon_model_asset_info(model, i, info, err.ptr) == 0) {
            assets.add({
              'index': i,
              'id': info.ref.id,
              'name': info.ref.name.cast<Utf8>().toDartString(),
              'mediaType': info.ref.media_type.cast<Utf8>().toDartString(),
              'origin': info.ref.origin.cast<Utf8>().toDartString(),
              'sha256': info.ref.sha256.cast<Utf8>().toDartString(),
              'width': info.ref.width,
              'height': info.ref.height,
              'size': info.ref.size,
            });
            c.emon_free_asset_info(info);
          }
        } finally {
          calloc.free(info);
        }
      }
      if (transcript != null) transcripts[model.address] = transcript;
      return ModelResult(handle: model.address, stats: stat, assets: assets, hasTranscript: transcript != null);
    } finally {
      calloc.free(stats);
      err.free();
    }
  }

  Pointer<c.emon_model> _model(Map<String, Object?> a) => Pointer<c.emon_model>.fromAddress(a['handle'] as int);

  String modelText(Map<String, Object?> a, String what) => withErr((err) {
        final buf = calloc<c.emon_buf>();
        final h = ((a['htmlOptions'] as String?) ?? '').toNativeUtf8();
        try {
          switch (what) {
            case 'json':
              err.check(c.emon_model_json(_model(a), (a['pretty'] as bool? ?? false) ? 1 : 0, buf, err.ptr));
            case 'html':
              err.check(c.emon_model_html(_model(a), h.cast(), buf, err.ptr));
            default:
              err.check(c.emon_model_evidence(_model(a), buf, err.ptr));
          }
          return takeBufText(buf);
        } finally {
          calloc.free(buf);
          calloc.free(h);
        }
      });

  Uint8List modelAsset(Map<String, Object?> a) => withErr((err) {
        final buf = calloc<c.emon_buf>();
        try {
          err.check(c.emon_model_asset_bytes(_model(a), a['index'] as int, buf, err.ptr));
          return takeBuf(buf);
        } finally {
          calloc.free(buf);
        }
      });

  Object? modelFree(int handle) {
    transcripts.remove(handle);
    c.emon_free_model(Pointer<c.emon_model>.fromAddress(handle));
    return null;
  }

  // ---------------------------------------------------------------------------------------------- the step API

  Future<ModelResult> convertStepwise(Map<String, Object?> a, Uint8List bytes, String options) async {
    final err = ErrBox();
    final sess = calloc<Pointer<c.emon_session>>();
    final pw = _password(a);
    final o = options.toNativeUtf8();
    final data = calloc<Uint8>(bytes.isEmpty ? 1 : bytes.length)..asTypedList(bytes.length).setAll(0, bytes);
    final stepOut = calloc<c.emon_step_out>();
    Pointer<c.emon_session> s = nullptr;
    try {
      final chunk = (a['chunk'] as int?) ?? 0;
      err.check(c.emon_open(bytes.length, chunk, 0, engines, pw.$1, pw.$2, o.cast(), sess, err.ptr));
      s = sess.value;
      final replay = a['transcript'] as Map<String, Object?>?;
      if (replay != null) {
        for (final e in replay.entries) {
          final name = e.key.toNativeUtf8();
          final v = (e.value as TransferableTypedData).materialize().asUint8List();
          final d = calloc<Uint8>(v.isEmpty ? 1 : v.length)..asTypedList(v.length).setAll(0, v);
          try {
            err.check(c.emon_replay_file(s, name.cast(), d, v.length, err.ptr));
          } finally {
            calloc.free(name);
            calloc.free(d);
          }
        }
        err.check(c.emon_replay_end(s, err.ptr));
      }
      // A session renders with the registry's pdfium, which reads the whole input through the session's cache: give it all up front.
      if (hasRenderer && bytes.isNotEmpty) {
        err.check(c.emon_provide(s, 0, data, bytes.length, err.ptr));
      }
      while (true) {
        final st = err.check(c.emon_step(s, stepOut, err.ptr));
        if (st == c.EMON_DONE) break;
        if (st == c.EMON_NEED_BYTES) {
          final out = stepOut.ref;
          for (var i = 0; i < out.nranges; i++) {
            final r = (out.ranges + i).ref;
            final off = r.offset;
            final len = r.length;
            err.check(c.emon_provide(s, off, data + off, len, err.ptr));
          }
          c.emon_free_step(stepOut);
        } else if (st == c.EMON_NEED_REQUESTS) {
          final reqs = <(int, List<int>)>[];
          final out = stepOut.ref;
          for (var i = 0; i < out.nrequests; i++) {
            final r = (out.requests + i).ref;
            reqs.add((r.id, List<int>.from(r.cells.asTypedList(r.ncells))));
          }
          c.emon_free_step(stepOut);
          for (final (id, cells) in reqs) {
            await answerRequest(s, id, cells, err);
          }
        } else if (st == c.EMON_WAITING) {
          // native async adapters: let their threads work; also let this isolate's own messages in
          err.check(c.emon_wait(s, 20, err.ptr));
          await Future<void>.delayed(Duration.zero);
        }
      }
      Map<String, Uint8List>? transcript;
      if ((a['record'] as bool?) ?? false) {
        final files = calloc<c.emon_files>();
        try {
          err.check(c.emon_transcript(s, files, err.ptr));
          transcript = {};
          for (var i = 0; i < files.ref.n; i++) {
            final f = (files.ref.files + i).ref;
            transcript[f.name.cast<Utf8>().toDartString()] = Uint8List.fromList(f.data.asTypedList(f.len));
          }
          c.emon_free_files(files);
        } finally {
          calloc.free(files);
        }
      }
      final model = calloc<Pointer<c.emon_model>>();
      try {
        err.check(c.emon_finish(s, model, err.ptr));
        return _modelResult(model.value, transcript);
      } finally {
        calloc.free(model);
      }
    } finally {
      if (s != nullptr) c.emon_close(s);
      calloc.free(sess);
      calloc.free(stepOut);
      calloc.free(data);
      if (pw.$1 != nullptr) calloc.free(pw.$1);
      calloc.free(o);
      err.free();
    }
  }

  // ---------------------------------------------------------------------------------------------- an ocr request the caller owes

  Future<void> answerRequest(Pointer<c.emon_session> s, int id, List<int> cells, ErrBox err) async {
    final kind = cells.length > 1 ? cells[1] : -1;
    if (kind != c.emon_const(2)) {
      // not an ocr request: nothing here fulfils it
      _answerStatus(s, kind, id, 12, err);
      return;
    }
    final n = cells.length;
    final rc = calloc<Int32>(n)..asTypedList(n).setAll(0, cells);
    final info = calloc<c.emon_ocr_request_info>();
    try {
      err.check(c.emon_ocr_request(rc, n, info, err.ptr));
      final ri = info.ref;
      DartAdapterSpec? spec;
      for (final d in dartAdapters) {
        if (d.info.engine == ri.engine) spec = d;
      }
      if (spec == null) {
        _answerStatus(s, kind, id, 12, err);
        return;
      }
      final langs = <String>[];
      for (var i = 0; i < ri.nlangs && i < 8; i++) {
        final bytes = <int>[];
        for (var k = 0; k < 8; k++) {
          final ch = ri.langs[i][k];
          if (ch == 0) break;
          bytes.add(ch & 255);
        }
        langs.add(String.fromCharCodes(bytes));
      }
      // the bitmap as a PNG: the requests of a page share one, so the last one is kept (a PNG of a page takes a few hundred milliseconds to make)
      Uint8List png;
      if (_pngCacheId == ri.bitmap && _pngCache != null) {
        png = _pngCache!;
      } else {
        final buf = calloc<c.emon_buf>();
        try {
          err.check(c.emon_bitmap_png(s, ri.bitmap, buf, err.ptr));
          png = takeBuf(buf);
        } finally {
          calloc.free(buf);
        }
        _pngCacheId = ri.bitmap;
        _pngCache = png;
      }
      final (w, h) = _pngSize(png);
      final request = OcrRequest(
        id: id,
        psm: ri.psm,
        flags: ri.flags,
        dpi: ri.dpi,
        region: ri.w > 0 ? Box(ri.x, ri.y, ri.w, ri.h) : null,
        languages: langs,
        png: png,
        width: w,
        height: h,
        bitmap: ri.bitmap,
      );
      Object? reading;
      try {
        reading = await callOwner('adapter:${spec.name}', request);
      } on Object {
        _answerStatus(s, kind, id, 11, err);
        return;
      }
      if (reading is! OcrReading) {
        _answerStatus(s, kind, id, 12, err);
        return;
      }
      final flat = encodeOil(reading, spec.info, request);
      final fc = calloc<Int32>(flat.length)..asTypedList(flat.length).setAll(0, flat);
      try {
        final r = c.emon_answer_ocr(s, id, fc, flat.length, err.ptr);
        if (r < 0) {
          // the library refused the reading (a box outside the bitmap, ...): the page is unread, with the reason kept for the notes
          final e = err.take(r);
          notes.add('${spec.name}: ocr request $id refused: ${e.name}: ${e.message}');
          _answerStatus(s, kind, id, 11, err);
        }
      } finally {
        calloc.free(fc);
      }
    } finally {
      calloc.free(rc);
      calloc.free(info);
    }
  }

  void _answerStatus(Pointer<c.emon_session> s, int kind, int id, int status, ErrBox err) {
    final rec = calloc<Int32>(4);
    rec[0] = 4;
    rec[1] = kind < 0 ? 2 : kind;
    rec[2] = id;
    rec[3] = status;
    try {
      err.check(c.emon_answer(s, rec, 4, err.ptr));
    } finally {
      calloc.free(rec);
    }
  }

  (int, int) _pngSize(Uint8List png) {
    if (png.length < 24) return (0, 0);
    final bd = ByteData.sublistView(png);
    return (bd.getUint32(16), bd.getUint32(20));
  }

  // ---------------------------------------------------------------------------------------------- calls to the owner

  Future<Object?> callOwner(String what, Object? payload) {
    final id = ++_callId;
    final comp = Completer<Object?>();
    _adapterCalls[id] = comp;
    replies.send([-id, true, what, payload]);
    return comp.future;
  }

  void adapterReply(int id, Object? value) {
    final comp = _adapterCalls.remove(id);
    if (comp == null) return;
    if (value is WorkerFailure) {
      comp.completeError(value.toError());
    } else {
      comp.complete(value);
    }
  }
}

/// Encode the options text for a conversion that goes through the step API with the engines written in Dart owed to the caller.
String withJsOcr(String options) => options.isEmpty ? 'js-ocr=1' : '$options\njs-ocr=1';

/// A convenience: the first line of an intake as a map.
Map<String, Object?> parseIntake(String line) {
  final parts = line.trim().split(RegExp(r'\s+'));
  final m = <String, Object?>{'format': parts.isNotEmpty ? parts[0] : '', 'line': line.trim()};
  if (parts.length > 1) {
    final v = parts[1].split('.');
    m['version'] = [int.tryParse(v[0]) ?? 0, v.length > 1 ? int.tryParse(v[1]) ?? 0 : 0];
  }
  for (final p in parts.skip(2)) {
    final kv = p.split('=');
    if (kv.length == 2) m[kv[0]] = jsonDecode(kv[1]);
  }
  return m;
}
