#!/usr/bin/env python3
"""THIRD-PARTY ORACLE: validate gold files against schema/emon-gold-1.json with the `jsonschema`
2020-12 validator.  The only Python in the importer path (maintainer policy, DEVLOG: scripting is
babashka; Python only for third-party oracles in tools/oracle/).  `bb import` calls it.

    tools/oracle/gold_validate.py (FILE.gold.json | DIR) ...

A DIR stands for every *.gold.json in it (sorted).

Prints one JSON object per INVALID file on stdout: {"file": PATH, "errors": ["schema: MSG at /PATH", ...]}
(at most 3 errors per file, sorted by path, as the importer always reported them).  Prints nothing for a
valid file.  Exit 0 when it ran (valid or not), 2 when no `jsonschema` can be had.

`jsonschema` is found on the path, else in $EMON_PYLIB, else ~/.cache/emon/pylib, else it is pip-installed
there once (the same search kin/scripts/json-check.py makes).
"""
import json
import os
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def find_jsonschema():
    try:
        import jsonschema  # noqa
        return jsonschema
    except ImportError:
        pass
    for lib in (os.environ.get("EMON_PYLIB"), os.path.expanduser("~/.cache/emon/pylib")):
        if lib and os.path.isdir(lib):
            sys.path.insert(0, lib)
            try:
                import jsonschema  # noqa
                return jsonschema
            except ImportError:
                sys.path.pop(0)
    lib = os.path.expanduser("~/.cache/emon/pylib")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--target", lib, "jsonschema"],
                       capture_output=True, text=True)
    if r.returncode == 0:
        sys.path.insert(0, lib)
        try:
            import jsonschema  # noqa
            return jsonschema
        except ImportError:
            pass
    return None


def main(files):
    js = find_jsonschema()
    if js is None:
        sys.stderr.write("gold_validate: no jsonschema available (pip install jsonschema, or set EMON_PYLIB)\n")
        return 2
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
    one = json.load(open(os.path.join(ROOT, "schema", "emon-1.json"), encoding="utf-8"))
    gs = json.load(open(os.path.join(ROOT, "schema", "emon-gold-1.json"), encoding="utf-8"))
    reg = Registry().with_resources([(one["$id"], Resource.from_contents(one, DRAFT202012)),
                                     (gs["$id"], Resource.from_contents(gs, DRAFT202012))])
    v = js.Draft202012Validator(gs, registry=reg)
    paths = []
    for a in files:
        if os.path.isdir(a):
            paths += [os.path.join(a, f) for f in sorted(os.listdir(a)) if f.endswith(".gold.json") and not f.startswith("._")]
        else:
            paths.append(a)
    for path in paths:
        try:
            g = json.load(open(path, encoding="utf-8"))
            errs = ["schema: %s at /%s" % (e.message[:160], "/".join(map(str, e.path)))
                    for e in sorted(v.iter_errors(g), key=lambda e: list(e.path))[:3]]
        except Exception as e:  # unreadable / not JSON
            errs = ["schema: %s" % e]
        if errs:
            print(json.dumps({"file": path, "errors": errs}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
