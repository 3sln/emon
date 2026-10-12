// A view of the Model's asset table (an index, like a Node): SPEC 3.9, 10.11, D117. Java nests it as `Model.Asset`; here it is
// top-level, since `Model.Asset(int)` is the accessor and C# does not let a type and a method share a name in one class.

namespace emon.Api;

using System.Collections.Generic;

public sealed class Asset {
    private readonly Model m;
    private readonly int id;

    internal Asset(Model m, int id) {
        this.m = m;
        this.id = id;
    }

    private int Num(System.Func<emon.Port.Cells, int, int> f) {
        lock (m.Lock) {
            return f(m.A.Assets, id);
        }
    }

    public override string ToString() {
        return "Asset(" + id + " " + Name() + ")";
    }

    /// <summary>The asset's id: its index in <c>Model.Assets()</c>, in admission order.</summary>
    public int Id() {
        return id;
    }

    /// <summary>The file name, `a&lt;id&gt;.jpg` or `a&lt;id&gt;.png`.</summary>
    public string Name() {
        return Num(emon.Model.AssetType) == emon.Model.AmtJpeg() ? "a" + id + ".jpg" : "a" + id + ".png";
    }

    /// <summary>`image/jpeg` or `image/png`.</summary>
    public string MediaType() {
        var sb = new System.Text.StringBuilder();
        emon.Model.AmtEmitName(sb, Num(emon.Model.AssetType));
        return sb.ToString();
    }

    /// <summary>`embedded` or `decoded` (`cropped` and `rendered` arrive with the `figures` level).</summary>
    public string Origin() {
        var sb = new System.Text.StringBuilder();
        emon.Model.AoEmitName(sb, Num(emon.Model.AssetOrigin));
        return sb.ToString();
    }

    /// <summary>What the pixels were before: `dct`, `flate`, `lzw`, `runlength`, `raw`, ...</summary>
    public string From() {
        var sb = new System.Text.StringBuilder();
        emon.Model.AfmEmitName(sb, Num(emon.Model.AssetFrom));
        return sb.ToString();
    }

    /// <summary>The image XObject's object number, or -1. (Java's `object()`.)</summary>
    public int Object() {
        return Num(emon.Model.AssetObject);
    }

    /// <summary>The SHA-256 of the encoded bytes, 64 lower-case hex digits.</summary>
    public string Sha256() {
        const string digits = "0123456789abcdef";
        var sb = new System.Text.StringBuilder();
        lock (m.Lock) {
            for (int i = 0; i < 8; i++) {
                int w = emon.Model.AssetSha(m.A.Assets, id, i);
                for (int k = 28; k >= 0; k -= 4) {
                    sb.Append(digits[(int) (((uint) w >> k) & 15)]);
                }
            }
        }
        return sb.ToString();
    }

    public int Width() {
        return Num(emon.Model.AssetW);
    }

    public int Height() {
        return Num(emon.Model.AssetH);
    }

    /// <summary>The render's resolution; only a rendered asset has one (0 otherwise).</summary>
    public int Dpi() {
        int d = Num(emon.Model.AssetDpi);
        return d > 0 ? d : 0;
    }

    /// <summary>`overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap`.</summary>
    public List<string> Flags() {
        int bits = Num(emon.Model.AssetFlags);
        var outp = new List<string>();
        for (int b = 0; b < 6; b++) {
            int bit = 1 << b;
            if ((bits & bit) != 0) {
                var sb = new System.Text.StringBuilder();
                emon.Model.AfgEmitName(sb, bit);
                outp.Add(sb.ToString());
            }
        }
        return outp;
    }

    /// <summary>Every node that shows it, with the page region its pixels map to.</summary>
    public List<Model.AssetUse> Uses() {
        var outp = new List<Model.AssetUse>();
        lock (m.Lock) {
            emon.Enctx.Arenas x = m.A;
            for (int u = emon.Model.AssetUseHead(x.Assets, id); u >= 0; u = emon.Model.UseNext(x.Uses, u)) {
                outp.Add(new Model.AssetUse(emon.Model.UseNode(x.Uses, u), new Model.Region(emon.Model.UsePage(x.Uses, u), emon.Model.UseX0(x.Uses, u),
                    emon.Model.UseY0(x.Uses, u), emon.Model.UseX1(x.Uses, u), emon.Model.UseY1(x.Uses, u))));
            }
        }
        return outp;
    }

    /// <summary>The encoded bytes (a copy: the Model's arenas sit behind a lock).</summary>
    public byte[] Bytes() {
        lock (m.Lock) {
            int s = emon.Model.AssetBstart(m.A.Assets, id), n = emon.Model.AssetBlen(m.A.Assets, id);
            byte[] outp = new byte[n];
            for (int i = 0; i < n; i++) {
                outp[i] = (byte) emon.Port.BufAt(m.A.Abytes, s + i);
            }
            return outp;
        }
    }
}
