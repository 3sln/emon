// THE PDF IMAGE CODECS, Java host (DEVLOG P6.1a; SPEC 7.3, 10.11; D117, D291, D332-D470). The twin of `host/go/pdfimg.go` and `host/rust/src/pdfimg.rs`: read
// those for the reasoning. What a `decode-img` request (kind 6) asks for: one PDF image's CODESTREAM in, its pixels out, 8-bit, gray (1 channel) or RGB (3),
// row-major, top-left, with a `bilevel` flag. The pixels are the codestream's and nothing else: never the PDF's `/Decode`, masks or `/Matte` (kin's), and
// never an EXIF orientation (a PDF paints the codestream as it is).
//
// WHICH LIBRARY DOES WHAT:
//   DCT    ImageIO's JPEG reader in raster mode, as the ImageDecode port reads it (`ImgDec.jpegRaw`); 4 components ONLY with an Adobe APP14 marker of
//          transform 0 or 2, converted to RGB honouring it.
//   CCITT  kin's `emon.ccitt` (P10.11, D396(4)): one decoder written from T.4 and T.6 serves TIFF and PDF on every host. The host only paints its packed rows
//          with `BlackIs1` and the image's size.
//   JPX, JBIG2   the embedder's `Options.PdfCodecs`, else `unsupported`: no JDK library reads them.
// A damaged stream is FAILED; a valid one this host does not read is UNSUPPORTED; one over the pixel bound is LIMIT.

package emon.api;

import emon.Port;

final class PdfImg {
    private PdfImg() {
    }

    /** The request's fields kin writes (`dq-*`). */
    static final class Args {
        int filter, w, h, comps, bpc, k, columns, rows;
        /** 1 BlackIs1, 2 EncodedByteAlign, 4 EndOfLine, 8 EndOfBlock false */
        int flags;
    }

    static ImgDec.PdfPix decode(Args a, byte[] data, byte[] globals, Options.PdfCodecs codecs, long maxPixels) throws ImgDec.Fail {
        if (a.filter == emon.Plan.fltDct()) {
            return dct(data, maxPixels);
        }
        if (a.filter == emon.Plan.fltCcitt()) {
            return ccitt(a, data, maxPixels);
        }
        if (a.filter == emon.Plan.fltJpx()) {
            if (codecs == null) {
                throw ImgDec.unsupported("JPX needs a PdfCodecs (OpenJPEG)");
            }
            return codecs.jpx(data, maxPixels);
        }
        if (a.filter == emon.Plan.fltJbig2()) {
            if (codecs == null) {
                throw ImgDec.unsupported("JBIG2 needs a PdfCodecs (jbig2dec)");
            }
            ImgDec.PdfPix p = codecs.jbig2(data, globals, maxPixels);
            return fitJbig2(p, a);
        }
        throw ImgDec.unsupported("not an image codec");
    }

    // A JBIG2 page is the image, whatever size the dictionary says: it is made the dictionary's size, cut on the right and below and padded with white (255)
    // where the page is smaller, as a viewer paints it.
    private static ImgDec.PdfPix fitJbig2(ImgDec.PdfPix p, Args a) throws ImgDec.Fail {
        if (p == null || p.ch != 1 || p.px.length != p.w * p.h) {
            throw ImgDec.failed("a JBIG2 page that is not one channel of its size");
        }
        if (p.w == a.w && p.h == a.h) {
            p.bilevel = true;
            return p;
        }
        if (a.w < 1 || a.h < 1) {
            throw ImgDec.failed("a JBIG2 image with no size");
        }
        byte[] out = new byte[a.w * a.h];
        java.util.Arrays.fill(out, (byte) 255);
        int cw = Math.min(a.w, p.w);
        for (int y = 0; y < Math.min(a.h, p.h); y++) {
            System.arraycopy(p.px, y * p.w, out, y * a.w, cw);
        }
        return new ImgDec.PdfPix(a.w, a.h, 1, out, true);
    }

