// The Engine API's native tests, C# (DEVLOG P9.2), run by `bb slice` (`kin/scripts/slice`) against the assembled tree
// (`kin/scripts/emon-cs-build`): the twin of `host/go/engine_test.go`, `host/rust/tests/engine.rs` and
// `test/driver/java/EngineTest.java`, test for test (no xunit: a plain runner, as the Java arm's, D044). They read fixtures from the
// repository's `test/`: a TEST may read files, the library may not (D058). `EMON_ROOT` is the repository root (default: the
// working directory).
//
//   emon-cs --engine-tests        prints `ok   NAME` per test and `EngineTest: all passed`; exit 1 on a failure
//
// What they hold: the byte-source adapters give IDENTICAL JSON and HTML; a 512-byte ASYNC source (misses, fetch rounds,
// re-runs) gives the in-memory run's output over the slice's fixtures AND every PDF fixture; D040's encryption errors; the
// input-bytes limit is checked against the SIZE before any byte is read; a source that lies about its size is
// `source-changed`; several inputs as one document are refused; record and replay; the walkable Model, read from many
// threads; raster intake (an image is pages, its pixels are recorded and replayed without a codec, an OCR port reads it);
// assets (P3.13); the Renderer port's fulfiller (P6.2, D127).

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using emon.Api;

public static class EngineTest {
    static readonly string Root = Environment.GetEnvironmentVariable("EMON_ROOT") ?? ".";
    static readonly string[] Slice = { "h-plain", "h-keyword", "h-inverted-numbered", "h-numbered", "initial-image", "initial-vector", "furn-informative" };
    static int failures = 0;
    static readonly object failLock = new object();
    static readonly UTF8Encoding Utf8 = new UTF8Encoding(false);

    static void Out(string s) {
        Console.Out.Write(s + "\n");
        Console.Out.Flush();
    }

    static void Check(bool ok, string what) {
        if (!ok) {
            lock (failLock) {
                failures++;
            }
            Out("  FAIL " + what);
        }
    }

    static byte[] Read(string rel) {
        return File.ReadAllBytes(Path.Combine(Root, rel));
    }

    static byte[] Synth(string name) {
        return Read("test/corpus/synth/pdf/" + name + ".pdf");
    }

    static List<Input> One(Input input) {
        return new List<Input> { input };
    }

    static string Js(Model m) {
        return m.ToJson(new Options.Json(true));
    }

    static string Flat(Model m) {
        return m.ToJson(new Options.Json());
    }

    static Engine AsyncEngine(int chunk) {
        var c = Options.Config.Defaults();
        c.ChunkSize = chunk;
        c.SimulateAsync = true;
        return new Engine(c);
    }

    static Engine Plain() {
        return new Engine(Options.Config.Defaults());
    }

    /// <summary>What convert says of b: {json, errname}.</summary>
    static string[] ResultOf(Engine e, byte[] b, string pw) {
        Input input = Input.Bytes(b);
        if (pw != null) {
            input = input.WithPassword(Utf8.GetBytes(pw));
        }
        try {
            return new[] { Flat(e.Convert(One(input), Options.Defaults())), "" };
        } catch (EmonError x) {
            return new[] { "", x.Name() };
        }
    }

    static string ErrOf(Engine e, List<Input> input, Options o) {
        try {
            e.Convert(input, o);
            return "";
        } catch (EmonError x) {
            return x.Name();
        }
    }

    static bool Same(int[] a, int[] b) {
        return a.SequenceEqual(b);
    }

    static void Adapters() {
        foreach (string name in Slice) {
            byte[] b = Synth(name);
            Engine e = Plain();
            Options o = Options.Defaults();
            Model by = e.Convert(One(Input.Bytes(b)), o);
            var tiny = Options.Config.Defaults();
            tiny.ChunkSize = 64;
            var sections = new List<KeyValuePair<string, Input>> {
                new KeyValuePair<string, Input>("seek", Input.Seekable(new MemoryStream(b, false))),
                new KeyValuePair<string, Input>("spool", Input.Spooled(new MemoryStream(b, false), 1L << 30)),
                new KeyValuePair<string, Input>("copy", Input.Bytes((byte[])b.Clone())),
                new KeyValuePair<string, Input>("span", Input.Bytes(new ArraySegment<byte>(b).ToArray())),
            };
            string want = Js(by), wantHtml = by.ToHtml(new Options.Html());
            Check(want.Contains("\"paragraph\""), name + " has paragraphs");
            foreach (var s in sections) {
                Model m = e.Convert(One(s.Value), o);
                Check(Js(m) == want, name + ": " + s.Key + " JSON differs");
                Check(m.ToHtml(new Options.Html()) == wantHtml, name + ": " + s.Key + " HTML differs");
            }
            // a seekable source through a tiny SYNC cache: chunks filled inline, no miss ever
            Model m2 = new Engine(tiny).Convert(One(Input.Seekable(new MemoryStream(b, false))), o);
            Check(Js(m2) == want, name + ": sync chunks differ");
        }
    }

    static void Async512() {
        foreach (string name in Slice) {
            byte[] b = Synth(name);
            Options o = Options.Defaults();
            Engine.Converted mem = Plain().ConvertStats(One(Input.Bytes(b)), o);
            Check(mem.Stats.Attempts == 1 && mem.Stats.FetchRounds == 0, "in memory: one attempt, no fetch");
            Engine.Converted asy = AsyncEngine(512).ConvertStats(One(Input.Bytes(b)), o);
            Check(Js(asy.Model) == Js(mem.Model) && asy.Model.ToHtml(new Options.Html()) == mem.Model.ToHtml(new Options.Html()), name + ": async differs from memory");
            Check(asy.Stats.FetchRounds >= 1 && asy.Stats.Attempts == asy.Stats.FetchRounds + 1, name + ": it missed and re-ran");
            Check(asy.Stats.BytesFetched <= b.Length && asy.Stats.FetchRounds <= 24, name + ": never more than the input, inside FETCH_ROUNDS");
            Model seek = AsyncEngine(512).Convert(One(Input.Seekable(new MemoryStream(b, false))), o);
            Check(Js(seek) == Js(mem.Model), name + ": async over a stream differs");
        }
    }

    static void EveryPdfFixture() {
        Engine mem = Plain(), asy = AsyncEngine(512);
        var files = new List<string>();
        foreach (string dir in new[] { "test/pdf", "test/pdf/p3", "test/content", "test/fuzz" }) {
            string d = Path.Combine(Root, dir);
            if (!Directory.Exists(d)) {
                continue;
            }
            foreach (string p in Directory.GetFiles(d)) {
                if (p.EndsWith(".pdf", StringComparison.Ordinal)) {
                    files.Add(p);
                }
            }
        }
        files.Sort(StringComparer.Ordinal);
        Check(files.Count > 60, "found the fixtures: " + files.Count);
        int ok = 0;
        foreach (string f in files) {
            byte[] b = File.ReadAllBytes(f);
            string[] a1 = ResultOf(mem, b, null), a2 = ResultOf(asy, b, null);
            Check(a1[0] == a2[0] && a1[1] == a2[1], f + ": in memory and async 512 differ (" + a1[1] + " vs " + a2[1] + ")");
            if (a1[1].Length == 0) {
                ok++;
            }
        }
        Check(ok > 20, "only " + ok + " converted");
    }

