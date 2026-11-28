// THE PACKS, JavaScript host (DEVLOG D925/D928 P13.16; the twin of `host/rust/src/packs.rs` and the C ABI's `emon_engines_packs`/`emon_on_pack_needed`/`emon_on_pack_event`/`emon_packs_ensure`).
//
// A pack is the model files an adapter reads with. WHAT IS KIN'S: which packs exist and their SHA-256 and sizes, which a request needs (`pk-resolve`), what to do about one that
// is missing (`ad-asks-hook`, `ad-pack-action`), `engines()` and `prepare()`. They are asked of the module (`emon.engines().packs`). WHAT IS THIS FILE'S: holding verified bytes,
// the app's hook, the events, and the download, which is THE APP'S: the library ships no HTTP client (D913). With no `fetcher` a `fetch` decision is `pack-fetch-unavailable:<id>`.
//
//     const engines = emon.engines();
//     engines.packs.setMode('auto');                                  // 'never' (the default), 'cache' or 'auto'
//     engines.packs.setFetcher(async (pack, file, progress) => bytes); // the app's download; progress(done) answers true to stop
//     engines.packs.onPackNeeded(async (need) => ({ action: 'fetch' })); // or { action: 'provide', data }, { action: 'decline' }, { action: 'substitute', engine }
//     engines.packs.onPackEvent((ev) => ...);                          // start, progress, done, failed, cancelled
//     await engines.packs.ensure(['tess-fast-eng']);                   // ahead of time
//
// EVERY PACK IS VERIFIED against the catalogue's size and SHA-256 before an adapter sees a byte of it, wherever it came from (a hook's bytes, a download, `install`). One that does
// not match is never kept (`pack-hash-mismatch:<id>`). `EMON_OFFLINE=1` (Node) and mode `never` win over every hook.

const ACTION = { fetch: 1, provide: 2, decline: 3, substitute: 4 };

const toHex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, '0')).join('');

/** SHA-256 of `bytes`, hex. */
export async function sha256Hex(bytes) {
  return toHex(await globalThis.crypto.subtle.digest('SHA-256', bytes));
}

const bytesOf = (d) => (d instanceof Uint8Array ? d : d instanceof ArrayBuffer ? new Uint8Array(d) : ArrayBuffer.isView(d) ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength) : null);

export class Packs {
  #w;
  #cat;
  #mode;
  #hook = null;
  #listeners = new Set();
  #fetcher = null;
  #cancel = false;
  #files = new Map(); // pack id -> Map(file name -> verified bytes)
  #notes;
  #fetching = Promise.resolve();

  /** @param w the loaded module; @param notes where `pack-hash-mismatch:<id>` and the like are noted */
  constructor(w, notes = () => {}) {
    this.#w = w;
    this.#cat = w.packCatalogue();
    this.#mode = this.#cat.pfetch.never;
    this.#notes = notes;
  }

  /** kin's catalogue: `{ class, packs: [{ code, id, rev, engine, kind, licence, bytes, files: [{ name, bytes, sha }] }], ... }`. */
  get catalogue() {
    return this.#cat;
  }

  /** `never` (the default: nothing is fetched and no hook is asked), `cache` (the hook is asked; a bare miss is not fetched) or `auto` (a miss with no hook is fetched). */
  setMode(name) {
    const m = this.#cat.pfetch[name];
    if (m === undefined) throw new Error(`usage: the pack mode is never, cache or auto, not ${name}`);
    this.#mode = m;
  }

