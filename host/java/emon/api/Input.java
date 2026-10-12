// One document or image: a source and, when the file is encrypted, its password (D040). The twin of Go's `Input`
// (`host/go/source.go`). The password is bytes: R2-R4 use them as given (PDFDocEncoding, in practice Latin-1), R5 and R6
// as UTF-8. It travels with its input (never in the settings, never in the Model, a dump or a transcript) and
// `toString` never prints it.

package emon.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

public final class Input {
    public final ByteSource source;
    public final byte[] password;
    private final boolean hasPw;

    private Input(ByteSource source, byte[] password, boolean hasPw) {
        this.source = source;
        this.password = password;
        this.hasPw = hasPw;
    }

    /** A source with no password. */
    public static Input of(ByteSource src) {
        return new Input(src, new byte[0], false);
    }

    /** An in-memory source over b (not copied) with no password. */
    public static Input bytes(byte[] b) {
        return of(ByteSource.of(b));
    }

    /** An in-memory source over the remaining bytes of b. */
    public static Input bytes(ByteBuffer b) {
        return of(ByteSource.of(b));
    }

    /** A seekable channel: a file channel, an in-memory channel, anything seekable. */
    public static Input seekable(SeekableByteChannel ch) throws IOException {
        return of(ByteSource.of(ch));
    }

    /** A forward-only stream, spooled; max is the input-bytes bound to spool to. */
    public static Input spooled(InputStream in, long max) throws IOException {
        return of(ByteSource.spool(in, max));
    }

    /** The input with a password (D040). */
    public Input withPassword(byte[] pw) {
        return new Input(source, pw.clone(), true);
    }

    @Override
    public String toString() {
        return "Input{len: " + source.size() + ", password: " + (hasPw ? "yes" : "no") + "}";
    }
}