    static void Encryption() {
        Engine e = Plain();
        string[] plainR = ResultOf(e, Read("test/pdf/p3/plain.pdf"), null);
        Check(plainR[1].Length == 0, "plain converts");
        byte[] locked = Read("test/pdf/p3/crypt-aes-128-user.pdf");
        Check(ResultOf(e, locked, null)[1] == "password-required", "no password");
        Check(ResultOf(e, locked, "wrong-password")[1] == "password-incorrect", "wrong password");
        string[] u = ResultOf(e, locked, "user-secret");
        Check(u[1].Length == 0 && u[0] == plainR[0], "the user password: the plain twin's Model");
        string[] ow = ResultOf(e, locked, "owner-secret");
        Check(ow[1].Length == 0 && ow[0] == plainR[0], "the owner password too");
        string[] em = ResultOf(e, Read("test/pdf/p3/crypt-aes-128-empty.pdf"), null);
        Check(em[1].Length == 0 && em[0] == plainR[0], "the empty user password is tried by itself");
        Check(ResultOf(e, Read("test/pdf/p3/unsupported-pubsec.pdf"), null)[1] == "encryption-unsupported", "pubsec");
        Engine asy = AsyncEngine(512);
        byte[] r6 = Read("test/pdf/p3/crypt-aes-256-r6-user.pdf");
        Check(ResultOf(asy, r6, null)[1] == "password-required", "async, no password");
        Check(ResultOf(asy, r6, "nope")[1] == "password-incorrect", "async, wrong password");
        Check(!Input.Bytes(new byte[] { 1 }).WithPassword(Utf8.GetBytes("hunter2")).ToString().Contains("hunter2"), "the password is in the Input's ToString");
    }

    /// <summary>A source that must not be read: a size check that happens first never reads it.</summary>
    sealed class Unreadable : IByteSource {
        readonly long n;

        public Unreadable(long n) {
            this.n = n;
        }

        public long Size() {
            return n;
        }

        public int ReadAt(long off, byte[] buf, int boff, int len) {
            throw new InvalidOperationException("a source over the input-bytes limit was read");
        }

        public byte[] AsSlice() {
            return null;
        }
    }

    /// <summary>A source whose size says more than it has.</summary>
    sealed class Liar : IByteSource {
        readonly byte[] b;
        readonly long n;

        public Liar(byte[] b, long n) {
            this.b = b;
            this.n = n;
        }

        public long Size() {
            return n;
        }

        public int ReadAt(long off, byte[] buf, int boff, int len) {
            if (off >= b.Length) {
                return 0;
            }
            int k = (int)Math.Min(len, b.Length - off);
            Array.Copy(b, (int)off, buf, boff, k);
            return k;
        }

        public byte[] AsSlice() {
            return null;
        }
    }

    static void Limits() {
        var c = Options.Config.Defaults();
        c.Limits = new[] { new[] { 0, 1000 } };
        try {
            new Engine(c).Convert(One(Input.Of(new Unreadable(5000))), Options.Defaults());
            Check(false, "limit 1000, size 5000");
        } catch (EmonError e) {
            Check(e.Kind == EmonError.ErrorKind.Limit && e.Name() == "input-bytes" && e.Limit == 1000 && e.Actual == 5000, "limit 1000, size 5000: " + e.Message);
        }
        Check(ErrOf(Plain(), One(Input.Of(new Unreadable(5L << 30))), Options.Defaults()) == "input-bytes", "5 GiB");
        try {
            Input.Spooled(new MemoryStream(new byte[5000]), 1000);
            Check(false, "spool");
        } catch (IOException e) {
            Check(e.Message.StartsWith("input-bytes", StringComparison.Ordinal), "spool: " + e.Message);
        }
        // a limit deeper in kin is named too (pages), and does not leak into the next conversion
        var few = Options.Config.Defaults();
        few.Limits = new[] { new[] { 8, 1 } };
        byte[] p3 = Read("test/pdf/p3/plain.pdf");
        Check(ErrOf(new Engine(few), One(Input.Bytes(p3)), Options.Defaults()) == "pages", "pages limit");
        Check(ErrOf(Plain(), One(Input.Bytes(p3)), Options.Defaults()).Length == 0, "the limit leaked");
        // a breach deep in kin arrives as the typed payload (P10.10, D435): the name is kin's table's, the bound the override's, the number reached
        // the document's; a limit set in the options text reaches the same place
        Options.Parsed lim = Options.Parse("limit.objects=10\n");
        try {
            new Engine(lim.Config).Convert(One(Input.Bytes(p3)), lim.Options);
            Check(false, "limit.objects=10");
        } catch (EmonError x) {
            Check(x.Kind == EmonError.ErrorKind.Limit && x.Name() == "objects" && x.Limit == 10 && x.Actual > 10, "limit.objects=10: " + x.Message);
        }
        try {
            Options.Parse("limit.nonsense=10\n");
            Check(false, "an unknown limit");
        } catch (ArgumentException x) {
            Check(x.Message.Length > 0 && Options.IsKey("limit.pages") && !Options.IsKey("limit-pages"), "kin's grammar refuses an unknown limit: " + x.Message);
        }
        // the pixels bound is a named limit, checked from the header before a pixel is decoded
        var px = Options.Config.Defaults();
        px.Limits = new[] { new[] { 9, 1000 } };
        Check(ErrOf(new Engine(px), One(Input.Bytes(Read("test/image/png-rgb8.png"))), Options.Defaults()) == "pixels", "the pixels bound");
    }

    static void Liars() {
        byte[] b = Synth("h-plain");
        var c = Options.Config.Defaults();
        c.ChunkSize = 4096;
        Check(ErrOf(new Engine(c), One(Input.Of(new Liar(b, b.Length + 10000))), Options.Defaults()) == "source-changed", "sync liar");
        Check(ErrOf(AsyncEngine(512), One(Input.Of(new Liar(b, b.Length + 10000))), Options.Defaults()) == "source-changed", "async liar");
    }

    static void Refused() {
        Engine e = Plain();
        byte[] b = Synth("h-plain");
        Check(ErrOf(e, new List<Input> { Input.Bytes(b), Input.Bytes(b) }, Options.Defaults()) == "unsupported", "two inputs");
        Check(ErrOf(e, new List<Input>(), Options.Defaults()) == "no-input", "no input");
        Check(ErrOf(e, One(Input.Bytes(Read("test/pdf/notpdf.pdf"))), Options.Defaults()) == "unsupported-format", "not a PDF");
        Options always = Options.Defaults(), never = Options.Defaults();
        always.Ocr = Options.OcrPolicy.Always;
        never.Ocr = Options.OcrPolicy.Never;
        Check(Js(e.Convert(One(Input.Bytes(b)), always)) == Js(e.Convert(One(Input.Bytes(b)), never)), "the OCR policy changed a born-digital Model");
        // images
        foreach (string[] t in new[] { new[] { "tiff-jpeg.tif", "unsupported-image" }, new[] { "jpeg-cmyk.jpg", "unsupported-image" },
                                       new[] { "png-truncated.png", "undecodable" }, new[] { "tiff-truncated.tif", "undecodable" } }) {
            Check(ErrOf(e, One(Input.Bytes(Read("test/image/" + t[0]))), Options.Defaults()) == t[1], t[0] + " is " + t[1]);
        }
    }