    // The pixels of a JPEG codestream as a PDF paints them. 4 components: an Adobe marker of transform 0 stores CMYK inverted; transform 2 is YCCK. Both give
    // `(255 - c)(255 - k) / 255` rounded of the ink the other hosts' decoders give (Go's image.CMYK).
    private static ImgDec.PdfPix dct(byte[] data, long maxPixels) throws ImgDec.Fail {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            throw ImgDec.failed("not a JPEG stream");
        }
        ImgDec.JpegMeta m = ImgDec.jpegMeta(data);
        long w = m.w, h = m.h;
        if (w * h > maxPixels) {
            throw new ImgDec.Fail(ImgDec.LIMIT, "pixels", w * h);
        }
        if (m.ncomp != 1 && m.ncomp != 3 && m.ncomp != 4) {
            throw ImgDec.unsupported("a JPEG with other than one, three or four components");
        }
        if (m.ncomp == 4 && !(m.adobe == 0 || m.adobe == 2)) {
            throw ImgDec.unsupported("a four-component JPEG without an Adobe marker of transform 0 or 2");
        }
        int iw = (int) w, ih = (int) h;
        if (m.ncomp != 4) {
            return new ImgDec.PdfPix(iw, ih, m.ncomp, ImgDec.jpegRaw(data, m, iw, ih), false);
        }
        java.awt.image.Raster ras = ImgDec.jpegRaster(data, m, iw, ih);
        byte[] px = new byte[iw * ih * 3];
        int[] row = new int[iw * 4];
        byte[] rgb = new byte[3];
        for (int y = 0; y < ih; y++) {
            ras.getPixels(0, y, iw, 1, row);
            for (int x = 0; x < iw; x++) {
                int v0 = row[4 * x], v1 = row[4 * x + 1], v2 = row[4 * x + 2], v3 = row[4 * x + 3];
                int o = (y * iw + x) * 3;
                if (m.adobe == 0) {
                    // stored inverted: the sample is (255 - ink)
                    px[o] = (byte) ((v0 * v3 + 127) / 255);
                    px[o + 1] = (byte) ((v1 * v3 + 127) / 255);
                    px[o + 2] = (byte) ((v2 * v3 + 127) / 255);
                } else {
                    // YCCK: the colour part is converted to RGB, which already is the inverted ink; the black is stored inverted too
                    ImgDec.yccRgb(v0, v1, v2, rgb, 0);
                    px[o] = (byte) (((255 - (rgb[0] & 0xFF)) * v3 + 127) / 255);
                    px[o + 1] = (byte) (((255 - (rgb[1] & 0xFF)) * v3 + 127) / 255);
                    px[o + 2] = (byte) (((255 - (rgb[2] & 0xFF)) * v3 + 127) / 255);
                }
            }
        }
        return new ImgDec.PdfPix(iw, ih, 3, px, false);
    }

    private static ImgDec.PdfPix ccitt(Args a, byte[] data, long maxPixels) throws ImgDec.Fail {
        if (a.w < 1 || a.h < 1 || a.columns < 1) {
            throw ImgDec.failed("a CCITT image with no size");
        }
        if ((long) a.w * a.h > maxPixels) {
            throw new ImgDec.Fail(ImgDec.LIMIT, "pixels", (long) a.w * a.h);
        }
        if (a.columns > 1 << 20) {
            throw ImgDec.unsupported("CCITT Columns over 1,048,576");
        }
        int w = a.w, h = a.h, cols = a.columns;
        int rows = a.rows > 0 ? Math.min(a.rows, a.h) : h;
        // the stream's own area (`Columns` need not be the image's width) is held to the pixel bound too
        if ((long) cols * rows > maxPixels) {
            throw new ImgDec.Fail(ImgDec.LIMIT, "pixels", (long) cols * rows);
        }
        // the one CCITT decoder (kin's `emon.ccitt`, TIFF's too): T.4 and T.6 over `K`, `Columns`, `Rows`, `EncodedByteAlign` and `EndOfLine`; the rows come
        // packed, high bit first, 1 for black
        byte[] bits = ImgDec.ccitt(data, a.k, cols, rows, a.flags & 6, Port.newCells());
        int stride = (cols + 7) / 8;
        // the image's size: rows below the stream's and columns past `Columns` are white
        byte blk = 0, wht = (byte) 255;
        if ((a.flags & 1) != 0) {
            blk = (byte) 255;
            wht = 0;
        }
        byte[] px = new byte[w * h];
        java.util.Arrays.fill(px, wht);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < Math.min(w, cols); x++) {
                if (((bits[y * stride + x / 8] >> (7 - (x % 8))) & 1) == 1) {
                    px[y * w + x] = blk;
                }
            }
        }
        return new ImgDec.PdfPix(w, h, 1, px, true);
    }
}
