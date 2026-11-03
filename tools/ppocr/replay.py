#!/usr/bin/env python3
"""Replay measured bundles without native inference; compare complete output bytes."""
import argparse,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(__doc__);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--bin',type=pathlib.Path,required=True);p.add_argument('--only',choices=['all','receipts','controls'],default='all');a=p.parse_args()
paths=[s for s in (ROOT/'test/controls/sweep3.txt').read_text().splitlines() if s and not s.startswith('#')];rows=[]
for directory in ['receipts-ppocr','controls']:
 if a.only=='receipts' and directory=='controls':continue
 if a.only=='controls' and directory!='controls':continue
 for recording in sorted((a.out/directory).glob('*.replay')):
  if not recording.is_dir() or recording.name.startswith('._'):continue
  if directory=='controls' and '-ppocr' not in recording.name:continue
  if directory=='controls':source=ROOT/paths[int(recording.name[:2])];flags=['--assets=figures','--ocr=auto']
  else:
   ident=recording.name[:-7];dataset='sroie' if ident.startswith('sroie') else 'cord'
   gold=json.loads((ROOT/'corpus/_gold'/dataset/(ident+'.gold.json')).read_text())
   source=ROOT/'corpus'/gold['provenance']['variants']['image'];flags=[]
  result=subprocess.run([str(a.bin.resolve()),'--ocr-engine=ppocr','--out=bundle','--replay='+str(recording),*flags,str(source)],cwd=ROOT,capture_output=True)
  expected=recording.with_suffix('.bundle').read_bytes();equal=result.returncode==0 and result.stdout==expected
  rows.append(dict(recording=str(recording),exit=result.returncode,equal=equal))
  if not equal:(a.out/'replay-failure.err').write_bytes(result.stderr)
  (a.out/'replay-summary.json').write_text(json.dumps(rows,indent=2));print(recording.name,equal,flush=True)
if not rows or not all(r['equal'] for r in rows):raise SystemExit(1)
