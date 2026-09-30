#!/usr/bin/env python3
"""The emon accuracy scorer (DEVLOG P2.9, D023, SPEC 14.2, D077).

    score(pred_model, gold_file) -> {"counts": {...}, "stubs": [...]}

COUNTS, NOT RATIOS: each document returns the numerators and denominators of
every metric, so a corpus can be pooled (micro-averaged) by adding them.
`metrics(counts)` turns pooled counts into the ratios the scoreboard tracks.

WHAT IS SCORED IS WHAT THE GOLD CLAIMS (D072): `annotated` says which node kinds,
which attributes per kind, whether text is annotated, which ledger reasons are
exhaustive, and which page fields. A metric the gold does not support is absent
from the counts, never zero.

NORMALIZATION (locale-independent, D077): NFC; every run of Unicode whitespace
(str.split(), no locale) is ONE space; ends trimmed. Case, punctuation and
digits are compared as they are. Text units are joined by "\\n" before this, so
a line break and a space are the same thing to CER/WER, and a paragraph boundary
is scored by its own metric. WER tokens are whitespace runs (for scripts
without spaces WER is a line-level measure; CER is the one to read).

MATCH RULE: predicted blocks are matched to gold blocks kind-blind, and kind is
scored AFTER matching (a mis-typed block is an FN of its gold kind and an FP of
its predicted kind).
  * if both sides have `at`: per-page bounding boxes overlap at IoU >= 0.5
    (areas summed over pages), greedy by IoU; DocLayNet style;
  * else (the synthetic gold has no geometry): a monotone alignment in reading
    order on normalized text, pairs admitted at similarity >= 0.5, where
    similarity = 1 - levenshtein/max(len), or 1 for two empty texts of one kind.
"""
import difflib
import json
import re
import sys
import unicodedata
from collections import Counter

# kinds that are "blocks" for P/R; everything else is structure inside them
BLOCK_KINDS = ["heading", "paragraph", "list", "item", "table", "figure",
               "caption", "seal", "note", "code", "quote"]
TEXT_KINDS = ("heading", "paragraph", "item", "caption", "cell", "code", "quote", "seal")
CONTAINERS = ("figure", "table", "list", "note")
IOU = 0.5
SIM = 0.5


# ----------------------------------------------------------------- text ----

def norm(s):
    return " ".join(unicodedata.normalize("NFC", s or "").split())


def lev(a, b):
    """Levenshtein distance of two sequences (str or list of hashables);
    Hyyro's bit-parallel algorithm on python ints: O(len(b)) big-int steps."""
    if len(a) < len(b):
        a, b = b, a            # bit-vector over the longer one
    m = len(a)
    if len(b) == 0:
        return m
    peq = {}
    for i, c in enumerate(a):
        peq[c] = peq.get(c, 0) | (1 << i)
    mask = (1 << m) - 1
    top = 1 << (m - 1)
    pv, mv, score = mask, 0, m
    for c in b:
        eq = peq.get(c, 0)
        xv = eq | mv
        xh = ((((eq & pv) + pv) & mask) ^ pv) | eq
        ph = mv | (~(xh | pv) & mask)
        mh = pv & xh
        if ph & top:
            score += 1
        elif mh & top:
            score -= 1
        ph = ((ph << 1) | 1) & mask
        mh = (mh << 1) & mask
        pv = mh | (~(xv | ph) & mask)
        mv = ph & xv
    return score


def sim(a, b):
    if not a and not b:
        return 1.0
    return 1.0 - lev(a, b) / max(len(a), len(b))


# ----------------------------------------------------------------- walk ----

def walk(n):
    yield n
    for c in n.get("children", []) or []:
        yield from walk(c)


def units(body):
    """Reading-order text units: (kind, text) of every text-bearing node."""
    return [(n["kind"], n["text"]) for n in walk(body)
            if n.get("kind") in TEXT_KINDS and isinstance(n.get("text"), str)]


def body_text(body):
    return norm("\n".join(t for _, t in units(body)))


def block_text(n):
    """A block's matching text: its own text, else its descendants' in order."""
    if n["kind"] not in CONTAINERS and isinstance(n.get("text"), str):
        return norm(n["text"])
    return norm("\n".join(t for _, t in units({"children": n.get("children", [])})))


