/** Chunked AEAD over a bounded single-call AES-GCM port (format v1).
 * A host may cap one AES-GCM call (Android: 256 KiB); a larger value is split
 * into fixed blocks. Every block's AAD binds the value identifier, the block
 * index, the total block count and a final-block flag, so reordering,
 * truncation, extension and splicing between values or versions all fail
 * authentication. Values are LOCAL storage only, never a sync wire object. */
import {encodeUtf8,encodeBase64,decodeBase64} from './bytes';
import {ContentSha256} from './content-sha256';
import type {ProjectionSql,Statement} from './sql-projection';

export const CHUNKED_AEAD_VERSION=1;
/** 64 KiB below the 256 KiB host bound: room for the tag and base64 framing. */
export const CHUNKED_AEAD_BLOCK_BYTES=192*1024;
export const CHUNKED_AEAD_MAX_CHUNKS=65535;
const DOMAIN='podjs.chunked-aead.v1',ENVELOPE='{"podjs":"chunked-aead",';
export interface AeadBlockPort {
 encrypt(aad:Uint8Array,plain:Uint8Array):Promise<{nonce:Uint8Array;ciphertext:Uint8Array}>;
 decrypt(aad:Uint8Array,nonce:Uint8Array,ciphertext:Uint8Array):Promise<Uint8Array>;
}
const corrupt=():never=>{throw Error('chunked_aead_corrupt');};
const block=(n:number|undefined)=>{const b=n??CHUNKED_AEAD_BLOCK_BYTES;if(!Number.isSafeInteger(b)||b<1||b>CHUNKED_AEAD_BLOCK_BYTES)throw Error('chunked_aead_block');return b;};
const id=(value:string|Uint8Array)=>typeof value==='string'?encodeUtf8(value,'strict'):value;
/** Number of blocks of a value; an empty value is one empty final block. */
export function chunkCount(bytes:number,blockBytes?:number){if(!Number.isSafeInteger(bytes)||bytes<0)throw Error('chunked_aead_size');const n=Math.max(1,Math.ceil(bytes/block(blockBytes)));if(n>CHUNKED_AEAD_MAX_CHUNKS)throw Error('object_too_large');return n;}
/** AAD = domain NUL | u32 index | u32 count | u8 final | value identifier. */
export function chunkAad(valueId:string|Uint8Array,index:number,count:number):Uint8Array {
 if(!Number.isInteger(count)||count<1||count>CHUNKED_AEAD_MAX_CHUNKS||!Number.isInteger(index)||index<0||index>=count)throw Error('chunked_aead_index');
 const head=encodeUtf8(DOMAIN,'strict'),v=id(valueId),out=new Uint8Array(head.length+10+v.length),view=new DataView(out.buffer);
 out.set(head);view.setUint32(head.length+1,index);view.setUint32(head.length+5,count);out[head.length+9]=index===count-1?1:0;out.set(v,head.length+10);return out;
}
export async function sealChunk(port:AeadBlockPort,valueId:string|Uint8Array,index:number,count:number,plain:Uint8Array){return port.encrypt(chunkAad(valueId,index,count),plain);}
export async function openChunk(port:AeadBlockPort,valueId:string|Uint8Array,index:number,count:number,nonce:Uint8Array,ciphertext:Uint8Array){
 try{return await port.decrypt(chunkAad(valueId,index,count),nonce,ciphertext);}catch{return corrupt();}
}
const encodeChunk=(c:{nonce:Uint8Array;ciphertext:Uint8Array})=>encodeBase64(c.nonce)+'.'+encodeBase64(c.ciphertext);
function decodeChunk(text:unknown){if(typeof text!=='string')return corrupt();const at=text.indexOf('.');if(at<1||at!==text.lastIndexOf('.'))return corrupt();try{return {nonce:decodeBase64(text.slice(0,at)),ciphertext:decodeBase64(text.slice(at+1))};}catch{return corrupt();}}
/** In-order opener: every block but the last is exactly `blockBytes` long. */
class ChunkReader {
 private next=0;private total=0;
 constructor(private port:AeadBlockPort,private valueId:string|Uint8Array,readonly count:number,private blockBytes:number){}
 async open(text:unknown){if(this.next>=this.count)corrupt();const c=decodeChunk(text),i=this.next++,plain=await openChunk(this.port,this.valueId,i,this.count,c.nonce,c.ciphertext);
  if(plain.length>this.blockBytes||i<this.count-1&&plain.length!==this.blockBytes||this.count>1&&i===this.count-1&&!plain.length)corrupt();this.total+=plain.length;return plain;}
 finish(bytes?:number){if(this.next!==this.count||bytes!==undefined&&bytes!==this.total)corrupt();return this.total;}
}
const join=(parts:Uint8Array[],total:number)=>{const out=new Uint8Array(total);let at=0;for(const p of parts){out.set(p,at);at+=p.length;}return out;};

