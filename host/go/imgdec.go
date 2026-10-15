// THE `ImageDecode` PORT, Go host (DEVLOG P6.1; SPEC 7.2, 7.3; D058, D089, D187-D190). The twin of
// `host/rust/src/imgdec.rs`: read that file for the reasoning. Encoded bytes (PNG, JPEG, TIFF) in, one frame's
// pixels out, normalised to the Bitmap contract: 8-bit gray or RGB, row-major, top-left, EXIF orientation
// applied, alpha composited onto white, resolution in dots per inch x100 (0 = unknown), a `bilevel` flag for
// one-bit gray. It never resamples.
//
// What an image may contain is kin's (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` parse the container, decide,
// apply the limits and report; `image/png` is shown only input they accepted, and only the chunks that matter. PNG and JPEG
// pixels come from `image/png` and `image/jpeg`; the reduction to 8 bits, alpha and orientation are ours and the same as Rust's.
// TIFF samples are unpacked from the strips and tiles the validator listed: LZW, PackBits, Deflate (`compress/zlib`), the
// predictor, bit unpacking and colour; CCITT G3 and G4 are kin's (`emon.ccitt`, the decoder PDF uses too).
// What it refuses is `imgUnsupported`, a damaged file `imgFailed`, a frame over the pixel bound `imgLimit`.

package emon

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"math/bits"

	kccitt "emon/ccitt"
	"emon/imgcheck"
	"emon/pngcheck"
	"emon/port"
	"emon/tiffcheck"
)

type imgFailKind int

const (
	imgFailed imgFailKind = iota
	imgUnsupported
	imgLimit
)

// imgFail is why a frame was not decoded.
type imgFail struct {
	Kind   imgFailKind
	Msg    string
	Pixels uint64 // imgLimit: the pixels the frame has
}

func (e *imgFail) Error() string { return e.Msg }

func imgFailedf(s string) error      { return &imgFail{Kind: imgFailed, Msg: s} }
func imgUnsupportedf(s string) error { return &imgFail{Kind: imgUnsupported, Msg: s} }

// ImgDecoded is one decoded frame (or only its header).
type imgDecoded struct {
	W, H, Ch int32 // the size AFTER orientation; 1 gray or 3 RGB
	Dpi100   int32
	Bilevel  bool
	Frames   int32
	Pixels   []byte // w*h*ch bytes; nil when only the header was asked for
}

type imgFormat int

const (
	imgNone imgFormat = iota
	imgPNG
	imgJPEG
	imgTIFF
)

func imgSniff(h []byte) imgFormat {
	switch {
	case len(h) >= 8 && bytes.Equal(h[:8], []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}):
		return imgPNG
	case len(h) >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF:
		return imgJPEG
	case len(h) >= 4 && (bytes.Equal(h[:4], []byte{'I', 'I', 42, 0}) || bytes.Equal(h[:4], []byte{'M', 'M', 0, 42})):
		return imgTIFF
	}
	return imgNone
}

type memSource []byte

func (m memSource) Size() int64 { return int64(len(m)) }
func (m memSource) ReadAt(p []byte, off int64) (int, error) {
	return readSlice(m, p, off)
}

// imgDecode: frame >= 0 is that frame's pixels; frame < 0 only the header of frame -frame-1. A frame over the
// PIXELS limit (limit-of 9) is an imgLimit failure.
func imgDecode(src ByteSource, frame int32) (*imgDecoded, error) {
	var head [8]byte
	n := imgReadAtMost(src, 0, head[:])
	want := frame >= 0
	f := frame
	if !want {
		f = -frame - 1
	}
	switch imgSniff(head[:n]) {
	case imgPNG:
		if f != 0 {
			return nil, imgFailedf("this format has one frame")
		}
		return imgPng(src, want)
	case imgJPEG:
		if f != 0 {
			return nil, imgFailedf("this format has one frame")
		}
		return imgJpeg(src, want)
	case imgTIFF:
		return imgTiff(src, f, want)
	}
	return nil, imgFailedf("not a PNG, JPEG or TIFF")
}

func imgReadAtMost(src ByteSource, off int64, buf []byte) int {
	got := 0
	for got < len(buf) {
		n, err := src.ReadAt(buf[got:], off+int64(got))
		got += n
		if n == 0 || (err != nil && got < len(buf)) {
			break
		}
	}
	return got
}

func imgReadExact(src ByteSource, off int64, n int) ([]byte, error) {
	if off < 0 || n < 0 || off+int64(n) > src.Size() {
		return nil, imgFailedf("a read past the end of the input")
	}
	v := make([]byte, n)
	if imgReadAtMost(src, off, v) != n {
		return nil, imgFailedf("the input is shorter than it said")
	}
	return v, nil
}

