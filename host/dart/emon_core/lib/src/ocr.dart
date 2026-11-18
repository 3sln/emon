/// The pieces of an OCR engine written in Dart (an engine behind a Flutter plugin, a service of your own): what it is asked ([OcrRequest]),
/// what it says it is ([OcrAdapterInfo]), what it answers ([OcrReading]), and the [OcrAdapter] that connects them.
///
/// Engines that are libraries (Tesseract, Apple Vision, PP-OCR, Windows.Media.Ocr) are registered with the C library by path
/// ([NativeAdapter]); an engine that lives in Dart answers through [OcrAdapter]. Either way an answer is OIL, emon's engine-neutral
/// OCR interchange (DEVLOG D883), checked by the library, and a reading the engine cannot give is `unsupported` or `failed`: the page is
/// then UNREAD, never blank.
library;

import 'dart:typed_data';

import 'packs.dart' show PackEvent;

/// A rectangle in pixels of the request's bitmap.
class Box {
  const Box(this.x, this.y, this.w, this.h);
  final int x, y, w, h;

  @override
  String toString() => 'Box($x, $y, $w x $h)';
}

/// What an engine is asked: one `ocr` request of emon's plan, and the pixels it names.
class OcrRequest {
  const OcrRequest({
    required this.id,
    required this.psm,
    required this.flags,
    required this.dpi,
    required this.region,
    required this.languages,
    required this.png,
    required this.width,
    required this.height,
    this.bitmap = 0,
  });

  /// The request's id (an answer carries it).
  final int id;

  /// Tesseract's page segmentation mode: 3 or 6 for a page or block, 7 a single line, 8 a single word, 11 sparse text.
  final int psm;

  /// emon's flags: 1 symbols and alternatives wanted, 2 orientation and script detection wanted, 4 `eng_best`, 8 source measurements.
  final int flags;

  /// The resolution the bitmap is at, or 0.
  final int dpi;

  /// The region of the bitmap to read, or null for all of it. A one-line or one-word [psm] with a region is a re-read of a cell or word.
  final Box? region;

  /// The languages as emon names them (`eng`, `deu`, `chs`, `cht`) and scripts (ISO 15924: `Latn`, `Cyrl`); empty: whatever the engine has.
  final List<String> languages;

  /// The whole bitmap, as a PNG. Coordinates in a [OcrReading] are pixels of this bitmap, never of the region.
  final Uint8List png;
  final int width, height;

  /// Which bitmap of the conversion [png] is: the requests of one page name the same, so an engine may keep what it decoded of it.
  final int bitmap;

  bool get wantsAlternatives => flags & 1 != 0;
  bool get wantsOrientation => flags & 2 != 0;

  /// A re-read of one line or word (psm 7, 8 or 13): nothing found there is no reading, not a blank page.
  bool get isSingle => psm == 7 || psm == 8 || psm == 13;
}

/// A word.
class OcrWord {
  const OcrWord(this.text, this.box, {this.confidence = -1, this.language});

  final String text;
  final Box box;

  /// 0..1000, or -1 when the engine gives none for words.
  final int confidence;

  /// The language of the word as emon names it (`eng`), or null.
  final String? language;
}

/// A line of words.
class OcrLine {
  const OcrLine(this.words, {required this.box, this.confidence = -1, this.quad, this.block = -1});

  final List<OcrWord> words;
  final Box box;

  /// 0..1000, or -1.
  final int confidence;

  /// The four corners, clockwise from the top left: x0, y0, x1, y1, x2, y2, x3, y3.
  final List<int>? quad;

  /// The index in [OcrReading.blocks], or -1.
  final int block;
}

/// A block of lines (a paragraph or a column, as the engine sees it).
class OcrBlock {
  const OcrBlock(this.box);
  final Box box;
}

/// What an engine read.
class OcrReading {
  const OcrReading({required this.lines, this.blocks = const [], this.language});

