// A headless Chrome for the harness (DEVLOG P9.3): found on the machine (EMON_CHROME, then the usual places), driven by
// puppeteer-core, which is the repository's own devDependency (`host/js/package.json`, pinned). Nothing is downloaded at run time.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { repo } from './server.mjs';
import { execFileSync } from 'node:child_process';

export function findChrome() {
  const cands = [process.env.EMON_CHROME, '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/Applications/Chromium.app/Contents/MacOS/Chromium'].filter(Boolean);
  for (const n of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser', 'chrome']) {
    try {
      cands.push(execFileSync('which', [n], { encoding: 'utf8' }).trim());
    } catch (_) {
      /* not on PATH */
    }
  }
  return cands.find((c) => c && fs.existsSync(c)) || null;
}

export function puppeteer() {
  const req = createRequire(path.join(repo, 'host', 'js', 'package.json'));
  return req('puppeteer-core');
}

export async function launch() {
  const exe = findChrome();
  if (!exe) throw new Error('no Chrome or Chromium (set EMON_CHROME)');
  return puppeteer().launch({
    executablePath: exe,
    headless: true,
    // a page that is not in front must not have its timers throttled to one a second: the harness awaits macrotasks
    args: ['--enable-precise-memory-info', '--js-flags=--expose-gc', '--no-first-run', '--disable-gpu', '--disable-extensions', '--no-default-browser-check', '--disable-background-timer-throttling', '--disable-renderer-backgrounding', '--disable-backgrounding-occluded-windows'],
  });
}

/** A page of the harness, loaded and ready (`window.H`). */
export async function harnessPage(browser, origin) {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  await page.bringToFront();
  await page.goto(origin + '/', { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction('window.__ready === true', { timeout: 60000 });
  page.errors = errors;
  return page;
}