func imgReadAll(src ByteSource) ([]byte, error) { return imgReadExact(src, 0, int(src.Size())) }

// ------------------------------------------------------------- small integer helpers

// lut: n-bit sample -> 8 bits, rounded: (v * 255 + max / 2) / max (D188).
func imgLut(b uint) []byte {
	max := uint64(1)<<b - 1
	t := make([]byte, max+1)
	for v := uint64(0); v <= max; v++ {
		t[v] = byte((v*255 + max/2) / max)
	}
	return t
}

func imgS16(v uint32) byte { return byte((uint64(v)*255 + 32767) / 65535) }

func imgOverWhite(c, a uint32) byte { return byte((c*a + 255*(255-a) + 127) / 255) }
func imgOverWhitePremul(c, a uint32) byte {
	if c+255-a > 255 {
		return 255
	}
	return byte(c + 255 - a)
}

func imgCmykRgb(c, m, y, k uint32) [3]byte {
	f := func(v uint32) byte { return byte(((255-v)*(255-k) + 127) / 255) }
	return [3]byte{f(c), f(m), f(y)}
}

func imgClamp8(v int32) byte {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return byte(v)
}

func imgYccRgb(y, cb, cr int32) [3]byte {
	cb -= 128
	cr -= 128
	return [3]byte{imgClamp8(y + (91881*cr+32768)>>16), imgClamp8(y - (22554*cb+46802*cr+32768)>>16), imgClamp8(y + (116130*cb+32768)>>16)}
}

// ------------------------------------------------------------------ orientation

func imgOrient(d []byte, w, h, ch int, o uint32) ([]byte, int, int) {
	if o <= 1 || o > 8 {
		return d, w, h
	}
	ow, oh := w, h
	if o >= 5 {
		ow, oh = h, w
	}
	out := make([]byte, len(d))
	for y := 0; y < oh; y++ {
		for x := 0; x < ow; x++ {
			var sx, sy int
			switch o {
			case 2:
				sx, sy = w-1-x, y
			case 3:
				sx, sy = w-1-x, h-1-y
			case 4:
				sx, sy = x, h-1-y
			case 5:
				sx, sy = y, x
			case 6:
				sx, sy = y, h-1-x
			case 7:
				sx, sy = w-1-y, h-1-x
			default:
				sx, sy = w-1-y, x
			}
			copy(out[(y*ow+x)*ch:(y*ow+x)*ch+ch], d[(sy*w+sx)*ch:(sy*w+sx)*ch+ch])
		}
	}
	return out, ow, oh
}

// --------------------------------------------------------------- the validators (kin)
//
// WHAT AN IMAGE MAY CONTAIN IS KIN'S (D396(4), P10.11; the Rust twin says it at length): `emon.pngcheck` and `emon.tiffcheck` read
// the container, decide, apply the pixel and byte limits and report what the host needs; a platform library is asked for pixels only
// of input they accepted, and is shown only the chunks that matter. CCITT G3 and G4 are kin's (`emon.ccitt`): `x/image/ccitt`
// reads no two-dimensional Group 3 and no fill bits.

func imgAt(r *port.Cells, i int32) int32 { return port.CellsAt(r, i) }

// imgRefused turns a validator's status into the port's failure.
func imgRefused(status int32, what string, limit int32) error {
	switch status {
	case imgcheck.IcUnsupported():
		return imgUnsupportedf(what)
	case imgcheck.IcLimit():
		return &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: uint64(limit)}
	}
	return imgFailedf(what)
}

// imgExif: what the first directory of an EXIF blob says: orientation and the horizontal and vertical resolution (dpi x100, 0
// unknown). A blob that does not read says nothing.
func imgExif(blob []byte) (o uint32, dx, dy int32) {
	r := port.NewCells()
	tiffcheck.TcExif(blob, 0, int32(len(blob)), r)
	return uint32(imgAt(r, tiffcheck.TrEo())), imgAt(r, tiffcheck.TrEx()), imgAt(r, tiffcheck.TrEy())
}

// ------------------------------------------------------------------------ PNG

