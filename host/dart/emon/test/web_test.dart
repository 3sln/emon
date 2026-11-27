// The turnkey compiles and runs in a browser (DEVLOG D9xx): `dart test -p chrome test/web_test.dart`. The conversions are held by emon_core's browser test.
@TestOn('browser')
library;

import 'package:emon/emon.dart';
import 'package:test/test.dart';

void main() {
  test('the web turnkey has no native adapters and defaults to the SDK\'s engine', () {
    expect(platformAdapters(), isEmpty);
    expect(defaultPlatformEngine, OcrEngine.tesseract);
  });

  test('openEmon names what is missing when the SDK is not where it was told', () async {
    await expectLater(openEmon(web: const WebAssets(sdk: 'nowhere/sdk/')), throwsA(isA<EmonError>()));
  });
}
