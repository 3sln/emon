// A node: the Model and an index. It borrows, it never copies (SPEC 3.9). The twin of Go's `Node` (`host/go/model.go`) and of
// `host/java/emon/api/Node.java`.

namespace emon.Api;

using System.Collections.Generic;

public sealed class Node {
    private readonly Model m;
    private readonly int n;

    internal Node(Model m, int n) {
        this.m = m;
        this.n = n;
    }

    public override string ToString() {
        return "Node(" + n + " " + Kind().Name() + ")";
    }

    private int Num(System.Func<emon.Port.Cells, int, int> f) {
        lock (m.Lock) {
            return f(m.A.Nodes, n);
        }
    }

    /// <summary>The node's index in the Model (kin's `node-*` accessors take it).</summary>
    public int Index() {
        return n;
    }

    /// <summary>The JSON `id`: the index. Inline nodes carry none in JSON (D052) but have an index here.</summary>
    public int Id() {
        return n;
    }

    public Model.Kind Kind() {
        return new Model.Kind(Num(emon.Model.NodeKind));
    }

    /// <summary>The confidence, 0..=1000, or -1 when the node has none.</summary>
    public int Conf() {
        return Num(emon.Model.NodeConf);
    }

    /// <summary>Provenance (SPEC 3.5), as the JSON `src` names, in kin's bit order.</summary>
    public List<string> Sources() {
        int bits = Num(emon.Model.NodeSrc);
        var outp = new List<string>();
        for (int b = 0; b < 9; b++) {
            int bit = 1 << b;
            if ((bits & bit) != 0) {
                var sb = new System.Text.StringBuilder();
                emon.Model.SrEmitName(sb, bit);
                outp.Add(sb.ToString());
            }
        }
        return outp;
    }

    /// <summary>The warning bits (SPEC 3.3), raw: kin's `fl-*`.</summary>
    public int Flags() {
        return Num(emon.Model.NodeFlags);
    }

    /// <summary>The parent node, or null for the root.</summary>
    public Node Parent() {
        int p = Num(emon.Model.NodeParent);
        return p < 0 ? null : new Node(m, p);
    }

    /// <summary>Every child in document order: blocks and inlines alike (`Inlines` and `Blocks` split them).</summary>
    public List<Node> Children() {
        var outp = new List<Node>();
        int c = Num(emon.Model.NodeFirstChild);
        while (c >= 0) {
            outp.Add(new Node(m, c));
            int next;
            lock (m.Lock) {
                next = emon.Model.NodeNext(m.A.Nodes, c);
            }
            c = next;
        }
        return outp;
    }

    /// <summary>Whether the node is an inline.</summary>
    public bool IsInline() {
        return Num(emon.Model.NodeIsInline) == 1;
    }

    /// <summary>The inline children.</summary>
    public List<Node> Inlines() {
        var outp = new List<Node>();
        foreach (Node c in Children()) {
            if (c.IsInline()) {
                outp.Add(c);
            }
        }
        return outp;
    }

    /// <summary>The block children.</summary>
    public List<Node> Blocks() {
        var outp = new List<Node>();
        foreach (Node c in Children()) {
            if (!c.IsInline()) {
                outp.Add(c);
            }
        }
        return outp;
    }

    private string Own() {
        if (Num(emon.Model.NodeHasText) != 1) {
            return null;
        }
        int st = Num(emon.Model.NodeTextStart), ln = Num(emon.Model.NodeTextLen);
        lock (m.Lock) {
            return Model.TextOf(m.A.Text, st, ln);
        }
    }

    /// <summary>
    /// The node's plain text: its own run when it has one, else (D052, as the JSON's `text`) the text of its inline
    /// children, a line break as a newline, a span or link by descent. Empty for a node with neither.
    /// </summary>
    public string Text() {
        string s = Own();
        if (s != null) {
            return s;
        }
        var sb = new System.Text.StringBuilder();
        Derive(sb);
        return sb.ToString();
    }

