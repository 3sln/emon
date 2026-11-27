/// The backend of this platform: the C library through `dart:ffi` where there is a VM, the wasm build and JS SDK through `dart:js_interop` on the web.
/// The public API is the same; the files below are never both compiled.
library;

export 'emon_io.dart' if (dart.library.js_interop) 'emon_web.dart';
