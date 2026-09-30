#!/usr/bin/env python3
"""Laws of the accuracy scorer (DEVLOG P2.9, D074).   bb unit runs this.

Every expected number below is worked out by hand in a comment beside it; none
is copied from the scorer's output. No corpus is needed except for the last
class, which checks the oracle and the degraded fixture on three real documents.
"""
import json
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import accuracy as A  # noqa: E402
import score as S  # noqa: E402

ALL = ["section", "heading", "paragraph", "list", "item", "table", "rowgroup", "row",
       "cell", "caption", "figure", "seal", "quote", "code", "note"]


def gold(children, **over):
    ann = {"structure": ALL, "attributes": {"heading": ["level"], "figure": ["subkind"]},
           "text": True, "furniture": False, "dropped": [], "pages": ["n"]}
    ann.update(over)
    return {"annotated": ann, "model": {"pages": [{"n": 1}],
                                        "body": {"id": 0, "kind": "document", "children": children}}}


def pred(children, **extra):
    m = {"pages": [{"n": 1}], "body": {"id": 0, "kind": "document", "children": children}}
    m.update(extra)
    return m


def P(t):
    return {"kind": "paragraph", "text": t}


def H(t, level):
    return {"kind": "heading", "level": level, "text": t}


def counts(g, p):
    return S.score(p, g)["counts"]


class Text(unittest.TestCase):
    def test_lev_hand(self):
        self.assertEqual(S.lev("kitten", "sitting"), 3)   # k>s, e>i, +g
        self.assertEqual(S.lev("", "abc"), 3)
        self.assertEqual(S.lev("abc", ""), 3)
        self.assertEqual(S.lev("flaw", "lawn"), 2)        # -f, +n
        self.assertEqual(S.lev(["a", "b"], ["a", "c", "b"]), 1)

    def test_lev_matches_dp(self):
        rnd = random.Random(7)
        def dp(a, b):
            prev = list(range(len(b) + 1))
            for i, x in enumerate(a, 1):
                cur = [i]
                for j, y in enumerate(b, 1):
                    cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
                prev = cur
            return prev[-1]
        for _ in range(300):
            a = "".join(rnd.choice("abc") for _ in range(rnd.randint(0, 25)))
            b = "".join(rnd.choice("abc") for _ in range(rnd.randint(0, 25)))
            self.assertEqual(S.lev(a, b), dp(a, b), (a, b))

    def test_cer_wer(self):
        # gold "abcd" (4 chars), pred "abxd": 1 substitution -> CER 1/4
        c = counts(gold([P("abcd")]), pred([P("abxd")]))
        self.assertEqual((c["text.chars"], c["text.char-err"]), (4, 1))
        self.assertAlmostEqual(S.metrics(c)["cer"], 0.25)
        # gold "the cat sat" (3 words), pred "the cat": one deleted word -> WER 1/3;
        # chars: " sat" deleted = 4 of 11
        c = counts(gold([P("the cat sat")]), pred([P("the cat")]))
        self.assertEqual((c["text.words"], c["text.word-err"]), (3, 1))
        self.assertEqual((c["text.chars"], c["text.char-err"]), (11, 4))

    def test_pooling_is_micro(self):
        a = counts(gold([P("abcd")]), pred([P("abxd")]))         # 1 / 4
        b = counts(gold([P("abcdefgh")]), pred([P("abcdefgh")])) # 0 / 8
        self.assertAlmostEqual(S.metrics(S.pool([a, b]))["cer"], 1 / 12)  # not the mean 1/8

    def test_bag_of_words_ignores_order(self):
        c = counts(gold([P("one two three")]), pred([P("three one two")]))
        m = S.metrics(c)
        self.assertEqual(m["bow-f1"], 1.0)           # same words
        self.assertGreater(m["wer"], 0.0)            # wrong order
        # gold "one two three" vs "three one two": delete-insert = 2 word edits
        self.assertEqual(c["text.word-err"], 2)

    def test_normalization(self):
        # NFC: decomposed e + U+0301 equals the composed character
        c = counts(gold([P("café")]), pred([P("café")]))
        self.assertEqual(c["text.char-err"], 0)
        # whitespace: runs of (Unicode) spaces, newlines, NBSP and tabs are one space
        c = counts(gold([P("a b\nc")]), pred([P("a   b\t\tc ")]))
        self.assertEqual(c["text.char-err"], 0)
        # no case folding, no locale: Turkish dotted/dotless I stays distinct
        c = counts(gold([P("I")]), pred([P("ı")]))
        self.assertEqual(c["text.char-err"], 1)
        c = counts(gold([P("Ab")]), pred([P("ab")]))
        self.assertEqual(c["text.char-err"], 1)

    def test_text_unit_boundaries_are_whitespace(self):
        # one paragraph vs two with the same words: CER/WER do not see the split
        c = counts(gold([P("a b"), P("c d")]), pred([P("a b c d")]))
        self.assertEqual(c["text.char-err"], 0)