    private void Derive(System.Text.StringBuilder sb) {
        foreach (Node c in Children()) {
            int k = c.Kind().Code;
            if (k == emon.Model.NkLinebreak()) {
                sb.Append('\n');
            }
            if (emon.Model.NkIsInline(k) == 1) {
                string s = c.Own();
                if (s != null) {
                    sb.Append(s);
                }
                c.Derive(sb);
            }
        }
    }

    /// <summary>The regions the node covers, one per page (or column fragment): centipoints.</summary>
    public List<Model.Region> Regions() {
        var outp = new List<Model.Region>();
        lock (m.Lock) {
            emon.Enctx.Arenas a = m.A;
            int r = emon.Model.NodeRegionHead(a.Nodes, n);
            for (int i = 0; i < emon.Model.NodeRegionCount(a.Nodes, n); i++) {
                outp.Add(new Model.Region(emon.Model.RegionPage(a.Regions, r), emon.Model.RegionX0(a.Regions, r), emon.Model.RegionY0(a.Regions, r),
                    emon.Model.RegionX1(a.Regions, r), emon.Model.RegionY1(a.Regions, r)));
                r = emon.Model.RegionNext(a.Regions, r);
            }
        }
        return outp;
    }

    /// <summary>An attribute by its JSON name (`level`, `align`, `locale`, ...), typed; null when absent.</summary>
    public Model.Attr Attr(string name) {
        lock (m.Lock) {
            emon.Enctx.Arenas a = m.A;
            int at = emon.Model.NodeAttrHead(a.Nodes, n);
            while (at >= 0) {
                var key = new System.Text.StringBuilder();
                emon.Model.FkEmitName(key, emon.Model.AttrKey(a.Attrs, at));
                if (key.ToString() == name) {
                    int v = emon.Model.AttrValue(a.Attrs, at), l = emon.Model.AttrLen(a.Attrs, at);
                    var outp = new Model.Attr();
                    int vk = emon.Model.AttrVkind(a.Attrs, at);
                    if (vk == emon.Model.AvText()) {
                        outp.Kind = Model.AttrKind.Text;
                        outp.Text = Model.TextOf(a.Text, v, l);
                    } else if (vk == emon.Model.AvNode()) {
                        outp.Kind = Model.AttrKind.Node;
                        outp.Node = v;
                    } else if (vk == emon.Model.AvBool()) {
                        outp.Kind = Model.AttrKind.Bool;
                        outp.Bool = v != 0;
                    } else {
                        outp.Kind = Model.AttrKind.Int;
                        outp.IntValue = v;
                    }
                    return outp;
                }
                at = emon.Model.AttrNext(a.Attrs, at);
            }
        }
        return null;
    }

    /// <summary>The asset this `figure` or `seal` shows, if it has one (SPEC 10.11); null otherwise.</summary>
    public Asset Asset() {
        Model.Attr at = Attr("asset");
        if (at == null || at.Kind != Model.AttrKind.Int) {
            return null;
        }
        return m.Asset(at.IntValue);
    }

    /// <summary>
    /// Why it has no asset (`withheld`, `part`, `vector`, `composite`, `colorspace`, `codec`, `render`, `too-large`,
    /// `budget`), when <c>Options.Assets</c> was on and it has none; null otherwise. (Java's `noAsset()`.)
    /// </summary>
    public string NoAsset() {
        lock (m.Lock) {
            emon.Enctx.Arenas a = m.A;
            int at = emon.Model.AttrFind(a.Attrs, emon.Model.NodeAttrHead(a.Nodes, n), emon.Model.FkNoasset());
            if (at < 0) {
                return null;
            }
            var sb = new System.Text.StringBuilder();
            emon.Model.NzEmitName(sb, emon.Model.AttrValue(a.Attrs, at));
            return sb.ToString();
        }
    }
}
