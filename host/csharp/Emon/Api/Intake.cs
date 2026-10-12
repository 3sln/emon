// Stage 0, cheap: what the file says it is (SPEC 13). Opening it reads the header, the trailer, the cross reference and
// the page tree, no page. The twin of Go's `Intake` (`host/go/engine.go`).

namespace emon.Api;

public sealed class Intake {
    public readonly string Format;
    public readonly int VersionMajor, VersionMinor;
    public readonly int Pages;
    public readonly bool Encrypted;
    public readonly long Bytes;

    internal Intake(string format, int versionMajor, int versionMinor, int pages, bool encrypted, long bytes) {
        this.Format = format;
        this.VersionMajor = versionMajor;
        this.VersionMinor = versionMinor;
        this.Pages = pages;
        this.Encrypted = encrypted;
        this.Bytes = bytes;
    }

    /// <summary>The driver's line: `pdf 1.7 pages=3 encrypted=false bytes=1234`.</summary>
    public string Line() {
        return Format + " " + VersionMajor + "." + VersionMinor + " pages=" + Pages + " encrypted=" + (Encrypted ? "true" : "false") + " bytes=" + Bytes;
    }
}
