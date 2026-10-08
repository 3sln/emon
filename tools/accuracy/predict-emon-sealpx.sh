#!/bin/sh
# The REPLAYING emon predictor for the seals read from PIXELS (P8.2, D802): the Rust Engine converting a scanned page (the `scan-ovl` variant) from the committed
# transcript test/sealpx/replay/ovl-ID/ (recorded live on Rust by tools/sealpx/record.clj WITH the bitmaps the page units read: the tracer, the ink key and the
# polar unwarp read pixels).   Protocol:
#   predict-emon-sealpx.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# A gate's: it calls no engine. A document with no committed transcript is `not available` (exit 3, the protocol's word).
set -e
if ! command -v cargo > /dev/null; then
  echo "emon-sealpx: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
t="$root/test/sealpx/replay/ovl-$1"
if [ "$2" != "scan-ovl" ] || [ ! -f "$t/transcript.edn" ]; then
  echo "emon-sealpx: no committed transcript for $1 ($2)" >&2
  exit 3
fi
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" --replay="$t" "$3"
