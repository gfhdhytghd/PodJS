import {afterEach,expect,test} from 'bun:test';
import {crc32,crc32Async} from '../src/checksum';

const root=globalThis as any,original=root.pod,originalCodecs=root.__podByteCodecs;
afterEach(()=>{if(original===undefined)delete root.pod;else root.pod=original;if(originalCodecs===undefined)delete root.__podByteCodecs;else root.__podByteCodecs=originalCodecs;});
const reference=(bytes:Uint8Array,initial=0)=>{let c=initial^0xffffffff;for(const b of bytes){c^=b;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0;};
test('CRC32 portable bytes, subviews and incremental seeds match independent bitwise vectors',async()=>{
 delete root.pod;const b=new Uint8Array([0,49,50,51,52,53,54,55,56,57,0]).subarray(1,10);
 expect(crc32(b)).toBe(0xcbf43926);expect(crc32(new Uint8Array(),0xffffffff)).toBe(0xffffffff);
 expect(crc32(b.subarray(4),crc32(b.subarray(0,4)))).toBe(0xcbf43926);
 const data=Uint8Array.from({length:256*1024+19},(_,i)=>i*31);
 for(const initial of [0,1,0xffffffff]){expect(crc32(data,initial)).toBe(reference(data,initial));expect(await crc32Async(data,{yield:async()=>{}},initial)).toBe(reference(data,initial));}
});
test('CRC32 native calls are bounded and chain the finalized checksum',async()=>{
 const calls:{length:number;initial:number}[]=[];
 root.pod={crc32Bytes:(b:Uint8Array,initial:number)=>{calls.push({length:b.length,initial});return reference(b,initial);}};
 const data=Uint8Array.from({length:1024*1024+3},(_,i)=>i^73);let yields=0;
 expect(await crc32Async(data,{yield:async()=>{yields++;}})).toBe(reference(data));
 expect(calls).toHaveLength(5);expect(calls.every(c=>c.length<=256*1024)).toBe(true);expect(calls[1]!.initial).toBe(reference(data.subarray(0,256*1024)));
 expect(crc32(data)).toBe(reference(data));
});
test('CRC32 rejected native capability falls back, yields within a large single buffer and cancels',async()=>{
 let calls=0,yields=0,cancelled=false;root.pod={crc32Bytes:()=>{calls++;return null;}};
 await expect(crc32Async(new Uint8Array(8*1024*1024),{cancelled:()=>cancelled,yield:async()=>{yields++;cancelled=true;}})).rejects.toThrow('checksum cancelled');
 expect(yields).toBe(1);expect(calls).toBe(1);
});
test('CRC32 validates seed and native result instead of accepting a malformed checksum',async()=>{
 for(const seed of [-1,1.5,NaN,Infinity,2**32]){expect(()=>crc32(new Uint8Array([1]),seed)).toThrow('invalid CRC32 input');await expect(crc32Async(new Uint8Array([1]),{},seed)).rejects.toThrow('invalid CRC32 input');}
 for(const value of [-1,2**32,NaN,1.1,true,new Uint8Array(4)]){root.pod={crc32Bytes:()=>value};expect(()=>crc32(new Uint8Array([1]))).toThrow('Invalid native CRC32 result');}
});

test('headless CRC uses the pure namespace without granting UI pod, including a partial foreground namespace',async()=>{
 delete root.pod;let calls=0;root.__podByteCodecs={crc32Bytes:(b:Uint8Array,seed:number)=>{calls++;return reference(b,seed);}};
 const bytes=Uint8Array.from({length:1024*1024+19},(_,i)=>i^31);expect(await crc32Async(bytes,{yield:async()=>{}})).toBe(reference(bytes));expect(calls).toBe(5);expect(root.pod).toBeUndefined();
 root.pod={};expect(crc32(new Uint8Array([49,50,51]))).toBe(reference(new Uint8Array([49,50,51])));expect(calls).toBe(6);
 for(const value of [null,undefined,{},3]){expect(()=>crc32(value as any)).toThrow('invalid CRC32 input');await expect(crc32Async(value as any)).rejects.toThrow('invalid CRC32 input');}
});
