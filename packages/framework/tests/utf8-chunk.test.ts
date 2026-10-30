import {expect,test} from 'bun:test';
import {decodeUtf8Chunk,stringToUtf8 as utf8Encode} from '../src/bytes.ts';
type Utf8Dialect='douyin'|'bili';
// Compatibility callbacks are fixtures, never public framework dialect names.
function decodeUtf8(bytes:Uint8Array,dialect:Utf8Dialect){const parts:string[]=[];for(let i=0;i<bytes.length;){
 const part=decodeUtf8Chunk(bytes,i,{fallback:(b,start)=>{
  let end=start;const limit=Math.min(b.length,start+1024);
  while(end<limit){const a=b[end++];end+=a<128?0:(dialect==='bili'?a<224:(a&224)===192)?1:(dialect==='bili'?a<240:(a&240)===224)?2:3;}
  return {text:legacy(b.subarray(start,Math.min(end,b.length)),dialect),next:end};
 }});parts.push(part.text);i=part.next;}return parts.join('');}
// Frozen pre-K14 decoders. The two invalid-byte dialects intentionally differ.
function legacy(bytes:Uint8Array,dialect:Utf8Dialect){let s='',i=0;while(i<bytes.length){const a=bytes[i++];let cp:number;if(a<128)cp=a;else if(dialect==='bili'?a<224:(a&224)===192)cp=(a&31)<<6|bytes[i++]&63;else if(dialect==='bili'?a<240:(a&240)===224)cp=(a&15)<<12|(bytes[i++]&63)<<6|bytes[i++]&63;else cp=(a&7)<<18|(bytes[i++]&63)<<12|(bytes[i++]&63)<<6|bytes[i++]&63;if(cp<=65535)s+=String.fromCharCode(cp);else{cp-=65536;s+=String.fromCharCode(0xd800|(cp>>10),0xdc00|(cp&1023));}}return s;}
test('portable decoder preserves both legacy outputs for malformed, truncated and boundary inputs',()=>{
  for(const dialect of ['douyin','bili'] as const){
    for(let a=0;a<256;a++)for(let b=0;b<256;b++){
      const bytes=new Uint8Array([a,b]);expect(decodeUtf8(bytes,dialect)).toBe(legacy(bytes,dialect));
    }
    let seed=42;
    for(let run=0;run<200;run++){
      const bytes=new Uint8Array(2048+run);for(let i=0;i<bytes.length;i++){seed=(Math.imul(seed,1664525)+1013904223)>>>0;bytes[i]=seed>>>24;}
      expect(decodeUtf8(bytes,dialect)).toBe(legacy(bytes,dialect));
    }
    for(const prefix of [1022,1023,1024,16381,16382,16383]){
      const value='a'.repeat(prefix)+'中文🙂\0'.repeat(1025);expect(decodeUtf8(utf8Encode(value),dialect)).toBe(value);
    }
    expect(decodeUtf8(new Uint8Array(),dialect)).toBe('');
  }
});

test('production default is strict, uses optional host, respects subarrays NUL and four-byte boundaries',()=>{
 const root=globalThis as any, previous=root.pod;const calls:number[]=[];
 try {
  for(const native of [undefined,(bytes:Uint8Array)=>{calls.push(bytes.length);try{return new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(bytes);}catch{return null;}}]){
   root.pod={decodeUtf8:native};
   for(const prefix of [0,1,1023,16381,16382,16383]){
    const value='a'.repeat(prefix)+'🙂中\0'.repeat(2000),bytes=utf8Encode('x'+value+'y').subarray(1,-1);
    let result='',offset=0;while(offset<bytes.length){const chunk=decodeUtf8Chunk(bytes,offset);expect(chunk.next).toBeGreaterThan(offset);expect(chunk.next-offset).toBeLessThanOrEqual(16384);result+=chunk.text;offset=chunk.next;}
    expect(result).toBe(value);
   }
   expect(()=>decodeUtf8Chunk(new Uint8Array([255]))).toThrow();
   for(const dialect of ['douyin','bili'] as const)for(let a=0;a<256;a++)for(let b=0;b<256;b++){
    const bytes=new Uint8Array([a,b]);expect(decodeUtf8(bytes,dialect)).toBe(legacy(bytes,dialect));
   }
  }
  expect(calls.length).toBeGreaterThan(0);
  expect(decodeUtf8Chunk(new Uint8Array())).toEqual({text:'',next:0});
  expect(()=>decodeUtf8Chunk(new Uint8Array(),0,{maxBytes:0})).toThrow();
 }finally{root.pod=previous;}
});

test('headless optional decoder uses the pure codec surface, preserves strict boundaries and legacy invalid-byte fallback without pod',()=>{
 const root=globalThis as any,previous=root.pod,codecs=root.__podByteCodecs;let calls=0;
 try{delete root.pod;root.__podByteCodecs={decodeUtf8:(b:Uint8Array)=>{calls++;try{return new TextDecoder('utf8',{fatal:true,ignoreBOM:true}).decode(b);}catch{return null;}}};
  const value='A'.repeat(16383)+'🙂中文\0'.repeat(10000),b=utf8Encode(value);expect(decodeUtf8(b,'douyin')).toBe(value);expect(calls).toBeGreaterThan(1);expect(root.pod).toBeUndefined();
  for(const dialect of ['douyin','bili'] as const)for(const bytes of [new Uint8Array([255,128]),new Uint8Array([240,159,153]),new Uint8Array([192,128])])expect(decodeUtf8(bytes,dialect)).toBe(legacy(bytes,dialect));
 }finally{root.pod=previous;root.__podByteCodecs=codecs;}
});
