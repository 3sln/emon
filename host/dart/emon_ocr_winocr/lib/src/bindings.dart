// The one symbol the adapter library exports (emon_adapter.h): this only asks where it is; the C library opens it.
import 'dart:ffi';

@Native<Pointer<Void> Function(Pointer<Void>, Pointer<Void>, Pointer<Void>)>(symbol: 'emon_ocr_adapter_v1', assetId: 'package:emon_ocr_winocr/src/bindings.dart')
external Pointer<Void> emonOcrAdapterV1(Pointer<Void> config, Pointer<Void> packsDir, Pointer<Void> status);

/// The address of the symbol, or 0 when the library is not part of this build (it is not for the target, or the release has none).
int adapterSymbolAddress() {
  try {
    return Native.addressOf<NativeFunction<Pointer<Void> Function(Pointer<Void>, Pointer<Void>, Pointer<Void>)>>(emonOcrAdapterV1).address;
  } on Object {
    return 0;
  }
}
