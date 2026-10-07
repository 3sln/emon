package emon

// THE PDF IMAGE CODECS, Go host (DEVLOG P6.1a; SPEC 7.3, 10.11; D117, D291, D332-D470). What a `decode-img` request (kind 6) asks
// for: one PDF image's CODESTREAM in, its pixels out, 8-bit, gray (1 channel) or RGB (3), row-major, top-left, with a `bilevel`
// flag. The pixels are the codestream's and nothing else: never the PDF's `/Decode`, masks or `/Matte` (kin's), and never an EXIF
// orientation (a PDF paints the codestream as it is).
//
// THE TWIN IS `host/rust/src/pdfimg.rs`. WHICH LIBRARY DOES WHAT:
//
//	DCT    `image/jpeg`. 1 and 3 components as the ImageDecode port reads them (libjpeg's chroma filter, `imgUpsample`); 4
//	       components ONLY with an Adobe APP14 marker of transform 0 or 2, converted to RGB honouring it (`pdfDCT`).
//	CCITT  `golang.org/x/image/ccitt`: Group 4 (with `EncodedByteAlign`) and Group 3 one-dimensional with EOLs. Group 3
//	       two-dimensional (K > 0) is `unsupported`, a Group 3 stream without EOLs fails: THE TWO HOSTS AGREE WHERE BOTH DECODE (the
//	       Rust host reads those two as well, through `fax`'s code tables under its own row loop).
//	JPX    the embedder's `PdfCodecs`, else `unsupported`. NO Go library is adopted: no pure-Go JPEG 2000 decoder is in the standard
//	       library or `x/image`, and a cgo OpenJPEG would make the Go host non-pure for one codec (D336).
//	JBIG2  the same: `unsupported` (jbig2dec is C; no pure-Go decoder is adopted).
//
// A damaged stream is `imgFailed`; a valid one this host does not read is `imgUnsupported`; one over the pixel bound is `imgLimit`.

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/jpeg"
	"io"

	"golang.org/x/image/ccitt"
)

// PdfPix is what a PDF image codec made: W * H * Ch bytes.
type PdfPix struct {
	W, H, Ch int32
	Px       []byte
	Bilevel  bool
}

// PdfCodecs is the embedder's JPX and JBIG2 decoder (the two codecs no pure library reads). Both answer 8-bit pixels at the
// codestream's own size. Jpx: 1 gray or 3 RGB channels. Jbig2: ONE channel, 0 for a black pixel and 255 for a white one (the PDF's
// JBIG2Decode convention). An error from either is the image's: wrap `ErrPdfUnsupported` for a codec that does not read this
// stream, anything else is a damaged one.
type PdfCodecs interface {
	Jpx(data []byte, maxPixels uint64) (*PdfPix, error)
	Jbig2(data, globals []byte, maxPixels uint64) (*PdfPix, error)
}

// ErrPdfUnsupported is what a PdfCodecs returns (wrapped or as is) for a stream it does not read.
var ErrPdfUnsupported = imgUnsupportedf("not read by this codec")

// pdfArgs are the request's fields kin writes (`dq-*`).
type pdfArgs struct {
	filter, w, h, comps, bpc, k, columns, rows int32
	// 1 BlackIs1, 2 EncodedByteAlign, 4 EndOfLine, 8 EndOfBlock false
	flags int32
}

// `emon.plan`'s `flt` codes
const (
	pdfFltDCT   = 1
	pdfFltJPX   = 2
	pdfFltJBIG2 = 3
	pdfFltCCITT = 4
)

