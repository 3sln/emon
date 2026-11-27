/// Where an app keeps packs: the native directory, or none on the web.
library;

export 'app_dirs_io.dart' if (dart.library.js_interop) 'app_dirs_web.dart';
