// Type declarations of `@3sln/emon/tesseract` (P11.4, D542).
import type { OcrPort } from './index.js';

export function tesseractPort(options: {
  /** tesseract.js's `createWorker` */
  createWorker: (...args: any[]) => Promise<any>;
  /** the models the engine loads (tesseract.js's names); default `['eng']` */
  languages?: string[];
  /** the engine mode (1: LSTM, the default) */
  oem?: number;
  workerOptions?: object;
  /** workers per language set (default 1) */
  workers?: number;
}): OcrPort & { terminate(): Promise<void>; producer: Record<string, string> };
