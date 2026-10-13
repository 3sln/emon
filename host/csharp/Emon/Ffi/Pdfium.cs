// The C# host's binding to pdfium's C API through .NET's native interop: function pointers resolved with NativeLibrary at run
// time (DEVLOG P9.2; P6.2, D127; SPEC 7.2, D007). The default `IRenderer`; the twin of `host/java/emon/ffi/Pdfium.java`,
// `host/go/pdfium/pdfium.go` and `host/rust/src/pdfium.rs`, whose headers say how a document is read (pdfium's custom
// file-access loader over the run's byte source: no path, no copy, no whole-file buffer), how a layer is made (page objects
// removed in memory, never saved) and why the watermark and background layers are `unsupported`.
//
// LIVE ONLY: the library is found at run time, so nothing links it and no gate needs it (a gate replays a committed
// transcript, `bb render`). It is the pinned build that `bb pdfium-fetch` installs (tools/pdfium/pdfium.edn).
//
// Where the library is looked for, in order: the `libDirOrFile` argument of `Open`, `$EMON_PDFIUM_LIB` (a directory or
// a file), then `.cache/pdfium/current/lib` (relative to the working directory: the repository root) and the system's
// own search. Nothing here names a filesystem type: the names are handed to the dynamic loader.

namespace emon.Ffi;

public sealed unsafe class Pdfium : RenderPort.IRenderer {
    private const int FpdfAnnot = 0x01;
    private const int FpdfReverseByteOrder = 0x10;
    private const int BitmapBgrx = 3;
    private const int ObjText = 1;
    private const int ObjForm = 5;

    // pdfium is not thread-safe: one process-wide lock, so any number of Pdfium values and threads may be used and the
    // renders happen one at a time.
    private static readonly object Lock = new object();
    private static bool initialised = false;

    // The source being read: set under Lock for the duration of one render (the callback is one static function per process).
    private static RenderPort.ISource current;

    // A render larger than the `pixels` limit (kin's table, read through Port.LimitOf(9) at each render, so a conversion's `limit.pixels` applies to
    // rendering too) is refused (`failed`), before any memory is asked for (P10.4).

    // The P/Invoke declarations. "pdfium" is a LOGICAL name: Native's one DllImport resolver maps it to the run-time path
    // (see Native.Resolve). `unsigned long` is `nuint`: right for the Unix targets (LP64), where this binding is used.
    // The C names are the entry points; the C# names are the ones the code below calls.
    private const string Lib = "pdfium";
    private const System.Runtime.InteropServices.CallingConvention Cc = System.Runtime.InteropServices.CallingConvention.Cdecl;

    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_InitLibrary", CallingConvention = Cc)]
    private static extern void initLibrary();
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_GetLastError", CallingConvention = Cc)]
    private static extern nuint getLastError();
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_LoadCustomDocument", CallingConvention = Cc)]
    private static extern nint loadCustomDocument(nint access, nint password);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_CloseDocument", CallingConvention = Cc)]
    private static extern void closeDocument(nint doc);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_GetPageCount", CallingConvention = Cc)]
    private static extern int getPageCount(nint doc);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_LoadPage", CallingConvention = Cc)]
    private static extern nint loadPage(nint doc, int index);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_ClosePage", CallingConvention = Cc)]
    private static extern void closePage(nint page);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_GetPageWidthF", CallingConvention = Cc)]
    private static extern float pageWidth(nint page);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_GetPageHeightF", CallingConvention = Cc)]
    private static extern float pageHeight(nint page);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFBitmap_CreateEx", CallingConvention = Cc)]
    private static extern nint bitmapCreateEx(int w, int h, int format, nint buf, int stride);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFBitmap_FillRect", CallingConvention = Cc)]
    private static extern int bitmapFillRect(nint bm, int x, int y, int w, int h, nuint colour);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFBitmap_Destroy", CallingConvention = Cc)]
    private static extern void bitmapDestroy(nint bm);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDF_RenderPageBitmap", CallingConvention = Cc)]
    private static extern void renderPageBitmap(nint bm, nint page, int x, int y, int w, int h, int rotate, int flags);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFPage_CountObjects", CallingConvention = Cc)]
    private static extern int pageCountObjects(nint page);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFPage_GetObject", CallingConvention = Cc)]
    private static extern nint pageGetObject(nint page, int index);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFPage_RemoveObject", CallingConvention = Cc)]
    private static extern int pageRemoveObject(nint page, nint obj);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFPageObj_Destroy", CallingConvention = Cc)]
    private static extern void objDestroy(nint obj);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFPageObj_GetType", CallingConvention = Cc)]
    private static extern int objGetType(nint obj);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFFormObj_CountObjects", CallingConvention = Cc)]
    private static extern int formCountObjects(nint form);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFFormObj_GetObject", CallingConvention = Cc)]
    private static extern nint formGetObject(nint form, nuint index);
    [System.Runtime.InteropServices.DllImport(Lib, EntryPoint = "FPDFFormObj_RemoveObject", CallingConvention = Cc)]
    private static extern int formRemoveObject(nint form, nint obj);

