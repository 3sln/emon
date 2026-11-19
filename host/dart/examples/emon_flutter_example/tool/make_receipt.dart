// Makes assets/receipt.png: a SYNTHETIC "photo of a receipt" (invented shop, items and prices: no real document), slightly turned, unevenly lit, on a table.
//   dart run tool/make_receipt.dart        (needs `image` from dev_dependencies)
import 'dart:io';
import 'dart:math';

import 'package:image/image.dart' as img;

void main() {
  const w = 1000, h = 1500;
  final paperW = 640, paperH = 1200;
  final paper = img.Image(width: paperW, height: paperH, numChannels: 3);
  img.fill(paper, color: img.ColorRgb8(246, 244, 238));
  final lines = <(String, bool)>[
    ('GREEN LEAF GROCERS', true),
    ('12 Market Lane, Springfield', false),
    ('Tel 555-0142', false),
    ('', false),
    ('Date 2026-11-14  Time 10:42', false),
    ('Receipt No 004871   Till 3', false),
    ('--------------------------------', false),
    ('Oat milk 1L              2 x 1.89   3.78', false),
    ('Sourdough loaf                      3.20', false),
    ('Free range eggs 12                  3.49', false),
    ('Bananas 1.2kg            2.40', false),
    ('Cheddar mature 400g                 4.75', false),
    ('Tomatoes vine 500g                  2.10', false),
    ('Olive oil 500ml                     6.95', false),
    ('--------------------------------', false),
    ('SUBTOTAL                           26.67', false),
    ('VAT 5%                              1.33', false),
    ('TOTAL                              28.00', true),
    ('', false),
    ('CARD PAYMENT                       28.00', false),
    ('Visa **** 1234', false),
    ('', false),
    ('Thank you for shopping with us', false),
  ];
  var y = 50;
  for (final (text, big) in lines) {
    if (text.isNotEmpty) {
      img.drawString(paper, text, font: big ? img.arial48 : img.arial24, x: 30, y: y, color: img.ColorRgb8(30, 30, 34));
    }
    y += big ? 62 : 38;
  }
  final rnd = Random(7);
  // paper grain
  for (var i = 0; i < 30000; i++) {
    final x = rnd.nextInt(paperW), yy = rnd.nextInt(paperH);
    final p = paper.getPixel(x, yy);
    final d = rnd.nextInt(14) - 7;
    paper.setPixelRgb(x, yy, (p.r + d).clamp(0, 255), (p.g + d).clamp(0, 255), (p.b + d).clamp(0, 255));
  }
  final turned = img.copyRotate(paper, angle: 3.2, interpolation: img.Interpolation.cubic);
  final photo = img.Image(width: w, height: h, numChannels: 3);
  img.fill(photo, color: img.ColorRgb8(96, 78, 64));
  // wood-like table lines
  for (var yy = 0; yy < h; yy += 9) {
    img.drawLine(photo, x1: 0, y1: yy, x2: w, y2: yy + rnd.nextInt(5), color: img.ColorRgb8(84 + rnd.nextInt(14), 66 + rnd.nextInt(12), 54 + rnd.nextInt(10)));
  }
  img.compositeImage(photo, turned, dstX: (w - turned.width) ~/ 2, dstY: (h - turned.height) ~/ 2);
  // uneven light: a soft gradient from the top left
  for (var yy = 0; yy < h; yy++) {
    for (var x = 0; x < w; x++) {
      final p = photo.getPixel(x, yy);
      final f = 1.08 - 0.28 * ((x / w) * 0.6 + (yy / h) * 0.4);
      photo.setPixelRgb(x, yy, (p.r * f).clamp(0, 255).round(), (p.g * f).clamp(0, 255).round(), (p.b * f).clamp(0, 255).round());
    }
  }
  File('assets/receipt.png').writeAsBytesSync(img.encodePng(img.gaussianBlur(photo, radius: 1)));
  stdout.writeln('wrote assets/receipt.png');
}
