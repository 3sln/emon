// P6.3 harness, Java arm: kin-generated `emon.Norm` over the `Bitmap` port. See norm.rs for the arguments and the output
// (this arm prints the phases and the summary, not the TRUTH counts).
//
//     java Norm PAGE W H CH N DPI100
public class Norm {
    static double ms(long a, long b) { return (b - a) / 1e6; }

    static int[] hash(emon.Port.Bitmap b) {
        emon.Port.Cells o = emon.Port.newCells();
        emon.Port.cellsPush(o, 0);
        emon.Port.cellsPush(o, 0);
        emon.Port.bitmapHash(b, o, 0);
        return new int[] { emon.Port.cellsAt(o, 0), emon.Port.cellsAt(o, 1) };
    }

    public static void main(String[] a) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[0]));
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]), ch = Integer.parseInt(a[3]);
        int n = Integer.parseInt(a[4]), dpi100 = Integer.parseInt(a[5]);
        emon.Port.Bitmap src = new emon.Port.Bitmap(w, h, ch, bytes);
        emon.Port.Bitmap gray = emon.Port.bitmapNew(w, h, 1), binary = emon.Port.bitmapNew(w, h, 1), mask = emon.Port.bitmapNew(w, h, 1);
        emon.Port.Cells ws = emon.Port.newCells(), sv = emon.Port.newCells(), aux = emon.Port.newCells();
        emon.Port.Cells views = null, inks = null;
        String[] names = { "gray", "hist", "layers", "mask", "sauv", "skew", "view" };
        double[] best = new double[7];
        java.util.Arrays.fill(best, 1e18);
        int nl = 0;
        for (int it = 0; it < n; it++) {
            long t0 = System.nanoTime();
            emon.Port.bitmapGrayInto(src, gray);
            long t1 = System.nanoTime();
            emon.Norm.nmWsInit(ws);
            emon.Port.bitmapHistRgb(src, ws, emon.Norm.nmWsHist());
            long t2 = System.nanoTime();
            emon.Norm.nmLayers(ws, emon.Norm.nmInkMerge(), emon.Norm.nmInkPaper(), emon.Norm.nmInkMin(), emon.Norm.nmInkSpike());
            emon.Norm.nmRoles(ws, emon.Norm.nmInkLight(), emon.Norm.nmInkChroma(), emon.Norm.nmInkBg());
            long t3 = System.nanoTime();
            emon.Norm.nmMaskInto(src, ws, 1, mask);
            long t4 = System.nanoTime();
            emon.Norm.nmSauvola(gray, binary, emon.Norm.nmSauvolaR(dpi100), emon.Norm.nmSauvolaK(), sv);
            long t5 = System.nanoTime();
            emon.Norm.nmDeskew(binary, aux, sv);
            long t6 = System.nanoTime();
            views = emon.Port.newCells();
            inks = emon.Port.newCells();
            emon.Evidence.vwNew(views, 0, emon.Evidence.vkDecode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0);
            nl = emon.Norm.nmView(views, inks, 0, 0, src, gray, binary, ws, sv, aux);
            long t7 = System.nanoTime();
            double[] ph = { ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t5, t6), ms(t6, t7) };
            for (int k = 0; k < 7; k++) if (ph[k] < best[k]) best[k] = ph[k];
        }
        for (int k = 0; k < 7; k++) System.out.println("T " + names[k] + " " + String.format("%.3f", best[k]));
        System.out.println("S " + nl + " " + emon.Evidence.vwGet(views, 0, emon.Evidence.vwfDeskew()));
        for (int i = 0; i < emon.Evidence.ikCount(inks); i++) {
            System.out.println("L " + (i + 1) + " " + emon.Evidence.ikGet(inks, i, emon.Evidence.ikfCount()) + " "
                + emon.Evidence.ikGet(inks, i, emon.Evidence.ikfRole()) + " " + emon.Evidence.ikGet(inks, i, emon.Evidence.ikfLight()) + " "
                + emon.Evidence.ikGet(inks, i, emon.Evidence.ikfChroma()) + " " + emon.Evidence.ikGet(inks, i, emon.Evidence.ikfColor()));
        }
        int[] g = hash(gray), b = hash(binary);
        System.out.println("H gray " + g[0] + " " + g[1]);
        System.out.println("H binary " + b[0] + " " + b[1]);
    }
}
