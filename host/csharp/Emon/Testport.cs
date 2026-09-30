// The C# half of emon's TEST support (DEVLOG P0.5, D044). See the Rust half.
// A failed assertion throws, which xunit reports against the law that threw
// it; throwing keeps this file free of an xunit reference, so the ordinary
// build (`bb check`) compiles it.

namespace emon;

public static class Testport {
    private static bool Inverted() {
        return System.Environment.GetEnvironmentVariable("EMON_TEST_INVERT") == "1";
    }

    private static void Verdict(bool ok, string what) {
        if (ok == Inverted()) {
            throw new System.Exception(what);
        }
    }

    public static void AssertEq(int got, int want, string name) {
        Verdict(got == want, name + ": got " + got + ", want " + want);
    }

    public static void AssertEqAt(int got, int want, string name, int row) {
        Verdict(got == want, name + " [row " + row + "]: got " + got + ", want " + want);
    }

    public static void AssertTextSpan(System.Text.StringBuilder got, byte[] b, int from, int to, string name, int row) {
        string want = System.Text.Encoding.UTF8.GetString(b, from, to - from);
        Verdict(got.ToString() == want, name + " [row " + row + "]: got `" + got + "`, want `" + want + "`");
    }

    public static void AssertEq64(long got, long want, string name) {
        Verdict(got == want, name + ": got " + got + ", want " + want);
    }

    public static void AssertThat(bool ok, string name) {
        Verdict(ok, name + ": not true");
    }

    public static void AssertThatAt(bool ok, string name, int row) {
        Verdict(ok, name + " [row " + row + "]: not true");
    }

    public static void AssertText(System.Text.StringBuilder got, string want, string name) {
        Verdict(got.ToString() == want, name + ": got `" + got + "`, want `" + want + "`");
    }

    public static void AssertCellsHex(Port.Cells c, string hex, string name) {
        byte[] want = HexBlob(hex);
        int n = Port.CellsLen(c);
        bool ok = n == want.Length;
        var got = new System.Text.StringBuilder();
        for (int i = 0; i < n; i++) {
            int v = Port.CellsAt(c, i);
            got.Append((v & 0xFF).ToString("x2"));
            if (ok && v != want[i]) {
                ok = false;
            }
        }
        Verdict(ok, name + ": got " + n + " bytes " + got + ", want " + hex);
    }

    public static byte[] TextBlob(string s) {
        return System.Text.Encoding.UTF8.GetBytes(s);
    }

    public static byte[] HexBlob(string s) {
        var digits = new System.Collections.Generic.List<int>();
        foreach (char ch in s) {
            if (System.Uri.IsHexDigit(ch)) {
                digits.Add(System.Convert.ToInt32(ch.ToString(), 16));
            }
        }
        var outb = new byte[digits.Count / 2];
        for (int i = 0; i < outb.Length; i++) {
            outb[i] = (byte) ((digits[2 * i] << 4) | digits[2 * i + 1]);
        }
        return outb;
    }

    // The same, the file's name being bytes b[from, to) (a corpus row).
    public static byte[] TestFileSpan(byte[] b, int from, int to) {
        return TestFile(System.Text.Encoding.UTF8.GetString(b, from, to - from));
    }

    // A corpus file, read whole; `EMON_TEST_DIR` names the directory.
    public static byte[] TestFile(string name) {
        var dir = System.Environment.GetEnvironmentVariable("EMON_TEST_DIR");
        if (string.IsNullOrEmpty(dir)) {
            dir = "test";
        }
        return System.IO.File.ReadAllBytes(System.IO.Path.Combine(dir, name));
    }

    public static byte[] CellsBlob(Port.Cells c) {
        var outb = new byte[Port.CellsLen(c)];
        for (int i = 0; i < outb.Length; i++) {
            outb[i] = (byte) Port.CellsAt(c, i);
        }
        return outb;
    }

    public static System.Text.StringBuilder NewText() {
        return new System.Text.StringBuilder();
    }

