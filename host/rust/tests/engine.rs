// The Engine API's native tests (DEVLOG P1.10), run by `bb slice` (`kin/scripts/slice`) against the assembled
// crate (`kin/scripts/emon-rs-build`). They read fixtures from the repository's `test/`: a TEST may read files,
// the library may not (D058). What they hold:
//
//   * the three byte-source adapters (bytes, Read + Seek, spooled Read) give IDENTICAL JSON and HTML;
//   * a 512-byte ASYNC source (misses, fetch rounds, re-runs) gives the in-memory run's output, byte for byte,
//     over the slice's fixtures AND every PDF fixture of `test/pdf` (damaged, repaired, incremental,
//     encrypted ones included: an Err must be the same Err);
//   * D040's three encryption errors, distinct; a password opens what it opens, and equals the plain twin;
//   * the input-bytes limit is checked against the LENGTH before any byte is read; a source that lies
//     about its length is `source-changed`;
//   * `assets` other than `none` and several inputs as one document are refused, not approximated;
//   * record and replay; the walkable Model; and that the library names no filesystem;
//   * the Renderer port (P6.2, D127): a fake engine through `RenderFulfiller` and through the Engine's Config.
use emon::{Assets, ByteSource, Config, Engine, Error, HtmlOptions, Input, JsonOptions, Kind, Ocr, OcrPolicy, OcrPort, Options, SeekSource};
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};

fn root() -> PathBuf {
    match std::env::var("EMON_ROOT") {
        Ok(r) => PathBuf::from(r),
        Err(_) => Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join(".."),
    }
}

fn read(rel: &str) -> Vec<u8> {
    let p = root().join(rel);
    std::fs::read(&p).unwrap_or_else(|e| panic!("{}: {}", p.display(), e))
}

const SLICE: [&str; 7] = ["h-plain", "h-keyword", "h-inverted-numbered", "h-numbered", "initial-image", "initial-vector", "furn-informative"];

fn synth(name: &str) -> Vec<u8> {
    read(&format!("test/corpus/synth/pdf/{}.pdf", name))
}

fn json(m: &emon::Model) -> String {
    m.to_json(&JsonOptions { pretty: true })
}

// a plain `Read`: no seek
struct Forward(Cursor<Vec<u8>>);
impl Read for Forward {
    fn read(&mut self, b: &mut [u8]) -> std::io::Result<usize> {
        self.0.read(b)
    }
}

fn async_engine(chunk: usize) -> Engine {
    Engine::new(Config { chunk_size: chunk, simulate_async: true, ..Config::default() })
}

#[test]
fn the_three_adapters_give_identical_json_and_html() {
    for name in SLICE {
        let bytes = synth(name);
        let e = Engine::new(Config::default());
        let o = Options::default();
        let by_bytes = e.convert(Input::bytes(bytes.clone()), &o).unwrap();
        let by_slice = e.convert(&bytes[..], &o).unwrap();
        let by_seek = e.convert(Input::seekable(Cursor::new(bytes.clone())).unwrap(), &o).unwrap();
        let by_spool = e.convert(Input::spooled(Forward(Cursor::new(bytes.clone())), 1 << 30).unwrap(), &o).unwrap();
        // a seekable source through a tiny SYNC cache: chunks filled inline, no miss ever
        let tiny = Engine::new(Config { chunk_size: 64, ..Config::default() });
        let by_chunks = tiny.convert(Input::new(SeekSource::new(Cursor::new(bytes.clone())).unwrap()), &o).unwrap();
        let want = json(&by_bytes);
        let want_html = by_bytes.to_html(&HtmlOptions::default());
        assert!(want.contains("\"paragraph\""), "{} has paragraphs", name);
        for (what, m) in [("slice", &by_slice), ("seek", &by_seek), ("spool", &by_spool), ("sync chunks", &by_chunks)] {
            assert_eq!(json(m), want, "{}: {} JSON", name, what);
            assert_eq!(m.to_html(&HtmlOptions::default()), want_html, "{}: {} HTML", name, what);
        }
    }
}

