// THE UNITS, C# host (DEVLOG P9.2, P10.6-P10.8; SPEC 7.3, 13.1; D058, D195, D351, D381-D383, D404-D408). The twin of `host/java/emon/api/Units.java`, of the unit
// half of `host/go/engine.go` (`attempt`, `attemptRaster`, `unitPdf`, `unitRaster`, `assetUnit`, `finishPdf`, `finishRaster`, `assetStageFigures`) and of
// `host/rust/src/engine.rs`: read those for the reasoning.
//
// WHAT THE HOST DOES HERE IS CALL KIN, in the order kin's schedule (`en-next`, in `Engine`) asks. The document pass is the one place the Engine calls the
// text-layer stages (when their signatures grow this is what changes: kin's S3 is not done, DEVLOG P10.6). Everything after it is one call each: a page unit
// (`pageunit.pu-run-pdf`, `rasterin.rs-page`), the document unit (`engine.en-finish-pdf`, `en-finish-raster`: fusion, the evidence, furniture, tables,
// headings, paragraphs and columns, the Model, its check), the asset unit and the figures unit. The C# stages the first host wrote by hand (`Stages`) are
// gone.
//
// The generated classes (`emon.Engine`, `emon.Asset`, `emon.Model`, ...) are written fully qualified here: this namespace has an `Engine`, an `Asset` and a
// `Model` of its own.

namespace emon.Api;

using System.Collections.Generic;

internal static class Units {
    /// <summary>What a document unit built: the arenas, the evidence dump (when asked for) and what it says of the input.</summary>
    internal sealed class Built {
        internal emon.Enctx.Arenas Arenas;
        internal string Evidence;
        internal Intake Intake;
        /// <summary>At `assets: figures` the document unit stops before the asset stage (D351): what the asset unit still needs. null otherwise.</summary>
        internal Pending Pending;
    }

    /// <summary>The asset unit's input at the level `figures` (SPEC 10.11, D351): the candidate rows and their bytes the document pass made, and Rfrom, 0 for a PDF or the `afm` of a raster input's format.</summary>
    internal sealed class Pending {
        internal readonly emon.Port.Cells Cand;
        internal readonly emon.Port.Buf Cbytes;
        internal readonly int Rfrom;

        internal Pending(emon.Port.Cells cand, emon.Port.Buf cbytes, int rfrom) {
            this.Cand = cand;
            this.Cbytes = cbytes;
            this.Rfrom = rfrom;
        }
    }

    internal sealed class Outcome {
        internal Built Built;
        internal Intake Intake;
        internal EmonError Err;

        internal static Outcome Error(EmonError e) {
            var o = new Outcome();
            o.Err = e;
            return o;
        }
    }

    /// <summary>One attempt's end for either kind of input: it missed, it asked for something, or it finished.</summary>
    internal sealed class Step {
        internal bool Miss, Requests;
        internal Outcome Out;
        // the document pass is done and the page units are next (P7.U)
        internal PdfDoc Pdf;
        internal RasterDoc Raster;

        internal static Step Missed() {
            var s = new Step();
            s.Miss = true;
            return s;
        }

        internal static Step Asked() {
            var s = new Step();
            s.Requests = true;
            return s;
        }

        internal static Step Done(Outcome o) {
            var s = new Step();
            s.Out = o;
            return s;
        }
    }

    /// <summary>What the PDF document pass leaves for its page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`).</summary>
    internal sealed class PdfDoc {
        internal readonly emon.Enctx.EvArenas Ev;
        internal readonly emon.Enctx.Doc Doc;
        internal readonly Intake Info;

        internal PdfDoc(emon.Enctx.EvArenas ev, emon.Enctx.Doc doc, Intake info) {
            this.Ev = ev;
            this.Doc = doc;
            this.Info = info;
        }
    }

    /// <summary>The same for a raster input.</summary>
    internal sealed class RasterDoc {
        internal readonly emon.Enctx.Arenas M;
        internal readonly emon.Enctx.EvArenas Ev;
        internal readonly emon.Enctx.Doc Doc;
        internal readonly Intake Info;

        internal RasterDoc(emon.Enctx.Arenas m, emon.Enctx.EvArenas ev, emon.Enctx.Doc doc, Intake info) {
            this.M = m;
            this.Ev = ev;
            this.Doc = doc;
            this.Info = info;
        }
    }

