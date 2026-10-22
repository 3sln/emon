"""Measurement-contract tests; no corpus and no OCR required."""
import unittest
from receipts import intact, overlap, words, units, score, angle
class Contracts(unittest.TestCase):
    def test_wrapped_angles(self):
        self.assertAlmostEqual(angle(359.9),.1)
        self.assertAlmostEqual(angle(-.1),.1)
        self.assertEqual(angle(270),90)
    def test_rotation_attempts(self):
        g={'model':{'body':{'text':'Soup'}}}
        r=score(g,{'body':{'text':'Soup'}},{'oruns':[{'pass':'re-rotated'},{'pass':'first'}]},[],[])
        self.assertEqual(r['rotation_retry_runs'],1)
    def test_multiset(self):
        self.assertEqual(overlap(words('1.00 1.00 -1.00'),words('1.00 -1.00')),2)
    def test_numeric_punctuation(self):
        self.assertFalse(intact('-1.00',['1.00']))
        self.assertFalse(intact('1,00',['1.00']))
    def test_entity_order_and_boundaries(self):
        self.assertFalse(intact('Soup 2 8.00',['8.00 2 Soup']))
        self.assertFalse(intact('Soup 2 8.00',['Soup','2 8.00']))
        self.assertTrue(intact('Soup 2 8.00',['Soup 2 8.00']))
    def test_rows_join_cells(self):
        model={'body':{'kind':'document','children':[{'kind':'row','children':[{'kind':'cell','text':'Soup'},{'kind':'cell','text':'2 8.00'}]}]}}
        self.assertTrue(intact('Soup 2 8.00',units(model)))
    def test_furniture_is_in_model(self):
        g={'model':{'body':{'text':'Market Soup'}}}
        m={'body':{'text':'Soup'},'furniture':[{'kind':'header','text':'Market'}]}
        r=score(g,m,{},[('company','Market')],[])
        self.assertEqual(r['matched'],2)
        self.assertEqual(r['silent_words'],0)
        self.assertEqual(r['entities_ok'],1)
    def test_dropped_is_accounted(self):
        g={'id':'fixture','model':{'body':{'text':'Soup 2 8.00'}}}
        m={'body':{'text':'Soup'},'dropped':[{'text':'2'}]}
        r=score(g,m,{'owords':[{'text':'8.00'}]},[],[])
        self.assertEqual(r['silent_words'],1)
        self.assertEqual(r['ocr_present_silent_words'],1)
if __name__=='__main__': unittest.main()
