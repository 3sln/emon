/// The pack store of this platform (the native one, or the web's, which fetches nothing).
library;

export 'pack_store_io.dart' if (dart.library.js_interop) 'pack_store_web.dart';
