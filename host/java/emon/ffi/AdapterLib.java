package emon.ffi;

import emon.Planrun;
import emon.Port;
import emon.api.OcrEngines;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * The Java host's loader of adapter libraries (DEVLOG D925, K3): the one export `emon_ocr_adapter_v1` of `host/c/include/emon_adapter.h` (ABI 1) and the calls through the adapter's
 * function pointers, over the FFM API. It copies buffers and decides nothing: registration, routing and the checks are `emon.api.OcrEngines`' and kin's. This is how Java reaches
 * Tesseract, through `libemon_ocr_tesseract`, instead of binding libtesseract (the twin it replaces).
 *
 * <p>The layouts below are the header's, on a 64-bit platform: status {reason[96], message[224]} (320 bytes); image {int32 width, height, channels, stride; pixels at 16; int32 dpi
 * at 24} (32); info {uint32 engine, caps, native, needs, flags; config at 24, observed at 32, producer at 40, langs at 48, nlangs at 56} (64); answer {cells at 0, ncells at 8,
 * binary at 16, int32 binary_width at 24, binary_height at 28, owner at 32} (40); adapter {uint32 abi, size; name at 8, ctx at 16; describe 24, supports 32, read 40, start 48,
 * cancel 56, release 64, bind 72, close 80} (88).
 */
public final class AdapterLib implements OcrEngines.Adapter {
    private static final Linker LINK = Linker.nativeLinker();
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong N = ValueLayout.JAVA_LONG;
    private static final AddressLayout P = ValueLayout.ADDRESS;
    private static final long STATUS = 320, VT_SIZE = 88;

    private final Arena arena;
    private MemorySegment vt;
    private final MemorySegment ctx;
    private final MethodHandle supports, read, release, close;
    private final OcrEngines.AdapterInfo info;

    private static MethodHandle handle(MemorySegment vt, long off, FunctionDescriptor fd) {
        MemorySegment f = vt.get(P, off);
        return f.equals(MemorySegment.NULL) ? null : LINK.downcallHandle(f, fd);
    }

    private static String text(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? "" : p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    private static String statusText(MemorySegment st, long off, String fallback) {
        String s = st.getString(off, StandardCharsets.UTF_8);
        return s.isEmpty() ? fallback : s;
    }

    /** Opens the adapter library at path. */
    public static AdapterLib open(String path, String config, String packsDir) {
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup lib;
            try {
                lib = SymbolLookup.libraryLookup(Path.of(path).toAbsolutePath(), arena);
            } catch (Throwable e) {
                throw new Unavailable("adapter-load-failed: " + path + " did not load");
            }
            MemorySegment sym = lib.find("emon_ocr_adapter_v1").orElseThrow(() -> new Unavailable("adapter-load-failed: " + path + " has no emon_ocr_adapter_v1"));
            MethodHandle opener = LINK.downcallHandle(sym, FunctionDescriptor.of(P, P, P, P));
            MemorySegment st = arena.allocate(STATUS);
            // locals, not conditionals inside the call: a conditional argument of invokeExact is typed Object and the call then fails with WrongMethodTypeException
            MemorySegment cfgSeg = config == null ? MemorySegment.NULL : arena.allocateFrom(config);
            MemorySegment dirSeg = packsDir == null || packsDir.isEmpty() ? MemorySegment.NULL : arena.allocateFrom(packsDir);
            MemorySegment v = ((MemorySegment) opener.invokeExact(cfgSeg, dirSeg, st)).reinterpret(VT_SIZE);
            if (v.equals(MemorySegment.NULL)) {
                throw new Unavailable(statusText(st, 0, "adapter-open-failed") + ": " + path + ": " + statusText(st, 96, ""));
            }
            return new AdapterLib(arena, v, path);
        } catch (Unavailable e) {
            arena.close();
            throw e;
        } catch (Throwable e) {
            arena.close();
            throw new Unavailable("adapter-load-failed: " + path + ": " + e, e);
        }
    }