    // ------------------------------------------------ a source that misses
    //
    // A Src over a blob, served in fixed-size CHUNKS of which only some are
    // RESIDENT (P1.11, D058); see the Rust half. Tests only.

    private sealed class ChunkedBacking : Port.ISrcBacking {
        public readonly byte[] Data;
        public readonly int Chunk;
        public readonly bool Sync;
        public readonly bool[] Resident;
        public readonly System.Collections.Generic.List<int> Pending = new System.Collections.Generic.List<int>();
        public int Fills = 0;
        public int Hints = 0;

        public ChunkedBacking(byte[] data, int chunk, bool sync) {
            Data = data;
            Chunk = chunk;
            Sync = sync;
            Resident = new bool[(data.Length + chunk - 1) / chunk];
        }

        private bool Touch(int c) {
            if (Resident[c]) {
                return true;
            }
            if (Sync) {
                Resident[c] = true;
                Fills++;
                return true;
            }
            if (!Pending.Contains(c)) {
                Pending.Add(c);
            }
            return false;
        }

        public int Len() {
            return Data.Length;
        }

        public int At(int i) {
            return Touch(i / Chunk) ? Data[i] : -1;
        }

        public bool Span(int s, byte[] buf) {
            int n = buf.Length;
            if (n == 0) {
                return true;
            }
            bool ok = true;
            for (int c = s / Chunk; c <= (s + n - 1) / Chunk; c++) {
                if (Touch(c)) {
                    int lo = System.Math.Max(s, c * Chunk);
                    int hi = System.Math.Min(s + n, (c + 1) * Chunk);
                    for (int k = lo; k < hi; k++) {
                        buf[k - s] = Data[k];
                    }
                } else {
                    ok = false;
                }
            }
            return ok;
        }

        public void Hint(int s, int e) {
            Hints++;
        }
    }

    // A source over b, in chunks of `chunk` bytes, none resident; sync 1 fills inline.
    public static Port.Src SrcChunked(byte[] b, int chunk, int sync) {
        return new Port.Src(new ChunkedBacking(b, chunk, sync == 1));
    }

    // A fresh view (missed flag clear) of the same chunk cache.
    public static Port.Src SrcView(Port.Src s) {
        return s.View();
    }

    // Control of the cache: op 0 fetch (a is the round: read-ahead of 2^a - 1
    // chunks each side of every pending chunk; answers the chunks made
    // resident), 1 make chunk a resident, 2 evict chunk a, 3 make every chunk
    // resident, 4 the pending count, 5 the resident count, 6 the hints
    // received, 7 the chunks a sync source filled inline, 8 the chunk count.
    public static int SrcCtl(Port.Src s, int op, int a) {
        var b = (ChunkedBacking) s.B;
        int n = b.Resident.Length;
        switch (op) {
            case 0: {
                int r = (1 << System.Math.Min(a, 20)) - 1;
                var pend = new System.Collections.Generic.List<int>(b.Pending);
                b.Pending.Clear();
                int made = 0;
                foreach (int c in pend) {
                    for (int k = System.Math.Max(0, c - r); k <= System.Math.Min(n - 1, c + r); k++) {
                        if (!b.Resident[k]) {
                            b.Resident[k] = true;
                            made++;
                        }
                    }
                }
                return made;
            }
            case 1:
                b.Resident[a] = true;
                b.Pending.Remove(a);
                return 0;
            case 2:
                b.Resident[a] = false;
                return 0;
            case 3:
                for (int k = 0; k < n; k++) {
                    b.Resident[k] = true;
                }
                b.Pending.Clear();
                return 0;
            case 4:
                return b.Pending.Count;
            case 5: {
                int c = 0;
                foreach (bool r in b.Resident) {
                    if (r) {
                        c++;
                    }
                }
                return c;
            }
            case 6:
                return b.Hints;
            case 7:
                return b.Fills;
            case 8:
                return n;
            default:
                throw new System.ArgumentException("SrcCtl: no op " + op);
        }
    }
}
