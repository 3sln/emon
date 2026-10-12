// THE MODEL, C# host (DEVLOG P9.2; SPEC 3.9, 4, 5). The twin of `host/java/emon/api/Model.java`, `host/go/model.go` and the
// Model half of `host/rust/src/engine.rs`.
//
// The Model is the seven arenas of SPEC 3.8, owned; `Node` is a (Model, index) pair and never copies (SPEC 3.9). Every
// accessor is a kin one (`node-kind`, `node-first-child`, ...): this layer renames and iterates, it decides nothing. A
// Model is read-only after convert and its accessors take a lock, so it may be read from several threads.
//
// The generated classes (`emon.Model` is kin's `emon.model`) are always written fully qualified here: this class's own
// name is the same.

namespace emon.Api;

using System.Collections.Generic;

public sealed class Model {
    internal readonly object Lock = new object();
    internal readonly emon.Enctx.Arenas A;
    internal readonly string EvidenceText;
    internal readonly Intake IntakeInfo;

    internal Model(emon.Enctx.Arenas a, string evidence, Intake intake) {
        this.A = a;
        this.EvidenceText = evidence;
        this.IntakeInfo = intake;
    }

    internal static string TextOf(emon.Port.Cells c, int start, int n) {
        var sb = new System.Text.StringBuilder();
        for (int i = start; i < start + n; i++) {
            emon.Port.TextCp(sb, emon.Model.TextAt(c, i));
        }
        return sb.ToString();
    }

    // ------------------------------------------------------------------ small value types

    /// <summary>A node's kind, by kin's own table (`emon.model` `nk`): `Name()` is the JSON `kind`.</summary>
    public sealed class Kind {
        public readonly int Code;

        public Kind(int code) {
            this.Code = code;
        }

        /// <summary>The JSON `kind`.</summary>
        public string Name() {
            var sb = new System.Text.StringBuilder();
            emon.Model.NkEmitName(sb, Code);
            return sb.ToString();
        }

        /// <summary>The kind with this JSON name (`"paragraph"`), if there is one; null otherwise.</summary>
        public static Kind Named(string name) {
            for (int k = 1; k <= emon.Model.NkInitial(); k++) {
                if (new Kind(k).Name() == name) {
                    return new Kind(k);
                }
            }
            return null;
        }

        public override bool Equals(object o) {
            return o is Kind && ((Kind) o).Code == Code;
        }

        public override int GetHashCode() {
            return Code;
        }
    }

    /// <summary>A page of the Model's page table (SPEC 3.7). Sizes are in centipoints (1/100 point), as displayed.</summary>
    public sealed class Page {
        public int Number;
        public string Label = "";
        public bool HasLabel;
        public int Width, Height, Rotation;
        public string Kind = "";
    }

    /// <summary>A region of a node: a page and a box in centipoints, origin top left of the displayed page.</summary>
    public sealed class Region {
        public readonly int Page, X0, Y0, X1, Y1;

        internal Region(int page, int x0, int y0, int x1, int y1) {
            this.Page = page;
            this.X0 = x0;
            this.Y0 = y0;
            this.X1 = x1;
            this.Y1 = y1;
        }
    }

    /// <summary>Which field of an Attr is meant (kin's `av-*`).</summary>
    public enum AttrKind {
        Int, Text, Node, Bool
    }

    /// <summary>An attribute's typed value.</summary>
    public sealed class Attr {
        public AttrKind Kind = AttrKind.Int;
        public int IntValue;
        public string Text = "";
        public int Node;
        public bool Bool;
    }

    /// <summary>An entry of the ledger of dropped content (SPEC 3.7). Java's nested `Dropped`: renamed, since `Dropped()` is the method.</summary>
    public sealed class DroppedItem {
        public string Reason = "";
        public int Page;
        public Region Region;
        public string Text = "";
    }

    /// <summary>One use of an asset: the node and the page region its pixels map to.</summary>
    public sealed class AssetUse {
        public readonly int Node;
        public readonly Region Region;

        internal AssetUse(int node, Region region) {
            this.Node = node;
            this.Region = region;
        }
    }

    /// <summary>One asset as a file for the caller to write beside the HTML: its name, media type and bytes.</summary>
    public sealed class AssetFile {
        public readonly string Name, MediaType;
        public readonly byte[] Bytes;

        internal AssetFile(string name, string mediaType, byte[] bytes) {
            this.Name = name;
            this.MediaType = mediaType;
            this.Bytes = bytes;
        }
    }

    // ------------------------------------------------------------------ the Model

    /// <summary>The document node.</summary>
    public Node Root() {
        return new Node(this, 0);
    }

    /// <summary>What stage 0 said of the input this Model was made from.</summary>
    public Intake Source() {
        return IntakeInfo;
    }

    /// <summary>The number of nodes.</summary>
    public int NodeCount() {
        lock (Lock) {
            return emon.Model.NodeCount(A.Nodes);
        }
    }

    /// <summary>The page table.</summary>
    public List<Page> Pages() {
        var outp = new List<Page>();
        lock (Lock) {
            for (int p = 0; p < emon.Model.PageCount(A.Pages); p++) {
                int ls = emon.Model.PageLabelStart(A.Pages, p), ll = emon.Model.PageLabelLen(A.Pages, p);
                var kind = new System.Text.StringBuilder();
                emon.Model.PkEmitName(kind, emon.Model.PageKind(A.Pages, p));
                var pg = new Page();
                pg.Number = emon.Model.PageNumber(A.Pages, p);
                pg.Width = emon.Model.PageW(A.Pages, p);
                pg.Height = emon.Model.PageH(A.Pages, p);
                pg.Rotation = emon.Model.PageRot(A.Pages, p);
                pg.Kind = kind.ToString();
                if (ls >= 0) {
                    pg.Label = Model.TextOf(A.Text, ls, ll);
                    pg.HasLabel = true;
                }
                outp.Add(pg);
            }
        }
        return outp;
    }

