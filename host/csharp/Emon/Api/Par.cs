// PARALLEL EXECUTION, C# host (DEVLOG P10.2, D1300-D1303; SPEC 12.1). Its Rust twin is `host/rust/src/par.rs`: read that for the reasoning.
//
// WHAT RUNS IN PARALLEL is the host's work, never kin's: a request's fulfilment (a render, a decode, the OCR of a page, a crop), on a bounded pool of
// `workers` threads. Kin stays single-threaded and runs in the order it always did; the plan machine is the one it always was; the Model, the transcript
// and the accounting are the serial run's, byte for byte, at any number of workers. A round's requests run at once and their answers are taken in the order
// they were started; and while the loop is on page p the host runs DRY attempts (`pu-dry`: the part of an attempt that precedes its first write) of the pages
// ahead, has what they ask answered, and keeps the answers by the request's record, so that a unit which later asks the same request is answered by that job.
// A dry chain never writes, runs on the thread that runs the loop (a worker never touches kin or the document), and a failure in it ends it and is dropped: the
// real unit meets the same condition at its own turn.
//
// THE LIMIT OVERRIDES of a conversion are process-wide here (D436): `Port.WithLimits` holds a lock exclusively for a conversion that has overrides, so no other
// conversion runs while it does, and its workers, which run inside that scope and are all finished before it ends (`Close`), read the same table. A worker
// never takes the lock itself (a read of a limit is a volatile read): taking it would wait on the conversion that holds it.

namespace emon.Api;

using System.Collections.Generic;

internal sealed class Par {
    /// <summary>What a worker runs: one request to its answer. The port set of one conversion, callable from any thread (the same routing as `Ports.Live`).</summary>
    internal sealed class Exec {
        private readonly emon.RenderPort.Fulfiller render;
        private readonly emon.Planrun.Fn local;
        private readonly bool ocr;

        internal Exec(emon.RenderPort.Fulfiller render, emon.Planrun.Fn local, bool ocr) {
            this.render = render;
            this.local = local;
            this.ocr = ocr;
        }

        internal emon.Planrun.Answer Run(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
            if (render != null && emon.Planrun.RoutesToRenderer(req.Kind(), ocr)) {
                return render.Fulfil(req);
            }
            return local(req, bm);
        }
    }

    /// <summary>A dry attempt of a page unit: `Units.UnitPage` with `dry`, answering a unit status.</summary>
    internal delegate int Dry(int page, emon.Port.Cells answers, emon.Port.Cells rq, emon.Port.BitmapSet bms, emon.Port.Buf payload);

    /// <summary>One request on its way to an answer, shared by the loop's thread (which wants it), a dry chain (which asked first) and a worker.</summary>
    internal sealed class Job {
        internal readonly emon.Planrun.Request Req;
        internal readonly Dictionary<int, emon.Port.Bitmap> Bm;
        private int taken;
        private readonly object gate = new object();
        private bool done, panicked;
        private emon.Planrun.Answer ans;
        private emon.Planrun.PlanError err;

        internal Job(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
            Req = req;
            Bm = bm;
        }

        /// <summary>True for the first caller only: the job is run once.</summary>
        internal bool TryTake() {
            return System.Threading.Interlocked.Exchange(ref taken, 1) == 0;
        }

        internal bool Taken() {
            return System.Threading.Volatile.Read(ref taken) != 0;
        }

        internal bool IsDone() {
            lock (gate) {
                return done;
            }
        }

        /// <summary>The state once done: the answer, the loop's error, or that it panicked.</summary>
        internal bool Result(out emon.Planrun.Answer a, out emon.Planrun.PlanError e, out bool p) {
            lock (gate) {
                a = ans;
                e = err;
                p = panicked;
                return done;
            }
        }

        internal void Run(Exec x) {
            emon.Planrun.Answer a = null;
            emon.Planrun.PlanError e = null;
            bool p = false;
            try {
                a = x.Run(Req, Bm);
            } catch (emon.Planrun.PlanError pe) {
                e = pe;
            } catch (System.Exception) {
                p = true;
            }
            lock (gate) {
                ans = a;
                err = e;
                panicked = p;
                done = true;
            }
        }
    }

