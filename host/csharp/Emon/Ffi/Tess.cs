// The C# host's binding to Tesseract's C API through .NET's native interop (DEVLOG P9.2; P6.5, D211-D214): the twin of
// `host/java/emon/ffi/Tess.java`, `host/go/tess.go` and `host/rust/src/tess.rs`, and read against them. LIVE ONLY: the libraries
// (`libtesseract`, `libleptonica`) are found at run time and nothing in `bb gates` loads them (a gate replays a transcript; the
// engine is the host's opinion, D008). `Tess.Open` is the `IOcr` port an embedder puts in `Config.Ocr`.
//
// Where the libraries are looked for: `$EMON_TESS_LIB` (a directory), then /opt/homebrew/lib, /usr/local/lib, the usual
// Linux library directories, then the dynamic loader's own search. The models: the `tessdata` argument, else
// `$TESSDATA_PREFIX` (and its `tessdata`) and the usual places.
//
// WHAT THE C API DOES NOT GIVE (D213): `PageIterator::RowAttributes` is not exported, so a line's row attributes are
// answered as -1 and kin measures them from the symbols' boxes.
//
// The filesystem is touched only in the clearly marked `TEST SUPPORT` helpers (tessdata discovery and the producer's
// model hashes), as Go's tess.go does: this file is a live binding, not the library.

namespace emon.Ffi;

using System.Collections.Generic;

public sealed unsafe class Tess : emon.Api.IOcr {
    private const int RilBlock = 0, RilPara = 1, RilTextline = 2, RilWord = 3, RilSymbol = 4;

