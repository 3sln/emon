#!/usr/bin/env python3
"""Serial OP1 option-off checks against a review snapshot; never commit/stage the real index."""
import argparse,hashlib,json,os,pathlib,shutil,subprocess,time
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(__doc__);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--skip-unit',action='store_true',help='reuse separately recorded four-host unit results');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
work=ROOT/'.cache/op1-git-check';work.mkdir(parents=True,exist_ok=True);objects=work/'objects';objects.mkdir(exist_ok=True)
env=dict(os.environ,EMON_CS_SHARED_COMPILATION='false',TMPDIR=str(ROOT/'.cache/op1-tmp'))
env.setdefault('npm_config_cache',str(ROOT/'.cache/op1-npm'))
for key in ['EMON_RS_PPOCR','EMON_RS_LIVE','EMON_RS_PDFIUM','EMON_CLI','EMON_NATIVE']:env.pop(key,None)
common=subprocess.check_output(['git','rev-parse','--git-common-dir'],cwd=ROOT,text=True).strip();common=(ROOT/common).resolve()
real_index=subprocess.check_output(['git','rev-parse','--git-path','index'],cwd=ROOT,text=True).strip();real_index=(ROOT/real_index).resolve()
def fingerprint(p):return hashlib.sha256(p.read_bytes()).hexdigest() if p.exists() else None
before=fingerprint(real_index)
view=dict(GIT_INDEX_FILE=str(work/'index'),GIT_OBJECT_DIRECTORY=str(objects),GIT_ALTERNATE_OBJECT_DIRECTORIES=str(common/'objects'))
for key in view:env.pop(key,None)
real_git=shutil.which('git');assert real_git
snapshot_env=dict(env,**view)
subprocess.run([real_git,'-c','core.fsmonitor=false','read-tree','HEAD'],cwd=ROOT,env=snapshot_env,check=True)
subprocess.run([real_git,'-c','core.fsmonitor=false','add','-A'],cwd=ROOT,env=snapshot_env,check=True)
# Self-tests create their own repositories, including below TMPDIR in this
# worktree. Global GIT_INDEX_FILE would corrupt the snapshot when they add a
# fixture. Apply the view only to this actual Git top-level, respecting -C.
wrapper_dir=work/'bin';wrapper_dir.mkdir(exist_ok=True)
wrapper=wrapper_dir/'git'
config=json.dumps(dict(root=str(ROOT.resolve()),git=real_git,view=view))
wrapper.write_text('''#!/usr/bin/env python3
import json,os,pathlib,subprocess,sys
config=json.loads(CONFIG)
args=sys.argv[1:];prefix=[];i=0
while i<len(args) and args[i].startswith('-'):
    arg=args[i];prefix.append(arg);i+=1
    if arg in ['-C','-c','--git-dir','--work-tree','--namespace','--config-env'] and i<len(args):
        prefix.append(args[i]);i+=1
command=args[i] if i<len(args) else ''
env=dict(os.environ)
if command not in ['init','clone'] and not any(k in env for k in config['view']):
    probe=subprocess.run([config['git'],*prefix,'rev-parse','--show-toplevel'],env=env,capture_output=True,text=True)
    if probe.returncode==0 and pathlib.Path(probe.stdout.strip()).resolve()==pathlib.Path(config['root']):
        env.update(config['view'])
os.execve(config['git'],[config['git'],*args],env)
'''.replace('CONFIG',repr(config)))
wrapper.chmod(0o755)
real_bb=shutil.which('bb');assert real_bb
bb_wrapper=wrapper_dir/'bb'
bb_wrapper.write_text('#!/usr/bin/env python3\nimport os,sys\nos.execv('+repr(real_bb)+',['+repr(real_bb)+','+repr('-Djava.io.tmpdir='+env['TMPDIR'])+',*sys.argv[1:]])\n')
bb_wrapper.chmod(0o755);env['PATH']=str(wrapper_dir)+os.pathsep+env['PATH']
commands=[['bb','gates','drift'],['bb','ci','core'],['bb','rederive','--check'],['bb','rederive'],['bb','accuracy'],['bb','samples']]
replays=['identity','repeat','plan','ocr','render','pdfium-wasm','align','fusion','gfuse','artifacts','seals','sealpx','sigs','retarget','asset']
commands.extend([['bb',name] for name in replays]);commands.append(['bb','embedders'])
if not a.skip_unit:commands.append(['bb','unit'])
commands.append(['bb','package','--check'])
rows=[]
try:
 for i,command in enumerate(commands):
    if shutil.disk_usage('/System/Volumes/Data').free<5*1024**3:raise RuntimeError('internal disk below 5 GiB')
    label='-'.join(command[1:]);start=time.monotonic();log=a.out/(label+'.log')
    step_env=dict(env)
    # Package consumers supply their own local feed containing the just-built
    # Emon package. A global offline source must not hide that feed.
    if command[:2]==['bb','package']:step_env.pop('RestoreSources',None)
    with log.open('w') as output:run=subprocess.run(command,cwd=ROOT,env=step_env,stdout=output,stderr=subprocess.STDOUT)
    row=dict(command=command,exit=run.returncode,seconds=time.monotonic()-start,log=str(log));rows.append(row)
    if command==['bb','rederive']:
        # Compare the complete worktree against the temporary pre-gate snapshot.
        check=subprocess.run(['git','diff','--exit-code'],cwd=ROOT,env=env,capture_output=True,text=True)
        row['no_worktree_diff']=check.returncode==0
        if check.returncode:(a.out/'rederive.diff').write_text(check.stdout)
    (a.out/'summary.json').write_text(json.dumps(rows,indent=2));print(label,run.returncode,flush=True)
    if run.returncode or not row.get('no_worktree_diff',True):break
finally:
 assert before==fingerprint(real_index),'real index changed'
 (a.out/'index-check.json').write_text(json.dumps(dict(real_index_unchanged=True,temporary_index=str(work/'index')),indent=2))
if not all(r['exit']==0 and r.get('no_worktree_diff',True) for r in rows):raise SystemExit(1)
