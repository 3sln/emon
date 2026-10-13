// The C# driver of kin's plan machine and the C# half of the transcript (P10.8, P10.9; D396, D399, D438; DEVLOG P2.5, SPEC 7.3, 13, D058 revised).
// Its twins are `host/java/emon/Planrun.java`, `host/go/planrun/{planrun,transcript}.go` and `Machine` and the end of `host/rust/src/plan.rs`: read those
// for the reasoning.
//
// THE LOOP AND THE TRANSCRIPT ARE KIN'S (`kin/planloop.kin`, `kin/transcript.kin`). What was C#'s `PlanLoop.Run` (and its EDN writer and reader, its kind
// table, `ProducerInOrder`, its replay map and its unused count) is here the unit call (the caller's, under the caller's own panic catch), the await, and
// the fulfilment of the ports. One attempt's accounting, the two named limits, the requests a unit wrote (checked, and sorted by id), the answered set,
// the order the answers are appended in, the transcript's entries, the schedule (`en-next`), the fetch planner and every name are kin's. What stays is
// what is not observable: the bitmaps (a PNG each, kin's `emon.pngread`, P10.11), the in-memory `Transcript` the embedder holds, the `Replayer` that answers the loop from
// kin's map and the fulfilment.
//
// It is named `Planrun`, not `PlanLoop`: the generated `emon.Planloop` is kin's and a case-insensitive file system would hold only one of them. Nothing
// here touches a filesystem (D058): a transcript is named files in memory (`File`, `FromFiles`) and the driver moves them to a directory. No `@kin:link`
// annotation is declared: this is a host driver for the ports, not a port kin calls.

using System.Collections.Generic;

namespace emon;

public static class Planrun {
    // The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).
    public const int KindRender = 1;
    public const int KindDecode = 2;
    public const int KindOcr = 3;
    public const int KindBytes = 4;
    public const int KindCrop = 5;
    public const int KindDecodeImg = 6;

    public const int StatusOk = 0;
    public const int StatusFailed = 1;

    public const int UnitDone = 0;
    public const int UnitRequests = 1;
    public const int UnitNeedBytes = 7;

    // SPEC 13.1's defaults. Both are NAMED limits: a breach is a PlanError whose name is `max-rounds` or `fetch-rounds`. (`Cfg` of `emon.config` holds the
    // same numbers; these are the defaults of a machine made with none.)
    public const int MaxRounds = 5;
    public const int FetchRounds = 24;

    public const string TranscriptFile = "transcript.edn";

    /// <summary>The named limits.</summary>
    public sealed class Config {
        public int MaxRounds = Planrun.MaxRounds;
        public int FetchRounds = Planrun.FetchRounds;

        public Config() {
        }

        public Config(int maxRounds, int fetchRounds) {
            MaxRounds = maxRounds;
            FetchRounds = fetchRounds;
        }
    }

    /// <summary>
    /// A named failure of the loop: the name is kin's (`Planloop.Pe*`: `max-rounds` and `fetch-rounds` (limits), `transcript-miss`, `transcript-bad`,
    /// `fulfil-failed`, `unit-failed`, `bad-record`, `need-bytes-stalled`), made by this class.
    /// </summary>
    public sealed class PlanError : System.Exception {
        public readonly string Name;
        public readonly string Msg;
        /// <summary>True for the two named limits, whose bound and the number reached are kin's (`err-limit`, `err-actual`), not words to parse.</summary>
        public readonly bool Limited;
        public readonly int Limit, Actual;

        public PlanError(string name, string msg) : this(name, msg, false, 0, 0) {
        }

        public PlanError(string name, string msg, bool limited, int limit, int actual) : base(name + ": " + msg) {
            Name = name;
            Msg = msg;
            Limited = limited;
            Limit = limit;
            Actual = actual;
        }
    }

    private static string PeName(int code) {
        var sb = new System.Text.StringBuilder();
        Planloop.PeEmitName(sb, code);
        return sb.ToString();
    }

    /// <summary>A `transcript-bad` error with this sentence.</summary>
    public static PlanError Bad(string msg) {
        return new PlanError(PeName(Planloop.PeTranscriptBad()), msg);
    }

    /// <summary>A `fulfil-failed` error with this sentence: a port could not run (distinct from an answer that says `failed`).</summary>
    public static PlanError FulfilFailed(string msg) {
        return new PlanError(PeName(Planloop.PeFulfilFailed()), msg);
    }

