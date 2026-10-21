// A STATIC SERVER FOR THE BUILT DEMO (DEVLOG P11.4c): `bb demo --serve` and `bb demo --check`. What GitHub Pages does and no more: files by path, the right media
// type for .wasm (a streamed compile wants application/wasm) and ES modules, no listing.
//
//   node tools/demo/serve.mjs DIR [PORT]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.wasm': 'application/wasm',
  '.svg': 'image/svg+xml', '.xml': 'application/xml', '.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.gz': 'application/gzip', '.md': 'text/markdown; charset=utf-8', '.txt': 'text/plain; charset=utf-8',
};

/** Serve `dir`; -> { origin, port, requests (paths asked for, in order), close() }. */
export function serve(dir, port = 0) {
  const root = path.resolve(dir);
  const requests = [];
  const server = http.createServer((req, res) => {
    try {
      let p = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      const candidate = path.resolve(root, '.' + p);
      if (!candidate.startsWith(root + path.sep) && candidate !== root) {
        res.writeHead(403); return res.end();
      }
      if (!p.endsWith('/') && fs.existsSync(candidate) && fs.statSync(candidate).isDirectory()) {
        requests.push([p, 301]);
        res.writeHead(301, { Location: p + '/' + new URL(req.url, 'http://x').search });
        return res.end();
      }
      if (p.endsWith('/')) p += 'index.html';
      const f = path.resolve(root, '.' + p);
      if (!f.startsWith(root + path.sep) && f !== root) {
        res.writeHead(403);
        return res.end();
      }
      if (!fs.existsSync(f) || !fs.statSync(f).isFile()) {
        requests.push([p, 404]);
        const fallback = path.join(root, '404.html');
        res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(fs.existsSync(fallback) ? fs.readFileSync(fallback) : 'not found');
      }
      requests.push([p, 200]);
      const body = fs.readFileSync(f);
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'application/octet-stream', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
      res.end(body);
    } catch (e) {
      res.writeHead(500);
      res.end(String(e));
    }
  });
  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => {
      const p = server.address().port;
      resolve({ origin: `http://127.0.0.1:${p}`, port: p, requests, close: () => new Promise((r) => server.close(r)) });
    });
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const s = await serve(process.argv[2] || '.', Number(process.argv[3] || 8080));
  console.log(`serving ${path.resolve(process.argv[2] || '.')} at ${s.origin}/ (Ctrl-C to stop)`);
}