    private AdapterLib(Arena arena, MemorySegment vt, String path) throws Throwable {
        this.arena = arena;
        this.vt = vt;
        this.ctx = vt.get(P, 16);
        this.close = handle(vt, 80, FunctionDescriptor.ofVoid(P));
        int abi = vt.get(I, 0), size = vt.get(I, 4);
        MethodHandle describe = handle(vt, 24, FunctionDescriptor.of(I, P, P, P));
        this.supports = handle(vt, 32, FunctionDescriptor.of(I, P, P, N, P));
        this.read = handle(vt, 40, FunctionDescriptor.of(I, P, P, N, P, I, P, P));
        this.release = handle(vt, 64, FunctionDescriptor.ofVoid(P, P));
        if (abi < 1 || abi > emon.Adapters.adAbi() || size < VT_SIZE || describe == null || read == null) {
            closeNative();
            throw new Unavailable("adapter-abi-unsupported: " + path + " is built for ABI " + abi + " (" + size + " bytes); this host speaks " + emon.Adapters.adAbi());
        }
        MemorySegment raw = arena.allocate(64), st = arena.allocate(STATUS);
        if ((int) describe.invokeExact(ctx, raw, st) != 0) {
            String why = statusText(st, 0, "describe failed") + ": " + statusText(st, 96, "");
            closeNative();
            throw new Unavailable("adapter-describe-failed: " + why);
        }
        OcrEngines.AdapterInfo i = new OcrEngines.AdapterInfo();
        String name = text(vt.get(P, 8));
        i.name = name.isEmpty() ? "adapter" : name;
        i.engine = raw.get(I, 0);
        i.caps = raw.get(I, 4);
        i.measured = raw.get(I, 8);
        i.needs = raw.get(I, 12);
        i.flags = raw.get(I, 16);
        i.config = text(raw.get(P, 24));
        i.observed = text(raw.get(P, 32));
        for (String line : text(raw.get(P, 40)).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                i.producer.add(new String[] {line.substring(0, eq), line.substring(eq + 1)});
            }
        }
        this.info = i;
    }

    private void closeNative() throws Throwable {
        if (close != null) {
            close.invokeExact(ctx);
        }
    }

    @Override
    public OcrEngines.AdapterInfo info() {
        return info;
    }

    @Override
    public synchronized void close() {
        if (vt != null) {
            try {
                closeNative();
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            } finally {
                vt = null;
                arena.close();
            }
        }
    }

    @Override
    public synchronized OcrEngines.Reading read(Planrun.Request req, Map<Integer, Port.Bitmap> bitmaps, boolean keepBinary) {
        Port.Bitmap b = bitmaps.get(req.field(emon.Plan.ocBitmap()));
        if (b == null || b.d.length == 0) {
            throw Planrun.fulfilFailed("ocr-adapter-invalid: " + info.name + ": the request names no bitmap");
        }
        if (vt == null) {
            throw Planrun.fulfilFailed("ocr-adapter-invalid: " + info.name + ": closed");
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment st = a.allocate(STATUS), rec = a.allocateFrom(I, req.rec);
            long n = req.rec.length;
            OcrEngines.Reading out = new OcrEngines.Reading();
            if (supports != null && (int) supports.invokeExact(ctx, rec, n, st) != 0) {
                out.status = OcrEngines.UNSUPPORTED;
                out.reason = statusText(st, 0, "unsupported");
                return out;
            }
            MemorySegment img = a.allocate(32);
            img.set(I, 0, b.w);
            img.set(I, 4, b.h);
            img.set(I, 8, b.ch);
            img.set(I, 12, b.w * b.ch);
            img.set(P, 16, a.allocateFrom(ValueLayout.JAVA_BYTE, b.d));
            img.set(I, 24, emon.Ocr.ocfDpi(req.field(emon.Plan.ocFlags())));
            MemorySegment ans = a.allocate(40);
            int rc = (int) read.invokeExact(ctx, rec, n, img, keepBinary ? 1 : 0, ans, st);
            if (rc != 0) {
                out.status = rc == OcrEngines.UNSUPPORTED ? OcrEngines.UNSUPPORTED : OcrEngines.FAILED;
                out.reason = statusText(st, 0, rc == OcrEngines.UNSUPPORTED ? "unsupported" : "failed");
                if (release != null && (!ans.get(P, 0).equals(MemorySegment.NULL) || !ans.get(P, 32).equals(MemorySegment.NULL))) {
                    release.invokeExact(ctx, ans);
                }
                return out;
            }
            try {
                MemorySegment cells = ans.get(P, 0);
                if (!cells.equals(MemorySegment.NULL)) {
                    out.rec = cells.reinterpret(Math.multiplyExact(ans.get(N, 8), 4L)).toArray(I);
                }
                MemorySegment bin = ans.get(P, 16);
                int bw = ans.get(I, 24), bh = ans.get(I, 28);
                if (keepBinary && !bin.equals(MemorySegment.NULL) && bw > 0 && bh > 0) {
                    out.binary = new Port.Bitmap(bw, bh, 1, bin.reinterpret((long) bw * bh).toArray(ValueLayout.JAVA_BYTE));
                }
                return out;
            } finally {
                if (release != null) {
                    release.invokeExact(ctx, ans);
                }
            }
        } catch (Planrun.PlanError e) {
            throw e;
        } catch (Throwable e) {
            throw Planrun.fulfilFailed("ocr-adapter-invalid: " + info.name + ": " + e);
        }
    }
}
