#!/usr/bin/env python3
"""Degraded 'scanned' variants of the synthetic PDFs (DEVLOG P2.8, D063).

    tools/synth/degrade.py [stem ...]     pdf/STEM.pdf -> scan/STEM.pdf

Rasterise at 150 dpi (poppler), skew by a small per-page angle, add Gaussian
noise and a slight blur, JPEG at quality 50, and assemble an image-only PDF
whose page size equals the source's.  Everything is seeded from the stem and
page number, so a rerun gives the same pixels.  The gold Model of a document
is the SAME for its scan: the truth did not change, only the evidence.
Tooling only (poppler + ImageMagick); no gate runs it.
"""
import hashlib
import os
import subprocess
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")
DPI = 150


def seed(stem, page):
    return int(hashlib.sha256(("%s/%d" % (stem, page)).encode()).hexdigest()[:8], 16)


def main(stems):
    if not stems:
        stems = sorted(f[:-5] for f in os.listdir(os.path.join(CORPUS, "html")) if f.endswith(".html"))
    for stem in stems:
        src = os.path.join(CORPUS, "pdf", stem + ".pdf")
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["pdftoppm", "-r", str(DPI), "-gray", "-png", src, os.path.join(tmp, "p")], check=True)
            pages = sorted(f for f in os.listdir(tmp) if f.endswith(".png"))
            outs = []
            for i, f in enumerate(pages, 1):
                sd = seed(stem, i)
                angle = ((sd % 2001) - 1000) / 1000.0 * 1.2       # -1.2 .. 1.2 degrees
                out = os.path.join(tmp, "s%03d.jpg" % i)
                w, h = subprocess.run(["magick", "identify", "-format", "%w %h", os.path.join(tmp, f)],
                                      capture_output=True, text=True, check=True).stdout.split()
                subprocess.run(["magick", os.path.join(tmp, f), "-background", "white", "-rotate", "%.3f" % angle,
                                "-gravity", "center", "-crop", "%sx%s+0+0" % (w, h), "+repage",
                                "-seed", str(sd), "-attenuate", "0.35", "+noise", "Gaussian",
                                "-blur", "0x0.5", "-strip", "-quality", "50", out], check=True)
                outs.append(out)
            dst = os.path.join(CORPUS, "scan", stem + ".pdf")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            subprocess.run(["magick"] + outs + ["-density", str(DPI), "-units", "PixelsPerInch", dst], check=True)
        print("  degraded %s (%d pages)" % (stem, len(pages)))


if __name__ == "__main__":
    main(sys.argv[1:])
