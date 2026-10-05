// The C# half of emon's host ports. See the Rust half for the reasoning.
//
// @kin:link:ns: emon.port

namespace emon;

public static class Port {
    // ---------------------------------------------------------- the Blob

    // @kin:link:form:blob-len: {:template "emon.Port.BlobLen({0})" :arity 1}
    public static int BlobLen(byte[] b) {
        return b.Length;
    }

    // @kin:link:form:blob-u8: {:template "emon.Port.BlobU8({0}, {1})" :arity 2}
    public static int BlobU8(byte[] b, int i) {
        return b[i];
    }

    // @kin:link:form:blob-u16: {:template "emon.Port.BlobU16({0}, {1})" :arity 2}
    public static int BlobU16(byte[] b, int i) {
        return (b[i] << 8) | b[i + 1];
    }

    // @kin:link:form:blob-u32: {:template "emon.Port.BlobU32({0}, {1})" :arity 2}
    public static int BlobU32(byte[] b, int i) {
        return unchecked((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]);
    }

    // @kin:link:form:blob-u16le: {:template "emon.Port.BlobU16Le({0}, {1})" :arity 2}
    public static int BlobU16Le(byte[] b, int i) {
        return (b[i + 1] << 8) | b[i];
    }

    // @kin:link:form:blob-u32le: {:template "emon.Port.BlobU32Le({0}, {1})" :arity 2}
    public static int BlobU32Le(byte[] b, int i) {
        return unchecked((b[i + 3] << 24) | (b[i + 2] << 16) | (b[i + 1] << 8) | b[i]);
    }

    // ---------------------------------------------------------- the Cells
    public sealed class Cells {
        internal int[] V = new int[16];
        internal int N = 0;
    }

    public static Cells NewCells() {
        return new Cells();
    }

    // @kin:link:form:cells-len: {:template "emon.Port.CellsLen({0})" :arity 1}
    public static int CellsLen(Cells c) {
        return c.N;
    }

    // @kin:link:form:cells-at: {:template "emon.Port.CellsAt({0}, {1})" :arity 2}
    public static int CellsAt(Cells c, int i) {
        if (i < 0 || i >= c.N) {
            throw new System.IndexOutOfRangeException();
        }
        return c.V[i];
    }

    // @kin:link:form:cells-set: {:template "emon.Port.CellsSet({0}, {1}, {2})" :arity 3}
    public static void CellsSet(Cells c, int i, int v) {
        if (i < 0 || i >= c.N) {
            throw new System.IndexOutOfRangeException();
        }
        c.V[i] = v;
    }

    // @kin:link:form:cells-push: {:template "emon.Port.CellsPush({0}, {1})" :arity 2}
    public static int CellsPush(Cells c, int v) {
        if (c.N == c.V.Length) {
            var w = new int[c.V.Length * 2];
            System.Array.Copy(c.V, w, c.N);
            c.V = w;
        }
        c.V[c.N] = v;
        return c.N++;
    }

    // @kin:link:form:cells-drop: {:template "emon.Port.CellsDrop({0}, {1})" :arity 2}
    public static void CellsDrop(Cells c, int n) {
        if (n < 0 || n > c.N) {
            throw new System.IndexOutOfRangeException();
        }
        c.N = n;
    }

    // @kin:link:form:cells-grow: {:template "emon.Port.CellsGrow({0}, {1}, {2})" :arity 3}
    //
    // Appends n copies of v (what a loop of `cells-push` does, in one call).
    public static void CellsGrow(Cells c, int n, int v) {
        if (n < 0) {
            throw new System.IndexOutOfRangeException();
        }
        int need = c.N + n;
        if (need > c.V.Length) {
            var w = new int[System.Math.Max(need, c.V.Length * 2)];
            System.Array.Copy(c.V, w, c.N);
            c.V = w;
        }
        System.Array.Fill(c.V, v, c.N, n);
        c.N = need;
    }

    // @kin:link:form:cells-bytes: {:template "emon.Port.CellsBytes({0})" :arity 1}
    //
    // A Blob VIEW of a Cells of bytes: a snapshot (see the Rust half).
    public static byte[] CellsBytes(Cells c) {
        byte[] b = new byte[c.N];
        for (int i = 0; i < c.N; i++) {
            b[i] = (byte) c.V[i];
        }
        return b;
    }

