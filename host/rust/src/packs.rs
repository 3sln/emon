// THE PACKS (K2, D900; D885, D896; pkg1 section 3): the model files an adapter reads with, found, verified, fetched and handed over.
//
// WHAT IS KIN'S. Which packs exist, what each is for, their SHA-256 and sizes (`emon.packdata`, generated from release/packs/packs.edn), which packs a conversion needs and which
// are missing (`emon.engines`' `pk-resolve`), and what to do about one that is missing (`emon.adapters`' `ad-pack-action`: never and offline win over every hook; a turnkey with no
// hook fetches; a hook fetches, provides, declines or substitutes). This file is the MECHANICS: directories, files, hashes, the network, the app's callbacks.
//
// EVERY PACK IS VERIFIED against the catalogue's SHA-256 (and size) before it is used, wherever it came from: the store, a directory the app named, bytes or a path the app
// provided, a download. A file that does not match is never used. One in the store (the cache) is deleted and the pack is missing again (`pack-hash-mismatch:<id>`); one in a
// directory that is the app's is left alone and not used. A hook cannot make an unverified byte an adapter's input.
//
// LAYOUT. The STORE is a directory the library owns: `<store>/<id>/<file>` (a pack is a directory of its files, named as the catalogue names them) and, for each adapter,
// `<store>/.views/<engine>/<file>`: a flat directory of links to the verified files of the packs that engine reads, which is what the adapter is given (`packs_dir`,
// emon_adapter.h). Other directories (the archive's `share/emon/packs`, `EMON_MODELS`, the app's own) are searched read-only, as `<dir>/<id>/<file>` or `<dir>/<file>`.
//
// OFFLINE. `EMON_OFFLINE=1` wins over a mode and a hook alike. The fetch itself is behind the feature `packs-fetch` (an HTTPS client): a build without it answers `fetch` as
// `pack-fetch-unavailable`, and everything else (finding, verifying, `provide`, `decline`) works.
use crate::emon::{adapters as ka, engines as ke, packdata as kp};
use crate::port::{cells_at, cells_len, cells_push, Cells, Sha256};
use std::collections::HashMap;
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::{Arc, Mutex};

// ------------------------------------------------------------------------------------------------ the catalogue, as kin holds it

pub fn pack_count() -> i32 {
    ke::pk_pack_count()
}

pub fn pack_id(p: i32) -> String {
    let mut s = String::new();
    kp::pkid_emit_name(&mut s, p);
    s
}

pub fn pack_find(name: &str) -> Option<i32> {
    let c = kp::pkid_find(name.as_bytes(), 0, name.len() as i32);
    if c > 0 && ke::pk_pack_known(c) == 1 {
        Some(c)
    } else {
        None
    }
}

#[derive(Debug, Clone)]
pub struct PackFile {
    pub name: String,
    pub bytes: u64,
    pub sha: [u8; 32],
}

#[derive(Debug, Clone)]
pub struct PackInfo {
    pub code: i32,
    pub id: String,
    pub rev: i32,
    // kin's `eng` of the engine the pack is for; 0 for a pack every engine may read
    pub engine: i32,
    pub engine_name: String,
    pub kind: i32,
    pub kind_name: String,
    pub licence: String,
    pub bytes: u64,
    pub files: Vec<PackFile>,
}

