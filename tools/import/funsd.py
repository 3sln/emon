#!/usr/bin/env python3
"""FUNSD -> emon gold (DEVLOG P2.11).   tools/import/funsd.py [--limit N]

Native ground truth: annotations/<id>.json, {"form": [entity...]}; an entity is
{id, box [x0,y0,x1,y1] in IMAGE PIXELS (origin top-left, y down), text, label
question|answer|header|other, words [{box,text}], linking [[a,b],...]}.  The images
are PNG, no resolution.  Coordinates need NO transform (already top-left pixels);
the page is the image at a nominal 1 px = 1 pt (tools/import/common.py).

MAPPING (decisions, D0xx):
  header entity                 -> heading   (no level: FUNSD has none)
  question entity               -> field     name = its text, value = the texts of the
                                   answer entities linked to it (joined by a space, top-to-bottom),
                                   `at` = the union of the question and its answers
  answer entity not linked to a question, other entity
                                -> paragraph (not claimed)
  entities with empty text are dropped (FUNSD has a few empty boxes).

CLAIMS: `field` and `heading` presence and absence; field name/value/at; heading text/at.
NOT claimed: paragraphs, reading order (body order is a top-to-bottom sort), heading levels.
Known noise: FUNSD's own labels and links contain errors and are a noisy-scan benchmark.

`form_gold` is shared with xfund.py (XFUND uses the same entity format).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def form_gold(gid, entities, page, fmt, lang, provenance):
    by = {e["id"]: e for e in entities}
    links = {}
    for e in entities:
        for a, b in e.get("linking") or []:
            for x, y in ((a, b), (b, a)):
                if x in by and y in by and by[x]["label"] == "question" and by[y]["label"] == "answer":
                    links.setdefault(x, [])
                    if y not in links[x]:
                        links[x].append(y)
    used = {y for v in links.values() for y in v}

    def reg(*es):
        x0, y0, x1, y1 = C.union([e["box"] for e in es])
        return C.region(1, x0, y0, x1, y1)

    items = []  # (sort key, node)
    for e in entities:
        text = C.norm(e.get("text"))
        if not text:
            continue
        key = (e["box"][1], e["box"][0])
        lab = e["label"]
        if lab == "header":
            items.append((key, C.node("heading", at=reg(e), text=text)))
        elif lab == "question":
            ans = sorted((by[y] for y in links.get(e["id"], []) if C.norm(by[y].get("text"))),
                         key=lambda a: (a["box"][1], a["box"][0]))
            f = C.node("field", at=reg(e, *ans), name=text)
            if ans:
                f["value"] = C.norm(" ".join(a["text"] for a in ans))
            items.append((key, f))
        elif lab == "answer" and e["id"] in used:
            continue
        else:
            items.append((key, C.node("paragraph", at=reg(e), text=text)))
    items.sort(key=lambda kv: kv[0])
    ann = C.annotated(
        structure=["heading", "field"],
        attributes={"heading": ["text", "at"], "field": ["name", "value", "at"]},
        pages=["n", "px", "kind"])
    return C.gold(gid, ann, [page], [n for _, n in items], fmt, lang, provenance)


def items(corpus, st):
    base = os.path.join(corpus, "forms", "funsd", "dataset")
    for split in ("training_data", "testing_data"):
        d = os.path.join(base, split, "annotations")
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".json"):
                continue
            stem = fn[:-5]
            img = os.path.join(base, split, "images", stem + ".png")
            ann = json.load(open(os.path.join(d, fn), encoding="utf-8"))["form"]
            page, fmt = C.raster_page(img)
            for e in ann:
                x0, y0, x1, y1 = e["box"]
                if x1 > page["w"] or y1 > page["h"] or x0 < 0 or y0 < 0:
                    st.warn("box outside the image")
            prov = {"producer": "FUNSD (Jaume et al. 2019), human-annotated; annotations/%s.json" % stem,
                    "variants": {"image": C.rel(img)}}
            yield "funsd-" + stem, form_gold("funsd-" + stem, ann, page, fmt, "en", prov)


if __name__ == "__main__":
    sys.exit(C.run("funsd", items, description=__doc__))
