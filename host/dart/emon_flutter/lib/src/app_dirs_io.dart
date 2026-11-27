/// Where an app keeps packs (native).
library;

import 'dart:io';

import 'package:emon/emon.dart';
import 'package:path/path.dart' as p;

/// Where an app keeps packs when it does not say: the app's own files, never shared. Android: `<app data>/files/emon/packs` (the parent of the cache directory the temporary directory is);
/// iOS and macOS: `Library/Application Support/emon/packs` of the app's container; elsewhere the user's cache (the library's default).
String appPackDirectory() {
  if (Platform.isAndroid) {
    return p.join(Directory.systemTemp.parent.path, 'files', 'emon', 'packs');
  }
  if (Platform.isIOS || Platform.isMacOS) {
    final home = Platform.environment['HOME'];
    if (home != null && home.isNotEmpty) return p.join(home, 'Library', 'Application Support', 'emon', 'packs');
  }
  return defaultPackDirectory();
}
