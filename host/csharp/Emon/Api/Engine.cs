// THE ENGINE API, C# host (DEVLOG P9.2, P10.6-P10.11; SPEC 13, 13.1, 3.9; D058, D040, D089-D091, D144-D147, D404-D408). The twin of `host/go/engine.go`,
// `host/rust/src/engine.rs` and `host/java/emon/api/Engine.java`: read those for the reasoning; this is the same machine in C#'s idiom.
//
//     var engine = new Engine(Options.Config.Defaults());
//     Model model = engine.Convert(new List<Input> { Input.Bytes(pdf) }, Options.Defaults());
//     string json = model.ToJson(new Options.Json());
//     string html = model.ToHtml(new Options.Html());
//     foreach (Node p in model.Root().Children()) Console.WriteLine(p.Text());
//
// THE SCHEDULE IS KIN'S (`en-next`, D404-D405): which unit runs next is the machine's phase (`Planrun.Machine`, over `emon.planloop`), and this class is the
// DRIVER of each unit: the document pass, the asset unit, one unit a page, the document unit (`Units.FinishPdf`, one call into `emon.engine`) and the figures
// unit. The loop is wired (D058): a fresh `Src` view and fresh arenas per attempt, a miss checked after each stage, a panic in an attempt that missed is a miss,
// never reported. A sync source is read inline and never misses; `Config.SimulateAsync` makes the cache behave like a browser Blob. The stages kin runs, and the
// order, are `Units`'.
//
// THE STACK: kin's stages recurse, and .NET cannot catch a stack overflow, so the conversion always runs on a thread with a 1 GiB stack (the Engine starts
// one itself unless the caller is already on one of its own): the library is self-contained.
//
// NOTHING IN THIS NAMESPACE NAMES A FILESYSTEM (D058): inputs are `IByteSource`s.

namespace emon.Api;

using System.Collections.Generic;

public sealed class Engine {
    private readonly Options.Config cfg;

    /// <summary>Makes an Engine. It holds only its Config: one Engine serves any number of conversions, on any number of threads.</summary>
    public Engine(Options.Config cfg) {
        this.cfg = cfg;
        if (cfg.ChunkSize < 1) {
            cfg.ChunkSize = 1;
        }
    }

    /// <summary>The result of a conversion: the Model, what the loop did, and (when recording) the transcript.</summary>
    public sealed class Converted {
        public readonly Model Model;
        public readonly RunStats Stats;
        public readonly emon.Planrun.Transcript Transcript;

        internal Converted(Model model, RunStats stats, emon.Planrun.Transcript transcript) {
            Model = model;
            Stats = stats;
            Transcript = transcript;
        }
    }

    // ------------------------------------------------------------------ the stack

    [System.ThreadStatic]
    private static bool onBigStack;

    /// <summary>Runs f on a thread with a 1 GiB stack (kin recurses and .NET cannot catch a stack overflow), unless this thread is one.</summary>
    private static T OnBigStack<T>(System.Func<T> f) {
        if (onBigStack) {
            return f();
        }
        T result = default(T);
        System.Exception err = null;
        var th = new System.Threading.Thread(() => {
            onBigStack = true;
            try {
                result = f();
            } catch (System.Exception e) {
                err = e;
            }
        }, 1 << 30);
        th.Start();
        th.Join();
        if (err != null) {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(err).Throw();
        }
        return result;
    }

    // ------------------------------------------------------------------ the loop

    /// <summary>Stage 0 of each input: format, version, page count, whether it is encrypted. Any number of inputs.</summary>
    public List<Intake> Intake(List<Input> inputs) {
        if (inputs.Count == 0) {
            throw EmonError.Of(EmonError.ErrorKind.NoInput);
        }
        var o = new List<Intake>();
        for (int i = 0; i < inputs.Count; i++) {
            int idx = i;
            Input one = inputs[i];
            Run r = OnBigStack(() => RunInput(idx, one, Options.Defaults(), true, null, null));
            if (r.Outcome.Intake == null) {
                throw EmonError.Internal("intake gave no intake");
            }
            o.Add(r.Outcome.Intake);
        }
        return o;
    }

