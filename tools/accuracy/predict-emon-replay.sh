#!/bin/sh
# The REPLAYING emon predictor for `bb accuracy` (P6.5, D211): the Rust Engine converting a scan from the committed OCR transcript
# test/ocr/scan/ID/ (recorded live on Rust by tools/ocr/record-scan.clj, bitmaps dropped: a replay needs the answers only).   Protocol:
#   predict-emon-replay.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# A gate's: it calls no engine, so it scores emon and never whose Tesseract is installed (D008, D095). A document with no committed
# transcript is `not available` (exit 3, the protocol's word); `bb ocr` runs it over the ones that have one (`--ids`).
# `px` first (the predictor `emon-replay-px`, D392): the WHOLE transcripts of test/ocr/scan-px/ (bitmaps kept: the engine reads pixels).
set -e
dir=scan
if [ "$1" = "px" ]; then dir=scan-px; shift; fi
if ! command -v cargo > /dev/null; then
  echo "emon-replay: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
name=$1
# a whole transcript of a colour or overlay scan is in test/ocr/scan-px/VARIANT--ID/ (D459); a scan's is in ID/
if [ "$dir" = scan-px ] && [ "$2" != scan ]; then name="$2--$1"; fi
t="$root/test/ocr/$dir/$name"
if [ ! -f "$t/transcript.edn" ]; then
  echo "emon-replay: no committed transcript for $1" >&2
  exit 3
fi
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" --replay="$t" "$3"
