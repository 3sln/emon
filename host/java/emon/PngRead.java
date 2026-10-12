// The PNG reader behind a transcript's bitmaps, Java host (DEVLOG P2.5, P9.1; SPEC 13). The twin of `port.PngDecode` in `host/go/port/plan.go` and the
// PNG reader of `host/rust/src/plan.rs`. A transcript's PNGs are emon's own canonical ones (the writer is kin's, `emon.png`), but any valid
// non-interlaced PNG, whoever wrote it, is read: gray, gray+alpha, RGB, RGBA, palette, depths 1 to 16; the alpha channel is dropped and a palette
// expanded, so the bitmap is gray (1) or RGB (3). THIS IS THE ONE PNG READER THAT IS NOT KIN'S YET: P10.11 left it (DEVLOG); the validators in front
// of the image port (`ImgDec`) are kin's.

package emon;

import java.nio.charset.StandardCharsets;

public final class PngRead {
    private PngRead() {
    }

    private static int be32(byte[] p, int i) {
        return ((p[i] & 0xFF) << 24) | ((p[i + 1] & 0xFF) << 16) | ((p[i + 2] & 0xFF) << 8) | (p[i + 3] & 0xFF);
    }

    /** Reads any non-interlaced PNG into a gray or RGB bitmap. @throws IllegalArgumentException on a bad PNG */
    public static Port.Bitmap pngDecode(byte[] p) {
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (p.length < 8) {
            throw new IllegalArgumentException("not a PNG");
        }
        for (int k = 0; k < 8; k++) {
            if (p[k] != sig[k]) {
                throw new IllegalArgumentException("not a PNG");
            }
        }
        int w = 0, h = 0, depth = 0;
        int ct = 255;
        boolean haveIhdr = false;
        byte[] plte = new byte[0];
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
        int i = 8;
        loop:
        while (i + 12 <= p.length) {
            int n = be32(p, i);
            if (n < 0 || i + 12 + n > p.length || i + 12 + n < 0) {
                throw new IllegalArgumentException("a truncated PNG chunk");
            }
            String kind = new String(p, i + 4, 4, StandardCharsets.ISO_8859_1);
            int bodyAt = i + 8;
            long crc = be32(p, i + 8 + n) & 0xFFFFFFFFL;
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            c.update(p, i + 4, 4 + n);
            if (crc != c.getValue()) {
                throw new IllegalArgumentException("a PNG chunk fails its checksum");
            }
            switch (kind) {
                case "IHDR":
                    if (n < 13) {
                        throw new IllegalArgumentException("a truncated PNG header");
                    }
                    w = be32(p, bodyAt);
                    h = be32(p, bodyAt + 4);
                    depth = p[bodyAt + 8] & 0xFF;
                    ct = p[bodyAt + 9] & 0xFF;
                    if (p[bodyAt + 10] != 0 || p[bodyAt + 11] != 0 || p[bodyAt + 12] != 0) {
                        throw new IllegalArgumentException("png-unsupported: only filter method 0 and no interlace");
                    }
                    haveIhdr = true;
                    break;
                case "PLTE":
                    plte = java.util.Arrays.copyOfRange(p, bodyAt, bodyAt + n);
                    break;
                case "IDAT":
                    idat.write(p, bodyAt, n);
                    break;
                case "IEND":
                    break loop;
                default:
                    break;
            }
            i += 12 + n;
        }
        if (!haveIhdr || idat.size() == 0) {
            throw new IllegalArgumentException("a PNG without header or data");
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
                throw new IllegalArgumentException("png-unsupported: colour type");
        }
        boolean okDepth;
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
            throw new IllegalArgumentException("png-unsupported: bit depth");
        }
        byte[] z = idat.toByteArray();
        if (z.length < 2 || (((z[0] & 0xFF) << 8) | (z[1] & 0xFF)) % 31 != 0 || (z[0] & 0x0F) != 8) {
            throw new IllegalArgumentException("a bad zlib header");
        }
        int bits = chans * depth;
        int rowb = (w * bits + 7) / 8;
        int bpp = Math.max(bits / 8, 1);
        long want = (long) h * (1 + rowb);
        if (want > Integer.MAX_VALUE - 8 || w < 0 || h < 0) {
            throw new IllegalArgumentException("a PNG whose data does not fit its size");
        }
        byte[] raw = new byte[(int) want];
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        inf.setInput(z);
        int got = 0;
        try {
            while (!inf.finished()) {
                // one byte of slack proves the stream does not run on past the expected size
                byte[] tmp = got < raw.length ? null : new byte[1];
                int k = tmp == null ? inf.inflate(raw, got, raw.length - got) : inf.inflate(tmp);
                if (tmp != null) {
                    if (k > 0) {
                        throw new IllegalArgumentException("a PNG whose data does not fit its size");
                    }
                } else {
                    got += k;
                }
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    throw new IllegalArgumentException("a bad zlib stream: unexpected EOF");
                }
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IllegalArgumentException("a bad zlib stream: " + e.getMessage());
        } finally {
            inf.end();
        }
        if (got != raw.length) {
            throw new IllegalArgumentException("a PNG whose data does not fit its size");
        }
        byte[] sl = new byte[h * rowb];
        for (int y = 0; y < h; y++) {
            int f = raw[y * (1 + rowb)] & 0xFF;
            for (int x = 0; x < rowb; x++) {
                int v = raw[y * (1 + rowb) + 1 + x] & 0xFF;
                int a = 0, bb = 0, cc = 0;
                if (x >= bpp) {
                    a = sl[y * rowb + x - bpp] & 0xFF;
                }
                if (y > 0) {
                    bb = sl[(y - 1) * rowb + x] & 0xFF;
                }
                if (x >= bpp && y > 0) {
                    cc = sl[(y - 1) * rowb + x - bpp] & 0xFF;
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
                        int pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - cc);
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
                        throw new IllegalArgumentException("a PNG row with an unknown filter");
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
                        px[o++] = (byte) sample(sl, rowb, depth, ct, y, x);
                        break;
                    case 4:
                        px[o++] = (byte) sample(sl, rowb, depth, ct, y, 2 * x);
                        break;
                    case 2:
                    case 6:
                        for (int c = 0; c < 3; c++) {
                            px[o++] = (byte) sample(sl, rowb, depth, ct, y, chans * x + c);
                        }
                        break;
                    default: {
                        int idx = sample(sl, rowb, depth, ct, y, x);
                        if (idx * 3 + 3 > plte.length) {
                            throw new IllegalArgumentException("a PNG pixel beyond its palette");
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
    private static int sample(byte[] sl, int rowb, int depth, int ct, int y, int k) {
        int base = y * rowb;
        switch (depth) {
            case 8:
                return sl[base + k] & 0xFF;
            case 16:
                return sl[base + 2 * k] & 0xFF;
            default:
                break;
        }
        int per = 8 / depth;
        int v = ((sl[base + k / per] & 0xFF) >> (8 - depth * (k % per + 1))) & ((1 << depth) - 1);
        if (ct == 3) {
            return v;
        }
        return v * 255 / ((1 << depth) - 1);
    }
}
