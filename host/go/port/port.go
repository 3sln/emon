// The Go half of emon's host ports. See the Rust half for the reasoning.
//
// @kin:link:ns: emon.port

package port

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// ------------------------------------------------------------------ the Blob

// @kin:link:form:blob-len: {:template "port.BlobLen({0})" :import "emon/port" :arity 1}
//
// `:import` IS GO'S ALONE: Rust and the two managed hosts reach the port by
// path or qualified name; Go's header must name the package.
func BlobLen(b []byte) int32 {
	return int32(len(b))
}

// @kin:link:form:blob-u8: {:template "port.BlobU8({0}, {1})" :import "emon/port" :arity 2}
func BlobU8(b []byte, i int32) int32 {
	return int32(b[i])
}

// @kin:link:form:blob-u16: {:template "port.BlobU16({0}, {1})" :import "emon/port" :arity 2}
func BlobU16(b []byte, i int32) int32 {
	return int32(b[i])<<8 | int32(b[i+1])
}

// @kin:link:form:blob-u32: {:template "port.BlobU32({0}, {1})" :import "emon/port" :arity 2}
func BlobU32(b []byte, i int32) int32 {
	return int32(uint32(b[i])<<24 | uint32(b[i+1])<<16 | uint32(b[i+2])<<8 | uint32(b[i+3]))
}

// @kin:link:form:blob-u16le: {:template "port.BlobU16Le({0}, {1})" :import "emon/port" :arity 2}
func BlobU16Le(b []byte, i int32) int32 {
	return int32(b[i+1])<<8 | int32(b[i])
}

// @kin:link:form:blob-u32le: {:template "port.BlobU32Le({0}, {1})" :import "emon/port" :arity 2}
func BlobU32Le(b []byte, i int32) int32 {
	return int32(uint32(b[i+3])<<24 | uint32(b[i+2])<<16 | uint32(b[i+1])<<8 | uint32(b[i]))
}

// ------------------------------------------------------------------ the Cells
type Cells struct {
	V []int32
}

func NewCells() *Cells {
	return &Cells{}
}

// @kin:link:form:cells-len: {:template "port.CellsLen({0})" :import "emon/port" :arity 1}
func CellsLen(c *Cells) int32 {
	return int32(len(c.V))
}

// @kin:link:form:cells-at: {:template "port.CellsAt({0}, {1})" :import "emon/port" :arity 2}
func CellsAt(c *Cells, i int32) int32 {
	return c.V[i]
}

// @kin:link:form:cells-set: {:template "port.CellsSet({0}, {1}, {2})" :import "emon/port" :arity 3}
func CellsSet(c *Cells, i int32, v int32) {
	c.V[i] = v
}

// @kin:link:form:cells-push: {:template "port.CellsPush({0}, {1})" :import "emon/port" :arity 2}
func CellsPush(c *Cells, v int32) int32 {
	c.V = append(c.V, v)
	return int32(len(c.V) - 1)
}

// @kin:link:form:cells-drop: {:template "port.CellsDrop({0}, {1})" :import "emon/port" :arity 2}
func CellsDrop(c *Cells, n int32) {
	c.V = c.V[:n]
}

// @kin:link:form:cells-bytes: {:template "port.CellsBytes({0})" :import "emon/port" :arity 1}
//
// A Blob VIEW of a Cells of bytes: a snapshot (see the Rust half).
func CellsBytes(c *Cells) []byte {
	b := make([]byte, len(c.V))
	for i, v := range c.V {
		b[i] = byte(v)
	}
	return b
}

// ------------------------------------------------------------------ the Text sink

// @kin:link:form:text-cp: {:template "port.TextCp({0}, {1})" :import "emon/port" :arity 2}
//
// WriteRune writes U+FFFD for an invalid rune (negative, past U+10FFFF, or a
// surrogate) -- the behaviour every host is held to.
func TextCp(s *strings.Builder, cp int32) {
	s.WriteRune(rune(cp))
}

// @kin:link:form:text-ascii: {:template "port.TextAscii({0}, {1})" :import "emon/port" :arity 2}
func TextAscii(s *strings.Builder, lit string) {
	s.WriteString(lit)
}

// @kin:link:form:text-i32: {:template "port.TextI32({0}, {1})" :import "emon/port" :arity 2}
func TextI32(s *strings.Builder, v int32) {
	s.WriteString(strconv.Itoa(int(v)))
}

// ------------------------------------------------------------------ limits

// @kin:link:form:limit-of: {:template "port.LimitOf({0})" :import "emon/port" :arity 1}
func LimitOf(kind int32) int32 {
	name, def := limitSpec(kind)
	if v, ok := os.LookupEnv("EMON_LIMIT_" + name); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}

// @kin:link:form:limit-breach: {:template "port.LimitBreach({0}, {1})" :import "emon/port" :arity 2}
func LimitBreach(kind int32, actual int32) int32 {
	name, _ := limitSpec(kind)
	panic(fmt.Sprintf(
		"the %s limit is %d and this input reached %d. Raise it with "+
			"EMON_LIMIT_%s, or find out why the input needs that much -- a limit "+
			"is a bound on emon, not a statement about the document.",
		strings.ToLower(strings.ReplaceAll(name, "_", " ")), LimitOf(kind), actual, name))
}

// @kin:link:form:setting-of: {:template "port.SettingOf({0})" :import "emon/port" :arity 1}
func SettingOf(kind int32) int32 {
	name, def := settingSpec(kind)
	if v, ok := os.LookupEnv("EMON_SETTING_" + name); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}

func limitSpec(kind int32) (string, int32) {
	switch kind {
	case 0:
		return "INPUT_BYTES", 1073741824
	case 1:
		return "STREAM_BYTES", 268435456
	case 2:
		return "TOTAL_BYTES", 1073741824
	case 3:
		return "OBJECTS", 10000000
	case 4:
		return "REF_DEPTH", 64
	case 5:
		return "OPERATORS", 50000000
	case 6:
		return "FORM_DEPTH", 32
	case 7:
		return "PATTERN_DEPTH", 8
	case 8:
		return "PAGES", 100000
	case 9:
		return "PIXELS", 268435456
	case 10:
		return "OCR_REQUESTS", 100000
	}
	return "UNKNOWN", 0
}

func settingSpec(kind int32) (string, int32) {
	switch kind {
	case 0:
		return "OCR_MODE", 0
	case 1:
		return "TASKS", 0
	}
	return "UNKNOWN", 0
}
