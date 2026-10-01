// THE MODEL, Go host (DEVLOG P2.1; SPEC 3.9, 4, 5). The twin of the Model half of `host/rust/src/engine.rs`.
//
// The Model is the seven arenas of SPEC 3.8, owned; `Node` is a (Model, index) pair and never copies
// (SPEC 3.9). Every accessor is a kin one (`node-kind`, `node-first-child`, ...): this layer renames and
// iterates, it decides nothing. A Model is read-only after Convert and its accessors take a lock, so it may
// be read from several goroutines.

package emon

import (
	"strings"
	"sync"

	"emon/html"
	"emon/json"
	"emon/model"
	"emon/port"
)

// Model is a converted document.
type Model struct {
	mu       sync.Mutex
	a        *arenas
	evidence *string
	intake   Intake
}

// Kind is a node's kind, by kin's own table (`emon.model` `nk`): `Name()` is the JSON `kind`.
type Kind int32

// Name is the JSON `kind`.
func (k Kind) Name() string {
	var sb strings.Builder
	model.NkEmitName(&sb, int32(k))
	return sb.String()
}

// KindNamed is the kind with this JSON name (`"paragraph"`), if there is one.
func KindNamed(name string) (Kind, bool) {
	for k := int32(1); k <= model.NkInitial(); k++ {
		if Kind(k).Name() == name {
			return Kind(k), true
		}
	}
	return 0, false
}

// Page is a page of the Model's page table (SPEC 3.7). Sizes are in centipoints (1/100 point), as displayed.
type Page struct {
	Number   int32
	Label    string
	HasLabel bool
	Width    int32
	Height   int32
	Rotation int32
	Kind     string
}

// Region is a region of a node: a page and a box in centipoints, origin top left of the displayed page.
type Region struct {
	Page           int32
	X0, Y0, X1, Y1 int32
}

// AttrKind says which field of an Attr is meant (kin's `av-*`).
type AttrKind int

const (
	AttrInt AttrKind = iota
	AttrText
	// AttrNode is another node, by index.
	AttrNode
	AttrBool
)

// Attr is an attribute's typed value.
type Attr struct {
	Kind AttrKind
	Int  int32
	Text string
	Node int32
	Bool bool
}

// Dropped is an entry of the ledger of dropped content (SPEC 3.7).
type Dropped struct {
	Reason string
	Page   int32
	Region Region
	Text   string
}

func (m *Model) with(f func(a *arenas)) {
	m.mu.Lock()
	defer m.mu.Unlock()
	f(m.a)
}

// Root is the document node.
func (m *Model) Root() Node { return Node{m: m, n: 0} }

// Source is what stage 0 said of the input this Model was made from.
func (m *Model) Source() Intake { return m.intake }

// NodeCount is the number of nodes.
func (m *Model) NodeCount() int {
	var n int32
	m.with(func(a *arenas) { n = model.NodeCount(a.nodes) })
	return int(n)
}

// Pages is the page table.
func (m *Model) Pages() []Page {
	var out []Page
	m.with(func(a *arenas) {
		for p := int32(0); p < model.PageCount(a.pages); p++ {
			ls, ll := model.PageLabelStart(a.pages, p), model.PageLabelLen(a.pages, p)
			var kind strings.Builder
			model.PkEmitName(&kind, model.PageKind(a.pages, p))
			pg := Page{Number: model.PageNumber(a.pages, p), Width: model.PageW(a.pages, p), Height: model.PageH(a.pages, p),
				Rotation: model.PageRot(a.pages, p), Kind: kind.String()}
			if ls >= 0 {
				pg.Label, pg.HasLabel = textOf(a.text, ls, ll), true
			}
			out = append(out, pg)
		}
	})
	return out
}

// Furniture is the furniture templates (SPEC 3.7): the top-level `furniture` nodes, in order.
func (m *Model) Furniture() []Node {
	var ids []int32
	m.with(func(a *arenas) {
		for n := int32(0); n < model.NodeCount(a.nodes); n++ {
			if model.NodeKind(a.nodes, n) == model.NkFurniture() && model.NodeParent(a.nodes, n) == -1 {
				ids = append(ids, n)
			}
		}
	})
	return m.nodes(ids)
}

