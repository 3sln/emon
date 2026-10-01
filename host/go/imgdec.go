// THE `ImageDecode` PORT, Go host (DEVLOG P6.1; SPEC 7.2, 7.3; D058, D089, D173-D176). The twin of
// `host/rust/src/imgdec.rs`: read that file for the reasoning. Encoded bytes (PNG, JPEG, TIFF) in, one frame's
// pixels out, normalised to the Bitmap contract: 8-bit gray or RGB, row-major, top-left, EXIF orientation
// applied, alpha composited onto white, resolution in dots per inch x100 (0 = unknown), a `bilevel` flag for
// one-bit gray. It never resamples.
//
// PNG and JPEG pixels come from `image/png` and `image/jpeg`; everything around them (header fields,
// reduction to 8 bits, alpha, orientation, resolution) is ours and is the same as Rust's. TIFF is read by our
// own container code over `ByteSource` (random access): IFDs, strips and tiles, then LZW, PackBits, Deflate
// (`compress/zlib`), CCITT G4 and G3 1-D (`golang.org/x/image/ccitt`), and our own bit unpacking and colour.
// What it refuses is `imgUnsupported`, a damaged file `imgFailed`, a frame over the pixel bound `imgLimit`.

package emon

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"math/bits"

	"golang.org/x/image/ccitt"
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

