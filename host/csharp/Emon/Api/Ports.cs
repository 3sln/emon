// THE LIVE PORTS, C# host (DEVLOG P9.2, P10.7, P10.8; SPEC 7.2; D127, D187, D199, D211, D291). The twin of `host/java/emon/api/Ports.java`, of `portFulfiller`,
// `decodeImg`, `livePorts` and the request readers of `host/go/engine.go` and `host/rust/src/engine.rs`: read those for the reasoning.
//
// WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); a request is read by the fields kin names (`dc-*`, `dq-*`, `bq-*`), never
// by a literal offset (D396 item 5). The `ocr` request's languages, models and answer layout are kin's (`emon.ocrport`) and the Ocr port (`emon.Ffi.Tess`)
// calls it.

namespace emon.Api;

using System.Collections.Generic;

internal static class Ports {
    private static emon.Planrun.Answer Failed(emon.Planrun.Request req, int kind, int status) {
        emon.Port.Cells c = emon.Port.NewCells();
        emon.Plan.AnFail(c, kind, req.Id(), status);
        return new emon.Planrun.Answer(emon.Port.CellsVec(c));
    }

    /// <summary>
    /// The LIVE ports as a fulfiller (SPEC 7.2): `ImageDecode` (D187) answers `decode` requests for this input from the host's codecs, and `decode-img` those
    /// of a PDF's image codecs; `Ocr` (D211) answers `ocr` requests from the embedder's engine, or `unsupported` when it has none. `keep` holds the pixels in a
    /// decode answer (a recording wants them, as canonical PNG, and so does a live OCR engine; a plain conversion needs only the numbers); `recording` also
    /// keeps the OCR engine's thresholded image, which only a transcript wants (D211).
    /// </summary>
    internal static emon.Planrun.Sync PortFulfiller(IByteSource source, int idx, bool keep, bool recording, IOcr ocr, Options.IPdfCodecs codecs) {
        return new emon.Planrun.Sync(PortFn(source, idx, keep, recording, ocr, codecs));
    }

    /// <summary>The same as a function from a request to its answer, callable from any thread (P10.2: what a worker runs).</summary>
    internal static emon.Planrun.Fn PortFn(IByteSource source, int idx, bool keep, bool recording, IOcr ocr, Options.IPdfCodecs codecs) {
        return (req, bm) => {
            // WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); this host has no renderer here (D199: `Live` sends a
            // render to one when it has one)
            int route = emon.Planrun.Route(req.Kind(), ocr != null, false);
            if (route == emon.Planloop.RtDecodeImg()) {
                return DecodeImg(req, source, idx, codecs);
            }
            if (route == emon.Planloop.RtUnsupported()) {
                return emon.Planrun.UnsupportedAnswer(req);
            }
            if (route == emon.Planloop.RtOcr()) {
                return OcrSafe(ocr, req, bm, recording);
            }
            if (route != emon.Planloop.RtDecode()) {
                throw emon.Planrun.FulfilFailed("no port is configured for this request kind");
            }
            int input = req.Field(emon.Plan.DcInput()), off = req.Field(emon.Plan.DcOffset()), n = req.Field(emon.Plan.DcLength());
            int filter = req.Field(emon.Plan.DcFilter()), frame = req.Field(emon.Plan.DcFrame());
            if (input != idx || off < 0 || n < 0 || (long) off + n > source.Size()) {
                throw emon.Planrun.FulfilFailed("a decode request names bytes the input does not have");
            }
            // a DCT stream is a JPEG file: the span is read in place (a scanned PDF page's image, D212); the other PDF image filters (JPX, JBIG2, CCITT) are
            // `decode-img`'s
            if (filter != emon.Plan.FltNone() && filter != emon.Plan.FltDct()) {
                return Failed(req, emon.Planrun.KindDecode, emon.Plan.PsUnsupported());
            }
            ImgDec.Decoded d;
            try {
                d = ImgDec.Decode(new ChunkCache.SpanSource(source, off, n), frame);
            } catch (ImgDec.Fail f) {
                return ImgFailure(req, emon.Planrun.KindDecode, f);
            }
            emon.Port.Cells c = emon.Port.NewCells();
            emon.Plan.AnDecode(c, req.Id(), d.W, d.H, d.Ch, d.Dpi100, d.Bilevel ? 1 : 0, d.Frames);
            return new emon.Planrun.Answer(emon.Port.CellsVec(c),
                keep && (filter != emon.Plan.FltDct() || ocr != null) && d.Pixels != null ? new emon.Port.Bitmap(d.W, d.H, d.Ch, d.Pixels) : null);
        };
    }