    static void IntakeTest() {
        Engine e = Plain();
        List<Intake> v = e.Intake(new List<Input> { Input.Bytes(Synth("h-plain")), Input.Bytes(Read("test/pdf/p3/crypt-aes-128-empty.pdf")) });
        Check(v.Count == 2 && v[0].Format == "pdf" && v[0].Pages == 2 && !v[0].Encrypted && v[1].Pages == 3 && v[1].Encrypted, "intake of two");
        try {
            e.Intake(One(Input.Bytes(Read("test/pdf/p3/crypt-aes-128-user.pdf"))));
            Check(false, "locked intake");
        } catch (EmonError x) {
            Check(x.Name() == "password-required", "locked: " + x.Name());
        }
        Model m = e.Convert(One(Input.Bytes(Synth("h-plain"))), Options.Defaults());
        Check(m.Source().Pages == 2 && !m.Source().Encrypted, "the Model says its source");
        // stage 0 of an image reads no pixels
        List<Intake> iv = e.Intake(new List<Input> { Input.Bytes(Read("test/image/tiff-multi3-g4.tif")), Input.Bytes(Read("test/image/jpeg-420.jpg")), Input.Bytes(Synth("h-plain")) });
        Check(iv[0].Format == "tiff" && iv[0].Pages == 3 && iv[1].Format == "jpeg" && iv[1].Pages == 1 && iv[2].Format == "pdf" && iv[2].Pages == 2, "image intake");
    }

    static void RecordReplay() {
        Engine e = Plain();
        byte[] b = Synth("h-plain");
        Engine.Converted rec = e.Record(One(Input.Bytes(b)), Options.Defaults());
        emon.Planrun.Transcript tr = rec.Transcript;
        // under `always` (the default, P7.S, D200) a born-digital page asks for its two rasters, the page with its text and without it; with no
        // Renderer each is answered `unsupported` and transcribed (two pages, four entries), and nothing is OCR'd
        Check(tr.Entries.Count == 4, "a born-digital page asks for its rasters under always: " + tr.Entries.Count + " entries");
        // under `never` it asks for nothing
        Options never = Options.Defaults();
        never.Ocr = Options.OcrPolicy.Never;
        Check(e.Record(One(Input.Bytes(b)), never).Transcript.Entries.Count == 0, "under never: no entries");
        Check(tr.Inputs.Count == 1 && tr.Inputs[0][0] == 0 && tr.Inputs[0][1] == b.Length, "inputs");
        Model r = e.Replay(One(Input.Bytes(b)), Options.Defaults(), tr);
        Check(Js(r) == Js(rec.Model), "replay differs from record");
        try {
            e.Replay(One(Input.Bytes(Synth("h-keyword"))), Options.Defaults(), tr);
            Check(false, "another input's transcript");
        } catch (EmonError x) {
            Check(x.Name() == "transcript-bad", "another input's transcript: " + x.Name());
        }
    }

    static void Walk() {
        Engine e = Plain();
        Options o = Options.Defaults();
        o.Locale = "en-GB";
        Model m = e.Convert(One(Input.Bytes(Synth("h-plain"))), o);
        Node root = m.Root();
        Check(root.Kind().Name() == "document", "root kind");
        Model.Attr a = root.Attr("locale");
        Check(a != null && a.Kind == Model.AttrKind.Text && a.Text == "en-GB", "locale");
        Check(Flat(m).Contains("\"locale\":\"en-GB\""), "the JSON has the locale");
        List<Node> top = root.Children();
        Check(top.Count != 0 && top[0].Kind().Name() == "section", "top");
        int sec = top[0].Index();
        List<Node> kids = top[0].Children();
        Check(kids.Count > 2 && kids[0].Kind().Name() == "heading" && kids[0].Text() == "Field Notes on Soil Moisture", "children");
        Check(kids[0].Sources().SequenceEqual(new[] { "text", "infer" }), "sources");
        Check(kids[0].Conf() == 500, "conf");
        Check(kids[0].Parent() != null && kids[0].Parent().Index() == sec, "parent");
        Check(root.Parent() == null, "the root has a parent");
        List<Model.Region> rg = kids[0].Regions();
        Check(rg.Count == 1 && rg[0].Page == 1 && rg[0].X0 == 7200 && rg[0].Y0 == 9781, "regions");
        Model.Kind para = Model.Kind.Named("paragraph");
        Check(para != null && Model.Kind.Named("nonsense") == null, "Kind.Named");
        List<Node> all = m.Find(para);
        for (int i = 1; i < all.Count; i++) {
            Check(all[i - 1].Index() < all[i].Index(), "document order");
        }
        List<Model.Page> pages = m.Pages();
        Check(pages.Count == 2 && pages[0].Number == 1 && pages[0].Width == 61200 && pages[0].Height == 79200 && pages[0].Kind == "vector", "pages");
        Check(m.Furniture().Count == 0 && m.Dropped().Count == 0, "furniture or dropped in a plain page");
        string j = Flat(m);
        foreach (Node k in kids) {
            if (!k.Kind().Equals(para)) {
                continue;
            }
            string q = k.Text().Replace("\\", "\\\\").Replace("\"", "\\\"");
            Check(j.Contains("\"text\":\"" + q + "\""), "JSON lacks " + k.Text());
        }
        Options o2 = Options.Defaults();
        o2.KeepEvidence = true;
        string ev = e.Convert(One(Input.Bytes(Synth("h-plain"))), o2).Evidence();
        Check(ev != null && ev.StartsWith("{\"emon-evidence\"", StringComparison.Ordinal), "evidence");
        Check(m.Evidence() == null, "evidence kept without asking");
    }

    static void ManyThreads() {
        Model m = Plain().Convert(One(Input.Bytes(Synth("h-plain"))), Options.Defaults());
        string want = Flat(m);
        var got = new string[8];
        var ts = new Thread[8];
        for (int i = 0; i < 8; i++) {
            int k = i;
            ts[i] = new Thread(() => {
                foreach (Node c in m.Root().Children()) {
                    c.Text();
                }
                got[k] = Flat(m);
            }, 1 << 26);
            ts[i].Start();
        }
        foreach (Thread t in ts) {
            t.Join();
        }
        foreach (string g in got) {
            Check(want == g, "a concurrent read differs");
        }
    }