  get mode() {
    return Object.keys(this.#cat.pfetch).find((k) => this.#cat.pfetch[k] === this.#mode);
  }

  /** `EMON_OFFLINE=1`: nothing is fetched and no hook is asked, whatever the mode says (Node; a browser has no environment). */
  get offline() {
    return globalThis.process?.env?.EMON_OFFLINE === '1';
  }

  /** The app's hook for a pack a conversion needs and nobody has: `(need) => decision`, sync or async. `need`: `{ pack, rev, engine, kind, licence, bytes, reason, requestEngine }`; the decision
   *  `{ action: 'fetch' }`, `{ action: 'provide', data }` (`Uint8Array`, or `{ [fileName]: Uint8Array }` for a pack of several files), `{ action: 'decline' }` or `{ action: 'substitute', engine }`.
   *  A hook that throws declines. `null` removes it. */
  onPackNeeded(fn) {
    this.#hook = fn ?? null;
  }

  /** The app's download: `async (pack, file, progress) => Uint8Array` for each file of a pack (`file`: `{ name, bytes, sha }`); `progress(done)` reports the bytes so far and answers true when the
   *  download is to stop (the app's `cancel()`, or an event listener's). `null` removes it. */
  setFetcher(fn) {
    this.#fetcher = fn ?? null;
  }

  /** Listens to the one progress stream of library- and app-initiated fetches: `{ kind: 'start' | 'progress' | 'done' | 'failed' | 'cancelled', pack, engine, done, total, initiator: 'library' | 'app', message }`.
   *  A listener that returns `true` on a `progress` event cancels the fetch. Answers the function that removes the listener. */
  onPackEvent(fn) {
    this.#listeners.add(fn);
    return () => this.#listeners.delete(fn);
  }

  /** The same events as an async iterable (`for await (const ev of packs.events())`): ends when `signal` aborts. */
  events(signal) {
    const queue = [];
    let wake = null;
    const off = this.onPackEvent((ev) => {
      queue.push(ev);
      wake?.();
    });
    const done = new Promise((res) => signal?.addEventListener('abort', res, { once: true }));
    return (async function* () {
      try {
        while (!signal?.aborted) {
          if (!queue.length) await Promise.race([new Promise((r) => (wake = r)), done]);
          while (queue.length) yield queue.shift();
        }
      } finally {
        off();
      }
    })();
  }

  /** Asks the running fetch to stop: the pack is then missing, `pack-fetch-cancelled:<id>`. */
  cancel() {
    this.#cancel = true;
  }

  #info(idOrCode) {
    return this.#cat.packs.find((p) => p.id === idOrCode || p.code === idOrCode);
  }

  #emit(kind, info, done, total, app, message = '') {
    let stop = false;
    for (const f of [...this.#listeners]) {
      try {
        if (f({ kind, pack: info.id, engine: info.engine, done, total, initiator: app ? 'app' : 'library', message }) === true) stop = true;
      } catch {
        /* a listener's failure is not the fetch's */
      }
    }
    return stop || this.#cancel;
  }

  /** The verified files of a pack, or null. */
  files(idOrCode) {
    const info = this.#info(idOrCode);
    const held = info && this.#files.get(info.id);
    return held && info.files.every((f) => held.has(f.name)) ? held : null;
  }

  /** The codes of the packs held. */
  installed() {
    return this.#cat.packs.filter((p) => this.files(p.id)).map((p) => p.code);
  }

  /** Verified files of every held pack an engine reads (`engine`: kin's engine name; packs of every engine included), by file name: what an adapter is given. */
  view(engine) {
    const out = new Map();
    for (const p of this.#cat.packs) {
      if (p.engine !== 'any' && p.engine !== engine) continue;
      const held = this.files(p.id);
      if (held) for (const [k, v] of held) out.set(k, v);
    }
    return out;
  }

