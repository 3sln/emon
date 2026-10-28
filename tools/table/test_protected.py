"""Adversarial checks for the protected ownership guard (synthetic text only)."""
import unittest
from protected import check, triples


def cell(text, col=0):
    return {'kind': 'cell', 'text': text, 'at': [[1, col, 0, col+1, 1]]}


def model(rows):
    return {'body': {'kind': 'document', 'children': [
        {'kind': 'table', 'children': [{'kind': 'rowgroup', 'role': 'body', 'children': [
            {'kind': 'row', 'children': [cell(v, c) for c, v in enumerate(row)]}
            for row in rows]}]}]}}


class OwnershipGuard(unittest.TestCase):
    def test_keeps_page_row_column_and_value(self):
        actual = triples(model([['Rent', '25.00']]))
        frozen = [{'page': p, 'row': list(r), 'col': c, 'value': v} for p, r, c, v in actual]
        self.assertEqual(check(frozen, actual)['changed'], 0)
        for replacement in (model([['25.00', 'Rent']]), model([['Rent', '26.00']])):
            self.assertGreater(check(frozen, triples(replacement))['changed'], 0)
        self.assertGreater(check(frozen, [(2, r, c, v) for _, r, c, v in actual])['changed'], 0)

    def test_duplicate_cell_cannot_mask_a_missing_occurrence(self):
        frozen = [{'page': 1, 'row': ['25.00'], 'col': 0, 'value': 'Rent'}]*2
        result = check(frozen, [(1, ('25.00',), 0, 'Rent')])
        self.assertEqual(result, {'total': 2, 'kept': 1, 'changed': 1})

    def test_stub_cannot_move_to_a_different_numeric_row(self):
        frozen = [{'page': 1, 'row': ['25.00'], 'col': 0, 'value': 'Rent'}]
        self.assertEqual(check(frozen, triples(model([['Rent', '30.00']])))['changed'], 1)


if __name__ == '__main__':
    unittest.main()
