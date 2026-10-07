#!/bin/sh
# The LIVE fusion emon predictor for `bb accuracy` (P7.3, D285-D290; P7.S, D200): the Rust Engine with pdfium rendering every page and Tesseract reading it, with
# its default settings (`ocr: always`, `ocr-scope: full`): born-digital pages are OCR'd and fused. NOT A GATE (it needs the pinned pdfium, libtesseract and
# libleptonica, and takes seconds a page).   Protocol:
#   predict-emon-fusion-live.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# EMON_OUT=evidence asks for the evidence dump (version "2") instead of the Model. EMON_RS_FLAGS: extra driver flags (`--ocr-scope=witness`,
# `--no-retarget`). The driver is built by `EMON_RS_PDFIUM=<libdir> kin/scripts/emon-rs-build` into .cache/emon-rs-fusion, and again when any source changes.
set -e
root=$(cd "$(dirname "$0")/../.." && pwd)
lib="$root/.cache/pdfium/current/lib"
if ! command -v cargo > /dev/null || [ ! -d "$lib" ] || ! ls "${EMON_TESS_LIB:-/opt/homebrew/lib}"/libtesseract.* > /dev/null 2>&1; then
  echo "emon-fusion-live: no cargo, no pinned pdfium (bb pdfium-fetch) or no libtesseract (SKIP)" >&2
  exit 3
fi
lib=$(cd "$lib" && pwd -P)
bin="$root/.cache/emon-rs-fusion/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs-fusion/stamp" 2>/dev/null)" != "$stamp" ]; then
  EMON_RS_PDFIUM="$lib" "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs-fusion/stamp"
fi
exec "$bin" --pdfium --tesseract $EMON_RS_FLAGS ${EMON_OUT:+--out=$EMON_OUT} "$3"
