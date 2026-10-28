"""Corpus shard lifecycle. Digests: UTF-8 relative path, NUL, hex SHA256, LF, sorted by path."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
ROOT = REPO / 'corpus'
REGISTRY = REPO / 'test/corpus/shards.json'

def files(shard):
    import glob
    import fnmatch
    import stat
    found = set()
    real_root = ROOT.resolve()
    checked_parents = set()
    def visit(p):
        rel = p.relative_to(ROOT)
        if p.parent not in checked_parents:
            if not p.parent.resolve().is_relative_to(real_root):
                raise ValueError(f'path escapes corpus root: {p}')
            checked_parents.add(p.parent)
        if any(x.startswith('._') or x == '.git' for x in rel.parts):
            return
        mode = p.lstat().st_mode
        if stat.S_ISLNK(mode):
            raise ValueError(f'refusing symlink inside shard: {p}')
        if stat.S_ISDIR(mode):
            with os.scandir(p) as entries:
                for e in entries:
                    if e.name.startswith('._') or e.name == '.git':
                        continue
                    if e.is_symlink():
                        raise ValueError(f'refusing symlink inside shard: {e.path}')
                    if e.is_dir(follow_symlinks=False):
                        visit(Path(e.path))
                    elif e.is_file(follow_symlinks=False):
                        found.add(Path(e.path).relative_to(ROOT).as_posix())
        elif stat.S_ISREG(mode):
            found.add(rel.as_posix())
    for pattern in shard['paths']:
        if Path(pattern).is_absolute() or '..' in Path(pattern).parts:
            raise ValueError(f'unsafe shard path: {pattern}')
        exact = ROOT / pattern
        if glob.has_magic(exact.name) and not glob.has_magic(str(exact.parent)):
            if not exact.parent.exists():
                continue
            if not exact.parent.resolve().is_relative_to(real_root):
                raise ValueError(f'path escapes corpus root: {exact}')
            with os.scandir(exact.parent) as entries:
                for e in entries:
                    if e.name.startswith('._') or not fnmatch.fnmatchcase(e.name, exact.name):
                        continue
                    if e.is_symlink():
                        raise ValueError(f'refusing symlink inside shard: {e.path}')
                    if e.is_file(follow_symlinks=False):
                        found.add(Path(e.path).relative_to(ROOT).as_posix())
                    else:
                        visit(Path(e.path))
        else:
            matches = [str(exact)] if exact.exists() or exact.is_symlink() else glob.iglob(str(exact))
            for name in matches:
                visit(Path(name))
    return sorted(found)

def fingerprint(shard):
    from concurrent.futures import ThreadPoolExecutor
    names = files(shard)
    def hash_file(name):
        fh = hashlib.sha256()
        with (ROOT / name).open('rb') as stream:
            before = os.fstat(stream.fileno())
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                fh.update(chunk)
            after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError(f'file changed while hashing: {name}')
        return name, fh.hexdigest(), after.st_size
    h = hashlib.sha256()
    size = 0
    # Preserve sorted reduction order while overlapping the external volume's file-open latency.
    with ThreadPoolExecutor(max_workers=32) as pool:
        for name, digest, length in pool.map(hash_file, names):
            size += length
            h.update((name + '\0' + digest + '\n').encode())
    return {'digest': h.hexdigest(), 'size': size, 'files': len(names)}

def state(s):
    actual = fingerprint(s)
    return ('absent' if not actual['files'] else 'present' if all(actual[k] == s[k] for k in actual) else 'modified'), actual

def config():
    # Read EDN with the project's existing reader; never load S3 settings or .env.
    out = subprocess.check_output(['bb', '-e', '(require \'[clojure.edn :as e] \'[cheshire.core :as j]) (println (j/generate-string (if (.exists (java.io.File. "local.edn")) (e/read-string (slurp "local.edn")) {})))'], cwd=REPO, text=True)
    return json.loads(out)

def wanted(reg):
    local = config().get('corpus-shards')
    ids = set(local if local is not None else reg['default'])
    unknown = ids - {s['id'] for s in reg['shards']}
    if unknown:
        raise ValueError(f'unknown :corpus-shards: {sorted(unknown)}')
    return ids

def require(reg, task, args):
    if task == 'gates' and args:
        needed = sorted({id for gate in args for id in reg['consumers'].get(gate, [])})
    else:
        key = task + ':corpus' if '--corpus' in args else task
        needed = reg['consumers'].get(key, [])
    if task == 'receipts' and any(x in args for x in ['--report', '--rescore', '--help', '-h']):
        return
    if task == 'receipts':
        sets = next((x.split('=', 1)[1] for x in args if x.startswith('--set=')), None)
        if '--set' in args:
            sets = args[args.index('--set') + 1]
        selected = (sets or 'sroie,cord').split(',')
        aliases = {'sroie': 'sroie2019', 'cord': 'cord-v2', 'expressexpense-srd': 'expressexpense-srd'}
        needed = [aliases[x] for x in selected if x in aliases]
    if task == 'ocr-live' and '--no-gold' in args:
        return
    # `bb unit red ARM` inverts every law to prove each can fail; it reads no corpus (CI runs it without one).
    if task == 'unit' and args[:1] == ['red']:
        return
    missing = []
    for s in reg['shards']:
        if s['id'] in needed:
            count = len(files(s))
            if count == 0 or count < s['files']:
                missing.append(s['id'])
    if missing:
        raise ValueError('missing or incomplete corpus shards: ' + ', '.join(missing) + '; run: bb corpus fetch ' + ' '.join(missing))

def fetch(s):
    current, _ = state(s)
    if current == 'present':
        print(s['id'], 'already verified')
        return
    if s['keep']:
        raise ValueError(f"{s['id']}: keep; exact refetch unavailable ({s['reason']})")
    if current == 'modified':
        raise ValueError(f"{s['id']}: modified or partial; preserve it and move it aside before fetching")
    for command in s['fetch']:
        subprocess.run(command, cwd=REPO, check=True)
    current, _ = state(s)
    if current != 'present':
        raise ValueError(f"{s['id']}: fetched contents differ from registry; preserved, never accepted as verified")
    print(s['id'], 'verified')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['status', 'fetch', 'prune', 'require'])
    parser.add_argument('ids', nargs='*')
    parser.add_argument('--needed', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--yes', action='store_true')
    a, extra = parser.parse_known_args()
    reg = json.loads(REGISTRY.read_text())
    if a.command == 'require':
        require(reg, a.ids[0], a.ids[1:] + extra)
        return
    if (a.ids and a.needed) or (a.command != 'fetch' and (a.ids or a.needed)) or extra or (a.yes and a.dry_run) or (a.command != 'prune' and (a.yes or a.dry_run)):
        parser.error('invalid options')
    if a.command == 'fetch':
        if ROOT.is_symlink() and not ROOT.exists():
            raise ValueError('corpus symlink is dangling; run bb corpus-link --check and mount the drive')
        ROOT.mkdir(exist_ok=True)
    want = wanted(reg)
    selected = a.ids or (sorted(want) if a.needed else [])
    if a.command == 'fetch' and not selected:
        if a.needed:
            print('No wanted shards configured.')
            return
        parser.error('fetch needs shard ids or --needed')
    unknown = set(selected) - {s['id'] for s in reg['shards']}
    if unknown:
        parser.error(f'unknown shards: {sorted(unknown)}')
    total = 0
    for s in reg['shards']:
        if a.command == 'fetch':
            if s['id'] in selected:
                fetch(s)
            continue
        if a.command == 'prune':
            if s['id'] in want:
                continue
            if s['keep']:
                if files(s):
                    print(f"RETAIN {s['id']}: keep: {s['reason']}")
                continue
        st, actual = state(s)
        users = ','.join(k for k,v in reg['consumers'].items() if s['id'] in v) or 'none'
        if a.command == 'status':
            print(f"{s['id']:40} {st:8} {actual['size']/1e9:8.3f} GB / {s['size']/1e9:.3f} registered  {'wanted' if s['id'] in want else 'unwanted'} {'keep' if s['keep'] else 'refetch'}  needs={users}")
        elif st != 'absent' and s['id'] not in want:
            if s['keep'] or st == 'modified':
                print(f"RETAIN {s['id']}: {'keep: ' + s['reason'] if s['keep'] else 'digest mismatch'}")
                continue
            total += actual['size']
            print(f"{'DELETE' if a.yes else 'WOULD DELETE'} {s['id']}: {actual['size']/1e9:.3f} GB")
            if a.yes:
                # Re-hash immediately before deletion, and delete only the exact verified file list.
                if state(s)[0] != 'present':
                    raise ValueError('shard changed before deletion: ' + s['id'])
                for name in files(s):
                    (ROOT / name).unlink()
                # Empty directories are harmless and do not count as a present shard.
    if a.command == 'prune':
        print(f"{'Deleted' if a.yes else 'Proposed savings'}: {total/1e9:.3f} GB ({total} bytes); {'--yes supplied' if a.yes else 'dry-run (default); deletion requires --yes'}")

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as e:
        print('corpus:', e, file=sys.stderr)
        sys.exit(1)
