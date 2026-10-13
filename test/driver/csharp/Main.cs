// THE C# DRIVER (DEVLOG P9.2): a test-only CLI over the Engine API, the twin of `test/driver/go/main.go`, `test/driver/rust/main.rs`
// and `test/driver/java/Main.java` with the same flags, the same stdout and the same exit codes, so that `bb identity` can hold
// them byte for byte. Its STDOUT is the only thing gates read; stats and errors go to stderr. It is not the library: it
// reads the file itself and hands the Engine bytes, a seekable stream or a spooled stream (D058).
//
//   emon-cs [--out=json|html|evidence|intake|bundle|pixels] [--pretty] [--html=OPTS] [--locale=TAG]
//           [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium[=LIBDIR]]
//           [--assets=none|images|figures] [--password=TEXT]
//           [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D]
//           [--html-assets=inline|external|omit] [--asset-prefix=P]
//           [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats]
//           [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE
//
//   --concurrent=N   run N conversions at once on one Engine; all N must give the same answer (`bb repeat`). (It was `--parallel=N` until P10.2: that
//                    is now kin's option, `parallel=N`, N workers inside ONE conversion; `bb repeat` runs both.)
//   --parallel=N     kin's option (SPEC 13, P10.2): spread one conversion's fulfilment over N workers; the output is the serial run's at any N
//   --fake-ports     TEST ONLY (P10.2): a deterministic Renderer and Ocr stand in for pdfium and Tesseract, so the fulfilment path runs on every document with no
//                    engine installed (`bb repeat` holds serial and parallel runs of it to the same bytes)
//
//   emon-cs --engine-tests    runs the Engine API's native tests instead (EngineTest.cs)
//
// The flags mean what the Java driver's mean (see test/driver/java/Main.java); `--source=seek` and `readerat` give the Engine a
// seekable FileStream, `spool` a forward-only stream that is spooled. --pdfium and --tesseract bind the libraries by P/Invoke.
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using System.Threading;
using emon.Api;

