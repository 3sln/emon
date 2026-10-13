// What a conversion is asked to do, and the ports and bounds an Engine is made with (SPEC 13; D038, D117, D153, D200).
// The twin of Go's `Options`, `Config`, `Parsed`, `HTMLOptions`, `JSONOptions`, `Output` and their enums (`host/go/engine.go`) and of
// `host/java/emon/api/Options.java`: read those for the reasoning.
//
// ONE `key=value` GRAMMAR, PARSED BY KIN (`emon.config`, P10.10, D434-D437) INTO A `Cfg`: the drivers, the browser binding and later the C binding and
// the pod say what a conversion is asked to do in the same text. `Options` and `Config` stay the idiomatic typed classes; `Parse` is the thin wrapper
// that reads one back out of the `Cfg` kin filled. A key's name, a word's spelling, a default and a range are not known here: the defaults are the
// parse of the empty text, and the `OcrBornDigital` setting of the first C# host (which had drifted from the other hosts) is gone with the policy on.

namespace emon.Api;

using System.Collections.Generic;

public sealed class Options {
    /// <summary>SPEC 13's OCR setting (D038): `never` asks for no OCR; `auto` and `always` OCR an image and a scanned PDF page, `always` also fuses a born-digital one. `Code()` is kin's (an extension below).</summary>
    public enum OcrPolicy {
        Always, Auto, Never
    }

    /// <summary>SPEC 13's `ocr-scope` (D200): what `ocr: always` reads of a born-digital page: the whole render with its text, or the render without it and witness lines. `Code()` is the `scope` of kin's `rp-plan`: 0 full, 1 witness.</summary>
    public enum OcrScopes {
        Full, Witness
    }

    /// <summary>SPEC 13's `assets` (D117): `none` (the default), `images` (a figure or seal that is one embedded image gets it), `figures` (also the others, cropped or rendered: P8.5). `Level()` is an extension below.</summary>
    public enum AssetsLevel {
        None, Images, Figures
    }

    /// <summary>SPEC 13's `furniture` (D017, D153): `policy` drops what the output already says and keeps the rest as asides. `Mode()` is an extension below.</summary>
    public enum FurnitureMode {
        Policy, Keep, Drop
    }

    /// <summary>A BCP 47 hint, written to the Model's `settings.locale`; empty is none (SPEC 10.9).</summary>
    public string Locale = "";
    public OcrPolicy Ocr = OcrPolicy.Always;
    public AssetsLevel Assets = AssetsLevel.None;
    /// <summary>The settings that go with `assets` (SPEC 10.11): the render-crop dpi, pixels per asset, bytes per asset and per document, and whether signatures' pixels are kept.</summary>
    public int AssetDpi, AssetPixels, AssetBytes, AssetsBytes;
    public bool AssetSignatures;
    public FurnitureMode Furniture = FurnitureMode.Policy;
    /// <summary>Keeps the text layer's evidence dump (`Model.Evidence()`): a test and debugging aid.</summary>
    public bool KeepEvidence;
    public OcrScopes OcrScope = OcrScopes.Full;
    /// <summary>A HIDDEN TEST SETTING (P7.4): leave every pixel unread by the page units.</summary>
    public bool NoRetarget;

    private Options() {
    }

    /// <summary>SPEC 13's defaults: kin's (`emon.config`'s `cf-defaults`), the parse of the empty text.</summary>
    public static Options Defaults() {
        return Parse("").Options;
    }

    public Options Copy() {
        var o = new Options();
        o.Locale = Locale;
        o.Ocr = Ocr;
        o.Assets = Assets;
        o.AssetDpi = AssetDpi;
        o.AssetPixels = AssetPixels;
        o.AssetBytes = AssetBytes;
        o.AssetsBytes = AssetsBytes;
        o.AssetSignatures = AssetSignatures;
        o.Furniture = Furniture;
        o.KeepEvidence = KeepEvidence;
        o.OcrScope = OcrScope;
        o.NoRetarget = NoRetarget;
        return o;
    }

    /// <summary>SPEC 4's options: the indented form or the compact one.</summary>
    public sealed class Json {
        public bool Pretty;

        public Json() {
        }

        public Json(bool pretty) {
            this.Pretty = pretty;
        }
    }

    /// <summary>What the HTML does with the Model's assets (SPEC 10.11).</summary>
    public enum HtmlAssets {
        Inline, External, Omit
    }

