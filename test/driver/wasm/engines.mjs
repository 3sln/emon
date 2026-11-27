// The JS engine registry's laws (DEVLOG D925), in Node over the built module: registration is held to kin's engine table and refused by name, a request goes to the adapter
// registered for the engine it names (kin's `ad-route`), an engine nobody registered answers unsupported, the producer combines. The rules are the module's; this holds that the
// host asks them. Not the browser: `node --experimental-wasm-exnref test/driver/wasm/engines.mjs --wasm PATH`.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { Emon } from '../../../host/js/src/index.js';
import { tesseractAdapter } from '../../../host/js/src/tesseract.js';

const i = process.argv.indexOf('--wasm');
const emon = await Emon.load(fs.readFileSync(path.resolve(process.argv[i + 1])));
const w = emon.w;
const request = (engine) => ({ id: 3, engine, bitmap: () => new Uint8Array(0) });
const refused = (p) => p.then(() => 'accepted', (e) => e.message);
let n = 0;
const ok = (what) => console.log('  ok   ' + what) || n++;

// registration is kin's rule
let e = emon.engines();
assert.match(await refused(e.add({ name: 'nobody', engine: 99, read: async () => null })), /^adapter-engine-unknown: nobody/);
assert.match(await refused(e.add({ name: 't', engine: 1, caps: -1, read: async () => null })), /^adapter-caps-beyond-engine: t/);
assert.match(await refused(e.add({ name: 't', engine: 1, flags: 1, read: async () => null })), /^adapter-abi-unsupported: t is async/);
assert.match(await refused(e.add({ name: 't', engine: 1, flags: 2, read: async () => null })), /^adapter-remote-refused: t/);
assert.match(await refused(e.add({ name: 't', engine: 1 })), /^adapter-abi-unsupported: t has no `read`/);
let closed = 0;
assert.match(await refused(e.add({ name: 'bad', engine: 99, read: async () => null, close: () => { closed++; } })), /adapter-engine-unknown/);
assert.equal(closed, 1);
assert.deepEqual(e.describe(), []);
ok('registration is held to kin\'s table, each refusal by name, and a refused adapter is closed');

// allowRemote first
e.allowRemote(true);
await e.add({ name: 'remote', engine: 1, flags: 2, read: async () => null });
assert.equal(e.describe().length, 1);
await e.close();
ok('a remote adapter registers after allowRemote(true)');

// routing by the engine a request names; nobody registered: unsupported
e = emon.engines();
const port0 = e.port();
assert.equal(await port0(request(undefined)), null);
const calls = [];
await e.add({ name: 'tesseract', engine: 1, needs: 1, producer: { engine: 'tesseract.js', host: 'a' }, read: async (r) => { calls.push('t'); return { words: [] }; } });
const port = e.port();
assert.deepEqual(await port(request(undefined)), { words: [] }, 'a request with no trailer is Tesseract\'s');
assert.deepEqual(await port(request(1)), { words: [] });
assert.equal(await port(request(2)), null, 'nobody registered for PP-OCR');
assert.deepEqual(calls, ['t', 't']);
await e.add({ name: 'ppocr', engine: 2, needs: 13, producer: { 'ocr-model': 'm', host: 'b' }, read: async () => { calls.push('p'); return null; } });
assert.equal(await port(request(2)), null);
assert.deepEqual(calls, ['t', 't', 'p']);
ok('a request goes to the adapter registered for its engine; an engine nobody registered is unsupported');

// the producer: the first adapter's pairs, the combined name
assert.deepEqual(e.producer(), { engine: 'tesseract.js', host: 'a', 'ocr-model': 'm', 'ocr-engine': 'tesseract+ppocr' });
assert.equal(port.producer['ocr-engine'], 'tesseract+ppocr');
ok('the producer combines the adapters');

// a rejection is noted and propagates (the loop answers failed)
await e.add({ name: 'mlkit', engine: 4, read: async () => { throw new Error('boom'); } });
await assert.rejects(() => port(request(4)), /boom/);
assert.ok(e.notes().some((l) => l.startsWith('mlkit: failed boom')));
ok('a rejection is noted');

// tesseract.js's adapter is an adapter of the Tesseract role
const t = tesseractAdapter({ createWorker: async () => { throw new Error('not here'); } });
assert.equal(t.engine, 1);
assert.equal(t.name, 'tesseract');
const e2 = emon.engines();
await e2.add(t);
assert.equal(e2.describe()[0].name, 'tesseract');
ok('tesseractAdapter registers as the Tesseract role');
await e.close();
await e2.close();
console.log(`engines: ${n} groups passed`);
