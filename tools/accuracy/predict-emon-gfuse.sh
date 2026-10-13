#!/bin/sh
# The GRAPHICS-FUSING emon predictor for `bb accuracy` (P7.G, D512-D519): the Rust Engine converting a document of the graphics set with its default settings (`ocr: always`: the
# pages are OCR'd, the tracer reads their pixels, graphics fusion arbitrates the vector, tracer and OCR sources), from the committed transcript test/fusion/graphics/KEY/ (recorded live
# on Rust with pdfium and Tesseract by tools/fusion/record-graphics.clj, WITH the bitmaps the page units read).   Protocol:
#   predict-emon-gfuse.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout (EMON_OUT=evidence: the evidence dump, for the graphics metrics)
# KEY is ID for the born-digital variant (`pdf`), VARIANT--ID for a scan. A gate's: it calls no engine. A document with no committed transcript is `not available` (exit 3, the protocol's word).
set -e
if ! command -v cargo > /dev/null; then
  echo "emon-gfuse: cargo is not on PATH, so the Rust Engine cannot be built (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
key=$1
if [ "$2" != pdf ]; then key="$2--$1"; fi
t="$root/test/fusion/graphics/$key"
if [ ! -f "$t/transcript.edn" ]; then
  echo "emon-gfuse: no committed transcript for $key" >&2
  exit 3
fi
bin="$root/.cache/emon-rs/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs/stamp" 2>/dev/null)" != "$stamp" ]; then
  "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs/stamp"
fi
exec "$bin" ${EMON_OUT:+--out=$EMON_OUT} --replay="$t" "$3"
