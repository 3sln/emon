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
#   1b. (P6.6, D209) wm_pdf.clj EDITS six of those PDFs by appended updates (watermark as marked content, as an
#      optional content group, as an annotation; two rewritten ToUnicode CMaps) and MAKES `hidden-older-ocr` from
#      scan/h-plain.pdf and a worse previous OCR's text layer (Chrome does not print that one);
#   2. degrade.clj rasterises each PDF and writes scan/STEM.pdf, an image-only
#      skewed/noisy/JPEG variant with the same gold -- except for the 12 documents of P6.6, which are scanned in
#      colour and with overlays instead (scan-color/, scan-ovl/ of the FUSION set: `degrade.clj --fusion`);
#   3. build.clj extracts the layout facts (page count and size, the page each
#      multi-page table row landed on), writes MANIFEST.json (producer and
#      version, sha256 of every file) and regenerates gold/STEM.gold.json.
#
# Needs: Chrome or Chromium ($CHROME, or found below), poppler (pdfinfo,
# pdftotext, pdftoppm), ImageMagick (magick), babashka (bb).  Fonts are system
# fonts only, so the output depends on the OS's fonts: PRODUCER.json records
# the OS and the producer.
set -e
cd "$(dirname "$0")/../.."
corpus=test/corpus/synth
TAGGED="h-inverted table-simple-ruled lists-basic"
HAND="hidden-older-ocr table-ledger-sparse table-summary-and-ledger"          # made by wm_pdf.clj, not printed by Chrome
# (P6.8, D227) the hand-made graphics PDFs (gfx_pdf.clj, tagged_tables.clj) are not printed either; the mixed documents are
# printed AFTER mixed.clj has made their images from the tables printed here
for h in gfx-onepath-grid gfx-hidden-rules gfx-shading-bands gfx-pattern-bands gfx-curve-lines gfx-image-rules gfx-softmask-rule \
         ttab-good ttab-good-ws ttab-bad-spans ttab-bad-rows ttab-allp ttab-layout ttab-th-as-td ttab-missing-row; do
  HAND="$HAND $h ${h}-untagged"
done
MIXED="mixed-table-image mixed-table-image-shaded mixed-two-tables"

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

# Chrome's exit status is not the answer (headless=new can exit 2 after a "teardown watchdog" with the
# PDF written): the answer is a non-empty PDF that was not there before this run.
print_pdf() { # print_pdf OUT [flags...]
  out=$1; shift
  rm -f "$out"
  "$chrome" --headless=new --disable-gpu --no-pdf-header-footer "$@" \
    --print-to-pdf="$out" "file://$(pwd)/$corpus/html/$s.html" > /dev/null 2>&1 || true
  [ -s "$out" ] || { echo "print.sh: Chrome wrote no PDF for $s ($out)" >&2; exit 1; }
}

for s in $stems; do
  case " $HAND $MIXED " in *" $s "*) continue ;; esac
  print_pdf "$corpus/pdf/$s.pdf" --disable-pdf-tagging
  case " $TAGGED " in
    *" $s "*) print_pdf "$corpus/pdf-tagged/$s.pdf" ;;
  esac
  echo "  printed $s"
done

[ -z "$PRINT_ONLY" ] || exit 0       # PRINT_ONLY=1: stop after Chrome (to look at the pages)
# (P6.8) rule truth of the sources, then the mixed documents' images and HTML, their print, the hand-made PDFs
bb -cp tools:tools/lib tools/synth/gfx_pdf.clj
bb -cp tools:tools/lib tools/synth/table_model.clj
bb -cp tools:tools/lib tools/synth/tagged_tables.clj
python3 tools/oracle/rules.py table-simple-ruled table-zebra
bb -cp tools:tools/lib tools/synth/mixed.clj image
bb -cp tools:tools/lib tools/synth/mixed.clj
for s in $MIXED; do print_pdf "$corpus/pdf/$s.pdf" --disable-pdf-tagging; echo "  printed $s"; done
python3 tools/oracle/rules.py
bb -cp tools:tools/lib tools/synth/wm_pdf.clj patch
bb -cp tools:tools/lib -m synth.degrade $stems
bb -cp tools:tools/lib -m synth.degrade-fusion
bb -cp tools:tools/lib -m synth.degrade-graphics
bb -cp tools:tools/lib -m synth.build
