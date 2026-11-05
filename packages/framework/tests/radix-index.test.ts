import {test,expect} from 'bun:test';
import {updateRadixIndex,type RadixNode} from '../src/radix-index';
const encode=(node:RadixNode)=>new TextEncoder().encode(JSON.stringify(node));
const hash=(bytes:Uint8Array)=>new Bun.CryptoHasher('sha256').update(bytes).digest('hex');
test('radix inventory rebuilds only affected paths, retains off-path bytes and detects corrupted nodes',async()=>{
 const stored=new Map<string,any>(),rows=Array.from({length:12500},(_,i)=>({key:new Bun.CryptoHasher('sha256').update(String(i)).digest('hex'),value:i}));
 let reads=0;const ports={read:async(prefix:string)=>{reads++;return stored.get(prefix)??null;},encode,hash};
 for(let at=0;at<rows.length;at+=32){const next=await updateRadixIndex(ports,rows.slice(at,at+32));for(const row of next.writes)stored.set(row.prefix,row);}
 expect(stored.get('').count).toBe(12500);const before=new Map([...stored].map(([key,row])=>[key,row.hash]));reads=0;
 const patch=await updateRadixIndex(ports,[{...rows[0]!,value:'changed'}]);expect(reads).toBeLessThanOrEqual(4);expect(patch.writes.length).toBeLessThanOrEqual(4);for(const row of patch.writes)stored.set(row.prefix,row);
 const changed=[...stored].filter(([key,row])=>before.get(key)!==row.hash);expect(changed.length).toBe(patch.writes.length);
 stored.get('').hash='wrong';await expect(updateRadixIndex(ports,[rows[0]!])).rejects.toThrow('index_hash_mismatch');
});
