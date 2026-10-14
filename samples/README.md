# emon samples

A set of source documents, each next to the semantic HTML and the JSON Model emon generates for it.
Open `index.html` straight from disk (no server, no external resources): the source is on the left, the
generated HTML on the right, with a link to the JSON.

Files, for each sample `NAME`:

- `NAME.pdf` / `.jpg` / `.png`: the source (committed, so nothing here needs `corpus/`).
- `NAME.html`: emon's HTML, byte for byte as the Rust driver wrote it (no styling).
- `NAME.json`: the Model (canonical, compact JSON).
- `figures-charts.assets.html`: the same document with `assets=figures`, the figures inlined as PNG.

`index.html` shows each generated page through `srcdoc` with a few lines of CSS spliced in, so it is
readable; that styling exists only inside the viewer. The `.html` files are untouched.

## How they were made

- emon commit `d53f21e` (main, "DEVLOG: park niche edge cases until after the first release").
- Rust driver `emon-rs` built live: `EMON_RS_PDFIUM=<.cache/pdfium/current/lib> kin/scripts/emon-rs-build`
  (rustc 1.92.0), run through `tools/accuracy/predict-emon-fusion-live.sh`.
- pdfium `chromium/8076` (pinned in `tools/pdfium/pdfium.edn`, `bb pdfium-fetch`), mac-arm64.
- Tesseract 5.5.3, leptonica 1.87.0, the `eng` model only (plus `osd`) from Homebrew.
- Default options: `ocr=always`, `ocr-scope=full`, no `--locale`, `assets=none` (except the one variant).
- Per sample, equivalent to:

```
emon-rs --pdfium --tesseract --out=json NAME.pdf > NAME.json
emon-rs --pdfium --tesseract --out=html NAME.pdf > NAME.html
emon-rs --pdfium --tesseract --assets=figures --out=html figures-charts.pdf > figures-charts.assets.html
```

- Regenerate everything (also rebuilds `index.html`): `bb samples` (or `bb samples NAME ...`).
  It is not a gate: it needs pdfium, Tesseract and cargo, and takes about 15 minutes (OCR is ~10 s a page).
- Trimmed sources: `qpdf SRC --pages . 1-8 -- gao-report.pdf`, `... 1-10 -- irs-pub15-excerpt.pdf`,
  `... 1-2 -- irs-form-w9.pdf`. The rest are whole files.

## Where the sources come from and why they may be here

- Synthetic corpus (`test/corpus/synth`, ours): columns-two, footnotes, dropcap, align-justify, figures-charts,
  invoice, bank-statement, table-multipage, table-zebra, table-spans, table-unruled, watermark-draft, seal-stamp,
  signature, tagged-table, broken-tounicode, scan-plain, scan-color-invoice, scan-stamp-overlay, arabic-rtl, cjk.