    static void Images() {
        Engine e = Plain();
        Model m = e.Convert(One(Input.Bytes(Read("test/image/png-phys300.png"))), Options.Defaults());
        List<Model.Page> pages = m.Pages();
        Check(pages.Count == 1 && pages[0].Kind == "image", "image pages");
        Check(pages[0].Width == 1152 && pages[0].Height == 864, "page size " + pages[0].Width + "x" + pages[0].Height);
        Check(m.Source().Format == "png", "format");
        string j = Flat(m);
        Check(j.Contains("\"source\":{\"format\":\"png\",\"pages\":1}") && j.Contains("\"dpi\":300,\"px\":[48,36]") && !j.Contains("dpi-unknown"), "json: " + j);
        m = e.Convert(One(Input.Bytes(Read("test/image/png-rgb8.png"))), Options.Defaults());
        Check(Flat(m).Contains("dpi-unknown"), "an image with no resolution is flagged");
        m = e.Convert(One(Input.Bytes(Read("test/image/tiff-multi3-rgb-lzw.tif"))), Options.Defaults());
        int[][] want = { new[] { 1, 1152, 864 }, new[] { 2, 960, 720 }, new[] { 3, 792, 600 } };
        List<Model.Page> ps = m.Pages();
        Check(ps.Count == 3, "three frames");
        for (int i = 0; i < Math.Min(3, ps.Count); i++) {
            Check(ps[i].Number == want[i][0] && ps[i].Width == want[i][1] && ps[i].Height == want[i][2], "frame " + i);
        }
        foreach (Node c in m.Root().Children()) {
            Check(c.Kind().Name() == "break", "an image page has a " + c.Kind().Name() + " with no OCR port");
        }
        // an image through a source that misses is the in-memory one
        foreach (string name in new[] { "png-rgba8.png", "jpeg-444.jpg", "tiff-multi3-g4.tif", "tiff-tiled16.tif" }) {
            byte[] b = Read("test/image/" + name);
            Model a = e.Convert(One(Input.Bytes(b)), Options.Defaults());
            Model c = AsyncEngine(64).Convert(One(Input.Bytes(b)), Options.Defaults());
            Check(Js(a) == Js(c), name + ": the async run differs");
        }
    }

    static Dictionary<string, byte[]> Files(emon.Planrun.Transcript tr) {
        var m = new Dictionary<string, byte[]>();
        foreach (emon.Planrun.File f in tr.ToFiles()) {
            m[f.Name] = f.Bytes;
        }
        return m;
    }

    static void ImageRecordReplay() {
        Engine e = Plain();
        byte[] input = Read("test/image/tiff-multi3-gray-none.tif");
        Engine.Converted rec = e.Record(One(Input.Bytes(input)), Options.Defaults());
        emon.Planrun.Transcript tr = rec.Transcript;
        Check(tr.Entries.Count == 9, "entries: " + tr.Entries.Count);
        int[] ids = { 1, 2, 3, 260, 272, 516, 528, 772, 784 };
        int[][] want = { new[] { 0, 2 }, new[] { 1, 2 }, new[] { 1, 2 }, new[] { 0, 2 }, new[] { 1, 3 }, new[] { 0, 2 }, new[] { 1, 3 }, new[] { 0, 2 }, new[] { 1, 3 } };
        for (int i = 0; i < Math.Min(9, tr.Entries.Count); i++) {
            emon.Planrun.Entry en = tr.Entries[i];
            Check(en.Round == want[i][0] && en.Req[1] == want[i][1] && en.Req[2] == ids[i], "entry " + i + ": round " + en.Round + " kind " + en.Req[1] + " id " + en.Req[2]);
            Check((en.Req[1] == 2 && en.Req[7] >= 0) == (en.Bitmap != null), "entry " + i + ": a decode keeps its pixels and an unsupported OCR has none");
            Check(en.Req[1] != 3 || en.Ans[3] == 2, "entry " + i + ": OCR without a port is `unsupported`");
        }
        emon.Planrun.Transcript t2 = emon.Planrun.FromFiles(Files(tr));
        Model r = e.Replay(One(Input.Bytes(input)), Options.Defaults(), t2);
        Check(Js(r) == Js(rec.Model), "the replay differs from the recording");
        try {
            e.Replay(One(Input.Bytes(Read("test/image/tiff-multi3-rgb-lzw.tif"))), Options.Defaults(), t2);
            Check(false, "a replay of another image did not miss");
        } catch (EmonError) {
            // the request is not in the transcript
        }
        Check(Js(e.Convert(One(Input.Bytes(input)), Options.Defaults())) == Js(rec.Model), "convert differs from record");
    }

    /// <summary>A FAKE `Ocr` port: it answers every request with the word `Hello` in one line, through the same kin builders a real engine's adapter uses.</summary>
    sealed class FakeOcr : IOcr {
        public emon.Planrun.Answer Fulfil(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm, bool keep) {
            emon.Port.Cells c = emon.Port.NewCells();
            int at = emon.Plan.AnOcr(c, req.Id());
            int w = emon.Plan.AnOcrWord(c, at, 10, 10, 100, 30, 900, 0, 0);
            foreach (System.Text.Rune cp in "Hello".EnumerateRunes()) {
                emon.Plan.AnOcrCp(c, w, cp.Value);
            }
            emon.Ocr.OxBegin(c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0);
            emon.Ocr.OxLine(c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6);
            emon.Ocr.OxBlock(c, 10, 10, 100, 30, 0, 0, 0);
            emon.Ocr.OxWord(c, 0, 0, -1, 0);
            emon.Plan.AnOcrEnd(c, at);
            return new emon.Planrun.Answer(emon.Port.CellsVec(c));
        }

        public List<string[]> Producer() {
            return new List<string[]> { new[] { "engine", "fake" } };
        }
    }

    static Engine WithOcr() {
        var c = Options.Config.Defaults();
        c.Ocr = new FakeOcr();
        return new Engine(c);
    }

    static List<int> Kinds(emon.Planrun.Transcript tr) {
        var k = new List<int>();
        foreach (emon.Planrun.Entry en in tr.Entries) {
            k.Add(en.Req[1]);
        }
        return k;
    }

    static string Show(List<int> v) {
        return "[" + string.Join(", ", v) + "]";
    }

