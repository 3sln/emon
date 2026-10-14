// Type declarations of `@3sln/emon` (P11.4, D1480). They are written by hand beside `index.js` and describe what `api/surface.edn` freezes: the names
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
export function toSource(input: Input, options?: { lazy?: boolean; max?: number }): Promise<ByteSource>;

/** A request for the OCR port (`ports.ocr`): see `@3sln/emon/tesseract`. */
export interface OcrRequest {
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
  furniture?: 'policy' | 'keep' | 'drop' | string;
  assets?: 'none' | 'images' | 'figures' | string;
  assetDpi?: number;
  assetPixels?: number;
  assetBytes?: number;
  assetsBytes?: number;
  assetSignatures?: boolean | 'on' | 'off';
  ocrScope?: 'full' | 'witness' | string;
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
  convert(input: Input | InputWithPassword, options?: ConvertOptions): Promise<Model>;
  /** Stage 0 of each input, cheap. */
  intake(inputs: Input | InputWithPassword | Array<Input | InputWithPassword>, options?: ConvertOptions): Promise<IntakeResult[]>;
}
