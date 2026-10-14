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
	ocr, err := emon.NewTessOcr("") // "": $TESSDATA_PREFIX and the usual places (build with -tags tesseract)
	if err != nil {
		panic(err)
	}
	config := emon.DefaultConfig()
	config.Renderer = renderer
	config.Ocr = ocr
	engine := emon.NewEngine(config)

	data, err := os.ReadFile("scan.pdf")
	if err != nil {
		panic(err)
	}
	model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
	if err != nil {
		panic(err)
	}
	err = os.WriteFile("scan.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
	// docs:end
	if err != nil {
		panic(err)
	}
}
