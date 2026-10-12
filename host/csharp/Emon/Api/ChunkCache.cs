// THE SOURCE CACHE, C# host (DEVLOG P9.2, P10.8; SPEC 13.1; D058). The twin of `host/java/emon/api/ChunkCache.java`, of Go's `chunkCache`
// (`host/go/engine.go`) and of `host/rust/src/engine.rs`.
//
// A `Port.ISrcBacking` over an `IByteSource`: aligned chunks (SPEC 13.1), resident or not. SYNC: a read of a chunk that is not resident fills it inline and
// answers. SIMULATED ASYNC: it records the chunk as pending and misses; `Fetch` (what the loop's `bytes` request does) makes the pending chunks resident with
// a read-ahead that doubles per round, coalescing a run of chunks into one read of the source. A read that fails, or returns fewer bytes than the size said,
// is remembered as `source-changed`.
//
// WHAT TO FETCH IS KIN'S (`fp-plan`, P10.8): the host says what was missed (in ascending order), what was hinted and which chunks are resident, and takes the
// ranges. The C# `ChunkCache.Take` of the first host (its own copy of the planner) is gone.

namespace emon.Api;

using System.Collections.Generic;

internal sealed class ChunkCache : emon.Port.ISrcBacking, emon.Planrun.IMisses {
    internal readonly IByteSource Src;
    internal readonly int N, Chunk;
    internal readonly bool Deferred;
    internal readonly int Input;

    private readonly byte[][] chunks;
    // 1 for each resident chunk, for kin's planner of the fetches (`fp-plan`)
    private readonly emon.Port.Cells res = emon.Port.NewCells();
    private readonly SortedSet<int> pending = new SortedSet<int>();
    private readonly SortedSet<int> wanted = new SortedSet<int>();
    private string failure = "";
    private bool failed, shortRead;
    private long bytes;
    private int reads;
    private readonly object gate = new object();

    internal ChunkCache(IByteSource src, int n, int chunk, bool deferred, int input) {
        this.Src = src;
        this.N = n;
        this.Chunk = chunk;
        this.Deferred = deferred;
        this.Input = input;
        int nc = (int) (((long) n + chunk - 1) / chunk);
        this.chunks = new byte[nc][];
        for (int i = 0; i < nc; i++) {
            emon.Port.CellsPush(res, 0);
        }
    }

    private int Nchunks() {
        return chunks.Length;
    }

    // reads chunks first..=last from the source into the cache, in one read of the source (lock held)
    private bool Load(int first, int last) {
        long lo = (long) first * Chunk;
        long hi = System.Math.Min((long) (last + 1) * Chunk, N);
        byte[] buf = new byte[(int) (hi - lo)];
        int got = 0;
        while (got < buf.Length) {
            int k;
            try {
                k = Src.ReadAt(lo + got, buf, got, buf.Length - got);
            } catch (System.Exception e) {
                failed = true;
                failure = string.IsNullOrEmpty(e.Message) ? e.ToString() : e.Message;
                return false;
            }
            if (k <= 0) {
                shortRead = true;
                failed = true;
                failure = "the source gave " + got + " bytes at offset " + lo + " and its length said " + buf.Length;
                return false;
            }
            got += k;
        }
        reads++;
        bytes += buf.Length;
        for (int k = first; k <= last; k++) {
            int a = (int) ((long) k * Chunk - lo);
            int b = System.Math.Min(a + Chunk, buf.Length);
            var c = new byte[b - a];
            System.Array.Copy(buf, a, c, 0, b - a);
            chunks[k] = c;
            emon.Port.CellsSet(res, k, 1);
        }
        return true;
    }