    // a refused or damaged image as the answer its request gets: `unsupported`, `limit` (with the pixels the frame has) or `failed`
    private static emon.Planrun.Answer ImgFailure(emon.Planrun.Request req, int kind, ImgDec.Fail f) {
        if (f.Kind == ImgDec.Unsupported) {
            return Failed(req, kind, emon.Plan.PsUnsupported());
        }
        if (f.Kind == ImgDec.Limit) {
            int actual = f.Pixels < int.MaxValue ? (int) f.Pixels : int.MaxValue;
            return new emon.Planrun.Answer(new int[] {5, kind, req.Id(), emon.Plan.PsLimit(), actual});
        }
        return Failed(req, kind, emon.Plan.PsFailed());
    }

    // a span of the input, whole or an error (the `decode-img` request names bytes of the input by offset and length)
    private static byte[] ReadSpan(IByteSource source, long off, long n) {
        if (off < 0 || n < 0 || off + n > source.Size()) {
            throw emon.Planrun.FulfilFailed("a decode-img request names bytes the input does not have");
        }
        byte[] b = new byte[(int) n];
        int got = 0;
        try {
            while (got < b.Length) {
                int k = source.ReadAt(off + got, b, got, b.Length - got);
                if (k <= 0) {
                    throw emon.Planrun.FulfilFailed("a read of the source failed");
                }
                got += k;
            }
        } catch (System.IO.IOException) {
            throw emon.Planrun.FulfilFailed("a read of the source failed");
        }
        return b;
    }

    // the bytes a `decode-img` request names (kin's `dq-where` / `dq-offset` / `dq-length`): a span of this input (where is its index), or of the unit's
    // PAYLOAD (where -2); -1 names none (P6.1a, D291)
    private static byte[] NamedBytes(emon.Planrun.Request req, IByteSource source, int idx, int where, int offset, int length) {
        int wh = req.Field(where);
        long off = req.Field(offset), n = req.Field(length);
        if (wh == -1) {
            return null;
        }
        if (wh == -2) {
            if (off < 0 || n < 0 || off + n > req.Payload.Length) {
                throw emon.Planrun.FulfilFailed("a decode-img request names payload bytes the unit did not make");
            }
            var o = new byte[n];
            System.Array.Copy(req.Payload, off, o, 0, n);
            return o;
        }
        if (wh == idx) {
            return ReadSpan(source, off, n);
        }
        throw emon.Planrun.FulfilFailed("a decode-img request names an input this run does not have");
    }

    /// <summary>
    /// The `decode-img` request (kind 6; P6.1a, D117, D291): one PDF image's codestream to its 8-bit pixels (`PdfImg`). The answer always carries the pixels
    /// (kin adopts them); a transcript stores them, never the codestream.
    /// </summary>
    internal static emon.Planrun.Answer DecodeImg(emon.Planrun.Request req, IByteSource source, int idx, Options.IPdfCodecs codecs) {
        var a = new PdfImg.Args();
        a.Filter = req.Field(emon.Plan.DqFilter());
        a.W = req.Field(emon.Plan.DqW());
        a.H = req.Field(emon.Plan.DqH());
        a.Comps = req.Field(emon.Plan.DqComps());
        a.Bpc = req.Field(emon.Plan.DqBpc());
        a.K = req.Field(emon.Plan.DqK());
        a.Columns = req.Field(emon.Plan.DqColumns());
        a.Rows = req.Field(emon.Plan.DqRows());
        a.Flags = req.Field(emon.Plan.DqCcitt());
        byte[] data = NamedBytes(req, source, idx, emon.Plan.DqWhere(), emon.Plan.DqOffset(), emon.Plan.DqLength());
        byte[] globals = NamedBytes(req, source, idx, emon.Plan.DqGwhere(), emon.Plan.DqGoffset(), emon.Plan.DqGlength());
        ImgDec.PdfPix p;
        try {
            p = PdfDecodeSafe(a, data, globals, codecs, emon.Port.LimitOf(9));
        } catch (ImgDec.Fail f) {
            return ImgFailure(req, emon.Planrun.KindDecodeImg, f);
        }
        emon.Port.Cells c = emon.Port.NewCells();
        emon.Plan.AnDecodeImg(c, req.Id(), p.W, p.H, p.Ch, p.Bilevel ? 1 : 0);
        return new emon.Planrun.Answer(emon.Port.CellsVec(c), new emon.Port.Bitmap(p.W, p.H, p.Ch, p.Px));
    }

