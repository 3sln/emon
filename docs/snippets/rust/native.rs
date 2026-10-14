use emon::{pdfium::Pdfium, tess::TessOcr, Config, Engine, HtmlOptions, Input, OcrPort, Options, Output};
use std::sync::Arc;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut config = Config::default();
    config.renderer = Some(Arc::new(Pdfium::new())); // built with --cfg pdfium: links libpdfium
    config.ocr = Some(OcrPort(Arc::new(TessOcr::new(None)?))); // built with --features tesseract: links libtesseract and libleptonica
    let engine = Engine::new(config);
    let model = engine.convert(Input::bytes(std::fs::read("scan.pdf")?), &Options::default())?;
    std::fs::write("scan.html", model.serialize(&Output::Html(HtmlOptions::default())))?;
    Ok(())
}
