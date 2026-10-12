// The `Renderer` port, C# host (DEVLOG P9.2; P6.2, D127; SPEC 7.2, D007). The twin of `host/go/port/render.go` and
// `host/java/emon/RenderPort.java`: read those for the reasoning. A page of an input, at a resolution, in a layer,
// optionally cut to a pixel rectangle, as an RGB Bitmap.
//
// This file is the PORT: the interface an engine implements, the request it is given, and the fulfiller that turns
// `render` and `crop` plan requests (D089, D117) into calls and answers. It names no engine and no filesystem.
// `emon.Ffi.Pdfium` (P/Invoke over the pinned libpdfium) is the default engine (D007).

namespace emon;

using System.Collections.Generic;

public static class RenderPort {
    // `emon.plan`'s layers (`lay-*`); a kin law holds the numbers.
    public const int LayerFull = 0;
    public const int LayerNoText = 1;
    public const int LayerTextOnly = 2;
    public const int LayerNoWatermark = 3;
    public const int LayerWatermarkOnly = 4;

    public const int StatusUnsupported = 2;

    /// <summary>A pixel rectangle of the whole-page bitmap.</summary>
    public sealed class Clip {
        public readonly int X, Y, W, H;

        public Clip(int x, int y, int w, int h) {
            X = x;
            Y = y;
            W = w;
            H = h;
        }
    }

    /// <summary>One page of one input, as the Renderer is asked for it.</summary>
    public sealed class Request {
        public readonly int Page; // 0-based
        public readonly int Dpi;
        public readonly int Layer;
        public readonly Clip Clip; // null: the whole page

        public Request(int page, int dpi, int layer, Clip clip) {
            Page = page;
            Dpi = dpi;
            Layer = layer;
            Clip = clip;
        }

        public Request WithoutClip() {
            return new Request(Page, Dpi, Layer, null);
        }
    }

    /// <summary>The host's random-access byte source (the engine's ByteSource, structurally).</summary>
    public interface ISource {
        long Size();

        /// <summary>Fills buf[boff, boff+len) from byte off: the count, fewer only at the end, -1 at or past it.</summary>
        int ReadAt(long off, byte[] buf, int boff, int len);
    }

    /// <summary>
    /// How a render did not happen. `Unsupported` is `ps-unsupported` (an engine that cannot separate a layer says so
    /// and kin plans around it); anything else is `ps-failed`.
    /// </summary>
    public sealed class RenderError : System.Exception {
        public readonly bool Unsupported;

        public RenderError(bool unsupported, string msg)
            : base((unsupported ? "render unsupported: " : "render failed: ") + msg) {
            Unsupported = unsupported;
        }
    }

    /// <summary>The port. It must be callable from any thread; an engine that is not thread-safe, as pdfium is not, serialises inside.</summary>
    public interface IRenderer {
        /// <summary>
        /// Renders req of the document `input` (opened with `password` when it is encrypted, null for none). The result
        /// is RGB (3 channels): the tracer wants colour for ink separation (SPEC 8.2.1) and OCR takes it as it is.
        /// </summary>
        Port.Bitmap Render(ISource input, byte[] password, Request req);

        /// <summary>The set of layers it can separate, as a bit set over Layer* (bit n = layer n).</summary>
        int Layers();

        /// <summary>
        /// Is a render with a Clip EXACTLY the cut of the same render without one (SPEC 7.2: "the same pixels, cut out,
        /// never a re-fit")? pdfium is not: a clipped render moves the pixels on the clip's edge (measured, D127). When
        /// false the fulfiller never passes a Clip: it renders the whole page and cuts.
        /// </summary>
        bool ExactCrop();
    }

    /// <summary>The size in pixels of `pt` points at `dpi`: floor(pt*dpi/72 + 1/2), at least 1.</summary>
    public static int PixelsOf(double pt, int dpi) {
        double v = System.Math.Floor(pt * (double) dpi / 72.0 + 0.5);
        if (v < 1) {
            return 1;
        }
        if (v > int.MaxValue) {
            return int.MaxValue;
        }
        return (int) v;
    }

    /// <summary>One input of the run, as the fulfiller reads it.</summary>
    public sealed class Input {
        public readonly ISource Source;
        public readonly byte[] Password;

        public Input(ISource source, byte[] password) {
            Source = source;
            Password = password;
        }
    }

    /// <summary>A parsed render request: the input index and what is asked of it.</summary>
    public sealed class Parsed {
        public readonly int Input;
        public readonly Request Request;

        internal Parsed(int input, Request request) {
            Input = input;
            Request = request;
        }
    }

    /// <summary>Reads a `render` (args input page dpi layer) or `crop` (and px py pw ph) request.</summary>
    public static Parsed ParseRender(Planrun.Request req) {
        int n;
        bool crop = false;
        switch (req.Kind()) {
            case Planrun.KindRender:
                n = 4;
                break;
            case Planrun.KindCrop:
                n = 8;
                crop = true;
                break;
            default:
                throw new System.ArgumentException("kind " + req.Kind() + " is not a render");
        }
        if (req.Rec.Length != 3 + n) {
            throw new System.ArgumentException("a render request has " + (req.Rec.Length - 3) + " args, not " + n);
        }
        if (req.Field(Plan.RdInput()) < 0) {
            throw new System.ArgumentException("input " + req.Field(Plan.RdInput()));
        }
        Clip clip = crop ? new Clip(req.Field(Plan.RdX()), req.Field(Plan.RdY()), req.Field(Plan.RdW()), req.Field(Plan.RdH())) : null;
        return new Parsed(req.Field(Plan.RdInput()), new Request(req.Field(Plan.RdPage()), req.Field(Plan.RdDpi()), req.Field(Plan.RdLayer()), clip));
    }