/** True when `text` is a chunked envelope (anything else is a caller format). */
export const isChunkedEnvelope=(text:string)=>text.startsWith(ENVELOPE);
/** Single-string envelope for values that fit one storage row. */
export async function sealChunked(port:AeadBlockPort,valueId:string|Uint8Array,data:Uint8Array,blockBytes?:number):Promise<string>{
 const b=block(blockBytes),count=chunkCount(data.length,b),chunks:string[]=[];
 for(let i=0;i<count;i++)chunks.push(encodeChunk(await sealChunk(port,valueId,i,count,data.subarray(i*b,(i+1)*b))));
 return ENVELOPE+'"version":1,"block":'+b+',"bytes":'+data.length+',"chunks":'+JSON.stringify(chunks)+'}';
}
export async function openChunked(port:AeadBlockPort,valueId:string|Uint8Array,text:string,maxBytes=Number.MAX_SAFE_INTEGER):Promise<Uint8Array>{
 if(!isChunkedEnvelope(text))corrupt();let v:any;try{v=JSON.parse(text);}catch{return corrupt();}
 if(Object.keys(v).sort().join()!=='block,bytes,chunks,podjs,version'||v.version!==CHUNKED_AEAD_VERSION||!Array.isArray(v.chunks)||!Number.isSafeInteger(v.bytes)||v.bytes<0||v.bytes>maxBytes)corrupt();
 let b:number;try{b=block(v.block);if(chunkCount(v.bytes,b)!==v.chunks.length)corrupt();}catch{return corrupt();}
 const reader=new ChunkReader(port,valueId,v.chunks.length,b),parts:Uint8Array[]=[];for(const c of v.chunks)parts.push(await reader.open(c));return join(parts,reader.finish(v.bytes));
}
/** Host AES-256-GCM (`crypto.aesGcmEncrypt`/`Decrypt`) as a block port. */
export function hostAesGcmPort(request:<T>(method:string,args:Record<string,unknown>)=>Promise<T>,keyBase64:()=>Promise<string>):AeadBlockPort {
 return {
  async encrypt(aad,plain){const out=await request<{nonceBase64:string;ciphertextBase64:string}>('crypto.aesGcmEncrypt',{keyBase64:await keyBase64(),aadBase64:encodeBase64(aad),dataBase64:encodeBase64(plain)});return {nonce:decodeBase64(out.nonceBase64),ciphertext:decodeBase64(out.ciphertextBase64)};},
  async decrypt(aad,nonce,ciphertext){return decodeBase64((await request<{dataBase64:string}>('crypto.aesGcmDecrypt',{keyBase64:await keyBase64(),aadBase64:encodeBase64(aad),nonceBase64:encodeBase64(nonce),ciphertextBase64:encodeBase64(ciphertext)})).dataBase64);},
 };
}

/** Row storage: one block per row, so no single SQL request or reply carries
 * the whole value. `version` names an immutable write of `owner`; the caller
 * commits a small sealed `ChunkedValueRef` plus `guard(ref)` under its own CAS
 * and drops superseded versions in that same transaction with `keepOnly`. */
