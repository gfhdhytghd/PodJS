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