func imgPng(src ByteSource, want bool) (*imgDecoded, error) {
	b, err := imgReadAll(src)
	if err != nil {
		return nil, err
	}
	deep := int32(0)
	if want {
		deep = 1
	}
	r, st, out, idat, clean := port.NewCells(), port.NewCells(), port.NewCells(), port.NewBuf(), port.NewBuf()
	s := pngcheck.PcCheck(b, deep, r, st, out, idat, clean)
	b, st, out, idat = nil, nil, nil, nil
	if s != imgcheck.IcOk() {
		return nil, imgRefused(s, "a PNG the validator refused: damaged, or past the rules of D389", imgAt(r, pngcheck.PrLimit()))
	}
	w, h := int(imgAt(r, pngcheck.PrW())), int(imgAt(r, pngcheck.PrH()))
	ch := int(imgAt(r, pngcheck.PrCh()))
	gray := ch == 1
	o := uint32(imgAt(r, pngcheck.PrOrient()))
	res := &imgDecoded{W: imgAt(r, pngcheck.PrOw()), H: imgAt(r, pngcheck.PrOh()), Ch: int32(ch), Dpi100: imgAt(r, pngcheck.PrDpi()), Bilevel: imgAt(r, pngcheck.PrBilevel()) != 0, Frames: 1}
	if !want {
		return res, nil
	}
	// the library is shown the chunks that matter, whole and in order, and nothing else (`clean`)
	img, err := png.Decode(bytes.NewReader(clean.B))
	if err != nil {
		return nil, imgFailedf(err.Error())
	}
	rc := img.Bounds()
	iw, ih := rc.Dx(), rc.Dy()
	if iw != w || ih != h {
		return nil, imgFailedf("a PNG whose pixels are not the size its header says")
	}
	px := make([]byte, iw*ih*ch)
	// pixel (x, y) as (c0, c1, c2, a) in 8 bits, c's straight (not premultiplied)
	switch im := img.(type) {
	case *image.Gray:
		if !gray {
			return nil, imgFailedf("a PNG the decoder gave in a form that does not match its header")
		}
		for y := 0; y < ih; y++ {
			copy(px[y*iw:(y+1)*iw], im.Pix[y*im.Stride:y*im.Stride+iw])
		}
	case *image.Gray16:
		if !gray {
			return nil, imgFailedf("a PNG the decoder gave in a form that does not match its header")
		}
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*2
				px[y*iw+x] = imgS16(uint32(im.Pix[i])<<8 | uint32(im.Pix[i+1]))
			}
		}
	case *image.RGBA:
		if gray {
			return nil, imgFailedf("a PNG the decoder gave in a form that does not match its header")
		}
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*4
				a := uint32(im.Pix[i+3])
				for k := 0; k < 3; k++ {
					c := uint32(im.Pix[i+k])
					// opaque in practice; if premultiplied alpha ever arrives, composite as premultiplied
					px[(y*iw+x)*3+k] = imgOverWhitePremul(c, a)
				}
			}
		}
	case *image.RGBA64:
		if gray {
			return nil, imgFailedf("a PNG the decoder gave in a form that does not match its header")
		}
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*8
				a := uint32(imgS16(uint32(im.Pix[i+6])<<8 | uint32(im.Pix[i+7])))
				for k := 0; k < 3; k++ {
					c := uint32(imgS16(uint32(im.Pix[i+2*k])<<8 | uint32(im.Pix[i+2*k+1])))
					px[(y*iw+x)*3+k] = imgOverWhitePremul(c, a)
				}
			}
		}
	case *image.NRGBA:
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*4
				a := uint32(im.Pix[i+3])
				if gray {
					px[y*iw+x] = imgOverWhite(uint32(im.Pix[i]), a)
				} else {
					for k := 0; k < 3; k++ {
						px[(y*iw+x)*3+k] = imgOverWhite(uint32(im.Pix[i+k]), a)
					}
				}
			}
		}
	case *image.NRGBA64:
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*8
				a := uint32(imgS16(uint32(im.Pix[i+6])<<8 | uint32(im.Pix[i+7])))
				if gray {
					px[y*iw+x] = imgOverWhite(uint32(imgS16(uint32(im.Pix[i])<<8|uint32(im.Pix[i+1]))), a)
				} else {
					for k := 0; k < 3; k++ {
						c := uint32(imgS16(uint32(im.Pix[i+2*k])<<8 | uint32(im.Pix[i+2*k+1])))
						px[(y*iw+x)*3+k] = imgOverWhite(c, a)
					}
				}
			}
		}
	case *image.Paletted:
		if gray {
			return nil, imgFailedf("a PNG the decoder gave in a form that does not match its header")
		}
		pal := make([][3]byte, len(im.Palette))
		for i, c := range im.Palette {
			switch v := c.(type) {
			case color.NRGBA:
				pal[i] = [3]byte{imgOverWhite(uint32(v.R), uint32(v.A)), imgOverWhite(uint32(v.G), uint32(v.A)), imgOverWhite(uint32(v.B), uint32(v.A))}
			case color.RGBA:
				pal[i] = [3]byte{imgOverWhitePremul(uint32(v.R), uint32(v.A)), imgOverWhitePremul(uint32(v.G), uint32(v.A)), imgOverWhitePremul(uint32(v.B), uint32(v.A))}
			default:
				return nil, imgFailedf("a PNG palette of an unexpected colour type")
			}
		}
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				idx := int(im.Pix[y*im.Stride+x])
				if idx >= len(pal) {
					return nil, imgFailedf("a PNG pixel outside its palette")
				}
				copy(px[(y*iw+x)*3:], pal[idx][:])
			}
		}
	default:
		return nil, imgFailedf("a PNG the decoder gave in a form we do not read")
	}
	px, _, _ = imgOrient(px, iw, ih, ch, o)
	res.Pixels = px
	return res, nil
}

