/// emon for Flutter.
///
/// ```dart
/// final emon = await EmonFlutter.open();              // ML Kit on Android, Tesseract elsewhere (Apple Vision selectable), packs on demand
/// final model = await emon.convert(EmonInput.file(path));
/// final html = await model.html();
/// ```
///
/// Everything of [emon] is here; what Flutter adds is [EmonFlutter]: the app's own cache directory for packs, the Android engine (ML Kit, with Tesseract when it is not usable), and
/// [PackDownloads], a [ChangeNotifier] that follows every download (the library's and the app's, and ML Kit's through Play services) so a widget can show progress and offer to cancel.
library;

export 'package:emon/emon.dart';
export 'package:emon_ocr_mlkit/emon_ocr_mlkit.dart' show MlKitAdapter;

export 'src/downloads.dart';
export 'src/emon_flutter.dart';
