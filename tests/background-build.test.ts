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