#[test]
fn a_512_byte_async_source_equals_the_in_memory_run_with_rounds_and_bytes_reported() {
    for name in SLICE {
        let bytes = synth(name);
        let o = Options::default();
        let (mem, ms) = Engine::new(Config::default()).convert_stats(Input::bytes(bytes.clone()), &o).unwrap();
        assert_eq!((ms.attempts, ms.fetch_rounds), (1, 0), "in memory: one attempt, no fetch");
        let (asy, st) = async_engine(512).convert_stats(Input::bytes(bytes.clone()), &o).unwrap();
        assert_eq!(json(&asy), json(&mem), "{}: JSON", name);
        assert_eq!(asy.to_html(&HtmlOptions::default()), mem.to_html(&HtmlOptions::default()), "{}: HTML", name);
        assert!(st.fetch_rounds >= 1 && st.attempts == st.fetch_rounds + 1, "{}: it missed and re-ran: {:?}", name, st);
        assert!(st.bytes_fetched <= bytes.len() as u64, "{}: never more than the input: {:?}", name, st);
        assert!(st.fetch_rounds <= 24, "{}: inside FETCH_ROUNDS", name);
        eprintln!("{}: {} bytes, {:?}", name, bytes.len(), st);
        // the same through a Read + Seek source
        let seek = async_engine(512).convert(Input::seekable(Cursor::new(bytes.clone())).unwrap(), &o).unwrap();
        assert_eq!(json(&seek), json(&mem), "{}: async over Read + Seek", name);
    }
}

fn result_of(e: &Engine, bytes: Vec<u8>, pw: Option<&str>) -> Result<String, String> {
    let mut input = Input::bytes(bytes);
    if let Some(p) = pw {
        input = input.with_password(p.as_bytes().to_vec());
    }
    match e.convert(input, &Options::default()) {
        Ok(m) => Ok(m.to_json(&JsonOptions::default())),
        Err(e) => Err(e.name()),
    }
}

#[test]
fn every_pdf_fixture_is_the_same_result_in_memory_and_from_a_512_byte_async_source() {
    let mem = Engine::new(Config::default());
    let asy = async_engine(512);
    let mut files: Vec<PathBuf> = Vec::new();
    for dir in ["test/pdf", "test/pdf/p3", "test/content", "test/fuzz"] {
        if let Ok(rd) = std::fs::read_dir(root().join(dir)) {
            for f in rd.flatten() {
                if f.path().extension().map(|x| x == "pdf").unwrap_or(false) {
                    files.push(f.path());
                }
            }
        }
    }
    files.sort();
    assert!(files.len() > 60, "found the fixtures: {}", files.len());
    let (mut ok, mut err) = (0, 0);
    for f in &files {
        let b = std::fs::read(f).unwrap();
        let a = result_of(&mem, b.clone(), None);
        let c = result_of(&asy, b, None);
        assert_eq!(a, c, "{}: in memory and async 512", f.display());
        match a {
            Ok(_) => ok += 1,
            Err(_) => err += 1,
        }
    }
    eprintln!("{} fixtures: {} converted, {} refused with a named error, each the same both ways", files.len(), ok, err);
    assert!(ok > 20);
}

#[test]
fn encryption_errors_are_distinct_and_a_password_opens_what_it_opens() {
    let e = Engine::new(Config::default());
    let plain = result_of(&e, read("test/pdf/p3/plain.pdf"), None).unwrap();
    let locked = read("test/pdf/p3/crypt-aes-128-user.pdf");
    assert_eq!(result_of(&e, locked.clone(), None), Err("password-required".to_string()));
    assert_eq!(result_of(&e, locked.clone(), Some("wrong-password")), Err("password-incorrect".to_string()));
    assert_eq!(result_of(&e, locked.clone(), Some("user-secret")), Ok(plain.clone()), "the user password: the plain twin's Model");
    assert_eq!(result_of(&e, locked, Some("owner-secret")), Ok(plain.clone()), "the owner password too");
    assert_eq!(result_of(&e, read("test/pdf/p3/crypt-aes-128-empty.pdf"), None), Ok(plain), "the empty user password is tried by itself");
    assert_eq!(result_of(&e, read("test/pdf/p3/unsupported-pubsec.pdf"), None), Err("encryption-unsupported".to_string()));
    // and over an async source, the password never read from the file's chunks
    let asy = async_engine(512);
    assert_eq!(result_of(&asy, read("test/pdf/p3/crypt-aes-256-r6-user.pdf"), None), Err("password-required".to_string()));
    assert_eq!(result_of(&asy, read("test/pdf/p3/crypt-aes-256-r6-user.pdf"), Some("nope")), Err("password-incorrect".to_string()));
    // a password is never in the Debug of an Input
    assert!(!format!("{:?}", Input::bytes(vec![1]).with_password(b"hunter2".to_vec())).contains("hunter2"));
}

// A source that must not be read: a length check that happens first never reads it.
struct Unreadable(u64);
impl ByteSource for Unreadable {
    fn len(&self) -> u64 {
        self.0
    }
    fn read_at(&self, _off: u64, _buf: &mut [u8]) -> std::io::Result<usize> {
        panic!("a source over the input-bytes limit was read")
    }
}

