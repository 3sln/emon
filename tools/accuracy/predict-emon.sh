#!/bin/sh
# The real emon predictor for `bb accuracy`: the Rust Engine's driver (P1.10, D078).   Protocol:
#   predict-emon.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# The driver `emon-rs` is built by kin/scripts/emon-rs-build into .cache/ (gitignored) from gen/rust and
# host/rust/src, and again when any of them changes (a cksum stamp, so the 160 documents of a run do not
# each ask cargo). EVERY variant goes through the Engine: a scanned page has no text layer and OCR is not
# wired yet, so its Model has pages and no text, which is what the scoreboard records for `scan/*` until it is.
# A document the Engine refuses is a FAILURE of the run (exit 4 reaches `bb accuracy`), never "unavailable".
set -e
# no Rust toolchain: "not available" (exit 3, the protocol's word), said loudly, as `bb unit` skips an arm
if ! command -v cargo > /dev/null; then
  echo "emon: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" "$3"
