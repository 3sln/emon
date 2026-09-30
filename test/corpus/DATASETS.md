# Public datasets: licences, what is committed, importers (P2.11)

Decisions: D077 (licence policy), D078 (importer conventions), D079 (seal/stamp set).
The downloaded corpus (`corpus/`, 12.5 GB) is **never committed** and is git-ignored in this repo.
`corpus/MANIFEST.md` / `manifest.json` describe each source; `corpus/fetch.sh` reproduces it.

## 1. Licence review

Verified 2026-09-30 from the primary source (dataset card, repo LICENSE or README, official site) by web
fetch. "Unverified" means the primary source could not be read or states nothing; such a source is treated
as **research-only**. Two corrections to the first manifest: **XFUND is CC BY-NC-SA 4.0, not MIT**; the
**FinTabNet_OTSL card says `license: other`** with no text (CDLA-Permissive-1.0 is believed for IBM's
original, not verified).

Two columns that must not be confused:

* **Committable by licence**: what the licence would allow us to put in the repository, *if* we chose to.
* **Committed**: what we actually commit. **Nothing from any public dataset**, because Git LFS is not
  available (maintainer, 2026-09-30). Only importers, the manifests and fetch scripts are committed.

Rule applied: research-only, non-commercial, share-alike (GPL, CC BY-SA, CC BY-NC-SA), no licence, or
unverified means *fetch-only* in the "committable" column too. Underlying documents keep their own
copyright even where the annotation licence is permissive.

| Source | Verified licence (primary source) | Catches | Committable by licence | Committed |
|---|---|---|---|---|
| katanaml-invoices | MIT on the HF card; upstream Mendeley data CC BY 4.0, synthetic | card MIT vs upstream CC BY: attribute | everything (attribution) | nothing |
| sroie2019 | CC-BY-4.0 on the third-party HF re-upload only; ICDAR RRC terms could not be read | real receipts; source terms unverified | fetch-only | nothing |
| cord-v2 | CC-BY-4.0 (HF card, clovaai/cord README) | real receipts | everything (attribution) | nothing |
| funsd | non-commercial, research and educational use only (official site, "License and Terms of Use"); images from RVL-CDIP | NC | fetch-only | nothing |
| xfund | **CC BY-NC-SA 4.0** (repo README; no LICENSE file) | NC + share-alike; manifest said MIT | fetch-only | nothing |
| doclaynet-v1.2 | CDLA-Permissive-1.0 (HF card) | covers annotations and IBM's compilation, not the source pages (financial reports, patents, laws, manuals, tenders, papers) | derived gold only | nothing |
| fintabnet-otsl | HF card `other`, no text; IBM original believed CDLA-Permissive-1.0 (unverified) | SEC filings underneath | fetch-only (unverified) | nothing |
| icdar2019-ctdar | none (repo README only, no LICENSE) | all rights reserved by default | fetch-only | nothing |
| icdar2013-table-competition | annotations via tabula-java (MIT, verified); PDFs are EU/US government documents (not re-verified; EU reuse terms may apply) | | derived gold only | nothing |
| gov-reports-tables | US Government works, public domain (17 USC 105); not re-fetched | check third-party content per agency page | everything (small sample) | nothing |
| agami-indian-bank-statements | Apache-2.0 (HF card), fully synthetic | 1.4 GB | everything (a small sample) | nothing |
| bank-statement-benchmark | MIT (repo licence API), synthetic | | everything | nothing |
| irs-forms | US Government works, public domain; not re-fetched | | everything | nothing |
| berkshire-annual-report | copyright Berkshire Hathaway, no open licence | | nothing | nothing |
| gov-reports-layout | GAO/Fed: public domain; Berkshire letter: copyright | | the GAO/Fed files only | nothing |
| arxiv-papers | per-paper arXiv licence (mostly the non-exclusive arXiv licence) | | nothing | nothing |
| cuad-contracts | CC BY 4.0 (Zenodo 4595826) for the Atticus labels; contracts are SEC EDGAR exhibits, filers keep copyright | | labels and derived gold only | nothing |
| pdfa-eng-wds | `other`: Common Crawl terms of use and Digital Corpora terms of use (HF card) | arbitrary web PDFs, per-document copyright | nothing | nothing |
| udhr-and-wiki-multiscript | UDHR: OHCHR terms not retrievable; Wikipedia text CC BY-SA 4.0 (well known, not re-fetched) | share-alike | nothing (Wikipedia PDFs would need CC BY-SA) | nothing |
| rvl-cdip-small | no licence of its own; subset of IIT-CDIP (tobacco documents, UCSF); HF `other` | research use | nothing | nothing |
| chartqa-test-400 | GPL-3.0 (HF card and repo LICENSE) | copyleft on data; charts crawled from Statista, Pew, OWID, OECD | nothing | nothing |
| libtiff-pics | libtiff licence (BSD-like) covers the code; the page says nothing of the sample images | | nothing (images unverified) | nothing |
| derived-formats-degradations | inherits its sources | most restrictive source wins | nothing | nothing |
| mapo80-stamps (new, D079) | CC-BY-4.0 (HF card) | aggregate of Roboflow/Kaggle community sets with unverified upstream licences; `neg_*` images come from RVL-CDIP and FUNSD and inherit research-only terms | fetch-only (positives unverified) | nothing |

