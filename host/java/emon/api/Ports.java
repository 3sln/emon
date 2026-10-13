// THE LIVE PORTS, Java host (DEVLOG P9.1, P10.7, P10.8; SPEC 7.2; D127, D187, D199, D211, D291). The twin of `portFulfiller`, `decodeImg`, `livePorts` and the
// request readers of `host/go/engine.go` and `host/rust/src/engine.rs`: read those for the reasoning.
//
// WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); a request is read by the fields kin names (`dc-*`, `dq-*`, `bq-*`), never
// by a literal offset (D396 item 5). The `ocr` request's languages, models and answer layout are kin's (`emon.ocrport`) and the Ocr port (`emon.ffi.Tess`)
// calls it.

package emon.api;

import emon.Plan;
import emon.Planloop;
import emon.Planrun;
import emon.Port;
import java.io.IOException;
import java.util.Map;

final class Ports {
    private Ports() {
    }

    private static Planrun.Answer failed(Planrun.Request req, int kind, int status) {
        Port.Cells c = Port.newCells();
        Plan.anFail(c, kind, req.id(), status);
        return new Planrun.Answer(Port.cellsVec(c));
    }

    /**
     * The LIVE ports as a fulfiller (SPEC 7.2): `ImageDecode` (D187) answers `decode` requests for this input from the host's codecs, and `decode-img` those
     * of a PDF's image codecs; `Ocr` (D211) answers `ocr` requests from the embedder's engine, or `unsupported` when it has none. `keep` holds the pixels in a
     * decode answer (a recording wants them, as canonical PNG, and so does a live OCR engine; a plain conversion needs only the numbers); `recording` also
     * keeps the OCR engine's thresholded image, which only a transcript wants (D211).
     */
    static Planrun.Sync portFulfiller(ByteSource source, int idx, boolean keep, boolean recording, Ocr ocr, Options.PdfCodecs codecs) {
        return new Planrun.Sync(portFn(source, idx, keep, recording, ocr, codecs));
    }

    /** The same as a function from a request to its answer, callable from any thread (P10.2: what a worker runs). */
    static Planrun.Fn portFn(ByteSource source, int idx, boolean keep, boolean recording, Ocr ocr, Options.PdfCodecs codecs) {
        return (req, bm) -> {
            // WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); this host has no renderer here (D199: `LivePorts` sends a
            // render to one when it has one)
            int route = Planrun.route(req.kind(), ocr != null, false);
            if (route == Planloop.rtDecodeImg()) {
                return decodeImg(req, source, idx, codecs);
            }
            if (route == Planloop.rtUnsupported()) {
                return Planrun.unsupportedAnswer(req);
            }
            if (route == Planloop.rtOcr()) {
                return ocrSafe(ocr, req, bm, recording);
            }
            if (route != Planloop.rtDecode()) {
                throw Planrun.fulfilFailed("no port is configured for this request kind");
            }
            int input = req.field(Plan.dcInput()), off = req.field(Plan.dcOffset()), n = req.field(Plan.dcLength());
            int filter = req.field(Plan.dcFilter()), frame = req.field(Plan.dcFrame());
            if (input != idx || off < 0 || n < 0 || (long) off + n > source.size()) {
                throw Planrun.fulfilFailed("a decode request names bytes the input does not have");
            }
            // a DCT stream is a JPEG file: the span is read in place (a scanned PDF page's image, D212); the other PDF image filters (JPX, JBIG2, CCITT) are
            // `decode-img`'s
            if (filter != Plan.fltNone() && filter != Plan.fltDct()) {
                return failed(req, Planrun.KIND_DECODE, Plan.psUnsupported());
            }
            ImgDec.Decoded d;
            try {
                d = ImgDec.decode(new ChunkCache.SpanSource(source, off, n), frame);
            } catch (ImgDec.Fail f) {
                return imgFailure(req, Planrun.KIND_DECODE, f);
            }
            Port.Cells c = Port.newCells();
            Plan.anDecode(c, req.id(), d.w, d.h, d.ch, d.dpi100, d.bilevel ? 1 : 0, d.frames);
            Planrun.Answer a = new Planrun.Answer(Port.cellsVec(c),
                keep && (filter != Plan.fltDct() || ocr != null) && d.pixels != null ? new Port.Bitmap(d.w, d.h, d.ch, d.pixels) : null);
            return a;
        };
    }

    // a refused or damaged image as the answer its request gets: `unsupported`, `limit` (with the pixels the frame has) or `failed`
    private static Planrun.Answer imgFailure(Planrun.Request req, int kind, ImgDec.Fail f) {
        if (f.kind == ImgDec.UNSUPPORTED) {
            return failed(req, kind, Plan.psUnsupported());
        }
        if (f.kind == ImgDec.LIMIT) {
            int actual = f.pixels < Integer.MAX_VALUE ? (int) f.pixels : Integer.MAX_VALUE;
            return new Planrun.Answer(new int[] {5, kind, req.id(), Plan.psLimit(), actual});
        }
        return failed(req, kind, Plan.psFailed());
    }

    // a span of the input, whole or an error (the `decode-img` request names bytes of the input by offset and length)
    private static byte[] readSpan(ByteSource source, long off, long n) {
        if (off < 0 || n < 0 || off + n > source.size()) {
            throw Planrun.fulfilFailed("a decode-img request names bytes the input does not have");
        }
        byte[] b = new byte[(int) n];
        int got = 0;
        try {
            while (got < b.length) {
                int k = source.readAt(off + got, b, got, b.length - got);
                if (k <= 0) {
                    throw Planrun.fulfilFailed("a read of the source failed");
                }
                got += k;
            }
        } catch (IOException e) {
            throw Planrun.fulfilFailed("a read of the source failed");
        }
        return b;
    }

