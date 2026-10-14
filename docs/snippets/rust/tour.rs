use emon::{parse_options, Engine, Input, Kind};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // One text says what to do, limits included (docs/options.md).
    let parsed = parse_options("ocr=never\nlimit.pages=500")?;
    let engine = Engine::new(parsed.config);

    // An input is a byte source. A file (Read + Seek) is read lazily, a chunk at a time, only where the engine looks.
    // Stage 0 alone is cheap: format, version, pages, encryption.
    let file = Input::seekable(std::fs::File::open("report.pdf")?)?;
    for intake in engine.intake(file)? {
        println!("{} {}.{}, {} pages", intake.format, intake.version.0, intake.version.1, intake.pages);
    }

    // A failure has a stable name to route on: "password-required", "pages", "bad-xref", ...
    let bytes = std::fs::read("report.pdf")?; // or the bytes you already have
    let model = match engine.convert(Input::bytes(bytes), &parsed.options) {
        Ok(model) => model,
        Err(e) => {
            eprintln!("{}: {}", e.name(), e);
            std::process::exit(1);
        }
    };

    // Walk the Model: every heading, with its page.
    for h in model.find(Kind::named("heading").unwrap()) {
        let page = h.regions().first().map_or(0, |r| r.page);
        println!("page {}: {}", page, h.text());
    }
    println!("{} pages, {} nodes, {} dropped", model.pages().len(), model.node_count(), model.dropped().len());
    Ok(())
}
