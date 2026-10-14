// THE HARNESS'S SERVER (DEVLOG P9.3): a static server for the page, the SDK, the module, the repository's fixtures and the
// tesseract.js files, with what a URL source needs: byte `Range`s (206, `Content-Range`), a strong ETag, and a counter of the
// bytes it sent per path, so a test can say how much of a document a conversion fetched.
//
//   /                       the harness page            /harness.mjs   its script
//   /sdk/*                  host/js/src/*               /emon.wasm     the module
//   /repo/*                 any file of the repository  (served with Range)
//   /gen/NAME               a document the test registered with `server.add(name, bytes)` (served with Range; changeable)
//   /tess/worker.min.js     tesseract.js's worker       /tess/core/*   tesseract.js-core   /tess/lang/*   the eng model
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const repo = path.resolve(here, '..', '..', '..');

const TYPES = { '.html': 'text/html', '.mjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.gz': 'application/gzip' };

/** A file of the pdfium wasm build in `.cache/pdfium/<version>/wasm/` (the unpacked archive, and `lib/pdfium.mjs` its loader), or null. */
export function pdfiumFile(rel) {
  const base = path.join(repo, '.cache', 'pdfium');
  if (!fs.existsSync(base)) return null;
  for (const v of fs.readdirSync(base)) {
    const d = path.join(base, v, 'wasm');
    const f = path.resolve(d, rel);
    if (f.startsWith(d + path.sep) && fs.existsSync(path.join(d, 'lib', 'pdfium.mjs')) && fs.existsSync(f)) return f;
  }
  return null;
}

export function startServer({ wasm }) {
  const generated = new Map(); // name -> { bytes, etag }
  const sent = new Map(); // path -> { requests, bytes }
  const log = [];
  const count = (p, n) => {
    const s = sent.get(p) || { requests: 0, bytes: 0 };
    s.requests += 1;
    s.bytes += n;
    sent.set(p, s);
  };
  const serve = (req, res, p, bytes, etag, type, noRange) => {
    const h = { 'Content-Type': type || 'application/octet-stream', 'Accept-Ranges': noRange ? 'none' : 'bytes', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Content-Range, ETag' };
    if (etag) h.ETag = etag;
    const range = noRange ? null : /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
    if (range) {
      const a = Number(range[1]);
      const b = range[2] === '' ? bytes.length - 1 : Math.min(Number(range[2]), bytes.length - 1);
      const ifRange = req.headers['if-range'];
      if (ifRange && etag && ifRange !== etag) {
        // the validator no longer matches: the whole new representation
        res.writeHead(200, { ...h, 'Content-Length': bytes.length });
        res.end(bytes);
        count(p, bytes.length);
        return;
      }
      if (bytes.length === 0 || a >= bytes.length) {
        res.writeHead(416, { ...h, 'Content-Range': `bytes */${bytes.length}` });
        res.end();
        return;
      }
      res.writeHead(206, { ...h, 'Content-Range': `bytes ${a}-${b}/${bytes.length}`, 'Content-Length': b - a + 1 });
      res.end(bytes.subarray(a, b + 1));
      count(p, b - a + 1);
      log.push([p, a, b]);
      return;
    }
    res.writeHead(200, { ...h, 'Content-Length': bytes.length });
    res.end(bytes);
    count(p, bytes.length);
  };
  const server = http.createServer((req, res) => {
    try {
      const url = new URL(req.url, 'http://x');
      const p = decodeURIComponent(url.pathname);
      const file = (f, noRange) => {
        const bytes = fs.readFileSync(f);
        serve(req, res, p, bytes, `"${bytes.length}-${fs.statSync(f).mtimeMs}"`, TYPES[path.extname(f)], noRange);
      };
      if (p === '/') return file(path.join(here, 'page.html'));
      if (p === '/harness.mjs') return file(path.join(here, 'harness.mjs'));
      if (p === '/favicon.ico') {
        res.writeHead(204);
        return res.end();
      }
      if (p === '/emon.wasm') return file(wasm);
      if (p.startsWith('/sdk/')) return file(path.join(repo, 'host', 'js', 'src', p.slice(5)));
      if (p.startsWith('/repo/')) {
        const f = path.resolve(repo, p.slice(6));
        if (!f.startsWith(repo + path.sep) || !fs.existsSync(f)) {
          res.writeHead(404);
          return res.end();
        }
        return file(f);
      }
      if (p.startsWith('/gen/')) {
        const g = generated.get(p.slice(5));
        if (!g) {
          res.writeHead(404);
          return res.end();
        }
        return serve(req, res, p, g.bytes, g.etag, g.type, g.noRange);
      }
      if (p === '/tess/worker.min.js') return file(path.join(repo, 'host/js/node_modules/tesseract.js/dist/worker.min.js'));
      if (p.startsWith('/tess/core/')) return file(path.join(repo, 'host/js/node_modules/tesseract.js-core', p.slice(11)));
      if (p.startsWith('/tess/lang/')) return file(path.join(repo, 'host/js/node_modules/@tesseract.js-data/eng/4.0.0_best_int', p.slice(11)));
      if (p === '/tess/tesseract.esm.min.js') return file(path.join(repo, 'host/js/node_modules/tesseract.js/dist/tesseract.esm.min.js'));
      // pdfium's WebAssembly build (`bb pdfium-fetch --platform wasm`, P11.4c): /pdfium/lib/pdfium.mjs and /pdfium/lib/pdfium.wasm, when it is in the cache
      if (p.startsWith('/pdfium/')) {
        const f = pdfiumFile(p.slice(8));
        if (!f) {
          res.writeHead(404);
          return res.end();
        }
        return file(f);
      }
      res.writeHead(404);
      res.end();
    } catch (e) {
      res.writeHead(500);
      res.end(String(e));
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      resolve({
        port,
        origin: `http://127.0.0.1:${port}`,
        /** register (or replace) a document at /gen/NAME; `etag` pins its validator; `noRange` makes the server ignore Range */
        add(name, bytes, { etag = `"${name}-${bytes.length}-${Date.now()}"`, type, noRange = false } = {}) {
          generated.set(name, { bytes, etag, type, noRange });
        },
        /** forget a document registered with `add` (a runner that stages one file per job frees it when the job is done) */
        remove(name) {
          generated.delete(name);
        },
        stats(p) {
          return sent.get(p) || { requests: 0, bytes: 0 };
        },
        reset() {
          sent.clear();
          log.length = 0;
        },
        log,
        close: () => new Promise((r) => server.close(r)),
      });
    });
  });
}