def blocks(body, kinds):
    return [n for n in walk(body) if n is not body and n.get("kind") in kinds]


# ------------------------------------------------------------- geometry ----

def _boxes(at):
    by = {}
    for r in at or []:
        p, x0, y0, x1, y1 = r
        b = by.setdefault(p, [x0, y0, x1, y1])
        b[0], b[1], b[2], b[3] = min(b[0], x0), min(b[1], y0), max(b[2], x1), max(b[3], y1)
    return by


def iou(a, b):
    ba, bb = _boxes(a), _boxes(b)
    inter = ua = 0.0
    for p in set(ba) | set(bb):
        x, y = ba.get(p), bb.get(p)
        ax = (x[2] - x[0]) * (x[3] - x[1]) if x else 0.0
        ay = (y[2] - y[0]) * (y[3] - y[1]) if y else 0.0
        i = 0.0
        if x and y:
            w = min(x[2], y[2]) - max(x[0], y[0])
            h = min(x[3], y[3]) - max(x[1], y[1])
            i = max(w, 0.0) * max(h, 0.0)
        inter += i
        ua += ax + ay - i
    return inter / ua if ua > 0 else 0.0


# ------------------------------------------------------------- matching ----

def match_geometry(g, p):
    """Greedy by descending IoU. Returns [(gi, pi)] sorted by gold index."""
    cand = sorted(((iou(a.get("at"), b.get("at")), i, j)
                   for i, a in enumerate(g) for j, b in enumerate(p)),
                  reverse=True)
    ug, up, out = set(), set(), []
    for v, i, j in cand:
        if v < IOU:
            break
        if i in ug or j in up:
            continue
        ug.add(i); up.add(j); out.append((i, j))
    return sorted(out)


def _dp(gt, pt, gk, pk):
    """Monotone alignment on a gap: maximise summed similarity."""
    n, m = len(gt), len(pt)
    best = [[0.0] * (m + 1) for _ in range(n + 1)]
    back = [[None] * (m + 1) for _ in range(n + 1)]

    def s(i, j):
        if not gt[i] and not pt[j]:
            return 1.0 if gk[i] == pk[j] else 0.0
        v = sim(gt[i], pt[j])
        return v if v >= SIM else 0.0

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            b, how = best[i - 1][j], "g"
            if best[i][j - 1] > b:
                b, how = best[i][j - 1], "p"
            v = s(i - 1, j - 1)
            if v > 0 and best[i - 1][j - 1] + v >= b:
                b, how = best[i - 1][j - 1] + v, "m"
            best[i][j], back[i][j] = b, how
    out, i, j = [], n, m
    while i > 0 and j > 0:
        h = back[i][j]
        if h == "m":
            out.append((i - 1, j - 1)); i -= 1; j -= 1
        elif h == "g":
            i -= 1
        else:
            j -= 1
    return out[::-1]


def match_text(g, p):
    gt, pt = [block_text(n) for n in g], [block_text(n) for n in p]
    gk, pk = [n["kind"] for n in g], [n["kind"] for n in p]
    # anchors: identical text (empty texts keyed by kind), then a DP in the gaps
    ga = [t or "\0" + k for t, k in zip(gt, gk)]
    pa = [t or "\0" + k for t, k in zip(pt, pk)]
    sm = difflib.SequenceMatcher(None, ga, pa, autojunk=False)
    out, gi, pi = [], 0, 0
    for a, b, size in sm.get_matching_blocks():
        for i, j in _dp(gt[gi:a], pt[pi:b], gk[gi:a], pk[pi:b]):
            out.append((gi + i, pi + j))
        out.extend((a + k, b + k) for k in range(size))
        gi, pi = a + size, b + size
    return out


def match(g, p):
    if g and p and all(n.get("at") for n in g) and all(n.get("at") for n in p):
        return match_geometry(g, p)
    return match_text(g, p)


# ------------------------------------------------------------- sections ----

def kendall_tau(pairs):
    """tau over matched blocks: pairs are (gold_idx, pred_idx), gold-sorted."""
    n = len(pairs)
    if n < 2:
        return None
    c = d = 0
    for a in range(n):
        for b in range(a + 1, n):
            if pairs[a][1] < pairs[b][1]:
                c += 1
            else:
                d += 1
    return (c - d) / (c + d)


