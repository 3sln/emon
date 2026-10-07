// BYTE SOURCES (DEVLOG P9.3, SPEC 13.1, D058): a document is `{ size, read(offset, length) -> Promise<Uint8Array> }`, a Blob-like
// thing that lets the engine read slices and jump around and never promises the bytes are in memory. Every adapter below is
// that shape (an embedder may bring any other: object storage, an archive member, a database column), and the engine reads
// only what it needs of it, a chunk at a time (64 KiB by default), awaiting.
//
//     bytesSource(ArrayBuffer | Uint8Array)    in memory. Resident: the SDK hands the engine every byte at once (a read of bytes the
//                                              page already holds is not a read), unless `{ lazy: true }`
//     blobSource(Blob | File)                  `blob.slice(a, b).arrayBuffer()`: the browser reads the file lazily
//     urlSource(url, opts)                     `fetch` with `Range`; the validator (a strong ETag, else Last-Modified) is pinned at
//                                              open and every read must match it, else `SourceChangedError` (`source-changed`)
//     streamSource(ReadableStream, { max })    forward-only: SPOOLED into memory, at most `max` bytes (the `input-bytes` limit)
//     any { size, read }                       as it is
//
// A `read` that fails with a `SourceChangedError` (or whose `code` is `source-changed`), or that answers fewer bytes than were
// asked for, ends the conversion with `source-changed`; any other rejection with `io`.

export class SourceChangedError extends Error {
  constructor(message) {
    super(message);
    this.name = 'SourceChangedError';
    this.code = 'source-changed';
  }
}

/** A named emon failure: `code` is the stable name a crawler routes on (`password-required`, `input-bytes`, ...). */
export class EmonError extends Error {
  constructor(code, message) {
    super(message || code);
    this.name = 'EmonError';
    this.code = code;
  }
}

const asU8 = (b) => (b instanceof Uint8Array ? b : ArrayBuffer.isView(b) ? new Uint8Array(b.buffer, b.byteOffset, b.byteLength) : new Uint8Array(b));

export function bytesSource(buf, { lazy = false } = {}) {
  const u8 = asU8(buf);
  const s = {
    size: u8.length,
    async read(offset, length) {
      return u8.subarray(offset, offset + length);
    },
  };
  if (!lazy) s.bytes = u8;
  return s;
}

export function blobSource(blob) {
  return {
    size: blob.size,
    async read(offset, length) {
      return new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer());
    },
  };
}

export async function streamSource(stream, { max = 2 ** 31 - 1 } = {}) {
  const reader = stream.getReader();
  const parts = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    const u8 = asU8(value);
    total += u8.length;
    if (total > max) {
      try {
        await reader.cancel();
      } catch (_) {
        /* the stream is being abandoned */
      }
      throw new EmonError('input-bytes', `input-bytes: the stream is longer than ${max} bytes`);
    }
    parts.push(u8);
  }
  const all = new Uint8Array(total);
  let at = 0;
  for (const p of parts) {
    all.set(p, at);
    at += p.length;
  }
  return bytesSource(all);
}

/**
 * A document at a URL, read by `Range`. `fetch`ing the first byte tells its size (`Content-Range`) and its validator; a server
 * that ignores `Range` (a 200) is a spooled source, its whole body read once. Options: `fetch` (default `globalThis.fetch`),
 * `headers`, `signal`, `fetchOptions` (merged into every request: credentials, mode, ...).
 */
export async function urlSource(url, { fetch: f = globalThis.fetch.bind(globalThis), headers = {}, signal, fetchOptions = {} } = {}) {
  const get = (range, extra = {}) => f(String(url), { cache: 'no-store', ...fetchOptions, signal, headers: { ...headers, ...(range ? { Range: range } : {}), ...extra } });
  const first = await get('bytes=0-0');
  if (first.status === 200) {
    // no ranges: the body is the document
    return bytesSource(new Uint8Array(await first.arrayBuffer()));
  }
  if (first.status === 416) {
    // an empty document
    await first.arrayBuffer();
    return bytesSource(new Uint8Array(0));
  }
  if (first.status !== 206) throw new Error(`${url}: HTTP ${first.status}`);
  await first.arrayBuffer();
  const cr = /bytes\s+(\d+)-(\d+)\/(\d+)/.exec(first.headers.get('content-range') || '');
  if (!cr) throw new Error(`${url}: no Content-Range in the answer to a range request`);
  const size = Number(cr[3]);
  const etag = first.headers.get('etag');
  const lastModified = first.headers.get('last-modified');
  const strong = etag && !etag.startsWith('W/') ? etag : null;
  const validator = strong || lastModified || null;
  const changed = (why) => new SourceChangedError(`${url} changed while it was being read: ${why}`);
  const s = {
    size,
    url: String(url),
    validator,
    async read(offset, length) {
      const r = await get(`bytes=${offset}-${offset + length - 1}`, validator ? { 'If-Range': validator } : {});
      if (r.status === 200) {
        // If-Range did not match (the document changed), or the server stopped honouring ranges
        await r.arrayBuffer();
        throw changed('the server answered the whole body to a range request');
      }
      if (r.status !== 206) {
        await r.arrayBuffer();
        throw new Error(`${url}: HTTP ${r.status}`);
      }
      const m = /bytes\s+(\d+)-(\d+)\/(\d+)/.exec(r.headers.get('content-range') || '');
      const buf = new Uint8Array(await r.arrayBuffer());
      if (!m || Number(m[1]) !== offset || Number(m[3]) !== size) throw changed('its length is not what it was');
      if (etag && r.headers.get('etag') !== etag) throw changed(`its ETag is ${r.headers.get('etag')}, not ${etag}`);
      if (!etag && lastModified && r.headers.get('last-modified') !== lastModified) throw changed('its Last-Modified moved');
      return buf;
    },
  };
  return s;
}

/**
 * The source of whatever an embedder hands over: an ArrayBuffer or view, a Blob or File, a URL (a string with a scheme or a
 * `URL`), a `ReadableStream`, or any `{ size, read }`.
 */
export async function toSource(x, opts = {}) {
  if (x && typeof x === 'object' && typeof x.read === 'function' && typeof x.size === 'number') return x;
  if (x instanceof ArrayBuffer || ArrayBuffer.isView(x)) return bytesSource(x, opts);
  if (typeof Blob !== 'undefined' && x instanceof Blob) return blobSource(x);
  if (typeof ReadableStream !== 'undefined' && x instanceof ReadableStream) return streamSource(x, opts);
  if (x instanceof URL || (typeof x === 'string' && /^[a-z][a-z0-9+.-]*:|^\//i.test(x))) return urlSource(x, opts);
  throw new TypeError('emon: an input is an ArrayBuffer, a Uint8Array, a Blob or File, a URL, a ReadableStream or { size, read(offset, length) }');
}