    /// <summary>A request record and the unit's PAYLOAD (P6.1a, D291): the bytes kin made itself in the attempt that wrote it, shared by every request of the round.</summary>
    public sealed class Request {
        public readonly int[] Rec;
        /// <summary>Never recorded in a transcript (the record names them by SHA-256); empty for a request that names none.</summary>
        public readonly byte[] Payload;

        public Request(int[] rec) : this(rec, new byte[0]) {
        }

        public Request(int[] rec, byte[] payload) {
            Rec = rec;
            Payload = payload;
        }

        public int Kind() {
            return Rec[1];
        }

        public int Id() {
            return Rec[2];
        }

        /// <summary>The argument a kin field names (`Plan.DqWhere()`, `Plan.BqOffset()`, ...): a host reads a request by these and never by a literal offset (D396 item 5).</summary>
        public int Field(int f) {
            return Rec[3 + f];
        }
    }

    /// <summary>An answer record and, for render/decode/ocr, its pixels.</summary>
    public sealed class Answer {
        public readonly int[] Rec;
        public readonly Port.Bitmap Bitmap;

        public Answer(int[] rec, Port.Bitmap bitmap) {
            Rec = rec;
            Bitmap = bitmap;
        }

        public Answer(int[] rec) : this(rec, null) {
        }

        public int Kind() {
            return Rec[1];
        }

        public int Id() {
            return Rec[2];
        }

        public int Status() {
            return Rec[3];
        }
    }

    /// <summary>Fulfils requests, sync or async-shaped. Errors are thrown as <see cref="PlanError"/>.</summary>
    public interface IFulfiller {
        /// <summary>
        /// Begins a request. A sync host may finish it here. `bm` holds every bitmap answered so far (an OCR request names one by id); take what is
        /// needed, it is not kept.
        /// </summary>
        void Start(Request req, Dictionary<int, Port.Bitmap> bm);

        /// <summary>The next completed answer, in whatever order completions arrive. Called once per started request.</summary>
        Answer Next();
    }

    /// <summary>A function from a request to an answer.</summary>
    public delegate Answer Fn(Request req, Dictionary<int, Port.Bitmap> bm);

    /// <summary>Adapts a function: it completes inside `Start` (a failure is remembered and thrown by `Next`).</summary>
    public sealed class Sync : IFulfiller {
        private readonly Fn f;
        private readonly Queue<object> done = new Queue<object>();

        public Sync(Fn f) {
            this.f = f;
        }

        public void Start(Request req, Dictionary<int, Port.Bitmap> bm) {
            try {
                done.Enqueue(f(req, bm));
            } catch (PlanError e) {
                done.Enqueue(e);
            }
        }

        public Answer Next() {
            object d = done.Count > 0 ? done.Dequeue() : null;
            if (d is PlanError pe) {
                throw pe;
            }
            return (Answer) d;
        }
    }

    /// <summary>A range of an input the source missed.</summary>
    public sealed class Range {
        public readonly int Input, Offset, Length;

        public Range(int input, int offset, int length) {
            Input = input;
            Offset = offset;
            Length = length;
        }
    }

    /// <summary>What a `Src` missed since the last call, for fetch round `round`. The ENGINE implements it over its backings (D058).</summary>
    public interface IMisses {
        List<Range> Take(int round);
    }

    private sealed class NoMissesImpl : IMisses {
        public List<Range> Take(int round) {
            return new List<Range>();
        }
    }

    /// <summary>A source that never misses.</summary>
    public static readonly IMisses NoMisses = new NoMissesImpl();

    /// <summary>
    /// The unit, closed over what it needs: handed the answers so far, an empty requests Cells, this attempt's BitmapSet and the PAYLOAD Buf, it answers a
    /// unit status.
    /// </summary>
    public delegate int Unit(Port.Cells answers, Port.Cells requests, Port.BitmapSet bms, Port.Buf payload);

    /// <summary>One recorded request/answer pair.</summary>
    public sealed class Entry {
        public readonly int Round;
        public readonly int[] Req, Ans;
        public readonly Port.Bitmap Bitmap;

        public Entry(int round, int[] req, int[] ans, Port.Bitmap bitmap) {
            Round = round;
            Req = req;
            Ans = ans;
            Bitmap = bitmap;
        }
    }