def para_boundaries(body):
    """Words of every prose unit in order, and the word indices where a unit
    begins (a boundary sits BEFORE that index)."""
    words, cuts = [], set()
    for k, t in units(body):
        if k not in ("paragraph", "item", "quote", "caption"):
            continue
        w = norm(t).split()
        if not w:
            continue
        if words:
            cuts.add(len(words))
        words.extend(w)
    return words, cuts


def boundary_counts(gb, pb):
    """A gold boundary is found when the word stream alignment maps it to a
    predicted boundary. Text errors that destroy the alignment cost recall."""
    gw, gc = para_boundaries(gb)
    pw, pc = para_boundaries(pb)
    sm = difflib.SequenceMatcher(None, gw, pw, autojunk=False)
    pair = {}
    for a, b, size in sm.get_matching_blocks():
        for k in range(size):
            pair[a + k] = b + k
    tp = 0
    for i in gc:
        if i in pair:
            j = pair[i]
        elif i - 1 in pair:
            j = pair[i - 1] + 1
        else:
            continue
        if j in pc:
            tp += 1
    return {"para.boundary.tp": tp, "para.boundary.gold": len(gc), "para.boundary.pred": len(pc)}


def _fmt_template(t, n):
    return re.sub(r"\{n(?::0?(\d+))?\}",
                  lambda m: str(n).zfill(int(m.group(1))) if m.group(1) else str(n), t)


def ledger(model, reasons):
    """Multiset of (reason, page, text) for the dropped entries of `reasons`."""
    out = {}
    for d in model.get("dropped", []) or []:
        if d.get("reason") in reasons:
            page = d.get("page") or (d["at"][0][0] if d.get("at") else 0)
            key = ("dropped:" + d["reason"], page, norm(d.get("text", "")))
            out[key] = out.get(key, 0) + 1
    return out


def furniture(model):
    """Multiset of (role, page, text): a template is expanded per page."""
    out = {}
    for f in model.get("furniture", []) or []:
        for pg in f.get("pages", []) or []:
            if f.get("template") is not None:
                t = _fmt_template(f["template"], pg)
            else:
                t = "\n".join(x for _, x in units({"children": f.get("children", [])}))
            key = ("furniture:" + str(f.get("role", "")), pg, norm(t))
            out[key] = out.get(key, 0) + 1
    return out


def multiset_counts(g, p):
    tp = sum(min(v, p.get(k, 0)) for k, v in g.items())
    return tp, sum(g.values()), sum(p.values())


def table_teds_lite(g, p):
    """A TEDS stand-in: a table's cells as (row, colspan, rowspan, text) tuples
    in order; similarity = 1 - edit distance / max length. `teds-struct` drops
    the text. The real TEDS (tree edit distance over HTML) is a stub (STUBS)."""
    def cells(t, with_text):
        out = []
        for ri, r in enumerate(x for x in walk(t) if x.get("kind") == "row"):
            for c in r.get("children", []) or []:
                out.append((ri, c.get("colspan", 1), c.get("rowspan", 1),
                            norm(c.get("text", "")) if with_text else ""))
        return out
    r = {}
    for name, wt in (("teds", True), ("teds-struct", False)):
        a, b = cells(g, wt), cells(p, wt)
        r[name] = 1.0 - lev(a, b) / max(len(a), len(b), 1)
    return r


STUBS = [
    "table.teds: real TEDS (tree edit distance over table HTML) not implemented; table-teds-lite is a cell-sequence stand-in",
    "heading.hierarchy-teds: hierarchy TEDS not implemented (heading detection and level accuracy are)",
    "confidence.ece: expected calibration error needs calibrated confidences (Model conf is 'uncalibrated', SPEC 14.2)",
    "seals.name-cer: seal name/text CER is not split out of block text",
    "gold has no geometry: the IoU >= 0.5 rule is implemented and unit-tested but unexercised on the synthetic corpus",
]


# ---------------------------------------------------------------- score ----