    /// <summary>The pixels of rectangle c of b, as a Bitmap of its own: the crop law (D117), which a test holds an engine's own crops to.</summary>
    public static Port.Bitmap Cut(Port.Bitmap b, Clip c) {
        if (c.W < 1 || c.H < 1 || c.X < 0 || c.Y < 0 || (long) c.X + c.W > b.W || (long) c.Y + c.H > b.H) {
            throw new RenderError(false, "the crop " + c.W + "x" + c.H + "+" + c.X + "+" + c.Y + " is not inside the " + b.W + "x" + b.H + " page");
        }
        int ch = b.Ch;
        byte[] o = new byte[c.W * c.H * ch];
        for (int y = 0; y < c.H; y++) {
            System.Array.Copy(b.D, (c.Y + y) * b.Stride + c.X * ch, o, y * c.W * ch, c.W * ch);
        }
        return new Port.Bitmap(c.W, c.H, b.Ch, o);
    }

    /// <summary>
    /// Fulfils `render` and `crop` with a Renderer, synchronously: a render is CPU work, and a host that wants them
    /// concurrent wraps this in its own pool. Reading the input happens inside. A crop is the cut of a whole-page render
    /// (<see cref="Cut"/>), and the LAST whole-page render is kept, so a run that asks for a page and then for regions of it
    /// (the assets, D117) renders it once.
    /// </summary>
    public sealed class Fulfiller : Planrun.IFulfiller {
        public readonly IRenderer Renderer;
        public readonly Dictionary<int, Input> Inputs;
        private readonly Queue<object> done = new Queue<object>();
        private string lastKey;
        private Port.Bitmap last;
        private readonly object gate = new object();

        public Fulfiller(IRenderer r, Dictionary<int, Input> inputs) {
            Renderer = r;
            Inputs = inputs;
        }

        /// <summary>Serves ONE input of a run, at its index (the Engine converts one input at a time).</summary>
        public static Fulfiller ForInput(IRenderer r, int idx, Input inp) {
            var m = new Dictionary<int, Input>();
            m[idx] = inp;
            return new Fulfiller(r, m);
        }

        private Port.Bitmap Whole(int idx, Input inp, Request rr) {
            lock (gate) {
                string key = idx + "/" + rr.Page + "/" + rr.Dpi + "/" + rr.Layer;
                if (last != null && key == lastKey) {
                    return last;
                }
                Port.Bitmap b = Renderer.Render(inp.Source, inp.Password, rr.WithoutClip());
                lastKey = key;
                last = b;
                return b;
            }
        }

        /// <summary>Answers one request.</summary>
        public Planrun.Answer Fulfil(Planrun.Request req) {
            Parsed p;
            try {
                p = ParseRender(req);
            } catch (System.ArgumentException e) {
                throw Planrun.FulfilFailed(e.Message);
            }
            if (!Inputs.TryGetValue(p.Input, out Input inp)) {
                throw Planrun.FulfilFailed("a render names input " + p.Input + ", which the run does not have");
            }
            Request rr = p.Request;
            Port.Bitmap bm;
            try {
                if (rr.Clip == null) {
                    bm = Whole(p.Input, inp, rr);
                } else if (Renderer.ExactCrop()) {
                    bm = Renderer.Render(inp.Source, inp.Password, rr);
                } else {
                    bm = Cut(Whole(p.Input, inp, rr), rr.Clip);
                }
            } catch (RenderError e) {
                int status = e.Unsupported ? StatusUnsupported : Planrun.StatusFailed;
                return new Planrun.Answer(new int[] {4, req.Kind(), req.Id(), status});
            }
            return new Planrun.Answer(new int[] {8, req.Kind(), req.Id(), Planrun.StatusOk, bm.W, bm.H, bm.Ch, rr.Dpi}, bm);
        }

        public void Start(Planrun.Request req, Dictionary<int, Port.Bitmap> bm) {
            try {
                done.Enqueue(Fulfil(req));
            } catch (Planrun.PlanError e) {
                done.Enqueue(e);
            }
        }

        public Planrun.Answer Next() {
            object d = done.Count > 0 ? done.Dequeue() : null;
            if (d is Planrun.PlanError pe) {
                throw pe;
            }
            return (Planrun.Answer) d;
        }
    }

    /// <summary>
    /// Puts several fulfillers behind one: a request goes to the one that takes its kind, and its completion is asked of
    /// that one. The Engine's port set.
    /// </summary>
    public sealed class KindMux : Planrun.IFulfiller {
        private readonly List<int[]> kinds = new List<int[]>();
        private readonly List<Planrun.IFulfiller> parts = new List<Planrun.IFulfiller>();
        private readonly Queue<int> order = new Queue<int>();

        /// <summary>Adds a fulfiller for these kinds.</summary>
        public KindMux With(int[] k, Planrun.IFulfiller f) {
            kinds.Add(k);
            parts.Add(f);
            return this;
        }

        public void Start(Planrun.Request req, Dictionary<int, Port.Bitmap> bm) {
            for (int i = 0; i < parts.Count; i++) {
                foreach (int k in kinds[i]) {
                    if (k == req.Kind()) {
                        parts[i].Start(req, bm);
                        order.Enqueue(i);
                        return;
                    }
                }
            }
            throw Planrun.FulfilFailed("no port is configured for request kind " + req.Kind());
        }

        public Planrun.Answer Next() {
            if (order.Count == 0) {
                throw Planrun.FulfilFailed("next without a started request");
            }
            return parts[order.Dequeue()].Next();
        }
    }
}
