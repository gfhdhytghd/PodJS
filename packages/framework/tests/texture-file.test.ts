import {afterEach,expect,test} from "bun:test";
import {hasTextureFileUpload,uploadTextureFile} from "../src/services.ts";
const original=(globalThis as any).pod;
afterEach(()=>{(globalThis as any).pod=original;});
test("default host path distinguishes unavailable, rejected and accepted uploads",()=>{
 delete (globalThis as any).pod;expect(hasTextureFileUpload()).toBe(false);expect(uploadTextureFile("x",2,1,3)).toBeNull();
 for(const result of [-1,0,53,NaN,1.5,undefined,null]) {
  const calls:unknown[]=[];(globalThis as any).pod={uploadTextureFile:(...args:unknown[])=>{calls.push(args);return result;}};
  expect(hasTextureFileUpload()).toBe(true);
  expect(uploadTextureFile("x",2,1,3)).toBe(typeof result==="number"&&Number.isInteger(result)?result:-1);
  expect(calls).toEqual([["x",2,1,3]]);
 }
});
test("supporting host errors propagate after ownership transfer",()=>{
 (globalThis as any).pod={uploadTextureFile:()=>{throw new Error("host failure");}};
 expect(()=>uploadTextureFile("x",2,1,3)).toThrow("host failure");
});
