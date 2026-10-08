// The `bb fuzz` driver, Go (DEVLOG P2.12, D106): the twin of test/fuzz/rust/main.rs.
// Test-only. Reads one file path per line on stdin; writes
// `NAME<TAB>MEMORY-LINE[<TAB>ASYNC-LINE]`, a recovered panic as `PANIC <message>`.
package main

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"

	"emon/fuzzprobe"
	"emon/port"
)

func run(b []byte, chunk int32) (out string) {
	defer func() {
		if r := recover(); r != nil {
			// a limit breach is a typed payload and a NAMED outcome (SPEC 7.4, P10.10), the same line on every host: nothing reads a message
			if b, ok := r.(*port.Breach); ok {
				out = "LIMIT-" + b.Name()
				return
			}
			m := strings.NewReplacer("\n", " ", "\t", " ").Replace(fmt.Sprint(r))
			out = "PANIC " + m
		}
	}()
	var t strings.Builder
	fuzzprobe.FzCase(b, chunk, &t)
	return t.String()
}

var _ = port.NewCells

func main() {
	chunk := int32(0)
	for _, a := range os.Args[1:] {
		if strings.HasPrefix(a, "--chunk=") {
			n, _ := strconv.Atoi(strings.TrimPrefix(a, "--chunk="))
			chunk = int32(n)
		}
	}
	sc := bufio.NewScanner(os.Stdin)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	w := bufio.NewWriter(os.Stdout)
	for sc.Scan() {
		path := sc.Text()
		data, err := os.ReadFile(path)
		if err != nil {
			fmt.Fprintf(w, "%s\tIOERROR\n", path)
			w.Flush()
			continue
		}
		out := path + "\t" + run(data, 0)
		if chunk > 0 {
			out += "\t" + run(data, chunk)
		}
		fmt.Fprintln(w, out)
		w.Flush()
	}
}
