// The Java driver of kin's plan machine and the Java half of the transcript (P10.8, P10.9; D396, D399, D438; DEVLOG P2.5, SPEC 7.3, 13, D058 revised).
// Its twins are `host/go/planrun/{planrun,transcript}.go` and `Machine` and the end of `host/rust/src/plan.rs`: read those for the reasoning.
//
// THE LOOP AND THE TRANSCRIPT ARE KIN'S (`kin/planloop.kin`, `kin/transcript.kin`). What was Java's `PlanLoop.run` (and its EDN writer and reader,
// its kind table, `producerInOrder`, its replay map and its unused count) is here the unit call (the caller's, under the caller's own panic catch),
// the await, and the fulfilment of the ports. One attempt's accounting, the two named limits, the requests a unit wrote (checked, and sorted by id),
// the answered set, the order the answers are appended in, the transcript's entries, the schedule (`en-next`), the fetch planner and every name
// are kin's. What stays is what is not observable: the bitmaps (a PNG each, kin's `emon.pngread`, P10.11), the in-memory `Transcript` the embedder holds, the
// `Replayer` that answers the loop from kin's map and the fulfilment.
//
// It is named `Planrun`, not `PlanLoop`: the generated `emon.Planloop` is kin's and a case-insensitive file system would hold only one of them.
// Nothing here touches a filesystem (D058): a transcript is named files in memory (`File`, `fromFiles`) and the driver moves them to a directory. No
// `@kin:link` annotation is declared: this is a host driver for the ports, not a port kin calls.

