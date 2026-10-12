// What the loop did, for the driver and the async gate. Not part of the Model (the Model is the same whichever way the bytes arrived). The twin of Go's
// `RunStats`.

package emon.api;

import java.util.ArrayList;
import java.util.List;

public final class RunStats {
    public int attempts, fetchRounds, bytesRequests, requests;
    public long sourceReads, bytesFetched, inputBytes;
    /** Per page unit (P7.U): the rounds it took and the requests it made, in page order. */
    public final List<Integer> pageRounds = new ArrayList<>();
    public final List<Integer> pageRequests = new ArrayList<>();
    /**
     * P7.S (D210): wall time in microseconds. fulfilUs is what the host's ports took, summed over every request of the input, by request kind (index: 1 render,
     * 2 decode, 3 `ocr` of a page's first pass, 4 bytes, 5 crop, 6 decode-img; 7 the `ocr` requests of every later pass). passUs is the document pass (a loop of
     * its own), pagesUs every page unit with the ports' time in it, finishUs the document unit (stage 3). Counters: the Model never depends on them.
     */
    public long[] fulfilUs = new long[8];
    public long passUs, pagesUs, finishUs;
}