pub fn pack_info(p: i32) -> PackInfo {
    let mut files = Vec::new();
    for i in 0..ke::pk_pack_file_count(p) {
        let mut name = String::new();
        ke::pk_pack_file_name(p, i, &mut name);
        let mut sha = [0u8; 32];
        for w in 0..8 {
            sha[4 * w as usize..4 * w as usize + 4].copy_from_slice(&(ke::pk_pack_file_sha(p, i, w) as u32).to_be_bytes());
        }
        files.push(PackFile { name, bytes: ke::pk_pack_file_bytes(p, i) as u64, sha });
    }
    let engine = ke::pk_pack_engine(p);
    let mut engine_name = String::new();
    if engine != 0 {
        ke::eng_emit_name(&mut engine_name, engine);
    } else {
        engine_name.push_str("any");
    }
    let kind = ke::pk_pack_kind(p);
    let mut kind_name = String::new();
    ke::pkind_emit_name(&mut kind_name, kind);
    let mut licence = String::new();
    kp::plic_emit_name(&mut licence, ke::pk_pack_licence(p));
    PackInfo { code: p, id: pack_id(p), rev: ke::pk_pack_rev(p), engine, engine_name, kind, kind_name, licence, bytes: ke::pk_pack_bytes(p) as u64, files }
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

// ------------------------------------------------------------------------------------------------ the app's callbacks (emon.h declares them)

pub const PACK_FETCH: i32 = 1;
pub const PACK_PROVIDE: i32 = 2;
pub const PACK_DECLINE: i32 = 3;
pub const PACK_SUBSTITUTE: i32 = 4;

pub const PACK_EVENT_START: i32 = 1;
pub const PACK_EVENT_PROGRESS: i32 = 2;
pub const PACK_EVENT_DONE: i32 = 3;
pub const PACK_EVENT_FAILED: i32 = 4;
pub const PACK_EVENT_CANCELLED: i32 = 5;

pub const PACK_BY_LIBRARY: i32 = 1;
pub const PACK_BY_APP: i32 = 2;

// What the library tells the hook about the pack it needs.
#[repr(C)]
pub struct PackRequest {
    pub pack: *const c_char,
    pub rev: i32,
    pub engine: *const c_char,
    pub kind: *const c_char,
    pub licence: *const c_char,
    pub bytes: u64,
    pub reason: *const c_char,
    pub request_engine: *const c_char,
}

// What the hook answers. Its pointers are the app's and need only be valid until the hook returns: the library copies what it keeps.
#[repr(C)]
pub struct PackDecision {
    pub action: i32,
    pub data: *const u8,
    pub len: usize,
    pub path: *const c_char,
    pub engine: *const c_char,
}

#[repr(C)]
pub struct PackEvent {
    pub kind: i32,
    pub pack: *const c_char,
    pub engine: *const c_char,
    pub done: u64,
    pub total: u64,
    pub initiator: i32,
    pub message: *const c_char,
}

pub type PackNeededFn = unsafe extern "C" fn(ctx: *mut c_void, req: *const PackRequest, out: *mut PackDecision) -> c_int;
pub type PackEventFn = unsafe extern "C" fn(ctx: *mut c_void, ev: *const PackEvent) -> c_int;

#[derive(Clone, Copy)]
struct Hook {
    f: PackNeededFn,
    ctx: usize,
}

#[derive(Clone, Copy)]
struct Events {
    f: PackEventFn,
    ctx: usize,
}

// What a hook decided, owned.
#[derive(Debug, Default)]
struct Decision {
    action: i32,
    data: Option<Vec<u8>>,
    path: Option<PathBuf>,
    engine: Option<String>,
}

// What becomes of a pack a request needs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Need {
    // verified and here
    Ready,
    // not here and not coming: the page is unread, `pack-missing:<id>` or the like
    Unread(String),
    // the hook named another engine to answer instead
    Substitute(String),
}

// One row of `pk-resolve`'s answer (kin's `pstate`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Row {
    pub state: i32,
    pub engine: i32,
    pub pack: i32,
    pub lang: i32,
}

struct Inner {
    store: Mutex<PathBuf>,
    extra: Mutex<Vec<PathBuf>>,
    mode: AtomicI32,
    hook: Mutex<Option<Hook>>,
    events: Mutex<Option<Events>>,
    cancel: AtomicBool,
    // files that were hashed and matched: (length, modified time) at that moment
    good: Mutex<HashMap<PathBuf, (u64, u128)>>,
    notes: Mutex<Vec<String>>,
    // one fetch at a time: a second conversion needing the same pack waits for the first and finds it
    fetch: Mutex<()>,
    class: i32,
}

#[derive(Clone)]
pub struct Packs(Arc<Inner>);

fn env(k: &str) -> Option<String> {
    std::env::var(k).ok().filter(|s| !s.is_empty())
}

// The store when nobody names one: `$EMON_PACK_CACHE`, else the user's cache directory (pkg1 3.4).
pub fn default_store() -> PathBuf {
    if let Some(d) = env("EMON_PACK_CACHE") {
        return PathBuf::from(d);
    }
    let home = env("HOME");
    let p = if cfg!(any(target_os = "macos", target_os = "ios")) {
        home.map(|h| PathBuf::from(h).join("Library/Caches/emon/packs"))
    } else if cfg!(windows) {
        env("LOCALAPPDATA").map(|d| PathBuf::from(d).join("emon").join("packs"))
    } else {
        env("XDG_CACHE_HOME").map(|d| PathBuf::from(d).join("emon/packs")).or_else(|| home.map(|h| PathBuf::from(h).join(".cache/emon/packs")))
    };
    p.unwrap_or_else(|| std::env::temp_dir().join("emon-packs"))
}

