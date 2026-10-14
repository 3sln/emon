// THE JAVA DRIVER (DEVLOG P9.1): a test-only CLI over the Engine API, the twin of `test/driver/go/main.go` and
// `test/driver/rust/main.rs` with the same flags, the same stdout and the same exit codes, so that `bb identity` can hold
// them byte for byte. Its STDOUT is the only thing gates read; stats and errors go to stderr. It is not the library: it
// reads the file itself and hands the Engine bytes, a seekable channel or a spooled stream (D058).
//
//   emon-java [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG]
//             [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium[=LIBDIR]]
//             [--assets=none|images|figures] [--password=TEXT]
//             [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D]
//             [--html-assets=inline|external|omit] [--asset-prefix=P]
//             [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats]
//             [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE
//
//   --source=        which adapter reads FILE: `bytes` (in memory), `seek` and `readerat` (a FileChannel as a
//                    SeekableByteChannel: Java's random-access form, SPEC 13.1), `spool` (an InputStream, spooled)
//   --out=bundle     one conversion, every output, for `bb identity` (see the Rust driver: the sections are the same)
//   --out=pixels     one recording as one line per decode entry (see the Rust driver; `bb image` compares them)
//   --dump=DIR       with --out=pixels, also write each frame's pixels to DIR/frame-N.raw
//   --pdfium[=D]     give the Engine the pdfium `Renderer` (D127) over the Java FFM API, from the pinned build `bb pdfium-fetch`
//                    installs (or the directory D / $EMON_PDFIUM_LIB). LIVE: needs the library; no gate uses it.
//   --tesseract[=D]  give the Engine the Tesseract `Ocr` port (D211) over the Java FFM API, reading models from D (default:
//                    $TESSDATA_PREFIX and the usual places). LIVE: needs the library; no gate uses it.
//   --fake-ports     TEST ONLY (P10.2): a deterministic Renderer and Ocr stand in for pdfium and Tesseract, so the fulfilment path runs on every document with no
//                    engine installed (`bb repeat` holds serial and parallel runs of it to the same bytes)
//   --record=DIR     also write the plan/fulfil transcript to DIR (transcript.edn and PNGs)
//   --replay=DIR     convert from the transcript in DIR (no port is called)
//   --concurrent=N   run N conversions at once on one Engine; all N must give the same answer (`bb repeat`). (It was `--parallel=N` until P10.2: that
//                    is now kin's option, `parallel=N`, N workers inside ONE conversion; `bb repeat` runs both.)
//   --parallel=N     kin's option (SPEC 13, P10.2): spread one conversion's fulfilment over N workers; the output is the serial run's at any N
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.