Synthetic corpus (`test/corpus/synth`) is unaffected: it is ours and is committed (D041).

**Importer output** (`corpus/_gold/<dataset>/`) is text and geometry derived from the dataset, so it inherits
its licence: it is not committed either, even for the permissive sources.

**Options to share the corpus without LFS** (nothing is set up): (a) keep `corpus/fetch.sh` as the
mechanism and add a pinned HF revision and a sha256 per downloaded file, so a fetch is verifiable and
reproducible; (b) a release asset or separate data repository for the permissive, small sources (DocLayNet
gold, the public-domain PDFs); (c) an object store bucket (R2/S3) holding the fetched archives, with the
same checksum list. (a) costs nothing and is the natural first step.

## 2. Importers

`python3 tools/import/import_all.py [--limit N] [name ...]` runs them all (not part of `bb gates`: the corpus is not in the
repository). Sources: `tools/import/<dataset>.py` (Python standard library; `jsonschema` is found as
`kin/scripts/json-check.py` finds it). Output: `corpus/_gold/<dataset>/<id>.gold.json`, each validated
against `schema/emon-gold-1.json` plus document-order ids, page numbering, and regions on existing pages.
`tools/import/spotcheck.py DATASET [ID...]` draws a gold's regions on its page (Pillow) for a human check.

