"""Measurement-contract tests; no corpus and no OCR required."""
import unittest
from receipts import intact, overlap, words, units, score, angle, input_views, baseline_comparison
class Contracts(unittest.TestCase):
    def test_baseline_comparison_preserves_the_population(self):
        baseline={'sets':{'cord':{'documents':2,'rows':[['a',1],['b',2]],
                                'totals':{'word_recall':.4,'word_precision':.37,'numeric_recall':.3,'empty':2}}}}
        result=baseline_comparison('cord',{'a':{},'b':{}},
                                   {'word_recall':.5,'word_precision':.4,'numeric_recall':.38,'empty':1},baseline)
        self.assertTrue(result['same_population'])
        self.assertAlmostEqual(result['deltas']['word_recall'],.1)
        self.assertEqual(result['deltas']['empty'],-1)
        self.assertNotIn('decimal_recall',result['deltas'])
    def test_different_receipt_samples_have_no_metric_delta(self):
        result=baseline_comparison('sroie',{'b':{}},{'word_recall':1},
                                   {'sroie':{'rows':{'a':{}},'totals':{'word_recall':0}}})
        self.assertFalse(result['same_population'])
        self.assertNotIn('deltas',result)
    def test_input_views_replay_metadata(self):
        ev={'views':[{}, {'flags':['receipt-photo','receipt-adopted']}, {'flags':['receipt-thermal']}],
            'oruns':[{'pass':'first','view':0},{'pass':'receipt','view':1},{'pass':'receipt','view':2}]}
        self.assertEqual(input_views(ev),dict(ocr_runs=3,receipt_reads=2,receipt_rescue_reads=0,receipt_trigger='photo',receipt_deferred=False,receipt_adopted=True,adopted_view='photo-sauvola'))
        self.assertEqual(input_views({}),dict(ocr_runs=0,receipt_reads=0,receipt_rescue_reads=0,receipt_trigger='none',receipt_deferred=False,receipt_adopted=False,adopted_view='baseline'))
    def test_trigger_without_a_receipt_response(self):
        ev={'views':[{'flags':['receipt-thermal','receipt-deferred']}],
            'oruns':[{'pass':'first','view':0}]}
        result=input_views(ev)
        self.assertEqual(result['receipt_trigger'],'thermal')
        self.assertTrue(result['receipt_deferred'])
        self.assertEqual(result['receipt_reads'],0)
        self.assertFalse(result['receipt_adopted'])
    def test_rescue_can_adopt_the_same_faint_bitmap(self):
        ev={'views':[{'flags':['receipt-photo','receipt-adopted']}],
            'oruns':[{'pass':'receipt','view':0},{'pass':'receipt-rescue','view':0}]}
        result=input_views(ev)
        self.assertEqual(result['receipt_reads'],2)
        self.assertEqual(result['receipt_rescue_reads'],1)
        self.assertEqual(result['adopted_view'],'faint-psm11')
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
