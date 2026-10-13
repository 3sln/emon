// PARALLEL EXECUTION, Java host (DEVLOG P10.2, D1300-D1303; SPEC 12.1). Its Rust twin is `host/rust/src/par.rs`: read that for the reasoning.
//
// WHAT RUNS IN PARALLEL is the host's work, never kin's: a request's fulfilment (a render, a decode, the OCR of a page, a crop), on a bounded pool of
// `workers` threads. Kin stays single-threaded and runs in the order it always did; the plan machine is the one it always was; the Model, the transcript
// and the accounting are the serial run's, byte for byte, at any number of workers. A round's requests run at once and their answers are taken in the order
// they were started; and while the loop is on page p the host runs DRY attempts (`pu-dry`: the part of an attempt that precedes its first write) of the pages
// ahead, has what they ask answered, and keeps the answers by the request's record, so that a unit which later asks the same request is answered by that job.
// A dry chain never writes, runs on the thread that runs the loop (a worker never touches kin or the document), and a failure in it ends it and is dropped: the
// real unit meets the same condition at its own turn.
//
// THE LIMIT OVERRIDES of a conversion are process-wide here (D436): `Port.withLimits` holds a lock exclusively for a conversion that has overrides, so no other
// conversion runs while it does, and its workers, which run inside that scope and are all finished before it ends ({@link #close}), read the same table.
// A worker never takes the lock itself (a read of a limit is a volatile read): taking it would wait on the conversion that holds it.

package emon.api;

