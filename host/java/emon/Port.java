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

    // @kin:link:form:limit-of: {:template "emon.Port.limitOf({0})" :arity 1}
    public static int limitOf(int kind) {
        String v = System.getenv("EMON_LIMIT_" + limitName(kind));
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // fall through to the default, as the other hosts do
            }
        }
        return limitDefault(kind);
    }

    // @kin:link:form:limit-breach: {:template "emon.Port.limitBreach({0}, {1})" :arity 2}
    public static int limitBreach(int kind, int actual) {
        String name = limitName(kind);
        throw new RuntimeException(
            "the " + name.toLowerCase().replace('_', ' ') + " limit is " + limitOf(kind)
            + " and this input reached " + actual + ". Raise it with EMON_LIMIT_"
            + name + ", or find out why the input needs that much -- a limit is a"
            + " bound on emon, not a statement about the document.");
    }

    // @kin:link:form:setting-of: {:template "emon.Port.settingOf({0})" :arity 1}
    public static int settingOf(int kind) {
        String v = System.getenv("EMON_SETTING_" + settingName(kind));
        if (v != null) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // fall through to the default
            }
        }
        return settingDefault(kind);
    }

    private static String limitName(int kind) {
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

    private static int limitDefault(int kind) {
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

    private static String settingName(int kind) {
        switch (kind) {
            case 0: return "OCR_MODE";
            case 1: return "TASKS";
            default: return "UNKNOWN";
        }
    }

    private static int settingDefault(int kind) {
        switch (kind) {
            case 0: return 0;
            case 1: return 0;
            default: return 0;
        }
    }
}