// imgDecode: frame >= 0 is that frame's pixels; frame < 0 only the header of frame -frame-1. maxPixels is the
// PIXELS limit.
func imgDecode(src ByteSource, frame int32, maxPixels uint64) (*imgDecoded, error) {
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
		return imgPng(src, want, maxPixels)
	case imgJPEG:
		if f != 0 {
			return nil, imgFailedf("this format has one frame")
		}
		return imgJpeg(src, want, maxPixels)
	case imgTIFF:
		return imgTiff(src, f, want, maxPixels)
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

// lut: n-bit sample -> 8 bits, rounded: (v * 255 + max / 2) / max (D174).
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

func imgDpiRational(num, den uint64, unit uint32) int32 {
	if den == 0 || num == 0 {
		return 0
	}
	var v uint64
	switch unit {
	case 2:
		v = (num*100*2 + den) / (den * 2)
	case 3:
		v = (num*254*2 + den) / (den * 2)
	}
	if v > 1<<31-1 {
		return 0
	}
	return int32(v)
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

// ------------------------------------------------------- the TIFF container (also EXIF)

type tifEntry struct {
	tag, typ uint16
	count    uint32
	raw      [4]byte
}

type tifIfd struct {
	entries []tifEntry
}

type tifFile struct {
	src ByteSource
	bo  binary.ByteOrder
}

func tifTypeSize(t uint16) int {
	switch t {
	case 1, 2, 6, 7:
		return 1
	case 3, 8:
		return 2
	case 4, 9, 11:
		return 4
	case 5, 10, 12:
		return 8
	}
	return 0
}

func newTifFile(src ByteSource) (*tifFile, error) {
	h, err := imgReadExact(src, 0, 8)
	if err != nil {
		return nil, err
	}
	t := &tifFile{src: src}
	switch string(h[:2]) {
	case "II":
		t.bo = binary.LittleEndian
	case "MM":
		t.bo = binary.BigEndian
	default:
		return nil, imgFailedf("not a TIFF header")
	}
	switch t.bo.Uint16(h[2:4]) {
	case 42:
		return t, nil
	case 43:
		return nil, imgUnsupportedf("BigTIFF")
	}
	return nil, imgFailedf("not a TIFF header")
}

func (t *tifFile) firstIfd() (uint32, error) {
	h, err := imgReadExact(t.src, 4, 4)
	if err != nil {
		return 0, err
	}
	return t.bo.Uint32(h), nil
}

func (t *tifFile) ifd(off uint32) (*tifIfd, error) {
	if off < 8 {
		return nil, imgFailedf("a TIFF directory at a bad offset")
	}
	c, err := imgReadExact(t.src, int64(off), 2)
	if err != nil {
		return nil, err
	}
	n := int(t.bo.Uint16(c))
	b, err := imgReadExact(t.src, int64(off)+2, n*12+4)
	if err != nil {
		return nil, err
	}
	d := &tifIfd{}
	for i := 0; i < n; i++ {
		e := b[i*12 : i*12+12]
		ent := tifEntry{tag: t.bo.Uint16(e[0:2]), typ: t.bo.Uint16(e[2:4]), count: t.bo.Uint32(e[4:8])}
		copy(ent.raw[:], e[8:12])
		d.entries = append(d.entries, ent)
	}
	return d, nil
}

func (t *tifFile) chain() ([]uint32, error) {
	var out []uint32
	off, err := t.firstIfd()
	if err != nil {
		return nil, err
	}
	for off != 0 && len(out) < 1<<20 {
		seen := false
		for _, o := range out {
			if o == off {
				seen = true
			}
		}
		if seen {
			break
		}
		c, err := imgReadExact(t.src, int64(off), 2)
		if err != nil {
			if len(out) == 0 {
				return nil, err
			}
			break
		}
		n := int64(t.bo.Uint16(c))
		nb, err := imgReadExact(t.src, int64(off)+2+n*12, 4)
		if err != nil {
			if len(out) == 0 {
				return nil, err
			}
			break
		}
		out = append(out, off)
		off = t.bo.Uint32(nb)
	}
	if len(out) == 0 {
		return nil, imgFailedf("a TIFF with no directory")
	}
	return out, nil
}

func (d *tifIfd) find(tag uint16) *tifEntry {
	for i := range d.entries {
		if d.entries[i].tag == tag {
			return &d.entries[i]
		}
	}
	return nil
}

// ints: the values of an entry as unsigned integers (BYTE, SHORT, LONG), at most cap of them; nil when absent.
func (t *tifFile) ints(d *tifIfd, tag uint16, cap int) ([]uint32, bool, error) {
	e := d.find(tag)
	if e == nil {
		return nil, false, nil
	}
	sz := tifTypeSize(e.typ)
	if !(e.typ == 1 || e.typ == 3 || e.typ == 4) || int(e.count) > cap {
		return nil, false, imgFailedf("a TIFF field of a type or size that does not fit")
	}
	n := int(e.count)
	var data []byte
	if sz*n <= 4 {
		data = e.raw[:sz*n]
	} else {
		var err error
		data, err = imgReadExact(t.src, int64(t.bo.Uint32(e.raw[:])), sz*n)
		if err != nil {
			return nil, false, err
		}
	}
	v := make([]uint32, n)
	for i := 0; i < n; i++ {
		switch sz {
		case 1:
			v[i] = uint32(data[i])
		case 2:
			v[i] = uint32(t.bo.Uint16(data[i*2:]))
		default:
			v[i] = t.bo.Uint32(data[i*4:])
		}
	}
	return v, true, nil
}

func (t *tifFile) int1(d *tifIfd, tag uint16) (uint32, bool, error) {
	v, ok, err := t.ints(d, tag, 1<<24)
	if err != nil || !ok || len(v) == 0 {
		return 0, false, err
	}
	return v[0], true, nil
}

func (t *tifFile) rational(d *tifIfd, tag uint16) (uint64, uint64, bool) {
	e := d.find(tag)
	if e == nil || e.typ != 5 || e.count < 1 {
		return 0, 0, false
	}
	b, err := imgReadExact(t.src, int64(t.bo.Uint32(e.raw[:])), 8)
	if err != nil {
		return 0, 0, false
	}
	return uint64(t.bo.Uint32(b[0:4])), uint64(t.bo.Uint32(b[4:8])), true
}

// imgExif: the orientation and the horizontal and vertical resolution (dpi x100, 0 unknown) of the first
// directory of an EXIF blob. A blob that does not read says nothing.
func imgExif(blob []byte) (o uint32, dx, dy int32) {
	o = 1
	t, err := newTifFile(memSource(blob))
	if err != nil {
		return
	}
	off, err := t.firstIfd()
	if err != nil {
		return
	}
	d, err := t.ifd(off)
	if err != nil {
		return
	}
	if v, ok, _ := t.int1(d, 274); ok && v >= 1 && v <= 8 {
		o = v
	}
	unit := uint32(2)
	if v, ok, _ := t.int1(d, 296); ok {
		unit = v
	}
	r := func(tag uint16) int32 {
		if n, dn, ok := t.rational(d, tag); ok {
			return imgDpiRational(n, dn, unit)
		}
		return 0
	}
	return o, r(282), r(283)
}

// ------------------------------------------------------------------------ PNG

type pngMeta struct {
	w, h         uint32
	depth, ctype uint8
	dx, dy       int32
	exif         []byte
}

func imgPngMeta(p []byte) (*pngMeta, error) {
	m := &pngMeta{ctype: 255}
	have := false
	i := 8
	for i+8 <= len(p) {
		n := int(binary.BigEndian.Uint32(p[i:]))
		kind := string(p[i+4 : i+8])
		if n < 0 || i+8+n > len(p) {
			break
		}
		body := p[i+8 : i+8+n]
		switch {
		case kind == "IHDR" && n >= 13:
			m.w = binary.BigEndian.Uint32(body[0:])
			m.h = binary.BigEndian.Uint32(body[4:])
			m.depth = body[8]
			m.ctype = body[9]
			have = true
		case kind == "pHYs" && n >= 9 && body[8] == 1:
			f := func(v uint64) int32 {
				r := (v*254 + 50) / 100
				if r > 1<<31-1 {
					r = 1<<31 - 1
				}
				return int32(r)
			}
			m.dx = f(uint64(binary.BigEndian.Uint32(body[0:])))
			m.dy = f(uint64(binary.BigEndian.Uint32(body[4:])))
		case kind == "eXIf":
			m.exif = append([]byte(nil), body...)
		}
		if kind == "IEND" {
			break
		}
		i += 12 + n
	}
	if !have || m.w == 0 || m.h == 0 {
		return nil, imgFailedf("a PNG without a usable header")
	}
	return m, nil
}

func imgPng(src ByteSource, want bool, maxPixels uint64) (*imgDecoded, error) {
	b, err := imgReadAll(src)
	if err != nil {
		return nil, err
	}
	m, err := imgPngMeta(b)
	if err != nil {
		return nil, err
	}
	o, ex, ey := uint32(1), int32(0), int32(0)
	if m.exif != nil {
		o, ex, ey = imgExif(m.exif)
	}
	gray := m.ctype == 0 || m.ctype == 4
	w, h := uint64(m.w), uint64(m.h)
	ow, oh := w, h
	if o >= 5 {
		ow, oh = h, w
	}
	dx, dy := m.dx, m.dy
	if dx <= 0 {
		dx, dy = ex, ey
	}
	out := &imgDecoded{W: int32(ow), H: int32(oh), Ch: 3, Frames: 1, Bilevel: m.ctype == 0 && m.depth == 1}
	if gray {
		out.Ch = 1
	}
	if o >= 5 {
		out.Dpi100 = dy
	} else {
		out.Dpi100 = dx
	}
	if w*h > maxPixels {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: w * h}
	}
	if ow > 1<<31-1 || oh > 1<<31-1 {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: 1<<64 - 1}
	}
	if !want {
		return out, nil
	}
	img, err := png.Decode(bytes.NewReader(b))
	if err != nil {
		return nil, imgFailedf(err.Error())
	}
	r := img.Bounds()
	iw, ih := r.Dx(), r.Dy()
	ch := int(out.Ch)
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
		type ent struct{ c [3]byte }
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
	out.Pixels = px
	return out, nil
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

func imgJpeg(src ByteSource, want bool, maxPixels uint64) (*imgDecoded, error) {
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
	var px []byte
	switch im := img.(type) {
	case *image.Gray:
		if m.ncomp != 1 {
			return nil, imgFailedf("a JPEG the decoder gave in a form that does not match its header")
		}
		px = make([]byte, iw*ih)
		for y := 0; y < ih; y++ {
			copy(px[y*iw:(y+1)*iw], im.Pix[y*im.Stride:y*im.Stride+iw])
		}
	case *image.YCbCr:
		if m.ncomp != 3 {
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
		if m.ncomp != 3 {
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
	px, _, _ = imgOrient(px, iw, ih, int(out.Ch), o)
	out.Pixels = px
	return out, nil
}

// imgUpsample brings a subsampled chroma plane to the size of the image with libjpeg's "fancy" (triangle)
// filters for 4:2:2 and 4:2:0 and 4:4:0 (what libjpeg-turbo and, within a byte or two, zune-jpeg do, so that the
// two hosts' pixels are close: a nearest-neighbour upsample differs by tens at a colour edge, D176); other
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

func imgTiff(src ByteSource, f int32, want bool, maxPixels uint64) (*imgDecoded, error) {
	t, err := newTifFile(src)
	if err != nil {
		return nil, err
	}
	chain, err := t.chain()
	if err != nil {
		return nil, err
	}
	if int(f) >= len(chain) {
		return nil, imgFailedf("a frame that the TIFF does not have")
	}
	d, err := t.ifd(chain[f])
	if err != nil {
		return nil, err
	}
	out, err := imgTiffFrame(t, d, want, maxPixels)
	if err != nil {
		return nil, err
	}
	out.Frames = int32(len(chain))
	return out, nil
}

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
	out := make([]byte, expect)
	n, err := io.ReadFull(zr, out)
	if err != nil && !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		return nil, imgFailedf("a bad Deflate stream")
	}
	if errors.Is(err, io.ErrUnexpectedEOF) {
		// a stream cut short: the fax library's twin, miniz, fails it, and so do we
		return nil, imgFailedf("a bad Deflate stream")
	}
	return out[:n], nil
}

// imgCcitt: `rows` rows of `cw` pixels, bits packed MSB first, 1 = black
func imgCcitt(d []byte, g4 bool, cw, rows int) ([]byte, error) {
	stride := (cw + 7) / 8
	mode := ccitt.Group3
	if g4 {
		mode = ccitt.Group4
	}
	r := ccitt.NewReader(bytes.NewReader(d), ccitt.MSB, mode, cw, rows, nil)
	out := make([]byte, stride*rows)
	n, err := io.ReadFull(r, out)
	if err != nil && g4 {
		return nil, imgFailedf("a bad CCITT G4 stream")
	}
	// Group 3 that stops early leaves the rest white (as the Rust host's `fax` does): only whole rows count
	done := n / stride
	if done > rows {
		done = rows
	}
	if done == 0 && !g4 {
		return nil, imgFailedf("a bad CCITT G3 stream")
	}
	for i := range out {
		if i < done*stride {
			// x/image/ccitt gives 0 for black and 1 for white; ours is the other way round
			out[i] = ^out[i]
		} else {
			out[i] = 0
		}
	}
	// the padding bits of each row are not pixels
	if cw%8 != 0 {
		for y := 0; y < rows; y++ {
			out[y*stride+stride-1] &= 0xFF << uint(8-cw%8)
		}
	}
	return out, nil
}

type imgPhoto struct {
	kind  uint32 // 0 MinIsWhite 1 MinIsBlack 2 RGB 3 palette 5 CMYK 6 YCbCr
	spp   int
	nc    int
	bps   uint
	alpha int // -1 none, 0 straight, 1 premultiplied
	cmap  []uint16
}

func imgTiffFrame(t *tifFile, d *tifIfd, want bool, maxPixels uint64) (*imgDecoded, error) {
	one := func(tag uint16) (uint32, bool, error) { return t.int1(d, tag) }
	orDefault := func(tag uint16, def uint32) (uint32, error) {
		v, ok, err := one(tag)
		if err != nil {
			return 0, err
		}
		if !ok {
			return def, nil
		}
		return v, nil
	}
	wv, ok, err := one(256)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, imgFailedf("a TIFF without a width")
	}
	hv, ok, err := one(257)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, imgFailedf("a TIFF without a height")
	}
	w, h := int(wv), int(hv)
	if w == 0 || h == 0 {
		return nil, imgFailedf("a TIFF frame with no pixels")
	}
	sppv, err := orDefault(277, 1)
	if err != nil {
		return nil, err
	}
	spp := int(sppv)
	if spp == 0 || spp > 16 {
		return nil, imgFailedf("a TIFF with a strange sample count")
	}
	bpsv, ok, err := t.ints(d, 258, 64)
	if err != nil {
		return nil, err
	}
	if !ok || len(bpsv) == 0 {
		bpsv = []uint32{1}
	}
	for len(bpsv) < spp {
		bpsv = append(bpsv, bpsv[len(bpsv)-1])
	}
	for _, b := range bpsv[:spp] {
		if b != bpsv[0] {
			return nil, imgUnsupportedf("TIFF samples of different depths")
		}
	}
	bps := uint(bpsv[0])
	if bps == 0 {
		return nil, imgFailedf("a TIFF with a strange bit depth")
	}
	if bps > 16 {
		return nil, imgUnsupportedf("TIFF samples deeper than 16 bits")
	}
	if sf, ok, err := t.ints(d, 339, 64); err != nil {
		return nil, err
	} else if ok {
		for _, v := range sf {
			if v != 1 && v != 4 {
				return nil, imgUnsupportedf("TIFF float or signed samples")
			}
		}
	}
	comp, err := orDefault(259, 1)
	if err != nil {
		return nil, err
	}
	kind, ok, err := one(262)
	if err != nil {
		return nil, err
	}
	if !ok {
		switch {
		case comp == 3 || comp == 4:
			kind = 0
		case spp >= 3:
			kind = 2
		default:
			kind = 1
		}
	}
	var nc int
	switch kind {
	case 0, 1, 3:
		nc = 1
	case 2, 6:
		nc = 3
	case 5:
		nc = 4
	default:
		return nil, imgUnsupportedf("this TIFF photometric interpretation (Lab, LogLuv, a mask)")
	}
	if spp < nc {
		return nil, imgFailedf("a TIFF with fewer samples than its colour needs")
	}
	if kind == 5 {
		if ink, err := orDefault(332, 1); err != nil {
			return nil, err
		} else if ink != 1 {
			return nil, imgUnsupportedf("a TIFF separation that is not CMYK")
		}
	}
	alpha := -1
	if spp > nc {
		if es, ok, err := t.ints(d, 338, 64); err != nil {
			return nil, err
		} else if ok && len(es) > 0 {
			switch es[0] {
			case 1:
				alpha = 1
			case 2:
				alpha = 0
			}
		}
	}
	var cmap []uint16
	if kind == 3 {
		v, ok, err := t.ints(d, 320, 3*65536)
		if err != nil {
			return nil, err
		}
		if !ok {
			return nil, imgFailedf("a palette TIFF with no colour map")
		}
		if len(v) < 3<<bps {
			return nil, imgFailedf("a TIFF colour map shorter than its depth needs")
		}
		cmap = make([]uint16, len(v))
		for i, x := range v {
			cmap[i] = uint16(x)
		}
	}
	if kind == 6 && (bps != 8 || spp != 3) {
		return nil, imgUnsupportedf("YCbCr TIFF other than 8 bits, three samples")
	}
	if (comp == 3 || comp == 4) && (bps != 1 || spp != 1) {
		return nil, imgFailedf("a CCITT TIFF that is not one-bit, one-sample")
	}
	switch comp {
	case 1, 3, 4, 5, 8, 32773, 32946:
	default:
		return nil, imgUnsupportedf("this TIFF compression (JPEG, ThunderScan, SGILog, ...)")
	}
	if comp == 3 {
		t4, err := orDefault(292, 0)
		if err != nil {
			return nil, err
		}
		// bit 0 two-dimensional, bit 1 uncompressed mode, bit 2 fill bits before each EOL: x/image/ccitt reads
		// none of them, and the two hosts must read the same set
		if t4&7 != 0 {
			return nil, imgUnsupportedf("CCITT Group 3 two-dimensional, uncompressed mode or fill bits")
		}
	}
	pl, err := orDefault(284, 1)
	if err != nil {
		return nil, err
	}
	planar2 := pl == 2 && spp > 1
	if planar2 && kind == 6 {
		return nil, imgUnsupportedf("planar YCbCr")
	}
	predictor, err := orDefault(317, 1)
	if err != nil {
		return nil, err
	}
	if predictor != 1 && !(predictor == 2 && (bps == 8 || bps == 16)) {
		return nil, imgUnsupportedf("this TIFF predictor")
	}
	fo, err := orDefault(266, 1)
	if err != nil {
		return nil, err
	}
	fill2 := fo == 2
	ov, err := orDefault(274, 1)
	if err != nil {
		return nil, err
	}
	if ov < 1 || ov > 8 {
		ov = 1
	}
	unit, err := orDefault(296, 2)
	if err != nil {
		return nil, err
	}
	res := func(tag uint16) int32 {
		if n, dn, ok := t.rational(d, tag); ok {
			return imgDpiRational(n, dn, unit)
		}
		return 0
	}
	rx, ry := res(282), res(283)
	ch := 3
	if kind == 0 || kind == 1 {
		ch = 1
	}
	ow, oh := w, h
	if ov >= 5 {
		ow, oh = h, w
	}
	out := &imgDecoded{W: int32(ow), H: int32(oh), Ch: int32(ch), Dpi100: rx, Bilevel: (kind == 0 || kind == 1) && bps == 1, Frames: 1}
	if ov >= 5 {
		out.Dpi100 = ry
	}
	pixels := uint64(w) * uint64(h)
	if pixels > maxPixels {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: pixels}
	}
	if ow > 1<<31-1 || oh > 1<<31-1 {
		return nil, &imgFail{Kind: imgLimit, Msg: "pixels", Pixels: 1<<64 - 1}
	}
	if !want {
		return out, nil
	}
	ph := &imgPhoto{kind: kind, spp: spp, nc: nc, bps: bps, alpha: alpha, cmap: cmap}

	_, tiled, err := t.ints(d, 322, 1)
	if err != nil {
		return nil, err
	}
	var cw0, bandRows, across, down int
	var offs, cnts []uint32
	pickInts := func(a, b uint16) ([]uint32, bool, error) {
		v, ok, err := t.ints(d, a, 1<<24)
		if err != nil || ok {
			return v, ok, err
		}
		return t.ints(d, b, 1<<24)
	}
	if tiled {
		tw, _ := orDefault(322, 0)
		th, _ := orDefault(323, 0)
		if tw == 0 || th == 0 || tw > 1<<20 || th > 1<<20 {
			return nil, imgFailedf("a TIFF with strange tiles")
		}
		cw0, bandRows = int(tw), int(th)
		across = (w + cw0 - 1) / cw0
		down = (h + bandRows - 1) / bandRows
		var ok bool
		if offs, ok, err = pickInts(324, 273); err != nil {
			return nil, err
		} else if !ok {
			return nil, imgFailedf("a tiled TIFF with no tile offsets")
		}
		if cnts, ok, err = pickInts(325, 279); err != nil {
			return nil, err
		} else if !ok {
			return nil, imgFailedf("a tiled TIFF with no tile byte counts")
		}
	} else {
		rv, err := orDefault(278, uint32(h))
		if err != nil {
			return nil, err
		}
		rps := int(rv)
		if rps == 0 || rps > h {
			rps = h
		}
		cw0, bandRows, across = w, rps, 1
		down = (h + rps - 1) / rps
		var ok bool
		if offs, ok, err = t.ints(d, 273, 1<<24); err != nil {
			return nil, err
		} else if !ok {
			return nil, imgFailedf("a TIFF with no strip offsets")
		}
		if cnts, _, err = t.ints(d, 279, 1<<24); err != nil {
			return nil, err
		}
	}
	planes := 1
	if planar2 {
		planes = spp
	}
	perPlane := across * down
	if len(offs) < perPlane*planes {
		return nil, imgFailedf("a TIFF with fewer strips or tiles than its size needs")
	}
	hs, vs := 1, 1
	if kind == 6 {
		v, ok, err := t.ints(d, 530, 2)
		if err != nil {
			return nil, err
		}
		if !ok {
			v = []uint32{2, 2}
		}
		okv := func(x uint32) bool { return x == 1 || x == 2 || x == 4 }
		if len(v) != 2 || !okv(v[0]) || !okv(v[1]) {
			return nil, imgFailedf("a TIFF with strange chroma subsampling")
		}
		hs, vs = int(v[0]), int(v[1])
	}
	cs := spp
	if planar2 {
		cs = 1
	}
	px := make([]byte, w*h*ch)
	table := imgLut(bps)
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
				idx := p*perPlane + by*across + bx
				off := int64(offs[idx])
				var n int64
				switch {
				case idx < len(cnts):
					n = int64(cnts[idx])
				case perPlane*planes == 1:
					n = t.src.Size() - off
					if n < 0 {
						n = 0
					}
				default:
					return nil, imgFailedf("a TIFF with no byte count for a strip")
				}
				data, err := imgReadExact(t.src, off, int(n))
				if err != nil {
					return nil, err
				}
				if fill2 {
					for i, b := range data {
						data[i] = bits.Reverse8(b)
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
					raw, err = imgCcitt(data, true, cw, crows)
				default:
					raw, err = imgCcitt(data, false, cw, crows)
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
								a := t.bo.Uint16(row[i*2:]) + t.bo.Uint16(row[(i-cs)*2:])
								t.bo.PutUint16(row[i*2:], a)
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
									v = t.bo.Uint16(row[k*2:])
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
	out.Pixels = px
	return out, nil
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
