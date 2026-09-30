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
}