    // ------------------------------------------------------------------------------------------------ small helpers

    internal static emon.Port.Cells[] CellsN(int n) {
        var v = new emon.Port.Cells[n];
        for (int i = 0; i < n; i++) {
            v[i] = emon.Port.NewCells();
        }
        return v;
    }

    internal static int BoolInt(bool b) {
        return b ? 1 : 0;
    }

    internal static emon.Port.Cells CloneCells(emon.Port.Cells c) {
        emon.Port.Cells r = emon.Port.NewCells();
        emon.Port.CellsCopyInto(r, c);
        return r;
    }

    internal static emon.Port.Buf CloneBuf(emon.Port.Buf b) {
        emon.Port.Buf r = emon.Port.NewBuf();
        emon.Port.BufCopyInto(r, b);
        return r;
    }

    /// <summary>The locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none.</summary>
    internal static emon.Port.Cells LocaleCells(Options opts) {
        emon.Port.Cells c = emon.Port.NewCells();
        string s = opts.Locale;
        for (int i = 0; i < s.Length;) {
            int cp = char.ConvertToUtf32(s, i);
            i += char.IsHighSurrogate(s[i]) && i + 1 < s.Length ? 2 : 1;
            emon.Port.CellsPush(c, cp);
        }
        return c;
    }

    /// <summary>A damaged input, named by kin's `ds` (the status itself when the table names it, `unreadable` otherwise).</summary>
    internal static EmonError Damaged(int input, int code) {
        var name = new System.Text.StringBuilder();
        emon.Planloop.DsEmitName(name, emon.Planloop.DsKnown(code));
        return EmonError.Damaged(input, name.ToString());
    }

    /// <summary>What a document status is as the Engine reports it: which error it is is kin's (`ds-class`).</summary>
    internal static EmonError StatusError(int input, int status) {
        switch (emon.Planloop.DsClass(status)) {
            case 1: return EmonError.Of(EmonError.ErrorKind.UnsupportedFormat, input);
            case 2: return EmonError.Of(EmonError.ErrorKind.PasswordRequired, input);
            case 3: return EmonError.Of(EmonError.ErrorKind.PasswordIncorrect, input);
            case 4: return EmonError.Of(EmonError.ErrorKind.EncryptionUnsupported, input);
            default: return Damaged(input, status);
        }
    }

    /// <summary>The Options the document unit reads (`emon.enctx`'s `Cfg`).</summary>
    internal static emon.Enctx.Cfg CfgOf(Options opts) {
        var c = new emon.Enctx.Cfg();
        c.Ocr = opts.Ocr.Code();
        c.Scope = opts.OcrScope.Code();
        c.Furn = opts.Furniture.Mode();
        c.Level = opts.Assets.Level();
        c.Dpi = opts.AssetDpi;
        c.Pixels = opts.AssetPixels;
        c.Abyte = opts.AssetBytes;
        c.Tbyte = opts.AssetsBytes;
        c.Sigs = BoolInt(opts.AssetSignatures);
        c.Keep = BoolInt(opts.KeepEvidence);
        if (opts.Locale.Length > 0) {
            c.Haveloc = 1;
            c.Lcl = LocaleCells(opts);
        }
        return c;
    }

    /// <summary>What kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3 `model-check`, 4 `model-check-assets`).</summary>
    internal static Outcome Finished(int r, emon.Enctx.Doc doc, emon.Enctx.Arenas m, Intake info, Options opts, int rfrom) {
        var o = new Outcome();
        switch (r) {
            case 0:
            case 1:
                var b = new Built();
                b.Arenas = m;
                b.Intake = info;
                if (opts.KeepEvidence) {
                    b.Evidence = doc.Evidence.ToString();
                }
                if (r == 1) {
                    b.Pending = new Pending(doc.Cand, doc.Cbytes, rfrom);
                }
                o.Built = b;
                return o;
            case 2:
                return Outcome.Error(EmonError.Internal("pl-check " + doc.Fcode));
            case 3:
                return Outcome.Error(EmonError.Internal("model-check " + doc.Fcode + " at node " + doc.Fnode));
            default:
                return Outcome.Error(EmonError.Internal("model-check-assets " + doc.Fcode + " at " + doc.Fnode));
        }
    }

