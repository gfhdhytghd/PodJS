import {test,expect} from 'bun:test';
import {createHmac} from 'node:crypto';
import {hmacSha256Bytes,hex} from '../src/crypto';
import {WorkReceipts} from '../src/work-receipts';
test('HMAC byte path matches RFC-compatible host for empty, block and oversized keys/messages',()=>{
 for(const size of [0,1,32,64,65,131])for(const length of [0,1,55,56,64,1000]){
  const key=new Uint8Array(size).fill(0xab),input=new Uint8Array(length).fill(0xcd),copy=key.slice();
  expect(hex(hmacSha256Bytes(key,input))).toBe(createHmac('sha256',key).update(input).digest('hex'));expect(key).toEqual(copy);
 }
});
test('disposable work receipts resume only exact fingerprints and retain bounded newest work',()=>{
 const first=new WorkReceipts<number>([],2);first.set('a',1);first.set('b',2);
 const resumed=new WorkReceipts(first.snapshot(),2);expect(resumed.get('a')).toBe(1);expect(resumed.get('changed-a')).toBeUndefined();resumed.set('c',3);expect(resumed.get('a')).toBeUndefined();expect(resumed.snapshot()).toEqual([['b',2],['c',3]]);
});
