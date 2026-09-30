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
}