// ----------------------------------------------------------------------- JPEG

type jpegMeta struct {
	w, h   uint32
	ncomp  uint8
	dx, dy int32
	exif   []byte
}

func imgJpegMeta(p []byte) (*jpegMeta, error) {
	m := &jpegMeta{}
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
		case mk == 0xE0 && len(body) >= 12 && string(body[:5]) == "JFIF\x00":
			units := body[7]
			xd := int64(binary.BigEndian.Uint16(body[8:]))
			yd := int64(binary.BigEndian.Uint16(body[10:]))
			f := func(d int64) int32 {
				switch units {
				case 1:
					return int32(d * 100)
				case 2:
					return int32(d * 254)
				}
				return 0
			}
			m.dx, m.dy = f(xd), f(yd)
		case mk == 0xE1 && len(body) > 6 && string(body[:6]) == "Exif\x00\x00" && m.exif == nil:
			m.exif = append([]byte(nil), body[6:]...)
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

func imgJpeg(src ByteSource, want bool) (*imgDecoded, error) {
	maxPixels := uint64(port.LimitOf(9))
	b, err := imgReadAll(src)
	if err != nil {
		return nil, err
	}
	m, err := imgJpegMeta(b)
	if err != nil {
		return nil, err
	}
	if m.ncomp != 1 && m.ncomp != 3 {
		return nil, imgUnsupportedf("a JPEG with other than one or three components (CMYK: P6.1a)")
	}
	o, ex, ey := uint32(1), int32(0), int32(0)
	if m.exif != nil {
		o, ex, ey = imgExif(m.exif)
	}
	w, h := uint64(m.w), uint64(m.h)
	ow, oh := w, h
	if o >= 5 {
		ow, oh = h, w
	}
	dx, dy := m.dx, m.dy
	if dx <= 0 {
		dx, dy = ex, ey
	}
	out := &imgDecoded{W: int32(ow), H: int32(oh), Ch: int32(m.ncomp), Frames: 1}
	if o >= 5 {
		out.Dpi100 = dy
	} else {
		out.Dpi100 = dx
	}
	if w*h > maxPixels {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: w * h}
	}
	if !want {
		return out, nil
	}
	img, err := jpeg.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, imgFailedf(err.Error())
	}
	r := img.Bounds()
	iw, ih := r.Dx(), r.Dy()
	if uint64(iw) != w || uint64(ih) != h {
		return nil, imgFailedf("a JPEG whose pixels are not the size its header says")
	}
	px, err := imgJpegRaw(img, int(m.ncomp), iw, ih)
	if err != nil {
		return nil, err
	}
	px, _, _ = imgOrient(px, iw, ih, int(out.Ch), o)
	out.Pixels = px
	return out, nil
}

// imgJpegRaw is the pixels of a decoded 1 or 3 component JPEG: gray, or RGB (the chroma filtered as libjpeg does, `imgUpsample`). It
// applies no orientation: the ImageDecode port orients after it, a PDF image (`pdfDCT`) never does.
func imgJpegRaw(img image.Image, ncomp, iw, ih int) ([]byte, error) {
	var px []byte
	switch im := img.(type) {
	case *image.Gray:
		if ncomp != 1 {
			return nil, imgFailedf("a JPEG the decoder gave in a form that does not match its header")
		}
		px = make([]byte, iw*ih)
		for y := 0; y < ih; y++ {
			copy(px[y*iw:(y+1)*iw], im.Pix[y*im.Stride:y*im.Stride+iw])
		}
	case *image.YCbCr:
		if ncomp != 3 {
			return nil, imgFailedf("a JPEG the decoder gave in a form that does not match its header")
		}
		px = make([]byte, iw*ih*3)
		cb := imgUpsample(im.Cb, im.CStride, iw, ih, im.SubsampleRatio)
		cr := imgUpsample(im.Cr, im.CStride, iw, ih, im.SubsampleRatio)
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				rgb := imgYccRgb(int32(im.Y[im.YOffset(x, y)]), int32(cb[y*iw+x]), int32(cr[y*iw+x]))
				copy(px[(y*iw+x)*3:], rgb[:])
			}
		}
	case *image.RGBA:
		if ncomp != 3 {
			return nil, imgFailedf("a JPEG the decoder gave in a form that does not match its header")
		}
		px = make([]byte, iw*ih*3)
		for y := 0; y < ih; y++ {
			for x := 0; x < iw; x++ {
				i := y*im.Stride + x*4
				copy(px[(y*iw+x)*3:], im.Pix[i:i+3])
			}
		}
	default:
		return nil, imgFailedf("a JPEG the decoder gave in a form we do not read")
	}
	return px, nil
}

