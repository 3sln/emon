# emon, the command-line tool

`emon` converts a PDF or an image of a document into semantic HTML or the JSON Model. One archive per
platform holds everything it needs: the binary, pdfium (renders pages), Tesseract and Leptonica (read
scans; linked into the binary) and the English and orientation models. Nothing else has to be installed.

Platforms: macOS arm64 and x64, Linux x64 and arm64 (glibc 2.39 or newer), Windows x64.

## Install

Download `emon-<version>-<platform>.tar.gz` (`.zip` on Windows) and its `.sha256` from the GitHub release,
check it, and unpack it anywhere:

    shasum -a 256 -c emon-0.1.0-aarch64-apple-darwin.tar.gz.sha256
    tar -xzf emon-0.1.0-aarch64-apple-darwin.tar.gz
    emon-0.1.0-aarch64-apple-darwin/bin/emon version

The directory is the install: keep `bin/`, `lib/` and `share/` together (the binary finds the others
relative to itself, through symlinks too). On macOS a downloaded binary is quarantined until you run
`xattr -dr com.apple.quarantine emon-0.1.0-aarch64-apple-darwin` (the release is not notarised).

    bin/emon                         (bin/emon.exe and bin/pdfium.dll on Windows)
    lib/libpdfium.dylib | .so        pdfium, the pinned build
    share/emon/tessdata/             eng.traineddata, osd.traineddata
    LICENSE  THIRD_PARTY_NOTICES.md  README.md (this file)

## Commands

    emon convert INPUT [-o FILE] [--format html|json|model] [--assets omit|inline|dir]
                       [--ocr auto|never|always] [--lang eng+deu] [--tessdata DIR] [--pdfium PATH]
                       [--password TEXT] [key=value ...]
    emon version
    emon licenses
    emon help

`INPUT` is a PDF or an image file (`-` reads standard input). The result goes to `FILE`, else to
standard output; errors and notes go to standard error.

| Flag | Meaning |
|---|---|
| `--format` | `html` (the default); `json`, the Model as canonical compact JSON; `model`, the same Model indented. |
| `--assets` | `omit`: figures left out. `inline`: figures, seals and signatures as PNGs inside the HTML. `dir`: as files in `FILE.assets/` beside `-o FILE`, the HTML naming them. Not given: the engine's own default. |
| `--ocr` | `always`, `auto` or `never`: when pages are read with Tesseract. Not given: the engine's default. With `never` neither the models nor pdfium are required. |
| `--lang` | The Tesseract models to read with, joined by `+` (`eng+deu`). Not given: every model in the models directory. |
| `--tessdata` | The models directory, in place of the bundled one. |
| `--pdfium` | The pdfium library file, or the directory holding it. |
| `--password` | The document's password. |
| `key=value` | Any option of the engine's grammar, parsed by the engine itself: `ocr-scope=witness`, `asset-dpi=300`, `limit.pages=20`, `locale=de`, `assets=figures`, `parallel=4`. An unknown key is refused. The same option given as a flag and as `key=value` is refused. |

`emon version` prints the emon version, the pinned pdfium build, the Tesseract and Leptonica versions
and where it found the pdfium library and the models. `emon licenses` prints `THIRD_PARTY_NOTICES.md`:
the licence of every component in the archive.

Exit status: 0 done; 2 usage; 3 a bundled file is missing (pdfium, the models, a `--lang` model); 4 a
named emon error (`error: NAME` on standard error); 1 anything else.

## Where it looks

| What | In order |
|---|---|
| pdfium | `--pdfium`; `$EMON_PDFIUM_LIB`; `../lib/` beside the executable; the executable's directory |
| models | `--tessdata`; `$EMON_TESSDATA`; `../share/emon/tessdata/` beside the executable |

A place you name (a flag or a variable) that is wrong is an error; it is never replaced by the next
one. `TESSDATA_PREFIX` is not read. pdfium is loaded when the command starts (never linked), so a
missing library is a sentence and exit 3, not a loader failure.

## More languages

Tesseract models are `<code>.traineddata` files (ISO 639-3: `deu`, `fra`, `chi_sim`...), from
[tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) (small), or `tessdata_best`. Either put
them in `share/emon/tessdata/` beside the bundled two, or make a directory with the models you want
**and `osd.traineddata`** and point at it:

    emon convert letter.pdf --tessdata ~/tessdata --lang eng+deu

Without `--lang` every model in the directory is loaded, so a large directory is slower; name the
languages a document needs. An absent model is exit 3 naming the file.

## Notes

- Never in the archive: JBIG2 (jbig2dec) and JPEG 2000 (OpenJPEG). A page whose image is JBIG2 or
  JPEG 2000 is converted without that image's pixels.
- pdfium pages are drawn with the platform's fonts for PDF fonts that are not embedded, so rendered
  figures of such documents differ a little between platforms (OCR text is read from those pixels).
- Build from source: `bb native` (the static libraries), `bb pdfium-fetch`, then `bb cli`; `bb cli --check`
  unpacks the archive and runs it (DEVLOG D541).
