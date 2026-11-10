"""Lossless task-evidence storage; native recording PNGs and transcripts stay verbatim."""
import gzip, hashlib, json, pathlib, shutil


def read_bytes(path):
    path = pathlib.Path(path)
    if path.exists():
        return path.read_bytes()
    with gzip.open(str(path)+'.gz', 'rb') as stream:
        return stream.read()


def read_text(path):
    return read_bytes(path).decode('utf-8')


def read_json(path):
    return json.loads(read_text(path))


def compact(path):
    path = pathlib.Path(path)
    if not path.exists() or path.stat().st_size < 262144:
        return
    target = pathlib.Path(str(path)+'.gz')
    temporary = pathlib.Path(str(target)+'.tmp')
    original = hashlib.sha256()
    with path.open('rb') as source, gzip.open(temporary, 'wb', compresslevel=6) as dest:
        while chunk := source.read(1048576):
            original.update(chunk)
            dest.write(chunk)
    verified = hashlib.sha256()
    with gzip.open(temporary, 'rb') as source:
        while chunk := source.read(1048576):
            verified.update(chunk)
    assert original.digest() == verified.digest(), path
    if temporary.stat().st_size >= path.stat().st_size:
        temporary.unlink()
        return
    temporary.replace(target)
    path.unlink()


if __name__ == '__main__':
    import sys
    for root in sys.argv[1:]:
        for pattern in ['*.bundle', '*.evidence']:
            for path in pathlib.Path(root).rglob(pattern):
                if not path.name.startswith('._'):
                    compact(path)
