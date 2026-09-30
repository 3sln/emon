#!/usr/bin/env python3
"""Write emon's hand-made PDF fixtures and the expectations about them.

    python3 kin/scripts/pdf-fixtures.py            write test/pdf/*.pdf, fixtures.txt, filters.txt
    python3 kin/scripts/pdf-fixtures.py --no-host  skip the Chrome and Ghostscript fixtures

DEVLOG P1.4/P1.5, D066. Run by a person, NOT by any gate: the committed files
are the fixtures, and a host tool's output differs between versions. Every
fixture here is built byte by byte so its page count, boxes and structure are
KNOWN BY CONSTRUCTION; where `pdfinfo` (poppler) is on the PATH each one it
can read is cross-checked against it, and the script stops if they disagree.

Files written, all under test/pdf/:
  *.pdf          the fixtures (two come from a host tool, see below)
  fixtures.txt   file, expected status, pages, digest of every page's boxes
  filters.txt    one row per filter case, inputs and outputs from Python encoders
                 that share no code with emon

`digest` is the same 23-bit rolling hash `kin/pdfdoctest.kin` computes over
each page's MediaBox, CropBox (centipoints) and Rotate.
"""
import base64, binascii, os, re, struct, subprocess, sys, zlib, shutil, random

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'test', 'pdf')
os.makedirs(OUT, exist_ok=True)
NOHOST = '--no-host' in sys.argv

MOD = 8388593

def digest(pages):
    """pages: [(media[4], crop[4], rotate)], boxes in centipoints."""
    h = 0
    for media, crop, rot in pages:
        for v in list(media) + list(crop) + [rot]:
            h = (h * 131 + (v % MOD)) % MOD
    return h

def cp(x):
    """centipoints of a decimal string or number, rounded half away from zero"""
    from decimal import Decimal, ROUND_HALF_UP
    return int((Decimal(str(x)) * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))

# --------------------------------------------------------------- builders

class Doc:
    """objects by number; `classic()` writes a table, `stream_xref()` a stream"""
    def __init__(self, version='1.4', eol=b'\n'):
        self.objs = {}
        self.version = version
        self.eol = eol
        self.order = []

    def set(self, n, body):
        if isinstance(body, str):
            body = body.encode('latin-1')
        self.objs[n] = body
        if n not in self.order:
            self.order.append(n)

    def stream(self, n, dict_extra, data, filt=None):
        if isinstance(data, str):
            data = data.encode('latin-1')
        d = (dict_extra + ' /Length %d' % len(data)).encode()
        self.set(n, b'<<' + d + b'>>\nstream\n' + data + b'\nendstream')

    def body(self, start=0):
        out = b''
        offs = {}
        for n in self.order:
            offs[n] = start + len(out)
            out += b'%d 0 obj\n' % n + self.objs[n] + b'\nendobj\n'
        return out, offs

def xref_table(offs, size, eol=b'\n', free=()):
    """a classic table: one subsection per run of consecutive numbers"""
    nums = sorted(set(offs) | set(free) | {0})
    out = b'xref\n'
    runs, run = [], [nums[0]]
    for n in nums[1:]:
        if n == run[-1] + 1:
            run.append(n)
        else:
            runs.append(run); run = [n]
    runs.append(run)
    for r in runs:
        out += b'%d %d\n' % (r[0], len(r))
        for n in r:
            if n == 0:
                out += b'0000000000 65535 f \n'
            elif n in free:
                out += b'0000000000 00001 f \n'
            else:
                out += b'%010d 00000 n \n' % offs[n]
    return out

def classic(doc, trailer, header_junk=b'', comment=True):
    head = header_junk + b'%PDF-' + doc.version.encode() + b'\n'
    if comment:
        head += b'%\xe2\xe3\xcf\xd3\n'
    body, offs = doc.body(len(head))
    xr = len(head) + len(body)
    size = max(doc.objs) + 1
    t = xref_table(offs, size)
    return head + body + t + b'trailer\n<<' + trailer.encode() + b' /Size %d>>\nstartxref\n%d\n%%%%EOF\n' % (size, xr)

def std_page(n, parent, extra='', contents=None):
    return '<</Type/Page/Parent %d 0 R%s%s>>' % (parent, (' /Contents %d 0 R' % contents) if contents else '', (' ' + extra) if extra else '')

def content(text):
    return ('BT /F1 12 Tf 72 720 Td (%s) Tj ET' % text)

FONT = '<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>'
LETTER = ((0, 0, 61200, 79200), (0, 0, 61200, 79200), 0)

written = {}     # file -> (status, pages[(media, crop, rot)]) for fixtures.txt
OROWS = []       # rows of objects-in-files.txt

def orow(file, num, kind, status, expected):
    OROWS.append('\t'.join(['pdf/' + file, str(num), kind, status, expected]))

def ohex(b):
    return b.hex() if b else '-'

def emit(name, data, status='ok', pages=None, pdfinfo_pages=True):
    path = os.path.join(OUT, name)
    open(path, 'wb').write(data)
    written[name] = (status, pages)
    return path

# ------------------------------------------------------------ the fixtures

