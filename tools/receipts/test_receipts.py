"""Measurement-contract tests; no corpus and no OCR required."""
import unittest
from receipts import intact, overlap, words, units, score, angle, input_views, baseline_comparison, completeness_bounds, menu_items
class Contracts(unittest.TestCase):
    def test_completeness_is_an_occurrence_bound_not_an_order_claim(self):
        g={'model':{'body':{'text':'Soup Soup 12.50'}}}
        ev={'owords':[{'text':'Soup','line':0,'run':0},
                       {'text':'12','line':0,'run':0},
                       {'text':'.','line':0,'run':0},
                       {'text':'50','line':0,'run':0}],
            'words':[{'text':'Soup'}]}
        b=completeness_bounds(g,ev,[('company','Soup Soup')],[['Soup','12.50']])
        self.assertEqual(b['raw']['words_present'],1)
        self.assertEqual(b['raw']['entity_company_complete'],0)
        self.assertEqual(b['raw']['items_complete'],0)
        self.assertEqual(b['raw']['numeric_present'],0)
        self.assertEqual(b['raw']['numeric_join_oracle'],1)
        self.assertEqual(b['raw']['items_numeric_join_complete'],1)
        self.assertEqual(b['selected']['words_present'],1)
        # Entity annotations need not be a subset of the body transcription.
        g={'model':{'body':{'text':'Soup'}}}
        b=completeness_bounds(g,ev,[('total','12.50')],[])
        self.assertEqual(b['raw']['entity_total_numeric_join_complete'],1)
    def test_join_oracle_never_bridges_reads_lines_or_partial_tokens(self):
        g={'model':{'body':{'text':'12.50'}}}
        for tail in ({'line':1,'run':0},{'line':0,'run':1},{'line':-1,'run':0},{'line':0}):
            ev={'owords':[{'text':'12.','line':0,'run':0},dict(text='50',**tail)]}
            self.assertEqual(completeness_bounds(g,ev,[],[])['raw']['numeric_join_oracle'],0)
        ev={'owords':[{'text':'x12.50','line':0,'run':0}]}
        self.assertEqual(completeness_bounds(g,ev,[],[])['raw']['numeric_join_oracle'],0)
    def test_join_oracle_counts_intervals_and_preserves_characters(self):
        g={'model':{'body':{'text':'12.50 12.50'}}}
        ev={'owords':[{'text':'12','line':0,'run':0},
                       {'text':'.','line':0,'run':0},
                       {'text':'50','line':0,'run':0}]}
        self.assertEqual(completeness_bounds(g,ev,[],[])['raw']['numeric_join_oracle'],1)
        for tokens in (('12','50'),('12',',','50'),('-','12','.','50')):
            ev={'owords':[{'text':t,'line':0,'run':0} for t in tokens]}
            target='-12.50' if tokens[0]=='-' else '12.50'
            g={'model':{'body':{'text':target}}}
            self.assertEqual(completeness_bounds(g,ev,[],[])['raw']['numeric_join_oracle'],int(tokens[0]=='-'))
    def test_native_item_groups_and_subgroups_stay_separate(self):
        def line(cat,value,group,sub=0):
            return {'category':cat,'group_id':group,'sub_group_id':sub,
                    'words':[{'text':value,'row_id':0,
                              'quad':{**{'x'+str(i):0 for i in range(1,5)},
                                      **{'y'+str(i):0 for i in range(1,5)}}}]}
        gt={'valid_line':[line('menu.nm','Soup',1),line('menu.price','8.00',2),
                          line('menu.nm','Bread',2),line('menu.price','9.00',1),
                          line('menu.sub.nm','Extra',1,1),line('menu.sub.price','1.00',1,1),
                          line('menu.sub.nm','Other',1,2)]}
        self.assertEqual(menu_items(gt),[['Soup','9.00'],['8.00','Bread']])
        self.assertEqual(menu_items(gt,'menu.sub.'),[['Extra','1.00']])
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