// `EMON_OFFLINE=1` (pkg1 3.4, D896): nothing is fetched and no hook is asked, whatever the mode says.
pub fn offline() -> bool {
    env("EMON_OFFLINE").map(|v| v == "1").unwrap_or(false)
}

fn mtime_ns(m: &std::fs::Metadata) -> u128 {
    m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_nanos()).unwrap_or(0)
}

fn hash_file(path: &Path) -> std::io::Result<[u8; 32]> {
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finish())
}

fn cstr(s: &str) -> CString {
    CString::new(s.replace('\0', "")).unwrap_or_default()
}

unsafe fn text_of(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        Some(CStr::from_ptr(p).to_string_lossy().to_string())
    }
}

// Write `bytes` to `dst` atomically: a file of its own beside it, renamed.
fn write_atomic(dst: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(d) = dst.parent() {
        std::fs::create_dir_all(d)?;
    }
    let tmp = dst.with_extension(format!("part{}", std::process::id()));
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.flush()?;
    }
    if dst.exists() {
        let _ = std::fs::remove_file(dst);
    }
    std::fs::rename(&tmp, dst).map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        e
    })
}

fn link_or_copy(src: &Path, dst: &Path) -> std::io::Result<()> {
    if let Some(d) = dst.parent() {
        std::fs::create_dir_all(d)?;
    }
    let _ = std::fs::remove_file(dst);
    std::fs::hard_link(src, dst).or_else(|_| std::fs::copy(src, dst).map(|_| ()))
}

impl Packs {
    pub fn new() -> Packs {
        Packs(Arc::new(Inner {
            store: Mutex::new(default_store()),
            extra: Mutex::new(Vec::new()),
            mode: AtomicI32::new(ke::pfetch_never()),
            hook: Mutex::new(None),
            events: Mutex::new(None),
            cancel: AtomicBool::new(false),
            good: Mutex::new(HashMap::new()),
            notes: Mutex::new(Vec::new()),
            fetch: Mutex::new(()),
            class: crate::plan::platform_class(),
        }))
    }

    pub fn class(&self) -> i32 {
        self.0.class
    }