class Blocks(unittest.TestCase):
    def test_precision_recall_hand(self):
        # gold: 3 paragraphs. pred: two of them + one spurious -> tp 2, gold 3, pred 3
        g = gold([P("alpha alpha alpha"), P("bravo bravo bravo"), P("charlie charlie")])
        p = pred([P("alpha alpha alpha"), P("zulu yankee xray"), P("charlie charlie")])
        c = counts(g, p)
        self.assertEqual((c["block.paragraph.tp"], c["block.paragraph.gold"], c["block.paragraph.pred"]), (2, 3, 3))
        m = S.metrics(c)
        self.assertAlmostEqual(m["block-paragraph-p"], 2 / 3)
        self.assertAlmostEqual(m["block-paragraph-r"], 2 / 3)
        self.assertAlmostEqual(m["block-paragraph-f1"], 2 / 3)

    def test_f1_unequal(self):
        # gold 4, pred 2 both right: P = 1, R = 1/2, F1 = 2*1*.5/1.5 = 2/3
        g = gold([P("one one"), P("two two"), P("three three"), P("four four")])
        p = pred([P("one one"), P("three three")])
        m = S.metrics(counts(g, p))
        self.assertEqual(m["block-paragraph-p"], 1.0)
        self.assertEqual(m["block-paragraph-r"], 0.5)
        self.assertAlmostEqual(m["block-paragraph-f1"], 2 / 3)

    def test_mistyped_block_is_fn_and_fp(self):
        g = gold([H("Intro", 1), P("body text here")])
        p = pred([P("Intro"), P("body text here")])
        m = S.metrics(counts(g, p))
        self.assertEqual(m["block-heading-r"], 0.0)         # the heading was missed
        self.assertAlmostEqual(m["block-paragraph-p"], 0.5)  # 1 of 2 predicted paragraphs is right
        self.assertEqual(m["block-paragraph-r"], 1.0)

    def test_fuzzy_match_above_half(self):
        # "abcdefgh" vs "abcdefxx": similarity 0.75 >= 0.5, a match; "abcd" vs "wxyz" is not
        c = counts(gold([P("abcdefgh")]), pred([P("abcdefxx")]))
        self.assertEqual(c["block.paragraph.tp"], 1)
        c = counts(gold([P("abcd")]), pred([P("wxyz")]))
        self.assertEqual(c["block.paragraph.tp"], 0)

    def test_annotated_honoured(self):
        g = gold([H("T", 1), P("body")], structure=["paragraph"], text=False)
        c = counts(g, pred([P("body"), P("extra")]))
        self.assertNotIn("block.heading.gold", c)        # heading not annotated: no claim
        self.assertEqual(c["block.paragraph.pred"], 2)
        self.assertNotIn("text.chars", c)                # text not annotated
        self.assertNotIn("heading.level.n", c)
        # absence is annotated too: an annotated kind with no gold makes predictions FPs
        g = gold([P("body")])
        c = counts(g, pred([P("body"), {"kind": "list", "children": [{"kind": "item", "text": "x"}]}]))
        self.assertEqual((c["block.list.gold"], c["block.list.pred"], c["block.list.tp"]), (0, 1, 0))
        self.assertEqual(S.metrics(c)["block-list-p"], 0.0)

    def test_reading_order_tau(self):
        self.assertEqual(S.kendall_tau([(0, 0), (1, 1), (2, 2)]), 1.0)
        self.assertEqual(S.kendall_tau([(0, 1), (1, 0)]), -1.0)
        self.assertAlmostEqual(S.kendall_tau([(0, 0), (1, 2), (2, 1)]), 1 / 3)  # 2 concordant, 1 discordant