    static void OcrPort() {
        byte[] input = Read("test/image/png-phys300.png");
        Options o = Options.Defaults();
        Engine.Converted rec = WithOcr().Record(One(Input.Bytes(input)), o);
        string j = Js(rec.Model);
        foreach (string w in new[] { "\"text\": \"Hello\"", "\"ocr\": \"always\"", "\"ocr-only\"" }) {
            Check(j.Contains(w), w + " is not in the JSON");
        }
        Check(Kinds(rec.Transcript).SequenceEqual(new[] { 2, 2, 3, 3, 3 }), "kinds " + Show(Kinds(rec.Transcript)));
        Check(rec.Transcript.Producer.Count == 1 && rec.Transcript.Producer[0][0] == "engine" && rec.Transcript.Producer[0][1] == "fake", "producer");
        Dictionary<string, byte[]> fs = Files(rec.Transcript);
        Check(Utf8.GetString(fs["transcript.edn"]).Contains(":producer {:engine \"fake\"}"), "the producer is in the file");
        emon.Planrun.Transcript t2 = emon.Planrun.FromFiles(fs);
        Check(t2.Producer.Count == 1, "the producer is read back");
        Check(Js(Plain().Replay(One(Input.Bytes(input)), o, t2)) == j, "the replay differs from the recording");
        Options never = o.Copy();
        never.Ocr = Options.OcrPolicy.Never;
        Engine.Converted rn = WithOcr().Record(One(Input.Bytes(input)), never);
        string jn = Js(rn.Model);
        Check(Kinds(rn.Transcript).SequenceEqual(new[] { 2, 2 }) && !jn.Contains("Hello") && !jn.Contains("\"ocr\""), "never: " + Show(Kinds(rn.Transcript)));
        Check(Js(Plain().Convert(One(Input.Bytes(input)), o)) == jn, "no port is not `never`");

        // a scanned PDF page is OCR'd and a born-digital one keeps its text layer
        byte[] scan = Read("test/corpus/synth/scan/h-keyword.pdf");
        Engine.Converted sr = WithOcr().Record(One(Input.Bytes(scan)), o);
        List<emon.Planrun.Entry> es = sr.Transcript.Entries;
        Check(Kinds(sr.Transcript).SequenceEqual(new[] { 2, 3 }) && es[0].Round == 0 && es[1].Round == 1, "scan kinds " + Show(Kinds(sr.Transcript)));
        Check(es[0].Req[6] == 1 && es[0].Bitmap != null, "the DCT filter, with the pixels an engine will read");
        string sj = Js(sr.Model);
        Check(sj.Contains("\"kind\": \"scanned\"") && sj.Contains("Hello"), "scanned");
        Check(Js(Plain().Replay(One(Input.Bytes(scan)), o, emon.Planrun.FromFiles(Files(sr.Transcript)))) == sj, "the scan replay differs");
        string none = Js(Plain().Convert(One(Input.Bytes(scan)), o));
        Check(!none.Contains("Hello") && !none.Contains("\"ocr\"") && none.Contains("\"kind\": \"vector\""), "no port: the page has no text");
        byte[] born = Synth("h-plain");
        Engine.Converted br = WithOcr().Record(One(Input.Bytes(born)), o);
        foreach (emon.Planrun.Entry en in br.Transcript.Entries) {
            Check(en.Req[1] == 1, "a text layer is read as it is: no OCR is asked");
        }
        Check(Js(Plain().Convert(One(Input.Bytes(born)), o)) == Js(br.Model), "the port changed a born-digital Model");
    }

    static void AssetsTest() {
        Engine e = Plain();
        Model.Kind figure = Model.Kind.Named("figure");
        Model m0 = e.Convert(One(Input.Bytes(Read("test/assets/dct-rgb.pdf"))), Options.Defaults());
        Check(m0.Assets().Count == 0 && m0.Find(figure).Count == 1 && !Js(m0).Contains("\"assets\"") && !m0.ToHtml(new Options.Html()).Contains("<img"), "assets off is not off");
        Options o = Options.Defaults();
        o.Assets = Options.AssetsLevel.Images;
        Model m = e.Convert(One(Input.Bytes(Read("test/assets/dct-rgb.pdf"))), o);
        List<Asset> a = m.Assets();
        Check(a.Count == 1, a.Count + " assets");
        Asset a0 = a[0];
        Check(a0.Id() == 0 && a0.Name() == "a0.jpg" && a0.MediaType() == "image/jpeg" && a0.Origin() == "embedded" && a0.From() == "dct", "asset 0");
        Check(a0.Width() == 24 && a0.Height() == 16 && a0.Uses().Count == 1 && a0.Sha256().Length == 64, "asset dimensions");
        byte[] b = a0.Bytes();
        Check(b.Length >= 2 && b[0] == 0xFF && b[1] == 0xD8, "not a JPEG");
        List<Node> figs = m.Find(figure);
        Check(figs.Count == 1 && figs[0].Asset() != null && figs[0].Asset().Id() == 0 && a0.Uses()[0].Node == figs[0].Id(), "the figure is asset 0");
        Check(m.AssetFiles().Count == 1 && m.AssetFiles()[0].Name == "a0.jpg", "files");
        Options.Html inl = new Options.Html(), ext = new Options.Html(), omit = new Options.Html();
        ext.Assets = Options.HtmlAssets.External;
        ext.AssetPrefix = "img/";
        omit.Assets = Options.HtmlAssets.Omit;
        Check(m.ToHtml(inl).Contains("<img src=\"data:image/jpeg;base64,/9j/") && m.ToHtml(ext).Contains("<img src=\"img/a0.jpg\"") && !m.ToHtml(omit).Contains("<img"), "the three HTML modes");
        Model p = e.Convert(One(Input.Bytes(Read("test/assets/flate-rgb8.pdf"))), o);
        List<Asset> pa = p.Assets();
        Check(pa.Count == 1 && pa[0].MediaType() == "image/png" && pa[0].Origin() == "decoded" && pa[0].From() == "flate"
            && pa[0].Bytes()[0] == 0x89 && pa[0].Bytes()[1] == 0x50, "a Flate image is not a decoded PNG");
        Model c = e.Convert(One(Input.Bytes(Read("test/assets/jpx.pdf"))), o);
        string why = c.Find(figure)[0].NoAsset();
        Check(c.Assets().Count == 0 && "codec" == why, "jpx: " + why);
        Options of = o.Copy();
        of.Assets = Options.AssetsLevel.Figures;
        Model f = e.Convert(One(Input.Bytes(Read("test/assets/dct-rgb.pdf"))), of);
        string jm = Js(m);
        int at = jm.IndexOf("\"assets\": \"images\"", StringComparison.Ordinal);
        string expect = at < 0 ? jm : jm.Substring(0, at) + "\"assets\": \"figures\"" + jm.Substring(at + "\"assets\": \"images\"".Length);
        Check(Js(f) == expect, "figures is not images");
        Model n = e.Convert(One(Input.Bytes(Synth("h-plain"))), o);
        Check(n.Assets().Count == 0 && Js(n).Contains("\"assets\": []"), "an empty table");
        // through a 512-byte async source: the same files
        foreach (string name in new[] { "dct-rgb", "flate-rgb8", "place-twice", "smask", "indexed16" }) {
            byte[] bb = Read("test/assets/" + name + ".pdf");
            Model mem = Plain().Convert(One(Input.Bytes(bb)), o);
            Model asy = AsyncEngine(512).Convert(One(Input.Bytes(bb)), o);
            Check(Js(asy) == Js(mem) && asy.ToHtml(new Options.Html()) == mem.ToHtml(new Options.Html()), name + ": the async run differs");
            List<Model.AssetFile> fa = asy.AssetFiles(), fm = mem.AssetFiles();
            Check(fa.Count == fm.Count, name + ": file count");
            for (int i = 0; i < Math.Min(fa.Count, fm.Count); i++) {
                Check(fa[i].Name == fm[i].Name && fa[i].Bytes.SequenceEqual(fm[i].Bytes), name + ": file " + i + " differs");
            }
        }
    }

    /// <summary>A byte source for the Renderer fulfiller's input (never read by the fake renderer).</summary>
    sealed class MemSource : emon.RenderPort.ISource {
        readonly byte[] b;

        public MemSource(byte[] b) {
            this.b = b;
        }

        public long Size() {
            return b.Length;
        }

        public int ReadAt(long off, byte[] buf, int boff, int len) {
            if (off >= b.Length) {
                return -1;
            }
            int n = (int)Math.Min(len, b.Length - off);
            Array.Copy(b, (int)off, buf, boff, n);
            return n;
        }
    }

    /// <summary>A fake Renderer: a flat RGB page of known size, so the fulfiller's records, cuts and failures are what is tested.</summary>
    sealed class FakeRenderer : emon.RenderPort.IRenderer {
        public int Calls = 0;
        readonly bool exact;