    // ---------------------------------------------------------- the Buf
    //
    // A growable run of bytes (see the Rust half for the contract).
    public sealed class Buf {
        internal byte[] V = new byte[64];
        internal int N = 0;
    }

    public static Buf NewBuf() {
        return new Buf();
    }

    // @kin:link:form:buf-len: {:template "emon.Port.BufLen({0})" :arity 1}
    public static int BufLen(Buf b) {
        return b.N;
    }

    // @kin:link:form:buf-at: {:template "emon.Port.BufAt({0}, {1})" :arity 2}
    public static int BufAt(Buf b, int i) {
        if (i < 0 || i >= b.N) {
            throw new System.IndexOutOfRangeException();
        }
        return b.V[i];
    }

    // @kin:link:form:buf-push: {:template "emon.Port.BufPush({0}, {1})" :arity 2}
    public static int BufPush(Buf b, int v) {
        if (b.N == b.V.Length) {
            System.Array.Resize(ref b.V, b.V.Length * 2);
        }
        b.V[b.N++] = unchecked((byte) v);
        return b.N;
    }

    // @kin:link:form:buf-set: {:template "emon.Port.BufSet({0}, {1}, {2})" :arity 3}
    public static void BufSet(Buf b, int i, int v) {
        if (i < 0 || i >= b.N) {
            throw new System.IndexOutOfRangeException();
        }
        b.V[i] = unchecked((byte) v);
    }

    // @kin:link:form:buf-truncate: {:template "emon.Port.BufTruncate({0}, {1})" :arity 2}
    public static void BufTruncate(Buf b, int n) {
        if (n < 0 || n > b.N) {
            throw new System.IndexOutOfRangeException();
        }
        b.N = n;
    }

    // @kin:link:form:buf-append-blob: {:template "emon.Port.BufAppendBlob({0}, {1}, {2}, {3})" :arity 4}
    public static void BufAppendBlob(Buf b, byte[] blob, int start, int end) {
        if (start < 0 || end < start || end > blob.Length) {
            throw new System.IndexOutOfRangeException();
        }
        int k = end - start;
        if (b.N + k > b.V.Length) {
            System.Array.Resize(ref b.V, System.Math.Max(b.N + k, b.V.Length * 2));
        }
        System.Array.Copy(blob, start, b.V, b.N, k);
        b.N += k;
    }

    // @kin:link:form:buf-blob: {:template "emon.Port.BufBlob({0})" :arity 1}
    //
    // A copy (a `byte[]` cannot be a prefix of a larger array).
    public static byte[] BufBlob(Buf b) {
        var r = new byte[b.N];
        System.Array.Copy(b.V, r, b.N);
        return r;
    }

    // ---------------------------------------------------------- the Bitmap
    //
    // An opaque 8-bit pixel buffer (see the Rust half for the contract). The
    // check is on the byte index, not on each coordinate.
    public sealed class Bitmap {
        internal readonly int W, H, Stride, Ch;
        internal readonly byte[] D;

        public Bitmap(int w, int h, int ch, byte[] d) {
            if (d.Length != w * h * ch) {
                throw new System.ArgumentException("bitmap size");
            }
            W = w;
            H = h;
            Stride = w * ch;
            Ch = ch;
            D = d;
        }
    }

    // @kin:link:form:bitmap-new: {:template "emon.Port.BitmapNew({0}, {1}, {2})" :arity 3}
    public static Bitmap BitmapNew(int w, int h, int ch) {
        return new Bitmap(w, h, ch, new byte[w * h * ch]);
    }

    // @kin:link:form:bitmap-w: {:template "emon.Port.BitmapW({0})" :arity 1}
    public static int BitmapW(Bitmap b) {
        return b.W;
    }

    // @kin:link:form:bitmap-h: {:template "emon.Port.BitmapH({0})" :arity 1}
    public static int BitmapH(Bitmap b) {
        return b.H;
    }

    // @kin:link:form:bitmap-stride: {:template "emon.Port.BitmapStride({0})" :arity 1}
    public static int BitmapStride(Bitmap b) {
        return b.Stride;
    }

