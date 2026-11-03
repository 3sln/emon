#!/usr/bin/env python3
"""Run one owned child and report its CPU/peak RSS, without process inspection."""
import json,os,sys
if len(sys.argv)<2:raise SystemExit('usage: measure.py COMMAND [ARG ...]')
pid=os.fork()
if pid==0:
    try:os.execvp(sys.argv[1],sys.argv[1:])
    except OSError as e:print(str(e),file=sys.stderr);os._exit(127)
_,status,usage=os.wait4(pid,0)
print('emon-cost:'+json.dumps(dict(cpu_seconds=usage.ru_utime+usage.ru_stime,
    peak_rss_bytes=int(usage.ru_maxrss)*(1 if sys.platform=='darwin' else 1024))),file=sys.stderr)
code=os.waitstatus_to_exitcode(status)
raise SystemExit(code if code>=0 else 128-code)
