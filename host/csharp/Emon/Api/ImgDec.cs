// THE `ImageDecode` PORT, C# host (DEVLOG P9.2, P6.1; SPEC 7.2, 7.3; D058, D089, D187-D190, D444-D448, D456-D457). The twin of `host/java/emon/api/ImgDec.java`,
// `host/go/imgdec.go` and `host/rust/src/imgdec.rs`: read those for the reasoning. Encoded bytes (PNG, JPEG, TIFF) in, one frame's pixels out, normalised to the
// Bitmap contract: 8-bit gray or RGB, row-major, top-left, EXIF orientation applied, alpha composited onto white, resolution in dots per inch x100
// (0 = unknown), a `bilevel` flag for one-bit gray. It never resamples.
//
// What an image may contain is kin's (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` parse the container, decide, apply the limits and report. The
// BCL has no PNG codec, so where Java hands ImageIO the chunks that matter, this host decodes them in kin (D456): the validator's `idat` (the IDAT payloads
// joined) goes through kin's inflate and `pf-png` (the filters of RFC 2083, the same code PDF's predictor uses), the seven Adam7 passes are placed here, and
// the colour step (palette, `tRNS`, 16 bits to 8, alpha over white) is the same arithmetic as Go's and Rust's (D188). TIFF's strips and tiles are unpacked
// here from the chunks the validator listed: LZW and PackBits are host code; Deflate is kin's inflate (D457) with the three answers of `miniz_oxide`; CCITT G3
// and G4 are kin's (`emon.ccitt`, the decoder PDF uses too). JPEG is the one codec that stays native (D190; `Jpeg.cs`): its pixels may differ in the last bits
// from the other hosts'. What is refused is UNSUPPORTED, a damaged file FAILED, a frame over the pixel bound LIMIT.
//
// NOTHING HERE NAMES A FILESYSTEM (D058): a source is an `IByteSource`.

namespace emon.Api;

using System;

public static partial class ImgDec {
    public const int Failed = 0, Unsupported = 1, Limit = 2;

    /// <summary>Why a frame was not decoded.</summary>
    public sealed class Fail : Exception {
        public readonly int Kind;
        /// <summary>LIMIT: the pixels the frame has (long.MaxValue when more than a long holds).</summary>
        public readonly long Pixels;

        public Fail(int kind, string msg) : this(kind, msg, 0) {
        }

        public Fail(int kind, string msg, long pixels) : base(msg) {
            Kind = kind;
            Pixels = pixels;
        }
    }

    /// <summary>One decoded frame (or only its header).</summary>
    public sealed class Decoded {
        public int W, H, Ch; // the size AFTER orientation; 1 gray or 3 RGB
        public int Dpi100;
        public bool Bilevel;
        public int Frames;
        public byte[] Pixels; // w*h*ch bytes; null when only the header was asked for
    }

    /// <summary>What a PDF image codec made (<see cref="PdfImg"/>): W * H * Ch bytes.</summary>
    public sealed class PdfPix {
        public int W, H, Ch;
        public byte[] Px;
        public bool Bilevel;

        public PdfPix(int w, int h, int ch, byte[] px, bool bilevel) {
            W = w;
            H = h;
            Ch = ch;
            Px = px;
            Bilevel = bilevel;
        }
    }

    internal static Fail Failedf(string s) {
        return new Fail(Failed, s);
    }

    internal static Fail Unsupportedf(string s) {
        return new Fail(Unsupported, s);
    }

    private const int None = 0, Png = 1, Jpeg = 2, Tiff = 3;