// A source whose length says more than it has.
struct Liar(Vec<u8>, u64);
impl ByteSource for Liar {
    fn len(&self) -> u64 {
        self.1
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        if off as usize >= self.0.len() {
            return Ok(0);
        }
        let n = std::cmp::min(buf.len(), self.0.len() - off as usize);
        buf[..n].copy_from_slice(&self.0[off as usize..off as usize + n]);
        Ok(n)
    }
}

#[test]
fn the_input_bytes_limit_is_checked_against_the_length_before_a_byte_is_read() {
    let e = Engine::new(Config { limits: vec![(0, 1000)], ..Config::default() });
    match e.convert(Input::new(Unreadable(5000)), &Options::default()) {
        Err(Error::Limit { name, limit, actual }) => assert_eq!((name.as_str(), limit, actual), ("input-bytes", 1000, 5000)),
        other => panic!("{:?}", other.map(|_| ())),
    }
    // the default is SPEC 7.4's 1 GiB: a length above it is refused too
    match Engine::new(Config::default()).convert(Input::new(Unreadable(5 << 30)), &Options::default()) {
        Err(Error::Limit { name, .. }) => assert_eq!(name, "input-bytes"),
        other => panic!("{:?}", other.map(|_| ())),
    }
    // a spool that runs past its bound is the same named limit
    let big = Cursor::new(vec![0u8; 5000]);
    let err = Input::spooled(big, 1000).err().expect("refused");
    assert!(err.to_string().starts_with("input-bytes"));
    // a limit deeper in kin is named too (pages), and does not leak into the next conversion
    let few = Engine::new(Config { limits: vec![(8, 1)], ..Config::default() });
    match few.convert(Input::bytes(read("test/pdf/p3/plain.pdf")), &Options::default()) {
        Err(Error::Limit { name, .. }) => assert_eq!(name, "pages"),
        other => panic!("{:?}", other.map(|_| ())),
    }
    assert!(Engine::new(Config::default()).convert(Input::bytes(read("test/pdf/p3/plain.pdf")), &Options::default()).is_ok());
}

#[test]
fn a_source_that_gives_fewer_bytes_than_its_length_is_source_changed() {
    let b = synth("h-plain");
    for (name, e) in [("sync", Engine::new(Config { chunk_size: 4096, ..Config::default() })), ("async", async_engine(512))] {
        let r = e.convert(Input::new(Liar(b.clone(), b.len() as u64 + 10_000)), &Options::default());
        assert_eq!(r.err().map(|e| e.name()), Some("source-changed".to_string()), "{}", name);
    }
}

#[test]
fn what_is_not_implemented_is_refused() {
    let e = Engine::new(Config::default());
    let b = synth("h-plain");
    for a in [Assets::Images, Assets::Figures] {
        let r = e.convert(Input::bytes(b.clone()), &Options { assets: a, ..Options::default() });
        assert_eq!(r.err().map(|e| e.name()), Some("unsupported".to_string()));
    }
    let r = e.convert(vec![Input::bytes(b.clone()), Input::bytes(b.clone())], &Options::default());
    assert_eq!(r.err().map(|e| e.name()), Some("unsupported".to_string()));
    assert_eq!(e.convert(Vec::<Input>::new(), &Options::default()).err().map(|e| e.name()), Some("no-input".to_string()));
    assert_eq!(e.convert(Input::bytes(read("test/pdf/notpdf.pdf")), &Options::default()).err().map(|e| e.name()), Some("unsupported-format".to_string()));
    // OCR is accepted and has no effect yet (D038's default is `always`): no policy changes the Model
    let always = e.convert(Input::bytes(b.clone()), &Options { ocr: OcrPolicy::Always, ..Options::default() }).unwrap();
    let never = e.convert(Input::bytes(b), &Options { ocr: OcrPolicy::Never, ..Options::default() }).unwrap();
    assert_eq!(json(&always), json(&never));
}

#[test]
fn intake_takes_any_number_of_inputs() {
    let e = Engine::new(Config::default());
    let v = e.intake(vec![Input::bytes(synth("h-plain")), Input::bytes(read("test/pdf/p3/crypt-aes-128-empty.pdf"))]).unwrap();
    assert_eq!(v.len(), 2);
    assert_eq!((v[0].format, v[0].pages, v[0].encrypted), ("pdf", 2, false));
    assert_eq!((v[1].pages, v[1].encrypted), (3, true));
    let locked = e.intake(Input::bytes(read("test/pdf/p3/crypt-aes-128-user.pdf")));
    assert_eq!(locked.err().map(|e| e.name()), Some("password-required".to_string()));
    let m = e.convert(synth("h-plain"), &Options::default()).unwrap();
    assert_eq!((m.source().pages, m.source().encrypted), (2, false));
}