  async #matches(file, bytes) {
    return bytes.length === file.bytes && (await sha256Hex(bytes)) === file.sha;
  }

  /** Verifies the app's bytes for a pack (`data`: bytes of a one-file pack, or `{ [fileName]: bytes }`) and keeps them. Throws `pack-hash-mismatch:<id>` (nothing is kept) or `pack-provide-failed:<id>`. */
  async install(idOrCode, data) {
    const info = this.#info(idOrCode);
    if (!info) throw new Error(`pack-missing:${idOrCode}`);
    const staged = new Map();
    for (const f of info.files) {
      const raw = bytesOf(data);
      const bytes = raw ? (info.files.length === 1 ? raw : null) : data?.[f.name] ? bytesOf(data[f.name]) : null;
      if (!bytes) throw new Error(`pack-provide-failed:${info.id}`);
      if (!(await this.#matches(f, bytes))) {
        this.#notes(`pack-hash-mismatch:${info.id}`);
        throw new Error(`pack-hash-mismatch:${info.id}`);
      }
      staged.set(f.name, bytes.slice());
    }
    this.#files.set(info.id, staged);
  }

  /** What becomes of pack `idOrCode`, which a conversion needs: `{ ready: true }`, `{ unread: reason }` or `{ substitute: engine }`. Kin decides (`ad-pack-action`); this does it. */
  async need(idOrCode, reason, requestEngine) {
    const info = this.#info(idOrCode);
    const present = !!this.files(info.id);
    const { mode, offline } = { mode: this.#mode, offline: this.offline };
    const hasHook = !!this.#hook;
    const asks = this.#w.packAction(present, mode, offline, hasHook, 0).asks;
    let decision = { action: 0 };
    if (asks) {
      try {
        const d = await this.#hook({ pack: info.id, rev: info.rev, engine: info.engine, kind: info.kind, licence: info.licence, bytes: info.bytes, reason, requestEngine });
        decision = { action: ACTION[d?.action] ?? 0, data: d?.data, engine: d?.engine };
      } catch {
        decision = { action: 0 };
      }
    }
    const { action } = this.#w.packAction(present, mode, offline, hasHook, decision.action);
    const c = this.#cat;
    if (action === c.pact.use) return { ready: true };
    if (action === c.pact.fetch) {
      try {
        await this.#fetch(info, false);
        return { ready: true };
      } catch (e) {
        return { unread: e.message };
      }
    }
    if (action === c.pact.provided) {
      try {
        await this.install(info.id, decision.data);
        return { ready: true };
      } catch (e) {
        return { unread: e.message };
      }
    }
    if (action === c.pact.substitute) return { substitute: decision.engine ?? '' };
    if (asks && decision.action === ACTION.fetch && !this.#fetcher) {
      const error = `pack-fetch-unavailable:${info.id}`;
      this.#notes(error);
      return { unread: error };
    }
    return { unread: `pack-missing:${info.id}` };
  }

  /** The packs of `ids` (names), fetched now: progress and cancel through the events. Only when the mode lets anything be fetched. */
  async ensure(ids) {
    if (this.offline) throw new Error('EMON_OFFLINE=1: nothing is fetched');
    if (this.#mode !== this.#cat.pfetch.auto) throw new Error('the pack mode does not fetch (it is `never` or `cache`)');
    for (const id of ids) {
      const info = this.#info(id);
      if (!info) throw new Error(`pack-missing:${id}`);
      if (this.files(info.id)) continue;
      await this.#fetch(info, true);
    }
  }

  async #fetch(info, app) {
    // one fetch at a time: a second conversion needing the same pack waits for the first and finds it
    const turn = this.#fetching;
    let release;
    this.#fetching = new Promise((r) => (release = r));
    await turn;
    try {
      if (this.files(info.id)) return;
      if (!this.#fetcher) {
        const error = `pack-fetch-unavailable:${info.id}`;
        this.#notes(error);
        throw new Error(error);
      }
      this.#cancel = false;
      this.#emit('start', info, 0, info.bytes, app);
      let base = 0;
      const staged = new Map();
      for (const f of info.files) {
        let cancelled = this.#cancel;
        let received = 0;
        let bytes;
        let failure = null;
        try {
          const got = await this.#fetcher(info, f, (done) => {
            done = Math.min(done, f.bytes);
            if (done > received) {
              received = done;
              cancelled = this.#emit('progress', info, base + done, info.bytes, app) || cancelled;
            }
            cancelled = this.#cancel || cancelled;
            return cancelled;
          });
          bytes = bytesOf(got);
          if (!bytes) throw new Error('the fetcher gave no bytes');
        } catch (e) {
          failure = e;
        }
        if (cancelled || this.#cancel) {
          this.#emit('cancelled', info, base, info.bytes, app);
          const error = `pack-fetch-cancelled:${info.id}`;
          this.#notes(error);
          throw new Error(error);
        }
        if (failure) {
          const error = `pack-fetch-failed:${info.id}: ${failure.message ?? failure}`;
          this.#notes(error);
          this.#emit('failed', info, base, info.bytes, app, error);
          throw new Error(error);
        }
        if (!(await this.#matches(f, bytes))) {
          const error = `pack-hash-mismatch:${info.id}`;
          this.#notes(error);
          this.#emit('failed', info, base, info.bytes, app, error);
          throw new Error(error);
        }
        staged.set(f.name, bytes.slice());
        base += f.bytes;
      }
      this.#files.set(info.id, staged);
      this.#emit('done', info, info.bytes, info.bytes, app);
    } finally {
      release();
    }
  }
}