    /// <summary>The furniture templates (SPEC 3.7): the top-level `furniture` nodes, in order.</summary>
    public List<Node> Furniture() {
        var ids = new List<int>();
        lock (Lock) {
            for (int n = 0; n < emon.Model.NodeCount(A.Nodes); n++) {
                if (emon.Model.NodeKind(A.Nodes, n) == emon.Model.NkFurniture() && emon.Model.NodeParent(A.Nodes, n) == -1) {
                    ids.Add(n);
                }
            }
        }
        return NodesOf(ids);
    }

    internal List<Node> NodesOf(List<int> ids) {
        var outp = new List<Node>(ids.Count);
        foreach (int n in ids) {
            outp.Add(new Node(this, n));
        }
        return outp;
    }

    /// <summary>The ledger of dropped content.</summary>
    public List<DroppedItem> Dropped() {
        var outp = new List<DroppedItem>();
        lock (Lock) {
            emon.Port.Cells l = A.Ledger;
            for (int i = 0; i < emon.Model.LedgerCount(l); i++) {
                var reason = new System.Text.StringBuilder();
                emon.Model.DrEmitName(reason, emon.Model.LedgerReason(l, i));
                int ts = emon.Model.LedgerTextStart(l, i), tl = emon.Model.LedgerTextLen(l, i);
                var d = new DroppedItem();
                d.Reason = reason.ToString();
                d.Page = emon.Model.LedgerPage(l, i);
                d.Region = new Region(emon.Model.LedgerPage(l, i), emon.Model.LedgerX0(l, i), emon.Model.LedgerY0(l, i),
                    emon.Model.LedgerX1(l, i), emon.Model.LedgerY1(l, i));
                if (ts >= 0) {
                    d.Text = Model.TextOf(A.Text, ts, tl);
                }
                outp.Add(d);
            }
        }
        return outp;
    }

    /// <summary>Every node of a kind, in document order (the arena is in preorder: `model-check` says so).</summary>
    public List<Node> Find(Kind kind) {
        var ids = new List<int>();
        lock (Lock) {
            for (int n = 0; n < emon.Model.NodeCount(A.Nodes); n++) {
                if (emon.Model.NodeKind(A.Nodes, n) == kind.Code) {
                    ids.Add(n);
                }
            }
        }
        return NodesOf(ids);
    }

    /// <summary>The text layer's evidence dump, when <c>Options.KeepEvidence</c> asked for it; null otherwise.</summary>
    public string Evidence() {
        return EvidenceText;
    }

    /// <summary>SPEC 4's canonical JSON.</summary>
    public string ToJson(Options.Json o) {
        var sb = new System.Text.StringBuilder();
        lock (Lock) {
            emon.Json.JsEmit(sb, o.Pretty ? 1 : 0, A.Nodes, A.Attrs, A.Regions, A.Text, A.Pages, A.Ledger, A.Layout, A.Assets, A.Uses);
        }
        return sb.ToString();
    }

    /// <summary>SPEC 5's HTML for crawlers.</summary>
    public string ToHtml(Options.Html o) {
        long[] bb = o.Bits();
        var sb = new System.Text.StringBuilder();
        emon.Port.Cells prefix = emon.Port.NewCells();
        foreach (System.Text.Rune r in o.AssetPrefix.EnumerateRunes()) {
            emon.Port.CellsPush(prefix, r.Value);
        }
        lock (Lock) {
            emon.Html.HtmlEmit(sb, (int) bb[0], (int) bb[1], A.Nodes, A.Attrs, A.Regions, A.Text, A.Pages, A.Ledger, A.Layout, A.Assets, A.Uses, A.Abytes, prefix);
        }
        return sb.ToString();
    }

    /// <summary>The same, by an `Output` value.</summary>
    public string Serialize(Options.Output o) {
        if (o.Html != null) {
            return ToHtml(o.Html);
        }
        if (o.Json != null) {
            return ToJson(o.Json);
        }
        return ToJson(new Options.Json());
    }

    // ------------------------------------------------------------------ assets (P3.13, SPEC 10.11, D117)
    //
    // An asset is a picture the Model carries beside its tree: a `figure` or `seal` whose content is one image the
    // document embeds (its original JPEG bytes, or its pixels as the canonical PNG). The bytes live in the Model (kin's
    // `abytes`); `Bytes()` copies them out (the arenas sit behind a lock). Nothing here exists unless `Options.Assets` was on.
    // The view type is the top-level `emon.Api.Asset` (in Asset.cs): a nested `Asset` could not share a name with `Asset(int)`.

    /// <summary>The asset table, in admission order; empty unless <c>Options.Assets</c> was on.</summary>
    public List<Asset> Assets() {
        int n;
        lock (Lock) {
            n = emon.Model.AssetCount(A.Assets);
        }
        var outp = new List<Asset>(n);
        for (int id = 0; id < n; id++) {
            outp.Add(new Asset(this, id));
        }
        return outp;
    }

    /// <summary>The asset with this id, or null.</summary>
    public Asset Asset(int id) {
        int n;
        lock (Lock) {
            n = emon.Model.AssetCount(A.Assets);
        }
        return id < 0 || id >= n ? null : new Asset(this, id);
    }

    /// <summary>The sidecar: every asset's file name, media type and bytes, for the caller to write beside the HTML (`external`).</summary>
    public List<AssetFile> AssetFiles() {
        var outp = new List<AssetFile>();
        foreach (Asset x in Assets()) {
            outp.Add(new AssetFile(x.Name(), x.MediaType(), x.Bytes()));
        }
        return outp;
    }
}