export const SQL_CHUNKED_VALUE_SCHEMA=[
 'CREATE TABLE IF NOT EXISTS podjs_sql_cas_guard(id INTEGER NOT NULL CHECK(id=1))',
 'CREATE TABLE IF NOT EXISTS podjs_sql_value_chunks(binding TEXT NOT NULL,owner TEXT NOT NULL,version TEXT NOT NULL,idx INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(binding,owner,version,idx))',
];
export type ChunkedValueRef={owner:string;version:string;count:number;bytes:number;block:number;sha256:string};
/** Marker of a value stored as chunk rows; `chunkedRef` recognises it. */
export const CHUNKED_REF_KEY='$podjs.chunked';
export const chunkedRef=(value:unknown):ChunkedValueRef|null=>value&&typeof value==='object'&&Object.keys(value).length===1&&Object.hasOwn(value,CHUNKED_REF_KEY)?(value as any)[CHUNKED_REF_KEY]:null;
export class SqlChunkedValues {
 private readonly blockBytes:number;private readonly batchBytes:number;
 constructor(private readonly db:Pick<ProjectionSql,'query'|'transaction'>,private readonly port:AeadBlockPort,readonly binding:string,options:{blockBytes?:number;batchBytes?:number}={}){this.blockBytes=block(options.blockBytes);this.batchBytes=options.batchBytes??512*1024;}
 private valueId(owner:string,version:string,aad:string){return JSON.stringify([DOMAIN+'/sql',this.binding,owner,version,aad]);}
 /** Writes every block of `data`; an already complete identical version is kept. */
 async write(owner:string,version:string,aad:string,data:Uint8Array):Promise<ChunkedValueRef>{
  const count=chunkCount(data.length,this.blockBytes),hash=new ContentSha256();for(let at=0;at<data.length;at+=this.blockBytes)hash.update(data.subarray(at,at+this.blockBytes));
  const ref:ChunkedValueRef={owner,version,count,bytes:data.length,block:this.blockBytes,sha256:hash.finish()},key=[this.binding,owner,version];
  const have=(await this.db.query('SELECT COUNT(*) AS n,MAX(idx) AS top FROM podjs_sql_value_chunks WHERE binding=? AND owner=? AND version=?',key))[0];
  if(Number(have?.n)===count&&Number(have?.top)===count-1)return ref;
  let batch:Statement[]=[{sql:'DELETE FROM podjs_sql_value_chunks WHERE binding=? AND owner=? AND version=? AND idx>=?',params:[...key,count]}],size=0;
  const id=this.valueId(owner,version,aad);
  for(let i=0;i<count;i++){const body=encodeChunk(await sealChunk(this.port,id,i,count,data.subarray(i*this.blockBytes,(i+1)*this.blockBytes)));
   if(batch.length&&size+body.length>this.batchBytes){await this.db.transaction(batch);batch=[];size=0;}
   batch.push({sql:'INSERT INTO podjs_sql_value_chunks VALUES(?,?,?,?,?) ON CONFLICT(binding,owner,version,idx) DO UPDATE SET body=excluded.body',params:[...key,i,body]});size+=body.length;}
  await this.db.transaction(batch);return ref;
 }
 /** Reads one block per query and checks length and SHA-256 while streaming. */
 async read(ref:ChunkedValueRef,aad:string,maxBytes=Number.MAX_SAFE_INTEGER):Promise<Uint8Array>{
  if(!ref||typeof ref.owner!=='string'||typeof ref.version!=='string'||!Number.isSafeInteger(ref.bytes)||ref.bytes<0||ref.bytes>maxBytes||!/^[a-f0-9]{64}$/.test(ref.sha256))corrupt();
  let b:number;try{b=block(ref.block);if(chunkCount(ref.bytes,b)!==ref.count)corrupt();}catch{return corrupt();}
  const reader=new ChunkReader(this.port,this.valueId(ref.owner,ref.version,aad),ref.count,b),out=new Uint8Array(ref.bytes),hash=new ContentSha256();let at=0;
  for(let i=0;i<ref.count;i++){const row=(await this.db.query('SELECT body FROM podjs_sql_value_chunks WHERE binding=? AND owner=? AND version=? AND idx=?',[this.binding,ref.owner,ref.version,i]))[0];if(!row)corrupt();
   const plain=await reader.open(row!.body);if(at+plain.length>out.length)corrupt();out.set(plain,at);at+=plain.length;hash.update(plain);}
  reader.finish(ref.bytes);if(hash.finish()!==ref.sha256)corrupt();return out;
 }
 /** Final-transaction assertion that the referenced version is complete. */
 guard(ref:ChunkedValueRef):Statement[]{return [{sql:'INSERT INTO podjs_sql_cas_guard SELECT CASE WHEN (SELECT COUNT(*) FROM podjs_sql_value_chunks WHERE binding=? AND owner=? AND version=? AND idx<?)=? THEN 1 ELSE 0 END',params:[this.binding,ref.owner,ref.version,ref.count,ref.count]},{sql:'DELETE FROM podjs_sql_cas_guard'}];}
 /** Drops every version of `owner` except `version` (all of them when null). */
 keepOnly(owner:string,version:string|null):Statement{return version===null?{sql:'DELETE FROM podjs_sql_value_chunks WHERE binding=? AND owner=?',params:[this.binding,owner]}:{sql:'DELETE FROM podjs_sql_value_chunks WHERE binding=? AND owner=? AND version<>?',params:[this.binding,owner,version]};}
 /** SQL expression: stored bytes of every chunk of the owner named by `ownerSql`. */
 sizeSql(ownerSql:string){return `(SELECT COALESCE(SUM(length(body)),0) FROM podjs_sql_value_chunks WHERE binding=${sqlText(this.binding)} AND owner=${ownerSql})`;}
}
const sqlText=(s:string)=>"'"+s.replace(/'/g,"''")+"'";
