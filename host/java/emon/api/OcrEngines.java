// THE ENGINE REGISTRY, Java host (DEVLOG D925, K3; the twin of `host/go/engines.go`, `host/csharp/Emon/Api/OcrEngines.cs` and `host/rust/src/adapter.rs`: read those for the reasoning).
//
// An OCR engine is an ADAPTER that is registered, never wired: a library with the one export `emon_ocr_adapter_v1` (`OcrEngines.load`, `emon.ffi.AdapterLib`) or an in-process value
// (`OcrEngines.add`). Kin decides every rule and this file carries them out: what an adapter may declare (`adRegisterCheck`, against the engine table), which adapter a request goes
// to (`adRoute`: the first registered for the OIL engine it names), what an unread page is (`adRefusal`) and what an answer is held to (`adAnswerCheck`, `oilRecordCheckBitmap`).
// `OcrEngines` is itself the `Ocr` port: `config.ocr = engines`. Sync adapters only; a remote one is refused unless `allowRemote` was set first.
// (Named `OcrEngines` because kin's generated class `emon.Engines` has the plain name.)

package emon.api;

import emon.Planrun;
import emon.Port;
import emon.ffi.Unavailable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class OcrEngines implements Ocr, AutoCloseable {
    public static final int ASYNC = 1, REMOTE = 2, THREADSAFE = 4;
    public static final int OK = 0, UNSUPPORTED = 1, FAILED = 2;

    /** What an adapter says it is: the OIL engine it answers as (1 tesseract, 2 ppocr, 3 apple-vision, 4 mlkit-text, 5 windows-media-ocr), its capabilities, the pack kinds it reads with, its flags and its producer pairs. */
    public static final class AdapterInfo {
        public String name = "adapter";
        public int engine, caps, measured, needs, flags;
        public String config = "", observed = "";
        public List<String[]> producer = new ArrayList<>();
    }

    /** What an adapter answered: the OIL answer record, or a status UNSUPPORTED / FAILED with a reason word (the page is then unread, never blank). */
    public static final class Reading {
        public int[] rec;
        public Port.Bitmap binary;
        public int status;
        public String reason = "";
    }

    /** One OCR engine. read answers one `ocr` request (bitmaps holds its bitmap); keepBinary asks it to keep its thresholded image. */
    public interface Adapter extends AutoCloseable {
        AdapterInfo info();

        Reading read(Planrun.Request req, Map<Integer, Port.Bitmap> bitmaps, boolean keepBinary);

        @Override
        void close();
    }

    private static final class Entry {
        final Adapter a;
        final AdapterInfo info;

        Entry(Adapter a, AdapterInfo info) {
            this.a = a;
            this.info = info;
        }
    }

    private final Object mu = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean allowRemote;
    private String adapterDir = "", tessdata = "";

    /** Lets adapters that send the pixels off the device register (default no: documents may hold personal data). Call it before registering them. */
    public void allowRemote(boolean allow) {
        synchronized (mu) {
            allowRemote = allow;
        }
    }

    /** Names the directory defaults() loads adapter libraries from ($EMON_ADAPTER_DIR, else lib/emon/adapters beside the application). */
    public void setAdapterDir(String dir) {
        synchronized (mu) {
            adapterDir = dir == null ? "" : dir;
        }
    }

    /** Names the models directory defaults() gives the Tesseract adapter ($EMON_TESSDATA, $TESSDATA_PREFIX). */
    public void setTessdata(String dir) {
        synchronized (mu) {
            tessdata = dir == null ? "" : dir;
        }
    }

    /** Registers an adapter, held to kin's engine table (`ad-register-check`); it is closed when refused, and the exception's message is the refusal's name and sentence. */
    public void add(Adapter a) {
        AdapterInfo info = a.info();
        int check = emon.Adapters.adRegisterCheck(emon.Adapters.adAbi(), info.engine, info.caps, info.measured, info.needs, info.flags);
        if (check != emon.Adapters.adrOk()) {
            StringBuilder why = new StringBuilder();
            emon.Adapters.adrEmitName(why, check);
            a.close();
            throw new Unavailable(why + ": " + info.name + " (engine " + info.engine + ", caps " + info.caps + ", needs " + info.needs + ", flags " + info.flags + ")");
        }
        if ((info.flags & ASYNC) != 0) {
            a.close();
            throw new Unavailable("adapter-abi-unsupported: " + info.name + " is async; this host registers sync adapters only");
        }
        synchronized (mu) {
            if ((info.flags & REMOTE) != 0 && !allowRemote) {
                a.close();
                throw new Unavailable("adapter-remote-refused: " + info.name + " sends the pixels off the device; call allowRemote(true) first");
            }
            if (info.producer.stream().noneMatch(kv -> kv[0].equals("engine"))) {
                info.producer.add(0, new String[] {"engine", info.name});
            }
            entries.add(new Entry(a, info));
        }
    }

    /** Registers the adapter library at path (`emon_ocr_adapter_v1`); config is the adapter's own key=value lines, packsDir the pack directory (or null). */
    public void load(String path, String config, String packsDir) {
        add(emon.ffi.AdapterLib.open(path, config, packsDir));
    }

    /** The turnkey: every libemon_ocr_* of the adapter directory, in name order. The Tesseract adapter is NOT loaded when the selection does not use Tesseract as its reader or fallback (`pk-uses-tesseract`, D920). */
    public void defaults(Options opts) {
        boolean uses = emon.Engines.pkUsesTesseract(emon.Engines.pkOptionEngine(opts.ocrEngine != 0 ? opts.ocrEngine : (opts.ppOcr ? 1 : 0))) == 1;
        for (Path lib : adapterLibs()) {
            String file = lib.getFileName().toString();
            String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
            String cfg = null;
            if (stem.equals("libemon_ocr_tesseract") || stem.equals("emon_ocr_tesseract")) {
                if (!uses) {
                    continue;
                }
                String dir = findTessdata();
                if (!dir.isEmpty()) {
                    cfg = "tessdata=" + dir;
                }
            }
            load(lib.toString(), cfg, null);
        }
    }

    private static boolean isLibrary(String n) {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") ? n.endsWith(".dylib") : os.contains("win") ? n.endsWith(".dll") : n.endsWith(".so");
    }

    private static List<Path> list(Path d) {
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                return Files.isRegularFile(p) && (n.startsWith("libemon_ocr_") || n.startsWith("emon_ocr_")) && isLibrary(n);
            }).sorted().toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private List<Path> adapterLibs() {
        String dir;
        synchronized (mu) {
            dir = adapterDir;
        }
        if (dir.isEmpty()) {
            dir = System.getenv().getOrDefault("EMON_ADAPTER_DIR", "");
        }
        if (!dir.isEmpty()) {
            if (!Files.isDirectory(Path.of(dir))) {
                throw new Unavailable("the adapter directory " + dir + " is not a directory");
            }
            return list(Path.of(dir));
        }
        Path b = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path up = b.getParent() == null ? b : b.getParent();
        for (Path c : new Path[] {b.resolve("emon").resolve("adapters"), b.resolve("lib").resolve("emon").resolve("adapters"), up.resolve("lib").resolve("emon").resolve("adapters")}) {
            List<Path> v = list(c);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return List.of();
    }

    private String findTessdata() {
        synchronized (mu) {
            if (!tessdata.isEmpty()) {
                return tessdata;
            }
        }
        for (String v : new String[] {"EMON_TESSDATA", "TESSDATA_PREFIX"}) {
            String d = System.getenv(v);
            if (d != null && !d.isEmpty()) {
                return d;
            }
        }
        File beside = new File(System.getProperty("user.dir"), "share/emon/tessdata");
        return beside.isDirectory() ? beside.getPath() : "";
    }

    /** The registered adapters in registration order. */
    public List<AdapterInfo> describe() {
        synchronized (mu) {
            return entries.stream().map(e -> e.info).toList();
        }
    }

    /** What adapters reported since the last call (why a request was left unread), one line each. */
    public List<String> notes() {
        synchronized (mu) {
            List<String> n = new ArrayList<>(notes);
            notes.clear();
            return n;
        }
    }

    /** Closes every adapter; the registry is empty after it. */
    @Override
    public void close() {
        List<Entry> es;
        synchronized (mu) {
            es = new ArrayList<>(entries);
            entries.clear();
        }
        for (Entry e : es) {
            e.a.close();
        }
    }

    /** The first adapter's pairs, the combined engine name when there are several (`tesseract+ppocr`), then the other adapters' further keys. */
    @Override
    public List<String[]> producer() {
        synchronized (mu) {
            String combined = null;
            if (entries.size() > 1) {
                List<String> names = new ArrayList<>();
                for (Entry e : entries) {
                    StringBuilder n = new StringBuilder();
                    emon.Engines.engEmitName(n, emon.Adapters.adOenEng(e.info.engine));
                    names.add(n.toString());
                }
                combined = String.join("+", names);
            }
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                for (String[] kv : entries.get(i).info.producer) {
                    if (combined != null && kv[0].equals("ocr-engine")) {
                        continue;
                    }
                    if (out.stream().noneMatch(o -> o[0].equals(kv[0]))) {
                        out.add(kv);
                    }
                }
                if (i == 0 && combined != null) {
                    out.add(new String[] {"ocr-engine", combined});
                }
            }
            return out;
        }
    }

    private static Port.Cells cellsFrom(int[] v) {
        Port.Cells c = Port.newCells();
        for (int x : v) {
            Port.cellsPush(c, x);
        }
        return c;
    }

    private static Planrun.Answer refuse(Planrun.Request req, int status) {
        Port.Cells o = Port.newCells();
        emon.Adapters.adRefusal(o, req.id(), status);
        return new Planrun.Answer(Port.cellsVec(o));
    }

    private static Planrun.Answer bad(Entry en, String why) {
        throw Planrun.fulfilFailed("ocr-adapter-invalid: " + en.info.name + ": " + why);
    }

    /** Routes the request by the engine it names, reads, and holds the answer to what the adapter registered. */
    @Override
    public Planrun.Answer fulfil(Planrun.Request req, Map<Integer, Port.Bitmap> bitmaps, boolean keepBinary) {
        int reqOen = emon.Adapters.adRequestOen(emon.Ocrport.opRequestEngine(cellsFrom(req.rec)));
        Entry en = null;
        synchronized (mu) {
            Port.Cells reg = Port.newCells();
            for (Entry e : entries) {
                Port.cellsPush(reg, e.info.engine);
            }
            int at = emon.Adapters.adRoute(reqOen, reg, 0);
            if (at >= 0 && at < entries.size()) {
                en = entries.get(at);
            }
        }
        if (en == null) {
            return refuse(req, emon.Adapters.adsUnsupported());
        }
        Reading r;
        if ((en.info.flags & THREADSAFE) == 0) {
            synchronized (en) {
                r = en.a.read(req, bitmaps, keepBinary);
            }
        } else {
            r = en.a.read(req, bitmaps, keepBinary);
        }
        if (r.status != OK) {
            synchronized (mu) {
                notes.add(en.info.name + ": " + (r.status == UNSUPPORTED ? "unsupported" : "failed") + " " + r.reason);
            }
            return refuse(req, r.status);
        }
        int[] rec = r.rec == null ? new int[0] : r.rec;
        if (rec.length < 4 || rec[0] != rec.length || rec[1] != emon.Plan.pkOcr() || rec[2] != req.id()) {
            return bad(en, "the answer is not a record of the request's `ocr` answer");
        }
        if (rec[3] != 0) {
            if (rec.length == 4) {
                return refuse(req, rec[3] == emon.Plan.psUnsupported() ? emon.Adapters.adsUnsupported() : emon.Adapters.adsFailed());
            }
            return bad(en, "a status that is not ok on a record with a reading");
        }
        Port.Bitmap b = bitmaps.get(req.field(emon.Plan.ocBitmap()));
        if (b == null) {
            return bad(en, "the request names no bitmap");
        }
        Port.Cells c = cellsFrom(rec);
        if (emon.Oil.oilRecordCheckBitmap(c, 0, b.w, b.h, Port.newCells()) != 0) {
            return bad(en, "the reading is not valid OIL for this bitmap");
        }
        int check = emon.Adapters.adAnswerCheck(en.info.engine, en.info.caps, reqOen, emon.Oil.oilGet(c, 0, emon.Oil.ohEngine()), emon.Oil.oilGet(c, 0, emon.Oil.ohCaps()));
        if (check != emon.Adapters.adaOk()) {
            StringBuilder why = new StringBuilder();
            emon.Adapters.adaEmitName(why, check);
            return bad(en, why.toString());
        }
        return new Planrun.Answer(rec.clone(), keepBinary ? r.binary : null);
    }
}
