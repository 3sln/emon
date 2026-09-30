// P2.7 harness, C# arm: kin-generated `emon.Raster` over the `Bitmap` port.
//
//     dotnet run -c Release -- PAGE W H N [port|bulk]
using System.Diagnostics;

var bytes = System.IO.File.ReadAllBytes(args[0]);
int w = int.Parse(args[1]), h = int.Parse(args[2]), n = int.Parse(args[3]);
bool skip = args.Length > 4 && args[4] == "skip";
bool bulk = skip || (args.Length > 4 && args[4] == "bulk");
var g = new emon.Port.Bitmap(w, h, 1, bytes);
var m = emon.Port.BitmapNew(w, h, 1);
var hist = emon.Port.NewCells(); var labels = emon.Port.NewCells();
var parent = emon.Port.NewCells(); var area = emon.Port.NewCells(); var stats = emon.Port.NewCells();
var best = new double[6];
for (int k = 0; k < 6; k++) best[k] = 1e18;
int thr = 0;
double Ms(long a, long b) => (b - a) * 1000.0 / Stopwatch.Frequency;
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
    var ph = new double[] { Ms(t0, t1), Ms(t1, t2), Ms(t2, t3), Ms(t3, t4), Ms(t4, t5), Ms(t0, t5) };
    for (int k = 0; k < 6; k++) if (ph[k] < best[k]) best[k] = ph[k];
}
var names = new[] { "hist", "otsu", "bin", "alloc", "label", "total" };
for (int k = 0; k < 6; k++)
    System.Console.WriteLine("T " + names[k] + " " + best[k].ToString("F3", System.Globalization.CultureInfo.InvariantCulture));
var s = new System.Text.StringBuilder();
for (int i = 0; i < emon.Port.CellsLen(stats); i++) s.Append(' ').Append(emon.Port.CellsAt(stats, i));
System.Console.WriteLine("S " + thr + s);
