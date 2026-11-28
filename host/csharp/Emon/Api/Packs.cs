// THE PACKS, C# host (DEVLOG D925/D928, K3; the twin of `host/rust/src/packs.rs`, read it for the reasoning; D896, D899, D913).
//
// WHAT IS KIN'S. Which packs exist and their SHA-256 and sizes (the catalogue), which a conversion needs and which are missing (`PkResolve`), and what to do about a missing one
// (`AdPackAction`: never and offline win over every hook; with no hook `auto` fetches and `cache` does not; the hook answers fetch, provide, decline or substitute). This file
// handles directories, files, hashes and the app's callbacks. The library ships NO HTTP client (D913): a FETCH decision is carried out by the app's `PackFetcher`
// (`SetFetcher`); with none it is `pack-fetch-unavailable:<id>` and the page stays unread. An app that has the bytes answers PROVIDE instead.
//
// EVERY PACK IS VERIFIED against the catalogue's SHA-256 and size before it is used, wherever it came from (the store, a directory, bytes or a path the app provided, a download).
// A file in the store that does not match is deleted; one in a directory that is the app's is left alone and not used.
//
// LAYOUT. The STORE `<store>/<id>/<file>`, and per adapter `<store>/.views/<engine>/<file>`: a flat directory of copies of the verified files of the packs that engine reads,
// which is what the adapter is given as its pack directory. Other directories (`AddDir`, `$EMON_MODELS`) are searched read-only, as `<dir>/<id>/<file>` or `<dir>/<file>`.

namespace emon.Api;

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;

/// <summary>One file of a pack, as the catalogue says it.</summary>
public sealed class PackFile {
    public string Name = "";
    public long Bytes;
    public byte[] Sha = new byte[32];

    public bool Matches(byte[] data) {
        return data.LongLength == Bytes && SHA256.HashData(data).AsSpan().SequenceEqual(Sha);
    }
}

/// <summary>One pack of the catalogue: id, revision, the engine it is for ("any" for all), kind, licence, size and files.</summary>
public sealed class PackInfo {
    internal int Code;
    public string Id = "", EngineName = "any", KindName = "", Licence = "";
    public int Rev;
    public long Bytes;
    public List<PackFile> Files = new List<PackFile>();
}

/// <summary>What the library tells the hook about a pack it needs.</summary>
public sealed class PackRequest {
    public string Pack = "", Engine = "", Kind = "", Licence = "", Reason = "", RequestEngine = "";
    public int Rev;
    public long Bytes;
}

/// <summary>What a hook decides (D896).</summary>
public enum PackAction { None = 0, Fetch = 1, Provide = 2, Decline = 3, Substitute = 4 }

/// <summary>The hook's answer: fetch (by the app's fetcher), provide (bytes or a path to the file or to a directory of the pack's files), decline (the page is unread, `pack-missing:&lt;id&gt;`) or substitute (another implementation of the same OIL engine).</summary>
public sealed class PackDecision {
    public PackAction Action;
    public byte[] Data;
    public string Path;
    public string Engine;

    public static PackDecision Fetch() { return new PackDecision { Action = PackAction.Fetch }; }
    public static PackDecision Provide(byte[] data) { return new PackDecision { Action = PackAction.Provide, Data = data }; }
    public static PackDecision ProvidePath(string path) { return new PackDecision { Action = PackAction.Provide, Path = path }; }
    public static PackDecision Decline() { return new PackDecision { Action = PackAction.Decline }; }
    public static PackDecision Substitute(string engine) { return new PackDecision { Action = PackAction.Substitute, Engine = engine }; }
}

public enum PackEventKind { Start = 1, Progress = 2, Done = 3, Failed = 4, Cancelled = 5 }

/// <summary>One step of a pack's download (library- or app-initiated): the one progress/cancel stream of D896.</summary>
public sealed class PackEvent {
    public PackEventKind Kind;
    public string Pack = "", Engine = "", Message = "";
    public long Done, Total;
    /// <summary>true when the app asked for it (`Ensure`), false when a conversion did.</summary>
    public bool ByApp;
}

/// <summary>The app's download of one file of a pack: report bytes received through <paramref name="progress"/> (true: stop); return the bytes, or throw.</summary>
public delegate byte[] PackFetcher(PackInfo pack, PackFile file, Func<long, bool> progress);

