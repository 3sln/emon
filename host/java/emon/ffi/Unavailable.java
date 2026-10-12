// A native engine (pdfium, Tesseract) the Java host could not load: the library is not where it was looked for.
// The message says what is missing and how to get it. Never thrown for a failure of a render or a recognition.

package emon.ffi;

public final class Unavailable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unavailable(String message) {
        super(message, null, false, false);
    }

    public Unavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