    private static readonly string[] Entries = {
        "FPDF_InitLibrary", "FPDF_GetLastError", "FPDF_LoadCustomDocument", "FPDF_CloseDocument", "FPDF_GetPageCount", "FPDF_LoadPage",
        "FPDF_ClosePage", "FPDF_GetPageWidthF", "FPDF_GetPageHeightF", "FPDFBitmap_CreateEx", "FPDFBitmap_FillRect", "FPDFBitmap_Destroy",
        "FPDF_RenderPageBitmap", "FPDFPage_CountObjects", "FPDFPage_GetObject", "FPDFPage_RemoveObject", "FPDFPageObj_Destroy",
        "FPDFPageObj_GetType", "FPDFFormObj_CountObjects", "FPDFFormObj_GetObject", "FPDFFormObj_RemoveObject" };

    private Pdfium() {
    }

    /// Loads pdfium and initialises it (once per process). `libDirOrFile` is a directory holding the library or the
    /// library itself; null searches (see the file header). Throws Unavailable when it cannot be found or loaded.
    public static Pdfium Open(string libDirOrFile) {
        var names = new System.Collections.Generic.List<string>();
        if (libDirOrFile != null) {
            AddCandidates(names, libDirOrFile);
        }
        string env = System.Environment.GetEnvironmentVariable("EMON_PDFIUM_LIB");
        if (!string.IsNullOrEmpty(env)) {
            AddCandidates(names, env);
        }
        AddCandidates(names, ".cache/pdfium/current/lib");
        names.Add("pdfium");
        // probe: the same candidates the resolver will use; a missing library or symbol is Unavailable, here, not a failed render later
        nint h = Native.Pin("pdfium", names);
        if (h == 0) {
            throw new Unavailable("pdfium was not found (looked for " + string.Join(", ", names) + "). Run `bb pdfium-fetch` and set EMON_PDFIUM_LIB to its lib directory"
                + " (.cache/pdfium/current/lib), or pass the directory.");
        }
        foreach (string e in Entries) {
            if (!System.Runtime.InteropServices.NativeLibrary.TryGetExport(h, e, out _)) {
                throw new Unavailable("the pdfium library has no symbol " + e + ": is it the pinned build? (`bb pdfium-fetch`)");
            }
        }
        lock (Lock) {
            if (!initialised) {
                try {
                    initLibrary();
                } catch (System.DllNotFoundException x) {
                    throw new Unavailable("pdfium could not be loaded: " + x.Message, x);
                } catch (System.EntryPointNotFoundException x) {
                    throw new Unavailable("the pdfium library lacks an entry point: " + x.Message, x);
                }
                initialised = true;
            }
        }
        return new Pdfium();
    }

