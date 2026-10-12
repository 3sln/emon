// THE MODEL, Java host (DEVLOG P9.1; SPEC 3.9, 4, 5). The twin of `host/go/model.go` and the Model half of
// `host/rust/src/engine.rs`.
//
// The Model is the seven arenas of SPEC 3.8, owned; `Node` is a (Model, index) pair and never copies (SPEC 3.9). Every
// accessor is a kin one (`node-kind`, `node-first-child`, ...): this layer renames and iterates, it decides nothing. A
// Model is read-only after convert and its accessors take a lock, so it may be read from several threads.
//
// The generated classes (`emon.Model` is kin's `emon.model`) are always written fully qualified here: this class's own
// name is the same.

package emon.api;

import emon.Port;
import java.util.ArrayList;
import java.util.List;

public final class Model {
    final Object lock = new Object();
    final emon.Enctx.Arenas a;
    final String evidence;
    final Intake intake;

    Model(emon.Enctx.Arenas a, String evidence, Intake intake) {
        this.a = a;
        this.evidence = evidence;
        this.intake = intake;
    }

    static String textOf(Port.Cells c, int start, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + n; i++) {
            Port.textCp(sb, emon.Model.textAt(c, i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ small value types

    /** A node's kind, by kin's own table (`emon.model` `nk`): `name()` is the JSON `kind`. */
    public static final class Kind {
        public final int code;

        public Kind(int code) {
            this.code = code;
        }

        /** The JSON `kind`. */
        public String name() {
            StringBuilder sb = new StringBuilder();
            emon.Model.nkEmitName(sb, code);
            return sb.toString();
        }

        /** The kind with this JSON name (`"paragraph"`), if there is one; null otherwise. */
        public static Kind named(String name) {
            for (int k = 1; k <= emon.Model.nkInitial(); k++) {
                if (new Kind(k).name().equals(name)) {
                    return new Kind(k);
                }
            }
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Kind && ((Kind) o).code == code;
        }

        @Override
        public int hashCode() {
            return code;
        }
    }

    /** A page of the Model's page table (SPEC 3.7). Sizes are in centipoints (1/100 point), as displayed. */
    public static final class Page {
        public int number;
        public String label = "";
        public boolean hasLabel;
        public int width, height, rotation;
        public String kind = "";
    }

    /** A region of a node: a page and a box in centipoints, origin top left of the displayed page. */
    public static final class Region {
        public final int page, x0, y0, x1, y1;

        Region(int page, int x0, int y0, int x1, int y1) {
            this.page = page;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }
    }

    /** Which field of an Attr is meant (kin's `av-*`). */
    public enum AttrKind {
        INT, TEXT, NODE, BOOL
    }

    /** An attribute's typed value. */
    public static final class Attr {
        public AttrKind kind = AttrKind.INT;
        public int intValue;
        public String text = "";
        public int node;
        public boolean bool;
    }

    /** An entry of the ledger of dropped content (SPEC 3.7). */
    public static final class Dropped {
        public String reason = "";
        public int page;
        public Region region;
        public String text = "";
    }

    /** One use of an asset: the node and the page region its pixels map to. */
    public static final class AssetUse {
        public final int node;
        public final Region region;

        AssetUse(int node, Region region) {
            this.node = node;
            this.region = region;
        }
    }

    /** One asset as a file for the caller to write beside the HTML: its name, media type and bytes. */
    public static final class AssetFile {
        public final String name, mediaType;
        public final byte[] bytes;

        AssetFile(String name, String mediaType, byte[] bytes) {
            this.name = name;
            this.mediaType = mediaType;
            this.bytes = bytes;
        }
    }

    // ------------------------------------------------------------------ the Model

    /** The document node. */
    public Node root() {
        return new Node(this, 0);
    }

    /** What stage 0 said of the input this Model was made from. */
    public Intake source() {
        return intake;
    }

    /** The number of nodes. */
    public int nodeCount() {
        synchronized (lock) {
            return emon.Model.nodeCount(a.nodes);
        }
    }

    /** The page table. */
    public List<Page> pages() {
        List<Page> out = new ArrayList<>();
        synchronized (lock) {
            for (int p = 0; p < emon.Model.pageCount(a.pages); p++) {
                int ls = emon.Model.pageLabelStart(a.pages, p), ll = emon.Model.pageLabelLen(a.pages, p);
                StringBuilder kind = new StringBuilder();
                emon.Model.pkEmitName(kind, emon.Model.pageKind(a.pages, p));
                Page pg = new Page();
                pg.number = emon.Model.pageNumber(a.pages, p);
                pg.width = emon.Model.pageW(a.pages, p);
                pg.height = emon.Model.pageH(a.pages, p);
                pg.rotation = emon.Model.pageRot(a.pages, p);
                pg.kind = kind.toString();
                if (ls >= 0) {
                    pg.label = Model.textOf(a.text, ls, ll);
                    pg.hasLabel = true;
                }
                out.add(pg);
            }
        }
        return out;
    }

    /** The furniture templates (SPEC 3.7): the top-level `furniture` nodes, in order. */
    public List<Node> furniture() {
        List<Integer> ids = new ArrayList<>();
        synchronized (lock) {
            for (int n = 0; n < emon.Model.nodeCount(a.nodes); n++) {
                if (emon.Model.nodeKind(a.nodes, n) == emon.Model.nkFurniture() && emon.Model.nodeParent(a.nodes, n) == -1) {
                    ids.add(n);
                }
            }
        }
        return nodes(ids);
    }

    List<Node> nodes(List<Integer> ids) {
        List<Node> out = new ArrayList<>(ids.size());
        for (int n : ids) {
            out.add(new Node(this, n));
        }
        return out;
    }

    /** The ledger of dropped content. */
    public List<Dropped> dropped() {
        List<Dropped> out = new ArrayList<>();
        synchronized (lock) {
            Port.Cells l = a.ledger;
            for (int i = 0; i < emon.Model.ledgerCount(l); i++) {
                StringBuilder reason = new StringBuilder();
                emon.Model.drEmitName(reason, emon.Model.ledgerReason(l, i));
                int ts = emon.Model.ledgerTextStart(l, i), tl = emon.Model.ledgerTextLen(l, i);
                Dropped d = new Dropped();
                d.reason = reason.toString();
                d.page = emon.Model.ledgerPage(l, i);
                d.region = new Region(emon.Model.ledgerPage(l, i), emon.Model.ledgerX0(l, i), emon.Model.ledgerY0(l, i),
                    emon.Model.ledgerX1(l, i), emon.Model.ledgerY1(l, i));
                if (ts >= 0) {
                    d.text = Model.textOf(a.text, ts, tl);
                }
                out.add(d);
            }
        }
        return out;
    }

    /** Every node of a kind, in document order (the arena is in preorder: `model-check` says so). */
    public List<Node> find(Kind kind) {
        List<Integer> ids = new ArrayList<>();
        synchronized (lock) {
            for (int n = 0; n < emon.Model.nodeCount(a.nodes); n++) {
                if (emon.Model.nodeKind(a.nodes, n) == kind.code) {
                    ids.add(n);
                }
            }
        }
        return nodes(ids);
    }

    /** The text layer's evidence dump, when {@code Options.keepEvidence} asked for it; null otherwise. */
    public String evidence() {
        return evidence;
    }

    /** SPEC 4's canonical JSON. */
    public String toJson(Options.Json o) {
        StringBuilder sb = new StringBuilder();
        synchronized (lock) {
            emon.Json.jsEmit(sb, o.pretty ? 1 : 0, a.nodes, a.attrs, a.regions, a.text, a.pages, a.ledger, a.layout, a.assets, a.uses);
        }
        return sb.toString();
    }

    /** SPEC 5's HTML for crawlers. */
    public String toHtml(Options.Html o) {
        long[] bb = o.bits();
        StringBuilder sb = new StringBuilder();
        Port.Cells prefix = Port.newCells();
        o.assetPrefix.codePoints().forEach(cp -> Port.cellsPush(prefix, cp));
        synchronized (lock) {
            emon.Html.htmlEmit(sb, (int) bb[0], (int) bb[1], a.nodes, a.attrs, a.regions, a.text, a.pages, a.ledger, a.layout, a.assets, a.uses, a.abytes, prefix);
        }
        return sb.toString();
    }

    /** The same, by an `Output` value. */
    public String serialize(Options.Output o) {
        if (o.html != null) {
            return toHtml(o.html);
        }
        if (o.json != null) {
            return toJson(o.json);
        }
        return toJson(new Options.Json());
    }

    // ------------------------------------------------------------------ assets (P3.13, SPEC 10.11, D117)
    //
    // An asset is a picture the Model carries beside its tree: a `figure` or `seal` whose content is one image the
    // document embeds (its original JPEG bytes, or its pixels as the canonical PNG). The bytes live in the Model (kin's
    // `abytes`); `bytes()` copies them out (the arenas sit behind a lock). Nothing here exists unless `Options.assets` was on.

    /** A view of the Model's asset table (an index, like a Node). */
    public static final class Asset {
        private final Model m;
        private final int id;

        Asset(Model m, int id) {
            this.m = m;
            this.id = id;
        }

        private interface Col {
            int get(Port.Cells c, int i);
        }

        private int num(Col f) {
            synchronized (m.lock) {
                return f.get(m.a.assets, id);
            }
        }

        @Override
        public String toString() {
            return "Asset(" + id + " " + name() + ")";
        }

        /** The asset's id: its index in {@link Model#assets()}, in admission order. */
        public int id() {
            return id;
        }

        /** The file name, `a<id>.jpg` or `a<id>.png`. */
        public String name() {
            return num(emon.Model::assetType) == emon.Model.amtJpeg() ? "a" + id + ".jpg" : "a" + id + ".png";
        }

        /** `image/jpeg` or `image/png`. */
        public String mediaType() {
            StringBuilder sb = new StringBuilder();
            emon.Model.amtEmitName(sb, num(emon.Model::assetType));
            return sb.toString();
        }

        /** `embedded` or `decoded` (`cropped` and `rendered` arrive with the `figures` level). */
        public String origin() {
            StringBuilder sb = new StringBuilder();
            emon.Model.aoEmitName(sb, num(emon.Model::assetOrigin));
            return sb.toString();
        }

        /** What the pixels were before: `dct`, `flate`, `lzw`, `runlength`, `raw`, ... */
        public String from() {
            StringBuilder sb = new StringBuilder();
            emon.Model.afmEmitName(sb, num(emon.Model::assetFrom));
            return sb.toString();
        }

        /** The image XObject's object number, or -1. */
        public int object() {
            return num(emon.Model::assetObject);
        }

        /** The SHA-256 of the encoded bytes, 64 lower-case hex digits. */
        public String sha256() {
            final String digits = "0123456789abcdef";
            StringBuilder sb = new StringBuilder();
            synchronized (m.lock) {
                for (int i = 0; i < 8; i++) {
                    int w = emon.Model.assetSha(m.a.assets, id, i);
                    for (int k = 28; k >= 0; k -= 4) {
                        sb.append(digits.charAt((w >>> k) & 15));
                    }
                }
            }
            return sb.toString();
        }

        public int width() {
            return num(emon.Model::assetW);
        }

        public int height() {
            return num(emon.Model::assetH);
        }

        /** The render's resolution; only a rendered asset has one (0 otherwise). */
        public int dpi() {
            int d = num(emon.Model::assetDpi);
            return d > 0 ? d : 0;
        }

        /** `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap`. */
        public List<String> flags() {
            int bits = num(emon.Model::assetFlags);
            List<String> out = new ArrayList<>();
            for (int b = 0; b < 6; b++) {
                int bit = 1 << b;
                if ((bits & bit) != 0) {
                    StringBuilder sb = new StringBuilder();
                    emon.Model.afgEmitName(sb, bit);
                    out.add(sb.toString());
                }
            }
            return out;
        }

        /** Every node that shows it, with the page region its pixels map to. */
        public List<AssetUse> uses() {
            List<AssetUse> out = new ArrayList<>();
            synchronized (m.lock) {
                emon.Enctx.Arenas x = m.a;
                for (int u = emon.Model.assetUseHead(x.assets, id); u >= 0; u = emon.Model.useNext(x.uses, u)) {
                    out.add(new AssetUse(emon.Model.useNode(x.uses, u), new Region(emon.Model.usePage(x.uses, u), emon.Model.useX0(x.uses, u),
                        emon.Model.useY0(x.uses, u), emon.Model.useX1(x.uses, u), emon.Model.useY1(x.uses, u))));
                }
            }
            return out;
        }

        /** The encoded bytes (a copy: the Model's arenas sit behind a lock). */
        public byte[] bytes() {
            synchronized (m.lock) {
                int s = emon.Model.assetBstart(m.a.assets, id), n = emon.Model.assetBlen(m.a.assets, id);
                byte[] out = new byte[n];
                for (int i = 0; i < n; i++) {
                    out[i] = (byte) Port.bufAt(m.a.abytes, s + i);
                }
                return out;
            }
        }
    }

    /** The asset table, in admission order; empty unless {@code Options.assets} was on. */
    public List<Asset> assets() {
        int n;
        synchronized (lock) {
            n = emon.Model.assetCount(a.assets);
        }
        List<Asset> out = new ArrayList<>(n);
        for (int id = 0; id < n; id++) {
            out.add(new Asset(this, id));
        }
        return out;
    }

    /** The asset with this id, or null. */
    public Asset asset(int id) {
        int n;
        synchronized (lock) {
            n = emon.Model.assetCount(a.assets);
        }
        return id < 0 || id >= n ? null : new Asset(this, id);
    }

    /** The sidecar: every asset's file name, media type and bytes, for the caller to write beside the HTML (`external`). */
    public List<AssetFile> assetFiles() {
        List<AssetFile> out = new ArrayList<>();
        for (Asset x : assets()) {
            out.add(new AssetFile(x.name(), x.mediaType(), x.bytes()));
        }
        return out;
    }
}
