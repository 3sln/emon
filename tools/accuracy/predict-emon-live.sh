#!/bin/sh
# The LIVE-OCR emon predictor for `bb accuracy` (P6.5, D211; P7.S, D200): the Rust Engine with the Tesseract `Ocr` port and, when the pinned pdfium is
# fetched (`bb pdfium-fetch`), the pdfium `Renderer`: with its default settings (`ocr: always`) a born-digital page is then OCR'd and fused too (the switch of
# P7.S), `EMON_RS_FLAGS=--ocr-scope=witness` reads it as witness lines. Protocol:
#   predict-emon-live.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# NOT A GATE: it needs libtesseract and libleptonica (brew: tesseract, leptonica; EMON_TESS_LIB is their directory, default
# /opt/homebrew/lib) and OCR takes seconds a page, and a gate that ran an engine would depend on whose Tesseract is installed
# (D008, D095). The gates replay a committed transcript instead (`predict-emon-replay.sh`, `bb ocr`, `bb fusion`). With the pdfium the driver is the one of
# `predict-emon-fusion-live.sh` (.cache/emon-rs-fusion); without it `EMON_RS_LIVE=1 kin/scripts/emon-rs-build` builds .cache/emon-rs-live, and again when any
# source changes (a cksum stamp, as `predict-emon.sh` does). Models are read from $TESSDATA_PREFIX or the usual places; no `--locale` is
# given, so no language is assumed (D038): the engine reads with every model it has (a born-digital page: those of its text layer's scripts).
set -e
if ! command -v cargo > /dev/null || ! ls "${EMON_TESS_LIB:-/opt/homebrew/lib}"/libtesseract.* > /dev/null 2>&1; then
  echo "emon-live: no cargo or no libtesseract (set EMON_TESS_LIB) (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
if [ -d "$root/.cache/pdfium/current/lib" ]; then
  exec "$root/tools/accuracy/predict-emon-fusion-live.sh" "$@"
fi
bin="$root/.cache/emon-rs-live/target/release/emon-rs"
stamp=$(cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs-live/stamp" 2>/dev/null)" != "$stamp" ]; then
  EMON_RS_LIVE=1 "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  printf '%s' "$stamp" > "$root/.cache/emon-rs-live/stamp"
fi
# EMON_RS_FLAGS: extra driver flags (P7.4: `--no-retarget` is the baseline of a measurement)
exec "$bin" --tesseract $EMON_RS_FLAGS "$3"