// imgUpsample brings a subsampled chroma plane to the size of the image with libjpeg's "fancy" (triangle)
// filters for 4:2:2 and 4:2:0 and 4:4:0 (what libjpeg-turbo and, within a byte or two, zune-jpeg do, so that the
// two hosts' pixels are close: a nearest-neighbour upsample differs by tens at a colour edge, D190); other
// ratios are replicated.
func imgUpsample(p []byte, stride, w, h int, r image.YCbCrSubsampleRatio) []byte {
	out := make([]byte, w*h)
	cw, chh := w, h
	switch r {
	case image.YCbCrSubsampleRatio422:
		cw = (w + 1) / 2
	case image.YCbCrSubsampleRatio420:
		cw, chh = (w+1)/2, (h+1)/2
	case image.YCbCrSubsampleRatio440:
		chh = (h + 1) / 2
	case image.YCbCrSubsampleRatio411:
		cw = (w + 3) / 4
	case image.YCbCrSubsampleRatio410:
		cw, chh = (w+3)/4, (h+1)/2
	}
	at := func(x, y int) int { return int(p[y*stride+x]) }
	switch {
	case r == image.YCbCrSubsampleRatio444:
		for y := 0; y < h; y++ {
			copy(out[y*w:(y+1)*w], p[y*stride:y*stride+w])
		}
	case (r == image.YCbCrSubsampleRatio422 || r == image.YCbCrSubsampleRatio420) && cw > 2:
		row := make([]int, cw)
		for y := 0; y < h; y++ {
			// the input row and the one on the side of this output row, edges replicated
			y0 := y / 2
			if r == image.YCbCrSubsampleRatio422 {
				y0 = y
			}
			y1 := y0
			if r == image.YCbCrSubsampleRatio420 {
				if y%2 == 0 {
					y1 = y0 - 1
				} else {
					y1 = y0 + 1
				}
				if y1 < 0 {
					y1 = 0
				}
				if y1 >= chh {
					y1 = chh - 1
				}
			}
			o := out[y*w : (y+1)*w]
			put := func(x, v int) {
				if x < w {
					o[x] = byte(v)
				}
			}
			if r == image.YCbCrSubsampleRatio422 {
				for x := 0; x < cw; x++ {
					row[x] = at(x, y0)
				}
				put(0, row[0])
				put(1, (row[0]*3+row[1]+2)>>2)
				for x := 1; x < cw-1; x++ {
					put(2*x, (row[x]*3+row[x-1]+1)>>2)
					put(2*x+1, (row[x]*3+row[x+1]+2)>>2)
				}
				put(2*cw-2, (row[cw-1]*3+row[cw-2]+1)>>2)
				put(2*cw-1, row[cw-1])
				continue
			}
			for x := 0; x < cw; x++ {
				row[x] = at(x, y0)*3 + at(x, y1)
			}
			put(0, (row[0]*4+8)>>4)
			put(1, (row[0]*3+row[1]+7)>>4)
			for x := 1; x < cw-1; x++ {
				put(2*x, (row[x]*3+row[x-1]+8)>>4)
				put(2*x+1, (row[x]*3+row[x+1]+7)>>4)
			}
			put(2*cw-2, (row[cw-1]*3+row[cw-2]+8)>>4)
			put(2*cw-1, (row[cw-1]*4+7)>>4)
		}
	case r == image.YCbCrSubsampleRatio440 && chh > 2:
		for y := 0; y < h; y++ {
			y0 := y / 2
			y1, bias := y0-1, 1
			if y%2 == 1 {
				y1, bias = y0+1, 2
			}
			if y1 < 0 {
				y1 = 0
			}
			if y1 >= chh {
				y1 = chh - 1
			}
			for x := 0; x < w; x++ {
				out[y*w+x] = byte((at(x, y0)*3 + at(x, y1) + bias) >> 2)
			}
		}
	default:
		// replicated: 4:1:1, 4:1:0, and a plane too small for the filter
		fx, fy := 1, 1
		switch r {
		case image.YCbCrSubsampleRatio422:
			fx = 2
		case image.YCbCrSubsampleRatio420:
			fx, fy = 2, 2
		case image.YCbCrSubsampleRatio440:
			fy = 2
		case image.YCbCrSubsampleRatio411:
			fx = 4
		case image.YCbCrSubsampleRatio410:
			fx, fy = 4, 2
		}
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				out[y*w+x] = p[(y/fy)*stride+x/fx]
			}
		}
	}
	return out
}

// ----------------------------------------------------------------------- TIFF

