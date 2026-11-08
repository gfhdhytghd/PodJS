import {test,expect} from 'bun:test';
import {Database} from 'bun:sqlite';
import {randomBytes,createCipheriv,createDecipheriv} from 'node:crypto';
import {CHUNKED_AEAD_BLOCK_BYTES,SQL_CHUNKED_VALUE_SCHEMA,SqlChunkedValues,chunkAad,chunkCount,sealChunked,openChunked,isChunkedEnvelope,hostAesGcmPort,type AeadBlockPort} from '../src/chunked-aead';
import {SqlAuthenticatedCache,SQL_AUTHENTICATED_CACHE_SCHEMA} from '../src/sql-authenticated-cache';

const key=Buffer.alloc(32,9),HOST_LIMIT=256*1024;
/** Android host contract: one AES-GCM call takes at most 256 KiB of input. */
function port(){const calls={encrypt:0,decrypt:0,max:0};const p:AeadBlockPort={
 async encrypt(aad,plain){if(plain.length>HOST_LIMIT)throw Error('chunk_too_large');calls.encrypt++;calls.max=Math.max(calls.max,plain.length);const nonce=randomBytes(12),c=createCipheriv('aes-256-gcm',key,nonce);c.setAAD(aad);return {nonce,ciphertext:new Uint8Array(Buffer.concat([c.update(plain),c.final(),c.getAuthTag()]))};},
 async decrypt(aad,nonce,ciphertext){if(ciphertext.length>HOST_LIMIT)throw Error('chunk_too_large');calls.decrypt++;const d=createDecipheriv('aes-256-gcm',key,nonce);d.setAAD(aad);d.setAuthTag(ciphertext.subarray(-16));return new Uint8Array(Buffer.concat([d.update(ciphertext.subarray(0,-16)),d.final()]));},
};return {port:p,calls};}
const bytes=(n:number,seed=1)=>{const b=new Uint8Array(n);for(let i=0;i<n;i++)b[i]=(i*31+seed)&255;return b;};
const edit=(text:string,f:(v:any)=>void)=>{const v=JSON.parse(text);f(v);return JSON.stringify(v);};

