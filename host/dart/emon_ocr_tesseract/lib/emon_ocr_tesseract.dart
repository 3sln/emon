/// Tesseract for emon.
///
/// Every platform. It needs the model packs `tess-fast-eng` (and `tess-osd` for orientation and script detection); other languages are packs of their own, fetched when a conversion asks for them.
///
/// ```dart
/// final emon = await Emon.open(nativeAdapters: [tesseractAdapter()]);
/// ```
library;

import 'package:emon_core/emon_core.dart';

import 'src/bindings.dart';

/// The packs this engine reads with (ids of the catalogue): fetch them ahead of time with `emon.packs.ensure`.
const List<String> tesseractPacks = ['tess-fast-eng', 'tess-osd'];

/// The Tesseract adapter, for [Emon.open]'s `nativeAdapters`.
///
/// Its library is bundled by this package's build hook; [NativeAdapter.symbolAddress] is 0 when this build has none.
NativeAdapter tesseractAdapter({String? config}) => NativeAdapter(name: 'tesseract', symbolAddress: adapterSymbolAddress(), config: config);

/// Whether the library of this adapter is part of the build.
bool get tesseractAvailable => adapterSymbolAddress() != 0;

/// The OCR engine of kin's table this adapter answers as.
const OcrEngine tesseractEngine = OcrEngine.tesseract;
