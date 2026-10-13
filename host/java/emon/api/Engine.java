// THE ENGINE API, Java host (DEVLOG P9.1, P10.6-P10.11; SPEC 13, 13.1, 3.9; D058, D040, D089-D091, D144-D147, D404-D408). The twin of `host/go/engine.go`
// and `host/rust/src/engine.rs`: read those for the reasoning; this is the same machine in Java's idiom.
//
//     Engine engine = new Engine(Options.Config.defaults());
//     Model model = engine.convert(List.of(Input.bytes(pdf)), Options.defaults());
//     String json = model.toJson(new Options.Json());
//     String html = model.toHtml(new Options.Html());
//     for (Node p : model.root().children()) System.out.println(p.text());
//
// THE SCHEDULE IS KIN'S (`en-next`, D404-D405): which unit runs next is the machine's phase (`Planrun.Machine`, over `emon.planloop`), and this class is the
// DRIVER of each unit: the document pass, the asset unit, one unit a page, the document unit and the figures unit, each ONE call into `emon.engine`
// (`Units`). The loop is wired (D058): a fresh `Src` view and fresh arenas per attempt, a miss checked after each stage, a panic in an attempt that missed is a
// miss, never reported. A sync source is read inline and never misses; `Config.simulateAsync` makes the cache behave like a browser Blob. The stages kin runs,
// and the order, are kin's (`emon.engine`).
//
// NOTHING IN THIS PACKAGE NAMES A FILESYSTEM (D058): inputs are `ByteSource`s.

package emon.api;

