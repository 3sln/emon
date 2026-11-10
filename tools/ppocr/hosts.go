package main

import (
	"bufio"
	"emon"
	"emon/port"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"os"
)

func main() {
	p, e := emon.NewPpOcr("", nil)
	if e != nil {
		panic(e)
	}
	defer p.Close()
	s := bufio.NewScanner(os.Stdin)
	for s.Scan() {
		d, e := os.ReadFile(s.Text())
		if e != nil {
			panic(e)
		}
		i := func(at int) int32 { return int32(binary.LittleEndian.Uint32(d[at*4:])) }
		n, w, h, ch := int(i(0)), i(1), i(2), i(3)
		r := make([]int32, n)
		for k := range r {
			r[k] = i(k + 4)
		}
		a, e := p.Fulfil(port.PlanRequest{Rec: r}, port.PlanBitmaps{1: port.BitmapFromBytes(w, h, ch, d[(4+n)*4:])}, false)
		if e != nil {
			panic(e)
		}
		b, _ := json.Marshal(a.Rec)
		fmt.Println(string(b))
	}
	if s.Err() != nil {
		panic(s.Err())
	}
}