    /// <summary>Stages 0-3 for the inputs: a Model. Several inputs are not yet one document.</summary>
    public Model Convert(List<Input> inputs, Options opts) {
        return ConvertWith(inputs, opts, null, null).Model;
    }

    /// <summary>Convert and what the loop did (attempts, fetch rounds, bytes fetched).</summary>
    public Converted ConvertStats(List<Input> inputs, Options opts) {
        return ConvertWith(inputs, opts, null, null);
    }

    /// <summary>Converts and RECORDS the plan/fulfil transcript (SPEC 13).</summary>
    public Converted Record(List<Input> inputs, Options opts) {
        return ConvertWith(inputs, opts, new emon.Planrun.Transcript(), null);
    }

    /// <summary>Converts from a transcript: no Renderer or Ocr is called; a request it has no answer for is an error.</summary>
    public Model Replay(List<Input> inputs, Options opts, emon.Planrun.Transcript t) {
        return ConvertWith(inputs, opts, null, t).Model;
    }

    private Converted ConvertWith(List<Input> inputs, Options opts, emon.Planrun.Transcript rec, emon.Planrun.Transcript replay) {
        if (inputs.Count == 0) {
            throw EmonError.Of(EmonError.ErrorKind.NoInput);
        }
        if (inputs.Count > 1) {
            throw EmonError.Unsupported("several inputs as one document are not wired yet (`Intake` takes any number; convert one at a time)");
        }
        Run r = OnBigStack(() => RunInput(0, inputs[0], opts, false, rec, replay));
        Units.Built b = r.Outcome.Built;
        if (b == null) {
            throw EmonError.Internal("the unit finished with no Model");
        }
        return new Converted(new Model(b.Arenas, b.Evidence, b.Intake), r.Stats, rec);
    }

    private sealed class Run {
        internal Units.Outcome Outcome;
        internal RunStats Stats;
    }

    /// <summary>The loop over one input: limits first, a `Src` over its source, the units run as kin schedules them, and the stats.</summary>
    private Run RunInput(int idx, Input input, Options opts, bool intakeOnly, emon.Planrun.Transcript rec, emon.Planrun.Transcript replay) {
        Run r = null;
        emon.Port.WithLimits(cfg.Limits, () => {
            r = RunInputBounded(idx, input, opts, intakeOnly, rec, replay);
        });
        return r;
    }

    // a loop error as the Engine reports it: a named failure of the loop, or an internal error
    private static EmonError LoopError(System.Exception e) {
        if (e is emon.Planrun.PlanError pe) {
            return EmonError.FromPlan(pe);
        }
        return EmonError.Panic(e);
    }

    private static long Us(long t0) {
        return (System.Diagnostics.Stopwatch.GetTimestamp() - t0) * 1000000 / System.Diagnostics.Stopwatch.Frequency;
    }

