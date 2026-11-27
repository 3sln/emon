// Type declarations of `@3sln/emon/tesseract` (P11.4, D542).
import type { OcrAdapter } from './index.js';

/** tesseract.js as a registered adapter (D925): `await emon.engines().add(tesseractAdapter({ createWorker }))`. */
export function tesseractAdapter(options: {
  /** tesseract.js's `createWorker` */
  createWorker: (...args: any[]) => Promise<any>;
  /** the models the engine loads (tesseract.js's names); default `['eng']` */
  languages?: string[];
  /** the engine mode (1: LSTM, the default) */
  oem?: number;
  workerOptions?: object;
  /** workers per language set (default 1) */
  workers?: number;
}): OcrAdapter & { name: 'tesseract'; engine: 1; producer: Record<string, string>; close(): Promise<void> };