public sealed class Packs {
    /// <summary>What becomes of a pack a request needs.</summary>
    internal enum NeedKind { Ready, Unread, Substitute }

    internal readonly struct Need {
        internal readonly NeedKind Kind;
        internal readonly string Text;
        internal Need(NeedKind k, string t) { Kind = k; Text = t; }
    }

    /// <summary>One row of `pk-resolve`'s answer.</summary>
    internal readonly struct Row {
        internal readonly int State, Engine, Pack, Lang;
        internal Row(int s, int e, int p, int l) { State = s; Engine = e; Pack = p; Lang = l; }
    }

    private readonly object mu = new object();
    private readonly object fetchGate = new object();
    private string store;
    private readonly List<string> extra = new List<string>();
    private int mode = emon.Engines.PfetchNever();
    private Func<PackRequest, PackDecision> hook;
    private Func<PackEvent, bool> events;
    private PackFetcher fetcher;
    private volatile bool cancel;
    private readonly Dictionary<string, (long, long)> good = new Dictionary<string, (long, long)>();
    private readonly List<string> notes = new List<string>();
    internal readonly int Class = PlatformClass();

    internal Packs() {
        store = DefaultStore();
    }

    internal static int PlatformClass() {
        if (OperatingSystem.IsMacOS()) return emon.Engines.PclsMacos();
        if (OperatingSystem.IsIOS()) return emon.Engines.PclsIos();
        if (OperatingSystem.IsWindows()) return emon.Engines.PclsWindows();
        if (OperatingSystem.IsAndroid()) return emon.Engines.PclsAndroid();
        if (OperatingSystem.IsBrowser()) return emon.Engines.PclsWeb();
        return emon.Engines.PclsLinux();
    }

    private static string Env(string k) {
        string v = Environment.GetEnvironmentVariable(k);
        return string.IsNullOrEmpty(v) ? null : v;
    }

    /// <summary>The store when nobody names one: $EMON_PACK_CACHE, else the user's cache directory.</summary>
    public static string DefaultStore() {
        string d = Env("EMON_PACK_CACHE");
        if (d != null) return d;
        string home = Env("HOME");
        string p = null;
        if (OperatingSystem.IsMacOS() || OperatingSystem.IsIOS()) {
            p = home == null ? null : Path.Combine(home, "Library", "Caches", "emon", "packs");
        } else if (OperatingSystem.IsWindows()) {
            string l = Env("LOCALAPPDATA");
            p = l == null ? null : Path.Combine(l, "emon", "packs");
        } else {
            string x = Env("XDG_CACHE_HOME");
            p = x != null ? Path.Combine(x, "emon", "packs") : home == null ? null : Path.Combine(home, ".cache", "emon", "packs");
        }
        try {
            if (p != null) {
                Directory.CreateDirectory(p);
                return p;
            }
        } catch (Exception) {
        }
        return Path.Combine(Path.GetTempPath(), "emon-packs");
    }

    /// <summary>`EMON_OFFLINE=1`: nothing is fetched and no hook is asked, whatever the mode says.</summary>
    public static bool Offline() { return Env("EMON_OFFLINE") == "1"; }

    // ---------------------------------------------------------------------------------------- the catalogue

    internal static int Count() { return emon.Engines.PkPackCount(); }

    internal static string PackId(int p) {
        var s = new StringBuilder();
        emon.Packdata.PkidEmitName(s, p);
        return s.ToString();
    }

    internal static int Find(string name) {
        byte[] b = Encoding.UTF8.GetBytes(name);
        int c = emon.Packdata.PkidFind(b, 0, b.Length);
        return c > 0 && emon.Engines.PkPackKnown(c) == 1 ? c : 0;
    }

    internal static string EngName(int eng) {
        var s = new StringBuilder();
        emon.Engines.EngEmitName(s, eng);
        return s.ToString();
    }

    internal static int EngCode(string name) {
        byte[] b = Encoding.UTF8.GetBytes(name);
        return emon.Engines.EngFind(b, 0, b.Length);
    }

