# The platform's own OCR: Apple Vision and Windows.Media.Ocr

An app can read scans with the recogniser the operating system already has, so there is no model to download and nothing to add to the
app's size. Both are **explicit engines** in every SDK and the CLI: `ocr-engine=` is how an app, or you on the command line, asks for one (DEVLOG D895, D901). Only a turnkey package chooses for an app that
chooses nothing, by kin's turnkey table: Apple Vision on Apple platforms (with Tesseract as its page fallback, below; maintainer decision of 2026-11-23, P13.21b, DEVLOG D907), Windows.Media.Ocr on Windows and ML Kit on Android
(`emon_flutter`; the pure-Dart `emon` keeps Tesseract on Android), Tesseract on Linux. On Apple platforms Vision is built in: nothing is downloaded for it. Tesseract's pack is a conditional, on-demand download, used only for the pages that fall back to it.

| `ocr-engine` | Runs on | What reads | Adapter library |
|---|---|---|---|
| `apple-vision` | macOS 12 or later, iOS 13 or later | `VNRecognizeTextRequest`, accurate level, language correction | `libemon_ocr_vision.dylib` |
| `windows-media-ocr` | Windows 10 or later | `Windows.Media.Ocr.OcrEngine`, the installed language packs | `emon_ocr_winocr.dll` |

`emon convert --ocr-engine apple-vision scan.pdf` on a Mac, `--ocr-engine windows-media-ocr` on Windows. In the C library the adapter
sits in `lib/emon/adapters/` beside Tesseract's, and `emon_engines_defaults` finds it; the app names the engine in the options of the
conversion (`ocr-engine=apple-vision`). An engine that cannot run on the platform is `unavailable` in `engines()`.

## Apple Vision's Tesseract fallback

`ocr-engine=apple-vision` reads every page with Vision, and kin sends a PAGE to Tesseract when Vision is the wrong reader for it (`kin/ofall.kin`, DEVLOG D907). The policy is a pure function
of what Vision answered and of the page's pixels, so a replay makes the same choice on every host. A page goes to Tesseract when
- **Vision gave no reading** (a language it has no locale for, a failure);
- **its reading is too thin**: fewer than 35 letters and digits. A page in a script Vision has no recogniser for (Arabic, Persian, Urdu, Devanagari, Hebrew, Khmer and the rest of the scripts not listed below, on macOS 14.6) comes out
  as a few stray marks, and a blank page costs Tesseract a reading;
- **its reading is a mix of scripts that no page has**: 10 per cent or more of its letters outside the script family most of them are in (Han and kana are one; Greek letters and, on a page that is not Latin, Latin ones count for nothing);
- **its pixels show a seal or a stamp** (D883 ruling 8: Tesseract holds seal and stamp pages: Vision reads a stamp as text and reads a ring badly);
- **it is a table of numbers that is not a receipt** (an invoice, a statement: Vision answers the cells of a row as lines and the rows come out in another order).

A page of Chinese, Japanese, Korean, Russian, Ukrainian or Thai text stays Vision's: Tesseract without a language read a quarter of the characters. The page is then read by Tesseract as it always was, passes and cells
included; if Tesseract cannot read it either (no adapter, a pack the app declined) the page keeps its Vision reading, so a host with Vision alone behaves as before. `prepare()` reports Tesseract's packs
as `conditional` (on Apple platforms by default, as Vision is the turnkey default there; not missing, and not fetched ahead of time: the page that needs one asks for it, D896), except for a language Vision has no locale for, whose model is needed as ever.

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

Neither engine is ever the incumbent on a page with a seal or a stamp (ruling 8): Apple's turnkey sends such a page to Tesseract, above.

## Replay

A reading is recorded like any engine's, with the engine's name, its requested configuration and the **observed identity** (the OS
build and, for Vision, the recogniser revision). A recording replays byte for byte on every host, none of which has the engine: the
engine runs once, on its own platform. A replay must name the same engine (`ocr-engine=apple-vision`), because a request says which engine
it is for; asking for another is `transcript-miss`, never a guess.

## Language

An engine reads the languages of its platform. emon's codes (`eng`, `deu`, `chs`, ...) and scripts (`Latn`, `Hani`, ...) are turned into
Vision's locale tags or Windows' language tags by one table in kin (`emon.ocrloc`); a request that names no language is read by Vision's own detection of the page's language (macOS 13+, iOS 16+) and, when
what it read is Latin, again with every language the OS supports, English first (receipts read best that way); on an older OS with every supported language. The observed identity says which (`locales=`).
Windows reads in the user's profile languages. Vision on macOS 14.6 reads English, French, Italian, German, Spanish, Portuguese, Chinese (simplified, traditional and Cantonese), Japanese, Korean, Russian, Ukrainian,
Thai and Vietnamese, and no Arabic, Persian, Urdu, Hindi or other Indic script, Hebrew, Greek, Armenian, Georgian, Ethiopic or Khmer; Windows reads the languages whose OCR pack is installed
(`Add-WindowsCapability -Online -Name Language.OCR~~~en-US~0.0.1.0`).
