import {expect,test} from 'bun:test';
import {encodeUtf8,encodeBase64,decodeBase64} from '../src/byte-codecs.ts';
import {encodeUtf8Async,encodeBase64Async,decodeBase64Async,decodeUtf8Async} from '../src/async-bytes.ts';

test('production codecs without host preserve strict/replacement policies and basic Base64 semantics',async()=>{
 expect([...encodeUtf8('\ud800x\udc00','question')]).toEqual([63,120,63]);
 expect([...encodeUtf8('\ud800','replacement')]).toEqual([239,191,189]);
 expect(()=>encodeUtf8('\ud800')).toThrow('invalid UTF-16 surrogate');
 expect(encodeBase64(new Uint8Array([9,0,255,1,8]).subarray(1,4))).toBe('AP8B');
 expect([...decodeBase64('Zh')]).toEqual([102]);expect([...decodeBase64('Zh==')]).toEqual([102]);
 for(const value of ['A','AA=','AAA==','AAAA=','AA==AA==','\nAA==','-w=='])expect(()=>decodeBase64(value)).toThrow();
 expect(await encodeUtf8Async('🙂中\0')).toEqual(encodeUtf8('🙂中\0'));
 expect(await decodeUtf8Async(encodeUtf8('\ufeff中🙂\0'))).toBe('\ufeff中🙂\0');
 expect(await encodeBase64Async(new Uint8Array([255]))).toBe('/w==');
 expect(await decodeBase64Async('/w')).toEqual(new Uint8Array([255]));
});

test('bounded optional host/rejection paths, ownership and cancellation at scheduled boundaries',async()=>{
 const root=globalThis as any,previous=root.pod;const sizes:number[]=[];
 try {
  root.pod={encodeUtf8Chunk:(text:string)=>{sizes.push(text.length);return null;},encodeBase64Chunk:(bytes:Uint8Array)=>{sizes.push(bytes.length);return undefined;},decodeBase64Chunk:(text:string)=>{sizes.push(text.length);return null;}};
  const text='a'.repeat(4095)+'🙂\0中'.repeat(5000),expected=new TextEncoder().encode(text);
  expect(encodeUtf8(text)).toEqual(expected);const encoded=encodeBase64(expected);expect(decodeBase64(encoded)).toEqual(expected);
  expect(Math.max(...sizes)).toBeLessThanOrEqual(4096);
  let cancelled=false,turns=0;
  const options={cancelled:()=>cancelled,yield:async()=>{turns++;cancelled=true;}};
  for(const run of [()=>encodeUtf8Async(text,'strict',options),()=>encodeBase64Async(expected,options),()=>decodeBase64Async(encoded,options),()=>decodeUtf8Async(expected,options)]){
   cancelled=false;turns=0;await expect(run()).rejects.toThrow('byte codec cancelled');expect(turns).toBe(1);
  }
  sizes.length=0;await expect(encodeUtf8Async(text,'strict',{cancelled:()=>true})).rejects.toThrow();expect(sizes).toHaveLength(0);
 }finally{root.pod=previous;}
});

test('fast codec pieces amortize scheduler waits while large streams remain bounded and cancellable',async()=>{
 const root=globalThis as any,previous=root.pod;let turns=0;
 try{
  root.pod={encodeUtf8Chunk:(s:string)=>new TextEncoder().encode(s),decodeUtf8:(b:Uint8Array)=>new TextDecoder('utf8',{fatal:true}).decode(b)};
  const text='a'.repeat(374001),options={yield:async()=>{turns++;}};
  const bytes=await encodeUtf8Async(text,'strict',options);expect(bytes).toEqual(new TextEncoder().encode(text));expect(turns).toBeGreaterThan(0);expect(turns).toBeLessThan(92);
  turns=0;expect(await decodeUtf8Async(bytes,options)).toBe(text);expect(turns).toBeGreaterThan(0);expect(turns).toBeLessThan(92);
  let checks=0;await expect(encodeUtf8Async(text,'strict',{cancelled:()=>++checks>10,yield:async()=>{}})).rejects.toThrow('cancelled');
 }finally{root.pod=previous;}
});

test('optional SHA256 bridge keeps bounded views and rejects malformed/absent results',async()=>{
 const {sha256Bytes}=await import('../src/bytes.ts');const root=globalThis as any,old=root.pod;
 try{
  delete root.pod;expect(sha256Bytes(new Uint8Array([1]))).toBeNull();
  let input:Uint8Array|undefined;root.pod={sha256Bytes:(b:Uint8Array)=>{input=b;return new Uint8Array(32).fill(9);}};
  const view=new Uint8Array([8,1,2,7]).subarray(1,3);expect(sha256Bytes(view)).toEqual(new Uint8Array(32).fill(9));expect(input).toBe(view);
  input=undefined;expect(sha256Bytes(new Uint8Array(256*1024+1))).toBeNull();expect(input).toBeUndefined();
  for(const value of [null,undefined]){root.pod={sha256Bytes:()=>value};expect(sha256Bytes(view)).toBeNull();}
  for(const value of [new Uint8Array(31),new Array(32),3]){root.pod={sha256Bytes:()=>value};expect(()=>sha256Bytes(view)).toThrow('Invalid native SHA-256 digest');}
 }finally{root.pod=old;}
});

test('optional bounded gzip view decode rejects malformed bridge results and keeps an absent-host fallback',async()=>{
 const {gunzipBytes}=await import('../src/bytes.ts');const root=globalThis as any,old=root.pod;
 try{
  delete root.pod;expect(gunzipBytes(new Uint8Array())).toBeNull();let got:Uint8Array|undefined;
  root.pod={gunzipBytes:(b:Uint8Array)=>{got=b;return new Uint8Array([1,2]);}};const view=new Uint8Array([9,3,4,8]).subarray(1,3);expect(gunzipBytes(view)).toEqual(new Uint8Array([1,2]));expect(got).toBe(view);
  got=undefined;expect(gunzipBytes(new Uint8Array(256*1024+1))).toBeNull();expect(got).toBeUndefined();
  root.pod={gunzipBytes:()=>null};expect(gunzipBytes(view)).toBeNull();root.pod={gunzipBytes:()=>new Uint8Array(256*1024+1)};expect(()=>gunzipBytes(view)).toThrow('Invalid native gzip result');
 }finally{root.pod=old;}
});