Last full run (corpus as fetched; "skipped" items are named in the importer's summary):

| Importer | Gold files | Invalid | Skipped | Claims (`annotated`) |
|---|---|---|---|---|
| funsd | 199 | 0 | 0 | field (name, value, at), heading (text, at) |
| xfund | 1393 | 0 | 0 | as FUNSD, locale from the file |
| sroie | 661 | 0 | 0 | text (line transcription, top-to-bottom), paragraph at; no structure |
| cord | 200 | 0 | 0 | row regions (paragraph at) only |
| doclaynet | 1760 | 0 | 0 | heading, paragraph, item, caption, note, formula, figure, table presence and `at`; no levels |
| icdar2013 | 66 | 0 | 5 | table, row, cell; cell rowspan, colspan, empty, text, at; table at, columns |
| ctdar | 1931 | 0 | 0 | table at (Track A); with cells: row, cell, rowspan, colspan, columns, cell at (archival only) |
| fintabnet_otsl | 5199 | 0 | 0 | table, row, cell; rowspan, colspan, empty, text, columns |
| pdfa_eng_wds | 1184 | 0 | 16 | nothing but the page table: the lines are silver (PDF text layer), unclaimed |
| stamps | 1571 | 0 | 0 | seal presence and absence (subkind stamp), at |

icdar2013: 71 annotation files, 4 without a PDF or `-reg.xml`, 1 PDF with a rotated page. pdfa-eng-wds:
16 documents have a rotated page. Both skips are counted, not hidden.

### Coordinate transforms (checked against the PDFs/images)

All `at` regions are `[page, x0, y0, x1, y1]`, top-left origin, y down (SPEC 3.4).

| Dataset | Native | Transform | Verified by |
|---|---|---|---|
| FUNSD, XFUND, SROIE, CORD, cTDaR, FinTabNet-OTSL, stamps | pixels, top-left origin | none (YOLO stamps: fractions times image size); page is the image at a nominal 1 px = 1 pt, `px` recorded, `dpi` not claimed | regions drawn on the image (`spotcheck.py`); annotated image size equals the file's header (XFUND, SROIE, CORD checked per item); no EXIF orientation anywhere (checked over every JPEG) |
| DocLayNet | `[x,y,w,h]` in a 1025 x 1025 canvas onto which the page is **stretched** (not letterboxed) | `x_pt = x * original_width / coco_width`, `y_pt = y * original_height / coco_height`, then add w, h | words of `pdf_cells` against `pdftotext -bbox` of the paired PDF: 480.80 px scaled = 279.23 pt, pdftotext 279.227; overlay on the 72-dpi render |
| ICDAR 2013 | PDF points, **bottom-left origin**, y up | `(x1, H - y2, x2, H - y1)` with H the page's height from `pdfinfo` | `RELEASES` at yMin 300.86..310.55 top-left equals `H - 541..H - 531`; overlays on eu-001 and us-001 |
| pdfa-eng-wds | `[x,y,w,h]` as fractions of the page, top-left | multiply by the page's points (`pdfinfo`) | "This" at 52.21, 62.92 pt from `pdftotext -bbox` equals 0.087711 x 595.276, 0.074735 x 841.89 |

### Conventions the importers had to choose (D078)

* Raster pages: nominal 1 px = 1 pt; a scorer rescales by `emon page w / gold px[0]`.
* `annotated.attributes[kind]` may list `text` and `at`; the global `annotated.text` is set only for SROIE.
* Body order is a geometric sort unless the source has an order; it is never claimed.
* DocLayNet `Page-header` and `Page-footer` are body `furniture` blocks with geometry (gold's
  `model.furniture` has no `at`); consecutive `List-item`s are wrapped in one `list` (ours, unclaimed).
* FUNSD/XFUND: a `question` becomes a `field` (name, value from linked answers); `header` becomes a `heading`
  without level; everything else is an unclaimed `paragraph`.
* Key-information labels (SROIE entities, CORD `gt_parse`) are not document structure and are not imported.
* ICDAR 2013 `row-increment`/`col-increment` are added to the region's cell indices (5 documents, flagged).
* cTDaR modern cells are content boxes, not cell areas: their `at` is not claimed.

### Known limits

* FUNSD and XFUND labels and links contain annotation errors. SROIE's transcription keeps OCR-style typos.
* cTDaR has no text, FinTabNet `at` is the text extent and is not claimed, DocLayNet tables have no cells.
* ICDAR 2013 tables are the only born-digital *PDF* table truth here; DocLayNet is the only PDF layout truth.

## 3. Seal and stamp dataset

Tobacco800, StaVer and SPODS need logins. **mapo80/stamps** ("Clean Core Stamp Detection Dataset", Hugging
Face, CC-BY-4.0, no login) was found and fetched: `corpus/fetch.sh stamps` downloads the val and test
zips (185 MB; train is 783 MB and not fetched), 1571 images with YOLO boxes (single class `stamp`),
imported by `tools/import/stamps.py`. Catches: the positives are an aggregate of community sets whose
upstream licences are unverified; 250 negatives (`neg_*`) come from RVL-CDIP and FUNSD and inherit their
research-only terms; it labels stamps only, so signatures and logos on an image are absent from the gold.
Signatures remain a gap: `tech4humans/signature-detection` (Apache-2.0, 2819 images, on HF) is a
candidate, not fetched. Stamps are also synthesized in `tools/synth` (seal overlays), which stays the only
source with exact seal text, name and date.
