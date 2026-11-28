// THE ENGINE REGISTRY, C# host (DEVLOG D925, K3; the twin of `host/go/engines.go` and of `host/rust/src/adapter.rs`, read those for the reasoning).
//
// An OCR engine is an ADAPTER that is registered, never wired: a library with the one export `emon_ocr_adapter_v1` (`OcrEngines.Load`, `emon.Ffi.AdapterLib`) or an in-process value
// (`OcrEngines.Add`). Kin decides every rule and this file carries them out: what an adapter may declare (`AdRegisterCheck`, against the engine table), which adapter a request goes
// to (`AdRoute`: the first registered for the OIL engine it names), what an unread page is (`AdRefusal`) and what an answer is held to (`AdAnswerCheck`,
// `OilRecordCheckBitmap`). `OcrEngines` is itself the `IOcr` port: `config.Ocr = engines`. Sync adapters only; a remote one is refused unless `AllowRemote` was set first.
// (Named `OcrEngines` because kin's generated static class `emon.Engines` has the plain name.)

namespace emon.Api;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

/// <summary>What an adapter says it is: the OIL engine it answers as (1 tesseract, 2 ppocr, 3 apple-vision, 4 mlkit-text, 5 windows-media-ocr), its capabilities, the pack kinds it reads with, its flags and its producer pairs.</summary>
public sealed class AdapterInfo {
    public string Name = "adapter";
    public int Engine, Caps, Measured, Needs, Flags;
    public string Config = "", Observed = "";
    public List<string[]> Producer = new List<string[]>();
}

/// <summary>What an adapter answered: the OIL answer record, or Status <see cref="IAdapter.Unsupported"/> / <see cref="IAdapter.Failed"/> with a reason word (the page is then unread, never blank).</summary>
public sealed class Reading {
    public int[] Rec;
    public emon.Port.Bitmap Binary;
    public int Status;
    public string Reason = "";
}

/// <summary>One OCR engine. Read answers one `ocr` request (bitmaps holds its bitmap); keepBinary asks it to keep its thresholded image.</summary>
public interface IAdapter : IDisposable {
    const int Async = 1, Remote = 2, Threadsafe = 4;
    const int Ok = 0, Unsupported = 1, Failed = 2;
    AdapterInfo Info();
    Reading Read(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bitmaps, bool keepBinary);
}

public sealed class OcrEngines : IOcr, IDisposable {
    private sealed class Entry {
        internal IAdapter A;
        internal AdapterInfo Info;
        // the engine whose packs the adapter reads with ("" when kin's table is asked by the adapter's OIL engine)
        internal string Engine = "";
        // the adapter was given its own models directory (`tessdata=`): the pack machinery is not in its way
        internal bool OwnModels;
        internal readonly object Gate = new object();
    }

    private readonly object mu = new object();
    private readonly List<Entry> entries = new List<Entry>();
    private readonly List<string> notes = new List<string>();
    private bool allowRemote;
    private readonly Packs packs = new Packs();
    private string adapterDir = "", tessdata = "";

    /// <summary>The packs (D896): the store, the hook for a missing pack, the progress events, the app's download. A new registry's mode is `never`.</summary>
    public Packs Packs { get { return packs; } }

    /// <summary>Whether a renderer (pdfium) is registered with the host's configuration: `State()` reports the render engines by it.</summary>
    public bool RendererRegistered;

    /// <summary>Lets adapters that send the pixels off the device register (default no: documents may hold personal data). Call it before registering them.</summary>
    public void AllowRemote(bool allow) { lock (mu) { allowRemote = allow; } }

    /// <summary>Names the directory <see cref="Defaults"/> loads adapter libraries from ($EMON_ADAPTER_DIR, else lib/emon/adapters beside the executable).</summary>
    public void SetAdapterDir(string dir) { lock (mu) { adapterDir = dir ?? ""; } }

    /// <summary>Names the models directory <see cref="Defaults"/> gives the Tesseract adapter ($EMON_TESSDATA, $TESSDATA_PREFIX, share/emon/tessdata beside the executable).</summary>
    public void SetTessdata(string dir) { lock (mu) { tessdata = dir ?? ""; } }

