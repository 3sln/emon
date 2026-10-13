// THE JPEG DECODER, C# host (DEVLOG P6.1, P9.2; D187, D190). JPEG is the one codec that stays NATIVE (D190: lossy stays native, and the BCL has none):
// baseline and progressive Huffman, Go's integer inverse DCT, libjpeg's "fancy" chroma upsampling for 4:2:2 / 4:2:0 / 4:4:0 (Go's `imgUpsample`, form for
// form). It is the first host's decoder, kept as it was; the first host's zlib, PNG and TIFF container code are gone (kin's: D456, D457). A part of
// `ImgDec`.

namespace emon.Api;

using System;

public static partial class ImgDec {
    // ratios: 0 = 4:4:4, 1 = 4:2:2, 2 = 4:2:0, 3 = 4:4:0, 4 = 4:1:1, 5 = 4:1:0
    // Brings a subsampled chroma plane to the size of the image with libjpeg's "fancy" (triangle) filters for 4:2:2,
    // 4:2:0 and 4:4:0 (Go's imgUpsample, form for form); other ratios are replicated.
    private static byte[] Upsample(byte[] p, int stride, int w, int h, int r) {
        byte[] outb = new byte[w * h];
        int cw = w, chh = h;
        switch (r) {
            case 1: cw = (w + 1) / 2; break;
            case 2: cw = (w + 1) / 2; chh = (h + 1) / 2; break;
            case 3: chh = (h + 1) / 2; break;
            case 4: cw = (w + 3) / 4; break;
            case 5: cw = (w + 3) / 4; chh = (h + 1) / 2; break;
        }
        if (r == 0) {
            for (int y = 0; y < h; y++) {
                Array.Copy(p, y * stride, outb, y * w, w);
            }
        } else if ((r == 1 || r == 2) && cw > 2) {
            int[] row = new int[cw];
            for (int y = 0; y < h; y++) {
                int y0 = y / 2;
                if (r == 1) {
                    y0 = y;
                }
                int y1 = y0;
                if (r == 2) {
                    if (y % 2 == 0) {
                        y1 = y0 - 1;
                    } else {
                        y1 = y0 + 1;
                    }
                    if (y1 < 0) {
                        y1 = 0;
                    }
                    if (y1 >= chh) {
                        y1 = chh - 1;
                    }
                }
                int ob = y * w;
                if (r == 1) {
                    for (int x = 0; x < cw; x++) {
                        row[x] = p[y0 * stride + x];
                    }
                    Put(outb, ob, w, 0, row[0]);
                    Put(outb, ob, w, 1, (row[0] * 3 + row[1] + 2) >> 2);
                    for (int x = 1; x < cw - 1; x++) {
                        Put(outb, ob, w, 2 * x, (row[x] * 3 + row[x - 1] + 1) >> 2);
                        Put(outb, ob, w, 2 * x + 1, (row[x] * 3 + row[x + 1] + 2) >> 2);
                    }
                    Put(outb, ob, w, 2 * cw - 2, (row[cw - 1] * 3 + row[cw - 2] + 1) >> 2);
                    Put(outb, ob, w, 2 * cw - 1, row[cw - 1]);
                    continue;
                }
                for (int x = 0; x < cw; x++) {
                    row[x] = p[y0 * stride + x] * 3 + p[y1 * stride + x];
                }
                Put(outb, ob, w, 0, (row[0] * 4 + 8) >> 4);
                Put(outb, ob, w, 1, (row[0] * 3 + row[1] + 7) >> 4);
                for (int x = 1; x < cw - 1; x++) {
                    Put(outb, ob, w, 2 * x, (row[x] * 3 + row[x - 1] + 8) >> 4);
                    Put(outb, ob, w, 2 * x + 1, (row[x] * 3 + row[x + 1] + 7) >> 4);
                }
                Put(outb, ob, w, 2 * cw - 2, (row[cw - 1] * 3 + row[cw - 2] + 8) >> 4);
                Put(outb, ob, w, 2 * cw - 1, (row[cw - 1] * 4 + 7) >> 4);
            }
        } else if (r == 3 && chh > 2) {
            for (int y = 0; y < h; y++) {
                int y0 = y / 2;
                int y1 = y0 - 1, bias = 1;
                if (y % 2 == 1) {
                    y1 = y0 + 1;
                    bias = 2;
                }
                if (y1 < 0) {
                    y1 = 0;
                }
                if (y1 >= chh) {
                    y1 = chh - 1;
                }
                for (int x = 0; x < w; x++) {
                    outb[y * w + x] = (byte)((p[y0 * stride + x] * 3 + p[y1 * stride + x] + bias) >> 2);
                }
            }
        } else {
            // replicated: 4:1:1, 4:1:0, and a plane too small for the filter
            int fx = 1, fy = 1;
            switch (r) {
                case 1: fx = 2; break;
                case 2: fx = 2; fy = 2; break;
                case 3: fy = 2; break;
                case 4: fx = 4; break;
                case 5: fx = 4; fy = 2; break;
            }
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    outb[y * w + x] = p[(y / fy) * stride + x / fx];
                }
            }
        }
        return outb;
    }

    private static void Put(byte[] o, int ob, int w, int x, int v) {
        if (x < w) {
            o[ob + x] = (byte)v;
        }
    }

    // ---- the JPEG decoder: baseline and progressive Huffman, Go's integer inverse DCT

    private sealed class JpegComp {
        public int Id, H, V, Tq;
        public int Bw, Bh, Stride;
        public short[] Coef; // progressive: the coefficients, natural order, not yet dequantised
        public byte[] Plane;
        public int Pred;
        public int Td, Ta;
    }

    private sealed class JpegImage {
        public int W, H, Hmax, Vmax;
        public JpegComp[] Comps;
    }

    private sealed class JHuff {
        public readonly int[] MinCode = new int[17], MaxCode = new int[18], ValPtr = new int[17];
        public byte[] Vals;
        public bool Set;
    }

    private static readonly byte[] Zig = {
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private sealed class JpegReader {
        public readonly byte[] D;
        public int Pos;
        private int bits, nbits;
        public int EobRun;

        public JpegReader(byte[] d) {
            D = d;
        }

        public void ResetBits() {
            bits = 0;
            nbits = 0;
            EobRun = 0;
        }

        public int Bit() {
            if (nbits == 0) {
                if (Pos >= D.Length) {
                    throw Failedf("unexpected EOF in a JPEG");
                }
                int c = D[Pos++];
                if (c == 0xFF) {
                    if (Pos >= D.Length) {
                        throw Failedf("unexpected EOF in a JPEG");
                    }
                    if (D[Pos] != 0) {
                        throw Failedf("missing 0xff00 sentinel in a JPEG's Huffman data");
                    }
                    Pos++;
                }
                bits = c;
                nbits = 8;
            }
            nbits--;
            return (bits >> nbits) & 1;
        }

        public int Receive(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | Bit();
            }
            return v;
        }

        public int Huff(JHuff h) {
            if (!h.Set) {
                throw Failedf("a JPEG scan that uses a missing Huffman table");
            }
            int code = Bit();
            int l = 1;
            while (l <= 16 && code > h.MaxCode[l]) {
                code = (code << 1) | Bit();
                l++;
            }
            if (l > 16) {
                throw Failedf("bad Huffman code in a JPEG");
            }
            return h.Vals[h.ValPtr[l] + code - h.MinCode[l]];
        }
    }

    private static int Extend(int v, int t) {
        return v < (1 << (t - 1)) ? v - (1 << t) + 1 : v;
    }

    private static JpegImage JpegRead(byte[] d) {
        var rd = new JpegReader(d);
        var img = new JpegImage();
        var dc = new JHuff[4];
        var ac = new JHuff[4];
        for (int k = 0; k < 4; k++) {
            dc[k] = new JHuff();
            ac[k] = new JHuff();
        }
        var qt = new int[4][];
        int ri = 0;
        bool progressive = false, haveFrame = false, anyScan = false;
        int mcusX = 0, mcusY = 0;
        int pos = 2;
        for (;;) {
            // find the next marker
            while (pos + 1 < d.Length && !(d[pos] == 0xFF && d[pos + 1] != 0 && d[pos + 1] != 0xFF && !(d[pos + 1] >= 0xD0 && d[pos + 1] <= 0xD7))) {
                pos++;
            }
            if (pos + 1 >= d.Length) {
                break;
            }
            int mk = d[pos + 1];
            pos += 2;
            if (mk == 0xD9) {
                break;
            }
            if (mk == 0xD8 || mk == 0x01) {
                continue;
            }
            if (pos + 2 > d.Length) {
                throw Failedf("unexpected EOF in a JPEG");
            }
            int len = U16(d, pos);
            if (len < 2 || pos + len > d.Length) {
                throw Failedf("short JPEG segment");
            }
            int bo = pos + 2, bl = len - 2;
            pos += len;
            switch (mk) {
                case 0xDB: { // DQT
                    int p = bo, e = bo + bl;
                    while (p < e) {
                        int pq = d[p] >> 4, tq = d[p] & 15;
                        p++;
                        if (tq > 3 || pq > 1) {
                            throw Failedf("bad DQT in a JPEG");
                        }
                        int need = pq == 0 ? 64 : 128;
                        if (p + need > e) {
                            throw Failedf("short DQT in a JPEG");
                        }
                        int[] q = new int[64];
                        for (int k = 0; k < 64; k++) {
                            int v = pq == 0 ? d[p + k] : (d[p + 2 * k] << 8) | d[p + 2 * k + 1];
                            q[Zig[k]] = v; // natural order
                        }
                        qt[tq] = q;
                        p += need;
                    }
                    break;
                }
                case 0xC4: { // DHT
                    int p = bo, e = bo + bl;
                    while (p < e) {
                        if (p + 17 > e) {
                            throw Failedf("short DHT in a JPEG");
                        }
                        int tc = d[p] >> 4, th = d[p] & 15;
                        if (tc > 1 || th > 3) {
                            throw Failedf("bad DHT in a JPEG");
                        }
                        int[] cnt = new int[17];
                        int tot = 0;
                        for (int k = 1; k <= 16; k++) {
                            cnt[k] = d[p + k];
                            tot += cnt[k];
                        }
                        p += 17;
                        if (tot > 256 || p + tot > e) {
                            throw Failedf("bad DHT in a JPEG");
                        }
                        JHuff h = tc == 0 ? dc[th] : ac[th];
                        h.Vals = new byte[tot];
                        Array.Copy(d, p, h.Vals, 0, tot);
                        p += tot;
                        int code = 0, kk = 0;
                        for (int l = 1; l <= 16; l++) {
                            h.ValPtr[l] = kk;
                            h.MinCode[l] = code;
                            code += cnt[l];
                            kk += cnt[l];
                            h.MaxCode[l] = cnt[l] > 0 ? code - 1 : -1;
                            code <<= 1;
                        }
                        h.MaxCode[17] = int.MaxValue;
                        h.Set = true;
                    }
                    break;
                }
                case 0xDD: // DRI
                    if (bl < 2) {
                        throw Failedf("bad DRI in a JPEG");
                    }
                    ri = U16(d, bo);
                    break;
                case 0xC0: case 0xC1: case 0xC2: { // SOF
                    if (haveFrame) {
                        throw Failedf("a JPEG with two frame headers");
                    }
                    if (bl < 6) {
                        throw Failedf("short SOF in a JPEG");
                    }
                    if (d[bo] != 8) {
                        // `undecodable`, as the other hosts' decoders say (D496): the one JPEG the port calls `unsupported-image` is a frame with other than one or
                        // three components (CMYK: P6.1a), decided before a decoder is asked; a decoder's refusal of a coding is a damaged image to every host
                        throw Failedf("a JPEG that is not 8-bit");
                    }
                    progressive = mk == 0xC2;
                    img.H = U16(d, bo + 1);
                    img.W = U16(d, bo + 3);
                    int nc = d[bo + 5];
                    if ((nc != 1 && nc != 3 && nc != 4) || bl != 6 + 3 * nc || img.W == 0 || img.H == 0) {
                        throw Failedf("bad SOF in a JPEG");
                    }
                    img.Comps = new JpegComp[nc];
                    for (int k = 0; k < nc; k++) {
                        var c = new JpegComp { Id = d[bo + 6 + 3 * k], H = d[bo + 7 + 3 * k] >> 4, V = d[bo + 7 + 3 * k] & 15, Tq = d[bo + 8 + 3 * k] };
                        if (c.H < 1 || c.H > 4 || c.V < 1 || c.V > 4 || c.Tq > 3) {
                            throw Failedf("bad component in a JPEG");
                        }
                        img.Comps[k] = c;
                        img.Hmax = Math.Max(img.Hmax, c.H);
                        img.Vmax = Math.Max(img.Vmax, c.V);
                    }
                    mcusX = (img.W + 8 * img.Hmax - 1) / (8 * img.Hmax);
                    mcusY = (img.H + 8 * img.Vmax - 1) / (8 * img.Vmax);
                    foreach (JpegComp c in img.Comps) {
                        c.Bw = mcusX * c.H;
                        c.Bh = mcusY * c.V;
                        c.Stride = c.Bw * 8;
                        long sz = (long)c.Bw * c.Bh * 64;
                        if (sz > int.MaxValue / 2) {
                            throw Failedf("a JPEG too large");
                        }
                        c.Plane = new byte[(int)sz];
                        if (progressive) {
                            c.Coef = new short[(int)sz];
                        }
                    }
                    haveFrame = true;
                    break;
                }
                case 0xC3: case 0xC5: case 0xC6: case 0xC7: case 0xC9: case 0xCA: case 0xCB: case 0xCD: case 0xCE: case 0xCF:
                    throw Failedf("this JPEG coding process (lossless, hierarchical or arithmetic)");   // `undecodable` on every host (D496)
                case 0xDA: { // SOS
                    if (!haveFrame) {
                        throw Failedf("a JPEG scan before its frame header");
                    }
                    int ns = d[bo];
                    if (ns < 1 || ns > img.Comps.Length || bl != 4 + 2 * ns) {
                        throw Failedf("bad SOS in a JPEG");
                    }
                    var sc = new JpegComp[ns];
                    for (int k = 0; k < ns; k++) {
                        int cs = d[bo + 1 + 2 * k];
                        JpegComp f = null;
                        foreach (JpegComp c in img.Comps) {
                            if (c.Id == cs) {
                                f = c;
                            }
                        }
                        if (f == null) {
                            throw Failedf("a JPEG scan names an unknown component");
                        }
                        f.Td = d[bo + 2 + 2 * k] >> 4;
                        f.Ta = d[bo + 2 + 2 * k] & 15;
                        if (f.Td > 3 || f.Ta > 3) {
                            throw Failedf("bad SOS in a JPEG");
                        }
                        sc[k] = f;
                    }
                    int ss = d[bo + 1 + 2 * ns], se = d[bo + 2 + 2 * ns], ah = d[bo + 3 + 2 * ns] >> 4, al = d[bo + 3 + 2 * ns] & 15;
                    if (progressive) {
                        if (ss > se || se > 63 || al > 13 || (ss == 0 && se != 0) || (ss > 0 && ns != 1)) {
                            throw Failedf("bad progressive scan parameters in a JPEG");
                        }
                    } else {
                        ss = 0;
                        se = 63;
                        ah = 0;
                        al = 0;
                    }
                    rd.Pos = pos;
                    JpegScan(rd, img, sc, progressive, dc, ac, qt, ri, ss, se, ah, al, mcusX, mcusY);
                    pos = rd.Pos;
                    anyScan = true;
                    break;
                }
                default:
                    break; // APPn, COM and the rest: skipped
            }
        }
        if (!haveFrame || !anyScan) {
            throw Failedf("a JPEG with no scan");
        }
        if (progressive) {
            int[] blk = new int[64];
            foreach (JpegComp c in img.Comps) {
                int[] q = qt[c.Tq];
                if (q == null) {
                    throw Failedf("a JPEG that uses a missing quantisation table");
                }
                for (int by = 0; by < c.Bh; by++) {
                    for (int bx = 0; bx < c.Bw; bx++) {
                        int co = (by * c.Bw + bx) * 64;
                        for (int k = 0; k < 64; k++) {
                            blk[k] = c.Coef[co + k] * q[k];
                        }
                        Idct(blk, c.Plane, by * 8 * c.Stride + bx * 8, c.Stride);
                    }
                }
            }
        }
        return img;
    }

    private static void JpegScan(JpegReader rd, JpegImage img, JpegComp[] sc, bool progressive, JHuff[] dc, JHuff[] ac, int[][] qt,
            int ri, int ss, int se, int ah, int al, int mcusX, int mcusY) {
        rd.ResetBits();
        foreach (JpegComp c in sc) {
            c.Pred = 0;
        }
        int[] blk = new int[64];
        int total, perRow;
        if (sc.Length == 1) {
            JpegComp c = sc[0];
            int cw = (img.W * c.H + img.Hmax - 1) / img.Hmax, chh = (img.H * c.V + img.Vmax - 1) / img.Vmax;
            perRow = (cw + 7) / 8;
            total = perRow * ((chh + 7) / 8);
        } else {
            perRow = mcusX;
            total = mcusX * mcusY;
        }
        int rst = 0;
        for (int m = 0; m < total; m++) {
            if (ri > 0 && m > 0 && m % ri == 0) {
                // restart: byte-align, expect RSTn
                rd.ResetBits();
                if (rd.Pos + 1 >= rd.D.Length || rd.D[rd.Pos] != 0xFF || rd.D[rd.Pos + 1] != 0xD0 + (rst & 7)) {
                    throw Failedf("bad RST marker in a JPEG");
                }
                rd.Pos += 2;
                rst++;
                foreach (JpegComp c in sc) {
                    c.Pred = 0;
                }
            }
            if (sc.Length == 1) {
                JpegComp c = sc[0];
                JpegBlock(rd, c, m % perRow, m / perRow, progressive, dc, ac, qt, blk, ss, se, ah, al);
            } else {
                int mx = m % perRow, my = m / perRow;
                foreach (JpegComp c in sc) {
                    for (int v = 0; v < c.V; v++) {
                        for (int h = 0; h < c.H; h++) {
                            JpegBlock(rd, c, mx * c.H + h, my * c.V + v, progressive, dc, ac, qt, blk, ss, se, ah, al);
                        }
                    }
                }
            }
        }
    }

    private static void JpegBlock(JpegReader rd, JpegComp c, int bx, int by, bool progressive, JHuff[] dc, JHuff[] ac, int[][] qt,
            int[] blk, int ss, int se, int ah, int al) {
        if (!progressive) {
            int[] q = qt[c.Tq];
            if (q == null) {
                throw Failedf("a JPEG that uses a missing quantisation table");
            }
            Array.Clear(blk, 0, 64);
            int t = rd.Huff(dc[c.Td]);
            if (t > 16) {
                throw Failedf("bad Huffman code in a JPEG");
            }
            int diff = t == 0 ? 0 : Extend(rd.Receive(t), t);
            c.Pred += diff;
            blk[0] = c.Pred * q[0];
            for (int k = 1; k < 64;) {
                int rs = rd.Huff(ac[c.Ta]);
                int r = rs >> 4, s = rs & 15;
                if (s == 0) {
                    if (r == 15) {
                        k += 16;
                        continue;
                    }
                    break;
                }
                k += r;
                if (k > 63) {
                    throw Failedf("bad Huffman code in a JPEG");
                }
                blk[Zig[k]] = Extend(rd.Receive(s), s) * q[Zig[k]];
                k++;
            }
            Idct(blk, c.Plane, by * 8 * c.Stride + bx * 8, c.Stride);
            return;
        }
        short[] co = c.Coef;
        int o = (by * c.Bw + bx) * 64;
        if (ss == 0) {
            if (ah == 0) {
                int t = rd.Huff(dc[c.Td]);
                if (t > 16) {
                    throw Failedf("bad Huffman code in a JPEG");
                }
                int diff = t == 0 ? 0 : Extend(rd.Receive(t), t);
                c.Pred += diff;
                co[o] = (short)(c.Pred << al);
            } else if (rd.Bit() != 0) {
                co[o] |= (short)(1 << al);
            }
            return;
        }
        JHuff h = ac[c.Ta];
        if (ah == 0) {
            if (rd.EobRun > 0) {
                rd.EobRun--;
                return;
            }
            for (int k = ss; k <= se;) {
                int rs = rd.Huff(h);
                int r = rs >> 4, s = rs & 15;
                if (s != 0) {
                    k += r;
                    if (k > 63) {
                        throw Failedf("bad Huffman code in a JPEG");
                    }
                    co[o + Zig[k]] = (short)(Extend(rd.Receive(s), s) << al);
                    k++;
                } else {
                    if (r < 15) {
                        rd.EobRun = (1 << r) - 1;
                        if (r > 0) {
                            rd.EobRun += rd.Receive(r);
                        }
                        break;
                    }
                    k += 16;
                }
            }
            return;
        }
        // AC refinement
        int p1 = 1 << al, m1 = -1 << al;
        int kk = ss;
        if (rd.EobRun == 0) {
            for (; kk <= se; kk++) {
                int rs = rd.Huff(h);
                int r = rs >> 4, s = rs & 15;
                if (s != 0) {
                    if (s != 1) {
                        throw Failedf("bad Huffman code in a JPEG");
                    }
                    s = rd.Bit() != 0 ? p1 : m1;
                } else if (r != 15) {
                    rd.EobRun = 1 << r;
                    if (r > 0) {
                        rd.EobRun += rd.Receive(r);
                    }
                    break;
                }
                do {
                    int idx = o + Zig[kk];
                    int cur = co[idx];
                    if (cur != 0) {
                        if (rd.Bit() != 0) {
                            if ((cur & p1) == 0) {
                                co[idx] = (short)(cur >= 0 ? cur + p1 : cur + m1);
                            }
                        }
                    } else {
                        if (--r < 0) {
                            break;
                        }
                    }
                    kk++;
                } while (kk <= se);
                if (s != 0 && kk <= se) {
                    co[o + Zig[kk]] = (short)s;
                }
            }
        }
        if (rd.EobRun > 0) {
            for (; kk <= se; kk++) {
                int idx = o + Zig[kk];
                int cur = co[idx];
                if (cur != 0) {
                    if (rd.Bit() != 0) {
                        if ((cur & p1) == 0) {
                            co[idx] = (short)(cur >= 0 ? cur + p1 : cur + m1);
                        }
                    }
                }
            }
            rd.EobRun--;
        }
    }

    // libjpeg's accurate integer inverse DCT (jidctint.c, "islow", what libjpeg and libjpeg-turbo give and what the Java
    // host's ImageIO reader gives), then the level shift and clamp, into the plane. `s` holds the dequantised block.
    private const int Fix0298 = 2446, Fix0390 = 3196, Fix0541 = 4433, Fix0765 = 6270, Fix0899 = 7373, Fix1175 = 9633,
        Fix1501 = 12299, Fix1847 = 15137, Fix1961 = 16069, Fix2053 = 16819, Fix2562 = 20995, Fix3072 = 25172;
    private const int ConstBits = 13, Pass1Bits = 2;

    private static void Idct(int[] s, byte[] plane, int po, int stride) {
        int[] ws = new int[64];
        for (int c = 0; c < 8; c++) {
            if (s[8 + c] == 0 && s[16 + c] == 0 && s[24 + c] == 0 && s[32 + c] == 0 && s[40 + c] == 0 && s[48 + c] == 0 && s[56 + c] == 0) {
                int dcv = s[c] << Pass1Bits;
                for (int k = 0; k < 8; k++) {
                    ws[k * 8 + c] = dcv;
                }
                continue;
            }
            IdctPass(s, c, 8, ws, c, 8, ConstBits - Pass1Bits, null, 0, 0);
        }
        for (int r = 0; r < 8; r++) {
            int o = r * 8;
            if (ws[o + 1] == 0 && ws[o + 2] == 0 && ws[o + 3] == 0 && ws[o + 4] == 0 && ws[o + 5] == 0 && ws[o + 6] == 0 && ws[o + 7] == 0) {
                byte v = Clip((ws[o] + (1 << (Pass1Bits + 2))) >> (Pass1Bits + 3));
                for (int k = 0; k < 8; k++) {
                    plane[po + r * stride + k] = v;
                }
                continue;
            }
            IdctPass(ws, o, 1, null, 0, 0, ConstBits + Pass1Bits + 3, plane, po + r * stride, 1);
        }
    }

    // One 1-D pass of jidctint: reads 8 values at `src[si + k * sstep]`; writes to `dst` (the workspace) or to the plane.
    private static void IdctPass(int[] src, int si, int sstep, int[] dst, int di, int dstep, int shift, byte[] plane, int po, int pstep) {
        int z2 = src[si + 2 * sstep], z3 = src[si + 6 * sstep];
        int z1 = (z2 + z3) * Fix0541;
        int tmp2 = z1 + z3 * (-Fix1847);
        int tmp3 = z1 + z2 * Fix0765;
        z2 = src[si];
        z3 = src[si + 4 * sstep];
        int tmp0 = (z2 + z3) << ConstBits;
        int tmp1 = (z2 - z3) << ConstBits;
        int tmp10 = tmp0 + tmp3, tmp13 = tmp0 - tmp3, tmp11 = tmp1 + tmp2, tmp12 = tmp1 - tmp2;
        tmp0 = src[si + 7 * sstep];
        tmp1 = src[si + 5 * sstep];
        tmp2 = src[si + 3 * sstep];
        tmp3 = src[si + sstep];
        z1 = tmp0 + tmp3;
        z2 = tmp1 + tmp2;
        z3 = tmp0 + tmp2;
        int z4 = tmp1 + tmp3;
        int z5 = (z3 + z4) * Fix1175;
        tmp0 *= Fix0298;
        tmp1 *= Fix2053;
        tmp2 *= Fix3072;
        tmp3 *= Fix1501;
        z1 *= -Fix0899;
        z2 *= -Fix2562;
        z3 *= -Fix1961;
        z4 *= -Fix0390;
        z3 += z5;
        z4 += z5;
        tmp0 += z1 + z3;
        tmp1 += z2 + z4;
        tmp2 += z2 + z3;
        tmp3 += z1 + z4;
        int rnd = 1 << (shift - 1);
        int o0 = (tmp10 + tmp3 + rnd) >> shift, o7 = (tmp10 - tmp3 + rnd) >> shift;
        int o1 = (tmp11 + tmp2 + rnd) >> shift, o6 = (tmp11 - tmp2 + rnd) >> shift;
        int o2 = (tmp12 + tmp1 + rnd) >> shift, o5 = (tmp12 - tmp1 + rnd) >> shift;
        int o3 = (tmp13 + tmp0 + rnd) >> shift, o4 = (tmp13 - tmp0 + rnd) >> shift;
        if (plane == null) {
            dst[di] = o0;
            dst[di + dstep] = o1;
            dst[di + 2 * dstep] = o2;
            dst[di + 3 * dstep] = o3;
            dst[di + 4 * dstep] = o4;
            dst[di + 5 * dstep] = o5;
            dst[di + 6 * dstep] = o6;
            dst[di + 7 * dstep] = o7;
        } else {
            plane[po] = Clip(o0);
            plane[po + pstep] = Clip(o1);
            plane[po + 2 * pstep] = Clip(o2);
            plane[po + 3 * pstep] = Clip(o3);
            plane[po + 4 * pstep] = Clip(o4);
            plane[po + 5 * pstep] = Clip(o5);
            plane[po + 6 * pstep] = Clip(o6);
            plane[po + 7 * pstep] = Clip(o7);
        }
    }

    private static byte Clip(int v) {
        v += 128;
        return (byte)(v < 0 ? 0 : (v > 255 ? 255 : v));
    }
}