    /// <summary>What a run did.</summary>
    public sealed class Stats {
        public int Attempts, Rounds, Requests, FetchRounds, BytesRequests;
    }

    /// <summary>What the finished units counted, and the page units' rounds and requests in page order.</summary>
    public sealed class Totals {
        public int Attempts, FetchRounds, BytesRequests, Requests;
        public readonly List<int> PageRounds = new List<int>();
        public readonly List<int> PageRequests = new List<int>();
    }

    /// <summary>What the host must do before the next attempt.</summary>
    public enum Next {
        /// <summary>The unit finished.</summary>
        Done,
        /// <summary>The attempt missed: fulfil the `bytes` requests of the round, then attempt again.</summary>
        Fetch,
        /// <summary>The unit asked for these: fulfil them, then attempt again.</summary>
        Requests
    }

    private static int[] Vec(Port.Cells c, int from, int to) {
        var o = new int[to - from];
        for (int i = from; i < to; i++) {
            o[i - from] = Port.CellsAt(c, i);
        }
        return o;
    }

    private static List<int[]> Records(Port.Cells c) {
        int n = Port.CellsLen(c);
        var o = new List<int[]>();
        for (int at = 0; at < n; at = Plan.RecNext(c, at)) {
            o.Add(Vec(c, at, at + Plan.RecLen(c, at)));
        }
        return o;
    }

    /// <summary>One conversion's plan machine: it lasts the whole conversion, and <see cref="EndUnit"/> starts the next unit.</summary>
    public sealed class Machine {
        private readonly Planctx.Plan p = new Planctx.Plan();
        private Dictionary<int, Port.Bitmap> bitmap = new Dictionary<int, Port.Bitmap>();
        private Dictionary<int, Port.Bitmap> all = new Dictionary<int, Port.Bitmap>();
        private List<Request> reqs = new List<Request>();
        private Dictionary<int, Port.Bitmap> staged = new Dictionary<int, Port.Bitmap>();

        public Machine(Config cfg) {
            p.MaxRounds = cfg.MaxRounds;
            p.FetchLimit = cfg.FetchRounds;
        }

        /// <summary>What the current unit has counted.</summary>
        public Stats Stats() {
            var s = new Stats();
            s.Attempts = p.Attempts;
            s.Rounds = p.Rounds;
            s.Requests = p.Requests;
            s.FetchRounds = p.FetchRounds;
            s.BytesRequests = p.BytesRequests;
            return s;
        }

        /// <summary>Kin's totals.</summary>
        public Totals Totals() {
            var t = new Totals();
            t.Attempts = p.TAttempts;
            t.FetchRounds = p.TFetchRounds;
            t.BytesRequests = p.TBytesRequests;
            t.Requests = p.TRequests;
            for (int i = 0; i < Port.CellsLen(p.Pgr); i++) {
                t.PageRounds.Add(Port.CellsAt(p.Pgr, i));
            }
            for (int i = 0; i < Port.CellsLen(p.Pgq); i++) {
                t.PageRequests.Add(Port.CellsAt(p.Pgq, i));
            }
            return t;
        }

        // the error kin holds, as the loop reports it: the name is kin's and so is the sentence
        private PlanError Err() {
            bool limited = p.Err == Planloop.PeMaxRounds() || p.Err == Planloop.PeFetchRounds() || p.Err == Planloop.PeOcrRequests();
            return new PlanError(PeName(p.Err), p.Msg.ToString(), limited, p.ErrLimit, p.ErrActual);
        }

        private List<Request> Round(byte[] payload) {
            var o = new List<Request>();
            foreach (int[] rec in Records(p.Reqs)) {
                o.Add(new Request(rec, payload ?? new byte[0]));
            }
            return o;
        }

