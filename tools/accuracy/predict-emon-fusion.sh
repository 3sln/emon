#!/bin/sh
# The FUSION-REPLAYING emon predictor for `bb accuracy` (P7.3, D259-D264): the Rust Engine converting a born-digital document with the hidden
# setting `ocr-born-digital`, from the committed transcript test/fusion/replay/ID/ (recorded live on Rust with pdfium and Tesseract by
# tools/fusion/record-replay.clj, bitmaps dropped: a replay needs the answers only).   Protocol:
#   predict-emon-fusion.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# A gate's: it calls no engine. A document with no committed transcript is `not available` (exit 3, the protocol's word).
set -e
if ! command -v cargo > /dev/null; then
  echo "emon-fusion: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
t="$root/test/fusion/replay/$1"
if [ ! -f "$t/transcript.edn" ]; then
  echo "emon-fusion: no committed transcript for $1" >&2
  exit 3
fi
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" --ocr-born-digital --replay="$t" "$3"