    private static void AddCandidates(System.Collections.Generic.List<string> output, string p) {
        string lower = p.ToLowerInvariant();
        if (lower.EndsWith(".dylib") || lower.EndsWith(".so") || lower.EndsWith(".dll")) {
            output.Add(p);
            return;
        }
        string d = p.EndsWith("/") ? p : p + "/";
        output.Add(d + "libpdfium.dylib");
        output.Add(d + "libpdfium.so");
        output.Add(d + "pdfium.dll");
    }

    /// Layers: full, no text, text only.
    public int Layers() {
        return 7;
    }

    /// False: a render clipped to a rectangle moves the pixels on the clip's edge (a vertical rule at the left edge of a
    /// crop of a bank statement differed by up to 107 of 255 from the same pixels cut out of the whole page, D127), so
    /// the fulfiller renders the whole page, keeps it, and cuts.
    public bool ExactCrop() {
        return false;
    }

    // The FPDF_FILEACCESS callback: fill buf with `size` bytes from `pos`; 1 on success, 0 on any failure.
    [System.Runtime.InteropServices.UnmanagedCallersOnly(CallConvs = new[] { typeof(System.Runtime.CompilerServices.CallConvCdecl) })]
    private static int GetBlock(nint param, nuint pos, byte* buf, nuint size) {
        try {
            RenderPort.ISource src = current;
            if (src == null || size > int.MaxValue) {
                return 0;
            }
            byte[] tmp = new byte[(int) size];
            int got = 0;
            while (got < tmp.Length) {
                int n = src.ReadAt((long) pos + got, tmp, got, tmp.Length - got);
                if (n <= 0) {
                    return 0;
                }
                got += n;
            }
            new System.ReadOnlySpan<byte>(tmp).CopyTo(new System.Span<byte>(buf, tmp.Length));
            return 1;
        } catch (System.Exception) {
            return 0;
        }
    }

    private string LastError() {
        long e = (long) getLastError();
        switch ((int) e) {
            case 0: return "no error";
            case 2: return "file not found or could not be opened";
            case 3: return "not a PDF, or its structure is damaged";
            case 4: return "password required or incorrect";
            case 5: return "unsupported security scheme";
            case 6: return "page not found or content error";
            default: return "pdfium error " + e;
        }
    }

    // keep: 1 keep, 0 drop, -1 a form to walk into (its contents decide).
    private int Keep(int layer, nint o) {
        int ty = objGetType(o);
        if (ty == ObjForm) {
            return -1;
        }
        if (layer == RenderPort.LayerNoText && ty == ObjText) {
            return 0;
        }
        if (layer == RenderPort.LayerTextOnly && ty != ObjText) {
            return 0;
        }
        return 1;
    }

    private void PruneForm(int layer, nint form) {
        for (int i = formCountObjects(form) - 1; i >= 0; i--) {
            nint o = formGetObject(form, (nuint) i);
            if (o == 0) {
                continue;
            }
            switch (Keep(layer, o)) {
                case 0:
                    if (formRemoveObject(form, o) != 0) {
                        objDestroy(o);
                    }
                    break;
                case -1:
                    PruneForm(layer, o);
                    break;
                default:
                    break;
            }
        }
    }

    private void PrunePage(int layer, nint page) {
        for (int i = pageCountObjects(page) - 1; i >= 0; i--) {
            nint o = pageGetObject(page, i);
            if (o == 0) {
                continue;
            }
            switch (Keep(layer, o)) {
                case 0:
                    if (pageRemoveObject(page, o) != 0) {
                        objDestroy(o);
                    }
                    break;
                case -1:
                    PruneForm(layer, o);
                    break;
                default:
                    break;
            }
        }
    }