func pdfDecode(a pdfArgs, data, globals []byte, codecs PdfCodecs, maxPixels uint64) (*PdfPix, error) {
	switch a.filter {
	case pdfFltDCT:
		return pdfDCT(data, maxPixels)
	case pdfFltCCITT:
		return pdfCCITT(a, data, maxPixels)
	case pdfFltJPX:
		if codecs == nil {
			return nil, imgUnsupportedf("JPX needs a PdfCodecs (OpenJPEG)")
		}
		return codecs.Jpx(data, maxPixels)
	case pdfFltJBIG2:
		if codecs == nil {
			return nil, imgUnsupportedf("JBIG2 needs a PdfCodecs (jbig2dec)")
		}
		p, err := codecs.Jbig2(data, globals, maxPixels)
		if err != nil {
			return nil, err
		}
		return pdfFitJBIG2(p, a)
	}
	return nil, imgUnsupportedf("not an image codec")
}

// A JBIG2 page is the image, whatever size the dictionary says: it is made the dictionary's size, cut on the right and below and
// padded with white (255) where the page is smaller, as a viewer paints it.
func pdfFitJBIG2(p *PdfPix, a pdfArgs) (*PdfPix, error) {
	if p == nil || p.Ch != 1 || len(p.Px) != int(p.W)*int(p.H) {
		return nil, imgFailedf("a JBIG2 page that is not one channel of its size")
	}
	if p.W == a.w && p.H == a.h {
		p.Bilevel = true
		return p, nil
	}
	if a.w < 1 || a.h < 1 {
		return nil, imgFailedf("a JBIG2 image with no size")
	}
	out := make([]byte, int(a.w)*int(a.h))
	for i := range out {
		out[i] = 255
	}
	cw := int(min(a.w, p.W))
	for y := 0; y < int(min(a.h, p.H)); y++ {
		copy(out[y*int(a.w):y*int(a.w)+cw], p.Px[y*int(p.W):y*int(p.W)+cw])
	}
	return &PdfPix{W: a.w, H: a.h, Ch: 1, Px: out, Bilevel: true}, nil
}

// ------------------------------------------------------------------------------- DCT

type pdfJpegHead struct {
	w, h  uint32
	ncomp uint8
	// the Adobe APP14 marker's transform byte, when there is one
	adobe    uint8
	hasAdobe bool
}

func pdfJpegHeadOf(p []byte) (*pdfJpegHead, error) {
	if len(p) < 4 || p[0] != 0xFF || p[1] != 0xD8 {
		return nil, imgFailedf("not a JPEG stream")
	}
	m := &pdfJpegHead{}
	i := 2
	sof := false
	for i+4 <= len(p) {
		if p[i] != 0xFF {
			i++
			continue
		}
		mk := p[i+1]
		if mk == 0xFF {
			i++
			continue
		}
		if mk == 0xD8 || mk == 0x01 || (mk >= 0xD0 && mk <= 0xD7) || mk == 0 {
			i += 2
			continue
		}
		if mk == 0xD9 || mk == 0xDA {
			break
		}
		l := int(binary.BigEndian.Uint16(p[i+2:]))
		if l < 2 || i+2+l > len(p) {
			break
		}
		body := p[i+4 : i+2+l]
		switch {
		case mk == 0xEE && len(body) >= 12 && string(body[:5]) == "Adobe" && !m.hasAdobe:
			m.adobe, m.hasAdobe = body[11], true
		case mk >= 0xC0 && mk <= 0xCF && mk != 0xC4 && mk != 0xC8 && mk != 0xCC && !sof:
			if len(body) >= 6 {
				m.h = uint32(binary.BigEndian.Uint16(body[1:]))
				m.w = uint32(binary.BigEndian.Uint16(body[3:]))
				m.ncomp = body[5]
				sof = true
			}
		}
		i += 2 + l
	}
	if !sof || m.w == 0 || m.h == 0 {
		return nil, imgFailedf("a JPEG without a usable frame header")
	}
	return m, nil
}