    private static int Sniff(byte[] h, int n) {
        if (n >= 8 && h[0] == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D && h[5] == 0x0A
                && h[6] == 0x1A && h[7] == 0x0A) {
            return Png;
        }
        if (n >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF) {
            return Jpeg;
        }
        if (n >= 4 && ((h[0] == 'I' && h[1] == 'I' && h[2] == 42 && h[3] == 0) || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 42))) {
            return Tiff;
        }
        return None;
    }

    /// <summary>
    /// Decode a frame: <c>frame >= 0</c> is that frame's pixels; <c>frame &lt; 0</c> only the header of frame <c>-frame-1</c>. A frame over the PIXELS limit
    /// (limit-of 9) is a LIMIT failure. Throws nothing but <see cref="Fail"/> (and a <see cref="Port.Breach"/>, which is the Engine's to name).
    /// </summary>
    public static Decoded Decode(IByteSource src, int frame) {
        try {
            return Decode0(src, frame);
        } catch (Fail) {
            throw;
        } catch (System.IO.IOException) {
            throw Failedf("a read of the source failed");
        } catch (Port.Breach) {
            throw;
        } catch (Exception e) {
            throw Failedf("the decoder failed on this input: " + e.GetType().Name + ": " + e.Message);
        }
    }

    private static Decoded Decode0(IByteSource src, int frame) {
        byte[] head = new byte[8];
        int n = ReadAtMost(src, 0, head, 0, 8);
        bool want = frame >= 0;
        int f = want ? frame : -frame - 1;
        switch (Sniff(head, n)) {
            case Png:
                if (f != 0) {
                    throw Failedf("this format has one frame");
                }
                return DecodePng(src, want);
            case Jpeg:
                if (f != 0) {
                    throw Failedf("this format has one frame");
                }
                return DecodeJpeg(src, want);
            case Tiff:
                return DecodeTiff(src, f, want);
            default:
                throw Failedf("not a PNG, JPEG or TIFF");
        }
    }

    private static int ReadAtMost(IByteSource src, long off, byte[] buf, int boff, int len) {
        int got = 0;
        while (got < len) {
            int k = src.ReadAt(off + got, buf, boff + got, len - got);
            if (k <= 0) {
                break;
            }
            got += k;
        }
        return got;
    }

    private static byte[] ReadAll(IByteSource src) {
        long n = src.Size();
        if (n < 0 || n > int.MaxValue - 16) {
            throw Failedf("a read past the end of the input");
        }
        byte[] v = new byte[(int) n];
        if (ReadAtMost(src, 0, v, 0, v.Length) != v.Length) {
            throw Failedf("the input is shorter than it said");
        }
        return v;
    }

    // ------------------------------------------------------------- small integer helpers

    // n-bit sample -> 8 bits, rounded: (v * 255 + max / 2) / max (D188).
    private static byte[] Lut(int b) {
        long max = (1L << b) - 1;
        byte[] t = new byte[(int) max + 1];
        for (long v = 0; v <= max; v++) {
            t[(int) v] = (byte) ((v * 255 + max / 2) / max);
        }
        return t;
    }

    private static int S16(int v) {
        return (int) (((long) v * 255 + 32767) / 65535);
    }

    private static int OverWhite(int c, int a) {
        return (c * a + 255 * (255 - a) + 127) / 255;
    }

    private static int OverWhitePremul(int c, int a) {
        return c + 255 - a > 255 ? 255 : c + 255 - a;
    }

    private static int CmykChan(int v, int k) {
        return ((255 - v) * (255 - k) + 127) / 255;
    }

    private static int Clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    internal static void YccRgb(int y, int cb, int cr, byte[] d, int o) {
        cb -= 128;
        cr -= 128;
        d[o] = (byte) Clamp8(y + ((91881 * cr + 32768) >> 16));
        d[o + 1] = (byte) Clamp8(y - ((22554 * cb + 46802 * cr + 32768) >> 16));
        d[o + 2] = (byte) Clamp8(y + ((116130 * cb + 32768) >> 16));
    }

    private static int U16(byte[] b, int i) {
        return (b[i] << 8) | b[i + 1];
    }

    // ------------------------------------------------------------------ orientation

    private static byte[] Orient(byte[] d, int w, int h, int ch, long o) {
        if (o <= 1 || o > 8) {
            return d;
        }
        int ow = w, oh = h;
        if (o >= 5) {
            ow = h;
            oh = w;
        }
        byte[] outb = new byte[d.Length];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int sx, sy;
                switch ((int) o) {
                    case 2: sx = w - 1 - x; sy = y; break;
                    case 3: sx = w - 1 - x; sy = h - 1 - y; break;
                    case 4: sx = x; sy = h - 1 - y; break;
                    case 5: sx = y; sy = x; break;
                    case 6: sx = y; sy = h - 1 - x; break;
                    case 7: sx = w - 1 - y; sy = h - 1 - x; break;
                    default: sx = w - 1 - y; sy = x; break;
                }
                Array.Copy(d, (sy * w + sx) * ch, outb, (y * ow + x) * ch, ch);
            }
        }
        return outb;
    }

    // --------------------------------------------------------------- the validators (kin)
    //
    // WHAT AN IMAGE MAY CONTAIN IS KIN'S (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` read the container, decide, apply the pixel and byte limits and
    // report what the host needs; pixels are made only of input they accepted. CCITT G3 and G4 are kin's (`emon.ccitt`).

    // turns a validator's status into the port's failure
    private static Fail Refused(int status, string what, int limit) {
        if (status == emon.Imgcheck.IcUnsupported()) {
            return Unsupportedf(what);
        }
        if (status == emon.Imgcheck.IcLimit()) {
            return new Fail(Limit, "pixels", limit & 0xFFFFFFFFL);
        }
        return Failedf(what);
    }

    // what the first directory of an EXIF blob says: orientation and the horizontal and vertical resolution (dpi x100, 0 unknown)
    private static long[] Exif(byte[] blob) {
        Port.Cells r = Port.NewCells();
        emon.Tiffcheck.TcExif(blob, 0, blob.Length, r);
        return new long[] {Port.CellsAt(r, emon.Tiffcheck.TrEo()), Port.CellsAt(r, emon.Tiffcheck.TrEx()), Port.CellsAt(r, emon.Tiffcheck.TrEy())};
    }

    // ------------------------------------------------------------------------ PNG

    // the seven Adam7 passes: x start, y start, x step, y step (`pc-adam`)
    private static readonly int[] Adam = {0, 0, 8, 8, 4, 0, 8, 8, 0, 4, 4, 8, 2, 0, 4, 4, 0, 2, 2, 4, 1, 0, 2, 2, 0, 1, 1, 2};

    private static Decoded DecodePng(IByteSource src, bool want) {
        byte[] b = ReadAll(src);
        Port.Cells r = Port.NewCells(), st = Port.NewCells(), outc = Port.NewCells();
        Port.Buf idat = Port.NewBuf(), clean = Port.NewBuf();
        int s = emon.Pngcheck.PcCheck(b, want ? 1 : 0, r, st, outc, idat, clean);
        if (s != emon.Imgcheck.IcOk()) {
            throw Refused(s, "a PNG the validator refused: damaged, or past the rules of D389", Port.CellsAt(r, emon.Pngcheck.PrLimit()));
        }
        int w = Port.CellsAt(r, emon.Pngcheck.PrW()), h = Port.CellsAt(r, emon.Pngcheck.PrH());
        int ch = Port.CellsAt(r, emon.Pngcheck.PrCh());
        int o = Port.CellsAt(r, emon.Pngcheck.PrOrient());
        var res = new Decoded();
        res.W = Port.CellsAt(r, emon.Pngcheck.PrOw());
        res.H = Port.CellsAt(r, emon.Pngcheck.PrOh());
        res.Ch = ch;
        res.Dpi100 = Port.CellsAt(r, emon.Pngcheck.PrDpi());
        res.Bilevel = Port.CellsAt(r, emon.Pngcheck.PrBilevel()) != 0;
        res.Frames = 1;
        if (!want) {
            return res;
        }
        int ctype = Port.CellsAt(r, emon.Pngcheck.PrCtype()), depth = Port.CellsAt(r, emon.Pngcheck.PrDepth());
        int interlace = Port.CellsAt(r, emon.Pngcheck.PrInterlace());
        // PLTE and tRNS: the validator has judged the chunks (their order, their number, their length); this reads the values
        byte[] plte = new byte[0], trns = new byte[0];
        for (int i = 8; i + 12 <= b.Length;) {
            int ln = Port.BlobU32(b, i), kind = Port.BlobU32(b, i + 4);
            if (kind == 1347179589 && plte.Length == 0) {
                plte = new byte[ln];
                Array.Copy(b, i + 8, plte, 0, ln);
            } else if (kind == 1951551059 && trns.Length == 0) {
                trns = new byte[ln];
                Array.Copy(b, i + 8, trns, 0, ln);
            } else if (kind == 1229278788) {
                break;
            }
            i += 12 + ln;
        }
        b = null;
        // the image data: the IDAT payloads are ONE zlib stream (the validator inflated it once to judge it); kin's inflate again, then kin's PNG filters
        Port.Cells raw = Port.NewCells();
        if (emon.Inflate.IzZlib(Port.BufBlob(idat), 0, Port.BufLen(idat), raw, st) != 0) {
            throw Failedf("a PNG whose image data does not inflate");
        }
        idat = null;
        clean = null;
        int chans = ctype == 2 ? 3 : ctype == 4 ? 2 : ctype == 6 ? 4 : 1;
        int bits = chans * depth;
        int bpp = Math.Max(bits / 8, 1);
        byte[] px = new byte[w * h * ch];
        Port.Cells u = Port.NewCells();
        int pos = 0;
        int npass = interlace == 0 ? 1 : 7;
        for (int pass = 0; pass < npass; pass++) {
            int x0 = 0, y0 = 0, dx = 1, dy = 1;
            if (interlace != 0) {
                x0 = Adam[pass * 4];
                y0 = Adam[pass * 4 + 1];
                dx = Adam[pass * 4 + 2];
                dy = Adam[pass * 4 + 3];
                if (w <= x0 || h <= y0) {
                    continue;
                }
            }
            int pw = (w - x0 + dx - 1) / dx, ph = (h - y0 + dy - 1) / dy;
            int rowb = (int) (((long) pw * bits + 7) / 8);
            int end = pos + ph * (1 + rowb);
            Port.CellsDrop(u, 0);
            if (emon.Pdffilter.PfPng(raw, pos, end, u, rowb, bpp) != 0) {
                throw Failedf("a PNG row with an unknown filter");
            }
            pos = end;
            for (int py = 0; py < ph; py++) {
                int rb = py * rowb;
                for (int pxi = 0; pxi < pw; pxi++) {
                    PngPixel(u, rb, pxi, ctype, depth, plte, trns, px, ((y0 + py * dy) * w + x0 + pxi * dx) * ch);
                }
            }
        }
        res.Pixels = Orient(px, w, h, ch, o);
        return res;
    }

    // sample k of a row as its raw value: 1..8 bits as they are, 16 bits whole
    private static int PngSample(Port.Cells u, int rb, int depth, int k) {
        switch (depth) {
            case 8:
                return Port.CellsAt(u, rb + k);
            case 16:
                return (Port.CellsAt(u, rb + 2 * k) << 8) | Port.CellsAt(u, rb + 2 * k + 1);
            default:
                break;
        }
        int per = 8 / depth;
        return (Port.CellsAt(u, rb + k / per) >> (8 - depth * (k % per + 1))) & ((1 << depth) - 1);
    }

    // a sample as 8 bits: a sub-byte sample scaled by 255 / max, a 16-bit one rounded (D188)
    private static int Png8(int v, int depth) {
        if (depth == 8) {
            return v;
        }
        if (depth == 16) {
            return S16(v);
        }
        return v * 255 / ((1 << depth) - 1);
    }

    // pixel `x` of the row at `rb`, in the gray (1) or RGB (3) bytes at px[o..], alpha composited onto white; the colour key of a `tRNS` is a transparent pixel
    private static void PngPixel(Port.Cells u, int rb, int x, int ctype, int depth, byte[] plte, byte[] trns, byte[] px, int o) {
        int mask = depth == 16 ? 0xFFFF : (1 << depth) - 1;
        switch (ctype) {
            case 0: {
                int v = PngSample(u, rb, depth, x);
                bool keyed = trns.Length >= 2 && v == (U16(trns, 0) & mask);
                px[o] = (byte) (keyed ? 255 : Png8(v, depth));
                break;
            }
            case 2: {
                int r = PngSample(u, rb, depth, 3 * x), g = PngSample(u, rb, depth, 3 * x + 1), bl = PngSample(u, rb, depth, 3 * x + 2);
                bool keyed = trns.Length >= 6 && r == (U16(trns, 0) & mask) && g == (U16(trns, 2) & mask) && bl == (U16(trns, 4) & mask);
                if (keyed) {
                    px[o] = px[o + 1] = px[o + 2] = 255;
                } else {
                    px[o] = (byte) Png8(r, depth);
                    px[o + 1] = (byte) Png8(g, depth);
                    px[o + 2] = (byte) Png8(bl, depth);
                }
                break;
            }
            case 3: {
                int idx = PngSample(u, rb, depth, x);
                if (idx * 3 + 3 > plte.Length) {
                    throw Failedf("a PNG pixel outside its palette");
                }
                int a = idx < trns.Length ? trns[idx] : 255;
                for (int k = 0; k < 3; k++) {
                    px[o + k] = (byte) OverWhite(plte[idx * 3 + k], a);
                }
                break;
            }
            case 4: {
                int c = Png8(PngSample(u, rb, depth, 2 * x), depth), a = Png8(PngSample(u, rb, depth, 2 * x + 1), depth);
                px[o] = (byte) OverWhite(c, a);
                break;
            }
            default: {
                int a = Png8(PngSample(u, rb, depth, 4 * x + 3), depth);
                for (int k = 0; k < 3; k++) {
                    px[o + k] = (byte) OverWhite(Png8(PngSample(u, rb, depth, 4 * x + k), depth), a);
                }
                break;
            }
        }
    }

    // ----------------------------------------------------------------------- JPEG

    /// <summary>What a JPEG's markers say, as far as the port needs: the frame, the resolution, the EXIF blob and the Adobe transform.</summary>
    internal sealed class JpegMeta {
        internal int W, H, Ncomp, Dx, Dy, Adobe = -1;
        internal byte[] Exif;
        internal int LastSos = -1;
    }

    internal static JpegMeta ReadJpegMeta(byte[] b) {
        var m = new JpegMeta();
        bool sof = false;
        int i = 2;
        while (i + 4 <= b.Length) {
            if (b[i] != 0xFF) {
                i++;
                continue;
            }
            int mk = b[i + 1];
            if (mk == 0xFF) {
                i++;
                continue;
            }
            if (mk == 0xD8 || mk == 0x01 || (mk >= 0xD0 && mk <= 0xD7) || mk == 0) {
                i += 2;
                continue;
            }
            if (mk == 0xD9 || mk == 0xDA) {
                if (mk == 0xDA) {
                    m.LastSos = i;
                }
                break;
            }
            int l = U16(b, i + 2);
            if (l < 2 || i + 2 + l > b.Length) {
                break;
            }
            int bo = i + 4, bl = l - 2;
            if (mk == 0xE0 && bl >= 12 && b[bo] == 'J' && b[bo + 1] == 'F' && b[bo + 2] == 'I' && b[bo + 3] == 'F' && b[bo + 4] == 0) {
                int units = b[bo + 7];
                long xd = U16(b, bo + 8), yd = U16(b, bo + 10);
                if (units == 1) {
                    m.Dx = (int) (xd * 100);
                    m.Dy = (int) (yd * 100);
                } else if (units == 2) {
                    m.Dx = (int) (xd * 254);
                    m.Dy = (int) (yd * 254);
                } else {
                    m.Dx = 0;
                    m.Dy = 0;
                }
            } else if (mk == 0xE1 && bl > 6 && b[bo] == 'E' && b[bo + 1] == 'x' && b[bo + 2] == 'i' && b[bo + 3] == 'f'
                    && b[bo + 4] == 0 && b[bo + 5] == 0 && m.Exif == null) {
                m.Exif = new byte[bl - 6];
                Array.Copy(b, bo + 6, m.Exif, 0, bl - 6);
            } else if (mk == 0xEE && bl >= 12 && b[bo] == 'A' && b[bo + 1] == 'd' && b[bo + 2] == 'o' && b[bo + 3] == 'b' && b[bo + 4] == 'e'
                    && m.Adobe < 0) {
                m.Adobe = b[bo + 11];
            } else if (mk >= 0xC0 && mk <= 0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC && !sof) {
                if (bl >= 6) {
                    m.H = U16(b, bo + 1);
                    m.W = U16(b, bo + 3);
                    m.Ncomp = b[bo + 5];
                    sof = true;
                }
            }
            i += 2 + l;
        }
        if (!sof || m.W == 0 || m.H == 0) {
            throw Failedf("a JPEG without a usable frame header");
        }
        return m;
    }

    private static Decoded DecodeJpeg(IByteSource src, bool want) {
        long maxPixels = Port.LimitOf(9);
        byte[] b = ReadAll(src);
        JpegMeta m = ReadJpegMeta(b);
        if (m.Ncomp != 1 && m.Ncomp != 3) {
            throw Unsupportedf("a JPEG with other than one or three components (CMYK: P6.1a)");
        }
        long o = 1;
        int ex = 0, ey = 0;
        if (m.Exif != null) {
            long[] e = Exif(m.Exif);
            o = e[0];
            ex = (int) e[1];
            ey = (int) e[2];
        }
        long w = m.W, h = m.H;
        long ow = w, oh = h;
        if (o >= 5) {
            ow = h;
            oh = w;
        }
        int dx = m.Dx, dy = m.Dy;
        if (dx <= 0) {
            dx = ex;
            dy = ey;
        }
        var outd = new Decoded();
        outd.W = (int) ow;
        outd.H = (int) oh;
        outd.Ch = m.Ncomp;
        outd.Frames = 1;
        outd.Dpi100 = o >= 5 ? dy : dx;
        if (w * h > maxPixels) {
            throw new Fail(Limit, "pixels", w * h);
        }
        if (!want) {
            return outd;
        }
        byte[] px = JpegRaw(b, m, (int) w, (int) h);
        outd.Pixels = Orient(px, (int) w, (int) h, m.Ncomp, o);
        return outd;
    }

    /// <summary>
    /// The pixels of a decoded 1 or 3 component JPEG: gray, or RGB (the chroma filtered as libjpeg does). It applies no orientation: the ImageDecode port
    /// orients after it, a PDF image (<see cref="PdfImg"/>) never does. A stream that stops before its end-of-image marker is damaged (Go's decoder says so; libjpeg only warns).
    /// </summary>
    internal static byte[] JpegRaw(byte[] b, JpegMeta m, int w, int h) {
        byte[] raw = JpegComponents(b, m, w, h);
        if (m.Ncomp == 1 || m.Adobe == 0) {
            // an Adobe marker of transform 0 on three components: the samples are RGB
            return raw;
        }
        byte[] px = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            YccRgb(raw[3 * i], raw[3 * i + 1], raw[3 * i + 2], px, 3 * i);
        }
        return px;
    }

    /// <summary>The components of a JPEG, interleaved (w * h * ncomp bytes, no colour conversion), checked against the header's size and component count.</summary>
    internal static byte[] JpegComponents(byte[] b, JpegMeta m, int w, int h) {
        int eoi = -1;
        for (int k = b.Length - 2; k > m.LastSos && k >= 0; k--) {
            if (b[k] == 0xFF && b[k + 1] == 0xD9) {
                eoi = k;
                break;
            }
        }
        if (m.LastSos < 0 || eoi < 0) {
            throw Failedf("unexpected EOF in a JPEG");
        }
        JpegImage img = JpegRead(b);
        if (img.W != w || img.H != h || img.Comps.Length != m.Ncomp) {
            throw Failedf("a JPEG whose pixels are not the size its header says");
        }
        int nc = m.Ncomp;
        byte[] px = new byte[w * h * nc];
        if (nc == 1) {
            JpegComp c = img.Comps[0];
            for (int y = 0; y < h; y++) {
                Array.Copy(c.Plane, y * c.Stride, px, y * w, w);
            }
            return px;
        }
        var planes = new byte[nc][];
        if (nc == 3) {
            JpegComp c0 = img.Comps[0], c1 = img.Comps[1], c2 = img.Comps[2];
            if (c1.H != c2.H || c1.V != c2.V || c0.H != img.Hmax || c0.V != img.Vmax || img.Hmax % c1.H != 0 || img.Vmax % c1.V != 0) {
                throw Failedf("unsupported luma/chroma subsampling ratio");
            }
            int ratio;
            switch ((img.Hmax / c1.H) * 10 + img.Vmax / c1.V) {
                case 11: ratio = 0; break;
                case 21: ratio = 1; break; // 4:2:2
                case 22: ratio = 2; break; // 4:2:0
                case 12: ratio = 3; break; // 4:4:0
                case 41: ratio = 4; break; // 4:1:1
                case 42: ratio = 5; break; // 4:1:0
                default: throw Failedf("unsupported luma/chroma subsampling ratio");
            }
            planes[0] = Upsample(c0.Plane, c0.Stride, w, h, 0);
            planes[1] = Upsample(c1.Plane, c1.Stride, w, h, ratio);
            planes[2] = Upsample(c2.Plane, c2.Stride, w, h, ratio);
        } else {
            // four components (a PDF's CMYK or YCCK): a plane smaller than the image is replicated
            for (int k = 0; k < nc; k++) {
                JpegComp c = img.Comps[k];
                var pl = new byte[w * h];
                for (int y = 0; y < h; y++) {
                    int sy = y * c.V / img.Vmax;
                    for (int x = 0; x < w; x++) {
                        pl[y * w + x] = c.Plane[sy * c.Stride + x * c.H / img.Hmax];
                    }
                }
                planes[k] = pl;
            }
        }
        for (int i = 0; i < w * h; i++) {
            for (int k = 0; k < nc; k++) {
                px[i * nc + k] = planes[k][i];
            }
        }
        return px;
    }

    // ----------------------------------------------------------------------- TIFF

    private static byte[] Packbits(byte[] d, int expect) {
        byte[] outb = new byte[expect];
        int n = 0;
        int i = 0;
        while (i < d.Length && n < expect) {
            int c = (sbyte) d[i++];
            if (c >= 0) {
                int cnt = c + 1;
                int e = Math.Min(i + cnt, d.Length);
                int k = Math.Min(e - i, expect - n);
                Array.Copy(d, i, outb, n, k);
                n += k;
                i = e;
            } else if (c != -128) {
                if (i >= d.Length) {
                    break;
                }
                int cnt = 1 - c;
                for (int j = 0; j < cnt && n < expect; j++) {
                    outb[n++] = d[i];
                }
                i++;
            }
        }
        if (n == expect) {
            return outb;
        }
        Array.Resize(ref outb, n);
        return outb;
    }

    // TIFF LZW: MSB first with early change, or the pre-1988 LSB stream (libtiff's "compat").
    private static byte[] Lzw(byte[] d, int expect) {
        bool compat = d.Length >= 2 && d[0] == 0 && (d[1] & 1) == 1;
        int early = compat ? 0 : 1;
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] outb = new byte[Math.Max(expect, 0) + 4096];
        int n = 0;
        int next = 258;
        int width = 9;
        long acc = 0;
        int nbits = 0;
        int pos = 0;
        int prev = -1;
        byte[] stack = new byte[4097];
        for (;;) {
            bool eof = false;
            while (nbits < width) {
                if (pos >= d.Length) {
                    eof = true;
                    break;
                }
                if (compat) {
                    acc |= (long) d[pos] << nbits;
                } else {
                    acc = (acc << 8) | d[pos];
                }
                pos++;
                nbits += 8;
            }
            if (eof) {
                break;
            }
            int code;
            if (compat) {
                code = (int) (acc & ((1L << width) - 1));
                acc = (long) ((ulong) acc >> width);
                nbits -= width;
            } else {
                code = (int) (((ulong) acc >> (nbits - width)) & ((1UL << width) - 1));
                nbits -= width;
                acc &= (1L << nbits) - 1;
            }
            if (code == 257) {
                break;
            }
            if (code == 256) {
                next = 258;
                width = 9;
                prev = -1;
                continue;
            }
            int sp = 0;
            bool extra = false;
            byte first;
            if (prev < 0) {
                if (code > 255) {
                    throw Failedf("a bad LZW code");
                }
                stack[sp++] = (byte) code;
                first = (byte) code;
            } else {
                int c;
                if (code < next) {
                    c = code;
                } else if (code == next) {
                    c = prev;
                } else {
                    throw Failedf("a bad LZW code");
                }
                while (c > 255) {
                    stack[sp++] = suffix[c];
                    c = prefix[c];
                }
                stack[sp++] = (byte) c;
                first = (byte) c;
                if (code == next) {
                    extra = true;
                }
                if (next < 4096) {
                    prefix[next] = prev;
                    suffix[next] = first;
                    next++;
                }
            }
            if (n + sp + 1 > outb.Length) {
                Array.Resize(ref outb, Math.Max(outb.Length * 2, n + sp + 1));
            }
            for (int i = sp - 1; i >= 0; i--) {
                outb[n++] = stack[i];
            }
            if (extra) {
                outb[n++] = first;
            }
            prev = code;
            if (next + early < 512) {
                width = 9;
            } else if (next + early < 1024) {
                width = 10;
            } else if (next + early < 2048) {
                width = 11;
            } else {
                width = 12;
            }
            if (n >= expect) {
                break;
            }
        }
        Array.Resize(ref outb, Math.Min(n, expect));
        return outb;
    }

    /// <summary>
    /// A Deflate strip, by kin's inflate (D457; the BCL's `ZLibStream` has a stream's habits, not these answers). The same three answers as the Rust host's
    /// `miniz_oxide`: a stream that ends cleanly (its checksum right) early gives what it has, one that fills `expect` bytes has its end read too (so a wrong
    /// checksum is seen), and one that has MORE is cut at `expect` without it; any damage before `expect` bytes is `failed`.
    /// </summary>
    private static byte[] InflateStrip(byte[] d, int expect) {
        Port.Cells o = Port.NewCells(), st = Port.NewCells();
        int s;
        try {
            s = emon.Inflate.IzZlib(d, 0, d.Length, o, st);
        } catch (Port.Breach) {
            // the stream bound (limit-of STREAM_BYTES) was reached: what was decoded is more than the strip holds, which is cut at `expect` as ever
            if (Port.CellsLen(o) < expect) {
                throw;
            }
            s = 0;
        }
        int got = Port.CellsLen(o);
        if (got < expect && s != 0) {
            throw Failedf("a bad Deflate stream");
        }
        if (got == expect && s != 0) {
            throw Failedf("a bad Deflate stream");
        }
        int n = Math.Min(got, expect);
        byte[] outb = new byte[n];
        for (int i = 0; i < n; i++) {
            outb[i] = (byte) Port.CellsAt(o, i);
        }
        return outb;
    }

    /// <summary>
    /// kin's one CCITT decoder (`emon.ccitt`, TIFF's and PDF's): `rows` rows of `cw` pixels, bits packed MSB first, 1 = black, a row to a whole number of
    /// bytes. The decoder needs a ZEROED Buf of rows * stride bytes (D447). `ws` is its scratch, kept for every strip of an image.
    /// </summary>
    internal static byte[] CcittRows(byte[] d, int k, int cw, int rows, int flags, Port.Cells ws) {
        Port.Buf outb = Port.NewBuf();
        int total = ((cw + 7) / 8) * rows;
        Port.BufAppendBlob(outb, new byte[total], 0, total);
        if (emon.Ccitt.CcDecode(d, 0, d.Length, k, cw, rows, flags, outb, ws) != emon.Ccitt.CcOk()) {
            throw Failedf("a short or damaged CCITT stream");
        }
        return Port.BufBlob(outb);
    }

    private sealed class Photo {
        internal int Kind, Spp, Nc, Bps, Alpha;
        internal int[] Cmap;
    }

    /// <summary>
    /// The whole file is read, `emon.tiffcheck` decides (the byte order, the chain of frames, every tag, the bounds of every strip and tile, the pixel limit)
    /// and reports; what is left here is turning the chunks it listed into samples and the samples into the 8-bit pixels of D188. A multi-page TIFF reads only
    /// the frame asked for.
    /// </summary>
    private static Decoded DecodeTiff(IByteSource src, int f, bool want) {
        byte[] b = ReadAll(src);
        Port.Cells r = Port.NewCells();
        int s = emon.Tiffcheck.TcCheck(b, f, want ? 1 : 0, r);
        if (s != emon.Imgcheck.IcOk()) {
            throw Refused(s, "a TIFF the validator refused: damaged, or a coding the port does not read (D188)", Port.CellsAt(r, emon.Tiffcheck.TrLimit()));
        }
        var outd = new Decoded();
        outd.W = Port.CellsAt(r, emon.Tiffcheck.TrOw());
        outd.H = Port.CellsAt(r, emon.Tiffcheck.TrOh());
        outd.Ch = Port.CellsAt(r, emon.Tiffcheck.TrCh());
        outd.Dpi100 = Port.CellsAt(r, emon.Tiffcheck.TrDpi());
        outd.Bilevel = Port.CellsAt(r, emon.Tiffcheck.TrBilevel()) != 0;
        outd.Frames = Port.CellsAt(r, emon.Tiffcheck.TrFrames());
        if (want) {
            outd.Pixels = TiffPixels(b, r);
        }
        return outd;
    }

    private static int U16o(byte[] row, int at, bool le) {
        return le ? (row[at + 1] << 8) | row[at] : (row[at] << 8) | row[at + 1];
    }

    private static byte RevByte(byte v) {
        int x = v;
        x = ((x & 0xF0) >> 4) | ((x & 0x0F) << 4);
        x = ((x & 0xCC) >> 2) | ((x & 0x33) << 2);
        x = ((x & 0xAA) >> 1) | ((x & 0x55) << 1);
        return (byte) x;
    }

    private static int G(Port.Cells rep, int i) {
        return Port.CellsAt(rep, i);
    }

    private static byte[] TiffPixels(byte[] b, Port.Cells rep) {
        int w = G(rep, emon.Tiffcheck.TrW()), h = G(rep, emon.Tiffcheck.TrH());
        int spp = G(rep, emon.Tiffcheck.TrSpp()), bps = G(rep, emon.Tiffcheck.TrBps());
        int comp = G(rep, emon.Tiffcheck.TrComp());
        var ph = new Photo();
        ph.Kind = G(rep, emon.Tiffcheck.TrKind());
        ph.Nc = G(rep, emon.Tiffcheck.TrNc());
        ph.Spp = spp;
        ph.Bps = bps;
        ph.Alpha = -1;
        switch (G(rep, emon.Tiffcheck.TrAlpha())) {
            case 1: ph.Alpha = 1; break;
            case 2: ph.Alpha = 0; break;
            default: break;
        }
        int predictor = G(rep, emon.Tiffcheck.TrPredictor());
        bool planar2 = G(rep, emon.Tiffcheck.TrPlanar()) == 1, fill2 = G(rep, emon.Tiffcheck.TrFill2()) == 1, le = G(rep, emon.Tiffcheck.TrLe()) == 1;
        bool tiled = G(rep, emon.Tiffcheck.TrTiled()) == 1;
        int cw0 = G(rep, emon.Tiffcheck.TrCw()), bandRows = G(rep, emon.Tiffcheck.TrBand());
        int across = G(rep, emon.Tiffcheck.TrAcross()), down = G(rep, emon.Tiffcheck.TrDown());
        int hs = G(rep, emon.Tiffcheck.TrHs()), vs = G(rep, emon.Tiffcheck.TrVs());
        int planes = G(rep, emon.Tiffcheck.TrPlanes());
        int offsAt = G(rep, emon.Tiffcheck.TrOffsAt()), lensAt = G(rep, emon.Tiffcheck.TrLensAt());
        int ch = G(rep, emon.Tiffcheck.TrCh());
        int ov = G(rep, emon.Tiffcheck.TrOrient());
        if (ph.Kind == 3) {
            ph.Cmap = new int[3 << bps];
            for (int i = 0; i < ph.Cmap.Length; i++) {
                ph.Cmap[i] = Port.CellsAt(rep, G(rep, emon.Tiffcheck.TrCmapAt()) + i);
            }
        }
        int perPlane = across * down;
        int cs = planar2 ? 1 : spp;
        byte[] px = new byte[w * h * ch];
        byte[] table = Lut(bps);
        Port.Cells ws = Port.NewCells();
        ushort[] samp = null;
        for (int by = 0; by < down; by++) {
            int y0 = by * bandRows;
            int rows = Math.Min(bandRows, h - y0);
            if (samp != null && samp.Length >= rows * w * spp) {
                Array.Clear(samp, 0, rows * w * spp);
            } else {
                samp = new ushort[rows * w * spp];
            }
            for (int p = 0; p < planes; p++) {
                for (int bx = 0; bx < across; bx++) {
                    int idx = p * perPlane + by * across + bx;
                    int off = Port.CellsAt(rep, offsAt + idx), n = Port.CellsAt(rep, lensAt + idx);
                    byte[] data = new byte[n];
                    Array.Copy(b, off, data, 0, n);
                    if (fill2) {
                        for (int i = 0; i < data.Length; i++) {
                            data[i] = RevByte(data[i]);
                        }
                    }
                    int cw = cw0;
                    int crows = tiled ? bandRows : rows;
                    int rowb = (cw * cs * bps + 7) / 8;
                    int expect = rowb * crows;
                    if (ph.Kind == 6) {
                        expect = ((cw + hs - 1) / hs) * ((crows + vs - 1) / vs) * (hs * vs + 2);
                    }
                    byte[] raw;
                    switch (comp) {
                        case 1: raw = data; break;
                        case 32773: raw = Packbits(data, expect); break;
                        case 5: raw = Lzw(data, expect); break;
                        case 8:
                        case 32946: raw = InflateStrip(data, expect); break;
                        case 4: raw = CcittRows(data, -1, cw, crows, 0, ws); break;
                        default: raw = CcittRows(data, 0, cw, crows, 0, ws); break;
                    }
                    if (raw.Length != expect) {
                        Array.Resize(ref raw, expect);
                    } else if (ReferenceEquals(raw, data)) {
                        raw = (byte[]) data.Clone();
                    }
                    if (predictor == 2) {
                        for (int r = 0; r < crows; r++) {
                            int rb = r * rowb;
                            if (bps == 8) {
                                for (int i = cs; i < cw * cs; i++) {
                                    raw[rb + i] = (byte) (raw[rb + i] + raw[rb + i - cs]);
                                }
                            } else {
                                for (int i = cs; i < cw * cs; i++) {
                                    int a = (U16o(raw, rb + i * 2, le) + U16o(raw, rb + (i - cs) * 2, le)) & 0xFFFF;
                                    if (le) {
                                        raw[rb + i * 2] = (byte) a;
                                        raw[rb + i * 2 + 1] = (byte) (a >> 8);
                                    } else {
                                        raw[rb + i * 2] = (byte) (a >> 8);
                                        raw[rb + i * 2 + 1] = (byte) a;
                                    }
                                }
                            }
                        }
                    }
                    int x0 = bx * cw0;
                    int ncols = Math.Min(cw0, w - x0);
                    if (ph.Kind == 6) {
                        int bw = (cw + hs - 1) / hs;
                        for (int r = 0; r < rows; r++) {
                            for (int c = 0; c < ncols; c++) {
                                int blk = (r / vs) * bw + c / hs;
                                int bas = blk * (hs * vs + 2);
                                int o = (r * w + x0 + c) * 3;
                                samp[o] = raw[bas + (r % vs) * hs + c % hs];
                                samp[o + 1] = raw[bas + hs * vs];
                                samp[o + 2] = raw[bas + hs * vs + 1];
                            }
                        }
                    } else {
                        for (int r = 0; r < rows; r++) {
                            int rb = r * rowb;
                            for (int c = 0; c < ncols; c++) {
                                for (int s = 0; s < cs; s++) {
                                    int k = c * cs + s;
                                    int v;
                                    if (bps == 8) {
                                        v = raw[rb + k];
                                    } else if (bps == 16) {
                                        v = U16o(raw, rb + k * 2, le);
                                    } else {
                                        int bit = k * bps;
                                        int acc = 0;
                                        for (int j = 0; j < bps; j++) {
                                            int q = bit + j;
                                            acc = (acc << 1) | ((raw[rb + q / 8] >> (7 - (q % 8))) & 1);
                                        }
                                        v = acc;
                                    }
                                    int si = planar2 ? p : s;
                                    samp[(r * w + x0 + c) * spp + si] = (ushort) v;
                                }
                            }
                        }
                    }
                }
            }
            for (int r = 0; r < rows; r++) {
                ConvertRow(ph, table, samp, r * w * spp, w, px, (y0 + r) * w * ch);
            }
        }
        return Orient(px, w, h, ch, ov);
    }

    private static int CompA(Photo ph, byte[] table, ushort[] s, int p, int c) {
        if (ph.Alpha < 0) {
            return c;
        }
        int a = table[s[p + ph.Nc]];
        return ph.Alpha == 1 ? OverWhitePremul(c, a) : OverWhite(c, a);
    }

    private static void ConvertRow(Photo ph, byte[] table, ushort[] s, int so, int n, byte[] d, int dobase) {
        int spp = ph.Spp;
        for (int x = 0; x < n; x++) {
            int p = so + x * spp;
            switch (ph.Kind) {
                case 0:
                case 1: {
                    int g = table[s[p]];
                    if (ph.Kind == 0) {
                        g = 255 - g;
                    }
                    d[dobase + x] = (byte) CompA(ph, table, s, p, g);
                    break;
                }
                case 2:
                    for (int k = 0; k < 3; k++) {
                        d[dobase + x * 3 + k] = (byte) CompA(ph, table, s, p, table[s[p + k]]);
                    }
                    break;
                case 3: {
                    int nn = 1 << ph.Bps;
                    int i = s[p];
                    for (int k = 0; k < 3; k++) {
                        d[dobase + x * 3 + k] = (byte) S16(ph.Cmap[k * nn + i]);
                    }
                    break;
                }
                case 5: {
                    int k8 = table[s[p + 3]];
                    for (int k = 0; k < 3; k++) {
                        int c = CmykChan(table[s[p + k]], k8);
                        d[dobase + x * 3 + k] = (byte) CompA(ph, table, s, p, c);
                    }
                    break;
                }
                default:
                    YccRgb(s[p], s[p + 1], s[p + 2], d, dobase + x * 3);
                    break;
            }
        }
    }
}
