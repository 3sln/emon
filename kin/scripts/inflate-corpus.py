#!/usr/bin/env python3
"""Generate test/inflate/streams.txt, the row corpus for `emon.inflatetest`.

    python3 kin/scripts/inflate-corpus.py          (writes the file)
    python3 kin/scripts/inflate-corpus.py --check  (fails if the file is stale)

WHERE THE ANSWERS COME FROM. The streams are made by a host tool that is not
emon: CPython's `zlib` (the zlib C library) at levels 0, 1, 6 and 9, with
each strategy, window size and flush mode, so stored, fixed and dynamic
blocks all occur. The expected output of a valid stream is the data that
went in. Damaged streams are either a prefix of a valid one (truncated) or
hand-built with the small encoder below, which writes RFC 1951 bit by bit
so that one specific rule is broken; for those, zlib itself must ALSO reject
the stream (the script asserts it), so the classification is not only ours.

Row format, `;`-separated, one per line, `#` lines are comments:

    name ; kind ; status ; consumed ; stream-hex ; output-hex

  kind      z zlib (RFC 1950)   r raw (RFC 1951); UPPER CASE (Z R) means the
            output on error must equal output-hex exactly, lower case that it
            must be a PREFIX of it (a truncated stream yields as much as was
            decodable, and how much is not part of the row)
  status    emon's `iz-status` code the decoder must answer (0 ok)
  consumed  index of the first byte after the stream, or -1 not checked
Deterministic: every random source is seeded, so the file is reproducible.
"""
import random
import struct
import sys
import zlib

OUT = "test/inflate/streams.txt"
rows = []


def hx(b):
    return bytes(b).hex()


def add(name, kind, status, consumed, stream, output):
    assert ";" not in name
    rows.append(f"{name};{kind};{status};{consumed};{hx(stream)};{hx(output)}")


# ------------------------------------------------------------- outcomes
def outcome(kind, stream):
    """What zlib itself says: ('ok', out, unused) | ('err', msg) | ('short', out)."""
    d = zlib.decompressobj(15 if kind in "zZ" else -15)
    try:
        out = d.decompress(bytes(stream))
        out += d.flush()
    except zlib.error as e:
        return ("err", str(e))
    if not d.eof:
        return ("short", out)
    return ("ok", out, d.unused_data)


# ----------------------------------------------------------------- data
rng = random.Random(1951)
WORDS = ("the of and to in is that for it as was with be by on not he this are "
         "or his from at which but have an had they you were their one all we "
         "can her has there been if more when will would who so no").split()


def prose(n):
    out = []
    size = 0
    while size < n:
        w = rng.choice(WORDS)
        if rng.random() < 0.08:
            w = w.capitalize() + "."
        out.append(w)
        size += len(w) + 1
    return (" ".join(out)).encode()[:n]


def noise(n):
    return bytes(rng.randrange(256) for _ in range(n))


block = noise(32000)
DATA = {
    "empty": b"",
    "one": b"a",
    "hello": b"hello, world\n",
    "pangram": b"The quick brown fox jumps over the lazy dog. " * 40,
    "prose": prose(6000),
    "noise": noise(1500),
    "zeros": bytes(40000),
    "ramp": bytes(range(256)) * 20,
    "farcopy": block + block,           # a match at distance 32000
    "structs": b"".join(struct.pack("<HiB", i, i * i - 7000, i & 255) for i in range(700)),
    "longprose": prose(30000),
}
# keep the file small: the big inputs get fewer variants
BIG = {"zeros": [0, 6], "farcopy": [6], "longprose": [1, 9]}

# ----------------------------------------------- 1. valid zlib and raw
STRATS = {"default": zlib.Z_DEFAULT_STRATEGY, "filtered": zlib.Z_FILTERED,
          "huffonly": zlib.Z_HUFFMAN_ONLY, "rle": zlib.Z_RLE, "fixed": zlib.Z_FIXED}


def comp(data, level=6, wbits=15, strategy=zlib.Z_DEFAULT_STRATEGY, mem=8):
    c = zlib.compressobj(level, zlib.DEFLATED, wbits, mem, strategy)
    return c.compress(data) + c.flush()


