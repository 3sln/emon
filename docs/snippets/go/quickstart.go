package main

// docs:begin
import (
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
	engine := emon.NewEngine(emon.DefaultConfig())
	model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
	if err != nil {
		panic(err) // *emon.Error: err.(*emon.Error).Name() is the stable name
	}
	err = os.WriteFile("report.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
	// docs:end
	if err != nil {
		panic(err)
	}
}
