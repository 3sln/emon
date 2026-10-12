// BYTE SOURCES, C# host (DEVLOG P9.2; SPEC 13.1; D058). The twin of `host/java/emon/api/ByteSource.java`, `host/go/source.go`
// and `host/rust/src/source.rs`: read those for the reasoning. The library never takes a path, never opens a file and never
// names a filesystem: a document is an `IByteSource` (a size and `ReadAt`), and what is shipped here is every way a .NET
// caller commonly HAS one (SPEC 13.1's table): a `byte[]`, a `ReadOnlyMemory<byte>`, a seekable `Stream` (a file stream, a
// `MemoryStream`, an object-store reader) and a forward-only `Stream` (SPOOLED into memory bounded by the input-bytes limit).

namespace emon.Api;

/// <summary>
/// Random access with a known length. <c>ReadAt</c> fills <c>buf[boff .. boff+len)</c> from byte <c>off</c> and answers how
/// many bytes it gave: fewer than <c>len</c> only at the end of the source, and -1 when <c>off</c> is at or past the end. A
/// source that ends before its <c>Size()</c> said is `source-changed`. Throws <see cref="System.IO.IOException"/> on a failed read.
/// </summary>
public interface IByteSource : emon.RenderPort.ISource {
    /// <summary>An optional capability: a source that holds all its bytes in memory answers them (no chunk cache, no copy).</summary>
    byte[] AsSlice() {
        return null;
    }

    // ------------------------------------------------------------------ adapters

    /// <summary>An in-memory source over <c>b</c>, not copied.</summary>
    static IByteSource Of(byte[] b) {
        return new BytesSource(b);
    }

    /// <summary>An in-memory source over a copy of <c>b</c>.</summary>
    static IByteSource CopyOf(byte[] b) {
        return new BytesSource((byte[]) b.Clone());
    }

    /// <summary>An in-memory source over the bytes of <c>b</c> (copied out: a memory need not be array-backed).</summary>
    static IByteSource Of(System.ReadOnlyMemory<byte> b) {
        return new BytesSource(b.ToArray());
    }

    /// <summary>A seekable stream: the size is taken once, when the source is made; every read seeks and reads under a lock.</summary>
    static IByteSource Of(System.IO.Stream s) {
        return new StreamSource(s);
    }

    /// <summary>A forward-only stream, spooled into memory. At most <c>max</c> bytes: a longer stream is an IOException whose message begins `input-bytes`.</summary>
    static IByteSource Spool(System.IO.Stream input, long max) {
        var outp = new System.IO.MemoryStream();
        byte[] buf = new byte[65536];
        long total = 0;
        for (;;) {
            int n = input.Read(buf, 0, buf.Length);
            if (n <= 0) {
                break;
            }
            total += n;
            if (total > max) {
                throw new System.IO.IOException("input-bytes: the stream is longer than " + max + " bytes");
            }
            outp.Write(buf, 0, n);
        }
        return new BytesSource(outp.ToArray());
    }
}

internal sealed class BytesSource : IByteSource {
    private readonly byte[] b;

    internal BytesSource(byte[] b) {
        this.b = b;
    }

    public long Size() {
        return b.Length;
    }

    public int ReadAt(long off, byte[] buf, int boff, int len) {
        if (off < 0) {
            throw new System.ArgumentException("negative offset");
        }
        if (off >= b.Length) {
            return -1;
        }
        int n = (int) System.Math.Min((long) len, b.Length - off);
        System.Array.Copy(b, (int) off, buf, boff, n);
        return n;
    }

    public byte[] AsSlice() {
        return b;
    }
}

internal sealed class StreamSource : IByteSource {
    private readonly System.IO.Stream ch;
    private readonly long n;
    private readonly object gate = new object();

    internal StreamSource(System.IO.Stream ch) {
        this.ch = ch;
        this.n = ch.Length;
    }

    public long Size() {
        return n;
    }

    public int ReadAt(long off, byte[] buf, int boff, int len) {
        lock (gate) {
            if (off >= n) {
                return -1;
            }
            ch.Position = off;
            int got = 0;
            while (got < len) {
                int k = ch.Read(buf, boff + got, len - got);
                if (k <= 0) {
                    break;
                }
                got += k;
            }
            return got == 0 ? -1 : got;
        }
    }
}
