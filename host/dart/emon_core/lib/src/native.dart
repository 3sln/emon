// Small helpers over dart:ffi that the rest of the package shares: where a loaded library lives, errors, buffers, strings.
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';

import 'bindings.g.dart' as c;
import 'errors.dart';

// ------------------------------------------------------------------------------------------------ where a library is

final class _DlInfo extends Struct {
  external Pointer<Utf8> fname;
  external Pointer<Void> fbase;
  external Pointer<Utf8> sname;
  external Pointer<Void> saddr;
}

typedef _DladdrC = Int Function(Pointer<Void>, Pointer<_DlInfo>);
typedef _DladdrD = int Function(Pointer<Void>, Pointer<_DlInfo>);

/// The file the library that holds the symbol at [address] was loaded from, or null when the platform does not say.
///
/// A build hook's code assets are loaded by the Dart VM from where the application put them (a bundle directory, a framework in an app, an
/// APK's library directory). This asks the loader, so the C library can be told where an adapter or pdfium is without a path ever being
/// written down.
String? libraryPathOf(int address) {
  if (address == 0) return null;
  try {
    if (Platform.isWindows) {
      final k32 = DynamicLibrary.open('kernel32.dll');
      final getModuleHandleEx = k32.lookupFunction<Int32 Function(Uint32, Pointer<Void>, Pointer<IntPtr>), int Function(int, Pointer<Void>, Pointer<IntPtr>)>('GetModuleHandleExW');
      final getModuleFileName = k32.lookupFunction<Uint32 Function(IntPtr, Pointer<Uint16>, Uint32), int Function(int, Pointer<Uint16>, int)>('GetModuleFileNameW');
      final hmod = calloc<IntPtr>();
      final buf = calloc<Uint16>(2048);
      try {
        // GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS (4) | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT (2)
        if (getModuleHandleEx(6, Pointer<Void>.fromAddress(address), hmod) == 0) return null;
        final n = getModuleFileName(hmod.value, buf, 2048);
        if (n == 0) return null;
        return String.fromCharCodes(buf.asTypedList(n));
      } finally {
        calloc.free(hmod);
        calloc.free(buf);
      }
    }
    final dladdr = DynamicLibrary.process().lookupFunction<_DladdrC, _DladdrD>('dladdr');
    final info = calloc<_DlInfo>();
    try {
      if (dladdr(Pointer<Void>.fromAddress(address), info) == 0 || info.ref.fname == nullptr) return null;
      return info.ref.fname.toDartString();
    } finally {
      calloc.free(info);
    }
  } on Object {
    return null;
  }
}

// ------------------------------------------------------------------------------------------------ errors and buffers

/// An `emon_error` to pass to a call: [check] turns what the call answered into an [EmonError].
final class ErrBox {
  ErrBox() : ptr = calloc<c.emon_error>();

  final Pointer<c.emon_error> ptr;

  /// Throws the error the call (which answered [status], negative for an error) filled in; returns [status] otherwise.
  int check(int status) {
    if (status >= 0) return status;
    throw take(status);
  }

  /// The error a call filled in (and frees its strings), for a [status] of `-code`.
  EmonError take([int status = 0]) {
    final e = ptr.ref;
    final name = e.name == nullptr ? 'internal' : e.name.cast<Utf8>().toDartString();
    final message = e.message == nullptr ? '' : e.message.cast<Utf8>().toDartString();
    final code = e.code != 0 ? e.code : -status;
    c.emon_free_error(ptr);
    return EmonError(name, message, EmonErrorCategory.of(code));
  }

  /// The error of a failure a call reported by status alone, with no emon_error filled in.
  void free() {
    c.emon_free_error(ptr);
    calloc.free(ptr);
  }
}

/// Runs [body] with an [ErrBox] that is freed afterwards.
T withErr<T>(T Function(ErrBox err) body) {
  final err = ErrBox();
  try {
    return body(err);
  } finally {
    err.free();
  }
}

/// The bytes of an `emon_buf` (a copy), freeing the buffer.
Uint8List takeBuf(Pointer<c.emon_buf> buf) {
  final b = buf.ref;
  final out = b.data == nullptr ? Uint8List(0) : Uint8List.fromList(b.data.asTypedList(b.len));
  c.emon_free_buf(buf);
  return out;
}

/// An `emon_buf` as text (UTF-8), freeing the buffer.
String takeBufText(Pointer<c.emon_buf> buf) => utf8.decode(takeBuf(buf), allowMalformed: true);
