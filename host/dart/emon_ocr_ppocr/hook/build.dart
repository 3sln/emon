// Bundle the PP-OCR adapter and its dynamic runtime from the same verified C archive.
import 'package:code_assets/code_assets.dart';
import 'package:emon_core/hook.dart';
import 'package:hooks/hooks.dart';

Future<void> main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;
    const directory = 'lib/emon/adapters';
    final runtimes = switch (input.config.code.targetOS) {
      OS.macOS => ['libonnxruntime.1.22.0.dylib'],
      OS.linux => ['libonnxruntime.so.1', 'libonnxruntime_providers_shared.so'],
      OS.windows => ['onnxruntime.dll', 'onnxruntime_providers_shared.dll'],
      OS.android => ['libonnxruntime.so'],
      _ => <String>[], // iOS links ORT into the adapter.
    };
    await addNativeAssets(input, output, [
      const NativeLibrary(assetName: 'src/bindings.dart', stem: 'emon_ocr_ppocr', directory: directory),
      for (final name in runtimes)
        NativeLibrary(assetName: name, stem: 'onnxruntime', fileName: name, directory: directory),
    ]);
  });
}