def minimal():
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>')
    d.stream(4, '', content('Hello, world'))
    d.set(5, FONT)
    emit('minimal.pdf', classic(d, '/Root 1 0 R'), 'ok', [LETTER])
    orow('minimal.pdf', 1, 'o', '-', '<< /Type /Catalog /Pages 2 0 R >>')
    orow('minimal.pdf', 2, 'o', '-', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>')
    orow('minimal.pdf', 3, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>')
    orow('minimal.pdf', 5, 'o', '-', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    orow('minimal.pdf', 0, 'o', '-', '!missing')
    orow('minimal.pdf', 6, 'o', '-', '!missing')
    orow('minimal.pdf', 4, 's', 'ok', ohex(content('Hello, world').encode()))

def inherit():
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R 6 0 R 7 0 R]/Count 4/MediaBox[0 0 500 600]/Rotate 90/Resources<</Font<</F1 9 0 R>>>>>>')
    d.set(3, '<</Type/Pages/Parent 2 0 R/Kids[4 0 R 5 0 R]/Count 2/CropBox[10 10 400 500]>>')
    d.set(4, std_page(4, 3, '', 8))
    d.set(5, std_page(5, 3, '/Rotate 270', 8))
    d.set(6, std_page(6, 2, '/MediaBox[0 0 300 300]/Rotate 0', 8))
    d.set(7, std_page(7, 2, '/MediaBox[612.5 792 0 0.25]/CropBox[0 0 1000 1000]', 8))
    d.stream(8, '', content('inherit'))
    d.set(9, FONT)
    pages = [
        ((0, 0, 50000, 60000), (1000, 1000, 40000, 50000), 90),
        ((0, 0, 50000, 60000), (1000, 1000, 40000, 50000), 270),
        ((0, 0, 30000, 30000), (0, 0, 30000, 30000), 0),
        ((0, 25, 61250, 79200), (0, 25, 61250, 79200), 90),
    ]
    emit('inherit.pdf', classic(d, '/Root 1 0 R'), 'ok', pages)

def boxes():
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R 4 0 R 5 0 R 6 0 R 7 0 R 8 0 R 9 0 R]/Count 7>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox 10 0 R>>')              # indirect array
    d.set(4, '<</Type/Page/Parent 2 0 R>>')                              # none: Letter
    d.set(5, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 0 792]>>')          # zero width: Letter
    d.set(6, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 200]/Rotate -90>>')
    d.set(7, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 200]/Rotate 450>>')
    d.set(8, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 200]/Rotate 45>>')
    d.set(9, '<</Type/Page/Parent 2 0 R/MediaBox[11 0 R 12 0 R 13 0 R 14 0 R]/CropBox[-10 -20 50.004 60.005]>>')
    d.set(10, '[0 0 595.276 841.89]')
    d.set(11, '5'); d.set(12, '6'); d.set(13, '700.5'); d.set(14, '800')
    pages = [
        ((0, 0, 59528, 84189), (0, 0, 59528, 84189), 0),
        LETTER,
        LETTER,
        ((0, 0, 10000, 20000), (0, 0, 10000, 20000), 270),
        ((0, 0, 10000, 20000), (0, 0, 10000, 20000), 90),
        ((0, 0, 10000, 20000), (0, 0, 10000, 20000), 0),
        ((500, 600, 70050, 80000), (500, 600, 5000, 6001), 0),
    ]
    emit('boxes.pdf', classic(d, '/Root 1 0 R'), 'ok', pages)

def zl(b):
    return zlib.compress(b, 9)

def png_predict(data, cols, bpp=1, ftype=12):
    """encode `data` (len multiple of cols) with PNG predictor rows of type ftype-10 (or cycling if 15)"""
    out = b''
    prev = bytes(cols)
    for r in range(0, len(data), cols):
        row = data[r:r + cols]
        t = (ftype - 10) if ftype < 15 else ((r // cols) % 5)
        if t == 0: enc = row
        elif t == 1: enc = bytes((row[i] - (row[i - bpp] if i >= bpp else 0)) & 255 for i in range(cols))
        elif t == 2: enc = bytes((row[i] - prev[i]) & 255 for i in range(cols))
        elif t == 3: enc = bytes((row[i] - (((row[i - bpp] if i >= bpp else 0) + prev[i]) // 2)) & 255 for i in range(cols))
        else:
            def paeth(a, b, c):
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                return a if pa <= pb and pa <= pc else (b if pb <= pc else c)
            enc = bytes((row[i] - paeth(row[i - bpp] if i >= bpp else 0, prev[i], prev[i - bpp] if i >= bpp else 0)) & 255 for i in range(cols))
        out += bytes([t]) + enc
        prev = row
    return out

def xref_stream_doc(name, version='1.5', with_objstm=True):
    """catalog, pages and two pages inside an ObjStm; content streams Flate; xref stream with Predictor 12"""
    # object stream 5: objects 1 2 3 4
    inner = {1: b'<</Type/Catalog/Pages 2 0 R>>',
             2: b'<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2/MediaBox[0 0 595 842]>>',
             3: b'<</Type/Page/Parent 2 0 R/Contents 6 0 R/Resources<</Font<</F1 8 0 R>>>>>>',
             4: b'<</Type/Page/Parent 2 0 R/Contents 6 0 R/Rotate 180/MediaBox[0 0 200 100]>>'}
    hdr = b''; datas = b''
    for n in (1, 2, 3, 4):
        hdr += b'%d %d ' % (n, len(datas))
        datas += inner[n] + b'\n'
    first = len(hdr)
    objstm = zl(hdr + datas)
    d = Doc(version)
    body_objs = []
    head = b'%PDF-' + version.encode() + b'\n%\xe2\xe3\xcf\xd3\n'
    out = head
    offs = {}
    def put(n, dict_txt, data):
        nonlocal out
        offs[n] = len(out)
        out += b'%d 0 obj\n<<' % n + dict_txt.encode() + b' /Length %d>>\nstream\n' % len(data) + data + b'\nendstream\nendobj\n'
    put(5, '/Type/ObjStm/N 4/First %d/Filter/FlateDecode' % first, objstm)
    put(6, '/Filter/FlateDecode', zl(content('xref stream').encode()))
    offs[8] = len(out)
    out += b'8 0 obj\n' + FONT.encode() + b'\nendobj\n'
    # xref stream 9: entries for 0..9, W [1 3 1]
    entries = [(0, 0, 255)]
    entries.append((2, 5, 0)); entries.append((2, 5, 1)); entries.append((2, 5, 2)); entries.append((2, 5, 3))
    entries.append((1, offs[5], 0)); entries.append((1, offs[6], 0)); entries.append((0, 0, 0))
    entries.append((1, offs[8], 0))
    xpos = len(out)
    entries.append((1, xpos, 0))
    raw = b''.join(struct.pack('>B', t) + struct.pack('>I', a)[1:] + struct.pack('>B', b) for t, a, b in entries)
    enc = png_predict(raw, 5, 1, 12)
    data = zl(enc)
    out += b'9 0 obj\n<</Type/XRef/Size 10/W[1 3 1]/Root 1 0 R/Filter/FlateDecode/DecodeParms<</Predictor 12/Columns 5>>/Length %d>>\nstream\n' % len(data) + data + b'\nendstream\nendobj\n'
    out += b'startxref\n%d\n%%%%EOF\n' % xpos
    pages = [((0, 0, 59500, 84200), (0, 0, 59500, 84200), 0),
             ((0, 0, 20000, 10000), (0, 0, 20000, 10000), 180)]
    emit(name, out, 'ok', pages)
    orow(name, 1, 'o', '-', '<< /Type /Catalog /Pages 2 0 R >>')
    orow(name, 2, 'o', '-', '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 595 842] >>')
    orow(name, 3, 'o', '-', '<< /Type /Page /Parent 2 0 R /Contents 6 0 R /Resources << /Font << /F1 8 0 R >> >> >>')
    orow(name, 4, 'o', '-', '<< /Type /Page /Parent 2 0 R /Contents 6 0 R /Rotate 180 /MediaBox [0 0 200 100] >>')
    orow(name, 8, 'o', '-', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    orow(name, 7, 'o', '-', '!missing')
    orow(name, 6, 's', 'ok', ohex(content('xref stream').encode()))
    orow(name, 5, 's', 'ok', ohex(hdr + datas))

def incremental():
    # base: two pages; update 1 rewrites page 2's MediaBox and frees object 7;
    # update 2 appends a third page
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>')
    d.set(4, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>')
    d.set(7, '<</Unused true>>')
    data = classic(d, '/Root 1 0 R')
    x0 = int(re.search(rb'startxref\n(\d+)', data).group(1))
    # update 1
    up1 = Doc(); up1.set(4, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 400]>>')
    body, offs = up1.body(len(data))
    x1 = len(data) + len(body)
    t = xref_table(offs, 8, free={7})
    data1 = data + body + t + b'trailer\n<</Root 1 0 R/Size 8/Prev %d>>\nstartxref\n%d\n%%%%EOF\n' % (x0, x1)
    # update 2
    up2 = Doc(); up2.set(2, '<</Type/Pages/Kids[3 0 R 4 0 R 5 0 R]/Count 3>>'); up2.set(5, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 50 60]/Rotate 90>>')
    body, offs = up2.body(len(data1))
    x2 = len(data1) + len(body)
    t = xref_table(offs, 8)
    data2 = data1 + body + t + b'trailer\n<</Root 1 0 R/Size 8/Prev %d>>\nstartxref\n%d\n%%%%EOF\n' % (x1, x2)
    pages = [((0, 0, 10000, 10000),) * 2 + (0,), ((0, 0, 30000, 40000),) * 2 + (0,), ((0, 0, 5000, 6000),) * 2 + (90,)]
    emit('incremental.pdf', data2, 'ok', pages)
    # the same file cut back to its first update: two pages, the new MediaBox
    emit('incremental-cut.pdf', data1, 'ok', pages[:2])
    orow('incremental.pdf', 4, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 400] >>')
    orow('incremental.pdf', 2, 'o', '-', '<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>')
    orow('incremental.pdf', 5, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 50 60] /Rotate 90 >>')
    orow('incremental.pdf', 7, 'o', '-', '!missing')
    orow('incremental.pdf', 3, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>')
    orow('incremental-cut.pdf', 5, 'o', '-', '!missing')
    orow('incremental-cut.pdf', 2, 'o', '-', '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>')
    orow('incremental-cut.pdf', 7, 'o', '-', '!missing')

def hybrid():
    # the page tree lives in an object stream; the classic table marks those objects free
    # and /XRefStm (an xref stream holding the type-2 entries) says where they really are
    inner = {2: b'<</Type/Pages/Kids[3 0 R 4 0 R]/Count 2>>',
             3: b'<</Type/Page/Parent 2 0 R/MediaBox[0 0 111 222]>>',
             4: b'<</Type/Page/Parent 2 0 R/MediaBox[0 0 333 444]>>'}
    hdr = b''; datas = b''
    for n in (2, 3, 4):
        hdr += b'%d %d ' % (n, len(datas)); datas += inner[n] + b'\n'
    objstm = zl(hdr + datas)
    head = b'%PDF-1.5\n%\xe2\xe3\xcf\xd3\n'
    out = head
    offs = {}
    offs[1] = len(out); out += b'1 0 obj\n<</Type/Catalog/Pages 2 0 R>>\nendobj\n'
    offs[5] = len(out)
    out += b'5 0 obj\n<</Type/ObjStm/N 3/First %d/Filter/FlateDecode/Length %d>>\nstream\n' % (len(hdr), len(objstm)) + objstm + b'\nendstream\nendobj\n'
    # xref stream 6 with entries for 2, 3, 4
    offs[6] = len(out)
    ents = [(2, 5, 0), (2, 5, 1), (2, 5, 2)]
    raw = b''.join(struct.pack('>B', t) + struct.pack('>H', a) + struct.pack('>B', b) for t, a, b in ents)
    out += b'6 0 obj\n<</Type/XRef/Size 7/W[1 2 1]/Index[2 3]/Length %d>>\nstream\n' % len(raw) + raw + b'\nendstream\nendobj\n'
    xr = len(out)
    t = b'xref\n0 7\n0000000000 65535 f \n%010d 00000 n \n0000000000 00000 f \n0000000000 00000 f \n0000000000 00000 f \n%010d 00000 n \n%010d 00000 n \n' % (offs[1], offs[5], offs[6])
    out += t + b'trailer\n<</Root 1 0 R/Size 7/XRefStm %d>>\nstartxref\n%d\n%%%%EOF\n' % (offs[6], xr)
    pages = [((0, 0, 11100, 22200),) * 2 + (0,), ((0, 0, 33300, 44400),) * 2 + (0,)]
    emit('hybrid.pdf', out, 'ok', pages)
    orow('hybrid.pdf', 2, 'o', '-', '<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>')
    orow('hybrid.pdf', 3, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 111 222] >>')
    orow('hybrid.pdf', 4, 'o', '-', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 333 444] >>')

def badlength():
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents[4 0 R 5 0 R 6 0 R]>>')
    c = content('abcdefghij').encode()
    d.set(4, b'<</Length 3>>\nstream\n' + c + b'\nendstream')       # too short
    d.set(5, b'<</Length 9999>>\nstream\n' + c + b'\nendstream')    # too long
    d.set(6, b'<</Length 7 0 R>>\nstream\n' + c + b'\nendstream')   # indirect, correct
    d.set(7, str(len(c)))
    emit('badlength.pdf', classic(d, '/Root 1 0 R'), 'ok', [LETTER])
    for n in (4, 5, 6):
        orow('badlength.pdf', n, 's', 'ok', ohex(c))

def junk_header():
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>')
    junk = b'GARBAGE BEFORE THE HEADER\r\n' * 3
    head = b'%PDF-1.3\n'
    body, offs = d.body(len(head))            # offsets RELATIVE TO THE HEADER (what Acrobat accepts)
    xr = len(head) + len(body)
    data = junk + head + body + xref_table(offs, 4) + b'trailer\n<</Root 1 0 R/Size 4>>\nstartxref\n%d\n%%%%EOF\n' % xr
    emit('junk-header.pdf', data, 'ok', [LETTER])
    orow('junk-header.pdf', 1, 'o', '-', '<< /Type /Catalog /Pages 2 0 R >>')

def crlf():
    d = Doc(eol=b'\r\n')
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents 4 0 R>>')
    d.stream(4, '', content('crlf'))
    # CRLF line ends throughout, offsets recomputed
    head = b'%PDF-1.4\r\n'
    out = head; offs = {}
    for n in d.order:
        offs[n] = len(out)
        out += b'%d 0 obj\r\n' % n + d.objs[n].replace(b'\n', b'\r\n') + b'\r\nendobj\r\n'
    xr = len(out)
    t = b'xref\r\n0 5\r\n0000000000 65535 f\r\n' + b''.join(b'%010d 00000 n\r\n' % offs[n] for n in (1, 2, 3, 4))
    out += t + b'trailer\r\n<</Root 1 0 R/Size 5>>\r\nstartxref\r\n%d\r\n%%%%EOF\r\n' % xr
    emit('crlf.pdf', out, 'ok', [LETTER])
    orow('crlf.pdf', 4, 's', 'ok', ohex(content('crlf').encode()))

def filters_pdf():
    text = b'The quick brown fox jumps over the lazy dog. ' * 4
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>')
    def put(n, dct, data):
        d.set(n, b'<<' + dct.encode() + b' /Length %d>>\nstream\n' % len(data) + data + b'\nendstream')
    put(4, '/Filter/ASCIIHexDecode', text.hex().upper().encode() + b'>')
    put(5, '/Filter[/ASCII85Decode/FlateDecode]', base64.a85encode(zl(text)) + b'~>')
    put(6, '/Filter[/ASCIIHexDecode/FlateDecode]/DecodeParms[null<</Predictor 12/Columns 8>>]', zl(png_predict(text[:176], 8, 1, 12)).hex().encode() + b'>')
    put(7, '/Filter 10 0 R/DecodeParms 11 0 R', zl(png_predict(text[:176], 8, 1, 12)))
    d.set(10, '/FlateDecode'); d.set(11, '<</Predictor 12/Columns 8>>')
    put(8, '/Filter/RunLengthDecode', rl_encode(text))
    put(9, '/Filter[/A85/LZW]', base64.a85encode(lzw_encode(text)) + b'~>')
    put(12, '/Filter/DCTDecode', b'\xff\xd8\xff\xe0JFIF')
    put(13, '/Filter[/FlateDecode/DCTDecode]', zl(b'\xff\xd8\xff\xe0JFIF'))
    put(14, '/Filter/NoSuchFilter', b'abc')
    put(15, '/Filter[/FlateDecode/FlateDecode]', zl(zl(text)))
    put(16, '', text)
    put(17, '/Filter[/ASCIIHexDecode/Crypt]/DecodeParms[null<</Name/Identity>>]', text[:10].hex().encode() + b'>')
    put(18, '/Filter/FlateDecode', zl(text)[:30])
    put(19, '/Filter/LZWDecode/DecodeParms<</EarlyChange 0>>', lzw_encode(text, 0))
    emit('filters.pdf', classic(d, '/Root 1 0 R'), 'ok', [LETTER])
    orow('filters.pdf', 4, 's', 'ok', ohex(text))
    orow('filters.pdf', 5, 's', 'ok', ohex(text))
    orow('filters.pdf', 6, 's', 'ok', ohex(text[:176]))
    orow('filters.pdf', 7, 's', 'ok', ohex(text[:176]))
    orow('filters.pdf', 8, 's', 'ok', ohex(text))
    orow('filters.pdf', 9, 's', 'ok', ohex(text))
    orow('filters.pdf', 12, 's', 'image-filter', ohex(b'\xff\xd8\xff\xe0JFIF'))
    orow('filters.pdf', 13, 's', 'image-filter', ohex(b'\xff\xd8\xff\xe0JFIF'))
    orow('filters.pdf', 14, 's', 'unsupported-filter', ohex(b'abc'))   # what there is, undecoded, like an image codec
    orow('filters.pdf', 15, 's', 'ok', ohex(text))
    orow('filters.pdf', 16, 's', 'ok', ohex(text))
    orow('filters.pdf', 17, 's', 'ok', ohex(text[:10]))
    orow('filters.pdf', 18, 's', 'flate-truncated', ohex(flate_prefix(zl(text)[:30])))
    orow('filters.pdf', 19, 's', 'ok', ohex(text))

def malformed():
    good = Doc()
    good.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    good.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    good.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>')
    base = classic(good, '/Root 1 0 R')
    # startxref points into the middle of an object: the cross-reference is rebuilt by scanning (P3.1)
    bad = re.sub(rb'startxref\n\d+', b'startxref\n17', base)
    emit('badxref.pdf', bad, 'ok', [LETTER])
    # no startxref at all (a download cut short): rebuilt by scanning too
    emit('nostartxref.pdf', base[:base.index(b'startxref')], 'ok', [LETTER])
    # not a PDF
    emit('notpdf.pdf', b'<html><body>not a pdf</body></html>\n', 'no-header', None)
    emit('empty.pdf', b'', 'no-header', None)
    # /Prev loops back on itself (the section's own offset)
    d = good
    head = b'%PDF-1.4\n'
    body, offs = d.body(len(head))
    xrp = len(head) + len(body)
    tab = xref_table(offs, 4)
    data = head + body + tab + b'trailer\n<</Root 1 0 R/Size 4/Prev %d>>\nstartxref\n%d\n%%%%EOF\n' % (xrp, xrp)
    emit('prev-loop.pdf', data, 'ok', [LETTER])
    # the page tree contains itself
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R 2 0 R 4 0 R]/Count 3>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>')
    d.set(4, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>')
    emit('kids-cycle.pdf', classic(d, '/Root 1 0 R'), 'ok',
         [((0, 0, 10000, 10000),) * 2 + (0,), ((0, 0, 20000, 20000),) * 2 + (0,)])
    # a Kids entry that is missing, one that is not a dictionary, a page with no /Type
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R 9 0 R 5 0 R 4 0 R]/Count 4>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 100 100]>>')
    d.set(4, '<</Parent 2 0 R/MediaBox[0 0 200 200]>>')
    d.set(5, '42')
    emit('kids-damaged.pdf', classic(d, '/Root 1 0 R'), 'ok',
         [((0, 0, 10000, 10000),) * 2 + (0,), ((0, 0, 20000, 20000),) * 2 + (0,)])
    # catalog missing: no page tree
    d = Doc()
    d.set(1, '<</Type/Catalog>>')
    emit('no-pages.pdf', classic(d, '/Root 1 0 R'), 'no-pages', None)
    # no /Root in the trailer
    d = Doc(); d.set(1, '<</Type/Catalog/Pages 2 0 R>>'); d.set(2, '<</Type/Pages/Kids[]/Count 0>>')
    # (the scan finds the /Type /Catalog object, P3.1: a document of no pages; poppler refuses it)
    emit('no-root.pdf', classic(d, ''), 'ok', [])
    # an /Encrypt entry whose strings are nonsense: no password opens it (P3.2), and `encrypted` is reported
    d = Doc()
    d.set(1, '<</Type/Catalog/Pages 2 0 R>>')
    d.set(2, '<</Type/Pages/Kids[3 0 R]/Count 1>>')
    d.set(3, '<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>')
    d.set(4, '<</Filter/Standard/V 1/R 2/O (x)/U (y)/P -4>>')
    emit('encrypt-flag.pdf', classic(d, '/Root 1 0 R/Encrypt 4 0 R'), 'password-required', None)
    # xref stream whose /W is damaged: rebuilt by scanning, object streams and all (P3.1)
    xs = open(os.path.join(OUT, 'xrefstream.pdf'), 'rb').read()
    emit('badxrefstream.pdf', xs.replace(b'/W[1 3 1]', b'/W[1 3 9]'), 'ok', written['xrefstream.pdf'][1])

# ----------------------------------------------------------- host fixtures

CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

def chrome():
    html = os.path.join(OUT, '.chrome.html')
    open(html, 'w').write('''<!doctype html><html lang="en"><meta charset="utf-8"><title>emon fixture</title>
<style>body{font-family:Helvetica,Arial,sans-serif;margin:1in} h1{font-size:20pt} p{font-size:11pt}</style>
<h1>Quarterly report</h1><p>The quick brown fox jumps over the lazy dog. Pack my box with five dozen liquor jugs.</p>
<p>Second paragraph, with a few more words to give the page a little body: alpha beta gamma delta.</p>
<div style="page-break-before:always"></div><h1>Second page</h1><p>Text on the second page.</p>''')
    pdf = os.path.join(OUT, 'html-chrome.pdf')
    r = subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--no-pdf-header-footer', '--print-to-pdf=' + pdf, 'file://' + html],
                       capture_output=True, timeout=120)
    os.remove(html)
    return os.path.exists(pdf)

def gs_fixture():
    ps = os.path.join(OUT, '.gs.ps')
    open(ps, 'w').write('''%!PS
/Helvetica findfont 14 scalefont setfont
72 720 moveto (Ghostscript fixture, page one) show showpage
/Helvetica findfont 14 scalefont setfont
72 720 moveto (Page two) show showpage
''')
    pdf = os.path.join(OUT, 'gs-xrefstream.pdf')
    subprocess.run(['gs', '-q', '-dNOPAUSE', '-dBATCH', '-sDEVICE=pdfwrite', '-dCompatibilityLevel=1.5', '-dPDFSETTINGS=/default',
                    '-sOutputFile=' + pdf, ps], capture_output=True, timeout=120)
    os.remove(ps)
    return os.path.exists(pdf)

# ------------------------------------------------------------------ filters

def lzw_encode(data, early=1):
    """PDF/TIFF-style LZW: MSB-first codes, 9..12 bits, clear 256, end 257"""
    out = []; bits = 0; nbits = 0
    def emit_code(code, width):
        nonlocal bits, nbits
        bits = (bits << width) | code; nbits += width
        while nbits >= 8:
            out.append((bits >> (nbits - 8)) & 255); nbits -= 8
    table = {bytes([i]): i for i in range(256)}
    nxt = 258; width = 9
    emit_code(256, width)
    w = b''
    for ch in data:
        c = bytes([ch]); wc = w + c
        if wc in table:
            w = wc
        else:
            emit_code(table[w], width)
            table[wc] = nxt; nxt += 1
            if nxt + early > (1 << width) and width < 12:
                width += 1
            if nxt >= 4094:
                emit_code(256, width)
                table = {bytes([i]): i for i in range(256)}; nxt = 258; width = 9
            w = c
    if w:
        emit_code(table[w], width)
        nxt += 1
        if nxt + early > (1 << width) and width < 12:
            width += 1
    emit_code(257, width)
    if nbits: out.append((bits << (8 - nbits)) & 255)
    return bytes(out)

def lzw_pack(codes, width=9):
    bits = 0; nbits = 0; out = []
    for c in codes:
        bits = (bits << width) | c; nbits += width
        while nbits >= 8:
            out.append((bits >> (nbits - 8)) & 255); nbits -= 8
    if nbits: out.append((bits << (8 - nbits)) & 255)
    return bytes(out)

def rl_encode(data):
    out = b''; i = 0
    while i < len(data):
        j = i
        while j + 1 < len(data) and data[j + 1] == data[i] and j - i < 127: j += 1
        if j > i:
            out += bytes([257 - (j - i + 1)]) + bytes([data[i]]); i = j + 1
        else:
            k = i
            while k + 1 < len(data) and k - i < 127 and not (k + 2 < len(data) and data[k + 1] == data[k + 2]): k += 1
            out += bytes([k - i]) + data[i:k + 1]; i = k + 1
    return out + b'\x80'

def hexs(b):
    return b.hex() if b else '-'

def a85_ref(data):
    """reference ASCII85 decoder with emon's stated semantics: complete groups before an error are kept;
    returns (bytes, status)"""
    out = bytearray(); grp = []; i = 0
    data = data.lstrip(b' \t\r\n\x0c\x00')
    if data.startswith(b'<~'): data = data[2:]
    for c in data:
        if c in b' \t\r\n\x0c\x00': continue
        if c == ord('~'): break
        if c == ord('z'):
            if grp: return bytes(out), 'bad-ascii85'
            out += bytes(4); continue
        if c < 33 or c > 117: return bytes(out), 'bad-ascii85'
        grp.append(c - 33)
        if len(grp) == 5:
            v = 0
            for d in grp: v = v * 85 + d
            if v > 0xFFFFFFFF: return bytes(out), 'bad-ascii85'
            out += v.to_bytes(4, 'big'); grp = []
    if len(grp) == 1: return bytes(out), 'bad-ascii85'
    if grp:
        n = len(grp); grp += [84] * (5 - n)
        v = 0
        for d in grp: v = v * 85 + d
        if v > 0xFFFFFFFF: return bytes(out), 'bad-ascii85'
        out += v.to_bytes(4, 'big')[:n - 1]
    return bytes(out), 'ok'

def filter_rows():
    rows = []
    def row(name, filt, parms, inp, status, out):
        rows.append('\t'.join([name, filt, parms, hexs(inp), status, hexs(out)]))
    rnd = random.Random(1)
    text = b'The quick brown fox jumps over the lazy dog. ' * 7
    bin_ = bytes(rnd.randrange(256) for _ in range(300))
    # ASCIIHexDecode
    row('hex plain', 'ASCIIHexDecode', '-', b'48656C6C6F>', 'ok', b'Hello')
    row('hex lower and space', 'ASCIIHexDecode', '-', b'48 65 6c\n6C 6f >', 'ok', b'Hello')
    row('hex odd digit', 'ASCIIHexDecode', '-', b'4865 6C6C 6>', 'ok', b'Hell\x60')
    row('hex no eod', 'ASCIIHexDecode', '-', b'48656C', 'ok', b'Hel')
    row('hex empty', 'ASCIIHexDecode', '-', b'>', 'ok', b'')
    row('hex junk ignored', 'ASCIIHexDecode', '-', b'48zz65>', 'ok', b'He')
    row('hex after eod ignored', 'ASCIIHexDecode', '-', b'48>65', 'ok', b'H')
    # ASCII85Decode
    def a85(b):
        return base64.a85encode(b) + b'~>'
    def a85row(name, inp, want=None):
        out, st = a85_ref(inp)
        if want is not None: assert out == want, (name, out, want)
        row(name, 'ASCII85Decode', '-', inp, st, out)
    a85row('a85 text', a85(text), text)
    a85row('a85 binary', a85(bin_), bin_)
    a85row('a85 zeros', b'zz~>', bytes(8))
    a85row('a85 zero bytes', a85(bytes(4) + b'abc'), bytes(4) + b'abc')
    a85row('a85 <~ prefix', b'<~' + a85(b'Hello, world'), b'Hello, world')
    a85row('a85 whitespace', a85(b'Hello, world')[:5] + b'\n \r' + a85(b'Hello, world')[5:], b'Hello, world')
    a85row('a85 partial 1', a85(b'a'), b'a')
    a85row('a85 partial 2', a85(b'ab'), b'ab')
    a85row('a85 partial 3', a85(b'abc'), b'abc')
    a85row('a85 no eod', a85(b'Hello!')[:-2], b'Hello!')
    a85row('a85 bad char', b'87cURD{]i,"Ebo80~>')
    a85row('a85 bad char in the first group', b'87cU{~>')
    a85row('a85 overflow group', b'uuuuu~>')
    a85row('a85 z inside group', b'87zcURD~>')
    a85row('a85 single trailing char', b'87cURD]~>'[:-2] + b'8~>')
    a85row('a85 max group', b's8W-!~>', b'\xff\xff\xff\xff')
    # RunLengthDecode
    row('rl literal', 'RunLengthDecode', '-', b'\x04Hello\x80', 'ok', b'Hello')
    row('rl repeat', 'RunLengthDecode', '-', b'\xfdA\x80', 'ok', b'AAAA')
    row('rl mixed', 'RunLengthDecode', '-', rl_encode(text), 'ok', text)
    row('rl binary', 'RunLengthDecode', '-', rl_encode(bin_ + bytes(40) + bin_), 'ok', bin_ + bytes(40) + bin_)
    row('rl no eod', 'RunLengthDecode', '-', b'\x02abc', 'ok', b'abc')
    row('rl truncated literal', 'RunLengthDecode', '-', b'\x05ab', 'truncated', b'ab')
    row('rl truncated repeat', 'RunLengthDecode', '-', b'\xfe', 'truncated', b'')
    row('rl max run', 'RunLengthDecode', '-', b'\x81A\x80', 'ok', b'A' * 128)
    row('rl data after eod ignored', 'RunLengthDecode', '-', b'\x00A\x80\x00B', 'ok', b'A')
    # LZWDecode
    row('lzw text', 'LZWDecode', '-', lzw_encode(text), 'ok', text)
    row('lzw binary', 'LZWDecode', '-', lzw_encode(bin_), 'ok', bin_)
    big = (text + bin_) * 40
    row('lzw grows past 512 and 1024 codes', 'LZWDecode', '-', lzw_encode(big), 'ok', big)
    huge = bytes(rnd.randrange(256) for _ in range(6000))
    row('lzw table full, clear code', 'LZWDecode', '-', lzw_encode(huge), 'ok', huge)
    row('lzw early change 0', 'LZWDecode', 'EarlyChange=0', lzw_encode(big, 0), 'ok', big)
    out, ok = lzw_decode_ref(lzw_encode(big, 1), 0)
    row('lzw early-change data read as 0', 'LZWDecode', 'EarlyChange=0', lzw_encode(big, 1), 'ok' if ok else 'bad-lzw', out)
    row('lzw empty', 'LZWDecode', '-', lzw_encode(b''), 'ok', b'')
    e = lzw_encode(text)
    out, ok = lzw_decode_ref(e[:len(e) // 2], 1)
    row('lzw cut short', 'LZWDecode', '-', e[:len(e) // 2], 'ok' if ok else 'bad-lzw', out)
    row('lzw bad first code', 'LZWDecode', '-', lzw_pack([300, 257]), 'bad-lzw', b'')
    row('lzw without a leading clear code', 'LZWDecode', '-', lzw_pack([72, 105, 257]), 'ok', b'Hi')
    row('lzw code beyond the table', 'LZWDecode', '-', lzw_pack([256, 65, 300, 257]), 'bad-lzw', b'A')
    # Flate + predictors
    def flate_row(name, data, cols, colors, bpc, pred, bpp):
        enc = png_predict(data, (cols * colors * bpc + 7) // 8, bpp, pred)
        parms = 'Predictor=%d,Columns=%d,Colors=%d,BitsPerComponent=%d' % (pred, cols, colors, bpc)
        row(name, 'FlateDecode', parms, zl(enc), 'ok', data)
    for pred in (10, 11, 12, 13, 14, 15):
        data = bytes(rnd.randrange(256) for _ in range(12 * 5))
        flate_row('png predictor %d, 1 byte per pixel' % pred, data, 12, 1, 8, pred, 1)
    data = bytes((i * 7 + (i // 9) * 3) & 255 for i in range(27 * 10))
    flate_row('png sub, rgb', data, 9, 3, 8, 11, 3)
    flate_row('png up, rgb', data, 9, 3, 8, 12, 3)
    flate_row('png avg, rgb', data, 9, 3, 8, 13, 3)
    flate_row('png paeth, rgb', data, 9, 3, 8, 14, 3)
    flate_row('png optimum, rgb', data, 9, 3, 8, 15, 3)
    data = bytes(rnd.randrange(256) for _ in range(32 * 6))
    flate_row('png paeth, 16 bit gray', data, 16, 1, 16, 14, 2)
    flate_row('png optimum, cmyk', bytes(rnd.randrange(256) for _ in range(16 * 4 * 6)), 16, 4, 8, 15, 4)
    flate_row('png sub, 1 bit', bytes(rnd.randrange(256) for _ in range(4 * 9)), 32, 1, 1, 11, 1)
    flate_row('png up, 4 bit', bytes(rnd.randrange(256) for _ in range(5 * 9)), 10, 1, 4, 12, 1)
    row('flate no input is an empty stream', 'FlateDecode', '-', b'', 'ok', b'')
    row('flate no predictor', 'FlateDecode', '-', zl(text), 'ok', text)
    row('flate predictor 1 (none)', 'FlateDecode', 'Predictor=1', zl(text), 'ok', text)
    t20 = zl(text)[:20]
    row('flate truncated', 'FlateDecode', '-', t20, 'flate-truncated', flate_prefix(t20))
    row('flate bad header', 'FlateDecode', '-', b'\x00\x00garbage', 'flate-bad-header', b'')
    bc = bytearray(zl(text)); bc[-1] ^= 1
    row('flate bad checksum keeps the data', 'FlateDecode', '-', bytes(bc), 'flate-bad-checksum', text)
    row('flate predictor, short last row', 'FlateDecode', 'Predictor=12,Columns=5', zl(b'\x00\x01\x02\x03\x04\x05\x02\x01\x01\x01'), 'ok', b'\x01\x02\x03\x04\x05\x02\x03\x04')
    row('flate predictor, bad row type', 'FlateDecode', 'Predictor=12,Columns=3', zl(b'\x00\x01\x02\x03\x07\x01\x02\x03'), 'bad-predictor', b'\x01\x02\x03')
    row('flate predictor, zero columns', 'FlateDecode', 'Predictor=12,Columns=0', zl(b'\x01\x01\x02\x03'), 'bad-predictor', b'')
    row('flate predictor, bad bits per component', 'FlateDecode', 'Predictor=12,Columns=3,BitsPerComponent=3', zl(b'\x01\x01\x02\x03'), 'bad-predictor', b'')
    # TIFF predictor 2
    def tiff_enc(data, cols, colors, bpc):
        rowb = (cols * colors * bpc + 7) // 8; out = b''
        for r in range(0, len(data), rowb):
            rw = data[r:r + rowb]
            if bpc == 8:
                out += bytes((rw[i] - (rw[i - colors] if i >= colors else 0)) & 255 for i in range(len(rw)))
            else:
                vals = [(rw[i] << 8) | rw[i + 1] for i in range(0, len(rw), 2)]
                d = [(vals[k] - (vals[k - colors] if k >= colors else 0)) & 0xFFFF for k in range(len(vals))]
                out += b''.join(v.to_bytes(2, 'big') for v in d)
        return out
    data = bytes(rnd.randrange(256) for _ in range(8 * 3 * 5))
    row('tiff predictor, rgb 8 bit', 'FlateDecode', 'Predictor=2,Columns=8,Colors=3', zl(tiff_enc(data, 8, 3, 8)), 'ok', data)
    data = bytes(rnd.randrange(256) for _ in range(10 * 2 * 4))
    row('tiff predictor, gray 16 bit', 'FlateDecode', 'Predictor=2,Columns=10,BitsPerComponent=16', zl(tiff_enc(data, 10, 1, 16)), 'ok', data)
    row('tiff predictor, 4 bit is unsupported', 'FlateDecode', 'Predictor=2,Columns=10,BitsPerComponent=4', zl(b'\x01' * 10), 'bad-predictor', b'')
    # LZW with a predictor
    data = bytes(rnd.randrange(256) for _ in range(12 * 5))
    row('lzw with png predictor', 'LZWDecode', 'Predictor=12,Columns=12', lzw_encode(png_predict(data, 12, 1, 12)), 'ok', data)
    return rows

def zl(b):
    return zlib.compress(b, 9)

def lzw_decode_ref(data, early=1):
    """reference LZW decoder, kept independent of emon; returns (bytes, ok)"""
    out = bytearray(); pos = 0; nbits = len(data) * 8
    table = None; width = 9; prev = None
    def reset():
        return [bytes([i]) for i in range(256)] + [b'', b'']
    table = reset()
    while pos + width <= nbits:
        code = 0
        for k in range(width):
            code = (code << 1) | ((data[(pos + k) >> 3] >> (7 - ((pos + k) & 7))) & 1)
        pos += width
        if code == 256:
            table = reset(); width = 9; prev = None; continue
        if code == 257:
            break
        if prev is None:
            if code >= 256: return bytes(out), False
            entry = table[code]
        else:
            if code < len(table): entry = table[code]
            elif code == len(table) and len(table) < 4096: entry = prev + prev[:1]
            else: return bytes(out), False
            if len(table) < 4096: table.append(prev + entry[:1])
        out += entry
        prev = entry
        if len(table) + early >= (1 << width) and width < 12: width += 1
    return bytes(out), True

def lzw_decode_prefix(orig, enc, early):
    out, ok = lzw_decode_ref(enc, early)
    return out

def lzw_nofinal(text):
    enc = lzw_encode(text)
    # drop the 257 end code (and the padding) by decoding until the data runs out
    out, _ = lzw_decode_ref(enc[:-1])
    return out

def flate_prefix(b):
    d = zlib.decompressobj()
    try:
        return d.decompress(b)
    except Exception:
        return b''

# --------------------------------------------------------------------- main

def main():
    minimal(); inherit(); boxes(); xref_stream_doc('xrefstream.pdf'); incremental(); hybrid(); badlength(); junk_header(); crlf(); filters_pdf()
    malformed()
    if NOHOST:
        for n in ('html-chrome.pdf', 'gs-xrefstream.pdf'):
            if os.path.exists(os.path.join(OUT, n)): written[n] = ('ok', None)
    if not NOHOST:
        if os.path.exists(CHROME):
            if chrome(): written['html-chrome.pdf'] = ('ok', None)
        if shutil.which('gs'):
            if gs_fixture(): written['gs-xrefstream.pdf'] = ('ok', None)
    # cross-check against poppler
    pdfinfo = shutil.which('pdfinfo')
    lines = ['# emon P1.5 fixtures (kin/pdfdoctest.kin). Written by kin/scripts/pdf-fixtures.py.',
             '# file <TAB> status <TAB> pages <TAB> digest of the boxes. `status` is a `pd-status`; pages and',
             '# digest are - where the file cannot be opened. Cross-checked against pdfinfo when present.']
    for name in sorted(written):
        status, pages = written[name]
        path = os.path.join(OUT, name)
        pi = None
        if pdfinfo and status == 'ok':
            r = subprocess.run([pdfinfo, '-f', '1', '-l', '99999', '-box', path], capture_output=True, text=True)
            m = re.search(r'^Pages:\s+(\d+)', r.stdout, re.M)
            pi = int(m.group(1)) if m else None
        if pages is None and status == 'ok':
            # host fixture: the expectation IS pdfinfo's (this is the cross-check, there being no hand truth)
            assert pi, name
            pages = []
            for i in range(1, pi + 1):
                mm = re.search(r'^Page\s+%d MediaBox:\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)' % i, r.stdout, re.M)
                cc = re.search(r'^Page\s+%d CropBox:\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)' % i, r.stdout, re.M)
                rr = re.search(r'^Page\s+%d rot:\s+(\d+)' % i, r.stdout, re.M)
                pages.append((tuple(cp(v) for v in mm.groups()), tuple(cp(v) for v in cc.groups()), int(rr.group(1))))
        if status != 'ok':
            lines.append('\t'.join(['pdf/' + name, status, '-', '-']))
            continue
        if pi is not None and pi != len(pages) and name not in ('kids-cycle.pdf', 'kids-damaged.pdf'):   # poppler reports the declared /Count there
            print('MISMATCH %s: expected %d pages, pdfinfo says %d' % (name, len(pages), pi)); sys.exit(1)
        # per page boxes vs pdfinfo
        if pdfinfo:
            for i, (media, crop, rot) in enumerate(pages, 1):
                mm = re.search(r'^Page\s+%d MediaBox:\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)' % i, r.stdout, re.M)
                rr = re.search(r'^Page\s+%d rot:\s+(\d+)' % i, r.stdout, re.M)
                if mm and name not in ('boxes.pdf',):
                    want = [cp(v) for v in mm.groups()]
                    if [abs(a - b) > 1 for a, b in zip(want, media)] != [False] * 4:
                        print('BOX MISMATCH %s page %d: %s vs pdfinfo %s' % (name, i, media, want)); sys.exit(1)
                if rr and int(rr.group(1)) != rot and not (name == 'boxes.pdf' and i == 6):   # /Rotate 45: poppler keeps 45, emon (like pdf.js) reads 0
                    print('ROT MISMATCH %s page %d: %d vs pdfinfo %s' % (name, i, rot, rr.group(1))); sys.exit(1)
        lines.append('\t'.join(['pdf/' + name, 'ok', str(len(pages)), str(digest(pages))]))
    open(os.path.join(OUT, 'fixtures.txt'), 'w').write('\n'.join(lines) + '\n')
    open(os.path.join(OUT, 'objects-in-files.txt'), 'w').write(
        '# emon P1.5 object-resolution rows (kin/pdfdoctest.kin). Written by kin/scripts/pdf-fixtures.py; every\n'
        '# expectation is known BY CONSTRUCTION of the fixture (the script built the file from these objects).\n'
        '# file <TAB> object number <TAB> kind <TAB> status <TAB> expected\n'
        '#   kind o: `pd-get` and dump the object (expected is the dump, or !missing)\n'
        '#   kind s: decode the stream (status is a pf-status, expected the decoded bytes in hex, - for none)\n'
        + '\n'.join(OROWS) + '\n')
    rows = filter_rows()
    open(os.path.join(OUT, 'filters.txt'), 'w').write(
        '# emon P1.5 filter rows (kin/pdfdoctest.kin). Written by kin/scripts/pdf-fixtures.py from Python\n'
        '# encoders (zlib, base64.a85encode, and LZW/RunLength/PNG-predictor encoders written there).\n'
        '# name <TAB> filter <TAB> parms (k=v,k=v or -) <TAB> input hex <TAB> status <TAB> output hex (- is empty)\n'
        + '\n'.join(rows) + '\n')
    print('wrote %d fixtures and %d filter rows' % (len(written), len(rows)))

if __name__ == '__main__':
    main()
