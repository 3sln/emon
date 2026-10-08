#!/bin/sh
# The REPLAYING emon predictor for handwritten signatures, initials and digital signatures (P8.3, D900-D907): the Rust Engine converting a fixture of
# test/signatures (a born-digital page with vector and raster signatures, initials, signature fields, or a scan) from the committed transcript
# test/signatures/replay/ID/ (recorded live on Rust by tools/sigs/record.clj WITH the bitmaps the page units read).   Protocol:
#   predict-emon-sigs.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# A gate's: it calls no engine. A document with no committed transcript is `not available` (exit 3, the protocol's word).
set -e
if ! command -v cargo > /dev/null; then
  echo "emon-sigs: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
t="$root/test/signatures/replay/$1"
if [ ! -f "$t/transcript.edn" ]; then
  echo "emon-sigs: no committed transcript for $1" >&2
  exit 3
fi
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" --replay="$t" "$3"
