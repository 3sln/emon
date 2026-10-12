// THE UNITS, Java host (DEVLOG P9.1, P10.6-P10.8; SPEC 7.3, 13.1; D058, D195, D351, D381-D383, D404-D408). The twin of the unit half of `host/go/engine.go`
// (`attempt`, `attemptRaster`, `unitPdf`, `unitRaster`, `assetUnit`, `finishPdf`, `finishRaster`, `assetStageFigures`) and of `host/rust/src/engine.rs`: read
// those for the reasoning.
//
// WHAT THE HOST DOES HERE IS CALL KIN, in the order kin's schedule (`en-next`, in `Engine`) asks. The document pass is the one place the Engine calls the
// text-layer stages (when their signatures grow this is what changes: kin's S3 is not done, DEVLOG P10.6). Everything after it is one call each: a page unit
// (`pageunit.pu-run-pdf`, `rasterin.rs-page`), the document unit (`engine.en-finish-pdf`, `en-finish-raster`: fusion, the evidence, furniture, tables,
// headings, paragraphs and columns, the Model, its check), the asset unit and the figures unit. The Java stages the first host wrote by hand (`Stages`) are
// gone.

package emon.api;

import emon.Asset;
import emon.Enctx;
import emon.Engine;
import emon.Figclass;
import emon.Gfuse;
import emon.Inline;
import emon.Pageunit;
import emon.Pdfannot;
import emon.Pdfcontent;
import emon.Pdfdoc;
import emon.Pdfmeta;
import emon.Pdfstruct;
import emon.Pdfx;
import emon.Planloop;
import emon.Port;
import emon.Rasterin;
import emon.Tlhealth;

final class Units {
    private Units() {
    }

    /** What a document unit built: the arenas, the evidence dump (when asked for) and what it says of the input. */
    static final class Built {
        Enctx.Arenas arenas;
        String evidence;
        Intake intake;
        /** At `assets: figures` the document unit stops before the asset stage (D351): what the asset unit still needs. null otherwise. */
        Pending pending;
    }

    /** The asset unit's input at the level `figures` (SPEC 10.11, D351): the candidate rows and their bytes the document pass made, and rfrom, 0 for a PDF or the `afm` of a raster input's format. */
    static final class Pending {
        final Port.Cells cand;
        final Port.Buf cbytes;
        final int rfrom;

        Pending(Port.Cells cand, Port.Buf cbytes, int rfrom) {
            this.cand = cand;
            this.cbytes = cbytes;
            this.rfrom = rfrom;
        }
    }

    static final class Outcome {
        Built built;
        Intake intake;
        EmonError err;

        static Outcome error(EmonError e) {
            Outcome o = new Outcome();
            o.err = e;
            return o;
        }
    }

    /** One attempt's end for either kind of input: it missed, it asked for something, or it finished. */
    static final class Step {
        boolean miss, requests;
        Outcome out;
        // the document pass is done and the page units are next (P7.U)
        PdfDoc pdf;
        RasterDoc raster;

        static Step missed() {
            Step s = new Step();
            s.miss = true;
            return s;
        }

        static Step asked() {
            Step s = new Step();
            s.requests = true;
            return s;
        }

        static Step done(Outcome o) {
            Step s = new Step();
            s.out = o;
            return s;
        }
    }

    /** What the PDF document pass leaves for its page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`). */
    static final class PdfDoc {
        final Enctx.EvArenas ev;
        final Enctx.Doc doc;
        final Intake info;

        PdfDoc(Enctx.EvArenas ev, Enctx.Doc doc, Intake info) {
            this.ev = ev;
            this.doc = doc;
            this.info = info;
        }
    }

    /** The same for a raster input. */
    static final class RasterDoc {
        final Enctx.Arenas m;
        final Enctx.EvArenas ev;
        final Enctx.Doc doc;
        final Intake info;

        RasterDoc(Enctx.Arenas m, Enctx.EvArenas ev, Enctx.Doc doc, Intake info) {
            this.m = m;
            this.ev = ev;
            this.doc = doc;
            this.info = info;
        }
    }

    // ------------------------------------------------------------------------------------------------ small helpers

    static Port.Cells[] cellsN(int n) {
        Port.Cells[] v = new Port.Cells[n];
        for (int i = 0; i < n; i++) {
            v[i] = Port.newCells();
        }
        return v;
    }

    static int[] cellsVec(Port.Cells c) {
        return Port.cellsVec(c);
    }

    static int boolInt(boolean b) {
        return b ? 1 : 0;
    }

