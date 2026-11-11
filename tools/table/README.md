Table ownership guards
======================

Run the adversarial guard tests with:

    bb tools/table/test_ab_checks.clj

Check frozen ownership against a directory containing DOC.json Models:

    bb tools/table/ab_checks.clj ownership PROTECTED_TRIPLES.json REPAIRS.json MODEL_DIRECTORY

The checker compares multisets of page, numeric row key, column and exact cell
text. Table IDs and row ordinals may change when fragments link. Duplicate
occurrences cannot conceal missing ownership. Only the supplied repair registry
can excuse a change.

Compare represented words for the items in ITEMS.json, with Models in two directories:

    bb tools/table/ab_checks.clj words ITEMS.json BEFORE_DIRECTORY AFTER_DIRECTORY

Body and furniture words include their serialized labels. Existing repeated
header ledger entries are counted on both sides, preserving the baseline's
established serialization. Every newly added `duplicate-header-row` occurrence
must exactly equal a header row from the first fragment of the same continued
table, on an earlier page. Empty cells contribute no separator to header text,
matching the emitter. Other ledger reasons do not account for lost words.

The JSON results report ownership changes or word losses, gains, repeated-header
words and newly accounted repeated-header words. The A/B comparison exits
nonzero on an unexcused ownership or word change. Private Models and frozen triples stay outside
the repository.

Table-engine comparison (`bb tables-ab`, TR-design)
===================================================

`bb tables-ab` runs two table engines of one build (`--tables=legacy` and
`--tables=scored`) over frozen populations and scores both against gold:

    bb tables-ab record                  record the live port answers once (SCRATCH/rec/)
    bb tables-ab run --label L [--args A]  convert with one engine (replay, direct or live)
    bb tables-ab score --label L         score a run (SCRATCH/runs/L/scores.json)
    bb tables-ab baseline --label L [--write]   write or check test/tables-ab/baseline.json
    bb tables-ab compare --a LA --b LB   apply test/tables-ab/criteria.edn
    bb tables-ab ab                      all of it, A and B, with the verdict

- `ab.clj` is the runner and `abscore.clj` the scorer. `ab_checks.clj` holds
  structural ownership and the word control.
- `ab_freeze.clj` froze the populations (`test/tables-ab/populations.json`); run
  it with `--check` to show the freeze is unchanged.
- Tests:
  - `bb tools/table/test_ab_checks.clj`
  - `bb tools/table/ab_freeze.clj --check`
  - `bb -cp tools:tools/lib tools/table/test_abscore.clj`

SCRATCH (`$EMON_SCRATCH/trd`, default `.cache/scratch/trd`, or `EMON_TABLES_AB_SCRATCH`) holds
Models, transcripts, logs and the frozen inputs. The repository holds only
IDs, digests, expectations and scores.

The engine binary defaults to the live driver, `.cache/emon-rs-fusion/target/release/emon-rs`.
Build it with `EMON_RS_PDFIUM="$(cd .cache/pdfium/current/lib && pwd -P)" kin/scripts/emon-rs-build`,
or pass another driver with `--bin`.

`ab` always reconverts both engines to avoid reusing a previous stage's Models.
Use `--a LABEL --b LABEL` to preserve stage results, and
`--a-args '--tables=legacy' --b-args '--tables=legacy'` for the identity check.
A legacy baseline mismatch stops the comparison and exits nonzero.

When the sandbox denies macOS `time -l` access to `kern.clockrate`, the A/B
runner builds `tools/table/time.c` into its own `.cache/table-time`. This
fallback measures the child's CPU and peak RSS with `wait4`, and preserves
its exit status. Both arms use the same measurement implementation.

A `column-merge` repair names one document, source fragment, page and column
pair. Its evidence must include the overlapping old cell extents and a source
render confirming one physical column. Unlike the F01 row-rebuild repair,
it excuses only that merge. `row_ownership` binds the exception to the frozen
ownership list and the complete normalized token multiset of every original
row. Values must keep their page, numeric key and row-mates; shared output
cells must contain separate occurrences of all protected values. Missing or
inconsistent row fingerprints fail closed. No other merge is excused.