  final List<OcrLine> lines;
  final List<OcrBlock> blocks;

  /// The language of the whole page as emon names it, or null.
  final String? language;
}

/// The OIL capability bits (kin's `oc`): what a reading can say besides words and their boxes.
abstract final class OilCap {
  static const blocks = 1;
  static const paras = 2;
  static const btype = 4;
  static const baseline = 8;
  static const rowmetrics = 16;
  static const quad = 32;
  static const angle = 64;
  static const lconf = 128;
  static const wconf = 256;
  static const orient = 512;
  static const deskew = 1024;
  static const script = 2048;
  static const wdir = 4096;
  static const wlang = 8192;
  static const lexicon = 16384;
  static const style = 32768;
  static const psize = 65536;
  static const symbols = 131072;
  static const symconf = 262144;
  static const alts = 524288;
  static const walts = 1048576;
  static const order = 2097152;
  static const binary = 4194304;
}

/// What an engine says it is.
class OcrAdapterInfo {
  const OcrAdapterInfo({
    required this.engine,
    required this.caps,
    required this.native,
    required this.config,
    required this.observed,
    this.granularity = 2,
    this.wordConfidence = true,
  });

  /// The OIL engine the answers carry (`EMON_OEN_*`: 4 is ML Kit's).
  final int engine;

  /// The capabilities the readings declare (a subset of what kin's engine table lets the engine declare).
  final int caps;

  /// Of [caps], those the engine MEASURES rather than derives.
  final int native;

  /// The canonical requested-configuration text of the engine: the text kin puts in each request for it, which is what makes two runs
  /// the same configuration. kin owns it (`oe-config-*`); an adapter cannot choose another.
  final String config;

  /// The observed identity: the engine's version and what it reads with. Stable for the life of the adapter.
  final String observed;

  /// 0 layout lines, 1 segments (a detector's boxes), 2 a recogniser's lines.
  final int granularity;

  /// True when each word carries its own confidence (`ck` word), false when only lines do (`ck` line).
  final bool wordConfidence;
}

/// An OCR engine written in Dart.
///
/// Register it in [Emon.open] (`adapters`); select it with `ocrEngine` of the conversion's options. [read] is called on the isolate that
/// opened the [Emon] (a Flutter plugin's platform channel works there), one request at a time.
abstract class OcrAdapter {
  /// The engine's name in kin's engine table (`mlkit-text`).
  String get name;

  /// What it is.
  OcrAdapterInfo get info;

  /// Whether the engine can run here and now (the platform has it, its models are there). Asked when the adapter is registered.
  Future<bool> isAvailable() async => true;

  /// Read one request. Return null for `unsupported` (a language the engine has no recogniser for, a region too small): the page is
  /// unread. A returned reading with no lines claims the region is blank, so only return one when the engine looked. A thrown error is
  /// `failed`: also unread.
  Future<OcrReading?> read(OcrRequest request);

  /// Called once when the adapter is registered with an [Emon]: [events] reports downloads the engine makes for itself (a platform's models, such as ML Kit's through Play services)
  /// on the same stream as the packs ([Packs.events]).
  void attach(void Function(PackEvent event) events) {}

  /// Free what the adapter holds.
  Future<void> close() async {}
}

/// An engine that is a library exporting `emon_ocr_adapter_v1` (emon_adapter.h), registered with the C library by its path.
///
/// An adapter package makes one with the address of the symbol its build hook bundled: the path of the library is then asked of the loader,
/// so no path is ever written down.
class NativeAdapter {
  const NativeAdapter({required this.name, this.symbolAddress = 0, this.path, this.config});

  /// The engine's name (`tesseract`).
  final String name;

  /// The address of the library's `emon_ocr_adapter_v1` (its path is asked of the loader).
  final int symbolAddress;

  /// Or its path.
  final String? path;

  /// The adapter's own `key=value` lines, or null.
  final String? config;
}
