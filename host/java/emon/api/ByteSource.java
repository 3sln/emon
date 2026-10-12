// BYTE SOURCES, Java host (DEVLOG P9.1; SPEC 13.1; D058). The twin of `host/go/source.go` and
// `host/rust/src/source.rs`: read those for the reasoning. The library never takes a path, never opens a
// file and never names a filesystem: a document is a `ByteSource` (a size and `readAt`), and what is
// shipped here is every way a Java caller commonly HAS one (SPEC 13.1's table): a `byte[]`, a
// `ByteBuffer`, a `SeekableByteChannel` (a file channel, an in-memory channel, an object-store reader) and an
// `InputStream` (forward-only, SPOOLED into memory bounded by the input-bytes limit).

package emon.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;

/**
 * Random access with a known length. {@code readAt} fills {@code buf[boff .. boff+len)} from byte {@code off}
 * and answers how many bytes it gave: fewer than {@code len} only at the end of the source, and -1 when
 * {@code off} is at or past the end. A source that ends before its {@code size()} said is `source-changed`.
 */
public interface ByteSource extends emon.RenderPort.Source {
    long size();

    int readAt(long off, byte[] buf, int boff, int len) throws IOException;

    /** An optional capability: a source that holds all its bytes in memory answers them (no chunk cache, no copy). */
    default byte[] asSlice() {
        return null;
    }

    // ------------------------------------------------------------------ adapters

    /** An in-memory source over {@code b}, not copied. */
    static ByteSource of(byte[] b) {
        return new BytesSource(b);
    }

    /** An in-memory source over a copy of {@code b}. */
    static ByteSource copyOf(byte[] b) {
        return new BytesSource(b.clone());
    }

    /** An in-memory source over the remaining bytes of {@code b} (copied out: a ByteBuffer need not be array-backed). */
    static ByteSource of(ByteBuffer b) {
        byte[] v = new byte[b.remaining()];
        b.duplicate().get(v);
        return new BytesSource(v);
    }

    /** A seekable channel: the size is taken once, when the source is made; every read seeks and reads under a lock. */
    static ByteSource of(SeekableByteChannel ch) throws IOException {
        return new ChannelSource(ch);
    }

    /** A forward-only stream, spooled into memory. At most {@code max} bytes: a longer stream is an {@link IOException} whose message begins `input-bytes`. */
    static ByteSource spool(InputStream in, long max) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        for (;;) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            total += n;
            if (total > max) {
                throw new IOException("input-bytes: the stream is longer than " + max + " bytes");
            }
            out.write(buf, 0, n);
        }
        return new BytesSource(out.toByteArray());
    }

    final class BytesSource implements ByteSource {
        private final byte[] b;

        BytesSource(byte[] b) {
            this.b = b;
        }

        public long size() {
            return b.length;
        }

        public int readAt(long off, byte[] buf, int boff, int len) {
            if (off < 0) {
                throw new IllegalArgumentException("negative offset");
            }
            if (off >= b.length) {
                return -1;
            }
            int n = (int) Math.min(len, b.length - off);
            System.arraycopy(b, (int) off, buf, boff, n);
            return n;
        }

        public byte[] asSlice() {
            return b;
        }
    }

    final class ChannelSource implements ByteSource {
        private final SeekableByteChannel ch;
        private final long n;

        ChannelSource(SeekableByteChannel ch) throws IOException {
            this.ch = ch;
            this.n = ch.size();
        }

        public long size() {
            return n;
        }

        public synchronized int readAt(long off, byte[] buf, int boff, int len) throws IOException {
            if (off >= n) {
                return -1;
            }
            ch.position(off);
            ByteBuffer bb = ByteBuffer.wrap(buf, boff, len);
            int got = 0;
            while (bb.hasRemaining()) {
                int k = ch.read(bb);
                if (k < 0) {
                    break;
                }
                got += k;
            }
            return got == 0 ? -1 : got;
        }
    }
}