    static Port.Cells cloneCells(Port.Cells c) {
        Port.Cells r = Port.newCells();
        Port.cellsCopyInto(r, c);
        return r;
    }

    static Port.Buf cloneBuf(Port.Buf b) {
        Port.Buf r = Port.newBuf();
        Port.bufCopyInto(r, b);
        return r;
    }

    /** The locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none. */
    static Port.Cells localeCells(Options opts) {
        Port.Cells c = Port.newCells();
        opts.locale.codePoints().forEach(cp -> Port.cellsPush(c, cp));
        return c;
    }

    /** A damaged input, named by kin's `ds` (the status itself when the table names it, `unreadable` otherwise). */
    static EmonError damaged(int input, int code) {
        StringBuilder name = new StringBuilder();
        Planloop.dsEmitName(name, Planloop.dsKnown(code));
        return EmonError.damaged(input, name.toString());
    }

    /** What a document status is as the Engine reports it: which error it is is kin's (`ds-class`). */
    static EmonError statusError(int input, int status) {
        switch (Planloop.dsClass(status)) {
            case 1: return EmonError.of(EmonError.Kind.UNSUPPORTED_FORMAT, input);
            case 2: return EmonError.of(EmonError.Kind.PASSWORD_REQUIRED, input);
            case 3: return EmonError.of(EmonError.Kind.PASSWORD_INCORRECT, input);
            case 4: return EmonError.of(EmonError.Kind.ENCRYPTION_UNSUPPORTED, input);
            default: return damaged(input, status);
        }
    }

    /** The Options the document unit reads (`emon.enctx`'s `Cfg`). */
    static Enctx.Cfg cfgOf(Options opts) {
        Enctx.Cfg c = new Enctx.Cfg();
        c.ocr = opts.ocr.code();
        c.scope = opts.ocrScope.code();
        c.furn = opts.furniture.mode();
        c.level = opts.assets.level();
        c.dpi = opts.assetDpi;
        c.pixels = opts.assetPixels;
        c.abyte = opts.assetBytes;
        c.tbyte = opts.assetsBytes;
        c.sigs = boolInt(opts.assetSignatures);
        c.keep = boolInt(opts.keepEvidence);
        if (!opts.locale.isEmpty()) {
            c.haveloc = 1;
            c.lcl = localeCells(opts);
        }
        return c;
    }

    /** What kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3 `model-check`, 4 `model-check-assets`). */
    static Outcome finished(int r, Enctx.Doc doc, Enctx.Arenas m, Intake info, Options opts, int rfrom) {
        Outcome o = new Outcome();
        switch (r) {
            case 0:
            case 1:
                Built b = new Built();
                b.arenas = m;
                b.intake = info;
                if (opts.keepEvidence) {
                    b.evidence = doc.evidence.toString();
                }
                if (r == 1) {
                    b.pending = new Pending(doc.cand, doc.cbytes, rfrom);
                }
                o.built = b;
                return o;
            case 2:
                return Outcome.error(EmonError.internal("pl-check " + doc.fcode));
            case 3:
                return Outcome.error(EmonError.internal("model-check " + doc.fcode + " at node " + doc.fnode));
            default:
                return Outcome.error(EmonError.internal("model-check-assets " + doc.fcode + " at " + doc.fnode));
        }
    }

    /** `model-check`, and `model-check-assets` when `assets` is on, over a finished Model (kin's `en-seal`); null when it holds. */
    static EmonError sealArenas(Enctx.Arenas m, Options opts) {
        Port.Cells out = Port.newCells();
        switch (Engine.enSeal(m, Port.newCells(), out, opts.assets.level())) {
            case 0:
                return null;
            case 1:
                return EmonError.internal("model-check " + Port.cellsAt(out, 0) + " at node " + Port.cellsAt(out, 1));
            default:
                return EmonError.internal("model-check-assets " + Port.cellsAt(out, 0) + " at " + Port.cellsAt(out, 1));
        }
    }

    // ------------------------------------------------------------------------------------------------ dispatch

    /**
     * The unit of one input: a PNG, JPEG or TIFF (by kin's sniff) is read by the raster unit, anything else by the PDF one (`attempt`, which refuses what is
     * not a PDF by name). A panic in an attempt that missed is a miss, never reported (SPEC 13.1).
     */
    static Step dispatch(Port.Src view, Port.Src pw, Port.Cells answers, Port.Cells rq, int idx, Options opts, boolean intakeOnly, long n) {
        try {
            int format = Rasterin.rsSniff(view);
            if (Port.srcMissed(view)) {
                return Step.missed();
            }
            if (format != Rasterin.rsNone()) {
                return attemptRaster(view, answers, rq, idx, format, opts, intakeOnly, n);
            }
            return attempt(view, pw, idx, opts, intakeOnly, n);
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
            if (Port.srcMissed(view)) {
                return Step.missed();
            }
            return Step.done(Outcome.error(EmonError.panic(p)));
        }
    }