        /// <summary>
        /// Runs one attempt of the unit from scratch on the answers so far and says what the host must do before the next. A PlanError is the loop's (a
        /// named limit, a malformed request, ...).
        /// </summary>
        public Next Attempt(Unit unit, IMisses misses) {
            Planloop.PlAttempt(p);
            reqs = new List<Request>();
            staged = new Dictionary<int, Port.Bitmap>();
            all = new Dictionary<int, Port.Bitmap>();
            Port.BitmapSet bms = Port.NewBitmapSet();
            foreach (var kv in bitmap) {
                bms.Answered[kv.Key] = kv.Value;
            }
            Port.Buf payload = Port.NewBuf();
            int status = unit(p.Ans, p.Rq, bms, payload);
            Dictionary<int, Port.Bitmap> made = bms.Made;
            int act = Planloop.PlAfter(p, status);
            if (act == Planloop.ActDone()) {
                return Next.Done;
            }
            if (act == Planloop.ActMiss()) {
                // The attempt missed: it is discarded whole, requests too. The ranges the source missed are the host's to say; what they become, and
                // what none means, is kin's.
                foreach (Range r in misses.Take(Planloop.PlMissRound(p))) {
                    Port.CellsPush(p.Cand, r.Input);
                    Port.CellsPush(p.Cand, r.Offset);
                    Port.CellsPush(p.Cand, r.Length);
                }
                if (Planloop.PlFetch(p) != Planloop.ActFetch()) {
                    throw Err();
                }
                reqs = Round(null);
                return Next.Fetch;
            }
            if (act == Planloop.ActRequests()) {
                byte[] shared = Port.BufLen(payload) > 0 ? Port.BufBlob(payload) : null;
                reqs = Round(shared);
                all = bitmap;
                if (made.Count > 0) {
                    all = new Dictionary<int, Port.Bitmap>(bitmap);
                    foreach (var kv in made) {
                        all[kv.Key] = kv.Value;
                    }
                }
                return Next.Requests;
            }
            throw Err();
        }

        /// <summary>The round in progress, sorted by id.</summary>
        public List<Request> Requests() {
            return reqs;
        }

        /// <summary>An answer to the round, in whatever order it arrives: refused (`bad-record`) when it answers nothing the round asked.</summary>
        public void Offer(Answer a) {
            Port.CellsDrop(p.Cur, 0);
            foreach (int v in a.Rec) {
                Port.CellsPush(p.Cur, v);
            }
            if (Planloop.PlOffer(p) != 0) {
                throw Err();
            }
            staged[a.Rec[2]] = a.Bitmap;
        }

        /// <summary>Whether the round has an answer to request id.</summary>
        public bool Has(int id) {
            return Planloop.PlHas(p, id) == 1;
        }

        /// <summary>How many requests of the round have no answer yet.</summary>
        public int Owed() {
            return Planloop.PlOwed(p);
        }

        /// <summary>
        /// Called when every request of the round is answered: the answers join the unit's, in id order. The entries are what a transcript records (round,
        /// request, answer, bitmap), in id order; a `bytes` round has none.
        /// </summary>
        public List<Entry> Commit() {
            bool requestsRound = p.Outk == 2;
            if (Planloop.PlCommit(p) != 0) {
                throw Err();
            }
            var o = new List<Entry>();
            Port.Cells tx = p.Tx;
            int n = Port.CellsLen(tx);
            for (int at = 0; at < n;) {
                int round = Planloop.TxRound(tx, at);
                int rq = Planloop.TxReq(tx, at), an = Planloop.TxAns(tx, at), next = Planloop.TxNext(tx, at);
                int[] req = Vec(tx, rq, an), ans = Vec(tx, an, next);
                staged.TryGetValue(ans[2], out Port.Bitmap bm);
                if (bm != null) {
                    bitmap[ans[2]] = bm;
                }
                o.Add(new Entry(round, req, ans, bm));
                at = next;
            }
            Port.CellsDrop(tx, 0);
            staged = new Dictionary<int, Port.Bitmap>();
            if (requestsRound) {
                reqs = new List<Request>();
                all = new Dictionary<int, Port.Bitmap>();
            }
            return o;
        }

        /// <summary>Starts every request of the round with f and takes every completion, in whatever order they arrive; the round is committed.</summary>
        public List<Entry> Fulfil(IFulfiller f) {
            Dictionary<int, Port.Bitmap> a = p.Outk == 2 ? all : new Dictionary<int, Port.Bitmap>();
            foreach (Request r in reqs) {
                f.Start(r, a);
            }
            for (int i = 0; i < reqs.Count; i++) {
                Offer(f.Next());
            }
            return Commit();
        }

        /// <summary>Folds a finished unit's accounting into the totals and starts the next with no answers and no bitmaps.</summary>
        public void EndUnit() {
            Planloop.PlFold(p);
            bitmap = new Dictionary<int, Port.Bitmap>();
            all = new Dictionary<int, Port.Bitmap>();
            reqs = new List<Request>();
            staged = new Dictionary<int, Port.Bitmap>();
        }

