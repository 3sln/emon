#!/usr/bin/env python3
"""What fusion did on one document, from its evidence dump (version 2): every verdict that is not the identity or an agreeing healthy
font, with the word's text, its box and the text-layer words the group replaced. A debugging aid (P7.3), not a gate.

    explain.py DUMP.json [--all]
"""
import json, sys

d = json.load(open(sys.argv[1]))
show_all = '--all' in sys.argv
fv, fw, cg, refs = d['fv'], d['fw'], d['cg'], d['refs']
words, ow = d['words'], d['owords']

def run(g, key):
    r = g.get(key)
    if not r:
        return []
    return [refs[i] for i in range(r[0], r[0] + r[1])]

def txt(ref):
    ea, i = ref
    return (words if ea == 'words' else ow)[i]['text']

groups = {}
for i, v in enumerate(fv):
    groups.setdefault(v['group'], []).append(i)
for g, rows in groups.items():
    v = fv[rows[0]]
    if not show_all and v['rule'] in (101, 110):
        continue
    if g < 0:
        continue
    G = cg[g]
    print(v['rule'], v['source'], 'conf', v.get('conf'), 'flags', v.get('flags', 0), 'sim', G.get('simfold'),
          'T:', ' '.join(txt(r) for r in run(G, 'text')), '| O:', ' '.join(txt(r) for r in run(G, 'ocr')),
          '| H:', ' '.join(txt(r) for r in run(G, 'hidden')), G['box'])
for a in d['fa']:
    if a.get('reason') == 'ocr-ghost':
        print('ghost', a.get('rule'), a['box'], ow[a['ref'][1]]['text'] if a.get('ref') else '')
for i, g in enumerate(cg):
    if g.get('flags') and 'in-figure' in str(g.get('flags')):
        print('in-figure group', i, g['box'])
