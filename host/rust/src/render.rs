// The `Renderer` port, Rust host (DEVLOG P6.2, D127; SPEC 7.2, D007). A page of an input, at a resolution,
// in a layer, optionally cut to a pixel rectangle, as an RGB `Bitmap`.
//
// This file is the PORT: the trait an engine implements, the request it is given, and the fulfiller that
// turns `render` and `crop` plan requests (D089, D117) into calls and answers. It names no engine and no
// filesystem. `pdfium.rs` (compiled under `--cfg pdfium`) is the default engine (D007); anything else that
// implements `Renderer` plugs in the same way (MuPDF, a browser's pdfium WASM behind a host shim, a fake).
//
// WHAT THE RENDERER READS. The input is the host's `ByteSource`, named in the request by input INDEX (D089,
// D058): never a path, never a copy kin made. An engine that wants random access reads the span it needs
// (pdfium's custom file-access loader does, `pdfium.rs`); one that insists on a slab reads that span itself.
//
// WHAT AN ANSWER IS. `[len kind id status w h channels dpi]` (kin's `an-render`), the pixels beside it in the
// transcript as a canonical PNG (D124, D125). A page that cannot be rendered, or a layer the engine cannot
// separate, is an ANSWER (`failed`, `unsupported`) and is transcribed, so a replay reproduces it.
//
// THE GEOMETRY, stated so that two engines and two hosts agree on the size before they disagree on a pixel:
// the page "as displayed" (rotation and crop box applied) is `wpt x hpt` points; the whole-page bitmap is
// `W x H` with `W = floor(wpt * dpi / 72 + 1/2)`, `H` likewise, at least 1. A crop `(px, py, pw, ph)` is a
// rectangle of THAT bitmap's pixels and must lie inside it; the answer is `pw x ph`.
use crate::emon;
use crate::plan::*;
use crate::port::{cells_at, cells_len, Bitmap, Cells};
use crate::source::ByteSource;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

// `emon.plan`'s layers (`lay-*`); a kin law holds the numbers.
pub const LAYER_FULL: i32 = 0;
pub const LAYER_NO_TEXT: i32 = 1;
pub const LAYER_TEXT_ONLY: i32 = 2;
pub const LAYER_NO_WATERMARK: i32 = 3;
pub const LAYER_WATERMARK_ONLY: i32 = 4;

pub const STATUS_UNSUPPORTED: i32 = 2;

// A pixel rectangle of the whole-page bitmap.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Clip {
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RenderRequest {
    // 0-based
    pub page: i32,
    pub dpi: i32,
    pub layer: i32,
    pub clip: Option<Clip>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RenderError {
    // Could not (a corrupt document, a page out of range, a crop outside the page, a limit): `ps-failed`.
    Failed(String),
    // The engine cannot do this (a layer it cannot separate): `ps-unsupported`, and kin plans around it.
    Unsupported(String),
}

impl std::fmt::Display for RenderError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            RenderError::Failed(s) => write!(f, "render failed: {}", s),
            RenderError::Unsupported(s) => write!(f, "render unsupported: {}", s),
        }
    }
}

impl std::error::Error for RenderError {}

// `Renderer` must be callable from any thread, one call at a time or several (an engine that is not
// thread-safe, as pdfium is not, serialises inside).
pub trait Renderer: Send + Sync + std::fmt::Debug {
    // Render `req` of the document `input` (opened with `password` when it is encrypted). The result is RGB
    // (3 channels): the tracer wants colour for ink separation (SPEC 8.2.1) and OCR takes it as it is.
    fn render(&self, input: &dyn ByteSource, password: Option<&[u8]>, req: &RenderRequest) -> Result<Bitmap, RenderError>;
    // The layers it can separate, as a bit set over `LAYER_*` (bit n = layer n). The full page is always there.
    fn layers(&self) -> u32 {
        1
    }
    // Is `render` with a `clip` EXACTLY the cut of the same render without one (SPEC 7.2: "the same pixels, cut
    // out, never a re-fit")? pdfium is not: a clipped render moves the pixels on the clip's edge (measured, D127).
    // When false (the default) the fulfiller never passes a clip: it renders the whole page and cuts.
    fn exact_crop(&self) -> bool {
        false
    }
}

