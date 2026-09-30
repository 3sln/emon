#!/bin/sh
# Reproduce the synthetic corpus's PDFs (DEVLOG P2.8, D041, D063).   tools/synth/print.sh [stem ...]
#
# A DEV-TIME action, reviewed like any other diff.  NO GATE RUNS THIS: `bb unit`
# checks the committed files (kin/scripts/gold-check.py) and never a producer.
# Steps:
#
#   1. headless Chromium/Chrome prints test/corpus/synth/html/STEM.html to
#      pdf/STEM.pdf (untagged: no struct tree, so structure must come from the
#      page) -- and, for the stems in TAGGED, also to pdf-tagged/STEM.pdf;
#   2. degrade.py rasterises each PDF and writes scan/STEM.pdf, an image-only
#      skewed/noisy/JPEG variant with the same gold;
#   3. build.py extracts the layout facts (page count and size, the page each
#      multi-page table row landed on), writes MANIFEST.json (producer and
#      version, sha256 of every file) and regenerates gold/STEM.gold.json.
#
# Needs: Chrome or Chromium ($CHROME, or found below), poppler (pdfinfo,
# pdftotext, pdftoppm), ImageMagick (magick), python3.  Fonts are system
# fonts only, so the output depends on the OS's fonts: PRODUCER.json records
# the OS and the producer.
set -e
cd "$(dirname "$0")/../.."
corpus=test/corpus/synth
TAGGED="h-inverted table-simple-ruled lists-basic"

chrome="${CHROME:-}"
if [ -z "$chrome" ]; then
  for c in "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "/Applications/Chromium.app/Contents/MacOS/Chromium" \
           "$(command -v chromium 2>/dev/null)" "$(command -v chromium-browser 2>/dev/null)" \
           "$(command -v google-chrome 2>/dev/null)"; do
    if [ -n "$c" ] && [ -x "$c" ]; then chrome="$c"; break; fi
  done
fi
[ -x "$chrome" ] || { echo "print.sh: no Chrome/Chromium found (set CHROME=...)" >&2; exit 1; }

version=$("$chrome" --version)
echo "producer: $version"
mkdir -p "$corpus/pdf" "$corpus/pdf-tagged" "$corpus/scan"
printf '{"name":"headless Chromium printToPDF","binary":"%s","version":"%s","os":"%s","flags":["--headless=new","--disable-gpu","--no-pdf-header-footer","--disable-pdf-tagging (except pdf-tagged/)"]}\n' \
  "$(basename "$chrome")" "$version" "$(uname -sr)" > "$corpus/PRODUCER.json"

stems="$*"
[ -n "$stems" ] || stems=$(cd "$corpus/html" && ls *.html | sed 's/\.html$//')

for s in $stems; do
  "$chrome" --headless=new --disable-gpu --no-pdf-header-footer --disable-pdf-tagging \
    --print-to-pdf="$corpus/pdf/$s.pdf" "file://$(pwd)/$corpus/html/$s.html" > /dev/null 2>&1
  case " $TAGGED " in
    *" $s "*) "$chrome" --headless=new --disable-gpu --no-pdf-header-footer \
        --print-to-pdf="$corpus/pdf-tagged/$s.pdf" "file://$(pwd)/$corpus/html/$s.html" > /dev/null 2>&1 ;;
  esac
  echo "  printed $s"
done

python3 tools/synth/degrade.py $stems
python3 tools/synth/build.py