class Headings(unittest.TestCase):
    def test_level_accuracy_hand(self):
        g = gold([H("Alpha", 1), H("Bravo", 2), H("Charlie", 2)])
        p = pred([H("Alpha", 1), H("Bravo", 2), H("Charlie", 4)])
        c = counts(g, p)
        self.assertEqual((c["heading.level.n"], c["heading.level.ok"], c["heading.level.near"]), (3, 2, 2))
        m = S.metrics(c)
        self.assertAlmostEqual(m["heading-level-acc"], 2 / 3)
        self.assertAlmostEqual(m["heading-level-near"], 2 / 3)   # 4 vs 2 is not within one
        self.assertEqual(m["heading-p"], 1.0)
        self.assertEqual(m["heading-r"], 1.0)

    def test_off_by_one_is_near_not_right(self):
        g = gold([H("Alpha", 2)])
        m = S.metrics(counts(g, pred([H("Alpha", 3)])))
        self.assertEqual(m["heading-level-acc"], 0.0)
        self.assertEqual(m["heading-level-near"], 1.0)

    def test_missed_heading_costs_end_to_end_only(self):
        # 4 gold headings, 3 found, 2 of those at the right level:
        # detection R = 3/4, level acc (matched) = 2/3, end-to-end = 2/4
        g = gold([H("Alpha Alpha", 1), H("Bravo Bravo", 2), H("Charlie Charlie", 3), H("Delta Delta", 2)])
        p = pred([H("Alpha Alpha", 1), H("Bravo Bravo", 2), H("Charlie Charlie", 1)])
        m = S.metrics(counts(g, p))
        self.assertAlmostEqual(m["heading-r"], 3 / 4)
        self.assertEqual(m["heading-p"], 1.0)
        self.assertAlmostEqual(m["heading-level-acc"], 2 / 3)
        self.assertAlmostEqual(m["heading-level-e2e"], 2 / 4)

    def test_level_skipped_when_not_annotated(self):
        g = gold([H("Alpha", 1)], attributes={"heading": []})
        c = counts(g, pred([H("Alpha", 3)]))
        self.assertNotIn("heading.level.n", c)
        self.assertNotIn("heading-level-acc", S.metrics(c))

    def test_nested_sections_found(self):
        sec = lambda lv, *ch: {"kind": "section", "level": lv, "children": list(ch)}
        g = gold([sec(1, H("Top", 1), sec(2, H("Sub", 2), P("x y z")))])
        m = S.metrics(counts(g, pred([sec(1, H("Top", 1), sec(2, H("Sub", 2), P("x y z")))])))
        self.assertEqual(m["heading-level-acc"], 1.0)