    // The P/Invoke declarations. "tesseract" and "lept" are LOGICAL names: Native's one DllImport resolver maps them to the
    // run-time paths (Open pins them). The C names are the entry points; the C# names are the ones the code below calls.
    private const string T = "tesseract";
    private const string L = "lept";
    private const System.Runtime.InteropServices.CallingConvention Cc = System.Runtime.InteropServices.CallingConvention.Cdecl;

    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessVersion", CallingConvention = Cc)] private static extern nint version();
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPICreate", CallingConvention = Cc)] private static extern nint create();
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIDelete", CallingConvention = Cc)] private static extern void delete(nint h);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIInit3", CallingConvention = Cc)] private static extern int init3(nint h, nint datapath, nint language);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPISetVariable", CallingConvention = Cc)] private static extern int setVariable(nint h, nint name, nint value);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPISetPageSegMode", CallingConvention = Cc)] private static extern void setPageSegMode(nint h, int mode);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPISetImage", CallingConvention = Cc)] private static extern void setImage(nint h, nint data, int w, int hh, int bytesPerPixel, int bytesPerLine);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPISetSourceResolution", CallingConvention = Cc)] private static extern void setSourceResolution(nint h, int ppi);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPISetRectangle", CallingConvention = Cc)] private static extern void setRectangle(nint h, int left, int top, int w, int hh);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIRecognize", CallingConvention = Cc)] private static extern int recognize(nint h, nint monitor);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIClear", CallingConvention = Cc)] private static extern void clear(nint h);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIGetIterator", CallingConvention = Cc)] private static extern nint getIterator(nint h);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIGetThresholdedImage", CallingConvention = Cc)] private static extern nint getThresholded(nint h);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessBaseAPIDetectOrientationScript", CallingConvention = Cc)] private static extern int detectOsd(nint h, nint orientDeg, nint orientConf, nint scriptName, nint scriptConf);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessDeleteText", CallingConvention = Cc)] private static extern void deleteText(nint p);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorDelete", CallingConvention = Cc)] private static extern void itDelete(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorCopy", CallingConvention = Cc)] private static extern nint itCopy(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorGetPageIterator", CallingConvention = Cc)] private static extern nint itPageIterator(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorNext", CallingConvention = Cc)] private static extern int itNext(nint it, int level);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorGetUTF8Text", CallingConvention = Cc)] private static extern nint itText(nint it, int level);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorConfidence", CallingConvention = Cc)] private static extern float itConfidence(nint it, int level);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorWordRecognitionLanguage", CallingConvention = Cc)] private static extern nint itWordLang(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorWordIsFromDictionary", CallingConvention = Cc)] private static extern int itIsDict(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorWordIsNumeric", CallingConvention = Cc)] private static extern int itIsNumeric(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorWordFontAttributes", CallingConvention = Cc)] private static extern nint itFont(nint it, nint bold, nint italic, nint underlined, nint monospace, nint serif, nint smallcaps, nint size, nint fontId);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessResultIteratorGetChoiceIterator", CallingConvention = Cc)] private static extern nint itChoiceIterator(nint it);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessChoiceIteratorNext", CallingConvention = Cc)] private static extern int chNext(nint ci);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessChoiceIteratorGetUTF8Text", CallingConvention = Cc)] private static extern nint chText(nint ci);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessChoiceIteratorConfidence", CallingConvention = Cc)] private static extern float chConf(nint ci);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessChoiceIteratorDelete", CallingConvention = Cc)] private static extern void chDelete(nint ci);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorIsAtBeginningOf", CallingConvention = Cc)] private static extern int piAtBeginning(nint pi, int level);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorIsAtFinalElement", CallingConvention = Cc)] private static extern int piAtFinal(nint pi, int level, int element);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorBoundingBox", CallingConvention = Cc)] private static extern int piBox(nint pi, int level, nint left, nint top, nint right, nint bottom);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorBaseline", CallingConvention = Cc)] private static extern int piBaseline(nint pi, int level, nint x1, nint y1, nint x2, nint y2);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorBlockType", CallingConvention = Cc)] private static extern int piBlockType(nint pi);
    [System.Runtime.InteropServices.DllImport(T, EntryPoint = "TessPageIteratorOrientation", CallingConvention = Cc)] private static extern void piOrientation(nint pi, nint orientation, nint writingDirection, nint textlineOrder, nint deskewAngle);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "pixGetWidth", CallingConvention = Cc)] private static extern int pixW(nint pix);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "pixGetHeight", CallingConvention = Cc)] private static extern int pixH(nint pix);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "pixGetDepth", CallingConvention = Cc)] private static extern int pixDepth(nint pix);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "pixGetPixel", CallingConvention = Cc)] private static extern int pixPixel(nint pix, int x, int y, nint val);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "pixDestroy", CallingConvention = Cc)] private static extern void pixDestroy(nint ppix);
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "getLeptonicaVersion", CallingConvention = Cc)] private static extern nint leptVersion();
    [System.Runtime.InteropServices.DllImport(L, EntryPoint = "lept_free", CallingConvention = Cc)] private static extern void leptFree(nint p);

    private static readonly string[] TessEntries = {
        "TessVersion", "TessBaseAPICreate", "TessBaseAPIDelete", "TessBaseAPIInit3", "TessBaseAPISetVariable", "TessBaseAPISetPageSegMode",
        "TessBaseAPISetImage", "TessBaseAPISetSourceResolution", "TessBaseAPISetRectangle", "TessBaseAPIRecognize", "TessBaseAPIClear",
        "TessBaseAPIGetIterator", "TessBaseAPIGetThresholdedImage", "TessBaseAPIDetectOrientationScript", "TessDeleteText",
        "TessResultIteratorDelete", "TessResultIteratorCopy", "TessResultIteratorGetPageIterator", "TessResultIteratorNext",
        "TessResultIteratorGetUTF8Text", "TessResultIteratorConfidence", "TessResultIteratorWordRecognitionLanguage",
        "TessResultIteratorWordIsFromDictionary", "TessResultIteratorWordIsNumeric", "TessResultIteratorWordFontAttributes",
        "TessResultIteratorGetChoiceIterator", "TessChoiceIteratorNext", "TessChoiceIteratorGetUTF8Text", "TessChoiceIteratorConfidence",
        "TessChoiceIteratorDelete", "TessPageIteratorIsAtBeginningOf", "TessPageIteratorIsAtFinalElement", "TessPageIteratorBoundingBox",
        "TessPageIteratorBaseline", "TessPageIteratorBlockType", "TessPageIteratorOrientation" };
    private static readonly string[] LeptEntries = {
        "pixGetWidth", "pixGetHeight", "pixGetDepth", "pixGetPixel", "pixDestroy", "getLeptonicaVersion", "lept_free" };


    private readonly string tessdata;
    private readonly Dictionary<string, nint> apis = new Dictionary<string, nint>();
    // the resolution given to the engine when a request names none (the request's own flags win); 0: none, as Go and the Engine
    private volatile int dpi = 0;

    /// Sets the resolution used when a request carries none (the spike's recording used the render's 150).
    public Tess WithDefaultDpi(int dpi) {
        this.dpi = dpi;
        return this;
    }

    private readonly List<string> notes = new List<string>();
    private readonly List<string[]> producer;

    private Tess(string tessdata) {
        this.tessdata = tessdata;
        this.producer = MakeProducer();
    }

    /// Opens the engine. `tessdata` is the directory of `.traineddata` files; null looks in $TESSDATA_PREFIX and the usual places.
    /// Throws Unavailable when the libraries or the models cannot be found.
    public static Tess Open(string tessdata) {
        string dir = !string.IsNullOrEmpty(tessdata) ? tessdata : FindTessdata();
        if (dir == null) {
            throw new Unavailable("no tessdata directory found (set TESSDATA_PREFIX, or pass the directory; `brew install tesseract` has the English model)");
        }
        var dirs = new List<string>();
        string env = System.Environment.GetEnvironmentVariable("EMON_TESS_LIB");
        if (!string.IsNullOrEmpty(env)) {
            dirs.Add(env);
        }
        dirs.AddRange(new[] { "/opt/homebrew/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib" });
        nint lept = Pin(L, dirs, new[] { "libleptonica.dylib", "libleptonica.so", "liblept.so.5", "libleptonica.so.6", "libleptonica-6.dll" }, "leptonica", LeptEntries);
        nint tess = Pin(T, dirs, new[] { "libtesseract.dylib", "libtesseract.so", "libtesseract.so.5", "libtesseract55.dll" }, "tesseract", TessEntries);
        _ = lept;
        _ = tess;
        try {
            return new Tess(dir);
        } catch (System.DllNotFoundException e) {
            throw new Unavailable("the Tesseract/Leptonica libraries could not be loaded: " + e.Message, e);
        } catch (System.EntryPointNotFoundException e) {
            throw new Unavailable("the Tesseract/Leptonica library lacks an entry point: " + e.Message, e);
        }
    }

    // Pins the logical name to the first library found in dirs (then the bare name) and checks its entry points: a missing
    // library or symbol is Unavailable, here, not an exception in the middle of a recognition.
    private static nint Pin(string logical, List<string> dirs, string[] files, string bare, string[] entries) {
        var tried = new List<string>();
        foreach (string d in dirs) {
            foreach (string f in files) {
                tried.Add(d + "/" + f);
            }
        }
        tried.Add(bare);
        nint h = Native.Pin(logical, tried);
        if (h == 0) {
            throw new Unavailable(bare + " was not found (looked in " + string.Join(", ", dirs) + "). Install Tesseract (`brew install tesseract`) or set EMON_TESS_LIB to the library directory.");
        }
        foreach (string e in entries) {
            if (!System.Runtime.InteropServices.NativeLibrary.TryGetExport(h, e, out _)) {
                throw new Unavailable("the Tesseract/Leptonica library has no symbol " + e);
            }
        }
        return h;
    }

    // ------------------------------------------------------------------ small helpers

    private static string CStr(nint p) {
        return Native.CStr(p);
    }

    private string TakeText(nint p) {
        if (p == 0) {
            return "";
        }
        string s = CStr(p);
        deleteText(p);
        return s;
    }

    private string TessVersion() {
        try {
            return CStr(version());
        } catch (System.Exception) {
            return "";
        }
    }

    private string LeptonicaVersion() {
        try {
            nint p = leptVersion();
            if (p == 0) {
                return "";
            }
            string s = CStr(p);
            leptFree(p);
            return s;
        } catch (System.Exception) {
            return "";
        }
    }

    // WHAT A REQUEST ASKS FOR AND HOW THE ENGINE'S ANSWER IS NAMED is kin's (`emon.ocrport`, P10.7, D396): which installed models read the request's
    // languages and scripts, a model's name as a request spells it, the OSD's script as ISO 15924, a block's type. This file passes it cells and takes
    // cells; the packed four-letter languages, the model and script tables and the block-type switch of the first C# host are gone.
    private static Port.Cells CellsOf(int[] v) {
        Port.Cells c = Port.NewCells();
        foreach (int x in v) {
            Port.CellsPush(c, x);
        }
        return c;
    }

    // Go's math.Round on float64(f)*10: half away from zero
    private static int X10(float f) {
        double d = (double) f * 10;
        return (int) (d < 0 ? -System.Math.Floor(-d + 0.5) : System.Math.Floor(d + 0.5));
    }

    private static int[] Cps(string s) {
        var l = new List<int>();
        foreach (System.Text.Rune r in s.EnumerateRunes()) {
            l.Add(r.Value);
        }
        return l.ToArray();
    }

    private sealed class Alt {
        public int[] Cps;
        public int Conf;
    }

    private sealed class Sym {
        public int[] Bx;
        public int Conf;
        public int[] Cps;
        public List<Alt> Alts = new List<Alt>();
    }

    private sealed class Word {
        public int Conf, Line, Block;
        public int[] Bx;
        public int[] Text;
        public int Flags, Lang, Psize, Dflags;
        public List<Sym> Syms = new List<Sym>();
    }

    private sealed class Line {
        public int[] Bx, Base;
        public int Block, Par, Btype;
    }

    private sealed class Block {
        public int[] Bx;
        public int Orient, Wdir, Tlo, Desk;
    }

    // ------------------------------------------------------------------ TEST SUPPORT: the filesystem (tessdata)

    private static List<string> InstalledIn(string dir) {
        var v = new SortedSet<string>(System.StringComparer.Ordinal);
        try {
            foreach (string p in System.IO.Directory.EnumerateFileSystemEntries(dir)) {
                int k = p.LastIndexOf('/');
                string n = k >= 0 ? p.Substring(k + 1) : p;
                if (n.EndsWith(".traineddata")) {
                    string l = n.Substring(0, n.Length - ".traineddata".Length);
                    if (l != "osd" && l != "snum") {
                        v.Add(l);
                    }
                }
            }
        } catch (System.Exception) {
            return new List<string>();
        }
        return new List<string>(v);
    }

    private static bool NonEmptyDir(string dir) {
        try {
            foreach (string p in System.IO.Directory.EnumerateFileSystemEntries(dir)) {
                return true;
            }
            return false;
        } catch (System.Exception) {
            return false;
        }
    }

    private static string FindTessdata() {
        var cands = new List<string>();
        string p = System.Environment.GetEnvironmentVariable("TESSDATA_PREFIX");
        if (!string.IsNullOrEmpty(p)) {
            cands.Add(p);
            cands.Add(p + "/tessdata");
        }
        cands.AddRange(new[] { "/opt/homebrew/share/tessdata", "/usr/local/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata" });
        foreach (string d in cands) {
            if (NonEmptyDir(d)) {
                return d;
            }
        }
        return null;
    }

    private List<string[]> MakeProducer() {
        var models = new List<string>();
        List<string> names = InstalledIn(tessdata);
        names.Add("osd");
        foreach (string n in names) {
            byte[] b;
            try {
                b = System.IO.File.ReadAllBytes(tessdata + "/" + n + ".traineddata");
            } catch (System.Exception) {
                continue;
            }
            ulong h = 0xcbf29ce484222325UL;
            unchecked {
                foreach (byte x in b) {
                    h ^= x;
                    h *= 0x100000001b3UL;
                }
            }
            models.Add(n + ":" + b.Length.ToString(System.Globalization.CultureInfo.InvariantCulture) + ":fnv64=" + h.ToString("x16"));
        }
        var output = new List<string[]>();
        output.Add(new[] { "engine", "tesseract" });
        output.Add(new[] { "version", TessVersion() });
        output.Add(new[] { "leptonica", LeptonicaVersion() });
        output.Add(new[] { "engine-mode", "default (LSTM)" });
        output.Add(new[] { "lstm-choice-mode", "2" });
        output.Add(new[] { "models", string.Join(" ", models) });
        output.Add(new[] { "host", "host/csharp/Emon/Ffi/Tess.cs" });
        return output;
    }

    // ------------------------------------------------------------------ the engine

    private nint Api(string langs) {
        if (apis.TryGetValue(langs, out nint a)) {
            return a;
        }
        nint h = create();
        using (var ar = new Native.Arena()) {
            if (init3(h, ar.Str(tessdata), ar.Str(langs)) != 0) {
                delete(h);
                return 0;
            }
            // lstm_choice_mode 2: the final beam-search candidates per symbol (D095); word confidences are carried verbatim and
            // 0 is never "wrong" (D130).
            setVariable(h, ar.Str("lstm_choice_mode"), ar.Str("2"));
        }
        apis[langs] = h;
        return h;
    }

    // the engine's thresholded image as a gray bitmap (0 ink, 255 paper): (w, h, pixels) or null
    private (int, int, byte[])? BinaryOf(nint h, Native.Arena ar) {
        nint pix = getThresholded(h);
        if (pix == 0) {
            return null;
        }
        int w = pixW(pix), hh = pixH(pix), d = pixDepth(pix);
        byte[] px = new byte[w * hh];
        System.Array.Fill(px, (byte) 255);
        nint v = ar.Alloc(4);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                pixPixel(pix, x, y, v);
                uint val = *(uint*) v;
                bool ink = d != 1 ? val < 128 : val != 0;
                if (ink) {
                    px[y * w + x] = 0;
                }
            }
        }
        DestroyPix(pix, ar);
        return (w, hh, px);
    }

    private void DestroyPix(nint pix, Native.Arena ar) {
        nint pp = ar.Alloc(8);
        *(nint*) pp = pix;
        pixDestroy(pp);
    }

    private static Planrun.Answer Failed(int id, int status) {
        Port.Cells c = Port.NewCells();
        emon.Plan.AnFail(c, Planrun.KindOcr, id, status);
        return new Planrun.Answer(Port.CellsVec(c));
    }

    private void SetImg(nint hp, nint cpx, int w, int hh, int ch, int dpiF, int rx, int ry, int rw, int rh) {
        setImage(hp, cpx, w, hh, ch, w * ch);
        if (dpiF > 0) {
            setSourceResolution(hp, dpiF);
        }
        if (rw > 0) {
            setRectangle(hp, rx, ry, rw, rh);
        }
    }

    private Planrun.Answer FulfilNative(Planrun.Request req, Dictionary<int, Port.Bitmap> bm, bool keepBinary) {
        int id = req.Id();
        if (!bm.TryGetValue(req.Field(emon.Plan.OcBitmap()), out Port.Bitmap b)) {
            return Failed(id, Planrun.StatusFailed);
        }
        if (b.Ch != 1 && b.Ch != 3) {
            return Failed(id, 2);
        }
        int psm = req.Field(emon.Plan.OcPsm()), flags = req.Field(emon.Plan.OcFlags());
        bool wantSyms = (flags & 1) != 0;
        int rx = req.Field(emon.Plan.OcX()), ry = req.Field(emon.Plan.OcY()), rw = req.Field(emon.Plan.OcW()), rh = req.Field(emon.Plan.OcH());
        List<string> installed = InstalledIn(tessdata);
        // which installed models read the request is kin's (`op-models`: languages, scripts, the ones not installed dropped, none left is every model);
        // the models go in as code points separated by 0
        Port.Cells reqc = CellsOf(req.Rec);
        Port.Cells inst = Port.NewCells();
        for (int i = 0; i < installed.Count; i++) {
            if (i > 0) {
                Port.CellsPush(inst, 0);
            }
            foreach (int cp in Cps(installed[i])) {
                Port.CellsPush(inst, cp);
            }
        }
        Port.Cells picked = Port.NewCells();
        emon.Ocrport.OpModels(reqc, inst, picked);
        Port.Cells gone = Port.NewCells();
        emon.Ocrport.OpDropped(reqc, inst, gone);
        var dropped = new System.Text.StringBuilder();
        for (int i = 0; i < Port.CellsLen(gone); i++) {
            int cp = Port.CellsAt(gone, i);
            if (cp == 0) {
                notes.Add("no model for " + dropped + ": dropped");
                dropped.Clear();
            } else {
                dropped.Append(char.ConvertFromUtf32(cp));
            }
        }
        var langs = new List<string>();
        for (int i = 0; i < Port.CellsLen(picked); i++) {
            langs.Add(installed[Port.CellsAt(picked, i)]);
        }
        string lang = string.Join("+", langs);
        if (lang.Length == 0) {
            return Failed(id, 2);
        }
        nint h = Api(lang);
        if (h == 0) {
            return Failed(id, Planrun.StatusFailed);
        }
        int w = b.W, hh = b.H, ch = b.Ch;
        byte[] px = b.D;
        if (b.Stride != w * ch) {
            px = new byte[w * hh * ch];
            for (int y = 0; y < hh; y++) {
                System.Array.Copy(b.D, y * b.Stride, px, y * w * ch, w * ch);
            }
        }
        int dpiV = emon.Ocr.OcfDpi(flags);
        if (dpiV == 0) {
            dpiV = dpi;
        }
        int dpiF = dpiV;
        var words = new List<Word>();
        var lines = new List<Line>();
        var blocks = new List<Block>();
        int binW = 0, binH = 0;
        byte[] binPx = null;
        bool hasBinary = false;
        int osd = -1, script = 0, sconf = -1, oconf = -1;
        // Tesseract keeps the pointer until Clear: native memory that lives for this request
        using (var ar = new Native.Arena()) {
            nint cpx = ar.Alloc(System.Math.Max(px.Length, 1), 16);
            System.Runtime.InteropServices.Marshal.Copy(px, 0, cpx, px.Length);
            if ((flags & 2) != 0) {
                // OSD has its own model (`osd`) and its own engine instance
                nint ho = Api("osd");
                if (ho == 0) {
                    ho = h;
                }
                setPageSegMode(ho, 0);
                SetImg(ho, cpx, w, hh, ch, dpiF, rx, ry, rw, rh);
                nint deg = ar.Alloc(4), oc = ar.Alloc(4), sc = ar.Alloc(4), sn = ar.Alloc(8);
                if (detectOsd(ho, deg, oc, sn, sc) != 0) {
                    osd = *(int*) deg;
                    oconf = X10(*(float*) oc);
                    sconf = X10(*(float*) sc);
                    script = emon.Ocrport.OpOsdScript(CellsOf(Cps(CStr(*(nint*) sn))));
                } else {
                    notes.Add("osd failed");
                }
                clear(ho);
            }
            setPageSegMode(h, psm);
            SetImg(h, cpx, w, hh, ch, dpiF, rx, ry, rw, rh);
            try {
                if (recognize(h, 0) != 0) {
                    return Failed(id, Planrun.StatusFailed);
                }
                nint it = getIterator(h);
                if (it != 0) {
                    Iterate(it, ar, wantSyms, words, lines, blocks);
                    itDelete(it);
                }
                // the image the engine thresholded to read this page; its pixels only when a transcript will keep them
                if (keepBinary) {
                    var r = BinaryOf(h, ar);
                    if (r != null) {
                        hasBinary = true;
                        binW = r.Value.Item1;
                        binH = r.Value.Item2;
                        binPx = r.Value.Item3;
                    }
                } else {
                    nint pix = getThresholded(h);
                    if (pix != 0) {
                        hasBinary = true;
                        binW = pixW(pix);
                        binH = pixH(pix);
                        DestroyPix(pix, ar);
                    }
                }
            } finally {
                clear(h);
            }
        }

        // Words, then the extension, through the kin builders.
        int orient = 0, wdir = 0, tlo = 0, deskew = 0;
        if (blocks.Count > 0) {
            Block b0 = blocks[0];
            orient = b0.Orient;
            wdir = b0.Wdir;
            tlo = b0.Tlo;
            deskew = b0.Desk;
        }
        Port.Cells c = Port.NewCells();
        int at = emon.Plan.AnOcr(c, id);
        foreach (Word w2 in words) {
            int wd = emon.Plan.AnOcrWord(c, at, w2.Bx[0], w2.Bx[1], w2.Bx[2], w2.Bx[3], w2.Conf, w2.Line, w2.Block);
            foreach (int cp in w2.Text) {
                emon.Plan.AnOcrCp(c, wd, cp);
            }
        }
        emon.Ocr.OxBegin(c, orient, wdir, tlo, deskew, osd, script, sconf, oconf, lines.Count, blocks.Count, hasBinary ? 1 : 0, binW, binH);
        foreach (Line l in lines) {
            emon.Ocr.OxLine(c, l.Bx[0], l.Bx[1], l.Bx[2], l.Bx[3], l.Base[0], l.Base[1], l.Base[2], l.Base[3], l.Block, l.Par, l.Btype, -1, -1, -1);
        }
        foreach (Block bl in blocks) {
            emon.Ocr.OxBlock(c, bl.Bx[0], bl.Bx[1], bl.Bx[2], bl.Bx[3], bl.Orient, bl.Wdir, bl.Tlo);
        }
        foreach (Word w2 in words) {
            int wx = emon.Ocr.OxWord(c, w2.Flags, w2.Lang, w2.Psize, w2.Dflags);
            foreach (Sym s in w2.Syms) {
                int sx = emon.Ocr.OxSym(c, wx, s.Bx[0], s.Bx[1], s.Bx[2], s.Bx[3], s.Conf);
                foreach (int cp in s.Cps) {
                    emon.Ocr.OxSymCp(c, sx, cp);
                }
                foreach (Alt a in s.Alts) {
                    int ax = emon.Ocr.OxAlt(c, sx, a.Conf);
                    foreach (int cp in a.Cps) {
                        emon.Ocr.OxAltCp(c, ax, cp);
                    }
                }
            }
        }
        emon.Plan.AnOcrEnd(c, at);
        int[] rec = Port.CellsVec(c);
        Port.Bitmap bmp = null;
        if (keepBinary && hasBinary && binPx != null && binPx.Length > 0) {
            bmp = new Port.Bitmap(binW, binH, 1, binPx);
        }
        return new Planrun.Answer(rec, bmp);
    }

    private int[] Box(nint pi, int level, Native.Arena ar) {
        nint l = ar.Alloc(4), t = ar.Alloc(4), r = ar.Alloc(4), bt = ar.Alloc(4);
        piBox(pi, level, l, t, r, bt);
        int L = *(int*) l, T = *(int*) t, R = *(int*) r, B = *(int*) bt;
        return new[] { L, T, R - L, B - T };
    }

    private void Iterate(nint it, Native.Arena ar, bool wantSyms, List<Word> words, List<Line> lines, List<Block> blocks) {
        int block = -1, par = -1, line = -1;
        for (;;) {
            nint pi = itPageIterator(it);
            if (piAtBeginning(pi, RilBlock) != 0) {
                block++;
                var bl = new Block();
                bl.Bx = Box(pi, RilBlock, ar);
                nint o = ar.Alloc(4), wd = ar.Alloc(4), tl = ar.Alloc(4), dk = ar.Alloc(4);
                piOrientation(pi, o, wd, tl, dk);
                bl.Orient = *(int*) o;
                bl.Wdir = *(int*) wd;
                bl.Tlo = *(int*) tl;
                // Java's Math.round: floor(x + 1/2)
                bl.Desk = (int) System.Math.Floor((double) *(float*) dk * 180 / System.Math.PI * 100 + 0.5);
                blocks.Add(bl);
            }
            if (piAtBeginning(pi, RilPara) != 0) {
                par++;
            }
            if (piAtBeginning(pi, RilTextline) != 0) {
                line++;
                var ln = new Line();
                ln.Bx = Box(pi, RilTextline, ar);
                nint x1 = ar.Alloc(4), y1 = ar.Alloc(4), x2 = ar.Alloc(4), y2 = ar.Alloc(4);
                piBaseline(pi, RilTextline, x1, y1, x2, y2);
                ln.Base = new[] { *(int*) x1, *(int*) y1, *(int*) x2, *(int*) y2 };
                ln.Btype = emon.Ocrport.OpBlockType(piBlockType(pi));
                ln.Block = block;
                ln.Par = par;
                lines.Add(ln);
            }
            string text = GoTrim(TakeText(itText(it, RilWord)));
            if (text.Length > 0) {
                var wd = new Word();
                wd.Bx = Box(pi, RilWord, ar);
                nint bo = ar.Alloc(4), itl = ar.Alloc(4), un = ar.Alloc(4), mo = ar.Alloc(4), se = ar.Alloc(4),
                    sc = ar.Alloc(4), sz = ar.Alloc(4), fid = ar.Alloc(4);
                itFont(it, bo, itl, un, mo, se, sc, sz, fid);
                wd.Flags = (*(int*) bo & 1) | ((*(int*) itl & 1) << 1) | ((*(int*) un & 1) << 2) | ((*(int*) mo & 1) << 6) | ((*(int*) sc & 1) << 7);
                int dfl = 0;
                if (itIsDict(it) != 0) {
                    dfl |= emon.Ocr.OcwDict();
                }
                if (itIsNumeric(it) != 0) {
                    dfl |= emon.Ocr.OcwNumeric();
                }
                int sizeV = *(int*) sz;
                wd.Psize = sizeV > 0 ? sizeV : -1;
                wd.Conf = X10(itConfidence(it, RilWord));
                wd.Line = line;
                wd.Block = block;
                wd.Text = Cps(text);
                wd.Lang = emon.Ocrport.OpWordLang(CellsOf(Cps(CStr(itWordLang(it)))));
                wd.Dflags = dfl;
                if (wantSyms) {
                    Symbols(it, ar, wd);
                }
                words.Add(wd);
            }
            if (itNext(it, RilWord) == 0) {
                break;
            }
        }
    }

    private void Symbols(nint it, Native.Arena ar, Word wd) {
        nint it2 = itCopy(it);
        for (;;) {
            nint p2 = itPageIterator(it2);
            var sy = new Sym();
            sy.Bx = Box(p2, RilSymbol, ar);
            string stext = TakeText(itText(it2, RilSymbol));
            sy.Conf = X10(itConfidence(it2, RilSymbol));
            sy.Cps = Cps(stext);
            nint ci = itChoiceIterator(it2);
            if (ci != 0) {
                for (;;) {
                    string ct = CStr(chText(ci));
                    if (ct != stext) {
                        var a = new Alt();
                        a.Cps = Cps(ct);
                        a.Conf = X10(chConf(ci));
                        sy.Alts.Add(a);
                    }
                    if (chNext(ci) == 0) {
                        break;
                    }
                }
                chDelete(ci);
            }
            wd.Syms.Add(sy);
            if (piAtFinal(p2, RilWord, RilSymbol) != 0 || itNext(it2, RilSymbol) == 0) {
                break;
            }
        }
        itDelete(it2);
    }

    // Go's strings.TrimSpace: Unicode white space
    private static string GoTrim(string s) {
        int a = 0, b = s.Length;
        while (a < b) {
            var r = System.Text.Rune.GetRuneAt(s, a);
            if (!IsSpace(r.Value)) {
                break;
            }
            a += r.Utf16SequenceLength;
        }
        while (b > a) {
            int i = b - 1;
            if (i > a && char.IsLowSurrogate(s[i]) && char.IsHighSurrogate(s[i - 1])) {
                i--;
            }
            var r = System.Text.Rune.GetRuneAt(s, i);
            if (!IsSpace(r.Value)) {
                break;
            }
            b = i;
        }
        return s.Substring(a, b - a);
    }

    private static bool IsSpace(int c) {
        return c == ' ' || (c >= 9 && c <= 13) || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a)
            || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
    }

    /// One engine behind a lock: Tesseract's API objects are not thread safe, and conversions that run at once take turns at the engine.
    public Planrun.Answer Fulfil(Planrun.Request req, Dictionary<int, Port.Bitmap> bitmaps, bool keepBinary) {
        lock (this) {
            try {
                return FulfilNative(req, bitmaps, keepBinary);
            } catch (System.Exception) {
                // an engine that fails on a page is a failed OCR of it, not a crashed process
                return Failed(req.Id(), Planrun.StatusFailed);
            }
        }
    }

    public List<string[]> Producer() {
        return producer;
    }
}
