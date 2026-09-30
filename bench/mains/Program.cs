// P2.7 harness, C# arm: kin-generated `emon.Raster` over the `Bitmap` port.
//
//     dotnet cs.dll PAGE W H N [port|bulk|skip]
using System.Diagnostics;

var bytes = System.IO.File.ReadAllBytes(args[0]);
int w = int.Parse(args[1]), h = int.Parse(args[2]), n = int.Parse(args[3]);
bool skip = args.Length > 4 && args[4] == "skip";
bool bulk = skip || (args.Length > 4 && args[4] == "bulk");
var g = new emon.Port.Bitmap(w, h, 1, bytes);
var m = emon.Port.BitmapNew(w, h, 1);
var m2 = emon.Port.BitmapNew(w, h, 1);
var hist = emon.Port.NewCells(); var labels = emon.Port.NewCells();
var parent = emon.Port.NewCells(); var area = emon.Port.NewCells();
var stats = emon.Port.NewCells(); var stats2 = emon.Port.NewCells();
var isum = emon.Port.NewCells(); var isq = emon.Port.NewCells(); var sqt = emon.Port.NewCells();
emon.Raster.RasSqrtTable(sqt);
var best = new double[11];
for (int k = 0; k < 11; k++) best[k] = 1e18;
int thr = 0, ink2 = 0;
double Ms(long a, long b) => (b - a) * 1000.0 / Stopwatch.Frequency;
string Stat(emon.Port.Cells c) {
    var s = new System.Text.StringBuilder();
    for (int i = 0; i < emon.Port.CellsLen(c); i++) s.Append(' ').Append(emon.Port.CellsAt(c, i));
    return s.ToString();
}
for (int it = 0; it < n; it++) {
    long t0 = Stopwatch.GetTimestamp();
    if (bulk) emon.Raster.RasHistogramBulk(g, hist); else emon.Raster.RasHistogram(g, hist);
    long t1 = Stopwatch.GetTimestamp();
    thr = emon.Raster.RasOtsu(hist, w * h);
    long t2 = Stopwatch.GetTimestamp();
    if (bulk) emon.Raster.RasBinariseBulk(g, m, thr); else emon.Raster.RasBinarise(g, m, thr);
    long t3 = Stopwatch.GetTimestamp();
    if (bulk) emon.Raster.RasAllocBulk(labels, w * h); else emon.Raster.RasAlloc(labels, w * h);
    long t4 = Stopwatch.GetTimestamp();
    if (skip) emon.Raster.RasLabelSkip(m, labels, parent, area, stats); else emon.Raster.RasLabel(m, labels, parent, area, stats);
    long t5 = Stopwatch.GetTimestamp();
    if (bulk) emon.Raster.RasIntegralBulk(g, isum, isq); else emon.Raster.RasIntegral(g, isum, isq);
    long t6 = Stopwatch.GetTimestamp();
    ink2 = emon.Raster.RasSauvola(g, isum, isq, sqt, m2);
    long t7 = Stopwatch.GetTimestamp();
    if (bulk) emon.Raster.RasAllocBulk(labels, w * h); else emon.Raster.RasAlloc(labels, w * h);
    long t8 = Stopwatch.GetTimestamp();
    if (skip) emon.Raster.RasLabelSkip(m2, labels, parent, area, stats2); else emon.Raster.RasLabel(m2, labels, parent, area, stats2);
    long t9 = Stopwatch.GetTimestamp();
    var ph = new double[] { Ms(t0, t1), Ms(t1, t2), Ms(t2, t3), Ms(t3, t4), Ms(t4, t5), Ms(t0, t5),
                            Ms(t5, t6), Ms(t6, t7), Ms(t7, t8), Ms(t8, t9), Ms(t5, t9) };
    for (int k = 0; k < 11; k++) if (ph[k] < best[k]) best[k] = ph[k];
}
var names = new[] { "hist", "otsu", "bin", "alloc", "label", "total", "integ", "sauv", "alloc2", "label2", "total2" };
for (int k = 0; k < 11; k++)
    System.Console.WriteLine("T " + names[k] + " " + best[k].ToString("F3", System.Globalization.CultureInfo.InvariantCulture));
System.Console.WriteLine("S " + thr + Stat(stats));
System.Console.WriteLine("S2 " + ink2 + Stat(stats2));