#[test]
fn record_and_replay_with_a_born_digital_page_asking_for_nothing() {
    let e = Engine::new(Config::default());
    let b = synth("h-plain");
    let (m, t) = e.record(Input::bytes(b.clone()), &Options::default()).unwrap();
    // a born-digital page's two rasters are lazy (D251, revised by the coordinator): nothing asks for them until a stage reads
    // pixels, so the transcript is empty; nothing is OCR'd
    assert!(t.entries.is_empty());
    assert!(t.entries.iter().all(|x| x.bitmap.is_none()));
    assert_eq!(t.inputs, vec![(0, b.len() as i32)]);
    let r = e.replay(Input::bytes(b.clone()), &Options::default(), &t).unwrap();
    assert_eq!(json(&r), json(&m));
    // a transcript is of the input it was recorded from
    let other = synth("h-keyword");
    assert_eq!(e.replay(Input::bytes(other), &Options::default(), &t).err().map(|e| e.name()), Some("transcript-bad".to_string()));
}

#[test]
fn the_model_can_be_walked() {
    let e = Engine::new(Config::default());
    let m = e.convert(Input::bytes(synth("h-plain")), &Options { locale: Some("en-GB".to_string()), ..Options::default() }).unwrap();
    let root = m.root();
    assert_eq!(root.kind().name(), "document");
    assert_eq!(root.attr("locale"), Some(emon::Attr::Text("en-GB".to_string())));
    assert!(m.to_json(&JsonOptions::default()).contains("\"locale\":\"en-GB\""));
    // the title is a heading (the heading analysis, D172) at the head of a section of level 1
    let top = root.children();
    assert_eq!(top[0].kind().name(), "section");
    let sec = top[0].index();
    let kids = top[0].children();
    assert!(kids.len() > 2);
    assert_eq!(kids[0].kind().name(), "heading");
    assert_eq!(kids[0].text(), "Field Notes on Soil Moisture");
    assert_eq!(kids[0].sources(), vec!["text".to_string(), "infer".to_string()]);
    assert_eq!(kids[0].conf(), 500);
    assert_eq!(kids[0].parent().map(|p| p.index()), Some(sec));
    let regions = kids[0].regions();
    assert_eq!(regions.len(), 1);
    assert_eq!((regions[0].page, regions[0].x0, regions[0].y0), (1, 7200, 9781));
    let para = Kind::named("paragraph").unwrap();
    assert_eq!(Kind::named("nonsense"), None);
    let all = m.find(para);
    assert!(all.len() >= kids.iter().filter(|k| k.kind() == para).count());
    assert!(all.windows(2).all(|w| w[0].index() < w[1].index()), "document order");
    let pages = m.pages();
    assert_eq!(pages.len(), 2);
    assert_eq!((pages[0].number, pages[0].width, pages[0].height, pages[0].kind.as_str()), (1, 61200, 79200, "vector"));
    assert!(m.furniture().is_empty() && m.dropped().is_empty());
    // the walk and the JSON say the same text, paragraph by paragraph
    let texts: Vec<String> = kids.iter().filter(|k| k.kind() == para).map(|k| k.text()).collect();
    let js = m.to_json(&JsonOptions::default());
    for t in &texts {
        assert!(js.contains(&format!("\"text\":\"{}\"", t.replace('\\', "\\\\").replace('"', "\\\""))), "JSON has {:?}", t);
    }
    // the keep-evidence option
    let ev = e.convert(Input::bytes(synth("h-plain")), &Options { keep_evidence: true, ..Options::default() }).unwrap();
    assert!(ev.evidence().unwrap().starts_with("{\"emon-evidence\""), "{:?}", &ev.evidence().unwrap()[..40]);
    assert!(m.evidence().is_none());
}

