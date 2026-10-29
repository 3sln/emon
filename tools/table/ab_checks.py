#!/usr/bin/env python3
"""The two document-level checks of `bb tables-ab` that compare Models with frozen evidence (TR-design).

    python3 tools/table/ab_checks.py ownership TRIPLES.json REPAIRS.json MODEL_DIR
    python3 tools/table/ab_checks.py words ITEMS.json DIR_A [DIR_B]

OWNERSHIP is the structural form of tools/table/protected.py. A frozen triple is (table, page, row key, column, value),
where the row key is the row's numeric values outside line-number columns. protected.py demands the SAME column index;
that froze an incumbent's grid (TF1's E09 rejection: 23 cells demanded zero-based column 8 of a correct eight-column
grid). Here a triple is compared on STRUCTURE AND VALUES:
  - the value must sit, on the same page, in a row with the same numeric key (exact text: kept; a cell whose text
    contains it at a token boundary: extended);
  - its baseline column-mates (the triples that shared its table and column) that land in the same new table must
    share its new column (else: moved);
  - two baseline columns of one table that land in one new table must stay distinct (else: merged) and keep their
    left-to-right order (else: reordered).
Column indices themselves are never compared, so a re-gridded, re-linked or merged table keeps its ownership as long
as every value keeps its row, its column-mates and its column order. A change matching a pre-registered repair
(REPAIRS.json) is counted as `repaired`, never silently as kept.

WORDS is the live word control (words.py's accounting): per control, the represented words of A, and with B the
words lost and gained (zero and zero is the requirement).
"""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import words as W  # noqa: E402

NUMBER = re.compile(r'^[\(\-–]?\$?[0-9][0-9,]*(\.[0-9]+)?\)?%?$')


def walk(node):
    yield node
    for child in node.get('children', []):
        yield from walk(child)


def is_number(v):
    return bool(NUMBER.fullmatch(v.replace('$ ', '').replace('$', '').strip()))


def line_cols(rows):
    columns = defaultdict(list)
    for _, cells in rows:
        for col, value in cells.items():
            columns[col].append(value)
    return {c for c, vs in columns.items() if len(vs) >= 3 and all(re.fullmatch(r'\d{1,3}', v) for v in vs)
            and all(int(b) >= int(a) for a, b in zip(vs, vs[1:]))}


def cells_of(model):
    """[(table, page, key, col, value)] of every body cell, the key as protected.py computes it."""
    out = []
    for t, table in enumerate(n for n in walk(model['body']) if n['kind'] == 'table'):
        rows = []
        for group in table['children']:
            role = group.get('role', 'body')
            source = group['children'] if group['kind'] == 'rowgroup' else [group]
            for row in source:
                if role != 'body' or row['kind'] != 'row':
                    continue
                col, page, cells = 0, None, {}
                for cell in row['children']:
                    if cell.get('text'):
                        cells[col] = cell['text']
                    if cell.get('at'):
                        page = cell['at'][0][0]
                    col += cell.get('colspan', 1)
                rows.append((page, cells))
        skip = line_cols(rows)
        for page, cells in rows:
            key = tuple(v for c, v in sorted(cells.items()) if c not in skip and is_number(v))
            out.extend((t, page, key, col, value) for col, value in cells.items())
    return out


def contains(text, value):
    return text != value and re.search(r'(^|\s)' + re.escape(value) + r'(\s|$)', text) is not None


def repaired(doc, cell, frozen_doc, repairs):
    """Does a changed frozen cell match a pre-registered repair? A repair never covers a numeric value of a row key."""
    for r in repairs:
        if r['doc'] != doc or r['page'] != cell['page']:
            continue
        nums = set(str(n) for n in r['line_numbers'])
        if is_number(cell['value']) and cell['value'] in cell['row'] and cell['value'] not in nums:
            continue
        mates = [x for x in frozen_doc if x['table'] == cell['table'] and x['page'] == cell['page'] and x['row'] == cell['row']]
        lines = {x['value'] for x in mates if x['col'] in r['line_cols']}
        if (not cell['row'] or lines & nums) and (cell['value'] in nums or not is_number(cell['value'])):
            return True
    return False


