// Stage 0, cheap: what the file says it is (SPEC 13). Opening it reads the header, the trailer, the cross reference and
// the page tree, no page. The twin of Go's `Intake` (`host/go/engine.go`).

package emon.api;

public final class Intake {
    public final String format;
    public final int versionMajor, versionMinor;
    public final int pages;
    public final boolean encrypted;
    public final long bytes;

    Intake(String format, int versionMajor, int versionMinor, int pages, boolean encrypted, long bytes) {
        this.format = format;
        this.versionMajor = versionMajor;
        this.versionMinor = versionMinor;
        this.pages = pages;
        this.encrypted = encrypted;
        this.bytes = bytes;
    }

    /** The driver's line: `pdf 1.7 pages=3 encrypted=false bytes=1234`. */
    public String line() {
        return format + " " + versionMajor + "." + versionMinor + " pages=" + pages + " encrypted=" + encrypted + " bytes=" + bytes;
    }
}
