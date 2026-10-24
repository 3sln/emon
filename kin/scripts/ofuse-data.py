#!/usr/bin/env python3
"""Check or regenerate RC5's Unicode letter/number ranges from vendored UCD."""
import argparse
from pathlib import Path


def render(ucd):
    ranges = []
    pending = None
    for line in ucd.splitlines():
        fields = line.split(';')
        cp = int(fields[0], 16)
        if fields[1].endswith(', First>'):
            pending = cp
            continue
        start = pending if fields[1].endswith(', Last>') else cp
        pending = None
        if fields[2][0] not in 'LN':
            continue
        if ranges and ranges[-1][1] + 1 == start:
            ranges[-1][1] = cp
        else:
            ranges.append([start, cp])
    return '\n' + ''.join(f'         {a} {b}\n' for a, b in ranges) + '        '


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = root / 'kin/ofuse.kin'
    old = source.read_text()
    table_start = old.index(':data [', old.index('(defdata of-alnums')) + len(':data [')
    table_end = old.index(']', table_start)
    data = render((root / 'test/normalize/UnicodeData.txt').read_text())
    if args.check:
        if old[table_start:table_end] != data:
            raise SystemExit('FAIL ofuse-data: Unicode letter/number ranges have drifted')
        print('  ok   ofuse-data: letter/number ranges match vendored UnicodeData.txt')
    else:
        source.write_text(old[:table_start] + data + old[table_end:])


if __name__ == '__main__':
    main()