package emon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Planrun {
    private Planrun() {
    }

    // The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).
    public static final int KIND_RENDER = 1;
    public static final int KIND_DECODE = 2;
    public static final int KIND_OCR = 3;
    public static final int KIND_BYTES = 4;
    public static final int KIND_CROP = 5;
    public static final int KIND_DECODE_IMG = 6;

    public static final int STATUS_OK = 0;
    public static final int STATUS_FAILED = 1;

    public static final int UNIT_DONE = 0;
    public static final int UNIT_REQUESTS = 1;
    public static final int UNIT_NEED_BYTES = 7;

    // SPEC 13.1's defaults. Both are NAMED limits: a breach is a PlanError whose name is `max-rounds` or `fetch-rounds`. (`Cfg` of `emon.config` holds the
    // same numbers; these are the defaults of a machine made with none.)
    public static final int MAX_ROUNDS = 5;
    public static final int FETCH_ROUNDS = 24;

    public static final String TRANSCRIPT_FILE = "transcript.edn";

    /** The named limits. */
    public static final class Config {
        public int maxRounds = MAX_ROUNDS;
        public int fetchRounds = FETCH_ROUNDS;

        public Config() {
        }

        public Config(int maxRounds, int fetchRounds) {
            this.maxRounds = maxRounds;
            this.fetchRounds = fetchRounds;
        }
    }

    /**
     * A named failure of the loop: the name is kin's (`Planloop.pe*`: `max-rounds` and `fetch-rounds` (limits), `transcript-miss`, `transcript-bad`,
     * `fulfil-failed`, `unit-failed`, `bad-record`, `need-bytes-stalled`), made by this class.
     */
    public static final class PlanError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String name;
        public final String msg;
        /** True for the two named limits, whose bound and the number reached are kin's (`err-limit`, `err-actual`), not words to parse. */
        public final boolean limited;
        public final int limit, actual;

        public PlanError(String name, String msg) {
            this(name, msg, false, 0, 0);
        }

        public PlanError(String name, String msg, boolean limited, int limit, int actual) {
            super(name + ": " + msg, null, false, false);
            this.name = name;
            this.msg = msg;
            this.limited = limited;
            this.limit = limit;
            this.actual = actual;
        }
    }

    private static String peName(int code) {
        StringBuilder sb = new StringBuilder();
        Planloop.peEmitName(sb, code);
        return sb.toString();
    }

    /** A `transcript-bad` error with this sentence. */
    public static PlanError bad(String msg) {
        return new PlanError(peName(Planloop.peTranscriptBad()), msg);
    }

    /** A `fulfil-failed` error with this sentence: a port could not run (distinct from an answer that says `failed`). */
    public static PlanError fulfilFailed(String msg) {
        return new PlanError(peName(Planloop.peFulfilFailed()), msg);
    }

    /** A request record and the unit's PAYLOAD (P6.1a, D291): the bytes kin made itself in the attempt that wrote it, shared by every request of the round. */
    public static final class Request {
        public final int[] rec;
        /** Never recorded in a transcript (the record names them by SHA-256); empty for a request that names none. */
        public final byte[] payload;

        public Request(int[] rec) {
            this(rec, new byte[0]);
        }

        public Request(int[] rec, byte[] payload) {
            this.rec = rec;
            this.payload = payload;
        }

        public int kind() {
            return rec[1];
        }

        public int id() {
            return rec[2];
        }

        /** The argument a kin field names (`Plan.dqWhere()`, `Plan.bqOffset()`, ...): a host reads a request by these and never by a literal offset (D396 item 5). */
        public int field(int f) {
            return rec[3 + f];
        }
    }

    /** An answer record and, for render/decode/ocr, its pixels. */
    public static final class Answer {
        public final int[] rec;
        public final Port.Bitmap bitmap;

        public Answer(int[] rec, Port.Bitmap bitmap) {
            this.rec = rec;
            this.bitmap = bitmap;
        }

        public Answer(int[] rec) {
            this(rec, null);
        }

        public int kind() {
            return rec[1];
        }

        public int id() {
            return rec[2];
        }

        public int status() {
            return rec[3];
        }
    }

    /** Fulfils requests, sync or async-shaped. Errors are thrown as {@link PlanError}. */
    public interface Fulfiller {
        /**
         * Begins a request. A sync host may finish it here. `bm` holds every bitmap answered so far (an OCR request names one by id); take what is
         * needed, it is not kept.
         */
        void start(Request req, Map<Integer, Port.Bitmap> bm);

        /** The next completed answer, in whatever order completions arrive. Called once per started request. */
        Answer next();
    }

    /** A function from a request to an answer. */
    public interface Fn {
        Answer apply(Request req, Map<Integer, Port.Bitmap> bm);
    }

    /** Adapts a function: it completes inside `start` (a failure is remembered and thrown by `next`). */
    public static final class Sync implements Fulfiller {
        private final Fn f;
        private final ArrayDeque<Object> done = new ArrayDeque<>();

        public Sync(Fn f) {
            this.f = f;
        }

        public void start(Request req, Map<Integer, Port.Bitmap> bm) {
            try {
                done.add(f.apply(req, bm));
            } catch (PlanError e) {
                done.add(e);
            }
        }

        public Answer next() {
            Object d = done.poll();
            if (d instanceof PlanError) {
                throw (PlanError) d;
            }
            return (Answer) d;
        }
    }

    /** A range of an input the source missed. */
    public static final class Range {
        public final int input, offset, length;

        public Range(int input, int offset, int length) {
            this.input = input;
            this.offset = offset;
            this.length = length;
        }
    }

    /** What a `Src` missed since the last call, for fetch round `round`. The ENGINE implements it over its backings (D058). */
    public interface Misses {
        List<Range> take(int round);
    }

    /** A source that never misses. */
    public static final Misses NO_MISSES = round -> new ArrayList<>();

    /**
     * The unit, closed over what it needs: handed the answers so far, an empty requests Cells, this attempt's BitmapSet and the PAYLOAD Buf, it answers a
     * unit status.
     */
    public interface Unit {
        int run(Port.Cells answers, Port.Cells requests, Port.BitmapSet bms, Port.Buf payload);
    }

    /** One recorded request/answer pair. */
    public static final class Entry {
        public final int round;
        public final int[] req, ans;
        public final Port.Bitmap bitmap;

        public Entry(int round, int[] req, int[] ans, Port.Bitmap bitmap) {
            this.round = round;
            this.req = req;
            this.ans = ans;
            this.bitmap = bitmap;
        }
    }

    /** What a run did. */
    public static final class Stats {
        public int attempts, rounds, requests, fetchRounds, bytesRequests;
    }

    /** What the finished units counted, and the page units' rounds and requests in page order. */
    public static final class Totals {
        public int attempts, fetchRounds, bytesRequests, requests;
        public final List<Integer> pageRounds = new ArrayList<>();
        public final List<Integer> pageRequests = new ArrayList<>();
    }

    /** What the host must do before the next attempt. */
    public enum Next {
        /** The unit finished. */
        DONE,
        /** The attempt missed: fulfil the `bytes` requests of the round, then attempt again. */
        FETCH,
        /** The unit asked for these: fulfil them, then attempt again. */
        REQUESTS
    }

    private static int[] vec(Port.Cells c, int from, int to) {
        int[] out = new int[to - from];
        for (int i = from; i < to; i++) {
            out[i - from] = Port.cellsAt(c, i);
        }
        return out;
    }

    private static List<int[]> records(Port.Cells c) {
        int n = Port.cellsLen(c);
        List<int[]> out = new ArrayList<>();
        for (int at = 0; at < n; at = Plan.recNext(c, at)) {
            out.add(vec(c, at, at + Plan.recLen(c, at)));
        }
        return out;
    }

    /** One conversion's plan machine: it lasts the whole conversion, and {@link #endUnit} starts the next unit. */
    public static final class Machine {
        private final Planctx.Plan p = new Planctx.Plan();
        private Map<Integer, Port.Bitmap> bitmap = new HashMap<>();
        private Map<Integer, Port.Bitmap> all = new HashMap<>();
        private List<Request> reqs = new ArrayList<>();
        private Map<Integer, Port.Bitmap> staged = new HashMap<>();

        public Machine(Config cfg) {
            p.maxRounds = cfg.maxRounds;
            p.fetchLimit = cfg.fetchRounds;
        }

        /** What the current unit has counted. */
        public Stats stats() {
            Stats s = new Stats();
            s.attempts = p.attempts;
            s.rounds = p.rounds;
            s.requests = p.requests;
            s.fetchRounds = p.fetchRounds;
            s.bytesRequests = p.bytesRequests;
            return s;
        }

        /** Kin's totals. */
        public Totals totals() {
            Totals t = new Totals();
            t.attempts = p.tAttempts;
            t.fetchRounds = p.tFetchRounds;
            t.bytesRequests = p.tBytesRequests;
            t.requests = p.tRequests;
            for (int i = 0; i < Port.cellsLen(p.pgr); i++) {
                t.pageRounds.add(Port.cellsAt(p.pgr, i));
            }
            for (int i = 0; i < Port.cellsLen(p.pgq); i++) {
                t.pageRequests.add(Port.cellsAt(p.pgq, i));
            }
            return t;
        }

        // the error kin holds, as the loop reports it: the name is kin's and so is the sentence
        private PlanError err() {
            boolean limited = p.err == Planloop.peMaxRounds() || p.err == Planloop.peFetchRounds() || p.err == Planloop.peOcrRequests();
            return new PlanError(peName(p.err), p.msg.toString(), limited, p.errLimit, p.errActual);
        }

        private List<Request> round(byte[] payload) {
            List<Request> out = new ArrayList<>();
            for (int[] rec : records(p.reqs)) {
                out.add(new Request(rec, payload == null ? new byte[0] : payload));
            }
            return out;
        }

        /**
         * Runs one attempt of the unit from scratch on the answers so far and says what the host must do before the next. A PlanError is the loop's (a
         * named limit, a malformed request, ...).
         */
        public Next attempt(Unit unit, Misses misses) {
            Planloop.plAttempt(p);
            reqs = new ArrayList<>();
            staged = new HashMap<>();
            all = new HashMap<>();
            Port.BitmapSet bms = Port.newBitmapSet();
            bms.answered.putAll(bitmap);
            Port.Buf payload = Port.newBuf();
            int status = unit.run(p.ans, p.rq, bms, payload);
            Map<Integer, Port.Bitmap> made = bms.made;
            int act = Planloop.plAfter(p, status);
            if (act == Planloop.actDone()) {
                return Next.DONE;
            }
            if (act == Planloop.actMiss()) {
                // The attempt missed: it is discarded whole, requests too. The ranges the source missed are the host's to say; what they become, and
                // what none means, is kin's.
                for (Range r : misses.take(Planloop.plMissRound(p))) {
                    Port.cellsPush(p.cand, r.input);
                    Port.cellsPush(p.cand, r.offset);
                    Port.cellsPush(p.cand, r.length);
                }
                if (Planloop.plFetch(p) != Planloop.actFetch()) {
                    throw err();
                }
                reqs = round(null);
                return Next.FETCH;
            }
            if (act == Planloop.actRequests()) {
                byte[] shared = Port.bufLen(payload) > 0 ? Port.bufBlob(payload) : null;
                reqs = round(shared);
                all = bitmap;
                if (!made.isEmpty()) {
                    all = new HashMap<>(bitmap);
                    all.putAll(made);
                }
                return Next.REQUESTS;
            }
            throw err();
        }

        /** The round in progress, sorted by id. */
        public List<Request> requests() {
            return reqs;
        }

        /** An answer to the round, in whatever order it arrives: refused (`bad-record`) when it answers nothing the round asked. */
        public void offer(Answer a) {
            Port.cellsDrop(p.cur, 0);
            for (int v : a.rec) {
                Port.cellsPush(p.cur, v);
            }
            if (Planloop.plOffer(p) != 0) {
                throw err();
            }
            staged.put(a.rec[2], a.bitmap);
        }

        /** Whether the round has an answer to request id. */
        public boolean has(int id) {
            return Planloop.plHas(p, id) == 1;
        }

        /** How many requests of the round have no answer yet. */
        public int owed() {
            return Planloop.plOwed(p);
        }

        /**
         * Called when every request of the round is answered: the answers join the unit's, in id order. The entries are what a transcript records (round,
         * request, answer, bitmap), in id order; a `bytes` round has none.
         */
        public List<Entry> commit() {
            boolean requestsRound = p.outk == 2;
            if (Planloop.plCommit(p) != 0) {
                throw err();
            }
            List<Entry> out = new ArrayList<>();
            Port.Cells tx = p.tx;
            int n = Port.cellsLen(tx);
            for (int at = 0; at < n;) {
                int round = Planloop.txRound(tx, at);
                int rq = Planloop.txReq(tx, at), an = Planloop.txAns(tx, at), next = Planloop.txNext(tx, at);
                int[] req = vec(tx, rq, an), ans = vec(tx, an, next);
                Port.Bitmap bm = staged.get(ans[2]);
                if (bm != null) {
                    bitmap.put(ans[2], bm);
                }
                out.add(new Entry(round, req, ans, bm));
                at = next;
            }
            Port.cellsDrop(tx, 0);
            staged = new HashMap<>();
            if (requestsRound) {
                reqs = new ArrayList<>();
                all = new HashMap<>();
            }
            return out;
        }

        /** Starts every request of the round with f and takes every completion, in whatever order they arrive; the round is committed. */
        public List<Entry> fulfil(Fulfiller f) {
            Map<Integer, Port.Bitmap> a = p.outk == 2 ? all : new HashMap<>();
            for (Request r : reqs) {
                f.start(r, a);
            }
            for (int i = 0; i < reqs.size(); i++) {
                offer(f.next());
            }
            return commit();
        }

        /** Folds a finished unit's accounting into the totals and starts the next with no answers and no bitmaps. */
        public void endUnit() {
            Planloop.plFold(p);
            bitmap = new HashMap<>();
            all = new HashMap<>();
            reqs = new ArrayList<>();
            staged = new HashMap<>();
        }

        /**
         * The schedule (kin's `en-next`): the unit that follows, as a `Planloop.ph*` code. kind is what the document pass built (0 nothing, 1 a PDF, 2 a
         * raster input), assets whether the asset level is on, pending whether the figures unit is still to run, np the page count.
         */
        public int nextPhase(int kind, boolean assets, boolean pending, int np) {
            return Planloop.enNext(p, kind, assets ? 1 : 0, pending ? 1 : 0, np);
        }

        /** The unit the schedule is on. */
        public int phase() {
            return p.phase;
        }

        /** The page unit the schedule is on. */
        public int page() {
            return p.page;
        }
    }

    /**
     * Runs the unit to completion with the synchronous driver: a miss is fetched with bytesF and the unit re-run; a round of requests is fulfilled
     * with ports (and recorded, when rec is not null) and the unit re-run on the answers.
     */
    public static void drive(Machine m, Unit unit, Fulfiller ports, Fulfiller bytesF, Misses misses, Transcript rec) {
        while (true) {
            switch (m.attempt(unit, misses)) {
                case DONE:
                    return;
                case FETCH:
                    m.fulfil(bytesF);
                    break;
                default:
                    List<Entry> entries = m.fulfil(ports);
                    if (rec != null) {
                        rec.entries.addAll(entries);
                    }
                    break;
            }
        }
    }

    /** The spans of input me that a request names (kin's `pl-spans`): as (offset, length). */
    public static List<int[]> spans(Request req, int me) {
        Port.Cells c = Port.newCells();
        for (int v : req.rec) {
            Port.cellsPush(c, v);
        }
        Port.Cells out = Port.newCells();
        Planloop.plSpans(c, 0, me, out);
        List<int[]> s = new ArrayList<>();
        for (int i = 0; i < Port.cellsLen(out) / 2; i++) {
            s.add(new int[] {Port.cellsAt(out, 2 * i), Port.cellsAt(out, 2 * i + 1)});
        }
        return s;
    }

    /** Which port fulfils a request of kind (kin's `pl-route`): ocr says whether the host has an engine, render a renderer. */
    public static int route(int kind, boolean ocr, boolean render) {
        int ports = 0;
        if (ocr) {
            ports += Planloop.ppOcr();
        }
        if (render) {
            ports += Planloop.ppRender();
        }
        return Planloop.plRoute(kind, ports);
    }

    /** The answer of a request no port is configured for. */
    public static Answer unsupportedAnswer(Request req) {
        Port.Cells c = Port.newCells();
        Planloop.plUnsupported(c, req.kind(), req.id());
        return new Answer(records(c).get(0));
    }

    // ------------------------------------------------------------------------------------------------- the transcript

    /** One named file of a transcript. */
    public static final class File {
        public final String name;
        public final byte[] bytes;

        public File(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private static Port.Cells cps(String s) {
        Port.Cells c = Port.newCells();
        s.codePoints().forEach(cp -> Port.cellsPush(c, cp));
        return c;
    }

    private static Transcriptctx.Tr inputsKin(List<int[]> inputs) {
        Transcriptctx.Tr tr = new Transcriptctx.Tr();
        for (int[] in : inputs) {
            emon.Transcript.trAddInput(tr, in[0], in[1]);
        }
        return tr;
    }

    /** The complete set of answers of one run. Inputs are (index, length) only: never bytes, origin, fetch count or password (SPEC 13.1). */
    public static final class Transcript {
        public List<int[]> inputs = new ArrayList<>();
        public final List<Entry> entries = new ArrayList<>();
        /**
         * WHO ANSWERED (D214): the live ports' identities as name/value pairs (an OCR engine and its version, the models it read with), written as
         * `:producer {...}` when there are any and never read back for a replay. Empty leaves the file as it always was.
         */
        public List<String[]> producer = new ArrayList<>();

        // the transcript as kin holds it (`Tr`), the bitmaps left behind: the entry at a place has one when its flag says
        Transcriptctx.Tr kin() {
            Transcriptctx.Tr tr = inputsKin(inputs);
            for (String[] kv : producer) {
                emon.Transcript.trAddProducer(tr, cps(kv[0]), cps(kv[1]));
            }
            for (Entry e : entries) {
                Port.Cells c = Port.newCells();
                for (int x : e.req) {
                    Port.cellsPush(c, x);
                }
                for (int x : e.ans) {
                    Port.cellsPush(c, x);
                }
                emon.Transcript.trAddEntry(tr, e.round, c, 0, e.req.length, e.req.length, e.ans.length, e.bitmap != null ? 1 : 0);
            }
            return tr;
        }

        /**
         * Writes the transcript as files in a fixed order: transcript.edn, then one lossless PNG per bitmap named by its entry's index. DETERMINISTIC: the
         * same entries always give the same bytes (and the same bytes as the Rust host).
         */
        public List<File> toFiles() {
            StringBuilder sb = new StringBuilder();
            emon.Transcript.trWrite(kin(), sb);
            List<File> files = new ArrayList<>();
            files.add(new File(TRANSCRIPT_FILE, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (int n = 0; n < entries.size(); n++) {
                Entry e = entries.get(n);
                if (e.bitmap != null) {
                    StringBuilder name = new StringBuilder();
                    emon.Transcript.trBitmapName(n, name);
                    files.add(new File(name.toString(), pngEncode(e.bitmap)));
                }
            }
            return files;
        }

        /** Replay must be given the inputs the recording saw. */
        public void checkInputs(List<int[]> given) {
            Port.Cells want = Port.newCells();
            for (int[] in : given) {
                Port.cellsPush(want, in[0]);
                Port.cellsPush(want, in[1]);
            }
            StringBuilder sb = new StringBuilder();
            if (emon.Transcript.trCheckInputs(inputsKin(inputs), want, sb) != 0) {
                throw bad(sb.toString());
            }
        }
    }

    private static String tbName(int code) {
        StringBuilder sb = new StringBuilder();
        emon.Transcript.tbEmitName(sb, code);
        return sb.toString();
    }

    /** Reads what {@link Transcript#toFiles} wrote. */
    public static Transcript fromFiles(Map<String, byte[]> files) {
        byte[] raw = files.get(TRANSCRIPT_FILE);
        if (raw == null) {
            throw bad(tbName(emon.Transcript.tbNoFile()));
        }
        Transcriptctx.Tr tr = new Transcriptctx.Tr();
        if (emon.Transcript.trRead(tr, raw) != 0) {
            throw bad(tr.msg.toString());
        }
        Transcript t = new Transcript();
        for (int i = 0; i < emon.Transcript.trNInputs(tr); i++) {
            t.inputs.add(new int[] {emon.Transcript.trInIndex(tr, i), emon.Transcript.trInLength(tr, i)});
        }
        for (int p = 0; emon.Transcript.trPMore(tr, p) == 1; p = emon.Transcript.trPNext(tr, p)) {
            StringBuilder k = new StringBuilder(), v = new StringBuilder();
            emon.Transcript.trPKey(tr, p, k);
            emon.Transcript.trPVal(tr, p, v);
            t.producer.add(new String[] {k.toString(), v.toString()});
        }
        for (int e = 0; emon.Transcript.trEMore(tr, e) == 1; e = emon.Transcript.trENext(tr, e)) {
            int rq = emon.Transcript.trEReq(tr, e), rl = emon.Transcript.trEReqLen(tr, e);
            int an = emon.Transcript.trEAns(tr, e), al = emon.Transcript.trEAnsLen(tr, e);
            int round = emon.Transcript.trERound(tr, e);
            int[] req = vec(tr.ents, rq, rq + rl), ans = vec(tr.ents, an, an + al);
            Port.Bitmap bm = null;
            StringBuilder name = new StringBuilder();
            if (emon.Transcript.trEBitmapName(tr, e, name) == 1) {
                byte[] png = files.get(name.toString());
                if (png == null) {
                    throw bad(name + " " + tbName(emon.Transcript.tbBitmapMissing()));
                }
                try {
                    bm = pngDecode(png);
                } catch (IllegalArgumentException x) {
                    throw bad(x.getMessage());
                }
            }
            t.entries.add(new Entry(round, req, ans, bm));
        }
        return t;
    }

    /** Writes a bitmap as emon's canonical PNG: 8-bit gray or RGB, no pHYs, bytes identical on every target (the encoder is kin's, `emon.png`). */
    public static byte[] pngEncode(Port.Bitmap b) {
        Port.Cells ws = Port.newCells(), pal = Port.newCells(), row = Port.newCells();
        Port.Buf out = Port.newBuf(), z = Port.newBuf();
        Pngread.pgEncode(b, ws, out, z, pal, row);
        return Port.bufBlob(out);
    }

    /** Reads a transcript's bitmap: a PNG emon wrote (kin's `emon.pngread`, P10.11, D1180). @throws IllegalArgumentException with kin's sentence on a bad one */
    public static Port.Bitmap pngDecode(byte[] p) {
        Port.Cells r = Port.newCells(), st = Port.newCells(), out = Port.newCells(), rows = Port.newCells();
        Port.Buf idat = Port.newBuf(), raw = Port.newBuf();
        int s = Pngread.pgOpen(p, r, st, out, idat, raw);
        if (s == 0) {
            Port.Bitmap bm = Port.bitmapNew(Port.cellsAt(r, Pngcheck.prW()), Port.cellsAt(r, Pngcheck.prH()), Port.cellsAt(r, Pngcheck.prCh()));
            s = Pngread.pgFill(r, raw, rows, bm);
            if (s == 0) {
                return bm;
            }
        }
        StringBuilder sb = new StringBuilder();
        Pngread.pgMsg(s, sb);
        throw new IllegalArgumentException(sb.toString());
    }

    /**
     * Answers from a transcript: a request it has no entry for is `transcript-miss`, never a guess. `bytes` is not here and cannot be. The map it answers
     * from is kin's (`tr-index`, `tr-take`): keyed by the whole request record.
     */
    public static final class Replayer implements Fulfiller {
        private final Transcriptctx.Tr tr;
        private final List<Port.Bitmap> bitmaps = new ArrayList<>();
        private final Port.Cells key = Port.newCells();
        private final ArrayDeque<Object> done = new ArrayDeque<>();

        /** Indexes a transcript. */
        public Replayer(Transcript t) {
            tr = t.kin();
            emon.Transcript.trIndex(tr);
            for (Entry e : t.entries) {
                bitmaps.add(e.bitmap);
            }
        }

        /** How many recorded entries the run never asked for. */
        public int unused() {
            return emon.Transcript.trUnused(tr);
        }

        /** The end of the run: a `transcript-bad` error, with kin's sentence, when it left recorded entries unasked (a transcript of another run). */
        public void finish() {
            if (emon.Transcript.trUnused(tr) == 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            emon.Transcript.trUnusedMsg(tr, sb);
            throw bad(sb.toString());
        }

        public void start(Request req, Map<Integer, Port.Bitmap> bm) {
            Port.cellsDrop(key, 0);
            for (int v : req.rec) {
                Port.cellsPush(key, v);
            }
            int e = emon.Transcript.trTake(tr, key, 0, req.rec.length);
            if (e >= 0) {
                int an = emon.Transcript.trEAns(tr, e);
                done.add(new Answer(vec(tr.ents, an, an + emon.Transcript.trEAnsLen(tr, e)), bitmaps.get(emon.Transcript.trEN(tr, e))));
            } else {
                StringBuilder sb = new StringBuilder();
                emon.Transcript.trMissMsg(key, 0, req.rec.length, sb);
                done.add(new PlanError(peName(Planloop.peTranscriptMiss()), sb.toString()));
            }
        }

        public Answer next() {
            Object d = done.poll();
            if (d instanceof PlanError) {
                throw (PlanError) d;
            }
            return (Answer) d;
        }
    }
}
