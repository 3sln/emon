// THE UNITS, C# host (DEVLOG P9.2, P10.6-P10.8; SPEC 7.3, 13.1; D058, D195, D351, D381-D383, D404-D408, D1160). The twin of `host/java/emon/api/Units.java`, of the unit
// half of `host/go/engine.go` (`dispatch`, `unitPage`, `assetUnit`, `finishDoc`, `assetStageFigures`) and of `host/rust/src/engine.rs`: read those for the reasoning.
//
// WHAT THE HOST DOES HERE IS CALL KIN, ONE CALL PER UNIT, in the order kin's schedule (`en-next`, in `Engine`) asks: the document pass (`emon.engine`'s
// `en-attempt`: the text layer, the catalogue, the tagged tree and the plan of the page units, or the raster input's header), a page unit (`en-page`), the
// document unit (`en-finish`: fusion, the evidence, furniture, tables, headings, paragraphs and columns, the Model, its check), the asset unit
// (`en-asset-unit`) and the figures unit (`en-asset-figures`). This file makes the contexts of `emon.enctx` those calls take and reads what they leave; it
// names no stage (P10.6 S3, S5). The C# stages the first host wrote by hand (`Stages`) are gone.
//
// The generated classes (`emon.Engine`, `emon.Enctx`, `emon.Rasterin`, ...) are written fully qualified here: this namespace has an `Engine`, an `Asset` and a
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
        internal UnitDoc Doc;

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

    /// <summary>
    /// What the document pass leaves for the page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`), and what the input said it is.
    /// `Doc.Kind` says whether it is a PDF (the text layer's arenas, the catalogue's extras, the plan of what each page's unit will ask) or a raster input.
    /// </summary>
    internal sealed class UnitDoc {
        internal readonly emon.Enctx.Arenas M;
        internal readonly emon.Enctx.EvArenas Ev;
        internal readonly emon.Enctx.Doc Doc;
        internal readonly Intake Info;

        internal UnitDoc(emon.Enctx.Arenas m, emon.Enctx.EvArenas ev, emon.Enctx.Doc doc, Intake info) {
            this.M = m;
            this.Ev = ev;
            this.Doc = doc;
            this.Info = info;
        }
    }

    // ------------------------------------------------------------------------------------------------ small helpers

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
        c.Nort = BoolInt(opts.NoRetarget);
        if (opts.Locale.Length > 0) {
            c.Haveloc = 1;
            c.Lcl = LocaleCells(opts);
        }
        return c;
    }

    /// <summary>What kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3 `model-check`, 4 `model-check-assets`).</summary>
    internal static Outcome Finished(int r, emon.Enctx.Doc doc, emon.Enctx.Arenas m, Intake info, Options opts) {
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
                    b.Pending = new Pending(doc.Cand, doc.Cbytes, doc.Rfrom);
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

    /// <summary>What kin's document pass says the input is (`Doc`'s Kind, Ifmt, Ipages, Imajor, Iminor, Ienc).</summary>
    internal static Intake IntakeOf(emon.Enctx.Doc doc, long n) {
        string format = "tiff";
        if (doc.Ifmt == emon.Engine.EnFmtPdf()) {
            format = "pdf";
        } else if (doc.Ifmt == emon.Engine.EnFmtPng()) {
            format = "png";
        } else if (doc.Ifmt == emon.Engine.EnFmtJpeg()) {
            format = "jpeg";
        }
        return new Intake(format, doc.Imajor, doc.Iminor, System.Math.Max(doc.Ipages, 0), doc.Ienc == 1, n);
    }

    // ------------------------------------------------------------------------------------------------ the document pass

    /// <summary>
    /// ONE ATTEMPT OF THE DOCUMENT PASS for one input, from scratch on a fresh view and fresh contexts: kin's `en-attempt` (a PNG, JPEG or TIFF by its sniff is
    /// read by the raster unit, anything else by the PDF one, which refuses what is not a PDF by name). A miss at any checkpoint ends it at once: what a missed
    /// attempt computed is never looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1).
    /// </summary>
    internal static Step Dispatch(emon.Port.Src view, emon.Port.Src pw, emon.Port.Cells answers, emon.Port.Cells rq, int idx, Options opts, bool intakeOnly, long n) {
        try {
            var m = new emon.Enctx.Arenas();
            var ev = new emon.Enctx.EvArenas();
            var doc = new emon.Enctx.Doc();
            int r = emon.Engine.EnAttempt(view, pw, CfgOf(opts), doc, ev, m, new emon.Enctx.Pass(), answers, rq, idx, BoolInt(intakeOnly));
            if (r == emon.Engine.AtMiss()) {
                return Step.Missed();
            }
            if (r == emon.Engine.AtRequests()) {
                return Step.Asked();
            }
            if (r == emon.Engine.AtFailed()) {
                return Step.Done(Outcome.Error(StatusError(idx, doc.Fcode)));
            }
            if (r == emon.Engine.AtInternal()) {
                return Step.Done(Outcome.Error(EmonError.Internal("the raster unit answered status " + doc.Fcode)));
            }
            if (r == emon.Engine.AtIntake()) {
                var o = new Outcome();
                o.Intake = IntakeOf(doc, n);
                return Step.Done(o);
            }
            var s = new Step();
            s.Doc = new UnitDoc(m, ev, doc, IntakeOf(doc, n));
            return s;
        } catch (System.Exception p) {
            if (emon.Port.SrcMissed(view)) {
                return Step.Missed();
            }
            return Step.Done(Outcome.Error(EmonError.Panic(p)));
        }
    }

    /// <summary>
    /// THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil loop, one call of kin's
    /// (`en-asset-unit`). It re-opens the document (the document pass's arenas are gone) and collects the placements the document pass kept: a codec image
    /// whose pixels are the host's is asked for through rq. Answers 0 (the candidates are in d.Doc.Cand, d.Doc.Cbytes), 1 (it asked) or 7 (the source missed).
    /// </summary>
    internal static int AssetUnit(emon.Port.Src view, emon.Port.Src pw, UnitDoc d, Options opts, int idx, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms, emon.Port.Buf payload) {
        return emon.Engine.EnAssetUnit(view, pw, CfgOf(opts), d.Doc, new emon.Enctx.Pass(), idx, answers, rq, bms, payload);
    }

    /// <summary>
    /// One page's (a raster input's frame's) unit (P7.U, D195): its own requests, answers and BitmapSet; a scanned page is read, any other is left as the text
    /// layer made it. It writes nothing until its answers are in, and never reads the input. One call of kin's.
    /// </summary>
    internal static int UnitPage(UnitDoc d, int i, int idx, long n, Options opts, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms) {
        return emon.Engine.EnPage(CfgOf(opts), d.Doc, d.Ev, i, idx, (int) n, answers, rq, bms);
    }

    /// <summary>
    /// The document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture, tables, headings, paragraphs
    /// and columns, the Model. All of it is kin's (`emon.engine`), whatever the kind of the input.
    /// </summary>
    internal static Outcome FinishDoc(UnitDoc d, Options opts) {
        int r = emon.Engine.EnFinish(CfgOf(opts), d.Doc, d.Ev, d.M, new emon.Enctx.Scratch());
        return Finished(r, d.Doc, d.M, d.Info, opts);
    }

    /// <summary>
    /// P8.5's asset stage at the level `figures` (SPEC 10.11, D351): one attempt of the asset unit, on copies the caller made. It writes the Model's settings
    /// and every figure's and seal's asset or reason, and any `crop` or `decode` request it has no answer to into rq (then its results are meaningless: the
    /// loop answers and runs it again).
    /// </summary>
    internal static void AssetStageFigures(emon.Enctx.Arenas m, Pending p, Options opts, int input, int nbytes, emon.Port.Cells ans, emon.Port.Cells rq, emon.Port.BitmapSet bms) {
        emon.Engine.EnAssetFigures(CfgOf(opts), m, p.Cand, p.Cbytes, p.Rfrom, new emon.Enctx.Pass(), input, nbytes, ans, rq, bms);
    }
}
