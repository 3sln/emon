#!/bin/sh
# The LIVE-OCR emon predictor for `bb accuracy` (P6.5, D211): the Rust Engine with the Tesseract `Ocr` port.   Protocol:
#   predict-emon-live.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# NOT A GATE: it needs libtesseract and libleptonica (brew: tesseract, leptonica; EMON_TESS_LIB is their directory, default
# /opt/homebrew/lib) and OCR takes seconds a page, and a gate that ran an engine would depend on whose Tesseract is installed
# (D008, D095). The gates replay a committed transcript instead (`predict-emon-replay.sh`, `bb ocr`). The driver `emon-rs` is built
# with the `tesseract` feature (`EMON_RS_LIVE=1 kin/scripts/emon-rs-build`) into .cache/emon-rs-live, and again when any source
# changes (a cksum stamp, as `predict-emon.sh` does). Models are read from $TESSDATA_PREFIX or the usual places; no `--locale` is
# given, so no language is assumed (D038): the engine reads with every model it has.
set -e
if ! command -v cargo > /dev/null || ! ls "${EMON_TESS_LIB:-/opt/homebrew/lib}"/libtesseract.* > /dev/null 2>&1; then
  echo "emon-live: no cargo or no libtesseract (set EMON_TESS_LIB) (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
bin="$root/.cache/emon-rs-live/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs-live/stamp" 2>/dev/null)" != "$stamp" ]; then
  EMON_RS_LIVE=1 "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs-live/stamp"
fi
exec "$bin" --tesseract "$3"
