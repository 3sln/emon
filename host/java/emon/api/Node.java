// A node: the Model and an index. It borrows, it never copies (SPEC 3.9). The twin of Go's `Node` (`host/go/model.go`).

package emon.api;

import emon.Port;
import java.util.ArrayList;
import java.util.List;

public final class Node {
    private final Model m;
    private final int n;

    Node(Model m, int n) {
        this.m = m;
        this.n = n;
    }

    @Override
    public String toString() {
        return "Node(" + n + " " + kind().name() + ")";
    }

    private interface Col {
        int get(Port.Cells c, int i);
    }

    private int num(Col f) {
        synchronized (m.lock) {
            return f.get(m.a.nodes, n);
        }
    }

    /** The node's index in the Model (kin's `node-*` accessors take it). */
    public int index() {
        return n;
    }

    /** The JSON `id`: the index. Inline nodes carry none in JSON (D052) but have an index here. */
    public int id() {
        return n;
    }

    public Model.Kind kind() {
        return new Model.Kind(num(emon.Model::nodeKind));
    }

    /** The confidence, 0..=1000, or -1 when the node has none. */
    public int conf() {
        return num(emon.Model::nodeConf);
    }

    /** Provenance (SPEC 3.5), as the JSON `src` names, in kin's bit order. */
    public List<String> sources() {
        int bits = num(emon.Model::nodeSrc);
        List<String> out = new ArrayList<>();
        for (int b = 0; b < 9; b++) {
            int bit = 1 << b;
            if ((bits & bit) != 0) {
                StringBuilder sb = new StringBuilder();
                emon.Model.srEmitName(sb, bit);
                out.add(sb.toString());
            }
        }
        return out;
    }

    /** The warning bits (SPEC 3.3), raw: kin's `fl-*`. */
    public int flags() {
        return num(emon.Model::nodeFlags);
    }

    /** The parent node, or null for the root. */
    public Node parent() {
        int p = num(emon.Model::nodeParent);
        return p < 0 ? null : new Node(m, p);
    }

    /** Every child in document order: blocks and inlines alike (`inlines` and `blocks` split them). */
    public List<Node> children() {
        List<Node> out = new ArrayList<>();
        int c = num(emon.Model::nodeFirstChild);
        while (c >= 0) {
            out.add(new Node(m, c));
            int next;
            synchronized (m.lock) {
                next = emon.Model.nodeNext(m.a.nodes, c);
            }
            c = next;
        }
        return out;
    }

    /** Whether the node is an inline. */
    public boolean isInline() {
        return num(emon.Model::nodeIsInline) == 1;
    }

    /** The inline children. */
    public List<Node> inlines() {
        List<Node> out = new ArrayList<>();
        for (Node c : children()) {
            if (c.isInline()) {
                out.add(c);
            }
        }
        return out;
    }

    /** The block children. */
    public List<Node> blocks() {
        List<Node> out = new ArrayList<>();
        for (Node c : children()) {
            if (!c.isInline()) {
                out.add(c);
            }
        }
        return out;
    }

    private String own() {
        if (num(emon.Model::nodeHasText) != 1) {
            return null;
        }
        int st = num(emon.Model::nodeTextStart), ln = num(emon.Model::nodeTextLen);
        synchronized (m.lock) {
            return Model.textOf(m.a.text, st, ln);
        }
    }

    /**
     * The node's plain text: its own run when it has one, else (D052, as the JSON's `text`) the text of its inline
     * children, a line break as a newline, a span or link by descent. Empty for a node with neither.
     */
    public String text() {
        String s = own();
        if (s != null) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        derive(sb);
        return sb.toString();
    }

    private void derive(StringBuilder sb) {
        for (Node c : children()) {
            int k = c.kind().code;
            if (k == emon.Model.nkLinebreak()) {
                sb.append('\n');
            }
            if (emon.Model.nkIsInline(k) == 1) {
                String s = c.own();
                if (s != null) {
                    sb.append(s);
                }
                c.derive(sb);
            }
        }
    }

    /** The regions the node covers, one per page (or column fragment): centipoints. */
    public List<Model.Region> regions() {
        List<Model.Region> out = new ArrayList<>();
        synchronized (m.lock) {
            emon.Enctx.Arenas a = m.a;
            int r = emon.Model.nodeRegionHead(a.nodes, n);
            for (int i = 0; i < emon.Model.nodeRegionCount(a.nodes, n); i++) {
                out.add(new Model.Region(emon.Model.regionPage(a.regions, r), emon.Model.regionX0(a.regions, r), emon.Model.regionY0(a.regions, r),
                    emon.Model.regionX1(a.regions, r), emon.Model.regionY1(a.regions, r)));
                r = emon.Model.regionNext(a.regions, r);
            }
        }
        return out;
    }

    /** An attribute by its JSON name (`level`, `align`, `locale`, ...), typed; null when absent. */
    public Model.Attr attr(String name) {
        synchronized (m.lock) {
            emon.Enctx.Arenas a = m.a;
            int at = emon.Model.nodeAttrHead(a.nodes, n);
            while (at >= 0) {
                StringBuilder key = new StringBuilder();
                emon.Model.fkEmitName(key, emon.Model.attrKey(a.attrs, at));
                if (key.toString().equals(name)) {
                    int v = emon.Model.attrValue(a.attrs, at), l = emon.Model.attrLen(a.attrs, at);
                    Model.Attr out = new Model.Attr();
                    int vk = emon.Model.attrVkind(a.attrs, at);
                    if (vk == emon.Model.avText()) {
                        out.kind = Model.AttrKind.TEXT;
                        out.text = Model.textOf(a.text, v, l);
                    } else if (vk == emon.Model.avNode()) {
                        out.kind = Model.AttrKind.NODE;
                        out.node = v;
                    } else if (vk == emon.Model.avBool()) {
                        out.kind = Model.AttrKind.BOOL;
                        out.bool = v != 0;
                    } else {
                        out.kind = Model.AttrKind.INT;
                        out.intValue = v;
                    }
                    return out;
                }
                at = emon.Model.attrNext(a.attrs, at);
            }
        }
        return null;
    }

    /** The asset this `figure` or `seal` shows, if it has one (SPEC 10.11); null otherwise. */
    public Model.Asset asset() {
        Model.Attr at = attr("asset");
        if (at == null || at.kind != Model.AttrKind.INT) {
            return null;
        }
        return m.asset(at.intValue);
    }

    /**
     * Why it has no asset (`withheld`, `part`, `vector`, `composite`, `colorspace`, `codec`, `render`, `too-large`,
     * `budget`), when {@code Options.assets} was on and it has none; null otherwise.
     */
    public String noAsset() {
        synchronized (m.lock) {
            emon.Enctx.Arenas a = m.a;
            int at = emon.Model.attrFind(a.attrs, emon.Model.nodeAttrHead(a.nodes, n), emon.Model.fkNoasset());
            if (at < 0) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            emon.Model.nzEmitName(sb, emon.Model.attrValue(a.attrs, at));
            return sb.toString();
        }
    }
}