import emon.Planrun;
import emon.api.EmonError;
import emon.api.Engine;
import emon.api.Input;
import emon.api.Intake;
import emon.api.Model;
import emon.api.Options;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    // `--fake-ports` (P10.2): a deterministic Renderer and Ocr, so that the fulfilment path (a page unit's renders, decodes and OCR, the pages ahead looked at,
    // a round fulfilled by several workers) runs on every document of a gate with no engine installed. The Renderer answers a 20 x 30 RGB page whose pixels are
    // a function of the page, the layer, the resolution and the position; the Ocr answers every request with the word `Hello` in one line, through the kin
    // builders a real engine's adapter uses. Both are pure functions of the request, as the real engines are (the property `bb repeat` holds them to).
    static final class FakeRenderer implements emon.RenderPort.Renderer {
        public emon.Port.Bitmap render(emon.RenderPort.Source input, byte[] password, emon.RenderPort.Request req) {
            byte[] px = new byte[20 * 30 * 3];
            for (int y = 0; y < 30; y++) {
                for (int x = 0; x < 20; x++) {
                    byte v = (byte) (x * 7 + y * 3 + req.layer * 50 + req.page * 11 + req.dpi);
                    px[(y * 20 + x) * 3] = v;
                    px[(y * 20 + x) * 3 + 1] = (byte) (v ^ 0x55);
                    px[(y * 20 + x) * 3 + 2] = (byte) (255 - v);
                }
            }
            emon.Port.Bitmap whole = new emon.Port.Bitmap(20, 30, 3, px);
            try {
                return req.clip == null ? whole : emon.RenderPort.cut(whole, req.clip);
            } catch (emon.RenderPort.RenderError e) {
                throw new IllegalStateException(e);
            }
        }

        public int layers() {
            return 1;
        }

        public boolean exactCrop() {
            return false;
        }
    }

    static final class FakeOcr implements emon.api.Ocr {
        public Planrun.Answer fulfil(Planrun.Request req, java.util.Map<Integer, emon.Port.Bitmap> bm, boolean keep) {
            emon.Port.Cells c = emon.Port.newCells();
            int at = emon.Plan.anOcr(c, req.id());
            int w = emon.Plan.anOcrWord(c, at, 10, 10, 100, 30, 900, 0, 0);
            "Hello".codePoints().forEach(cp -> emon.Plan.anOcrCp(c, w, cp));
            emon.Ocr.oxBegin(c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0);
            emon.Ocr.oxLine(c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6);
            emon.Ocr.oxBlock(c, 10, 10, 100, 30, 0, 0, 0);
            emon.Ocr.oxWord(c, 0, 0, -1, 0);
            emon.Plan.anOcrEnd(c, at);
            return new Planrun.Answer(emon.Port.cellsVec(c));
        }

        public List<String[]> producer() {
            List<String[]> p = new ArrayList<>();
            p.add(new String[] {"engine", "fake"});
            return p;
        }
    }

    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    static PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);

    static void usage(String msg) {
        err.println("emon-java: " + msg);
        err.println("usage: emon-java [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium[=LIBDIR]] [--assets=none|images|figures] [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D] [--html-assets=inline|external|omit] [--asset-prefix=P] [--password=TEXT] [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE");
        throw new Exit(2);
    }

    static Options.Html htmlOptions(String list) {
        Options.Html o = new Options.Html();
        for (String item : list.split(",", -1)) {
            if (item.isEmpty()) {
                continue;
            } else if (item.equals("ids")) {
                o.ids = true;
            } else if (item.equals("geometry")) {
                o.geometry = true;
            } else if (item.equals("ledger")) {
                o.ledger = true;
            } else if (item.equals("pretty")) {
                o.pretty = true;
            } else if (item.equals("drop-furniture")) {
                o.furnitureDrop = true;
            } else if (item.equals("no-milestones")) {
                o.milestones = false;
            } else if (item.equals("initials-plain")) {
                o.initialsPlain = true;
            } else if (item.equals("align-style")) {
                o.alignStyle = true;
            } else if (item.equals("assets=inline")) {
                o.assets = Options.HtmlAssets.INLINE;
            } else if (item.equals("assets=external")) {
                o.assets = Options.HtmlAssets.EXTERNAL;
            } else if (item.equals("assets=omit")) {
                o.assets = Options.HtmlAssets.OMIT;
            } else if (item.startsWith("prefix=")) {
                o.assetPrefix = item.substring("prefix=".length());
            } else if (item.startsWith("confidence=")) {
                try {
                    long n = Long.parseUnsignedLong(item.substring("confidence=".length()));
                    if (n > 0xFFFFFFFFL) {
                        throw new NumberFormatException();
                    }
                    o.confidenceBelow = (int) n;
                } catch (NumberFormatException e) {
                    usage("confidence=N wants a number");
                }
            } else {
                usage("unknown html option " + item);
            }
        }
        return o;
    }

    // the `NAME=VALUE` line of `--NAME=VALUE` when NAME is a key of kin's options grammar (`emon.config`), else null
    static String kinFlag(String a) {
        if (!a.startsWith("--")) {
            return null;
        }
        String flag = a.substring(2);
        int eq = flag.indexOf('=');
        if (eq < 0 || !Options.isKey(flag.substring(0, eq))) {
            return null;
        }
        return flag;
    }

    static Map<String, byte[]> readDir(String dir) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (var ents = Files.list(Path.of(dir))) {
            for (Path p : (Iterable<Path>) ents::iterator) {
                if (Files.isRegularFile(p)) {
                    files.put(p.getFileName().toString(), Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    /** One conversion's whole answer: the text, or the error. */
    static final class Result {
        String text;
        Throwable err;
        RunStatsBox stats;
        Planrun.Transcript trans;
    }

    static final class RunStatsBox {
        emon.api.RunStats s;
    }

    static void fail(String msg, Throwable e) {
        err.println("emon-java: " + msg + ": " + e);
        throw new Exit(1);
    }

    static void errExit(Throwable e) {
        if (e instanceof EmonError) {
            EmonError x = (EmonError) e;
            err.print("error: " + x.name() + "\n" + x.getMessage() + "\n");
            err.flush();
            throw new Exit(x.kind == EmonError.Kind.INTERNAL ? 1 : 4);
        }
        err.print("error: internal\n" + e + "\n");
        err.flush();
        throw new Exit(1);
    }

    static boolean same(Result a, Result b) {
        return a.text.equals(b.text) && (a.err == null) == (b.err == null) && (a.err == null || a.err.getMessage().equals(b.err.getMessage()));
    }

    static void run(String[] args, OutputStream stdout) throws Exception {
        String out = "json";
        boolean pretty = false;
        Options.Html html = new Options.Html();
        int htmlAssets = -1;
        String assetPrefix = "";
        boolean prefixSet = false;
        StringBuilder kinText = new StringBuilder();
        boolean simulateAsync = false, noRetarget = false;
        String password = null;
        String source = "bytes";
        boolean stats = false;
        String file = "", record = "", replay = "", dump = "", assetDir = "";
        boolean tess = false, pdfium = false, fakePorts = false;
        String tessdata = "", pdfiumLib = "";
        int concurrent = 1;
        for (String a : args) {
            if (a.startsWith("--out=")) {
                out = a.substring("--out=".length());
            } else if (a.equals("--pretty")) {
                pretty = true;
            } else if (a.startsWith("--html=")) {
                html = htmlOptions(a.substring("--html=".length()));
            } else if (a.startsWith("--html-assets=")) {
                switch (a.substring("--html-assets=".length())) {
                    case "inline": htmlAssets = Options.HtmlAssets.INLINE.ordinal(); break;
                    case "external": htmlAssets = Options.HtmlAssets.EXTERNAL.ordinal(); break;
                    case "omit": htmlAssets = Options.HtmlAssets.OMIT.ordinal(); break;
                    default: usage("--html-assets is inline, external or omit");
                }
            } else if (a.startsWith("--asset-prefix=")) {
                assetPrefix = a.substring("--asset-prefix=".length());
                prefixSet = true;
            } else if (a.startsWith("--asset-dir=")) {
                assetDir = a.substring("--asset-dir=".length());
            } else if (a.startsWith("--password=")) {
                password = a.substring("--password=".length());
            } else if (a.startsWith("--source=")) {
                source = a.substring("--source=".length());
            } else if (a.equals("--async")) {
                simulateAsync = true;
            } else if (a.equals("--no-retarget")) {
                noRetarget = true;
            } else if (a.equals("--fake-ports")) {
                fakePorts = true;
            } else if (a.equals("--tesseract")) {
                tess = true;
            } else if (a.startsWith("--tesseract=")) {
                tess = true;
                tessdata = a.substring("--tesseract=".length());
            } else if (a.equals("--pdfium")) {
                pdfium = true;
            } else if (a.startsWith("--pdfium=")) {
                pdfium = true;
                pdfiumLib = a.substring("--pdfium=".length());
            } else if (a.equals("--stats")) {
                stats = true;
            } else if (a.startsWith("--dump=")) {
                dump = a.substring("--dump=".length());
            } else if (a.startsWith("--record=")) {
                record = a.substring("--record=".length());
            } else if (a.startsWith("--replay=")) {
                replay = a.substring("--replay=".length());
            } else if (a.startsWith("--concurrent=")) {
                int n = -1;
                try {
                    n = Integer.parseInt(a.substring("--concurrent=".length()));
                } catch (NumberFormatException e) {
                    // falls to the usage below
                }
                if (n < 1) {
                    usage("--concurrent=N wants a positive number");
                }
                concurrent = n;
            } else if (kinFlag(a) != null) {
                // a key of kin's options grammar (`emon.config`): --ocr=never, --asset-dpi=300, --limit.pages=5, --chunk=512, ...
                kinText.append(kinFlag(a)).append('\n');
            } else if (a.startsWith("--")) {
                usage("unknown flag " + a);
            } else if (file.isEmpty()) {
                file = a;
            } else {
                usage("one FILE");
            }
        }
        if (file.isEmpty()) {
            usage("no FILE");
        }
        if (!record.isEmpty() && !replay.isEmpty()) {
            usage("--record and --replay are two ways");
        }
        // THE OPTIONS AND THE ENGINE'S BOUNDS ARE KIN'S GRAMMAR: the flags it knows were collected as `key=value` lines and are parsed once.
        Options.Parsed parsed = null;
        try {
            parsed = Options.parse(kinText.toString());
        } catch (IllegalArgumentException e) {
            usage(e.getMessage());
        }
        Options opts = parsed.options;
        Options.Config cfg = parsed.config;
        cfg.simulateAsync = simulateAsync;
        emon.api.TestHooks.setNoRetarget(opts, noRetarget);
        opts.keepEvidence = out.equals("evidence") || out.equals("bundle");
        if (htmlAssets >= 0) {
            html.assets = Options.HtmlAssets.values()[htmlAssets];
        }
        if (prefixSet) {
            html.assetPrefix = assetPrefix;
        }

        // THE DRIVER READS THE FILE (the library never does): four ways of handing the Engine the same bytes.
        Input input = null;
        Path path = Path.of(file);
        try {
            switch (source) {
                case "bytes":
                    input = Input.bytes(Files.readAllBytes(path));
                    break;
                case "readerat":
                case "seek":
                    input = Input.seekable(FileChannel.open(path, StandardOpenOption.READ));
                    break;
                case "spool": {
                    // a plain InputStream: no seek is used
                    InputStream raw = new FileInputStream(file);
                    InputStream fwd = new InputStream() {
                        public int read() throws IOException {
                            return raw.read();
                        }

                        public int read(byte[] b, int off, int len) throws IOException {
                            return raw.read(b, off, len);
                        }
                    };
                    input = Input.spooled(fwd, 1L << 30);
                    break;
                }
                default:
                    usage("--source is bytes, readerat, seek or spool");
            }
        } catch (IOException e) {
            fail(file, e);
        }
        if (password != null) {
            input = input.withPassword(password.getBytes(StandardCharsets.UTF_8));
        }
        final List<Input> inputs = List.of(input);
        if (tess) {
            try {
                cfg.ocr = emon.ffi.Tess.open(tessdata.isEmpty() ? null : tessdata);
            } catch (emon.ffi.Unavailable e) {
                usage(e.getMessage());
            }
        }
        if (pdfium) {
            try {
                cfg.renderer = emon.ffi.Pdfium.open(pdfiumLib.isEmpty() ? null : pdfiumLib);
            } catch (emon.ffi.Unavailable e) {
                usage(e.getMessage());
            }
        }
        if (fakePorts) {
            if (tess || pdfium) {
                usage("--fake-ports is the stand-in for --tesseract and --pdfium: not with them");
            }
            cfg.ocr = new FakeOcr();
            cfg.renderer = new FakeRenderer();
        }
        final Engine engine = new Engine(cfg);

        if (out.equals("intake")) {
            try {
                for (Intake i : engine.intake(inputs)) {
                    stdout.write((i.line() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (EmonError e) {
                errExit(e);
            }
            return;
        }
        switch (out) {
            case "json":
            case "html":
            case "evidence":
            case "bundle":
            case "pixels":
                break;
            default:
                usage("--out is json, html, evidence, bundle, pixels or intake");
        }

        Planrun.Transcript tin = null;
        if (!replay.isEmpty()) {
            Map<String, byte[]> files = null;
            try {
                files = readDir(replay);
            } catch (IOException e) {
                fail(replay, e);
            }
            try {
                tin = Planrun.fromFiles(files);
            } catch (Planrun.PlanError e) {
                errExit(EmonError.plan(e));
            }
        }

        final String fOut = out, fRecord = record, fDump = dump, fAssetDir = assetDir;
        final boolean fPretty = pretty;
        final Options.Html fHtml = html;
        final Planrun.Transcript fTin = tin;
        final int fConcurrent = concurrent;
        final Options fOpts = opts;
        java.util.function.Supplier<Result> one = () -> {
            Result r = new Result();
            try {
                if (fOut.equals("pixels")) {
                    Engine.Converted c = engine.record(inputs, fOpts);
                    r.text = pixelLines(c.transcript, fDump);
                    return r;
                }
                Model m;
                if (fTin != null) {
                    m = engine.replay(inputs, fOpts, fTin);
                } else if (!fRecord.isEmpty()) {
                    Engine.Converted c = engine.record(inputs, fOpts);
                    m = c.model;
                    r.trans = c.transcript;
                } else {
                    Engine.Converted c = engine.convertStats(inputs, fOpts);
                    m = c.model;
                    r.stats = new RunStatsBox();
                    r.stats.s = c.stats;
                }
                if (!fAssetDir.isEmpty() && fConcurrent == 1) {
                    Files.createDirectories(Path.of(fAssetDir));
                    for (Model.AssetFile f : m.assetFiles()) {
                        Files.write(Path.of(fAssetDir, f.name), f.bytes);
                    }
                }
                r.text = render(engine, inputs, m, fOut, fPretty, fHtml);
            } catch (EmonError e) {
                r.err = e;
                r.text = "";
            } catch (IOException e) {
                fail(fAssetDir, e);
            }
            return r;
        };

        Result[] results = new Result[concurrent];
        if (concurrent == 1) {
            results[0] = one.get();
        } else {
            Thread[] ts = new Thread[concurrent];
            for (int i = 0; i < concurrent; i++) {
                final int k = i;
                ts[i] = new Thread(null, () -> results[k] = one.get(), "conv-" + i, 1L << 29);
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }
        }
        for (int i = 1; i < results.length; i++) {
            Result a = results[0], b = results[i];
            if (!same(a, b)) {
                err.print("error: nondeterministic\nconversion " + i + " of " + concurrent + " differs from conversion 0\n");
                err.flush();
                throw new Exit(1);
            }
        }
        Result r = results[0];
        if (r.err != null) {
            errExit(r.err);
        }
        if (!record.isEmpty()) {
            Files.createDirectories(Path.of(record));
            for (Planrun.File f : r.trans.toFiles()) {
                Files.write(Path.of(record, f.name), f.bytes);
            }
        }
        if (stats) {
            emon.api.RunStats s = r.stats.s;
            err.print("stats: attempts=" + s.attempts + " fetch-rounds=" + s.fetchRounds + " bytes-requests=" + s.bytesRequests
                + " source-reads=" + s.sourceReads + " bytes-fetched=" + s.bytesFetched + " of " + s.inputBytes + " requests=" + s.requests + "\n");
            // P7.S (D210): wall time by stage, microseconds (the Rust driver's `time-stats`, same words)
            err.print("time-stats: render-us=" + s.fulfilUs[1] + " crop-us=" + s.fulfilUs[5] + " decode-us=" + s.fulfilUs[2] + " ocr-first-us=" + s.fulfilUs[3]
                + " ocr-later-us=" + s.fulfilUs[7] + " pass-us=" + s.passUs + " pages-us=" + s.pagesUs + " finish-us=" + s.finishUs + "\n");
            err.flush();
        }
        stdout.write(r.text.getBytes(StandardCharsets.UTF_8));
    }

    /** The `--out=pixels` text of a recording: its decode entries, in order. */
    static String pixelLines(Planrun.Transcript t, String dump) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Planrun.Entry e : t.entries) {
            if (e.req.length < 8 || e.req[1] != 2 || e.req[7] < 0) {
                continue;
            }
            if (e.ans[3] != 0) {
                sb.append("frame ").append(e.req[7]).append(" status ").append(e.ans[3]).append('\n');
                continue;
            }
            long h = 0xcbf29ce484222325L;
            if (e.bitmap != null) {
                for (byte x : e.bitmap.d) {
                    h ^= (x & 0xFFL);
                    h *= 0x100000001b3L;
                }
                if (!dump.isEmpty()) {
                    Files.createDirectories(Path.of(dump));
                    Files.write(Path.of(dump, "frame-" + e.req[7] + ".raw"), e.bitmap.d);
                }
            }
            sb.append(String.format("frame %d w %d h %d ch %d dpi100 %d bilevel %d frames %d fnv %016x\n", e.req[7], e.ans[4], e.ans[5], e.ans[6], e.ans[7], e.ans[8], e.ans[9], h));
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        final String d = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(d.charAt((x >> 4) & 15)).append(d.charAt(x & 15));
        }
        return sb.toString();
    }

    /** The text of `--out=NAME` (or the whole bundle) for a converted Model. */
    static String render(Engine engine, List<Input> inputs, Model m, String out, boolean pretty, Options.Html html) {
        switch (out) {
            case "json": {
                Options.Output o = new Options.Output();
                o.json = new Options.Json(pretty);
                return m.serialize(o);
            }
            case "html": {
                Options.Output o = new Options.Output();
                o.html = html;
                return m.serialize(o);
            }
            case "evidence": {
                String ev = m.evidence();
                return ev == null ? "" : ev;
            }
            default:
                break;
        }
        Options.Html full = new Options.Html();
        full.ids = true;
        full.geometry = true;
        full.confidenceBelow = 950;
        full.ledger = true;
        full.pretty = true;
        String intake;
        try {
            List<String> lines = new ArrayList<>();
            for (Intake i : engine.intake(inputs)) {
                lines.add(i.line());
            }
            intake = String.join("\n", lines);
        } catch (EmonError e) {
            intake = "error: " + e.name();
        }
        String ev = m.evidence();
        if (ev == null) {
            ev = "";
        }
        Options.Html def = new Options.Html();
        StringBuilder s = new StringBuilder();
        s.append("== json\n").append(m.toJson(new Options.Json())).append("\n== json-pretty\n").append(m.toJson(new Options.Json(true)))
            .append("\n== html\n").append(m.toHtml(def)).append("\n== html-full\n").append(m.toHtml(full))
            .append("\n== evidence\n").append(ev).append("\n== intake\n").append(intake).append("\n");
        // SPEC 10.11 / 14.1: with assets on, the HTML in the other two modes and every asset file, byte for byte (hex)
        if (m.root().attr("assets") != null) {
            Options.Html external = new Options.Html();
            external.assets = Options.HtmlAssets.EXTERNAL;
            external.assetPrefix = "assets/";
            Options.Html omit = new Options.Html();
            omit.assets = Options.HtmlAssets.OMIT;
            s.append("== html-external\n").append(m.toHtml(external)).append("\n== html-omit\n").append(m.toHtml(omit)).append("\n== assets\n");
            for (Model.AssetFile f : m.assetFiles()) {
                s.append(f.name).append(' ').append(f.mediaType).append(' ').append(f.bytes.length).append('\n').append(hex(f.bytes)).append('\n');
            }
        }
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        // Deep recursion is how kin's stages are written (Go grows its stacks; the JVM does not): run on a big stack.
        final int[] code = {0};
        Thread t = new Thread(null, () -> {
            try {
                OutputStream stdout = new java.io.BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
                try {
                    run(args, stdout);
                } finally {
                    stdout.flush();
                }
            } catch (Exit e) {
                code[0] = e.code;
            } catch (Throwable e) {
                err.print("error: internal\n" + e + "\n");
                err.flush();
                code[0] = 1;
            }
        }, "emon-main", 1L << 30);
        t.start();
        t.join();
        err.flush();
        System.out.flush();
        System.exit(code[0]);
    }
}
