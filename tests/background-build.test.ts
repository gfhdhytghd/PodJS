import { expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { runInNewContext } from "node:vm";
import { buildBackground } from "../packages/cli/src/background-build.ts";

function fixture(source: string) {
  const root=mkdtempSync(join(tmpdir(),"pod-background-build-"));
  const entry=join(root,"handler.ts"); writeFileSync(entry,source);
  writeFileSync(join(root,"helper.ts"),"export const outcome = 'success' as const;");
  return { root, entry };
}
test("builds a separate headless IIFE with exact artifact hash",async () => {
  const {root,entry}=fixture("import { outcome } from './helper.ts'; export default async (job: {taskId: string}) => job.taskId === 'refresh' ? outcome : 'failure';");
  const artifacts=await buildBackground({refresh:entry},root);
  const artifact=artifacts.refresh!;
  const bytes=readFileSync(join(root,artifact.file));
  expect(artifact.bytes).toBe(bytes.length);
  expect(artifact.sha256).toBe(createHash("sha256").update(bytes).digest("hex"));
  expect(artifact.file).toMatch(/^background\/[a-f0-9]{64}\.js$/);
  const context: Record<string,any>={}; runInNewContext(bytes.toString(),context);
  expect(context.frame).toBeUndefined();
  expect(await context.backgroundHandler({taskId:"refresh"})).toBe("success");
});
test("rejects UI and unrestricted imports and static dynamic imports",async () => {
  for (const source of ["import { Text } from '@podjs/framework'; export default () => Text;","import fs from 'node:fs'; export default () => fs;","export default async () => import('./helper.ts');"]) {
    const {root,entry}=fixture(source);
    await expect(buildBackground({refresh:entry},root)).rejects.toThrow("Background");
  }
});
test("empty background configuration emits no phantom handlers",async () => {
  expect(Object.keys(await buildBackground({},tmpdir()))).toEqual([]);
});

test("headless handlers can use pure SQL/regexp helpers without gaining UI or host imports",async()=>{
 const {root,entry}=fixture("import {querySqlPages,readSqlText} from '@podjs/framework/sql'; import {lazyRegExpTest} from '@podjs/framework/regexp'; export default async()=>({rows:await querySqlPages({query:async()=>[{id:7}]},'SELECT id'),text:await readSqlText({query:async()=>[]},'SELECT value'),match:lazyRegExpTest('^ok$','')('ok')});");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({helper:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.helper!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(await context.backgroundHandler()))).toEqual({rows:[{id:7}],text:null,match:true});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 for(const denied of ['@podjs/framework/variable-list','@podjs/framework/text-layout','@podjs/framework/text-environment','@podjs/framework/services','@podjs/framework/regexp/unsafe']){
  const f=fixture(`import * as api from '${denied}'; export default ()=>api;`);await expect(buildBackground({denied:f.entry},f.root)).rejects.toThrow('Background');
 }
});


test("headless JSON numeric tokens execute without a host and keep UI denied",async()=>{
 const {root,entry}=fixture("import {parseJsonObjectNumbers,jsonNumberToken,isJsonInteger} from '@podjs/framework/json-numbers'; export default ()=>{const obj=parseJsonObjectNumbers('{\"i\":1,\"d\":1.0}');return {i:isJsonInteger(obj,'i'),d:isJsonInteger(obj,'d'),token:jsonNumberToken(obj,'d')};};");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({numbers:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.numbers!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(await context.backgroundHandler()))).toEqual({i:true,d:false,token:'1.0'});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 for(const denied of ['@podjs/framework/startup-overlay','@podjs/framework/startup','@podjs/framework/logging','@podjs/framework/services','@podjs/framework/json-numbers/unsafe']){
  const f=fixture(`import * as api from '${denied}'; export default ()=>api;`);await expect(buildBackground({denied:f.entry},f.root)).rejects.toThrow('Background');
 }
});

test("headless build gate executes once without host bootstrap and rejects other U10 entrypoints",async()=>{
 const {root,entry}=fixture("import {FailClosedGate} from '@podjs/framework/build-gate'; export default async()=>{const gate=new FailClosedGate(value=>value===true);const before=gate.allowed;const first=gate.initialize(async()=>true);const same=gate.initialize(async()=>false)===first;await first;return {before,same,allowed:gate.allowed};};");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({gate:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.gate!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(await context.backgroundHandler()))).toEqual({before:false,same:true,allowed:true});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 for(const denied of ['@podjs/framework/build-gate/unsafe','@podjs/framework/private-response','@podjs/framework/preview-session','@podjs/framework/media-volume','@podjs/framework/text-match','@podjs/framework/services','@podjs/framework/startup-overlay']){
  const f=fixture(`import * as api from '${denied}'; export default ()=>api;`);await expect(buildBackground({denied:f.entry},f.root)).rejects.toThrow('Background');
 }
});

test("headless Kotlin whitespace compatibility executes without host bootstrap and keeps unsafe subpaths denied",async()=>{
 const {root,entry}=fixture("import {kotlinTrim} from '@podjs/framework/text-compat'; export default ()=>({control:kotlinTrim('\\u001c x \\u001f'),bom:kotlinTrim('\\ufeffx\\ufeff')});");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({trim:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.trim!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(context.backgroundHandler()))).toEqual({control:'x',bom:'\ufeffx\ufeff'});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 const denied=fixture("import * as api from '@podjs/framework/text-compat/unsafe'; export default ()=>api;");await expect(buildBackground({denied:denied.entry},denied.root)).rejects.toThrow('Background');
});


test('headless opaque frame facade runs with caller codecs and no host bootstrap',async()=>{
 const {root,entry}=fixture("import {createFrameFacade} from '@podjs/framework/frame-stream'; export default ()=>{const f=createFrameFacade({maxFrameBytes:8,encodeBody:v=>v,decodeBody:v=>v,encodeBodyAsync:async(v)=>v,decodeBodyAsync:async(v)=>v});const bytes=f.encodeFrame(Uint8Array.of(0,255));const decoder=new f.FrameDecoder();return {bytes:[...bytes],body:[...decoder.push(bytes)[0]]};};");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({frames:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.frames!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(context.backgroundHandler()))).toEqual({bytes:[0,0,0,2,0,255],body:[0,255]});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 for(const denied of ['@podjs/framework/frame-stream/unsafe','@podjs/framework/texture-upload']){
  const f=fixture(`import * as api from '${denied}'; export default ()=>api;`);await expect(buildBackground({denied:f.entry},f.root)).rejects.toThrow('Background');
 }
});


test('headless crypto uses caller request ports without bootstrapping the unrestricted host',async()=>{
 const {root,entry}=fixture(`import {lengthPrefix,hex,decimalCode,createCrypto} from '@podjs/framework/crypto';
 export default async()=>{const calls=[];const crypto=createCrypto(async(method,args)=>{calls.push(method);return method==='crypto.verifyP256'?{valid:true}:{digestBase64:'AQI='};});return{lp:hex(lengthPrefix('x',1)),code:decimalCode(new Uint8Array(32)),hmac:[...await crypto.hmacSha256(Uint8Array.of(1),Uint8Array.of(2))],valid:await crypto.verifyP256(new Uint8Array(),new Uint8Array(),new Uint8Array(64)),short:await crypto.verifyP256(new Uint8Array(),new Uint8Array(),new Uint8Array(63)),calls};};`);
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({crypto:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.crypto!.file),'utf8'),context);
 expect(JSON.parse(JSON.stringify(await context.backgroundHandler()))).toEqual({lp:'00000001780000000131',code:'00000000',hmac:[1,2],valid:true,short:false,calls:['crypto.hmacSha256','crypto.verifyP256']});expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 for(const denied of ['@podjs/framework/crypto/unsafe','@podjs/framework/services','@podjs/framework/watch']){const f=fixture(`import * as api from '${denied}';export default ()=>api;`);await expect(buildBackground({denied:f.entry},f.root)).rejects.toThrow('Background');}
});

test('headless changed-row SQL helper bundles without services and keeps unsafe subpaths denied',async()=>{
 const {root,entry}=fixture("import {onlyChangedSql} from '@podjs/framework/sql-changes';export default ()=>onlyChangedSql('UPDATE things SET value=? WHERE id=?');");
 mkdirSync(join(root,'node_modules/@podjs'),{recursive:true});symlinkSync(new URL('../packages/framework',import.meta.url).pathname,join(root,'node_modules/@podjs/framework'),'dir');
 const artifacts=await buildBackground({sql:entry},root),context:Record<string,any>={};runInNewContext(readFileSync(join(root,artifacts.sql!.file),'utf8'),context);
 expect(context.backgroundHandler()).toContain('IS NOT');expect(context.pod).toBeUndefined();expect(context.frame).toBeUndefined();
 const denied=fixture("import * as api from '@podjs/framework/sql-changes/unsafe';export default ()=>api;");await expect(buildBackground({denied:denied.entry},denied.root)).rejects.toThrow('Background');
});