def score(pred, gold):
    """Counts for one document. `pred` is an emon-1 Model dict, `gold` a gold dict."""
    ann = gold["annotated"]
    gm = gold["model"]
    gb = gm["body"]
    pb = pred.get("body") or {"id": 0, "kind": "document"}
    attrs = ann.get("attributes", {})
    c = {}

    def add(k, v=1):
        c[k] = c.get(k, 0) + v

    # -- text: CER / WER / bag of words over reading order
    if ann.get("text"):
        gt, pt = body_text(gb), body_text(pb)
        add("text.chars", len(gt)); add("text.char-err", lev(gt, pt))
        gw, pw = gt.split(), pt.split()
        add("text.words", len(gw)); add("text.word-err", lev(gw, pw))
        add("text.bow.tp", sum((Counter(gw) & Counter(pw)).values()))
        add("text.bow.gold", len(gw)); add("text.bow.pred", len(pw))

    # -- blocks by kind (presence AND absence: an annotated kind absent from
    #    gold makes every predicted block of it a false positive)
    kinds = [k for k in BLOCK_KINDS if k in ann["structure"]]
    g, p = blocks(gb, kinds), blocks(pb, kinds)
    pairs = match(g, p)
    for k in kinds:
        ng = sum(1 for n in g if n["kind"] == k)
        npd = sum(1 for n in p if n["kind"] == k)
        tp = sum(1 for i, j in pairs if g[i]["kind"] == k == p[j]["kind"])
        if ng or npd:
            add("block.%s.tp" % k, tp); add("block.%s.gold" % k, ng); add("block.%s.pred" % k, npd)
    t = kendall_tau(pairs)
    if t is not None:
        add("order.tau-sum", t); add("order.docs")

    # -- headings: detection is block.heading; level on matched heading pairs
    if "heading" in kinds and "level" in attrs.get("heading", []):
        hp = [(i, j) for i, j in pairs if g[i]["kind"] == "heading" == p[j]["kind"]]
        add("heading.level.n", len(hp))
        add("heading.level.ok", sum(1 for i, j in hp if g[i].get("level") == p[j].get("level")))
        add("heading.level.near", sum(1 for i, j in hp if isinstance(p[j].get("level"), int)
                                      and abs(g[i]["level"] - p[j]["level"]) <= 1))
        add("heading.gold", sum(1 for n in g if n["kind"] == "heading"))

    # -- figures: kind accuracy on matched figures
    if "figure" in kinds and "subkind" in attrs.get("figure", []):
        fp = [(i, j) for i, j in pairs if g[i]["kind"] == "figure" == p[j]["kind"]]
        add("figure.subkind.n", len(fp))
        add("figure.subkind.ok", sum(1 for i, j in fp if g[i].get("subkind") == p[j].get("subkind")))

    # -- tables: TEDS-lite on matched tables (an unmatched gold table scores 0)
    if "table" in kinds:
        ng = sum(1 for n in g if n["kind"] == "table")
        if ng:
            tp_ = [(i, j) for i, j in pairs if g[i]["kind"] == "table" == p[j]["kind"]]
            s = [table_teds_lite(g[i], p[j]) for i, j in tp_]
            add("table.n", ng)
            add("table.teds-lite-sum", sum(x["teds"] for x in s))
            add("table.teds-struct-lite-sum", sum(x["teds-struct"] for x in s))
            if "continued" in attrs.get("table", []):
                add("table.continued.n", ng)
                add("table.continued.ok", sum(1 for i, j in tp_
                                               if g[i].get("continued", 1) == p[j].get("continued", 1)))

    # -- paragraph boundaries
    if ann.get("text") and "paragraph" in kinds:
        for k, v in boundary_counts(gb, pb).items():
            add(k, v)

    # -- furniture / dropped ledger (only the reasons the gold declares exhaustive)
    if ann.get("furniture") or ann.get("dropped"):
        reasons = set(ann.get("dropped", []))
        gx, px = ledger(gm, reasons), ledger(pred, reasons)
        if ann.get("furniture"):
            gx.update(furniture(gm)); px.update(furniture(pred))
        tp, ng, npd = multiset_counts(gx, px)
        add("furniture.tp", tp); add("furniture.gold", ng); add("furniture.pred", npd)
        # watermark contamination: characters of watermark words that leaked into the body
        if "watermark" in reasons:
            wm = set()
            for d in gm.get("dropped", []) or []:
                if d.get("reason") == "watermark":
                    wm.update(norm(d.get("text", "")).split())
            if wm:
                cg = Counter(w for w in body_text(gb).split() if w in wm)
                cp = Counter(w for w in body_text(pb).split() if w in wm)
                add("contam.leak-chars", sum(len(w) * (n - cg[w]) for w, n in cp.items() if n > cg[w]))
                add("contam.body-chars", len(body_text(pb)))

    # -- page labels
    if "label" in ann.get("pages", []):
        gp = {x["n"]: x.get("label") for x in gm.get("pages", [])}
        pp = {x["n"]: x.get("label") for x in pred.get("pages", []) or []}
        add("pages.label.n", len(gp))
        add("pages.label.ok", sum(1 for n, v in gp.items() if pp.get(n) == v))
    return {"counts": c, "stubs": STUBS}


