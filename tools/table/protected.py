#!/usr/bin/env python3
"""Check frozen table ownership against live Models, without storing private text.

Usage: python3 tools/table/protected.py TRIPLES.json MODEL_DIRECTORY
The directory contains one DOC.json per key in TRIPLES.json. Table indices are
informational: a continuation may merge tables without changing cell ownership.
"""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

NUMBER = re.compile(r'^[\(\-–]?\$?[0-9][0-9,]*(\.[0-9]+)?\)?%?$')


def walk(node):
    yield node
    for child in node.get('children', []):
        yield from walk(child)


def triples(model):
    result = []
    for table in (n for n in walk(model['body']) if n['kind'] == 'table'):
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
        columns = {}
        for _, cells in rows:
            for col, value in cells.items():
                columns.setdefault(col, []).append(value)
        skip = {c for c, vs in columns.items() if len(vs) >= 3
                and all(re.fullmatch(r'\d{1,3}', v) for v in vs)
                and all(int(b) >= int(a) for a, b in zip(vs, vs[1:]))}
        for page, cells in rows:
            key = tuple(v for c, v in sorted(cells.items()) if c not in skip
                        and NUMBER.fullmatch(v.replace('$ ', '').replace('$', '').strip()))
            result.extend((page, key, col, value) for col, value in cells.items())
    return result


def check(frozen, actual):
    # Use a multiset: duplicate cells must not hide lost ownership occurrences.
    available = Counter(actual)
    counts = Counter()
    for cell in frozen:
        key = cell['page'], tuple(cell['row']), cell['col'], cell['value']
        if available[key]:
            available[key] -= 1
            counts['kept'] += 1
        else:
            counts['changed'] += 1
    return dict(total=len(frozen), kept=counts['kept'], changed=counts['changed'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('triples', type=Path)
    parser.add_argument('models', type=Path)
    args = parser.parse_args()
    frozen = json.loads(args.triples.read_text())
    results = {doc: check(cells, triples(json.loads((args.models / (doc + '.json')).read_text())))
               for doc, cells in frozen.items()}
    print(json.dumps(results, indent=2))
    return int(any(r['changed'] for r in results.values()))


if __name__ == '__main__':
    raise SystemExit(main())
