// THE `ImageDecode` PORT, Java host (DEVLOG P9.1, P6.1; SPEC 7.2, 7.3; D058, D089, D187-D190, D444-D448). The twin of `host/go/imgdec.go` and
// `host/rust/src/imgdec.rs`: read those for the reasoning. Encoded bytes (PNG, JPEG, TIFF) in, one frame's pixels out, normalised to the Bitmap contract:
// 8-bit gray or RGB, row-major, top-left, EXIF orientation applied, alpha composited onto white, resolution in dots per inch x100 (0 = unknown), a `bilevel`
// flag for one-bit gray. It never resamples.
//
// What an image may contain is kin's (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` parse the container, decide, apply the limits and report;
// the JDK's ImageIO PNG reader is shown only input they accepted, and only the chunks that matter (`clean`). JPEG pixels come from ImageIO's JPEG reader in
// RASTER mode (the components, not a colour conversion: libjpeg's own upsampling and inverse DCT) and the YCbCr to RGB step is ours, the same integer
// arithmetic as the other hosts (D190: JPEG may differ in the last bits). The reduction to 8 bits, alpha and orientation are ours and the same as Rust's
// and Go's. TIFF samples are unpacked from the strips and tiles the validator listed: LZW, PackBits and Deflate (the JDK has no LZW or PackBits; its
// Inflater is used), the predictor, bit unpacking and colour; CCITT G3 and G4 are kin's (`emon.ccitt`, the decoder PDF uses too). What it refuses is
// UNSUPPORTED, a damaged file FAILED, a frame over the pixel bound LIMIT.
//
// NOTHING HERE NAMES A FILESYSTEM (D058): a source is a ByteSource.

package emon.api;

import emon.Imgcheck;
import emon.Pngcheck;
import emon.Port;
import emon.Tiffcheck;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

public final class ImgDec {
    private ImgDec() {
    }

    public static final int FAILED = 0, UNSUPPORTED = 1, LIMIT = 2;

    /** Why a frame was not decoded. */
    public static final class Fail extends Exception {
        private static final long serialVersionUID = 1L;
        public final int kind;
        /** LIMIT: the pixels the frame has (Long.MAX_VALUE when more than a long holds). */
        public final long pixels;

        public Fail(int kind, String msg) {
            this(kind, msg, 0);
        }

        public Fail(int kind, String msg, long pixels) {
            super(msg, null, false, false);
            this.kind = kind;
            this.pixels = pixels;
        }
    }

    /** One decoded frame (or only its header). */
    public static final class Decoded {
        public int w, h, ch; // the size AFTER orientation; 1 gray or 3 RGB
        public int dpi100;
        public boolean bilevel;
        public int frames;
        public byte[] pixels; // w*h*ch bytes; null when only the header was asked for
    }

    /** What a PDF image codec made (`PdfImg`): W * H * Ch bytes. */
    public static final class PdfPix {
        public int w, h, ch;
        public byte[] px;
        public boolean bilevel;

        public PdfPix(int w, int h, int ch, byte[] px, boolean bilevel) {
            this.w = w;
            this.h = h;
            this.ch = ch;
            this.px = px;
            this.bilevel = bilevel;
        }
    }

    static Fail failed(String s) {
        return new Fail(FAILED, s);
    }

    static Fail unsupported(String s) {
        return new Fail(UNSUPPORTED, s);
    }

    private static final int NONE = 0, PNG = 1, JPEG = 2, TIFF = 3;

