#!/usr/bin/env python3
"""XFUND -> emon gold (DEVLOG P2.11).   tools/import/xfund.py [--limit N]

Native ground truth: <lang>.<split>.json {lang, version, split, documents: [{id, uid,
img {fname, width, height}, document: [entity...]}]}: the FUNSD entity format (box in image
pixels, top-left origin) for 7 languages.  Mapping, claims and coordinate handling are FUNSD's
(tools/import/funsd.py); the locale is the file's language.  The annotated image size must equal
the image file's; a mismatch is skipped and counted.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C
import funsd


def items(corpus, st):
    base = os.path.join(corpus, "multilingual", "xfund")
    for fn in sorted(os.listdir(base)):
        if not fn.endswith(".json"):
            continue
        d = json.load(open(os.path.join(base, fn), encoding="utf-8"))
        lang, split = d["lang"], d["split"]
        for doc in d["documents"]:
            img = os.path.join(base, "%s.%s" % (lang, split), doc["img"]["fname"])
            if not os.path.exists(img):
                st.warn("image missing")
                yield doc["id"], None
                continue
            page, fmt = C.raster_page(img)
            if [page["w"], page["h"]] != [doc["img"]["width"], doc["img"]["height"]]:
                st.warn("annotated image size differs from the file")
                yield doc["id"], None
                continue
            prov = {"producer": "XFUND (Xu et al. 2021), human-annotated; %s" % fn, "variants": {"image": C.rel(img)}}
            gid = "xfund-" + doc["id"]
            yield gid, funsd.form_gold(gid, doc["document"], page, fmt, lang, prov)


if __name__ == "__main__":
    sys.exit(C.run("xfund", items, description=__doc__))