    /// <summary>`model-check`, and `model-check-assets` when `assets` is on, over a finished Model (kin's `en-seal`); null when it holds.</summary>
    internal static EmonError SealArenas(emon.Enctx.Arenas m, Options opts) {
        emon.Port.Cells o = emon.Port.NewCells();
        switch (emon.Engine.EnSeal(m, emon.Port.NewCells(), o, opts.Assets.Level())) {
            case 0:
                return null;
            case 1:
                return EmonError.Internal("model-check " + emon.Port.CellsAt(o, 0) + " at node " + emon.Port.CellsAt(o, 1));
            default:
                return EmonError.Internal("model-check-assets " + emon.Port.CellsAt(o, 0) + " at " + emon.Port.CellsAt(o, 1));
        }
    }

    // ------------------------------------------------------------------------------------------------ dispatch

    /// <summary>
    /// The unit of one input: a PNG, JPEG or TIFF (by kin's sniff) is read by the raster unit, anything else by the PDF one (`Attempt`, which refuses what is
    /// not a PDF by name). A panic in an attempt that missed is a miss, never reported (SPEC 13.1).
    /// </summary>
    internal static Step Dispatch(emon.Port.Src view, emon.Port.Src pw, emon.Port.Cells answers, emon.Port.Cells rq, int idx, Options opts, bool intakeOnly, long n) {
        try {
            int format = emon.Rasterin.RsSniff(view);
            if (emon.Port.SrcMissed(view)) {
                return Step.Missed();
            }
            if (format != emon.Rasterin.RsNone()) {
                return AttemptRaster(view, answers, rq, idx, format, opts, intakeOnly, n);
            }
            return Attempt(view, pw, idx, opts, intakeOnly, n);
        } catch (System.Exception p) {
            if (emon.Port.SrcMissed(view)) {
                return Step.Missed();
            }
            return Step.Done(Outcome.Error(EmonError.Panic(p)));
        }
    }

    // ------------------------------------------------------------------------------------------------ raster intake

    /// <summary>
    /// ONE ATTEMPT OF THE RASTER UNIT, from scratch on fresh arenas, as <see cref="Attempt"/> is for a PDF. An image's pages are made by kin's `RsDoc` (decode, then
    /// OCR: P6.5), and its OCR lines are the text layer's lines to everything after (D213).
    /// </summary>
    internal static Step AttemptRaster(emon.Port.Src view, emon.Port.Cells answers, emon.Port.Cells rq, int idx, int format, Options opts, bool intakeOnly, long n) {
        var m = new emon.Enctx.Arenas();
        emon.Model.ModelInit(m.Nodes, m.Attrs, m.Regions, m.Text, m.Pages, m.Ledger, m.Layout);
        emon.Port.Cells mask = emon.Port.NewCells();
        int status = emon.Rasterin.RsDoc(view, idx, format, answers, rq, m.Nodes, m.Attrs, m.Text, m.Pages, mask);
        if (emon.Port.SrcMissed(view)) {
            return Step.Missed();
        }
        if (status == 1) {
            return Step.Asked();
        }
        if (status == emon.Planloop.DsUndecodable() || status == emon.Planloop.DsUnsupportedImage()) {
            return Step.Done(Outcome.Error(Damaged(idx, status)));
        }
        if (status != 0) {
            return Step.Done(Outcome.Error(EmonError.Internal("the raster unit answered status " + status)));
        }
        string name = "tiff";
        if (format == emon.Rasterin.RsPng()) {
            name = "png";
        } else if (format == emon.Rasterin.RsJpeg()) {
            name = "jpeg";
        }
        var info = new Intake(name, 0, 0, System.Math.Max(emon.Model.PageCount(m.Pages), 0), false, n);
        if (intakeOnly) {
            var o = new Outcome();
            o.Intake = info;
            return Step.Done(o);
        }
        var doc = new emon.Enctx.Doc();
        doc.Mask = mask;
        doc.Loc = LocaleCells(opts);
        var s = new Step();
        s.Raster = new RasterDoc(m, new emon.Enctx.EvArenas(), doc, info);
        return s;
    }