    /// <summary>Registers an adapter, held to kin's engine table (`ad-register-check`); it is disposed when refused, and the exception's message is the refusal's name and sentence.</summary>
    public void Add(IAdapter a) {
        Add(a, "", false);
    }

    private void Add(IAdapter a, string engine, bool ownModels) {
        AdapterInfo info = a.Info();
        int check = emon.Adapters.AdRegisterCheck(emon.Adapters.AdAbi(), info.Engine, info.Caps, info.Measured, info.Needs, info.Flags);
        if (check != emon.Adapters.AdrOk()) {
            var why = new StringBuilder();
            emon.Adapters.AdrEmitName(why, check);
            a.Dispose();
            throw new emon.Ffi.Unavailable($"{why}: {info.Name} (engine {info.Engine}, caps {info.Caps}, needs {info.Needs}, flags {info.Flags})");
        }
        if ((info.Flags & IAdapter.Async) != 0) {
            a.Dispose();
            throw new emon.Ffi.Unavailable($"adapter-abi-unsupported: {info.Name} is async; this host registers sync adapters only");
        }
        lock (mu) {
            if ((info.Flags & IAdapter.Remote) != 0 && !allowRemote) {
                a.Dispose();
                throw new emon.Ffi.Unavailable($"adapter-remote-refused: {info.Name} sends the pixels off the device; call AllowRemote(true) first");
            }
            if (!info.Producer.Any(kv => kv[0] == "engine")) {
                info.Producer.Insert(0, new[] { "engine", info.Name });
            }
            entries.Add(new Entry { A = a, Info = info, Engine = engine, OwnModels = ownModels });
        }
    }

    /// <summary>Registers the adapter library at path (`emon_ocr_adapter_v1`); config is the adapter's own key=value lines, packsDir the pack directory (or null).</summary>
    public void Load(string path, string config = null, string packsDir = null) {
        // which engine's packs the adapter is given: named by `engine=<name>` in its config, else the engine of kin's table whose name the file's carries
        string engine = "";
        foreach (string line in (config ?? "").Split('\n')) {
            if (line.StartsWith("engine=")) engine = line.Substring(7).Trim();
        }
        if (engine == "") {
            string stem = Path.GetFileNameWithoutExtension(path).ToLowerInvariant().Replace('-', '_');
            for (int i = 0; i < emon.Engines.PkEngineCount(); i++) {
                int eng = emon.Engines.PkEngineAt(i);
                string n = Packs.EngName(eng);
                if (emon.Engines.PkEngineKind(eng) == emon.Engines.EkindOcr() && stem.Contains(n.Replace('-', '_'))) { engine = n; break; }
            }
        }
        bool own = config != null && config.Split('\n').Any(l => l.StartsWith("tessdata="));
        if (string.IsNullOrEmpty(packsDir) && engine != "") {
            packsDir = packs.View(engine);
        }
        Add(emon.Ffi.AdapterLib.Open(path, config, packsDir), engine, own);
    }

    /// <summary>The turnkey: every libemon_ocr_* of the adapter directory, in name order. The Tesseract adapter is NOT loaded when the selection does not use Tesseract as its reader or fallback (`pk-uses-tesseract`, D920).</summary>
    public void Defaults(Options opts) {
        bool uses = emon.Engines.PkUsesTesseract(emon.Engines.PkOptionEngine(opts.OcrEngine != 0 ? opts.OcrEngine : (opts.PpOcr ? 1 : 0))) == 1;
        string shared = FindPacksDir();
        if (shared != "") packs.AddDir(shared);
        string env = Environment.GetEnvironmentVariable("EMON_PACKS_FETCH") ?? "";
        packs.SetMode(env switch {
            "never" => emon.Engines.PfetchNever(),
            "cache" => emon.Engines.PfetchCache(),
            "auto" => emon.Engines.PfetchAuto(),
            "" => packs.HasFetcher ? emon.Engines.PkDefaultFetch(packs.Class) : emon.Engines.PfetchCache(),
            _ => throw new emon.Ffi.Unavailable($"EMON_PACKS_FETCH is never, cache or auto, not \"{env}\""),
        });
        foreach (string lib in AdapterLibs()) {
            string stem = Path.GetFileNameWithoutExtension(lib);
            string cfg = null;
            if (stem == "libemon_ocr_tesseract" || stem == "emon_ocr_tesseract") {
                if (!uses) {
                    continue;
                }
                string dir = FindTessdata();
                if (dir != "") {
                    cfg = "tessdata=" + dir;
                    packs.AddDir(dir);
                }
            }
            Load(lib, cfg, null);
        }
    }

