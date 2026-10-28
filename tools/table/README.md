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
