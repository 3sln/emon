#!/usr/bin/env python3
"""Choose real PDFs from corpus/ and write test/pdf/corpus.txt (DEVLOG P1.5).

    python3 kin/scripts/pdf-corpus.py            select, cross-check, write
    python3 kin/scripts/pdf-corpus.py --all      cross-check EVERY corpus PDF (no file written)

A DEVELOPMENT TOOL, run by a person; no gate runs it. For each PDF under
corpus/ (read-only; nothing is written there) it asks poppler's `pdfinfo -box`
for the page count and every page's MediaBox, CropBox and rotation, and the
generated Rust (`kin/scripts/pdf-sweep`) for what emon makes of the same file.
The two must agree, to the whole point: poppler prints boxes with two binary-
rounded decimals, so a box that is 68.315 in the file is 68.31 to poppler and
68.32 to emon. A disagreement is printed and the script exits 1 without
writing; a file emon cannot open (none in `corpus/` now: repair by scanning is
P3.1 and decryption P3.2, D099, D098) would be written with its status and is a
ratchet. `kin/scripts/pdf-compare` (babashka) is the whole-corpus cross-check.

`test/pdf/corpus.txt` rows: path (relative to test/), emon's status, pages,
digest. SELECTION is deterministic (paths ordered by SHA-1) and aims at
variety, not volume: every distinct producer and structure (classic table,
xref stream + object streams, hybrid, incremental, linearized, encrypted) in
each corpus area, a few files with a non-zero rotation or a CropBox inside their
MediaBox, every hybrid-reference file (up to 5), every file emon cannot open, at most 25 files per area, and nothing over 8 MB (at most 8 over 3 MB).
"""
import hashlib, os, re, subprocess, sys
from decimal import Decimal, ROUND_HALF_UP

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
CORPUS = os.path.join(ROOT, 'corpus')
OUT = os.path.join(ROOT, 'test', 'pdf', 'corpus.txt')
MOD = 8388593
MAXSIZE = 8 * 1024 * 1024
BIGSIZE = 3 * 1024 * 1024     # over this, at most 8 files
ALL = '--all' in sys.argv

def pts(s):
    cp = int((Decimal(s) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))
    return (cp + 50) // 100     # Python ints floor, as dt-pts does

def pdfinfo(path):
    r = subprocess.run(['pdfinfo', '-f', '1', '-l', '100000', '-box', path], capture_output=True, text=True, timeout=120)
    m = re.search(r'^Pages:\s+(\d+)', r.stdout, re.M)
    if not m: return None
    n = int(m.group(1)); h = 0; rot = 0; crop = 0
    for pg in range(1, n + 1):
        mm = re.search(r'^Page\s+%d MediaBox:\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)' % pg, r.stdout, re.M)
        cc = re.search(r'^Page\s+%d CropBox:\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)' % pg, r.stdout, re.M)
        rr = re.search(r'^Page\s+%d rot:\s+(\d+)' % pg, r.stdout, re.M)
        if not (mm and cc and rr): return None
        v = [pts(x) for x in mm.groups()] + [pts(x) for x in cc.groups()]
        for j, x in enumerate(v):
            h = (h * 131 + (x % MOD)) % MOD
            if j == 7: h = (h * 131 + int(rr.group(1))) % MOD
        if int(rr.group(1)) % 360: rot += 1
        if mm.groups() != cc.groups(): crop += 1
    prod = re.search(r'^Producer:\s*(.*)$', r.stdout, re.M)
    return dict(pages=n, digest=h, rot=rot, crop=crop, producer=(prod.group(1).strip() if prod else ''))

def sniff(path):
    d = open(path, 'rb').read()
    head = d[:1024]
    return dict(
        objstm=b'/ObjStm' in d,
        hybrid=b'/XRefStm' in d,
        incremental=d.count(b'startxref') > 1,
        linear=b'/Linearized' in head + d[:4096],
        encrypt=b'/Encrypt' in d[-4096:] or b'/Encrypt' in d[:4096],
        ver=(re.match(rb'%PDF-(\d\.\d)', head.lstrip()[:16]) or [None, b'?'])[1].decode() if re.search(rb'%PDF-', head) else '?')

