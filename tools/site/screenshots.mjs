// Visual review uses the same headless Chrome as the demo check.
// node tools/site/screenshots.mjs --site DIR --out SHOTS
import fs from 'node:fs/promises';
import path from 'node:path';
import {serve} from '../demo/serve.mjs';
import {launch} from '../../test/driver/wasm/browser.mjs';
const arg=(key,fallback)=>process.argv.includes(key)?process.argv[process.argv.indexOf(key)+1]:fallback;
const destination=path.resolve(arg('--out','.cache/site/shots'));
await fs.mkdir(destination,{recursive:true});
const server=await serve(arg('--site','.cache/site/out'));
let browser;
try{
 browser=await launch();
 for(const [name,width,dark,url] of [
  ['landing-light',1440,false,'/'],['landing-dark',1440,true,'/'],['landing-mobile',390,false,'/'],
  ['demo-light',1440,false,'/demo/'],['demo-dark',1440,true,'/demo/'],['demo-mobile',390,false,'/demo/']]){
  const page=await browser.newPage();
  await page.setViewport({width,height:1000,deviceScaleFactor:1});
  await page.emulateMediaFeatures([{name:'prefers-color-scheme',value:dark?'dark':'light'}]);
  await page.goto(server.origin+url,{waitUntil:'networkidle0'});
  if(url==='/demo/')await page.waitForFunction(()=>window.__demo!==undefined);
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  if(overflow)throw Error(name+': horizontal page overflow');
  await page.screenshot({path:path.join(destination,name+'.png'),fullPage:true});
  console.log('ok screenshot '+path.join(destination,name+'.png'));
  await page.close();
 }
}finally{await browser?.close();await server.close();}
