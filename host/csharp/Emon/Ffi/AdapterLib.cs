// The C# host's loader of adapter libraries (DEVLOG D925, K3): the one export `emon_ocr_adapter_v1` of `host/c/include/emon_adapter.h` (ABI 1) and the calls through the adapter's
// function pointers. It copies buffers and decides nothing: registration, routing and the checks are `emon.Api.OcrEngines`' and kin's. This is how C# reaches Tesseract, through
// `libemon_ocr_tesseract`, instead of P/Invoking libtesseract (the twin it replaces).

namespace emon.Ffi;

using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using emon.Api;

public sealed unsafe class AdapterLib : IAdapter {
    [StructLayout(LayoutKind.Sequential)] private struct Status { public fixed byte reason[96]; public fixed byte message[224]; }
    [StructLayout(LayoutKind.Sequential)] private struct Image { public int width, height, channels, stride; public byte* pixels; public int dpi; }
    [StructLayout(LayoutKind.Sequential)] private struct RawInfo { public uint engine, caps, native, needs, flags; public byte* config, observed, producer; public byte** langs; public nuint nlangs; }
    [StructLayout(LayoutKind.Sequential)] private struct Answer { public int* cells; public nuint ncells; public byte* binary; public int binaryWidth, binaryHeight; public void* owner; }
    [StructLayout(LayoutKind.Sequential)]
    private struct Vt {
        public uint abi, size;
        public byte* name;
        public void* ctx;
        public delegate* unmanaged[Cdecl]<void*, RawInfo*, Status*, int> describe;
        public delegate* unmanaged[Cdecl]<void*, int*, nuint, Status*, int> supports;
        public delegate* unmanaged[Cdecl]<void*, int*, nuint, Image*, int, Answer*, Status*, int> read;
        public delegate* unmanaged[Cdecl]<void*, int*, nuint, Image*, ulong, Status*, int> start;
        public delegate* unmanaged[Cdecl]<void*, ulong, void> cancel;
        public delegate* unmanaged[Cdecl]<void*, Answer*, void> release;
        public delegate* unmanaged[Cdecl]<void*, void*, void> bind;
        public delegate* unmanaged[Cdecl]<void*, void> close;
    }

    private Vt* vt;
    private readonly nint lib;
    private readonly AdapterInfo info;
    private readonly object gate = new object();

    private AdapterLib(Vt* vt, nint lib, AdapterInfo info) { this.vt = vt; this.lib = lib; this.info = info; }

    private static string Text(Status* st, byte* f, string fallback) {
        string s = Marshal.PtrToStringUTF8((nint) f) ?? "";
        return s.Length == 0 ? fallback : s;
    }

    /// <summary>Opens the adapter library at path.</summary>
    public static AdapterLib Open(string path, string config, string packsDir) {
        if (!NativeLibrary.TryLoad(path, out nint lib)) {
            throw new Unavailable($"adapter-load-failed: {path} did not load");
        }
        if (!NativeLibrary.TryGetExport(lib, "emon_ocr_adapter_v1", out nint sym)) {
            throw new Unavailable($"adapter-load-failed: {path} has no emon_ocr_adapter_v1");
        }
        using var a = new Native.Arena();
        Status st = default;
        var open = (delegate* unmanaged[Cdecl]<byte*, byte*, Status*, Vt*>) sym;
        Vt* vt = open(config == null ? null : (byte*) a.Str(config), string.IsNullOrEmpty(packsDir) ? null : (byte*) a.Str(packsDir), &st);
        if (vt == null) {
            throw new Unavailable($"{Text(&st, st.reason, "adapter-open-failed")}: {path}: {Text(&st, st.message, "")}");
        }
        void Close() { if (vt->close != null) { vt->close(vt->ctx); } }
        if (vt->abi < 1 || vt->abi > (uint) emon.Adapters.AdAbi() || vt->size < (uint) sizeof(Vt) || vt->describe == null || vt->read == null) {
            uint abi = vt->abi, size = vt->size;
            Close();
            throw new Unavailable($"adapter-abi-unsupported: {path} is built for ABI {abi} ({size} bytes); this host speaks {emon.Adapters.AdAbi()}");
        }
        RawInfo raw = default;
        Status dst = default;
        if (vt->describe(vt->ctx, &raw, &dst) != 0) {
            string why = $"{Text(&dst, dst.reason, "describe failed")}: {Text(&dst, dst.message, "")}";
            Close();
            throw new Unavailable($"adapter-describe-failed: {why}");
        }
        var info = new AdapterInfo {
            Name = Native.CStr((nint) vt->name) is { Length: > 0 } n ? n : "adapter",
            Engine = (int) raw.engine, Caps = (int) raw.caps, Measured = (int) raw.native, Needs = (int) raw.needs, Flags = (int) raw.flags,
            Config = Native.CStr((nint) raw.config), Observed = Native.CStr((nint) raw.observed),
        };
        foreach (string line in Native.CStr((nint) raw.producer).Split('\n')) {
            int eq = line.IndexOf('=');
            if (eq > 0) {
                info.Producer.Add(new[] { line.Substring(0, eq), line.Substring(eq + 1) });
            }
        }
        return new AdapterLib(vt, lib, info);
    }