func (m *Model) nodes(ids []int32) []Node {
	out := make([]Node, len(ids))
	for i, n := range ids {
		out[i] = Node{m: m, n: n}
	}
	return out
}

// Dropped is the ledger of dropped content.
func (m *Model) Dropped() []Dropped {
	var out []Dropped
	m.with(func(a *arenas) {
		l := a.ledger
		for i := int32(0); i < model.LedgerCount(l); i++ {
			var reason strings.Builder
			model.DrEmitName(&reason, model.LedgerReason(l, i))
			ts, tl := model.LedgerTextStart(l, i), model.LedgerTextLen(l, i)
			d := Dropped{Reason: reason.String(), Page: model.LedgerPage(l, i),
				Region: Region{model.LedgerPage(l, i), model.LedgerX0(l, i), model.LedgerY0(l, i), model.LedgerX1(l, i), model.LedgerY1(l, i)}}
			if ts >= 0 {
				d.Text = textOf(a.text, ts, tl)
			}
			out = append(out, d)
		}
	})
	return out
}

// Find is every node of a kind, in document order (the arena is in preorder: `model-check` says so).
func (m *Model) Find(kind Kind) []Node {
	var ids []int32
	m.with(func(a *arenas) {
		for n := int32(0); n < model.NodeCount(a.nodes); n++ {
			if model.NodeKind(a.nodes, n) == int32(kind) {
				ids = append(ids, n)
			}
		}
	})
	return m.nodes(ids)
}

// Evidence is the text layer's evidence dump, when `Options.KeepEvidence` asked for it.
func (m *Model) Evidence() (string, bool) {
	if m.evidence == nil {
		return "", false
	}
	return *m.evidence, true
}

// ToJSON is SPEC 4's canonical JSON.
func (m *Model) ToJSON(o JSONOptions) string {
	var sb strings.Builder
	m.with(func(a *arenas) {
		var pretty int32
		if o.Pretty {
			pretty = 1
		}
		json.JsEmit(&sb, pretty, a.nodes, a.attrs, a.regions, a.text, a.pages, a.ledger, a.layout)
	})
	return sb.String()
}

// ToHTML is SPEC 5's HTML for crawlers.
func (m *Model) ToHTML(o HTMLOptions) string {
	bits, below := o.bits()
	var sb strings.Builder
	m.with(func(a *arenas) {
		html.HtmlEmit(&sb, bits, below, a.nodes, a.attrs, a.regions, a.text, a.pages, a.ledger, a.layout)
	})
	return sb.String()
}

// Serialize is the same, by an `Output` value.
func (m *Model) Serialize(o Output) string {
	if o.HTML != nil {
		return m.ToHTML(*o.HTML)
	}
	if o.JSON != nil {
		return m.ToJSON(*o.JSON)
	}
	return m.ToJSON(JSONOptions{})
}

// Node is a node: the Model and an index. It borrows, it never copies.
type Node struct {
	m *Model
	n int32
}

func (n Node) String() string { return "Node(" + itoa(n.n) + " " + n.Kind().Name() + ")" }

func itoa(v int32) string {
	var sb strings.Builder
	port.TextI32(&sb, v)
	return sb.String()
}

func (n Node) num(f func(c *port.Cells, i int32) int32) int32 {
	var v int32
	n.m.with(func(a *arenas) { v = f(a.nodes, n.n) })
	return v
}

// Index is the node's index in the Model (kin's `node-*` accessors take it).
func (n Node) Index() int { return int(n.n) }

// ID is the JSON `id`: the index. Inline nodes carry none in JSON (D052) but have an index here.
func (n Node) ID() int32 { return n.n }

// Kind is the node's kind.
func (n Node) Kind() Kind { return Kind(n.num(model.NodeKind)) }

// Conf is the confidence, 0..=1000, or -1 when the node has none.
func (n Node) Conf() int32 { return n.num(model.NodeConf) }

// Sources is provenance (SPEC 3.5), as the JSON `src` names, in kin's bit order.
func (n Node) Sources() []string {
	bits := n.num(model.NodeSrc)
	var out []string
	for b := 0; b < 9; b++ {
		bit := int32(1) << b
		if bits&bit != 0 {
			var sb strings.Builder
			model.SrEmitName(&sb, bit)
			out = append(out, sb.String())
		}
	}
	return out
}

