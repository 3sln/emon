// A PNG READER for the pdfium-wasm gate (DEVLOG P11.4c): emon's canonical PNGs (8-bit gray, RGB or RGBA, no interlace, any of the five filters) to the
// pixels they hold. Used only to compare the pixels a live pdfium-wasm render made with the ones the native transcripts hold (Node's zlib is all it needs).
import zlib from 'node:zlib';

/** @returns {{ width: number, height: number, channels: number, data: Uint8Array }} rows top to bottom, `channels` bytes a pixel */
export function decodePng(buf) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (buf.length < 8 || sig.some((b, i) => buf[i] !== b)) throw new Error('not a PNG');
  let at = 8;
  let ihdr = null;
  const idat = [];
  while (at + 8 <= buf.length) {
    const len = buf.readUInt32BE(at);
    const type = buf.toString('latin1', at + 4, at + 8);
    const body = buf.subarray(at + 8, at + 8 + len);
    if (type === 'IHDR') ihdr = { w: body.readUInt32BE(0), h: body.readUInt32BE(4), depth: body[8], color: body[9], interlace: body[12] };
    else if (type === 'IDAT') idat.push(body);
    else if (type === 'IEND') break;
    at += 12 + len;
  }
  if (!ihdr) throw new Error('no IHDR');
  if (ihdr.depth !== 8 || ihdr.interlace !== 0) throw new Error(`unsupported PNG (depth ${ihdr.depth}, interlace ${ihdr.interlace})`);
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.color];
  if (!channels) throw new Error(`unsupported PNG colour type ${ihdr.color}`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = ihdr.w * channels;
  if (raw.length !== (stride + 1) * ihdr.h) throw new Error('PNG data is not the size the header says');
  const out = new Uint8Array(stride * ihdr.h);
  for (let y = 0; y < ihdr.h; y++) {
    const f = raw[y * (stride + 1)];
    const src = y * (stride + 1) + 1;
    const dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? out[dst + x - channels] : 0;
      const b = y > 0 ? out[dst - stride + x] : 0;
      const c = x >= channels && y > 0 ? out[dst - stride + x - channels] : 0;
      let v = raw[src + x];
      switch (f) {
        case 0: break;
        case 1: v += a; break;
        case 2: v += b; break;
        case 3: v += (a + b) >> 1; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          break;
        }
        default: throw new Error(`PNG filter ${f}`);
      }
      out[dst + x] = v & 255;
    }
  }
  return { width: ihdr.w, height: ihdr.h, channels, data: out };
}
