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

    // ------------------------------------------------ a source that misses
    //
    // A Src over a blob, served in fixed-size CHUNKS of which only some are
    // RESIDENT (P1.11, D058); see the Rust half. Tests only.

    private static final class ChunkedBacking implements Port.SrcBacking {
        final byte[] data;
        final int chunk;
        final boolean sync;
        final boolean[] resident;
        final java.util.ArrayList<Integer> pending = new java.util.ArrayList<>();
        int fills = 0;
        int hints = 0;

        ChunkedBacking(byte[] data, int chunk, boolean sync) {
            this.data = data;
            this.chunk = chunk;
            this.sync = sync;
            this.resident = new boolean[(data.length + chunk - 1) / chunk];
        }

        int nchunks() {
            return resident.length;
        }

        boolean touch(int c) {
            if (resident[c]) {
                return true;
            }
            if (sync) {
                resident[c] = true;
                fills++;
                return true;
            }
            if (!pending.contains(c)) {
                pending.add(c);
            }
            return false;
        }

        public int len() {
            return data.length;
        }

        public int at(int i) {
            return touch(i / chunk) ? (data[i] & 0xFF) : -1;
        }

        public boolean span(int s, byte[] buf) {
            int n = buf.length;
            if (n == 0) {
                return true;
            }
            boolean ok = true;
            for (int c = s / chunk; c <= (s + n - 1) / chunk; c++) {
                if (touch(c)) {
                    int lo = Math.max(s, c * chunk);
                    int hi = Math.min(s + n, (c + 1) * chunk);
                    for (int k = lo; k < hi; k++) {
                        buf[k - s] = data[k];
                    }
                } else {
                    ok = false;
                }
            }
            return ok;
        }

        public void hint(int s, int e) {
            hints++;
        }
    }

    // A source over b, in chunks of `chunk` bytes, none resident; sync 1 fills inline.
    public static Port.Src srcChunked(byte[] b, int chunk, int sync) {
        return new Port.Src(new ChunkedBacking(b, chunk, sync == 1));
    }

    // A fresh view (missed flag clear) of the same chunk cache.
    public static Port.Src srcView(Port.Src s) {
        return s.view();
    }

    // Control of the cache: op 0 fetch (a is the round: read-ahead of 2^a - 1
    // chunks each side of every pending chunk; answers the chunks made
    // resident), 1 make chunk a resident, 2 evict chunk a, 3 make every chunk
    // resident, 4 the pending count, 5 the resident count, 6 the hints
    // received, 7 the chunks a sync source filled inline, 8 the chunk count.
    public static int srcCtl(Port.Src s, int op, int a) {
        ChunkedBacking b = (ChunkedBacking) s.b;
        int n = b.nchunks();
        switch (op) {
            case 0: {
                int r = (1 << Math.min(a, 20)) - 1;
                java.util.ArrayList<Integer> pend = new java.util.ArrayList<>(b.pending);
                b.pending.clear();
                int made = 0;
                for (int c : pend) {
                    for (int k = Math.max(0, c - r); k <= Math.min(n - 1, c + r); k++) {
                        if (!b.resident[k]) {
                            b.resident[k] = true;
                            made++;
                        }
                    }
                }
                return made;
            }
            case 1:
                b.resident[a] = true;
                b.pending.remove(Integer.valueOf(a));
                return 0;
            case 2:
                b.resident[a] = false;
                return 0;
            case 3:
                java.util.Arrays.fill(b.resident, true);
                b.pending.clear();
                return 0;
            case 4:
                return b.pending.size();
            case 5: {
                int c = 0;
                for (boolean r : b.resident) {
                    if (r) {
                        c++;
                    }
                }
                return c;
            }
            case 6:
                return b.hints;
            case 7:
                return b.fills;
            case 8:
                return n;
            default:
                throw new IllegalArgumentException("srcCtl: no op " + op);
        }
    }
}
