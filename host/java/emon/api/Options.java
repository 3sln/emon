// What a conversion is asked to do, and the ports and bounds an Engine is made with (SPEC 13; D038, D117, D153, D200).
// The twin of Go's `Options`, `Config`, `Parsed`, `HTMLOptions`, `JSONOptions`, `Output` and their enums (`host/go/engine.go`): read that for the reasoning.
//
// ONE `key=value` GRAMMAR, PARSED BY KIN (`emon.config`, P10.10, D434-D437) INTO A `Cfg`: the drivers, the browser binding and later the C binding and
// the pod say what a conversion is asked to do in the same text. `Options` and `Config` stay the idiomatic typed classes; `parse` is the thin wrapper
// that reads one back out of the `Cfg` kin filled. A key's name, a word's spelling, a default and a range are not known here: the defaults are the
// parse of the empty text, and the `ocrBornDigital` setting of the first Java host (which had drifted from the other hosts) is gone with the policy on.

package emon.api;

import emon.Port;
import java.util.ArrayList;
import java.util.List;

public final class Options {
    /** SPEC 13's OCR setting (D038): `never` asks for no OCR; `auto` and `always` OCR an image and a scanned PDF page, `always` also fuses a born-digital one. */
    public enum OcrPolicy {
        ALWAYS, AUTO, NEVER;

        /** kin's code: 0 never, 1 auto, 2 always (`emon.scan`, `emon.rasterin`). */
        public int code() {
            switch (this) {
                case NEVER: return 0;
                case AUTO: return 1;
                default: return 2;
            }
        }

        static OcrPolicy of(int code) {
            return code == 0 ? NEVER : code == 1 ? AUTO : ALWAYS;
        }
    }

    /** SPEC 13's `ocr-scope` (D200): what `ocr: always` reads of a born-digital page: the whole render with its text, or the render without it and witness lines. */
    public enum OcrScope {
        FULL, WITNESS;

        /** The `scope` of kin's `rp-plan`: 0 full, 1 witness. */
        public int code() {
            return ordinal();
        }
    }

    /** SPEC 13's `assets` (D117): `none` (the default), `images` (a figure or seal that is one embedded image gets it), `figures` (also the others, cropped or rendered: P8.5). */
    public enum Assets {
        NONE, IMAGES, FIGURES;

        public int level() {
            return ordinal();
        }
    }

    /** SPEC 13's `furniture` (D017, D153): `policy` drops what the output already says and keeps the rest as asides. */
    public enum Furniture {
        POLICY, KEEP, DROP;

        public int mode() {
            return ordinal();
        }
    }

    /** A BCP 47 hint, written to the Model's `settings.locale`; empty is none (SPEC 10.9). */
    public String locale = "";
    public OcrPolicy ocr = OcrPolicy.ALWAYS;
    public Assets assets = Assets.NONE;
    /** The settings that go with `assets` (SPEC 10.11): the render-crop dpi, pixels per asset, bytes per asset and per document, and whether signatures' pixels are kept. */
    public int assetDpi, assetPixels, assetBytes, assetsBytes;
    public boolean assetSignatures;
    public Furniture furniture = Furniture.POLICY;
    /** Keeps the text layer's evidence dump (`Model.evidence()`): a test and debugging aid. */
    public boolean keepEvidence;
    public OcrScope ocrScope = OcrScope.FULL;
    /** A HIDDEN TEST SETTING (P7.4): leave every pixel unread by the page units. NOT PUBLIC API (P11.4, D1480): package-private; the gates' driver
     *  reaches it through `emon.api.TestHooks` (test/driver/java/emon/api/TestHooks.java), which is not part of the published jar. */
    boolean noRetarget;

    private Options() {
    }

    /** SPEC 13's defaults: kin's (`emon.config`'s `cf-defaults`), the parse of the empty text. */
    public static Options defaults() {
        return parse("").options;
    }

    public Options copy() {
        Options o = new Options();
        o.locale = locale;
        o.ocr = ocr;
        o.assets = assets;
        o.assetDpi = assetDpi;
        o.assetPixels = assetPixels;
        o.assetBytes = assetBytes;
        o.assetsBytes = assetsBytes;
        o.assetSignatures = assetSignatures;
        o.furniture = furniture;
        o.keepEvidence = keepEvidence;
        o.ocrScope = ocrScope;
        o.noRetarget = noRetarget;
        return o;
    }