    // ------------------------------------------------------------------------------------------------ raster intake

    /**
     * ONE ATTEMPT OF THE RASTER UNIT, from scratch on fresh arenas, as {@link #attempt} is for a PDF. An image's pages are made by kin's `RsDoc` (decode, then
     * OCR: P6.5), and its OCR lines are the text layer's lines to everything after (D213).
     */
    static Step attemptRaster(Port.Src view, Port.Cells answers, Port.Cells rq, int idx, int format, Options opts, boolean intakeOnly, long n) {
        Enctx.Arenas m = new Enctx.Arenas();
        emon.Model.modelInit(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout);
        Port.Cells mask = Port.newCells();
        int status = Rasterin.rsDoc(view, idx, format, answers, rq, m.nodes, m.attrs, m.text, m.pages, mask);
        if (Port.srcMissed(view)) {
            return Step.missed();
        }
        if (status == 1) {
            return Step.asked();
        }
        if (status == Planloop.dsUndecodable() || status == Planloop.dsUnsupportedImage()) {
            return Step.done(Outcome.error(damaged(idx, status)));
        }
        if (status != 0) {
            return Step.done(Outcome.error(EmonError.internal("the raster unit answered status " + status)));
        }
        String name = "tiff";
        if (format == Rasterin.rsPng()) {
            name = "png";
        } else if (format == Rasterin.rsJpeg()) {
            name = "jpeg";
        }
        Intake info = new Intake(name, 0, 0, Math.max(emon.Model.pageCount(m.pages), 0), false, n);
        if (intakeOnly) {
            Outcome o = new Outcome();
            o.intake = info;
            return Step.done(o);
        }
        Enctx.Doc doc = new Enctx.Doc();
        doc.mask = mask;
        doc.loc = localeCells(opts);
        Step s = new Step();
        s.raster = new RasterDoc(m, new Enctx.EvArenas(), doc, info);
        return s;
    }

    /** One frame's page unit (P7.U): its own requests and answers; it writes nothing until its answers are in. */
    static int unitRaster(RasterDoc d, int f, int idx, long n, Options opts, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms) {
        Port.Cells scr = Port.newCells();
        Enctx.EvArenas ev = d.ev;
        return Rasterin.rsPage(f, idx, (int) n, opts.ocr.code(), answers, rq, d.doc.loc, bms, d.doc.mask,
            ev.epages, ev.views, d.doc.et, ev.feats, ev.images, ev.oruns, ev.oblocks, ev.olines, ev.owords, ev.osyms, ev.oalts, d.doc.wd, d.doc.ln, scr);
    }

    /** The raster document unit: stage 3 over the frames' OCR lines, in kin (`emon.engine`). */
    static Outcome finishRaster(RasterDoc d, Options opts) {
        int rfrom = emon.Model.afmTiff();
        if (d.info.format.equals("png")) {
            rfrom = emon.Model.afmPng();
        } else if (d.info.format.equals("jpeg")) {
            rfrom = emon.Model.afmJpeg();
        }
        int r = Engine.enFinishRaster(cfgOf(opts), d.doc, d.ev, d.m, new Enctx.Scratch());
        return finished(r, d.doc, d.m, d.info, opts, rfrom);
    }

    // ------------------------------------------------------------------------------------------------ the PDF document pass

