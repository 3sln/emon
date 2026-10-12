// THE `Ocr` PORT, C# host (SPEC 7.2; D008, D095, D211): it fulfils one `ocr` request. The twin of Go's `Ocr` interface
// (`host/go/engine.go`) and of `host/java/emon/api/Ocr.java`. A host brings its engine (Tesseract through `emon.Ffi.Tess`,
// over P/Invoke; anything else that answers in the layout of `emon.ocr`). It is LIVE only: a gate replays a transcript and
// never calls it. With no port a request is answered `unsupported`, a page then has no text, and nothing is guessed.
//
// Named `IOcr` because kin's generated static class `emon.Ocr` has the plain name.

namespace emon.Api;

public interface IOcr {
    /// <summary>
    /// Answers req, whose bitmap is in bitmaps. keepBinary: the caller records, so the engine's thresholded image, when it
    /// has one, goes in the answer's bitmap (the record says it has one either way).
    /// </summary>
    emon.Planrun.Answer Fulfil(emon.Planrun.Request req, System.Collections.Generic.Dictionary<int, emon.Port.Bitmap> bitmaps, bool keepBinary);

    /// <summary>What this engine is, for the transcript's `:producer` (D214): name/value pairs.</summary>
    System.Collections.Generic.List<string[]> Producer();
}
