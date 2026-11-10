namespace emon.Ffi;
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

/// <summary>Experimental PP-OCR: ABI copies only; the qualified Rust port owns policy.</summary>
public sealed unsafe class PpOcr : emon.Api.IOcr, IDisposable {
    private const string Lib = "emon_ppocr_adapter";
    private const CallingConvention Cc = CallingConvention.Cdecl;
    [DllImport(Lib, EntryPoint="emon_ppocr_open_v1", CallingConvention=Cc)] private static extern nint OpenNative(nint dir, byte* error, nuint cap);
    [DllImport(Lib, EntryPoint="emon_ppocr_close_v1", CallingConvention=Cc)] private static extern void CloseNative(nint p);
    [DllImport(Lib, EntryPoint="emon_ppocr_read_v1", CallingConvention=Cc)] private static extern int ReadNative(nint p, int* r, nuint n, byte* pixels, nuint bytes, int w, int h, int ch, out nint answer, out nuint count, byte* error, nuint cap);
    [DllImport(Lib, EntryPoint="emon_ppocr_free_v1", CallingConvention=Cc)] private static extern void FreeNative(nint p, nuint n);
    private nint port;
    private readonly object gate = new object();
    private readonly emon.Api.IOcr incumbent;
    private PpOcr(nint p, emon.Api.IOcr incumbent) { port=p; this.incumbent=incumbent; }
    public static PpOcr Open(string? dir, emon.Api.IOcr incumbent) {
        if(string.IsNullOrEmpty(dir)) dir=Environment.GetEnvironmentVariable("EMON_PPOCR_MODELS") ?? ".cache/ppocr";
        foreach(string name in new[]{"ch_PP-OCRv5_det_mobile.onnx","en_PP-OCRv5_rec_mobile.onnx","ch_ppocr_mobile_v2.0_cls_mobile.onnx"})
            if(!System.IO.File.Exists(System.IO.Path.Combine(dir,name))) throw new Unavailable("ppocr-models-missing");
        string ext=OperatingSystem.IsMacOS()?"dylib":OperatingSystem.IsWindows()?"dll":"so";
        string file=Environment.GetEnvironmentVariable("EMON_PPOCR_LIB") ?? ".cache/ppocr/libemon_ppocr_adapter."+ext;
        nint library=Native.Pin(Lib,new List<string>{file});
        if(library==0) throw new Unavailable("ppocr-runtime-missing");
        foreach(string symbol in new[]{"emon_ppocr_open_v1","emon_ppocr_close_v1","emon_ppocr_read_v1","emon_ppocr_free_v1"})
            if(!NativeLibrary.TryGetExport(library,symbol,out _)) throw new Unavailable("ppocr-runtime-missing");
        using var a=new Native.Arena();
        byte* error=stackalloc byte[2048];
        nint p=OpenNative(a.Str(dir),error,2048);
        if(p==0) throw new Unavailable(Marshal.PtrToStringUTF8((nint)error) ?? "ppocr-internal");
        return new PpOcr(p,incumbent);
    }
    public void Dispose() { lock(gate) { if(port!=0) { CloseNative(port); port=0; } } GC.SuppressFinalize(this); }
    ~PpOcr() { Dispose(); }
    public List<string[]> Producer() {
        var p=incumbent==null?new List<string[]>():new List<string[]>(incumbent.Producer());
        var id=new System.Text.StringBuilder(); for(int i=0;i<emon.Ocrengine.OeIdentity.Length;i++) id.Append(char.ConvertFromUtf32(emon.Ocrengine.OeIdentity[i]));
        p.Add(new[]{"ocr-engine","tesseract+ppocr"}); p.Add(new[]{"ocr-model",id.ToString()});
        p.Add(new[]{"pp-lexicon","tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"});
        p.Add(new[]{"onnxruntime","1.22.0 CPU sequential intra=1 inter=1"}); return p;
    }
    public emon.Planrun.Answer Fulfil(emon.Planrun.Request req, Dictionary<int,emon.Port.Bitmap> bitmaps, bool keep) {
        var r=emon.Port.NewCells(); foreach(int v in req.Rec) emon.Port.CellsPush(r,v);
        if(emon.Ocrport.OpIdentityAt(r)<0) {
            if(incumbent==null) throw emon.Planrun.FulfilFailed("ppocr-incumbent-missing");
            return incumbent.Fulfil(req,bitmaps,keep);
        }
        if(!bitmaps.TryGetValue(req.Field(emon.Plan.OcBitmap()),out var b)) throw emon.Planrun.FulfilFailed("ppocr-bad-request");
        lock(gate) {
            if(port==0) throw emon.Planrun.FulfilFailed("ppocr-closed");
            byte* error=stackalloc byte[2048];
            fixed(int* rr=req.Rec) fixed(byte* pixels=b.D) {
                int status=ReadNative(port,rr,(nuint)req.Rec.Length,pixels,(nuint)b.D.Length,b.W,b.H,b.Ch,out nint answer,out nuint n,error,2048);
                if(status!=0) throw emon.Planrun.FulfilFailed(Marshal.PtrToStringUTF8((nint)error) ?? "ppocr-internal");
                try { int[] cells=new int[checked((int)n)]; Marshal.Copy(answer,cells,0,cells.Length); return new emon.Planrun.Answer(cells,null); }
                finally { FreeNative(answer,n); }
            }
        }
    }
}
