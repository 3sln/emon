use emon::packs::{hex, PackFile, PackInfo};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

const DEFAULT_MIRRORS: &str = "https://emon-packs.3sln.com,https://github.com/3sln/emon/releases/download/packs/{id}@{rev}-{file}";

#[cfg(target_os = "macos")]
const CURL: &str = "/usr/bin/curl";
#[cfg(windows)]
const CURL: &str = "curl.exe";
#[cfg(all(not(target_os = "macos"), not(windows)))]
const CURL: &str = "curl";

struct Scratch(PathBuf);

impl Scratch {
    fn new() -> io::Result<Self> {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        loop {
            let suffix = NEXT.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!("emon-pack-{}-{}", std::process::id(), suffix));
            match fs::create_dir(&path) {
                Ok(()) => return Ok(Self(path)),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => return Err(error),
            }
        }
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn download(url: &str, path: &Path, limit: u64, progress: &mut dyn FnMut(u64) -> bool) -> io::Result<Vec<u8>> {
    let mut child = Command::new(CURL)
        .args(["-q", "--silent", "--fail", "--location", "--globoff", "--proto", "=https", "--proto-redir", "=https"])
        .args(["--max-time", "600", "--max-filesize", &limit.to_string(), "--output"])
        .arg(path)
        .args(["--", url])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| {
            if error.kind() == io::ErrorKind::NotFound {
                io::Error::new(io::ErrorKind::NotFound, "system curl was not found. Install curl or place the catalogue's model files in a directory and set EMON_MODELS to that directory.")
            } else {
                error
            }
        })?;
    let result = (|| loop {
        let status = child.try_wait()?;
        let done = fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        if progress(done) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "download cancelled"));
        }
        if done > limit {
            return Err(io::Error::other("download exceeds the catalogue size"));
        }
        if let Some(status) = status {
            if !status.success() {
                return Err(io::Error::other(format!("curl exited with {}", status)));
            }
            return fs::read(path).map_err(io::Error::other);
        }
        std::thread::sleep(Duration::from_millis(50));
    })();
    if result.is_err() {
        let _ = child.kill();
        let _ = child.wait();
    }
    result
}

pub fn fetch(pack: &PackInfo, file: &PackFile, progress: &mut dyn FnMut(u64) -> bool) -> Result<Vec<u8>, String> {
    let scratch = Scratch::new().map_err(|e| format!("could not create a temporary download directory: {}", e))?;
    let mirrors = std::env::var("EMON_PACKS_URL")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| DEFAULT_MIRRORS.to_string());
    let sha = hex(&file.sha);
    let mut last_error = "no pack mirrors configured".to_string();
    let mut mismatch = None;
    for (index, mirror) in mirrors.split(',').map(str::trim).filter(|s| !s.is_empty()).enumerate() {
        let url = if mirror.contains('{') {
            mirror
                .replace("{sha}", &sha)
                .replace("{id}", &pack.id)
                .replace("{rev}", &pack.rev.to_string())
                .replace("{file}", &file.name)
        } else {
            format!("{}/sha256/{}", mirror.trim_end_matches('/'), sha)
        };
        let path = scratch.0.join(index.to_string());
        match download(&url, &path, file.bytes, progress) {
            Ok(bytes) if file.matches(&bytes) => return Ok(bytes),
            Ok(bytes) => mismatch = Some(bytes),
            Err(e) if matches!(e.kind(), io::ErrorKind::NotFound | io::ErrorKind::Interrupted) => return Err(e.to_string()),
            Err(e) => last_error = e.to_string(),
        }
        let _ = fs::remove_file(path);
    }
    // Let the core report a catalogue mismatch if a mirror supplied invalid bytes.
    mismatch.ok_or(last_error)
}
