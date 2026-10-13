// The Java half of emon's host ports. See the Rust half for the reasoning.
//
// @kin:link:ns: emon.port

package emon;

public final class Port {
    // ---------------------------------------------------------- the Blob
    //
    // JAVA'S `byte` IS SIGNED: every read masks with 0xFF so a byte is 0..255
    // here as it is everywhere else.

    // @kin:link:form:blob-len: {:template "emon.Port.blobLen({0})" :arity 1}
    public static int blobLen(byte[] b) {
        return b.length;
    }

    // @kin:link:form:blob-u8: {:template "emon.Port.blobU8({0}, {1})" :arity 2}
    public static int blobU8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    // @kin:link:form:blob-u16: {:template "emon.Port.blobU16({0}, {1})" :arity 2}
    public static int blobU16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    // @kin:link:form:blob-u32: {:template "emon.Port.blobU32({0}, {1})" :arity 2}
    public static int blobU32(byte[] b, int i) {
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16)
            | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    // @kin:link:form:blob-u16le: {:template "emon.Port.blobU16le({0}, {1})" :arity 2}
    public static int blobU16le(byte[] b, int i) {
        return ((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF);
    }

    // @kin:link:form:blob-u32le: {:template "emon.Port.blobU32le({0}, {1})" :arity 2}
    public static int blobU32le(byte[] b, int i) {
        return ((b[i + 3] & 0xFF) << 24) | ((b[i + 2] & 0xFF) << 16)
            | ((b[i + 1] & 0xFF) << 8) | (b[i] & 0xFF);
    }

    // ---------------------------------------------------------- the Cells
    public static final class Cells {
        int[] v = new int[16];
        int n = 0;
    }

    public static Cells newCells() {
        return new Cells();
    }

    // The cells as an int[] copy: what a host takes out of an arena (Go's `Cells.V`).
    public static int[] cellsVec(Cells c) {
        return java.util.Arrays.copyOf(c.v, c.n);
    }

    // @kin:link:form:cells-len: {:template "emon.Port.cellsLen({0})" :arity 1}
    public static int cellsLen(Cells c) {
        return c.n;
    }

    // @kin:link:form:cells-at: {:template "emon.Port.cellsAt({0}, {1})" :arity 2}
    public static int cellsAt(Cells c, int i) {
        if (i < 0 || i >= c.n) {
            throw new IndexOutOfBoundsException();
        }
        return c.v[i];
    }

    // @kin:link:form:cells-set: {:template "emon.Port.cellsSet({0}, {1}, {2})" :arity 3}
    public static void cellsSet(Cells c, int i, int v) {
        if (i < 0 || i >= c.n) {
            throw new IndexOutOfBoundsException();
        }
        c.v[i] = v;
    }

    // @kin:link:form:cells-push: {:template "emon.Port.cellsPush({0}, {1})" :arity 2}
    public static int cellsPush(Cells c, int v) {
        if (c.n == c.v.length) {
            c.v = java.util.Arrays.copyOf(c.v, c.v.length * 2);
        }
        c.v[c.n] = v;
        return c.n++;
    }

    // @kin:link:form:cells-drop: {:template "emon.Port.cellsDrop({0}, {1})" :arity 2}
    public static void cellsDrop(Cells c, int n) {
        if (n < 0 || n > c.n) {
            throw new IndexOutOfBoundsException();
        }
        c.n = n;
    }

    // @kin:link:form:cells-grow: {:template "emon.Port.cellsGrow({0}, {1}, {2})" :arity 3}
    //
    // Appends n copies of v (what a loop of `cells-push` does, in one call).
    public static void cellsGrow(Cells c, int n, int v) {
        if (n < 0) {
            throw new IndexOutOfBoundsException();
        }
        int need = c.n + n;
        if (need > c.v.length) {
            c.v = java.util.Arrays.copyOf(c.v, Math.max(need, c.v.length * 2));
        }
        java.util.Arrays.fill(c.v, c.n, need, v);
        c.n = need;
    }

    // @kin:link:form:cells-swap: {:template "emon.Port.cellsSwap({0}, {1})" :arity 2}
    //
    // Exchanges the CONTENTS of two arenas, O(1): each stays where its owner
    // keeps it (a context field), only what it holds changes hands (D362).
    public static void cellsSwap(Cells a, Cells b) {
        int[] v = a.v;
        int n = a.n;
        a.v = b.v;
        a.n = b.n;
        b.v = v;
        b.n = n;
    }

    // @kin:link:form:cells-copy-into: {:template "emon.Port.cellsCopyInto({0}, {1})" :arity 2}
    //
    // `(cells-copy-into dst src)`: dst becomes a copy of src, whatever it held
    // before. The two are different arenas.
    public static void cellsCopyInto(Cells dst, Cells src) {
        dst.v = java.util.Arrays.copyOf(src.v, Math.max(src.v.length, 16));
        dst.n = src.n;
    }

    // @kin:link:form:cells-free: {:template "emon.Port.cellsFree({0})" :arity 1}
    //
    // Empties the arena AND gives its storage back.
    public static void cellsFree(Cells c) {
        c.v = new int[16];
        c.n = 0;
    }

    // @kin:link:form:cells-bytes: {:template "emon.Port.cellsBytes({0})" :arity 1}
    //
    // A Blob VIEW of a Cells of bytes: a snapshot (see the Rust half).
    public static byte[] cellsBytes(Cells c) {
        byte[] b = new byte[c.n];
        for (int i = 0; i < c.n; i++) {
            b[i] = (byte) c.v[i];
        }
        return b;
    }

    // ---------------------------------------------------------- the Buf
    //
    // A growable run of bytes (see the Rust half for the contract). JAVA'S
    // `byte` IS SIGNED, so a read masks with 0xFF.
    public static final class Buf {
        byte[] v = new byte[64];
        int n = 0;
    }

    public static Buf newBuf() {
        return new Buf();
    }

    // @kin:link:form:buf-len: {:template "emon.Port.bufLen({0})" :arity 1}
    public static int bufLen(Buf b) {
        return b.n;
    }

    // @kin:link:form:buf-at: {:template "emon.Port.bufAt({0}, {1})" :arity 2}
    public static int bufAt(Buf b, int i) {
        if (i < 0 || i >= b.n) {
            throw new IndexOutOfBoundsException();
        }
        return b.v[i] & 0xFF;
    }

    // @kin:link:form:buf-push: {:template "emon.Port.bufPush({0}, {1})" :arity 2}
    public static int bufPush(Buf b, int v) {
        if (b.n == b.v.length) {
            b.v = java.util.Arrays.copyOf(b.v, b.v.length * 2);
        }
        b.v[b.n++] = (byte) v;
        return b.n;
    }

    // @kin:link:form:buf-set: {:template "emon.Port.bufSet({0}, {1}, {2})" :arity 3}
    public static void bufSet(Buf b, int i, int v) {
        if (i < 0 || i >= b.n) {
            throw new IndexOutOfBoundsException();
        }
        b.v[i] = (byte) v;
    }

    // @kin:link:form:buf-truncate: {:template "emon.Port.bufTruncate({0}, {1})" :arity 2}
    public static void bufTruncate(Buf b, int n) {
        if (n < 0 || n > b.n) {
            throw new IndexOutOfBoundsException();
        }
        b.n = n;
    }

    // @kin:link:form:buf-append-blob: {:template "emon.Port.bufAppendBlob({0}, {1}, {2}, {3})" :arity 4}
    public static void bufAppendBlob(Buf b, byte[] blob, int start, int end) {
        if (start < 0 || end < start || end > blob.length) {
            throw new IndexOutOfBoundsException();
        }
        int k = end - start;
        if (b.n + k > b.v.length) {
            b.v = java.util.Arrays.copyOf(b.v, Math.max(b.n + k, b.v.length * 2));
        }
        System.arraycopy(blob, start, b.v, b.n, k);
        b.n += k;
    }

    // @kin:link:form:buf-grow: {:template "emon.Port.bufGrow({0}, {1}, {2})" :arity 3}
    // See the Rust half: appends n copies of the low eight bits of v.
    public static void bufGrow(Buf b, int n, int v) {
        if (n < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (b.n + n > b.v.length) {
            b.v = java.util.Arrays.copyOf(b.v, Math.max(b.n + n, b.v.length * 2));
        }
        java.util.Arrays.fill(b.v, b.n, b.n + n, (byte) v);
        b.n += n;
    }

    // @kin:link:form:buf-copy-back: {:template "emon.Port.bufCopyBack({0}, {1}, {2})" :arity 3}
    // See the Rust half: appends n bytes, byte k the one dist before it (an overlapping match repeats).
    public static void bufCopyBack(Buf b, int dist, int n) {
        if (dist <= 0 || dist > b.n || n < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (b.n + n > b.v.length) {
            b.v = java.util.Arrays.copyOf(b.v, Math.max(b.n + n, b.v.length * 2));
        }
        byte[] v = b.v;
        int at = b.n;
        if (dist >= n) {
            System.arraycopy(v, at - dist, v, at, n);
        } else {
            for (int k = 0; k < n; k++) {
                v[at + k] = v[at - dist + k];
            }
        }
        b.n = at + n;
    }

    // @kin:link:form:buf-copy-into: {:template "emon.Port.bufCopyInto({0}, {1})" :arity 2}
    //
    // `(buf-copy-into dst src)`: dst becomes a copy of src (see `cells-copy-into`).
    public static void bufCopyInto(Buf dst, Buf src) {
        dst.v = java.util.Arrays.copyOf(src.v, Math.max(src.v.length, 64));
        dst.n = src.n;
    }

    // @kin:link:form:buf-blob: {:template "emon.Port.bufBlob({0})" :arity 1}
    //
    // A copy (a `byte[]` cannot be a prefix of a larger array).
    public static byte[] bufBlob(Buf b) {
        return java.util.Arrays.copyOf(b.v, b.n);
    }

    // ---------------------------------------------------------- the Bitmap
    //
    // An opaque 8-bit pixel buffer (see the Rust half for the contract). The
    // check is on the byte index, not on each coordinate; JAVA'S `byte` IS
    // SIGNED, so a read masks with 0xFF.
    public static final class Bitmap {
        public final int w, h, stride, ch;
        public final byte[] d;

        public Bitmap(int w, int h, int ch, byte[] d) {
            if (d.length != w * h * ch) {
                throw new IllegalArgumentException("bitmap size");
            }
            this.w = w;
            this.h = h;
            this.stride = w * ch;
            this.ch = ch;
            this.d = d;
        }
    }

    // @kin:link:form:bitmap-new: {:template "emon.Port.bitmapNew({0}, {1}, {2})" :arity 3}
    public static Bitmap bitmapNew(int w, int h, int ch) {
        return new Bitmap(w, h, ch, new byte[w * h * ch]);
    }

    // @kin:link:form:bitmap-w: {:template "emon.Port.bitmapW({0})" :arity 1}
    public static int bitmapW(Bitmap b) {
        return b.w;
    }

    // @kin:link:form:bitmap-h: {:template "emon.Port.bitmapH({0})" :arity 1}
    public static int bitmapH(Bitmap b) {
        return b.h;
    }

    // @kin:link:form:bitmap-stride: {:template "emon.Port.bitmapStride({0})" :arity 1}
    public static int bitmapStride(Bitmap b) {
        return b.stride;
    }

    // @kin:link:form:bitmap-channels: {:template "emon.Port.bitmapChannels({0})" :arity 1}
    public static int bitmapChannels(Bitmap b) {
        return b.ch;
    }

    // @kin:link:form:bitmap-u8: {:template "emon.Port.bitmapU8({0}, {1}, {2}, {3})" :arity 4}
    public static int bitmapU8(Bitmap b, int x, int y, int c) {
        return b.d[y * b.stride + x * b.ch + c] & 0xFF;
    }

    // @kin:link:form:bitmap-set-u8: {:template "emon.Port.bitmapSetU8({0}, {1}, {2}, {3}, {4})" :arity 5}
    public static void bitmapSetU8(Bitmap b, int x, int y, int c, int v) {
        b.d[y * b.stride + x * b.ch + c] = (byte) v;
    }

    // ---- bulk forms (P2.7): the per-pixel loops the spike priced, done in the
    // host. See the Rust half for the contracts.

    // @kin:link:form:bitmap-histogram: {:template "emon.Port.bitmapHistogram({0}, {1}, {2})" :arity 3}
    public static void bitmapHistogram(Bitmap b, Cells hist, int off) {
        if (off < 0 || off + 256 > hist.n) {
            throw new IndexOutOfBoundsException();
        }
        // Four partial histograms, for the Rust half's reason.
        int[] p0 = new int[256], p1 = new int[256], p2 = new int[256], p3 = new int[256];
        byte[] d = b.d;
        int ch = b.ch;
        for (int y = 0; y < b.h; y++) {
            int s = y * b.stride;
            int end = s + b.w * ch;
            int i = s;
            for (; i + 4 * ch <= end; i += 4 * ch) {
                p0[d[i] & 0xFF]++;
                p1[d[i + ch] & 0xFF]++;
                p2[d[i + 2 * ch] & 0xFF]++;
                p3[d[i + 3 * ch] & 0xFF]++;
            }
            for (; i < end; i += ch) {
                p0[d[i] & 0xFF]++;
            }
        }
        int[] bins = hist.v;
        for (int v = 0; v < 256; v++) {
            bins[off + v] += p0[v] + p1[v] + p2[v] + p3[v];
        }
    }

    // @kin:link:form:bitmap-threshold-into: {:template "emon.Port.bitmapThresholdInto({0}, {1}, {2})" :arity 3}
    public static int bitmapThresholdInto(Bitmap g, int t, Bitmap m) {
        if (g.w != m.w || g.h != m.h) {
            throw new IllegalArgumentException("bitmap size");
        }
        int ink = 0;
        byte[] gd = g.d, md = m.d;
        for (int y = 0; y < g.h; y++) {
            int gi = y * g.stride, mi = y * m.stride;
            if (g.ch == 1 && m.ch == 1) {
                // The common case: unit strides, which the JIT can vectorise.
                for (int x = 0; x < g.w; x++) {
                    int on = 1 - ((t - (gd[gi + x] & 0xFF)) >>> 31);
                    md[mi + x] = (byte) -on;
                    ink += on;
                }
                continue;
            }
            for (int x = 0; x < g.w; x++) {
                // on = 1 when v <= t, branch-free (the sign bit of t - v).
                int on = 1 - ((t - (gd[gi] & 0xFF)) >>> 31);
                md[mi] = (byte) -on;
                ink += on;
                gi += g.ch;
                mi += m.ch;
            }
        }
        return ink;
    }

    // @kin:link:form:bitmap-next-nonzero: {:template "emon.Port.bitmapNextNonzero({0}, {1}, {2})" :arity 3}
    public static int bitmapNextNonzero(Bitmap b, int x, int y) {
        int s = y * b.stride;
        byte[] d = b.d;
        for (int i = x; i < b.w; i++) {
            if (d[s + i * b.ch] != 0) {
                return i;
            }
        }
        return b.w;
    }

    // @kin:link:form:bitmap-integral: {:template "emon.Port.bitmapIntegral({0}, {1}, {2})" :arity 3}
    public static void bitmapIntegral(Bitmap b, Cells sum, Cells sq) {
        int w1 = b.w + 1;
        int n = w1 * (b.h + 1);
        // Only row 0 and column 0 need zeroing: every other cell is written below.
        if (sum.v.length < n) {
            sum.v = new int[n];
        }
        sum.n = n;
        if (sq.v.length < n) {
            sq.v = new int[n];
        }
        sq.n = n;
        int[] s = sum.v, q = sq.v;
        byte[] d = b.d;
        for (int x = 0; x < w1; x++) {
            s[x] = 0;
            q[x] = 0;
        }
        for (int y = 0; y < b.h; y++) {
            s[(y + 1) * w1] = 0;
            q[(y + 1) * w1] = 0;
            int rs = 0, rq = 0;
            int src = y * b.stride;
            int p = y * w1 + 1, c = (y + 1) * w1 + 1;
            for (int x = 0; x < b.w; x++) {
                int v = d[src + x * b.ch] & 0xFF;
                rs += v;
                rq += v * v;
                s[c + x] = s[p + x] + rs;
                q[c + x] = q[p + x] + rq;
            }
        }
    }

    // ---------------------------------------------------------- the Src
    //
    // An input byte source as kin sees it: a per-unit view over a backing
    // that may miss (see the Rust half for the contract). `Src.view()` is a
    // fresh view of the same backing, for the host's re-run.

    public interface SrcBacking {
        int len();

        // The byte 0..255, or -1 when it is not resident (the backing records the miss).
        int at(int i);

        // Fill buf from s; false when any of it is not resident (recorded).
        boolean span(int s, byte[] buf);

        // Bytes [s, e) will be wanted. May be ignored.
        void hint(int s, int e);
    }

    private static final class MemBacking implements SrcBacking {
        final byte[] v;

        MemBacking(byte[] v) {
            this.v = v;
        }

        public int len() {
            return v.length;
        }

        public int at(int i) {
            return v[i] & 0xFF;
        }

        public boolean span(int s, byte[] buf) {
            System.arraycopy(v, s, buf, 0, buf.length);
            return true;
        }

        public void hint(int s, int e) {
        }
    }

    public static final class Src {
        final SrcBacking b;
        volatile boolean missed = false;

        public Src(SrcBacking b) {
            this.b = b;
        }

        // A fresh view of the same backing: the missed flag is clear.
        public Src view() {
            return new Src(b);
        }

        public boolean missed() {
            return missed;
        }
    }

    // @kin:link:form:src-len: {:template "emon.Port.srcLen({0})" :arity 1}
    public static int srcLen(Src s) {
        return s.b.len();
    }

    // @kin:link:form:src-u8: {:template "emon.Port.srcU8({0}, {1})" :arity 2}
    public static int srcU8(Src s, int i) {
        if (i < 0 || i >= s.b.len()) {
            throw new IndexOutOfBoundsException("source index " + i + " is outside 0.." + s.b.len());
        }
        int v = s.b.at(i);
        if (v < 0) {
            s.missed = true;
            return 0;
        }
        return v;
    }

    // @kin:link:form:src-missed: {:template "emon.Port.srcMissed({0})" :arity 1}
    public static boolean srcMissed(Src s) {
        return s.missed;
    }

    // @kin:link:form:src-slice: {:template "emon.Port.srcSlice({0}, {1}, {2})" :arity 3}
    public static byte[] srcSlice(Src s, int a, int b) {
        if (a < 0 || b < a || b > s.b.len()) {
            throw new IndexOutOfBoundsException("source span " + a + ".." + b + " is outside 0.." + s.b.len());
        }
        byte[] buf = new byte[b - a];
        if (!s.b.span(a, buf)) {
            s.missed = true;
        }
        return buf;
    }

    // @kin:link:form:src-of-cells: {:template "emon.Port.srcOfCells({0})" :arity 1}
    public static Src srcOfCells(Cells c) {
        return new Src(new MemBacking(cellsBytes(c)));
    }

    // @kin:link:form:src-of-blob: {:template "emon.Port.srcOfBlob({0})" :arity 1}
    public static Src srcOfBlob(byte[] b) {
        return new Src(new MemBacking(b));
    }

    // @kin:link:form:src-hint: {:template "emon.Port.srcHint({0}, {1}, {2})" :arity 3}
    public static void srcHint(Src s, int a, int b) {
        s.b.hint(a, b);
    }

    // ---------------------------------------------------------- the Text sink
    //
    // A StringBuilder is UTF-16; the host encodes to UTF-8 when the text
    // leaves. An invalid code point appends U+FFFD, as on every target.

    // @kin:link:form:text-cp: {:template "emon.Port.textCp({0}, {1})" :arity 2}
    public static void textCp(StringBuilder s, int cp) {
        if (cp < 0 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            cp = 0xFFFD;
        }
        s.appendCodePoint(cp);
    }

    // @kin:link:form:text-ascii: {:template "emon.Port.textAscii({0}, {1})" :arity 2}
    public static void textAscii(StringBuilder s, String lit) {
        s.append(lit);
    }

    // @kin:link:form:text-i32: {:template "emon.Port.textI32({0}, {1})" :arity 2}
    public static void textI32(StringBuilder s, int v) {
        s.append(v);
    }

    // ---------------------------------------------------------- limits
    //
    // THE TABLES OF LIMITS AND SETTINGS ARE KIN'S (`emon.limits`, P10.10): a name, a default and a range are read from it. Overrides are
    // the platform's (D436): none here yet; the Engine layer adopts a process-wide table behind a lock, as Go does.

    private static String kinName(int n, java.util.function.IntUnaryOperator ch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(ch.applyAsInt(i));
        }
        return sb.toString();
    }

    // The name of limit `kind` as kin's table spells it (`input-bytes`); `unknown` for a kind it has no row for.
    public static String limitName(int kind) {
        String s = kinName(Limits.lmNameLen(kind), i -> Limits.lmNameCh(kind, i));
        return s.isEmpty() ? "unknown" : s;
    }

    // `input-bytes` is `EMON_LIMIT_INPUT_BYTES`.
    private static String envName(String prefix, String name) {
        return prefix + name.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    // THE ENGINE'S LIMITS (SPEC 13: `Config` carries the limits), as Go's `port.WithLimits` has them (Java has no thread-local a worker pool would
    // respect): the override table is process-wide and `withLimits` keeps it honest. A conversion with overrides holds the lock EXCLUSIVELY (it runs
    // alone), one without holds it shared (it never sees an override). `limitOf` consults the table before `EMON_LIMIT_*`; kin still reads a bound
    // only through `limit-of`. The names and defaults are kin's table (`emon.limits`).
    private static volatile int[][] limitOverrides = null;
    private static final java.util.concurrent.locks.ReentrantReadWriteLock limitLock =
        new java.util.concurrent.locks.ReentrantReadWriteLock();

    // Runs f with the (kind, value) overrides in force.
    public static void withLimits(int[][] overrides, Runnable f) {
        if (overrides == null || overrides.length == 0) {
            limitLock.readLock().lock();
            try {
                f.run();
            } finally {
                limitLock.readLock().unlock();
            }
            return;
        }
        limitLock.writeLock().lock();
        try {
            limitOverrides = overrides;
            f.run();
        } finally {
            limitOverrides = null;
            limitLock.writeLock().unlock();
        }
    }

    // @kin:link:form:limit-of: {:template "emon.Port.limitOf({0})" :arity 1}
    public static int limitOf(int kind) {
        int[][] ov = limitOverrides;
        if (ov != null) {
            for (int[] o : ov) {
                if (o[0] == kind) {
                    return o[1];
                }
            }
        }
        String v = System.getenv(envName("EMON_LIMIT_", limitName(kind)));
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // fall through to the default, as the other hosts do
            }
        }
        return Limits.lmDefault(kind);
    }

    // THE PAYLOAD OF A BREACH (P10.10, D435): which limit, the bound in force and the number the input reached. A host catches it by its TYPE and
    // names the error from `name()`; nothing reads the words of the message, which is for a person.
    public static final class Breach extends RuntimeException {
        public final int kind;
        public final int limit;
        public final int actual;

        public Breach(int kind, int limit, int actual) {
            super("the " + limitName(kind).replace('-', ' ') + " limit is " + limit
                + " and this input reached " + actual + ". Raise it with "
                + envName("EMON_LIMIT_", limitName(kind)) + ", or find out why the input needs that much -- a limit is a"
                + " bound on emon, not a statement about the document.");
            this.kind = kind;
            this.limit = limit;
            this.actual = actual;
        }

        public String name() {
            return limitName(kind);
        }
    }

    // @kin:link:form:limit-breach: {:template "emon.Port.limitBreach({0}, {1})" :arity 2}
    public static int limitBreach(int kind, int actual) {
        throw new Breach(kind, limitOf(kind), actual);
    }

    // @kin:link:form:setting-of: {:template "emon.Port.settingOf({0})" :arity 1}
    public static int settingOf(int kind) {
        String name = kinName(Limits.sgNameLen(kind), i -> Limits.sgNameCh(kind, i));
        String v = System.getenv(envName("EMON_SETTING_", name.isEmpty() ? "unknown" : name));
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return Limits.sgDefault(kind);
    }

    // ------------------------------------------------------------- Crypto
    //
    // THE `Crypto` PORT: see the Rust half (host/rust/src/port.rs) for the
    // contract. java.security and javax.crypto have MD5, SHA-2 and AES; RC4 is
    // written out. Every form takes a span of a Blob and APPENDS its output to
    // a Cells, answering how many cells it appended, or -1 for an unusable key.

    private static int pushBytes(Cells out, byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            cellsPush(out, b[i] & 0xFF);
        }
        return n;
    }