- Public domain US Government works (`test/corpus/DATASETS.md`): `uscourts-table` (US Courts), `irs-form-w9`, `irs-form-1040` and
  `irs-pub15-excerpt` (IRS), `gao-report` (GAO; GAO notes that third-party images in some products may be copyrighted,
  the excerpt's pages are text and the agency logo/photo).
- `image-invoice.jpg`: katanaml-invoices (synthetic; MIT on the card, upstream CC BY 4.0: attribution).
- `image-statement.png`: bank-statement-benchmark preview (synthetic, MIT, (c) 2026 Bankstatemently).
- Left out for licensing: the Berkshire Hathaway 10-K (copyright, no open licence; it is the only large annual report
  in the corpus, and no SEC filing is there), arXiv papers (per-paper licences), FUNSD/XFUND (non-commercial), SROIE
  (unverified terms), libtiff sample images (unverified; so no TIFF sample), the Wikipedia-derived multiscript PDFs
  (CC BY-SA), RVL-CDIP and the stamp dataset (research-only / unverified). CORD would be allowed (CC BY 4.0) but was not needed.

## What to look at

| Sample | Look at |
|---|---|
| gao-report | headings as nested sections, running header/footer as `aside` furniture, the logo/photo as figures, tables |
| irs-pub15-excerpt | the large real-world document: lists with `data-emon-marker`, multi-column reading order, boxes, tables (68 KB HTML for 10 pages) |
| columns-two | reading order down each column, then across pages |
| footnotes | `doc-noteref` links to `doc-footnote` asides, notes out of the body |
| dropcap | `<span data-emon="initial" data-emon-form="drop">` and the word kept whole |
| align-justify | `data-emon-align`, hyphenated line ends rejoined |
| figures-charts | `figure data-emon-kind=chart/diagram/photo` with captions; `.assets.html` has the image data |
| invoice | header block, one table with a thead, right-aligned amounts, footer furniture |
| bank-statement | one `table` with one `thead` over 3 pages; header and footer lines as furniture on pages 1 2 3 |
| irs-form-w9 | a real form: numbered lines, bold line numbers, signature; its 8 AcroForm check boxes and 15 text fields as `<span data-emon="field">` beside their labels (`role="checkbox"`, `data-emon-checked`, `data-emon-value`) |
| irs-form-1040 | the real 2025 Form 1040 (2 pages); it crashed on an `i32` overflow until D521; its 199 fields (73 buttons, 126 text) beside their line numbers and labels, the ones with no label in a paragraph of their own |
| table-multipage | the repeated header row folded into one thead |
| table-zebra / table-unruled | tables found from shading only or from alignment only |
| table-spans | `rowspan`/`colspan` in the header |
| uscourts-table | a wide real statistical table over 4 pages |
| watermark-draft | the text DRAFT is in no body (`grep DRAFT watermark-draft.html` is empty); it is in the JSON ledger |
| seal-stamp | `figure data-emon-kind=seal/stamp` with name, date, colour; their words are not in the body |
| signature | `data-emon-kind=signature`, the typed name still read |
| tagged-table | table from the structure tree, `caption` with its label |
| broken-tounicode | the font's text layer is wrong; the HTML text is nevertheless right (OCR fusion) |
| scan-plain | a page with no text layer, all OCR; headings and paragraphs still found, a few character errors |
| scan-color-invoice | colour scan: table recovered, some cells merged |
| scan-stamp-overlay | the seal and stamp found as nodes over a scan; the body text mostly survives |
| image-invoice / image-statement | raster inputs through the same pipeline |
| arabic-rtl / cjk | RTL text and CJK text in logical order |

## Known weak spots (honest list)

- **Scans: ruled tables are weaker.** In `scan-color-invoice` the invoice table loses some column boundaries (quantity and
  unit price share a cell, a `~` noise glyph in an amount) and the first rows of the header block merge into one paragraph.
- **Scan text has character errors** (`friend` read `frlend`, `group` read `gtoup`, `Course` read `Cours¢`): Tesseract's
  reading, with no language model beyond `eng`.
- **Faint or low-contrast stamps are missed** and a stamp's words can leak into the body; in `scan-stamp-overlay` the
  seal's name is read as `OF NOTARY PUBLIC` (the vector original says `CITY OF RIVERTON`), and the signature line becomes the
  paragraph `——__..`. Seals in raster form are judged from pixels and are less exact than vector ones.
- **Low-resolution images give little.** `image-statement.png` (a thumbnail, about 300 px wide) comes out as two garbled
  words; the faint BANK DOCUMENT watermark there is not separated from the page.
- **The JPEG invoice (`image-invoice.jpg`, 2481x3508)**: the text is read well but the heading levels are over-nested
  (`h5`, `h6`, even `aria-level=9` for ITEMS/SUMMARY) because the type sizes are all close.
- **P7.G scan rules.** The scan-specific rules (ruling lines on scans, table recovery from pixels, the P7.G rule set in
  DEVLOG) are the least mature part of the pipeline; the scan samples are where they show.
- **Only the `eng` Tesseract model was installed.** The Arabic and CJK samples are born-digital, so their text comes from
  the text layer; a scanned Arabic or CJK page would not read here. `ocr=always` also runs OCR on those pages, and
  the CJK sample shows its artefact: ideographic text with a stray space (`之 一`, `重 点`) that the fusion kept.
- **JBIG2 and JPX images are `unsupported`** in a plain run (their decoders are the optional `--codecs` live build, which
  these samples do not use); a scan stored as JBIG2 or JPX would have no pixels to read. None of the samples uses them.
- **Forms**: AcroForm fields are `field` inlines of the block that holds their label (D1510); a form drawn without an AcroForm (boxes
  drawn as vectors or scanned) has none yet, and on the 1040 the 44 fields with no label near them (mostly check boxes of the dependents
  table and of page 2) are in paragraphs of their own.
- **Figures**: chart labels are kept as `span data-emon="label"` runs, not as data; the chart itself is a picture.
- The Model JSON for the bigger documents is large (IRS Pub 15, 190 KB); the HTML is the readable output.
