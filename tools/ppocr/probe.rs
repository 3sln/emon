#[path = "../../host/rust/src/ppocr_ctc.rs"]
mod ppocr_ctc;
#[path = "../../host/rust/src/ppocr_image.rs"]
mod ppocr_image;
#[path = "../../host/rust/src/ppocr_pipeline.rs"]
mod ppocr_pipeline;
#[path = "../../host/rust/src/ppocr_runtime.rs"]
mod ppocr_runtime;
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let w = args[2].parse().unwrap();
    let h = args[3].parse().unwrap();
    let im = ppocr_image::Image::new(w, h, std::fs::read(&args[1]).unwrap()).unwrap();
    let mut p = ppocr_pipeline::Pipeline::open(
        std::path::Path::new(".cache/ppocr"),
        include_str!("../../tools/ppocr/en-dictionary.txt"),
    )
    .unwrap();
    let r = p.read(&im).unwrap();
    for word in r.words {
        println!("{}\t{:?}\t{}", word.text, word.bounds, word.posterior);
    }
}
