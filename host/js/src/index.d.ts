// Type declarations of `@3sln/emon` (P11.4, D542). They are written by hand beside `index.js` and describe what `api/surface.edn` freezes: the names
// below are the package's exports, `bb package --check` fails when `index.js` exports a name this file does not declare (and the other way
// round). The WASM instance, a Model's handle and the constructors of `Emon`, `Model` and `Asset` are not API and are not declared.

/** A byte source: the engine reads only what it needs of it, a chunk at a time. `bytes` is there when the document is already in memory. */
export interface ByteSource {
  readonly size: number;
  read(offset: number, length: number): Promise<Uint8Array | ArrayBuffer | ArrayBufferView>;
  bytes?: Uint8Array;
}

/** Anything `toSource` takes: an ArrayBuffer or view, a Blob or File, a URL (a string with a scheme, or a `URL`), a `ReadableStream`, or a `ByteSource`. */
export type Input = ArrayBuffer | ArrayBufferView | Blob | URL | string | ReadableStream<Uint8Array> | ByteSource;

/** An input with the password of an encrypted document. */
export interface InputWithPassword {
  source: Input;
  password?: string | Uint8Array;
}

export class SourceChangedError extends Error {
  constructor(message: string);
  readonly code: 'source-changed';
}

/** A named emon failure: `code` is the stable name a crawler routes on (`password-required`, `input-bytes`, ...). */
export class EmonError extends Error {
  constructor(code: string, message?: string);
  readonly code: string;
}

export function bytesSource(buf: ArrayBuffer | ArrayBufferView, options?: { lazy?: boolean }): ByteSource;
export function blobSource(blob: Blob): ByteSource;
export function streamSource(stream: ReadableStream<Uint8Array>, options?: { max?: number }): Promise<ByteSource>;
export function urlSource(
  url: string | URL,
  options?: { fetch?: typeof fetch; headers?: Record<string, string>; signal?: AbortSignal; fetchOptions?: RequestInit },
): Promise<ByteSource>;
export function toSource(input: Input | readonly Input[], options?: { lazy?: boolean; max?: number }): Promise<ByteSource>;

/** A request for the OCR port (`ports.ocr`): see `@3sln/emon/tesseract`. */
export interface OcrRequest {
  engine?: number;
  configuration?: string;
  record?: Int32Array;
  id: number;
  psm: number;
  flags: number;
  dpi: number;
  rect: { x: number; y: number; w: number; h: number } | null;
  langs: string[];
  symbols: boolean;
  osd: boolean;
  bitmap(): Uint8Array;
  [k: string]: unknown;
}

/** An OCR port: `null` or `undefined` answers `unsupported`; a rejection answers `failed`. */
export type OcrPort = (request: OcrRequest) => Promise<object | null | undefined>;

/**
 * An OCR engine, registered with `emon.engines().add(...)` (D925): the OIL engine it answers as (1 tesseract, 2 ppocr, 3 apple-vision, 4 mlkit-text, 5 windows-media-ocr), what it
 * declares, and `read`, in the OCR port's form. `@3sln/emon/tesseract` and `@3sln/emon/ppocr` make them.
 */
export interface OcrAdapter {
  name?: string;
  engine: number;
  caps?: number;
  measured?: number;
  /** the pack kinds it reads with: 1 recognizer, 2 osd, 4 detector, 8 classifier */
  needs?: number;
  /** 2 sends the pixels off the device (refused unless `allowRemote(true)`); 1 (async) is refused */
  flags?: number;
  /** true: the adapter reads its models from packs (`engines.packs`): a request that needs one the app has not provided or fetched is left unread (`pack-missing:<id>`), and `read`'s second argument holds the verified files. Default false: the adapter brings its own models. */
  packs?: boolean;
  producer?: Record<string, string>;
  read(request: OcrRequest, context?: { packs: Map<string, Uint8Array> }): Promise<object | null | undefined>;
  close?(): void | Promise<void>;
}

