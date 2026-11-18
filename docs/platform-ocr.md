# The platform's own OCR: Apple Vision and Windows.Media.Ocr

An app can read scans with the recogniser the operating system already has, so there is no model to download and nothing to add to the
app's size. Both are **explicit engines**: Tesseract stays the default everywhere, and `ocr-engine=` is how an app, or you on the command
line, asks for the other (DEVLOG D895, D901).

| `ocr-engine` | Runs on | What reads | Adapter library |
|---|---|---|---|
| `apple-vision` | macOS 12 or later, iOS 13 or later | `VNRecognizeTextRequest`, accurate level, language correction | `libemon_ocr_vision.dylib` |
| `windows-media-ocr` | Windows 10 or later | `Windows.Media.Ocr.OcrEngine`, the installed language packs | `emon_ocr_winocr.dll` |

`emon convert --ocr-engine apple-vision scan.pdf` on a Mac, `--ocr-engine windows-media-ocr` on Windows. In the C library the adapter
sits in `lib/emon/adapters/` beside Tesseract's, and `emon_engines_defaults` finds it; the app names the engine in the options of the
conversion (`ocr-engine=apple-vision`). An engine that cannot run on the platform is `unavailable` in `engines()`.

## What they say, and what they do not

An OCR engine answers in OIL, emon's own interchange (D883), and declares which parts of it are real. These two are honest about it.

| | Apple Vision | Windows.Media.Ocr |
|---|---|---|
| words and their boxes | yes (a box for each word, `boundingBox(for:)`) | yes (`OcrWord.BoundingRect`) |
| lines | yes, an OS recogniser line, with its quad and angle | yes, the union of its words' rectangles |
| confidence | one number for each **line**, quantized (the values seen are 300, 500 and 1000), which the line's words inherit | **none**: the answer says so and carries no number |
| alternatives | the line's other candidates, as word alternatives | none |
| language | the one language the request names | the one language the request names |
| page orientation and script (OSD) | none: emon judges the script itself, and a page turned a quarter is not detected | none |
| blocks, paragraphs, baseline, row metrics, reading order, style, dictionary flag | none | none |

A reading the engine cannot give is **unread, never blank**: a language the platform has no recogniser or pack for, or a crop that reads
nothing, leaves the page unread (D038).

## Calibration, and why they are explicit

An engine's own confidence is not a probability. emon fits, for each engine, a map from its raw confidence to how often the word is
right, on documents held out from the ones it is judged on, and freezes it (D883 ruling 3). Until an engine has such a map it may vote,
but it never inserts or replaces a reading, its confidences are capped and flagged `uncalibrated`, and it is never the incumbent.

- **Apple Vision** has a frozen word map for the accurate level with language correction, the one configuration the engine name selects.
  The reliability report is `test/calibration/engines/3/`.
- **Windows.Media.Ocr** has no confidence, so no map can exist: its readings stay uncalibrated for good.

Neither engine is chosen for an app that chooses nothing, and neither is ever the incumbent on a page with a seal or a stamp (ruling 8).

## Replay

A reading is recorded like any engine's, with the engine's name, its requested configuration and the **observed identity** (the OS
build and, for Vision, the recogniser revision). A recording replays byte for byte on every host, none of which has the engine: the
engine runs once, on its own platform. A replay must name the same engine (`ocr-engine=apple-vision`), because a request says which engine
it is for; asking for another is `transcript-miss`, never a guess.

## Language

An engine reads the languages of its platform. emon's codes (`eng`, `deu`, `chs`, ...) and scripts (`Latn`, `Hani`, ...) are turned into
Vision's locale tags or Windows' language tags by one table in kin (`emon.ocrloc`); a request that names no language is read in Vision's
Latin-script languages (no language is assumed), or in the user's profile languages on Windows. Vision reads English, French, Italian, German,
Spanish, Portuguese, Chinese (simplified and traditional), Japanese, Korean, Russian, Ukrainian, Thai and Vietnamese; Windows reads the
languages whose OCR pack is installed (`Add-WindowsCapability -Online -Name Language.OCR~~~en-US~0.0.1.0`).
