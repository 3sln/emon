// THE SOURCE CACHE, Java host (DEVLOG P9.1, P10.8; SPEC 13.1; D058). The twin of Go's `chunkCache` (`host/go/engine.go`) and of `host/rust/src/engine.rs`.
//
// A `Port.SrcBacking` over a `ByteSource`: aligned chunks (SPEC 13.1), resident or not. SYNC: a read of a chunk that is not resident fills it inline and
// answers. SIMULATED ASYNC: it records the chunk as pending and misses; `fetch` (what the loop's `bytes` request does) makes the pending chunks resident with
// a read-ahead that doubles per round, coalescing a run of chunks into one read of the source. A read that fails, or returns fewer bytes than the size said,
// is remembered as `source-changed`.
//
// WHAT TO FETCH IS KIN'S (`fp-plan`, P10.8): the host says what was missed (in ascending order), what was hinted and which chunks are resident, and takes the
// ranges. The Java `ChunkCache.take` of the first host (its own copy of the planner) is gone.

package emon.api;

import emon.Planloop;
import emon.Planrun;
import emon.Port;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class ChunkCache implements Port.SrcBacking, Planrun.Misses {
    final ByteSource src;
    final int n, chunk;
    final boolean deferred;
    final int input;

    private final byte[][] chunks;
    // 1 for each resident chunk, for kin's planner of the fetches (`fp-plan`)
    private final Port.Cells res = Port.newCells();
    private final TreeSet<Integer> pending = new TreeSet<>();
    private final TreeSet<Integer> wanted = new TreeSet<>();
    private String failure = "";
    private boolean failed, shortRead;
    private long bytes;
    private int reads;

    ChunkCache(ByteSource src, int n, int chunk, boolean deferred, int input) {
        this.src = src;
        this.n = n;
        this.chunk = chunk;
        this.deferred = deferred;
        this.input = input;
        int nc = (int) (((long) n + chunk - 1) / chunk);
        this.chunks = new byte[nc][];
        for (int i = 0; i < nc; i++) {
            Port.cellsPush(res, 0);
        }
    }

    private int nchunks() {
        return chunks.length;
    }

    // reads chunks first..=last from the source into the cache, in one read of the source (lock held)
    private boolean load(int first, int last) {
        long lo = (long) first * chunk;
        long hi = Math.min((long) (last + 1) * chunk, n);
        byte[] buf = new byte[(int) (hi - lo)];
        int got = 0;
        while (got < buf.length) {
            int k;
            try {
                k = src.readAt(lo + got, buf, got, buf.length - got);
            } catch (IOException | RuntimeException e) {
                failed = true;
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
                return false;
            }
            if (k <= 0) {
                shortRead = true;
                failed = true;
                failure = "the source gave " + got + " bytes at offset " + lo + " and its length said " + buf.length;
                return false;
            }
            got += k;
        }
        reads++;
        bytes += buf.length;
        for (int k = first; k <= last; k++) {
            int a = (int) ((long) k * chunk - lo);
            int b = Math.min(a + chunk, buf.length);
            chunks[k] = java.util.Arrays.copyOfRange(buf, a, b);
            Port.cellsSet(res, k, 1);
        }
        return true;
    }

    // true when chunk k is resident now (a sync cache fills it here; an async one records the miss)
    private boolean touch(int k) {
        if (chunks[k] != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        if (deferred) {
            pending.add(k);
            return false;
        }
        return load(k, k);
    }

    private static Port.Cells seeds(TreeSet<Integer> set) {
        Port.Cells out = Port.newCells();
        for (int k : set) {
            Port.cellsPush(out, k);
        }
        return out;
    }

    /** The ranges to fetch for fetch round `round`. THE PLAN IS KIN'S (`fp-plan`): the host says what was missed and what is resident, and takes the ranges. */
    public synchronized List<Planrun.Range> take(int round) {
        Port.Cells pend = seeds(pending), hint = seeds(wanted);
        pending.clear();
        wanted.clear();
        Port.Cells out = Port.newCells();
        Planloop.fpPlan(pend, hint, res, chunk, n, input, round, out);
        List<Planrun.Range> ranges = new ArrayList<>();
        for (int i = 0; i < Port.cellsLen(out) / 3; i++) {
            ranges.add(new Planrun.Range(Port.cellsAt(out, 3 * i), Port.cellsAt(out, 3 * i + 1), Port.cellsAt(out, 3 * i + 2)));
        }
        return ranges;
    }

    /** Makes [offset, offset + length) resident: what a `bytes` request does. False is the source's failure. */
    synchronized boolean fetch(int offset, int length) {
        if (length <= 0) {
            return true;
        }
        int first = offset / chunk;
        int last = (int) (((long) offset + length - 1) / chunk);
        for (int k = first; k <= last;) {
            if (chunks[k] != null) {
                k++;
                continue;
            }
            int e = k;
            while (e < last && chunks[e + 1] == null) {
                e++;
            }
            if (!load(k, e)) {
                return false;
            }
            k = e + 1;
        }
        return true;
    }

    /** The source's failure, if it had one: null, or {short read?, message}. */
    synchronized Object[] fail() {
        return failed ? new Object[] {shortRead, failure} : null;
    }

    synchronized long[] stats() {
        return new long[] {reads, bytes};
    }

    /** SPEC 13.1's intake round 0: the first and the last chunk (the header, `startxref`, the trailer). */
    void prefetchEnds() {
        if (n == 0) {
            return;
        }
        int nc = nchunks();
        fetch(0, Math.min(chunk, n));
        fetch((nc - 1) * chunk, n - (nc - 1) * chunk);
    }

    public int len() {
        return n;
    }

    public synchronized int at(int i) {
        int k = i / chunk;
        if (!touch(k)) {
            return -1;
        }
        return chunks[k][i - k * chunk] & 0xFF;
    }

    public synchronized boolean span(int s, byte[] buf) {
        if (buf.length == 0) {
            return true;
        }
        int first = s / chunk;
        int last = (int) (((long) s + buf.length - 1) / chunk);
        boolean ok = true;
        for (int k = first; k <= last; k++) {
            if (touch(k)) {
                byte[] ch = chunks[k];
                int lo = Math.max(s, k * chunk);
                int hi = Math.min(s + buf.length, k * chunk + ch.length);
                System.arraycopy(ch, lo - k * chunk, buf, lo - s, hi - lo);
            } else {
                ok = false;
            }
        }
        return ok;
    }

    public synchronized void hint(int s, int e) {
        if (deferred && e > s && n > 0) {
            int first = Math.max(s, 0) / chunk;
            int last = Math.min(e - 1, n - 1) / chunk;
            for (int k = first; k <= last; k++) {
                if (chunks[k] == null) {
                    wanted.add(k);
                }
            }
        }
    }

    /** An in-memory source, read in place: no cache, no copy, no miss. */
    static final class SliceBacking implements Port.SrcBacking {
        final byte[] b;

        SliceBacking(byte[] b) {
            this.b = b;
        }

        public int len() {
            return b.length;
        }

        public int at(int i) {
            return b[i] & 0xFF;
        }

        public boolean span(int s, byte[] buf) {
            System.arraycopy(b, s, buf, 0, buf.length);
            return true;
        }

        public void hint(int s, int e) {
        }
    }

    /** A ByteSource over a span of an input's ByteSource: what the decode request names (input, offset, length), read in place. */
    static final class SpanSource implements ByteSource {
        final ByteSource src;
        final long off, len;

        SpanSource(ByteSource src, long off, long len) {
            this.src = src;
            this.off = off;
            this.len = len;
        }

        public long size() {
            return len;
        }

        public int readAt(long o, byte[] buf, int boff, int n) throws IOException {
            if (o >= len) {
                return -1;
            }
            int k = (int) Math.min(n, len - o);
            return src.readAt(off + o, buf, boff, k);
        }
    }
}