for name, data in DATA.items():
    variants = [(lv, "default") for lv in (0, 1, 6, 9)]
    if name in BIG:
        variants = [(lv, "default") for lv in BIG[name]]
    else:
        variants += [(6, "filtered"), (6, "huffonly"), (6, "rle"), (6, "fixed"),
                     (9, "fixed")]
    for level, strat in variants:
        s = comp(data, level, 15, STRATS[strat])
        assert outcome("z", s)[:2] == ("ok", data)
        add(f"z-{name}-l{level}-{strat}", "Z", 0, len(s), s, data)
        if name not in BIG:
            r = comp(data, level, -15, STRATS[strat])
            assert outcome("r", r)[:2] == ("ok", data)
            add(f"r-{name}-l{level}-{strat}", "R", 0, len(r), r, data)

# window sizes (CINFO 1..7) and memLevel: the header byte changes, the codes do not
for wbits in (9, 10, 12, 14):
    for name in ("prose", "pangram"):
        s = comp(DATA[name], 6, wbits, mem=9 if wbits > 9 else 8)
        assert outcome("z", s)[:2] == ("ok", DATA[name])
        add(f"z-{name}-w{wbits}", "Z", 0, len(s), s, DATA[name])

# flush modes: empty stored blocks and several blocks in one stream
for mode_name, mode in (("sync", zlib.Z_SYNC_FLUSH), ("full", zlib.Z_FULL_FLUSH)):
    c = zlib.compressobj(6)
    parts = [DATA["prose"][:900], DATA["pangram"][:500], b"", DATA["noise"][:300]]
    s = b""
    for p in parts:
        s += c.compress(p) + c.flush(mode)
    s += c.flush()
    want = b"".join(parts)
    assert outcome("z", s)[:2] == ("ok", want)
    add(f"z-flush-{mode_name}", "Z", 0, len(s), s, want)

# trailing bytes after the stream: `consumed` says where it ended
s = comp(DATA["hello"])
add("z-trailing-garbage", "Z", 0, len(s), s + b"GARBAGE", DATA["hello"])
r = comp(DATA["hello"], 6, -15)
add("r-trailing-garbage", "R", 0, len(r), r + b"\x00\x01\x02", DATA["hello"])


# ------------------------------------------- 2. a bit-level encoder
class Bits:
    """RFC 1951 3.1.1: numbers low bit first, Huffman codes high bit first."""

    def __init__(self):
        self.v = 0
        self.n = 0

    def num(self, value, nbits):
        for i in range(nbits):
            self.v |= ((value >> i) & 1) << self.n
            self.n += 1

    def code(self, value, nbits):
        for i in range(nbits - 1, -1, -1):
            self.v |= ((value >> i) & 1) << self.n
            self.n += 1

    def bytes(self):
        return self.v.to_bytes((self.n + 7) // 8, "little")


def canonical(lengths):
    """Codes for a list of code lengths, exactly as 3.2.2 assigns them."""
    maxl = max(lengths) if lengths else 0
    count = [0] * (maxl + 2)
    for l in lengths:
        count[l] += 1
    count[0] = 0
    nxt, code = [0] * (maxl + 2), 0
    for b in range(1, maxl + 1):
        code = (code + count[b - 1]) << 1
        nxt[b] = code
    out = []
    for l in lengths:
        if l:
            out.append((nxt[l], l))
            nxt[l] += 1
        else:
            out.append(None)
    return out


def fixed_lens():
    return [8] * 144 + [9] * 112 + [7] * 24 + [8] * 8


def emit_fixed(bits, syms):
    """syms: ('lit', b) | ('match', length, dist) | ('eob',) | ('rawcode', v, nbits)."""
    codes = canonical(fixed_lens())
    LB = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258]
    LE = [0] * 8 + [1] * 4 + [2] * 4 + [3] * 4 + [4] * 4 + [5] * 4 + [0]
    DB = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577]
    DE = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13]
    for s in syms:
        if s[0] == "lit":
            bits.code(*codes[s[1]])
        elif s[0] == "eob":
            bits.code(*codes[256])
        elif s[0] == "rawcode":
            bits.code(s[1], s[2])
        else:
            _, ln, dist = s
            li = max(i for i in range(29) if LB[i] <= ln)
            bits.code(*codes[257 + li])
            bits.num(ln - LB[li], LE[li])
            di = max(i for i in range(30) if DB[i] <= dist)
            bits.code(di, 5)
            bits.num(dist - DB[di], DE[di])


def header(bits, final, btype):
    bits.num(final, 1)
    bits.num(btype, 2)