    // a request's record as a map key
    private readonly struct Key : System.IEquatable<Key> {
        private readonly int[] rec;

        internal Key(int[] rec) {
            this.rec = rec;
        }

        public bool Equals(Key o) {
            return System.MemoryExtensions.SequenceEqual(new System.ReadOnlySpan<int>(rec), new System.ReadOnlySpan<int>(o.rec));
        }

        public override bool Equals(object o) {
            return o is Key k && Equals(k);
        }

        public override int GetHashCode() {
            var h = new System.HashCode();
            foreach (int v in rec) {
                h.Add(v);
            }
            return h.ToHashCode();
        }
    }

    // a dry chain: one page ahead of the loop, asked round by round in a machine of its own (its accounting is not the run's)
    private sealed class Scout {
        internal readonly emon.Planrun.Machine M;
        // every request this chain has asked, by its record
        internal readonly Dictionary<Key, Job> Jobs = new Dictionary<Key, Job>();
        // the round in flight, in id order
        internal readonly List<Job> Waiting = new List<Job>();
        internal bool Done;

        internal Scout(emon.Planrun.Config cfg) {
            M = new emon.Planrun.Machine(cfg);
        }
    }

    private readonly Exec exec;
    private readonly emon.Planrun.Config pcfg;
    private readonly int ahead;
    private readonly object mu = new object();
    private readonly Queue<Job> urgent = new Queue<Job>(), later = new Queue<Job>();
    private int events;
    private bool stop;
    private readonly List<System.Threading.Thread> threads = new List<System.Threading.Thread>();
    private readonly SortedDictionary<int, Scout> scouts = new SortedDictionary<int, Scout>();
    // the page unit the loop is on (-1 outside the page units), how many there are, and a dry attempt of one of them: set by the engine's loop
    internal int Cur = -1, Pages;
    internal Dry DryAttempt;

    private readonly int workers;

    internal Par(Exec exec, int workers, int ahead, emon.Planrun.Config pcfg) {
        this.exec = exec;
        this.workers = workers;
        this.ahead = ahead;
        this.pcfg = pcfg;
    }

    // One more worker, while there are fewer than `workers`: the pool's threads are started as jobs arrive, one a job, so a document that asks nothing of any
    // port (a born-digital one under `ocr: auto`) starts none. Called by the loop's thread only.
    private void Grow() {
        if (threads.Count < workers) {
            var t = new System.Threading.Thread(Worker, 64 << 20);
            t.Name = "emon-par-" + threads.Count;
            t.IsBackground = true;
            threads.Add(t);
            t.Start();
        }
    }

    private void Worker() {
        while (true) {
            Job job;
            lock (mu) {
                while (true) {
                    if (stop) {
                        return;
                    }
                    if (urgent.Count > 0) {
                        job = urgent.Dequeue();
                        break;
                    }
                    if (later.Count > 0) {
                        job = later.Dequeue();
                        break;
                    }
                    System.Threading.Monitor.Wait(mu);
                }
            }
            // a job the loop asked for after a dry chain queued it is in the queue twice: the first worker to reach it runs it
            if (!job.TryTake()) {
                continue;
            }
            job.Run(exec);
            lock (mu) {
                events++;
                System.Threading.Monitor.PulseAll(mu);
            }
        }
    }

    /// <summary>Stops the workers once they have finished the job each is on: no thread of the pool outlives the conversion (and so its limit scope).</summary>
    internal void Close() {
        lock (mu) {
            stop = true;
            System.Threading.Monitor.PulseAll(mu);
        }
        foreach (System.Threading.Thread t in threads) {
            t.Join();
        }
    }

    private Job Submit(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm, bool isUrgent) {
        Grow();
        var job = new Job(req, bm);
        lock (mu) {
            (isUrgent ? urgent : later).Enqueue(job);
            System.Threading.Monitor.PulseAll(mu);
        }
        return job;
    }

    // the job that answers req: the one a dry chain of the page the loop is on already asked (moved up the queue if it has not started), or a new one
    private Job Want(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
        if (Cur >= 0 && scouts.TryGetValue(Cur, out Scout s) && s.Jobs.TryGetValue(new Key(req.Rec), out Job job)) {
            if (!job.IsDone() && !job.Taken()) {
                lock (mu) {
                    urgent.Enqueue(job);
                    System.Threading.Monitor.PulseAll(mu);
                }
            }
            return job;
        }
        return Submit(req, new Dictionary<int, emon.Port.Bitmap>(bm), true);
    }

