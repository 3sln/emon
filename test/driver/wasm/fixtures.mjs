// FIXTURES THE BROWSER CHECKS BUILD (DEVLOG P9.3): documents made in memory, never committed, for the measurements the committed
// corpus cannot make: a one-page PDF that is mostly image, to see how little of a document a conversion reads; and PDFs whose assets
// add up to the 32 MiB `assets-bytes` default, to measure the JS heap. Deterministic: the same bytes every run.
import { createHash } from 'node:crypto';

// xorshift32: incompressible, repeatable "image" bytes
function noise(n, seed) {
  const out = new Uint8Array(n);
  let x = seed >>> 0 || 1;
  for (let i = 0; i < n; i++) {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    out[i] = x & 255;
  }
  return out;
}

const enc = (s) => new TextEncoder().encode(s);

/** Assemble PDF objects (each `{ dict, stream? }`) with a classic xref table. Object numbers are 1-based, in order; 1 is the catalog. */
function assemble(objs, extraTrailer = '') {
  const parts = [enc('%PDF-1.7\n%\xE2\xE3\xCF\xD3\n')];
  const offs = [];
  let at = parts[0].length;
  const push = (u8) => {
    parts.push(u8);
    at += u8.length;
  };
  objs.forEach((o, i) => {
    offs.push(at);
    push(enc(`${i + 1} 0 obj\n${o.dict.replace('LEN', o.stream ? String(o.stream.length) : '0')}\n`));
    if (o.stream) {
      push(enc('stream\n'));
      push(o.stream);
      push(enc('\nendstream\n'));
    }
    push(enc('endobj\n'));
  });
  const xref = at;
  let t = `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n`;
  for (const o of offs) t += `${String(o).padStart(10, '0')} 00000 n \n`;
  t += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R ${extraTrailer}>>\nstartxref\n${xref}\n%%EOF\n`;
  push(enc(t));
  const all = new Uint8Array(at);
  let p = 0;
  for (const u of parts) {
    all.set(u, p);
    p += u.length;
  }
  return all;
}

/**
 * A one-page document that is mostly image: a heading, a paragraph and a raw gray image of `imageBytes` bytes (an XObject the
 * page draws), which a conversion without assets never decodes.
 */
export function bigImagePdf({ imageBytes = 3 * 1024 * 1024 } = {}) {
  const w = 2048;
  const h = Math.ceil(imageBytes / w);
  const img = noise(w * h, 7);
  const content = enc('BT /F1 24 Tf 72 700 Td (Field notes on range reads) Tj ET\nBT /F1 12 Tf 72 660 Td (Most of this page is a picture the conversion never reads.) Tj ET\nq 400 0 0 300 72 300 cm /Im1 Do Q\n');
  const objs = [
    { dict: '<< /Type /Catalog /Pages 2 0 R >>' },
    { dict: '<< /Type /Pages /Kids [3 0 R] /Count 1 >>' },
    { dict: '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> /XObject << /Im1 6 0 R >> >> >>' },
    { dict: '<< /Length LEN >>', stream: content },
    { dict: '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>' },
    { dict: `<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceGray /BitsPerComponent 8 /Length LEN >>`, stream: img },
  ];
  return assemble(objs);
}

/**
 * A document with `pictures` pages, each a heading and one raw gray picture of `each` bytes drawn on it: a figure at `assets=images`
 * becomes an asset (its pixels as the canonical PNG), so the Model's assets add up to about `pictures * each` bytes (a raw picture of
 * noise does not compress, so its PNG is a little bigger than it). Used to fill the 32 MiB `assets-bytes` default.
 */
export function pictureBookPdf({ pictures = 12, each = 3 * 1024 * 1024 } = {}) {
  const w = 2048;
  const h = Math.ceil(each / w);
  const objs = [{ dict: '<< /Type /Catalog /Pages 2 0 R >>' }, null, { dict: '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>' }];
  const kids = [];
  for (let p = 0; p < pictures; p++) {
    const base = objs.length + 1; // page, contents, image
    kids.push(`${base} 0 R`);
    objs.push({ dict: `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents ${base + 1} 0 R /Resources << /Font << /F1 3 0 R >> /XObject << /Im1 ${base + 2} 0 R >> >> >>` });
    objs.push({ dict: '<< /Length LEN >>', stream: enc(`BT /F1 20 Tf 72 740 Td (Picture ${p + 1}) Tj ET\nq 468 0 0 468 72 200 cm /Im1 Do Q\n`) });
    objs.push({ dict: `<< /Type /XObject /Subtype /Image /Width ${w} /Height ${h} /ColorSpace /DeviceGray /BitsPerComponent 8 /Length LEN >>`, stream: noise(w * h, 1000 + p) });
  }
  objs[1] = { dict: `<< /Type /Pages /Kids [${kids.join(' ')}] /Count ${pictures} >>` };
  return assemble(objs);
}

export const sha = (u8) => createHash('sha256').update(u8).digest('hex');