    pub fn store(&self) -> PathBuf {
        self.0.store.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn set_store(&self, dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(dir).map_err(|e| format!("the pack directory {} cannot be made: {}", dir.display(), e))?;
        *self.0.store.lock().unwrap_or_else(|e| e.into_inner()) = dir.to_path_buf();
        Ok(())
    }

    // A directory that is searched, read-only, for packs.
    pub fn add_dir(&self, dir: &Path) {
        let mut v = self.0.extra.lock().unwrap_or_else(|e| e.into_inner());
        if !v.iter().any(|d| d == dir) {
            v.push(dir.to_path_buf());
        }
    }

    pub fn mode(&self) -> i32 {
        self.0.mode.load(Ordering::SeqCst)
    }

    pub fn set_mode(&self, mode: i32) {
        self.0.mode.store(mode, Ordering::SeqCst);
    }

    pub fn set_hook(&self, f: Option<PackNeededFn>, ctx: *mut c_void) {
        *self.0.hook.lock().unwrap_or_else(|e| e.into_inner()) = f.map(|f| Hook { f, ctx: ctx as usize });
    }

    pub fn set_events(&self, f: Option<PackEventFn>, ctx: *mut c_void) {
        *self.0.events.lock().unwrap_or_else(|e| e.into_inner()) = f.map(|f| Events { f, ctx: ctx as usize });
    }

    pub fn has_hook(&self) -> bool {
        self.0.hook.lock().unwrap_or_else(|e| e.into_inner()).is_some()
    }

    // Ask the running fetch to stop (any thread): the pack is then missing, `pack-fetch-cancelled:<id>`.
    pub fn cancel(&self) {
        self.0.cancel.store(true, Ordering::SeqCst);
    }

    fn note(&self, s: String) {
        self.0.notes.lock().unwrap_or_else(|e| e.into_inner()).push(s);
    }

    // What happened to packs since the last call: mismatches, failed fetches (for the app's log and for the tests).
    pub fn take_notes(&self) -> Vec<String> {
        std::mem::take(&mut *self.0.notes.lock().unwrap_or_else(|e| e.into_inner()))
    }

    // ---------------------------------------------------------------------------------------- finding and verifying

    fn candidates(&self, info: &PackInfo, f: &PackFile) -> Vec<(PathBuf, bool)> {
        let mut v = vec![(self.store().join(&info.id).join(&f.name), true)];
        for d in self.0.extra.lock().unwrap_or_else(|e| e.into_inner()).iter() {
            v.push((d.join(&info.id).join(&f.name), false));
            v.push((d.join(&f.name), false));
        }
        if let Some(m) = env("EMON_MODELS") {
            for d in std::env::split_paths(&m) {
                v.push((d.join(&info.id).join(&f.name), false));
                v.push((d.join(&f.name), false));
            }
        }
        v
    }

    // Is this file the catalogue's: its size and its SHA-256? A file that was hashed and has not changed since is not hashed again.
    fn verify(&self, path: &Path, f: &PackFile) -> bool {
        let m = match std::fs::metadata(path) {
            Ok(m) if m.is_file() => m,
            _ => return false,
        };
        if m.len() != f.bytes {
            return false;
        }
        let stamp = (m.len(), mtime_ns(&m));
        if self.0.good.lock().unwrap_or_else(|e| e.into_inner()).get(path) == Some(&stamp) {
            return true;
        }
        match hash_file(path) {
            Ok(h) if h == f.sha => {
                self.0.good.lock().unwrap_or_else(|e| e.into_inner()).insert(path.to_path_buf(), stamp);
                true
            }
            _ => false,
        }
    }

    // The verified files of pack `p` (one per file of the pack, in the catalogue's order), or None when any is absent or does not match.
    pub fn locate(&self, p: i32) -> Option<Vec<PathBuf>> {
        let info = pack_info(p);
        let mut out = Vec::new();
        for f in &info.files {
            let mut found = None;
            for (path, ours) in self.candidates(&info, f) {
                if !path.exists() {
                    continue;
                }
                if self.verify(&path, f) {
                    found = Some(path);
                    break;
                }
                // a file that is there and is not the catalogue's: never used. In the store it is deleted and the pack is missing again; a directory that is the app's is left alone
                self.note(format!("pack-hash-mismatch:{}", info.id));
                if ours {
                    let _ = std::fs::remove_file(&path);
                }
            }
            out.push(found?);
        }
        Some(out)
    }

    pub fn installed(&self) -> Vec<i32> {
        (1..=pack_count()).filter(|p| self.locate(*p).is_some()).collect()
    }

    // The flat directory an adapter of `engine` (kin's engine name) reads: links to the verified files of the packs it reads. Rebuilt each time it is asked for, so a pack that
    // arrived since is in it and one that no longer verifies is not.
    pub fn view(&self, engine: &str) -> Result<PathBuf, String> {
        let dir = self.store().join(".views").join(engine);
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {}", dir.display(), e))?;
        let eng = {
            let c = ke::eng_find(engine.as_bytes(), 0, engine.len() as i32);
            if c < 0 { 0 } else { c }
        };
        let mut want: HashMap<String, PathBuf> = HashMap::new();
        for p in 1..=pack_count() {
            let pe = ke::pk_pack_engine(p);
            if pe != 0 && pe != eng {
                continue;
            }
            if let Some(paths) = self.locate(p) {
                let info = pack_info(p);
                for (f, path) in info.files.iter().zip(paths) {
                    want.insert(f.name.clone(), path);
                }
            }
        }
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for e in rd.filter_map(|e| e.ok()) {
                let n = e.file_name().to_string_lossy().to_string();
                if !want.contains_key(&n) {
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
        for (name, src) in want {
            let dst = dir.join(&name);
            let same = match (std::fs::metadata(&dst), std::fs::metadata(&src)) {
                (Ok(a), Ok(b)) => a.len() == b.len() && mtime_ns(&a) == mtime_ns(&b),
                _ => false,
            };
            if !same {
                link_or_copy(&src, &dst).map_err(|e| format!("{}: {}", dst.display(), e))?;
            }
        }
        Ok(dir)
    }

    // ---------------------------------------------------------------------------------------- what a conversion needs

    // D896's `prepare()`, as kin resolves it: the rows (state, engine, pack, language) for the engines (kin's `eng`), the languages (packed cells), and whether orientation
    // detection is wanted, given the packs that are installed.
    pub fn resolve(&self, engines: &[i32], langs: &[i32], osd: bool) -> Result<Vec<Row>, String> {
        let installed = self.installed();
        self.resolve_with(engines, langs, osd, &installed)
    }

    pub fn resolve_with(&self, engines: &[i32], langs: &[i32], osd: bool, installed: &[i32]) -> Result<Vec<Row>, String> {
        let (mut l, mut e, mut i) = (Cells::new(), Cells::new(), Cells::new());
        for v in langs {
            cells_push(&mut l, *v);
        }
        for v in engines {
            cells_push(&mut e, *v);
        }
        for v in installed {
            cells_push(&mut i, *v);
        }
        let (mut out, mut scratch) = (Cells::new(), Cells::new());
        let r = ke::pk_resolve(self.0.class, if osd { ke::pkf_osd() } else { 0 }, &mut l, &mut e, &mut i, &mut out, &mut scratch);
        if r != 0 {
            let mut msg = String::new();
            ke::pk_explain(r, &mut out, &mut msg);
            return Err(msg);
        }
        Ok((0..cells_len(&out) / 4).map(|k| Row { state: cells_at(&out, 4 * k), engine: cells_at(&out, 4 * k + 1), pack: cells_at(&out, 4 * k + 2), lang: cells_at(&out, 4 * k + 3) }).collect())
    }

    // ---------------------------------------------------------------------------------------- the hook, events

    // Tell the app (a pack event); true when the app, or `cancel`, asks to stop.
    fn emit(&self, kind: i32, info: &PackInfo, done: u64, total: u64, app: bool, message: &str) -> bool {
        let ev = *self.0.events.lock().unwrap_or_else(|e| e.into_inner());
        let mut stop = false;
        if let Some(Events { f, ctx }) = ev {
            let (pack, engine, msg) = (cstr(&info.id), cstr(&info.engine_name), cstr(message));
            let e = PackEvent { kind, pack: pack.as_ptr(), engine: engine.as_ptr(), done, total, initiator: if app { PACK_BY_APP } else { PACK_BY_LIBRARY }, message: msg.as_ptr() };
            stop = unsafe { f(ctx as *mut c_void, &e) } != 0;
        }
        stop || self.0.cancel.load(Ordering::SeqCst)
    }

    fn ask(&self, info: &PackInfo, reason: &str, request_engine: &str) -> Decision {
        let hook = *self.0.hook.lock().unwrap_or_else(|e| e.into_inner());
        let Some(Hook { f, ctx }) = hook else { return Decision::default() };
        let (pack, engine, kind, licence, why, req_engine) = (cstr(&info.id), cstr(&info.engine_name), cstr(&info.kind_name), cstr(&info.licence), cstr(reason), cstr(request_engine));
        let req = PackRequest { pack: pack.as_ptr(), rev: info.rev, engine: engine.as_ptr(), kind: kind.as_ptr(), licence: licence.as_ptr(), bytes: info.bytes, reason: why.as_ptr(), request_engine: req_engine.as_ptr() };
        let mut out = PackDecision { action: 0, data: std::ptr::null(), len: 0, path: std::ptr::null(), engine: std::ptr::null() };
        let r = unsafe { f(ctx as *mut c_void, &req, &mut out) };
        if r != 0 {
            return Decision::default();
        }
        unsafe {
            Decision {
                action: out.action,
                data: if !out.data.is_null() && out.len > 0 { Some(std::slice::from_raw_parts(out.data, out.len).to_vec()) } else { None },
                path: text_of(out.path).filter(|s| !s.is_empty()).map(PathBuf::from),
                engine: text_of(out.engine).filter(|s| !s.is_empty()),
            }
        }
    }

    // ---------------------------------------------------------------------------------------- a pack a request needs

    // D896: what becomes of pack `p`, which a conversion (or the app: `app`) needs. Kin decides what to do (`ad-pack-action`); this does it.
    pub fn need(&self, p: i32, reason: &str, request_engine: &str) -> Need {
        let info = pack_info(p);
        let present = self.locate(p).is_some();
        let (mode, off, hook) = (self.mode(), offline(), self.has_hook());
        let asks = ka::ad_asks_hook(present as i32, mode, off as i32, hook as i32) == 1;
        let decision = if asks { self.ask(&info, reason, request_engine) } else { Decision::default() };
        let action = ka::ad_pack_action(present as i32, mode, off as i32, hook as i32, decision.action);
        let id = info.id.clone();
        if action == ka::pact_use() {
            Need::Ready
        } else if action == ka::pact_fetch() {
            match self.fetch(&info, false) {
                Ok(()) => Need::Ready,
                Err(e) => Need::Unread(e),
            }
        } else if action == ka::pact_provided() {
            match self.install(&info, &decision) {
                Ok(()) => Need::Ready,
                Err(e) => Need::Unread(e),
            }
        } else if action == ka::pact_substitute() {
            Need::Substitute(decision.engine.unwrap_or_default())
        } else {
            // unread: never, offline, cache, a decline, an answer nobody defined
            Need::Unread(format!("pack-missing:{}", id))
        }
    }

    // The app's own bytes or path for a pack, installed into the store after they are verified (D896: every pack, including app-provided bytes, is verified).
    fn install(&self, info: &PackInfo, d: &Decision) -> Result<(), String> {
        let id = &info.id;
        let mut staged: Vec<(&PackFile, Vec<u8>)> = Vec::new();
        for f in &info.files {
            let bytes = if let Some(data) = &d.data {
                if info.files.len() != 1 {
                    return Err(format!("pack-provide-failed:{}", id));
                }
                data.clone()
            } else if let Some(path) = &d.path {
                let file = if path.is_dir() { path.join(&f.name) } else { path.clone() };
                std::fs::read(&file).map_err(|_| format!("pack-provide-failed:{}", id))?
            } else {
                return Err(format!("pack-provide-failed:{}", id));
            };
            let mut h = Sha256::new();
            h.update(&bytes);
            if bytes.len() as u64 != f.bytes || h.finish() != f.sha {
                self.note(format!("pack-hash-mismatch:{}", id));
                return Err(format!("pack-hash-mismatch:{}", id));
            }
            staged.push((f, bytes));
        }
        for (f, bytes) in staged {
            write_atomic(&self.store().join(id).join(&f.name), &bytes).map_err(|_| format!("pack-provide-failed:{}", id))?;
        }
        if self.locate(info.code).is_some() {
            Ok(())
        } else {
            Err(format!("pack-provide-failed:{}", id))
        }
    }

    // The packs of `ids`, fetched now (the app's `packs.ensure`): progress and cancel through the events. Only when the mode lets anything be fetched.
    pub fn ensure(&self, ids: &[i32]) -> Result<(), String> {
        if offline() {
            return Err("EMON_OFFLINE=1: nothing is fetched".to_string());
        }
        if self.mode() != ke::pfetch_auto() {
            return Err("the pack mode does not fetch (it is `never` or `cache`)".to_string());
        }
        for p in ids {
            if self.locate(*p).is_some() {
                continue;
            }
            self.fetch(&pack_info(*p), true)?;
        }
        Ok(())
    }

    #[cfg(not(feature = "packs-fetch"))]
    fn fetch(&self, info: &PackInfo, app: bool) -> Result<(), String> {
        let _ = app;
        self.note(format!("pack-fetch-unavailable:{}", info.id));
        Err(format!("pack-fetch-unavailable:{}", info.id))
    }

    // Where a file may be downloaded from: `$EMON_PACKS_URL` (a comma list of bases, each `<base>/sha256/<hex>`, or of templates with {sha} {id} {rev} {file}), else the
    // mirror, then the data release (pkg1 3.3).
    #[cfg(feature = "packs-fetch")]
    fn urls(&self, info: &PackInfo, f: &PackFile) -> Vec<String> {
        let fill = |t: &str| t.replace("{sha}", &hex(&f.sha)).replace("{id}", &info.id).replace("{rev}", &info.rev.to_string()).replace("{file}", &f.name);
        let bases: Vec<String> = match env("EMON_PACKS_URL") {
            Some(v) => v.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect(),
            None => vec!["https://emon-packs.3sln.com".to_string(), "https://github.com/3sln/emon/releases/download/packs/{id}@{rev}-{file}".to_string()],
        };
        bases.iter().map(|b| if b.contains('{') { fill(b) } else { format!("{}/sha256/{}", b.trim_end_matches('/'), hex(&f.sha)) }).collect()
    }

    #[cfg(feature = "packs-fetch")]
    fn download(&self, url: &str, expect: u64, progress: &mut dyn FnMut(u64, u64) -> bool) -> Result<Vec<u8>, String> {
        let agent: ureq::Agent = ureq::Agent::config_builder().timeout_global(Some(std::time::Duration::from_secs(600))).build().into();
        let mut resp = agent.get(url).call().map_err(|e| format!("{}: {}", url, e))?;
        let total = resp.body().content_length().unwrap_or(expect);
        let mut reader = resp.body_mut().with_config().limit(expect.saturating_mul(2).max(1 << 20)).reader();
        let mut out = Vec::with_capacity(expect.min(1 << 28) as usize);
        let mut buf = vec![0u8; 64 * 1024];
        loop {
            let n = reader.read(&mut buf).map_err(|e| format!("{}: {}", url, e))?;
            if n == 0 {
                break;
            }
            out.extend_from_slice(&buf[..n]);
            if progress(out.len() as u64, total) {
                return Err("cancelled".to_string());
            }
        }
        Ok(out)
    }

    // Fetch every file of the pack, verify each against the catalogue (a mismatch is never written), install into the store.
    #[cfg(feature = "packs-fetch")]
    fn fetch(&self, info: &PackInfo, app: bool) -> Result<(), String> {
        let _one = self.0.fetch.lock().unwrap_or_else(|e| e.into_inner());
        if self.locate(info.code).is_some() {
            return Ok(());
        }
        let id = info.id.clone();
        // a cancel asks to stop the fetch that is running: one asked for while nothing was fetching is not held against this one
        self.0.cancel.store(false, Ordering::SeqCst);
        self.emit(PACK_EVENT_START, info, 0, info.bytes, app, "");
        let mut base = 0u64;
        for f in &info.files {
            let mut got: Option<Vec<u8>> = None;
            let mut last = String::new();
            for url in self.urls(info, f) {
                let mut cancelled = false;
                let r = self.download(&url, f.bytes, &mut |done, _| {
                    cancelled = self.emit(PACK_EVENT_PROGRESS, info, base + done, info.bytes, app, "");
                    cancelled
                });
                match r {
                    Ok(bytes) => {
                        let mut h = Sha256::new();
                        h.update(&bytes);
                        if bytes.len() as u64 == f.bytes && h.finish() == f.sha {
                            got = Some(bytes);
                            break;
                        }
                        // the bytes are not the catalogue's: discarded, never written
                        self.note(format!("pack-hash-mismatch:{}", id));
                        last = format!("pack-hash-mismatch:{}", id);
                    }
                    Err(e) if cancelled || e == "cancelled" => {
                        self.emit(PACK_EVENT_CANCELLED, info, base, info.bytes, app, "");
                        self.note(format!("pack-fetch-cancelled:{}", id));
                        self.0.cancel.store(false, Ordering::SeqCst);
                        return Err(format!("pack-fetch-cancelled:{}", id));
                    }
                    Err(e) => {
                        self.note(format!("pack-fetch-failed:{}: {}", id, e));
                        last = format!("pack-fetch-failed:{}", id);
                    }
                }
            }
            let Some(bytes) = got else {
                self.emit(PACK_EVENT_FAILED, info, base, info.bytes, app, &last);
                return Err(last);
            };
            write_atomic(&self.store().join(&id).join(&f.name), &bytes).map_err(|e| {
                self.emit(PACK_EVENT_FAILED, info, base, info.bytes, app, &e.to_string());
                format!("pack-fetch-failed:{}", id)
            })?;
            base += f.bytes;
        }
        self.emit(PACK_EVENT_DONE, info, info.bytes, info.bytes, app, "");
        if self.locate(info.code).is_some() {
            Ok(())
        } else {
            Err(format!("pack-fetch-failed:{}", id))
        }
    }
}

impl Default for Packs {
    fn default() -> Packs {
        Packs::new()
    }
}