    /** SPEC 4's options: the indented form or the compact one. */
    public static final class Json {
        public boolean pretty;

        public Json() {
        }

        public Json(boolean pretty) {
            this.pretty = pretty;
        }
    }

    /** What the HTML does with the Model's assets (SPEC 10.11). */
    public enum HtmlAssets {
        INLINE, EXTERNAL, OMIT
    }

    /**
     * SPEC 5.2's (D133). {@code new Html()} is clean HTML: no ids, no geometry, milestones on, furniture templates kept, the ledger off. The option bits are
     * `kin/html.kin`'s (`ho-*`).
     */
    public static final class Html {
        public boolean ids, geometry, ledger, pretty, furnitureDrop, initialsPlain, alignStyle;
        public boolean milestones = true;
        /** When not null, writes `data-emon-conf` on nodes whose confidence (0..=1000) is under it. */
        public Integer confidenceBelow;
        public HtmlAssets assets = HtmlAssets.INLINE;
        public String assetPrefix = "";

        long[] bits() {
            int b = 0;
            if (ids) {
                b |= emon.Html.hoIds();
            }
            if (geometry) {
                b |= emon.Html.hoGeometry();
            }
            if (confidenceBelow != null) {
                b |= emon.Html.hoConfidence();
            }
            if (ledger) {
                b |= emon.Html.hoLedger();
            }
            if (pretty) {
                b |= emon.Html.hoPretty();
            }
            if (furnitureDrop) {
                b |= emon.Html.hoFurnitureDrop();
            }
            if (!milestones) {
                b |= emon.Html.hoNoMilestones();
            }
            if (initialsPlain) {
                b |= emon.Html.hoInitialsPlain();
            }
            if (alignStyle) {
                b |= emon.Html.hoAlignStyle();
            }
            switch (assets) {
                case EXTERNAL:
                    b |= emon.Html.hoAssetsExternal();
                    break;
                case OMIT:
                    b |= emon.Html.hoAssetsOmit();
                    break;
                default:
                    break;
            }
            long below = confidenceBelow == null ? 0 : (confidenceBelow & 0xFFFFFFFFL);
            if (below > 1000) {
                below = 1000;
            }
            return new long[] {b, below};
        }
    }

    /** An output format, for callers (and the driver) that choose one by value: exactly one is set. */
    public static final class Output {
        public Json json;
        public Html html;
    }

    /** The JPX and JBIG2 decoder of the `decode-img` request (P6.1a, D117, D336): what an embedder brings for the two PDF image codecs no JDK library reads. */
    public interface PdfCodecs {
        /** Gray (1) or RGB (3) channels, 8-bit; null for a stream this codec does not read ({@link ImgDec.Fail} with UNSUPPORTED is the same). */
        ImgDec.PdfPix jpx(byte[] data, long maxPixels) throws ImgDec.Fail;

        /** ONE channel, 0 for a black pixel and 255 for a white one (the PDF's JBIG2Decode convention). */
        ImgDec.PdfPix jbig2(byte[] data, byte[] globals, long maxPixels) throws ImgDec.Fail;
    }

    /** The ports and bounds an Engine is made with. */
    public static final class Config {
        /** SPEC 7.4's `Port.limitOf` kinds as [kind, value] pairs: an entry here beats `EMON_LIMIT_*`. A conversion with Limits runs ALONE in the process. */
        public int[][] limits = new int[0][];
        /** The source cache's chunk size (SPEC 13.1: 64 KiB by default). */
        public int chunkSize;
        /** Makes the cache behave like an ASYNC source (a browser Blob): a miss stops the unit with `need-bytes`, and the loop fetches and re-runs it. The output is identical. */
        public boolean simulateAsync;
        public int maxRounds, fetchRounds;
        /**
         * The `parallel` option (P10.2, SPEC 12.1, D505): how many workers a conversion may spread its fulfilment over. 1, the default, is serial and starts no
         * thread; the limit `workers` caps it (kin's `pl-workers` is the clamp). It changes how the work is spread and nothing in the Model, the transcript or
         * the accounting. A conversion's workers run inside its limit scope (`Port.withLimits`) and end before it does.
         */
        public int parallel = 1;
        /** The `Ocr` port, if the embedder has one (D211). null: no live OCR; a replay needs none. */
        public Ocr ocr;
        /** The `Renderer` port (D007, D127): what fulfils `render` and `crop` requests when the Engine converts or records. null: none, and a request is answered `unsupported` (D199). */
        public emon.RenderPort.Renderer renderer;
        /** The JPX and JBIG2 decoder of the `decode-img` request. null (the default): those two are answered `unsupported` and the image is `noasset: "codec"`. */
        public PdfCodecs pdfCodecs;

