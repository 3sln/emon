// The Renderer DRIVER, Java (DEVLOG P6.2, D127; P9.5, D494): the replay twin of test/render/{rust,go}/main.*, with the same output.
//
//     renderdrv replay DIR --pdf P... --req SPEC...     the gate: answer from DIR (no pdfium needed)
//
// SPEC is `input:page:dpi:layer` or, for a crop, `input:page:dpi:layer:px,py,pw,ph`. There is no `record` here: recording is `bb render-record`, which
// drives pdfium on Rust and (with --go) Go, live, and is not a gate (the Java and C# hosts record through their own Engines: `emon-java --pdfium`).
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

    /** Spells a record the way Rust's `{:?}` spells a Vec<i32>: [1, 2, 3]. */
    static String ints(int[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(v[i]);
        }
        return s.append("]").toString();
    }

    static void fail(Planrun.PlanError e) {
        System.out.println("error: " + e.name);
        System.err.println(e.getMessage());
        System.out.flush();
        System.exit(3);
    }

    record Spec(int id, int input, int page, int dpi, int layer, int[] clip) {}

    static Spec parseSpec(int id, String s) {
        String[] p = s.split(":");
        int[] clip = null;
        if (p.length > 4) {
            String[] c = p[4].split(",");
            clip = new int[] {Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3])};
        }
        return new Spec(id, Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), clip);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("replay")) {
            System.err.println("usage: renderdrv replay DIR --pdf P... --req SPEC...");
            System.exit(2);
        }
        String dir = args[1];
        List<String> pdfs = new ArrayList<>();
        List<Spec> specs = new ArrayList<>();
        for (int i = 2; i < args.length; i += 2) {
            switch (args[i]) {
                case "--pdf" -> pdfs.add(args[i + 1]);
                case "--req" -> specs.add(parseSpec(specs.size() + 1, args[i + 1]));
                default -> throw new IllegalArgumentException("argument " + args[i]);
            }
        }
        if (Plan.pkRender() != Planrun.KIND_RENDER || Plan.pkCrop() != Planrun.KIND_CROP) {
            throw new IllegalStateException("the host and emon.plan disagree on the kinds");
        }
        List<int[]> inputs = new ArrayList<>();
        for (int i = 0; i < pdfs.size(); i++) {
            inputs.add(new int[] {i, (int) Files.size(Paths.get(pdfs.get(i)))});
        }
        Planrun.Unit unit = (an, rq, bms, payload) -> {
            boolean missing = false;
            for (Spec s : specs) {
                if (Plan.recFind(an, s.id()) < 0) {
                    missing = true;
                    if (s.clip() == null) {
                        Plan.rqRender(rq, s.id(), s.input(), s.page(), s.dpi(), s.layer());
                    } else {
                        Plan.rqCrop(rq, s.id(), s.input(), s.page(), s.dpi(), s.layer(), s.clip()[0], s.clip()[1], s.clip()[2], s.clip()[3]);
                    }
                }
            }
            return missing ? Planrun.UNIT_REQUESTS : Planrun.UNIT_DONE;
        };
        Planrun.Sync bytesF = new Planrun.Sync((req, bm) -> {
            throw Planrun.fulfilFailed("this driver reads no bytes through the loop");
        });
        Planrun.Transcript t;
        Planrun.Replayer replayer;
        Planrun.Machine m = new Planrun.Machine(new Planrun.Config());
        try {
            Map<String, byte[]> files = new HashMap<>();
            try (Stream<Path> es = Files.list(Paths.get(dir))) {
                for (Path e : (Iterable<Path>) es::iterator) {
                    files.put(e.getFileName().toString(), Files.readAllBytes(e));
                }
            }
            t = Planrun.fromFiles(files);
            t.checkInputs(inputs);
            replayer = new Planrun.Replayer(t);
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

        StringBuilder out = new StringBuilder();
        for (Planrun.Entry e : t.entries) {
            String kind = e.req[1] == Planrun.KIND_RENDER ? "render" : e.req[1] == Planrun.KIND_CROP ? "crop" : "other";
            out.append("entry ").append(e.req[2]).append(' ').append(kind).append(": req ").append(ints(e.req)).append(" ans ").append(ints(e.ans)).append('\n');
            Port.Bitmap b = e.bitmap;
            if (b != null) {
                out.append(String.format("bitmap %d: %dx%dx%d %08x%n", e.req[2], b.w, b.h, b.ch, fnv(b.d)));
            }
        }
        for (Planrun.File f : t.toFiles()) {
            out.append(String.format("file %s: %d bytes %08x%n", f.name, f.bytes.length, fnv(f.bytes)));
        }
        System.out.print(out.toString().replace("\r", ""));
        System.out.flush();
    }
}
