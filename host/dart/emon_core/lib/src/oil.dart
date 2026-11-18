// OIL v1 (DEVLOG D883, kin/oil.kin): the integers an `ocr` answer is made of, written from an [OcrReading].
//
// The layout is kin's, and exists once, in kin: this writes the FLAT form (the 24-cell header, blocks, lines, then words), which
// `emon_answer_ocr` takes, checks against the bitmap and seals. A reading that does not fit is refused there, by name (`ocr-adapter-invalid`
// for a registered adapter; a usage error for the plain step API), so a drift from kin's layout shows at once rather than as a wrong page.
// What this writes covers the capabilities a Dart engine can state: blocks, quad, line and word confidence, word language. Others are
// refused here ([UnsupportedError]) because writing them half-way would be dishonest.
import 'dart:convert';
import 'dart:typed_data';

import 'ocr.dart';

const _supported = OilCap.blocks | OilCap.quad | OilCap.lconf | OilCap.wconf | OilCap.wlang;

/// Four ASCII characters in one integer, big-endian: how languages and scripts are written in an answer.
int pack4(String? s) {
  if (s == null || s.isEmpty) return 0;
  var v = 0;
  for (var i = 0; i < 4; i++) {
    v = v * 256 + (i < s.length ? s.codeUnitAt(i) & 255 : 0);
  }
  return v.toSigned(32);
}

/// The names back from [pack4].
String unpack4(int v) {
  final b = StringBuffer();
  for (var i = 0; i < 4; i++) {
    final c = (v >> (24 - 8 * i)) & 255;
    if (c != 0) b.writeCharCode(c);
  }
  return b.toString();
}

/// kin's `oil-seg-from-tess`: the segmentation a Tesseract page-segmentation mode asks for.
int segOfPsm(int psm) {
  switch (psm) {
    case 6:
      return 2; // block
    case 7:
      return 3; // single line
    case 11:
      return 4; // sparse
    case 8:
      return 5; // single word
    default:
      return 1; // page, automatic
  }
}

/// The observed identity as the two header cells: FNV-1a 64 over the UTF-8 bytes (kin's `oil-obs-hash`).
(int hi, int lo) observedHash(String observed) {
  var h = -3750763034362895579; // 0xcbf29ce484222325
  for (final b in utf8.encode(observed)) {
    h = (h ^ b) * 1099511628211; // wraps at 64 bits, as the native int does
  }
  return ((h >> 32).toSigned(32), h.toSigned(32));
}

/// The flat OIL form of [reading], as [info] says the engine answers, for [request].
Int32List encodeOil(OcrReading reading, OcrAdapterInfo info, OcrRequest request) {
  if (info.caps & ~_supported != 0) {
    throw UnsupportedError('the Dart OIL writer does not write capabilities 0x${(info.caps & ~_supported).toRadixString(16)}');
  }
  final caps = info.caps;
  bool has(int bit) => caps & bit != 0;
  final blocks = has(OilCap.blocks) ? reading.blocks : const <OcrBlock>[];
  final v = <int>[];
  int clampX(int x) => x.clamp(0, request.width);
  int clampY(int y) => y.clamp(0, request.height);
  // a box that lies inside the bitmap, width and height of at least 0
  List<int> box(Box b) {
    final x0 = clampX(b.x), y0 = clampY(b.y);
    final x1 = clampX(b.x + b.w), y1 = clampY(b.y + b.h);
    return [x0, y0, x1 - x0, y1 - y0];
  }

  var nwords = 0;
  for (final l in reading.lines) {
    nwords += l.words.length;
  }
  final (hi, lo) = observedHash(info.observed);
  v.addAll([
    1, // version
    info.engine,
    hi,
    lo,
    caps,
    info.native,
    segOfPsm(request.psm),
    info.wordConfidence ? 1 : 2, // ck: word or line
    1000, // the confidence scale
    0, 0, 0, 0, 0, 0, 0, // orient oconf deskew script sconf wdir lorder
    pack4(reading.language),
    info.granularity,
    blocks.length,
    reading.lines.length,
    nwords,
    0, 0, 0, // sidecars, binary, spare
  ]);
  assert(v.length == 24);
  for (final b in blocks) {
    v
      ..addAll(box(b.box))
      ..add(-1); // no block confidence
  }
  for (final l in reading.lines) {
    v
      ..addAll(box(l.box))
      ..add(has(OilCap.blocks) ? l.block : -1)
      ..add(l.words.length);
    if (has(OilCap.lconf)) v.add(l.confidence);
    if (has(OilCap.quad)) {
      final q = l.quad;
      if (q != null && q.length == 8) {
        for (var i = 0; i < 8; i += 2) {
          v
            ..add(clampX(q[i]))
            ..add(clampY(q[i + 1]));
        }
      } else {
        final b = box(l.box);
        v.addAll([b[0], b[1], b[0] + b[2], b[1], b[0] + b[2], b[1] + b[3], b[0], b[1] + b[3]]);
      }
    }
  }
  for (var li = 0; li < reading.lines.length; li++) {
    final l = reading.lines[li];
    for (final w in l.words) {
      final cps = w.text.runes.toList();
      v
        ..addAll(box(w.box))
        ..add(li)
        ..add(info.wordConfidence ? w.confidence : l.confidence);
      if (has(OilCap.wlang)) v.add(pack4(w.language ?? reading.language));
      v
        ..add(0) // symbols
        ..add(cps.length)
        ..addAll(cps);
    }
  }
  return Int32List.fromList(v);
}