    private Run RunInputBounded(int idx, Input input, Options opts, bool intakeOnly, emon.Planrun.Transcript rec, emon.Planrun.Transcript replay) {
        // the input-bytes bound is checked against the SIZE, before any byte is read (SPEC 13.1)
        long size = input.Source.Size();
        long limit = System.Math.Min((long) emon.Port.LimitOf(0), int.MaxValue);
        if (size > limit || size > int.MaxValue) {
            throw EmonError.Limited("input-bytes", limit, size);
        }
        int n = (int) size;
        bool simulate = cfg.SimulateAsync;
        emon.Port.ISrcBacking backing;
        ChunkCache cache = null;
        byte[] slice = input.Source.AsSlice();
        if (slice != null && !simulate) {
            backing = new ChunkCache.SliceBacking(slice);
        } else {
            cache = new ChunkCache(input.Source, n, System.Math.Max(cfg.ChunkSize, 1), simulate, idx);
            backing = cache;
            if (simulate) {
                cache.PrefetchEnds();
            }
        }
        ChunkCache cch = cache;
        var bas = new emon.Port.Src(backing);
        emon.Port.Src pw = emon.Port.SrcOfBlob((byte[]) input.Password.Clone());
        Units.Outcome res = new Units.Outcome();
        Units.PdfDoc docPdf = null;
        Units.RasterDoc docRaster = null;
        emon.Planrun.Unit unit = (answers, rq, bms, payload) => {
            emon.Port.Src view = bas.View();
            Units.Step s = Units.Dispatch(view, pw, answers, rq, idx, opts, intakeOnly, size);
            if (s.Miss) {
                return emon.Planrun.UnitNeedBytes;
            }
            if (s.Requests) {
                return emon.Planrun.UnitRequests;
            }
            res = s.Out != null ? s.Out : new Units.Outcome();
            docPdf = s.Pdf;
            docRaster = s.Raster;
            return emon.Planrun.UnitDone;
        };
        emon.Planrun.IFulfiller bytesF = new emon.Planrun.Sync((req, bm) => {
            int off = req.Field(emon.Plan.BqOffset()), len = req.Field(emon.Plan.BqLength());
            if (cch != null && !cch.Fetch(off, len)) {
                throw emon.Planrun.FulfilFailed("a read of the source failed");
            }
            emon.Port.Cells c = emon.Port.NewCells();
            emon.Plan.AnBytes(c, req.Id(), off, len);
            return new emon.Planrun.Answer(emon.Port.CellsVec(c));
        });
        emon.Planrun.IMisses misses = cache != null ? cache : emon.Planrun.NoMisses;
        var pcfg = new emon.Planrun.Config(cfg.MaxRounds, cfg.FetchRounds);
        var thisInput = new List<int[]>();
        thisInput.Add(new int[] {idx, (int) size});
        // the live port set (D127, D211): `render` and `crop` go to the configured Renderer, reading the same input through the same byte source; with none
        // they, like the other kinds, fall to the host's own fulfiller, which answers them `unsupported` (D199). Which port a request goes to is kin's
        // (`pl-route`). Pixels are kept in the answers when a transcript is being recorded or an OCR engine will read them.
        emon.Planrun.IFulfiller renderF = null;
        if (cfg.Renderer != null) {
            byte[] rpw = input.Password.Length > 0 ? input.Password : null;
            renderF = emon.RenderPort.Fulfiller.ForInput(cfg.Renderer, idx, new emon.RenderPort.Input(input.Source, rpw));
        }
        emon.Planrun.IFulfiller ports = new Ports.Live(renderF,
            Ports.PortFulfiller(input.Source, idx, rec != null || cfg.Ocr != null, rec != null, cfg.Ocr, cfg.PdfCodecs), cfg.Ocr != null);
        emon.Planrun.Replayer replayer = null;
        if (rec != null) {
            rec.Inputs = thisInput;
            if (cfg.Ocr != null) {
                rec.Producer = cfg.Ocr.Producer();
            }
        }
        if (replay != null) {
            try {
                replay.CheckInputs(thisInput);
            } catch (emon.Planrun.PlanError e) {
                throw EmonError.FromPlan(e);
            }
            replayer = new emon.Planrun.Replayer(replay);
            ports = replayer;
        }
        var timed = new Ports.Timed(ports);
        ports = timed;
        // THE SCHEDULE IS KIN'S (`en-next`): which unit runs next is the machine's phase, and this function is the driver of each unit. The unit that just
        // finished is folded into the totals, and the schedule moves on.
        var m = new emon.Planrun.Machine(pcfg);
        var stats = new RunStats();
        stats.InputBytes = size;
        long tPages = 0;
        for (;;) {
            int phase = m.Phase();
            if (phase == emon.Planloop.PhDoc()) {
                // THE DOCUMENT PASS: everything that reads the input, in its own loop (misses fetch and re-run it).
                long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
                System.Exception perr = null;
                try {
                    emon.Planrun.Drive(m, unit, ports, bytesF, misses, rec);
                } catch (System.Exception e) {
                    perr = e;
                }
                stats.PassUs = Us(t0);
                SourceFailed(cache, idx);
                if (perr != null) {
                    throw LoopError(perr);
                }
                tPages = System.Diagnostics.Stopwatch.GetTimestamp();
                Advance(m, docPdf, docRaster, opts);
            } else if (phase == emon.Planloop.PhAsset()) {
                // THE ASSET UNIT (P6.1a, D117): a PDF's image placements become asset candidates, and a codec image's pixels are asked of the host
                // (`decode-img`, one round for them all); it reads the input (and so may miss, like the document pass)
                EmonError afailed = null;
                Units.PdfDoc dp = docPdf;
                emon.Planrun.Unit unitA = (answers, rq, bms, payload) => {
                    emon.Port.Src view = bas.View();
                    try {
                        int s = Units.AssetUnit(view, pw, dp, opts, idx, answers, rq, bms, payload);
                        if (s == 0) {
                            return emon.Planrun.UnitDone;
                        }
                        if (s == 1) {
                            return emon.Planrun.UnitRequests;
                        }
                        if (s == 7 || emon.Port.SrcMissed(view)) {
                            return emon.Planrun.UnitNeedBytes;
                        }
                        afailed = EmonError.Internal("the asset unit answered status " + s);
                    } catch (System.Exception p) {
                        if (emon.Port.SrcMissed(view)) {
                            return emon.Planrun.UnitNeedBytes;
                        }
                        afailed = EmonError.Panic(p);
                    }
                    return emon.Planrun.UnitDone;
                };
                System.Exception aerr = null;
                try {
                    emon.Planrun.Drive(m, unitA, ports, bytesF, misses, rec);
                } catch (System.Exception e) {
                    aerr = e;
                }
                SourceFailed(cache, idx);
                if (aerr != null) {
                    throw LoopError(aerr);
                }
                if (afailed != null) {
                    throw afailed;
                }
                Advance(m, docPdf, docRaster, opts);
            } else if (phase == emon.Planloop.PhPages()) {
                // THE PAGE UNITS (P7.U, D195): one plan/fulfil loop a page, each with its own answers, requests and BitmapSet, dropped when the unit ends, so a
                // conversion holds one page's bitmaps and not the document's. A page unit never reads the input.
                int page = m.Page();
                EmonError failed = null;
                Units.PdfDoc dp = docPdf;
                Units.RasterDoc dr = docRaster;
                emon.Planrun.Unit unitP = (answers, rq, bms, payload) => {
                    try {
                        int s = dp != null ? Units.UnitPdf(dp, page, idx, opts, answers, rq, bms) : Units.UnitRaster(dr, page, idx, size, opts, answers, rq, bms);
                        if (s == 0) {
                            return emon.Planrun.UnitDone;
                        }
                        if (s == 1) {
                            return emon.Planrun.UnitRequests;
                        }
                        if (s == emon.Planloop.DsUndecodable() || s == emon.Planloop.DsUnsupportedImage()) {
                            failed = Units.Damaged(idx, s);
                        } else {
                            failed = EmonError.Internal("a page unit answered status " + s);
                        }
                    } catch (System.Exception p) {
                        failed = EmonError.Panic(p);
                    }
                    return emon.Planrun.UnitDone;
                };
                try {
                    emon.Planrun.Drive(m, unitP, ports, bytesF, emon.Planrun.NoMisses, rec);
                } catch (System.Exception e) {
                    throw LoopError(e);
                }
                if (failed != null) {
                    throw failed;
                }
                Advance(m, docPdf, docRaster, opts);
            } else if (phase == emon.Planloop.PhFinish()) {
                stats.PagesUs = Us(tPages);
                long t0 = System.Diagnostics.Stopwatch.GetTimestamp();
                // the document unit is kin too: a panic in it (a bug, or a limit breach) is the Engine's named error, never the embedder's (P10.5, D386)
                try {
                    res = docPdf != null ? Units.FinishPdf(docPdf, opts) : Units.FinishRaster(docRaster, opts);
                } catch (System.Exception p) {
                    res = Units.Outcome.Error(EmonError.Panic(p));
                }
                stats.FinishUs = Us(t0);
                m.NextPhase(0, false, res.Built != null && res.Built.Pending != null, 0);
            } else if (phase == emon.Planloop.PhFigures()) {
                // THE ASSET UNIT (P8.5, D351): at `figures` the Model is final but for its assets, whose renders and crops are requests, so one more
                // plan/fulfil loop runs over it, with its own answers and BitmapSet, on copies until every answer is in.
                Units.Built b = res.Built;
                Units.Pending pend = b.Pending;
                b.Pending = null;
                emon.Enctx.Arenas done = null;
                EmonError afailed = null;
                emon.Planrun.Unit unitF = (answers, rq, bms, payload) => {
                    try {
                        emon.Enctx.Arenas t = b.Arenas.Clone();
                        var p = new Units.Pending(Units.CloneCells(pend.Cand), Units.CloneBuf(pend.Cbytes), pend.Rfrom);
                        Units.AssetStageFigures(t, p, opts, idx, (int) size, answers, rq, bms);
                        if (emon.Port.CellsLen(rq) > 0) {
                            return emon.Planrun.UnitRequests;
                        }
                        done = t;
                    } catch (System.Exception ex) {
                        afailed = EmonError.Panic(ex);
                    }
                    return emon.Planrun.UnitDone;
                };
                try {
                    emon.Planrun.Drive(m, unitF, ports, bytesF, emon.Planrun.NoMisses, rec);
                } catch (System.Exception e) {
                    throw LoopError(e);
                }
                if (afailed != null) {
                    throw afailed;
                }
                if (done == null) {
                    throw EmonError.Internal("the asset unit finished with no Model");
                }
                Advance(m, docPdf, docRaster, opts);
                b.Arenas = done;
                EmonError sealedErr = Units.SealArenas(b.Arenas, opts);
                if (sealedErr != null) {
                    throw sealedErr;
                }
            } else {
                break;
            }
        }
        emon.Planrun.Totals t2 = m.Totals();
        stats.Attempts = t2.Attempts;
        stats.FetchRounds = t2.FetchRounds;
        stats.BytesRequests = t2.BytesRequests;
        stats.Requests = t2.Requests;
        stats.PageRounds.AddRange(t2.PageRounds);
        stats.PageRequests.AddRange(t2.PageRequests);
        for (int i = 0; i < 8; i++) {
            stats.FulfilUs[i] = timed.Us[i];
        }
        if (replayer != null) {
            try {
                replayer.Finish();
            } catch (emon.Planrun.PlanError e) {
                throw EmonError.FromPlan(e);
            }
        }
        if (cache != null) {
            long[] s = cache.Stats();
            stats.SourceReads = s[0];
            stats.BytesFetched = s[1];
        }
        if (res.Err != null) {
            throw res.Err;
        }
        if (res.Built == null && res.Intake == null) {
            throw EmonError.Internal("the unit finished with nothing");
        }
        var run = new Run();
        run.Outcome = res;
        run.Stats = stats;
        return run;
    }

