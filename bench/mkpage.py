#!/usr/bin/env python3
"""Make the P2.7 benchmark page: one scanned 300-dpi A4 page as raw 8-bit gray.

    python3 bench/mkpage.py [SRC.png] [OUT]

The default source is corpus/formats/derived/inv_00007.noise.png: a scanned
invoice page (katanaml-invoices) with scan noise added, 2481x3508 px = A4 at
300 dpi (corpus/ is untracked; see corpus/MANIFEST.md). The output is
tightly packed, row-major, top-left: width*height bytes, no header. The size
goes to stdout ("W H") and to OUT.size. The raw file is 8.7 MB and is NOT
committed (bench/ keeps data/ untracked).

Decoding the PNG is done by ImageMagick (`magick`), because the stock python3
here has no imaging library; the conversion is luma, 8 bit.
"""
import os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    here, "..", "corpus", "formats", "derived", "inv_00007.noise.png")
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "data", "page.gray")
os.makedirs(os.path.dirname(out), exist_ok=True)
w, h = map(int, subprocess.check_output(
    ["magick", "identify", "-format", "%w %h", src]).decode().split())
subprocess.check_call(["magick", src, "-colorspace", "Gray", "-depth", "8",
                       "gray:" + out])
assert os.path.getsize(out) == w * h, (os.path.getsize(out), w, h)
open(out + ".size", "w").write("%d %d\n" % (w, h))
print(w, h)
