#!/bin/sh
# TEMPORARY predictor for P1.8's measurement: PDF -> content -> para -> Model JSON (the Rust target),
# NOT the Engine (P1.10 replaces this and `predict-emon.sh`).
#   bb accuracy --predictor "sh tools/accuracy/predict-naive.sh"
# Protocol: predict-naive.sh ID VARIANT INPUT GOLD -> Model JSON on stdout; exit 3 for a variant with no
# text layer. The binary is built once into .cache/ (gitignored) from gen/rust, and again when gen/ changes.
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
if [ "$2" != "pdf" ] && [ "$2" != "pdf-tagged" ]; then
  # no text layer to read (a scan has no OCR here): an empty document, so a run over every variant
  # completes; only the pdf variant means anything
  printf '%s' '{"emon":"1.1","settings":{"locale":null},"conf":"uncalibrated","pages":[],"body":{"id":0,"kind":"document"}}'
  exit 0
fi
bin="$root/.cache/naive"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/tools/accuracy/naive/main.rs | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$bin.stamp" 2>/dev/null)" != "$stamp" ]; then
  work=$(mktemp -d "${TMPDIR:-/tmp}/emon-naive.XXXXXX")
  mkdir -p "$work/emon" "$root/.cache"
  cp "$root"/gen/rust/emon/*.rs "$work/emon/"
  cp "$root/host/rust/src/port.rs" "$root/host/rust/src/testport.rs" "$work/"
  for f in "$root"/gen/rust/emon/*.rs; do echo "pub mod $(basename "$f" .rs);"; done > "$work/emon.rs"
  cp "$root/tools/accuracy/naive/main.rs" "$work/main.rs"
  (cd "$work" && rustc -O -C overflow-checks=on -o naive main.rs 2>&1 | grep -E '^error' -A14 | head -80 >&2) || true
  if [ ! -x "$work/naive" ]; then
    echo "naive: build failed" >&2
    rm -rf "$work"
    exit 1
  fi
  mv "$work/naive" "$bin"
  printf '%s' "$stamp" > "$bin.stamp"
  rm -rf "$work"
fi
exec "$bin" "$3"
