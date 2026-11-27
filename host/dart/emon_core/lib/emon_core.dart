/// emon: PDF and image to semantic HTML and JSON, over the emon C library.
///
/// ```dart
/// final emon = await Emon.open();
/// final model = await emon.convert(EmonInput.file('report.pdf'));
/// print(await model.html());
/// ```
///
/// This is the core: no OCR engine and no model packs come with it. OCR engines are adapters (the `emon_ocr_*` packages, or one of your own
/// written in Dart: [OcrAdapter]); the turnkey packages `emon` and `emon_flutter` choose them for a platform. See [Emon.open].
library;

export 'src/emon.dart' show Asset, Emon, EmonInput, Model, defaultPackDirectory;
export 'src/types.dart' show ConvertStats, IntakeResult;
export 'src/web_assets.dart' show WebAssets, WebPdfium, WebTesseract;
export 'src/engines.dart' show EngineInfo, EnginePack, EngineState, NeededPack, PrepareResult, PrepareVerdict;
export 'src/errors.dart' show EmonError, EmonErrorCategory;
export 'src/ocr.dart';
export 'src/oil.dart' show encodeOil, observedHash, pack4, segOfPsm, unpack4;
export 'src/options.dart' show ConvertOptions, HtmlOptions, OcrEngine, OcrMode;
export 'src/packs.dart'
    show
        CancelToken,
        PackCatalog,
        PackDecision,
        PackEvent,
        PackEventKind,
        PackFetch,
        PackFile,
        PackInfo,
        PackInitiator,
        PackMode,
        PackBackend,
        PackNeededHook,
        PackProvide,
        PackRequest,
        Packs,
        PackSubstitute,
        PackDecline;
export 'src/pack_store.dart' show PackStore;