    /// <summary>One frame's page unit (P7.U): its own requests and answers; it writes nothing until its answers are in.</summary>
    internal static int UnitRaster(RasterDoc d, int f, int idx, long n, Options opts, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms) {
        emon.Port.Cells scr = emon.Port.NewCells();
        emon.Enctx.EvArenas ev = d.Ev;
        return emon.Rasterin.RsPage(f, idx, (int) n, opts.Ocr.Code(), answers, rq, d.Doc.Loc, bms, d.Doc.Mask,
            ev.Epages, ev.Views, d.Doc.Et, ev.Feats, ev.Images, ev.Oruns, ev.Oblocks, ev.Olines, ev.Owords, ev.Osyms, ev.Oalts, d.Doc.Wd, d.Doc.Ln, scr);
    }

    /// <summary>The raster document unit: stage 3 over the frames' OCR lines, in kin (`emon.engine`).</summary>
    internal static Outcome FinishRaster(RasterDoc d, Options opts) {
        int rfrom = emon.Model.AfmTiff();
        if (d.Info.Format == "png") {
            rfrom = emon.Model.AfmPng();
        } else if (d.Info.Format == "jpeg") {
            rfrom = emon.Model.AfmJpeg();
        }
        int r = emon.Engine.EnFinishRaster(CfgOf(opts), d.Doc, d.Ev, d.M, new emon.Enctx.Scratch());
        return Finished(r, d.Doc, d.M, d.Info, opts, rfrom);
    }

    // ------------------------------------------------------------------------------------------------ the PDF document pass