// pdfDCT is the pixels of a JPEG codestream as a PDF paints them (see the Rust twin, `dct`). 4 components: Adobe's CMYK is stored
// inverted, so Go's `image.CMYK` (which has inverted it back: its values are ink) gives `(255 - c)(255 - k) / 255` rounded.
func pdfDCT(data []byte, maxPixels uint64) (*PdfPix, error) {
	m, err := pdfJpegHeadOf(data)
	if err != nil {
		return nil, err
	}
	w, h := uint64(m.w), uint64(m.h)
	if w*h > maxPixels {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: w * h}
	}
	if m.ncomp != 1 && m.ncomp != 3 && m.ncomp != 4 {
		return nil, imgUnsupportedf("a JPEG with other than one, three or four components")
	}
	if m.ncomp == 4 && !(m.hasAdobe && (m.adobe == 0 || m.adobe == 2)) {
		return nil, imgUnsupportedf("a four-component JPEG without an Adobe marker of transform 0 or 2")
	}
	img, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, imgFailedf(err.Error())
	}
	r := img.Bounds()
	iw, ih := r.Dx(), r.Dy()
	if uint64(iw) != w || uint64(ih) != h {
		return nil, imgFailedf("a JPEG whose pixels are not the size its header says")
	}
	if m.ncomp == 4 {
		im, ok := img.(*image.CMYK)
		if !ok {
			return nil, imgFailedf("a JPEG the decoder gave in a form that does not match its header")
		}
		px := make([]byte, iw*ih*3)
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*4
				c, mm, yy, k := uint32(im.Pix[i]), uint32(im.Pix[i+1]), uint32(im.Pix[i+2]), uint32(im.Pix[i+3])
				o := (y*iw + x) * 3
				px[o] = byte(((255-c)*(255-k) + 127) / 255)
				px[o+1] = byte(((255-mm)*(255-k) + 127) / 255)
				px[o+2] = byte(((255-yy)*(255-k) + 127) / 255)
			}
		}
		return &PdfPix{W: int32(iw), H: int32(ih), Ch: 3, Px: px}, nil
	}
	px, err := imgJpegRaw(img, int(m.ncomp), iw, ih)
	if err != nil {
		return nil, err
	}
	return &PdfPix{W: int32(iw), H: int32(ih), Ch: int32(m.ncomp), Px: px}, nil
}

// ----------------------------------------------------------------------------- CCITT

func pdfCCITT(a pdfArgs, data []byte, maxPixels uint64) (*PdfPix, error) {
	if a.w < 1 || a.h < 1 || a.columns < 1 {
		return nil, imgFailedf("a CCITT image with no size")
	}
	if uint64(a.w)*uint64(a.h) > maxPixels {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: uint64(a.w) * uint64(a.h)}
	}
	if a.columns > 1<<20 {
		return nil, imgUnsupportedf("CCITT Columns over 1,048,576")
	}
	if a.k > 0 {
		return nil, imgUnsupportedf("CCITT Group 3 two-dimensional (K > 0)")
	}
	w, h, cols := int(a.w), int(a.h), int(a.columns)
	rows := h
	if a.rows > 0 {
		rows = int(min(a.rows, a.h))
	}
	sub := ccitt.Group3
	if a.k < 0 {
		sub = ccitt.Group4
	}
	rd := ccitt.NewReader(bytes.NewReader(data), ccitt.MSB, sub, cols, rows, &ccitt.Options{Align: a.flags&2 != 0})
	stride := (cols + 7) / 8
	bits := make([]byte, stride*rows)
	if _, err := io.ReadFull(rd, bits); err != nil {
		return nil, imgFailedf("a bad or short CCITT stream")
	}
	// white is 1 and black is 0 in the reader's bits
	blk, wht := byte(0), byte(255)
	if a.flags&1 != 0 {
		blk, wht = 255, 0
	}
	px := make([]byte, w*h)
	for i := range px {
		px[i] = wht
	}
	for y := 0; y < rows; y++ {
		for x := 0; x < min(w, cols); x++ {
			if bits[y*stride+x/8]>>(7-uint(x%8))&1 == 0 {
				px[y*w+x] = blk
			}
		}
	}
	return &PdfPix{W: a.w, H: a.h, Ch: 1, Px: px, Bilevel: true}, nil
}
