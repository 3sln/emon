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
