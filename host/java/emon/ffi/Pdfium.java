// The Java host's binding to pdfium's C API through the Java FFM API, java.lang.foreign (DEVLOG P9.1, D520; P6.2, D127;
// SPEC 7.2, D007). The default `Renderer`; the twin of `host/go/pdfium/pdfium.go` and `host/rust/src/pdfium.rs`, whose
// headers say how a document is read (pdfium's custom file-access loader over the run's byte source: no path, no copy,
// no whole-file buffer), how a layer is made (page objects removed in memory, never saved) and why the watermark and
// background layers are `unsupported`.
//
// LIVE ONLY: the library is found at run time, so nothing links it and no gate needs it (a gate replays a committed
// transcript, `bb render`). Run the JVM with `--enable-native-access=ALL-UNNAMED`. It is the pinned build that
// `bb pdfium-fetch` installs (tools/pdfium/pdfium.edn).
//
// Where the library is looked for, in order: the `libDirOrFile` argument of `open`, `$EMON_PDFIUM_LIB` (a directory or
// a file), then `.cache/pdfium/current/lib` (relative to the working directory: the repository root) and the system's
// own search. Nothing here names a filesystem type: the names are handed to the dynamic loader.

package emon.ffi;

import emon.Port;
import emon.RenderPort;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Pdfium implements RenderPort.Renderer {
    private static final int FPDF_ANNOT = 0x01;
    private static final int FPDF_REVERSE_BYTE_ORDER = 0x10;
    private static final int BITMAP_BGRX = 3;
    private static final int OBJ_TEXT = 1;
    private static final int OBJ_FORM = 5;

    // pdfium is not thread-safe: one process-wide lock, so any number of Pdfium values and threads may be used and the
    // renders happen one at a time.
    private static final Object LOCK = new Object();
    private static boolean initialised = false;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final ValueLayout.OfLong ULONG = (ValueLayout.OfLong) LINKER.canonicalLayouts().get("long");
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    // struct FPDF_FILEACCESS { unsigned long m_FileLen; int (*m_GetBlock)(void*, unsigned long, unsigned char*, unsigned long); void* m_Param; }
    private static final MemoryLayout ACCESS = MemoryLayout.structLayout(ULONG.withName("len"), PTR.withName("get"), PTR.withName("param"));

    /** A render larger than this many pixels is refused (`failed`), before any memory is asked for. */
    public long maxPixels = 1L << 28;

    private final MethodHandle initLibrary, getLastError, loadCustomDocument, closeDocument, getPageCount, loadPage, closePage,
        pageWidth, pageHeight, bitmapCreateEx, bitmapFillRect, bitmapDestroy, renderPageBitmap, pageCountObjects, pageGetObject,
        pageRemoveObject, objDestroy, objGetType, formCountObjects, formGetObject, formRemoveObject;
    private final MethodHandle getBlockHandle;

    // The source being read: set under LOCK for the duration of one render (the callback is one static stub per library).
    private RenderPort.Source current;
    private final MemorySegment getBlockStub;

    private Pdfium(SymbolLookup lib) throws ReflectiveOperationException {
        initLibrary = fn(lib, "FPDF_InitLibrary", FunctionDescriptor.ofVoid());
        getLastError = fn(lib, "FPDF_GetLastError", FunctionDescriptor.of(ULONG));
        loadCustomDocument = fn(lib, "FPDF_LoadCustomDocument", FunctionDescriptor.of(PTR, PTR, PTR));
        closeDocument = fn(lib, "FPDF_CloseDocument", FunctionDescriptor.ofVoid(PTR));
        getPageCount = fn(lib, "FPDF_GetPageCount", FunctionDescriptor.of(INT, PTR));
        loadPage = fn(lib, "FPDF_LoadPage", FunctionDescriptor.of(PTR, PTR, INT));
        closePage = fn(lib, "FPDF_ClosePage", FunctionDescriptor.ofVoid(PTR));
        pageWidth = fn(lib, "FPDF_GetPageWidthF", FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, PTR));
        pageHeight = fn(lib, "FPDF_GetPageHeightF", FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, PTR));
        bitmapCreateEx = fn(lib, "FPDFBitmap_CreateEx", FunctionDescriptor.of(PTR, INT, INT, INT, PTR, INT));
        bitmapFillRect = fn(lib, "FPDFBitmap_FillRect", FunctionDescriptor.of(INT, PTR, INT, INT, INT, INT, ULONG));
        bitmapDestroy = fn(lib, "FPDFBitmap_Destroy", FunctionDescriptor.ofVoid(PTR));
        renderPageBitmap = fn(lib, "FPDF_RenderPageBitmap", FunctionDescriptor.ofVoid(PTR, PTR, INT, INT, INT, INT, INT, INT));
        pageCountObjects = fn(lib, "FPDFPage_CountObjects", FunctionDescriptor.of(INT, PTR));
        pageGetObject = fn(lib, "FPDFPage_GetObject", FunctionDescriptor.of(PTR, PTR, INT));
        pageRemoveObject = fn(lib, "FPDFPage_RemoveObject", FunctionDescriptor.of(INT, PTR, PTR));
        objDestroy = fn(lib, "FPDFPageObj_Destroy", FunctionDescriptor.ofVoid(PTR));
        objGetType = fn(lib, "FPDFPageObj_GetType", FunctionDescriptor.of(INT, PTR));
        formCountObjects = fn(lib, "FPDFFormObj_CountObjects", FunctionDescriptor.of(INT, PTR));
        formGetObject = fn(lib, "FPDFFormObj_GetObject", FunctionDescriptor.of(PTR, PTR, ULONG));
        formRemoveObject = fn(lib, "FPDFFormObj_RemoveObject", FunctionDescriptor.of(INT, PTR, PTR));
        getBlockHandle = MethodHandles.lookup().findVirtual(Pdfium.class, "getBlock",
            MethodType.methodType(int.class, MemorySegment.class, long.class, MemorySegment.class, long.class)).bindTo(this);
        getBlockStub = LINKER.upcallStub(getBlockHandle, FunctionDescriptor.of(INT, PTR, ULONG, PTR, ULONG), Arena.global());
    }

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new Unavailable("the pdfium library has no symbol " + name + ": is it the pinned build? (`bb pdfium-fetch`)"));
        return LINKER.downcallHandle(sym, fd);
    }

    /**
     * Loads pdfium and initialises it (once per process). `libDirOrFile` is a directory holding the library or the
     * library itself; null searches (see the file header).
     *
     * @throws Unavailable when it cannot be found or loaded
     */
    public static Pdfium open(String libDirOrFile) {
        List<String> names = new ArrayList<>();
        if (libDirOrFile != null) {
            addCandidates(names, libDirOrFile);
        }
        String env = System.getenv("EMON_PDFIUM_LIB");
        if (env != null && !env.isEmpty()) {
            addCandidates(names, env);
        }
        addCandidates(names, ".cache/pdfium/current/lib");
        names.add("pdfium");
        Throwable last = null;
        for (String n : names) {
            try {
                SymbolLookup lib = SymbolLookup.libraryLookup(n, Arena.global());
                Pdfium p = new Pdfium(lib);
                synchronized (LOCK) {
                    if (!initialised) {
                        p.initLibrary.invoke();
                        initialised = true;
                    }
                }
                return p;
            } catch (IllegalArgumentException e) {
                last = e; // not there: try the next
            } catch (Unavailable e) {
                throw e;
            } catch (Throwable e) {
                last = e;
            }
        }
        throw new Unavailable("pdfium was not found (looked for " + String.join(", ", names) + "). Run `bb pdfium-fetch` and set EMON_PDFIUM_LIB to its lib directory"
            + " (.cache/pdfium/current/lib), or pass the directory.", last);
    }

    private static void addCandidates(List<String> out, String p) {
        String lower = p.toLowerCase();
        if (lower.endsWith(".dylib") || lower.endsWith(".so") || lower.endsWith(".dll")) {
            out.add(p);
            return;
        }
        String d = p.endsWith("/") ? p : p + "/";
        out.add(d + "libpdfium.dylib");
        out.add(d + "libpdfium.so");
        out.add(d + "pdfium.dll");
    }

    /** Layers: full, no text, text only. */
    public int layers() {
        return 0b00111;
    }

    /**
     * False: a render clipped to a rectangle moves the pixels on the clip's edge (a vertical rule at the left edge of a
     * crop of a bank statement differed by up to 107 of 255 from the same pixels cut out of the whole page, D127), so
     * the fulfiller renders the whole page, keeps it, and cuts.
     */
    public boolean exactCrop() {
        return false;
    }

    // The FPDF_FILEACCESS callback: fill buf with `size` bytes from `pos`; 1 on success, 0 on any failure.
    @SuppressWarnings("unused")
    private int getBlock(MemorySegment param, long pos, MemorySegment buf, long size) {
        try {
            RenderPort.Source src = current;
            if (src == null || size < 0 || size > Integer.MAX_VALUE) {
                return 0;
            }
            byte[] tmp = new byte[(int) size];
            int got = 0;
            while (got < tmp.length) {
                int n = src.readAt(pos + got, tmp, got, tmp.length - got);
                if (n <= 0) {
                    return 0;
                }
                got += n;
            }
            MemorySegment.copy(tmp, 0, buf.reinterpret(size), ValueLayout.JAVA_BYTE, 0, tmp.length);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String lastError() throws Throwable {
        long e = (long) getLastError.invoke();
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
    private int keep(int layer, MemorySegment o) throws Throwable {
        int ty = (int) objGetType.invoke(o);
        if (ty == OBJ_FORM) {
            return -1;
        }
        if (layer == RenderPort.LAYER_NO_TEXT && ty == OBJ_TEXT) {
            return 0;
        }
        if (layer == RenderPort.LAYER_TEXT_ONLY && ty != OBJ_TEXT) {
            return 0;
        }
        return 1;
    }

    private void pruneForm(int layer, MemorySegment form) throws Throwable {
        for (int i = (int) formCountObjects.invoke(form) - 1; i >= 0; i--) {
            MemorySegment o = (MemorySegment) formGetObject.invoke(form, (long) i);
            if (o.equals(MemorySegment.NULL)) {
                continue;
            }
            switch (keep(layer, o)) {
                case 0:
                    if ((int) formRemoveObject.invoke(form, o) != 0) {
                        objDestroy.invoke(o);
                    }
                    break;
                case -1:
                    pruneForm(layer, o);
                    break;
                default:
                    break;
            }
        }
    }

    private void prunePage(int layer, MemorySegment page) throws Throwable {
        for (int i = (int) pageCountObjects.invoke(page) - 1; i >= 0; i--) {
            MemorySegment o = (MemorySegment) pageGetObject.invoke(page, i);
            if (o.equals(MemorySegment.NULL)) {
                continue;
            }
            switch (keep(layer, o)) {
                case 0:
                    if ((int) pageRemoveObject.invoke(page, o) != 0) {
                        objDestroy.invoke(o);
                    }
                    break;
                case -1:
                    pruneForm(layer, o);
                    break;
                default:
                    break;
            }
        }
    }

    public Port.Bitmap render(RenderPort.Source input, byte[] password, RenderPort.Request req) throws RenderPort.RenderError {
        if (req.layer == RenderPort.LAYER_NO_WATERMARK || req.layer == RenderPort.LAYER_WATERMARK_ONLY) {
            throw new RenderPort.RenderError(true, "pdfium's mark API cannot read /Artifact /Subtype (a name)");
        }
        if (req.layer < 0 || req.layer > 4) {
            throw new RenderPort.RenderError(true, "layer " + req.layer);
        }
        if (req.dpi < 1 || req.dpi > 4800) {
            throw new RenderPort.RenderError(false, "dpi " + req.dpi);
        }
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                current = input;
                MemorySegment access = arena.allocate(ACCESS);
                access.set(ULONG, ACCESS.byteOffset(MemoryLayout.PathElement.groupElement("len")), input.size());
                access.set(PTR, ACCESS.byteOffset(MemoryLayout.PathElement.groupElement("get")), getBlockStub);
                access.set(PTR,ACCESS.byteOffset(MemoryLayout.PathElement.groupElement("param")), MemorySegment.NULL);
                MemorySegment pw = MemorySegment.NULL;
                if (password != null) {
                    for (byte b : password) {
                        if (b == 0) {
                            throw new RenderPort.RenderError(false, "a password with a NUL");
                        }
                    }
                    byte[] z = java.util.Arrays.copyOf(password, password.length + 1);
                    pw = arena.allocate(z.length);
                    MemorySegment.copy(z, 0, pw, ValueLayout.JAVA_BYTE, 0, z.length);
                }
                MemorySegment doc = (MemorySegment) loadCustomDocument.invoke(access, pw);
                if (doc.equals(MemorySegment.NULL)) {
                    throw new RenderPort.RenderError(false, lastError());
                }
                try {
                    return renderDoc(arena, doc, req);
                } finally {
                    closeDocument.invoke(doc);
                }
            } catch (RenderPort.RenderError e) {
                throw e;
            } catch (Throwable t) {
                throw new RenderPort.RenderError(false, "pdfium call failed: " + t);
            } finally {
                current = null;
            }
        }
    }

    private Port.Bitmap renderDoc(Arena arena, MemorySegment doc, RenderPort.Request req) throws Throwable {
        int n = (int) getPageCount.invoke(doc);
        if (req.page < 0 || req.page >= n) {
            throw new RenderPort.RenderError(false, "page " + req.page + " of " + n);
        }
        MemorySegment page = (MemorySegment) loadPage.invoke(doc, req.page);
        if (page.equals(MemorySegment.NULL)) {
            throw new RenderPort.RenderError(false, lastError());
        }
        try {
            int w = RenderPort.pixelsOf((double) (float) pageWidth.invoke(page), req.dpi);
            int h = RenderPort.pixelsOf((double) (float) pageHeight.invoke(page), req.dpi);
            if ((long) w * (long) h > maxPixels) {
                throw new RenderPort.RenderError(false, w + "x" + h + " pixels is over the limit of " + maxPixels);
            }
            int flags = FPDF_REVERSE_BYTE_ORDER;
            switch (req.layer) {
                case RenderPort.LAYER_FULL:
                    flags |= FPDF_ANNOT;
                    break;
                case RenderPort.LAYER_NO_TEXT:
                    prunePage(req.layer, page);
                    flags |= FPDF_ANNOT;
                    break;
                default:
                    prunePage(req.layer, page);
                    break;
            }
            // the pixels live in native memory: pdfium keeps the pointer across calls
            long bytes = (long) w * h * 4;
            MemorySegment buf = arena.allocate(bytes, 16);
            MemorySegment bm = (MemorySegment) bitmapCreateEx.invoke(w, h, BITMAP_BGRX, buf, w * 4);
            if (bm.equals(MemorySegment.NULL)) {
                throw new RenderPort.RenderError(false, "the bitmap could not be created");
            }
            bitmapFillRect.invoke(bm, 0, 0, w, h, 0xFFFFFFFFL);
            renderPageBitmap.invoke(bm, page, 0, 0, w, h, 0, flags);
            bitmapDestroy.invoke(bm);
            byte[] raw = new byte[(int) bytes];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, raw, 0, raw.length);
            byte[] rgb = new byte[w * h * 3];
            for (int i = 0, o = 0; i < raw.length; i += 4) {
                rgb[o++] = raw[i];
                rgb[o++] = raw[i + 1];
                rgb[o++] = raw[i + 2];
            }
            return new Port.Bitmap(w, h, 3, rgb);
        } finally {
            closePage.invoke(page);
        }
    }
}
