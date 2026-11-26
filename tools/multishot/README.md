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

The seed 920 Tesseract reference covers 181 SROIE receipts, 19 CORD receipts
and six traps. Use it with the corpus snapshot that produced those crops.

```sh
bb multishot --sweep --ocr-engine tesseract --ratchet tools/multishot/tesseract-sweep-920.json
```

The reference records a failing acceptance run. The command compares the
measured scores and also applies the absolute acceptance checks.

`bb multishot --clipped` cuts text at all four image edges and reports
clipping precision and recall. It also reports the flag rate on tight and
full-image controls. Use the same seed and set when comparing engines.

`bb multishot --report` shows the last report under
`${EMON_SCRATCH:-.cache/scratch}/multishot/`. The report holds the seed,
case identifiers, piece hashes and aggregate scores. Generated crops are
removed at the end of the run unless `--keep` is supplied.
