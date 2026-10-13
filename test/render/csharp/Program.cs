// The Renderer DRIVER, C# (DEVLOG P6.2, D127; P9.5, D494): the replay twin of test/render/{rust,go,java}, with the same output.
//
//     renderdrv replay DIR --pdf P... --req SPEC...     the gate: answer from DIR (no pdfium needed)
//
// SPEC is `input:page:dpi:layer` or, for a crop, `input:page:dpi:layer:px,py,pw,ph`. There is no `record` here: recording is `bb render-record`.
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using emon;

internal static class RenderDrv {
    private sealed class Spec {
        internal int Id, Input, Page, Dpi, Layer;
        internal int[] Clip;
    }

    private static uint Fnv(byte[] b) {
        uint h = 0x811C9DC5;
        foreach (byte x in b) {
            h ^= x;
            h = unchecked(h * 16777619);
        }
        return h;
    }

    /// <summary>Spells a record the way Rust's `{:?}` spells a Vec&lt;i32&gt;: [1, 2, 3].</summary>
    private static string Ints(int[] v) => "[" + string.Join(", ", v) + "]";

    private static void Fail(Planrun.PlanError e) {
        Console.Out.Write("error: " + e.Name + "\n");
        Console.Out.Flush();
        Console.Error.WriteLine(e.Message);
        Environment.Exit(3);
    }

    private static Spec ParseSpec(int id, string s) {
        string[] p = s.Split(':');
        var sp = new Spec { Id = id, Input = int.Parse(p[0]), Page = int.Parse(p[1]), Dpi = int.Parse(p[2]), Layer = int.Parse(p[3]) };
        if (p.Length > 4) {
            string[] c = p[4].Split(',');
            sp.Clip = new[] { int.Parse(c[0]), int.Parse(c[1]), int.Parse(c[2]), int.Parse(c[3]) };
        }
        return sp;
    }

    internal static int Main(string[] args) {
        if (args.Length < 2 || args[0] != "replay") {
            Console.Error.WriteLine("usage: renderdrv replay DIR --pdf P... --req SPEC...");
            return 2;
        }
        string dir = args[1];
        var pdfs = new List<string>();
        var specs = new List<Spec>();
        for (int i = 2; i < args.Length; i += 2) {
            switch (args[i]) {
                case "--pdf": pdfs.Add(args[i + 1]); break;
                case "--req": specs.Add(ParseSpec(specs.Count + 1, args[i + 1])); break;
                default: throw new ArgumentException("argument " + args[i]);
            }
        }
        if (Plan.PkRender() != Planrun.KindRender || Plan.PkCrop() != Planrun.KindCrop) {
            throw new InvalidOperationException("the host and emon.plan disagree on the kinds");
        }
        var inputs = new List<int[]>();
        for (int i = 0; i < pdfs.Count; i++) {
            inputs.Add(new[] { i, (int) new FileInfo(pdfs[i]).Length });
        }
        Planrun.Unit unit = (an, rq, bms, payload) => {
            bool missing = false;
            foreach (Spec s in specs) {
                if (Plan.RecFind(an, s.Id) < 0) {
                    missing = true;
                    if (s.Clip == null) {
                        Plan.RqRender(rq, s.Id, s.Input, s.Page, s.Dpi, s.Layer);
                    } else {
                        Plan.RqCrop(rq, s.Id, s.Input, s.Page, s.Dpi, s.Layer, s.Clip[0], s.Clip[1], s.Clip[2], s.Clip[3]);
                    }
                }
            }
            return missing ? Planrun.UnitRequests : Planrun.UnitDone;
        };
        var bytesF = new Planrun.Sync((req, bm) => throw Planrun.FulfilFailed("this driver reads no bytes through the loop"));
        Planrun.Transcript t;
        var m = new Planrun.Machine(new Planrun.Config());
        try {
            var files = new Dictionary<string, byte[]>();
            foreach (string f in Directory.GetFiles(dir)) {
                files[Path.GetFileName(f)] = File.ReadAllBytes(f);
            }
            t = Planrun.FromFiles(files);
            t.CheckInputs(inputs);
            var replayer = new Planrun.Replayer(t);
            Planrun.Drive(m, unit, replayer, bytesF, Planrun.NoMisses, null);
            if (replayer.Unused() != 0) {
                replayer.Finish();
            }
        } catch (Planrun.PlanError e) {
            Fail(e);
            return 3;
        }
        Planrun.Stats st = m.Stats();
        Console.Error.WriteLine("stats: attempts=" + st.Attempts + " rounds=" + st.Rounds + " requests=" + st.Requests);

        var o = new StringBuilder();
        foreach (Planrun.Entry e in t.Entries) {
            string kind = e.Req[1] == Planrun.KindRender ? "render" : e.Req[1] == Planrun.KindCrop ? "crop" : "other";
            o.Append("entry ").Append(e.Req[2]).Append(' ').Append(kind).Append(": req ").Append(Ints(e.Req)).Append(" ans ").Append(Ints(e.Ans)).Append('\n');
            Port.Bitmap b = e.Bitmap;
            if (b != null) {
                o.Append("bitmap ").Append(e.Req[2]).Append(": ").Append(b.W).Append('x').Append(b.H).Append('x').Append(b.Ch).Append(' ').Append(Fnv(b.D).ToString("x8")).Append('\n');
            }
        }
        foreach (Planrun.File f in t.ToFiles()) {
            o.Append("file ").Append(f.Name).Append(": ").Append(f.Bytes.Length).Append(" bytes ").Append(Fnv(f.Bytes).ToString("x8")).Append('\n');
        }
        var stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        stdout.Write(o.ToString());
        stdout.Flush();
        return 0;
    }
}
