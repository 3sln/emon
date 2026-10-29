Table ownership guards
======================

Run the adversarial guard tests with:

    python3 -m unittest discover -s tools/table

Check frozen ownership against a directory containing DOC.json Models:

    python3 tools/table/protected.py PROTECTED_TRIPLES.json MODEL_DIRECTORY

The checker compares multisets of page, numeric row key, column and exact cell
text. Table IDs and row ordinals may change when fragments link. Duplicate
occurrences cannot conceal missing ownership. No repair exceptions are enabled
by default.

Compare two Models' represented words with:

    python3 tools/table/words.py BEFORE.json AFTER.json

Body and furniture words include their serialized labels. Existing repeated
header ledger entries are counted on both sides, preserving the baseline's
established serialization. Every newly added `duplicate-header-row` occurrence
must exactly equal a header row from the first fragment of the same continued
table, on an earlier page. Empty cells contribute no separator to header text,
matching the emitter. Other ledger reasons do not account for lost words.

The JSON result reports losses, gains, total repeated-header words, and newly
accounted repeated-header words. A mismatch, unverified new header entry, or
ownership change exits nonzero. Private Models and frozen triples stay outside
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

- `ab.clj` is the runner and `abscore.clj` the scorer. `ab_checks.py` holds
  structural ownership (the index-free form of `protected.py`) and the word
  control.
- `ab_freeze.py` froze the populations (`test/tables-ab/populations.json`); run
  it with `--check` to show the freeze is unchanged.
- Tests:
  - `python3 -m unittest discover -s tools/table`
  - `bb -cp tools:tools/lib tools/table/test_abscore.clj`

SCRATCH (`/Volumes/SSK SSD/emon-scratch/trd`, or `EMON_TABLES_AB_SCRATCH`) holds
Models, transcripts, logs and the frozen inputs. The repository holds only
IDs, digests, expectations and scores.

The engine binary defaults to the live driver, `.cache/emon-rs-fusion/target/release/emon-rs`.
Build it with `EMON_RS_PDFIUM="$(cd .cache/pdfium/current/lib && pwd -P)" kin/scripts/emon-rs-build`,
or pass another driver with `--bin`.
