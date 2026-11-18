// Bundles libemon (the core) and pdfium for the target as code assets (DEVLOG D884). See lib/hook.dart for where they come from.
import 'package:emon_core/hook.dart';
import 'package:hooks/hooks.dart';

Future<void> main(List<String> args) async {
  await build(args, (input, output) async {
    await addNativeAssets(input, output, const [
      NativeLibrary(assetName: 'src/bindings.g.dart', stem: 'emon'),
      NativeLibrary(assetName: 'src/pdfium_binding.dart', stem: 'pdfium', optional: true),
    ]);
  });
}