    // @kin:link:form:bitmap-channels: {:template "emon.Port.BitmapChannels({0})" :arity 1}
    public static int BitmapChannels(Bitmap b) {
        return b.Ch;
    }

    // @kin:link:form:bitmap-u8: {:template "emon.Port.BitmapU8({0}, {1}, {2}, {3})" :arity 4}
    public static int BitmapU8(Bitmap b, int x, int y, int c) {
        return b.D[y * b.Stride + x * b.Ch + c];
    }

    // @kin:link:form:bitmap-set-u8: {:template "emon.Port.BitmapSetU8({0}, {1}, {2}, {3}, {4})" :arity 5}
    public static void BitmapSetU8(Bitmap b, int x, int y, int c, int v) {
        b.D[y * b.Stride + x * b.Ch + c] = (byte) v;
    }

    // ---- bulk forms (P2.7): the per-pixel loops the spike priced, done in the
    // host. See the Rust half for the contracts.

    // @kin:link:form:bitmap-histogram: {:template "emon.Port.BitmapHistogram({0}, {1}, {2})" :arity 3}
    public static void BitmapHistogram(Bitmap b, Cells hist, int off) {
        if (off < 0 || off + 256 > hist.N) {
            throw new System.IndexOutOfRangeException();
        }
        // Four partial histograms, for the Rust half's reason.
        var p0 = new int[256]; var p1 = new int[256]; var p2 = new int[256]; var p3 = new int[256];
        var d = b.D;
        int ch = b.Ch;
        for (int y = 0; y < b.H; y++) {
            int s = y * b.Stride;
            int end = s + b.W * ch;
            int i = s;
            for (; i + 4 * ch <= end; i += 4 * ch) {
                p0[d[i]]++;
                p1[d[i + ch]]++;
                p2[d[i + 2 * ch]]++;
                p3[d[i + 3 * ch]]++;
            }
            for (; i < end; i += ch) {
                p0[d[i]]++;
            }
        }
        var bins = hist.V;
        for (int v = 0; v < 256; v++) {
            bins[off + v] += p0[v] + p1[v] + p2[v] + p3[v];
        }
    }

    // @kin:link:form:bitmap-threshold-into: {:template "emon.Port.BitmapThresholdInto({0}, {1}, {2})" :arity 3}
    public static int BitmapThresholdInto(Bitmap g, int t, Bitmap m) {
        if (g.W != m.W || g.H != m.H) {
            throw new System.ArgumentException("bitmap size");
        }
        int ink = 0;
        var gd = g.D; var md = m.D;
        for (int y = 0; y < g.H; y++) {
            int gi = y * g.Stride, mi = y * m.Stride;
            if (g.Ch == 1 && m.Ch == 1) {
                // The common case: unit strides.
                for (int x = 0; x < g.W; x++) {
                    int on = 1 - (int)((uint)(t - gd[gi + x]) >> 31);
                    md[mi + x] = (byte)(-on);
                    ink += on;
                }
                continue;
            }
            for (int x = 0; x < g.W; x++) {
                // on = 1 when v <= t, branch-free (the sign bit of t - v).
                int on = 1 - (int)((uint)(t - gd[gi]) >> 31);
                md[mi] = (byte)(-on);
                ink += on;
                gi += g.Ch;
                mi += m.Ch;
            }
        }
        return ink;
    }

    // @kin:link:form:bitmap-next-nonzero: {:template "emon.Port.BitmapNextNonzero({0}, {1}, {2})" :arity 3}
    public static int BitmapNextNonzero(Bitmap b, int x, int y) {
        int s = y * b.Stride;
        if (b.Ch == 1) {
            // Vectorised by the runtime.
            int k = System.MemoryExtensions.IndexOfAnyExcept(new System.ReadOnlySpan<byte>(b.D, s + x, b.W - x), (byte)0);
            return k < 0 ? b.W : x + k;
        }
        for (int i = x; i < b.W; i++) {
            if (b.D[s + i * b.Ch] != 0) {
                return i;
            }
        }
        return b.W;
    }