def raw_fixed(syms, final=1):
    b = Bits()
    header(b, final, 1)
    emit_fixed(b, syms)
    return b.bytes()


# The code-length code used by `dyn`: symbols 0..12 have length 4, 13..18
# length 5 (Kraft sum 13/16 + 6/32 = 1: complete).
CL_LENS = [4] * 13 + [5] * 6
ORDER = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]


def dyn(lit_lens, dist_lens, body, final=1, hlit=None, hdist=None, cl_lens=None,
        lens_stream=None, into=None):
    """A dynamic block. body(litcodes, distcodes, bits) writes the symbols."""
    cl_lens = cl_lens or CL_LENS
    b = into or Bits()
    header(b, final, 2)
    nlit = hlit if hlit is not None else len(lit_lens)
    ndist = hdist if hdist is not None else len(dist_lens)
    b.num(nlit - 257, 5)
    b.num(ndist - 1, 5)
    ncode = 19
    b.num(ncode - 4, 4)
    for i in range(ncode):
        b.num(cl_lens[ORDER[i]], 3)
    clc = canonical(cl_lens)
    seq = lens_stream if lens_stream is not None else [("len", x) for x in lit_lens + dist_lens]
    for item in seq:
        if item[0] == "len":
            b.code(*clc[item[1]])
        else:                       # ("rep", symbol, extra)
            _, sym, extra = item
            b.code(*clc[sym])
            b.num(extra, {16: 2, 17: 3, 18: 7}[sym])
    body(canonical(lit_lens), canonical(dist_lens) if any(dist_lens) else None, b)
    return b if into else b.bytes()


def lit_body(text):
    def f(lc, dc, b):
        for ch in text:
            b.code(*lc[ch])
        b.code(*lc[256])
    return f


# ----------------------------------------- 3. hand-built valid streams
# A dynamic block whose literal code is 'a','b','c' (length 2) and EOB (2),
# and whose distance code is EMPTY: valid, literals only.
L4 = [0] * 97 + [2, 2, 2] + [0] * 156 + [2]                      # 'a' 'b' 'c' and 256
assert len(L4) == 257
s = dyn(L4, [0], lit_body(b"abcabc"))
assert outcome("r", s)[:2] == ("ok", b"abcabc")
add("r-dyn-empty-distance-code", "R", 0, len(s), s, b"abcabc")

# ... and with ONE distance code of length 1 (the one incomplete form allowed)
s = dyn(L4, [1], lit_body(b"cab"))
assert outcome("r", s)[:2] == ("ok", b"cab")
add("r-dyn-single-distance-code", "R", 0, len(s), s, b"cab")

# a match through the single distance code: length code needs symbol 257+
L5 = [0] * 97 + [2, 2] + [0] * 157 + [2, 2]                     # 'a' 'b', 256, 257 (len 3)
assert len(L5) == 258
def body_match(lc, dc, b):
    b.code(*lc[97]); b.code(*lc[98])
    b.code(*lc[257])                 # length 3
    b.code(0, 1)                     # the only distance code (symbol 0 = distance 1)
    b.code(*lc[256])
s = dyn(L5, [1], body_match)
assert outcome("r", s)[:2] == ("ok", b"abbbb")
add("r-dyn-match", "R", 0, len(s), s, b"abbbb")

# repeat codes 16, 17, 18 in the header, spelled out by hand
lens = [0] * 97 + [2, 2, 2] + [0] * 156 + [2]
seq = [("rep", 18, 97 - 11), ("len", 2), ("len", 2), ("len", 2),
       ("rep", 18, 138 - 11), ("rep", 18, 18 - 11), ("len", 2),
       ("len", 0)]                                              # distance: one zero length
# and a 17 (3..10 zeros) in place of the last 18 bytes of zeros
seq17 = [("rep", 18, 97 - 11), ("len", 2), ("len", 2), ("len", 2),
         ("rep", 18, 138 - 11), ("rep", 17, 10 - 3), ("rep", 17, 8 - 3),
         ("len", 2), ("len", 0)]
