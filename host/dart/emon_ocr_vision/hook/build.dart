// Bundles the Apple Vision adapter library for the target as a code asset (DEVLOG D885). See package:emon_core/hook.dart for where it comes from.
import 'package:code_assets/code_assets.dart';
import 'package:emon_core/hook.dart';
import 'package:hooks/hooks.dart';

Future<void> main(List<String> args) async {
  await build(args, (input, output) async {
    if (!input.config.buildCodeAssets) return;
    if (![OS.macOS, OS.iOS].contains(input.config.code.targetOS)) return;
    await addNativeAssets(input, output, const [
      NativeLibrary(assetName: 'src/bindings.dart', stem: 'emon_ocr_vision', directory: 'lib/emon/adapters', optional: false),
    ]);
  });
}
