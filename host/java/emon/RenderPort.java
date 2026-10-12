// The `Renderer` port, Java host (DEVLOG P9.1; P6.2, D127; SPEC 7.2, D007). The twin of `host/go/port/render.go`:
// read that for the reasoning. A page of an input, at a resolution, in a layer, optionally cut to a pixel rectangle,
// as an RGB Bitmap.
//
// This file is the PORT: the interface an engine implements, the request it is given, and the fulfiller that turns
// `render` and `crop` plan requests (D089, D117) into calls and answers. It names no engine and no filesystem.
// `emon.ffi.Pdfium` (the Java FFM API over the pinned libpdfium) is the default engine (D007).

package emon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RenderPort {
    private RenderPort() {
    }

    // `emon.plan`'s layers (`lay-*`); a kin law holds the numbers.
    public static final int LAYER_FULL = 0;
    public static final int LAYER_NO_TEXT = 1;
    public static final int LAYER_TEXT_ONLY = 2;
    public static final int LAYER_NO_WATERMARK = 3;
    public static final int LAYER_WATERMARK_ONLY = 4;

    public static final int STATUS_UNSUPPORTED = 2;

    /** A pixel rectangle of the whole-page bitmap. */
    public static final class Clip {
        public final int x, y, w, h;

        public Clip(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** One page of one input, as the Renderer is asked for it. */
    public static final class Request {
        public final int page; // 0-based
        public final int dpi;
        public final int layer;
        public final Clip clip; // null: the whole page

        public Request(int page, int dpi, int layer, Clip clip) {
            this.page = page;
            this.dpi = dpi;
            this.layer = layer;
            this.clip = clip;
        }

        public Request withoutClip() {
            return new Request(page, dpi, layer, null);
        }
    }

    /** The host's random-access byte source (the engine's ByteSource, structurally). */
    public interface Source {
        long size();

        /** Fills buf[boff, boff+len) from byte off: the count, fewer only at the end, -1 at or past it. */
        int readAt(long off, byte[] buf, int boff, int len) throws java.io.IOException;
    }

    /**
     * How a render did not happen. `unsupported` is `ps-unsupported` (an engine that cannot separate a layer says so
     * and kin plans around it); anything else is `ps-failed`.
     */
    public static final class RenderError extends Exception {
        private static final long serialVersionUID = 1L;
        public final boolean unsupported;

        public RenderError(boolean unsupported, String msg) {
            super((unsupported ? "render unsupported: " : "render failed: ") + msg, null, false, false);
            this.unsupported = unsupported;
        }
    }

    /** The port. It must be callable from any thread; an engine that is not thread-safe, as pdfium is not, serialises inside. */
    public interface Renderer {
        /**
         * Renders req of the document `input` (opened with `password` when it is encrypted, null for none). The result
         * is RGB (3 channels): the tracer wants colour for ink separation (SPEC 8.2.1) and OCR takes it as it is.
         */
        Port.Bitmap render(Source input, byte[] password, Request req) throws RenderError;

        /** The set of layers it can separate, as a bit set over LAYER_* (bit n = layer n). */
        int layers();

        /**
         * Is a render with a Clip EXACTLY the cut of the same render without one (SPEC 7.2: "the same pixels, cut out,
         * never a re-fit")? pdfium is not: a clipped render moves the pixels on the clip's edge (measured, D127). When
         * false the fulfiller never passes a Clip: it renders the whole page and cuts.
         */
        boolean exactCrop();
    }

    /** The size in pixels of `pt` points at `dpi`: floor(pt*dpi/72 + 1/2), at least 1. */
    public static int pixelsOf(double pt, int dpi) {
        double v = Math.floor(pt * (double) dpi / 72.0 + 0.5);
        if (v < 1) {
            return 1;
        }
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) v;
    }

    /** One input of the run, as the fulfiller reads it. */
    public static final class Input {
        public final Source source;
        public final byte[] password;

        public Input(Source source, byte[] password) {
            this.source = source;
            this.password = password;
        }
    }

    /** A parsed render request: the input index and what is asked of it. */
    public static final class Parsed {
        public final int input;
        public final Request request;

        Parsed(int input, Request request) {
            this.input = input;
            this.request = request;
        }
    }

    /** Reads a `render` (args input page dpi layer) or `crop` (and px py pw ph) request. */
    public static Parsed parseRender(Planrun.Request req) {
        int n;
        boolean crop = false;
        switch (req.kind()) {
            case Planrun.KIND_RENDER:
                n = 4;
                break;
            case Planrun.KIND_CROP:
                n = 8;
                crop = true;
                break;
            default:
                throw new IllegalArgumentException("kind " + req.kind() + " is not a render");
        }
        if (req.rec.length != 3 + n) {
            throw new IllegalArgumentException("a render request has " + (req.rec.length - 3) + " args, not " + n);
        }
        if (req.field(Plan.rdInput()) < 0) {
            throw new IllegalArgumentException("input " + req.field(Plan.rdInput()));
        }
        Clip clip = crop ? new Clip(req.field(Plan.rdX()), req.field(Plan.rdY()), req.field(Plan.rdW()), req.field(Plan.rdH())) : null;
        return new Parsed(req.field(Plan.rdInput()), new Request(req.field(Plan.rdPage()), req.field(Plan.rdDpi()), req.field(Plan.rdLayer()), clip));
    }

    /** The pixels of rectangle c of b, as a Bitmap of its own: the crop law (D117), which a test holds an engine's own crops to. */
    public static Port.Bitmap cut(Port.Bitmap b, Clip c) throws RenderError {
        if (c.w < 1 || c.h < 1 || c.x < 0 || c.y < 0 || (long) c.x + c.w > b.w || (long) c.y + c.h > b.h) {
            throw new RenderError(false, "the crop " + c.w + "x" + c.h + "+" + c.x + "+" + c.y + " is not inside the " + b.w + "x" + b.h + " page");
        }
        int ch = b.ch;
        byte[] out = new byte[c.w * c.h * ch];
        for (int y = 0; y < c.h; y++) {
            System.arraycopy(b.d, (c.y + y) * b.stride + c.x * ch, out, y * c.w * ch, c.w * ch);
        }
        return new Port.Bitmap(c.w, c.h, b.ch, out);
    }

    /**
     * Fulfils `render` and `crop` with a Renderer, synchronously: a render is CPU work, and a host that wants them
     * concurrent wraps this in its own pool. Reading the input happens inside. A crop is the cut of a whole-page render
     * ({@link #cut}), and the LAST whole-page render is kept, so a run that asks for a page and then for regions of it
     * (the assets, D117) renders it once.
     */
    public static final class Fulfiller implements Planrun.Fulfiller {
        public final Renderer renderer;
        public final Map<Integer, Input> inputs;
        private final java.util.ArrayDeque<Object> done = new java.util.ArrayDeque<>();
        private String lastKey;
        private Port.Bitmap last;

        public Fulfiller(Renderer r, Map<Integer, Input> inputs) {
            this.renderer = r;
            this.inputs = inputs;
        }

        /** Serves ONE input of a run, at its index (the Engine converts one input at a time). */
        public static Fulfiller forInput(Renderer r, int idx, Input in) {
            Map<Integer, Input> m = new HashMap<>();
            m.put(idx, in);
            return new Fulfiller(r, m);
        }

        private synchronized Port.Bitmap whole(int idx, Input in, Request rr) throws RenderError {
            String key = idx + "/" + rr.page + "/" + rr.dpi + "/" + rr.layer;
            if (last != null && key.equals(lastKey)) {
                return last;
            }
            Port.Bitmap b = renderer.render(in.source, in.password, rr.withoutClip());
            lastKey = key;
            last = b;
            return b;
        }

        /** Answers one request. */
        public Planrun.Answer fulfil(Planrun.Request req) {
            Parsed p;
            try {
                p = parseRender(req);
            } catch (IllegalArgumentException e) {
                throw Planrun.fulfilFailed(e.getMessage());
            }
            Input in = inputs.get(p.input);
            if (in == null) {
                throw Planrun.fulfilFailed("a render names input " + p.input + ", which the run does not have");
            }
            Request rr = p.request;
            Port.Bitmap bm;
            try {
                if (rr.clip == null) {
                    bm = whole(p.input, in, rr);
                } else if (renderer.exactCrop()) {
                    bm = renderer.render(in.source, in.password, rr);
                } else {
                    bm = cut(whole(p.input, in, rr), rr.clip);
                }
            } catch (RenderError e) {
                int status = e.unsupported ? STATUS_UNSUPPORTED : Planrun.STATUS_FAILED;
                return new Planrun.Answer(new int[] {4, req.kind(), req.id(), status});
            }
            return new Planrun.Answer(new int[] {8, req.kind(), req.id(), Planrun.STATUS_OK, bm.w, bm.h, bm.ch, rr.dpi}, bm);
        }

        public void start(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            try {
                done.add(fulfil(req));
            } catch (Planrun.PlanError e) {
                done.add(e);
            }
        }

        public Planrun.Answer next() {
            Object d = done.poll();
            if (d instanceof Planrun.PlanError) {
                throw (Planrun.PlanError) d;
            }
            return (Planrun.Answer) d;
        }
    }

    /**
     * Puts several fulfillers behind one: a request goes to the one that takes its kind, and its completion is asked of
     * that one. The Engine's port set.
     */
    public static final class KindMux implements Planrun.Fulfiller {
        private final List<int[]> kinds = new ArrayList<>();
        private final List<Planrun.Fulfiller> parts = new ArrayList<>();
        private final java.util.ArrayDeque<Integer> order = new java.util.ArrayDeque<>();

        /** Adds a fulfiller for these kinds. */
        public KindMux with(int[] k, Planrun.Fulfiller f) {
            kinds.add(k);
            parts.add(f);
            return this;
        }

        public void start(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            for (int i = 0; i < parts.size(); i++) {
                for (int k : kinds.get(i)) {
                    if (k == req.kind()) {
                        parts.get(i).start(req, bm);
                        order.add(i);
                        return;
                    }
                }
            }
            throw Planrun.fulfilFailed("no port is configured for request kind " + req.kind());
        }

        public Planrun.Answer next() {
            if (order.isEmpty()) {
                throw Planrun.fulfilFailed("next without a started request");
            }
            return parts.get(order.poll()).next();
        }
    }
}
