// THE PDF IMAGE CODECS, C# host (DEVLOG P9.2, P6.1a; SPEC 7.3, 10.11; D117, D291, D332-D470). The twin of `host/java/emon/api/PdfImg.java`, `host/go/pdfimg.go` and
// `host/rust/src/pdfimg.rs`: read those for the reasoning. What a `decode-img` request (kind 6) asks for: one PDF image's CODESTREAM in, its pixels out, 8-bit, gray
// (1 channel) or RGB (3), row-major, top-left, with a `bilevel` flag. The pixels are the codestream's and nothing else: never the PDF's `/Decode`, masks or
// `/Matte` (kin's), and never an EXIF orientation (a PDF paints the codestream as it is).
//
// WHICH LIBRARY DOES WHAT:
//   DCT    the native JPEG decoder of the ImageDecode port (`ImgDec.JpegRaw`, `Jpeg.cs`); 4 components ONLY with an Adobe APP14 marker of transform 0 or 2,
//          converted to RGB honouring it.
//   CCITT  kin's `emon.ccitt` (P10.11, D396(4)): one decoder written from T.4 and T.6 serves TIFF and PDF on every host. The host only paints its packed rows
//          with `BlackIs1` and the image's size.
//   JPX, JBIG2   the embedder's `Options.IPdfCodecs`, else `unsupported`: the BCL reads neither.
// A damaged stream is FAILED; a valid one this host does not read is UNSUPPORTED; one over the pixel bound is LIMIT.

namespace emon.Api;

using System;

internal static class PdfImg {
    /// <summary>The request's fields kin writes (`dq-*`).</summary>
    internal sealed class Args {
        internal int Filter, W, H, Comps, Bpc, K, Columns, Rows;
        /// <summary>1 BlackIs1, 2 EncodedByteAlign, 4 EndOfLine, 8 EndOfBlock false</summary>
        internal int Flags;
    }

    internal static ImgDec.PdfPix Decode(Args a, byte[] data, byte[] globals, Options.IPdfCodecs codecs, long maxPixels) {
        if (a.Filter == emon.Plan.FltDct()) {
            return Dct(data, maxPixels);
        }
        if (a.Filter == emon.Plan.FltCcitt()) {
            return Ccitt(a, data, maxPixels);
        }
        if (a.Filter == emon.Plan.FltJpx()) {
            if (codecs == null) {
                throw ImgDec.Unsupportedf("JPX needs a PdfCodecs (OpenJPEG)");
            }
            return codecs.Jpx(data, maxPixels);
        }
        if (a.Filter == emon.Plan.FltJbig2()) {
            if (codecs == null) {
                throw ImgDec.Unsupportedf("JBIG2 needs a PdfCodecs (jbig2dec)");
            }
            ImgDec.PdfPix p = codecs.Jbig2(data, globals, maxPixels);
            return FitJbig2(p, a);
        }
        throw ImgDec.Unsupportedf("not an image codec");
    }

    // A JBIG2 page is the image, whatever size the dictionary says: it is made the dictionary's size, cut on the right and below and padded with white (255)
    // where the page is smaller, as a viewer paints it.
    private static ImgDec.PdfPix FitJbig2(ImgDec.PdfPix p, Args a) {
        if (p == null || p.Ch != 1 || p.Px.Length != p.W * p.H) {
            throw ImgDec.Failedf("a JBIG2 page that is not one channel of its size");
        }
        if (p.W == a.W && p.H == a.H) {
            p.Bilevel = true;
            return p;
        }
        if (a.W < 1 || a.H < 1) {
            throw ImgDec.Failedf("a JBIG2 image with no size");
        }
        byte[] o = new byte[a.W * a.H];
        Array.Fill(o, (byte) 255);
        int cw = Math.Min(a.W, p.W);
        for (int y = 0; y < Math.Min(a.H, p.H); y++) {
            Array.Copy(p.Px, y * p.W, o, y * a.W, cw);
        }
        return new ImgDec.PdfPix(a.W, a.H, 1, o, true);
    }

