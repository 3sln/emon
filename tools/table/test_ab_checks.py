import unittest

from ab_checks import cells_of, ownership


def table(rows, page=1):
    """rows: lists of cell texts ('' is an empty cell)."""
    return {'kind': 'table', 'children': [{'kind': 'rowgroup', 'role': 'body', 'children': [
        {'kind': 'row', 'children': [{'kind': 'cell', 'text': t, 'at': [[page, 0, 0, 1, 1]]} if t else {'kind': 'cell'}
                                     for t in row]} for row in rows]}]}


def model(*tables):
    return {'body': {'kind': 'document', 'children': list(tables)}}


def frozen_of(m):
    return [dict(table=t, page=p, row=list(k), col=c, value=v) for t, p, k, c, v in cells_of(m)]


LEDGER = [['01/02', 'Coffee', '4.50', '', '95.50'],
          ['02/02', 'Salary', '', '1,000.00', '1,095.50'],
          ['03/02', 'Rent', '500.00', '', '595.50']]


class StructuralOwnership(unittest.TestCase):
    def setUp(self):
        self.base = model(table(LEDGER))
        self.frozen = frozen_of(self.base)

    def check(self, m, repairs=()):
        return ownership('X', self.frozen, m, list(repairs))

    def test_identity_keeps_everything(self):
        r = self.check(self.base)
        self.assertEqual((r['kept'], r['changed']), (12, 0))

    def test_regridding_is_not_a_change(self):
        # a phantom leading column, as an incumbent made it: every column index moves, no value moves
        shifted = model(table([[''] + row for row in LEDGER]))
        r = self.check(shifted)
        self.assertEqual((r['kept'], r['changed']), (12, 0))
        self.assertEqual(r['index_exact'], 0)  # the frozen-index check of protected.py would reject it all

    def test_fragments_linked_into_one_table_keep_ownership(self):
        base = model(table(LEDGER[:2], 1), table([[''] + LEDGER[2]], 2))  # the second fragment had a phantom column
        frozen = frozen_of(base)
        linked = model({'kind': 'table', 'children': table(LEDGER[:2], 1)['children'] + table(LEDGER[2:], 2)['children']})
        r = ownership('X', frozen, linked, [])
        self.assertEqual(r['changed'], 0)

    def test_a_value_leaving_its_column_mates_is_moved(self):
        rows = [list(r) for r in LEDGER]
        rows[2][2], rows[2][3] = '', '500.00'  # the debit drifts into the credit column
        r = self.check(model(table(rows)))
        self.assertEqual(r['moved'], 1)

    def test_two_columns_collapsed_into_one_are_merged(self):
        rows = [[r[0], r[1], r[2] or r[3], r[4]] for r in LEDGER]
        r = self.check(model(table(rows)))
        self.assertGreater(r['merged'], 0)

    def test_lost_leader_text_is_missing(self):
        rows = [list(r) for r in LEDGER]
        rows[0][1] = ''
        r = self.check(model(table(rows)))
        self.assertEqual(r['missing'], 1)

    def test_extended_stub_is_preserved(self):
        rows = [list(r) for r in LEDGER]
        rows[0][1] = 'Coffee beans'
        r = self.check(model(table(rows)))
        self.assertEqual((r.get('extended'), r['changed']), (1, 0))

    def test_a_repair_never_excuses_a_number(self):
        rows = [list(r) for r in LEDGER]
        rows[0][4] = ''
        rows[0][1] = ''
        repairs = [{'doc': 'X', 'page': 1, 'line_numbers': [], 'line_cols': []}]
        r = self.check(model(table(rows)), repairs)
        # a lost amount changes the row's numeric key, so the whole row (4 cells) loses ownership; no repair covers it
        self.assertEqual(r['changed'], 4)

    def test_a_registered_stub_repair_is_counted_as_repaired(self):
        rows = [['1', 'Alpha', '5.0', '1'], ['2', 'Beta', '6.0', '2'], ['3', 'Gamma', '7.0', '3']]
        frozen = frozen_of(model(table(rows)))
        fixed = [list(r) for r in rows]
        fixed[1][1] = 'Beta-wrapped'
        repairs = [{'doc': 'X', 'page': 1, 'line_numbers': [2], 'line_cols': [0, 3]}]
        r = ownership('X', frozen, model(table(fixed)), repairs)
        self.assertEqual((r.get('repaired'), r['changed']), (1, 0))
        r = ownership('X', frozen, model(table(fixed)), [])
        self.assertEqual(r['changed'], 1)


if __name__ == '__main__':
    unittest.main()
