// THE `Ocr` PORT, Java host (SPEC 7.2; D008, D095, D211): it fulfils one `ocr` request. The twin of Go's `Ocr` interface
// (`host/go/engine.go`). A host brings its engine (Tesseract through `emon.ffi.Tess`, over the Java FFM API; anything
// else that answers in the layout of `emon.ocr`). It is LIVE only: a gate replays a transcript and never calls it. With
// no port a request is answered `unsupported`, a page then has no text, and nothing is guessed.

package emon.api;

import emon.Planrun;
import emon.Port;
import java.util.List;
import java.util.Map;

public interface Ocr {
    /**
     * Answers req, whose bitmap is in bitmaps. keepBinary: the caller records, so the engine's thresholded image, when
     * it has one, goes in the answer's bitmap (the record says it has one either way).
     */
    Planrun.Answer fulfil(Planrun.Request req, Map<Integer, Port.Bitmap> bitmaps, boolean keepBinary);

    /** What this engine is, for the transcript's `:producer` (D214): name/value pairs. */
    List<String[]> producer();
}