    private static byte[] digest(String alg, byte[] data, int off, int len) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(alg);
            md.update(data, off, len);
            return md.digest();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // @kin:link:form:crypto-md5: {:template "emon.Port.cryptoMd5({0}, {1}, {2}, {3})" :arity 4}
    public static int cryptoMd5(byte[] data, int off, int len, Cells out) {
        byte[] h = digest("MD5", data, off, len);
        return pushBytes(out, h, h.length);
    }

    // @kin:link:form:crypto-sha256: {:template "emon.Port.cryptoSha256({0}, {1}, {2}, {3})" :arity 4}
    public static int cryptoSha256(byte[] data, int off, int len, Cells out) {
        byte[] h = digest("SHA-256", data, off, len);
        return pushBytes(out, h, h.length);
    }

    // @kin:link:form:crypto-sha384: {:template "emon.Port.cryptoSha384({0}, {1}, {2}, {3})" :arity 4}
    public static int cryptoSha384(byte[] data, int off, int len, Cells out) {
        byte[] h = digest("SHA-384", data, off, len);
        return pushBytes(out, h, h.length);
    }

    // @kin:link:form:crypto-sha512: {:template "emon.Port.cryptoSha512({0}, {1}, {2}, {3})" :arity 4}
    public static int cryptoSha512(byte[] data, int off, int len, Cells out) {
        byte[] h = digest("SHA-512", data, off, len);
        return pushBytes(out, h, h.length);
    }