    /**
     * ONE ATTEMPT OF THE UNIT for one PDF: everything kin does with it, from scratch, on a fresh view and fresh arenas. A miss at any checkpoint ends it at
     * once: what a missed attempt computed is never looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1). THIS IS THE
     * ONE PLACE the Engine calls the text-layer stages.
     */
    static Step attempt(Port.Src view, Port.Src pw, int idx, Options opts, boolean intakeOnly, long n) {
        Port.Cells[] a = cellsN(6);
        Port.Cells ar = a[0], dc = a[1], ca = a[2], cb = a[3], st = a[4], pg = a[5];
        int ostatus = Pdfdoc.pdOpenPw(view, pw, ar, dc, ca, cb, st);
        if (Port.srcMissed(view) || ostatus == Pdfdoc.pdNeedBytes()) {
            return Step.missed();
        }
        if (ostatus != 0) {
            return Step.done(Outcome.error(statusError(idx, ostatus)));
        }
        java.util.function.IntFunction<Intake> intake = np -> new Intake("pdf", Pdfdoc.pdVersionMajor(dc), Pdfdoc.pdVersionMinor(dc),
            Math.max(np, 0), Pdfdoc.pdEncrypted(dc) == 1, n);
        if (intakeOnly) {
            int np = Pdfdoc.pdPages(view, ar, dc, ca, cb, st, pg);
            if (Port.srcMissed(view)) {
                return Step.missed();
            }
            if (np < 0) {
                return Step.done(Outcome.error(damaged(idx, Planloop.dsNoPages())));
            }
            Outcome o = new Outcome();
            o.intake = intake.apply(np);
            return Step.done(o);
        }
        Port.Cells[] x = cellsN(18);
        Port.Cells cx = x[0], ox = x[1], cs = x[2], out = x[3], f0 = x[4], f1 = x[5], f2 = x[6], f3 = x[7], gl = x[8], et = x[9], mk = x[10], fnt = x[11],
            wd = x[12], ln = x[13], pa = x[14], im = x[15], pts = x[16], gx = x[17];
        int status = Pdfcontent.pcDocument(view, ar, dc, ca, cb, st, cx, ox, cs, out, f0, f1, f2, f3, gl, et, mk, fnt, pg, wd, ln, pa, im, pts, gx);
        if (Port.srcMissed(view) || status == Pdfdoc.pdNeedBytes()) {
            return Step.missed();
        }
        if (status != 0) {
            return Step.done(Outcome.error(damaged(idx, Planloop.dsContentFailed())));
        }
        int np = Pdfdoc.pdPgCount(pg);
        Intake info = intake.apply(np);
        // THE PLAN OF THE PAGE UNITS (P6.5, D212, P7.U): a page with no text layer and one JPEG image that covers it is a scanned page (a `decode` of the
        // stream, then an `ocr` of its bitmap, in its own unit); its stream is located here, the one place that reads the input. A page with a text layer is
        // left as the text layer made it (fusion, P7.3, is what weighs a second reading). The text layer's own triage and health (P7.0, D195, D208): epages,
        // the stage-1 features, the flow COPIED into fw/fl without the words that failed a P3.6 check (set aside in fa, written to the ledger after FuWrite),
        // and the font health in hl. Stage 3 reads fw/fl from here on, never wd/ln; the scanned pages' lines join them after the page units.
        Port.Cells[] l7 = cellsN(7);
        Port.Cells tlEpages = l7[0], tlFeats = l7[1], fw = l7[2], fl = l7[3], fa = l7[4], hl = l7[5], hsc = l7[6];
        Tlhealth.thRun(pg, gl, et, mk, fnt, wd, ln, im, tlEpages, tlFeats, fw, fl, fa, hl, hsc);
        int nl0 = Port.cellsLen(ln) / 16;
        Port.Cells[] sx = cellsN(4);
        Port.Cells plan0 = sx[0], sp = sx[1], rs1 = sx[2], rs2 = sx[3];
        Pageunit.puPlan(view, ar, dc, ca, cb, st, pg, im, wd, opts.ocr.code(), opts.ocrScope.code(), fw, fl, et, hl, sp, plan0, rs1, rs2);
        if (Port.srcMissed(view)) {
            return Step.missed();
        }
        // The fused graphics (P5.5a, D270): the vector source's rules and fills of every page, which the table stage reads instead of paths (until P7.3 moves
        // it into the page units, SPEC 9.9).
        Port.Cells[] gx4 = cellsN(4);
        Port.Cells grl = gx4[0], gfl = gx4[1], gcand = gx4[2], gmeas = gx4[3];
        Gfuse.gfDoc(pa, pts, mk, im, np, Gfuse.gfxMode(), grl, gfl, gcand, gmeas);
        // P3.13 (SPEC 10.11): the asset candidates are made by the ASSET UNIT (`assetUnit`, P6.1a), which runs after this pass and reads the input again,
        // because a codec image's pixels are the host's and ask the plan/fulfil loop; the placements it works from are kept.
        Port.Cells cand = Port.newCells();
        Port.Buf cbytes = Port.newBuf();
        Port.Cells aim = Port.newCells();
        if (opts.assets != Options.Assets.NONE) {
            aim = im;
        }
        // P8.1 (SPEC 10.6): what each image placement's dictionary says, which the figures' kinds are read from (one more read of the input)
        Port.Cells[] fz3 = cellsN(3);
        Port.Cells fi = fz3[0];
        Figclass.fcImages(view, ar, dc, ca, cb, st, im, fz3[1], fz3[2], fi);
        if (Port.srcMissed(view)) {
            return Step.missed();
        }
        Port.Cells[] snap = null;
        if (opts.keepEvidence) {
            snap = new Port.Cells[] {cloneCells(gl), cloneCells(mk), cloneCells(fnt)};
        }
        // With fusion on (every policy but `never`), the text layer's epages and feats are the evidence's own: the OCR of a born-digital page adds its feature
        // rows to the same arena, and alignment reads both (P7.3). Without it they are dropped, so the dump is the one it always was.
        boolean fusion = opts.ocr.code() != 0;
        Enctx.EvArenas ev0 = new Enctx.EvArenas();
        if (fusion) {
            ev0.epages = tlEpages;
            ev0.feats = tlFeats;
        }
        // The catalogue's extras and the tagged tree (P3.9, P3.7): the Info /Title and /PageLabels, and the tree's trust (D076), which the furniture stage
        // weighs the tree's /Artifact marks by.
        Port.Cells[] q = cellsN(24);
        Port.Cells qidx = q[0], qtxt = q[1], qw = q[2], qseen = q[3];
        Port.Cells md = q[4], xm = q[5], ol = q[6], lb = q[7], nd = q[8], nt = q[9];
        Port.Cells ss = q[10], se = q[11], sk = q[12], sc = q[13], so = q[14], sa = q[15], wk = q[16];
        Port.Cells ha = q[17], hb = q[18], hh = q[19];
        Pdfx.pxIndexBuild(view, ar, dc, ca, cb, st, pg, qidx);
        Pdfmeta.pmRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, md, xm, ol, lb, nd, nt);
        Pdfstruct.tgRead(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, ss, se, sk, sc, so, wk, md);
        Pdfstruct.tgJoin(ss, se, sk, sc, so, sa, gl, mk, fnt, pg, ha, hb, hh);
        // inline styles and links (P4.7): what each font says of weight and slant, and the link annotations (P3.8)
        Port.Cells[] v = cellsN(8);
        Port.Cells ift = v[0], vfs = v[1], vfm = v[2], van = v[3], vaq = v[4], vfd = v[5], vsg = v[6];
        Inline.ilFonts(view, ar, dc, ca, cb, st, fnt, et, ift);
        Pdfannot.paRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, vfs, qseen, vfm, van, vaq, vfd, vsg);
        if (Port.srcMissed(view)) {
            return Step.missed();
        }
        Port.Cells mask = Port.newCells();
        for (int i = 0; i < Math.max(np, 0); i++) {
            Port.cellsPush(mask, 0);
        }
        Port.Cells ptsKept = fusion ? pts : Port.newCells();
        Enctx.Doc doc = new Enctx.Doc();
        doc.hl = hl;
        doc.et = et;
        doc.gl = gl;
        doc.mk = mk;
        doc.fnt = fnt;
        doc.wd = wd;
        doc.ln = ln;
        doc.fw = fw;
        doc.fl = fl;
        doc.fa = fa;
        doc.nl0 = nl0;
        doc.pa = pa;
        doc.im = im;
        doc.grl = grl;
        doc.gfl = gfl;
        doc.ift = ift;
        doc.an = van;
        doc.pts = ptsKept;
        doc.pg = pg;
        doc.txt = qtxt;
        doc.md = md;
        doc.lb = lb;
        doc.ol = ol;
        doc.ss = ss;
        doc.se = se;
        doc.sc = sc;
        doc.plan = plan0;
        doc.mask = mask;
        doc.loc = localeCells(opts);
        if (snap != null) {
            doc.snapg = snap[0];
            doc.snapk = snap[1];
            doc.snapf = snap[2];
            doc.hassnap = 1;
        }
        doc.cand = cand;
        doc.cbytes = cbytes;
        doc.aim = aim;
        doc.fi = fi;
        Step s = new Step();
        s.pdf = new PdfDoc(ev0, doc, info);
        return s;
    }

    /**
     * THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil loop. It re-opens the
     * document (the document pass's arenas are gone) and runs `asCollect` over the placements the document pass kept: a codec image whose pixels are the
     * host's is asked for, with the bytes kin made itself in `payload`; the unit is run again with the answers and makes the candidates. Answers 0 (the
     * candidates are in d.doc.cand, d.doc.cbytes), 1 (it asked) or 7 (the source missed).
     */
    static int assetUnit(Port.Src view, Port.Src pw, PdfDoc d, Options opts, int idx, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms, Port.Buf payload) {
        Port.Cells[] a = cellsN(5);
        Port.Cells ar = a[0], dc = a[1], ca = a[2], cb = a[3], st = a[4];
        int ostatus = Pdfdoc.pdOpenPw(view, pw, ar, dc, ca, cb, st);
        if (Port.srcMissed(view) || ostatus == Pdfdoc.pdNeedBytes()) {
            return 7;
        }
        if (ostatus != 0) {
            return 100 + ostatus;
        }
        Port.Cells[] w = cellsN(10);
        Port.Buf z = Port.newBuf();
        Port.Cells glob = Port.newCells(), words = Port.newCells(), hs = Port.newCells();
        d.doc.cbytes = Port.newBuf();
        int rc = Asset.asCollect(view, ar, dc, ca, cb, st, d.doc.aim, d.doc.pa, d.doc.gl, d.doc.pg, opts.assetPixels, opts.assetsBytes, opts.assets.level(),
            Port.newCells(), d.doc.cand, d.doc.cbytes, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8], z, idx, answers, rq, payload, bms, glob, words, hs);
        if (Port.srcMissed(view) || rc == 12) {
            return 7;
        }
        return rc;
    }

    /**
     * One page's unit (P7.U, D195): its own requests, answers and BitmapSet; a scanned page is read, any other is left as the text layer made it. It writes
     * nothing until its answers are in, and never reads the input.
     */
    static int unitPdf(PdfDoc d, int i, int idx, Options opts, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms) {
        Port.Cells scr = Port.newCells();
        Enctx.Doc x = d.doc;
        Enctx.EvArenas ev = d.ev;
        return Pageunit.puRunPdf(i, idx, opts.ocr.code(), x.plan, answers, rq, x.loc, bms, x.mask, scr,
            ev.epages, ev.views, x.et, ev.feats, ev.images, ev.oruns, ev.oblocks, ev.olines, ev.owords, ev.osyms, ev.oalts, x.wd, x.ln,
            2 * boolInt(opts.noRetarget), x.fw, x.fl, x.hl, ev.refs, x.cg, ev.tlines, x.xs0, x.xs1, x.xs2, x.xs3, x.xs4, x.xs5,
            ev.inks, ev.comps, ev.rules, ev.prims, ev.pts,
            x.ys0, x.ys1, x.ys2, x.ys3, x.ys4, x.ys5, x.ys6, x.ys7, x.ys8, x.ys9, x.ys10, x.ys11, x.ys12, x.ys13, x.ys14, x.ys15, x.ys16, x.ys17, x.ys18);
    }

    /**
     * The document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture, tables, headings, paragraphs
     * and columns, the Model. All of it is kin's (`emon.engine`).
     */
    static Outcome finishPdf(PdfDoc d, Options opts) {
        Enctx.Arenas m = new Enctx.Arenas();
        int r = Engine.enFinishPdf(cfgOf(opts), d.doc, d.ev, m, new Enctx.Scratch());
        return finished(r, d.doc, m, d.info, opts, 0);
    }

    /**
     * P8.5's asset stage at the level `figures` (SPEC 10.11, D351): one attempt of the asset unit, on copies the caller made. It writes the Model's settings
     * and every figure's and seal's asset or reason, and any `crop` or `decode` request it has no answer to into rq (then its results are meaningless: the
     * loop answers and runs it again).
     */
    static void assetStageFigures(Enctx.Arenas m, Pending p, Options opts, int input, int nbytes, Port.Cells ans, Port.Cells rq, Port.BitmapSet bms) {
        int sigs = boolInt(opts.assetSignatures);
        Asset.asSettings(m.nodes, m.attrs, m.text, opts.assets.level(), opts.assetDpi, opts.assetPixels, opts.assetBytes, opts.assetsBytes, sigs);
        Port.Cells[] s = cellsN(5);
        Port.Buf z = Port.newBuf();
        Asset.asRunFigures(m.nodes, m.attrs, m.regions, m.pages, p.cand, p.cbytes, m.assets, m.uses, m.abytes,
            opts.assetBytes, opts.assetsBytes, sigs, opts.assetDpi, opts.assetPixels, input, nbytes, p.rfrom, ans, rq, bms,
            s[0], z, s[1], s[2], s[3], s[4]);
    }
}