class Other(unittest.TestCase):
    def test_paragraph_boundaries(self):
        # gold: "a b" | "c d"  (one boundary, before word 2).  pred merges them:
        g = gold([P("a b"), P("c d")])
        c = counts(g, pred([P("a b c d")]))
        self.assertEqual((c["para.boundary.tp"], c["para.boundary.gold"], c["para.boundary.pred"]), (0, 1, 0))
        m = S.metrics(c)
        self.assertEqual(m["para-boundary-r"], 0.0)
        self.assertEqual(m["para-boundary-p"], 0.0)   # expected one, predicted none
        # an extra split costs precision: gold one boundary, pred 3 paragraphs
        c = counts(gold([P("a b"), P("c d e")]), pred([P("a b"), P("c d"), P("e")]))
        self.assertEqual((c["para.boundary.tp"], c["para.boundary.gold"], c["para.boundary.pred"]), (1, 1, 2))
        self.assertAlmostEqual(S.metrics(c)["para-boundary-p"], 0.5)
        # a boundary survives a typo in a neighbouring word
        c = counts(gold([P("a b c"), P("d e f")]), pred([P("a b x"), P("d e f")]))
        self.assertEqual(c["para.boundary.tp"], 1)

    def test_geometry_iou(self):
        a = [[1, 0, 0, 10, 10]]
        self.assertEqual(S.iou(a, a), 1.0)
        self.assertEqual(S.iou(a, [[1, 20, 20, 30, 30]]), 0.0)
        self.assertEqual(S.iou(a, [[2, 0, 0, 10, 10]]), 0.0)                # other page
        self.assertAlmostEqual(S.iou(a, [[1, 5, 0, 15, 10]]), 50 / 150)     # half shifted: 50 / (100+100-50)
        # the rule: >= 0.5 matches irrespective of text when both have geometry
        g = gold([dict(P("same"), at=[[1, 0, 0, 10, 10]])])
        ok = counts(g, pred([dict(P("other words"), at=[[1, 0, 0, 10, 9]])]))   # IoU 0.9
        no = counts(g, pred([dict(P("same"), at=[[1, 5, 0, 15, 10]])]))         # IoU 1/3
        self.assertEqual(ok["block.paragraph.tp"], 1)
        self.assertEqual(no["block.paragraph.tp"], 0)

    def test_furniture_and_ledger(self):
        g = gold([P("body")], furniture=True, dropped=["page-number"])
        g["model"]["pages"] = [{"n": 1}, {"n": 2}]
        g["model"]["furniture"] = [{"id": 1, "role": "footer", "template": "ACME-{n:02}", "pages": [1, 2]}]
        g["model"]["dropped"] = [{"reason": "page-number", "page": 1, "text": "1"},
                                 {"reason": "page-number", "page": 2, "text": "2"}]
        p = pred([P("body")], furniture=[{"id": 1, "role": "footer", "pages": [1, 2],
                                          "children": [{"kind": "paragraph", "text": "ACME-01"}]}],
                 dropped=[{"reason": "page-number", "page": 1, "text": "1", "at": [[1, 0, 0, 1, 1]]}])
        # gold: 2 page-numbers + 2 expanded furniture = 4.  pred: 1 + one furniture item over 2 pages
        # (ACME-01 on both pages; page 2's text is wrong) -> tp = 1 page-number + 1 furniture = 2
        c = counts(g, p)
        self.assertEqual((c["furniture.tp"], c["furniture.gold"], c["furniture.pred"]), (2, 4, 3))

    def test_watermark_leak(self):
        g = gold([P("real body text")], dropped=["watermark"])
        g["model"]["dropped"] = [{"reason": "watermark", "page": 1, "text": "DRAFT"}]
        c = counts(g, pred([P("real body text DRAFT")], dropped=[{"reason": "watermark", "page": 1, "at": [[1, 0, 0, 1, 1]], "text": "DRAFT"}]))
        self.assertEqual(c["contam.leak-chars"], 5)
        self.assertEqual(c["contam.body-chars"], 20)
        self.assertAlmostEqual(S.metrics(c)["watermark-leak"], 0.25)

    def test_table_teds_lite(self):
        cell = lambda t: {"kind": "cell", "text": t}
        row = lambda *ts: {"kind": "row", "children": [cell(t) for t in ts]}
        tbl = lambda *rows: {"kind": "table", "children": [{"kind": "rowgroup", "children": list(rows)}]}
        g = gold([tbl(row("a", "b"), row("c", "d"))])
        self.assertEqual(S.metrics(counts(g, pred([tbl(row("a", "b"), row("c", "d"))])))["table-teds-lite"], 1.0)
        # one wrong cell of four: similarity 1 - 1/4
        r = S.table_teds_lite(g["model"]["body"]["children"][0], tbl(row("a", "b"), row("c", "x")))
        self.assertAlmostEqual(r["teds"], 0.75)
        self.assertEqual(r["teds-struct"], 1.0)
        # a table missed entirely scores 0
        self.assertEqual(S.metrics(counts(g, pred([P("a b c d")])))["table-teds-lite"], 0.0)