        private Config() {
        }

        /** SPEC 13.1's defaults: kin's too. */
        public static Config defaults() {
            return parse("").config;
        }
    }

    /** What a text asks for: the Options and the Config (limits, chunk size, the loop's bounds), and the browser binding's own switches. */
    public static final class Parsed {
        public Options options;
        public Config config;
        /** `record=1`: keep a transcript of the conversion. `js-ocr=1`: the host owes the `ocr` requests. */
        public boolean record, jsOcr;
        /** `js-render=1`: the host owes the `render` and `crop` requests (P11.4c). */
        public boolean jsRender;
        /** `producer.NAME=VALUE`, in order: what the transcript says produced it. */
        public final List<String[]> producer = new ArrayList<>();
    }

    private static Port.Cells cellsOf(String s) {
        Port.Cells c = Port.newCells();
        s.codePoints().forEach(cp -> Port.cellsPush(c, cp));
        return c;
    }

    private static String stringOf(Port.Cells c, int from, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + n; i++) {
            sb.appendCodePoint(Port.cellsAt(c, i));
        }
        return sb.toString();
    }

    /**
     * Reads `key=value` lines, parsed by kin: the settings of SPEC 13 by their names, `limit.NAME=N` for the limits, the loop's bounds, and the ports the
     * host has. An {@link IllegalArgumentException} carries the sentence a driver prints after `usage`.
     */
    public static Parsed parse(String text) {
        emon.Enctx.Cfg c = new emon.Enctx.Cfg();
        if (emon.Config.cfParse(cellsOf(text), c) != 0) {
            throw new IllegalArgumentException(c.msg.toString());
        }
        Options o = new Options();
        o.ocr = OcrPolicy.of(c.ocr);
        o.assets = Assets.values()[c.level];
        o.assetDpi = c.dpi;
        o.assetPixels = c.pixels;
        o.assetBytes = c.abyte;
        o.assetsBytes = c.tbyte;
        o.assetSignatures = c.sigs != 0;
        o.furniture = Furniture.values()[c.furn];
        o.keepEvidence = c.keep != 0;
        o.ocrScope = OcrScope.values()[c.scope];
        if (c.haveloc == 1) {
            o.locale = stringOf(c.lcl, 0, Port.cellsLen(c.lcl));
        }
        Config cfg = new Config();
        cfg.chunkSize = c.chunk;
        cfg.maxRounds = c.maxr;
        cfg.fetchRounds = c.fetchr;
        cfg.parallel = c.par;
        cfg.limits =new int[Port.cellsLen(c.lims) / 2][];
        for (int i = 0; i < cfg.limits.length; i++) {
            cfg.limits[i] = new int[] {Port.cellsAt(c.lims, 2 * i), Port.cellsAt(c.lims, 2 * i + 1)};
        }
        Parsed p = new Parsed();
        p.options = o;
        p.config = cfg;
        p.record = c.rec != 0;
        p.jsOcr = c.jsocr != 0;
        p.jsRender = c.jsrender != 0;
        for (int at = 0; at < Port.cellsLen(c.prod);) {
            int kl = Port.cellsAt(c.prod, at), vl = Port.cellsAt(c.prod, at + 1);
            p.producer.add(new String[] {stringOf(c.prod, at + 2, kl), stringOf(c.prod, at + 2 + kl, vl)});
            at += 2 + kl + vl;
        }
        return p;
    }

    /** Whether name (a driver's `--name=`) is a key of the grammar, which kin owns. */
    public static boolean isKey(String name) {
        Port.Cells t = cellsOf(name);
        return emon.Config.cfIsKey(t, 0, Port.cellsLen(t)) == 1;
    }
}
