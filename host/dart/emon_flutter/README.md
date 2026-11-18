# emon_flutter

[emon](https://github.com/3sln/emon) for Flutter: PDF and image to semantic HTML and JSON with OCR that works out of the box.

```dart
final emon = await EmonFlutter.open();
final model = await emon.convert(EmonInput.file(path));
final html = await model.html();
```

* **Android**: ML Kit text recognition (the plugin `emon_ocr_mlkit`, models bundled in the app: no Play services, no download), with Tesseract when ML Kit cannot run. Add recognisers for other scripts in
  `android/gradle.properties` (`emonMlkitScripts=latin,chinese,japanese`).
* **iOS, macOS, Windows, Linux**: Tesseract, with Apple Vision (`OcrEngine.appleVision`) selectable.
* **Packs** (language models) are fetched on first use into the app's support directory, by `dart:io`. `EmonFlutter.downloads` is a `ChangeNotifier` that follows every download; `engines()`, `prepare()`,
  `emon.packs.ensure()` and `onPackNeeded` let your UI decide what is fetched (DEVLOG D896).
* `example/` (in the repository: `host/dart/examples/emon_flutter_example`) converts a bundled PDF and a photo of a receipt, shows the HTML, lets you pick the engine and shows download progress.