def main():
    paths = []
    for dp, dn, fn in os.walk(CORPUS):
        if '/_dl' in dp: continue
        for f in fn:
            if f.lower().endswith('.pdf'): paths.append(os.path.join(dp, f))
    paths.sort(key=lambda p: hashlib.sha1(p.encode()).hexdigest())
    if not ALL:
        paths = [p for p in paths if os.path.getsize(p) <= MAXSIZE]
    # emon's answers
    sweep = subprocess.run([os.path.join(HERE, 'pdf-sweep')], input='\n'.join(paths) + '\n', capture_output=True, text=True, cwd=ROOT)
    emon = {}
    for line in sweep.stdout.splitlines():
        f = line.split('\t')
        emon[f[0]] = dict(status=f[1], pages=int(f[2]), digest=int(f[3]), warn=int(f[4]), enc=int(f[5]))
    rows = []; bad = 0; seen = set(); picks = []
    counts = {}
    def area(p):
        rel = os.path.relpath(p, CORPUS).split(os.sep)
        return '/'.join(rel[:2])
    for p in paths:
        e = emon[p]
        info = pdfinfo(p)
        if info is None:
            if ALL: print('pdfinfo cannot read', p)
            continue
        if e['status'].startswith('PANIC'):
            print('PANIC', p, e['status']); bad += 1; continue
        if e['status'] == 'ok':
            if e['pages'] != info['pages'] or e['digest'] != info['digest']:
                print('DISAGREE %s: emon %d pages digest %d, pdfinfo %d pages digest %d' % (p, e['pages'], e['digest'], info['pages'], info['digest']))
                bad += 1; continue
        if ALL: continue
        s = sniff(p)
        key = (area(p), info['producer'].split(' ')[0][:14], 'xs' if s['objstm'] else 'tab', 'hy' if s['hybrid'] else '', 'inc' if s['incremental'] else '',
               'lin' if s['linear'] else '', 'enc' if s['encrypt'] else '', e['status'])
        special = (e['status'] != 'ok' or (info['rot'] and counts.get('rot', 0) < 10) or (info['crop'] and counts.get('crop', 0) < 8)
                   or (s['hybrid'] and counts.get('hybrid', 0) < 5))
        if key in seen and not special: continue
        if os.path.getsize(p) > BIGSIZE and counts.get('big', 0) >= 8 and not special: continue
        if counts.get('area:' + area(p), 0) >= 25 and not special: continue
        if not special and counts.get('n', 0) >= 150: continue
        seen.add(key); counts['n'] = counts.get('n', 0) + 1
        counts['area:' + area(p)] = counts.get('area:' + area(p), 0) + 1
        if os.path.getsize(p) > BIGSIZE: counts['big'] = counts.get('big', 0) + 1
        if info['crop']: counts['crop'] = counts.get('crop', 0) + 1
        if info['rot']: counts['rot'] = counts.get('rot', 0) + 1
        if s['hybrid']: counts['hybrid'] = counts.get('hybrid', 0) + 1
        rel = os.path.relpath(p, os.path.join(ROOT, 'test'))
        if e['status'] == 'ok':
            rows.append((rel, 'ok', info['pages'], info['digest']))
        else:
            rows.append((rel, e['status'], '-', '-'))
    if ALL:
        print('%d files cross-checked; %d disagreements' % (len(paths), bad)); sys.exit(1 if bad else 0)
    if bad: sys.exit(1)
    rows.sort()
    with open(OUT, 'w') as fh:
        fh.write('# emon P1.5: real files from corpus/ (read-only), kin/pdfdoctest.kin. Written by kin/scripts/pdf-corpus.py.\n')
        fh.write('# path (relative to test/) <TAB> emon status <TAB> pages <TAB> digest of boxes in whole points + rotation.\n')
        fh.write('# Pages and digest are what poppler reports and emon must match; a status other than ok is a ratchet.\n')
        for r in rows: fh.write('\t'.join(str(x) for x in r) + '\n')
    st = {}
    for r in rows: st[r[1]] = st.get(r[1], 0) + 1
    print('wrote %d rows %s' % (len(rows), st))

main()
