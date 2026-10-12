// The PNG reader behind a transcript's bitmaps, C# host (DEVLOG P2.5, P9.2; SPEC 13). The twin of `PngRead.java`, of `port.PngDecode` in
// `host/go/port/plan.go` and of the PNG reader of `host/rust/src/plan.rs`. A transcript's PNGs are emon's own canonical ones (the writer is kin's,
// `emon.png`), but any valid non-interlaced PNG, whoever wrote it, is read: gray, gray+alpha, RGB, RGBA, palette, depths 1 to 16; the alpha channel is
// dropped and a palette expanded, so the bitmap is gray (1) or RGB (3). THIS IS THE ONE PNG READER THAT IS NOT KIN'S YET: P10.11 left it (DEVLOG); the
// validators in front of the image port (`ImgDec`) are kin's, and so is the image port's PNG pixel path (D1022). The BCL's `ZLibStream` inflates the
// data here (it is emon's own PNG, not an input).

namespace emon;

public static class PngRead {
    private static int Be32(byte[] p, int i) {
        return unchecked((p[i] << 24) | (p[i + 1] << 16) | (p[i + 2] << 8) | p[i + 3]);
    }

    private static readonly uint[] CrcTable = MakeCrc();

    private static uint[] MakeCrc() {
        var t = new uint[256];
        for (uint n = 0; n < 256; n++) {
            uint c = n;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            }
            t[n] = c;
        }
        return t;
    }

    private static uint Crc(byte[] p, int from, int n) {
        uint c = 0xFFFFFFFFu;
        for (int i = from; i < from + n; i++) {
            c = CrcTable[(c ^ p[i]) & 0xFF] ^ (c >> 8);
        }
        return c ^ 0xFFFFFFFFu;
    }

    /// <summary>Reads any non-interlaced PNG into a gray or RGB bitmap. Throws <see cref="System.ArgumentException"/> on a bad PNG.</summary>
    public static Port.Bitmap PngDecode(byte[] p) {
        byte[] sig = {0x89, (byte) 'P', (byte) 'N', (byte) 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (p.Length < 8) {
            throw new System.ArgumentException("not a PNG");
        }
        for (int k = 0; k < 8; k++) {
            if (p[k] != sig[k]) {
                throw new System.ArgumentException("not a PNG");
            }
        }
        int w = 0, h = 0, depth = 0;
        int ct = 255;
        bool haveIhdr = false;
        byte[] plte = new byte[0];
        var idat = new System.IO.MemoryStream();
        int i = 8;
        while (i + 12 <= p.Length) {
            int n = Be32(p, i);
            if (n < 0 || i + 12 + n > p.Length || i + 12 + n < 0) {
                throw new System.ArgumentException("a truncated PNG chunk");
            }
            string kind = System.Text.Encoding.Latin1.GetString(p, i + 4, 4);
            int bodyAt = i + 8;
            uint crc = unchecked((uint) Be32(p, i + 8 + n));
            if (crc != Crc(p, i + 4, 4 + n)) {
                throw new System.ArgumentException("a PNG chunk fails its checksum");
            }
            bool end = false;
            switch (kind) {
                case "IHDR":
                    if (n < 13) {
                        throw new System.ArgumentException("a truncated PNG header");
                    }
                    w = Be32(p, bodyAt);
                    h = Be32(p, bodyAt + 4);
                    depth = p[bodyAt + 8];
                    ct = p[bodyAt + 9];
                    if (p[bodyAt + 10] != 0 || p[bodyAt + 11] != 0 || p[bodyAt + 12] != 0) {
                        throw new System.ArgumentException("png-unsupported: only filter method 0 and no interlace");
                    }
                    haveIhdr = true;
                    break;
                case "PLTE":
                    plte = new byte[n];
                    System.Array.Copy(p, bodyAt, plte, 0, n);
                    break;
                case "IDAT":
                    idat.Write(p, bodyAt, n);
                    break;
                case "IEND":
                    end = true;
                    break;
                default:
                    break;
            }
            if (end) {
                break;
            }
            i += 12 + n;
        }
        if (!haveIhdr || idat.Length == 0) {
            throw new System.ArgumentException("a PNG without header or data");
        }
        int chans;
        switch (ct) {
            case 0:
            case 3:
                chans = 1;
                break;
            case 2:
                chans = 3;
                break;
            case 4:
                chans = 2;
                break;
            case 6:
                chans = 4;
                break;
            default:
                throw new System.ArgumentException("png-unsupported: colour type");
        }
        bool okDepth;
        switch (ct) {
            case 0:
                okDepth = depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
                break;
            case 3:
                okDepth = depth == 1 || depth == 2 || depth == 4 || depth == 8;
                break;
            default:
                okDepth = depth == 8 || depth == 16;
                break;
        }
        if (!okDepth) {
            throw new System.ArgumentException("png-unsupported: bit depth");
        }
        byte[] z = idat.ToArray();
        if (z.Length < 2 || ((z[0] << 8) | z[1]) % 31 != 0 || (z[0] & 0x0F) != 8) {
            throw new System.ArgumentException("a bad zlib header");
        }
        int bits = chans * depth;
        int rowb = (int) (((long) w * bits + 7) / 8);
        int bpp = System.Math.Max(bits / 8, 1);
        long want = (long) h * (1 + rowb);
        if (want > int.MaxValue - 8 || w < 0 || h < 0) {
            throw new System.ArgumentException("a PNG whose data does not fit its size");
        }
        byte[] raw = new byte[(int) want];
        int got = 0;
        try {
            using var zs = new System.IO.Compression.ZLibStream(new System.IO.MemoryStream(z), System.IO.Compression.CompressionMode.Decompress);
            while (got < raw.Length) {
                int k = zs.Read(raw, got, raw.Length - got);
                if (k <= 0) {
                    break;
                }
                got += k;
            }
            // one byte of slack proves the stream does not run on past the expected size
            if (got == raw.Length && zs.Read(new byte[1], 0, 1) > 0) {
                throw new System.ArgumentException("a PNG whose data does not fit its size");
            }
        } catch (System.IO.InvalidDataException e) {
            throw new System.ArgumentException("a bad zlib stream: " + e.Message);
        }
        if (got != raw.Length) {
            throw new System.ArgumentException("a PNG whose data does not fit its size");
        }
        byte[] sl = new byte[h * rowb];
        for (int y = 0; y < h; y++) {
            int f = raw[y * (1 + rowb)];
            for (int x = 0; x < rowb; x++) {
                int v = raw[y * (1 + rowb) + 1 + x];
                int a = 0, bb = 0, cc = 0;
                if (x >= bpp) {
                    a = sl[y * rowb + x - bpp];
                }
                if (y > 0) {
                    bb = sl[(y - 1) * rowb + x];
                }
                if (x >= bpp && y > 0) {
                    cc = sl[(y - 1) * rowb + x - bpp];
                }
                int pred;
                switch (f) {
                    case 0:
                        pred = 0;
                        break;
                    case 1:
                        pred = a;
                        break;
                    case 2:
                        pred = bb;
                        break;
                    case 3:
                        pred = (a + bb) / 2;
                        break;
                    case 4: {
                        int pp = a + bb - cc;
                        int pa = System.Math.Abs(pp - a), pb = System.Math.Abs(pp - bb), pc = System.Math.Abs(pp - cc);
                        if (pa <= pb && pa <= pc) {
                            pred = a;
                        } else if (pb <= pc) {
                            pred = bb;
                        } else {
                            pred = cc;
                        }
                        break;
                    }
                    default:
                        throw new System.ArgumentException("a PNG row with an unknown filter");
                }
                sl[y * rowb + x] = (byte) (v + pred);
            }
        }
        int outCh = (ct == 0 || ct == 4) ? 1 : 3;
        byte[] px = new byte[w * h * outCh];
        int o = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                switch (ct) {
                    case 0:
                        px[o++] = (byte) Sample(sl, rowb, depth, ct, y, x);
                        break;
                    case 4:
                        px[o++] = (byte) Sample(sl, rowb, depth, ct, y, 2 * x);
                        break;
                    case 2:
                    case 6:
                        for (int c = 0; c < 3; c++) {
                            px[o++] = (byte) Sample(sl, rowb, depth, ct, y, chans * x + c);
                        }
                        break;
                    default: {
                        int idx = Sample(sl, rowb, depth, ct, y, x);
                        if (idx * 3 + 3 > plte.Length) {
                            throw new System.ArgumentException("a PNG pixel beyond its palette");
                        }
                        px[o++] = plte[idx * 3];
                        px[o++] = plte[idx * 3 + 1];
                        px[o++] = plte[idx * 3 + 2];
                        break;
                    }
                }
            }
        }
        return new Port.Bitmap(w, h, outCh, px);
    }

    // sample k of row y as 0..255 (sub-byte scaled up, 16-bit high byte)
    private static int Sample(byte[] sl, int rowb, int depth, int ct, int y, int k) {
        int bas = y * rowb;
        switch (depth) {
            case 8:
                return sl[bas + k];
            case 16:
                return sl[bas + 2 * k];
            default:
                break;
        }
        int per = 8 / depth;
        int v = (sl[bas + k / per] >> (8 - depth * (k % per + 1))) & ((1 << depth) - 1);
        if (ct == 3) {
            return v;
        }
        return v * 255 / ((1 << depth) - 1);
    }
}