public static class Program {
    // `--fake-ports` (P10.2): a deterministic Renderer and Ocr, so that the fulfilment path (a page unit's renders, decodes and OCR, the pages ahead looked at,
    // a round fulfilled by several workers) runs on every document of a gate with no engine installed. The Renderer answers a 20 x 30 RGB page whose pixels are
    // a function of the page, the layer, the resolution and the position; the Ocr answers every request with the word `Hello` in one line, through the kin
    // builders a real engine's adapter uses. Both are pure functions of the request, as the real engines are (the property `bb repeat` holds them to).
    sealed class FakeRenderer : emon.RenderPort.IRenderer {
        public emon.Port.Bitmap Render(emon.RenderPort.ISource input, byte[] password, emon.RenderPort.Request req) {
            var px = new byte[20 * 30 * 3];
            for (int y = 0; y < 30; y++) {
                for (int x = 0; x < 20; x++) {
                    byte v = unchecked((byte)(x * 7 + y * 3 + req.Layer * 50 + req.Page * 11 + req.Dpi));
                    px[(y * 20 + x) * 3] = v;
                    px[(y * 20 + x) * 3 + 1] = (byte)(v ^ 0x55);
                    px[(y * 20 + x) * 3 + 2] = (byte)(255 - v);
                }
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

    sealed class FakeOcr : IOcr {
        public emon.Planrun.Answer Fulfil(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm, bool keep) {
            emon.Port.Cells c = emon.Port.NewCells();
            int at = emon.Plan.AnOcr(c, req.Id());
            int w = emon.Plan.AnOcrWord(c, at, 10, 10, 100, 30, 900, 0, 0);
            foreach (Rune cp in "Hello".EnumerateRunes()) {
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

    sealed class ExitException : Exception {
        public readonly int Code;

        public ExitException(int code) {
            Code = code;
        }
    }

    static readonly UTF8Encoding Utf8 = new UTF8Encoding(false);
    static readonly Stream ErrStream = Console.OpenStandardError();

    static void Err(string s) {
        byte[] b = Utf8.GetBytes(s);
        ErrStream.Write(b, 0, b.Length);
        ErrStream.Flush();
    }

    static void Usage(string msg) {
        Err("emon-cs: " + msg + "\n");
        Err("usage: emon-cs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium[=LIBDIR]] [--assets=none|images|figures] [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D] [--html-assets=inline|external|omit] [--asset-prefix=P] [--password=TEXT] [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE\n");
        throw new ExitException(2);
    }

    static string Describe(Exception e) {
        if (Environment.GetEnvironmentVariable("EMON_CS_STACK") == "1") {
            return e.ToString();
        }
        return e.GetType().FullName + ": " + e.Message;
    }

    static Options.Html HtmlOptions(string list) {
        var o = new Options.Html();
        foreach (string item in list.Split(',')) {
            if (item.Length == 0) {
                continue;
            } else if (item == "ids") {
                o.Ids = true;
            } else if (item == "geometry") {
                o.Geometry = true;
            } else if (item == "ledger") {
                o.Ledger = true;
            } else if (item == "pretty") {
                o.Pretty = true;
            } else if (item == "drop-furniture") {
                o.FurnitureDrop = true;
            } else if (item == "no-milestones") {
                o.Milestones = false;
            } else if (item == "initials-plain") {
                o.InitialsPlain = true;
            } else if (item == "align-style") {
                o.AlignStyle = true;
            } else if (item == "assets=inline") {
                o.Assets = Options.HtmlAssets.Inline;
            } else if (item == "assets=external") {
                o.Assets = Options.HtmlAssets.External;
            } else if (item == "assets=omit") {
                o.Assets = Options.HtmlAssets.Omit;
            } else if (item.StartsWith("prefix=", StringComparison.Ordinal)) {
                o.AssetPrefix = item.Substring("prefix=".Length);
            } else if (item.StartsWith("confidence=", StringComparison.Ordinal)) {
                if (ulong.TryParse(item.Substring("confidence=".Length), NumberStyles.None, CultureInfo.InvariantCulture, out ulong n) && n <= 0xFFFFFFFFUL) {
                    o.ConfidenceBelow = unchecked((int)(uint)n);
                } else {
                    Usage("confidence=N wants a number");
                }
            } else {
                Usage("unknown html option " + item);
            }
        }
        return o;
    }

    static bool ParseInt(string s, out int n) {
        return int.TryParse(s, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out n);
    }

    // the `NAME=VALUE` line of `--NAME=VALUE` when NAME is a key of kin's options grammar (`emon.config`), else null
    static string KinFlag(string a) {
        if (!a.StartsWith("--", StringComparison.Ordinal)) {
            return null;
        }
        string flag = a.Substring(2);
        int eq = flag.IndexOf('=');
        if (eq < 0 || !Options.IsKey(flag.Substring(0, eq))) {
            return null;
        }
        return flag;
    }

    static Dictionary<string, byte[]> ReadDir(string dir) {
        var files = new Dictionary<string, byte[]>();
        foreach (string p in Directory.GetFiles(dir)) {
            files[Path.GetFileName(p)] = File.ReadAllBytes(p);
        }
        return files;
    }

    /// <summary>A stream that cannot seek, so that `Spooled` is shown to need only forward reads.</summary>
    sealed class ForwardStream : Stream {
        readonly Stream raw;

        public ForwardStream(Stream raw) {
            this.raw = raw;
        }

        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override void Flush() {
        }

        public override int Read(byte[] buffer, int offset, int count) {
            return raw.Read(buffer, offset, count);
        }

        public override long Seek(long offset, SeekOrigin origin) {
            throw new NotSupportedException();
        }

        public override void SetLength(long value) {
            throw new NotSupportedException();
        }

        public override void Write(byte[] buffer, int offset, int count) {
            throw new NotSupportedException();
        }
    }

    /// <summary>One conversion's whole answer: the text, or the error.</summary>
    sealed class Result {
        public string Text;
        public Exception Error;
        public RunStats Stats;
        public emon.Planrun.Transcript Trans;
    }

    static void Fail(string msg, Exception e) {
        Err("emon-cs: " + msg + ": " + Describe(e) + "\n");
        throw new ExitException(1);
    }

    static void ErrExit(Exception e) {
        if (e is EmonError x) {
            Err("error: " + x.Name() + "\n" + x.Message + "\n");
            throw new ExitException(x.Kind == EmonError.ErrorKind.Internal ? 1 : 4);
        }
        Err("error: internal\n" + Describe(e) + "\n");
        throw new ExitException(1);
    }

    static bool Same(Result a, Result b) {
        return a.Text == b.Text && (a.Error == null) == (b.Error == null) && (a.Error == null || a.Error.Message == b.Error.Message);
    }

    static void Run(string[] args, Stream stdout) {
        string outName = "json";
        bool pretty = false;
        var html = new Options.Html();
        int htmlAssets = -1;
        string assetPrefix = "";
        bool prefixSet = false;
        var kinText = new StringBuilder();
        bool simulateAsync = false, noRetarget = false;
        string password = null;
        string source = "bytes";
        bool stats = false;
        string file = "", record = "", replay = "", dump = "", assetDir = "";
        bool tess = false, pdfium = false, fakePorts = false;
        string tessdata = "", pdfiumLib = "";
        int concurrent = 1;
        foreach (string a in args) {
            if (a.StartsWith("--out=", StringComparison.Ordinal)) {
                outName = a.Substring("--out=".Length);
            } else if (a == "--pretty") {
                pretty = true;
            } else if (a.StartsWith("--html=", StringComparison.Ordinal)) {
                html = HtmlOptions(a.Substring("--html=".Length));
            } else if (a.StartsWith("--html-assets=", StringComparison.Ordinal)) {
                switch (a.Substring("--html-assets=".Length)) {
                    case "inline": htmlAssets = (int)Options.HtmlAssets.Inline; break;
                    case "external": htmlAssets = (int)Options.HtmlAssets.External; break;
                    case "omit": htmlAssets = (int)Options.HtmlAssets.Omit; break;
                    default: Usage("--html-assets is inline, external or omit"); break;
                }
            } else if (a.StartsWith("--asset-prefix=", StringComparison.Ordinal)) {
                assetPrefix = a.Substring("--asset-prefix=".Length);
                prefixSet = true;
            } else if (a.StartsWith("--asset-dir=", StringComparison.Ordinal)) {
                assetDir = a.Substring("--asset-dir=".Length);
            } else if (a.StartsWith("--password=", StringComparison.Ordinal)) {
                password = a.Substring("--password=".Length);
            } else if (a.StartsWith("--source=", StringComparison.Ordinal)) {
                source = a.Substring("--source=".Length);
            } else if (a == "--async") {
                simulateAsync = true;
            } else if (a == "--no-retarget") {
                noRetarget = true;
            } else if (a == "--tesseract") {
                tess = true;
            } else if (a.StartsWith("--tesseract=", StringComparison.Ordinal)) {
                tess = true;
                tessdata = a.Substring("--tesseract=".Length);
            } else if (a == "--fake-ports") {
                fakePorts = true;
            } else if (a == "--pdfium") {
                pdfium = true;
            } else if (a.StartsWith("--pdfium=", StringComparison.Ordinal)) {
                pdfium = true;
                pdfiumLib = a.Substring("--pdfium=".Length);
            } else if (a == "--stats") {
                stats = true;
            } else if (a.StartsWith("--dump=", StringComparison.Ordinal)) {
                dump = a.Substring("--dump=".Length);
            } else if (a.StartsWith("--record=", StringComparison.Ordinal)) {
                record = a.Substring("--record=".Length);
            } else if (a.StartsWith("--replay=", StringComparison.Ordinal)) {
                replay = a.Substring("--replay=".Length);
            } else if (a.StartsWith("--concurrent=", StringComparison.Ordinal)) {
                if (!ParseInt(a.Substring("--concurrent=".Length), out int n)) {
                    n = -1;
                }
                if (n < 1) {
                    Usage("--concurrent=N wants a positive number");
                }
                concurrent = n;
            } else if (KinFlag(a) != null) {
                // a key of kin's options grammar (`emon.config`): --ocr=never, --asset-dpi=300, --limit.pages=5, --chunk=512, ...
                kinText.Append(KinFlag(a)).Append('\n');
            } else if (a.StartsWith("--", StringComparison.Ordinal)) {
                Usage("unknown flag " + a);
            } else if (file.Length == 0) {
                file = a;
            } else {
                Usage("one FILE");
            }
        }
        if (file.Length == 0) {
            Usage("no FILE");
        }
        if (record.Length != 0 && replay.Length != 0) {
            Usage("--record and --replay are two ways");
        }
        // THE OPTIONS AND THE ENGINE'S BOUNDS ARE KIN'S GRAMMAR: the flags it knows were collected as `key=value` lines and are parsed once.
        Options.Parsed parsed = null;
        try {
            parsed = Options.Parse(kinText.ToString());
        } catch (ArgumentException e) {
            Usage(e.Message);
        }
        Options opts = parsed.Options;
        Options.Config cfg = parsed.Config;
        cfg.SimulateAsync = simulateAsync;
        opts.NoRetarget = noRetarget;
        opts.KeepEvidence = outName == "evidence" || outName == "bundle";
        if (htmlAssets >= 0) {
            html.Assets = (Options.HtmlAssets)htmlAssets;
        }
        if (prefixSet) {
            html.AssetPrefix = assetPrefix;
        }

        // THE DRIVER READS THE FILE (the library never does): four ways of handing the Engine the same bytes.
        Input input = null;
        try {
            switch (source) {
                case "bytes":
                    input = Input.Bytes(File.ReadAllBytes(file));
                    break;
                case "readerat":
                case "seek":
                    input = Input.Seekable(new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read));
                    break;
                case "spool":
                    // a plain forward-only stream: no seek is used
                    input = Input.Spooled(new ForwardStream(new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read)), 1L << 30);
                    break;
                default:
                    Usage("--source is bytes, readerat, seek or spool");
                    break;
            }
        } catch (IOException e) {
            Fail(file, e);
        } catch (UnauthorizedAccessException e) {
            Fail(file, e);
        }
        if (password != null) {
            input = input.WithPassword(Utf8.GetBytes(password));
        }
        var inputs = new List<Input> { input };
        if (tess) {
            try {
                cfg.Ocr = emon.Ffi.Tess.Open(tessdata.Length == 0 ? null : tessdata);
            } catch (emon.Ffi.Unavailable e) {
                Usage(e.Message);
            }
        }
        if (pdfium) {
            try {
                cfg.Renderer = emon.Ffi.Pdfium.Open(pdfiumLib.Length == 0 ? null : pdfiumLib);
            } catch (emon.Ffi.Unavailable e) {
                Usage(e.Message);
            }
        }
        if (fakePorts) {
            if (tess || pdfium) {
                Usage("--fake-ports is the stand-in for --tesseract and --pdfium: not with them");
            }
            cfg.Ocr = new FakeOcr();
            cfg.Renderer = new FakeRenderer();
        }
        var engine = new Engine(cfg);

        if (outName == "intake") {
            try {
                foreach (Intake i in engine.Intake(inputs)) {
                    byte[] b = Utf8.GetBytes(i.Line() + "\n");
                    stdout.Write(b, 0, b.Length);
                }
            } catch (EmonError e) {
                ErrExit(e);
            }
            return;
        }
        switch (outName) {
            case "json":
            case "html":
            case "evidence":
            case "bundle":
            case "pixels":
                break;
            default:
                Usage("--out is json, html, evidence, bundle, pixels or intake");
                break;
        }

        emon.Planrun.Transcript tin = null;
        if (replay.Length != 0) {
            Dictionary<string, byte[]> files = null;
            try {
                files = ReadDir(replay);
            } catch (IOException e) {
                Fail(replay, e);
            } catch (UnauthorizedAccessException e) {
                Fail(replay, e);
            }
            try {
                tin = emon.Planrun.FromFiles(files);
            } catch (emon.Planrun.PlanError e) {
                ErrExit(EmonError.FromPlan(e));
            }
        }

        string fOut = outName, fRecord = record, fDump = dump, fAssetDir = assetDir;
        bool fPretty = pretty;
        Options.Html fHtml = html;
        emon.Planrun.Transcript fTin = tin;
        int fConcurrent = concurrent;
        Options fOpts = opts;
        Func<Result> one = () => {
            var r = new Result();
            try {
                if (fOut == "pixels") {
                    Engine.Converted c = engine.Record(inputs, fOpts);
                    r.Text = PixelLines(c.Transcript, fDump);
                    return r;
                }
                Model m;
                if (fTin != null) {
                    m = engine.Replay(inputs, fOpts, fTin);
                } else if (fRecord.Length != 0) {
                    Engine.Converted c = engine.Record(inputs, fOpts);
                    m = c.Model;
                    r.Trans = c.Transcript;
                } else {
                    Engine.Converted c = engine.ConvertStats(inputs, fOpts);
                    m = c.Model;
                    r.Stats = c.Stats;
                }
                if (fAssetDir.Length != 0 && fConcurrent == 1) {
                    Directory.CreateDirectory(fAssetDir);
                    foreach (Model.AssetFile f in m.AssetFiles()) {
                        File.WriteAllBytes(Path.Combine(fAssetDir, f.Name), f.Bytes);
                    }
                }
                r.Text = Render(engine, inputs, m, fOut, fPretty, fHtml);
            } catch (EmonError e) {
                r.Error = e;
                r.Text = "";
            } catch (IOException e) {
                Fail(fAssetDir, e);
            }
            return r;
        };

        var results = new Result[concurrent];
        if (concurrent == 1) {
            results[0] = one();
        } else {
            var ts = new Thread[concurrent];
            for (int i = 0; i < concurrent; i++) {
                int k = i;
                ts[i] = new Thread(() => {
                    try {
                        results[k] = one();
                    } catch (ExitException) {
                        results[k] = new Result { Text = "", Error = new InvalidOperationException("exit") };
                    }
                }, 1 << 29);
                ts[i].Start();
            }
            foreach (Thread t in ts) {
                t.Join();
            }
        }
        for (int i = 1; i < results.Length; i++) {
            if (!Same(results[0], results[i])) {
                Err("error: nondeterministic\nconversion " + i + " of " + concurrent + " differs from conversion 0\n");
                throw new ExitException(1);
            }
        }
        Result res = results[0];
        if (res.Error != null) {
            ErrExit(res.Error);
        }
        if (record.Length != 0) {
            Directory.CreateDirectory(record);
            foreach (emon.Planrun.File f in res.Trans.ToFiles()) {
                File.WriteAllBytes(Path.Combine(record, f.Name), f.Bytes);
            }
        }
        if (stats && res.Stats != null) {
            RunStats s = res.Stats;
            Err("stats: attempts=" + s.Attempts + " fetch-rounds=" + s.FetchRounds + " bytes-requests=" + s.BytesRequests
                + " source-reads=" + s.SourceReads + " bytes-fetched=" + s.BytesFetched + " of " + s.InputBytes + " requests=" + s.Requests + "\n");
            // P7.S (D210): wall time by stage, microseconds (the Rust driver's `time-stats`, same words)
            Err("time-stats: render-us=" + s.FulfilUs[1] + " crop-us=" + s.FulfilUs[5] + " decode-us=" + s.FulfilUs[2] + " ocr-first-us=" + s.FulfilUs[3]
                + " ocr-later-us=" + s.FulfilUs[7] + " pass-us=" + s.PassUs + " pages-us=" + s.PagesUs + " finish-us=" + s.FinishUs + "\n");
        }
        byte[] outBytes = Utf8.GetBytes(res.Text);
        stdout.Write(outBytes, 0, outBytes.Length);
    }

    /// <summary>The `--out=pixels` text of a recording: its decode entries, in order.</summary>
    static string PixelLines(emon.Planrun.Transcript t, string dump) {
        var sb = new StringBuilder();
        foreach (emon.Planrun.Entry e in t.Entries) {
            if (e.Req.Length < 8 || e.Req[1] != 2 || e.Req[7] < 0) {
                continue;
            }
            if (e.Ans[3] != 0) {
                sb.Append("frame ").Append(e.Req[7]).Append(" status ").Append(e.Ans[3]).Append('\n');
                continue;
            }
            ulong h = 0xcbf29ce484222325UL;
            if (e.Bitmap != null) {
                unchecked {
                    foreach (byte x in e.Bitmap.D) {
                        h ^= x;
                        h *= 0x100000001b3UL;
                    }
                }
                if (dump.Length != 0) {
                    Directory.CreateDirectory(dump);
                    File.WriteAllBytes(Path.Combine(dump, "frame-" + e.Req[7] + ".raw"), e.Bitmap.D);
                }
            }
            sb.Append("frame ").Append(e.Req[7]).Append(" w ").Append(e.Ans[4]).Append(" h ").Append(e.Ans[5]).Append(" ch ").Append(e.Ans[6])
                .Append(" dpi100 ").Append(e.Ans[7]).Append(" bilevel ").Append(e.Ans[8]).Append(" frames ").Append(e.Ans[9])
                .Append(" fnv ").Append(h.ToString("x16", CultureInfo.InvariantCulture)).Append('\n');
        }
        return sb.ToString();
    }

    static string Hex(byte[] b) {
        return Convert.ToHexString(b).ToLowerInvariant();
    }

    /// <summary>The text of `--out=NAME` (or the whole bundle) for a converted Model.</summary>
    static string Render(Engine engine, List<Input> inputs, Model m, string outName, bool pretty, Options.Html html) {
        switch (outName) {
            case "json": {
                var o = new Options.Output();
                o.Json = new Options.Json(pretty);
                return m.Serialize(o);
            }
            case "html": {
                var o = new Options.Output();
                o.Html = html;
                return m.Serialize(o);
            }
            case "evidence": {
                string ev0 = m.Evidence();
                return ev0 ?? "";
            }
            default:
                break;
        }
        var full = new Options.Html();
        full.Ids = true;
        full.Geometry = true;
        full.ConfidenceBelow = 950;
        full.Ledger = true;
        full.Pretty = true;
        string intake;
        try {
            var lines = new List<string>();
            foreach (Intake i in engine.Intake(inputs)) {
                lines.Add(i.Line());
            }
            intake = string.Join("\n", lines);
        } catch (EmonError e) {
            intake = "error: " + e.Name();
        }
        string ev = m.Evidence() ?? "";
        var def = new Options.Html();
        var s = new StringBuilder();
        s.Append("== json\n").Append(m.ToJson(new Options.Json())).Append("\n== json-pretty\n").Append(m.ToJson(new Options.Json(true)))
            .Append("\n== html\n").Append(m.ToHtml(def)).Append("\n== html-full\n").Append(m.ToHtml(full))
            .Append("\n== evidence\n").Append(ev).Append("\n== intake\n").Append(intake).Append("\n");
        // SPEC 10.11 / 14.1: with assets on, the HTML in the other two modes and every asset file, byte for byte (hex)
        if (m.Root().Attr("assets") != null) {
            var external = new Options.Html();
            external.Assets = Options.HtmlAssets.External;
            external.AssetPrefix = "assets/";
            var omit = new Options.Html();
            omit.Assets = Options.HtmlAssets.Omit;
            s.Append("== html-external\n").Append(m.ToHtml(external)).Append("\n== html-omit\n").Append(m.ToHtml(omit)).Append("\n== assets\n");
            foreach (Model.AssetFile f in m.AssetFiles()) {
                s.Append(f.Name).Append(' ').Append(f.MediaType).Append(' ').Append(f.Bytes.Length).Append('\n').Append(Hex(f.Bytes)).Append('\n');
            }
        }
        return s.ToString();
    }

    public static int Main(string[] args) {
        if (args.Length > 0 && args[0] == "--engine-tests") {
            return EngineTest.Run();
        }
        // Deep recursion is how kin's stages are written (Go grows its stacks; the CLR does not): run on a big stack.
        int code = 0;
        var t = new Thread(() => {
            Stream stdout = new BufferedStream(Console.OpenStandardOutput(), 1 << 16);
            try {
                try {
                    Run(args, stdout);
                } finally {
                    stdout.Flush();
                }
            } catch (ExitException e) {
                code = e.Code;
            } catch (Exception e) {
                Err("error: internal\n" + Describe(e) + "\n");
                code = 1;
            }
        }, 1 << 30);
        t.Start();
        t.Join();
        return code;
    }
}