    // true when chunk k is resident now (a sync cache fills it here; an async one records the miss)
    private bool Touch(int k) {
        if (chunks[k] != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        if (Deferred) {
            pending.Add(k);
            return false;
        }
        return Load(k, k);
    }

    private static emon.Port.Cells Seeds(SortedSet<int> set) {
        emon.Port.Cells o = emon.Port.NewCells();
        foreach (int k in set) {
            emon.Port.CellsPush(o, k);
        }
        return o;
    }

    /// <summary>The ranges to fetch for fetch round `round`. THE PLAN IS KIN'S (`fp-plan`): the host says what was missed and what is resident, and takes the ranges.</summary>
    public List<emon.Planrun.Range> Take(int round) {
        lock (gate) {
            emon.Port.Cells pend = Seeds(pending), hint = Seeds(wanted);
            pending.Clear();
            wanted.Clear();
            emon.Port.Cells o = emon.Port.NewCells();
            emon.Planloop.FpPlan(pend, hint, res, Chunk, N, Input, round, o);
            var ranges = new List<emon.Planrun.Range>();
            for (int i = 0; i < emon.Port.CellsLen(o) / 3; i++) {
                ranges.Add(new emon.Planrun.Range(emon.Port.CellsAt(o, 3 * i), emon.Port.CellsAt(o, 3 * i + 1), emon.Port.CellsAt(o, 3 * i + 2)));
            }
            return ranges;
        }
    }

    /// <summary>Makes [offset, offset + length) resident: what a `bytes` request does. False is the source's failure.</summary>
    internal bool Fetch(int offset, int length) {
        lock (gate) {
            if (length <= 0) {
                return true;
            }
            int first = offset / Chunk;
            int last = (int) (((long) offset + length - 1) / Chunk);
            for (int k = first; k <= last;) {
                if (chunks[k] != null) {
                    k++;
                    continue;
                }
                int e = k;
                while (e < last && chunks[e + 1] == null) {
                    e++;
                }
                if (!Load(k, e)) {
                    return false;
                }
                k = e + 1;
            }
            return true;
        }
    }

    /// <summary>The source's failure, if it had one: null, or {short read?, message}.</summary>
    internal object[] Fail() {
        lock (gate) {
            return failed ? new object[] {shortRead, failure} : null;
        }
    }

    internal long[] Stats() {
        lock (gate) {
            return new long[] {reads, bytes};
        }
    }

    /// <summary>SPEC 13.1's intake round 0: the first and the last chunk (the header, `startxref`, the trailer).</summary>
    internal void PrefetchEnds() {
        if (N == 0) {
            return;
        }
        int nc = Nchunks();
        Fetch(0, System.Math.Min(Chunk, N));
        Fetch((nc - 1) * Chunk, N - (nc - 1) * Chunk);
    }

    public int Len() {
        return N;
    }

    public int At(int i) {
        lock (gate) {
            int k = i / Chunk;
            if (!Touch(k)) {
                return -1;
            }
            return chunks[k][i - k * Chunk];
        }
    }

    public bool Span(int s, byte[] buf) {
        lock (gate) {
            if (buf.Length == 0) {
                return true;
            }
            int first = s / Chunk;
            int last = (int) (((long) s + buf.Length - 1) / Chunk);
            bool ok = true;
            for (int k = first; k <= last; k++) {
                if (Touch(k)) {
                    byte[] ch = chunks[k];
                    int lo = System.Math.Max(s, k * Chunk);
                    int hi = System.Math.Min(s + buf.Length, k * Chunk + ch.Length);
                    System.Array.Copy(ch, lo - k * Chunk, buf, lo - s, hi - lo);
                } else {
                    ok = false;
                }
            }
            return ok;
        }
    }

    public void Hint(int s, int e) {
        lock (gate) {
            if (Deferred && e > s && N > 0) {
                int first = System.Math.Max(s, 0) / Chunk;
                int last = System.Math.Min(e - 1, N - 1) / Chunk;
                for (int k = first; k <= last; k++) {
                    if (chunks[k] == null) {
                        wanted.Add(k);
                    }
                }
            }
        }
    }

    /// <summary>An in-memory source, read in place: no cache, no copy, no miss.</summary>
    internal sealed class SliceBacking : emon.Port.ISrcBacking {
        private readonly byte[] b;

        internal SliceBacking(byte[] b) {
            this.b = b;
        }

        public int Len() {
            return b.Length;
        }

        public int At(int i) {
            return b[i];
        }

        public bool Span(int s, byte[] buf) {
            System.Array.Copy(b, s, buf, 0, buf.Length);
            return true;
        }

        public void Hint(int s, int e) {
        }
    }

    /// <summary>A byte source over a span of an input's byte source: what the decode request names (input, offset, length), read in place.</summary>
    internal sealed class SpanSource : IByteSource {
        private readonly IByteSource src;
        private readonly long off, len;

        internal SpanSource(IByteSource src, long off, long len) {
            this.src = src;
            this.off = off;
            this.len = len;
        }

        public long Size() {
            return len;
        }

        public int ReadAt(long o, byte[] buf, int boff, int n) {
            if (o >= len) {
                return -1;
            }
            int k = (int) System.Math.Min(n, len - o);
            return src.ReadAt(off + o, buf, boff, k);
        }

        public byte[] AsSlice() {
            return null;
        }
    }
}