/** What the library tells the app's hook about a pack a conversion needs. */
export interface PackNeed { pack: string; rev: number; engine: string; kind: string; licence: string; bytes: number; reason: string; requestEngine: string }
/** What the hook decides: fetch (the app's `fetcher`), provide bytes (a one-file pack) or `{ [fileName]: bytes }`, decline (unread, `pack-missing:<id>`), or substitute another adapter of the same engine. */
export type PackDecision = { action: 'fetch' } | { action: 'provide'; data: Uint8Array | Record<string, Uint8Array> } | { action: 'decline' } | { action: 'substitute'; engine: string };
export interface PackFile { name: string; bytes: number; sha: string }
export interface PackEvent { kind: 'start' | 'progress' | 'done' | 'failed' | 'cancelled'; pack: string; engine: string; done: number; total: number; initiator: 'library' | 'app'; message: string }
/** Packs (D896, P13.16): verified model bytes, the app's hook, download and progress events. The library ships no HTTP client: `setFetcher` is the app's download. */
export interface Packs {
  readonly catalogue: { class: string; packs: Array<{ code: number; id: string; rev: number; engine: string; kind: string; licence: string; bytes: number; files: PackFile[] }> };
  setMode(mode: 'never' | 'cache' | 'auto'): void;
  readonly mode: 'never' | 'cache' | 'auto';
  /** `EMON_OFFLINE=1` in Node. */
  readonly offline: boolean;
  onPackNeeded(hook: ((need: PackNeed) => PackDecision | Promise<PackDecision>) | null): void;
  setFetcher(fetcher: ((pack: unknown, file: PackFile, progress: (done: number) => boolean) => Promise<Uint8Array>) | null): void;
  onPackEvent(listener: (event: PackEvent) => boolean | void): () => void;
  events(signal?: AbortSignal): AsyncIterable<PackEvent>;
  cancel(): void;
  /** Verifies and keeps the app's bytes for a pack; rejects `pack-hash-mismatch:<id>` / `pack-provide-failed:<id>`. */
  install(id: string, data: Uint8Array | Record<string, Uint8Array>): Promise<void>;
  /** Fetches packs ahead of time (mode `auto`, not offline, a fetcher set). */
  ensure(ids: string[]): Promise<void>;
  installed(): number[];
  files(id: string): Map<string, Uint8Array> | null;
}

/** The engine registry: kin's rules (`ad-register-check`, `ad-route`) over the adapters you register. */
export class Engines {
  /** Lets adapters that send the pixels off the device register (default no). Call it before registering them. */
  allowRemote(allow: boolean): void;
  /** The pack mode, hook, download and events. */
  readonly packs: Packs;
  /** D896's `engines()`: every engine of kin's table with its state and why (the C ABI's JSON shape). */
  state(options?: { renderer?: boolean }): { platform: string; mode: string; offline: boolean; store: string; engines: Array<{ name: string; kind: string; impl: string; state: string; registered: boolean; calibrated: boolean; caps: number; oen: number; role: string; needs: Array<{ id: string; kind: string; bytes: number; licence: string; installed: boolean }>; download_bytes: number; reason: string }> };
  /** D896's `prepare(options)`: the packs a conversion needs and which are missing (the C ABI's JSON shape). */
  prepare(options?: { ocr?: string; ocrEngine?: string; locale?: string }): { verdict: string; mode: string; offline: boolean; engines: string[]; languages: string[]; osd: boolean; packs: object[]; unsupported: object[]; missing: number; missing_bytes: number; conditional: number; conditional_bytes: number };
  /** Registers an adapter, held to kin's engine table; rejects with `usage` and the refusal's name (`adapter-engine-unknown`, `adapter-caps-beyond-engine`, ...). */
  add(adapter: OcrAdapter): Promise<void>;
  describe(): Array<{ name: string; engine: number; caps: number; measured: number; needs: number; flags: number; producer: Record<string, string> }>;
  /** Why requests were left unread since the last call. */
  notes(): string[];
  producer(): Record<string, string>;
  /** The `ports.ocr` of a conversion: routes each request by the engine it names. */
  port(): OcrPort & { terminate(): Promise<void>; readonly producer: Record<string, string> };
  close(): Promise<void>;
}

/** One page of a renderer's document. */
export interface Rendered {
  width: number;
  height: number;
  /** RGB, 3 bytes a pixel, row-major. */
  rgb: Uint8Array;
}

export interface RendererDocument {
  render(
    request: { page: number; dpi: number; layer: number },
    sink?: (bytes: number) => Uint8Array,
  ): Promise<Rendered | { status: 'failed' | 'unsupported'; reason?: string }>;
  close(): void;
}

/** A renderer (`ports.render`): see `@3sln/emon/pdfium`. */
export interface Renderer {
  /** the layers it can separate, a bit set (bit n = layer n) */
  layers?: number;
  producer?: Record<string, string>;
  open(
    doc: { size: number; bytes?: Uint8Array; read(offset: number, length: number): Promise<Uint8Array>; password?: Uint8Array },
    limits: { pixels: number },
  ): Promise<RendererDocument>;
  terminate?(): Promise<void>;
}