        /// <summary>
        /// The schedule (kin's `en-next`): the unit that follows, as a `Planloop.Ph*` code. kind is what the document pass built (0 nothing, 1 a PDF, 2 a
        /// raster input), assets whether the asset level is on, pending whether the figures unit is still to run, np the page count.
        /// </summary>
        public int NextPhase(int kind, bool assets, bool pending, int np) {
            return Planloop.EnNext(p, kind, assets ? 1 : 0, pending ? 1 : 0, np);
        }

        /// <summary>The unit the schedule is on.</summary>
        public int Phase() {
            return p.Phase;
        }

        /// <summary>The page unit the schedule is on.</summary>
        public int Page() {
            return p.Page;
        }
    }

    /// <summary>
    /// Runs the unit to completion with the synchronous driver: a miss is fetched with bytesF and the unit re-run; a round of requests is fulfilled
    /// with ports (and recorded, when rec is not null) and the unit re-run on the answers.
    /// </summary>
    public static void Drive(Machine m, Unit unit, IFulfiller ports, IFulfiller bytesF, IMisses misses, Transcript rec) {
        while (true) {
            switch (m.Attempt(unit, misses)) {
                case Next.Done:
                    return;
                case Next.Fetch:
                    m.Fulfil(bytesF);
                    break;
                default:
                    List<Entry> entries = m.Fulfil(ports);
                    if (rec != null) {
                        rec.Entries.AddRange(entries);
                    }
                    break;
            }
        }
    }

    /// <summary>The spans of input me that a request names (kin's `pl-spans`): as (offset, length).</summary>
    public static List<int[]> Spans(Request req, int me) {
        Port.Cells c = Port.NewCells();
        foreach (int v in req.Rec) {
            Port.CellsPush(c, v);
        }
        Port.Cells o = Port.NewCells();
        Planloop.PlSpans(c, 0, me, o);
        var s = new List<int[]>();
        for (int i = 0; i < Port.CellsLen(o) / 2; i++) {
            s.Add(new int[] {Port.CellsAt(o, 2 * i), Port.CellsAt(o, 2 * i + 1)});
        }
        return s;
    }

    /// <summary>Which port fulfils a request of kind (kin's `pl-route`): ocr says whether the host has an engine, render a renderer.</summary>
    public static int Route(int kind, bool ocr, bool render) {
        int ports = 0;
        if (ocr) {
            ports += Planloop.PpOcr();
        }
        if (render) {
            ports += Planloop.PpRender();
        }
        return Planloop.PlRoute(kind, ports);
    }

    /// <summary>The answer of a request no port is configured for.</summary>
    public static Answer UnsupportedAnswer(Request req) {
        Port.Cells c = Port.NewCells();
        Planloop.PlUnsupported(c, req.Kind(), req.Id());
        return new Answer(Records(c)[0]);
    }

    // ------------------------------------------------------------------------------------------------- the transcript

    /// <summary>One named file of a transcript.</summary>
    public sealed class File {
        public readonly string Name;
        public readonly byte[] Bytes;

        public File(string name, byte[] bytes) {
            Name = name;
            Bytes = bytes;
        }
    }

    private static Port.Cells Cps(string s) {
        Port.Cells c = Port.NewCells();
        for (int i = 0; i < s.Length;) {
            int cp = char.ConvertToUtf32(s, i);
            i += char.IsHighSurrogate(s[i]) && i + 1 < s.Length ? 2 : 1;
            Port.CellsPush(c, cp);
        }
        return c;
    }

    private static Transcriptctx.Tr InputsKin(List<int[]> inputs) {
        var tr = new Transcriptctx.Tr();
        foreach (int[] i in inputs) {
            emon.Transcript.TrAddInput(tr, i[0], i[1]);
        }
        return tr;
    }

    /// <summary>The complete set of answers of one run. Inputs are (index, length) only: never bytes, origin, fetch count or password (SPEC 13.1).</summary>
    public sealed class Transcript {
        public List<int[]> Inputs = new List<int[]>();
        public readonly List<Entry> Entries = new List<Entry>();
        /// <summary>
        /// WHO ANSWERED (D214): the live ports' identities as name/value pairs (an OCR engine and its version, the models it read with), written as
        /// `:producer {...}` when there are any and never read back for a replay. Empty leaves the file as it always was.
        /// </summary>
        public List<string[]> Producer = new List<string[]>();

