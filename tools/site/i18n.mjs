#!/usr/bin/env node
// Extraction uses bab's binding-aware parser; data messages are merged explicitly.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
const require = createRequire(resolve('site/package.json'));
const { extract, formatJSON, formatPOT } = await import(pathToFileURL(require.resolve('@3sln/bab-extract')));
const readJSON = async file => JSON.parse(await readFile(file, 'utf8'));
function edn(file) {
  const result = spawnSync('bb', ['-e', `(require '[clojure.edn :as edn] '[cheshire.core :as json]) (print (json/generate-string (edn/read-string (slurp ${JSON.stringify(file)}))))`], { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr || `Cannot read ${file}`);
  return JSON.parse(result.stdout);
}
export function pluralCategories(locale) {
  const rule = new Intl.PluralRules(locale);
  return [...new Set(Array.from({length:10001}, (_, n) => rule.select(n)))].sort();
}
export async function sourceMessages() {
  const result = await extract({ include:['site/pages/**/*.js', 'demo/*.js'], modules:['@3sln/bab','bab','./vendor/bab/index.js'], cwd:process.cwd() });
  if (result.warnings.length) throw new Error(`Strict extraction failed:\n${result.warnings.map(w => typeof w === 'string' ? w : JSON.stringify(w)).join('\n')}`);
  const messages = [...result.messages];
  for (const sample of edn('samples/catalog.edn')) {
    for (const field of ['group','title','note','look-at']) {
      const id = sample[field];
      if (id && !messages.some(m => m.scope === 'samples' && m.id === id)) messages.push({scope:'samples', id, plural:false, singular:null, comments:[`Sample ${sample.id}: ${field}`], references:[{file:'samples/catalog.edn',line:1,column:1}]});
    }
  }
  for (const install of edn('site/install.edn')) {
    const id = install.requirement;
    if (id && !messages.some(m => m.scope === 'landing' && m.id === id)) messages.push({scope:'landing',id,plural:false,singular:null,comments:[`Install ${install.id}: requirement`],references:[{file:'site/install.edn',line:1,column:1}]});
  }
  return messages.sort((a,b) => a.scope.localeCompare(b.scope) || a.id.localeCompare(b.id));
}
function shape(messages, locale) {
  const out = {};
  for (const m of messages) (out[m.scope] ??= {})[m.id] = m.plural ? Object.fromEntries(pluralCategories(locale).map(c => [c,null])) : null;
  return out;
}
const signature = (text, regex) => [...new Set(text.match(regex) ?? [])].sort().join('\u0000');
const escapeRE = s => s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
function hasTerm(text, term) {
  // Word boundaries apply to Latin source terms, but not to the surrounding CJK translation.
  return new RegExp(`(?<![A-Za-z0-9_])${escapeRE(term)}(?![A-Za-z0-9_])`, 'u').test(text);
}
export function validateCatalogue(messages, catalogue, locale, glossary) {
  const errors = [], warnings = [], expected = shape(messages, locale);
  for (const [scope, entries] of Object.entries(catalogue)) for (const key of Object.keys(entries)) if (!(key in (expected[scope] ?? {}))) errors.push(`${locale}/${scope}: stale key ${key}`);
  for (const message of messages) {
    const {scope,id} = message, entry = catalogue[scope]?.[id];
    const values = message.plural ? pluralCategories(locale).map(category => [category, entry?.[category]]) : [['',entry]];
    if (message.plural && (typeof entry !== 'object' || !entry || Array.isArray(entry))) errors.push(`${locale}/${scope}: expected plural entry ${id}`);
    for (const [category, value] of values) {
      const label = `${locale}/${scope}: ${id}${category ? ` [${category}]` : ''}`;
      if (typeof value !== 'string' || !value.trim()) {errors.push(`${label}: missing, null or empty translation`);continue;}
      for (const [name,re] of [['placeholders',/\{(?:#|[A-Za-z][\w-]*)\}/g],['backtick spans',/`[^`]+`/g]]) {
        if (signature(id,re) !== signature(value,re)) errors.push(`${label}: ${name} differ`);
      }
      for (const term of glossary['do-not-translate'] ?? []) if (hasTerm(id,term) && !hasTerm(value,term)) errors.push(`${label}: missing protected term ${term}`);
      if (value === id && !(glossary['identical-allowed'] ?? []).includes(id)) warnings.push(`${label}: identical to English`);
    }
  }
  return {errors,warnings};
}
export async function run(mode) {
  const languages = edn('site/i18n/languages.edn').filter(l => l.code !== 'en');
  const messages = await sourceMessages();
  await mkdir('.cache/site',{recursive:true});
  await writeFile('.cache/site/messages.json',formatJSON(messages,{locale:'en'}));
  if (mode === '--extract') {
    await writeFile('site/i18n/messages.pot',formatPOT(messages));
    for (const {code} of languages) {
      const file = `site/i18n/${code}.json`, next = shape(messages,code);
      let old = {}; try { old = await readJSON(file); } catch(e) { if(e.code !== 'ENOENT')throw e; }
      for (const [scope,entries] of Object.entries(next)) for (const [id,entry] of Object.entries(entries)) {
        const previous = old[scope]?.[id];
        if (entry && typeof entry === 'object') for (const category of Object.keys(entry)) entry[category] = typeof previous?.[category] === 'string' ? previous[category] : null;
        else next[scope][id] = typeof previous === 'string' ? previous : null;
        if (previous == null) console.log(`NEW ${code}/${scope}: ${id}`);
      }
      for (const [scope,entries] of Object.entries(old)) for (const id of Object.keys(entries)) if (!(id in (next[scope] ?? {}))) console.log(`STALE ${code}/${scope}: ${id}`);
      await writeFile(file,JSON.stringify(next,null,2)+'\n');
    }
    console.log(`ok i18n: extracted ${messages.length} messages, wrote POT and ${languages.length} catalogues`);
  } else if (mode === '--check') {
    const glossary = edn('site/i18n/glossary.edn'); let errors = [];
    for (const {code} of languages) {
      try {const result=validateCatalogue(messages,await readJSON(`site/i18n/${code}.json`),code,glossary);errors.push(...result.errors);for(const warning of result.warnings)console.log(`WARN ${warning}`);}
      catch(e){errors.push(`${code}: ${e.message}`);}
    }
    if(errors.length) throw new Error(errors.join('\n'));
    console.log(`ok i18n: ${messages.length} messages complete in all ${languages.length} translations`);
  } else throw new Error('usage: node tools/site/i18n.mjs --extract | --check');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) run(process.argv[2] ?? '--check').catch(error => {console.error(`FAIL i18n: ${error.message}`);process.exitCode=1;});
