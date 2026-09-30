// The Java half of emon's TEST support (DEVLOG P0.5, D044). See the Rust half.
// A failed assertion throws AssertionError, which the unit script's runner
// (this machine has a JDK and no JUnit) reports against the law that threw it.

package emon;

public final class Testport {
    private static boolean inverted() {
        return "1".equals(System.getenv("EMON_TEST_INVERT"));
    }

    private static void verdict(boolean ok, String what) {
        if (ok == inverted()) {
            throw new AssertionError(what);
        }
    }

    public static void assertEq(int got, int want, String name) {
        verdict(got == want, name + ": got " + got + ", want " + want);
    }

    public static void assertEqAt(int got, int want, String name, int row) {
        verdict(got == want, name + " [row " + row + "]: got " + got + ", want " + want);
    }

    public static void assertTextSpan(StringBuilder got, byte[] b, int from, int to, String name, int row) {
        String want = new String(b, from, to - from, java.nio.charset.StandardCharsets.UTF_8);
        verdict(got.toString().equals(want), name + " [row " + row + "]: got `" + got + "`, want `" + want + "`");
    }

    public static void assertEq64(long got, long want, String name) {
        verdict(got == want, name + ": got " + got + ", want " + want);
    }

    public static void assertThat(boolean ok, String name) {
        verdict(ok, name + ": not true");
    }

    public static void assertThatAt(boolean ok, String name, int row) {
        verdict(ok, name + " [row " + row + "]: not true");
    }

    public static void assertText(StringBuilder got, String want, String name) {
        verdict(got.toString().equals(want),
                name + ": got `" + got + "`, want `" + want + "`");
    }

    public static void assertCellsHex(Port.Cells c, String hex, String name) {
        byte[] want = hexBlob(hex);
        int n = Port.cellsLen(c);
        boolean ok = n == want.length;
        StringBuilder got = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int v = Port.cellsAt(c, i);
            got.append(String.format("%02x", v & 0xFF));
            if (ok && v != (want[i] & 0xFF)) {
                ok = false;
            }
        }
        verdict(ok, name + ": got " + n + " bytes " + got + ", want " + hex);
    }

    public static byte[] textBlob(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] hexBlob(String s) {
        int[] digits = new int[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 16);
            if (d >= 0) {
                digits[n++] = d;
            }
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((digits[2 * i] << 4) | digits[2 * i + 1]);
        }
        return out;
    }

    // The same, the file's name being bytes b[from, to) (a corpus row).
    public static byte[] testFileSpan(byte[] b, int from, int to) {
        return testFile(new String(b, from, to - from, java.nio.charset.StandardCharsets.UTF_8));
    }

    // A corpus file, read whole; `EMON_TEST_DIR` names the directory.
    public static byte[] testFile(String name) {
        String dir = System.getenv("EMON_TEST_DIR");
        if (dir == null || dir.isEmpty()) {
            dir = "test";
        }
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(dir, name));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] cellsBlob(Port.Cells c) {
        byte[] out = new byte[Port.cellsLen(c)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Port.cellsAt(c, i);
        }
        return out;
    }

    public static StringBuilder newText() {
        return new StringBuilder();
    }
}