        // the transcript as kin holds it (`Tr`), the bitmaps left behind: the entry at a place has one when its flag says
        internal Transcriptctx.Tr Kin() {
            Transcriptctx.Tr tr = InputsKin(Inputs);
            foreach (string[] kv in Producer) {
                emon.Transcript.TrAddProducer(tr, Cps(kv[0]), Cps(kv[1]));
            }
            foreach (Entry e in Entries) {
                Port.Cells c = Port.NewCells();
                foreach (int x in e.Req) {
                    Port.CellsPush(c, x);
                }
                foreach (int x in e.Ans) {
                    Port.CellsPush(c, x);
                }
                emon.Transcript.TrAddEntry(tr, e.Round, c, 0, e.Req.Length, e.Req.Length, e.Ans.Length, e.Bitmap != null ? 1 : 0);
            }
            return tr;
        }

        /// <summary>
        /// Writes the transcript as files in a fixed order: transcript.edn, then one lossless PNG per bitmap named by its entry's index. DETERMINISTIC: the
        /// same entries always give the same bytes (and the same bytes as the Rust host).
        /// </summary>
        public List<File> ToFiles() {
            var sb = new System.Text.StringBuilder();
            emon.Transcript.TrWrite(Kin(), sb);
            var files = new List<File>();
            files.Add(new File(TranscriptFile, new System.Text.UTF8Encoding(false).GetBytes(sb.ToString())));
            for (int n = 0; n < Entries.Count; n++) {
                Entry e = Entries[n];
                if (e.Bitmap != null) {
                    var name = new System.Text.StringBuilder();
                    emon.Transcript.TrBitmapName(n, name);
                    files.Add(new File(name.ToString(), PngEncode(e.Bitmap)));
                }
            }
            return files;
        }

        /// <summary>Replay must be given the inputs the recording saw.</summary>
        public void CheckInputs(List<int[]> given) {
            Port.Cells want = Port.NewCells();
            foreach (int[] i in given) {
                Port.CellsPush(want, i[0]);
                Port.CellsPush(want, i[1]);
            }
            var sb = new System.Text.StringBuilder();
            if (emon.Transcript.TrCheckInputs(InputsKin(Inputs), want, sb) != 0) {
                throw Bad(sb.ToString());
            }
        }
    }

    private static string TbName(int code) {
        var sb = new System.Text.StringBuilder();
        emon.Transcript.TbEmitName(sb, code);
        return sb.ToString();
    }

    /// <summary>Reads what <see cref="Transcript.ToFiles"/> wrote.</summary>
    public static Transcript FromFiles(Dictionary<string, byte[]> files) {
        if (!files.TryGetValue(TranscriptFile, out byte[] raw)) {
            throw Bad(TbName(emon.Transcript.TbNoFile()));
        }
        var tr = new Transcriptctx.Tr();
        if (emon.Transcript.TrRead(tr, raw) != 0) {
            throw Bad(tr.Msg.ToString());
        }
        var t = new Transcript();
        for (int i = 0; i < emon.Transcript.TrNInputs(tr); i++) {
            t.Inputs.Add(new int[] {emon.Transcript.TrInIndex(tr, i), emon.Transcript.TrInLength(tr, i)});
        }
        for (int p = 0; emon.Transcript.TrPMore(tr, p) == 1; p = emon.Transcript.TrPNext(tr, p)) {
            var k = new System.Text.StringBuilder();
            var v = new System.Text.StringBuilder();
            emon.Transcript.TrPKey(tr, p, k);
            emon.Transcript.TrPVal(tr, p, v);
            t.Producer.Add(new string[] {k.ToString(), v.ToString()});
        }
        for (int e = 0; emon.Transcript.TrEMore(tr, e) == 1; e = emon.Transcript.TrENext(tr, e)) {
            int rq = emon.Transcript.TrEReq(tr, e), rl = emon.Transcript.TrEReqLen(tr, e);
            int an = emon.Transcript.TrEAns(tr, e), al = emon.Transcript.TrEAnsLen(tr, e);
            int round = emon.Transcript.TrERound(tr, e);
            int[] req = Vec(tr.Ents, rq, rq + rl), ans = Vec(tr.Ents, an, an + al);
            Port.Bitmap bm = null;
            var name = new System.Text.StringBuilder();
            if (emon.Transcript.TrEBitmapName(tr, e, name) == 1) {
                if (!files.TryGetValue(name.ToString(), out byte[] png)) {
                    throw Bad(name + " " + TbName(emon.Transcript.TbBitmapMissing()));
                }
                try {
                    bm = PngDecode(png);
                } catch (System.ArgumentException x) {
                    throw Bad(x.Message);
                }
            }
            t.Entries.Add(new Entry(round, req, ans, bm));
        }
        return t;
    }