    private static bool IsLibrary(string n) {
        return OperatingSystem.IsMacOS() ? n.EndsWith(".dylib") : OperatingSystem.IsWindows() ? n.EndsWith(".dll") : n.EndsWith(".so");
    }

    private List<string> AdapterLibs() {
        string dir;
        lock (mu) { dir = adapterDir; }
        if (dir == "") {
            dir = Environment.GetEnvironmentVariable("EMON_ADAPTER_DIR") ?? "";
        }
        Func<string, List<string>> list = d => !Directory.Exists(d) ? new List<string>() : Directory.GetFiles(d)
            .Where(f => { string n = Path.GetFileName(f); return (n.StartsWith("libemon_ocr_") || n.StartsWith("emon_ocr_")) && IsLibrary(n); })
            .OrderBy(f => f, StringComparer.Ordinal).ToList();
        if (dir != "") {
            if (!Directory.Exists(dir)) {
                throw new emon.Ffi.Unavailable($"the adapter directory {dir} is not a directory");
            }
            return list(dir);
        }
        string b = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        string up = Path.GetDirectoryName(b) ?? b;
        foreach (string c in new[] { Path.Combine(b, "emon", "adapters"), Path.Combine(b, "lib", "emon", "adapters"), Path.Combine(up, "lib", "emon", "adapters") }) {
            var v = list(c);
            if (v.Count > 0) {
                return v;
            }
        }
        return new List<string>();
    }