    // the unit that just finished is folded into the totals, and the schedule moves on
    private static void Advance(emon.Planrun.Machine m, Units.PdfDoc dp, Units.RasterDoc dr, Options opts) {
        int kind = 0, np = 0;
        if (dp != null) {
            kind = 1;
            np = dp.Info.Pages;
        } else if (dr != null) {
            kind = 2;
            np = dr.Info.Pages;
        }
        m.EndUnit();
        m.NextPhase(kind, opts.Assets != Options.AssetsLevel.None, false, np);
    }

    // a source that failed is the answer, whatever the loop made of the miss it caused
    private static void SourceFailed(ChunkCache cache, int idx) {
        if (cache == null) {
            return;
        }
        object[] f = cache.Fail();
        if (f != null) {
            if ((bool) f[0]) {
                throw EmonError.Of(EmonError.ErrorKind.SourceChanged, idx);
            }
            throw EmonError.Io(idx, (string) f[1]);
        }
    }

    /// <summary>Which of the Engine's names a Kind lookup uses: kept so a driver can map a name to a Model.Kind.</summary>
    public static Dictionary<string, Model.Kind> Kinds() {
        var m = new Dictionary<string, Model.Kind>();
        for (int k = 1; k <= emon.Model.NkInitial(); k++) {
            var kd = new Model.Kind(k);
            m[kd.Name()] = kd;
        }
        return m;
    }
}
