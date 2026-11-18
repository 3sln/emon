/// Windows.Media.Ocr for emon.
///
/// Windows 10 or newer, with the language pack of the language to read installed. No confidence at all, no orientation detection. Select it explicitly with `ocrEngine: OcrEngine.windowsMediaOcr`.
///
/// ```dart
/// final emon = await Emon.open(nativeAdapters: [winocrAdapter()]);
/// ```
library;

import 'package:emon_core/emon_core.dart';

import 'src/bindings.dart';

/// The packs this engine reads with (ids of the catalogue): fetch them ahead of time with `emon.packs.ensure`.
const List<String> winocrPacks = [];

/// The Windows.Media.Ocr adapter, for [Emon.open]'s `nativeAdapters`.
///
/// Its library is bundled by this package's build hook; [NativeAdapter.symbolAddress] is 0 when this build has none.
NativeAdapter winocrAdapter({String? config}) => NativeAdapter(name: 'windows-media-ocr', symbolAddress: adapterSymbolAddress(), config: config);

/// Whether the library of this adapter is part of the build.
bool get winocrAvailable => adapterSymbolAddress() != 0;

/// The OCR engine of kin's table this adapter answers as.
const OcrEngine winocrEngine = OcrEngine.windowsMediaOcr;