    private static string FindPacksDir() {
        string beside = Path.Combine(Path.GetDirectoryName(AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar)) ?? "", "share", "emon", "packs");
        return Directory.Exists(beside) ? beside : "";
    }

    private string FindTessdata() {
        lock (mu) { if (tessdata != "") { return tessdata; } }
        foreach (string v in new[] { "EMON_TESSDATA", "TESSDATA_PREFIX" }) {
            string d = Environment.GetEnvironmentVariable(v);
            if (!string.IsNullOrEmpty(d)) {
                return d;
            }
        }
        string beside = Path.Combine(Path.GetDirectoryName(AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar)) ?? "", "share", "emon", "tessdata");
        return Directory.Exists(beside) ? beside : "";
    }

    /// <summary>The registered adapters in registration order.</summary>
    public List<AdapterInfo> Describe() {
        lock (mu) { return entries.Select(e => e.Info).ToList(); }
    }

    /// <summary>What adapters reported since the last call (why a request was left unread), one line each.</summary>
    public List<string> Notes() {
        lock (mu) { var n = new List<string>(notes); notes.Clear(); n.AddRange(packs.TakeNotes()); return n; }
    }

    /// <summary>Closes every adapter; the registry is empty after it.</summary>
    public void Dispose() {
        List<Entry> es;
        lock (mu) { es = new List<Entry>(entries); entries.Clear(); }
        foreach (Entry e in es) {
            e.A.Dispose();
        }
    }

    /// <summary>The first adapter's pairs, the combined engine name when there are several (`tesseract+ppocr`), then the other adapters' further keys.</summary>
    public List<string[]> Producer() {
        lock (mu) {
            string combined = null;
            if (entries.Count > 1) {
                combined = string.Join("+", entries.Select(e => { var n = new StringBuilder(); emon.Engines.EngEmitName(n, emon.Adapters.AdOenEng(e.Info.Engine)); return n.ToString(); }));
            }
            var outp = new List<string[]>();
            for (int i = 0; i < entries.Count; i++) {
                foreach (string[] kv in entries[i].Info.Producer) {
                    if (combined != null && kv[0] == "ocr-engine") {
                        continue;
                    }
                    if (!outp.Any(o => o[0] == kv[0])) {
                        outp.Add(kv);
                    }
                }
                if (i == 0 && combined != null) {
                    outp.Add(new[] { "ocr-engine", combined });
                }
            }
            return outp;
        }
    }

    private static emon.Port.Cells CellsFrom(int[] v) {
        emon.Port.Cells c = emon.Port.NewCells();
        foreach (int x in v) {
            emon.Port.CellsPush(c, x);
        }
        return c;
    }

    // ---------------------------------------------------------------------------------------- the packs a request needs

    private static string PackedText(int p) {
        var sb = new StringBuilder();
        foreach (int c in new[] { (p >> 24) & 255, (p >> 16) & 255, (p >> 8) & 255, p & 255 }) if (c > 0) sb.Append((char) c);
        return sb.ToString();
    }

    private static List<int> LanguagesOf(Entry en, emon.Planrun.Request req) {
        // Tesseract's requests name languages; PP-OCR has one recognizer (English)
        var v = new List<int>();
        if (en.Info.Engine != 1) return v;
        emon.Port.Cells c = CellsFrom(req.Rec);
        int n = emon.Ocrport.OpLangCount(c);
        for (int i = 0; i < n; i++) {
            int l = emon.Ocrport.OpLangAt(c, i);
            // a script (ISO 15924) is not a language: no pack is named for it
            if (l > 0 && emon.Ocrport.OpIsScript(l) == 0) v.Add(l);
        }
        return v;
    }

    private static string EngineOf(Entry en) {
        int eng = emon.Adapters.AdOenEng(en.Info.Engine);
        return eng < 0 ? "" : Packs.EngName(eng);
    }

    // (another adapter of the same engine, null) go on; (null, null) go on with this one; (null, reason) the page is unread.
    private (Entry, string) EnsurePacks(Entry en, emon.Planrun.Request req) {
        if (en.Info.Needs == 0 || en.OwnModels) return (null, null);
        int eng = emon.Adapters.AdOenEng(en.Info.Engine);
        if (eng < 0) return (null, null);
        int flags = req.Field(emon.Plan.OcFlags());
        bool osd = (flags & emon.Ocr.OcfOsd()) != 0 && (en.Info.Needs & 2) != 0;
        List<int> langs = LanguagesOf(en, req);
        List<Packs.Row> rows;
        try {
            rows = packs.Resolve(new[] { eng }, langs, osd);
        } catch (emon.Ffi.Unavailable e) {
            return (null, "engine-unavailable: " + e.Message);
        }
        // a language no pack of the catalogue reads is not read; when that is every language the request asked for, the page is unread
        var unreadable = rows.Where(r => r.State == emon.Engines.PstateUnsupported()).ToList();
        if (langs.Count > 0 && unreadable.Count >= langs.Count) return (null, "lang-unavailable:" + PackedText(unreadable[0].Lang));
        string engineName = Packs.EngName(eng);
        bool arrived = false;
        foreach (Packs.Row r in rows.Where(r => r.State == emon.Engines.PstateMissing())) {
            int kind = emon.Engines.PkPackKind(r.Pack);
            Packs.Need n = packs.Needed(r.Pack, "conversion", engineName);
            if (n.Kind == Packs.NeedKind.Ready) {
                // a pack that came is not in the adapter's directory yet: rebuilt below, before it reads
                arrived = true;
            } else if (n.Kind == Packs.NeedKind.Unread) {
                // orientation detection degrades to kin's own policy without its model; it never leaves the page unread
                if (kind != emon.Engines.PkindOsd()) return (null, n.Text);
            } else {
                int e2 = Packs.EngCode(n.Text);
                if (e2 < 0 || emon.Engines.PkEngineOen(e2) != en.Info.Engine) {
                    // another engine would answer under its own name: kin's plan chose this engine, so the request stays unread
                    return (null, "pack-substitute-differs:" + n.Text);
                }
                Entry alt;
                lock (mu) { alt = entries.FirstOrDefault(x => x.Info.Engine == en.Info.Engine && !ReferenceEquals(x, en)); }
                return alt != null ? (alt, null) : (null, "pack-substitute-unavailable:" + n.Text);
            }
        }
        // The bundled PP adapter reads English dictionary entries from the Tesseract pack even when used alone.
        if (eng == emon.Engines.EngPpocr() && en.Info.Producer.Any(kv => kv[0] == "pp-lexicon-pack" && kv[1] == "tess-fast-eng")) {
            int p = Packs.Find("tess-fast-eng");
            if (p == 0) return (null, "pack-missing:tess-fast-eng");
            Packs.Need n = packs.Needed(p, "conversion", engineName);
            if (n.Kind == Packs.NeedKind.Ready) arrived = true;
            else if (n.Kind == Packs.NeedKind.Unread) return (null, n.Text);
            else return (null, "pack-substitute-differs:" + n.Text);
        }
        if (arrived || rows.Any(r => r.State == emon.Engines.PstateInstalled())) {
            // the adapter lists its pack directory for each request, so what it is given must hold what arrived
            try { packs.View(engineName); } catch (emon.Ffi.Unavailable e) { return (null, "pack-dir-missing: " + e.Message); }
        }
        return (null, null);
    }

    // Does the request want orientation detection that the engine's model for it cannot give (the pack is missing and was declined, or could not be fetched)?
    private bool OsdMissing(Entry en, emon.Planrun.Request req) {
        if (en.OwnModels || (en.Info.Needs & 2) == 0 || (req.Field(emon.Plan.OcFlags()) & emon.Ocr.OcfOsd()) == 0) return false;
        int eng = emon.Adapters.AdOenEng(en.Info.Engine);
        if (eng < 0) return false;
        try {
            return packs.Resolve(new[] { eng }, new int[0], true).Any(r => r.State == emon.Engines.PstateMissing() && emon.Engines.PkPackKind(r.Pack) == emon.Engines.PkindOsd());
        } catch (emon.Ffi.Unavailable) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------- engines() and prepare()

    private static string Js(string s) {
        var o = new StringBuilder("\"");
        foreach (char c in s) {
            switch (c) {
                case '"': o.Append("\\\""); break;
                case '\\': o.Append("\\\\"); break;
                case '\n': o.Append("\\n"); break;
                case '\r': o.Append("\\r"); break;
                case '\t': o.Append("\\t"); break;
                default:
                    if (c < 0x20) o.Append("\\u").Append(((int) c).ToString("x4")); else o.Append(c);
                    break;
            }
        }
        return o.Append('"').ToString();
    }

    private static string Name(Action<StringBuilder> emit) {
        var sb = new StringBuilder();
        emit(sb);
        return sb.ToString();
    }

    private bool Registered(int eng) {
        if (emon.Engines.PkEngineKind(eng) == emon.Engines.EkindRender()) return RendererRegistered;
        int oen = emon.Engines.PkEngineOen(eng);
        lock (mu) { return emon.Engines.PkEngineOn(eng, packs.Class) == 1 && entries.Any(e => e.Info.Engine == oen); }
    }

    /// <summary>`engines()` (D896): every engine of kin's table that runs on this platform, as JSON, with its state (built-in, installed, downloadable, unavailable): the C ABI's `emon_engines_describe`, the same shape.</summary>
    public string State() {
        int cls = packs.Class;
        List<int> installed = packs.Installed();
        int md = packs.Mode;
        bool off = Packs.Offline();
        var engines = new List<string>();
        for (int i = 0; i < emon.Engines.PkEngineCount(); i++) {
            int eng = emon.Engines.PkEngineAt(i);
            if (emon.Engines.PkEngineOn(eng, cls) == 0) continue;
            string name = Packs.EngName(eng);
            bool registered = Registered(eng);
            var rows = new List<Packs.Row>();
            if (emon.Engines.PkEngineKind(eng) == emon.Engines.EkindOcr() && emon.Engines.PkEngineNeeds(eng) != 0) {
                try { rows = packs.ResolveWith(new[] { eng }, new int[0], false, installed); } catch (emon.Ffi.Unavailable) { }
            }
            var missing = rows.Where(r => r.State == emon.Engines.PstateMissing()).ToList();
            int state = emon.Adapters.AdEngineState(registered ? 1 : 0, emon.Engines.PkEngineImpl(eng), emon.Engines.PkEngineNeeds(eng), missing.Count, md, off ? 1 : 0);
            string reason = !registered ? "no adapter is registered for it"
                : state == emon.Adapters.EstUnavailable() ? (off ? "EMON_OFFLINE=1" : "a pack is missing and the pack mode does not fetch") : "";
            string needs = string.Join(",", rows.Where(r => r.Pack > 0).Select(r => {
                PackInfo info = Packs.Info(r.Pack);
                return "{\"id\":" + Js(info.Id) + ",\"kind\":" + Js(info.KindName) + ",\"bytes\":" + info.Bytes + ",\"licence\":" + Js(info.Licence) + ",\"installed\":" + (r.State == emon.Engines.PstateInstalled() ? "true" : "false") + "}";
            }));
            long dl = missing.Sum(r => Packs.Info(r.Pack).Bytes);
            engines.Add("{\"name\":" + Js(name) + ",\"kind\":" + Js(Name(sb => emon.Engines.EkindEmitName(sb, emon.Engines.PkEngineKind(eng)))) + ",\"impl\":" + Js(Name(sb => emon.Engines.EimplEmitName(sb, emon.Engines.PkEngineImpl(eng))))
                + ",\"state\":" + Js(Name(sb => emon.Adapters.EstEmitName(sb, state))) + ",\"registered\":" + (registered ? "true" : "false") + ",\"calibrated\":" + (emon.Engines.PkEngineCalibrated(eng) == 1 ? "true" : "false")
                + ",\"caps\":" + emon.Engines.PkEngineCaps(eng) + ",\"oen\":" + emon.Engines.PkEngineOen(eng) + ",\"role\":" + Js(Name(sb => emon.Engines.EroleEmitName(sb, emon.Engines.PkRoleDefault(eng, cls))))
                + ",\"needs\":[" + needs + "],\"download_bytes\":" + dl + ",\"reason\":" + Js(reason) + "}");
        }
        return "{\"platform\":" + Js(Name(sb => emon.Engines.PclsEmitName(sb, cls))) + ",\"mode\":" + Js(Name(sb => emon.Engines.PfetchEmitName(sb, md))) + ",\"offline\":" + (off ? "true" : "false")
            + ",\"store\":" + Js(packs.Store) + ",\"engines\":[" + string.Join(",", engines) + "]}";
    }

    /// <summary>`prepare(options)` (D896): the packs a conversion with these options needs and which are missing, as JSON (verdict ready, partial, downloadable, blocked): the C ABI's `emon_prepare`, the same shape.</summary>
    public string Prepare(string options) {
        Options o = Options.Parse(options ?? "").Options;
        int md = packs.Mode;
        bool off = Packs.Offline();
        bool never = o.Ocr == Options.OcrPolicy.Never;
        int code = o.OcrEngine != 0 ? o.OcrEngine : (o.PpOcr ? 1 : 0);
        int sel = emon.Engines.PkOptionEngine(code);
        var engines = new List<int> { sel };
        if (code == 1) engines.Add(emon.Engines.EngPpocr());
        bool osd = !never && emon.Engines.PkUsesTesseract(sel) == 1;
        var langs = new List<int>();
        if (!string.IsNullOrEmpty(o.Locale)) {
            emon.Port.Cells c = emon.Port.NewCells();
            foreach (char ch in o.Locale) emon.Port.CellsPush(c, ch);
            int lg = emon.Scan.ScLang(c);
            if (lg > 0) langs.Add(lg * 256);
        }
        List<int> installed = packs.Installed();
        List<Packs.Row> rows;
        int verdict;
        if (never) {
            rows = new List<Packs.Row>();
            verdict = emon.Adapters.PverReady();
        } else {
            var (l, e, i) = (emon.Port.NewCells(), emon.Port.NewCells(), emon.Port.NewCells());
            langs.ForEach(v => emon.Port.CellsPush(l, v));
            engines.ForEach(v => emon.Port.CellsPush(e, v));
            installed.ForEach(v => emon.Port.CellsPush(i, v));
            var (outp, scratch) = (emon.Port.NewCells(), emon.Port.NewCells());
            int r = emon.Engines.PkResolve(packs.Class, osd ? emon.Engines.PkfOsd() : 0, l, e, i, outp, scratch);
            if (r != 0) {
                var msg = new StringBuilder();
                emon.Engines.PkExplain(r, outp, msg);
                throw new emon.Ffi.Unavailable(msg.ToString());
            }
            rows = new List<Packs.Row>();
            for (int k = 0; k < emon.Port.CellsLen(outp) / 4; k++) {
                rows.Add(new Packs.Row(emon.Port.CellsAt(outp, 4 * k), emon.Port.CellsAt(outp, 4 * k + 1), emon.Port.CellsAt(outp, 4 * k + 2), emon.Port.CellsAt(outp, 4 * k + 3)));
            }
            verdict = emon.Adapters.AdVerdict(outp, md, off ? 1 : 0);
        }
        Func<int, string> langName = l => PackedText(l & unchecked((int) 0xffffff00));
        var packsJson = new List<string>();
        var unsupported = new List<string>();
        int missing = 0, conditional = 0;
        long missingBytes = 0, conditionalBytes = 0;
        foreach (Packs.Row r in rows) {
            if (r.State == emon.Engines.PstateUnsupported()) {
                unsupported.Add("{\"engine\":" + Js(Packs.EngName(r.Engine)) + ",\"language\":" + Js(langName(r.Lang)) + "}");
                continue;
            }
            PackInfo info = Packs.Info(r.Pack);
            if (r.State == emon.Engines.PstateMissing()) { missing++; missingBytes += info.Bytes; }
            if (r.State == emon.Engines.PstateConditional()) { conditional++; conditionalBytes += info.Bytes; }
            packsJson.Add("{\"id\":" + Js(info.Id) + ",\"state\":" + Js(Name(sb => emon.Engines.PstateEmitName(sb, r.State))) + ",\"engine\":" + Js(info.EngineName) + ",\"kind\":" + Js(info.KindName) + ",\"bytes\":" + info.Bytes + ",\"licence\":" + Js(info.Licence) + ",\"rev\":" + info.Rev + "}");
        }
        IEnumerable<string> engineNames = never ? new string[0] : engines.Select(e => Js(Packs.EngName(e)));
        IEnumerable<string> langNames = never ? new string[0] : langs.Count == 0 ? new[] { Js("eng") } : langs.Select(l => Js(langName(l)));
        return "{\"verdict\":" + Js(Name(sb => emon.Adapters.PverEmitName(sb, verdict))) + ",\"mode\":" + Js(Name(sb => emon.Engines.PfetchEmitName(sb, md))) + ",\"offline\":" + (off ? "true" : "false")
            + ",\"engines\":[" + string.Join(",", engineNames) + "],\"languages\":[" + string.Join(",", langNames) + "],\"osd\":" + (osd ? "true" : "false") + ",\"packs\":[" + string.Join(",", packsJson)
            + "],\"unsupported\":[" + string.Join(",", unsupported) + "],\"missing\":" + missing + ",\"missing_bytes\":" + missingBytes + ",\"conditional\":" + conditional + ",\"conditional_bytes\":" + conditionalBytes + "}";
    }

    /// <summary>Routes the request by the engine it names, reads, and holds the answer to what the adapter registered.</summary>
    public emon.Planrun.Answer Fulfil(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bitmaps, bool keepBinary) {
        int reqOen = emon.Adapters.AdRequestOen(emon.Ocrport.OpRequestEngine(CellsFrom(req.Rec)));
        Entry en = null;
        lock (mu) {
            emon.Port.Cells reg = emon.Port.NewCells();
            foreach (Entry e in entries) {
                emon.Port.CellsPush(reg, e.Info.Engine);
            }
            int at = emon.Adapters.AdRoute(reqOen, reg, 0);
            if (at >= 0 && at < entries.Count) {
                en = entries[at];
            }
        }
        Func<int, emon.Planrun.Answer> refuse = status => {
            emon.Port.Cells o = emon.Port.NewCells();
            emon.Adapters.AdRefusal(o, req.Id(), status);
            return new emon.Planrun.Answer(emon.Port.CellsVec(o));
        };
        if (en == null) {
            return refuse(emon.Adapters.AdsUnsupported());
        }
        // the packs the request needs, made present or refused (D896); the app may name another adapter of the same engine
        for (int round = 0; round < 2; round++) {
            string why;
            Entry other;
            (other, why) = EnsurePacks(en, req);
            if (why != null) {
                lock (mu) { notes.Add($"{en.Info.Name}: unsupported {why}"); }
                return refuse(emon.Adapters.AdsUnsupported());
            }
            if (other == null) break;
            en = other;
        }
        // orientation detection without its model degrades to kin's own policy: the engine is not asked for it
        if (OsdMissing(en, req)) {
            int[] rec2 = (int[]) req.Rec.Clone();
            rec2[3 + emon.Plan.OcFlags()] &= ~emon.Ocr.OcfOsd();
            req = new emon.Planrun.Request(rec2, req.Payload);
        }
        Reading r;
        if ((en.Info.Flags & IAdapter.Threadsafe) == 0) {
            lock (en.Gate) { r = en.A.Read(req, bitmaps, keepBinary); }
        } else {
            r = en.A.Read(req, bitmaps, keepBinary);
        }
        if (r.Status != IAdapter.Ok) {
            lock (mu) { notes.Add($"{en.Info.Name}: {(r.Status == IAdapter.Unsupported ? "unsupported" : "failed")} {r.Reason}"); }
            return refuse(r.Status);
        }
        Func<string, emon.Planrun.Answer> bad = why => throw emon.Planrun.FulfilFailed($"ocr-adapter-invalid: {en.Info.Name}: {why}");
        int[] rec = r.Rec ?? new int[0];
        if (rec.Length < 4 || rec[0] != rec.Length || rec[1] != emon.Plan.PkOcr() || rec[2] != req.Id()) {
            return bad("the answer is not a record of the request's `ocr` answer");
        }
        if (rec[3] != 0) {
            if (rec.Length == 4) {
                return refuse(rec[3] == emon.Plan.PsUnsupported() ? emon.Adapters.AdsUnsupported() : emon.Adapters.AdsFailed());
            }
            return bad("a status that is not ok on a record with a reading");
        }
        if (!bitmaps.TryGetValue(req.Field(emon.Plan.OcBitmap()), out emon.Port.Bitmap b)) {
            return bad("the request names no bitmap");
        }
        emon.Port.Cells c = CellsFrom(rec);
        if (emon.Oil.OilRecordCheckBitmap(c, 0, b.W, b.H, emon.Port.NewCells()) != 0) {
            return bad("the reading is not valid OIL for this bitmap");
        }
        int check = emon.Adapters.AdAnswerCheck(en.Info.Engine, en.Info.Caps, reqOen, emon.Oil.OilGet(c, 0, emon.Oil.OhEngine()), emon.Oil.OilGet(c, 0, emon.Oil.OhCaps()));
        if (check != emon.Adapters.AdaOk()) {
            var why = new StringBuilder();
            emon.Adapters.AdaEmitName(why, check);
            return bad(why.ToString());
        }
        return new emon.Planrun.Answer((int[]) rec.Clone(), keepBinary ? r.Binary : null);
    }
}
