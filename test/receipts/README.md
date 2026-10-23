`bb receipts` measures live Tesseract over 60 receipts per set, outside the gates.
Use `--n N`, `--set sroie|cord` (or comma-separated sets), `--report` to display
the last run, and `--bin PATH` for an independently built driver. The task refreshes
the default driver before live measurements. Build manually with
`EMON_RS_LIVE=1 CARGO_TARGET_DIR=/private/tmp/emon-rc/live kin/scripts/emon-rs-build`.
`EMON_RECEIPTS_OUT` overrides the external scratch directory.
The default binary is `/private/tmp/emon-rc/live/release/emon-rs`.

Sampling is deterministic by split and gold word-count bands (<80, 80–159,
160+), equally allocated among nonempty strata, evenly spaced within sorted
lengths. SROIE uses test only; CORD uses test and validation. AppleDouble files
are excluded. Baseline rows contain only IDs and numbers; raw bundles and
reports stay under `/Volumes/SSK SSD/emon-scratch/rc/`. `--rescore` reuses those
bundles without calling OCR. `--save PATH` changes the numerical report path.

Tokens use NFC, case folding and whitespace splitting; punctuation, minus,
currency and decimal separators are retained. Word metrics use multiset
intersection, not unique sets. CORD gold covers selected regions only, so its
precision is *annotated-token precision*, not a false-positive OCR estimate.
Numeric fidelity is exact numeric-token recall (including dates and IDs).
Entities require a contiguous token sequence in one text node or table row.
CORD item integrity requires the native menu group (name and price, quantity/
unit price when annotated), sorted by gold row position and word x coordinate,
in one node or row. `line_item_integrity` additionally requires a shared
evidence line or Model table row. SROIE does not annotate item associations: no item score is
claimed. A paragraph can contain several visual lines: this metric establishes
node integrity, not physical-line integrity.

Silent words count missing gold token occurrences not represented in body, furniture or
dropped text; this is lexical loss, not proof of geometric region deletion.
Raw `owords` distinguish OCR misses from words seen by OCR but absent from the
Model/ledger. Raw OCR has multiple attempts, so this is a diagnostic upper
bound on downstream loss. Skewed lines (>2 degrees), explicit `re-rotated` OCR runs
and rotated Model pages are diagnostics, not verified orientation failures.
Inspection of the external bundles is required to assign a failure stage.

ExpressExpense SRD is image-only and intentionally cannot be text-scored.
Its page-only gold must never be treated as empty-transcription truth.

Baseline run: repository `cf7a607d`, Tesseract 5.5.3, Leptonica 1.87.0;
default installed models and engine options. Near-empty counts include empty.
The compact baseline has a shared column list; absent counters are zero.

The automatic build refreshes generated/host sources before each live run.
The scorer's tests run with
`python3 -m unittest discover -s tools/receipts -p 'test_*.py'`.

`--record-transcripts` keeps live plan/answer transcripts under
`EMON_RECEIPTS_OUT/<id>.replay`; `--replay-transcripts` runs those same port
answers through a new driver without calling OCR. Missing transcripts fail
explicitly. Keep these recordings outside git: they contain receipt pixels
and text. Replay is suitable for changes after OCR planning; a planner change
can require a fresh live recording.
