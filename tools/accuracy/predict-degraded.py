#!/usr/bin/env python3
"""FIXTURE PREDICTOR "degraded": the gold, deliberately and deterministically
damaged, to prove that every metric moves the way it should (D077).

Same protocol as predict-oracle.py. Damage is periodic, with no randomness, so the
scoreboard entry is reproducible; the `scan` variant is damaged harder:
  text       every Nth character of each text unit is replaced (CER/WER/BOW)
  headings   every 3rd heading's level is +1 (level accuracy), every 7th heading
             becomes a paragraph (detection P/R, paragraph FP)
  paragraphs every 2nd pair of adjacent paragraphs is merged (boundary recall)
  tables     the last cell of every table loses its text (TEDS-lite)
  furniture  running-title/page-number entries of page 2+ are dropped (furniture recall)
  watermark  a watermark's text is appended to the first paragraph (leak)
Nothing here looks at the input file.
"""
import json
import sys


def walk(n):
    yield n
    for c in n.get("children", []) or []:
        yield from walk(c)


def damage(s, every):
    if not s:
        return s
    out = list(s)
    for i in range(every - 1, len(out), every):
        out[i] = "#" if out[i] != "#" else "@"
    return "".join(out)


def main():
    _id, variant, _inp, gold_path = sys.argv[1:5]
    with open(gold_path) as f:
        m = json.load(f)["model"]
    every = 40 if variant == "pdf" else 15      # scan: harsher
    body = m["body"]
    for n in walk(body):
        if isinstance(n.get("text"), str):
            n["text"] = damage(n["text"], every)
            n.pop("inlines", None)
    hs = [n for n in walk(body) if n.get("kind") == "heading"]
    for i, h in enumerate(hs):
        if i % 3 == 2:
            h["level"] = h["level"] + 1
        if i % 7 == 6:
            h["kind"] = "paragraph"
            for k in ("level", "number", "numbering"):
                h.pop(k, None)
    for n in list(walk(body)):
        ch = n.get("children")
        if not ch:
            continue
        out, i, k = [], 0, 0
        while i < len(ch):
            if (i + 1 < len(ch) and ch[i].get("kind") == "paragraph" == ch[i + 1].get("kind")):
                k += 1
                if k % 2 == 0:
                    ch[i]["text"] = ch[i]["text"] + " " + ch[i + 1]["text"]
                    out.append(ch[i]); i += 2
                    continue
            out.append(ch[i]); i += 1
        n["children"] = out
    for t in (n for n in walk(body) if n.get("kind") == "table"):
        cells = [n for n in walk(t) if n.get("kind") == "cell"]
        if cells:
            cells[-1]["text"] = ""
    m["dropped"] = [d for d in m.get("dropped", []) or []
                    if not (d["reason"] in ("running-title", "page-number") and d["page"] >= 2)]
    wm = [d for d in m.get("dropped", []) or [] if d["reason"] == "watermark"]
    paras = [n for n in walk(body) if n.get("kind") == "paragraph"]
    if wm and paras:
        paras[0]["text"] += " " + wm[0].get("text", "")
    json.dump(m, sys.stdout)


main()
