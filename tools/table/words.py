#!/usr/bin/env python3
"""Compare represented words, accounting only for verified repeated table headers."""
import argparse
import json
import unicodedata
from collections import Counter
from pathlib import Path


def walk(node):
    yield node
    for child in node.get('children', []):
        yield from walk(child)


def text(node):
    value = node.get('text')
    body = value if value is not None else ' '.join(text(c) for c in node.get('children', []))
    extra = []
    for key in ['labels', 'number', 'label']:
        value = node.get(key)
        if key == 'label' and any(c.get('kind') == 'caption' and c.get('label') == value
                                  for c in node.get('children', [])):
            continue
        if isinstance(value, str):
            extra.append(value)
        elif isinstance(value, list):
            extra.extend(x for x in value if isinstance(x, str))
    return ' '.join([body] + extra)


def tokens(value):
    return Counter(unicodedata.normalize('NFC', value).split())


def accounted(model, trusted=()):
    trusted = Counter(trusted)
    words = tokens(' '.join(text(n) for n in [model.get('body', {})] + model.get('furniture', [])))
    headers = []
    for table in (n for n in walk(model['body']) if n['kind'] == 'table'):
        table_pages = {a[0] for n in walk(table) for a in n.get('at', [])}
        for group in table.get('children', []):
            if group.get('role') == 'head':
                for row in group.get('children', []):
                    pages = [a[0] for n in walk(row) for a in n.get('at', [])]
                    if pages and table_pages and min(pages) == min(table_pages):
                        headers.append((' '.join(text(c).strip() for c in row.get('children', [])
                                                 if text(c).strip()), min(pages), table_pages))
    count = 0
    for item in model.get('dropped', []):
        if item.get('reason') != 'duplicate-header-row':
            continue
        value = item['text']
        page = item.get('page', item.get('at', [0])[0])
        key = (page, value)
        existing = trusted[key] > 0
        if existing:
            trusted[key] -= 1
        if not existing and not any(value == header and first < page and page in pages
                                   for header, first, pages in headers):
            raise ValueError('duplicate-header-row is not an exact first-fragment header repeat')
        addition = tokens(value)
        words.update(addition)
        count += sum(addition.values())
    return words, count


def compare(before, after):
    # Existing drops retain the established baseline convention (some older
    # widened fragments serialize cell text in a different order). Only newly
    # moved occurrences need proof against the first fragment's header.
    trusted = [(d.get('page', d.get('at', [0])[0]), d['text'])
               for d in before.get('dropped', []) if d.get('reason') == 'duplicate-header-row']
    old, old_count = accounted(before, trusted)
    new, new_count = accounted(after, trusted)
    return dict(lost=sum((old-new).values()), gained=sum((new-old).values()),
                duplicate_header_words=new_count, newly_accounted_header_words=new_count-old_count)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('before', type=Path)
    parser.add_argument('after', type=Path)
    args = parser.parse_args()
    result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
    print(json.dumps(result, indent=2))
    raise SystemExit(bool(result['lost'] or result['gained']))
