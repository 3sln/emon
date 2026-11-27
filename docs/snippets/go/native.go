package main

// docs:begin
import (
	"os"

	emon "github.com/3sln/emon/go"
	"github.com/3sln/emon/go/pdfium"
)

// docs:end

func main() {
	// docs:begin
	renderer, err := pdfium.Open("") // "": $EMON_PDFIUM_LIB, then the system's search
	if err != nil {
		panic(err) // *pdfium.Unavailable: the library was not found
	}
	options := emon.DefaultOptions()
	engines := emon.NewEngines() // OCR engines are registered adapters, never wired in
	defer engines.Close()
	if err := engines.Defaults(options); err != nil { // lib/emon/adapters beside the program, or $EMON_ADAPTER_DIR
		panic(err)
	}
	config := emon.DefaultConfig()
	config.Renderer = renderer
	config.Ocr = engines
	engine := emon.NewEngine(config)

	data, err := os.ReadFile("scan.pdf")
	if err != nil {
		panic(err)
	}
	model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, options)
	if err != nil {
		panic(err)
	}
	err = os.WriteFile("scan.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
	// docs:end
	if err != nil {
		panic(err)
	}
}
