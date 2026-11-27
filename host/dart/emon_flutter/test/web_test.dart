// emon_flutter compiles and runs on Flutter web (DEVLOG D9xx): `flutter test --platform chrome test/web_test.dart`.
@TestOn('browser')
library;

import 'package:emon_flutter/emon_flutter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('open names what is missing when the SDK is not where it was told', () async {
    await expectLater(EmonFlutter.open(web: const WebAssets(sdk: 'nowhere/sdk/')), throwsA(isA<EmonError>()));
  });

  test('there is no pack directory on the web', () => expect(appPackDirectory(), ''));
}