func imgPackbits(d []byte, expect int) []byte {
	out := make([]byte, 0, expect)
	i := 0
	for i < len(d) && len(out) < expect {
		n := int(int8(d[i]))
		i++
		if n >= 0 {
			c := n + 1
			e := i + c
			if e > len(d) {
				e = len(d)
			}
			out = append(out, d[i:e]...)
			i = e
		} else if n != -128 {
			if i >= len(d) {
				break
			}
			c := 1 - n
			for j := 0; j < c; j++ {
				out = append(out, d[i])
			}
			i++
		}
	}
	if len(out) > expect {
		out = out[:expect]
	}
	return out
}

// imgLzw is TIFF LZW: MSB first with early change, or the pre-1988 LSB stream (libtiff's "compat").
func imgLzw(d []byte, expect int) ([]byte, error) {
	compat := len(d) >= 2 && d[0] == 0 && d[1]&1 == 1
	early := 1
	if compat {
		early = 0
	}
	prefix := make([]uint16, 4096)
	suffix := make([]byte, 4096)
	out := make([]byte, 0, expect)
	next := 258
	width := uint(9)
	var acc uint32
	var nbits uint
	pos := 0
	prev := -1
	var stack []byte
	for {
		for nbits < width {
			if pos >= len(d) {
				if len(out) > expect {
					out = out[:expect]
				}
				return out, nil
			}
			if compat {
				acc |= uint32(d[pos]) << nbits
			} else {
				acc = acc<<8 | uint32(d[pos])
			}
			pos++
			nbits += 8
		}
		var code int
		if compat {
			code = int(acc & (1<<width - 1))
			acc >>= width
			nbits -= width
		} else {
			code = int((acc >> (nbits - width)) & (1<<width - 1))
			nbits -= width
			acc &= 1<<nbits - 1
		}
		if code == 257 {
			break
		}
		if code == 256 {
			next = 258
			width = 9
			prev = -1
			continue
		}
		stack = stack[:0]
		if prev < 0 {
			if code > 255 {
				return nil, imgFailedf("a bad LZW code")
			}
			stack = append(stack, byte(code))
		} else {
			var c int
			switch {
			case code < next:
				c = code
			case code == next:
				c = prev
			default:
				return nil, imgFailedf("a bad LZW code")
			}
			for c > 255 {
				stack = append(stack, suffix[c])
				c = int(prefix[c])
			}
			stack = append(stack, byte(c))
			first := byte(c)
			if code == next {
				stack = append([]byte{first}, stack...)
			}
			if next < 4096 {
				prefix[next] = uint16(prev)
				suffix[next] = first
				next++
			}
		}
		for i := len(stack) - 1; i >= 0; i-- {
			out = append(out, stack[i])
		}
		prev = code
		switch {
		case next+early < 512:
			width = 9
		case next+early < 1024:
			width = 10
		case next+early < 2048:
			width = 11
		default:
			width = 12
		}
		if len(out) >= expect {
			break
		}
	}
	if len(out) > expect {
		out = out[:expect]
	}
	return out, nil
}

func imgInflate(d []byte, expect int) ([]byte, error) {
	zr, err := zlib.NewReader(bytes.NewReader(d))
	if err != nil {
		return nil, imgFailedf("a bad Deflate stream")
	}
	// The same three answers as the Rust host's `miniz_oxide`: a stream that ends cleanly (its checksum right)
	// early gives what it has, one that fills `expect` bytes has its end read too (so a wrong checksum is seen), and
	// one that has MORE is cut at `expect` without it; any damage is `failed`.
	out := make([]byte, expect)
	n := 0
	for n < expect {
		k, err := zr.Read(out[n:])
		n += k
		if err == io.EOF {
			return out[:n], nil
		}
		if err != nil {
			return nil, imgFailedf("a bad Deflate stream")
		}
	}
	var one [1]byte
	if k, err := zr.Read(one[:]); k == 0 && err != nil && err != io.EOF {
		return nil, imgFailedf("a bad Deflate stream")
	}
	return out[:n], nil
}

// imgCcitt: kin's one CCITT decoder (`emon.ccitt`, TIFF's and PDF's): `rows` rows of `cw` pixels, bits packed MSB first, 1 = black, a
// row to a whole number of bytes. `ws` is the decoder's scratch, kept for every strip of an image.
func imgCcitt(d []byte, k int32, cw, rows int, ws *port.Cells) ([]byte, error) {
	out := &port.Buf{B: make([]byte, ((cw+7)/8)*rows)}
	if kccitt.CcDecode(d, 0, int32(len(d)), k, int32(cw), int32(rows), 0, out, ws) != kccitt.CcOk() {
		return nil, imgFailedf("a short or damaged CCITT stream")
	}
	return out.B, nil
}

