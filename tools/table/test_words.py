import unittest
from words import compare


def model():
    return {'body': {'kind': 'document', 'children': [
        {'kind': 'table', 'at': [[1, 0, 0, 10, 10], [2, 0, 0, 10, 10]], 'children': [
            {'kind': 'rowgroup', 'role': 'head', 'children': [
                {'kind': 'row', 'at': [[1, 0, 0, 10, 1]], 'children': [
                    {'kind': 'cell', 'text': 'Date Balance'}]}]}]}]}, 'dropped': []}


class HeaderAccounting(unittest.TestCase):
    def test_exact_repeat_is_accounted(self):
        before = model()
        before['body']['children'].append({'kind': 'paragraph', 'text': 'Date Balance'})
        after = model()
        after['dropped'] = [{'reason': 'duplicate-header-row', 'page': 2, 'text': 'Date Balance'}]
        self.assertEqual(compare(before, after), dict(lost=0, gained=0,
                         duplicate_header_words=2, newly_accounted_header_words=2))

    def test_changed_header_or_foreign_page_is_rejected(self):
        for page, value in [(2, 'Date Amount'), (3, 'Date Balance'), (1, 'Date Balance')]:
            after = model()
            after['dropped'] = [{'reason': 'duplicate-header-row', 'page': page, 'text': value}]
            with self.assertRaises(ValueError):
                compare(model(), after)

    def test_other_drops_and_gains_still_fail(self):
        before = model()
        before['body']['children'].append({'kind': 'paragraph', 'text': 'transaction'})
        after = model()
        after['dropped'] = [{'reason': 'running-title', 'page': 2, 'text': 'transaction'}]
        self.assertEqual(compare(before, after)['lost'], 1)
        self.assertEqual(compare(after, before)['gained'], 1)

    def test_existing_ledger_is_preserved_but_new_occurrences_need_proof(self):
        before = model()
        before['dropped'] = [{'reason': 'duplicate-header-row', 'page': 2,
                              'text': 'legacy reordered header'}]
        self.assertEqual(compare(before, before)['lost'], 0)
        after = model()
        after['dropped'] = before['dropped'] * 2
        with self.assertRaises(ValueError):
            compare(before, after)

    def test_duplicate_occurrences_do_not_mask_loss(self):
        before = model()
        before['body']['children'].append({'kind': 'paragraph', 'text': 'Date Balance Date Balance'})
        after = model()
        after['dropped'] = [{'reason': 'duplicate-header-row', 'page': 2, 'text': 'Date Balance'}]
        self.assertEqual(compare(before, after)['lost'], 2)
