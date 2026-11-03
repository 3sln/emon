#!/usr/bin/env python3
"""Audit per-document loss counters against native source annotation lines."""
import argparse,collections,json,pathlib,sys
ROOT=pathlib.Path.cwd();sys.path.insert(0,str(ROOT/'tools/receipts'));import receipts as R
p=argparse.ArgumentParser(__doc__);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
O=a.out;losses=json.loads((O/'receipt-losses.json').read_text());out={}
for ds,data in losses.items():
 rows=[]
 for row in data['rows']:
  g=json.loads((ROOT/'corpus/_gold'/ds/(row['id']+'.gold.json')).read_text());image=ROOT/'corpus'/g['provenance']['variants']['image'];native=json.loads(image.with_suffix('.json').read_text())
  if ds=='sroie':lines=native['words']
  else:
   gt=native['ground_truth'];gt=json.loads(gt) if isinstance(gt,str) else gt
   lines=[' '.join(w['text'] for w in line['words']) for line in gt['valid_line']]
  counters=[collections.Counter(R.words(s)) for s in lines];native_words=sum(counters,collections.Counter());checks=[]
  for token,count in row['lost_gold'].items():
   checks.append(dict(token=token,lost_count=count,native_annotation_count=native_words[token],annotated_source_lines=[i for i,c in enumerate(counters) if c[token]],supported=native_words[token]>=count))
  rows.append(dict(id=row['id'],net_matched_delta=row['new_matched']-row['old_matched'],lost=sum(row['lost_gold'].values()),checks=checks))
 summary=dict(documents=len(rows),net_worse=sum(r['net_matched_delta']<0 for r in rows),net_equal=sum(r['net_matched_delta']==0 for r in rows),net_better=sum(r['net_matched_delta']>0 for r in rows),lost_tokens=sum(r['lost'] for r in rows),native_annotation_supported_tokens=sum(c['lost_count'] for r in rows for c in r['checks'] if c['supported']),note='Counter loss checked against native image annotations and associated source-line indices. This is not an independent physical-source rejudgment or exact duplicate-token spatial alignment.',rows=rows)
 out[ds]=summary;print(ds,{k:v for k,v in summary.items() if k not in ['rows','note']})
(O/'receipt-native-loss-audit.json').write_text(json.dumps(out,indent=2))