    public AdapterInfo Info() { return info; }

    public void Dispose() {
        lock (gate) {
            if (vt != null) {
                if (vt->close != null) { vt->close(vt->ctx); }
                vt = null;
            }
        }
        GC.SuppressFinalize(this);
    }

    ~AdapterLib() { Dispose(); }

    public Reading Read(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bitmaps, bool keepBinary) {
        if (!bitmaps.TryGetValue(req.Field(emon.Plan.OcBitmap()), out emon.Port.Bitmap b) || b.D.Length == 0) {
            throw emon.Planrun.FulfilFailed($"ocr-adapter-invalid: {info.Name}: the request names no bitmap");
        }
        lock (gate) {
            if (vt == null) {
                throw emon.Planrun.FulfilFailed($"ocr-adapter-invalid: {info.Name}: closed");
            }
            Status st = default;
            fixed (int* rec = req.Rec) fixed (byte* px = b.D) {
                nuint n = (nuint) req.Rec.Length;
                if (vt->supports != null && vt->supports(vt->ctx, rec, n, &st) != 0) {
                    return new Reading { Status = IAdapter.Unsupported, Reason = Text(&st, st.reason, "unsupported") };
                }
                var img = new Image { width = b.W, height = b.H, channels = b.Ch, stride = b.W * b.Ch, pixels = px, dpi = emon.Ocr.OcfDpi(req.Field(emon.Plan.OcFlags())) };
                Answer ans = default;
                int rc = vt->read(vt->ctx, rec, n, &img, keepBinary ? 1 : 0, &ans, &st);
                if (rc != 0) {
                    string reason = Text(&st, st.reason, rc == IAdapter.Unsupported ? "unsupported" : "failed");
                    if ((ans.cells != null || ans.owner != null) && vt->release != null) { vt->release(vt->ctx, &ans); }
                    return new Reading { Status = rc == IAdapter.Unsupported ? IAdapter.Unsupported : IAdapter.Failed, Reason = reason };
                }
                try {
                    var r = new Reading();
                    if (ans.cells != null) {
                        r.Rec = new int[checked((int) ans.ncells)];
                        Marshal.Copy((nint) ans.cells, r.Rec, 0, r.Rec.Length);
                    }
                    if (keepBinary && ans.binary != null && ans.binaryWidth > 0 && ans.binaryHeight > 0) {
                        var bytes = new byte[ans.binaryWidth * ans.binaryHeight];
                        Marshal.Copy((nint) ans.binary, bytes, 0, bytes.Length);
                        r.Binary = new emon.Port.Bitmap(ans.binaryWidth, ans.binaryHeight, 1, bytes);
                    }
                    return r;
                } finally {
                    if (vt->release != null) { vt->release(vt->ctx, &ans); }
                }
            }
        }
    }
}
