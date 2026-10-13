// The alignment DRIVER, C# (DEVLOG P7.1, D278-D280; P9.5, D1240): the REPLAY twin of test/fusion/align/{rust,go,java}, with the same output.
// C# never records here: pdfium and Tesseract are the Rust host's bindings in this gate.
//
//     aligndrv replay DIR PDF
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using emon;

internal static class AlignDrv {
    private static uint Fnv(byte[] b) {
        uint h = 0x811C9DC5;
        foreach (byte x in b) {
            h ^= x;
            h = unchecked(h * 16777619);
        }
        return h;
    }

    private static int[] ToVec(Port.Cells c) {
        int n = Port.CellsLen(c);
        var v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = Port.CellsAt(c, i);
        }
        return v;
    }

    private static void Fail(Planrun.PlanError e) {
        Console.Out.Write("error: " + e.Name + "\n");
        Console.Out.Flush();
        Console.Error.WriteLine(e.Message);
        Environment.Exit(3);
    }

    internal static int Main(string[] args) {
        if (args.Length < 3 || args[0] != "replay") {
            Console.Error.WriteLine("usage: aligndrv replay DIR PDF");
            return 2;
        }
        string dir = args[1], pdf = args[2];
        byte[] pdfBytes = File.ReadAllBytes(pdf);
        if (Plan.PkOcr() != Planrun.KindOcr) {
            throw new InvalidOperationException("the host and emon.plan disagree on the kinds");
        }
        var inputs = new List<int[]> { new[] { 0, pdfBytes.Length } };
        Port.Src baseSrc = Port.SrcOfBlob(pdfBytes);

        var files = new Dictionary<string, byte[]>();
        foreach (string f in Directory.GetFiles(dir)) {
            files[Path.GetFileName(f)] = File.ReadAllBytes(f);
        }
        Planrun.Transcript t;
        string dump = "";
        var m = new Planrun.Machine(new Planrun.Config());
        try {
            t = Planrun.FromFiles(files);
            t.CheckInputs(inputs);
            var replayer = new Planrun.Replayer(t);
            Planrun.Unit unit = (an, rq, bms, payload) => {
                Port.Src view = baseSrc.View();
                var c = new Port.Cells[51];
                for (int i = 0; i < c.Length; i++) {
                    c[i] = Port.NewCells();
                }
                Port.Cells loc = Port.NewCells();
                var d = new StringBuilder();
                int st = Alignprobe.ApUnit(view, an, rq, loc, d, 1,
                    c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16], c[17], c[18], c[19], c[20], c[21], c[22], c[23],
                    c[24], c[25], c[26], c[27], c[28], c[29], c[30], c[31], c[32], c[33], c[34], c[35], c[36], c[37], c[38], c[39], c[40], c[41], c[42], c[43], c[44], c[45], c[46],
                    c[47], c[48], c[49], c[50]);
                if (st == Planrun.UnitDone) {
                    dump = d.ToString();
                }
                return st;
            };
            var bytesF = new Planrun.Sync((req, bm) => {
                Port.Cells c = Port.NewCells();
                Plan.AnBytes(c, req.Id(), req.Field(Plan.BqOffset()), req.Field(Plan.BqLength()));
                return new Planrun.Answer(ToVec(c));
            });
            Planrun.Drive(m, unit, replayer, bytesF, Planrun.NoMisses, null);
            if (replayer.Unused() != 0) {
                replayer.Finish();
            }
        } catch (Planrun.PlanError e) {
            Fail(e);
            return 3;
        }
        Planrun.Stats st2 = m.Stats();
        Console.Error.WriteLine("stats: attempts=" + st2.Attempts + " rounds=" + st2.Rounds + " requests=" + st2.Requests);

        var o = new StringBuilder(dump);
        foreach (Planrun.File f in t.ToFiles()) {
            o.Append("file ").Append(f.Name).Append(": ").Append(f.Bytes.Length).Append(" bytes ").Append(Fnv(f.Bytes).ToString("x8")).Append('\n');
        }
        var stdout = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false));
        stdout.Write(o.ToString());
        stdout.Flush();
        return 0;
    }
}
