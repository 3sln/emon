// The Engine API's native tests, Java (DEVLOG P9.1), run by `bb slice` (`kin/scripts/slice`) against the assembled tree
// (`kin/scripts/emon-java-build`): the twin of `host/go/engine_test.go` and `host/rust/tests/engine.rs`, test for test (this
// machine has a JDK and no JUnit, D044: a plain runner). They read fixtures from the repository's `test/`: a TEST may read
// files, the library may not (D058). `EMON_ROOT` is the repository root (default: the working directory).
//
//   java -cp .cache/emon-java/classes EngineTest          prints `ok NAME` per test and `EngineTest: N passed` ; exit 1 on a failure
//
// What they hold: the byte-source adapters give IDENTICAL JSON and HTML; a 512-byte ASYNC source (misses, fetch rounds,
// re-runs) gives the in-memory run's output over the slice's fixtures AND every PDF fixture; D040's encryption errors; the
// input-bytes limit is checked against the SIZE before any byte is read; a source that lies about its size is
// `source-changed`; several inputs as one document are refused; record and replay; the walkable Model, read from many
// threads; raster intake (an image is pages, its pixels are recorded and replayed without a codec, an OCR port reads it);
// assets (P3.13); the Renderer port's fulfiller (P6.2, D127).

import emon.Planrun;
import emon.Port;
import emon.RenderPort;
import emon.api.EmonError;
import emon.api.Engine;
import emon.api.ImgDec;
import emon.api.Input;
import emon.api.Intake;
import emon.api.Model;
import emon.api.Node;
import emon.api.Ocr;
import emon.api.Options;
import emon.api.ByteSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EngineTest {
    static final Path ROOT = Path.of(System.getenv("EMON_ROOT") != null ? System.getenv("EMON_ROOT") : ".");
    static final String[] SLICE = {"h-plain", "h-keyword", "h-inverted-numbered", "h-numbered", "initial-image", "initial-vector", "furn-informative"};
    static int failures = 0;

    interface T {
        void run() throws Exception;
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }

    static byte[] read(String rel) {
        try {
            return Files.readAllBytes(ROOT.resolve(rel));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static byte[] synth(String name) {
        return read("test/corpus/synth/pdf/" + name + ".pdf");
    }

    static List<Input> one(Input in) {
        return List.of(in);
    }

    static String js(Model m) {
        return m.toJson(new Options.Json(true));
    }

    static String flat(Model m) {
        return m.toJson(new Options.Json());
    }

    static Engine asyncEngine(int chunk) {
        Options.Config c = Options.Config.defaults();
        c.chunkSize = chunk;
        c.simulateAsync = true;
        return new Engine(c);
    }

    static Engine plain() {
        return new Engine(Options.Config.defaults());
    }

    static String errName(Throwable e) {
        return e instanceof EmonError ? ((EmonError) e).name() : e == null ? "" : "?" + e;
    }

    /** What convert says of b: {json, errname}. */
    static String[] resultOf(Engine e, byte[] b, String pw) {
        Input in = Input.bytes(b);
        if (pw != null) {
            in = in.withPassword(pw.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return new String[] {flat(e.convert(one(in), Options.defaults())), ""};
        } catch (EmonError x) {
            return new String[] {"", x.name()};
        }
    }

    static String errOf(Engine e, List<Input> in, Options o) {
        try {
            e.convert(in, o);
            return "";
        } catch (EmonError x) {
            return x.name();
        }
    }

    /** A seekable channel over memory (what `Input.seekable` is given). */
    static SeekableByteChannel channel(byte[] b) {
        return new java.nio.channels.SeekableByteChannel() {
            long pos = 0;

            public int read(java.nio.ByteBuffer dst) {
                if (pos >= b.length) {
                    return -1;
                }
                int n = (int) Math.min(dst.remaining(), b.length - pos);
                dst.put(b, (int) pos, n);
                pos += n;
                return n;
            }

            public int write(java.nio.ByteBuffer src) {
                throw new UnsupportedOperationException();
            }

            public long position() {
                return pos;
            }

            public SeekableByteChannel position(long p) {
                pos = p;
                return this;
            }

            public long size() {
                return b.length;
            }

            public SeekableByteChannel truncate(long s) {
                throw new UnsupportedOperationException();
            }

            public boolean isOpen() {
                return true;
            }

            public void close() {
            }
        };
    }

    static void adapters() throws Exception {
        for (String name : SLICE) {
            byte[] b = synth(name);
            Engine e = plain();
            Options o = Options.defaults();
            Model by = e.convert(one(Input.bytes(b)), o);
            Options.Config tiny = Options.Config.defaults();
            tiny.chunkSize = 64;
            Map<String, Input> sections = new HashMap<>();
            sections.put("seek", Input.seekable(channel(b)));
            sections.put("spool", Input.spooled(new ByteArrayInputStream(b), 1L << 30));
            sections.put("copy", Input.of(ByteSource.copyOf(b)));
            sections.put("buffer", Input.bytes(java.nio.ByteBuffer.wrap(b)));
            String want = js(by), wantHtml = by.toHtml(new Options.Html());
            check(want.contains("\"paragraph\""), name + " has paragraphs");
            for (Map.Entry<String, Input> s : sections.entrySet()) {
                Model m = e.convert(one(s.getValue()), o);
                check(js(m).equals(want), name + ": " + s.getKey() + " JSON differs");
                check(m.toHtml(new Options.Html()).equals(wantHtml), name + ": " + s.getKey() + " HTML differs");
            }
            // a seekable source through a tiny SYNC cache: chunks filled inline, no miss ever
            Model m = new Engine(tiny).convert(one(Input.seekable(channel(b))), o);
            check(js(m).equals(want), name + ": sync chunks differ");
        }
    }

    static void async512() throws Exception {
        for (String name : SLICE) {
            byte[] b = synth(name);
            Options o = Options.defaults();
            Engine.Converted mem = plain().convertStats(one(Input.bytes(b)), o);
            check(mem.stats.attempts == 1 && mem.stats.fetchRounds == 0, "in memory: one attempt, no fetch");
            Engine.Converted asy = asyncEngine(512).convertStats(one(Input.bytes(b)), o);
            check(js(asy.model).equals(js(mem.model)) && asy.model.toHtml(new Options.Html()).equals(mem.model.toHtml(new Options.Html())), name + ": async differs from memory");
            check(asy.stats.fetchRounds >= 1 && asy.stats.attempts == asy.stats.fetchRounds + 1, name + ": it missed and re-ran");
            check(asy.stats.bytesFetched <= b.length && asy.stats.fetchRounds <= 24, name + ": never more than the input, inside FETCH_ROUNDS");
            Model seek = asyncEngine(512).convert(one(Input.seekable(channel(b))), o);
            check(js(seek).equals(js(mem.model)), name + ": async over a channel differs");
        }
    }

    static void everyPdfFixture() throws Exception {
        Engine mem = plain(), asy = asyncEngine(512);
        List<Path> files = new ArrayList<>();
        for (String dir : new String[] {"test/pdf", "test/pdf/p3", "test/content", "test/fuzz"}) {
            Path d = ROOT.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (var s = Files.list(d)) {
                s.filter(p -> p.getFileName().toString().endsWith(".pdf")).forEach(files::add);
            }
        }
        files.sort(null);
        check(files.size() > 60, "found the fixtures: " + files.size());
        int ok = 0;
        for (Path f : files) {
            byte[] b = Files.readAllBytes(f);
            String[] a1 = resultOf(mem, b, null), a2 = resultOf(asy, b, null);
            check(a1[0].equals(a2[0]) && a1[1].equals(a2[1]), f + ": in memory and async 512 differ (" + a1[1] + " vs " + a2[1] + ")");
            if (a1[1].isEmpty()) {
                ok++;
            }
        }
        check(ok > 20, "only " + ok + " converted");
    }

    static void encryption() throws Exception {
        Engine e = plain();
        String[] plainR = resultOf(e, read("test/pdf/p3/plain.pdf"), null);
        check(plainR[1].isEmpty(), "plain converts");
        byte[] locked = read("test/pdf/p3/crypt-aes-128-user.pdf");
        check(resultOf(e, locked, null)[1].equals("password-required"), "no password");
        check(resultOf(e, locked, "wrong-password")[1].equals("password-incorrect"), "wrong password");
        String[] u = resultOf(e, locked, "user-secret");
        check(u[1].isEmpty() && u[0].equals(plainR[0]), "the user password: the plain twin's Model");
        String[] ow = resultOf(e, locked, "owner-secret");
        check(ow[1].isEmpty() && ow[0].equals(plainR[0]), "the owner password too");
        String[] em = resultOf(e, read("test/pdf/p3/crypt-aes-128-empty.pdf"), null);
        check(em[1].isEmpty() && em[0].equals(plainR[0]), "the empty user password is tried by itself");
        check(resultOf(e, read("test/pdf/p3/unsupported-pubsec.pdf"), null)[1].equals("encryption-unsupported"), "pubsec");
        Engine asy = asyncEngine(512);
        byte[] r6 = read("test/pdf/p3/crypt-aes-256-r6-user.pdf");
        check(resultOf(asy, r6, null)[1].equals("password-required"), "async, no password");
        check(resultOf(asy, r6, "nope")[1].equals("password-incorrect"), "async, wrong password");
        check(!Input.bytes(new byte[] {1}).withPassword("hunter2".getBytes(StandardCharsets.UTF_8)).toString().contains("hunter2"), "the password is in the Input's toString");
    }

    /** A source that must not be read: a size check that happens first never reads it. */
    static final class Unreadable implements ByteSource {
        final long n;

        Unreadable(long n) {
            this.n = n;
        }

        public long size() {
            return n;
        }

        public int readAt(long off, byte[] buf, int boff, int len) {
            throw new IllegalStateException("a source over the input-bytes limit was read");
        }
    }

    /** A source whose size says more than it has. */
    static final class Liar implements ByteSource {
        final byte[] b;
        final long n;

        Liar(byte[] b, long n) {
            this.b = b;
            this.n = n;
        }

        public long size() {
            return n;
        }

        public int readAt(long off, byte[] buf, int boff, int len) {
            if (off >= b.length) {
                return 0;
            }
            int k = (int) Math.min(len, b.length - off);
            System.arraycopy(b, (int) off, buf, boff, k);
            return k;
        }
    }

    static void limits() throws Exception {
        Options.Config c = Options.Config.defaults();
        c.limits = new int[][] {{0, 1000}};
        try {
            new Engine(c).convert(one(Input.of(new Unreadable(5000))), Options.defaults());
            check(false, "limit 1000, size 5000");
        } catch (EmonError e) {
            check(e.kind == EmonError.Kind.LIMIT && e.name().equals("input-bytes") && e.limit == 1000 && e.actual == 5000, "limit 1000, size 5000: " + e.getMessage());
        }
        check(errOf(plain(), one(Input.of(new Unreadable(5L << 30))), Options.defaults()).equals("input-bytes"), "5 GiB");
        try {
            Input.spooled(new ByteArrayInputStream(new byte[5000]), 1000);
            check(false, "spool");
        } catch (IOException e) {
            check(e.getMessage().startsWith("input-bytes"), "spool: " + e.getMessage());
        }
        // a limit deeper in kin is named too (pages), and does not leak into the next conversion
        Options.Config few = Options.Config.defaults();
        few.limits = new int[][] {{8, 1}};
        byte[] p3 = read("test/pdf/p3/plain.pdf");
        check(errOf(new Engine(few), one(Input.bytes(p3)), Options.defaults()).equals("pages"), "pages limit");
        check(errOf(plain(), one(Input.bytes(p3)), Options.defaults()).isEmpty(), "the limit leaked");
        // a breach deep in kin arrives as the typed payload (P10.10, D435): the name is kin's table's, the bound the override's, the number reached
        // the document's; a limit set in the options text reaches the same place
        Options.Parsed lim = Options.parse("limit.objects=10\n");
        try {
            new Engine(lim.config).convert(one(Input.bytes(p3)), lim.options);
            check(false, "limit.objects=10");
        } catch (EmonError x) {
            check(x.kind == EmonError.Kind.LIMIT && x.name().equals("objects") && x.limit == 10 && x.actual > 10, "limit.objects=10: " + x.getMessage());
        }
        try {
            Options.parse("limit.nonsense=10\n");
            check(false, "an unknown limit");
        } catch (IllegalArgumentException x) {
            check(!x.getMessage().isEmpty() && Options.isKey("limit.pages") && !Options.isKey("limit-pages"), "kin's grammar refuses an unknown limit: " + x.getMessage());
        }
        // the pixels bound is a named limit, checked from the header before a pixel is decoded
        Options.Config px = Options.Config.defaults();
        px.limits = new int[][] {{9, 1000}};
        check(errOf(new Engine(px), one(Input.bytes(read("test/image/png-rgb8.png"))), Options.defaults()).equals("pixels"), "the pixels bound");
    }

    static void liar() throws Exception {
        byte[] b = synth("h-plain");
        Options.Config c = Options.Config.defaults();
        c.chunkSize = 4096;
        check(errOf(new Engine(c), one(Input.of(new Liar(b, b.length + 10000))), Options.defaults()).equals("source-changed"), "sync liar");
        check(errOf(asyncEngine(512), one(Input.of(new Liar(b, b.length + 10000))), Options.defaults()).equals("source-changed"), "async liar");
    }

    static void refused() throws Exception {
        Engine e = plain();
        byte[] b = synth("h-plain");
        check(errOf(e, List.of(Input.bytes(b), Input.bytes(b)), Options.defaults()).equals("unsupported"), "two inputs");
        check(errOf(e, List.of(), Options.defaults()).equals("no-input"), "no input");
        check(errOf(e, one(Input.bytes(read("test/pdf/notpdf.pdf"))), Options.defaults()).equals("unsupported-format"), "not a PDF");
        Options always = Options.defaults(), never = Options.defaults();
        always.ocr = Options.OcrPolicy.ALWAYS;
        never.ocr = Options.OcrPolicy.NEVER;
        check(js(e.convert(one(Input.bytes(b)), always)).equals(js(e.convert(one(Input.bytes(b)), never))), "the OCR policy changed a born-digital Model");
        // images
        for (String[] t : new String[][] {{"tiff-jpeg.tif", "unsupported-image"}, {"jpeg-cmyk.jpg", "unsupported-image"},
            {"png-truncated.png", "undecodable"}, {"tiff-truncated.tif", "undecodable"}}) {
            check(errOf(e, one(Input.bytes(read("test/image/" + t[0]))), Options.defaults()).equals(t[1]), t[0] + " is " + t[1]);
        }
    }

    static void intake() throws Exception {
        Engine e = plain();
        List<Intake> v = e.intake(List.of(Input.bytes(synth("h-plain")), Input.bytes(read("test/pdf/p3/crypt-aes-128-empty.pdf"))));
        check(v.size() == 2 && v.get(0).format.equals("pdf") && v.get(0).pages == 2 && !v.get(0).encrypted && v.get(1).pages == 3 && v.get(1).encrypted, "intake of two");
        try {
            e.intake(one(Input.bytes(read("test/pdf/p3/crypt-aes-128-user.pdf"))));
            check(false, "locked intake");
        } catch (EmonError x) {
            check(x.name().equals("password-required"), "locked: " + x.name());
        }
        Model m = e.convert(one(Input.bytes(synth("h-plain"))), Options.defaults());
        check(m.source().pages == 2 && !m.source().encrypted, "the Model says its source");
        // stage 0 of an image reads no pixels
        List<Intake> iv = e.intake(List.of(Input.bytes(read("test/image/tiff-multi3-g4.tif")), Input.bytes(read("test/image/jpeg-420.jpg")), Input.bytes(synth("h-plain"))));
        check(iv.get(0).format.equals("tiff") && iv.get(0).pages == 3 && iv.get(1).format.equals("jpeg") && iv.get(1).pages == 1 && iv.get(2).format.equals("pdf") && iv.get(2).pages == 2, "image intake");
    }

    static void recordReplay() throws Exception {
        Engine e = plain();
        byte[] b = synth("h-plain");
        Engine.Converted rec = e.record(one(Input.bytes(b)), Options.defaults());
        Planrun.Transcript tr = rec.transcript;
        // under `always` (the default, P7.S, D200) a born-digital page asks for its two rasters, the page with its text and without it; with no
        // Renderer each is answered `unsupported` and transcribed (two pages, four entries), and nothing is OCR'd
        check(tr.entries.size() == 4, "a born-digital page asks for its rasters under always: " + tr.entries.size() + " entries");
        // under `never` it asks for nothing
        Options never = Options.defaults();
        never.ocr = Options.OcrPolicy.NEVER;
        check(e.record(one(Input.bytes(b)), never).transcript.entries.isEmpty(), "under never: no entries");
        check(tr.inputs.size() == 1 && tr.inputs.get(0)[0] == 0 && tr.inputs.get(0)[1] == b.length, "inputs");
        Model r = e.replay(one(Input.bytes(b)), Options.defaults(), tr);
        check(js(r).equals(js(rec.model)), "replay differs from record");
        try {
            e.replay(one(Input.bytes(synth("h-keyword"))), Options.defaults(), tr);
            check(false, "another input's transcript");
        } catch (EmonError x) {
            check(x.name().equals("transcript-bad"), "another input's transcript: " + x.name());
        }
    }

    static void walk() throws Exception {
        Engine e = plain();
        Options o = Options.defaults();
        o.locale = "en-GB";
        Model m = e.convert(one(Input.bytes(synth("h-plain"))), o);
        Node root = m.root();
        check(root.kind().name().equals("document"), "root kind");
        Model.Attr a = root.attr("locale");
        check(a != null && a.kind == Model.AttrKind.TEXT && a.text.equals("en-GB"), "locale");
        check(flat(m).contains("\"locale\":\"en-GB\""), "the JSON has the locale");
        List<Node> top = root.children();
        check(!top.isEmpty() && top.get(0).kind().name().equals("section"), "top");
        int sec = top.get(0).index();
        List<Node> kids = top.get(0).children();
        check(kids.size() > 2 && kids.get(0).kind().name().equals("heading") && kids.get(0).text().equals("Field Notes on Soil Moisture"), "children");
        check(kids.get(0).sources().equals(List.of("text", "infer")), "sources");
        check(kids.get(0).conf() == 500, "conf");
        check(kids.get(0).parent() != null && kids.get(0).parent().index() == sec, "parent");
        check(root.parent() == null, "the root has a parent");
        List<Model.Region> rg = kids.get(0).regions();
        check(rg.size() == 1 && rg.get(0).page == 1 && rg.get(0).x0 == 7200 && rg.get(0).y0 == 9781, "regions");
        Model.Kind para = Model.Kind.named("paragraph");
        check(para != null && Model.Kind.named("nonsense") == null, "Kind.named");
        List<Node> all = m.find(para);
        for (int i = 1; i < all.size(); i++) {
            check(all.get(i - 1).index() < all.get(i).index(), "document order");
        }
        List<Model.Page> pages = m.pages();
        check(pages.size() == 2 && pages.get(0).number == 1 && pages.get(0).width == 61200 && pages.get(0).height == 79200 && pages.get(0).kind.equals("vector"), "pages");
        check(m.furniture().isEmpty() && m.dropped().isEmpty(), "furniture or dropped in a plain page");
        String j = flat(m);
        for (Node k : kids) {
            if (!k.kind().equals(para)) {
                continue;
            }
            String q = k.text().replace("\\", "\\\\").replace("\"", "\\\"");
            check(j.contains("\"text\":\"" + q + "\""), "JSON lacks " + k.text());
        }
        Options o2 = Options.defaults();
        o2.keepEvidence = true;
        String ev = e.convert(one(Input.bytes(synth("h-plain"))), o2).evidence();
        check(ev != null && ev.startsWith("{\"emon-evidence\""), "evidence");
        check(m.evidence() == null, "evidence kept without asking");
    }

    static void manyThreads() throws Exception {
        Model m = plain().convert(one(Input.bytes(synth("h-plain"))), Options.defaults());
        String want = flat(m);
        String[] got = new String[8];
        Thread[] ts = new Thread[8];
        for (int i = 0; i < 8; i++) {
            final int k = i;
            ts[i] = new Thread(null, () -> {
                for (Node c : m.root().children()) {
                    c.text();
                }
                got[k] = flat(m);
            }, "t" + i, 1L << 26);
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        for (String g : got) {
            check(want.equals(g), "a concurrent read differs");
        }
    }

    static void images() throws Exception {
        Engine e = plain();
        Model m = e.convert(one(Input.bytes(read("test/image/png-phys300.png"))), Options.defaults());
        List<Model.Page> pages = m.pages();
        check(pages.size() == 1 && pages.get(0).kind.equals("image"), "image pages");
        check(pages.get(0).width == 1152 && pages.get(0).height == 864, "page size " + pages.get(0).width + "x" + pages.get(0).height);
        check(m.source().format.equals("png"), "format");
        String j = flat(m);
        check(j.contains("\"source\":{\"format\":\"png\",\"pages\":1}") && j.contains("\"dpi\":300,\"px\":[48,36]") && !j.contains("dpi-unknown"), "json: " + j);
        m = e.convert(one(Input.bytes(read("test/image/png-rgb8.png"))), Options.defaults());
        check(flat(m).contains("dpi-unknown"), "an image with no resolution is flagged");
        m = e.convert(one(Input.bytes(read("test/image/tiff-multi3-rgb-lzw.tif"))), Options.defaults());
        int[][] want = {{1, 1152, 864}, {2, 960, 720}, {3, 792, 600}};
        List<Model.Page> ps = m.pages();
        check(ps.size() == 3, "three frames");
        for (int i = 0; i < Math.min(3, ps.size()); i++) {
            check(ps.get(i).number == want[i][0] && ps.get(i).width == want[i][1] && ps.get(i).height == want[i][2], "frame " + i);
        }
        for (Node c : m.root().children()) {
            check(c.kind().name().equals("break"), "an image page has a " + c.kind().name() + " with no OCR port");
        }
        // an image through a source that misses is the in-memory one
        for (String name : new String[] {"png-rgba8.png", "jpeg-444.jpg", "tiff-multi3-g4.tif", "tiff-tiled16.tif"}) {
            byte[] b = read("test/image/" + name);
            Model a = e.convert(one(Input.bytes(b)), Options.defaults());
            Model c = asyncEngine(64).convert(one(Input.bytes(b)), Options.defaults());
            check(js(a).equals(js(c)), name + ": the async run differs");
        }
    }

    static Map<String, byte[]> files(Planrun.Transcript tr) {
        Map<String, byte[]> m = new HashMap<>();
        for (Planrun.File f : tr.toFiles()) {
            m.put(f.name, f.bytes);
        }
        return m;
    }

    static void imageRecordReplay() throws Exception {
        Engine e = plain();
        byte[] in = read("test/image/tiff-multi3-gray-none.tif");
        Engine.Converted rec = e.record(one(Input.bytes(in)), Options.defaults());
        Planrun.Transcript tr = rec.transcript;
        check(tr.entries.size() == 9, "entries: " + tr.entries.size());
        int[] ids = {1, 2, 3, 260, 272, 516, 528, 772, 784};
        int[][] want = {{0, 2}, {1, 2}, {1, 2}, {0, 2}, {1, 3}, {0, 2}, {1, 3}, {0, 2}, {1, 3}};
        for (int i = 0; i < Math.min(9, tr.entries.size()); i++) {
            Planrun.Entry en = tr.entries.get(i);
            check(en.round == want[i][0] && en.req[1] == want[i][1] && en.req[2] == ids[i], "entry " + i + ": round " + en.round + " kind " + en.req[1] + " id " + en.req[2]);
            check((en.req[1] == 2 && en.req[7] >= 0) == (en.bitmap != null), "entry " + i + ": a decode keeps its pixels and an unsupported OCR has none");
            check(en.req[1] != 3 || en.ans[3] == 2, "entry " + i + ": OCR without a port is `unsupported`");
        }
        Planrun.Transcript t2 = Planrun.fromFiles(files(tr));
        Model r = e.replay(one(Input.bytes(in)), Options.defaults(), t2);
        check(js(r).equals(js(rec.model)), "the replay differs from the recording");
        try {
            e.replay(one(Input.bytes(read("test/image/tiff-multi3-rgb-lzw.tif"))), Options.defaults(), t2);
            check(false, "a replay of another image did not miss");
        } catch (EmonError x) {
            // the request is not in the transcript
        }
        check(js(e.convert(one(Input.bytes(in)), Options.defaults())).equals(js(rec.model)), "convert differs from record");
    }

    /** A FAKE `Ocr` port: it answers every request with the word `Hello` in one line, through the same kin builders a real engine's adapter uses. */
    static final class FakeOcr implements Ocr {
        public Planrun.Answer fulfil(Planrun.Request req, Map<Integer, Port.Bitmap> bm, boolean keep) {
            Port.Cells c = Port.newCells();
            int at = emon.Plan.anOcr(c, req.id());
            int w = emon.Plan.anOcrWord(c, at, 10, 10, 100, 30, 900, 0, 0);
            "Hello".codePoints().forEach(cp -> emon.Plan.anOcrCp(c, w, cp));
            emon.Ocr.oxBegin(c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0);
            emon.Ocr.oxLine(c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6);
            emon.Ocr.oxBlock(c, 10, 10, 100, 30, 0, 0, 0);
            emon.Ocr.oxWord(c, 0, 0, -1, 0);
            emon.Plan.anOcrEnd(c, at);
            return new Planrun.Answer(Port.cellsVec(c));
        }

        public List<String[]> producer() {
            List<String[]> p = new ArrayList<>();
            p.add(new String[] {"engine", "fake"});
            return p;
        }
    }

    static Engine withOcr() {
        Options.Config c = Options.Config.defaults();
        c.ocr = new FakeOcr();
        return new Engine(c);
    }

    static List<Integer> kinds(Planrun.Transcript tr) {
        List<Integer> k = new ArrayList<>();
        for (Planrun.Entry en : tr.entries) {
            k.add(en.req[1]);
        }
        return k;
    }

    static void ocrPort() throws Exception {
        byte[] in = read("test/image/png-phys300.png");
        Options o = Options.defaults();
        Engine.Converted rec = withOcr().record(one(Input.bytes(in)), o);
        String j = js(rec.model);
        for (String w : new String[] {"\"text\": \"Hello\"", "\"ocr\": \"always\"", "\"ocr-only\""}) {
            check(j.contains(w), w + " is not in the JSON");
        }
        check(kinds(rec.transcript).equals(List.of(2, 2, 3)), "kinds " + kinds(rec.transcript));
        check(rec.transcript.producer.size() == 1 && rec.transcript.producer.get(0)[0].equals("engine") && rec.transcript.producer.get(0)[1].equals("fake"), "producer");
        Map<String, byte[]> fs = files(rec.transcript);
        check(new String(fs.get("transcript.edn"), StandardCharsets.UTF_8).contains(":producer {:engine \"fake\"}"), "the producer is in the file");
        Planrun.Transcript t2 = Planrun.fromFiles(fs);
        check(t2.producer.size() == 1, "the producer is read back");
        check(js(plain().replay(one(Input.bytes(in)), o, t2)).equals(j), "the replay differs from the recording");
        Options never = o.copy();
        never.ocr = Options.OcrPolicy.NEVER;
        Engine.Converted rn = withOcr().record(one(Input.bytes(in)), never);
        String jn = js(rn.model);
        check(kinds(rn.transcript).equals(List.of(2, 2)) && !jn.contains("Hello") && !jn.contains("\"ocr\""), "never: " + kinds(rn.transcript));
        check(js(plain().convert(one(Input.bytes(in)), o)).equals(jn), "no port is not `never`");

        // a scanned PDF page is OCR'd and a born-digital one keeps its text layer
        byte[] scan = read("test/corpus/synth/scan/h-keyword.pdf");
        Engine.Converted sr = withOcr().record(one(Input.bytes(scan)), o);
        List<Planrun.Entry> es = sr.transcript.entries;
        check(kinds(sr.transcript).equals(List.of(2, 3)) && es.get(0).round == 0 && es.get(1).round == 1, "scan kinds " + kinds(sr.transcript));
        check(es.get(0).req[6] == 1 && es.get(0).bitmap != null, "the DCT filter, with the pixels an engine will read");
        String sj = js(sr.model);
        check(sj.contains("\"kind\": \"scanned\"") && sj.contains("Hello"), "scanned");
        check(js(plain().replay(one(Input.bytes(scan)), o, Planrun.fromFiles(files(sr.transcript)))).equals(sj), "the scan replay differs");
        String none = js(plain().convert(one(Input.bytes(scan)), o));
        check(!none.contains("Hello") && !none.contains("\"ocr\"") && none.contains("\"kind\": \"vector\""), "no port: the page has no text");
        byte[] born = synth("h-plain");
        Engine.Converted br = withOcr().record(one(Input.bytes(born)), o);
        for (Planrun.Entry en : br.transcript.entries) {
            check(en.req[1] == 1, "a text layer is read as it is: no OCR is asked");
        }
        check(js(plain().convert(one(Input.bytes(born)), o)).equals(js(br.model)), "the port changed a born-digital Model");
    }

    static void assets() throws Exception {
        Engine e = plain();
        Model.Kind figure = Model.Kind.named("figure");
        Model m0 = e.convert(one(Input.bytes(read("test/assets/dct-rgb.pdf"))), Options.defaults());
        check(m0.assets().isEmpty() && m0.find(figure).size() == 1 && !js(m0).contains("\"assets\"") && !m0.toHtml(new Options.Html()).contains("<img"), "assets off is not off");
        Options o = Options.defaults();
        o.assets = Options.Assets.IMAGES;
        Model m = e.convert(one(Input.bytes(read("test/assets/dct-rgb.pdf"))), o);
        List<Model.Asset> a = m.assets();
        check(a.size() == 1, a.size() + " assets");
        Model.Asset a0 = a.get(0);
        check(a0.id() == 0 && a0.name().equals("a0.jpg") && a0.mediaType().equals("image/jpeg") && a0.origin().equals("embedded") && a0.from().equals("dct"), "asset 0");
        check(a0.width() == 24 && a0.height() == 16 && a0.uses().size() == 1 && a0.sha256().length() == 64, "asset dimensions");
        byte[] b = a0.bytes();
        check(b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8, "not a JPEG");
        List<Node> figs = m.find(figure);
        check(figs.size() == 1 && figs.get(0).asset() != null && figs.get(0).asset().id() == 0 && a0.uses().get(0).node == figs.get(0).id(), "the figure is asset 0");
        check(m.assetFiles().size() == 1 && m.assetFiles().get(0).name.equals("a0.jpg"), "files");
        Options.Html inl = new Options.Html(), ext = new Options.Html(), omit = new Options.Html();
        ext.assets = Options.HtmlAssets.EXTERNAL;
        ext.assetPrefix = "img/";
        omit.assets = Options.HtmlAssets.OMIT;
        check(m.toHtml(inl).contains("<img src=\"data:image/jpeg;base64,/9j/") && m.toHtml(ext).contains("<img src=\"img/a0.jpg\"") && !m.toHtml(omit).contains("<img"), "the three HTML modes");
        Model p = e.convert(one(Input.bytes(read("test/assets/flate-rgb8.pdf"))), o);
        List<Model.Asset> pa = p.assets();
        check(pa.size() == 1 && pa.get(0).mediaType().equals("image/png") && pa.get(0).origin().equals("decoded") && pa.get(0).from().equals("flate")
            && (pa.get(0).bytes()[0] & 0xFF) == 0x89 && pa.get(0).bytes()[1] == 0x50, "a Flate image is not a decoded PNG");
        Model c = e.convert(one(Input.bytes(read("test/assets/jpx.pdf"))), o);
        String why = c.find(figure).get(0).noAsset();
        check(c.assets().isEmpty() && "codec".equals(why), "jpx: " + why);
        Options of = o.copy();
        of.assets = Options.Assets.FIGURES;
        Model f = e.convert(one(Input.bytes(read("test/assets/dct-rgb.pdf"))), of);
        check(js(f).equals(js(m).replaceFirst("\"assets\": \"images\"", "\"assets\": \"figures\"")), "figures is not images");
        Model n = e.convert(one(Input.bytes(synth("h-plain"))), o);
        check(n.assets().isEmpty() && js(n).contains("\"assets\": []"), "an empty table");
        // through a 512-byte async source: the same files
        for (String name : new String[] {"dct-rgb", "flate-rgb8", "place-twice", "smask", "indexed16"}) {
            byte[] bb = read("test/assets/" + name + ".pdf");
            Model mem = plain().convert(one(Input.bytes(bb)), o);
            Model asy = asyncEngine(512).convert(one(Input.bytes(bb)), o);
            check(js(asy).equals(js(mem)) && asy.toHtml(new Options.Html()).equals(mem.toHtml(new Options.Html())), name + ": the async run differs");
            List<Model.AssetFile> fa = asy.assetFiles(), fm = mem.assetFiles();
            check(fa.size() == fm.size(), name + ": file count");
            for (int i = 0; i < Math.min(fa.size(), fm.size()); i++) {
                check(fa.get(i).name.equals(fm.get(i).name) && Arrays.equals(fa.get(i).bytes, fm.get(i).bytes), name + ": file " + i + " differs");
            }
        }
    }

    /**
     * A JBIG2 and JPX decoder that records what it was handed and answers a fixed bitmap: the request names the image's bytes (the input's own) and the
     * globals (kin's, in the unit's payload), and the fulfiller must be given both.
     */
    static final class SeenCodecs implements Options.PdfCodecs {
        final List<int[]> seen = new ArrayList<>();

        public ImgDec.PdfPix jpx(byte[] data, long max) throws ImgDec.Fail {
            throw new ImgDec.Fail(ImgDec.UNSUPPORTED, "not read by this codec");
        }

        public ImgDec.PdfPix jbig2(byte[] data, byte[] globals, long max) {
            seen.add(new int[] {data.length, globals.length});
            byte[] px = new byte[64 * 32];
            for (int i = 0; i < px.length; i++) {
                px[i] = (byte) (i % 3 == 0 ? 0 : 255);
            }
            return new ImgDec.PdfPix(64, 32, 1, px, true);
        }
    }

    static void decodeImg() throws Exception {
        Options o = Options.defaults();
        o.assets = Options.Assets.IMAGES;
        Model.Kind figure = Model.Kind.named("figure");
        // DCT that cannot pass through (a /Decode) and CCITT: the host's own codecs, no port configured
        String[][] cases = {{"dct-decode", "dct", "24", "16"}, {"dct-a85-decode", "dct", "24", "16"}, {"ccitt-g4", "ccitt", "70", "24"},
            {"ccitt-g4-align", "ccitt", "70", "24"}, {"ccitt-g3-1d", "ccitt", "70", "24"}, {"ccitt-g3-1d-noeol", "ccitt", "70", "24"},
            {"ccitt-g3-1d-align", "ccitt", "70", "24"}, {"ccitt-g3-1d-align-noeol", "ccitt", "70", "24"}, {"ccitt-g3-2d", "ccitt", "70", "24"}};
        for (String[] c : cases) {
            Model m = plain().convert(one(Input.bytes(read("test/assets/" + c[0] + ".pdf"))), o);
            List<Model.Asset> a = m.assets();
            check(a.size() == 1 && a.get(0).mediaType().equals("image/png") && a.get(0).origin().equals("decoded") && a.get(0).from().equals(c[1])
                && a.get(0).width() == Integer.parseInt(c[2]) && a.get(0).height() == Integer.parseInt(c[3]) && (a.get(0).bytes()[0] & 0xFF) == 0x89,
                c[0] + ": not a decoded PNG of " + c[1]);
        }
        // what this host does not read says `codec`: JPX, JBIG2
        for (String name : new String[] {"jpx", "jbig2-globals"}) {
            Model m = plain().convert(one(Input.bytes(read("test/assets/" + name + ".pdf"))), o);
            String why = m.find(figure).get(0).noAsset();
            check(m.assets().isEmpty() && "codec".equals(why), name + ": " + why);
        }
        // JBIG2 with its globals, through an embedder's PdfCodecs: the fulfiller sees the image's bytes and the globals (the payload)
        SeenCodecs seen = new SeenCodecs();
        Options.Config cfg = Options.Config.defaults();
        cfg.pdfCodecs = seen;
        Engine e = new Engine(cfg);
        byte[] jb = read("test/assets/jbig2-globals.pdf");
        Model m = e.convert(one(Input.bytes(jb)), o);
        List<Model.Asset> a = m.assets();
        check(a.size() == 1 && a.get(0).from().equals("jbig2") && a.get(0).width() == 64 && a.get(0).height() == 32 && a.get(0).origin().equals("decoded"), "the JBIG2 asset");
        check(seen.seen.size() == 1 && seen.seen.get(0)[0] > 20 && seen.seen.get(0)[1] == 21, "the fulfiller saw the image's bytes and 21 bytes of globals");
        // a transcript of that run replays without the codecs, also through an async source
        Engine.Converted rec = e.record(one(Input.bytes(jb)), o);
        Model r = plain().replay(one(Input.bytes(jb)), o, rec.transcript);
        check(js(r).equals(js(m)) && r.assetFiles().size() == m.assetFiles().size() && Arrays.equals(r.assetFiles().get(0).bytes, m.assetFiles().get(0).bytes), "the replay is not the run");
        for (String name : new String[] {"dct-decode", "ccitt-g4", "ccitt-g4-mask", "dct-smask"}) {
            byte[] b = read("test/assets/" + name + ".pdf");
            Engine.Converted live = plain().record(one(Input.bytes(b)), o);
            Model asy = asyncEngine(512).replay(one(Input.bytes(b)), o, live.transcript);
            check(js(asy).equals(js(live.model)) && asy.assetFiles().size() == live.model.assetFiles().size(), name + ": the replay through an async source is not the run");
        }
    }

    /** A fake Renderer: a flat RGB page of known size, so the fulfiller's records, cuts and failures are what is tested. */
    static final class FakeRenderer implements RenderPort.Renderer {
        int calls = 0;
        final boolean exact;

        FakeRenderer(boolean exact) {
            this.exact = exact;
        }

        public Port.Bitmap render(RenderPort.Source input, byte[] password, RenderPort.Request req) throws RenderPort.RenderError {
            calls++;
            if (req.layer == RenderPort.LAYER_NO_WATERMARK) {
                throw new RenderPort.RenderError(true, "no watermark layer");
            }
            if (req.page > 0) {
                throw new RenderPort.RenderError(false, "page " + req.page);
            }
            int w = RenderPort.pixelsOf(100, req.dpi), h = RenderPort.pixelsOf(50, req.dpi);
            Port.Bitmap whole = new Port.Bitmap(w, h, 3, new byte[w * h * 3]);
            for (int i = 0; i < whole.d.length; i++) {
                whole.d[i] = (byte) (i * 7);
            }
            return req.clip == null ? whole : RenderPort.cut(whole, req.clip);
        }

        public int layers() {
            return 0b00111;
        }

        public boolean exactCrop() {
            return exact;
        }
    }

    static void renderPort() throws Exception {
        FakeRenderer r = new FakeRenderer(false);
        RenderPort.Fulfiller f = RenderPort.Fulfiller.forInput(r, 0, new RenderPort.Input(ByteSource.of(new byte[4]), null));
        Planrun.Answer a = f.fulfil(new Planrun.Request(new int[] {7, Planrun.KIND_RENDER, 1, 0, 0, 72, 0}));
        check(a.rec.length == 8 && a.rec[3] == 0 && a.rec[4] == 100 && a.rec[5] == 50 && a.rec[6] == 3 && a.rec[7] == 72 && a.bitmap != null, "a render answers its size");
        Planrun.Answer c = f.fulfil(new Planrun.Request(new int[] {11, Planrun.KIND_CROP, 2, 0, 0, 72, 0, 10, 5, 20, 10}));
        check(c.rec[4] == 20 && c.rec[5] == 10 && r.calls == 1, "a crop is the cut of the kept whole page: one render");
        Port.Bitmap whole = r.render(null, null, new RenderPort.Request(0, 72, 0, null));
        boolean same = true;
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                for (int k = 0; k < 3; k++) {
                    same &= c.bitmap.d[(y * 20 + x) * 3 + k] == whole.d[((y + 5) * 100 + x + 10) * 3 + k];
                }
            }
        }
        check(same, "the crop law: the same pixels, cut out");
        Planrun.Answer u = f.fulfil(new Planrun.Request(new int[] {7, Planrun.KIND_RENDER, 3, 0, 0, 72, 3}));
        check(u.rec[3] == 2 && u.rec.length == 4, "an unsupported layer is `unsupported`");
        Planrun.Answer bad = f.fulfil(new Planrun.Request(new int[] {7, Planrun.KIND_RENDER, 4, 0, 5, 72, 0}));
        check(bad.rec[3] == 1, "a failed render is `failed`");
        try {
            f.fulfil(new Planrun.Request(new int[] {7, Planrun.KIND_RENDER, 5, 9, 0, 72, 0}));
            check(false, "an unknown input");
        } catch (Planrun.PlanError e) {
            check(e.name.equals("fulfil-failed"), "an unknown input is fulfil-failed");
        }
        // an Engine with a Renderer renders a born-digital page under `always` (it asks for its rasters) and asks for nothing under `never`, where
        // it is the same Engine as one with none
        Options.Config cfg = Options.Config.defaults();
        FakeRenderer er = new FakeRenderer(true);
        cfg.renderer = er;
        byte[] born = synth("h-plain");
        Options never = Options.defaults();
        never.ocr = Options.OcrPolicy.NEVER;
        check(js(new Engine(cfg).convert(one(Input.bytes(born)), never)).equals(js(plain().convert(one(Input.bytes(born)), never))) && er.calls == 0,
            "under never an Engine with a Renderer is the same Engine and renders nothing");
        new Engine(cfg).convert(one(Input.bytes(born)), Options.defaults());
        check(er.calls > 0, "under always a born-digital page is rendered");
    }

    static void run(String name, T t) {
        int before = failures;
        try {
            t.run();
        } catch (Throwable e) {
            failures++;
            System.out.println("  FAIL " + name + ": " + e);
            e.printStackTrace(System.out);
        }
        System.out.println((failures == before ? "ok   " : "FAIL ") + name);
    }

    public static void main(String[] a) throws Exception {
        Thread t = new Thread(null, () -> {
            run("the adapters give identical JSON and HTML", EngineTest::adapters);
            run("a 512-byte async source equals the in-memory run", EngineTest::async512);
            run("every PDF fixture is the same in memory and async", EngineTest::everyPdfFixture);
            run("encryption errors are distinct and a password opens what it opens", EngineTest::encryption);
            run("limits: input-bytes against the size, pages, pixels", EngineTest::limits);
            run("a source that gives fewer bytes than its size is source-changed", EngineTest::liar);
            run("what is not implemented or not readable is refused by name", EngineTest::refused);
            run("intake takes any number of inputs and reads no pixels", EngineTest::intake);
            run("record and replay", EngineTest::recordReplay);
            run("the Model can be walked", EngineTest::walk);
            run("the Model is readable from many threads", EngineTest::manyThreads);
            run("an image becomes pages (and async)", EngineTest::images);
            run("an image records its pixels and replays without a codec", EngineTest::imageRecordReplay);
            run("an OCR port reads an image and a scanned page; the transcript replays without it", EngineTest::ocrPort);
            run("assets", EngineTest::assets);
            run("decode-img: the host's codecs, the embedder's, and the payload", EngineTest::decodeImg);
            run("the Renderer port's fulfiller", EngineTest::renderPort);
        }, "engine-test", 1L << 30);
        t.start();
        t.join();
        System.out.println(failures == 0 ? "EngineTest: all passed" : "EngineTest: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