class Ratchet(unittest.TestCase):
    def test_edn_round_trip(self):
        b = {":format": 1, ":tolerance": 0.002, ":predictors": {"x": {"pdf/all": {":cer": 0.0123, ":wer": 1.0}}}}
        self.assertEqual(A.edn_read(A.HEADER + A.edn_write(b)), b)

    def test_compare_directions(self):
        old = {"k": {":cer": 0.10, ":heading-level-acc": 0.80}}
        # within tolerance either way: fine
        reg, imp, gone = A.compare(old, {"k": {":cer": 0.101, ":heading-level-acc": 0.799}}, 0.002)
        self.assertEqual((reg, gone), ([], []))
        # CER is lower-is-better: a rise beyond tolerance is a regression; accuracy falling is too
        reg, imp, gone = A.compare(old, {"k": {":cer": 0.12, ":heading-level-acc": 0.70}}, 0.002)
        self.assertEqual(sorted(r[1] for r in reg), [":cer", ":heading-level-acc"])
        # improvements in either direction
        reg, imp, gone = A.compare(old, {"k": {":cer": 0.05, ":heading-level-acc": 0.90}}, 0.002)
        self.assertEqual((reg, len(imp)), ([], 2))
        # a vanished metric or category fails
        reg, imp, gone = A.compare(old, {"k": {":cer": 0.1}}, 0.002)
        self.assertEqual([g[1] for g in gone], [":heading-level-acc"])
        self.assertEqual(len(A.compare(old, {}, 0.002)[2]), 2)

    def test_merge_only_moves_toward_better(self):
        old = {"k": {":cer": 0.10, ":f": 0.80}}
        new = {"k": {":cer": 0.12, ":f": 0.85, ":g": 0.5}}
        self.assertEqual(A.merge_up(old, new), {"k": {":cer": 0.10, ":f": 0.85, ":g": 0.5}})


class Fixtures(unittest.TestCase):
    """The oracle is perfect and the degraded predictor is not, on real documents."""

    DOCS = ["h-numbered", "table-multipage-thead", "furn-title-pagenum", "watermark-draft"]

    def run_pred(self, name, doc):
        inp = os.path.join(A.CORPUS, "pdf", doc + ".pdf")
        gp = os.path.join(A.CORPUS, "gold", doc + ".gold.json")
        m, _ = A.run_predictor(A.PREDICTORS[name], doc, "pdf", inp, gp)
        with open(gp) as f:
            return S.metrics(S.score(m, json.load(f))["counts"])

    def test_oracle_perfect_degraded_worse(self):
        for doc in self.DOCS:
            o, d = self.run_pred("oracle", doc), self.run_pred("degraded", doc)
            self.assertEqual(o["cer"], 0.0, doc)
            self.assertEqual(o["wer"], 0.0, doc)
            for k, v in o.items():
                if k not in ("cer", "wer", "watermark-leak"):
                    self.assertEqual(v, 1.0, (doc, k))
            self.assertGreater(d["cer"], 0.0, doc)
        dh = self.run_pred("degraded", "h-numbered")     # many headings: levels and kinds get damaged
        self.assertLess(dh["block-heading-f1"], 1.0)
        self.assertLess(dh["heading-level-acc"], 1.0)
        self.assertLess(dh["para-boundary-p"], 1.0)      # demoted headings add boundaries
        self.assertLess(self.run_pred("degraded", "furn-title-pagenum")["para-boundary-r"], 1.0)  # merged paragraphs
        self.assertLess(self.run_pred("degraded", "table-multipage-thead")["table-teds-lite"], 1.0)
        self.assertLess(self.run_pred("degraded", "furn-title-pagenum")["furniture-r"], 1.0)
        self.assertGreater(self.run_pred("degraded", "watermark-draft")["watermark-leak"], 0.0)

    def test_ratchet_fails_a_degraded_predictor_against_the_oracle_board(self):
        # the ratchet, end to end: the oracle's values are the "committed" board,
        # the degraded predictor is the run that regressed
        good, _ = A.evaluate(A.PREDICTORS["oracle"])
        bad, _ = A.evaluate(A.PREDICTORS["degraded"])
        reg, imp, gone = A.compare(good, bad, 0.002)
        self.assertGreater(len(reg), 100)
        self.assertEqual(A.compare(good, good, 0.002), ([], [], []))

    def test_placeholder_is_not_a_failure(self):
        r = A.run_predictor(A.PREDICTORS["emon"], "x", "pdf", "x", "x")
        self.assertIsNone(r[0])


def main():
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    res = unittest.TextTestRunner(verbosity=0, stream=sys.stderr).run(suite)
    if not res.wasSuccessful():
        print("  FAIL accuracy-test: %d failure(s)" % (len(res.failures) + len(res.errors)))
        return 1
    print("  ok   accuracy-test: %d laws of the scorer and the ratchet hold (CER/WER, P/R/F1, heading level, "
          "boundaries, IoU, ledger, oracle perfect, degraded worse)" % res.testsRun)
    return 0


if __name__ == "__main__":
    sys.exit(main())