s = dyn(lens, [0], lit_body(b"cba"), lens_stream=seq)
assert outcome("r", s)[:2] == ("ok", b"cba"), outcome("r", s)
add("r-dyn-repeat-17-18", "R", 0, len(s), s, b"cba")
s = dyn(lens, [0], lit_body(b"abc"), lens_stream=seq17)
assert outcome("r", s)[:2] == ("ok", b"abc"), outcome("r", s)
add("r-dyn-repeat-17", "R", 0, len(s), s, b"abc")
# 16 repeats the previous length: symbols 65..68 all length 3? use lens with runs
runs = [0] * 65 + [3, 3, 3, 3, 3] + [0] * 186 + [3]              # 65..69 len 3, 256 len 3
# 5 codes of length 3 + eob = 6 codes: incomplete but allowed only if <=... make it complete: add lens
runs = [0] * 65 + [3] * 7 + [0] * 184 + [3]                     # 65..71 (7 codes) + 256 = 8 codes of 3 bits
assert len(runs) == 257 and sum(1 for x in runs if x) == 8
seq = [("rep", 18, 65 - 11), ("len", 3), ("rep", 16, 6 - 3),
       ("rep", 18, 138 - 11), ("rep", 18, 46 - 11), ("len", 3), ("len", 0)]
s = dyn(runs, [0], lit_body(b"ABCDEFG"), lens_stream=seq)
assert outcome("r", s)[:2] == ("ok", b"ABCDEFG"), outcome("r", s)
add("r-dyn-repeat-16", "R", 0, len(s), s, b"ABCDEFG")

# three blocks in one stream: stored, then fixed, then dynamic -- blocks are
# contiguous BITS, so all three are written into one bit buffer
b = Bits()
header(b, 0, 0)
b.num(0, (-b.n) % 8)                                  # pad to the byte boundary
b.v |= int.from_bytes(struct.pack("<HH", 5, 0xFFFF - 5) + b"stone", "little") << b.n
b.n += 9 * 8
header(b, 0, 1)
emit_fixed(b, [("lit", 33), ("match", 5, 5), ("eob",)])
dyn(L4, [0], lit_body(b"abc"), final=1, into=b)
s = b.bytes()
o = outcome("r", s)
assert o[0] == "ok" and o[1] == b"stone" + b"!" + b"stone"[0:0] + b"!stone"[1:1] + o[1][6:], o
assert o[1].startswith(b"stone!") and o[1].endswith(b"abc"), o
add("r-stored-fixed-dynamic", "R", 0, len(s), s, o[1])

# a fixed block using the longest match (258) and the farthest distance
lits = [("lit", i & 255) for i in range(300)]
syms = lits + [("match", 258, 300), ("match", 258, 1), ("match", 3, 300), ("eob",)]
s = raw_fixed(syms)
o = outcome("r", s)
assert o[0] == "ok", o
add("r-fixed-long-matches", "R", 0, len(s), s, o[1])

syms = [("lit", i & 255) for i in range(32768)] + [("match", 258, 32768), ("eob",)]
s = raw_fixed(syms)
o = outcome("r", s)
assert o[0] == "ok", o
add("r-fixed-distance-32768", "R", 0, len(s), s, o[1])

# a stored block of 65535 bytes (the maximum)
data = noise(65535)
b = Bits(); header(b, 1, 0)
s = b.bytes() + struct.pack("<HH", 65535, 0) + data
assert outcome("r", s)[:2] == ("ok", data)
add("r-stored-65535", "R", 0, len(s), s, data)
# and an empty stored block
b = Bits(); header(b, 1, 0)
s = b.bytes() + struct.pack("<HH", 0, 0xFFFF)
assert outcome("r", s)[:2] == ("ok", b"")
add("r-stored-empty", "R", 0, len(s), s, b"")

# ------------------------------------------------- 4. damaged streams
# 4a. zlib headers
def fcheck(cmf, flg=0):
    flg += 31 - ((cmf * 256 + flg) % 31)
    return bytes([cmf, flg % 256]) if (cmf * 256 + flg) % 31 == 0 else bytes([cmf, flg])


add("z-empty-input", "Z", 1, -1, b"", b"")
add("z-one-byte", "Z", 1, -1, b"\x78", b"")
for name, hdr, st in (
        ("cm-9", fcheck(0x79), 7),                  # CM=9, FCHECK valid
        ("fcheck", b"\x78\x00", 7),                 # 0x7800 % 31 != 0
        ("window-8", fcheck(0x88), 7),              # CINFO=8: window over 32K
        ):
    assert (hdr[0] * 256 + hdr[1]) % 31 == 0 or name == "fcheck"
    s = hdr + comp(b"hello", 6, -15) + struct.pack(">I", zlib.adler32(b"hello"))
    assert outcome("z", s)[0] == "err", (name, outcome("z", s))
    add(f"z-bad-header-{name}", "Z", st, -1, s, b"")