    internal static PackInfo Info(int p) {
        var info = new PackInfo { Code = p, Id = PackId(p), Rev = emon.Engines.PkPackRev(p), Bytes = emon.Engines.PkPackBytes(p) };
        int n = emon.Engines.PkPackFileCount(p);
        for (int i = 0; i < n; i++) {
            var name = new StringBuilder();
            emon.Engines.PkPackFileName(p, i, name);
            var f = new PackFile { Name = name.ToString(), Bytes = (uint) emon.Engines.PkPackFileBytes(p, i) };
            for (int w = 0; w < 8; w++) {
                int v = emon.Engines.PkPackFileSha(p, i, w);
                f.Sha[4 * w] = (byte) (v >> 24); f.Sha[4 * w + 1] = (byte) (v >> 16); f.Sha[4 * w + 2] = (byte) (v >> 8); f.Sha[4 * w + 3] = (byte) v;
            }
            info.Files.Add(f);
        }
        int eng = emon.Engines.PkPackEngine(p);
        if (eng != 0) info.EngineName = EngName(eng);
        var kind = new StringBuilder();
        emon.Engines.PkindEmitName(kind, emon.Engines.PkPackKind(p));
        info.KindName = kind.ToString();
        var lic = new StringBuilder();
        emon.Packdata.PlicEmitName(lic, emon.Engines.PkPackLicence(p));
        info.Licence = lic.ToString();
        return info;
    }

    // ---------------------------------------------------------------------------------------- configuration

    /// <summary>The directory the library owns: downloads and provided packs are kept in it, and each adapter's view is built in it. Name it before registering adapters.</summary>
    public string Store {
        get { lock (mu) { return store; } }
    }

    public void SetStore(string dir) {
        try {
            Directory.CreateDirectory(dir);
        } catch (Exception e) {
            throw new emon.Ffi.Unavailable($"pack-dir-missing: the pack directory {dir} cannot be made: {e.Message}");
        }
        lock (mu) { store = dir; }
    }

    /// <summary>A directory that is searched, read-only, for packs (as `&lt;dir&gt;/&lt;id&gt;/&lt;file&gt;` or `&lt;dir&gt;/&lt;file&gt;`).</summary>
    public void AddDir(string dir) {
        lock (mu) { if (!extra.Contains(dir)) extra.Add(dir); }
    }

    /// <summary>The fetch mode (kin's `pfetch`): `Engines.PfetchNever()` (a new registry's), `PfetchCache()`, `PfetchAuto()`.</summary>
    public int Mode {
        get { lock (mu) { return mode; } }
    }

    public void SetMode(int m) {
        lock (mu) { mode = m; }
    }

    /// <summary>The hook that decides about a missing pack (D896). An exception it throws is no decision (the pack stays missing).</summary>
    public void OnPackNeeded(Func<PackRequest, PackDecision> f) {
        lock (mu) { hook = f; }
    }

    /// <summary>The one progress stream of library- and app-initiated downloads: return true to cancel the download.</summary>
    public void OnPackEvent(Func<PackEvent, bool> f) {
        lock (mu) { events = f; }
    }

    /// <summary>The app's download (the library has no HTTP client, D913).</summary>
    public void SetFetcher(PackFetcher f) {
        lock (mu) { fetcher = f; }
    }

    internal bool HasFetcher { get { lock (mu) { return fetcher != null; } } }

    /// <summary>Stops the running download (any thread): the pack is then missing, `pack-fetch-cancelled:&lt;id&gt;`.</summary>
    public void Cancel() { cancel = true; }

    private void Note(string s) {
        lock (mu) { notes.Add(s); }
    }

    /// <summary>What happened to packs since the last call: mismatches, failed fetches.</summary>
    public List<string> TakeNotes() {
        lock (mu) { var n = new List<string>(notes); notes.Clear(); return n; }
    }

    // ---------------------------------------------------------------------------------------- finding and verifying