    // @kin:link:form:crypto-rc4: {:template "emon.Port.cryptoRc4({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
    public static int cryptoRc4(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, Cells out) {
        if (klen < 1 || klen > 256) {
            return -1;
        }
        if (koff < 0 || koff + klen > key.length || doff < 0 || dlen < 0 || doff + dlen > data.length) {
            throw new IndexOutOfBoundsException();
        }
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[koff + (i % klen)] & 0xFF)) & 255;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        int a = 0;
        int b = 0;
        for (int n = 0; n < dlen; n++) {
            a = (a + 1) & 255;
            b = (b + s[a]) & 255;
            int t = s[a];
            s[a] = s[b];
            s[b] = t;
            cellsPush(out, (data[doff + n] & 0xFF) ^ s[(s[a] + s[b]) & 255]);
        }
        return dlen;
    }

    private static javax.crypto.Cipher aesEcb(int mode, byte[] key, int koff, int klen) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            c.init(mode, new javax.crypto.spec.SecretKeySpec(key, koff, klen, "AES"));
            return c;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // @kin:link:form:crypto-aes-decrypt: {:template "emon.Port.cryptoAesDecrypt({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})" :arity 8}
    public static int cryptoAesDecrypt(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, int pad, Cells out) {
        if (!(klen == 16 || klen == 24 || klen == 32)) {
            return -1;
        }
        if (doff < 0 || dlen < 0 || doff + dlen > data.length) {
            throw new IndexOutOfBoundsException();
        }
        if (dlen < 32) {
            return 0;
        }
        int n = (dlen - 16) / 16;
        javax.crypto.Cipher c = aesEcb(javax.crypto.Cipher.DECRYPT_MODE, key, koff, klen);
        byte[] plain;
        try {
            plain = c.doFinal(data, doff + 16, n * 16);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        for (int b = 0; b < n; b++) {
            for (int i = 0; i < 16; i++) {
                plain[b * 16 + i] ^= data[doff + b * 16 + i];
            }
        }
        int len = plain.length;
        if (pad == 1 && len > 0) {
            int p = plain[len - 1] & 0xFF;
            if (p >= 1 && p <= 16 && p <= len) {
                len -= p;
            }
        }
        return pushBytes(out, plain, len);
    }

    // @kin:link:form:crypto-aes-encrypt: {:template "emon.Port.cryptoAesEncrypt({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
    public static int cryptoAesEncrypt(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, Cells out) {
        if (!(klen == 16 || klen == 24 || klen == 32)) {
            return -1;
        }
        if (doff < 0 || dlen < 0 || doff + dlen > data.length) {
            throw new IndexOutOfBoundsException();
        }
        if (dlen < 16) {
            return 0;
        }
        int n = (dlen - 16) / 16;
        javax.crypto.Cipher c = aesEcb(javax.crypto.Cipher.ENCRYPT_MODE, key, koff, klen);
        byte[] prev = java.util.Arrays.copyOfRange(data, doff, doff + 16);
        byte[] ct = new byte[n * 16];
        byte[] blk = new byte[16];
        try {
            for (int b = 0; b < n; b++) {
                for (int i = 0; i < 16; i++) {
                    blk[i] = (byte) (data[doff + 16 + b * 16 + i] ^ prev[i]);
                }
                prev = c.doFinal(blk);
                System.arraycopy(prev, 0, ct, b * 16, 16);
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return pushBytes(out, ct, ct.length);
    }

    // ;; P6.3 bulk forms (D197): see the Rust half for the contracts.

    // @kin:link:form:bitmap-put-span: {:template "emon.Port.bitmapPutSpan({0}, {1}, {2}, {3}, {4}, {5})" :arity 6}
    // See the Rust half: the bytes y*stride+off+k := the low eight bits of src[from+k], k in 0..n. Answers 0.
    public static int bitmapPutSpan(Bitmap b, int y, int off, Cells src, int from, int n) {
        int at = y * b.stride + off;
        if (n < 0 || at < 0 || at + n > b.d.length || from < 0 || from + n > src.n) {
            throw new IndexOutOfBoundsException("bitmap-put-span");
        }
        byte[] d = b.d;
        int[] s = src.v;
        for (int k = 0; k < n; k++) {
            d[at + k] = (byte) s[from + k];
        }
        return 0;
    }

    // @kin:link:form:bitmap-gray-into: {:template "emon.Port.bitmapGrayInto({0}, {1})" :arity 2}
    public static int bitmapGrayInto(Bitmap src, Bitmap dst) {
        if (src.w != dst.w || src.h != dst.h || dst.ch != 1) {
            throw new IllegalArgumentException("bitmap size");
        }
        byte[] s = src.d, d = dst.d;
        int ch = src.ch;
        for (int y = 0; y < src.h; y++) {
            int si = y * src.stride, di = y * dst.stride;
            if (ch < 3) {
                for (int x = 0; x < src.w; x++) {
                    d[di + x] = s[si + x * ch];
                }
            } else {
                for (int x = 0; x < src.w; x++) {
                    int p = si + x * ch;
                    d[di + x] = (byte) ((77 * (s[p] & 0xFF) + 150 * (s[p + 1] & 0xFF) + 29 * (s[p + 2] & 0xFF) + 128) >> 8);
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-hist-rgb: {:template "emon.Port.bitmapHistRgb({0}, {1}, {2})" :arity 3}
    public static int bitmapHistRgb(Bitmap src, Cells cells, int off) {
        if (off < 0 || off + 4096 > cells.n) {
            throw new IndexOutOfBoundsException();
        }
        int[] bins = cells.v;
        byte[] s = src.d;
        int ch = src.ch;
        for (int y = 0; y < src.h; y++) {
            int p = y * src.stride;
            if (ch < 3) {
                for (int x = 0; x < src.w; x++, p += ch) {
                    bins[off + (s[p] & 0xFF) / 16 * 273]++;
                }
            } else {
                for (int x = 0; x < src.w; x++, p += ch) {
                    bins[off + (((s[p] & 0xF0) << 4) | (s[p + 1] & 0xF0) | ((s[p + 2] & 0xF0) >> 4))]++;
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-lut-into: {:template "emon.Port.bitmapLutInto({0}, {1}, {2}, {3})" :arity 4}
    public static int bitmapLutInto(Bitmap src, Cells lut, int off, Bitmap dst) {
        if (src.w != dst.w || src.h != dst.h || dst.ch != 1) {
            throw new IllegalArgumentException("bitmap size");
        }
        if (off < 0 || off + 4096 > lut.n) {
            throw new IndexOutOfBoundsException();
        }
        int[] t = lut.v;
        byte[] s = src.d, d = dst.d;
        int ch = src.ch;
        for (int y = 0; y < src.h; y++) {
            int p = y * src.stride, q = y * dst.stride;
            if (ch < 3) {
                for (int x = 0; x < src.w; x++, p += ch) {
                    d[q + x] = (byte) t[off + (s[p] & 0xFF) / 16 * 273];
                }
            } else {
                for (int x = 0; x < src.w; x++, p += ch) {
                    d[q + x] = (byte) t[off + (((s[p] & 0xF0) << 4) | (s[p + 1] & 0xF0) | ((s[p + 2] & 0xF0) >> 4))];
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-hash: {:template "emon.Port.bitmapHash({0}, {1}, {2})" :arity 3}
    public static int bitmapHash(Bitmap b, Cells out, int off) {
        if (off < 0 || off + 2 > out.n) {
            throw new IndexOutOfBoundsException();
        }
        long h = 0xcbf29ce484222325L;
        int[] head = {b.w, b.h, b.ch};
        for (int v : head) {
            for (int k = 0; k < 4; k++) {
                h = (h ^ ((v >>> (8 * k)) & 255)) * 0x100000001b3L;
            }
        }
        byte[] d = b.d;
        int n = b.w * b.ch;
        for (int y = 0; y < b.h; y++) {
            int e = y * b.stride + n;
            for (int i = y * b.stride; i < e; i++) {
                h = (h ^ (d[i] & 0xFF)) * 0x100000001b3L;
            }
        }
        out.v[off] = (int) (h >>> 32);
        out.v[off + 1] = (int) h;
        return 0;
    }

    // @kin:link:form:bitmap-quarter-into: {:template "emon.Port.bitmapQuarterInto({0}, {1}, {2})" :arity 3}
    public static int bitmapQuarterInto(Bitmap src, Bitmap dst, int turns) {
        int q = ((turns % 4) + 4) % 4;
        if (src.ch != dst.ch || (q % 2 == 0 && (src.w != dst.w || src.h != dst.h))
            || (q % 2 == 1 && (src.w != dst.h || src.h != dst.w))) {
            throw new IllegalArgumentException("bitmap size");
        }
        int ch = src.ch;
        for (int y = 0; y < dst.h; y++) {
            for (int x = 0; x < dst.w; x++) {
                int sx, sy;
                switch (q) {
                    case 0: sx = x; sy = y; break;
                    case 1: sx = y; sy = src.h - 1 - x; break;
                    case 2: sx = src.w - 1 - x; sy = src.h - 1 - y; break;
                    default: sx = src.w - 1 - y; sy = x; break;
                }
                System.arraycopy(src.d, sy * src.stride + sx * ch, dst.d, y * dst.stride + x * ch, ch);
            }
        }
        return 0;
    }

    // ================================================================ P7.U BitmapSet
    //
    // The pixels a page unit may read (see the Rust half for the contract): the bitmaps its answers carried by request id
    // and the ones kin makes (`bms-put`, ids from KIN_VIEW_BASE).
    public static final class BitmapSet {
        public final java.util.HashMap<Integer, Bitmap> answered = new java.util.HashMap<>();
        public final java.util.HashMap<Integer, Bitmap> made = new java.util.HashMap<>();
        final Bitmap none = new Bitmap(0, 0, 1, new byte[0]);
    }

    public static BitmapSet newBitmapSet() {
        return new BitmapSet();
    }

    // @kin:link:form:bms-get: {:template "emon.Port.bmsGet({0}, {1})" :arity 2}
    public static Bitmap bmsGet(BitmapSet s, int id) {
        Bitmap b = s.made.get(id);
        if (b != null) {
            return b;
        }
        b = s.answered.get(id);
        return b != null ? b : s.none;
    }

    // @kin:link:form:bms-has: {:template "emon.Port.bmsHas({0}, {1})" :arity 2}
    public static int bmsHas(BitmapSet s, int id) {
        return s.made.containsKey(id) || s.answered.containsKey(id) ? 1 : 0;
    }

    // @kin:link:form:bms-put: {:template "emon.Port.bmsPut({0}, {1}, {2}, {3}, {4})" :arity 5}
    public static Bitmap bmsPut(BitmapSet s, int id, int w, int h, int ch) {
        if (bmsHas(s, id) == 1) {
            throw new IllegalStateException("bms_put: id is already in the set");
        }
        Bitmap b = bitmapNew(w, h, ch);
        s.made.put(id, b);
        return b;
    }
}
