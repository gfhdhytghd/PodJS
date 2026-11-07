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
test('radix deletion never resurrects stale descendants when a removed branch is reinserted',async()=>{
 const stored=new Map<string,any>(),ports={read:async(p:string)=>stored.get(p)??null,encode,hash},rows=Array.from({length:300},(_,i)=>({key:'0'+hash(encode({i} as any)).slice(1),value:i}));
 const apply=async(patch:any[])=>{const result=await updateRadixIndex(ports,patch);for(const w of result.writes)stored.set(w.prefix,w);return result.root.count;};
 expect(await apply(rows)).toBe(300);expect(await apply(rows.map(r=>({...r,remove:true})))).toBe(0);expect(await apply([{...rows[0]!,value:'new'}])).toBe(1);expect(await apply(rows.slice(1,270))).toBe(270);
});