import emon.Plan;
import emon.Planloop;
import emon.Planrun;
import emon.Port;
import emon.RenderPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Engine {
    private final Options.Config cfg;

    /** Makes an Engine. It holds only its Config: one Engine serves any number of conversions, on any number of threads. */
    public Engine(Options.Config cfg) {
        this.cfg = cfg;
        if (cfg.chunkSize < 1) {
            cfg.chunkSize = 1;
        }
    }

    /** The result of a conversion: the Model, what the loop did, and (when recording) the transcript. */
    public static final class Converted {
        public final Model model;
        public final RunStats stats;
        public final Planrun.Transcript transcript;

        Converted(Model model, RunStats stats, Planrun.Transcript transcript) {
            this.model = model;
            this.stats = stats;
            this.transcript = transcript;
        }
    }

    /** Stage 0 of each input: format, version, page count, whether it is encrypted. Any number of inputs. */
    public List<Intake> intake(List<Input> inputs) throws EmonError {
        if (inputs.isEmpty()) {
            throw EmonError.of(EmonError.Kind.NO_INPUT);
        }
        List<Intake> out = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            Run r = runInput(i, inputs.get(i), Options.defaults(), true, null, null);
            if (r.outcome.intake == null) {
                throw EmonError.internal("intake gave no intake");
            }
            out.add(r.outcome.intake);
        }
        return out;
    }

    /** Stages 0-3 for the inputs: a Model. Several inputs are not yet one document. */
    public Model convert(List<Input> inputs, Options opts) throws EmonError {
        return convertWith(inputs, opts, null, null).model;
    }

    /** {@link #convert} and what the loop did (attempts, fetch rounds, bytes fetched). */
    public Converted convertStats(List<Input> inputs, Options opts) throws EmonError {
        return convertWith(inputs, opts, null, null);
    }

    /** Converts and RECORDS the plan/fulfil transcript (SPEC 13). */
    public Converted record(List<Input> inputs, Options opts) throws EmonError {
        return convertWith(inputs, opts, new Planrun.Transcript(), null);
    }

    /** Converts from a transcript: no Renderer or Ocr is called; a request it has no answer for is an error. */
    public Model replay(List<Input> inputs, Options opts, Planrun.Transcript t) throws EmonError {
        return convertWith(inputs, opts, null, t).model;
    }

    private Converted convertWith(List<Input> inputs, Options opts, Planrun.Transcript rec, Planrun.Transcript replay) throws EmonError {
        if (inputs.isEmpty()) {
            throw EmonError.of(EmonError.Kind.NO_INPUT);
        }
        if (inputs.size() > 1) {
            throw EmonError.unsupported("several inputs as one document are not wired yet (`intake` takes any number; convert one at a time)");
        }
        Run r = runInput(0, inputs.get(0), opts, false, rec, replay);
        Units.Built b = r.outcome.built;
        if (b == null) {
            throw EmonError.internal("the unit finished with no Model");
        }
        return new Converted(new Model(b.arenas, b.evidence, b.intake), r.stats, rec);
    }

    private static final class Run {
        Units.Outcome outcome;
        RunStats stats;
    }

    /** The loop over one input: limits first, a `Src` over its source, the units run as kin schedules them, and the stats. */
    private Run runInput(int idx, Input in, Options opts, boolean intakeOnly, Planrun.Transcript rec, Planrun.Transcript replay) throws EmonError {
        Object[] box = new Object[1];
        Port.withLimits(cfg.limits, () -> {
            try {
                box[0] = runInputBounded(idx, in, opts, intakeOnly, rec, replay);
            } catch (EmonError e) {
                box[0] = e;
            }
        });
        if (box[0] instanceof EmonError) {
            throw (EmonError) box[0];
        }
        return (Run) box[0];
    }

    // a loop error as the Engine reports it: a named failure of the loop, or an internal error
    private static EmonError loopError(RuntimeException e) {
        if (e instanceof Planrun.PlanError) {
            return EmonError.plan((Planrun.PlanError) e);
        }
        return EmonError.panic(e);
    }

    private Run runInputBounded(int idx, Input in, Options opts, boolean intakeOnly, Planrun.Transcript rec, Planrun.Transcript replay) throws EmonError {
        // the input-bytes bound is checked against the SIZE, before any byte is read (SPEC 13.1)
        final long size = in.source.size();
        long limit = Math.min((long) Port.limitOf(0), Integer.MAX_VALUE);
        if (size > limit || size > Integer.MAX_VALUE) {
            throw EmonError.limit("input-bytes", limit, size);
        }
        final int n = (int) size;
        boolean simulate = cfg.simulateAsync;
        Port.SrcBacking backing;
        ChunkCache cache = null;
        byte[] slice = in.source.asSlice();
        if (slice != null && !simulate) {
            backing = new ChunkCache.SliceBacking(slice);
        } else {
            cache = new ChunkCache(in.source, n, Math.max(cfg.chunkSize, 1), simulate, idx);
            backing = cache;
            if (simulate) {
                cache.prefetchEnds();
            }
        }
        final ChunkCache cch = cache;
        final Port.Src base = new Port.Src(backing);
        final Port.Src pw = Port.srcOfBlob(in.password.clone());
        final Units.Outcome[] res = {new Units.Outcome()};
        final Units.UnitDoc[] docU = {null};
        Planrun.Unit unit = (answers, rq, bms, payload) -> {
            Port.Src view = base.view();
            Units.Step s = Units.dispatch(view, pw, answers, rq, idx, opts, intakeOnly, size);
            if (s.miss) {
                return Planrun.UNIT_NEED_BYTES;
            }
            if (s.requests) {
                return Planrun.UNIT_REQUESTS;
            }
            res[0] = s.out != null ? s.out : new Units.Outcome();
            docU[0] = s.doc;
            return Planrun.UNIT_DONE;
        };
        Planrun.Fulfiller bytesF = new Planrun.Sync((req, bm) -> {
            int off = req.field(Plan.bqOffset()), len = req.field(Plan.bqLength());
            if (cch != null && !cch.fetch(off, len)) {
                throw Planrun.fulfilFailed("a read of the source failed");
            }
            Port.Cells c = Port.newCells();
            Plan.anBytes(c, req.id(), off, len);
            return new Planrun.Answer(Port.cellsVec(c));
        });
        Planrun.Misses misses = cache != null ? cache : Planrun.NO_MISSES;
        Planrun.Config pcfg = new Planrun.Config(cfg.maxRounds, cfg.fetchRounds);
        List<int[]> thisInput = new ArrayList<>();
        thisInput.add(new int[] {idx, (int) size});
        // the live port set (D127, D211): `render` and `crop` go to the configured Renderer, reading the same input through the same byte source; with none
        // they, like the other kinds, fall to the host's own fulfiller, which answers them `unsupported` (D199). Which port a request goes to is kin's
        // (`pl-route`). Pixels are kept in the answers when a transcript is being recorded or an OCR engine will read them.
        Planrun.Fulfiller renderF = null;
        if (cfg.renderer != null) {
            byte[] rpw = in.password.length > 0 ? in.password : null;
            renderF = RenderPort.Fulfiller.forInput(cfg.renderer, idx, new RenderPort.Input(in.source, rpw));
        }
        Planrun.Fulfiller ports = new Ports.Live(renderF,
            Ports.portFulfiller(in.source, idx, rec != null || cfg.ocr != null, rec != null, cfg.ocr, cfg.pdfCodecs), cfg.ocr != null);
        Planrun.Replayer replayer = null;
        if (rec != null) {
            rec.inputs = thisInput;
            if (cfg.ocr != null) {
                rec.producer = cfg.ocr.producer();
            }
        }
        if (replay != null) {
            try {
                replay.checkInputs(thisInput);
            } catch (Planrun.PlanError e) {
                throw EmonError.plan(e);
            }
            replayer = new Planrun.Replayer(replay);
            ports = replayer;
        }
        Ports.Timed timed = new Ports.Timed(ports);
        ports = timed;
        // THE SCHEDULE IS KIN'S (`en-next`): which unit runs next is the machine's phase, and this function is the driver of each unit. The unit that just
        // finished is folded into the totals, and the schedule moves on.
        Planrun.Machine m = new Planrun.Machine(pcfg);
        RunStats stats = new RunStats();
        stats.inputBytes = size;
        long tPages = 0;
        phases:
        for (;;) {
            int phase = m.phase();
            if (phase == Planloop.phDoc()) {
                // THE DOCUMENT PASS: everything that reads the input, in its own loop (misses fetch and re-run it).
                long t0 = System.nanoTime();
                RuntimeException perr = null;
                try {
                    Planrun.drive(m, unit, ports, bytesF, misses, rec);
                } catch (RuntimeException e) {
                    perr = e;
                }
                stats.passUs = (System.nanoTime() - t0) / 1000;
                sourceFailed(cache, idx);
                if (perr != null) {
                    throw loopError(perr);
                }
                tPages = System.nanoTime();
                advance(m, docU[0], opts);
            } else if (phase == Planloop.phAsset()) {
                // THE ASSET UNIT (P6.1a, D117): a PDF's image placements become asset candidates, and a codec image's pixels are asked of the host
                // (`decode-img`, one round for them all); it reads the input (and so may miss, like the document pass)
                final EmonError[] afailed = {null};
                final Units.UnitDoc du = docU[0];
                Planrun.Unit unitA = (answers, rq, bms, payload) -> {
                    Port.Src view = base.view();
                    try {
                        int s = Units.assetUnit(view, pw, du, opts, idx, answers, rq, bms, payload);
                        if (s == 0) {
                            return Planrun.UNIT_DONE;
                        }
                        if (s == 1) {
                            return Planrun.UNIT_REQUESTS;
                        }
                        if (s == 7 || Port.srcMissed(view)) {
                            return Planrun.UNIT_NEED_BYTES;
                        }
                        afailed[0] = EmonError.internal("the asset unit answered status " + s);
                    } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
                        if (Port.srcMissed(view)) {
                            return Planrun.UNIT_NEED_BYTES;
                        }
                        afailed[0] = EmonError.panic(p);
                    }
                    return Planrun.UNIT_DONE;
                };
                RuntimeException aerr = null;
                try {
                    Planrun.drive(m, unitA, ports, bytesF, misses, rec);
                } catch (RuntimeException e) {
                    aerr = e;
                }
                sourceFailed(cache, idx);
                if (aerr != null) {
                    throw loopError(aerr);
                }
                if (afailed[0] != null) {
                    throw afailed[0];
                }
                advance(m, docU[0], opts);
            } else if (phase == Planloop.phPages()) {
                // THE PAGE UNITS (P7.U, D195): one plan/fulfil loop a page, each with its own answers, requests and BitmapSet, dropped when the unit ends, so a
                // conversion holds one page's bitmaps and not the document's. A page unit never reads the input.
                final int page = m.page();
                final EmonError[] failed = {null};
                final Units.UnitDoc du = docU[0];
                Planrun.Unit unitP = (answers, rq, bms, payload) -> {
                    try {
                        int s = Units.unitPage(du, page, idx, size, opts, answers, rq, bms);
                        if (s == 0) {
                            return Planrun.UNIT_DONE;
                        }
                        if (s == 1) {
                            return Planrun.UNIT_REQUESTS;
                        }
                        if (s == Planloop.dsUndecodable() || s == Planloop.dsUnsupportedImage()) {
                            failed[0] = Units.damaged(idx, s);
                        } else {
                            failed[0] = EmonError.internal("a page unit answered status " + s);
                        }
                    } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
                        failed[0] = EmonError.panic(p);
                    }
                    return Planrun.UNIT_DONE;
                };
                try {
                    Planrun.drive(m, unitP, ports, bytesF, Planrun.NO_MISSES, rec);
                } catch (RuntimeException e) {
                    throw loopError(e);
                }
                if (failed[0] != null) {
                    throw failed[0];
                }
                advance(m, docU[0], opts);
            } else if (phase == Planloop.phFinish()) {
                stats.pagesUs = (System.nanoTime() - tPages) / 1000;
                long t0 = System.nanoTime();
                // the document unit is kin too: a panic in it (a bug, or a limit breach) is the Engine's named error, never the embedder's (P10.5, D386)
                try {
                    res[0] = Units.finishDoc(docU[0], opts);
                } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
                    res[0] = Units.Outcome.error(EmonError.panic(p));
                }
                stats.finishUs = (System.nanoTime() - t0) / 1000;
                m.nextPhase(0, false, res[0].built != null && res[0].built.pending != null, 0);
            } else if (phase == Planloop.phFigures()) {
                // THE ASSET UNIT (P8.5, D351): at `figures` the Model is final but for its assets, whose renders and crops are requests, so one more
                // plan/fulfil loop runs over it, with its own answers and BitmapSet, on copies until every answer is in.
                final Units.Built b = res[0].built;
                final Units.Pending pend = b.pending;
                b.pending = null;
                final emon.Enctx.Arenas[] done = {null};
                final EmonError[] afailed = {null};
                Planrun.Unit unitF = (answers, rq, bms, payload) -> {
                    try {
                        emon.Enctx.Arenas t = b.arenas.clone();
                        Units.Pending p = new Units.Pending(Units.cloneCells(pend.cand), Units.cloneBuf(pend.cbytes), pend.rfrom);
                        Units.assetStageFigures(t, p, opts, idx, (int) size, answers, rq, bms);
                        if (Port.cellsLen(rq) > 0) {
                            return Planrun.UNIT_REQUESTS;
                        }
                        done[0] = t;
                    } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
                        afailed[0] = EmonError.panic(p);
                    }
                    return Planrun.UNIT_DONE;
                };
                try {
                    Planrun.drive(m, unitF, ports, bytesF, Planrun.NO_MISSES, rec);
                } catch (RuntimeException e) {
                    throw loopError(e);
                }
                if (afailed[0] != null) {
                    throw afailed[0];
                }
                if (done[0] == null) {
                    throw EmonError.internal("the asset unit finished with no Model");
                }
                advance(m, docU[0], opts);
                b.arenas = done[0];
                EmonError sealed = Units.sealArenas(b.arenas, opts);
                if (sealed != null) {
                    throw sealed;
                }
            } else {
                break phases;
            }
        }
        Planrun.Totals t = m.totals();
        stats.attempts = t.attempts;
        stats.fetchRounds = t.fetchRounds;
        stats.bytesRequests = t.bytesRequests;
        stats.requests = t.requests;
        stats.pageRounds.addAll(t.pageRounds);
        stats.pageRequests.addAll(t.pageRequests);
        stats.fulfilUs = timed.us;
        if (replayer != null) {
            try {
                replayer.finish();
            } catch (Planrun.PlanError e) {
                throw EmonError.plan(e);
            }
        }
        if (cache != null) {
            long[] s = cache.stats();
            stats.sourceReads = s[0];
            stats.bytesFetched = s[1];
        }
        if (res[0].err != null) {
            throw res[0].err;
        }
        if (res[0].built == null && res[0].intake == null) {
            throw EmonError.internal("the unit finished with nothing");
        }
        Run r = new Run();
        r.outcome = res[0];
        r.stats = stats;
        return r;
    }

    // the unit that just finished is folded into the totals, and the schedule moves on
    private static void advance(Planrun.Machine m, Units.UnitDoc du, Options opts) {
        int kind = 0, np = 0;
        if (du != null) {
            kind = du.doc.kind;
            np = du.info.pages;
        }
        m.endUnit();
        m.nextPhase(kind, opts.assets != Options.Assets.NONE, false, np);
    }

    // a source that failed is the answer, whatever the loop made of the miss it caused
    private static void sourceFailed(ChunkCache cache, int idx) throws EmonError {
        if (cache == null) {
            return;
        }
        Object[] f = cache.fail();
        if (f != null) {
            if ((Boolean) f[0]) {
                throw EmonError.of(EmonError.Kind.SOURCE_CHANGED, idx);
            }
            throw EmonError.io(idx, (String) f[1]);
        }
    }

    /** Which of the Engine's names a Kind lookup uses: kept so a driver can map a name to a Model.Kind. */
    public static Map<String, Model.Kind> kinds() {
        Map<String, Model.Kind> m = new HashMap<>();
        for (int k = 1; k <= emon.Model.nkInitial(); k++) {
            Model.Kind kd = new Model.Kind(k);
            m.put(kd.name(), kd);
        }
        return m;
    }
}