#[test]
fn the_library_names_no_filesystem() {
    // D058: nothing under host/rust/src (the Engine, its sources, the loop, the ports) opens a file or names a path.
    // `tess.rs` (the Tesseract adapter's tessdata directory) and `testport.rs` (test support) are not the library.
    for f in ["lib.rs", "engine.rs", "source.rs", "plan.rs", "port.rs", "render.rs", "pdfium.rs"] {
        let text = String::from_utf8(read(&format!("host/rust/src/{}", f))).unwrap();
        for (i, line) in text.lines().enumerate() {
            let code = line.split("//").next().unwrap();
            for bad in ["std::fs", "std::path", "Path", "File::open", "read_dir"] {
                assert!(!code.contains(bad), "{}:{} names `{}`: {}", f, i + 1, bad, line);
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- raster intake
// (P6.1, D187-D190) An image input is read through the `ImageDecode` port: one `image` page per frame.

fn image(name: &str) -> Vec<u8> {
    read(&format!("test/image/{}", name))
}

#[test]
fn an_image_becomes_pages_and_a_multi_page_tiff_several() {
    let e = Engine::new(Config::default());
    let m = e.convert(Input::bytes(image("png-phys300.png")), &Options::default()).unwrap();
    let pages = m.pages();
    assert_eq!(pages.len(), 1);
    assert_eq!(pages[0].kind, "image");
    // 48 x 36 pixels at 300 dpi: 11.52 x 8.64 pt, in centipoints
    assert_eq!((pages[0].width, pages[0].height), (1152, 864));
    assert_eq!(m.source().format, "png");
    let j = m.to_json(&JsonOptions::default());
    assert!(j.contains("\"source\":{\"format\":\"png\",\"pages\":1}"), "{}", j);
    assert!(j.contains("\"dpi\":300,\"px\":[48,36]"), "{}", j);
    assert!(!j.contains("dpi-unknown"), "{}", j);
    // no stated resolution: read as 300 and flagged
    let m = e.convert(Input::bytes(image("png-rgb8.png")), &Options::default()).unwrap();
    assert!(m.to_json(&JsonOptions::default()).contains("dpi-unknown"));
    // a multi-page TIFF: three frames of three sizes, in order
    let m = e.convert(Input::bytes(image("tiff-multi3-rgb-lzw.tif")), &Options::default()).unwrap();
    let px: Vec<(i32, i32)> = m.pages().iter().map(|p| (p.width, p.height)).collect();
    assert_eq!(px, vec![(1152, 864), (960, 720), (792, 600)]);
    assert_eq!(m.pages().iter().map(|p| p.number).collect::<Vec<_>>(), vec![1, 2, 3]);
    // with no Ocr port an image page has no text: the body is the page milestones the paragraph stage writes between pages (P6.5)
    assert!(m.root().children().iter().all(|n| n.kind().name() == "break"));
}

#[test]
fn stage_zero_of_an_image_reads_no_pixels() {
    let e = Engine::new(Config::default());
    let v = e.intake(vec![Input::bytes(image("tiff-multi3-g4.tif")), Input::bytes(image("jpeg-420.jpg")), Input::bytes(synth("h-plain"))]).unwrap();
    assert_eq!((v[0].format, v[0].pages), ("tiff", 3));
    assert_eq!((v[1].format, v[1].pages), ("jpeg", 1));
    assert_eq!((v[2].format, v[2].pages), ("pdf", 2));
}

#[test]
fn an_image_records_its_pixels_and_replays_without_a_codec() {
    let e = Engine::new(Config::default());
    let o = Options::default();
    let (m, t) = e.record(Input::bytes(image("tiff-multi3-gray-none.tif")), &o).unwrap();
    // the document pass asks each frame's HEADER (frame 0 first, then frames 1 and 2 together: rounds 0 and 1, no pixels),
    // then each frame is its own page unit (P7.U, D207): its decode (round 0, the bitmap kept) and its OCR (round 1: no Ocr
    // port is configured, so it is answered `unsupported`, P6.5, D211)
    assert_eq!(t.entries.len(), 9);
    assert_eq!(t.entries.iter().map(|x| x.round).collect::<Vec<_>>(), vec![0, 1, 1, 0, 1, 0, 1, 0, 1]);
    assert_eq!(t.entries.iter().map(|x| x.req[1]).collect::<Vec<_>>(), vec![2, 2, 2, 2, 3, 2, 3, 2, 3]);
    assert_eq!(t.entries.iter().map(|x| x.req[2]).collect::<Vec<_>>(), vec![1, 2, 3, 260, 272, 516, 528, 772, 784]);
    assert!(t.entries.iter().filter(|x| x.req[1] == 2 && x.req[7] >= 0).all(|x| x.bitmap.is_some()));
    assert!(t.entries.iter().filter(|x| x.req[1] == 2 && x.req[7] < 0).all(|x| x.bitmap.is_none()));
    assert!(t.entries.iter().filter(|x| x.req[1] == 3).all(|x| x.bitmap.is_none() && x.ans[3] == 2));
    // the transcript's files survive a round trip (the pixels as canonical PNG), and replaying them gives the same Model
    let files: std::collections::BTreeMap<String, Vec<u8>> = t.to_files().into_iter().collect();
    let t2 = emon::Transcript::from_files(&files).unwrap();
    let r = e.replay(Input::bytes(image("tiff-multi3-gray-none.tif")), &o, &t2).unwrap();
    assert_eq!(json(&m), json(&r));
    // a replay of another image misses: the request is not in the transcript
    let miss = e.replay(Input::bytes(image("tiff-multi3-rgb-lzw.tif")), &o, &t2);
    assert!(miss.is_err());
    // a plain conversion keeps no pixels, and says the same thing
    assert_eq!(json(&e.convert(Input::bytes(image("tiff-multi3-gray-none.tif")), &o).unwrap()), json(&m));
}

// ------------------------------------------------------------------------------------- OCR (P6.5, D211-D216)
//
// A FAKE `Ocr` port: it answers every request with the word `Hello` in one line, through the same kin builders a real engine's
// adapter uses, so the whole path (the request kin builds, the answer kin reads, the evidence, the pipeline's lines, the Model's
// provenance) runs without Tesseract. What the real engine does to a real page is `bb ocr` (a replayed transcript) and `bb ocr-live`.
struct FakeOcr;
impl Ocr for FakeOcr {
    fn fulfil(&self, req: &emon::plan::Request, _bm: &emon::plan::Bitmaps, _keep: bool) -> Result<emon::plan::Answer, emon::plan::PlanError> {
        use emon::emon::{ocr, plan};
        let mut c = emon::port::Cells::new();
        let at = plan::an_ocr(&mut c, req.id());
        let w = plan::an_ocr_word(&mut c, at, 10, 10, 100, 30, 900, 0, 0);
        for ch in "Hello".chars() {
            plan::an_ocr_cp(&mut c, w, ch as i32);
        }
        ocr::ox_begin(&mut c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0);
        ocr::ox_line(&mut c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6);
        ocr::ox_block(&mut c, 10, 10, 100, 30, 0, 0, 0);
        let _ = ocr::ox_word(&mut c, 0, 0, -1, 0);
        plan::an_ocr_end(&mut c, at);
        let rec = (0..emon::port::cells_len(&c)).map(|i| emon::port::cells_at(&c, i)).collect();
        Ok(emon::plan::Answer { rec, bitmap: None })
    }
    fn producer(&self) -> Vec<(String, String)> {
        vec![("engine".to_string(), "fake".to_string())]
    }
}

fn with_ocr() -> Engine {
    Engine::new(Config { ocr: Some(OcrPort(std::sync::Arc::new(FakeOcr))), ..Config::default() })
}

#[test]
fn an_image_is_read_by_the_ocr_port_and_its_transcript_replays_without_it() {
    let bytes = image("png-phys300.png");
    let o = Options::default();
    let (m, t) = with_ocr().record(Input::bytes(bytes.clone()), &o).unwrap();
    let j = json(&m);
    assert!(j.contains("\"text\": \"Hello\""), "{}", j);
    assert!(j.contains("\"ocr\": \"always\""), "settings.ocr is written when OCR gave the text: {}", j);
    assert!(j.contains("\"ocr\"") && j.contains("\"ocr-only\""), "{}", j);
    assert_eq!(m.pages()[0].kind, "image");
    // the header, then the page unit's decode and ocr; the transcript says whose engine answered
    assert_eq!(t.entries.iter().map(|x| x.req[1]).collect::<Vec<_>>(), vec![2, 2, 3]);
    assert_eq!(t.producer, vec![("engine".to_string(), "fake".to_string())]);
    // a replay needs no port, and gives the same Model; the files of the transcript survive a round trip
    let files: std::collections::BTreeMap<String, Vec<u8>> = t.to_files().into_iter().collect();
    assert!(String::from_utf8_lossy(&files["transcript.edn"]).contains(":producer {:engine \"fake\"}"));
    let t2 = emon::Transcript::from_files(&files).unwrap();
    assert_eq!(t2.producer, t.producer);
    let r = Engine::new(Config::default()).replay(Input::bytes(bytes.clone()), &o, &t2).unwrap();
    assert_eq!(json(&r), j);
    // `never` asks for nothing: no OCR request, no text and no setting
    let (m_never, t_never) = with_ocr().record(Input::bytes(bytes.clone()), &Options { ocr: OcrPolicy::Never, ..Options::default() }).unwrap();
    assert_eq!(t_never.entries.iter().map(|x| x.req[1]).collect::<Vec<_>>(), vec![2, 2]);
    assert!(!json(&m_never).contains("Hello") && !json(&m_never).contains("\"ocr\""));
    // with no port the request is answered `unsupported` and the page has no text, as before OCR existed
    let m_none = Engine::new(Config::default()).convert(Input::bytes(bytes), &o).unwrap();
    assert_eq!(json(&m_none), json(&m_never));
}

#[test]
fn a_scanned_pdf_page_is_ocred_and_a_born_digital_one_keeps_its_text_layer() {
    let scan = read("test/corpus/synth/scan/h-keyword.pdf");
    let o = Options::default();
    let (m, t) = with_ocr().record(Input::bytes(scan.clone()), &o).unwrap();
    // the stream's own bytes (filter DCT, kind 2), then OCR of that bitmap (kind 3): two rounds
    assert_eq!(t.entries.iter().map(|x| (x.round, x.req[1])).collect::<Vec<_>>(), vec![(0, 2), (1, 3)]);
    assert_eq!(t.entries[0].req[6], 1, "the DCT filter");
    assert!(t.entries[0].bitmap.is_some(), "an engine will read the pixels, so the transcript keeps them");
    let j = json(&m);
    assert!(j.contains("\"kind\": \"scanned\"") && j.contains("Hello"), "{}", j);
    // a replay needs no port
    let files: std::collections::BTreeMap<String, Vec<u8>> = t.to_files().into_iter().collect();
    let t2 = emon::Transcript::from_files(&files).unwrap();
    assert_eq!(json(&Engine::new(Config::default()).replay(Input::bytes(scan.clone()), &o, &t2).unwrap()), j);
    // no port: the page is there and has no text, and the Model says nothing about OCR
    let none = json(&Engine::new(Config::default()).convert(Input::bytes(scan), &o).unwrap());
    assert!(!none.contains("Hello") && !none.contains("\"ocr\"") && none.contains("\"kind\": \"vector\""), "{}", none);
    // a born-digital page keeps its text layer under `always`: it asks for its rasters and for no OCR, and the Model is the
    // same with or without a port (D212, D199)
    let born = synth("h-plain");
    let (mb, tb) = with_ocr().record(Input::bytes(born.clone()), &o).unwrap();
    assert!(tb.entries.iter().all(|x| x.req[1] == 1), "a text layer is read as it is: no OCR is asked");
    assert_eq!(json(&mb), json(&Engine::new(Config::default()).convert(Input::bytes(born), &o).unwrap()));
}

#[test]
fn an_image_through_a_source_that_misses_is_the_in_memory_one() {
    for name in ["png-rgba8.png", "jpeg-444.jpg", "tiff-multi3-g4.tif", "tiff-tiled16.tif"] {
        let bytes = image(name);
        let a = Engine::new(Config::default()).convert(Input::bytes(bytes.clone()), &Options::default()).unwrap();
        let b = async_engine(64).convert(Input::bytes(bytes), &Options::default()).unwrap();
        assert_eq!(json(&a), json(&b), "{}", name);
    }
}

#[test]
fn an_image_that_cannot_be_read_is_refused_by_name() {
    let e = Engine::new(Config::default());
    let name = |f: &str| e.convert(Input::bytes(image(f)), &Options::default()).err().map(|e| e.name());
    assert_eq!(name("tiff-jpeg.tif"), Some("unsupported-image".to_string()));
    assert_eq!(name("jpeg-cmyk.jpg"), Some("unsupported-image".to_string()));
    assert_eq!(name("png-truncated.png"), Some("undecodable".to_string()));
    assert_eq!(name("tiff-truncated.tif"), Some("undecodable".to_string()));
    // the pixels bound is a named limit, checked from the header before a pixel is decoded
    let small = Engine::new(Config { limits: vec![(9, 1000)], ..Config::default() });
    let r = small.convert(Input::bytes(image("png-rgb8.png")), &Options::default());
    assert_eq!(r.err().map(|e| e.name()), Some("pixels".to_string()));
}

// ------------------------------------------------------------------ the Renderer port (P6.2, D127)

use emon::plan::{Fulfiller, Request};
use emon::{Clip, RenderError, RenderFulfiller, RenderInput, RenderRequest, Renderer};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

// A fake engine: a 20 x 30 RGB page whose pixel (x, y) is a function of x, y, the layer and the first byte
// of the input it READ through the byte source; layer 3 is unsupported, page 9 is not there. It counts calls.
#[derive(Debug, Default)]
struct Fake {
    calls: AtomicUsize,
    saw_clip: AtomicUsize,
}
impl Renderer for Fake {
    fn render(&self, input: &dyn ByteSource, _pw: Option<&[u8]>, req: &RenderRequest) -> Result<emon::port::Bitmap, RenderError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if req.clip.is_some() {
            self.saw_clip.fetch_add(1, Ordering::SeqCst);
        }
        if req.layer == 3 {
            return Err(RenderError::Unsupported("no watermark layer".to_string()));
        }
        if req.page == 9 {
            return Err(RenderError::Failed("no such page".to_string()));
        }
        let mut first = [0u8; 1];
        assert_eq!(input.read_at(0, &mut first).unwrap(), 1);
        let mut px = Vec::new();
        for y in 0..30u32 {
            for x in 0..20u32 {
                let v = (x * 7 + y * 3 + req.layer as u32 * 50 + first[0] as u32) as u8;
                px.extend_from_slice(&[v, v ^ 0x55, 255 - v]);
            }
        }
        Ok(emon::port::Bitmap::from_bytes(20, 30, 3, &px))
    }
}

fn ask(f: &mut RenderFulfiller, rec: Vec<i32>) -> emon::plan::Answer {
    let r = Request { rec };
    f.start(&r, &Default::default()).unwrap();
    f.next().unwrap()
}

#[test]
fn a_render_fulfiller_answers_renders_and_crops_and_failures_as_records() {
    let fake = Arc::new(Fake::default());
    let pdf = synth("h-plain");
    let mut f = RenderFulfiller::new(fake.clone(), vec![RenderInput { source: Arc::new(pdf.clone()), password: None }]);
    // render: [len kind id input page dpi layer]; the answer is [len kind id status w h channels dpi]
    let a = ask(&mut f, vec![7, 1, 1, 0, 0, 72, 0]);
    assert_eq!(a.rec, vec![8, 1, 1, 0, 20, 30, 3, 72]);
    let whole = a.bitmap.clone().unwrap();
    // a crop of the same page: answered with a crop record, the very pixels of the cut, from ONE engine call
    let c = ask(&mut f, vec![11, 5, 2, 0, 0, 72, 0, 4, 5, 8, 9]);
    assert_eq!(c.rec, vec![8, 5, 2, 0, 8, 9, 3, 72]);
    assert_eq!(fake.calls.load(Ordering::SeqCst), 1, "a page and its crops are rendered once");
    assert_eq!(fake.saw_clip.load(Ordering::SeqCst), 0, "an engine without exact crops is never handed a clip");
    let cut = emon::render::cut(&whole, Clip { x: 4, y: 5, w: 8, h: 9 }).unwrap();
    assert_eq!(c.bitmap.unwrap().to_bytes(), cut.to_bytes());
    let wb = whole.to_bytes();
    assert_eq!(&cut.to_bytes()[..3], &wb[(5 * 20 + 4) * 3..(5 * 20 + 4) * 3 + 3]);
    // another layer is another render, and the input was READ through the source (its first byte is in the pixels)
    let l1 = ask(&mut f, vec![7, 1, 3, 0, 0, 72, 1]);
    assert_eq!(fake.calls.load(Ordering::SeqCst), 2);
    assert_ne!(l1.bitmap.unwrap().to_bytes(), wb);
    assert_eq!(wb[0], pdf[0], "pixel (0,0) of layer 0 is the input's first byte");
    // failures are answers: header and status only, and no bitmap
    let un = ask(&mut f, vec![7, 1, 4, 0, 0, 72, 3]);
    assert_eq!((un.rec.clone(), un.bitmap.is_none()), (vec![4, 1, 4, 2], true));
    let nopage = ask(&mut f, vec![7, 1, 5, 0, 9, 72, 0]);
    assert_eq!(nopage.rec, vec![4, 1, 5, 1]);
    let outside = ask(&mut f, vec![11, 5, 6, 0, 0, 72, 0, 15, 0, 10, 10]);
    assert_eq!((outside.rec, outside.bitmap.is_none()), (vec![4, 5, 6, 1], true));
    let unsupported_crop = ask(&mut f, vec![11, 5, 7, 0, 0, 72, 3, 0, 0, 1, 1]);
    assert_eq!(unsupported_crop.rec, vec![4, 5, 7, 2]);
    // a request for an input the run does not have, or a malformed one, is the loop's error and not an answer
    let bad_input = Request { rec: vec![7, 1, 8, 4, 0, 72, 0] };
    f.start(&bad_input, &Default::default()).unwrap();
    assert!(matches!(f.next(), Err(emon::plan::PlanError::Fulfil(_))));
    let short = Request { rec: vec![6, 1, 9, 0, 0, 72] };
    f.start(&short, &Default::default()).unwrap();
    assert!(matches!(f.next(), Err(emon::plan::PlanError::Fulfil(_))));
}

#[test]
fn an_engine_with_a_renderer_is_the_same_engine_and_a_born_digital_page_is_not_rendered() {
    // The Engine's port set (D127): `render` and `crop` go to Config.renderer. Under `always` a born-digital page asks for its
    // two rasters lazily (D251), none does yet, so the Model is byte-for-byte the one of an Engine with no
    // renderer, and the renderer is never called.
    let fake = Arc::new(Fake::default());
    let with = Engine::new(Config { renderer: Some(fake.clone()), ..Config::default() });
    let without = Engine::new(Config::default());
    for name in SLICE {
        let bytes = synth(name);
        let a = with.convert(Input::bytes(bytes.clone()), &Options::default()).unwrap();
        let b = without.convert(Input::bytes(bytes), &Options::default()).unwrap();
        assert_eq!(json(&a), json(&b), "{}", name);
    }
    let n = fake.calls.load(Ordering::SeqCst);
    assert_eq!(n, 0, "the renderer was called {} times: a born-digital page's rasters are lazy", n);
}
