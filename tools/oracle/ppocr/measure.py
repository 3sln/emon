#!/usr/bin/env python3
"""Run one owned child and report its CPU/peak RSS, without process inspection."""
import json,os,signal,sys
args=sys.argv[1:];timeout=0
if args[:1]==['--timeout']:
    timeout=int(args[1]);args=args[2:]
if not args:raise SystemExit('usage: measure.py [--timeout SECONDS] COMMAND [ARG ...]')
pid=os.fork()
if pid==0:
    try:os.setsid();os.execvp(args[0],args)
    except OSError as e:print(str(e),file=sys.stderr);os._exit(127)
interrupted=0
def stop_owned(signum,frame):
    global interrupted
    interrupted=124 if signum==signal.SIGALRM else 128+signum
    try:os.killpg(pid,signal.SIGKILL)
    except ProcessLookupError:pass
signal.signal(signal.SIGTERM,stop_owned);signal.signal(signal.SIGINT,stop_owned)
signal.signal(signal.SIGALRM,stop_owned)
if timeout>0:signal.alarm(timeout)
_,status,usage=os.wait4(pid,0)
signal.alarm(0)
print('emon-cost:'+json.dumps(dict(cpu_seconds=usage.ru_utime+usage.ru_stime,
    peak_rss_bytes=int(usage.ru_maxrss)*(1 if sys.platform=='darwin' else 1024))),file=sys.stderr)
code=interrupted or os.waitstatus_to_exitcode(status)
raise SystemExit(code if code>=0 else 128-code)