    private List<(string path, bool ours)> Candidates(PackInfo info, PackFile f) {
        var v = new List<(string, bool)> { (Path.Combine(Store, info.Id, f.Name), true) };
        List<string> dirs;
        lock (mu) { dirs = new List<string>(extra); }
        string m = Env("EMON_MODELS");
        if (m != null) dirs.AddRange(m.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries));
        foreach (string d in dirs) {
            v.Add((Path.Combine(d, info.Id, f.Name), false));
            v.Add((Path.Combine(d, f.Name), false));
        }
        return v;
    }

    private bool Verify(string path, PackFile f) {
        FileInfo fi;
        try {
            fi = new FileInfo(path);
            if (!fi.Exists || fi.Length != f.Bytes) return false;
        } catch (Exception) {
            return false;
        }
        var stamp = (fi.Length, fi.LastWriteTimeUtc.Ticks);
        lock (mu) { if (good.TryGetValue(path, out var s) && s == stamp) return true; }
        try {
            using var fs = File.OpenRead(path);
            byte[] h = SHA256.HashData(fs);
            if (h.AsSpan().SequenceEqual(f.Sha)) {
                lock (mu) { good[path] = stamp; }
                return true;
            }
        } catch (Exception) {
        }
        return false;
    }

    /// <summary>The verified files of pack p (one per file of the pack), or null when any is absent or does not match.</summary>
    internal List<string> Locate(int p) {
        PackInfo info = Info(p);
        var outp = new List<string>();
        foreach (PackFile f in info.Files) {
            string found = null;
            foreach (var (path, ours) in Candidates(info, f)) {
                if (!File.Exists(path)) continue;
                if (Verify(path, f)) { found = path; break; }
                Note($"pack-hash-mismatch:{info.Id}");
                if (ours) {
                    try { File.Delete(path); } catch (Exception) { }
                }
            }
            if (found == null) return null;
            outp.Add(found);
        }
        return outp;
    }

    internal List<int> Installed() {
        var v = new List<int>();
        for (int p = 1; p <= Count(); p++) if (Locate(p) != null) v.Add(p);
        return v;
    }

    /// <summary>The flat directory an adapter of the engine (kin's name) reads: copies of the verified files of the packs it reads. Rebuilt each time, so a pack that arrived since is in it.</summary>
    internal string View(string engine) {
        string dir = Path.Combine(Store, ".views", engine);
        try {
            Directory.CreateDirectory(dir);
        } catch (Exception e) {
            throw new emon.Ffi.Unavailable($"pack-dir-missing: {dir}: {e.Message}");
        }
        int eng = Math.Max(EngCode(engine), 0);
        // The bundled PP adapter uses the English Tesseract pack as its lexicon.
        int lexicon = engine == "ppocr" ? Find("tess-fast-eng") : 0;
        var want = new Dictionary<string, string>();
        for (int p = 1; p <= Count(); p++) {
            int pe = emon.Engines.PkPackEngine(p);
            if (pe != 0 && pe != eng && lexicon != p) continue;
            List<string> paths = Locate(p);
            if (paths == null) continue;
            PackInfo info = Info(p);
            for (int i = 0; i < info.Files.Count; i++) want[info.Files[i].Name] = paths[i];
        }
        foreach (string f in Directory.GetFiles(dir)) {
            if (!want.ContainsKey(Path.GetFileName(f))) {
                try { File.Delete(f); } catch (Exception) { }
            }
        }
        foreach (var (name, src) in want) {
            string dst = Path.Combine(dir, name);
            var a = new FileInfo(dst);
            var b = new FileInfo(src);
            if (a.Exists && a.Length == b.Length && a.LastWriteTimeUtc == b.LastWriteTimeUtc) continue;
            try {
                File.Copy(src, dst, true);
                File.SetLastWriteTimeUtc(dst, b.LastWriteTimeUtc);
            } catch (Exception e) {
                throw new emon.Ffi.Unavailable($"pack-dir-missing: {dst}: {e.Message}");
            }
        }
        return dir;
    }

    // ---------------------------------------------------------------------------------------- what a conversion needs

    internal List<Row> Resolve(IEnumerable<int> engines, IEnumerable<int> langs, bool osd) {
        return ResolveWith(engines, langs, osd, Installed());
    }

    internal List<Row> ResolveWith(IEnumerable<int> engines, IEnumerable<int> langs, bool osd, IEnumerable<int> installed) {
        var (l, e, i) = (emon.Port.NewCells(), emon.Port.NewCells(), emon.Port.NewCells());
        foreach (int v in langs) emon.Port.CellsPush(l, v);
        foreach (int v in engines) emon.Port.CellsPush(e, v);
        foreach (int v in installed) emon.Port.CellsPush(i, v);
        var (outp, scratch) = (emon.Port.NewCells(), emon.Port.NewCells());
        int r = emon.Engines.PkResolve(Class, osd ? emon.Engines.PkfOsd() : 0, l, e, i, outp, scratch);
        if (r != 0) {
            var msg = new StringBuilder();
            emon.Engines.PkExplain(r, outp, msg);
            throw new emon.Ffi.Unavailable(msg.ToString());
        }
        var rows = new List<Row>();
        for (int k = 0; k < emon.Port.CellsLen(outp) / 4; k++) {
            rows.Add(new Row(emon.Port.CellsAt(outp, 4 * k), emon.Port.CellsAt(outp, 4 * k + 1), emon.Port.CellsAt(outp, 4 * k + 2), emon.Port.CellsAt(outp, 4 * k + 3)));
        }
        return rows;
    }

    // ---------------------------------------------------------------------------------------- the hook, events

    private bool Emit(PackEventKind kind, PackInfo info, long done, long total, bool app, string message) {
        Func<PackEvent, bool> f;
        lock (mu) { f = events; }
        bool stop = false;
        if (f != null) {
            try {
                stop = f(new PackEvent { Kind = kind, Pack = info.Id, Engine = info.EngineName, Done = done, Total = total, ByApp = app, Message = message });
            } catch (Exception) {
            }
        }
        return stop || cancel;
    }

    private PackDecision Ask(PackInfo info, string reason, string requestEngine) {
        Func<PackRequest, PackDecision> h;
        lock (mu) { h = hook; }
        if (h == null) return new PackDecision();
        try {
            return h(new PackRequest { Pack = info.Id, Rev = info.Rev, Engine = info.EngineName, Kind = info.KindName, Licence = info.Licence, Bytes = info.Bytes, Reason = reason, RequestEngine = requestEngine }) ?? new PackDecision();
        } catch (Exception) {
            return new PackDecision();
        }
    }

    // ---------------------------------------------------------------------------------------- a pack a request needs

    /// <summary>What becomes of pack p, which a conversion needs. Kin decides what to do (`ad-pack-action`); this does it.</summary>
    internal Need Needed(int p, string reason, string requestEngine) {
        PackInfo info = Info(p);
        bool present = Locate(p) != null;
        int md = Mode;
        bool off = Offline();
        bool hasHook, hasFetcher;
        lock (mu) { hasHook = hook != null; hasFetcher = fetcher != null; }
        bool asks = emon.Adapters.AdAsksHook(present ? 1 : 0, md, off ? 1 : 0, hasHook ? 1 : 0) == 1;
        PackDecision d = asks ? Ask(info, reason, requestEngine) : new PackDecision();
        int action = emon.Adapters.AdPackAction(present ? 1 : 0, md, off ? 1 : 0, hasHook ? 1 : 0, (int) d.Action);
        if (action == emon.Adapters.PactUse()) return new Need(NeedKind.Ready, "");
        if (action == emon.Adapters.PactFetch()) {
            string e = Fetch(info, false);
            return e == null ? new Need(NeedKind.Ready, "") : new Need(NeedKind.Unread, e);
        }
        if (action == emon.Adapters.PactProvided()) {
            string e = Install(info, d);
            return e == null ? new Need(NeedKind.Ready, "") : new Need(NeedKind.Unread, e);
        }
        if (action == emon.Adapters.PactSubstitute()) return new Need(NeedKind.Substitute, d.Engine ?? "");
        if (asks && d.Action == PackAction.Fetch && !hasFetcher) {
            string e = $"pack-fetch-unavailable:{info.Id}";
            Note(e);
            return new Need(NeedKind.Unread, e);
        }
        return new Need(NeedKind.Unread, $"pack-missing:{info.Id}");
    }

    private string Install(PackInfo info, PackDecision d) {
        string id = info.Id;
        var staged = new List<(PackFile, byte[])>();
        foreach (PackFile f in info.Files) {
            byte[] bytes;
            if (d.Data != null && d.Data.Length > 0) {
                if (info.Files.Count != 1) return $"pack-provide-failed:{id}";
                bytes = d.Data;
            } else if (!string.IsNullOrEmpty(d.Path)) {
                try {
                    bytes = File.ReadAllBytes(Directory.Exists(d.Path) ? Path.Combine(d.Path, f.Name) : d.Path);
                } catch (Exception) {
                    return $"pack-provide-failed:{id}";
                }
            } else {
                return $"pack-provide-failed:{id}";
            }
            if (!f.Matches(bytes)) {
                Note($"pack-hash-mismatch:{id}");
                return $"pack-hash-mismatch:{id}";
            }
            staged.Add((f, bytes));
        }
        return Keep(info, staged) ? null : $"pack-provide-failed:{id}";
    }

    private bool Keep(PackInfo info, List<(PackFile, byte[])> staged) {
        try {
            string dir = Path.Combine(Store, info.Id);
            Directory.CreateDirectory(dir);
            foreach (var (f, bytes) in staged) {
                string dst = Path.Combine(dir, f.Name);
                string tmp = dst + ".part" + Environment.ProcessId;
                File.WriteAllBytes(tmp, bytes);
                File.Move(tmp, dst, true);
            }
        } catch (Exception) {
            return false;
        }
        return Locate(info.Code) != null;
    }

    /// <summary>The packs of the ids, fetched now by the app's fetcher (`packs.ensure`): progress and cancel through <see cref="OnPackEvent"/>. Only when the mode lets anything be fetched.</summary>
    public void Ensure(params string[] ids) {
        if (Offline()) throw new emon.Ffi.Unavailable("EMON_OFFLINE=1: nothing is fetched");
        if (Mode != emon.Engines.PfetchAuto()) throw new emon.Ffi.Unavailable("the pack mode does not fetch (it is `never` or `cache`)");
        foreach (string id in ids) {
            int p = Find(id);
            if (p == 0) throw new emon.Ffi.Unavailable($"pack-unknown:{id}");
            if (Locate(p) != null) continue;
            string e = Fetch(Info(p), true);
            if (e != null) throw new emon.Ffi.Unavailable(e);
        }
    }

    // Ask the app for the files. Verify all of them before installing any. Null on success, else the error's text.
    private string Fetch(PackInfo info, bool app) {
        lock (fetchGate) {
            if (Locate(info.Code) != null) return null;
            string id = info.Id;
            PackFetcher f;
            lock (mu) { f = fetcher; }
            if (f == null) {
                string u = $"pack-fetch-unavailable:{id}";
                Note(u);
                return u;
            }
            cancel = false;
            Emit(PackEventKind.Start, info, 0, info.Bytes, app, "");
            long baseBytes = 0;
            var staged = new List<(PackFile, byte[])>();
            foreach (PackFile file in info.Files) {
                bool cancelled = cancel;
                long received = 0;
                byte[] bytes = null;
                string failure = null;
                try {
                    bytes = f(info, file, done => {
                        done = Math.Min(done, file.Bytes);
                        if (done > received) {
                            received = done;
                            cancelled |= Emit(PackEventKind.Progress, info, baseBytes + done, info.Bytes, app, "");
                        }
                        cancelled |= cancel;
                        return cancelled;
                    });
                } catch (Exception e) {
                    failure = e.Message;
                }
                if (cancelled || cancel) {
                    Emit(PackEventKind.Cancelled, info, baseBytes, info.Bytes, app, "");
                    string c = $"pack-fetch-cancelled:{id}";
                    Note(c);
                    return c;
                }
                if (failure != null || bytes == null) {
                    string e = $"pack-fetch-failed:{id}: {failure ?? "no bytes"}";
                    Note(e);
                    Emit(PackEventKind.Failed, info, baseBytes, info.Bytes, app, e);
                    return e;
                }
                if (!file.Matches(bytes)) {
                    string e = $"pack-hash-mismatch:{id}";
                    Note(e);
                    Emit(PackEventKind.Failed, info, baseBytes, info.Bytes, app, e);
                    return e;
                }
                staged.Add((file, bytes));
                baseBytes += file.Bytes;
            }
            if (!Keep(info, staged)) {
                string e = $"pack-fetch-failed:{id}: installed files could not be verified";
                Note(e);
                Emit(PackEventKind.Failed, info, baseBytes, info.Bytes, app, e);
                return e;
            }
            Emit(PackEventKind.Done, info, info.Bytes, info.Bytes, app, "");
            return null;
        }
    }
}
