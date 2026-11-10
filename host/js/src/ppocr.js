// Experimental opt-in. No imports from ORT, no model or runtime URL defaults.
import { Wasm } from './wasm.js';
import { canonicalOcr } from './ocr.js';
import { trampolineBytes, dictionaryBytes } from './ppocr-link.js';

const pins = [
  ['ch_PP-OCRv5_det_mobile.onnx', '4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae'],
  ['ch_ppocr_mobile_v2.0_cls_mobile.onnx', 'e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c'],
  ['en_PP-OCRv5_rec_mobile.onnx', 'c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8'],
];
async function pinnedBytes(source, sha) {
  if (typeof source === 'string' || source instanceof URL) {
    const response = await fetch(source);
    if (!response.ok) throw new Error(`ppocr-models-missing: ${response.status}`);
    source = await response.arrayBuffer();
  }
  if (!source) throw new Error('ppocr-models-missing');
  // Own the verified snapshot even when the caller supplies a Node Buffer.
  const bytes = source instanceof Uint8Array ? new Uint8Array(source) : new Uint8Array(source).slice();
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  if (Array.from(hash, x => x.toString(16).padStart(2, '0')).join('') !== sha) throw new Error('ppocr-model-pin-mismatch');
  return bytes;
}

/** Dedicated dictionary instance: never recognizes or adapts document words.
 * Supply tesseract.js-core 7.0.0's ordinary SIMD LSTM factory and its local
 * module options (locateFile/wasmBinary); traineddata is the native pinned file.
 */
export function ppocrLexicon({ createCore, coreOptions, traineddata }) {
  let ready;
  const load = () => ready ||= (async () => {
    const data = await pinnedBytes(traineddata, '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2');
    const binary = await pinnedBytes(coreOptions?.wasmBinary ?? coreOptions?.locateFile?.('tesseract-core-simd-lstm.wasm', ''),
      '34e8d50cac216427d86bf397d610fdd9f49492539bbcdfbfccc4eda20c810bea');
    let exports, rejectWasm;
    const failed = new Promise((_, reject) => { rejectWasm = reject; });
    const core = await Promise.race([createCore({ ...coreOptions, wasmBinary: binary,
      instantiateWasm(imports, receive) {
        WebAssembly.instantiate(binary, imports).then(result => {
          exports = result.instance.exports;
          receive(result.instance, result.module);
        }).catch(rejectWasm);
        return {};
      },
    }), failed]);
    core.FS.mkdir('/ppocr');
    core.FS.writeFile('/ppocr/eng.traineddata', data);
    const api = new core.TessBaseAPI();
    if (api.Init('/ppocr', 'eng', 1) !== 0) { core.destroy(api); throw new Error('ppocr-lexicon-init'); }
    const memory = Object.values(exports).find(v => v instanceof WebAssembly.Memory);
    const functions = { malloc: core._malloc, free: core._free, valid: core._emscripten_bind_TessBaseAPI_IsValidWord_1 };
    if (!memory || Object.values(functions).some(fn => !Object.values(exports).includes(fn))) {
      api.End(); core.destroy(api); throw new Error('ppocr-lexicon-wasm-exports');
    }
    return { core, api, memory, functions };
  })();
  const valid = {};
  valid.bindWasm = async emonMemory => {
    const { core, api, memory, functions } = await load();
    return new WebAssembly.Instance(new WebAssembly.Module(dictionaryBytes), { link: {
      emon: emonMemory, tess: memory, api: new WebAssembly.Global({ value: 'i32' }, core.getPointer(api)), ...functions,
    } }).exports.valid_word;
  };
  valid.terminate = async () => {
    if (ready) { const { core, api } = await ready; api.End(); core.destroy(api); ready = undefined; }
  };
  return valid;
}

/** Tensor/answer adapter used by the qualification harness. Supply an optional
 * emon wasm build, loadOrt: () => import('onnxruntime-web/wasm') at exactly
 * 1.22.0, caller-hosted wasmPaths, the three models by filename, and lexicon.
 * The optional module is separate so ordinary emon.wasm retains its size.
 */