// The size in pixels of `pt` points at `dpi`: floor(pt * dpi / 72 + 1/2), at least 1.
pub fn pixels_of(pt: f64, dpi: i32) -> i32 {
    let v = (pt * dpi as f64 / 72.0 + 0.5).floor();
    if v < 1.0 {
        1
    } else if v > i32::MAX as f64 {
        i32::MAX
    } else {
        v as i32
    }
}

// One input of the run, as the fulfiller reads it.
#[derive(Clone)]
pub struct RenderInput {
    pub source: Arc<dyn ByteSource>,
    pub password: Option<Vec<u8>>,
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

// Parse a `render` (args input page dpi layer) or `crop` (and px py pw ph) request.
pub fn parse_request(req: &Request) -> Result<(usize, RenderRequest), String> {
    let (n, crop) = match req.kind() {
        KIND_RENDER => (4, false),
        KIND_CROP => (8, true),
        k => return Err(format!("kind {} is not a render", k)),
    };
    if req.rec.len() != 3 + n {
        return Err(format!("a {} request has {} args, not {}", if crop { "crop" } else { "render" }, req.rec.len() - 3, n));
    }
    let f = |k: i32| req.field(k);
    let clip = if crop { Some(Clip { x: f(emon::plan::rd_x()), y: f(emon::plan::rd_y()), w: f(emon::plan::rd_w()), h: f(emon::plan::rd_h()) }) } else { None };
    if f(emon::plan::rd_input()) < 0 {
        return Err(format!("input {}", f(emon::plan::rd_input())));
    }
    Ok((f(emon::plan::rd_input()) as usize, RenderRequest { page: f(emon::plan::rd_page()), dpi: f(emon::plan::rd_dpi()), layer: f(emon::plan::rd_layer()), clip }))
}

// The pixels of rectangle `c` of `b`, as a Bitmap of its own. Public: it is the crop law (D117), and a test
// holds an engine's own crops to it.
pub fn cut(b: &Bitmap, c: Clip) -> Result<Bitmap, RenderError> {
    if c.w < 1 || c.h < 1 || c.x < 0 || c.y < 0 || c.x as i64 + c.w as i64 > b.width() as i64 || c.y as i64 + c.h as i64 > b.height() as i64 {
        return Err(RenderError::Failed(format!("the crop {}x{}+{}+{} is not inside the {}x{} page", c.w, c.h, c.x, c.y, b.width(), b.height())));
    }
    let (bytes, ch, bw) = (b.to_bytes(), b.channels() as usize, b.width() as usize);
    let mut out = Vec::with_capacity(c.w as usize * c.h as usize * ch);
    for y in 0..c.h as usize {
        let at = ((c.y as usize + y) * bw + c.x as usize) * ch;
        out.extend_from_slice(&bytes[at..at + c.w as usize * ch]);
    }
    Ok(Bitmap::from_bytes(c.w, c.h, b.channels(), &out))
}

type WholeKey = (usize, i32, i32, i32);

// Fulfils `render` and `crop` with a `Renderer`, synchronously (a render is CPU work; the host that wants
// them concurrent wraps this in its own pool, as the plan loop allows). Reading the input happens inside.
// A crop is the cut of a whole-page render (`cut`), and the LAST whole-page render is kept, so a run that
// asks for a page and then for regions of it (the assets, D117) renders it once.
pub struct RenderFulfiller {
    renderer: Arc<dyn Renderer>,
    inputs: Vec<Option<RenderInput>>,
    done: VecDeque<Result<Answer, PlanError>>,
    last: Mutex<Option<(WholeKey, Arc<Bitmap>)>>,
}

impl RenderFulfiller {
    pub fn new(renderer: Arc<dyn Renderer>, inputs: Vec<RenderInput>) -> RenderFulfiller {
        RenderFulfiller { renderer, inputs: inputs.into_iter().map(Some).collect(), done: VecDeque::new(), last: Mutex::new(None) }
    }

    // One input of a run, at its index (the Engine converts one input at a time, `idx` of the run).
    pub fn for_input(renderer: Arc<dyn Renderer>, idx: usize, input: RenderInput) -> RenderFulfiller {
        let mut inputs: Vec<Option<RenderInput>> = vec![None; idx];
        inputs.push(Some(input));
        RenderFulfiller { renderer, inputs, done: VecDeque::new(), last: Mutex::new(None) }
    }

