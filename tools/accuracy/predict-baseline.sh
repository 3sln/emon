#!/bin/sh
# A REFERENCE predictor for `bb accuracy` (P2.10, D500): the Model a pinned third-party extractor's output was
# adapted to by tools/oracle/baselines.py, read back from .cache/baselines/TOOL/VARIANT/ID.json.  Protocol:
#   predict-baseline.sh TOOL ID VARIANT INPUT GOLD   -> the Model's JSON on stdout
# No extractor runs here; `bb baselines` does that, once, and this only reads what it wrote. A missing file
# is "not available" (exit 3), said so.
root=$(cd "$(dirname "$0")/../.." && pwd)
f="$root/.cache/baselines/$1/$3/$2.json"
if [ ! -f "$f" ]; then
  echo "baseline $1 has no output for $3/$2 (run: bb baselines $1 --extract)" >&2
  exit 3
fi
exec cat "$f"