import emon.Planrun;
import emon.Port;
import emon.RenderPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class Par {
    /** What a worker runs: one request to its answer. The port set of one conversion, callable from any thread (the same routing as {@code Ports.Live}). */
    static final class Exec {
        final RenderPort.Fulfiller render;
        final Planrun.Fn local;
        final boolean ocr;

        Exec(RenderPort.Fulfiller render, Planrun.Fn local, boolean ocr) {
            this.render = render;
            this.local = local;
            this.ocr = ocr;
        }

        Planrun.Answer run(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            if (render != null && Planrun.routesToRenderer(req.kind(), ocr)) {
                return render.fulfil(req);
            }
            return local.apply(req, bm);
        }
    }

    /** A dry attempt of a page unit: {@code Units.unitPage} with {@code dry}, answering a unit status. */
    interface Dry {
        int run(int page, Port.Cells answers, Port.Cells rq, Port.BitmapSet bms, Port.Buf payload);
    }

    /** One request on its way to an answer, shared by the loop's thread (which wants it), a dry chain (which asked first) and a worker. */
    static final class Job {
        final Planrun.Request req;
        final Map<Integer, Port.Bitmap> bm;
        final AtomicBoolean taken = new AtomicBoolean();
        private boolean done, panicked;
        private Planrun.Answer ans;
        private Planrun.PlanError err;

        Job(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            this.req = req;
            this.bm = bm;
        }

        synchronized boolean isDone() {
            return done;
        }

        void run(Exec x) {
            Planrun.Answer a = null;
            Planrun.PlanError e = null;
            boolean p = false;
            try {
                a = x.run(req, bm);
            } catch (Planrun.PlanError pe) {
                e = pe;
            } catch (Throwable t) {
                p = true;
            }
            synchronized (this) {
                ans = a;
                err = e;
                panicked = p;
                done = true;
            }
        }
    }

    // a request's record as a map key
    private static final class Key {
        final int[] rec;

        Key(int[] rec) {
            this.rec = rec;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && Arrays.equals(rec, ((Key) o).rec);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(rec);
        }
    }

    // a dry chain: one page ahead of the loop, asked round by round in a machine of its own (its accounting is not the run's)
    private static final class Scout {
        final Planrun.Machine m;
        // every request this chain has asked, by its record
        final Map<Key, Job> jobs = new HashMap<>();
        // the round in flight, in id order
        final List<Job> waiting = new ArrayList<>();
        boolean done;

        Scout(Planrun.Config cfg) {
            this.m = new Planrun.Machine(cfg);
        }
    }

    private final Exec exec;
    private final Planrun.Config pcfg;
    private final int ahead;
    private final Object mu = new Object();
    private final ArrayDeque<Job> urgent = new ArrayDeque<>(), later = new ArrayDeque<>();
    private int events;
    private boolean stop;
    private final List<Thread> threads = new ArrayList<>();
    private final TreeMap<Integer, Scout> scouts = new TreeMap<>();
    // the page unit the loop is on (-1 outside the page units), how many there are, and a dry attempt of one of them: set by the engine's loop
    int cur = -1, pages;
    Dry dry;

    private final int workers;

    Par(Exec exec, int workers, int ahead, Planrun.Config pcfg) {
        this.exec = exec;
        this.workers = workers;
        this.ahead = ahead;
        this.pcfg = pcfg;
    }

    // One more worker, while there are fewer than `workers`: the pool's threads are started as jobs arrive, one a job, so a document that asks nothing of any
    // port (a born-digital one under `ocr: auto`) starts none. Called by the loop's thread only.
    private void grow() {
        if (threads.size() < workers) {
            Thread t = new Thread(null, this::worker, "emon-par-" + threads.size(), 64L << 20);
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
    }

    private void worker() {
        while (true) {
            Job job;
            synchronized (mu) {
                while (true) {
                    if (stop) {
                        return;
                    }
                    job = urgent.poll();
                    if (job == null) {
                        job = later.poll();
                    }
                    if (job != null) {
                        break;
                    }
                    try {
                        mu.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            // a job the loop asked for after a dry chain queued it is in the queue twice: the first worker to reach it runs it
            if (!job.taken.compareAndSet(false, true)) {
                continue;
            }
            job.run(exec);
            synchronized (mu) {
                events++;
                mu.notifyAll();
            }
        }
    }

    /** Stops the workers once they have finished the job each is on: no thread of the pool outlives the conversion (and so its limit scope). */
    void close() {
        synchronized (mu) {
            stop = true;
            mu.notifyAll();
        }
        for (Thread t : threads) {
            boolean interrupted = false;
            while (true) {
                try {
                    t.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Job submit(Planrun.Request req, Map<Integer, Port.Bitmap> bm, boolean isUrgent) {
        grow();
        Job job = new Job(req, bm);
        synchronized (mu) {
            (isUrgent ? urgent : later).add(job);
            mu.notifyAll();
        }
        return job;
    }

    // the job that answers req: the one a dry chain of the page the loop is on already asked (moved up the queue if it has not started), or a new one
    private Job want(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
        if (cur >= 0) {
            Scout s = scouts.get(cur);
            Job job = s == null ? null : s.jobs.get(new Key(req.rec));
            if (job != null) {
                if (!job.isDone() && !job.taken.get()) {
                    synchronized (mu) {
                        urgent.add(job);
                        mu.notifyAll();
                    }
                }
                return job;
            }
        }
        return submit(req, new HashMap<>(bm), true);
    }

    // moves every dry chain on as far as the answers in hand allow, and starts those for the pages ahead of the loop
    private void pump() {
        if (cur < 0 || dry == null) {
            scouts.clear();
            return;
        }
        scouts.headMap(cur, false).clear();
        int last = Math.min(cur + ahead, pages - 1);
        for (int page = cur + 1; page <= last; page++) {
            scouts.computeIfAbsent(page, k -> new Scout(pcfg));
        }
        // the nearest page first: its jobs are the ones the loop will want soonest
        for (Map.Entry<Integer, Scout> e : new ArrayList<>(scouts.entrySet())) {
            advance(e.getKey(), e.getValue());
        }
    }

    // one dry chain, as far as it goes without waiting
    private void advance(int page, Scout s) {
        while (!s.done) {
            if (!s.waiting.isEmpty()) {
                for (Job j : s.waiting) {
                    if (!j.isDone()) {
                        return;
                    }
                }
                boolean ok = true;
                try {
                    for (Job j : s.waiting) {
                        synchronized (j) {
                            if (j.panicked || j.err != null) {
                                ok = false;
                            } else if (ok) {
                                s.m.offer(j.ans);
                            }
                        }
                    }
                    if (ok) {
                        s.m.commit();
                    }
                } catch (Planrun.PlanError e) {
                    ok = false;
                }
                if (!ok) {
                    s.done = true;
                    return;
                }
                s.waiting.clear();
            }
            Planrun.Next next;
            try {
                next = s.m.attempt((answers, rq, bms, payload) -> dry.run(page, answers, rq, bms, payload), Planrun.NO_MISSES);
            } catch (Planrun.PlanError e) {
                s.done = true;
                return;
            }
            if (next != Planrun.Next.REQUESTS) {
                s.done = true;
                return;
            }
            Map<Integer, Port.Bitmap> all = new HashMap<>(s.m.bitmaps());
            for (Planrun.Request r : new ArrayList<>(s.m.requests())) {
                Key key = new Key(r.rec);
                Job job = s.jobs.get(key);
                if (job == null) {
                    job = submit(r, all, false);
                    s.jobs.put(key, job);
                }
                s.waiting.add(job);
            }
        }
    }

    // the answer of job, once it has run: the loop's thread keeps the dry chains moving while it waits
    Planrun.Answer await(Job job) {
        while (true) {
            pump();
            boolean done, panicked;
            Planrun.PlanError err;
            Planrun.Answer ans;
            synchronized (job) {
                done = job.done;
                panicked = job.panicked;
                err = job.err;
                ans = job.ans;
            }
            if (done) {
                if (panicked) {
                    // a panic is the loop's own, in its own place: run the request here
                    return exec.run(job.req, job.bm);
                }
                if (err != null) {
                    throw err;
                }
                return ans;
            }
            synchronized (mu) {
                if (events == 0 && !job.isDone()) {
                    try {
                        mu.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw Planrun.fulfilFailed("the run was interrupted");
                    }
                }
                events = 0;
            }
        }
    }

    /** The round's fulfiller: {@code Machine.fulfil} starts every request of the round and then takes every completion in order. */
    static final class Round implements Planrun.Fulfiller {
        private final Par pool;
        private final ArrayDeque<Job> started = new ArrayDeque<>();

        Round(Par pool) {
            this.pool = pool;
        }

        public void start(Planrun.Request req, Map<Integer, Port.Bitmap> bm) {
            started.add(pool.want(req, bm));
        }

        public Planrun.Answer next() {
            Job job = started.poll();
            if (job == null) {
                throw Planrun.fulfilFailed("next without a started request");
            }
            return pool.await(job);
        }
    }
}