    // a panic of a codec on damage is a damaged image and not a crashed process
    private static ImgDec.PdfPix PdfDecodeSafe(PdfImg.Args a, byte[] data, byte[] globals, Options.IPdfCodecs codecs, long maxPixels) {
        try {
            return PdfImg.Decode(a, data, globals, codecs, maxPixels);
        } catch (ImgDec.Fail) {
            throw;
        } catch (System.Exception) {
            throw ImgDec.Failedf("the decoder panicked");
        }
    }

    // calls the Ocr port; an engine that panics on a page is a failed OCR of it, not a crashed process
    private static emon.Planrun.Answer OcrSafe(IOcr ocr, emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm, bool keepBinary) {
        try {
            return ocr.Fulfil(req, bm, keepBinary);
        } catch (emon.Planrun.PlanError) {
            throw;
        } catch (System.Exception) {
            return Failed(req, emon.Planrun.KindOcr, emon.Planrun.StatusFailed);
        }
    }

    /// <summary>
    /// The LIVE port set: a request goes where kin says (`pl-route`, P10.8): a `render` or `crop` to the configured Renderer, everything else to the host's own
    /// fulfiller (<see cref="PortFulfiller"/>), which also answers `unsupported` where no port is configured (D199).
    /// </summary>
    internal sealed class Live : emon.Planrun.IFulfiller {
        private readonly emon.Planrun.IFulfiller render, local;
        private readonly bool ocr;
        // true when the request in that place went to the renderer
        private readonly Queue<bool> order = new Queue<bool>();

        internal Live(emon.Planrun.IFulfiller render, emon.Planrun.IFulfiller local, bool ocr) {
            this.render = render;
            this.local = local;
            this.ocr = ocr;
        }

        public void Start(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
            if (render != null && emon.Planrun.Route(req.Kind(), ocr, true) == emon.Planloop.RtRender()) {
                order.Enqueue(true);
                render.Start(req, bm);
                return;
            }
            order.Enqueue(false);
            local.Start(req, bm);
        }

        public emon.Planrun.Answer Next() {
            if (order.Count == 0) {
                throw emon.Planrun.FulfilFailed("next without a started request");
            }
            return order.Dequeue() ? render.Next() : local.Next();
        }
    }

    /// <summary>The ports' fulfiller, timed by request kind (RunStats.FulfilUs).</summary>
    internal sealed class Timed : emon.Planrun.IFulfiller {
        private readonly emon.Planrun.IFulfiller inner;
        internal readonly long[] Us = new long[8];

        internal Timed(emon.Planrun.IFulfiller inner) {
            this.inner = inner;
        }

        public void Start(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
            long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
            try {
                inner.Start(req, bm);
            } finally {
                int k = req.Kind();
                if (req.Kind() == emon.Planrun.KindOcr && req.Id() % 256 != 16) {
                    k = 7;
                }
                if (k < 0 || k > 7) {
                    k = 0;
                }
                Us[k] += (System.Diagnostics.Stopwatch.GetTimestamp() - t0) * 1000000 / System.Diagnostics.Stopwatch.Frequency;
            }
        }

        public emon.Planrun.Answer Next() {
            return inner.Next();
        }
    }
}
