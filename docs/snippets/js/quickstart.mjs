import { readFile, writeFile } from 'node:fs/promises';
import { Emon } from '@3sln/emon';

const wasm = await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm')));
const emon = await Emon.load(wasm);
const model = await emon.convert(await readFile('report.pdf'));
await writeFile('report.html', model.html());
model.dispose();
