// The pdfium library the build hook bundles (a code asset of its own): all this needs of it is where it lives, so the C library can be told.
import 'dart:ffi';

@Native<Void Function()>(symbol: 'FPDF_InitLibrary', assetId: 'package:emon_core/src/pdfium_binding.dart')
external void _fpdfInitLibrary();

/// The address of a symbol of the bundled pdfium, or 0 when this build has none (the hook found no pdfium for the target).
int pdfiumSymbolAddress() {
  try {
    return Native.addressOf<NativeFunction<Void Function()>>(_fpdfInitLibrary).address;
  } on Object {
    return 0;
  }
}