export function ppocrAdapter({ wasm, loadOrt, wasmPaths, ortWasm, models, lexicon }) {
  let ready, closed = false, chain = Promise.resolve();
  const load = () => ready ||= (async () => {
    const module = await loadOrt();
    const ort = module.env ? module : module.default;
    if (ort.env.versions.web !== '1.22.0') throw new Error('ppocr-runtime-version');
    ort.env.wasm.numThreads = 1;
    ort.env.wasm.simd = 'fixed';
    ort.env.wasm.proxy = false;
    if (!wasmPaths) throw new Error('ppocr-runtime-paths-missing');
    ort.env.wasm.wasmPaths = wasmPaths;
    ort.env.wasm.wasmBinary = await pinnedBytes(ortWasm, '71aef04959c5c1b6de461b6538e2058e306610034a85aad2742d0c7fd4533fe4');
    const sessions = [];
    try {
      for (const [name, sha] of pins) {
        sessions.push(await ort.InferenceSession.create(await pinnedBytes(models[name], sha), {
          executionProviders: ['wasm'], graphOptimizationLevel: 'all', executionMode: 'sequential',
        }));
      }
      const calls = new WebAssembly.Table({ initial: 1, element: 'anyfunc' });
      const trampoline = new WebAssembly.Instance(new WebAssembly.Module(trampolineBytes), { link: { calls } });
      const w = await Wasm.instantiate(wasm, { ppocr: { valid_word: trampoline.exports.valid_word } });
      if (!lexicon?.bindWasm) throw new Error('ppocr-wasm-lexicon-required');
      calls.set(0, await lexicon.bindWasm(w.x.memory));
      return { ort, sessions, w };
    } catch (e) { await Promise.all(sessions.map(s => s.release())); throw e; }
  })();
  const read = ({ png, id = 2, rect = null, record }) => {
    if (closed) return Promise.reject(new Error('ppocr-port-closed'));
    const job = chain.then(async () => {
      const { ort, sessions, w } = await load();
      if (!w.x.emon_pp_begin) throw new Error('ppocr-wasm-build-missing');
      try {
        if (record) {
          const rc = w.withBytes(new Uint8Array(record.buffer, record.byteOffset, record.byteLength), (p, n) =>
            w.withBytes(png, (bp, bn) => w.x.emon_pp_check(p, n, bp, bn)));
          if (rc < 0) throw new Error('PP-OCR request engine/model identity mismatch');
          if (rc === 0) throw new Error('PP-OCR request has no engine identity');
          if (rc === 2) return null;
        }
        let status = w.withBytes(png, (p, n) => w.x.emon_pp_begin(p, n, id, rect?.x || 0, rect?.y || 0, rect?.w || 0, rect?.h || 0));
        while (status > 0) {
          const data = w.outBytes(); w.outFree();
          const header = new DataView(data.buffer, data.byteOffset, data.byteLength);
          const kind = header.getInt32(0, true);
          let response, rank;
          {
            const session = sessions[kind];
            if (!session) throw new Error('ppocr-invalid-model');
            const dims = Array.from({ length: header.getInt32(4, true) }, (_, i) => header.getInt32(8 + 4 * i, true));
            const values = new Float32Array(data.buffer.slice(8 + dims.length * 4));
            const output = await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', values, dims) });
            const tensor = output[session.outputNames[0]];
            if (tensor.type !== 'float32') throw new Error('ppocr-invalid-tensor');
            rank = tensor.dims.length;
            response = new Uint8Array((rank + tensor.data.length) * 4);
            const view = new DataView(response.buffer);
            tensor.dims.forEach((d, i) => view.setInt32(i * 4, d, true));
            response.set(new Uint8Array(tensor.data.buffer, tensor.data.byteOffset, tensor.data.byteLength), rank * 4);
            for (const t of Object.values(output)) t.dispose();
          }
          status = w.withBytes(response, (p, n) => w.x.emon_pp_resume(p, n, rank));
        }
        if (status < 0) throw new Error(w.outText());
        const answer = w.outI32(); w.outFree(); return answer;
      } finally { w.x.emon_pp_cancel(); }
    });
    chain = job.catch(() => {});
    return job;
  };
  read.terminate = async () => {
    closed = true;
    await chain;
    try {
      if (ready) { const { sessions } = await ready; await Promise.all(sessions.map(s => s.release())); ready = undefined; }
    } finally { await lexicon?.terminate?.(); }
  };
  return read;
}

/** Compose the optional PP fulfiller with the existing Tesseract fulfiller.
 * Ordinary requests retain the incumbent. Tagged requests use Rust's complete
 * canonical answer, so JS never reconstructs boxes, confidence or extensions.
 */
export function ppocrPort({ incumbent, ...options }) {
  const read = ppocrAdapter(options);
  const port = async req => {
    // kin identified the canonical trailer. A view hash may contain any cell.
    if (!req.record) return incumbent(req);
    const answer = await read({ png: req.bitmap(), id: req.id, rect: req.rect, record: req.record });
    return answer === null ? null : { [canonicalOcr]: answer };
  };
  port.terminate = async () => { try { await read.terminate(); } finally { await incumbent.terminate?.(); } };
  port.producer = { ...incumbent.producer, 'ocr-engine': 'tesseract+ppocr',
    'ocr-model': 'ppocr5-en-0420cd75efc786ba37d1e028a4c9396d2f17a3b4264880f1296a7f186faced7f',
    'pp-lexicon': 'tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2',
    'onnxruntime': '1.22.0 WASM fixed SIMD sequential threads=1 graph=all', host: 'host/js/src/ppocr.js' };
  return port;
}
