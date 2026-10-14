use emon::{Config, Engine, HtmlOptions, Input, Options, Output};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let engine = Engine::new(Config::default());
    let model = engine.convert(Input::bytes(std::fs::read("report.pdf")?), &Options::default())?;
    std::fs::write("report.html", model.serialize(&Output::Html(HtmlOptions::default())))?;
    Ok(())
}
