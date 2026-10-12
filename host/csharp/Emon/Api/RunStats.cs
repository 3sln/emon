// What the loop did, for the driver and the async gate. Not part of the Model (the Model is the same whichever way the
// bytes arrived). The twin of Go's `RunStats`.

namespace emon.Api;

public sealed class RunStats {
    public int Attempts, FetchRounds, BytesRequests, Requests;
    public long SourceReads, BytesFetched, InputBytes;
    /// <summary>Per page unit (P7.U): the rounds it took and the requests it made, in page order.</summary>
    public readonly System.Collections.Generic.List<int> PageRounds = new System.Collections.Generic.List<int>();
    public readonly System.Collections.Generic.List<int> PageRequests = new System.Collections.Generic.List<int>();
    /// <summary>
    /// P7.S (D210): wall time in microseconds. FulfilUs is what the host's ports took, summed over every request of the input, by request kind (index: 1 render,
    /// 2 decode, 3 `ocr` of a page's first pass, 4 bytes, 5 crop, 6 decode-img; 7 the `ocr` requests of every later pass). PassUs is the document pass (a loop of
    /// its own), PagesUs every page unit with the ports' time in it, FinishUs the document unit (stage 3). Counters: the Model never depends on them.
    /// </summary>
    public readonly long[] FulfilUs = new long[8];
    public long PassUs, PagesUs, FinishUs;
}