    // moves every dry chain on as far as the answers in hand allow, and starts those for the pages ahead of the loop
    private void Pump() {
        if (Cur < 0 || DryAttempt == null) {
            scouts.Clear();
            return;
        }
        var gone = new List<int>();
        foreach (int page in scouts.Keys) {
            if (page < Cur) {
                gone.Add(page);
            }
        }
        foreach (int page in gone) {
            scouts.Remove(page);
        }
        int last = System.Math.Min(Cur + ahead, Pages - 1);
        for (int page = Cur + 1; page <= last; page++) {
            if (!scouts.ContainsKey(page)) {
                scouts[page] = new Scout(pcfg);
            }
        }
        // the nearest page first: its jobs are the ones the loop will want soonest
        foreach (KeyValuePair<int, Scout> e in new List<KeyValuePair<int, Scout>>(scouts)) {
            Advance(e.Key, e.Value);
        }
    }

    // one dry chain, as far as it goes without waiting
    private void Advance(int page, Scout s) {
        while (!s.Done) {
            if (s.Waiting.Count > 0) {
                foreach (Job j in s.Waiting) {
                    if (!j.IsDone()) {
                        return;
                    }
                }
                bool ok = true;
                try {
                    foreach (Job j in s.Waiting) {
                        j.Result(out emon.Planrun.Answer a, out emon.Planrun.PlanError e, out bool p);
                        if (p || e != null) {
                            ok = false;
                        } else if (ok) {
                            s.M.Offer(a);
                        }
                    }
                    if (ok) {
                        s.M.Commit();
                    }
                } catch (emon.Planrun.PlanError) {
                    ok = false;
                }
                if (!ok) {
                    s.Done = true;
                    return;
                }
                s.Waiting.Clear();
            }
            emon.Planrun.Next next;
            try {
                next = s.M.Attempt((answers, rq, bms, payload) => DryAttempt(page, answers, rq, bms, payload), emon.Planrun.NoMisses);
            } catch (emon.Planrun.PlanError) {
                s.Done = true;
                return;
            }
            if (next != emon.Planrun.Next.Requests) {
                s.Done = true;
                return;
            }
            var all = new Dictionary<int, emon.Port.Bitmap>(s.M.Bitmaps());
            foreach (emon.Planrun.Request r in new List<emon.Planrun.Request>(s.M.Requests())) {
                var key = new Key(r.Rec);
                if (!s.Jobs.TryGetValue(key, out Job job)) {
                    job = Submit(r, all, false);
                    s.Jobs[key] = job;
                }
                s.Waiting.Add(job);
            }
        }
    }

    // the answer of job, once it has run: the loop's thread keeps the dry chains moving while it waits
    internal emon.Planrun.Answer Await(Job job) {
        while (true) {
            Pump();
            if (job.Result(out emon.Planrun.Answer ans, out emon.Planrun.PlanError err, out bool panicked)) {
                if (panicked) {
                    // a panic is the loop's own, in its own place: run the request here
                    return exec.Run(job.Req, job.Bm);
                }
                if (err != null) {
                    throw err;
                }
                return ans;
            }
            lock (mu) {
                if (events == 0 && !job.IsDone()) {
                    System.Threading.Monitor.Wait(mu);
                }
                events = 0;
            }
        }
    }

    /// <summary>The round's fulfiller: `Machine.Fulfil` starts every request of the round and then takes every completion in order.</summary>
    internal sealed class Round : emon.Planrun.IFulfiller {
        private readonly Par pool;
        private readonly Queue<Job> started = new Queue<Job>();

        internal Round(Par pool) {
            this.pool = pool;
        }

        public void Start(emon.Planrun.Request req, Dictionary<int, emon.Port.Bitmap> bm) {
            started.Enqueue(pool.Want(req, bm));
        }

        public emon.Planrun.Answer Next() {
            if (started.Count == 0) {
                throw emon.Planrun.FulfilFailed("next without a started request");
            }
            return pool.Await(started.Dequeue());
        }
    }
}