    // @kin:link:form:bitmap-integral: {:template "emon.Port.BitmapIntegral({0}, {1}, {2})" :arity 3}
    public static void BitmapIntegral(Bitmap b, Cells sum, Cells sq) {
        int w1 = b.W + 1;
        int n = w1 * (b.H + 1);
        // Only row 0 and column 0 need zeroing: every other cell is written below.
        if (sum.V.Length < n) {
            sum.V = new int[n];
        }
        sum.N = n;
        if (sq.V.Length < n) {
            sq.V = new int[n];
        }
        sq.N = n;
        int[] s = sum.V, q = sq.V;
        byte[] d = b.D;
        unchecked {
            for (int x = 0; x < w1; x++) {
                s[x] = 0;
                q[x] = 0;
            }
            for (int y = 0; y < b.H; y++) {
                s[(y + 1) * w1] = 0;
                q[(y + 1) * w1] = 0;
                int rs = 0, rq = 0;
                int src = y * b.Stride;
                int p = y * w1 + 1, c = (y + 1) * w1 + 1;
                for (int x = 0; x < b.W; x++) {
                    int v = d[src + x * b.Ch];
                    rs += v;
                    rq += v * v;
                    s[c + x] = s[p + x] + rs;
                    q[c + x] = q[p + x] + rq;
                }
            }
        }
    }

    // ---------------------------------------------------------- the Src
    //
    // An input byte source as kin sees it: a per-unit view over a backing
    // that may miss (see the Rust half for the contract). `Src.View()` is a
    // fresh view of the same backing, for the host's re-run.

    public interface ISrcBacking {
        int Len();

        // The byte 0..255, or -1 when it is not resident (the backing records the miss).
        int At(int i);

        // Fill buf from s; false when any of it is not resident (recorded).
        bool Span(int s, byte[] buf);

        // Bytes [s, e) will be wanted. May be ignored.
        void Hint(int s, int e);
    }

    private sealed class MemBacking : ISrcBacking {
        private readonly byte[] v;

        public MemBacking(byte[] v) {
            this.v = v;
        }

        public int Len() {
            return v.Length;
        }

        public int At(int i) {
            return v[i];
        }

        public bool Span(int s, byte[] buf) {
            System.Array.Copy(v, s, buf, 0, buf.Length);
            return true;
        }

        public void Hint(int s, int e) {
        }
    }

    public sealed class Src {
        internal readonly ISrcBacking B;
        internal volatile bool Missed = false;

        public Src(ISrcBacking b) {
            B = b;
        }

        // A fresh view of the same backing: the missed flag is clear.
        public Src View() {
            return new Src(B);
        }

        public bool WasMissed() {
            return Missed;
        }
    }

    // @kin:link:form:src-len: {:template "emon.Port.SrcLen({0})" :arity 1}
    public static int SrcLen(Src s) {
        return s.B.Len();
    }

    // @kin:link:form:src-u8: {:template "emon.Port.SrcU8({0}, {1})" :arity 2}
    public static int SrcU8(Src s, int i) {
        if (i < 0 || i >= s.B.Len()) {
            throw new System.IndexOutOfRangeException("source index " + i + " is outside 0.." + s.B.Len());
        }
        int v = s.B.At(i);
        if (v < 0) {
            s.Missed = true;
            return 0;
        }
        return v;
    }

    // @kin:link:form:src-missed: {:template "emon.Port.SrcMissed({0})" :arity 1}
    public static bool SrcMissed(Src s) {
        return s.Missed;
    }

    // @kin:link:form:src-slice: {:template "emon.Port.SrcSlice({0}, {1}, {2})" :arity 3}
    public static byte[] SrcSlice(Src s, int a, int b) {
        if (a < 0 || b < a || b > s.B.Len()) {
            throw new System.IndexOutOfRangeException("source span " + a + ".." + b + " is outside 0.." + s.B.Len());
        }
        var buf = new byte[b - a];
        if (!s.B.Span(a, buf)) {
            s.Missed = true;
        }
        return buf;
    }

    // @kin:link:form:src-of-cells: {:template "emon.Port.SrcOfCells({0})" :arity 1}
    public static Src SrcOfCells(Cells c) {
        return new Src(new MemBacking(CellsBytes(c)));
    }