flg = 0x20
flg += 31 - ((0x78 * 256 + flg) % 31)
s = bytes([0x78, flg]) + comp(b"hello", 6, -15) + struct.pack(">I", zlib.adler32(b"hello"))
assert outcome("z", s)[0] == "err"
add("z-preset-dictionary", "Z", 8, -1, s, b"")

# 4b. checksum
s = bytearray(comp(DATA["prose"]))
s[-1] ^= 0x01
assert outcome("z", bytes(s))[0] == "err"
add("z-bad-adler-low", "Z", 9, len(s), bytes(s), DATA["prose"])
s = bytearray(comp(DATA["prose"]))
s[-4] ^= 0x80
assert outcome("z", bytes(s))[0] == "err"
add("z-bad-adler-high", "Z", 9, len(s), bytes(s), DATA["prose"])

# 4c. truncation: every prefix of two small streams, a spread of a big one
def truncations(label, kind, s, data, cuts):
    for cut in cuts:
        t = s[:cut]
        o = outcome(kind, t)
        assert o[0] in ("short", "err"), (label, cut, o)
        if o[0] == "short":
            assert data.startswith(o[1])
        add(f"{label}-cut-{cut}", kind.lower(), 1, -1, t, data)

small = comp(b"the cat sat on the mat. the cat sat on the mat.", 6)
truncations("z-small", "z", small, b"the cat sat on the mat. the cat sat on the mat.", range(len(small)))
smallr = comp(b"the cat sat on the mat. the cat sat on the mat.", 6, -15)
truncations("r-small", "r", smallr, b"the cat sat on the mat. the cat sat on the mat.", range(len(smallr)))
dynz = comp(DATA["prose"][:1500], 9)
truncations("z-dynamic", "z", dynz, DATA["prose"][:1500], range(0, len(dynz), max(1, len(dynz) // 45)))
st0 = comp(DATA["noise"][:600], 0, -15)
truncations("r-stored", "r", st0, DATA["noise"][:600], [0, 1, 2, 4, 5, 6, 100, 300, len(st0) - 1])
fx = comp(DATA["pangram"], 6, -15, zlib.Z_FIXED)
truncations("r-fixed", "r", fx, DATA["pangram"], range(0, len(fx), max(1, len(fx) // 30)))

# 4d. block-level damage, each hand-built and rejected by zlib as well
def bad(name, kind, status, s, expect_out=b""):
    o = outcome(kind, s)
    assert o[0] == "err", (name, o)
    add(name, kind.upper(), status, -1, s, expect_out)


b = Bits(); header(b, 1, 3)
bad("r-reserved-block-type", "r", 2, b.bytes() + b"\x00\x00")

b = Bits(); header(b, 1, 0)
bad("r-stored-nlen-mismatch", "r", 3, b.bytes() + struct.pack("<HH", 5, 0x1234) + b"hello")

b = Bits(); header(b, 1, 0)
bad("r-stored-len-is-nlen", "r", 3, b.bytes() + struct.pack("<HH", 5, 5) + b"hello")

# a match before any output: distance too far back
bad("r-fixed-distance-too-far", "r", 5, raw_fixed([("match", 3, 1), ("eob",)]))
bad("r-fixed-distance-past-output", "r", 5,
    raw_fixed([("lit", 65), ("lit", 66), ("match", 4, 3), ("eob",)]), b"AB")

# fixed block: length symbol 286 and distance symbol 30 never occur
codes = canonical(fixed_lens())
bb = Bits(); header(bb, 1, 1); bb.code(*codes[286])
bad("r-fixed-length-symbol-286", "r", 4, bb.bytes() + b"\x00\x00")
bb = Bits(); header(bb, 1, 1); bb.code(*codes[65]); bb.code(*codes[257]); bb.code(30, 5)
bad("r-fixed-distance-symbol-30", "r", 4, bb.bytes() + b"\x00\x00\x00", b"A")

# dynamic headers
def b_hdr(nlit, ndist, cl_lens=None):
    return dyn([2] * nlit, [1] * ndist, lambda lc, dc, b: None, hlit=nlit, hdist=ndist,
               cl_lens=cl_lens)

bb = Bits(); header(bb, 1, 2); bb.num(30, 5); bb.num(0, 5); bb.num(0, 4)
bad("r-dyn-too-many-literal-codes", "r", 10, bb.bytes() + b"\x00" * 8)   # HLIT=30: 287 > 286
bb = Bits(); header(bb, 1, 2); bb.num(0, 5); bb.num(30, 5); bb.num(0, 4)
bad("r-dyn-too-many-distance-codes", "r", 10, bb.bytes() + b"\x00" * 8)  # HDIST=30: 31 > 30

# code-length code over-subscribed: nineteen symbols of length 1
bb = Bits(); header(bb, 1, 2); bb.num(0, 5); bb.num(0, 5); bb.num(15, 4)
for _ in range(19):
    bb.num(1, 3)
bad("r-dyn-code-length-code-oversubscribed", "r", 6, bb.bytes() + b"\x00" * 8)
# code-length code incomplete: only symbol 16 has a code
bb = Bits(); header(bb, 1, 2); bb.num(0, 5); bb.num(0, 5); bb.num(0, 4)
bb.num(1, 3); bb.num(0, 3); bb.num(0, 3); bb.num(0, 3)
bad("r-dyn-code-length-code-incomplete", "r", 6, bb.bytes() + b"\x00" * 8)

# repeat 16 with nothing before it
seq = [("rep", 16, 0)]
bad("r-dyn-repeat-16-first", "r", 6,
    dyn(L4, [0], lit_body(b"a"), lens_stream=seq))
# a repeat that runs past HLIT + HDIST
seq = [("rep", 18, 127)] * 3
bad("r-dyn-repeat-overruns", "r", 6, dyn(L4, [0], lit_body(b"a"), lens_stream=seq))
# literal/length code over-subscribed: three codes of length 1
lens = [0] * 97 + [1, 1, 1] + [0] * 156 + [1]
bad("r-dyn-literal-code-oversubscribed", "r", 6, dyn(lens, [0], lit_body(b"a")))
# incomplete literal/length code (two codes of length 2 and EOB of length 3)
lens = [0] * 97 + [2, 2] + [0] * 157 + [3]
bad("r-dyn-literal-code-incomplete", "r", 6, dyn(lens, [0], lambda lc, dc, b: None))
# no end-of-block code
lens = [0] * 97 + [1, 1] + [0] * 158
bad("r-dyn-no-end-of-block-code", "r", 6, dyn(lens, [0], lambda lc, dc, b: None))
# distance code over-subscribed: three codes of length 1
bad("r-dyn-distance-code-oversubscribed", "r", 6, dyn(L4, [1, 1, 1], lit_body(b"a")))
# a match whose distance is beyond the output, through a dynamic code
def body_far(lc, dc, b):
    b.code(*lc[257]); b.code(0, 1)                # a match with no output yet
bad("r-dyn-distance-too-far", "r", 5,
    dyn(L5, [1], body_far), b"")
# an unassigned code, reached: the literal/length code holds ONLY end-of-block
# (length 1, the one incomplete form allowed), so the bit 1 is no code at all
def body_bad_code(lc, dc, b):
    b.code(1, 1)
lens = [0] * 256 + [1]
# (padded with zero bytes: the decoder keeps reading bits looking for a longer
# code, so what it answers is `bad-code` only if 15 bits are there to read)
o_ = dyn(lens, [0], body_bad_code) + b"\x00\x00\x00"
assert outcome("r", o_)[0] == "err", outcome("r", o_)
add("r-dyn-unassigned-code", "R", 4, -1, o_, b"")
# ... and the same code used correctly: an empty block
o_ = dyn(lens, [0], lambda lc, dc, b: b.code(0, 1))
assert outcome("r", o_)[:2] == ("ok", b""), outcome("r", o_)
add("r-dyn-only-end-of-block", "R", 0, len(o_), o_, b"")

# --------------------------------------------------------------- write
text = ("# GENERATED by kin/scripts/inflate-corpus.py -- do not edit.\n"
        "# name;kind;status;consumed;stream-hex;output-hex   (see the script's docstring)\n"
        + "\n".join(rows) + "\n")
if "--check" in sys.argv:
    cur = open(OUT).read()
    if cur != text:
        print("FAIL", OUT, "is stale; run kin/scripts/inflate-corpus.py")
        sys.exit(1)
    print("ok  ", OUT, "is current,", len(rows), "rows")
else:
    open(OUT, "w").write(text)
    print("wrote", OUT, len(rows), "rows,", len(text), "bytes")
