#!/usr/bin/env python3
"""Nine actual Ocr-port readings per recorded page bitmap, serialized and compared."""
import argparse,hashlib,json,pathlib,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(__doc__);p.add_argument('--manifest',type=pathlib.Path,required=True);p.add_argument('--bin',type=pathlib.Path,required=True);p.add_argument('--out',type=pathlib.Path,required=True);a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=True);rows=[]
for item in json.loads(a.manifest.read_text()):
    expected=None;result=dict(id=item['id'],fresh_processes=0,readings=0,equal=True)
    for process in range(3):
        run=subprocess.run([str(a.bin.resolve()),item['file']],cwd=ROOT,capture_output=True,timeout=600)
        result['fresh_processes']+=1
        if run.returncode:
            result.update(equal=False,error=run.stderr.decode(errors='replace')[-1000:]);continue
        answers=run.stdout.splitlines();result['readings']+=len(answers)
        if len(answers)!=3:result['equal']=False
        for answer in answers:
            if expected is None:expected=answer
            if answer!=expected:
                result['equal']=False
                (a.out/('mismatch-'+str(len(rows))+'-'+str(process)+'.txt')).write_bytes(run.stdout)
        if expected is not None:result['sha256']=hashlib.sha256(expected).hexdigest()
    rows.append(result);(a.out/'summary.json').write_text(json.dumps(rows,indent=2));print(item['id'],result['equal'],flush=True)
if not rows or not all(r['equal'] and r['fresh_processes']==3 and r['readings']==9 for r in rows):raise SystemExit(1)
print('all',len(rows),'page bitmaps have nine byte-identical port answers')