    // the bytes a `decode-img` request names (kin's `dq-where` / `dq-offset` / `dq-length`): a span of this input (where is its index), or of the unit's
    // PAYLOAD (where -2); -1 names none (P6.1a, D291)
    private static byte[] namedBytes(Planrun.Request req, ByteSource source, int idx, int where, int offset, int length) {
        int wh = req.field(where);
        long off = req.field(offset), n = req.field(length);
        if (wh == -1) {
            return null;
        }
        if (wh == -2) {
            if (off < 0 || n < 0 || off + n > req.payload.length) {
                throw Planrun.fulfilFailed("a decode-img request names payload bytes the unit did not make");
            }
            return java.util.Arrays.copyOfRange(req.payload, (int) off, (int) (off + n));
        }
        if (wh == idx) {
            return readSpan(source, off, n);
        }
        throw Planrun.fulfilFailed("a decode-img request names an input this run does not have");
    }

    /**
     * The `decode-img` request (kind 6; P6.1a, D117, D291): one PDF image's codestream to its 8-bit pixels (`PdfImg`). The answer always carries the pixels
     * (kin adopts them); a transcript stores them, never the codestream.
     */
    static Planrun.Answer decodeImg(Planrun.Request req, ByteSource source, int idx, Options.PdfCodecs codecs) {
        PdfImg.Args a = new PdfImg.Args();
        a.filter = req.field(Plan.dqFilter());
        a.w = req.field(Plan.dqW());
        a.h = req.field(Plan.dqH());
        a.comps = req.field(Plan.dqComps());
        a.bpc = req.field(Plan.dqBpc());
        a.k = req.field(Plan.dqK());
        a.columns = req.field(Plan.dqColumns());
        a.rows = req.field(Plan.dqRows());
        a.flags = req.field(Plan.dqCcitt());
        byte[] data = namedBytes(req, source, idx, Plan.dqWhere(), Plan.dqOffset(), Plan.dqLength());
        byte[] globals = namedBytes(req, source, idx, Plan.dqGwhere(), Plan.dqGoffset(), Plan.dqGlength());
        ImgDec.PdfPix p;
        try {
            p = pdfDecodeSafe(a, data, globals, codecs, Port.limitOf(9));
        } catch (ImgDec.Fail f) {
            return imgFailure(req, Planrun.KIND_DECODE_IMG, f);
        }
        Port.Cells c = Port.newCells();
        Plan.anDecodeImg(c, req.id(), p.w, p.h, p.ch, p.bilevel ? 1 : 0);
        return new Planrun.Answer(Port.cellsVec(c), new Port.Bitmap(p.w, p.h, p.ch, p.px));
    }

    // a panic of a codec on damage is a damaged image and not a crashed process
    private static ImgDec.PdfPix pdfDecodeSafe(PdfImg.Args a, byte[] data, byte[] globals, Options.PdfCodecs codecs, long maxPixels) throws ImgDec.Fail {
        try {
            return PdfImg.decode(a, data, globals, codecs, maxPixels);
        } catch (ImgDec.Fail f) {
            throw f;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            throw ImgDec.failed("the decoder panicked");
        }
    }

    // calls the Ocr port; an engine that panics on a page is a failed OCR of it, not a crashed process
    private static Planrun.Answer ocrSafe(Ocr ocr, Planrun.Request req, Map<Integer, Port.Bitmap> bm, boolean keepBinary) {
        try {
            return ocr.fulfil(req, bm, keepBinary);
        } catch (Planrun.PlanError e) {
            throw e;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            return failed(req, Planrun.KIND_OCR, Planrun.STATUS_FAILED);
        }
    }

    /**
     * The LIVE port set: a request goes where kin says (`pl-route`, P10.8): a `render` or `crop` to the configured Renderer, everything else to the host's own
     * fulfiller ({@link #portFulfiller}), which also answers `unsupported` where no port is configured (D199).
     */
    static final class Live implements Planrun.Fulfiller {
        final Planrun.Fulfiller render, local;
        final boolean ocr;
        // true when the request in that place went to the renderer
        private final java.util.ArrayDeque<Boolean> order = new java.util.ArrayDeque<>();

        Live(Planrun.Fulfiller render, Planrun.Fulfiller local, boolean ocr) {
            this.render = render;
            this.local = local;
            this.ocr = ocr;
        }

        public void start(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            if (render != null && Planrun.route(req.kind(), ocr, true) == Planloop.rtRender()) {
                order.add(true);
                render.start(req, bm);
                return;
            }
            order.add(false);
            local.start(req, bm);
        }

        public Planrun.Answer next() {
            if (order.isEmpty()) {
                throw Planrun.fulfilFailed("next without a started request");
            }
            return order.poll() ? render.next() : local.next();
        }
    }

    /** The ports' fulfiller, timed by request kind (RunStats.fulfilUs). */
    static final class Timed implements Planrun.Fulfiller {
        final Planrun.Fulfiller inner;
        final long[] us = new long[8];

        Timed(Planrun.Fulfiller inner) {
            this.inner = inner;
        }

        public void start(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            long t0 = System.nanoTime();
            try {
                inner.start(req, bm);
            } finally {
                int k = req.kind();
                if (req.kind() == Planrun.KIND_OCR && req.id() % 256 != 16) {
                    k = 7;
                }
                if (k < 0 || k > 7) {
                    k = 0;
                }
                us[k] += (System.nanoTime() - t0) / 1000;
            }
        }

        public Planrun.Answer next() {
            return inner.next();
        }
    }
}