    // The pixels of a JPEG codestream as a PDF paints them. 4 components: an Adobe marker of transform 0 stores CMYK inverted; transform 2 is YCCK. Both give
    // `(255 - c)(255 - k) / 255` rounded of the ink the other hosts' decoders give (Go's image.CMYK).
    private static ImgDec.PdfPix Dct(byte[] data, long maxPixels) {
        if (data.Length < 4 || data[0] != 0xFF || data[1] != 0xD8) {
            throw ImgDec.Failedf("not a JPEG stream");
        }
        ImgDec.JpegMeta m = ImgDec.ReadJpegMeta(data);
        long w = m.W, h = m.H;
        if (w * h > maxPixels) {
            throw new ImgDec.Fail(ImgDec.Limit, "pixels", w * h);
        }
        if (m.Ncomp != 1 && m.Ncomp != 3 && m.Ncomp != 4) {
            throw ImgDec.Unsupportedf("a JPEG with other than one, three or four components");
        }
        if (m.Ncomp == 4 && !(m.Adobe == 0 || m.Adobe == 2)) {
            throw ImgDec.Unsupportedf("a four-component JPEG without an Adobe marker of transform 0 or 2");
        }
        int iw = (int) w, ih = (int) h;
        if (m.Ncomp != 4) {
            return new ImgDec.PdfPix(iw, ih, m.Ncomp, ImgDec.JpegRaw(data, m, iw, ih), false);
        }
        byte[] raw = ImgDec.JpegComponents(data, m, iw, ih);
        byte[] px = new byte[iw * ih * 3];
        byte[] rgb = new byte[3];
        for (int i = 0; i < iw * ih; i++) {
            int v0 = raw[4 * i], v1 = raw[4 * i + 1], v2 = raw[4 * i + 2], v3 = raw[4 * i + 3];
            int o = i * 3;
            if (m.Adobe == 0) {
                // stored inverted: the sample is (255 - ink)
                px[o] = (byte) ((v0 * v3 + 127) / 255);
                px[o + 1] = (byte) ((v1 * v3 + 127) / 255);
                px[o + 2] = (byte) ((v2 * v3 + 127) / 255);
            } else {
                // YCCK: the colour part is converted to RGB, which already is the inverted ink; the black is stored inverted too
                ImgDec.YccRgb(v0, v1, v2, rgb, 0);
                px[o] = (byte) (((255 - rgb[0]) * v3 + 127) / 255);
                px[o + 1] = (byte) (((255 - rgb[1]) * v3 + 127) / 255);
                px[o + 2] = (byte) (((255 - rgb[2]) * v3 + 127) / 255);
            }
        }
        return new ImgDec.PdfPix(iw, ih, 3, px, false);
    }

    private static ImgDec.PdfPix Ccitt(Args a, byte[] data, long maxPixels) {
        if (a.W < 1 || a.H < 1 || a.Columns < 1) {
            throw ImgDec.Failedf("a CCITT image with no size");
        }
        if ((long) a.W * a.H > maxPixels) {
            throw new ImgDec.Fail(ImgDec.Limit, "pixels", (long) a.W * a.H);
        }
        if (a.Columns > 1 << 20) {
            throw ImgDec.Unsupportedf("CCITT Columns over 1,048,576");
        }
        int w = a.W, h = a.H, cols = a.Columns;
        int rows = a.Rows > 0 ? Math.Min(a.Rows, a.H) : h;
        // the stream's own area (`Columns` need not be the image's width) is held to the pixel bound too
        if ((long) cols * rows > maxPixels) {
            throw new ImgDec.Fail(ImgDec.Limit, "pixels", (long) cols * rows);
        }
        // the one CCITT decoder (kin's `emon.ccitt`, TIFF's too): T.4 and T.6 over `K`, `Columns`, `Rows`, `EncodedByteAlign` and `EndOfLine`; the rows come
        // packed, high bit first, 1 for black
        byte[] bits = ImgDec.CcittRows(data, a.K, cols, rows, a.Flags & 6, Port.NewCells());
        int stride = (cols + 7) / 8;
        // the image's size: rows below the stream's and columns past `Columns` are white
        byte blk = 0, wht = 255;
        if ((a.Flags & 1) != 0) {
            blk = 255;
            wht = 0;
        }
        byte[] px = new byte[w * h];
        Array.Fill(px, wht);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < Math.Min(w, cols); x++) {
                if (((bits[y * stride + x / 8] >> (7 - (x % 8))) & 1) == 1) {
                    px[y * w + x] = blk;
                }
            }
        }
        return new ImgDec.PdfPix(w, h, 1, px, true);
    }
}
