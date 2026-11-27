# Multi-photo receipt qualification

The benchmark reads SROIE and CORD gold and source images from `corpus/`.
Set `EMON_MULTISHOT_CORPUS` to use a different corpus root. It generates crops
with pinned Pillow and NumPy versions from
`tools/oracle/multishot/requirements.txt`. Set `EMON_MULTISHOT_PYTHON` to
the interpreter in the environment that has those packages. The local runs
used Python 3.12.

`bb multishot --ci --baseline` measures separate pieces concatenated in upload
order and their full-image controls. `bb multishot --ci` also runs a single
document conversion over the original encoded crops and applies the
acceptance checks. Both commands default to seed 917 and Tesseract, with
12 SROIE receipts, 4 CORD receipts and six explicit trap cases. The clean-cut
and distinct-page controls compare canonical Models from the same input under
automatic and scan capture. Pass `--tiff` to reproduce the round-one input
transport with a multi-frame TIFF. The distinct-page control participates in
seam checks. Word accuracy excludes it because only its first input has gold
from the receipt being scored. The report also includes the historical
word aggregate for comparison with earlier runs.
Crops are always uploaded in physical order: the upload order is
authoritative (D918, D924) and the harness does not generate shuffled uploads.
All seeds require zero unflagged cross-photo duplicates. A flagged
`possible-duplicate` is allowed only on a seam with one shared row.

`bb multishot --sweep` draws a fresh seed and selects 200 receipts.
An explicit `--seed` reproduces the crop design. Pass `--ocr-engine` to select
Tesseract, Apple Vision or PP-OCR, and `--bin` to use an existing Rust
reference driver. The all-engine selection includes Tesseract, Apple Vision
on macOS and PP-OCR when `EMON_PPOCR_LIB` is set.
Pass `--output FILE` to retain a report and `--sweep --ratchet FILE` to
compare a later run with that report. The ratchet reuses its seed by default
and requires the same piece hashes and input transport. Each sweep writes
completed case scores and any driver errors to `FILE.cases.jsonl` as it
runs. Driver errors fail the sweep after the remaining cases have run.

The seam metric version is recorded in each report. Ratchet comparisons
require the same version. The seed 920 Tesseract reference covers 181 SROIE
receipts, 19 CORD receipts and six traps. It was re-based in D924
(the shuffled uploads were removed from scope, so the crops changed) with the
current policy and the `model-v3` scorer, measured on the spool runner
(`bb spool-multishot`, x86-64; a reference is measured on one platform). To measure that corpus
against the reference locally, run

```sh
bb multishot --sweep --ocr-engine tesseract \
  --ratchet tools/multishot/tesseract-sweep-920.json --output sweep-920.json
```

Reports include the following counts.

- `dup` counts extra cross-photo occurrences of each gold row in emitted Model text.
  The matcher walks text in document order, including table cells, and uses
  Model source regions projected into each crop to distinguish genuine
  repeated rows. It allocates each token span once. A candidate needs at
  least 75% of the gold tokens and a word-overlap F1 of at least 0.80.
  Rows whose complete word multiset also occurs in another gold row are
  excluded from duplicate scoring. `same-photo-dup` records extra assigned
  occurrences within one photo for diagnosis.
  `unflagged` counts extra copies whose source pair lacks a
  `possible-duplicate` mark. `matched-dup` counts extra copies on pairs with
  a confirmed overlap.
- `false-merges` counts distinct gold rows that a separate-photo Model
  emitted, the joined Model omitted, and a seam drop removed. The scorer
  projects the drop onto the gold rows to attribute it, then looks for a
  surviving occurrence in the joined Model across all photos. One row may
  have several dropped OCR segments. A row recovered from any photo is
  present even when neither crop shows its entire gold box. Row
  presence requires 70% gold-token coverage and a word-overlap F1 of at
  least 0.72. An exact reading of a unique gold row can also survive across
  cells of the same Model table row, with intervening text from another gold
  row. Per-case `false-merge-rows` lists the zero-based gold row indices.
  Reports identify this check with `false-merge-metric`. The ratcheted
  metrics retain their existing version.
- `missing-output` and `missing-concat` count gold rows without an emitted
  match. `lost-rows` counts absent seam rows whose projected gold box
  crosses a joining crop edge or falls between the crops, with no complete
  copy across the pair. `lost` and `lost-flagged` count seams with such
  loss and how many carry a gap flag. The geometry test uses the crop edge
  directly, including cuts above the 97% evidence visibility cutoff.
- `spurious-gap` counts gap flags on seams with no lost output row.
  In the partial and gap buckets it requires a clipped row with a complete
  copy in the neighboring crop.
- `evidence_dup` retains the historical count based on surviving OCR lines
  from both photos and requires at least 97% projected visibility in each.
  `evidence_missing` and `evidence_missing_concat` count rows absent from
  the corresponding surviving OCR lines.

`bb multishot --clipped` cuts text at all four image edges and reports
clipping precision and recall. It also reports the flag rate on tight and
full-image controls. Use the same seed and set when comparing engines.

`bb multishot --report` shows the last report under
`${EMON_SCRATCH:-.cache/scratch}/multishot/`. The report holds the seed,
case identifiers, piece hashes and aggregate scores. Generated crops are
removed at the end of the run unless `--keep` is supplied.
