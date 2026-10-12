// A native engine (pdfium, Tesseract) the C# host could not load: the library is not where it was looked for.
// The message says what is missing and how to get it. Never thrown for a failure of a render or a recognition.

namespace emon.Ffi;

public sealed class Unavailable : System.Exception {
    public Unavailable(string message) : base(message) {
    }

    public Unavailable(string message, System.Exception cause) : base(message, cause) {
    }
}
