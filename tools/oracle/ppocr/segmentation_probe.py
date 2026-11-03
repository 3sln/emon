#!/usr/bin/env python3
"""Non-gate upper bound for literal, source-recorded boundary-only agreements."""
import argparse,collections,json,pathlib,re,sys
ROOT=pathlib.Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'tools/receipts'));import receipts as R
p=argparse.ArgumentParser(__doc__);p.add_argument('--fast',type=pathlib.Path,required=True);p.add_argument('--pp',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
def sections(p):return {k:json.loads(v) for k,v in re.findall(r'== (json|evidence)\n(.*?)(?=\n== |\Z)',p.read_text(),re.S)}
def overlap(b,c):
 area=min((b[2]-b[0])*(b[3]-b[1]),(c[2]-c[0])*(c[3]-c[1]));return max(0,min(b[2],c[2])-max(b[0],c[0]))*max(0,min(b[3],c[3])-max(b[1],c[1]))/area if area>0 else 0
rows=[];totals=collections.Counter()
for bundle in sorted(a.pp.glob('sroie-*.bundle')):
 if bundle.name.startswith('._'):continue
 ident=bundle.stem;f=sections(a.fast/bundle.name);q=sections(bundle);e=q['evidence'];raw=e['owords'];pp=[w for run in e['oruns'] if run.get('request',0)%256==23 for w in raw[run['words'][0]:sum(run['words'])]]
 gold=json.loads((ROOT/'corpus/_gold/sroie'/(ident+'.gold.json')).read_text());gc=collections.Counter(R.words(R.text(gold['model']['body'])))
 candidates=[]
 for w in f['evidence']['words']:
  parts=sorted([v for v in pp if v['page']==w['page'] and overlap(v['box'],w['box'])>=.5],key=lambda v:v['box'][0])
  if not 2<=len(parts)<=4 or len({v['line'] for v in parts})!=1:continue
  if ''.join(v['text'] for v in parts).casefold()!=w['text'].casefold():continue
  totals['literal_split']+=1
  mixed=any(c.isalpha() for c in w['text']) and any(c.isdigit() for c in w['text'])
  totals['mixed_literal_split']+=mixed
  h=min(v['box'][3]-v['box'][1] for v in parts)
  gap=all(parts[j+1]['box'][0]-parts[j]['box'][2]>=h/4 for j in range(len(parts)-1))
  totals['mixed_gap_split']+=mixed and gap
  candidates.append(dict(incumbent=w['text'],parts=[v['text'] for v in parts],mixed=mixed,gap=gap,conf=min(v['conf'] for v in parts),source_gold_has_incumbent=bool(gc[w['text'].casefold()])))
 rows.append(dict(id=ident,candidates=candidates))
a.out.write_text(json.dumps(dict(totals=dict(totals),rows=rows),indent=2));print(json.dumps(dict(totals)))
