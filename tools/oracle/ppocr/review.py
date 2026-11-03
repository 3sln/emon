#!/usr/bin/env python3
"""Build external physical-source review cards from final selected evidence."""
import argparse,collections,json,pathlib,re,sys,subprocess
sys.path.insert(0,str(pathlib.Path.home()/'.cache/emon/pylib'))
from PIL import Image,ImageDraw,ImageFont
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'tools/receipts'));import receipts as R
p=argparse.ArgumentParser(__doc__);p.add_argument('--live',type=pathlib.Path,required=True);p.add_argument('--prior',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--reference-controls',type=pathlib.Path,help='same-policy paired target runs already copied into the live controls directory');p.add_argument('--exclude-controls',default='',help='explicit review exclusions, retained in external metadata');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
controls={r['index']:r for r in json.loads(a.reference_controls.read_text())} if a.reference_controls else {}
controls.update({r['index']:r for r in json.loads((a.live/'controls.json').read_text())});cache={};model_cache={}
def evidence(i,engine):
 key=i,engine
 if key not in cache:cache[key]=json.loads((a.live/'controls'/f'{i:02}-{engine}.evidence').read_text())
 return cache[key]
def page_size(e,page):
 row=next(v for v in e['epages'] if v['page']==page);return row['width'],row['height']
def overlap(b,c):
 x=max(0,min(b[2],c[2])-max(b[0],c[0]));y=max(0,min(b[3],c[3])-max(b[1],c[1]));area=min((b[2]-b[0])*(b[3]-b[1]),(c[2]-c[0])*(c[3]-c[1]));return x*y/area if area>0 else 0

def line_words(e,page,b):
 ws=[w for w in e['words'] if w['page']==page and overlap(w['box'],b)>=.35]
 return sorted(ws,key=lambda w:(w['box'][0],w['box'][1]))
def counter(ws):return collections.Counter(R.words(' '.join(w['text'] for w in ws)))
def model_leaves(i,engine):
 key=i,engine
 if key not in model_cache:
  model=json.loads((a.live/'controls'/f'{i:02}-{engine}.json').read_text());leaves=[]
  def walk(n,at=None):
   at=n.get('at',at)
   if n.get('text') is not None:leaves.append((collections.Counter(R.words(n['text'])),at or []))
   else:
    for c in n.get('inlines',[]):walk(c,at)
   for c in n.get('children',[]):walk(c,at)
   for key in ['labels','number','label','template']:
    value=n.get(key)
    if isinstance(value,str):leaves.append((collections.Counter(R.words(value)),at or []))
    elif isinstance(value,list):
     for v in value:
      if isinstance(v,str):leaves.append((collections.Counter(R.words(v)),at or []))
  for node in [model['body']]+model.get('furniture',[]):walk(node)
  model_cache[key]=leaves
 return model_cache[key]
representation_cache={}
def represented(i,engine,w):
 key=i,engine,w["page"],tuple(w["box"]),w["text"]
 if key in representation_cache:return representation_cache[key]
 tokens=collections.Counter(R.words(w['text']))
 result=any(not(tokens-counts) and any(at[0]==w['page'] and overlap(at[1:],w['box'])>=.35 for at in ats) for counts,ats in model_leaves(i,engine))
 representation_cache[key]=result
 return result
def audit(i,page,b):
 fast=line_words(evidence(i,'tesseract'),page,b);pp=line_words(evidence(i,'ppocr'),page,b)
 fm=[w for w in fast if represented(i,'tesseract',w)];pm=[w for w in pp if represented(i,'ppocr',w)]
 before=counter(fm);after=counter(pm)
 return dict(control=i,page=page,box=b,fast=' '.join(w['text'] for w in fast),ppocr=' '.join(w['text'] for w in pp),
             fast_model=' '.join(w['text'] for w in fm),ppocr_model=' '.join(w['text'] for w in pm),
             lost=dict(before-after),gained=dict(after-before),changed=[w['text'] for w in fm]!=[w['text'] for w in pm])

prior=[]
for old in json.loads(a.prior.read_text()):
 if not old.get('counted_distinct_readable_line'):continue
 i=old['control']
 if i not in controls:continue
 e=evidence(i,'tesseract');width,height=page_size(e,old['page']);pw,ph=old['physical'];b=old['box'];b=[b[0]*width/pw,b[1]*height/ph,b[2]*width/pw,b[3]*height/ph]
 row=audit(i,old['page'],b);row.update(prior_index=old['index'],prior_review=old['review'],prior_confirmed_loss=old.get('confirmed_new_source_correct_incumbent_word_loss',False),crop=old['crop'])
 prior.append(row)
(a.out/'prior-bands.json').write_text(json.dumps(prior,indent=2))
# Additional candidates are one physical baseline, sampled round-robin by
# image control. No inferred PDF rendering or invented source word geometry.
by_control={}
bitmap_cache={}
for i,control in sorted(controls.items()):
 if i in {int(v) for v in a.exclude_controls.split(',') if v}:continue
 if control['same_model']:continue
 print('review control',i,flush=True)
 manifest=subprocess.run(['bb','tools/ppocr/bitmaps.clj',str(a.live/'controls'/f'{i:02}-ppocr.replay')],cwd=ROOT,capture_output=True,text=True,check=True)
 bitmaps={v['page']:v for v in json.loads(manifest.stdout)}
 for page,v in bitmaps.items():
  view=next((w for w in evidence(i,'ppocr')['views'] if w['page']==page and w.get('request')==v['request'] and w.get('kind') in ['decode','render']),None)
  if view is not None and not view.get('b') and not view.get('c') and view.get('a',0)>0 and view.get('d',0)>0:bitmap_cache[i,page]=(v['file'],view)
 groups=[]
 for engine in ['tesseract','ppocr']:
  gg=collections.defaultdict(list)
  for w in evidence(i,engine)['words']:gg[w['page'],w['line']].append(w)
  groups+=sorted(gg.items())
 candidates=[]
 for (page,line),ws in groups:
  if (i,page) not in bitmap_cache:continue
  b=[min(w['box'][0] for w in ws),min(w['box'][1] for w in ws),max(w['box'][2] for w in ws),max(w['box'][3] for w in ws)]
  if b[3]<=b[1] or b[2]<=b[0]:continue
  row=audit(i,page,b)
  if not row['changed'] or not row['ppocr_model']:continue
  if any(old['control']==i and old['page']==page and overlap(old['box'],b)>.3 for old in prior+candidates):continue
  row['source'],row['view']=bitmap_cache[i,page];candidates.append(row)
 by_control[i]=candidates
fresh=[]
while any(by_control.values()) and len(fresh)<70:
 for i,rs in by_control.items():
  if rs:fresh.append(rs.pop(0))
selected=[r for r in prior if r['changed']]+fresh
# First all 12 previous failure bands, then changed bands, no duplicate card.
review=[];seen=set()
for row in [r for r in prior if r['prior_confirmed_loss']]+selected:
 key=(row['control'],row['page'],tuple(row['box']))
 if key in seen:continue
 seen.add(key);row=dict(row);row['card']=len(review)
 if 'crop' not in row:
  source=Image.open(row['source']).convert('RGB');v=row['view'];b=row['box']
  def px(x):return (x*100-v.get('e',0)/65536)/(v['a']/65536)
  def py(y):return (y*100-v.get('f',0)/65536)/(v['d']/65536)
  box=(max(0,int(px(b[0]-2))),max(0,int(py(b[1]-2))),min(source.width,int(px(b[2]+2)+1)),min(source.height,int(py(b[3]+2)+1)))
  if box[2]<=box[0] or box[3]<=box[1]:continue
  crop=a.out/f"source-{row['card']:02}.png";source.crop(box).save(crop);row['crop']=str(crop)
 review.append(row)
(a.out/'cards.json').write_text(json.dumps(review,indent=2))
font=ImageFont.truetype('/System/Library/Fonts/Menlo.ttc',14)
for start in range(0,len(review),10):
 cards=[]
 for row in review[start:start+10]:
  source=Image.open(row['crop']).convert('RGB');scale=min(1,1000/source.width);source=source.resize((int(source.width*scale),max(1,int(source.height*scale))))
  header=f"#{row['card']:02} control {row['control']:02} page {row['page']} | prior-loss={row.get('prior_confirmed_loss',False)}"
  fast='FAST: '+row['fast_model'];pp='FUSE: '+row['ppocr_model'];lines=[header]
  import textwrap
  lines+=textwrap.wrap(fast,115)+textwrap.wrap(pp,115)
  card=Image.new('RGB',(1020,source.height+25+19*len(lines)), 'white');card.paste(source,(10,5));draw=ImageDraw.Draw(card)
  for j,line in enumerate(lines):draw.text((10,source.height+10+19*j),line,font=font,fill='black')
  cards.append(card)
 sheet=Image.new('RGB',(1020,sum(c.height+8 for c in cards)),'#cccccc');y=0
 for card in cards:sheet.paste(card,(0,y));y+=card.height+8
 sheet.save(a.out/f'sheet-{start//10:02}.png')
print(json.dumps(dict(prior_readable_bands=len(prior),previous_loss_bands=sum(r['prior_confirmed_loss'] for r in prior),prior_changed=sum(r['changed'] for r in prior),cards=len(review))))
