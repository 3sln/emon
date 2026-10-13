// The alignment DRIVER, Java (DEVLOG P7.1, D278-D280; P9.5, D1240): the REPLAY twin of test/fusion/align/{rust,go}, with the same output.
// Java never records here: pdfium and Tesseract are the Rust host's bindings in this gate (a transcript recorded there replays here to the identical
// correspondence groups).
//
//     aligndrv replay DIR PDF
import emon.Alignprobe;
import emon.Plan;
import emon.Planrun;
import emon.Port;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class Main {
    static long fnv(byte[] b) {
        int h = 0x811C9DC5;
        for (byte x : b) {
            h ^= (x & 0xFF);
            h *= 16777619;
        }
        return h & 0xFFFFFFFFL;
    }

    static int[] toVec(Port.Cells c) {
        int n = Port.cellsLen(c);
        int[] v = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = Port.cellsAt(c, i);
        }
        return v;
    }

    static void fail(Planrun.PlanError e) {
        System.out.println("error: " + e.name);   // (an error name is ASCII)
        System.err.println(e.getMessage());
        System.out.flush();
        System.exit(3);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !args[0].equals("replay")) {
            System.err.println("usage: aligndrv replay DIR PDF");
            System.exit(2);
        }
        String dir = args[1], pdf = args[2];
        byte[] pdfBytes = Files.readAllBytes(Paths.get(pdf));
        if (Plan.pkOcr() != Planrun.KIND_OCR) {
            throw new IllegalStateException("the host and emon.plan disagree on the kinds");
        }
        List<int[]> inputs = new ArrayList<>();
        inputs.add(new int[] {0, pdfBytes.length});
        Port.Src base = Port.srcOfBlob(pdfBytes);

        Map<String, byte[]> files = new HashMap<>();
        try (Stream<Path> es = Files.list(Paths.get(dir))) {
            for (Path e : (Iterable<Path>) es::iterator) {
                files.put(e.getFileName().toString(), Files.readAllBytes(e));
            }
        }
        Planrun.Transcript t;
        Planrun.Replayer replayer;
        String[] dump = {""};
        Planrun.Machine m = new Planrun.Machine(new Planrun.Config());
        try {
            t = Planrun.fromFiles(files);
            t.checkInputs(inputs);
            replayer = new Planrun.Replayer(t);
            Planrun.Unit unit = (an, rq, bms, payload) -> {
                Port.Src view = base.view();
                Port.Cells[] c = new Port.Cells[51];
                for (int i = 0; i < c.length; i++) {
                    c[i] = Port.newCells();
                }
                Port.Cells loc = Port.newCells();
                StringBuilder d = new StringBuilder();
                int st = Alignprobe.apUnit(view, an, rq, loc, d, 1,
                    c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16], c[17], c[18], c[19], c[20], c[21], c[22], c[23],
                    c[24], c[25], c[26], c[27], c[28], c[29], c[30], c[31], c[32], c[33], c[34], c[35], c[36], c[37], c[38], c[39], c[40], c[41], c[42], c[43], c[44], c[45], c[46],
                    c[47], c[48], c[49], c[50]);
                if (st == Planrun.UNIT_DONE) {
                    dump[0] = d.toString();
                }
                return st;
            };
            Planrun.Sync bytesF = new Planrun.Sync((req, bm) -> {
                Port.Cells c = Port.newCells();
                Plan.anBytes(c, req.id(), req.field(Plan.bqOffset()), req.field(Plan.bqLength()));
                return new Planrun.Answer(toVec(c));
            });
            Planrun.drive(m, unit, replayer, bytesF, Planrun.NO_MISSES, null);
            if (replayer.unused() != 0) {
                replayer.finish();
            }
        } catch (Planrun.PlanError e) {
            fail(e);
            return;
        }
        Planrun.Stats st = m.stats();
        System.err.println("stats: attempts=" + st.attempts + " rounds=" + st.rounds + " requests=" + st.requests);

        StringBuilder out = new StringBuilder(dump[0]);
        for (Planrun.File f : t.toFiles()) {
            out.append(String.format("file %s: %d bytes %08x%n", f.name, f.bytes.length, fnv(f.bytes)));
        }
        // UTF-8 whatever the locale says (the dump has U+FFFD words and Hebrew)
        java.io.PrintStream ps = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8");
        ps.print(out);
        ps.flush();
    }
}
