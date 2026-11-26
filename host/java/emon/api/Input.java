// One document or image: a source and, when the file is encrypted, its password (D040). The twin of Go's `Input`
// (`host/go/source.go`). The password is bytes: R2-R4 use them as given (PDFDocEncoding, in practice Latin-1), R5 and R6
// as UTF-8. It travels with its input (never in the settings, never in the Model, a dump or a transcript) and
// `toString` never prints it.

package emon.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;

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

    /** Groups single-frame images into one document, reading the original encoded sources in place. */
    public static Input images(Input... images) {
        if (images.length == 0 || images.length > (Integer.MAX_VALUE - 12) / 4) {
            throw new IllegalArgumentException("expected a nonempty list of single-frame images");
        }
        var header = ByteBuffer.allocate(12 + images.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{69, 77, 79, 78, 73, 77, 71, 49}).putInt(images.length);
        long[] starts = new long[images.length + 2];
        ByteSource[] parts = new ByteSource[images.length + 1];
        starts[1] = header.capacity();
        parts[0] = ByteSource.of(header.array());
        for (int i = 0; i < images.length; i++) {
            Input image = images[i];
            long length = image.source.size();
            if (image.hasPw || length < 4 || length > Integer.MAX_VALUE - starts[i + 1]) {
                throw new IllegalArgumentException("invalid image input or combined image size");
            }
            header.putInt((int) length);
            starts[i + 2] = starts[i + 1] + length;
            parts[i + 1] = image.source;
        }
        return of(new ByteSource() {
            public long size() { return starts[parts.length]; }

            public int readAt(long offset, byte[] buffer, int begin, int length) throws IOException {
                if (offset < 0) throw new IllegalArgumentException("negative offset");
                if (offset >= size()) return -1;
                int index = Arrays.binarySearch(starts, offset);
                if (index < 0) index = -index - 2;
                int total = 0;
                while (total < length && index < parts.length) {
                    int count = (int) Math.min(length - total, starts[index + 1] - offset);
                    int read = parts[index].readAt(offset - starts[index], buffer, begin + total, count);
                    if (read < 1) break;
                    total += read;
                    offset += read;
                    if (offset == starts[index + 1]) index++;
                }
                return total == 0 && length != 0 ? -1 : total;
            }
        });
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
