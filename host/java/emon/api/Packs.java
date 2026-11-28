// THE PACKS, Java host (DEVLOG D928; the twin of `host/rust/src/packs.rs` and `host/go`'s: read the Rust file for the reasoning): the model files an adapter reads with, found, verified, handed over.
//
// WHAT IS KIN'S. Which packs exist, what each is for, their SHA-256 and sizes (`emon.Engines`' catalogue), which packs a conversion needs (`pkResolve`) and what to do about one that is
// missing (`emon.Adapters.adPackAction`: `never` and `EMON_OFFLINE=1` win over every hook; a hook fetches, provides, declines or substitutes). This file handles directories, files, hashes and
// the app's callbacks.
//
// NO HTTP IN THE LIBRARY (D913). A `fetch` decision goes to the app's own `Fetcher` (the host app supplies the download); with none it is `pack-fetch-unavailable:<id>` and the page is unread, never
// blank. EVERY pack is verified against the catalogue's SHA-256 and size before use, wherever it came from; a file that does not match is never used (one in the store is deleted).
//
// LAYOUT. The STORE is `<store>/<id>/<file>` and, for each engine, `<store>/.views/<engine>/<file>`: a flat directory of links to the verified files, which is what an adapter is given.
// Other directories (`addDir`, `$EMON_MODELS`) are searched read-only, as `<dir>/<id>/<file>` or `<dir>/<file>`.

package emon.api;

