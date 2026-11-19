import 'dart:io';

import 'package:emon_core/emon_core.dart';

Future<void> main() async {
  final emon = await Emon.open();
  final model = await emon.convert(EmonInput.file('report.pdf'));
  File('report.html').writeAsStringSync(await model.html());
  await model.dispose();
  await emon.close();
}
