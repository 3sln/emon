/// PP-OCR for emon.
///
/// The adapter and ONNX Runtime ship in the C archive. The engine needs [ppocrPacks], which can be fetched on demand.
///
/// ```dart
/// final emon = await Emon.open(nativeAdapters: [ppocrAdapter()]);
/// ```
library;

import 'package:emon_core/emon_core.dart';

import 'src/bindings.dart';

/// The packs this engine reads with (ids of the catalogue): fetch them ahead of time with `emon.packs.ensure`.
const List<String> ppocrPacks = ['pp5-det-mobile', 'pp5-cls-mobile', 'pp5-rec-en-mobile', 'tess-fast-eng'];

/// The PP-OCR adapter, for [Emon.open]'s `nativeAdapters`.
///
/// Its library is bundled by this package's build hook; [NativeAdapter.symbolAddress] is 0 when this build has none.
NativeAdapter ppocrAdapter({String? config}) => NativeAdapter(name: 'ppocr', symbolAddress: adapterSymbolAddress(), config: config);

/// Whether the library of this adapter is part of the build.
bool get ppocrAvailable => adapterSymbolAddress() != 0;

/// The OCR engine of kin's table this adapter answers as.
const OcrEngine ppocrEngine = OcrEngine.ppocr;