/** SPEC 13's settings by their names. */
export interface ConvertOptions {
  locale?: string;
  ocr?: 'always' | 'auto' | 'never' | string;
  /** Experimental PP-OCR needs the optional wasm build and @3sln/emon/ppocr port. Tesseract is the default. */
  ocrEngine?: 'tesseract' | 'ppocr' | string;
  furniture?: 'policy' | 'keep' | 'drop' | string;
  assets?: 'none' | 'images' | 'figures' | string;
  assetDpi?: number;
  assetPixels?: number;
  assetBytes?: number;
  assetsBytes?: number;
  assetSignatures?: boolean | 'on' | 'off';
  ocrScope?: 'full' | 'witness' | string;
  capture?: 'auto' | 'scan' | 'photos';
  keepEvidence?: boolean;
  maxRounds?: number;
  fetchRounds?: number;
  /** kin's limits by name (`{ 'input-bytes': n, ... }`) */
  limits?: Record<string, number>;
  producer?: Record<string, string>;
  /** the byte source's chunk size (default 64 KiB) */
  chunk?: number;
  /** read even an in-memory input by range */
  lazy?: boolean;
  ports?: { ocr?: OcrPort; render?: Renderer };
  /** replay: a Map (or entries, or an object) of transcript file name to bytes; no port is called */
  transcript?: Map<string, Uint8Array | ArrayBuffer> | Array<[string, Uint8Array | ArrayBuffer]> | Record<string, Uint8Array | ArrayBuffer>;
  /** the Model then has `transcript()` */
  record?: boolean;
}

export interface HtmlOptions {
  ids?: boolean;
  geometry?: boolean;
  /** 0..1000 */
  confidenceBelow?: number;
  ledger?: boolean;
  pretty?: boolean;
  furnitureDrop?: boolean;
  milestones?: boolean;
  initialsPlain?: boolean;
  alignStyle?: boolean;
  assets?: 'inline' | 'external' | 'omit';
  assetPrefix?: string;
}

export interface ConvertStats {
  attempts: number;
  fetchRounds: number;
  bytesRequests: number;
  requests: number;
  sourceReads: number;
  bytesFetched: number;
  inputBytes: number;
  reads: number;
  bytesRead: number;
  notes: string[];
}

export interface IntakeResult {
  format: string;
  version: [number, number];
  pages: number;
  encrypted: boolean;
  bytes: number;
  line: string;
}

export class Asset {
  /** the position in `model.assets` */
  readonly index: number;
  /** the file name, `a<id>.jpg` or `a<id>.png` */
  readonly name: string;
  /** `image/jpeg` or `image/png` */
  readonly type: string;
  /** the encoded size in bytes */
  readonly size: number;
  /** The encoded bytes (a copy). */
  bytes(): Uint8Array;
  blob(): Blob;
}

export class Model {
  readonly stats: ConvertStats;
  /** SPEC 4: emon JSON, as text. */
  json(options?: { pretty?: boolean }): string;
  /** The JSON, parsed. */
  object(): any;
  /** SPEC 5: HTML. */
  html(options?: HtmlOptions): string;
  htmlWithBlobURLs(options?: HtmlOptions): { html: string; urls: Map<string, string>; revoke(): void };
  evidence(): string;
  readonly assets: Asset[];
  transcript(): Map<string, Uint8Array>;
  /** Frees the Model's WASM memory. */
  dispose(): void;
  [Symbol.dispose](): void;
}

export class Emon {
  /** @param wasm the module (`emon.wasm`, shipped in this package as `@3sln/emon/emon.wasm`): bytes, a compiled module, a Response or a URL */
  static load(
    wasm: ArrayBuffer | Uint8Array | WebAssembly.Module | Response | Promise<Response> | string | URL,
    defaults?: { chunk?: number },
  ): Promise<Emon>;
  readonly defaults: { chunk?: number };
  convert(input: Input | InputWithPassword | readonly Input[], options?: ConvertOptions): Promise<Model>;
  /** An empty engine registry bound to this module; register each OCR engine with `add`, then pass `engines.port()` as `ports.ocr`. */
  engines(): Engines;
  /** Stage 0 of each input, cheap. */
  intake(inputs: Input | InputWithPassword | Array<Input | InputWithPassword>, options?: ConvertOptions): Promise<IntakeResult[]>;
}