import emon.Engines;
import emon.Port;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Packs {
    /** The app's decisions about a pack (`PackDecision.action`). */
    public static final int FETCH = 1, PROVIDE = 2, DECLINE = 3, SUBSTITUTE = 4;
    /** The kinds of a pack event. */
    public static final int EVENT_START = 1, EVENT_PROGRESS = 2, EVENT_DONE = 3, EVENT_FAILED = 4, EVENT_CANCELLED = 5;

    /** What the library tells the hook about the pack it needs. */
    public static final class PackRequest {
        public final String pack, engine, kind, licence, reason, requestEngine;
        public final int rev;
        public final long bytes;

        PackRequest(String pack, int rev, String engine, String kind, String licence, long bytes, String reason, String requestEngine) {
            this.pack = pack;
            this.rev = rev;
            this.engine = engine;
            this.kind = kind;
            this.licence = licence;
            this.bytes = bytes;
            this.reason = reason;
            this.requestEngine = requestEngine;
        }
    }

    /** What the hook answers: FETCH (the app's Fetcher), PROVIDE (data, or path: a file, or a directory holding the pack's files), DECLINE or SUBSTITUTE (engine: another implementation of the same OIL engine). */
    public static final class PackDecision {
        public int action;
        public byte[] data;
        public String path, engine;

        public static PackDecision fetch() {
            return of(FETCH);
        }

        public static PackDecision decline() {
            return of(DECLINE);
        }

        public static PackDecision provideBytes(byte[] data) {
            PackDecision d = of(PROVIDE);
            d.data = data;
            return d;
        }

        public static PackDecision providePath(String path) {
            PackDecision d = of(PROVIDE);
            d.path = path;
            return d;
        }

        public static PackDecision substitute(String engine) {
            PackDecision d = of(SUBSTITUTE);
            d.engine = engine;
            return d;
        }

        private static PackDecision of(int a) {
            PackDecision d = new PackDecision();
            d.action = a;
            return d;
        }
    }

    /** One step of a pack's download (`packs.events`): library-initiated (byApp false) or the app's own `ensure`. */
    public static final class PackEvent {
        public final int kind;
        public final String pack, engine, message;
        public final long done, total;
        public final boolean byApp;

        PackEvent(int kind, String pack, String engine, long done, long total, boolean byApp, String message) {
            this.kind = kind;
            this.pack = pack;
            this.engine = engine;
            this.done = done;
            this.total = total;
            this.byApp = byApp;
            this.message = message;
        }
    }

    /** The app's download: the bytes of one file of a pack. Report bytes received through progress (it answers true to stop); throw to fail. */
    public interface Fetcher {
        byte[] fetch(String pack, String file, long bytes, java.util.function.LongPredicate progress) throws Exception;
    }

    static final class PackFile {
        final String name;
        final long bytes;
        final byte[] sha;

        PackFile(String name, long bytes, byte[] sha) {
            this.name = name;
            this.bytes = bytes;
            this.sha = sha;
        }

        boolean matches(byte[] data) {
            return data.length == bytes && Arrays.equals(sha256(data), sha);
        }
    }

    static final class PackInfo {
        int code, rev, engine, kind;
        String id, engineName, kindName, licence;
        long bytes;
        List<PackFile> files = new ArrayList<>();
    }

    /** One row of `pk-resolve`'s answer. */
    static final class Row {
        int state, engine, pack, lang;
    }

    /** What becomes of a pack a request needs. */
    static final class Need {
        static final int READY = 0, UNREAD = 1, SUBSTITUTE = 2;
        final int kind;
        final String text;

        Need(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    // ---------------------------------------------------------------------------------------------- the catalogue, as kin holds it

    static int packCount() {
        return Engines.pkPackCount();
    }

    static String engName(int eng) {
        StringBuilder s = new StringBuilder();
        Engines.engEmitName(s, eng);
        return s.toString();
    }

    static int engFind(String name) {
        byte[] b = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Engines.engFind(b, 0, b.length);
    }

    static int packFind(String name) {
        for (int p = 1; p <= packCount(); p++) {
            if (packInfo(p).id.equals(name)) {
                return p;
            }
        }
        return -1;
    }

    static PackInfo packInfo(int p) {
        PackInfo info = new PackInfo();
        info.code = p;
        StringBuilder id = new StringBuilder();
        emon.Packdata.pkidEmitName(id, p);
        info.id = id.toString();
        info.rev = Engines.pkPackRev(p);
        info.engine = Engines.pkPackEngine(p);
        info.engineName = info.engine != 0 ? engName(info.engine) : "any";
        info.kind = Engines.pkPackKind(p);
        StringBuilder k = new StringBuilder();
        Engines.pkindEmitName(k, info.kind);
        info.kindName = k.toString();
        StringBuilder l = new StringBuilder();
        emon.Packdata.plicEmitName(l, Engines.pkPackLicence(p));
        info.licence = l.toString();
        info.bytes = Engines.pkPackBytes(p) & 0xffffffffL;
        for (int i = 0; i < Engines.pkPackFileCount(p); i++) {
            StringBuilder name = new StringBuilder();
            Engines.pkPackFileName(p, i, name);
            byte[] sha = new byte[32];
            for (int w = 0; w < 8; w++) {
                int v = Engines.pkPackFileSha(p, i, w);
                sha[4 * w] = (byte) (v >>> 24);
                sha[4 * w + 1] = (byte) (v >>> 16);
                sha[4 * w + 2] = (byte) (v >>> 8);
                sha[4 * w + 3] = (byte) v;
            }
            info.files.add(new PackFile(name.toString(), Engines.pkPackFileBytes(p, i) & 0xffffffffL, sha));
        }
        return info;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hashFile(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                h.update(buf, 0, n);
            }
            return h.digest();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static int platformClass() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") ? Engines.pclsMacos() : os.contains("win") ? Engines.pclsWindows() : Engines.pclsLinux();
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? null : v;
    }

    /** The store when nobody names one: `$EMON_PACK_CACHE`, else the user's cache directory. */
    public static Path defaultStore() {
        String d = env("EMON_PACK_CACHE");
        if (d != null) {
            return Path.of(d);
        }
        String home = env("HOME");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path p = null;
        if (os.contains("mac")) {
            p = home == null ? null : Path.of(home, "Library/Caches/emon/packs");
        } else if (os.contains("win")) {
            p = env("LOCALAPPDATA") == null ? null : Path.of(env("LOCALAPPDATA"), "emon", "packs");
        } else if (env("XDG_CACHE_HOME") != null) {
            p = Path.of(env("XDG_CACHE_HOME"), "emon/packs");
        } else if (home != null) {
            p = Path.of(home, ".cache/emon/packs");
        }
        if (p != null) {
            try {
                Files.createDirectories(p);
                return p;
            } catch (IOException e) {
                // not a store: the temporary directory is
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "emon-packs");
    }

    /** `EMON_OFFLINE=1`: nothing is fetched and no hook is asked, whatever the mode says. */
    public static boolean offline() {
        return "1".equals(env("EMON_OFFLINE"));
    }

    // ---------------------------------------------------------------------------------------------- state

    private final Object mu = new Object();
    private Path store = defaultStore();
    private final List<Path> extra = new ArrayList<>();
    private int mode = Engines.pfetchNever();
    private Function<PackRequest, PackDecision> hook;
    private Predicate<PackEvent> events;
    private volatile boolean cancel;
    private final Map<Path, long[]> good = new HashMap<>();
    private final List<String> notes = new ArrayList<>();
    private Fetcher fetcher;
    private final Object fetchLock = new Object();
    private final int cls = platformClass();

    Packs() {
    }

    int platform() {
        return cls;
    }

    /** The directory the library owns (`<store>/<id>/<file>`). */
    public Path store() {
        synchronized (mu) {
            return store;
        }
    }

    /** Names the store (made if need be). */
    public void setStore(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new emon.ffi.Unavailable("pack-dir-missing: the pack directory " + dir + " cannot be made: " + e.getMessage());
        }
        synchronized (mu) {
            store = dir;
        }
    }

    /** A directory that is searched, read-only, for packs (`<dir>/<id>/<file>` or `<dir>/<file>`). */
    public void addDir(Path dir) {
        synchronized (mu) {
            if (!extra.contains(dir)) {
                extra.add(dir);
            }
        }
    }

    /** How packs come: `never` (what is here), `cache` (what is cached is used) or `auto` (fetch on demand through the Fetcher or the hook). */
    public enum Mode {
        NEVER, CACHE, AUTO;

        int code() {
            return this == NEVER ? Engines.pfetchNever() : this == CACHE ? Engines.pfetchCache() : Engines.pfetchAuto();
        }
    }

    public void setMode(Mode m) {
        synchronized (mu) {
            mode = m.code();
        }
    }

    int mode() {
        synchronized (mu) {
            return mode;
        }
    }

    /** The app's hook for a pack a conversion needs and is not here (D896). Null removes it. */
    public void onPackNeeded(Function<PackRequest, PackDecision> f) {
        synchronized (mu) {
            hook = f;
        }
    }

    /** The one progress and cancel stream (`packs.events`); answering true cancels the running fetch. Null removes it. */
    public void onPackEvent(Predicate<PackEvent> f) {
        synchronized (mu) {
            events = f;
        }
    }

    /** The app's download (the library has no HTTP client, D913). */
    public void setFetcher(Fetcher f) {
        synchronized (mu) {
            fetcher = f;
        }
    }

    /** Asks the running fetch to stop (any thread): the pack is then missing, `pack-fetch-cancelled:<id>`. */
    public void cancel() {
        cancel = true;
    }

    private boolean hasHook() {
        synchronized (mu) {
            return hook != null;
        }
    }

    /** What happened to packs since the last call: mismatches, failed fetches. */
    public List<String> takeNotes() {
        synchronized (mu) {
            List<String> n = new ArrayList<>(notes);
            notes.clear();
            return n;
        }
    }

    private void note(String s) {
        synchronized (mu) {
            notes.add(s);
        }
    }

    // ---------------------------------------------------------------------------------------------- finding and verifying

    private List<Object[]> candidates(PackInfo info, PackFile f) {
        List<Object[]> v = new ArrayList<>();
        v.add(new Object[] {store().resolve(info.id).resolve(f.name), true});
        List<Path> dirs;
        synchronized (mu) {
            dirs = new ArrayList<>(extra);
        }
        String m = env("EMON_MODELS");
        if (m != null) {
            for (String d : m.split(java.io.File.pathSeparator)) {
                if (!d.isEmpty()) {
                    dirs.add(Path.of(d));
                }
            }
        }
        for (Path d : dirs) {
            v.add(new Object[] {d.resolve(info.id).resolve(f.name), false});
            v.add(new Object[] {d.resolve(f.name), false});
        }
        return v;
    }

    private boolean verify(Path path, PackFile f) {
        long len, mt;
        try {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            len = Files.size(path);
            mt = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return false;
        }
        if (len != f.bytes) {
            return false;
        }
        synchronized (mu) {
            long[] s = good.get(path);
            if (s != null && s[0] == len && s[1] == mt) {
                return true;
            }
        }
        try {
            if (Arrays.equals(hashFile(path), f.sha)) {
                synchronized (mu) {
                    good.put(path, new long[] {len, mt});
                }
                return true;
            }
        } catch (IOException e) {
            // unreadable: not verified
        }
        return false;
    }

    /** The verified files of pack `p` (one per file, in the catalogue's order), or null when any is absent or does not match. */
    List<Path> locate(int p) {
        PackInfo info = packInfo(p);
        List<Path> out = new ArrayList<>();
        for (PackFile f : info.files) {
            Path found = null;
            for (Object[] c : candidates(info, f)) {
                Path path = (Path) c[0];
                if (!Files.exists(path)) {
                    continue;
                }
                if (verify(path, f)) {
                    found = path;
                    break;
                }
                note("pack-hash-mismatch:" + info.id);
                if ((Boolean) c[1]) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        // left
                    }
                }
            }
            if (found == null) {
                return null;
            }
            out.add(found);
        }
        return out;
    }

    List<Integer> installed() {
        List<Integer> v = new ArrayList<>();
        for (int p = 1; p <= packCount(); p++) {
            if (locate(p) != null) {
                v.add(p);
            }
        }
        return v;
    }

    /** The flat directory an adapter of `engine` (kin's engine name) reads: links to the verified files of the packs it reads, rebuilt each time. */
    Path view(String engine) {
        Path dir = store().resolve(".views").resolve(engine);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new emon.ffi.Unavailable("pack-dir-missing: " + dir + ": " + e.getMessage());
        }
        int eng = Math.max(engFind(engine), 0);
        int lexicon = engine.equals("ppocr") ? packFind("tess-fast-eng") : -1;
        Map<String, Path> want = new HashMap<>();
        for (int p = 1; p <= packCount(); p++) {
            int pe = Engines.pkPackEngine(p);
            if (pe != 0 && pe != eng && lexicon != p) {
                continue;
            }
            List<Path> paths = locate(p);
            if (paths != null) {
                PackInfo info = packInfo(p);
                for (int i = 0; i < info.files.size(); i++) {
                    want.put(info.files.get(i).name, paths.get(i));
                }
            }
        }
        try (var rd = Files.list(dir)) {
            for (Path e : rd.toList()) {
                if (!want.containsKey(e.getFileName().toString())) {
                    Files.deleteIfExists(e);
                }
            }
        } catch (IOException e) {
            // left
        }
        for (Map.Entry<String, Path> w : want.entrySet()) {
            Path dst = dir.resolve(w.getKey()), src = w.getValue();
            try {
                boolean same = Files.exists(dst) && Files.size(dst) == Files.size(src) && Files.getLastModifiedTime(dst).equals(Files.getLastModifiedTime(src));
                if (!same) {
                    Files.deleteIfExists(dst);
                    try {
                        Files.createLink(dst, src);
                    } catch (IOException | UnsupportedOperationException e) {
                        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            } catch (IOException e) {
                throw new emon.ffi.Unavailable("pack-dir-missing: " + dst + ": " + e.getMessage());
            }
        }
        return dir;
    }

    // ---------------------------------------------------------------------------------------------- what a conversion needs

    List<Row> resolve(List<Integer> engines, List<Integer> langs, boolean osd) {
        return resolveWith(engines, langs, osd, installed());
    }

    List<Row> resolveWith(List<Integer> engines, List<Integer> langs, boolean osd, List<Integer> installed) {
        Port.Cells l = Port.newCells(), e = Port.newCells(), i = Port.newCells(), out = Port.newCells(), scratch = Port.newCells();
        langs.forEach(v -> Port.cellsPush(l, v));
        engines.forEach(v -> Port.cellsPush(e, v));
        installed.forEach(v -> Port.cellsPush(i, v));
        int r = Engines.pkResolve(cls, osd ? Engines.pkfOsd() : 0, l, e, i, out, scratch);
        if (r != 0) {
            StringBuilder msg = new StringBuilder();
            Engines.pkExplain(r, out, msg);
            throw new IllegalStateException(msg.toString());
        }
        return rows(out);
    }

    static List<Row> rows(Port.Cells out) {
        List<Row> v = new ArrayList<>();
        for (int k = 0; k < Engines.pkRows(out); k++) {
            Row r = new Row();
            r.state = Engines.pkRowState(out, k);
            r.engine = Engines.pkRowEngine(out, k);
            r.pack = Engines.pkRowPack(out, k);
            r.lang = Engines.pkRowLang(out, k);
            v.add(r);
        }
        return v;
    }

    // ---------------------------------------------------------------------------------------------- the hook, events

    private boolean emit(int kind, PackInfo info, long done, long total, boolean app, String message) {
        Predicate<PackEvent> ev;
        synchronized (mu) {
            ev = events;
        }
        boolean stop = false;
        if (ev != null) {
            stop = ev.test(new PackEvent(kind, info.id, info.engineName, done, total, app, message));
        }
        return stop || cancel;
    }

    private PackDecision ask(PackInfo info, String reason, String requestEngine) {
        Function<PackRequest, PackDecision> h;
        synchronized (mu) {
            h = hook;
        }
        if (h == null) {
            return new PackDecision();
        }
        PackDecision d;
        try {
            d = h.apply(new PackRequest(info.id, info.rev, info.engineName, info.kindName, info.licence, info.bytes, reason, requestEngine));
        } catch (RuntimeException e) {
            return new PackDecision();
        }
        return d == null ? new PackDecision() : d;
    }

    // ---------------------------------------------------------------------------------------------- a pack a request needs

    /** D896: what becomes of pack `p`, which a conversion needs. Kin decides what to do (`ad-pack-action`); this does it. */
    Need need(int p, String reason, String requestEngine) {
        PackInfo info = packInfo(p);
        int present = locate(p) != null ? 1 : 0;
        int md = mode(), off = offline() ? 1 : 0, hk = hasHook() ? 1 : 0;
        boolean asks = emon.Adapters.adAsksHook(present, md, off, hk) == 1;
        PackDecision decision = asks ? ask(info, reason, requestEngine) : new PackDecision();
        int action = emon.Adapters.adPackAction(present, md, off, hk, decision.action);
        if (action == emon.Adapters.pactUse()) {
            return new Need(Need.READY, "");
        } else if (action == emon.Adapters.pactFetch()) {
            try {
                fetch(info, false);
                return new Need(Need.READY, "");
            } catch (PackError e) {
                return new Need(Need.UNREAD, e.getMessage());
            }
        } else if (action == emon.Adapters.pactProvided()) {
            try {
                install(info, decision);
                return new Need(Need.READY, "");
            } catch (PackError e) {
                return new Need(Need.UNREAD, e.getMessage());
            }
        } else if (action == emon.Adapters.pactSubstitute()) {
            return new Need(Need.SUBSTITUTE, decision.engine == null ? "" : decision.engine);
        } else if (asks && decision.action == FETCH && !hasFetcher()) {
            String error = "pack-fetch-unavailable:" + info.id;
            note(error);
            return new Need(Need.UNREAD, error);
        }
        return new Need(Need.UNREAD, "pack-missing:" + info.id);
    }

    boolean hasFetcherPublic() {
        return hasFetcher();
    }

    private boolean hasFetcher() {
        synchronized (mu) {
            return fetcher != null;
        }
    }

    static final class PackError extends Exception {
        private static final long serialVersionUID = 1L;

        PackError(String m) {
            super(m, null, false, false);
        }
    }

    private void install(PackInfo info, PackDecision d) throws PackError {
        String id = info.id;
        List<byte[]> staged = new ArrayList<>();
        for (PackFile f : info.files) {
            byte[] bytes;
            if (d.data != null && d.data.length > 0) {
                if (info.files.size() != 1) {
                    throw new PackError("pack-provide-failed:" + id);
                }
                bytes = d.data.clone();
            } else if (d.path != null && !d.path.isEmpty()) {
                Path path = Path.of(d.path);
                Path file = Files.isDirectory(path) ? path.resolve(f.name) : path;
                try {
                    bytes = Files.readAllBytes(file);
                } catch (IOException e) {
                    throw new PackError("pack-provide-failed:" + id);
                }
            } else {
                throw new PackError("pack-provide-failed:" + id);
            }
            if (!f.matches(bytes)) {
                note("pack-hash-mismatch:" + id);
                throw new PackError("pack-hash-mismatch:" + id);
            }
            staged.add(bytes);
        }
        try {
            keep(info, staged);
        } catch (IOException | PackError e) {
            throw new PackError("pack-provide-failed:" + id);
        }
    }

    private void keep(PackInfo info, List<byte[]> staged) throws IOException, PackError {
        Path dir = store().resolve(info.id);
        Files.createDirectories(dir);
        for (int i = 0; i < staged.size(); i++) {
            Path dst = dir.resolve(info.files.get(i).name);
            Path tmp = dir.resolve(info.files.get(i).name + ".part" + ProcessHandle.current().pid());
            try {
                Files.write(tmp, staged.get(i));
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        if (locate(info.code) == null) {
            throw new PackError("installed files could not be verified");
        }
    }

    /** The packs of `ids` (kin's pack ids), fetched now (the app's `packs.ensure`): progress and cancel through the events. Only when the mode lets anything be fetched; the bytes come from the Fetcher. */
    public void ensure(String... ids) {
        if (offline()) {
            throw new emon.ffi.Unavailable("EMON_OFFLINE=1: nothing is fetched");
        }
        if (mode() != Engines.pfetchAuto()) {
            throw new emon.ffi.Unavailable("the pack mode does not fetch (it is `never` or `cache`)");
        }
        for (String id : ids) {
            int p = packFind(id);
            if (p < 0) {
                throw new IllegalArgumentException("unknown pack: " + id);
            }
            if (locate(p) != null) {
                continue;
            }
            try {
                fetch(packInfo(p), true);
            } catch (PackError e) {
                throw new emon.ffi.Unavailable(e.getMessage());
            }
        }
    }

    private void fetch(PackInfo info, boolean app) throws PackError {
        synchronized (fetchLock) {
            if (locate(info.code) != null) {
                return;
            }
            String id = info.id;
            Fetcher fx;
            synchronized (mu) {
                fx = fetcher;
            }
            if (fx == null) {
                String error = "pack-fetch-unavailable:" + id;
                note(error);
                throw new PackError(error);
            }
            cancel = false;
            emit(EVENT_START, info, 0, info.bytes, app, "");
            long base = 0;
            List<byte[]> staged = new ArrayList<>();
            for (PackFile f : info.files) {
                boolean[] cancelled = {cancel};
                long[] received = {0};
                final long b = base;
                byte[] bytes = null;
                Exception failure = null;
                try {
                    bytes = fx.fetch(id, f.name, f.bytes, done -> {
                        done = Math.min(done, f.bytes);
                        if (done > received[0]) {
                            received[0] = done;
                            cancelled[0] |= emit(EVENT_PROGRESS, info, b + done, info.bytes, app, "");
                        }
                        cancelled[0] |= cancel;
                        return cancelled[0];
                    });
                } catch (Exception e) {
                    failure = e;
                }
                if (cancelled[0] || cancel) {
                    emit(EVENT_CANCELLED, info, base, info.bytes, app, "");
                    String error = "pack-fetch-cancelled:" + id;
                    note(error);
                    throw new PackError(error);
                }
                if (failure != null || bytes == null) {
                    String error = "pack-fetch-failed:" + id + ": " + (failure == null ? "no bytes" : String.valueOf(failure.getMessage()));
                    note(error);
                    emit(EVENT_FAILED, info, base, info.bytes, app, error);
                    throw new PackError(error);
                }
                if (!f.matches(bytes)) {
                    String error = "pack-hash-mismatch:" + id;
                    note(error);
                    emit(EVENT_FAILED, info, base, info.bytes, app, error);
                    throw new PackError(error);
                }
                staged.add(bytes);
                base += f.bytes;
            }
            try {
                keep(info, staged);
            } catch (IOException | PackError e) {
                String error = "pack-fetch-failed:" + id + ": " + e.getMessage();
                note(error);
                emit(EVENT_FAILED, info, base, info.bytes, app, error);
                throw new PackError(error);
            }
            emit(EVENT_DONE, info, info.bytes, info.bytes, app, "");
        }
    }
}
