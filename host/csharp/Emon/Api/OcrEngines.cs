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
        internal readonly object Gate = new object();
    }

    private readonly object mu = new object();
    private readonly List<Entry> entries = new List<Entry>();
    private readonly List<string> notes = new List<string>();
    private bool allowRemote;
    private string adapterDir = "", tessdata = "";

    /// <summary>Lets adapters that send the pixels off the device register (default no: documents may hold personal data). Call it before registering them.</summary>
    public void AllowRemote(bool allow) { lock (mu) { allowRemote = allow; } }

    /// <summary>Names the directory <see cref="Defaults"/> loads adapter libraries from ($EMON_ADAPTER_DIR, else lib/emon/adapters beside the executable).</summary>
    public void SetAdapterDir(string dir) { lock (mu) { adapterDir = dir ?? ""; } }

    /// <summary>Names the models directory <see cref="Defaults"/> gives the Tesseract adapter ($EMON_TESSDATA, $TESSDATA_PREFIX, share/emon/tessdata beside the executable).</summary>
    public void SetTessdata(string dir) { lock (mu) { tessdata = dir ?? ""; } }

    /// <summary>Registers an adapter, held to kin's engine table (`ad-register-check`); it is disposed when refused, and the exception's message is the refusal's name and sentence.</summary>
    public void Add(IAdapter a) {
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
            entries.Add(new Entry { A = a, Info = info });
        }
    }

    /// <summary>Registers the adapter library at path (`emon_ocr_adapter_v1`); config is the adapter's own key=value lines, packsDir the pack directory (or null).</summary>
    public void Load(string path, string config = null, string packsDir = null) {
        Add(emon.Ffi.AdapterLib.Open(path, config, packsDir));
    }

    /// <summary>The turnkey: every libemon_ocr_* of the adapter directory, in name order. The Tesseract adapter is NOT loaded when the selection does not use Tesseract as its reader or fallback (`pk-uses-tesseract`, D920).</summary>
    public void Defaults(Options opts) {
        bool uses = emon.Engines.PkUsesTesseract(emon.Engines.PkOptionEngine(opts.OcrEngine != 0 ? opts.OcrEngine : (opts.PpOcr ? 1 : 0))) == 1;
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
        lock (mu) { var n = new List<string>(notes); notes.Clear(); return n; }
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