    private static int sniff(byte[] h, int n) {
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G' && h[4] == 0x0D && h[5] == 0x0A
                && h[6] == 0x1A && h[7] == 0x0A) {
            return PNG;
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if (n >= 4 && ((h[0] == 'I' && h[1] == 'I' && h[2] == 42 && h[3] == 0) || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 42))) {
            return TIFF;
        }
        return NONE;
    }

    /**
     * Decode a frame: {@code frame >= 0} is that frame's pixels; {@code frame < 0} only the header of frame {@code -frame-1}. A frame over the PIXELS limit
     * (limit-of 9) is a LIMIT failure. Throws nothing but {@link Fail}.
     */
    public static Decoded decode(ByteSource src, int frame) throws Fail {
        try {
            return decode0(src, frame);
        } catch (Fail f) {
            throw f;
        } catch (IOException e) {
            throw failed("a read of the source failed");
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
            if (e instanceof Port.Breach) {
                throw (Port.Breach) e;
            }
            throw failed("the decoder failed on this input: " + e);
        }
    }

    private static Decoded decode0(ByteSource src, int frame) throws Fail, IOException {
        byte[] head = new byte[8];
        int n = readAtMost(src, 0, head, 0, 8);
        boolean want = frame >= 0;
        int f = want ? frame : -frame - 1;
        switch (sniff(head, n)) {
            case PNG:
                if (f != 0) {
                    throw failed("this format has one frame");
                }
                return png(src, want);
            case JPEG:
                if (f != 0) {
                    throw failed("this format has one frame");
                }
                return jpeg(src, want);
            case TIFF:
                return tiff(src, f, want);
            default:
                throw failed("not a PNG, JPEG or TIFF");
        }
    }

    private static int readAtMost(ByteSource src, long off, byte[] buf, int boff, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int k = src.readAt(off + got, buf, boff + got, len - got);
            if (k <= 0) {
                break;
            }
            got += k;
        }
        return got;
    }

    private static byte[] readAll(ByteSource src) throws Fail, IOException {
        long n = src.size();
        if (n < 0 || n > Integer.MAX_VALUE - 16) {
            throw failed("a read past the end of the input");
        }
        byte[] v = new byte[(int) n];
        if (readAtMost(src, 0, v, 0, v.length) != v.length) {
            throw failed("the input is shorter than it said");
        }
        return v;
    }

    // ------------------------------------------------------------- small integer helpers

    // n-bit sample -> 8 bits, rounded: (v * 255 + max / 2) / max (D188).
    private static byte[] lut(int b) {
        long max = (1L << b) - 1;
        byte[] t = new byte[(int) max + 1];
        for (long v = 0; v <= max; v++) {
            t[(int) v] = (byte) ((v * 255 + max / 2) / max);
        }
        return t;
    }

    private static int s16(int v) {
        return (int) (((long) v * 255 + 32767) / 65535);
    }

    private static int overWhite(int c, int a) {
        return (c * a + 255 * (255 - a) + 127) / 255;
    }

    private static int overWhitePremul(int c, int a) {
        return c + 255 - a > 255 ? 255 : c + 255 - a;
    }

    private static int cmykChan(int v, int k) {
        return ((255 - v) * (255 - k) + 127) / 255;
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static void yccRgb(int y, int cb, int cr, byte[] d, int o) {
        cb -= 128;
        cr -= 128;
        d[o] = (byte) clamp8(y + ((91881 * cr + 32768) >> 16));
        d[o + 1] = (byte) clamp8(y - ((22554 * cb + 46802 * cr + 32768) >> 16));
        d[o + 2] = (byte) clamp8(y + ((116130 * cb + 32768) >> 16));
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    // ------------------------------------------------------------------ orientation

    private static byte[] orient(byte[] d, int w, int h, int ch, long o) {
        if (o <= 1 || o > 8) {
            return d;
        }
        int ow = w, oh = h;
        if (o >= 5) {
            ow = h;
            oh = w;
        }
        byte[] out = new byte[d.length];
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
                System.arraycopy(d, (sy * w + sx) * ch, out, (y * ow + x) * ch, ch);
            }
        }
        return out;
    }

    // --------------------------------------------------------------- the validators (kin)
    //
    // WHAT AN IMAGE MAY CONTAIN IS KIN'S (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` read the container, decide, apply the pixel and byte limits and
    // report what the host needs; a platform library is asked for pixels only of input they accepted. CCITT G3 and G4 are kin's (`emon.ccitt`).

    // turns a validator's status into the port's failure
    private static Fail refused(int status, String what, int limit) {
        if (status == Imgcheck.icUnsupported()) {
            return unsupported(what);
        }
        if (status == Imgcheck.icLimit()) {
            return new Fail(LIMIT, "pixels", limit & 0xFFFFFFFFL);
        }
        return failed(what);
    }

    // what the first directory of an EXIF blob says: orientation and the horizontal and vertical resolution (dpi x100, 0 unknown)
    private static long[] exif(byte[] blob) {
        Port.Cells r = Port.newCells();
        Tiffcheck.tcExif(blob, 0, blob.length, r);
        return new long[] {Port.cellsAt(r, Tiffcheck.trEo()), Port.cellsAt(r, Tiffcheck.trEx()), Port.cellsAt(r, Tiffcheck.trEy())};
    }

    // ------------------------------------------------------------------------ PNG

    private static Decoded png(ByteSource src, boolean want) throws Fail, IOException {
        byte[] b = readAll(src);
        Port.Cells r = Port.newCells(), st = Port.newCells(), out = Port.newCells();
        Port.Buf idat = Port.newBuf(), clean = Port.newBuf();
        int s = Pngcheck.pcCheck(b, want ? 1 : 0, r, st, out, idat, clean);
        if (s != Imgcheck.icOk()) {
            throw refused(s, "a PNG the validator refused: damaged, or past the rules of D389", Port.cellsAt(r, Pngcheck.prLimit()));
        }
        int w = Port.cellsAt(r, Pngcheck.prW()), h = Port.cellsAt(r, Pngcheck.prH());
        int ch = Port.cellsAt(r, Pngcheck.prCh());
        int o = Port.cellsAt(r, Pngcheck.prOrient());
        Decoded res = new Decoded();
        res.w = Port.cellsAt(r, Pngcheck.prOw());
        res.h = Port.cellsAt(r, Pngcheck.prOh());
        res.ch = ch;
        res.dpi100 = Port.cellsAt(r, Pngcheck.prDpi());
        res.bilevel = Port.cellsAt(r, Pngcheck.prBilevel()) != 0;
        res.frames = 1;
        if (!want) {
            return res;
        }
        // the library is shown the chunks that matter, whole and in order, and nothing else (`clean`)
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(Port.bufBlob(clean)));
        } catch (IOException | RuntimeException e) {
            throw failed("a PNG the decoder could not read: " + e.getMessage());
        }
        if (img == null) {
            throw failed("a PNG the decoder could not read");
        }
        int iw = img.getWidth(), ih = img.getHeight();
        if (iw != w || ih != h) {
            throw failed("a PNG whose pixels are not the size its header says");
        }
        boolean gray = ch == 1;
        ColorModel cm = img.getColorModel();
        Raster ras = img.getRaster();
        byte[] px = new byte[iw * ih * ch];
        if (cm instanceof IndexColorModel) {
            // a palette, or a gray of up to four bits (ImageIO gives that one a gray palette): colour and alpha straight (not premultiplied)
            IndexColorModel icm = (IndexColorModel) cm;
            int size = icm.getMapSize();
            int[] argb = new int[size];
            icm.getRGBs(argb);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    int idx = ras.getSample(x, y, 0);
                    if (idx >= size) {
                        throw failed("a PNG pixel outside its palette");
                    }
                    int v = argb[idx];
                    int a = v >>> 24;
                    if (gray) {
                        px[y * iw + x] = (byte) overWhite((v >> 16) & 0xFF, a);
                    } else {
                        for (int k = 0; k < 3; k++) {
                            px[(y * iw + x) * 3 + k] = (byte) overWhite((v >> (16 - 8 * k)) & 0xFF, a);
                        }
                    }
                }
            }
        } else {
            int nb = ras.getNumBands();
            boolean alpha = cm.hasAlpha();
            int colour = alpha ? nb - 1 : nb;
            if (colour != (gray ? 1 : 3)) {
                throw failed("a PNG the decoder gave in a form that does not match its header");
            }
            boolean wide = ras.getSampleModel().getSampleSize(0) == 16;
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    int a = 255;
                    if (alpha) {
                        int sa = ras.getSample(x, y, colour);
                        a = wide ? s16(sa) : sa;
                    }
                    for (int k = 0; k < colour; k++) {
                        int sc = ras.getSample(x, y, k);
                        int c = wide ? s16(sc) : sc;
                        px[(y * iw + x) * ch + k] = (byte) (alpha ? overWhite(c, a) : c);
                    }
                }
            }
        }
        res.pixels = orient(px, iw, ih, ch, o);
        return res;
    }

    // ----------------------------------------------------------------------- JPEG

    /** What a JPEG's markers say, as far as the port needs: the frame, the resolution, the EXIF blob and the Adobe transform. */
    static final class JpegMeta {
        int w, h, ncomp, dx, dy, adobe = -1;
        byte[] exif;
        int lastSos = -1;
    }

    static JpegMeta jpegMeta(byte[] b) throws Fail {
        JpegMeta m = new JpegMeta();
        boolean sof = false;
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int mk = b[i + 1] & 0xFF;
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
                    m.lastSos = i;
                }
                break;
            }
            int l = u16(b, i + 2);
            if (l < 2 || i + 2 + l > b.length) {
                break;
            }
            int bo = i + 4, bl = l - 2;
            if (mk == 0xE0 && bl >= 12 && b[bo] == 'J' && b[bo + 1] == 'F' && b[bo + 2] == 'I' && b[bo + 3] == 'F' && b[bo + 4] == 0) {
                int units = b[bo + 7] & 0xFF;
                long xd = u16(b, bo + 8), yd = u16(b, bo + 10);
                if (units == 1) {
                    m.dx = (int) (xd * 100);
                    m.dy = (int) (yd * 100);
                } else if (units == 2) {
                    m.dx = (int) (xd * 254);
                    m.dy = (int) (yd * 254);
                } else {
                    m.dx = 0;
                    m.dy = 0;
                }
            } else if (mk == 0xE1 && bl > 6 && b[bo] == 'E' && b[bo + 1] == 'x' && b[bo + 2] == 'i' && b[bo + 3] == 'f'
                    && b[bo + 4] == 0 && b[bo + 5] == 0 && m.exif == null) {
                m.exif = Arrays.copyOfRange(b, bo + 6, bo + bl);
            } else if (mk == 0xEE && bl >= 12 && b[bo] == 'A' && b[bo + 1] == 'd' && b[bo + 2] == 'o' && b[bo + 3] == 'b' && b[bo + 4] == 'e'
                    && m.adobe < 0) {
                m.adobe = b[bo + 11] & 0xFF;
            } else if (mk >= 0xC0 && mk <= 0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC && !sof) {
                if (bl >= 6) {
                    m.h = u16(b, bo + 1);
                    m.w = u16(b, bo + 3);
                    m.ncomp = b[bo + 5] & 0xFF;
                    sof = true;
                }
            }
            i += 2 + l;
        }
        if (!sof || m.w == 0 || m.h == 0) {
            throw failed("a JPEG without a usable frame header");
        }
        return m;
    }

    private static Decoded jpeg(ByteSource src, boolean want) throws Fail, IOException {
        long maxPixels = Port.limitOf(9);
        byte[] b = readAll(src);
        JpegMeta m = jpegMeta(b);
        if (m.ncomp != 1 && m.ncomp != 3) {
            throw unsupported("a JPEG with other than one or three components (CMYK: P6.1a)");
        }
        long o = 1;
        int ex = 0, ey = 0;
        if (m.exif != null) {
            long[] e = exif(m.exif);
            o = e[0];
            ex = (int) e[1];
            ey = (int) e[2];
        }
        long w = m.w, h = m.h;
        long ow = w, oh = h;
        if (o >= 5) {
            ow = h;
            oh = w;
        }
        int dx = m.dx, dy = m.dy;
        if (dx <= 0) {
            dx = ex;
            dy = ey;
        }
        Decoded out = new Decoded();
        out.w = (int) ow;
        out.h = (int) oh;
        out.ch = m.ncomp;
        out.frames = 1;
        out.dpi100 = o >= 5 ? dy : dx;
        if (w * h > maxPixels) {
            throw new Fail(LIMIT, "pixels", w * h);
        }
        if (!want) {
            return out;
        }
        byte[] px = jpegRaw(b, m, (int) w, (int) h);
        out.pixels = orient(px, (int) w, (int) h, m.ncomp, o);
        return out;
    }

    /**
     * The pixels of a decoded 1 or 3 component JPEG: gray, or RGB (the chroma filtered as libjpeg does). It applies no orientation: the ImageDecode port
     * orients after it, a PDF image (`PdfImg`) never does. A stream that stops before its end-of-image marker is damaged (Go's decoder says so; libjpeg only warns).
     */
    static byte[] jpegRaw(byte[] b, JpegMeta m, int w, int h) throws Fail {
        Raster ras = jpegRaster(b, m, w, h);
        int ncomp = m.ncomp;
        byte[] px = new byte[w * h * ncomp];
        int[] row = new int[w * ncomp];
        for (int y = 0; y < h; y++) {
            ras.getPixels(0, y, w, 1, row);
            if (ncomp == 1) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = (byte) row[x];
                }
            } else if (m.adobe == 0) {
                // an Adobe marker of transform 0 on three components: the samples are RGB
                for (int x = 0; x < w * 3; x++) {
                    px[y * w * 3 + x] = (byte) row[x];
                }
            } else {
                for (int x = 0; x < w; x++) {
                    yccRgb(row[3 * x], row[3 * x + 1], row[3 * x + 2], px, (y * w + x) * 3);
                }
            }
        }
        return px;
    }

    /** The components of a JPEG as ImageIO's raster gives them (no colour conversion), checked against the header's size and component count. */
    static Raster jpegRaster(byte[] b, JpegMeta m, int w, int h) throws Fail {
        int eoi = -1;
        for (int k = b.length - 2; k > m.lastSos && k >= 0; k--) {
            if ((b[k] & 0xFF) == 0xFF && (b[k + 1] & 0xFF) == 0xD9) {
                eoi = k;
                break;
            }
        }
        if (m.lastSos < 0 || eoi < 0) {
            throw failed("unexpected EOF in a JPEG");
        }
        Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("jpeg");
        if (!it.hasNext()) {
            throw failed("no JPEG reader in this JDK");
        }
        ImageReader r = it.next();
        Raster ras;
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(b))) {
            r.setInput(in, true, true);
            ras = r.readRaster(0, null);
        } catch (IOException | RuntimeException e) {
            throw failed("a JPEG the decoder could not read: " + e.getMessage());
        } finally {
            r.dispose();
        }
        if (ras.getWidth() != w || ras.getHeight() != h || ras.getNumBands() != m.ncomp) {
            throw failed("a JPEG whose pixels are not the size its header says");
        }
        return ras;
    }

    // ----------------------------------------------------------------------- TIFF

    private static byte[] packbits(byte[] d, int expect) {
        byte[] out = new byte[expect];
        int n = 0;
        int i = 0;
        while (i < d.length && n < expect) {
            int c = d[i++];
            if (c >= 0) {
                int cnt = c + 1;
                int e = Math.min(i + cnt, d.length);
                int k = Math.min(e - i, expect - n);
                System.arraycopy(d, i, out, n, k);
                n += k;
                i = e;
            } else if (c != -128) {
                if (i >= d.length) {
                    break;
                }
                int cnt = 1 - c;
                for (int j = 0; j < cnt && n < expect; j++) {
                    out[n++] = d[i];
                }
                i++;
            }
        }
        return n == expect ? out : Arrays.copyOf(out, n);
    }

    // TIFF LZW: MSB first with early change, or the pre-1988 LSB stream (libtiff's "compat").
    private static byte[] lzw(byte[] d, int expect) throws Fail {
        boolean compat = d.length >= 2 && d[0] == 0 && (d[1] & 1) == 1;
        int early = compat ? 0 : 1;
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] out = new byte[Math.max(expect, 0) + 4096];
        int n = 0;
        int next = 258;
        int width = 9;
        long acc = 0;
        int nbits = 0;
        int pos = 0;
        int prev = -1;
        byte[] stack = new byte[4097];
        outer:
        for (;;) {
            while (nbits < width) {
                if (pos >= d.length) {
                    break outer;
                }
                if (compat) {
                    acc |= (long) (d[pos] & 0xFF) << nbits;
                } else {
                    acc = (acc << 8) | (d[pos] & 0xFF);
                }
                pos++;
                nbits += 8;
            }
            int code;
            if (compat) {
                code = (int) (acc & ((1L << width) - 1));
                acc >>>= width;
                nbits -= width;
            } else {
                code = (int) ((acc >>> (nbits - width)) & ((1L << width) - 1));
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
            boolean extra = false;
            byte first;
            if (prev < 0) {
                if (code > 255) {
                    throw failed("a bad LZW code");
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
                    throw failed("a bad LZW code");
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
            if (n + sp + 1 > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, n + sp + 1));
            }
            for (int i = sp - 1; i >= 0; i--) {
                out[n++] = stack[i];
            }
            if (extra) {
                out[n++] = first;
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
        return Arrays.copyOf(out, Math.min(n, expect));
    }

    private static byte[] inflate(byte[] d, int expect) throws Fail {
        Inflater inf = new Inflater();
        try {
            inf.setInput(d);
            byte[] out = new byte[expect];
            int n = 0;
            // The same three answers as the Rust host's `miniz_oxide`: a stream that ends cleanly (its checksum right) early gives what it has, one that
            // fills `expect` bytes has its end read too (so a wrong checksum is seen), and one that has MORE is cut at `expect` without it; any damage is `failed`.
            while (n < expect) {
                int k = inf.inflate(out, n, expect - n);
                n += k;
                if (inf.finished()) {
                    return n == expect ? out : Arrays.copyOf(out, n);
                }
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    throw failed("a bad Deflate stream");
                }
            }
            if (!inf.finished()) {
                byte[] one = new byte[1];
                int k = inf.inflate(one, 0, 1);
                if (k == 0 && !inf.finished() && (inf.needsInput() || inf.needsDictionary())) {
                    throw failed("a bad Deflate stream");
                }
            }
            return out;
        } catch (DataFormatException e) {
            throw failed("a bad Deflate stream");
        } finally {
            inf.end();
        }
    }

    /**
     * kin's one CCITT decoder (`emon.ccitt`, TIFF's and PDF's): `rows` rows of `cw` pixels, bits packed MSB first, 1 = black, a row to a whole number of
     * bytes. The decoder needs a ZEROED Buf of rows * stride bytes (D447). `ws` is its scratch, kept for every strip of an image.
     */
    static byte[] ccitt(byte[] d, int k, int cw, int rows, int flags, Port.Cells ws) throws Fail {
        Port.Buf out = Port.newBuf();
        int total = ((cw + 7) / 8) * rows;
        Port.bufAppendBlob(out, new byte[total], 0, total);
        if (emon.Ccitt.ccDecode(d, 0, d.length, k, cw, rows, flags, out, ws) != emon.Ccitt.ccOk()) {
            throw failed("a short or damaged CCITT stream");
        }
        return Port.bufBlob(out);
    }

    private static final class Photo {
        int kind, spp, nc, bps, alpha;
        int[] cmap;
    }

    /**
     * The whole file is read, `emon.tiffcheck` decides (the byte order, the chain of frames, every tag, the bounds of every strip and tile, the pixel limit)
     * and reports; what is left here is turning the chunks it listed into samples and the samples into the 8-bit pixels of D188. A multi-page TIFF reads only
     * the frame asked for.
     */
    private static Decoded tiff(ByteSource src, int f, boolean want) throws Fail, IOException {
        byte[] b = readAll(src);
        Port.Cells r = Port.newCells();
        int s = Tiffcheck.tcCheck(b, f, want ? 1 : 0, r);
        if (s != Imgcheck.icOk()) {
            throw refused(s, "a TIFF the validator refused: damaged, or a coding the port does not read (D188)", Port.cellsAt(r, Tiffcheck.trLimit()));
        }
        Decoded out = new Decoded();
        out.w = Port.cellsAt(r, Tiffcheck.trOw());
        out.h = Port.cellsAt(r, Tiffcheck.trOh());
        out.ch = Port.cellsAt(r, Tiffcheck.trCh());
        out.dpi100 = Port.cellsAt(r, Tiffcheck.trDpi());
        out.bilevel = Port.cellsAt(r, Tiffcheck.trBilevel()) != 0;
        out.frames = Port.cellsAt(r, Tiffcheck.trFrames());
        if (want) {
            out.pixels = tiffPixels(b, r);
        }
        return out;
    }

    private static int u16o(byte[] row, int at, boolean le) {
        return le ? ((row[at + 1] & 0xFF) << 8) | (row[at] & 0xFF) : ((row[at] & 0xFF) << 8) | (row[at + 1] & 0xFF);
    }

    private static byte[] tiffPixels(byte[] b, Port.Cells rep) throws Fail {
        int w = g(rep, Tiffcheck.trW()), h = g(rep, Tiffcheck.trH());
        int spp = g(rep, Tiffcheck.trSpp()), bps = g(rep, Tiffcheck.trBps());
        int comp = g(rep, Tiffcheck.trComp());
        Photo ph = new Photo();
        ph.kind = g(rep, Tiffcheck.trKind());
        ph.nc = g(rep, Tiffcheck.trNc());
        ph.spp = spp;
        ph.bps = bps;
        ph.alpha = -1;
        switch (g(rep, Tiffcheck.trAlpha())) {
            case 1: ph.alpha = 1; break;
            case 2: ph.alpha = 0; break;
            default: break;
        }
        int predictor = g(rep, Tiffcheck.trPredictor());
        boolean planar2 = g(rep, Tiffcheck.trPlanar()) == 1, fill2 = g(rep, Tiffcheck.trFill2()) == 1, le = g(rep, Tiffcheck.trLe()) == 1;
        boolean tiled = g(rep, Tiffcheck.trTiled()) == 1;
        int cw0 = g(rep, Tiffcheck.trCw()), bandRows = g(rep, Tiffcheck.trBand());
        int across = g(rep, Tiffcheck.trAcross()), down = g(rep, Tiffcheck.trDown());
        int hs = g(rep, Tiffcheck.trHs()), vs = g(rep, Tiffcheck.trVs());
        int planes = g(rep, Tiffcheck.trPlanes());
        int offsAt = g(rep, Tiffcheck.trOffsAt()), lensAt = g(rep, Tiffcheck.trLensAt());
        int ch = g(rep, Tiffcheck.trCh());
        int ov = g(rep, Tiffcheck.trOrient());
        if (ph.kind == 3) {
            ph.cmap = new int[3 << bps];
            for (int i = 0; i < ph.cmap.length; i++) {
                ph.cmap[i] = Port.cellsAt(rep, g(rep, Tiffcheck.trCmapAt()) + i);
            }
        }
        int perPlane = across * down;
        int cs = planar2 ? 1 : spp;
        byte[] px = new byte[w * h * ch];
        byte[] table = lut(bps);
        Port.Cells ws = Port.newCells();
        char[] samp = null;
        for (int by = 0; by < down; by++) {
            int y0 = by * bandRows;
            int rows = Math.min(bandRows, h - y0);
            if (samp != null && samp.length >= rows * w * spp) {
                Arrays.fill(samp, 0, rows * w * spp, (char) 0);
            } else {
                samp = new char[rows * w * spp];
            }
            for (int p = 0; p < planes; p++) {
                for (int bx = 0; bx < across; bx++) {
                    int idx = p * perPlane + by * across + bx;
                    int off = Port.cellsAt(rep, offsAt + idx), n = Port.cellsAt(rep, lensAt + idx);
                    byte[] data = Arrays.copyOfRange(b, off, off + n);
                    if (fill2) {
                        for (int i = 0; i < data.length; i++) {
                            data[i] = (byte) (Integer.reverse(data[i] & 0xFF) >>> 24);
                        }
                    }
                    int cw = cw0;
                    int crows = tiled ? bandRows : rows;
                    int rowb = (cw * cs * bps + 7) / 8;
                    int expect = rowb * crows;
                    if (ph.kind == 6) {
                        expect = ((cw + hs - 1) / hs) * ((crows + vs - 1) / vs) * (hs * vs + 2);
                    }
                    byte[] raw;
                    switch (comp) {
                        case 1: raw = data; break;
                        case 32773: raw = packbits(data, expect); break;
                        case 5: raw = lzw(data, expect); break;
                        case 8:
                        case 32946: raw = inflate(data, expect); break;
                        case 4: raw = ccitt(data, -1, cw, crows, 0, ws); break;
                        default: raw = ccitt(data, 0, cw, crows, 0, ws); break;
                    }
                    if (raw.length < expect) {
                        raw = Arrays.copyOf(raw, expect);
                    } else if (raw.length > expect) {
                        raw = Arrays.copyOf(raw, expect);
                    }
                    if (predictor == 2) {
                        for (int r = 0; r < crows; r++) {
                            int rb = r * rowb;
                            if (bps == 8) {
                                for (int i = cs; i < cw * cs; i++) {
                                    raw[rb + i] += raw[rb + i - cs];
                                }
                            } else {
                                for (int i = cs; i < cw * cs; i++) {
                                    int a = (u16o(raw, rb + i * 2, le) + u16o(raw, rb + (i - cs) * 2, le)) & 0xFFFF;
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
                    int ncols = Math.min(cw0, w - x0);
                    if (ph.kind == 6) {
                        int bw = (cw + hs - 1) / hs;
                        for (int r = 0; r < rows; r++) {
                            for (int c = 0; c < ncols; c++) {
                                int blk = (r / vs) * bw + c / hs;
                                int base = blk * (hs * vs + 2);
                                int o = (r * w + x0 + c) * 3;
                                samp[o] = (char) (raw[base + (r % vs) * hs + c % hs] & 0xFF);
                                samp[o + 1] = (char) (raw[base + hs * vs] & 0xFF);
                                samp[o + 2] = (char) (raw[base + hs * vs + 1] & 0xFF);
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
                                        v = raw[rb + k] & 0xFF;
                                    } else if (bps == 16) {
                                        v = u16o(raw, rb + k * 2, le);
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
                                    samp[(r * w + x0 + c) * spp + si] = (char) v;
                                }
                            }
                        }
                    }
                }
            }
            for (int r = 0; r < rows; r++) {
                convertRow(ph, table, samp, r * w * spp, w, px, (y0 + r) * w * ch);
            }
        }
        return orient(px, w, h, ch, ov);
    }

    private static int g(Port.Cells rep, int i) {
        return Port.cellsAt(rep, i);
    }

    private static int comp(Photo ph, byte[] table, char[] s, int p, int c) {
        if (ph.alpha < 0) {
            return c;
        }
        int a = table[s[p + ph.nc]] & 0xFF;
        return ph.alpha == 1 ? overWhitePremul(c, a) : overWhite(c, a);
    }

    private static void convertRow(Photo ph, byte[] table, char[] s, int so, int n, byte[] d, int dobase) {
        int spp = ph.spp;
        for (int x = 0; x < n; x++) {
            int p = so + x * spp;
            switch (ph.kind) {
                case 0: case 1: {
                    int g = table[s[p]] & 0xFF;
                    if (ph.kind == 0) {
                        g = 255 - g;
                    }
                    d[dobase + x] = (byte) comp(ph, table, s, p, g);
                    break;
                }
                case 2:
                    for (int k = 0; k < 3; k++) {
                        d[dobase + x * 3 + k] = (byte) comp(ph, table, s, p, table[s[p + k]] & 0xFF);
                    }
                    break;
                case 3: {
                    int nn = 1 << ph.bps;
                    int i = s[p];
                    for (int k = 0; k < 3; k++) {
                        d[dobase + x * 3 + k] = (byte) s16(ph.cmap[k * nn + i]);
                    }
                    break;
                }
                case 5: {
                    int k8 = table[s[p + 3]] & 0xFF;
                    for (int k = 0; k < 3; k++) {
                        int c = cmykChan(table[s[p + k]] & 0xFF, k8);
                        d[dobase + x * 3 + k] = (byte) comp(ph, table, s, p, c);
                    }
                    break;
                }
                default:
                    yccRgb(s[p], s[p + 1], s[p + 2], d, dobase + x * 3);
            }
        }
    }
}