def ownership(doc, frozen, model, repairs):
    current = cells_of(model)
    index = defaultdict(list)
    for i, (t, page, key, col, value) in enumerate(current):
        index[(page, key)].append(i)
    used = set()
    match = {}
    for exact in (True, False):
        for k, f in enumerate(frozen):
            if k in match:
                continue
            for i in index[(f['page'], tuple(f['row']))]:
                if i in used:
                    continue
                v = current[i][4]
                if (v == f['value']) if exact else contains(v, f['value']):
                    match[k] = (i, 'kept' if exact else 'extended')
                    used.add(i)
                    break
    status = {k: s for k, (i, s) in match.items()}
    # column structure: baseline groups (table, col) -> new (table, col) per matched member
    groups = defaultdict(list)
    for k, f in enumerate(frozen):
        if k in match:
            i = match[k][0]
            groups[(f['table'], f['col'])].append((k, current[i][0], current[i][3]))
    modal = {}
    for g, members in groups.items():
        by_table = defaultdict(Counter)
        for k, nt, nc in members:
            by_table[nt][nc] += 1
        for nt, cols in by_table.items():
            best = sorted(cols.items(), key=lambda x: (-x[1], x[0]))[0][0]
            modal[(g, nt)] = best
            for k, t2, nc in members:
                if t2 == nt and nc != best:
                    status[k] = 'moved'
    by_new = defaultdict(list)
    for (g, nt), nc in modal.items():
        by_new[(g[0], nt)].append((g[1], nc, g))
    for _, cols in by_new.items():
        cols.sort()
        seen = {}
        for old, new, g in cols:
            if new in seen:
                for k, t2, nc in groups[g]:
                    if status.get(k) in ('kept', 'extended'):
                        status[k] = 'merged'
            seen[new] = g
        news = [new for _, new, _ in cols]
        for (old, new, g), prev in zip(cols[1:], news):
            if new < prev:
                for k, t2, nc in groups[g]:
                    if status.get(k) in ('kept', 'extended'):
                        status[k] = 'reordered'
    counts = Counter()
    for k, f in enumerate(frozen):
        s = status.get(k, 'missing')
        if s not in ('kept', 'extended') and repaired(doc, f, frozen, repairs):
            s = 'repaired'
        counts[s] += 1
    counts['total'] = len(frozen)
    counts['changed'] = sum(counts[s] for s in ('missing', 'moved', 'merged', 'reordered'))
    legacy = Counter((page, key, col, value) for _, page, key, col, value in current)
    exact = 0
    for f in frozen:
        key = (f['page'], tuple(f['row']), f['col'], f['value'])
        if legacy[key]:
            legacy[key] -= 1
            exact += 1
    counts['index_exact'] = exact
    return dict(counts)


def main():
    cmd = sys.argv[1]
    if cmd == 'ownership':
        frozen = json.loads(Path(sys.argv[2]).read_text())
        repairs = json.loads(Path(sys.argv[3]).read_text())['repairs']
        folder = Path(sys.argv[4])
        out = {}
        for doc, cells in sorted(frozen.items()):
            f = folder / (doc + '.json')
            out[doc] = ownership(doc, cells, json.loads(f.read_text()), repairs) if f.exists() else {'total': len(cells), 'changed': len(cells), 'missing': len(cells)}
        print(json.dumps(out, sort_keys=True))
    elif cmd == 'words':
        items = json.loads(Path(sys.argv[2]).read_text())
        a = Path(sys.argv[3])
        b = Path(sys.argv[4]) if len(sys.argv) > 4 else None
        out = {}
        for item in items:
            name = item['id'].replace('/', '__')
            fa = a / (name + '.json')
            if not fa.exists():
                out[item['id']] = {'failed': True}
                continue
            ma = json.loads(fa.read_text())
            # A's own repeated-header entries are its established serialization (words.compare trusts them the same way)
            own = [(d.get('page', d.get('at', [0])[0]), d['text']) for d in ma.get('dropped', []) if d.get('reason') == 'duplicate-header-row']
            row = {'words': sum(W.accounted(ma, own)[0].values())}
            if b is not None:
                fb = b / (name + '.json')
                if fb.exists():
                    row.update(W.compare(ma, json.loads(fb.read_text())))
                else:
                    row['failed'] = True
            out[item['id']] = row
        print(json.dumps(out, sort_keys=True))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