    // @kin:link:form:src-of-blob: {:template "emon.Port.SrcOfBlob({0})" :arity 1}
    public static Src SrcOfBlob(byte[] b) {
        return new Src(new MemBacking(b));
    }

    // @kin:link:form:src-hint: {:template "emon.Port.SrcHint({0}, {1}, {2})" :arity 3}
    public static void SrcHint(Src s, int a, int b) {
        s.B.Hint(a, b);
    }

    // ---------------------------------------------------------- the Text sink
    //
    // A StringBuilder is UTF-16; the host encodes to UTF-8 when the text
    // leaves. An invalid code point appends U+FFFD, as on every target.

    // @kin:link:form:text-cp: {:template "emon.Port.TextCp({0}, {1})" :arity 2}
    public static void TextCp(System.Text.StringBuilder s, int cp) {
        if (!System.Text.Rune.IsValid(cp)) {
            cp = 0xFFFD;
        }
        s.Append(new System.Text.Rune(cp).ToString());
    }

    // @kin:link:form:text-ascii: {:template "emon.Port.TextAscii({0}, {1})" :arity 2}
    public static void TextAscii(System.Text.StringBuilder s, string lit) {
        s.Append(lit);
    }

    // @kin:link:form:text-i32: {:template "emon.Port.TextI32({0}, {1})" :arity 2}
    //
    // INVARIANT CULTURE: a locale's negative sign must not reach the output.
    public static void TextI32(System.Text.StringBuilder s, int v) {
        s.Append(v.ToString(System.Globalization.CultureInfo.InvariantCulture));
    }

    // ---------------------------------------------------------- limits

    // @kin:link:form:limit-of: {:template "emon.Port.LimitOf({0})" :arity 1}
    public static int LimitOf(int kind) {
        string v = System.Environment.GetEnvironmentVariable("EMON_LIMIT_" + LimitName(kind));
        if (v != null && int.TryParse(v, out int n)) return n;
        return LimitDefault(kind);
    }

    // @kin:link:form:limit-breach: {:template "emon.Port.LimitBreach({0}, {1})" :arity 2}
    public static int LimitBreach(int kind, int actual) {
        string name = LimitName(kind);
        throw new System.Exception(
            "the " + name.ToLower().Replace('_', ' ') + " limit is " + LimitOf(kind)
            + " and this input reached " + actual + ". Raise it with EMON_LIMIT_"
            + name + ", or find out why the input needs that much -- a limit is a"
            + " bound on emon, not a statement about the document.");
    }

    // @kin:link:form:setting-of: {:template "emon.Port.SettingOf({0})" :arity 1}
    public static int SettingOf(int kind) {
        string v = System.Environment.GetEnvironmentVariable("EMON_SETTING_" + SettingName(kind));
        if (v != null && int.TryParse(v, out int n)) return n;
        return SettingDefault(kind);
    }

    private static string LimitName(int kind) {
        switch (kind) {
            case 0: return "INPUT_BYTES";
            case 1: return "STREAM_BYTES";
            case 2: return "TOTAL_BYTES";
            case 3: return "OBJECTS";
            case 4: return "REF_DEPTH";
            case 5: return "OPERATORS";
            case 6: return "FORM_DEPTH";
            case 7: return "PATTERN_DEPTH";
            case 8: return "PAGES";
            case 9: return "PIXELS";
            case 10: return "OCR_REQUESTS";
            default: return "UNKNOWN";
        }
    }

    private static int LimitDefault(int kind) {
        switch (kind) {
            case 0: return 1073741824;
            case 1: return 268435456;
            case 2: return 1073741824;
            case 3: return 10000000;
            case 4: return 64;
            case 5: return 50000000;
            case 6: return 32;
            case 7: return 8;
            case 8: return 100000;
            case 9: return 268435456;
            case 10: return 100000;
            default: return 0;
        }
    }

    private static string SettingName(int kind) {
        switch (kind) {
            case 0: return "OCR_MODE";
            case 1: return "TASKS";
            default: return "UNKNOWN";
        }
    }

    private static int SettingDefault(int kind) {
        switch (kind) {
            case 0: return 0;
            case 1: return 0;
            default: return 0;
        }
    }

