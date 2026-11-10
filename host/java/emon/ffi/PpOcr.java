package emon.ffi;

import emon.Port;
import emon.Planrun;
import emon.Ocrengine;
import emon.Ocrport;
import emon.Plan;
import emon.api.Ocr;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;

/** Experimental PP-OCR: copies canonical requests/answers through ABI v1. */
public final class PpOcr implements Ocr, AutoCloseable {
    private final MethodHandle close, read, release;
    private MemorySegment port;
    private final Ocr incumbent;
    private static final Linker LINK = Linker.nativeLinker();
    private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong N = ValueLayout.JAVA_LONG;
    private static final AddressLayout P = ValueLayout.ADDRESS;
    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return LINK.downcallHandle(lib.find(name).orElseThrow(), fd);
    }
    public static PpOcr open(String dir, Ocr incumbent) {
        if (dir == null || dir.isEmpty()) dir = System.getenv().getOrDefault("EMON_PPOCR_MODELS", ".cache/ppocr");
        for (String name : List.of("ch_PP-OCRv5_det_mobile.onnx", "en_PP-OCRv5_rec_mobile.onnx", "ch_ppocr_mobile_v2.0_cls_mobile.onnx"))
            if (!Files.isRegularFile(Path.of(dir,name))) throw new Unavailable("ppocr-models-missing");
        String ext = System.getProperty("os.name").equals("Mac OS X") ? "dylib" : "so";
        String file = System.getenv().getOrDefault("EMON_PPOCR_LIB", ".cache/ppocr/libemon_ppocr_adapter." + ext);
        SymbolLookup lib;
        try { lib = SymbolLookup.libraryLookup(Path.of(file).toAbsolutePath(), Arena.global()); }
        catch (Throwable e) { throw new Unavailable("ppocr-runtime-missing"); }
        return new PpOcr(lib,dir,incumbent);
    }
    private PpOcr(SymbolLookup lib, String dir, Ocr incumbent) {
        this.incumbent = incumbent;
        try {
            var open = fn(lib,"emon_ppocr_open_v1",FunctionDescriptor.of(P,P,P,N));
            close = fn(lib,"emon_ppocr_close_v1",FunctionDescriptor.ofVoid(P));
            read = fn(lib,"emon_ppocr_read_v1",FunctionDescriptor.of(I,P,P,N,P,N,I,I,I,P,P,P,N));
            release = fn(lib,"emon_ppocr_free_v1",FunctionDescriptor.ofVoid(P,N));
            try (Arena a = Arena.ofConfined()) {
                var error = a.allocate(2048);
                port = (MemorySegment)open.invokeExact(a.allocateFrom(dir),error,2048L);
                if (port.equals(MemorySegment.NULL)) throw new Unavailable(error.getString(0));
            }
        } catch (Unavailable e) { throw e; }
        catch (Throwable e) { throw new Unavailable("ppocr-runtime-missing"); }
    }
    public synchronized void close() {
        if (!port.equals(MemorySegment.NULL)) {
            try { close.invokeExact(port); port = MemorySegment.NULL; }
            catch (Throwable e) { throw new IllegalStateException(e); }
        }
    }
    public List<String[]> producer() {
        List<String[]> out = new ArrayList<>();
        if (incumbent != null) out.addAll(incumbent.producer());
        StringBuilder id = new StringBuilder();
        for(int k=0;k<Ocrengine.OE_IDENTITY.length;k++) id.appendCodePoint(Ocrengine.OE_IDENTITY[k]);
        out.add(new String[]{"ocr-engine","tesseract+ppocr"});
        out.add(new String[]{"ocr-model",id.toString()});
        out.add(new String[]{"pp-lexicon","tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"});
        out.add(new String[]{"onnxruntime","1.22.0 CPU sequential intra=1 inter=1"});
        return out;
    }
    public synchronized Planrun.Answer fulfil(Planrun.Request req, Map<Integer,Port.Bitmap> bitmaps, boolean keep) {
        var r=Port.newCells(); for(int v:req.rec) Port.cellsPush(r,v);
        if(Ocrport.opIdentityAt(r)<0) {
            if(incumbent==null) throw Planrun.fulfilFailed("ppocr-incumbent-missing");
            return incumbent.fulfil(req,bitmaps,keep);
        }
        Port.Bitmap b=bitmaps.get(req.field(Plan.ocBitmap()));
        if(b==null) throw Planrun.fulfilFailed("ppocr-bad-request");
        if(port.equals(MemorySegment.NULL)) throw Planrun.fulfilFailed("ppocr-closed");
        try(Arena a=Arena.ofConfined()) {
            var out=a.allocate(P); var count=a.allocate(N); var error=a.allocate(2048);
            int status=(int)read.invokeExact(port,a.allocateFrom(I,req.rec),(long)req.rec.length,
                a.allocateFrom(ValueLayout.JAVA_BYTE,b.d),(long)b.d.length,b.w,b.h,b.ch,out,count,error,2048L);
            if(status!=0) throw Planrun.fulfilFailed(error.getString(0));
            MemorySegment data=out.get(P,0); long n=count.get(N,0);
            try { return new Planrun.Answer(data.reinterpret(Math.multiplyExact(n,4)).toArray(I)); }
            finally { release.invokeExact(data,n); }
        } catch(Planrun.PlanError e) { throw e; }
        catch(Throwable e) { throw Planrun.fulfilFailed("ppocr-internal: " + e); }
    }
}
