#!/usr/bin/env python3
"""Rescore the frozen source-reviewed F08 numeric slots against emitted words."""
import argparse,json,pathlib,re
p=argparse.ArgumentParser(__doc__);p.add_argument('--model',type=pathlib.Path,required=True);p.add_argument('--evidence',type=pathlib.Path,required=True);p.add_argument('--slots',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
e=json.loads(a.evidence.read_text());model=json.loads(a.model.read_text());slots=json.loads(a.slots.read_text());rows=[]
def overlap(b,c):
 w=max(0,min(b[2],c[2])-max(b[0],c[0]));h=max(0,min(b[3],c[3])-max(b[1],c[1]));area=min((b[2]-b[0])*(b[3]-b[1]),(c[2]-c[0])*(c[3]-c[1]));return w*h/area if area>0 else 0
def norm(s):return re.sub(r'^[^\d]+|[^\d]+$','',s).replace(',','')
model_tokens=[]
def collect(n,at=None):
 at=n.get('at',at)
 if n.get('text') is not None:
  for token in n['text'].split():
   if any(c.isdigit() for c in token):model_tokens.append(dict(text=token,at=at or [],used=False))
 else:
  for child in n.get('inlines',[]):collect(child,at)
 for child in n.get('children',[]):collect(child,at)
for root in [model['body']]+model.get('furniture',[]):collect(root)
for slot in slots['rows']:
 candidates=[w for w in e['words'] if any(c.isdigit() for c in w['text']) and overlap(w['box'],slot['ppocr_box'])>=.5]
 # A source slot is a spatial occurrence, not any repeated token on the page.
 candidates.sort(key=lambda w:(abs((w['box'][0]+w['box'][2])-(slot['ppocr_box'][0]+slot['ppocr_box'][2])), -overlap(w['box'],slot['ppocr_box'])))
 selected=candidates[0]['text'] if candidates else ''
 normalized=re.sub(r'^[^\d]+|[^\d]+$','',selected).replace(',','')
 matches=[(j,t) for j,t in enumerate(model_tokens) if not t['used'] and norm(t['text'])==norm(slot['gold']) and ('(' in t['text'])==('(' in slot['gold'])
          and any(r[0]==1 and overlap(r[1:],slot['ppocr_box'])>=.25 for r in t['at'])]
 represented_ok=bool(matches)
 if matches:model_tokens[matches[0][0]]['used']=True
 rows.append(dict(represented=represented_ok,slot=slot['slot'],gold=slot['gold'],selected=selected,box=candidates[0]['box'] if candidates else None,correct=represented_ok and normalized==re.sub(r'^[^\d]+|[^\d]+$','',slot['gold']).replace(',','') and ('(' in selected)==('(' in slot['gold'])))
a.out.write_text(json.dumps(dict(correct=sum(r['correct'] for r in rows),total=len(rows),rows=rows),indent=2))
print(sum(r['correct'] for r in rows),'/',len(rows))
