import {expect,test} from 'bun:test';
import {equalBytesAsync} from '../src/bytes.ts';

test('exact byte comparison respects views, empty input, unequal lengths and both end bytes',async()=>{
 const storage=new Uint8Array([9,0,255,2,8]);
 expect(await equalBytesAsync(storage.subarray(1,4),new Uint8Array([0,255,2]))).toBe(true);
 expect(await equalBytesAsync(new Uint8Array(),new Uint8Array())).toBe(true);
 expect(await equalBytesAsync(storage,storage)).toBe(true);
 for(const b of [new Uint8Array([0,255]),new Uint8Array([1,255,2]),new Uint8Array([0,255,3])])expect(await equalBytesAsync(storage.subarray(1,4),b)).toBe(false);
});

test('large comparison bounds reads between scheduler turns and preserves its inputs',async()=>{
 const a=new Uint8Array(1024*1024).fill(73),b=a.slice();let reads=0,turns=0,last=0;
 const observed=new Proxy(a,{get(target,key){if(typeof key==='string'&&/^\d+$/.test(key))reads++;return Reflect.get(target,key,target);}});
 expect(await equalBytesAsync(observed,b,{yield:async()=>{expect(reads-last).toBeLessThanOrEqual(65536);last=reads;turns++;}})).toBe(true);
 expect(turns).toBeGreaterThan(0);expect(reads).toBe(a.length);expect(reads-last).toBeLessThanOrEqual(65536);
 expect(a).toEqual(b);b[b.length-1]=74;expect(await equalBytesAsync(a,b,{yield:async()=>{}})).toBe(false);
});

test('cancellation and failed scheduler stop comparison without changing either view',async()=>{
 const a=new Uint8Array(128*1024).fill(31),b=a.slice();let cancelled=false,turns=0;
 await expect(equalBytesAsync(a,b,{cancelled:()=>cancelled,yield:async()=>{turns++;cancelled=true;}})).rejects.toThrow('byte codec cancelled');
 expect(turns).toBe(1);expect(a).toEqual(b);
 await expect(equalBytesAsync(a,b,{cancelled:()=>true})).rejects.toThrow('byte codec cancelled');
 const failure=new Error('scheduler unavailable');await expect(equalBytesAsync(a,b,{yield:async()=>{throw failure;}})).rejects.toBe(failure);
 expect(a).toEqual(b);
});
