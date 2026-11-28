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
        /** The models are the app's own directory (an explicit tessdata): the pack machinery stays out of it. */
        boolean selfProvisioned;

        Entry(Adapter a, AdapterInfo info) {
            this.a = a;
            this.info = info;
        }
    }

    private final Object mu = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean allowRemote, rendererPresent;
    private String adapterDir = "", tessdata = "";
    private final Packs packs = new Packs();

    /** The packs: store, search directories, mode, the app's hook, events and download (D896, D928). A new registry fetches nothing (mode never). */
    public Packs packs() {
        return packs;
    }

    /** Says whether the conversion has a renderer (pdfium), which `state()` reports for the render engines. */
    public void rendererPresent(boolean present) {
        synchronized (mu) {
            rendererPresent = present;
        }
    }

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
        boolean own = packsDir != null && !packsDir.isEmpty() || config != null && config.lines().anyMatch(l -> l.startsWith("tessdata="));
        if (config != null) {
            config.lines().filter(l -> l.startsWith("tessdata=")).findFirst().ifPresent(l -> packs.addDir(Path.of(l.substring(9).trim())));
        }
        String engine = engineOfLibrary(path, config);
        String dir = packsDir;
        if ((dir == null || dir.isEmpty()) && !own && !engine.isEmpty()) {
            dir = packs.view(engine).toString();
        }
        Adapter a = emon.ffi.AdapterLib.open(path, config, dir);
        add(a);
        synchronized (mu) {
            for (Entry e : entries) {
                if (e.a == a) {
                    e.selfProvisioned = own;
                }
            }
        }
    }

    // Which engine's packs a library is given: `engine=<name>` in its config, else the engine of kin's table whose name the file's carries (`libemon_ocr_tesseract`, `libemon_ppocr_adapter`).
    private static String engineOfLibrary(String path, String config) {
        if (config != null) {
            for (String l : config.split("\\R")) {
                if (l.startsWith("engine=")) {
                    return l.substring(7).trim();
                }
            }
        }
        String file = Path.of(path).getFileName().toString().toLowerCase().replace('-', '_');
        String stem = file.contains(".") ? file.substring(0, file.lastIndexOf('.')) : file;
        for (int i = 0; i < emon.Engines.pkEngineCount(); i++) {
            int eng = emon.Engines.pkEngineAt(i);
            String n = Packs.engName(eng);
            if (emon.Engines.pkEngineKind(eng) == emon.Engines.ekindOcr() && stem.contains(n.replace('-', '_'))) {
                return n;
            }
        }
        return "";
    }

    /** The turnkey: every libemon_ocr_* of the adapter directory, in name order. The Tesseract adapter is NOT loaded when the selection does not use Tesseract as its reader or fallback (`pk-uses-tesseract`, D920). */
    public void defaults(Options opts) {
        boolean uses = emon.Engines.pkUsesTesseract(emon.Engines.pkOptionEngine(opts.ocrEngine != 0 ? opts.ocrEngine : (opts.ppOcr ? 1 : 0))) == 1;
        Path shared = packsBeside();
        if (shared != null) {
            packs.addDir(shared);
        }
        packs.setMode(turnkeyMode());
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

    // The pack mode a turnkey registry starts in: `$EMON_PACKS_FETCH` (never, cache, auto), else `auto` when the app supplied a download, else `cache`.
    private Packs.Mode turnkeyMode() {
        String v = System.getenv("EMON_PACKS_FETCH");
        if (v != null && !v.isEmpty()) {
            switch (v) {
                case "never": return Packs.Mode.NEVER;
                case "cache": return Packs.Mode.CACHE;
                case "auto": return Packs.Mode.AUTO;
                default: throw new IllegalArgumentException("EMON_PACKS_FETCH is never, cache or auto, not \"" + v + "\"");
            }
        }
        return packs.hasFetcherPublic() ? Packs.Mode.AUTO : Packs.Mode.CACHE;
    }

    private static Path packsBeside() {
        Path b = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path up = b.getParent() == null ? b : b.getParent();
        for (Path c : new Path[] {b.resolve("share/emon/packs"), up.resolve("share/emon/packs")}) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
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
            n.addAll(packs.takeNotes());
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
        for (int round = 0; round < 2; round++) {
            Entry[] other = new Entry[1];
            String why = ensurePacks(en, req, other);
            if (why != null) {
                synchronized (mu) {
                    notes.add(en.info.name + ": unsupported " + why);
                }
                return refuse(req, emon.Adapters.adsUnsupported());
            }
            if (other[0] == null) {
                break;
            }
            en = other[0];
        }
        if (osdMissing(en, req)) {
            // orientation detection without its model degrades to kin's own policy: the engine is not asked for it
            int[] rec = req.rec.clone();
            rec[3 + emon.Plan.ocFlags()] &= ~emon.Ocr.ocfOsd();
            req = new Planrun.Request(rec, req.payload);
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

    // ---------------------------------------------------------------------------------------------- packs a request needs (D896)

    private List<Integer> languagesOf(Entry en, Planrun.Request req) {
        // Tesseract's requests name languages; PP-OCR has one recognizer (English)
        List<Integer> v = new ArrayList<>();
        if (en.info.engine != 1) {
            return v;
        }
        Port.Cells c = cellsFrom(req.rec);
        int n = emon.Ocrport.opLangCount(c);
        for (int i = 0; i < n; i++) {
            int l = emon.Ocrport.opLangAt(c, i);
            // a script (ISO 15924) is not a language: no pack is named for it
            if (l > 0 && emon.Ocrport.opIsScript(l) == 0) {
                v.add(l);
            }
        }
        return v;
    }

    private static String packedText(int p) {
        StringBuilder b = new StringBuilder();
        for (int sh = 24; sh >= 0; sh -= 8) {
            int ch = (p >> sh) & 255;
            if (ch > 0) {
                b.append((char) ch);
            }
        }
        return b.toString();
    }

    // The packs a request needs, made present or refused. null: go on (with `other[0]` when the app named another adapter of the same engine); else the page is unread, with the reason.
    private String ensurePacks(Entry en, Planrun.Request req, Entry[] other) {
        if (en.info.needs == 0 || en.selfProvisioned) {
            return null;
        }
        int eng = emon.Adapters.adOenEng(en.info.engine);
        if (eng < 0) {
            return null;
        }
        boolean osd = (req.field(emon.Plan.ocFlags()) & emon.Ocr.ocfOsd()) != 0 && (en.info.needs & 2) != 0;
        List<Integer> langs = languagesOf(en, req);
        List<Packs.Row> rows;
        try {
            rows = packs.resolve(List.of(eng), langs, osd);
        } catch (IllegalStateException e) {
            return "engine-unavailable: " + e.getMessage();
        }
        List<Packs.Row> unreadable = rows.stream().filter(r -> r.state == emon.Engines.pstateUnsupported()).toList();
        if (!langs.isEmpty() && unreadable.size() >= langs.size()) {
            return "lang-unavailable:" + packedText(unreadable.get(0).lang);
        }
        String engineName = Packs.engName(eng);
        boolean arrived = false;
        for (Packs.Row r : rows) {
            if (r.state != emon.Engines.pstateMissing()) {
                continue;
            }
            int kind = emon.Engines.pkPackKind(r.pack);
            Packs.Need need = packs.need(r.pack, "conversion", engineName);
            if (need.kind == Packs.Need.READY) {
                arrived = true;
            } else if (need.kind == Packs.Need.UNREAD) {
                if (kind == emon.Engines.pkindOsd()) {
                    continue; // orientation detection degrades to kin's own policy without its model
                }
                return need.text;
            } else {
                int e2 = Packs.engFind(need.text);
                if (e2 < 0 || emon.Engines.pkEngineOen(e2) != en.info.engine) {
                    return "pack-substitute-differs:" + need.text;
                }
                synchronized (mu) {
                    for (Entry o : entries) {
                        if (o != en && o.info.engine == en.info.engine) {
                            other[0] = o;
                            return null;
                        }
                    }
                }
                return "pack-substitute-unavailable:" + need.text;
            }
        }
        // The bundled PP adapter reads English dictionary entries from the Tesseract pack even when used alone.
        if (eng == emon.Engines.engPpocr() && en.info.producer.stream().anyMatch(kv -> kv[0].equals("pp-lexicon-pack") && kv[1].equals("tess-fast-eng"))) {
            int pack = Packs.packFind("tess-fast-eng");
            if (pack < 0) {
                return "pack-missing:tess-fast-eng";
            }
            Packs.Need need = packs.need(pack, "conversion", engineName);
            if (need.kind == Packs.Need.READY) {
                arrived = true;
            } else if (need.kind == Packs.Need.UNREAD) {
                return need.text;
            } else {
                return "pack-substitute-differs:" + need.text;
            }
        }
        if (arrived || rows.stream().anyMatch(r -> r.state == emon.Engines.pstateInstalled())) {
            // the adapter lists its pack directory for each request, so what it is given must hold what arrived
            try {
                packs.view(engineName);
            } catch (emon.ffi.Unavailable e) {
                return "pack-dir-missing: " + e.getMessage();
            }
        }
        return null;
    }

    // Does the request want orientation detection that the engine's model for it cannot give (the pack is missing and was declined, or could not be fetched)?
    private boolean osdMissing(Entry en, Planrun.Request req) {
        if (en.selfProvisioned || (en.info.needs & 2) == 0 || (req.field(emon.Plan.ocFlags()) & emon.Ocr.ocfOsd()) == 0) {
            return false;
        }
        int eng = emon.Adapters.adOenEng(en.info.engine);
        if (eng < 0) {
            return false;
        }
        try {
            return packs.resolve(List.of(eng), List.of(), true).stream().anyMatch(r -> r.state == emon.Engines.pstateMissing() && emon.Engines.pkPackKind(r.pack) == emon.Engines.pkindOsd());
        } catch (IllegalStateException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------- engines() and prepare()

    private boolean registered(int eng) {
        if (emon.Engines.pkEngineKind(eng) == emon.Engines.ekindRender()) {
            synchronized (mu) {
                return rendererPresent;
            }
        }
        int oen = emon.Engines.pkEngineOen(eng);
        synchronized (mu) {
            return emon.Engines.pkEngineOn(eng, packs.platform()) == 1 && entries.stream().anyMatch(e -> e.info.engine == oen);
        }
    }

    private static String name(java.util.function.ToIntBiFunction<StringBuilder, Integer> f, int code) {
        StringBuilder b = new StringBuilder();
        f.applyAsInt(b, code);
        return b.toString();
    }

    static String js(String s) {
        StringBuilder o = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': o.append("\\\""); break;
                case '\\': o.append("\\\\"); break;
                case '\n': o.append("\\n"); break;
                case '\r': o.append("\\r"); break;
                case '\t': o.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        o.append(String.format("\\u%04x", (int) c));
                    } else {
                        o.append(c);
                    }
            }
        }
        return o.append('"').toString();
    }

    /** D896's `engines()`: every engine of kin's table that runs on this platform, with its state (built in, installed, downloadable or unavailable), as JSON (the shape of the C ABI's `emon_engines_describe`). */
    public String state() {
        int cls = packs.platform();
        List<Integer> installed = packs.installed();
        int mode = packs.mode();
        boolean off = Packs.offline();
        List<String> engines = new ArrayList<>();
        for (int i = 0; i < emon.Engines.pkEngineCount(); i++) {
            int eng = emon.Engines.pkEngineAt(i);
            if (emon.Engines.pkEngineOn(eng, cls) == 0) {
                continue;
            }
            String nm = Packs.engName(eng);
            boolean reg = registered(eng);
            List<Packs.Row> rows = new ArrayList<>();
            if (emon.Engines.pkEngineKind(eng) == emon.Engines.ekindOcr() && emon.Engines.pkEngineNeeds(eng) != 0) {
                try {
                    rows = packs.resolveWith(List.of(eng), List.of(), false, installed);
                } catch (IllegalStateException e) {
                    rows = new ArrayList<>();
                }
            }
            List<Packs.Row> missing = rows.stream().filter(r -> r.state == emon.Engines.pstateMissing()).toList();
            int st = emon.Adapters.adEngineState(reg ? 1 : 0, emon.Engines.pkEngineImpl(eng), emon.Engines.pkEngineNeeds(eng), missing.size(), mode, off ? 1 : 0);
            String stName = name(emon.Adapters::estEmitName, st);
            String reason = !reg ? "no adapter is registered for it" : st == emon.Adapters.estUnavailable() ? (off ? "EMON_OFFLINE=1" : "a pack is missing and the pack mode does not fetch") : "";
            List<String> needs = new ArrayList<>();
            for (Packs.Row r : rows) {
                if (r.pack > 0) {
                    Packs.PackInfo info = Packs.packInfo(r.pack);
                    needs.add("{\"id\":" + js(info.id) + ",\"kind\":" + js(info.kindName) + ",\"bytes\":" + info.bytes + ",\"licence\":" + js(info.licence) + ",\"installed\":" + (r.state == emon.Engines.pstateInstalled()) + "}");
                }
            }
            long dl = missing.stream().mapToLong(r -> Packs.packInfo(r.pack).bytes).sum();
            engines.add("{\"name\":" + js(nm) + ",\"kind\":" + js(name(emon.Engines::ekindEmitName, emon.Engines.pkEngineKind(eng))) + ",\"impl\":" + js(name(emon.Engines::eimplEmitName, emon.Engines.pkEngineImpl(eng)))
                + ",\"state\":" + js(stName) + ",\"registered\":" + reg + ",\"calibrated\":" + (emon.Engines.pkEngineCalibrated(eng) == 1) + ",\"caps\":" + emon.Engines.pkEngineCaps(eng)
                + ",\"oen\":" + emon.Engines.pkEngineOen(eng) + ",\"role\":" + js(name(emon.Engines::eroleEmitName, emon.Engines.pkRoleDefault(eng, cls))) + ",\"needs\":[" + String.join(",", needs)
                + "],\"download_bytes\":" + dl + ",\"reason\":" + js(reason) + "}");
        }
        return "{\"platform\":" + js(name(emon.Engines::pclsEmitName, cls)) + ",\"mode\":" + js(name(emon.Engines::pfetchEmitName, mode)) + ",\"offline\":" + off + ",\"store\":" + js(packs.store().toString()) + ",\"engines\":[" + String.join(",", engines) + "]}";
    }

    private static String langName(int l) {
        StringBuilder b = new StringBuilder();
        for (int sh = 24; sh >= 8; sh -= 8) {
            int c = (l >> sh) & 255;
            if (c > 0) {
                b.append((char) c);
            }
        }
        return b.toString();
    }

    /** D896's `prepare(options)`: the packs a conversion with these options (kin's `key=value` text) needs and which are missing, as kin resolves them and summarises them, as JSON (the shape of `emon_prepare`). */
    public String prepare(String options) {
        Options o = Options.parse(options).options;
        int mode = packs.mode();
        boolean off = Packs.offline();
        boolean never = o.ocr == Options.OcrPolicy.NEVER;
        List<Integer> engines = new ArrayList<>();
        engines.add(emon.Engines.pkOptionEngine(o.ocrEngine));
        if (o.ocrEngine == 1) {
            engines.add(emon.Engines.engPpocr());
        }
        boolean osd = !never && emon.Engines.pkUsesTesseract(emon.Engines.pkOptionEngine(o.ocrEngine)) == 1;
        List<Integer> langs = new ArrayList<>();
        if (o.locale != null && !o.locale.isEmpty()) {
            Port.Cells c = Port.newCells();
            o.locale.codePoints().forEach(ch -> Port.cellsPush(c, ch));
            int lg = emon.Scan.scLang(c);
            if (lg > 0) {
                langs.add(lg * 256);
            }
        }
        List<Packs.Row> rows = new ArrayList<>();
        int verdict = emon.Adapters.pverReady();
        if (!never) {
            Port.Cells l = Port.newCells(), e = Port.newCells(), i = Port.newCells(), out = Port.newCells(), scratch = Port.newCells();
            langs.forEach(v -> Port.cellsPush(l, v));
            engines.forEach(v -> Port.cellsPush(e, v));
            packs.installed().forEach(v -> Port.cellsPush(i, v));
            int r = emon.Engines.pkResolve(packs.platform(), osd ? emon.Engines.pkfOsd() : 0, l, e, i, out, scratch);
            if (r != 0) {
                StringBuilder msg = new StringBuilder();
                emon.Engines.pkExplain(r, out, msg);
                throw new IllegalArgumentException(msg.toString());
            }
            rows = Packs.rows(out);
            verdict = emon.Adapters.adVerdict(out, mode, off ? 1 : 0);
        }
        List<String> packsJson = new ArrayList<>(), unsupported = new ArrayList<>();
        int missing = 0, conditional = 0;
        long missingBytes = 0, conditionalBytes = 0;
        for (Packs.Row r : rows) {
            if (r.state == emon.Engines.pstateUnsupported()) {
                unsupported.add("{\"engine\":" + js(Packs.engName(r.engine)) + ",\"language\":" + js(langName(r.lang)) + "}");
                continue;
            }
            Packs.PackInfo info = Packs.packInfo(r.pack);
            if (r.state == emon.Engines.pstateMissing()) {
                missing++;
                missingBytes += info.bytes;
            }
            if (r.state == emon.Engines.pstateConditional()) {
                conditional++;
                conditionalBytes += info.bytes;
            }
            packsJson.add("{\"id\":" + js(info.id) + ",\"state\":" + js(name(emon.Engines::pstateEmitName, r.state)) + ",\"engine\":" + js(info.engineName) + ",\"kind\":" + js(info.kindName)
                + ",\"bytes\":" + info.bytes + ",\"licence\":" + js(info.licence) + ",\"rev\":" + info.rev + "}");
        }
        List<String> engineNames = new ArrayList<>(), langNames = new ArrayList<>();
        if (!never) {
            engines.forEach(e -> engineNames.add(js(Packs.engName(e))));
            if (langs.isEmpty()) {
                langNames.add(js("eng"));
            } else {
                langs.forEach(l -> langNames.add(js(langName(l))));
            }
        }
        return "{\"verdict\":" + js(name(emon.Adapters::pverEmitName, verdict)) + ",\"mode\":" + js(name(emon.Engines::pfetchEmitName, mode)) + ",\"offline\":" + off + ",\"engines\":[" + String.join(",", engineNames)
            + "],\"languages\":[" + String.join(",", langNames) + "],\"osd\":" + osd + ",\"packs\":[" + String.join(",", packsJson) + "],\"unsupported\":[" + String.join(",", unsupported) + "],\"missing\":" + missing
            + ",\"missing_bytes\":" + missingBytes + ",\"conditional\":" + conditional + ",\"conditional_bytes\":" + conditionalBytes + "}";
    }
}
