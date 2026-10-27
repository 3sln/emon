`bb receipts` measures live Tesseract over 60 receipts per set, outside the gates.
Use `--n N`, `--set sroie|cord` (or comma-separated sets), `--report` to display
the last run, and `--bin PATH` for an independently built driver. The task refreshes
the default driver before live measurements. Build manually with
`EMON_RS_LIVE=1 kin/scripts/emon-rs-build`.
`EMON_RECEIPTS_OUT` overrides the external scratch directory.
The default binary is `.cache/emon-rs-live/target/release/emon-rs`; use `--bin`
or `EMON_RECEIPTS_BIN` for a build in another target directory.

Sampling is deterministic by split and gold word-count bands (<80, 80–159,
160+), equally allocated among nonempty strata, evenly spaced within sorted
lengths. SROIE uses test only; CORD uses test and validation. AppleDouble files
are excluded. Baseline rows contain only IDs and numbers; raw bundles and
reports stay under `/Volumes/SSK SSD/emon-scratch/rc/`. `--rescore` reuses those
bundles without calling OCR. `--save PATH` changes the numerical report path. `--baseline FILE` defaults to
`test/receipts/baseline.json`: deltas are emitted only when both sets of document
IDs match. Different populations are explicitly non-comparable; no automatic
baseline or accuracy-ratchet update occurs.

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

Baseline run: RC5c worktree from `aedbde7f` with guarded recorded-reading fusion
active (D731–D735; RC3 `cbc79751`, D704–D709, before it), Tesseract 5.5.3,
Leptonica 1.87.0;
default installed models and engine options. Near-empty counts include empty.
The compact baseline has a shared column list; absent counters are zero.

The automatic build refreshes generated/host sources before each live run.
The scorer's tests run with
`python3 -m unittest discover -s tools/receipts -p 'test_*.py'`.

Live runs record transcripts by default. `--record-transcripts` explicitly keeps
live plan/answer transcripts under
`EMON_RECEIPTS_OUT/<id>.replay`; `--replay-transcripts` runs those same port
answers through a new driver without calling OCR. Missing transcripts fail
explicitly. Keep these recordings outside git: they contain receipt pixels
and text. Replay is suitable for changes after OCR planning; a planner change
can require a fresh live recording.

`input_views` is deterministic evidence metadata: the classifier (`photo`,
`thermal`, or `none`), whether its read was deferred, successful OCR runs,
receipt/rescue runs and the adopted view. The synthetic `fused` run is not an OCR
run; `fused_slots`, `fused_replacements`, `fused_insertions` and `fused_new_lines`
count the page's `ofuse` decision rows (D734), and OCR presence counters ignore
fused copies. Thermal classification is retained
although the extra thermal view was reverted (D706). PDF scan pages retain
classification but defer RC3 photo reads and rescues to preserve baseline
empty recovery (D705). Actual `ocr_reads` count
requests in the adjacent transcript, including failed responses; successful run
counts are reported separately. Rescoring can recover request counts when that
transcript is present. A photo rescue shares the faint bitmap with its main read.

Live/replay `cpu_seconds` measures child driver user plus system CPU, including
kin and the OCR port; it is not isolated Tesseract CPU. CPU is outside scored
rows and totals, and replay CPU excludes live OCR. Compare paired runs using
the same output/recording options; shared-machine timing is not a ratchet.

`--transcript-dir DIR` separates external transcript input/output from numerical
reports and bundles. In replay mode it lets a policy experiment preserve the
original live bundles while writing new results to `EMON_RECEIPTS_OUT`.

`--bounds` adds `completeness_bounds` with numerical per-document rows and
pooled counts for both the selected `words` and all-attempt `owords`. Exact
word completeness uses a multiset occurrence budget, ignores order and geometry,
and is an optimistic ceiling for reconstruction that keeps token boundaries.
The all-attempt pool includes rejected views and repeated reads: it is not an
adoptable reading. Entity and item ceilings are independent, so they may reuse
an occurrence across separate annotations.

`numeric_join_oracle` and `decimal_join_oracle` additionally count whole-token
concatenations within one known page/run/line. They preserve every character
(including locale punctuation and signs), never bridge OCR reads, and count
candidate intervals. They ignore gaps and competition between overlapping
intervals, so they are optimistic ceilings, not safe joins or measured gains.
Unknown line IDs authorize no joins. These counters do not change scores,
baseline rows, OCR planning, or historical accuracy populations.

With `--bounds`, CORD also reports a separate `sub_items` cohort for annotated
`menu.sub` name-and-price groups, keyed by native group and subgroup. The
historical 151-parent-item metric retains its definition and population.
`items_numeric_join_complete` and the corresponding entity counters also allow
those optimistic numeric concatenations when testing completeness, so split
amounts do not incorrectly make a combined grouping-and-joining target appear
unreachable. They retain the oracle's relaxed geometry and overlap assumptions.

The join oracle is conditional on existing OCR line identities. It is not a
ceiling for repairing tokens across separate OCR lines that share a physical
baseline; the exact-token completeness counters remain independent of geometry.
