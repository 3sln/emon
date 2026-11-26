// One document or image: a source and, when the file is encrypted, its password (D040). The twin of Go's `Input`
// (`host/go/source.go`) and `host/java/emon/api/Input.java`. The password is bytes: R2-R4 use them as given (PDFDocEncoding,
// in practice Latin-1), R5 and R6 as UTF-8. It travels with its input (never in the settings, never in the Model, a dump or a
// transcript) and `ToString` never prints it.

namespace emon.Api;

public sealed class Input {
    public readonly IByteSource Source;
    public readonly byte[] Password;
    private readonly bool hasPw;

    private Input(IByteSource source, byte[] password, bool hasPw) {
        this.Source = source;
        this.Password = password;
        this.hasPw = hasPw;
    }

    /// <summary>A source with no password.</summary>
    public static Input Of(IByteSource src) {
        return new Input(src, new byte[0], false);
    }

    /// <summary>An in-memory source over b (not copied) with no password.</summary>
    public static Input Bytes(byte[] b) {
        return Of(IByteSource.Of(b));
    }

    /// <summary>An in-memory source over the bytes of b.</summary>
    public static Input Bytes(System.ReadOnlyMemory<byte> b) {
        return Of(IByteSource.Of(b));
    }

    /// <summary>Groups single-frame images into one document, reading the original encoded sources in place.</summary>
    public static Input Images(params Input[] images) {
        if (images.Length == 0 || images.Length > (int.MaxValue - 12) / 4)
            throw new System.ArgumentException("expected a nonempty list of single-frame images");
        byte[] header = new byte[12 + images.Length * 4];
        System.Text.Encoding.ASCII.GetBytes("EMONIMG1").CopyTo(header, 0);
        System.Buffers.Binary.BinaryPrimitives.WriteInt32LittleEndian(new System.Span<byte>(header, 8, 4), images.Length);
        var starts = new long[images.Length + 2];
        var parts = new IByteSource[images.Length + 1];
        starts[1] = header.Length;
        parts[0] = IByteSource.Of(header);
        for (int i = 0; i < images.Length; i++) {
            Input image = images[i];
            long length = image.Source.Size();
            if (image.hasPw || length < 4 || length > int.MaxValue - starts[i + 1])
                throw new System.ArgumentException("invalid image input or combined image size");
            System.Buffers.Binary.BinaryPrimitives.WriteInt32LittleEndian(new System.Span<byte>(header, 12 + i * 4, 4), (int) length);
            starts[i + 2] = starts[i + 1] + length;
            parts[i + 1] = image.Source;
        }
        return Of(new ImageSource(parts, starts));
    }

    private sealed class ImageSource : IByteSource {
        private readonly IByteSource[] parts;
        private readonly long[] starts;
        internal ImageSource(IByteSource[] parts, long[] starts) {
            this.parts = parts;
            this.starts = starts;
        }

        public long Size() => starts[parts.Length];

        public int ReadAt(long offset, byte[] buffer, int begin, int length) {
            if (offset < 0) throw new System.ArgumentException("negative offset");
            if (offset >= Size()) return -1;
            int index = System.Array.BinarySearch(starts, offset);
            if (index < 0) index = ~index - 1;
            int total = 0;
            while (total < length && index < parts.Length) {
                int count = (int) System.Math.Min(length - total, starts[index + 1] - offset);
                int read = parts[index].ReadAt(offset - starts[index], buffer, begin + total, count);
                if (read < 1) break;
                total += read;
                offset += read;
                if (offset == starts[index + 1]) index++;
            }
            return total == 0 && length != 0 ? -1 : total;
        }
    }

    /// <summary>A seekable stream: a file stream, a memory stream, anything seekable.</summary>
    public static Input Seekable(System.IO.Stream s) {
        return Of(IByteSource.Of(s));
    }

    /// <summary>A forward-only stream, spooled; max is the input-bytes bound to spool to.</summary>
    public static Input Spooled(System.IO.Stream s, long max) {
        return Of(IByteSource.Spool(s, max));
    }

    /// <summary>The input with a password (D040).</summary>
    public Input WithPassword(byte[] pw) {
        return new Input(Source, (byte[]) pw.Clone(), true);
    }

    public override string ToString() {
        return "Input{len: " + Source.Size() + ", password: " + (hasPw ? "yes" : "no") + "}";
    }
}
