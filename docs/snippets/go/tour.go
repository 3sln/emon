package main

// docs:begin
import (
	"errors"
	"fmt"
	"os"

	emon "github.com/3sln/emon/go"
)

// docs:end

func main() {
	// docs:begin
	data, err := os.ReadFile("report.pdf")
	if err != nil {
		panic(err)
	}

	// One text says what to do, limits included (docs/options.md).
	parsed, err := emon.ParseOptions("ocr=never\nlimit.pages=500")
	if err != nil {
		panic(err)
	}
	engine := emon.NewEngine(parsed.Config)
	inputs := []emon.Input{emon.BytesInput(data)} // or the bytes you already have

	// An input is a byte source. A file (io.ReadSeeker) is read lazily, a chunk at a time, only where the engine looks.
	// Stage 0 alone is cheap: format, version, pages, encryption.
	file, err := os.Open("report.pdf")
	if err != nil {
		panic(err)
	}
	defer file.Close()
	lazy, err := emon.SeekableInput(file)
	if err != nil {
		panic(err)
	}
	intakes, err := engine.Intake([]emon.Input{lazy})
	if err != nil {
		panic(err)
	}
	for _, in := range intakes {
		fmt.Printf("%s %d.%d, %d pages\n", in.Format, in.VersionMajor, in.VersionMinor, in.Pages)
	}

	model, err := engine.Convert(inputs, parsed.Options)
	if err != nil {
		var e *emon.Error
		if errors.As(err, &e) {
			// e.Name() is the stable name to route on: "password-required", "pages", "bad-xref", ...
			fmt.Fprintln(os.Stderr, e.Name()+": "+e.Error())
			os.Exit(1)
		}
		panic(err)
	}

	// Walk the Model: every heading, with its page.
	heading, _ := emon.KindNamed("heading")
	for _, h := range model.Find(heading) {
		page := int32(0)
		if rs := h.Regions(); len(rs) > 0 {
			page = rs[0].Page
		}
		fmt.Printf("page %d: %s\n", page, h.Text())
	}
	fmt.Printf("%d pages, %d nodes, %d dropped\n", len(model.Pages()), model.NodeCount(), len(model.Dropped()))
	// docs:end
}
