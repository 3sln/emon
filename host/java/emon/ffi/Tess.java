// The Java host's binding to Tesseract's C API through the Java FFM API, java.lang.foreign (DEVLOG P9.1, D520; P6.5,
// D211-D214): the twin of `host/go/tess.go` and `host/rust/src/tess.rs`, and read against them. LIVE ONLY: the libraries
// (`libtesseract`, `libleptonica`) are found at run time and nothing in `bb gates` loads them (a gate replays a
// transcript; the engine is the host's opinion, D008). Run the JVM with `--enable-native-access=ALL-UNNAMED`.
// `Tess.open` is the `Ocr` port an embedder puts in `Config.ocr`.
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

package emon.ffi;

import emon.Ocrport;
import emon.Plan;
import emon.Planrun;
import emon.Port;
import emon.api.Ocr;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class Tess implements Ocr {
    private static final int RIL_BLOCK = 0, RIL_PARA = 1, RIL_TEXTLINE = 2, RIL_WORD = 3, RIL_SYMBOL = 4;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfFloat FLT = ValueLayout.JAVA_FLOAT;

    private final MethodHandle version, create, delete, init3, setVariable, setPageSegMode, setImage, setSourceResolution, setRectangle,
        recognize, clear, getIterator, getThresholded, detectOsd, deleteText, itDelete, itCopy, itPageIterator, itNext, itText,
        itConfidence, itWordLang, itIsDict, itIsNumeric, itFont, itChoiceIterator, chNext, chText, chConf, chDelete,
        piAtBeginning, piAtFinal, piBox, piBaseline, piBlockType, piOrientation,
        pixW, pixH, pixDepth, pixPixel, pixDestroy, leptVersion, leptFree;

    private final String tessdata;

    /**
     * One ENGINE: initialised instances per language string, used by one thread at a time. A POOL OF ENGINES (P10.2, D509): Tesseract's API objects are not
     * thread safe, so each reading takes an engine of its own from the pool (or makes one, which is a full model load) and gives it back; the pool holds as
     * many as were ever wanted at once, which is as many as the conversions and the workers of a `parallel` conversion that read at the same moment. A serial
     * run has one. A reading is a function of the request and the model alone (the LSTM engine learns nothing between pages), so which engine reads a page
     * does not show in the answer.
     */
    private static final class Engine {
        final Map<String, MemorySegment> apis = new HashMap<>();
        final List<String> notes = new ArrayList<>();
    }

    private final java.util.ArrayDeque<Engine> idle = new java.util.ArrayDeque<>();
    // the resolution given to the engine when a request names none (the request's own flags win); 0: none, as Go and the Engine
    private volatile int dpi = 0;

    /** Sets the resolution used when a request carries none (the spike's recording used the render's 150). */
    public Tess withDefaultDpi(int dpi) {
        this.dpi = dpi;
        return this;
    }
    private final List<String[]> producer;

    private Tess(SymbolLookup t, SymbolLookup l, String tessdata) {
        this.tessdata = tessdata;
        version = fn(t, "TessVersion", FunctionDescriptor.of(PTR));
        create = fn(t, "TessBaseAPICreate", FunctionDescriptor.of(PTR));
        delete = fn(t, "TessBaseAPIDelete", FunctionDescriptor.ofVoid(PTR));
        init3 = fn(t, "TessBaseAPIInit3", FunctionDescriptor.of(INT, PTR, PTR, PTR));
        setVariable = fn(t, "TessBaseAPISetVariable", FunctionDescriptor.of(INT, PTR, PTR, PTR));
        setPageSegMode = fn(t, "TessBaseAPISetPageSegMode", FunctionDescriptor.ofVoid(PTR, INT));
        setImage = fn(t, "TessBaseAPISetImage", FunctionDescriptor.ofVoid(PTR, PTR, INT, INT, INT, INT));
        setSourceResolution = fn(t, "TessBaseAPISetSourceResolution", FunctionDescriptor.ofVoid(PTR, INT));
        setRectangle = fn(t, "TessBaseAPISetRectangle", FunctionDescriptor.ofVoid(PTR, INT, INT, INT, INT));
        recognize = fn(t, "TessBaseAPIRecognize", FunctionDescriptor.of(INT, PTR, PTR));
        clear = fn(t, "TessBaseAPIClear", FunctionDescriptor.ofVoid(PTR));
        getIterator = fn(t, "TessBaseAPIGetIterator", FunctionDescriptor.of(PTR, PTR));
        getThresholded = fn(t, "TessBaseAPIGetThresholdedImage", FunctionDescriptor.of(PTR, PTR));
        detectOsd = fn(t, "TessBaseAPIDetectOrientationScript", FunctionDescriptor.of(INT, PTR, PTR, PTR, PTR, PTR));
        deleteText = fn(t, "TessDeleteText", FunctionDescriptor.ofVoid(PTR));
        itDelete = fn(t, "TessResultIteratorDelete", FunctionDescriptor.ofVoid(PTR));
        itCopy = fn(t, "TessResultIteratorCopy", FunctionDescriptor.of(PTR, PTR));
        itPageIterator = fn(t, "TessResultIteratorGetPageIterator", FunctionDescriptor.of(PTR, PTR));
        itNext = fn(t, "TessResultIteratorNext", FunctionDescriptor.of(INT, PTR, INT));
        itText = fn(t, "TessResultIteratorGetUTF8Text", FunctionDescriptor.of(PTR, PTR, INT));
        itConfidence = fn(t, "TessResultIteratorConfidence", FunctionDescriptor.of(FLT, PTR, INT));
        itWordLang = fn(t, "TessResultIteratorWordRecognitionLanguage", FunctionDescriptor.of(PTR, PTR));
        itIsDict = fn(t, "TessResultIteratorWordIsFromDictionary", FunctionDescriptor.of(INT, PTR));
        itIsNumeric = fn(t, "TessResultIteratorWordIsNumeric", FunctionDescriptor.of(INT, PTR));
        itFont = fn(t, "TessResultIteratorWordFontAttributes", FunctionDescriptor.of(PTR, PTR, PTR, PTR, PTR, PTR, PTR, PTR, PTR, PTR));
        itChoiceIterator = fn(t, "TessResultIteratorGetChoiceIterator", FunctionDescriptor.of(PTR, PTR));
        chNext = fn(t, "TessChoiceIteratorNext", FunctionDescriptor.of(INT, PTR));
        chText = fn(t, "TessChoiceIteratorGetUTF8Text", FunctionDescriptor.of(PTR, PTR));
        chConf = fn(t, "TessChoiceIteratorConfidence", FunctionDescriptor.of(FLT, PTR));
        chDelete = fn(t, "TessChoiceIteratorDelete", FunctionDescriptor.ofVoid(PTR));
        piAtBeginning = fn(t, "TessPageIteratorIsAtBeginningOf", FunctionDescriptor.of(INT, PTR, INT));
        piAtFinal = fn(t, "TessPageIteratorIsAtFinalElement", FunctionDescriptor.of(INT, PTR, INT, INT));
        piBox = fn(t, "TessPageIteratorBoundingBox", FunctionDescriptor.of(INT, PTR, INT, PTR, PTR, PTR, PTR));
        piBaseline = fn(t, "TessPageIteratorBaseline", FunctionDescriptor.of(INT, PTR, INT, PTR, PTR, PTR, PTR));
        piBlockType = fn(t, "TessPageIteratorBlockType", FunctionDescriptor.of(INT, PTR));
        piOrientation = fn(t, "TessPageIteratorOrientation", FunctionDescriptor.ofVoid(PTR, PTR, PTR, PTR, PTR));
        pixW = fn(l, "pixGetWidth", FunctionDescriptor.of(INT, PTR));
        pixH = fn(l, "pixGetHeight", FunctionDescriptor.of(INT, PTR));
        pixDepth = fn(l, "pixGetDepth", FunctionDescriptor.of(INT, PTR));
        pixPixel = fn(l, "pixGetPixel", FunctionDescriptor.of(INT, PTR, INT, INT, PTR));
        pixDestroy = fn(l, "pixDestroy", FunctionDescriptor.ofVoid(PTR));
        leptVersion = fn(l, "getLeptonicaVersion", FunctionDescriptor.of(PTR));
        leptFree = fn(l, "lept_free", FunctionDescriptor.ofVoid(PTR));
        this.producer = makeProducer();
    }

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name).orElseThrow(() -> new Unavailable("the Tesseract/Leptonica library has no symbol " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    /**
     * Opens the engine. `tessdata` is the directory of `.traineddata` files; null looks in $TESSDATA_PREFIX and the usual places.
     *
     * @throws Unavailable when the libraries or the models cannot be found
     */
    public static Tess open(String tessdata) {
        String dir = tessdata != null && !tessdata.isEmpty() ? tessdata : findTessdata();
        if (dir == null) {
            throw new Unavailable("no tessdata directory found (set TESSDATA_PREFIX, or pass the directory; `brew install tesseract` has the English model)");
        }
        List<String> dirs = new ArrayList<>();
        String env = System.getenv("EMON_TESS_LIB");
        if (env != null && !env.isEmpty()) {
            dirs.add(env);
        }
        dirs.addAll(List.of("/opt/homebrew/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib"));
        SymbolLookup lept = load(dirs, new String[] {"libleptonica.dylib", "libleptonica.so", "liblept.so.5", "libleptonica.so.6", "libleptonica-6.dll"}, "leptonica");
        SymbolLookup tess = load(dirs, new String[] {"libtesseract.dylib", "libtesseract.so", "libtesseract.so.5", "libtesseract55.dll"}, "tesseract");
        return new Tess(tess, lept, dir);
    }

    private static SymbolLookup load(List<String> dirs, String[] files, String bare) {
        List<String> tried = new ArrayList<>();
        for (String d : dirs) {
            for (String f : files) {
                String p = d + "/" + f;
                tried.add(p);
                try {
                    return SymbolLookup.libraryLookup(p, Arena.global());
                } catch (IllegalArgumentException e) {
                    // not there
                }
            }
        }
        try {
            return SymbolLookup.libraryLookup(bare, Arena.global());
        } catch (IllegalArgumentException e) {
            throw new Unavailable(bare + " was not found (looked in " + String.join(", ", dirs) + "). Install Tesseract (`brew install tesseract`) or set EMON_TESS_LIB to the library directory.");
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String cstr(MemorySegment p) {
        if (p.equals(MemorySegment.NULL)) {
            return "";
        }
        return p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private String takeText(MemorySegment p) throws Throwable {
        if (p.equals(MemorySegment.NULL)) {
            return "";
        }
        String s = cstr(p);
        deleteText.invoke(p);
        return s;
    }

    private String tessVersion() {
        try {
            return cstr((MemorySegment) version.invoke());
        } catch (Throwable t) {
            return "";
        }
    }

    private String leptonicaVersion() {
        try {
            MemorySegment p = (MemorySegment) leptVersion.invoke();
            if (p.equals(MemorySegment.NULL)) {
                return "";
            }
            String s = cstr(p);
            leptFree.invoke(p);
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    // WHAT A REQUEST ASKS FOR AND HOW THE ENGINE'S ANSWER IS NAMED is kin's (`emon.ocrport`, P10.7, D396): which installed models read the request's
    // languages and scripts, a model's name as a request spells it, the OSD's script as ISO 15924, a block's type. This file passes it cells and takes
    // cells; the packed four-letter languages, the model and script tables and the block-type switch of the first Java host are gone.
    private static Port.Cells cellsOf(int[] v) {
        Port.Cells c = Port.newCells();
        for (int x : v) {
            Port.cellsPush(c, x);
        }
        return c;
    }

    // Go's math.Round on float64(f)*10: half away from zero
    private static int x10(float f) {
        double d = (double) f * 10;
        return (int) (d < 0 ? -Math.floor(-d + 0.5) : Math.floor(d + 0.5));
    }

    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    private static final class Alt {
        int[] cps;
        int conf;
    }

    private static final class Sym {
        int[] bx;
        int conf;
        int[] cps;
        List<Alt> alts = new ArrayList<>();
    }

    private static final class Word {
        int conf, line, block;
        int[] bx;
        int[] text;
        int flags, lang, psize, dflags;
        List<Sym> syms = new ArrayList<>();
    }

    private static final class Line {
        int[] bx, base;
        int block, par, btype;
    }

    private static final class Block {
        int[] bx;
        int orient, wdir, tlo, desk;
    }

    // ------------------------------------------------------------------ TEST SUPPORT: the filesystem (tessdata)

    private static List<String> installedIn(String dir) {
        TreeSet<String> v = new TreeSet<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(java.nio.file.Paths.get(dir))) {
            for (java.nio.file.Path p : ds) {
                String n = p.getFileName().toString();
                if (n.endsWith(".traineddata")) {
                    String l = n.substring(0, n.length() - ".traineddata".length());
                    if (!l.equals("osd") && !l.equals("snum")) {
                        v.add(l);
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            return new ArrayList<>();
        }
        return new ArrayList<>(v);
    }

    private static boolean nonEmptyDir(String dir) {
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(java.nio.file.Paths.get(dir))) {
            return ds.iterator().hasNext();
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    private static String findTessdata() {
        List<String> cands = new ArrayList<>();
        String p = System.getenv("TESSDATA_PREFIX");
        if (p != null && !p.isEmpty()) {
            cands.add(p);
            cands.add(p + "/tessdata");
        }
        cands.addAll(List.of("/opt/homebrew/share/tessdata", "/usr/local/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata"));
        for (String d : cands) {
            if (nonEmptyDir(d)) {
                return d;
            }
        }
        return null;
    }

    private List<String[]> makeProducer() {
        List<String> models = new ArrayList<>();
        List<String> names = installedIn(tessdata);
        names.add("osd");
        for (String n : names) {
            byte[] b;
            try {
                b = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(tessdata, n + ".traineddata"));
            } catch (java.io.IOException | RuntimeException e) {
                continue;
            }
            long h = 0xcbf29ce484222325L;
            for (byte x : b) {
                h ^= (x & 0xFF);
                h *= 0x100000001b3L;
            }
            models.add(String.format("%s:%d:fnv64=%016x", n, b.length, h));
        }
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"engine", "tesseract"});
        out.add(new String[] {"version", tessVersion()});
        out.add(new String[] {"leptonica", leptonicaVersion()});
        out.add(new String[] {"engine-mode", "default (LSTM)"});
        out.add(new String[] {"lstm-choice-mode", "2"});
        out.add(new String[] {"models", String.join(" ", models)});
        out.add(new String[] {"host", "host/java/emon/ffi/Tess.java"});
        return out;
    }

    // ------------------------------------------------------------------ the engine

    private MemorySegment api(Engine eng, String langs) throws Throwable {
        MemorySegment a = eng.apis.get(langs);
        if (a != null) {
            return a;
        }
        MemorySegment h = (MemorySegment) create.invoke();
        try (Arena ar = Arena.ofConfined()) {
            if ((int) init3.invoke(h, ar.allocateFrom(tessdata), ar.allocateFrom(langs)) != 0) {
                delete.invoke(h);
                return null;
            }
            // lstm_choice_mode 2: the final beam-search candidates per symbol (D095); word confidences are carried verbatim and
            // 0 is never "wrong" (D130).
            setVariable.invoke(h, ar.allocateFrom("lstm_choice_mode"), ar.allocateFrom("2"));
        }
        eng.apis.put(langs, h);
        return h;
    }

    // the engine's thresholded image as a gray bitmap (0 ink, 255 paper): {w, h, pixels} or null
    private Object[] binaryOf(MemorySegment h, Arena ar) throws Throwable {
        MemorySegment pix = (MemorySegment) getThresholded.invoke(h);
        if (pix.equals(MemorySegment.NULL)) {
            return null;
        }
        int w = (int) pixW.invoke(pix), hh = (int) pixH.invoke(pix), d = (int) pixDepth.invoke(pix);
        byte[] px = new byte[w * hh];
        Arrays.fill(px, (byte) 255);
        MemorySegment v = ar.allocate(4);
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                pixPixel.invoke(pix, x, y, v);
                int val = v.get(INT, 0);
                boolean ink = d != 1 ? Integer.compareUnsigned(val, 128) < 0 : val != 0;
                if (ink) {
                    px[y * w + x] = 0;
                }
            }
        }
        destroyPix(pix, ar);
        return new Object[] {w, hh, px};
    }

    private void destroyPix(MemorySegment pix, Arena ar) throws Throwable {
        MemorySegment pp = ar.allocate(8);
        pp.set(PTR, 0, pix);
        pixDestroy.invoke(pp);
    }

    private static Planrun.Answer failed(int id, int status) {
        Port.Cells c = Port.newCells();
        Plan.anFail(c, Planrun.KIND_OCR, id, status);
        return new Planrun.Answer(Port.cellsVec(c));
    }

    private Planrun.Answer fulfilNative(Engine eng, Planrun.Request req, Map<Integer, Port.Bitmap> bm, boolean keepBinary) throws Throwable {
        int id = req.id();
        Port.Bitmap b = bm.get(req.field(Plan.ocBitmap()));
        if (b == null) {
            return failed(id, Planrun.STATUS_FAILED);
        }
        if (b.ch != 1 && b.ch != 3) {
            return failed(id, 2);
        }
        int psm = req.field(Plan.ocPsm()), flags = req.field(Plan.ocFlags());
        boolean wantSyms = (flags & 1) != 0;
        int rx = req.field(Plan.ocX()), ry = req.field(Plan.ocY()), rw = req.field(Plan.ocW()), rh = req.field(Plan.ocH());
        List<String> installed = installedIn(tessdata);
        // which installed models read the request is kin's (`op-models`: languages, scripts, the ones not installed dropped, none left is every model);
        // the models go in as code points separated by 0
        Port.Cells reqc = cellsOf(req.rec);
        Port.Cells inst = Port.newCells();
        for (int i = 0; i < installed.size(); i++) {
            if (i > 0) {
                Port.cellsPush(inst, 0);
            }
            installed.get(i).codePoints().forEach(cp -> Port.cellsPush(inst, cp));
        }
        Port.Cells picked = Port.newCells();
        Ocrport.opModels(reqc, inst, picked);
        Port.Cells gone = Port.newCells();
        Ocrport.opDropped(reqc, inst, gone);
        StringBuilder dropped = new StringBuilder();
        for (int i = 0; i < Port.cellsLen(gone); i++) {
            int cp = Port.cellsAt(gone, i);
            if (cp == 0) {
                eng.notes.add("no model for " + dropped + ": dropped");
                dropped.setLength(0);
            } else {
                dropped.appendCodePoint(cp);
            }
        }
        List<String> langs = new ArrayList<>();
        for (int i = 0; i < Port.cellsLen(picked); i++) {
            langs.add(installed.get(Port.cellsAt(picked, i)));
        }
        String lang = String.join("+", langs);
        if (lang.isEmpty()) {
            return failed(id, 2);
        }
        MemorySegment h = api(eng, lang);
        if (h == null) {
            return failed(id, Planrun.STATUS_FAILED);
        }
        int w = b.w, hh = b.h, ch = b.ch;
        byte[] px = b.d;
        if (b.stride != w * ch) {
            px = new byte[w * hh * ch];
            for (int y = 0; y < hh; y++) {
                System.arraycopy(b.d, y * b.stride, px, y * w * ch, w * ch);
            }
        }
        int dpiV = emon.Ocr.ocfDpi(flags);
        if (dpiV == 0) {
            dpiV = dpi;
        }
        final int dpiF = dpiV;
        List<Word> words = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        int binW = 0, binH = 0;
        byte[] binPx = null;
        boolean hasBinary = false;
        int osd = -1, script = 0, sconf = -1, oconf = -1;
        // Tesseract keeps the pointer until Clear: native memory that lives for this request
        try (Arena ar = Arena.ofConfined()) {
            MemorySegment cpx = ar.allocate(Math.max(px.length, 1), 16);
            MemorySegment.copy(px, 0, cpx, ValueLayout.JAVA_BYTE, 0, px.length);
            java.util.function.Consumer<MemorySegment> setimg = (hp) -> {
                try {
                    setImage.invoke(hp, cpx, w, hh, ch, w * ch);
                    if (dpiF > 0) {
                        setSourceResolution.invoke(hp, dpiF);
                    }
                    if (rw > 0) {
                        setRectangle.invoke(hp, rx, ry, rw, rh);
                    }
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            };
            if ((flags & 2) != 0) {
                // OSD has its own model (`osd`) and its own engine instance
                MemorySegment ho = api(eng, "osd");
                if (ho == null) {
                    ho = h;
                }
                setPageSegMode.invoke(ho, 0);
                setimg.accept(ho);
                MemorySegment deg = ar.allocate(4), oc = ar.allocate(4), sc = ar.allocate(4), sn = ar.allocate(8);
                if ((int) detectOsd.invoke(ho, deg, oc, sn, sc) != 0) {
                    osd = deg.get(INT, 0);
                    oconf = x10(oc.get(FLT, 0));
                    sconf = x10(sc.get(FLT, 0));
                    script = Ocrport.opOsdScript(cellsOf(cps(cstr(sn.get(PTR, 0)))));
                } else {
                    eng.notes.add("osd failed");
                }
                clear.invoke(ho);
            }
            setPageSegMode.invoke(h, psm);
            setimg.accept(h);
            try {
                if ((int) recognize.invoke(h, MemorySegment.NULL) != 0) {
                    return failed(id, Planrun.STATUS_FAILED);
                }
                MemorySegment it = (MemorySegment) getIterator.invoke(h);
                if (!it.equals(MemorySegment.NULL)) {
                    iterate(it, ar, wantSyms, words, lines, blocks);
                    itDelete.invoke(it);
                }
                // the image the engine thresholded to read this page; its pixels only when a transcript will keep them
                if (keepBinary) {
                    Object[] r = binaryOf(h, ar);
                    if (r != null) {
                        hasBinary = true;
                        binW = (Integer) r[0];
                        binH = (Integer) r[1];
                        binPx = (byte[]) r[2];
                    }
                } else {
                    MemorySegment pix = (MemorySegment) getThresholded.invoke(h);
                    if (!pix.equals(MemorySegment.NULL)) {
                        hasBinary = true;
                        binW = (int) pixW.invoke(pix);
                        binH = (int) pixH.invoke(pix);
                        destroyPix(pix, ar);
                    }
                }
            } finally {
                clear.invoke(h);
            }
        }

        // Words, then the extension, through the kin builders.
        int orient = 0, wdir = 0, tlo = 0, deskew = 0;
        if (!blocks.isEmpty()) {
            Block b0 = blocks.get(0);
            orient = b0.orient;
            wdir = b0.wdir;
            tlo = b0.tlo;
            deskew = b0.desk;
        }
        Port.Cells c = Port.newCells();
        int at = Plan.anOcr(c, id);
        for (Word w2 : words) {
            int wd = Plan.anOcrWord(c, at, w2.bx[0], w2.bx[1], w2.bx[2], w2.bx[3], w2.conf, w2.line, w2.block);
            for (int cp : w2.text) {
                Plan.anOcrCp(c, wd, cp);
            }
        }
        emon.Ocr.oxBegin(c, orient, wdir, tlo, deskew, osd, script, sconf, oconf, lines.size(), blocks.size(), hasBinary ? 1 : 0, binW, binH);
        for (Line l : lines) {
            emon.Ocr.oxLine(c, l.bx[0], l.bx[1], l.bx[2], l.bx[3], l.base[0], l.base[1], l.base[2], l.base[3], l.block, l.par, l.btype, -1, -1, -1);
        }
        for (Block bl : blocks) {
            emon.Ocr.oxBlock(c, bl.bx[0], bl.bx[1], bl.bx[2], bl.bx[3], bl.orient, bl.wdir, bl.tlo);
        }
        for (Word w2 : words) {
            int wx = emon.Ocr.oxWord(c, w2.flags, w2.lang, w2.psize, w2.dflags);
            for (Sym s : w2.syms) {
                int sx = emon.Ocr.oxSym(c, wx, s.bx[0], s.bx[1], s.bx[2], s.bx[3], s.conf);
                for (int cp : s.cps) {
                    emon.Ocr.oxSymCp(c, sx, cp);
                }
                for (Alt a : s.alts) {
                    int ax = emon.Ocr.oxAlt(c, sx, a.conf);
                    for (int cp : a.cps) {
                        emon.Ocr.oxAltCp(c, ax, cp);
                    }
                }
            }
        }
        Plan.anOcrEnd(c, at);
        int[] rec = Port.cellsVec(c);
        Port.Bitmap bmp = null;
        if (keepBinary && hasBinary && binPx != null && binPx.length > 0) {
            bmp = new Port.Bitmap(binW, binH, 1, binPx);
        }
        return new Planrun.Answer(rec, bmp);
    }

    private int[] box(MemorySegment pi, int level, Arena ar) throws Throwable {
        MemorySegment l = ar.allocate(4), t = ar.allocate(4), r = ar.allocate(4), bt = ar.allocate(4);
        piBox.invoke(pi, level, l, t, r, bt);
        int L = l.get(INT, 0), T = t.get(INT, 0), R = r.get(INT, 0), B = bt.get(INT, 0);
        return new int[] {L, T, R - L, B - T};
    }

    private void iterate(MemorySegment it, Arena ar, boolean wantSyms, List<Word> words, List<Line> lines, List<Block> blocks) throws Throwable {
        int block = -1, par = -1, line = -1;
        for (;;) {
            MemorySegment pi = (MemorySegment) itPageIterator.invoke(it);
            if ((int) piAtBeginning.invoke(pi, RIL_BLOCK) != 0) {
                block++;
                Block bl = new Block();
                bl.bx = box(pi, RIL_BLOCK, ar);
                MemorySegment o = ar.allocate(4), wd = ar.allocate(4), tl = ar.allocate(4), dk = ar.allocate(4);
                piOrientation.invoke(pi, o, wd, tl, dk);
                bl.orient = o.get(INT, 0);
                bl.wdir = wd.get(INT, 0);
                bl.tlo = tl.get(INT, 0);
                bl.desk = (int) Math.round((double) dk.get(FLT, 0) * 180 / Math.PI * 100);
                blocks.add(bl);
            }
            if ((int) piAtBeginning.invoke(pi, RIL_PARA) != 0) {
                par++;
            }
            if ((int) piAtBeginning.invoke(pi, RIL_TEXTLINE) != 0) {
                line++;
                Line ln = new Line();
                ln.bx = box(pi, RIL_TEXTLINE, ar);
                MemorySegment x1 = ar.allocate(4), y1 = ar.allocate(4), x2 = ar.allocate(4), y2 = ar.allocate(4);
                piBaseline.invoke(pi, RIL_TEXTLINE, x1, y1, x2, y2);
                ln.base = new int[] {x1.get(INT, 0), y1.get(INT, 0), x2.get(INT, 0), y2.get(INT, 0)};
                ln.btype = Ocrport.opBlockType((int) piBlockType.invoke(pi));
                ln.block = block;
                ln.par = par;
                lines.add(ln);
            }
            String text = goTrim(takeText((MemorySegment) itText.invoke(it, RIL_WORD)));
            if (!text.isEmpty()) {
                Word wd = new Word();
                wd.bx = box(pi, RIL_WORD, ar);
                MemorySegment bo = ar.allocate(4), itl = ar.allocate(4), un = ar.allocate(4), mo = ar.allocate(4), se = ar.allocate(4),
                    sc = ar.allocate(4), sz = ar.allocate(4), fid = ar.allocate(4);
                itFont.invoke(it, bo, itl, un, mo, se, sc, sz, fid);
                wd.flags = (bo.get(INT, 0) & 1) | ((itl.get(INT, 0) & 1) << 1) | ((un.get(INT, 0) & 1) << 2) | ((mo.get(INT, 0) & 1) << 6) | ((sc.get(INT, 0) & 1) << 7);
                int dfl = 0;
                if ((int) itIsDict.invoke(it) != 0) {
                    dfl |= emon.Ocr.ocwDict();
                }
                if ((int) itIsNumeric.invoke(it) != 0) {
                    dfl |= emon.Ocr.ocwNumeric();
                }
                int sizeV = sz.get(INT, 0);
                wd.psize = sizeV > 0 ? sizeV : -1;
                wd.conf = x10((float) itConfidence.invoke(it, RIL_WORD));
                wd.line = line;
                wd.block = block;
                wd.text = cps(text);
                wd.lang = Ocrport.opWordLang(cellsOf(cps(cstr((MemorySegment) itWordLang.invoke(it)))));
                wd.dflags = dfl;
                if (wantSyms) {
                    symbols(it, ar, wd);
                }
                words.add(wd);
            }
            if ((int) itNext.invoke(it, RIL_WORD) == 0) {
                break;
            }
        }
    }

    private void symbols(MemorySegment it, Arena ar, Word wd) throws Throwable {
        MemorySegment it2 = (MemorySegment) itCopy.invoke(it);
        for (;;) {
            MemorySegment p2 = (MemorySegment) itPageIterator.invoke(it2);
            Sym sy = new Sym();
            sy.bx = box(p2, RIL_SYMBOL, ar);
            String stext = takeText((MemorySegment) itText.invoke(it2, RIL_SYMBOL));
            sy.conf = x10((float) itConfidence.invoke(it2, RIL_SYMBOL));
            sy.cps = cps(stext);
            MemorySegment ci = (MemorySegment) itChoiceIterator.invoke(it2);
            if (!ci.equals(MemorySegment.NULL)) {
                for (;;) {
                    String ct = cstr((MemorySegment) chText.invoke(ci));
                    if (!ct.equals(stext)) {
                        Alt a = new Alt();
                        a.cps = cps(ct);
                        a.conf = x10((float) chConf.invoke(ci));
                        sy.alts.add(a);
                    }
                    if ((int) chNext.invoke(ci) == 0) {
                        break;
                    }
                }
                chDelete.invoke(ci);
            }
            wd.syms.add(sy);
            if ((int) piAtFinal.invoke(p2, RIL_WORD, RIL_SYMBOL) != 0 || (int) itNext.invoke(it2, RIL_SYMBOL) == 0) {
                break;
            }
        }
        itDelete.invoke(it2);
    }

    // Go's strings.TrimSpace: Unicode white space
    private static String goTrim(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.codePointAt(a))) {
            a += Character.charCount(s.codePointAt(a));
        }
        while (b > a && isSpace(s.codePointBefore(b))) {
            b -= Character.charCount(s.codePointBefore(b));
        }
        return s.substring(a, b);
    }

    private static boolean isSpace(int c) {
        return c == ' ' || (c >= 9 && c <= 13) || c == 0x85 || c == 0xA0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a)
            || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
    }

    /** A reading on an engine of its own from the pool (see {@link Engine}); readings that run at once run on different engines. */
    public Planrun.Answer fulfil(Planrun.Request req, Map<Integer, Port.Bitmap> bitmaps, boolean keepBinary) {
        Engine eng;
        synchronized (idle) {
            eng = idle.poll();
        }
        if (eng == null) {
            eng = new Engine();
        }
        try {
            Planrun.Answer a = fulfilNative(eng, req, bitmaps, keepBinary);
            synchronized (idle) {
                idle.add(eng);
            }
            return a;
        } catch (Throwable t) {
            // an engine that fails on a page is a failed OCR of it, not a crashed process; one that failed is not given back (its state is not known)
            return failed(req.id(), Planrun.STATUS_FAILED);
        }
    }

    public List<String[]> producer() {
        return producer;
    }
}
