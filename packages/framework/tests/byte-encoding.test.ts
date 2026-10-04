import {expect,test} from 'bun:test';
import {bytesToBase64} from '../src/services.ts';
test('native Base64 encoding preserves empty output, views and fallback on older hosts',()=>{
 const root=globalThis as {pod?:{encodeBase64?:(bytes:Uint8Array)=>string|null|undefined}};const saved=root.pod;
 try{
  const backing=Uint8Array.from({length:262160},(_,i)=>i&255);
  for(const offset of [0,1,7])for(const length of [0,1,2,3,255,256,65536,262144]){
   const bytes=backing.subarray(offset,offset+length),expected=Buffer.from(bytes).toString('base64');
   root.pod={};expect(bytesToBase64(bytes)).toBe(expected);
   let calls=0;root.pod={encodeBase64:value=>{calls++;expect(value).toBe(bytes);return Buffer.from(value).toString('base64');}};
   expect(bytesToBase64(bytes)).toBe(expected);expect(calls).toBe(1);
   root.pod={encodeBase64:()=>null};expect(bytesToBase64(bytes)).toBe(expected);
  }
  root.pod={encodeBase64:()=>''};expect(bytesToBase64(new Uint8Array([1]))).toBe('');
 }finally{root.pod=saved;}
});