    /// <summary>
    /// ONE ATTEMPT OF THE UNIT for one PDF: everything kin does with it, from scratch, on a fresh view and fresh arenas. A miss at any checkpoint ends it at
    /// once: what a missed attempt computed is never looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1). THIS IS THE
    /// ONE PLACE the Engine calls the text-layer stages.
    /// </summary>
    internal static Step Attempt(emon.Port.Src view, emon.Port.Src pw, int idx, Options opts, bool intakeOnly, long n) {
        emon.Port.Cells[] a = CellsN(6);
        emon.Port.Cells ar = a[0], dc = a[1], ca = a[2], cb = a[3], st = a[4], pg = a[5];
        int ostatus = emon.Pdfdoc.PdOpenPw(view, pw, ar, dc, ca, cb, st);
        if (emon.Port.SrcMissed(view) || ostatus == emon.Pdfdoc.PdNeedBytes()) {
            return Step.Missed();
        }
        if (ostatus != 0) {
            return Step.Done(Outcome.Error(StatusError(idx, ostatus)));
        }
        System.Func<int, Intake> intake = np => new Intake("pdf", emon.Pdfdoc.PdVersionMajor(dc), emon.Pdfdoc.PdVersionMinor(dc),
            System.Math.Max(np, 0), emon.Pdfdoc.PdEncrypted(dc) == 1, n);
        if (intakeOnly) {
            int np = emon.Pdfdoc.PdPages(view, ar, dc, ca, cb, st, pg);
            if (emon.Port.SrcMissed(view)) {
                return Step.Missed();
            }
            if (np < 0) {
                return Step.Done(Outcome.Error(Damaged(idx, emon.Planloop.DsNoPages())));
            }
            var o = new Outcome();
            o.Intake = intake(np);
            return Step.Done(o);
        }
        emon.Port.Cells[] x = CellsN(18);
        emon.Port.Cells cx = x[0], ox = x[1], cs = x[2], outc = x[3], f0 = x[4], f1 = x[5], f2 = x[6], f3 = x[7], gl = x[8], et = x[9], mk = x[10], fnt = x[11],
            wd = x[12], ln = x[13], pa = x[14], im = x[15], pts = x[16], gx = x[17];
        int status = emon.Pdfcontent.PcDocument(view, ar, dc, ca, cb, st, cx, ox, cs, outc, f0, f1, f2, f3, gl, et, mk, fnt, pg, wd, ln, pa, im, pts, gx);
        if (emon.Port.SrcMissed(view) || status == emon.Pdfdoc.PdNeedBytes()) {
            return Step.Missed();
        }
        if (status != 0) {
            return Step.Done(Outcome.Error(Damaged(idx, emon.Planloop.DsContentFailed())));
        }
        int npg = emon.Pdfdoc.PdPgCount(pg);
        Intake info = intake(npg);
        // THE PLAN OF THE PAGE UNITS (P6.5, D212, P7.U): a page with no text layer and one JPEG image that covers it is a scanned page (a `decode` of the
        // stream, then an `ocr` of its bitmap, in its own unit); its stream is located here, the one place that reads the input. A page with a text layer is
        // left as the text layer made it (fusion, P7.3, is what weighs a second reading). The text layer's own triage and health (P7.0, D195, D208): epages,
        // the stage-1 features, the flow COPIED into fw/fl without the words that failed a P3.6 check (set aside in fa, written to the ledger after FuWrite),
        // and the font health in hl. Stage 3 reads fw/fl from here on, never wd/ln; the scanned pages' lines join them after the page units.
        emon.Port.Cells[] l7 = CellsN(7);
        emon.Port.Cells tlEpages = l7[0], tlFeats = l7[1], fw = l7[2], fl = l7[3], fa = l7[4], hl = l7[5], hsc = l7[6];
        emon.Tlhealth.ThRun(pg, gl, et, mk, fnt, wd, ln, im, tlEpages, tlFeats, fw, fl, fa, hl, hsc);
        int nl0 = emon.Port.CellsLen(ln) / 16;
        emon.Port.Cells[] sx = CellsN(4);
        emon.Port.Cells plan0 = sx[0], sp = sx[1], rs1 = sx[2], rs2 = sx[3];
        emon.Pageunit.PuPlan(view, ar, dc, ca, cb, st, pg, im, wd, opts.Ocr.Code(), opts.OcrScope.Code(), fw, fl, et, hl, sp, plan0, rs1, rs2);
        if (emon.Port.SrcMissed(view)) {
            return Step.Missed();
        }
        // The fused graphics (P5.5a, D270): the vector source's rules and fills of every page, which the table stage reads instead of paths (until P7.3 moves
        // it into the page units, SPEC 9.9).
        emon.Port.Cells[] gx4 = CellsN(4);
        emon.Port.Cells grl = gx4[0], gfl = gx4[1], gcand = gx4[2], gmeas = gx4[3];
        emon.Gfuse.GfDoc(pa, pts, mk, im, npg, emon.Gfuse.GfxMode(), grl, gfl, gcand, gmeas);
        // P3.13 (SPEC 10.11): the asset candidates are made by the ASSET UNIT (`AssetUnit`, P6.1a), which runs after this pass and reads the input again,
        // because a codec image's pixels are the host's and ask the plan/fulfil loop; the placements it works from are kept.
        emon.Port.Cells cand = emon.Port.NewCells();
        emon.Port.Buf cbytes = emon.Port.NewBuf();
        emon.Port.Cells aim = emon.Port.NewCells();
        if (opts.Assets != Options.AssetsLevel.None) {
            aim = im;
        }
        // P8.1 (SPEC 10.6): what each image placement's dictionary says, which the figures' kinds are read from (one more read of the input)
        emon.Port.Cells[] fz3 = CellsN(3);
        emon.Port.Cells fi = fz3[0];
        emon.Figclass.FcImages(view, ar, dc, ca, cb, st, im, fz3[1], fz3[2], fi);
        if (emon.Port.SrcMissed(view)) {
            return Step.Missed();
        }
        emon.Port.Cells[] snap = null;
        if (opts.KeepEvidence) {
            snap = new emon.Port.Cells[] {CloneCells(gl), CloneCells(mk), CloneCells(fnt)};
        }
        // With fusion on (every policy but `never`), the text layer's epages and feats are the evidence's own: the OCR of a born-digital page adds its feature
        // rows to the same arena, and alignment reads both (P7.3). Without it they are dropped, so the dump is the one it always was.
        bool fusion = opts.Ocr.Code() != 0;
        var ev0 = new emon.Enctx.EvArenas();
        if (fusion) {
            ev0.Epages = tlEpages;
            ev0.Feats = tlFeats;
        }
        // The catalogue's extras and the tagged tree (P3.9, P3.7): the Info /Title and /PageLabels, and the tree's trust (D076), which the furniture stage
        // weighs the tree's /Artifact marks by.
        emon.Port.Cells[] q = CellsN(24);
        emon.Port.Cells qidx = q[0], qtxt = q[1], qw = q[2], qseen = q[3];
        emon.Port.Cells md = q[4], xm = q[5], ol = q[6], lb = q[7], nd = q[8], nt = q[9];
        emon.Port.Cells ss = q[10], se = q[11], sk = q[12], sc = q[13], so = q[14], sa = q[15], wk = q[16];
        emon.Port.Cells ha = q[17], hb = q[18], hh = q[19];
        emon.Pdfx.PxIndexBuild(view, ar, dc, ca, cb, st, pg, qidx);
        emon.Pdfmeta.PmRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, md, xm, ol, lb, nd, nt);
        emon.Pdfstruct.TgRead(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, ss, se, sk, sc, so, wk, md);
        emon.Pdfstruct.TgJoin(ss, se, sk, sc, so, sa, gl, mk, fnt, pg, ha, hb, hh);
        // inline styles and links (P4.7): what each font says of weight and slant, and the link annotations (P3.8)
        emon.Port.Cells[] v = CellsN(8);
        emon.Port.Cells ift = v[0], vfs = v[1], vfm = v[2], van = v[3], vaq = v[4], vfd = v[5], vsg = v[6];
        emon.Inline.IlFonts(view, ar, dc, ca, cb, st, fnt, et, ift);
        emon.Pdfannot.PaRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, vfs, qseen, vfm, van, vaq, vfd, vsg);
        if (emon.Port.SrcMissed(view)) {
            return Step.Missed();
        }
        emon.Port.Cells mask = emon.Port.NewCells();
        for (int i = 0; i < System.Math.Max(npg, 0); i++) {
            emon.Port.CellsPush(mask, 0);
        }
        emon.Port.Cells ptsKept = fusion ? pts : emon.Port.NewCells();
        var doc = new emon.Enctx.Doc();
        doc.Hl = hl;
        doc.Et = et;
        doc.Gl = gl;
        doc.Mk = mk;
        doc.Fnt = fnt;
        doc.Wd = wd;
        doc.Ln = ln;
        doc.Fw = fw;
        doc.Fl = fl;
        doc.Fa = fa;
        doc.Nl0 = nl0;
        doc.Pa = pa;
        doc.Im = im;
        doc.Grl = grl;
        doc.Gfl = gfl;
        doc.Ift = ift;
        doc.An = van;
        doc.Pts = ptsKept;
        doc.Pg = pg;
        doc.Txt = qtxt;
        doc.Md = md;
        doc.Lb = lb;
        doc.Ol = ol;
        doc.Ss = ss;
        doc.Se = se;
        doc.Sc = sc;
        doc.Plan = plan0;
        doc.Mask = mask;
        doc.Loc = LocaleCells(opts);
        if (snap != null) {
            doc.Snapg = snap[0];
            doc.Snapk = snap[1];
            doc.Snapf = snap[2];
            doc.Hassnap = 1;
        }
        doc.Cand = cand;
        doc.Cbytes = cbytes;
        doc.Aim = aim;
        doc.Fi = fi;
        var s = new Step();
        s.Pdf = new PdfDoc(ev0, doc, info);
        return s;
    }

    /// <summary>
    /// THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil loop. It re-opens the
    /// document (the document pass's arenas are gone) and runs `AsCollect` over the placements the document pass kept: a codec image whose pixels are the
    /// host's is asked for, with the bytes kin made itself in `payload`; the unit is run again with the answers and makes the candidates. Answers 0 (the
    /// candidates are in d.Doc.Cand, d.Doc.Cbytes), 1 (it asked) or 7 (the source missed).
    /// </summary>
    internal static int AssetUnit(emon.Port.Src view, emon.Port.Src pw, PdfDoc d, Options opts, int idx, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms, emon.Port.Buf payload) {
        emon.Port.Cells[] a = CellsN(5);
        emon.Port.Cells ar = a[0], dc = a[1], ca = a[2], cb = a[3], st = a[4];
        int ostatus = emon.Pdfdoc.PdOpenPw(view, pw, ar, dc, ca, cb, st);
        if (emon.Port.SrcMissed(view) || ostatus == emon.Pdfdoc.PdNeedBytes()) {
            return 7;
        }
        if (ostatus != 0) {
            return 100 + ostatus;
        }
        emon.Port.Cells[] w = CellsN(10);
        emon.Port.Buf z = emon.Port.NewBuf();
        emon.Port.Cells glob = emon.Port.NewCells(), words = emon.Port.NewCells(), hs = emon.Port.NewCells();
        d.Doc.Cbytes = emon.Port.NewBuf();
        int rc = emon.Asset.AsCollect(view, ar, dc, ca, cb, st, d.Doc.Aim, d.Doc.Pa, d.Doc.Gl, d.Doc.Pg, opts.AssetPixels, opts.AssetsBytes, opts.Assets.Level(),
            emon.Port.NewCells(), d.Doc.Cand, d.Doc.Cbytes, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8], z, idx, answers, rq, payload, bms, glob, words, hs);
        if (emon.Port.SrcMissed(view) || rc == 12) {
            return 7;
        }
        return rc;
    }

    /// <summary>
    /// One page's unit (P7.U, D195): its own requests, answers and BitmapSet; a scanned page is read, any other is left as the text layer made it. It writes
    /// nothing until its answers are in, and never reads the input.
    /// </summary>
    internal static int UnitPdf(PdfDoc d, int i, int idx, Options opts, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms) {
        emon.Port.Cells scr = emon.Port.NewCells();
        emon.Enctx.Doc x = d.Doc;
        emon.Enctx.EvArenas ev = d.Ev;
        return emon.Pageunit.PuRunPdf(i, idx, opts.Ocr.Code(), x.Plan, answers, rq, x.Loc, bms, x.Mask, scr,
            ev.Epages, ev.Views, x.Et, ev.Feats, ev.Images, ev.Oruns, ev.Oblocks, ev.Olines, ev.Owords, ev.Osyms, ev.Oalts, x.Wd, x.Ln,
            2 * BoolInt(opts.NoRetarget), x.Fw, x.Fl, x.Hl, ev.Refs, x.Cg, ev.Tlines, x.Xs0, x.Xs1, x.Xs2, x.Xs3, x.Xs4, x.Xs5,
            ev.Inks, ev.Comps, ev.Rules, ev.Prims, ev.Pts,
            x.Ys0, x.Ys1, x.Ys2, x.Ys3, x.Ys4, x.Ys5, x.Ys6, x.Ys7, x.Ys8, x.Ys9, x.Ys10, x.Ys11, x.Ys12, x.Ys13, x.Ys14, x.Ys15, x.Ys16, x.Ys17, x.Ys18);
    }

    /// <summary>
    /// The document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture, tables, headings, paragraphs
    /// and columns, the Model. All of it is kin's (`emon.engine`).
    /// </summary>
    internal static Outcome FinishPdf(PdfDoc d, Options opts) {
        var m = new emon.Enctx.Arenas();
        int r = emon.Engine.EnFinishPdf(CfgOf(opts), d.Doc, d.Ev, m, new emon.Enctx.Scratch());
        return Finished(r, d.Doc, m, d.Info, opts, 0);
    }

    /// <summary>
    /// P8.5's asset stage at the level `figures` (SPEC 10.11, D351): one attempt of the asset unit, on copies the caller made. It writes the Model's settings
    /// and every figure's and seal's asset or reason, and any `crop` or `decode` request it has no answer to into rq (then its results are meaningless: the
    /// loop answers and runs it again).
    /// </summary>
    internal static void AssetStageFigures(emon.Enctx.Arenas m, Pending p, Options opts, int input, int nbytes, emon.Port.Cells ans, emon.Port.Cells rq, emon.Port.BitmapSet bms) {
        int sigs = BoolInt(opts.AssetSignatures);
        emon.Asset.AsSettings(m.Nodes, m.Attrs, m.Text, opts.Assets.Level(), opts.AssetDpi, opts.AssetPixels, opts.AssetBytes, opts.AssetsBytes, sigs);
        emon.Port.Cells[] s = CellsN(5);
        emon.Port.Buf z = emon.Port.NewBuf();
        emon.Asset.AsRunFigures(m.Nodes, m.Attrs, m.Regions, m.Pages, p.Cand, p.Cbytes, m.Assets, m.Uses, m.Abytes,
            opts.AssetBytes, opts.AssetsBytes, sigs, opts.AssetDpi, opts.AssetPixels, input, nbytes, p.Rfrom, ans, rq, bms,
            s[0], z, s[1], s[2], s[3], s[4]);
    }
}