    /// <summary>
    /// SPEC 5.2's (D133). <c>new Html()</c> is clean HTML: no ids, no geometry, milestones on, furniture templates kept,
    /// the ledger off. The option bits are `kin/html.kin`'s (`ho-*`).
    /// </summary>
    public sealed class Html {
        public bool Ids, Geometry, Ledger, Pretty, FurnitureDrop, InitialsPlain, AlignStyle;
        public bool Milestones = true;
        /// <summary>When not null, writes `data-emon-conf` on nodes whose confidence (0..=1000) is under it.</summary>
        public int? ConfidenceBelow;
        public HtmlAssets Assets = HtmlAssets.Inline;
        public string AssetPrefix = "";

        internal long[] Bits() {
            int b = 0;
            if (Ids) {
                b |= emon.Html.HoIds();
            }
            if (Geometry) {
                b |= emon.Html.HoGeometry();
            }
            if (ConfidenceBelow != null) {
                b |= emon.Html.HoConfidence();
            }
            if (Ledger) {
                b |= emon.Html.HoLedger();
            }
            if (Pretty) {
                b |= emon.Html.HoPretty();
            }
            if (FurnitureDrop) {
                b |= emon.Html.HoFurnitureDrop();
            }
            if (!Milestones) {
                b |= emon.Html.HoNoMilestones();
            }
            if (InitialsPlain) {
                b |= emon.Html.HoInitialsPlain();
            }
            if (AlignStyle) {
                b |= emon.Html.HoAlignStyle();
            }
            switch (Assets) {
                case HtmlAssets.External:
                    b |= emon.Html.HoAssetsExternal();
                    break;
                case HtmlAssets.Omit:
                    b |= emon.Html.HoAssetsOmit();
                    break;
                default:
                    break;
            }
            long below = ConfidenceBelow == null ? 0 : (long) (uint) ConfidenceBelow.Value;
            if (below > 1000) {
                below = 1000;
            }
            return new long[] {b, below};
        }
    }

    /// <summary>An output format, for callers (and the driver) that choose one by value: exactly one is set.</summary>
    public sealed class Output {
        public Json Json;
        public Html Html;
    }

    /// <summary>The JPX and JBIG2 decoder of the `decode-img` request (P6.1a, D117, D336): what an embedder brings for the two PDF image codecs the BCL does not read.</summary>
    public interface IPdfCodecs {
        /// <summary>Gray (1) or RGB (3) channels, 8-bit; null for a stream this codec does not read (an <see cref="ImgDec.Fail"/> with Unsupported is the same).</summary>
        ImgDec.PdfPix Jpx(byte[] data, long maxPixels);

        /// <summary>ONE channel, 0 for a black pixel and 255 for a white one (the PDF's JBIG2Decode convention).</summary>
        ImgDec.PdfPix Jbig2(byte[] data, byte[] globals, long maxPixels);
    }

    /// <summary>The ports and bounds an Engine is made with.</summary>
    public sealed class Config {
        /// <summary>SPEC 7.4's `Port.LimitOf` kinds as [kind, value] pairs: an entry here beats `EMON_LIMIT_*`. A conversion with Limits runs ALONE in the process.</summary>
        public int[][] Limits = new int[0][];
        /// <summary>The source cache's chunk size (SPEC 13.1: 64 KiB by default).</summary>
        public int ChunkSize;
        /// <summary>Makes the cache behave like an ASYNC source (a browser Blob): a miss stops the unit with `need-bytes`, and the loop fetches and re-runs it. The output is identical.</summary>
        public bool SimulateAsync;
        public int MaxRounds, FetchRounds;
        /// <summary>
        /// The `parallel` option (P10.2, SPEC 12.1, D505): how many workers a conversion may spread its fulfilment over. 1, the default, is serial and starts
        /// no thread; the limit `workers` caps it (kin's `pl-workers` is the clamp). It changes how the work is spread and nothing in the Model, the
        /// transcript or the accounting. A conversion's workers run inside its limit scope (`Port.WithLimits`) and end before it does.
        /// </summary>
        public int Parallel = 1;
        /// <summary>The `Ocr` port, if the embedder has one (D211). null: no live OCR; a replay needs none.</summary>
        public IOcr Ocr;
        /// <summary>The `Renderer` port (D007, D127): what fulfils `render` and `crop` requests when the Engine converts or records. null: none, and a request is answered `unsupported` (D199).</summary>
        public emon.RenderPort.IRenderer Renderer;
        /// <summary>The JPX and JBIG2 decoder of the `decode-img` request. null (the default): those two are answered `unsupported` and the image is `noasset: "codec"`.</summary>
        public IPdfCodecs PdfCodecs;

        internal Config() {
        }

        internal static Config New() {
            return new Config();
        }

        /// <summary>SPEC 13.1's defaults: kin's too.</summary>
        public static Config Defaults() {
            return Parse("").Config;
        }
    }

