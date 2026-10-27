import {test,expect} from 'bun:test';
import {memoizeSmallDigest} from '../src/digest-cache';
test('digest reuse is exact, bounded, mutation-safe and fences hits',async()=>{
 let calls=0,allowed=true;const hash=memoizeSmallDigest(async b=>{calls++;await Promise.resolve();return [...b].join(',');},{maxEntries:2,maxBytes:1024,maxInputBytes:4,beforeUse:()=>{if(!allowed)throw Error('account fenced');}});
 const input=new Uint8Array([1,0,255]);const one=hash(input);input[0]=2;expect(await one).toBe('1,0,255');expect(await hash(new Uint8Array([1,0,255]))).toBe('1,0,255');expect(calls).toBe(1);
 await hash(input);await hash(new Uint8Array([3]));await hash(new Uint8Array([1,0,255]));expect(calls).toBe(4);
 await hash(new Uint8Array(5));await hash(new Uint8Array(5));expect(calls).toBe(6);allowed=false;await expect(hash(new Uint8Array([1,0,255]))).rejects.toThrow('account fenced');
});
test('digest failure retries and separate instances never share cache',async()=>{
 let calls=0;const digest=async()=>{if(++calls===1)throw Error('host failed');return 'ok';};const one=memoizeSmallDigest(digest),two=memoizeSmallDigest(digest);await expect(one(new Uint8Array([1]))).rejects.toThrow('host failed');expect(await one(new Uint8Array([1]))).toBe('ok');expect(await two(new Uint8Array([1]))).toBe('ok');expect(calls).toBe(3);
});
test('byte ceiling evicts exact values and in-flight identical calls coalesce',async()=>{
 let calls=0;const hash=memoizeSmallDigest(async b=>{calls++;await Promise.resolve();return [...b].join(',');},{maxBytes:520,maxEntries:100});
 await Promise.all([hash(new Uint8Array([1])),hash(new Uint8Array([1]))]);expect(calls).toBe(1);
 await hash(new Uint8Array([2]));await hash(new Uint8Array([3]));await hash(new Uint8Array([1]));expect(calls).toBe(4);
});

