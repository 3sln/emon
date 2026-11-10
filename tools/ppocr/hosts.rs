//! Raw-answer transport probe. Input: lines of packed fixture paths.
use emon::{
    plan::{Bitmaps, Request},
    port::Bitmap,
    Ocr,
};
use std::{
    io::{self, BufRead},
    sync::Arc,
};
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("prepare") {
        let image = image::open(&args[2]).unwrap().to_rgb8();
        let mut request = emon::port::Cells::new();
        let at = emon::emon::plan::rq_ocr(&mut request, 2, 1, 3, 0, 0, 0, 0, 0);
        emon::emon::plan::rq_ocr_lang(&mut request, 101, 110, 103, 0);
        emon::emon::plan::rq_ocr_end(&mut request, at);
        emon::emon::ocrengine::oe_tag(&mut request, &mut emon::port::Cells::new(), 1);
        let n = emon::port::cells_len(&request);
        let mut out = Vec::new();
        for v in [n, image.width() as i32, image.height() as i32, 3] {
            out.extend(v.to_le_bytes());
        }
        for i in 0..n {
            out.extend(emon::port::cells_at(&request, i).to_le_bytes());
        }
        out.extend(image.as_raw());
        std::fs::write(&args[3], out).unwrap();
        return;
    }
    let port: Box<dyn Ocr> = if args.get(1).map(String::as_str) == Some("native") {
        Box::new(emon::ppocr_native::PpOcr::open(None, None).unwrap())
    } else {
        Box::new(emon::ppocr::PpOcr::new(std::path::Path::new(".cache/ppocr")).unwrap())
    };
    for path in io::stdin().lock().lines() {
        let data = std::fs::read(path.unwrap()).unwrap();
        let i = |at: usize| i32::from_le_bytes(data[at * 4..at * 4 + 4].try_into().unwrap());
        let (n, w, h, ch) = (i(0) as usize, i(1), i(2), i(3));
        let req = Request::new((4..4 + n).map(i).collect());
        let mut bm = Bitmaps::new();
        bm.insert(
            1,
            Arc::new(Bitmap::from_bytes(w, h, ch, &data[(4 + n) * 4..])),
        );
        let a = port.fulfil(&req, &bm, false).unwrap();
        println!("{:?}", a.rec);
    }
}
