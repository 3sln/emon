/// Apple Vision for emon.
///
/// iOS 13 and macOS 10.15 or newer. It reads with the OS's own recogniser and needs no model pack. Its confidences are quantized, it has no orientation detection and no blocks: select it explicitly with `ocrEngine: OcrEngine.appleVision`.
///
/// ```dart
/// final emon = await Emon.open(nativeAdapters: [visionAdapter()]);
/// ```
library;

import 'package:emon_core/emon_core.dart';

import 'src/bindings.dart';

/// The packs this engine reads with (ids of the catalogue): fetch them ahead of time with `emon.packs.ensure`.
const List<String> visionPacks = [];

/// The Apple Vision adapter, for [Emon.open]'s `nativeAdapters`.
///
/// Its library is bundled by this package's build hook; [NativeAdapter.symbolAddress] is 0 when this build has none.
NativeAdapter visionAdapter({String? config}) => NativeAdapter(name: 'apple-vision', symbolAddress: adapterSymbolAddress(), config: config);

/// Whether the library of this adapter is part of the build.
bool get visionAvailable => adapterSymbolAddress() != 0;

/// The OCR engine of kin's table this adapter answers as.
const OcrEngine visionEngine = OcrEngine.appleVision;
