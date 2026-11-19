import 'dart:io';

import 'package:emon/emon.dart';

Future<void> main() async {
  // the turnkey: Tesseract through its adapter library, pdfium, and the packs it reads with (fetched on first use; here they are already beside the app)
  final emon = await openEmon(packs: PackMode.never);
  final html = await emon.convertToHtml(EmonInput.file('scan.pdf'));
  File('scan.html').writeAsStringSync(html);
  await emon.close();
}