    // The whole page, from the one-entry cache or the engine.
    fn whole(&self, input: usize, inp: &RenderInput, rr: &RenderRequest) -> Result<Arc<Bitmap>, RenderError> {
        let key: WholeKey = (input, rr.page, rr.dpi, rr.layer);
        if let Some((k, b)) = self.last.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
            if *k == key {
                return Ok(b.clone());
            }
        }
        let b = Arc::new(self.renderer.render(&*inp.source, inp.password.as_deref(), &RenderRequest { clip: None, ..*rr })?);
        *self.last.lock().unwrap_or_else(|e| e.into_inner()) = Some((key, b.clone()));
        Ok(b)
    }

    // One request to its answer. Public so a host that schedules its own threads can call it directly.
    pub fn fulfil(&self, req: &Request) -> Result<Answer, PlanError> {
        let (input, rr) = parse_request(req).map_err(PlanError::Fulfil)?;
        let mut c = Cells::new();
        let inp = match self.inputs.get(input).and_then(|i| i.as_ref()) {
            Some(i) => i,
            None => return Err(PlanError::Fulfil(format!("a render names input {}, and the run has {}", input, self.inputs.len()))),
        };
        let got: Result<Arc<Bitmap>, RenderError> = match rr.clip {
            None => self.whole(input, inp, &rr),
            Some(clip) if self.renderer.exact_crop() => self.renderer.render(&*inp.source, inp.password.as_deref(), &rr).map(Arc::new),
            Some(clip) => self.whole(input, inp, &rr).and_then(|w| cut(&w, clip)).map(Arc::new),
        };
        match got {
            Ok(bm) => {
                emon::plan::an_render(&mut c, req.id(), bm.width(), bm.height(), bm.channels(), rr.dpi);
                let mut rec = to_vec(&c);
                // A crop is answered with a record of ITS kind, laid out as a render's (the loop matches an
                // answer to its request by id and kind).
                rec[1] = req.kind();
                Ok(Answer { rec, bitmap: Some(bm) })
            }
            Err(e) => {
                let status = match e {
                    RenderError::Failed(_) => STATUS_FAILED,
                    RenderError::Unsupported(_) => STATUS_UNSUPPORTED,
                };
                emon::plan::an_fail(&mut c, req.kind(), req.id(), status);
                Ok(Answer { rec: to_vec(&c), bitmap: None })
            }
        }
    }
}

impl Fulfiller for RenderFulfiller {
    fn start(&mut self, req: &Request, _bitmaps: &Bitmaps) -> Result<(), PlanError> {
        let a = self.fulfil(req);
        self.done.push_back(a);
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        self.done.pop_front().expect("next without a started request")
    }
}

// Several fulfillers behind one: a request goes to the one that takes its kind, and its completion is
// asked of that one. The Engine's port set (render now, OCR and decode as their ports land).
pub struct KindMux {
    parts: Vec<(Vec<i32>, Box<dyn Fulfiller>)>,
    order: VecDeque<usize>,
}

impl KindMux {
    pub fn new() -> KindMux {
        KindMux { parts: Vec::new(), order: VecDeque::new() }
    }
    pub fn with(mut self, kinds: &[i32], f: Box<dyn Fulfiller>) -> KindMux {
        self.parts.push((kinds.to_vec(), f));
        self
    }
}

impl Default for KindMux {
    fn default() -> KindMux {
        KindMux::new()
    }
}

impl Fulfiller for KindMux {
    fn start(&mut self, req: &Request, bitmaps: &Bitmaps) -> Result<(), PlanError> {
        for (i, (kinds, f)) in self.parts.iter_mut().enumerate() {
            if kinds.contains(&req.kind()) {
                f.start(req, bitmaps)?;
                self.order.push_back(i);
                return Ok(());
            }
        }
        Err(PlanError::Fulfil(format!("no port is configured for request kind {}", req.kind())))
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        match self.order.pop_front() {
            Some(i) => self.parts[i].1.next(),
            None => Err(PlanError::Fulfil("next without a started request".to_string())),
        }
    }
}