# -------------------------------------------------------------- ratios -----

LOWER_IS_BETTER = {"cer", "wer", "watermark-leak"}


def _ratio(n, d):
    return n / d if d else None


def _prf(c, pre, out, name):
    if (pre + ".gold") not in c and (pre + ".pred") not in c:
        return
    tp, g, p = c.get(pre + ".tp", 0), c.get(pre + ".gold", 0), c.get(pre + ".pred", 0)
    # with nothing predicted, precision is 1 only if nothing was expected
    pr = tp / p if p else (1.0 if not g else 0.0)
    rc = tp / g if g else (1.0 if not p else 0.0)
    out[name + "-p"], out[name + "-r"] = pr, rc
    out[name + "-f1"] = 2 * pr * rc / (pr + rc) if pr + rc else 0.0


def metrics(c):
    """Pooled counts -> {metric-name: value}. Names are scoreboard keys."""
    m = {}
    if "text.chars" in c:
        m["cer"] = _ratio(c["text.char-err"], c["text.chars"])
        m["wer"] = _ratio(c["text.word-err"], c["text.words"])
        _prf(c, "text.bow", m, "bow")
    if c.get("order.docs"):
        m["order-tau"] = c["order.tau-sum"] / c["order.docs"]
    for k in BLOCK_KINDS:
        _prf(c, "block." + k, m, "block-" + k)
    _prf(c, "block.heading", m, "heading")
    if "heading.level.n" in c:
        m["heading-level-acc"] = _ratio(c["heading.level.ok"], c["heading.level.n"])
        m["heading-level-near"] = _ratio(c["heading.level.near"], c["heading.level.n"])
        # end to end: a heading never found has no level, so it counts against
        m["heading-level-e2e"] = _ratio(c["heading.level.ok"], c["heading.gold"])
    if "figure.subkind.n" in c:
        m["figure-subkind-acc"] = _ratio(c["figure.subkind.ok"], c["figure.subkind.n"])
    if c.get("table.n"):
        m["table-teds-lite"] = c["table.teds-lite-sum"] / c["table.n"]
        m["table-teds-struct-lite"] = c["table.teds-struct-lite-sum"] / c["table.n"]
        if c.get("table.continued.n"):
            m["table-continued-acc"] = c["table.continued.ok"] / c["table.continued.n"]
    _prf(c, "para.boundary", m, "para-boundary")
    _prf(c, "furniture", m, "furniture")
    if c.get("pages.label.n"):
        m["page-label-acc"] = c["pages.label.ok"] / c["pages.label.n"]
    if "contam.leak-chars" in c:
        m["watermark-leak"] = _ratio(c["contam.leak-chars"], c["contam.body-chars"]) or 0.0
    return {k: v for k, v in m.items() if v is not None}


def pool(list_of_counts):
    out = {}
    for c in list_of_counts:
        for k, v in c.items():
            out[k] = out.get(k, 0) + v
    return out


def main(argv):
    if len(argv) != 3:
        print("usage: score.py PRED.json GOLD.json")
        return 2
    with open(argv[1]) as f:
        pred = json.load(f)
    with open(argv[2]) as f:
        gold = json.load(f)
    r = score(pred, gold)
    print(json.dumps({"metrics": metrics(r["counts"]), "counts": r["counts"]}, indent=1, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
