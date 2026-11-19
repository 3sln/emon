#!/bin/sh
# The LIVE Apple turnkey predictor for `bb accuracy` (K8, D901; P13.21b, D907; the live-Tesseract one is predict-emon-live.sh): the Rust Engine with the Vision `Ocr` port (`ocr-engine=apple-vision`)
# AND Tesseract as the fallback of the pages kin's policy sends to it (`emon.ofall`), and when the pinned pdfium is fetched (`bb pdfium-fetch`) the pdfium `Renderer`. Protocol:
#   predict-emon-live-vision.sh ID VARIANT INPUT GOLD   -> the Model's canonical JSON on stdout
# macOS only, and NOT A GATE: a gate that ran an engine would depend on whose Vision (the OS build) is installed (D008, D095); the committed Vision transcripts are replayed instead
# (`bb ocr`). The driver is built by `EMON_RS_VISION=1 EMON_RS_LIVE=1 [EMON_RS_PDFIUM=<libdir>] kin/scripts/emon-rs-build` into .cache/emon-rs-vision-tess, and again when any source changes.
# The fallback reads with the models of $TESSDATA_PREFIX (a directory of .traineddata files; the homebrew tessdata, English and OSD, when it is unset).
# EMON_OUT=evidence asks for the evidence dump instead of the Model; EMON_RS_FLAGS: extra driver flags (`--ocr-scope=witness`, `--no-retarget`).
# `predict-emon-live-vision-alone.sh` is the same with no Tesseract: Vision reads every page.
set -e
if [ "$(uname -s)" != Darwin ] || ! command -v cargo > /dev/null; then
  echo "emon-live-vision: Apple Vision needs macOS and cargo (SKIP)" >&2
  exit 3
fi
root=$(cd "$(dirname "$0")/../.." && pwd)
lib="$root/.cache/pdfium/current/lib"
pdfium=""
if [ -d "$lib" ]; then pdfium="--pdfium"; lib=$(cd "$lib" && pwd -P); else lib=""; fi
bin="$root/.cache/emon-rs-vision-tess/target/release/emon-rs"
stamp=$( (echo "pdfium=$lib"; cat "$root"/gen/rust/emon/*.rs "$root"/host/rust/src/*.rs "$root"/host/rust/tests/*.rs "$root"/host/vision/* "$root"/test/driver/rust/main.rs "$root"/kin/scripts/emon-rs-build) | cksum)
if [ ! -x "$bin" ] || [ "$(cat "$root/.cache/emon-rs-vision-tess/stamp" 2>/dev/null)" != "$stamp" ]; then
  if [ -n "$lib" ]; then EMON_RS_VISION=1 EMON_RS_LIVE=1 EMON_RS_PDFIUM="$lib" "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1
  else EMON_RS_VISION=1 EMON_RS_LIVE=1 "$root/kin/scripts/emon-rs-build" > /dev/null || exit 1; fi
  printf '%s' "$stamp" > "$root/.cache/emon-rs-vision-tess/stamp"
fi
exec "$bin" --ocr-engine=apple-vision --tesseract $pdfium $EMON_RS_FLAGS ${EMON_OUT:+--out=$EMON_OUT} "$3"
