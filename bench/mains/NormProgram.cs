// P6.3 harness, C# arm: kin-generated `emon.Norm` over the `Bitmap` port. See norm.rs for the arguments and the output
// (this arm prints the phases and the summary, not the TRUTH counts).
//
//     dotnet cs.dll PAGE W H CH N DPI100
using System.Diagnostics;

var bytes = System.IO.File.ReadAllBytes(args[0]);
int w = int.Parse(args[1]), h = int.Parse(args[2]), ch = int.Parse(args[3]), n = int.Parse(args[4]), dpi100 = int.Parse(args[5]);
var src = new emon.Port.Bitmap(w, h, ch, bytes);
var gray = emon.Port.BitmapNew(w, h, 1); var binary = emon.Port.BitmapNew(w, h, 1); var mask = emon.Port.BitmapNew(w, h, 1);
var ws = emon.Port.NewCells(); var sv = emon.Port.NewCells(); var aux = emon.Port.NewCells();
emon.Port.Cells views = null, inks = null;
string[] names = { "gray", "hist", "layers", "mask", "sauv", "skew", "view" };
var best = new double[7];
for (int k = 0; k < 7; k++) best[k] = 1e18;
int nl = 0;
double Ms(long a, long b) => (b - a) * 1000.0 / Stopwatch.Frequency;
int[] Hash(emon.Port.Bitmap b) {
    var o = emon.Port.NewCells();
    emon.Port.CellsPush(o, 0);
    emon.Port.CellsPush(o, 0);
    emon.Port.BitmapHash(b, o, 0);
    return new[] { emon.Port.CellsAt(o, 0), emon.Port.CellsAt(o, 1) };
}
for (int it = 0; it < n; it++) {
    long t0 = Stopwatch.GetTimestamp();
    emon.Port.BitmapGrayInto(src, gray);
    long t1 = Stopwatch.GetTimestamp();
    emon.Norm.NmWsInit(ws);
    emon.Port.BitmapHistRgb(src, ws, emon.Norm.NmWsHist());
    long t2 = Stopwatch.GetTimestamp();
    emon.Norm.NmLayers(ws, emon.Norm.NmInkMerge(), emon.Norm.NmInkPaper(), emon.Norm.NmInkMin(), emon.Norm.NmInkSpike());
    emon.Norm.NmRoles(ws, emon.Norm.NmInkLight(), emon.Norm.NmInkChroma(), emon.Norm.NmInkBg());
    long t3 = Stopwatch.GetTimestamp();
    emon.Norm.NmMaskInto(src, ws, 1, mask);
    long t4 = Stopwatch.GetTimestamp();
    emon.Norm.NmSauvola(gray, binary, emon.Norm.NmSauvolaR(dpi100), emon.Norm.NmSauvolaK(), sv);
    long t5 = Stopwatch.GetTimestamp();
    emon.Norm.NmDeskew(binary, aux, sv);
    long t6 = Stopwatch.GetTimestamp();
    views = emon.Port.NewCells();
    inks = emon.Port.NewCells();
    emon.Evidence.VwNew(views, 0, emon.Evidence.VkDecode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0);
    nl = emon.Norm.NmView(views, inks, 0, 0, src, gray, binary, ws, sv, aux);
    long t7 = Stopwatch.GetTimestamp();
    var ph = new double[] { Ms(t0, t1), Ms(t1, t2), Ms(t2, t3), Ms(t3, t4), Ms(t4, t5), Ms(t5, t6), Ms(t6, t7) };
    for (int k = 0; k < 7; k++) if (ph[k] < best[k]) best[k] = ph[k];
}
for (int k = 0; k < 7; k++) System.Console.WriteLine("T " + names[k] + " " + best[k].ToString("F3", System.Globalization.CultureInfo.InvariantCulture));
System.Console.WriteLine("S " + nl + " " + emon.Evidence.VwGet(views, 0, emon.Evidence.VwfDeskew()));
for (int i = 0; i < emon.Evidence.IkCount(inks); i++) {
    System.Console.WriteLine("L " + (i + 1) + " " + emon.Evidence.IkGet(inks, i, emon.Evidence.IkfCount()) + " "
        + emon.Evidence.IkGet(inks, i, emon.Evidence.IkfRole()) + " " + emon.Evidence.IkGet(inks, i, emon.Evidence.IkfLight()) + " "
        + emon.Evidence.IkGet(inks, i, emon.Evidence.IkfChroma()) + " " + emon.Evidence.IkGet(inks, i, emon.Evidence.IkfColor()));
}
var g = Hash(gray); var bh = Hash(binary);
System.Console.WriteLine("H gray " + g[0] + " " + g[1]);
System.Console.WriteLine("H binary " + bh[0] + " " + bh[1]);