    /// <summary>Writes a bitmap as emon's canonical PNG: 8-bit gray or RGB, no pHYs, bytes identical on every target (the encoder is kin's, `emon.png`).</summary>
    public static byte[] PngEncode(Port.Bitmap b) {
        Port.Cells ws = Port.NewCells(), pal = Port.NewCells(), row = Port.NewCells();
        Port.Buf o = Port.NewBuf(), z = Port.NewBuf();
        Pngread.PgEncode(b, ws, o, z, pal, row);
        return Port.BufBlob(o);
    }

    /// <summary>Reads a transcript's bitmap: a PNG emon wrote (kin's `emon.pngread`, P10.11, D1180). Throws an ArgumentException with kin's sentence on a bad one.</summary>
    public static Port.Bitmap PngDecode(byte[] p) {
        Port.Cells r = Port.NewCells(), st = Port.NewCells(), inflated = Port.NewCells(), px = Port.NewCells();
        Port.Buf idat = Port.NewBuf(), clean = Port.NewBuf();
        int s = Pngread.PgOpen(p, r, st, inflated, idat, clean);
        if (s == 0) {
            Port.Bitmap bm = Port.BitmapNew(Port.CellsAt(r, Pngcheck.PrW()), Port.CellsAt(r, Pngcheck.PrH()), Port.CellsAt(r, Pngcheck.PrCh()));
            s = Pngread.PgFill(r, inflated, px, bm);
            if (s == 0) {
                return bm;
            }
        }
        var sb = new System.Text.StringBuilder();
        Pngread.PgMsg(s, sb);
        throw new System.ArgumentException(sb.ToString());
    }

    /// <summary>
    /// Answers from a transcript: a request it has no entry for is `transcript-miss`, never a guess. `bytes` is not here and cannot be. The map it answers
    /// from is kin's (`tr-index`, `tr-take`): keyed by the whole request record.
    /// </summary>
    public sealed class Replayer : IFulfiller {
        private readonly Transcriptctx.Tr tr;
        private readonly List<Port.Bitmap> bitmaps = new List<Port.Bitmap>();
        private readonly Port.Cells key = Port.NewCells();
        private readonly Queue<object> done = new Queue<object>();

        /// <summary>Indexes a transcript.</summary>
        public Replayer(Transcript t) {
            tr = t.Kin();
            emon.Transcript.TrIndex(tr);
            foreach (Entry e in t.Entries) {
                bitmaps.Add(e.Bitmap);
            }
        }

        /// <summary>How many recorded entries the run never asked for.</summary>
        public int Unused() {
            return emon.Transcript.TrUnused(tr);
        }

        /// <summary>The end of the run: a `transcript-bad` error, with kin's sentence, when it left recorded entries unasked (a transcript of another run).</summary>
        public void Finish() {
            if (emon.Transcript.TrUnused(tr) == 0) {
                return;
            }
            var sb = new System.Text.StringBuilder();
            emon.Transcript.TrUnusedMsg(tr, sb);
            throw Bad(sb.ToString());
        }

        public void Start(Request req, Dictionary<int, Port.Bitmap> bm) {
            Port.CellsDrop(key, 0);
            foreach (int v in req.Rec) {
                Port.CellsPush(key, v);
            }
            int e = emon.Transcript.TrTake(tr, key, 0, req.Rec.Length);
            if (e >= 0) {
                int an = emon.Transcript.TrEAns(tr, e);
                done.Enqueue(new Answer(Vec(tr.Ents, an, an + emon.Transcript.TrEAnsLen(tr, e)), bitmaps[emon.Transcript.TrEN(tr, e)]));
            } else {
                var sb = new System.Text.StringBuilder();
                emon.Transcript.TrMissMsg(key, 0, req.Rec.Length, sb);
                done.Enqueue(new PlanError(PeName(Planloop.PeTranscriptMiss()), sb.ToString()));
            }
        }

        public Answer Next() {
            object d = done.Count > 0 ? done.Dequeue() : null;
            if (d is PlanError pe) {
                throw pe;
            }
            return (Answer) d;
        }
    }
}
