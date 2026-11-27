/// Where the web backend finds the files it runs on (DEVLOG D9xx). A native build never reads these: they are accepted everywhere so one `Emon.open` call works on every platform.
///
/// The files are the ones `@3sln/emon` (the JS SDK) ships: its `src/` directory and `emon.wasm`, plus, for the engines, the pdfium and tesseract.js builds the SDK's own demo uses.
/// The app serves them (a `web/emon/` directory, or Flutter assets); nothing is fetched from anywhere else, and nothing here is a download the library makes for the app.
library;

/// tesseract.js, the OCR engine of the web (the SDK's `tesseractPort`).
class WebTesseract {
  const WebTesseract({
    required this.module,
    required this.worker,
    required this.core,
    required this.lang,
    this.languages = const ['eng'],
    this.workers = 1,
    this.gzip = true,
  });

  /// The ES module that has `createWorker` (tesseract.js's `tesseract.esm.min.js`, default export or a named one).
  final String module;

  /// tesseract.js's `workerPath`.
  final String worker;

  /// tesseract.js's `corePath` (a directory of its WebAssembly cores).
  final String core;

  /// tesseract.js's `langPath`: where the language models are. THIS is the web's model pack: the engine loads them itself, from the app's own files.
  final String lang;

  /// The models the engine loads (tesseract.js's names: `eng`, `deu`, `chi_sim`).
  final List<String> languages;

  /// Recognitions that run side by side.
  final int workers;

  /// Whether the models are `.traineddata.gz`.
  final bool gzip;
}

/// pdfium's WebAssembly build, the renderer of scanned pages and figures (the SDK's `pdfiumRenderer`).
class WebPdfium {
  const WebPdfium({required this.module, required this.wasm});

  /// The ES module with pdfium's factory (`pdfium.mjs`).
  final String module;

  /// pdfium's `.wasm`.
  final String wasm;
}

/// Where the web backend's files are. URLs are relative to the page (or absolute).
class WebAssets {
  const WebAssets({this.sdk = 'emon/sdk/', this.wasm = 'emon/emon.wasm', this.tesseract, this.pdfium});

  /// The directory of the JS SDK's `src/` (`index.js`, `tesseract.js`, `pdfium.js`, ...), ending in `/`.
  final String sdk;

  /// `emon.wasm`.
  final String wasm;

  /// The OCR engine, or null for none: pages that need OCR are then unread (reported, never blank).
  final WebTesseract? tesseract;

  /// The renderer, or null for none: scanned PDFs and figures then cannot be rendered.
  final WebPdfium? pdfium;
}