    // ------------------------------------------------------------- Crypto
    //
    // THE `Crypto` PORT: see the Rust half (host/rust/src/port.rs) for the
    // contract. System.Security.Cryptography has MD5, SHA-2 and AES; RC4 is
    // written out (.NET has none). Every form takes a span of a Blob and
    // APPENDS its output to a Cells, answering how many cells it appended, or
    // -1 for an unusable key.

    private static int PushBytes(Cells o, byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            CellsPush(o, b[i]);
        }
        return n;
    }

    // @kin:link:form:crypto-md5: {:template "emon.Port.CryptoMd5({0}, {1}, {2}, {3})" :arity 4}
    public static int CryptoMd5(byte[] data, int off, int len, Cells o) {
        byte[] h = System.Security.Cryptography.MD5.HashData(new System.ReadOnlySpan<byte>(data, off, len));
        return PushBytes(o, h, h.Length);
    }

    // @kin:link:form:crypto-sha256: {:template "emon.Port.CryptoSha256({0}, {1}, {2}, {3})" :arity 4}
    public static int CryptoSha256(byte[] data, int off, int len, Cells o) {
        byte[] h = System.Security.Cryptography.SHA256.HashData(new System.ReadOnlySpan<byte>(data, off, len));
        return PushBytes(o, h, h.Length);
    }

    // @kin:link:form:crypto-sha384: {:template "emon.Port.CryptoSha384({0}, {1}, {2}, {3})" :arity 4}
    public static int CryptoSha384(byte[] data, int off, int len, Cells o) {
        byte[] h = System.Security.Cryptography.SHA384.HashData(new System.ReadOnlySpan<byte>(data, off, len));
        return PushBytes(o, h, h.Length);
    }

    // @kin:link:form:crypto-sha512: {:template "emon.Port.CryptoSha512({0}, {1}, {2}, {3})" :arity 4}
    public static int CryptoSha512(byte[] data, int off, int len, Cells o) {
        byte[] h = System.Security.Cryptography.SHA512.HashData(new System.ReadOnlySpan<byte>(data, off, len));
        return PushBytes(o, h, h.Length);
    }

    // @kin:link:form:crypto-rc4: {:template "emon.Port.CryptoRc4({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
    public static int CryptoRc4(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, Cells o) {
        if (klen < 1 || klen > 256) {
            return -1;
        }
        if (koff < 0 || koff + klen > key.Length || doff < 0 || dlen < 0 || doff + dlen > data.Length) {
            throw new System.IndexOutOfRangeException();
        }
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key[koff + (i % klen)]) & 255;
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
            CellsPush(o, data[doff + n] ^ s[(s[a] + s[b]) & 255]);
        }
        return dlen;
    }

    private static System.Security.Cryptography.Aes AesKey(byte[] key, int koff, int klen) {
        var aes = System.Security.Cryptography.Aes.Create();
        aes.Key = new System.ReadOnlySpan<byte>(key, koff, klen).ToArray();
        return aes;
    }

    // @kin:link:form:crypto-aes-decrypt: {:template "emon.Port.CryptoAesDecrypt({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})" :arity 8}
    public static int CryptoAesDecrypt(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, int pad, Cells o) {
        if (!(klen == 16 || klen == 24 || klen == 32)) {
            return -1;
        }
        if (doff < 0 || dlen < 0 || doff + dlen > data.Length) {
            throw new System.IndexOutOfRangeException();
        }
        if (dlen < 32) {
            return 0;
        }
        int n = (dlen - 16) / 16;
        using var aes = AesKey(key, koff, klen);
        byte[] plain = aes.DecryptEcb(new System.ReadOnlySpan<byte>(data, doff + 16, n * 16),
                                      System.Security.Cryptography.PaddingMode.None);
        for (int b = 0; b < n; b++) {
            for (int i = 0; i < 16; i++) {
                plain[b * 16 + i] ^= data[doff + b * 16 + i];
            }
        }
        int len = plain.Length;
        if (pad == 1 && len > 0) {
            int p = plain[len - 1];
            if (p >= 1 && p <= 16 && p <= len) {
                len -= p;
            }
        }
        return PushBytes(o, plain, len);
    }

    // @kin:link:form:crypto-aes-encrypt: {:template "emon.Port.CryptoAesEncrypt({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
    public static int CryptoAesEncrypt(byte[] key, int koff, int klen, byte[] data, int doff, int dlen, Cells o) {
        if (!(klen == 16 || klen == 24 || klen == 32)) {
            return -1;
        }
        if (doff < 0 || dlen < 0 || doff + dlen > data.Length) {
            throw new System.IndexOutOfRangeException();
        }
        if (dlen < 16) {
            return 0;
        }
        int n = (dlen - 16) / 16;
        using var aes = AesKey(key, koff, klen);
        byte[] prev = new System.ReadOnlySpan<byte>(data, doff, 16).ToArray();
        byte[] ct = new byte[n * 16];
        byte[] blk = new byte[16];
        for (int b = 0; b < n; b++) {
            for (int i = 0; i < 16; i++) {
                blk[i] = (byte) (data[doff + 16 + b * 16 + i] ^ prev[i]);
            }
            prev = aes.EncryptEcb(blk, System.Security.Cryptography.PaddingMode.None);
            System.Array.Copy(prev, 0, ct, b * 16, 16);
        }
        return PushBytes(o, ct, ct.Length);
    }

    // ;; P6.3 bulk forms (D197): see the Rust half for the contracts.

    // @kin:link:form:bitmap-gray-into: {:template "emon.Port.BitmapGrayInto({0}, {1})" :arity 2}
    public static int BitmapGrayInto(Bitmap src, Bitmap dst) {
        if (src.W != dst.W || src.H != dst.H || dst.Ch != 1) {
            throw new System.ArgumentException("bitmap size");
        }
        var s = src.D; var d = dst.D;
        int ch = src.Ch;
        for (int y = 0; y < src.H; y++) {
            int si = y * src.Stride, di = y * dst.Stride;
            if (ch < 3) {
                for (int x = 0; x < src.W; x++) {
                    d[di + x] = s[si + x * ch];
                }
            } else {
                for (int x = 0; x < src.W; x++) {
                    int p = si + x * ch;
                    d[di + x] = (byte)((77 * s[p] + 150 * s[p + 1] + 29 * s[p + 2] + 128) >> 8);
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-hist-rgb: {:template "emon.Port.BitmapHistRgb({0}, {1}, {2})" :arity 3}
    public static int BitmapHistRgb(Bitmap src, Cells cells, int off) {
        if (off < 0 || off + 4096 > cells.N) {
            throw new System.IndexOutOfRangeException();
        }
        var bins = cells.V;
        var s = src.D;
        int ch = src.Ch;
        for (int y = 0; y < src.H; y++) {
            int p = y * src.Stride;
            if (ch < 3) {
                for (int x = 0; x < src.W; x++, p += ch) {
                    bins[off + (s[p] >> 4) * 273]++;
                }
            } else {
                for (int x = 0; x < src.W; x++, p += ch) {
                    bins[off + (((s[p] & 0xF0) << 4) | (s[p + 1] & 0xF0) | ((s[p + 2] & 0xF0) >> 4))]++;
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-lut-into: {:template "emon.Port.BitmapLutInto({0}, {1}, {2}, {3})" :arity 4}
    public static int BitmapLutInto(Bitmap src, Cells lut, int off, Bitmap dst) {
        if (src.W != dst.W || src.H != dst.H || dst.Ch != 1) {
            throw new System.ArgumentException("bitmap size");
        }
        if (off < 0 || off + 4096 > lut.N) {
            throw new System.IndexOutOfRangeException();
        }
        var t = lut.V;
        var s = src.D; var d = dst.D;
        int ch = src.Ch;
        for (int y = 0; y < src.H; y++) {
            int p = y * src.Stride, q = y * dst.Stride;
            if (ch < 3) {
                for (int x = 0; x < src.W; x++, p += ch) {
                    d[q + x] = (byte)t[off + (s[p] >> 4) * 273];
                }
            } else {
                for (int x = 0; x < src.W; x++, p += ch) {
                    d[q + x] = (byte)t[off + (((s[p] & 0xF0) << 4) | (s[p + 1] & 0xF0) | ((s[p + 2] & 0xF0) >> 4))];
                }
            }
        }
        return 0;
    }

    // @kin:link:form:bitmap-hash: {:template "emon.Port.BitmapHash({0}, {1}, {2})" :arity 3}
    public static int BitmapHash(Bitmap b, Cells o, int off) {
        if (off < 0 || off + 2 > o.N) {
            throw new System.IndexOutOfRangeException();
        }
        ulong h = 0xcbf29ce484222325UL;
        int[] head = { b.W, b.H, b.Ch };
        unchecked {
            foreach (int v in head) {
                for (int k = 0; k < 4; k++) {
                    h = (h ^ (ulong)(((uint)v >> (8 * k)) & 255)) * 0x100000001b3UL;
                }
            }
            var d = b.D;
            int n = b.W * b.Ch;
            for (int y = 0; y < b.H; y++) {
                int e = y * b.Stride + n;
                for (int i = y * b.Stride; i < e; i++) {
                    h = (h ^ d[i]) * 0x100000001b3UL;
                }
            }
            o.V[off] = (int)(uint)(h >> 32);
            o.V[off + 1] = (int)(uint)h;
        }
        return 0;
    }

    // @kin:link:form:bitmap-quarter-into: {:template "emon.Port.BitmapQuarterInto({0}, {1}, {2})" :arity 3}
    public static int BitmapQuarterInto(Bitmap src, Bitmap dst, int turns) {
        int q = ((turns % 4) + 4) % 4;
        if (src.Ch != dst.Ch || (q % 2 == 0 && (src.W != dst.W || src.H != dst.H))
            || (q % 2 == 1 && (src.W != dst.H || src.H != dst.W))) {
            throw new System.ArgumentException("bitmap size");
        }
        int ch = src.Ch;
        for (int y = 0; y < dst.H; y++) {
            for (int x = 0; x < dst.W; x++) {
                int sx, sy;
                switch (q) {
                    case 0: sx = x; sy = y; break;
                    case 1: sx = y; sy = src.H - 1 - x; break;
                    case 2: sx = src.W - 1 - x; sy = src.H - 1 - y; break;
                    default: sx = src.W - 1 - y; sy = x; break;
                }
                System.Array.Copy(src.D, sy * src.Stride + sx * ch, dst.D, y * dst.Stride + x * ch, ch);
            }
        }
        return 0;
    }

    // ================================================================ P7.U BitmapSet
    //
    // The pixels a page unit may read (see the Rust half for the contract): the bitmaps its answers carried by request id
    // and the ones kin makes (`bms-put`, ids from KIN_VIEW_BASE).
    public sealed class BitmapSet {
        public readonly System.Collections.Generic.Dictionary<int, Bitmap> Answered = new System.Collections.Generic.Dictionary<int, Bitmap>();
        public readonly System.Collections.Generic.Dictionary<int, Bitmap> Made = new System.Collections.Generic.Dictionary<int, Bitmap>();
        internal readonly Bitmap None = new Bitmap(0, 0, 1, new byte[0]);
    }

    public static BitmapSet NewBitmapSet() {
        return new BitmapSet();
    }

    // @kin:link:form:bms-get: {:template "emon.Port.BmsGet({0}, {1})" :arity 2}
    public static Bitmap BmsGet(BitmapSet s, int id) {
        if (s.Made.TryGetValue(id, out var b)) {
            return b;
        }
        if (s.Answered.TryGetValue(id, out b)) {
            return b;
        }
        return s.None;
    }

    // @kin:link:form:bms-has: {:template "emon.Port.BmsHas({0}, {1})" :arity 2}
    public static int BmsHas(BitmapSet s, int id) {
        return s.Made.ContainsKey(id) || s.Answered.ContainsKey(id) ? 1 : 0;
    }

    // @kin:link:form:bms-put: {:template "emon.Port.BmsPut({0}, {1}, {2}, {3}, {4})" :arity 5}
    public static Bitmap BmsPut(BitmapSet s, int id, int w, int h, int ch) {
        if (BmsHas(s, id) == 1) {
            throw new System.InvalidOperationException("bms_put: id is already in the set");
        }
        var b = BitmapNew(w, h, ch);
        s.Made[id] = b;
        return b;
    }
}