test('chunked AEAD: empty, one byte, exact block boundaries and multi-block values round-trip under the host bound',async()=>{
 const {port:p,calls}=port(),B=CHUNKED_AEAD_BLOCK_BYTES;
 for(const n of [0,1,B-1,B,B+1,2*B,2*B+7,650*1024]){const data=bytes(n,n),text=await sealChunked(p,'v',data);expect(isChunkedEnvelope(text)).toBe(true);expect(JSON.parse(text).chunks.length).toBe(chunkCount(n));expect(await openChunked(p,'v',text)).toEqual(data);}
 expect(chunkCount(0)).toBe(1);expect(chunkCount(B)).toBe(1);expect(chunkCount(B+1)).toBe(2);expect(calls.max).toBeLessThanOrEqual(B);
 // The final-block flag and count are inside the AAD.
 expect(chunkAad('v',0,2)).not.toEqual(chunkAad('v',0,1));expect(chunkAad('v',1,2).at(-2)).toBe(1);
});
test('chunked AEAD rejects reordering, truncation, extension, splicing, tampering and a foreign value id',async()=>{
 const {port:p}=port(),B=1024,data=bytes(3*B+5),text=await sealChunked(p,'a',data,B),other=await sealChunked(p,'b',bytes(3*B+5,2),B),shorter=await sealChunked(p,'a',data.subarray(0,2*B),B);
 const fail=(t:string,id='a')=>expect(openChunked(p,id,t)).rejects.toThrow('chunked_aead_corrupt');
 await fail(edit(text,v=>{[v.chunks[0],v.chunks[1]]=[v.chunks[1],v.chunks[0]];}));                    // reorder
 await fail(edit(text,v=>{v.chunks.pop();v.bytes=3*B;}));                                                // truncate (count bound)
 await fail(edit(shorter,v=>{v.chunks.push(JSON.parse(text).chunks[2]);v.bytes=3*B;}));                 // extend a shorter value
 await fail(edit(text,v=>{v.chunks[1]=JSON.parse(other).chunks[1];}));                                   // splice another value
 await fail(edit(text,v=>{const c=v.chunks[2];v.chunks[2]=c.slice(0,-4)+(c.at(-4)==='A'?'B':'A')+c.slice(-3);}));  // tamper
 await fail(edit(text,v=>{v.bytes=3*B+4;}));await fail(edit(text,v=>{v.version=2;}));await fail(text,'b');
 // A truncated value re-labelled as complete still fails: the old last block was not final.
 await fail(edit(text,v=>{v.chunks.length=2;v.bytes=2*B;}));
});
test('SqlChunkedValues: one block per row and request, streamed read, CAS guard and version cleanup',async()=>{
 const raw=new Database(':memory:');for(const s of [...SQL_CHUNKED_VALUE_SCHEMA])raw.exec(s);raw.exec('CREATE TABLE owner_rows(id TEXT PRIMARY KEY,ref TEXT)');
 let maxRequest=0,maxReply=0;const db={query:async(q:string,p:readonly unknown[]=[])=>{const rows=raw.query(q).all(...p as any[]) as any[];maxReply=Math.max(maxReply,JSON.stringify(rows).length);return rows;},transaction:async(ss:any[])=>{maxRequest=Math.max(maxRequest,JSON.stringify(ss).length);raw.exec('BEGIN');try{for(const s of ss)raw.query(s.sql).run(...s.params??[]);raw.exec('COMMIT');}catch(e){raw.exec('ROLLBACK');throw e;}}};
 const {port:p,calls}=port(),store=new SqlChunkedValues(db,p,'b'),data=bytes(2*1024*1024+3);
 const ref=await store.write('note','v1',"aad",data);expect(ref.count).toBe(chunkCount(data.length));
 await db.transaction([...store.guard(ref),store.keepOnly('note','v1'),{sql:'INSERT INTO owner_rows VALUES(?,?)',params:['note',JSON.stringify(ref)]}]);
 expect(await store.read(ref,'aad')).toEqual(data);expect(maxRequest).toBeLessThan(1024*1024);expect(maxReply).toBeLessThan(1024*1024);expect(calls.max).toBeLessThanOrEqual(CHUNKED_AEAD_BLOCK_BYTES);
 const encrypts=calls.encrypt;expect((await store.write('note','v1','aad',data)).sha256).toBe(ref.sha256);expect(calls.encrypt).toBe(encrypts);   // complete version is reused
 await expect(store.read(ref,'other')).rejects.toThrow('chunked_aead_corrupt');
 await expect(store.read({...ref,count:ref.count-1,bytes:ref.bytes-3},'aad')).rejects.toThrow('chunked_aead_corrupt');
 // Rows swapped inside one version, or replaced by another version, fail.
 const two=await store.write('note','v2','aad',bytes(3*CHUNKED_AEAD_BLOCK_BYTES,5));
 raw.exec("UPDATE podjs_sql_value_chunks SET idx=idx+10 WHERE version='v2' AND idx<2;UPDATE podjs_sql_value_chunks SET idx=CASE idx WHEN 10 THEN 1 ELSE 0 END WHERE version='v2' AND idx>=10");await expect(store.read(two,'aad')).rejects.toThrow('chunked_aead_corrupt');
 raw.exec("DELETE FROM podjs_sql_value_chunks WHERE version='v2' AND idx=2");await expect(db.transaction(store.guard(two))).rejects.toThrow();
 await db.transaction([store.keepOnly('note','v1')]);expect(raw.query("SELECT COUNT(*) AS n FROM podjs_sql_value_chunks WHERE version<>'v1'").get()).toEqual({n:0});
 await db.transaction([store.keepOnly('note',null)]);expect(raw.query('SELECT COUNT(*) AS n FROM podjs_sql_value_chunks').get()).toEqual({n:0});
 const empty=await store.write('e','v','aad',new Uint8Array());expect(await store.read(empty,'aad')).toEqual(new Uint8Array());raw.close();
});
test('authenticated cache spills large values into chunk rows, counts them in page bounds and drops them with their value',async()=>{
 const raw=new Database(':memory:');for(const s of [...SQL_AUTHENTICATED_CACHE_SCHEMA,...SQL_CHUNKED_VALUE_SCHEMA])raw.exec(s);
 const db={query:async(q:string,p:readonly unknown[]=[])=>raw.query(q).all(...p as any[]) as any[],transaction:async(ss:any[])=>{raw.exec('BEGIN');try{for(const s of ss)raw.query(s.sql).run(...s.params??[]);raw.exec('COMMIT');}catch(e){raw.exec('ROLLBACK');throw e;}}};
 const {port:p}=port(),blocks=hostAesGcmPort(async(method,args:any)=>{const b=(k:string)=>Buffer.from(args[k],'base64');if(method==='crypto.aesGcmEncrypt'){const out=await p.encrypt(b('aadBase64'),b('dataBase64'));return {nonceBase64:Buffer.from(out.nonce).toString('base64'),ciphertextBase64:Buffer.from(out.ciphertext).toString('base64')} as any;}return {dataBase64:Buffer.from(await p.decrypt(b('aadBase64'),b('nonceBase64'),b('ciphertextBase64'))).toString('base64')} as any;},async()=>key.toString('base64'));
 const cipher={seal:async(aad:string,v:unknown)=>{const t=JSON.stringify(v);if(t.length>HOST_LIMIT)throw Error('chunk_too_large');return JSON.stringify({aad,t});},open:async(aad:string,b:string)=>{const v=JSON.parse(b);if(v.aad!==aad)throw Error('bad');return JSON.parse(v.t);}};
 const cache=new SqlAuthenticatedCache(db,cipher,'s',key,JSON.stringify,{chunks:new SqlChunkedValues(db,blocks,'s#cache'),inlineBytes:64*1024}),big={text:'长'.repeat(400000)};
 await db.transaction(await cache.prepare('a',[{key:'1',value:big},{key:'2',value:{text:'small'}}]));
 expect(await cache.get('a','1')).toEqual(big);expect((await cache.page('a','',32,100000)).length).toBe(1);expect((await cache.page('a','1',32,100000))).toEqual([{text:'small'}]);
 expect(Number((raw.query('SELECT COUNT(*) AS n FROM podjs_sql_value_chunks').get() as any).n)).toBeGreaterThan(1);
 raw.exec("UPDATE podjs_sql_value_chunks SET body=(SELECT body FROM podjs_sql_value_chunks WHERE idx=0) WHERE idx=1");await expect(cache.get('a','1')).rejects.toThrow('chunked_aead_corrupt');
 await db.transaction(cache.dropExcept('b'));expect(raw.query('SELECT COUNT(*) AS n FROM podjs_sql_value_chunks').get()).toEqual({n:0});raw.close();
});