    public Port.Bitmap Render(RenderPort.ISource input, byte[] password, RenderPort.Request req) {
        if (req.Layer == RenderPort.LayerNoWatermark || req.Layer == RenderPort.LayerWatermarkOnly) {
            throw new RenderPort.RenderError(true, "pdfium's mark API cannot read /Artifact /Subtype (a name)");
        }
        if (req.Layer < 0 || req.Layer > 4) {
            throw new RenderPort.RenderError(true, "layer " + req.Layer);
        }
        if (req.Dpi < 1 || req.Dpi > 4800) {
            throw new RenderPort.RenderError(false, "dpi " + req.Dpi);
        }
        lock (Lock) {
            using var arena = new Native.Arena();
            try {
                current = input;
                // struct FPDF_FILEACCESS { unsigned long m_FileLen; int (*m_GetBlock)(void*, unsigned long, unsigned char*, unsigned long); void* m_Param; }
                nint access = arena.Alloc(24);
                ((nuint*) access)[0] = (nuint) input.Size();
                ((nint*) access)[1] = (nint) (delegate* unmanaged[Cdecl]<nint, nuint, byte*, nuint, int>) &GetBlock;
                ((nint*) access)[2] = 0;
                nint pw = 0;
                if (password != null) {
                    foreach (byte b in password) {
                        if (b == 0) {
                            throw new RenderPort.RenderError(false, "a password with a NUL");
                        }
                    }
                    pw = arena.Alloc(password.Length + 1);
                    System.Runtime.InteropServices.Marshal.Copy(password, 0, pw, password.Length);
                }
                nint doc = loadCustomDocument(access, pw);
                if (doc == 0) {
                    throw new RenderPort.RenderError(false, LastError());
                }
                try {
                    return RenderDoc(arena, doc, req);
                } finally {
                    closeDocument(doc);
                }
            } catch (RenderPort.RenderError) {
                throw;
            } catch (System.Exception t) {
                throw new RenderPort.RenderError(false, "pdfium call failed: " + t);
            } finally {
                current = null;
            }
        }
    }

    private Port.Bitmap RenderDoc(Native.Arena arena, nint doc, RenderPort.Request req) {
        int n = getPageCount(doc);
        if (req.Page < 0 || req.Page >= n) {
            throw new RenderPort.RenderError(false, "page " + req.Page + " of " + n);
        }
        nint page = loadPage(doc, req.Page);
        if (page == 0) {
            throw new RenderPort.RenderError(false, LastError());
        }
        try {
            int w = RenderPort.PixelsOf((double) pageWidth(page), req.Dpi);
            int h = RenderPort.PixelsOf((double) pageHeight(page), req.Dpi);
            long maxPixels = Port.LimitOf(9);
            if ((long) w * (long) h > maxPixels) {
                throw new RenderPort.RenderError(false, w + "x" + h + " pixels is over the limit of " + maxPixels);
            }
            int flags = FpdfReverseByteOrder;
            switch (req.Layer) {
                case RenderPort.LayerFull:
                    flags |= FpdfAnnot;
                    break;
                case RenderPort.LayerNoText:
                    PrunePage(req.Layer, page);
                    flags |= FpdfAnnot;
                    break;
                default:
                    PrunePage(req.Layer, page);
                    break;
            }
            // the pixels live in native memory: pdfium keeps the pointer across calls
            long bytes = (long) w * h * 4;
            nint buf = arena.Alloc(bytes, 16);
            nint bm = bitmapCreateEx(w, h, BitmapBgrx, buf, w * 4);
            if (bm == 0) {
                throw new RenderPort.RenderError(false, "the bitmap could not be created");
            }
            bitmapFillRect(bm, 0, 0, w, h, 0xFFFFFFFFu);
            renderPageBitmap(bm, page, 0, 0, w, h, 0, flags);
            bitmapDestroy(bm);
            var raw = new System.ReadOnlySpan<byte>((void*) buf, (int) bytes);
            byte[] rgb = new byte[w * h * 3];
            for (int i = 0, o = 0; i < raw.Length; i += 4) {
                rgb[o++] = raw[i];
                rgb[o++] = raw[i + 1];
                rgb[o++] = raw[i + 2];
            }
            return new Port.Bitmap(w, h, 3, rgb);
        } finally {
            closePage(page);
        }
    }
}