// Flags is the warning bits (SPEC 3.3), raw: kin's `fl-*`.
func (n Node) Flags() int32 { return n.num(model.NodeFlags) }

// Parent is the parent node, absent for the root.
func (n Node) Parent() (Node, bool) {
	p := n.num(model.NodeParent)
	if p < 0 {
		return Node{}, false
	}
	return Node{m: n.m, n: p}, true
}

// Children is every child in document order: blocks and inlines alike (`Inlines` and `Blocks` split them).
func (n Node) Children() []Node {
	var out []Node
	c := n.num(model.NodeFirstChild)
	for c >= 0 {
		out = append(out, Node{m: n.m, n: c})
		var next int32
		n.m.with(func(a *arenas) { next = model.NodeNext(a.nodes, c) })
		c = next
	}
	return out
}

// IsInline is whether the node is an inline.
func (n Node) IsInline() bool { return n.num(model.NodeIsInline) == 1 }

// Inlines is the inline children.
func (n Node) Inlines() []Node {
	var out []Node
	for _, c := range n.Children() {
		if c.IsInline() {
			out = append(out, c)
		}
	}
	return out
}

// Blocks is the block children.
func (n Node) Blocks() []Node {
	var out []Node
	for _, c := range n.Children() {
		if !c.IsInline() {
			out = append(out, c)
		}
	}
	return out
}

func (n Node) own() (string, bool) {
	if n.num(model.NodeHasText) != 1 {
		return "", false
	}
	st, ln := n.num(model.NodeTextStart), n.num(model.NodeTextLen)
	var s string
	n.m.with(func(a *arenas) { s = textOf(a.text, st, ln) })
	return s, true
}

// Text is the node's plain text: its own run when it has one, else (D052, as the JSON's `text`) the text of
// its inline children, a line break as a newline, a span or link by descent. Empty for a node with neither.
func (n Node) Text() string {
	if s, ok := n.own(); ok {
		return s
	}
	var sb strings.Builder
	n.derive(&sb)
	return sb.String()
}

func (n Node) derive(sb *strings.Builder) {
	for _, c := range n.Children() {
		k := c.Kind()
		if int32(k) == model.NkLinebreak() {
			sb.WriteByte('\n')
		}
		if model.NkIsInline(int32(k)) == 1 {
			if s, ok := c.own(); ok {
				sb.WriteString(s)
			}
			c.derive(sb)
		}
	}
}

// Regions is the regions the node covers, one per page (or column fragment): centipoints.
func (n Node) Regions() []Region {
	var out []Region
	n.m.with(func(a *arenas) {
		r := model.NodeRegionHead(a.nodes, n.n)
		for i := int32(0); i < model.NodeRegionCount(a.nodes, n.n); i++ {
			out = append(out, Region{model.RegionPage(a.regions, r), model.RegionX0(a.regions, r), model.RegionY0(a.regions, r),
				model.RegionX1(a.regions, r), model.RegionY1(a.regions, r)})
			r = model.RegionNext(a.regions, r)
		}
	})
	return out
}

// Attr is an attribute by its JSON name (`level`, `align`, `locale`, ...), typed; false when absent.
func (n Node) Attr(name string) (Attr, bool) {
	var out Attr
	var found bool
	n.m.with(func(a *arenas) {
		at := model.NodeAttrHead(a.nodes, n.n)
		for at >= 0 {
			var key strings.Builder
			model.FkEmitName(&key, model.AttrKey(a.attrs, at))
			if key.String() == name {
				v, l := model.AttrValue(a.attrs, at), model.AttrLen(a.attrs, at)
				switch model.AttrVkind(a.attrs, at) {
				case model.AvText():
					out = Attr{Kind: AttrText, Text: textOf(a.text, v, l)}
				case model.AvNode():
					out = Attr{Kind: AttrNode, Node: v}
				case model.AvBool():
					out = Attr{Kind: AttrBool, Bool: v != 0}
				default:
					out = Attr{Kind: AttrInt, Int: v}
				}
				found = true
				return
			}
			at = model.AttrNext(a.attrs, at)
		}
	})
	return out, found
}
