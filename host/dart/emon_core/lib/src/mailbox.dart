// A pack decision crosses from the isolate that is converting to the isolate that owns the app's hook, and back.
//
// The C library asks the hook on the caller's thread (it serves the callbacks of the app while its worker converts), and waits for the answer. The
// worker isolate is inside that call, so it cannot await: it posts the request to the owner (a SendPort message: that never blocks) and waits on
// this block of memory, which the owner fills in when the app's hook has decided (it may await a dialog or a download). Only plain integers and
// NUL-terminated strings live here.
//
// layout:  0 int32 state (0 idle, 1 asked, 2 decided)   4 int32 action   [kPathOffset, +8192) path (NUL terminated)   [kEngineOffset, +256) engine
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

const int kMailboxSize = 16384;
const int _kPathOffset = 4096;
const int _kPathMax = 8000;
const int _kEngineOffset = _kPathOffset + 8192;
const int _kEngineMax = 200;

/// A mailbox: memory the owner allocates and the worker is told the address of.
final class PackMailbox {
  PackMailbox() : ptr = calloc<Uint8>(kMailboxSize);
  PackMailbox.at(int address) : ptr = Pointer<Uint8>.fromAddress(address);

  final Pointer<Uint8> ptr;

  int get address => ptr.address;

  /// Worker: a request is about to be posted; the previous decision is gone.
  void ask() => ptr.cast<Int32>().value = 1;

  /// Worker: block until the owner has decided: (action, path, engine). The thread sleeps; the isolate's event loop does not run.
  (int, String, String) waitDecision() {
    final state = ptr.cast<Int32>();
    while (state.value != 2) {
      sleep(const Duration(milliseconds: 2));
    }
    final action = (ptr + 4).cast<Int32>().value;
    String text(int off, int max) {
      var n = 0;
      while (n < max && ptr[off + n] != 0) {
        n++;
      }
      return utf8.decode(Uint8List.fromList((ptr + off).asTypedList(n)), allowMalformed: true);
    }

    final r = (action, text(_kPathOffset, _kPathMax), text(_kEngineOffset, _kEngineMax));
    state.value = 0;
    return r;
  }

  /// Owner: answer the posted request: [action] is `EMON_PACK_*`; [path] for provide; [engine] for substitute.
  void answer(int action, {String path = '', String engine = ''}) {
    void put(int off, int max, String v) {
      final b = utf8.encode(v);
      final n = b.length < max ? b.length : max;
      (ptr + off).asTypedList(n + 1)
        ..setAll(0, b.sublist(0, n))
        ..[n] = 0;
    }

    put(_kPathOffset, _kPathMax, path);
    put(_kEngineOffset, _kEngineMax, engine);
    (ptr + 4).cast<Int32>().value = action;
    ptr.cast<Int32>().value = 2;
  }

  void free() => calloc.free(ptr);
}
