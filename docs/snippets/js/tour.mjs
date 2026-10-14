import { readFile } from 'node:fs/promises';
import { Emon, EmonError } from '@3sln/emon';

const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));
const pdf = await readFile('report.pdf');

// An input is a byte source: bytes, a Blob or File, a URL (read by Range requests), a stream, or your own { size, read }.
// A Blob is read lazily, a chunk at a time, only where the engine looks. Stage 0 alone is cheap: format, version, pages, encryption.
for (const intake of await emon.intake(new Blob([pdf]))) console.log(intake.line);

try {
  // The options are an object; limits are named as in docs/options.md.
  const model = await emon.convert(pdf, { ocr: 'never', limits: { pages: 500 } });
  // Walk the Model: every heading, with its page.
  const walk = function* (n) { yield n; for (const c of n.children ?? []) yield* walk(c); };
  for (const n of walk(model.object().body)) {
    if (n.kind === 'heading') console.log(`page ${n.at?.[0]?.[0] ?? 0}: ${n.text}`);
  }
  console.log(`${model.object().pages.length} pages, ${model.stats.requests} port requests`);
  model.dispose();
} catch (e) {
  if (!(e instanceof EmonError)) throw e;
  // e.code is the stable name to route on: "password-required", "pages", "bad-xref", ...
  console.error(`${e.code}: ${e.message}`);
  process.exit(1);
}