        public FakeRenderer(bool exact) {
            this.exact = exact;
        }

        public emon.Port.Bitmap Render(emon.RenderPort.ISource input, byte[] password, emon.RenderPort.Request req) {
            Calls++;
            if (req.Layer == emon.RenderPort.LayerNoWatermark) {
                throw new emon.RenderPort.RenderError(true, "no watermark layer");
            }
            if (req.Page > 0) {
                throw new emon.RenderPort.RenderError(false, "page " + req.Page);
            }
            int w = emon.RenderPort.PixelsOf(100, req.Dpi), h = emon.RenderPort.PixelsOf(50, req.Dpi);
            var whole = new emon.Port.Bitmap(w, h, 3, new byte[w * h * 3]);
            for (int i = 0; i < whole.D.Length; i++) {
                whole.D[i] = unchecked((byte)(i * 7));
            }
            return req.Clip == null ? whole : emon.RenderPort.Cut(whole, req.Clip);
        }

        public int Layers() {
            return 7;
        }

        public bool ExactCrop() {
            return exact;
        }
    }

    static void RenderPortTest() {
        var r = new FakeRenderer(false);
        var f = emon.RenderPort.Fulfiller.ForInput(r, 0, new emon.RenderPort.Input(new MemSource(new byte[4]), null));
        emon.Planrun.Answer a = f.Fulfil(new emon.Planrun.Request(new[] { 7, emon.Planrun.KindRender, 1, 0, 0, 72, 0 }));
        Check(a.Rec.Length == 8 && a.Rec[3] == 0 && a.Rec[4] == 100 && a.Rec[5] == 50 && a.Rec[6] == 3 && a.Rec[7] == 72 && a.Bitmap != null, "a render answers its size");
        emon.Planrun.Answer c = f.Fulfil(new emon.Planrun.Request(new[] { 11, emon.Planrun.KindCrop, 2, 0, 0, 72, 0, 10, 5, 20, 10 }));
        Check(c.Rec[4] == 20 && c.Rec[5] == 10 && r.Calls == 1, "a crop is the cut of the kept whole page: one render");
        emon.Port.Bitmap whole = r.Render(null, null, new emon.RenderPort.Request(0, 72, 0, null));
        bool same = true;
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                for (int k = 0; k < 3; k++) {
                    same &= c.Bitmap.D[(y * 20 + x) * 3 + k] == whole.D[((y + 5) * 100 + x + 10) * 3 + k];
                }
            }
        }
        Check(same, "the crop law: the same pixels, cut out");
        emon.Planrun.Answer u = f.Fulfil(new emon.Planrun.Request(new[] { 7, emon.Planrun.KindRender, 3, 0, 0, 72, 3 }));
        Check(u.Rec[3] == 2 && u.Rec.Length == 4, "an unsupported layer is `unsupported`");
        emon.Planrun.Answer bad = f.Fulfil(new emon.Planrun.Request(new[] { 7, emon.Planrun.KindRender, 4, 0, 5, 72, 0 }));
        Check(bad.Rec[3] == 1, "a failed render is `failed`");
        try {
            f.Fulfil(new emon.Planrun.Request(new[] { 7, emon.Planrun.KindRender, 5, 9, 0, 72, 0 }));
            Check(false, "an unknown input");
        } catch (emon.Planrun.PlanError e) {
            Check(e.Name == "fulfil-failed", "an unknown input is fulfil-failed");
        }
        // an Engine with a Renderer renders a born-digital page under `always` (it asks for its rasters) and asks for nothing under `never`, where
        // it is the same Engine as one with none
        var cfg = Options.Config.Defaults();
        var er = new FakeRenderer(true);
        cfg.Renderer = er;
        byte[] born = Synth("h-plain");
        Options never = Options.Defaults();
        never.Ocr = Options.OcrPolicy.Never;
        Check(Js(new Engine(cfg).Convert(One(Input.Bytes(born)), never)) == Js(Plain().Convert(One(Input.Bytes(born)), never)) && er.Calls == 0,
            "under never an Engine with a Renderer is the same Engine and renders nothing");
        new Engine(cfg).Convert(One(Input.Bytes(born)), Options.Defaults());
        Check(er.Calls > 0, "under always a born-digital page is rendered");
    }

    static void RunOne(string name, Action t) {
        int before = failures;
        try {
            t();
        } catch (Exception e) {
            lock (failLock) {
                failures++;
            }
            Out("  FAIL " + name + ": " + e.GetType().FullName + ": " + e.Message);
            Out(e.StackTrace ?? "");
        }
        Out((failures == before ? "ok   " : "FAIL ") + name);
    }

    /// <summary>
    /// A JBIG2 and JPX decoder that records what it was handed and answers a fixed bitmap: the request names the image's bytes (the input's own) and the
    /// globals (kin's, in the unit's payload), and the fulfiller must be given both.
    /// </summary>
    sealed class SeenCodecs : Options.IPdfCodecs {
        public readonly List<int[]> Seen = new List<int[]>();

        public ImgDec.PdfPix Jpx(byte[] data, long max) {
            throw new ImgDec.Fail(ImgDec.Unsupported, "not read by this codec");
        }

        public ImgDec.PdfPix Jbig2(byte[] data, byte[] globals, long max) {
            Seen.Add(new[] { data.Length, globals.Length });
            byte[] px = new byte[64 * 32];
            for (int i = 0; i < px.Length; i++) {
                px[i] = (byte) (i % 3 == 0 ? 0 : 255);
            }
            return new ImgDec.PdfPix(64, 32, 1, px, true);
        }
    }

    static void DecodeImg() {
        Options o = Options.Defaults();
        o.Assets = Options.AssetsLevel.Images;
        Model.Kind figure = Model.Kind.Named("figure");
        // DCT that cannot pass through (a /Decode) and CCITT: the host's own codecs, no port configured
        string[][] cases = { new[] { "dct-decode", "dct", "24", "16" }, new[] { "dct-a85-decode", "dct", "24", "16" }, new[] { "ccitt-g4", "ccitt", "70", "24" },
            new[] { "ccitt-g4-align", "ccitt", "70", "24" }, new[] { "ccitt-g3-1d", "ccitt", "70", "24" }, new[] { "ccitt-g3-1d-noeol", "ccitt", "70", "24" },
            new[] { "ccitt-g3-1d-align", "ccitt", "70", "24" }, new[] { "ccitt-g3-1d-align-noeol", "ccitt", "70", "24" }, new[] { "ccitt-g3-2d", "ccitt", "70", "24" } };
        foreach (string[] c in cases) {
            Model m = Plain().Convert(One(Input.Bytes(Read("test/assets/" + c[0] + ".pdf"))), o);
            List<Asset> a = m.Assets();
            Check(a.Count == 1 && a[0].MediaType() == "image/png" && a[0].Origin() == "decoded" && a[0].From() == c[1]
                && a[0].Width() == int.Parse(c[2]) && a[0].Height() == int.Parse(c[3]) && a[0].Bytes()[0] == 0x89,
                c[0] + ": not a decoded PNG of " + c[1]);
        }
        // what this host does not read says `codec`: JPX, JBIG2
        foreach (string name in new[] { "jpx", "jbig2-globals" }) {
            Model m = Plain().Convert(One(Input.Bytes(Read("test/assets/" + name + ".pdf"))), o);
            string why = m.Find(figure)[0].NoAsset();
            Check(m.Assets().Count == 0 && why == "codec", name + ": " + why);
        }
        // JBIG2 with its globals, through an embedder's PdfCodecs: the fulfiller sees the image's bytes and the globals (the payload)
        var seen = new SeenCodecs();
        Options.Config cfg = Options.Config.Defaults();
        cfg.PdfCodecs = seen;
        var e = new Engine(cfg);
        byte[] jb = Read("test/assets/jbig2-globals.pdf");
        Model m2 = e.Convert(One(Input.Bytes(jb)), o);
        List<Asset> a2 = m2.Assets();
        Check(a2.Count == 1 && a2[0].From() == "jbig2" && a2[0].Width() == 64 && a2[0].Height() == 32 && a2[0].Origin() == "decoded", "the JBIG2 asset");
        Check(seen.Seen.Count == 1 && seen.Seen[0][0] > 20 && seen.Seen[0][1] == 21, "the fulfiller saw the image's bytes and 21 bytes of globals");
        // a transcript of that run replays without the codecs, also through an async source
        Engine.Converted rec = e.Record(One(Input.Bytes(jb)), o);
        Model r = Plain().Replay(One(Input.Bytes(jb)), o, rec.Transcript);
        Check(Js(r) == Js(m2) && r.AssetFiles().Count == m2.AssetFiles().Count && r.AssetFiles()[0].Bytes.AsSpan().SequenceEqual(m2.AssetFiles()[0].Bytes), "the replay is not the run");
        foreach (string name in new[] { "dct-decode", "ccitt-g4", "ccitt-g4-mask", "dct-smask" }) {
            byte[] b = Read("test/assets/" + name + ".pdf");
            Engine.Converted live = Plain().Record(One(Input.Bytes(b)), o);
            Model asy = AsyncEngine(512).Replay(One(Input.Bytes(b)), o, live.Transcript);
            Check(Js(asy) == Js(live.Model) && asy.AssetFiles().Count == live.Model.AssetFiles().Count, name + ": the replay through an async source is not the run");
        }
    }

    // ---------------------------------------------------------------------------- parallel execution (P10.2, D505-D508)
    //
    // `Parallel` = N: N workers fulfil a round's requests at once, and the page units AHEAD of the loop's are looked at (dry attempts) so that what they will
    // ask is being answered meanwhile. None of it may show in the result: the Model, the transcript (every entry) and the number of times a port is called are
    // the serial run's, at every N; the limit overrides of the conversion are in force on the workers, and a conversion's workers have ended when it has.

    /// <summary>An OCR engine that takes its time and counts the readings going at once and in all.</summary>
    sealed class SlowOcr : IOcr {
        int now, peak, calls;
        readonly FakeOcr inner = new FakeOcr();

        public int Peak => Volatile.Read(ref peak);
        public int Calls => Volatile.Read(ref calls);

        public emon.Planrun.Answer Fulfil(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm, bool keep) {
            int n = Interlocked.Increment(ref now);
            int p;
            while (n > (p = Volatile.Read(ref peak)) && Interlocked.CompareExchange(ref peak, n, p) != p) {
            }
            Interlocked.Increment(ref calls);
            Thread.Sleep(60);
            emon.Planrun.Answer a = inner.Fulfil(req, bm, keep);
            Interlocked.Decrement(ref now);
            return a;
        }

        public List<string[]> Producer() {
            return inner.Producer();
        }
    }

    /// <summary>A renderer that answers every page, counts its calls, and may panic on one page.</summary>
    sealed class CountingRenderer : emon.RenderPort.IRenderer {
        int calls;
        readonly int panicPage;

        public CountingRenderer(int panicPage) {
            this.panicPage = panicPage;
        }

        public int Calls => Volatile.Read(ref calls);

        public emon.Port.Bitmap Render(emon.RenderPort.ISource input, byte[] password, emon.RenderPort.Request req) {
            Interlocked.Increment(ref calls);
            if (req.Page == panicPage) {
                throw new InvalidOperationException("the renderer broke on page " + req.Page);
            }
            var px = new byte[20 * 30 * 3];
            for (int i = 0; i < px.Length; i++) {
                px[i] = unchecked((byte)(i * 7 + req.Layer * 50));
            }
            var whole = new emon.Port.Bitmap(20, 30, 3, px);
            return req.Clip == null ? whole : emon.RenderPort.Cut(whole, req.Clip);
        }

        public int Layers() {
            return 1;
        }

        public bool ExactCrop() {
            return false;
        }
    }

    static Engine ParEngine(IOcr ocr, emon.RenderPort.IRenderer r, int n) {
        var c = Options.Config.Defaults();
        c.Ocr = ocr;
        c.Renderer = r;
        c.Parallel = n;
        return new Engine(c);
    }

    /// <summary>The transcript's entries, comparably.</summary>
    static List<string> EntriesOf(emon.Planrun.Transcript tr) {
        var o = new List<string>();
        foreach (emon.Planrun.Entry e in tr.Entries) {
            o.Add(e.Round + " " + string.Join(",", e.Req) + " " + string.Join(",", e.Ans));
        }
        return o;
    }

    static void ParallelModel() {
        byte[] tiff = Read("test/image/tiff-multi3-rgb-lzw.tif");
        Options o = Options.Defaults();
        var one0 = new SlowOcr();
        Engine.Converted c0 = ParEngine(one0, null, 1).Record(One(Input.Bytes(tiff)), o);
        string j0 = Js(c0.Model);
        Check(one0.Peak == 1 && one0.Calls == 9, "a serial run reads one page at a time: peak " + one0.Peak + " calls " + one0.Calls);
        foreach (int n in new[] { 2, 3, 4, 8 }) {
            var slow = new SlowOcr();
            Engine.Converted c = ParEngine(slow, null, n).Record(One(Input.Bytes(tiff)), o);
            Check(Js(c.Model) == j0, "parallel=" + n + ": the Model differs from the serial one");
            Check(EntriesOf(c.Transcript).SequenceEqual(EntriesOf(c0.Transcript)), "parallel=" + n + ": the transcript differs from the serial one");
            Check(slow.Calls == one0.Calls, "parallel=" + n + ": every page is read once (a look ahead's answer is used, not asked again): " + slow.Calls);
            Check(slow.Peak >= 2 && slow.Peak <= n, "parallel=" + n + ": the pages ahead are read while the loop is on one, at most N at once: peak " + slow.Peak);
        }
        // limit.workers is the most the option is served with: 1 is serial whatever is asked
        var capped = new SlowOcr();
        var cfg = Options.Config.Defaults();
        cfg.Ocr = capped;
        cfg.Parallel = 8;
        cfg.Limits = new[] { new[] { 16, 1 } };
        Check(Js(new Engine(cfg).Convert(One(Input.Bytes(tiff)), o)) == j0 && capped.Peak == 1, "limit.workers=1 is serial: peak " + capped.Peak);
    }

    static void ParallelPorts() {
        // under `always` a born-digital page asks its two renders, then the OCR of the page: the dry attempts ask them ahead; the renderer and the OCR engine
        // are called as many times as in a serial run
        Options o = Options.Defaults();
        var docs = new List<KeyValuePair<string, byte[]>>();
        foreach (string n in Slice) {
            docs.Add(new KeyValuePair<string, byte[]>(n, Synth(n)));
        }
        docs.Add(new KeyValuePair<string, byte[]>("h-keyword scan", Read("test/corpus/synth/scan/h-keyword.pdf")));
        docs.Add(new KeyValuePair<string, byte[]>("multi-frame tiff", Read("test/image/tiff-multi3-rgb-lzw.tif")));
        foreach (KeyValuePair<string, byte[]> d in docs) {
            var fake0 = new CountingRenderer(-1);
            var ocr0 = new SlowOcr();
            Engine.Converted c0 = ParEngine(ocr0, fake0, 1).Record(One(Input.Bytes(d.Value)), o);
            foreach (int n in new[] { 2, 4, 8 }) {
                var fake = new CountingRenderer(-1);
                var ocr = new SlowOcr();
                Engine.Converted c = ParEngine(ocr, fake, n).Record(One(Input.Bytes(d.Value)), o);
                string what = d.Key + " at parallel=" + n;
                Check(Js(c.Model) == Js(c0.Model), what + ": the Model differs");
                Check(EntriesOf(c.Transcript).SequenceEqual(EntriesOf(c0.Transcript)), what + ": the transcript differs");
                Check(fake.Calls == fake0.Calls && ocr.Calls == ocr0.Calls,
                    what + ": the ports are called " + fake.Calls + "/" + ocr.Calls + " times, not " + fake0.Calls + "/" + ocr0.Calls);
            }
        }
    }

    /// <summary>A codec that says which pixel bound it was given: the one the workers see.</summary>
    sealed class BoundCodecs : Options.IPdfCodecs {
        public readonly List<long> Seen = new List<long>();

        public ImgDec.PdfPix Jpx(byte[] data, long max) {
            lock (Seen) {
                Seen.Add(max);
            }
            throw new ImgDec.Fail(ImgDec.Unsupported, "no");
        }

        public ImgDec.PdfPix Jbig2(byte[] data, byte[] globals, long max) {
            lock (Seen) {
                Seen.Add(max);
            }
            throw new ImgDec.Fail(ImgDec.Unsupported, "no");
        }
    }

    static void ParallelLimits() {
        // `limit.pixels` is read where a port decodes (kind 9): on a worker thread. The table is process-wide here (D436) and a conversion with overrides holds
        // it exclusively, so a worker, which runs inside that scope, sees it; no other conversion runs while it is held, so none sees it.
        Options o = Options.Defaults();
        o.Assets = Options.AssetsLevel.Images;
        byte[] doc = Read("test/assets/jbig2-globals.pdf");
        foreach (int n in new[] { 1, 4 }) {
            var codecs = new BoundCodecs();
            var c = Options.Config.Defaults();
            c.PdfCodecs = codecs;
            c.Limits = new[] { new[] { 9, 12345 } };
            c.Parallel = n;
            new Engine(c).Convert(One(Input.Bytes(doc)), o);
            Check(codecs.Seen.SequenceEqual(new long[] { 12345 }), "parallel=" + n + ": the codec was given [" + string.Join(",", codecs.Seen) + "], not the conversion's bound");
        }
        // two engines side by side, one bounded, one not, each converting from several threads
        var tight = new BoundCodecs();
        var free = new BoundCodecs();
        var ct = Options.Config.Defaults();
        var cf = Options.Config.Defaults();
        ct.PdfCodecs = tight;
        ct.Limits = new[] { new[] { 9, 777 } };
        ct.Parallel = 4;
        cf.PdfCodecs = free;
        cf.Parallel = 4;
        var et = new Engine(ct);
        var ef = new Engine(cf);
        var ts = new Thread[8];
        for (int i = 0; i < ts.Length; i++) {
            Engine e = i % 2 == 0 ? et : ef;
            ts[i] = new Thread(() => {
                try {
                    e.Convert(One(Input.Bytes(doc)), o);
                } catch (EmonError x) {
                    Check(false, "a conversion failed: " + x.Message);
                }
            }, 1 << 29);
            ts[i].Start();
        }
        foreach (Thread t in ts) {
            t.Join();
        }
        Check(tight.Seen.SequenceEqual(new long[] { 777, 777, 777, 777 }), "the bounded engine's workers saw [" + string.Join(",", tight.Seen) + "]: its bound, every time");
        Check(free.Seen.SequenceEqual(new long[] { 268435456, 268435456, 268435456, 268435456 }), "the other's saw [" + string.Join(",", free.Seen) + "]: the default, never the first's");
    }

    static void ParallelPanic() {
        byte[] doc = Synth("furn-informative");
        foreach (int n in new[] { 1, 4 }) {
            bool reached = false;
            try {
                ParEngine(null, new CountingRenderer(0), n).Convert(One(Input.Bytes(doc)), Options.Defaults());
            } catch (EmonError e) {
                // the Engine's own named error for a panic of a port (internal), the same at every N
                reached = e.Name() == "internal";
            }
            Check(reached, "parallel=" + n + ": a port that panics is the conversion's `internal` error");
        }
    }

    public static int Run() {
        var t = new Thread(() => {
            RunOne("the adapters give identical JSON and HTML", Adapters);
            RunOne("a 512-byte async source equals the in-memory run", Async512);
            RunOne("every PDF fixture is the same in memory and async", EveryPdfFixture);
            RunOne("encryption errors are distinct and a password opens what it opens", Encryption);
            RunOne("limits: input-bytes against the size, pages, pixels", Limits);
            RunOne("a source that gives fewer bytes than its size is source-changed", Liars);
            RunOne("what is not implemented or not readable is refused by name", Refused);
            RunOne("intake takes any number of inputs and reads no pixels", IntakeTest);
            RunOne("record and replay", RecordReplay);
            RunOne("the Model can be walked", Walk);
            RunOne("the Model is readable from many threads", ManyThreads);
            RunOne("an image becomes pages (and async)", Images);
            RunOne("an image records its pixels and replays without a codec", ImageRecordReplay);
            RunOne("an OCR port reads an image and a scanned page; the transcript replays without it", OcrPort);
            RunOne("assets", AssetsTest);
            RunOne("the Renderer port's fulfiller", RenderPortTest);
            RunOne("decode-img: the host's codecs, the embedder's, and the payload", DecodeImg);
            RunOne("parallel: the serial Model and transcript, and the OCR of a multi-page scan overlapped", ParallelModel);
            RunOne("parallel: a renderer and an OCR port are called as often as in a serial run", ParallelPorts);
            RunOne("parallel: the limit overrides are in force on the workers and only there", ParallelLimits);
            RunOne("parallel: a port that panics is the conversion's error as in a serial run", ParallelPanic);
        }, 1 << 30);
        t.Start();
        t.Join();
        Out(failures == 0 ? "EngineTest: all passed" : "EngineTest: " + failures + " failed");
        return failures == 0 ? 0 : 1;
    }
}