type imgPhoto struct {
	kind  uint32 // 0 MinIsWhite 1 MinIsBlack 2 RGB 3 palette 5 CMYK 6 YCbCr
	spp   int
	nc    int
	bps   uint
	alpha int // -1 none, 0 straight, 1 premultiplied
	cmap  []uint16
}

// imgTiff: the whole file is read, `emon.tiffcheck` decides (the byte order, the chain of frames, every tag, the bounds of every strip
// and tile, the pixel limit) and reports; what is left here is turning the chunks it listed into samples and the samples into the
// 8-bit pixels of D188. A multi-page TIFF reads only the frame asked for.
func imgTiff(src ByteSource, f int32, want bool) (*imgDecoded, error) {
	b, err := imgReadAll(src)
	if err != nil {
		return nil, err
	}
	deep := int32(0)
	if want {
		deep = 1
	}
	r := port.NewCells()
	s := tiffcheck.TcCheck(b, f, deep, r)
	if s != imgcheck.IcOk() {
		return nil, imgRefused(s, "a TIFF the validator refused: damaged, or a coding the port does not read (D188)", imgAt(r, tiffcheck.TrLimit()))
	}
	out := &imgDecoded{
		W: imgAt(r, tiffcheck.TrOw()), H: imgAt(r, tiffcheck.TrOh()), Ch: imgAt(r, tiffcheck.TrCh()),
		Dpi100: imgAt(r, tiffcheck.TrDpi()), Bilevel: imgAt(r, tiffcheck.TrBilevel()) != 0, Frames: imgAt(r, tiffcheck.TrFrames()),
	}
	if !want {
		return out, nil
	}
	out.Pixels, err = imgTiffPixels(b, r)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func imgTiffPixels(b []byte, rep *port.Cells) ([]byte, error) {
	g := func(i int32) int { return int(imgAt(rep, i)) }
	w, h := g(tiffcheck.TrW()), g(tiffcheck.TrH())
	spp, bps := g(tiffcheck.TrSpp()), uint(g(tiffcheck.TrBps()))
	comp, kind, nc := g(tiffcheck.TrComp()), uint32(g(tiffcheck.TrKind())), g(tiffcheck.TrNc())
	alpha := -1
	switch g(tiffcheck.TrAlpha()) {
	case 1:
		alpha = 1
	case 2:
		alpha = 0
	}
	predictor, planar2, fill2, le := g(tiffcheck.TrPredictor()), g(tiffcheck.TrPlanar()) == 1, g(tiffcheck.TrFill2()) == 1, g(tiffcheck.TrLe()) == 1
	tiled := g(tiffcheck.TrTiled()) == 1
	cw0, bandRows := g(tiffcheck.TrCw()), g(tiffcheck.TrBand())
	across, down := g(tiffcheck.TrAcross()), g(tiffcheck.TrDown())
	hs, vs := g(tiffcheck.TrHs()), g(tiffcheck.TrVs())
	planes := g(tiffcheck.TrPlanes())
	offsAt, lensAt := int32(g(tiffcheck.TrOffsAt())), int32(g(tiffcheck.TrLensAt()))
	ch := g(tiffcheck.TrCh())
	ov := uint32(g(tiffcheck.TrOrient()))
	var cmap []uint16
	if kind == 3 {
		cmap = make([]uint16, 3<<bps)
		for i := range cmap {
			cmap[i] = uint16(imgAt(rep, int32(g(tiffcheck.TrCmapAt())+i)))
		}
	}
	ph := &imgPhoto{kind: kind, spp: spp, nc: nc, bps: bps, alpha: alpha, cmap: cmap}
	var bo binary.ByteOrder = binary.BigEndian
	if le {
		bo = binary.LittleEndian
	}

	perPlane := across * down
	cs := spp
	if planar2 {
		cs = 1
	}
	px := make([]byte, w*h*ch)
	table := imgLut(bps)
	ws := port.NewCells()
	var samp []uint16
	for by := 0; by < down; by++ {
		y0 := by * bandRows
		rows := bandRows
		if h-y0 < rows {
			rows = h - y0
		}
		if cap(samp) >= rows*w*spp {
			samp = samp[:rows*w*spp]
			for i := range samp {
				samp[i] = 0
			}
		} else {
			samp = make([]uint16, rows*w*spp)
		}
		for p := 0; p < planes; p++ {
			for bx := 0; bx < across; bx++ {
				idx := int32(p*perPlane + by*across + bx)
				off, n := int(uint32(imgAt(rep, offsAt+idx))), int(uint32(imgAt(rep, lensAt+idx)))
				data := make([]byte, n)
				copy(data, b[off:off+n])
				if fill2 {
					for i, c := range data {
						data[i] = bits.Reverse8(c)
					}
				}
				cw := cw0
				crows := rows
				if tiled {
					crows = bandRows
				}
				rowb := (cw*cs*int(bps) + 7) / 8
				expect := rowb * crows
				if kind == 6 {
					expect = ((cw + hs - 1) / hs) * ((crows + vs - 1) / vs) * (hs*vs + 2)
				}
				var raw []byte
				var err error
				switch comp {
				case 1:
					raw = data
				case 32773:
					raw = imgPackbits(data, expect)
				case 5:
					raw, err = imgLzw(data, expect)
				case 8, 32946:
					raw, err = imgInflate(data, expect)
				case 4:
					raw, err = imgCcitt(data, -1, cw, crows, ws)
				default:
					// Group 3: k 4 when T4Options says two-dimensional (a row after each EOL and its tag bit), else 0 (P12.6)
					k := int32(0)
					if g(tiffcheck.TrT4())&1 != 0 {
						k = 4
					}
					raw, err = imgCcitt(data, k, cw, crows, ws)
				}
				if err != nil {
					return nil, err
				}
				if len(raw) < expect {
					raw = append(raw, make([]byte, expect-len(raw))...)
				}
				raw = raw[:expect]
				if predictor == 2 {
					for r := 0; r < crows; r++ {
						row := raw[r*rowb : (r+1)*rowb]
						if bps == 8 {
							for i := cs; i < cw*cs; i++ {
								row[i] += row[i-cs]
							}
						} else {
							for i := cs; i < cw*cs; i++ {
								a := bo.Uint16(row[i*2:]) + bo.Uint16(row[(i-cs)*2:])
								bo.PutUint16(row[i*2:], a)
							}
						}
					}
				}
				x0 := bx * cw0
				ncols := cw0
				if w-x0 < ncols {
					ncols = w - x0
				}
				if kind == 6 {
					bw := (cw + hs - 1) / hs
					for r := 0; r < rows; r++ {
						for c := 0; c < ncols; c++ {
							blk := (r/vs)*bw + c/hs
							base := blk * (hs*vs + 2)
							o := (r*w + x0 + c) * 3
							samp[o] = uint16(raw[base+(r%vs)*hs+c%hs])
							samp[o+1] = uint16(raw[base+hs*vs])
							samp[o+2] = uint16(raw[base+hs*vs+1])
						}
					}
				} else {
					for r := 0; r < rows; r++ {
						row := raw[r*rowb : (r+1)*rowb]
						for c := 0; c < ncols; c++ {
							for s := 0; s < cs; s++ {
								k := c*cs + s
								var v uint16
								switch bps {
								case 8:
									v = uint16(row[k])
								case 16:
									v = bo.Uint16(row[k*2:])
								default:
									bit := k * int(bps)
									var acc uint32
									for j := 0; j < int(bps); j++ {
										q := bit + j
										acc = acc<<1 | uint32((row[q/8]>>(7-uint(q%8)))&1)
									}
									v = uint16(acc)
								}
								si := s
								if planar2 {
									si = p
								}
								samp[(r*w+x0+c)*spp+si] = v
							}
						}
					}
				}
			}
		}
		for r := 0; r < rows; r++ {
			imgConvertRow(ph, table, samp[r*w*spp:(r+1)*w*spp], px[(y0+r)*w*ch:(y0+r+1)*w*ch])
		}
	}
	px, _, _ = imgOrient(px, w, h, ch, ov)
	return px, nil
}

func imgConvertRow(ph *imgPhoto, table []byte, s []uint16, d []byte) {
	spp := ph.spp
	n := len(s) / spp
	comp := func(c uint32, p []uint16) byte {
		if ph.alpha < 0 {
			return byte(c)
		}
		a := uint32(table[p[ph.nc]])
		if ph.alpha == 1 {
			return imgOverWhitePremul(c, a)
		}
		return imgOverWhite(c, a)
	}
	for x := 0; x < n; x++ {
		p := s[x*spp : (x+1)*spp]
		switch ph.kind {
		case 0, 1:
			g := uint32(table[p[0]])
			if ph.kind == 0 {
				g = 255 - g
			}
			d[x] = comp(g, p)
		case 2:
			for k := 0; k < 3; k++ {
				d[x*3+k] = comp(uint32(table[p[k]]), p)
			}
		case 3:
			n := 1 << ph.bps
			i := int(p[0])
			for k := 0; k < 3; k++ {
				d[x*3+k] = imgS16(uint32(ph.cmap[k*n+i]))
			}
		case 5:
			rgb := imgCmykRgb(uint32(table[p[0]]), uint32(table[p[1]]), uint32(table[p[2]]), uint32(table[p[3]]))
			for k := 0; k < 3; k++ {
				d[x*3+k] = comp(uint32(rgb[k]), p)
			}
		default:
			rgb := imgYccRgb(int32(p[0]), int32(p[1]), int32(p[2]))
			copy(d[x*3:x*3+3], rgb[:])
		}
	}
}
