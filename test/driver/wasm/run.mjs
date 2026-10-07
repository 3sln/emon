// THE BROWSER DRIVER (DEVLOG P9.3): runs a list of conversion jobs in headless Chrome pages, in parallel, and writes each job's
// answer where `bb identity` and `bb slice` read it, the way the native drivers' stdout, stderr and exit code are read.
//
//   node test/driver/wasm/run.mjs --wasm PATH --jobs FILE.json --out DIR [--pages N]
//
// A job is `{ id, path, adapter, password, assets, chunk, replayDir, replay: [names], record }` (`harness.mjs`). For each, DIR gets
// `ID.out` (the bundle), `ID.err`, `ID.exit`, `ID.stats` (what the loop did, JSON) and, when it recorded, `ID.transcript/NAME`. Exit 0 once every job has an answer, whatever
// the answers say: judging them is the caller's.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { startServer, repo } from './server.mjs';
import { launch, harnessPage } from './browser.mjs';

const arg = (k, d) => {
  const i = process.argv.indexOf(k);
  return i >= 0 ? process.argv[i + 1] : d;
};

const wasm = path.resolve(arg('--wasm'));
const jobs = JSON.parse(fs.readFileSync(arg('--jobs'), 'utf8'));
const out = path.resolve(arg('--out'));
const pages = Number(arg('--pages', Math.max(1, Math.min(4, os.cpus().length - 2))));
fs.mkdirSync(out, { recursive: true });

const server = await startServer({ wasm });
const browser = await launch();
let next = 0;
const t0 = Date.now();
let done = 0;

// pages are opened one at a time: Chrome loads a page it opens beside another that is still loading badly
const opening = { chain: Promise.resolve() };
const open = () => (opening.chain = opening.chain.then(() => harnessPage(browser, server.origin)));

async function worker() {
  let page = await open();
  while (next < jobs.length) {
    const job = jobs[next++];
    let r;
    try {
      r = await Promise.race([page.evaluate((j) => window.H.run(j), job), new Promise((_, no) => setTimeout(() => no(new Error('TIMEOUT')), 180000))]);
    } catch (e) {
      r = { exit: 1, out: '', err: `error: harness: ${e && e.message ? e.message : e}\n` };
      // a crashed or hung page is replaced
      try {
        await page.close();
      } catch (_) {
        /* gone */
      }
      page = await open();
    }
    fs.writeFileSync(path.join(out, `${job.id}.out`), r.out);
    fs.writeFileSync(path.join(out, `${job.id}.err`), r.err);
    fs.writeFileSync(path.join(out, `${job.id}.exit`), String(r.exit));
    if (r.stats) fs.writeFileSync(path.join(out, `${job.id}.stats`), JSON.stringify(r.stats));
    if (r.transcript) {
      const d = path.join(out, `${job.id}.transcript`);
      fs.mkdirSync(d, { recursive: true });
      for (const [n, b64] of r.transcript) fs.writeFileSync(path.join(d, n), Buffer.from(b64, 'base64'));
    }
    done++;
    if (process.env.EMON_WASM_VERBOSE) console.error(`wasm: ${job.id} exit ${r.exit} at ${Date.now() - t0} ms`);
  }
  await page.close();
}

try {
  await Promise.all(Array.from({ length: Math.min(pages, jobs.length || 1) }, worker));
  console.error(`wasm: ${done} jobs in ${Math.round((Date.now() - t0) / 1000)} s on ${Math.min(pages, jobs.length)} page(s)`);
} finally {
  const t = Date.now();
  await browser.close();
  if (process.env.EMON_WASM_VERBOSE) console.error(`wasm: the browser closed in ${Date.now() - t} ms`);
  await server.close();
  if (process.env.EMON_WASM_VERBOSE) console.error(`wasm: the server closed at ${Date.now() - t0} ms`);
}
void repo;
process.exit(0);