    /// <summary>What a text asks for: the Options and the Config (limits, chunk size, the loop's bounds), and the browser binding's own switches.</summary>
    public sealed class Parsed {
        public Options Options;
        public Config Config;
        /// <summary>`record=1`: keep a transcript of the conversion. `js-ocr=1`: the host owes the `ocr` requests.</summary>
        public bool Record, JsOcr;
        /// <summary>`producer.NAME=VALUE`, in order: what the transcript says produced it.</summary>
        public readonly List<string[]> Producer = new List<string[]>();
    }

    private static emon.Port.Cells CellsOf(string s) {
        emon.Port.Cells c = emon.Port.NewCells();
        for (int i = 0; i < s.Length;) {
            int cp = char.ConvertToUtf32(s, i);
            i += char.IsHighSurrogate(s[i]) && i + 1 < s.Length ? 2 : 1;
            emon.Port.CellsPush(c, cp);
        }
        return c;
    }

    private static string StringOf(emon.Port.Cells c, int from, int n) {
        var sb = new System.Text.StringBuilder();
        for (int i = from; i < from + n; i++) {
            sb.Append(char.ConvertFromUtf32(emon.Port.CellsAt(c, i)));
        }
        return sb.ToString();
    }

    /// <summary>
    /// Reads `key=value` lines, parsed by kin: the settings of SPEC 13 by their names, `limit.NAME=N` for the limits, the loop's bounds, and the ports the
    /// host has. An <see cref="System.ArgumentException"/> carries the sentence a driver prints after `usage`.
    /// </summary>
    public static Parsed Parse(string text) {
        var c = new emon.Enctx.Cfg();
        if (emon.Config.CfParse(CellsOf(text), c) != 0) {
            throw new System.ArgumentException(c.Msg.ToString());
        }
        var o = new Options();
        o.Ocr = (OcrPolicy) (c.Ocr == 0 ? 2 : c.Ocr == 1 ? 1 : 0);
        o.Assets = (AssetsLevel) c.Level;
        o.AssetDpi = c.Dpi;
        o.AssetPixels = c.Pixels;
        o.AssetBytes = c.Abyte;
        o.AssetsBytes = c.Tbyte;
        o.AssetSignatures = c.Sigs != 0;
        o.Furniture = (FurnitureMode) c.Furn;
        o.KeepEvidence = c.Keep != 0;
        o.OcrScope = (OcrScopes) c.Scope;
        if (c.Haveloc == 1) {
            o.Locale = StringOf(c.Lcl, 0, emon.Port.CellsLen(c.Lcl));
        }
        var cfg = new Config();
        cfg.ChunkSize = c.Chunk;
        cfg.MaxRounds = c.Maxr;
        cfg.FetchRounds = c.Fetchr;
        cfg.Parallel = c.Par;
        cfg.Limits =new int[emon.Port.CellsLen(c.Lims) / 2][];
        for (int i = 0; i < cfg.Limits.Length; i++) {
            cfg.Limits[i] = new int[] {emon.Port.CellsAt(c.Lims, 2 * i), emon.Port.CellsAt(c.Lims, 2 * i + 1)};
        }
        var p = new Parsed();
        p.Options = o;
        p.Config = cfg;
        p.Record = c.Rec != 0;
        p.JsOcr = c.Jsocr != 0;
        for (int at = 0; at < emon.Port.CellsLen(c.Prod);) {
            int kl = emon.Port.CellsAt(c.Prod, at), vl = emon.Port.CellsAt(c.Prod, at + 1);
            p.Producer.Add(new string[] {StringOf(c.Prod, at + 2, kl), StringOf(c.Prod, at + 2 + kl, vl)});
            at += 2 + kl + vl;
        }
        return p;
    }

    /// <summary>Whether name (a driver's `--name=`) is a key of the grammar, which kin owns.</summary>
    public static bool IsKey(string name) {
        emon.Port.Cells t = CellsOf(name);
        return emon.Config.CfIsKey(t, 0, emon.Port.CellsLen(t)) == 1;
    }
}

/// <summary>Java's package-private `code()`, `level()` and `mode()` on the Options enums.</summary>
internal static class OptionsEnums {
    /// <summary>kin's code: 0 never, 1 auto, 2 always (`emon.scan`, `emon.rasterin`).</summary>
    internal static int Code(this Options.OcrPolicy p) {
        switch (p) {
            case Options.OcrPolicy.Never: return 0;
            case Options.OcrPolicy.Auto: return 1;
            default: return 2;
        }
    }

    /// <summary>The `scope` of kin's `rp-plan`: 0 full, 1 witness.</summary>
    internal static int Code(this Options.OcrScopes s) {
        return (int) s;
    }

    internal static int Level(this Options.AssetsLevel a) {
        return (int) a;
    }

    internal static int Mode(this Options.FurnitureMode f) {
        return (int) f;
    }
}
