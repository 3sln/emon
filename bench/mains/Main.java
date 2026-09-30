// P2.7 harness, Java arm: kin-generated `emon.Raster` over the `Bitmap` port.
//
//     java Main PAGE W H N [port|bulk|skip]
public class Main {
    static double ms(long a, long b) { return (b - a) / 1e6; }

    static String stat(emon.Port.Cells c) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < emon.Port.cellsLen(c); i++) s.append(' ').append(emon.Port.cellsAt(c, i));
        return s.toString();
    }

    public static void main(String[] a) throws Exception {
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[0]));
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]), n = Integer.parseInt(a[3]);
        boolean skip = a.length > 4 && a[4].equals("skip");
        boolean bulk = skip || (a.length > 4 && a[4].equals("bulk"));
        emon.Port.Bitmap g = new emon.Port.Bitmap(w, h, 1, bytes);
        emon.Port.Bitmap m = emon.Port.bitmapNew(w, h, 1);
        emon.Port.Bitmap m2 = emon.Port.bitmapNew(w, h, 1);
        emon.Port.Cells hist = emon.Port.newCells(), labels = emon.Port.newCells(),
            parent = emon.Port.newCells(), area = emon.Port.newCells(), stats = emon.Port.newCells(),
            stats2 = emon.Port.newCells(), isum = emon.Port.newCells(), isq = emon.Port.newCells(),
            sqt = emon.Port.newCells();
        emon.Raster.rasSqrtTable(sqt);
        double[] best = new double[11];
        java.util.Arrays.fill(best, 1e18);
        int thr = 0, ink2 = 0;
        for (int it = 0; it < n; it++) {
            long t0 = System.nanoTime();
            if (bulk) emon.Raster.rasHistogramBulk(g, hist); else emon.Raster.rasHistogram(g, hist);
            long t1 = System.nanoTime();
            thr = emon.Raster.rasOtsu(hist, w * h);
            long t2 = System.nanoTime();
            if (bulk) emon.Raster.rasBinariseBulk(g, m, thr); else emon.Raster.rasBinarise(g, m, thr);
            long t3 = System.nanoTime();
            if (bulk) emon.Raster.rasAllocBulk(labels, w * h); else emon.Raster.rasAlloc(labels, w * h);
            long t4 = System.nanoTime();
            if (skip) emon.Raster.rasLabelSkip(m, labels, parent, area, stats); else emon.Raster.rasLabel(m, labels, parent, area, stats);
            long t5 = System.nanoTime();
            if (bulk) emon.Raster.rasIntegralBulk(g, isum, isq); else emon.Raster.rasIntegral(g, isum, isq);
            long t6 = System.nanoTime();
            ink2 = emon.Raster.rasSauvola(g, isum, isq, sqt, m2);
            long t7 = System.nanoTime();
            if (bulk) emon.Raster.rasAllocBulk(labels, w * h); else emon.Raster.rasAlloc(labels, w * h);
            long t8 = System.nanoTime();
            if (skip) emon.Raster.rasLabelSkip(m2, labels, parent, area, stats2); else emon.Raster.rasLabel(m2, labels, parent, area, stats2);
            long t9 = System.nanoTime();
            double[] ph = { ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t0, t5),
                            ms(t5, t6), ms(t6, t7), ms(t7, t8), ms(t8, t9), ms(t5, t9) };
            for (int k = 0; k < 11; k++) if (ph[k] < best[k]) best[k] = ph[k];
        }
        String[] names = { "hist", "otsu", "bin", "alloc", "label", "total", "integ", "sauv", "alloc2", "label2", "total2" };
        for (int k = 0; k < 11; k++) System.out.println("T " + names[k] + " " + String.format("%.3f", best[k]));
        System.out.println("S " + thr + stat(stats));
        System.out.println("S2 " + ink2 + stat(stats2));
    }
}
