//! Three sequential calls to the actual reference Ocr port in one fresh process.
#[cfg(ppocr)]
fn main() {
    use emon::{
        port::{cells_at, cells_len, Bitmap, Cells},
        Ocr,
    };
    use std::sync::Arc;
    let path = std::env::args().nth(1).expect("ppocr-repeat PNG/JPEG");
    let image = image::open(path).unwrap().to_rgb8();
    let bitmap = Bitmap::from_bytes(
        image.width() as i32,
        image.height() as i32,
        3,
        image.as_raw(),
    );
    let mut bitmaps = emon::plan::Bitmaps::new();
    bitmaps.insert(1, Arc::new(bitmap));
    let mut request = Cells::new();
    let at = emon::emon::plan::rq_ocr(&mut request, 2, 1, 3, 0, 0, 0, 0, 0);
    emon::emon::plan::rq_ocr_lang(&mut request, 101, 110, 103, 0);
    emon::emon::plan::rq_ocr_end(&mut request, at);
    emon::emon::ocrengine::oe_tag(&mut request, &mut Cells::new(), 1);
    let request = emon::plan::Request::new(
        (0..cells_len(&request))
            .map(|i| cells_at(&request, i))
            .collect(),
    );
    let port = emon::ppocr::PpOcr::new(std::path::Path::new(".cache/ppocr")).unwrap();
    let mut expected = None;
    for _ in 0..3 {
        let answer = port.fulfil(&request, &bitmaps, false).unwrap();
        if let Some(ref first) = expected {
            assert_eq!(first, &answer.rec);
        }
        expected = Some(answer.rec.clone());
        println!("{:?}", answer.rec);
    }
}
#[cfg(not(ppocr))]
fn main() {
    panic!("ppocr is unsupported in this build");
}
