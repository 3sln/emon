// THE UNITS, Java host (DEVLOG P9.1, P10.6-P10.8; SPEC 7.3, 13.1; D058, D195, D351, D381-D383, D404-D408, D485). The twin of the unit half of `host/go/engine.go`
// (`dispatch`, `unitPage`, `assetUnit`, `finishDoc`, `assetStageFigures`) and of `host/rust/src/engine.rs`: read those for the reasoning.
//
// WHAT THE HOST DOES HERE IS CALL KIN, ONE CALL PER UNIT, in the order kin's schedule (`en-next`, in `Engine`) asks: the document pass (`emon.engine`'s
// `en-attempt`: the text layer, the catalogue, the tagged tree and the plan of the page units, or the raster input's header), a page unit (`en-page`), the
// document unit (`en-finish`: fusion, the evidence, furniture, tables, headings, paragraphs and columns, the Model, its check), the asset unit
// (`en-asset-unit`) and the figures unit (`en-asset-figures`). This file makes the contexts of `emon.enctx` those calls take and reads what they leave; it
// names no stage (P10.6 S3, S5). The Java stages the first host wrote by hand (`Stages`) are gone.

package emon.api;

import emon.Enctx;
import emon.Engine;
import emon.Planloop;
import emon.Port;

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
        UnitDoc doc;

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

    /**
     * What the document pass leaves for the page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`), and what the input said it is.
     * `doc.kind` says whether it is a PDF (the text layer's arenas, the catalogue's extras, the plan of what each page's unit will ask) or a raster input.
     */
    static final class UnitDoc {
        final Enctx.Arenas m;
        final Enctx.EvArenas ev;
        final Enctx.Doc doc;
        final Intake info;

        UnitDoc(Enctx.Arenas m, Enctx.EvArenas ev, Enctx.Doc doc, Intake info) {
            this.m = m;
            this.ev = ev;
            this.doc = doc;
            this.info = info;
        }
    }

    // ------------------------------------------------------------------------------------------------ small helpers

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
        c.nort = boolInt(opts.noRetarget);
        if (!opts.locale.isEmpty()) {
            c.haveloc = 1;
            c.lcl = localeCells(opts);
        }
        return c;
    }

    /** What kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3 `model-check`, 4 `model-check-assets`). */
    static Outcome finished(int r, Enctx.Doc doc, Enctx.Arenas m, Intake info, Options opts) {
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
                    b.pending = new Pending(doc.cand, doc.cbytes, doc.rfrom);
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

    /** What kin's document pass says the input is (`Doc`'s kind, ifmt, ipages, imajor, iminor, ienc). */
    static Intake intakeOf(Enctx.Doc doc, long n) {
        String format = "tiff";
        if (doc.ifmt == Engine.enFmtPdf()) {
            format = "pdf";
        } else if (doc.ifmt == Engine.enFmtPng()) {
            format = "png";
        } else if (doc.ifmt == Engine.enFmtJpeg()) {
            format = "jpeg";
        }
        return new Intake(format, doc.imajor, doc.iminor, Math.max(doc.ipages, 0), doc.ienc == 1, n);
    }

    // ------------------------------------------------------------------------------------------------ the document pass

    /**
     * ONE ATTEMPT OF THE DOCUMENT PASS for one input, from scratch on a fresh view and fresh contexts: kin's `en-attempt` (a PNG, JPEG or TIFF by its sniff is
     * read by the raster unit, anything else by the PDF one, which refuses what is not a PDF by name). A miss at any checkpoint ends it at once: what a missed
     * attempt computed is never looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1).
     */
    static Step dispatch(Port.Src view, Port.Src pw, Port.Cells answers, Port.Cells rq, int idx, Options opts, boolean intakeOnly, long n) {
        try {
            Enctx.Arenas m = new Enctx.Arenas();
            Enctx.EvArenas ev = new Enctx.EvArenas();
            Enctx.Doc doc = new Enctx.Doc();
            int r = Engine.enAttempt(view, pw, cfgOf(opts), doc, ev, m, new Enctx.Pass(), answers, rq, idx, boolInt(intakeOnly));
            if (r == Engine.atMiss()) {
                return Step.missed();
            }
            if (r == Engine.atRequests()) {
                return Step.asked();
            }
            if (r == Engine.atFailed()) {
                return Step.done(Outcome.error(statusError(idx, doc.fcode)));
            }
            if (r == Engine.atInternal()) {
                return Step.done(Outcome.error(EmonError.internal("the raster unit answered status " + doc.fcode)));
            }
            if (r == Engine.atIntake()) {
                Outcome o = new Outcome();
                o.intake = intakeOf(doc, n);
                return Step.done(o);
            }
            Step s = new Step();
            s.doc = new UnitDoc(m, ev, doc, intakeOf(doc, n));
            return s;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError p) {
            if (Port.srcMissed(view)) {
                return Step.missed();
            }
            return Step.done(Outcome.error(EmonError.panic(p)));
        }
    }

    /**
     * THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil loop, one call of kin's
     * (`en-asset-unit`). It re-opens the document (the document pass's arenas are gone) and collects the placements the document pass kept: a codec image
     * whose pixels are the host's is asked for through rq. Answers 0 (the candidates are in d.doc.cand, d.doc.cbytes), 1 (it asked) or 7 (the source missed).
     */
    static int assetUnit(Port.Src view, Port.Src pw, UnitDoc d, Options opts, int idx, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms, Port.Buf payload) {
        return Engine.enAssetUnit(view, pw, cfgOf(opts), d.doc, new Enctx.Pass(), idx, answers, rq, bms, payload);
    }

    /**
     * One page's (a raster input's frame's) unit (P7.U, D195): its own requests, answers and BitmapSet; a scanned page is read, any other is left as the text
     * layer made it. It writes nothing until its answers are in, and never reads the input. One call of kin's.
     */
    static int unitPage(UnitDoc d, int i, int idx, long n, Options opts, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms) {
        return Engine.enPage(cfgOf(opts), d.doc, d.ev, i, idx, (int) n, answers, rq, bms);
    }

    /**
     * The document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture, tables, headings, paragraphs
     * and columns, the Model. All of it is kin's (`emon.engine`), whatever the kind of the input.
     */
    static Outcome finishDoc(UnitDoc d, Options opts) {
        int r = Engine.enFinish(cfgOf(opts), d.doc, d.ev, d.m, new Enctx.Scratch());
        return finished(r, d.doc, d.m, d.info, opts);
    }

    /**
     * P8.5's asset stage at the level `figures` (SPEC 10.11, D351): one attempt of the asset unit, on copies the caller made. It writes the Model's settings
     * and every figure's and seal's asset or reason, and any `crop` or `decode` request it has no answer to into rq (then its results are meaningless: the
     * loop answers and runs it again).
     */
    static void assetStageFigures(Enctx.Arenas m, Pending p, Options opts, int input, int nbytes, Port.Cells ans, Port.Cells rq, Port.BitmapSet bms) {
        Engine.enAssetFigures(cfgOf(opts), m, p.cand, p.cbytes, p.rfrom, new Enctx.Pass(), input, nbytes, ans, rq, bms);
    }
}
