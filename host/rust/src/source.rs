// BYTE SOURCES (DEVLOG P1.10, SPEC 13.1, D058). The library never takes a path, never opens a file and
// never names a filesystem: a document is a `ByteSource` (a length and `read_at`), and what is shipped
// here is every way a Rust caller commonly HAS one. Test drivers and CLIs read the file themselves and
// hand over bytes or a `File` wrapped in `SeekSource`.
//
//     bytes        `Vec<u8>`, `&[u8]` (copied), `Arc<[u8]>`: an in-memory source (`as_slice` answers, so the
//                  engine reads it with no copy and no chunk cache)
//     Read + Seek  `SeekSource`: a file, a `Cursor`, an object-store reader. Random access through the seek;
//                  the engine reads it in chunks, on demand
//     Read         `SpoolSource`: a forward-only stream, SPOOLED into memory bounded by the input-bytes limit
//                  (SPEC 13.1: a stream is an adapter, not the contract)
//
// `Input { source, password }` is one document (the password travels with its input, D040: never in the
// settings, never in the Model, a dump or a transcript).

use std::io::{self, Read, Seek, SeekFrom};
use std::sync::{Arc, Mutex};

// Random access with a known length. `read_at` may return fewer bytes than asked for (like `pread`); the
// engine loops, and treats a read that returns 0 before the span is full as `source-changed`.
pub trait ByteSource: Send + Sync {
    fn len(&self) -> u64;
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize>;
    // The whole source when it is already in memory: lets the engine skip the chunk cache.
    fn as_slice(&self) -> Option<&[u8]> {
        None
    }
}

fn read_slice(v: &[u8], off: u64, buf: &mut [u8]) -> io::Result<usize> {
    if off >= v.len() as u64 {
        return Ok(0);
    }
    let s = off as usize;
    let n = std::cmp::min(buf.len(), v.len() - s);
    buf[..n].copy_from_slice(&v[s..s + n]);
    Ok(n)
}

impl ByteSource for Vec<u8> {
    fn len(&self) -> u64 {
        Vec::len(self) as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        read_slice(self, off, buf)
    }
    fn as_slice(&self) -> Option<&[u8]> {
        Some(self)
    }
}

impl ByteSource for Arc<[u8]> {
    fn len(&self) -> u64 {
        <[u8]>::len(self) as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        read_slice(self, off, buf)
    }
    fn as_slice(&self) -> Option<&[u8]> {
        Some(self)
    }
}

// `&[u8]` borrowed for a call cannot outlive it, and a source must (the engine holds it across attempts
// and a `Model` does not, but an `Input` may be kept): this COPIES. Pass a `Vec<u8>` to hand bytes over.
pub struct BytesSource(Vec<u8>);

impl BytesSource {
    pub fn new(b: &[u8]) -> BytesSource {
        BytesSource(b.to_vec())
    }
}

impl ByteSource for BytesSource {
    fn len(&self) -> u64 {
        self.0.len() as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        read_slice(&self.0, off, buf)
    }
    fn as_slice(&self) -> Option<&[u8]> {
        Some(&self.0)
    }
}

// A `Read + Seek`: a file, a `Cursor`, anything seekable. The length is taken once, when the source is
// made (seek to the end); every `read_at` seeks and reads under a lock, so one handle serves the engine.
pub struct SeekSource<R: Read + Seek + Send> {
    r: Mutex<R>,
    len: u64,
}

impl<R: Read + Seek + Send> SeekSource<R> {
    pub fn new(mut r: R) -> io::Result<SeekSource<R>> {
        let len = r.seek(SeekFrom::End(0))?;
        Ok(SeekSource { r: Mutex::new(r), len })
    }
}

impl<R: Read + Seek + Send> ByteSource for SeekSource<R> {
    fn len(&self) -> u64 {
        self.len
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        let mut r = self.r.lock().unwrap();
        r.seek(SeekFrom::Start(off))?;
        r.read(buf)
    }
}

// A forward-only `Read`, SPOOLED into memory. At most `max` bytes are taken: a stream longer than that is
// `Err` of kind `InvalidData` whose message begins `input-bytes` (the engine maps it to the named limit),
// so a hostile stream cannot be spooled without bound.
pub struct SpoolSource(Vec<u8>);

impl SpoolSource {
    pub fn spool<R: Read>(mut r: R, max: u64) -> io::Result<SpoolSource> {
        let mut v = Vec::new();
        (&mut r).take(max.saturating_add(1)).read_to_end(&mut v)?;
        if v.len() as u64 > max {
            return Err(io::Error::new(io::ErrorKind::InvalidData, format!("input-bytes: the stream is longer than {} bytes", max)));
        }
        Ok(SpoolSource(v))
    }
}

impl ByteSource for SpoolSource {
    fn len(&self) -> u64 {
        self.0.len() as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize> {
        read_slice(&self.0, off, buf)
    }
    fn as_slice(&self) -> Option<&[u8]> {
        Some(&self.0)
    }
}

// One document or image: a source and, when the file is encrypted, its password (D040). The password is
// bytes: R2-R4 use them as given (PDFDocEncoding, in practice Latin-1), R5 and R6 as UTF-8.
#[derive(Clone)]
pub struct Input {
    pub source: Arc<dyn ByteSource>,
    pub password: Option<Vec<u8>>,
}

impl Input {
    pub fn new(source: impl ByteSource + 'static) -> Input {
        Input { source: Arc::new(source), password: None }
    }
    // A bare byte buffer is shorthand for an in-memory source with no password.
    pub fn bytes(b: Vec<u8>) -> Input {
        Input::new(b)
    }
    pub fn seekable<R: Read + Seek + Send + 'static>(r: R) -> io::Result<Input> {
        Ok(Input::new(SeekSource::new(r)?))
    }
    // A forward-only stream, spooled; `max` is the input-bytes bound to spool to.
    pub fn spooled<R: Read>(r: R, max: u64) -> io::Result<Input> {
        Ok(Input::new(SpoolSource::spool(r, max)?))
    }
    pub fn with_password(mut self, password: impl Into<Vec<u8>>) -> Input {
        self.password = Some(password.into());
        self
    }
}

impl std::fmt::Debug for Input {
    // Never prints the password.
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        write!(f, "Input {{ len: {}, password: {} }}", self.source.len(), if self.password.is_some() { "yes" } else { "no" })
    }
}

impl From<Vec<u8>> for Input {
    fn from(v: Vec<u8>) -> Input {
        Input::bytes(v)
    }
}

impl From<&[u8]> for Input {
    fn from(v: &[u8]) -> Input {
        Input::new(BytesSource::new(v))
    }
}

// What `convert` and `intake` take: one `Input` or an ordered list of them; a bare byte buffer is an input.
pub struct Inputs(pub Vec<Input>);

impl From<Input> for Inputs {
    fn from(i: Input) -> Inputs {
        Inputs(vec![i])
    }
}
impl From<Vec<Input>> for Inputs {
    fn from(v: Vec<Input>) -> Inputs {
        Inputs(v)
    }
}
impl From<Vec<u8>> for Inputs {
    fn from(v: Vec<u8>) -> Inputs {
        Inputs(vec![Input::from(v)])
    }
}
impl From<&[u8]> for Inputs {
    fn from(v: &[u8]) -> Inputs {
        Inputs(vec![Input::from(v)])
    }
}
impl<const N: usize> From<&[u8; N]> for Inputs {
    fn from(v: &[u8; N]) -> Inputs {
        Inputs(vec![Input::from(&v[..])])
    }
}
