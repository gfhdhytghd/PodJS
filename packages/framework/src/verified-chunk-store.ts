import {encodeBase64} from './byte-codecs';

/** Host capability, deliberately independent of companion.sync.file consent. */
export const VERIFIED_CHUNK_CAPABILITY='data.files.resumable' as const;
export const VERIFIED_CHUNK_METHODS=Object.freeze(['file.resumable.stage','file.resumable.status','file.resumable.missing','file.resumable.finalize','file.resumable.release'] as const);
export const VERIFIED_CHUNK_BYTES=65536;
export interface VerifiedObject {id:string;size:number;sha256:string;chunkHashes:readonly string[]}
export interface ObjectLease {scope:string;connectionId:string;epoch:number}
export interface VerifiedObjectStatus {state:'PENDING'|'PARTIAL'|'STAGED';missing:number[]}
export interface VerifiedRequestBinding {id:string;digest:string}
export interface VerifiedChunkReceipt {index:number;bytes:number;sha256:string;bindingId:string;requestDigest:string;durable:true}
export interface VerifiedPublication {path:string;size:number;sha256:string}
export interface VerifiedChunkRuntime {version:1;methods:readonly string[];chunkBytes:65536;maxObjectBytes:number;maxInFlightObjects:number;maxStagingBytes:number;maxJournalBytes:number;durable:true;fencing:true;chunkReceipt?:true;requestBindings?:true;elapsedMillis:number}
const REQUEST_BINDING_PAGE_SIZE=128;
const requestId=(value:string)=>/^[0-9a-f]{32}$/.test(value);
export type ChunkHost=<T>(method:string,args:Record<string,unknown>)=>Promise<T>;
const hex=(value:string)=>/^[0-9a-f]{64}$/.test(value);
export function validateVerifiedObject(value:VerifiedObject):void {
 if(!hex(value.id)||!hex(value.sha256)||!Number.isSafeInteger(value.size)||value.size<0||value.size>16777216||value.chunkHashes.length!==Math.ceil(value.size/VERIFIED_CHUNK_BYTES)||value.chunkHashes.some(h=>!hex(h)))throw Error('invalid_verified_object');
}
/** The caller creates scope/connection locally after authenticating its peer.
 * Neither a wire object ID nor a recovered job ID can construct a grant. The
 * native dispatcher additionally scopes all state to the installed app sandbox.
 */
export class VerifiedChunkError extends Error {
 constructor(readonly code:string,readonly detail?:string){super(code);this.name='VerifiedChunkError';}
}
export class VerifiedChunkStore {
 constructor(private readonly host:ChunkHost,private readonly byteHost?:<T>(method:string,args:Record<string,unknown>,bytes:Uint8Array)=>Promise<T>){}
 private async invoke<T>(method:string,args:Record<string,unknown>):Promise<T>{const value=await this.host<T>(method,args);if(value&&typeof value==='object'&&'error' in value&&typeof value.error==='string')throw new VerifiedChunkError(value.error,'detail' in value?String(value.detail):undefined);return value;}
 async runtime():Promise<VerifiedChunkRuntime|undefined>{
  try{const r=await this.invoke<VerifiedChunkRuntime>('file.resumable.status',{operation:'probe'});
   if(r.version!==1||r.chunkBytes!==VERIFIED_CHUNK_BYTES||r.durable!==true||r.fencing!==true||!Number.isSafeInteger(r.elapsedMillis)||r.elapsedMillis<0||!Array.isArray(r.methods)||VERIFIED_CHUNK_METHODS.some(m=>!r.methods.includes(m))||![r.maxObjectBytes,r.maxInFlightObjects,r.maxStagingBytes,r.maxJournalBytes].every(v=>Number.isSafeInteger(v)&&v>0)||r.maxObjectBytes>16777216||r.maxInFlightObjects>4)return;
   return r;
  }catch{return;}
 }
 async acquire(scope:string,connectionId:string,readOnly=false,bounds={maxInFlightObjects:4,maxStagingBytes:67108864}):Promise<ObjectLease>{
  if(!hex(scope)||!/^[0-9a-f]{32}$/.test(connectionId))throw Error('invalid_object_authorization');
  const r=await this.invoke<{epoch:number}>('file.resumable.status',{operation:'acquire',scope,connectionId,readOnly,...bounds});
  if(!Number.isSafeInteger(r.epoch)||r.epoch<1)throw Error('invalid_object_epoch');
  return Object.freeze({scope,connectionId,epoch:r.epoch});
 }
 async check(lease:ObjectLease):Promise<void>{await this.invoke('file.resumable.status',{operation:'check',...lease});}
 /** List only durable binding metadata under the current lease. The cursor
  * is the last returned ID, and a short page ends the listing. */
 async requestBindings(lease:ObjectLease,after?:string):Promise<{entries:VerifiedRequestBinding[];next?:string}>{
  if(after!==undefined&&!requestId(after))throw Error('invalid_verified_request_cursor');
  const result=await this.invoke<{entries:VerifiedRequestBinding[]}>('file.resumable.status',{
   operation:'requestBindings',...lease,...(after===undefined?{}:{after}),limit:REQUEST_BINDING_PAGE_SIZE,
  });
  if(!Array.isArray(result?.entries)||result.entries.length>REQUEST_BINDING_PAGE_SIZE)throw new VerifiedChunkError('JOURNAL_CORRUPT');
  let last=after??'';
  for(const entry of result.entries){
   if(typeof entry?.id!=='string'||!requestId(entry.id)||entry.id<=last||typeof entry.digest!=='string'||!hex(entry.digest))
    throw new VerifiedChunkError('JOURNAL_CORRUPT');
   last=entry.id;
  }
  return {entries:result.entries,...(result.entries.length===REQUEST_BINDING_PAGE_SIZE?{next:last}:{})};
 }
 async revokeWrites(lease:ObjectLease):Promise<void>{await this.invoke('file.resumable.status',{operation:'readOnly',...lease});}
 async collect(scope:string,discardPublication=false):Promise<void>{await this.invoke('file.resumable.status',{operation:'collect',scope,discardPublication});}
 async status(lease:ObjectLease,object:VerifiedObject):Promise<VerifiedObjectStatus>{validateVerifiedObject(object);return this.invoke('file.resumable.status',{operation:'object',...lease,object});}
 async missing(lease:ObjectLease,object:VerifiedObject):Promise<number[]>{validateVerifiedObject(object);return (await this.invoke<{missing:number[]}>('file.resumable.missing',{...lease,object})).missing;}
 stage(lease:ObjectLease,object:VerifiedObject,index:number,bytes:Uint8Array,request:{id:string;digest:string}):Promise<VerifiedObjectStatus>{
  return this.writeChunk(lease,object,index,bytes,request,false);
 }
 /** Acknowledge the current block and its durable request binding. Object
  * status and missing ranges are obtained separately with explicit verification. */
 async stageReceipt(lease:ObjectLease,object:VerifiedObject,index:number,bytes:Uint8Array,request:{id:string;digest:string}):Promise<VerifiedChunkReceipt>{
  const result=await this.writeChunk<VerifiedChunkReceipt>(lease,object,index,bytes,request,true);
  if(result?.durable!==true||result.index!==index||result.bytes!==bytes.length||result.sha256!==object.chunkHashes[index]||
    result.bindingId!==request.id||result.requestDigest!==request.digest)throw Error('invalid_verified_chunk_receipt');
  return result;
 }
 private async writeChunk<T>(lease:ObjectLease,object:VerifiedObject,index:number,bytes:Uint8Array,request:{id:string;digest:string},receiptOnly:boolean):Promise<T>{
  validateVerifiedObject(object);
  if(!Number.isSafeInteger(index)||index<0||index>=object.chunkHashes.length||bytes.length!==Math.min(VERIFIED_CHUNK_BYTES,object.size-index*VERIFIED_CHUNK_BYTES)||!requestId(request.id)||!hex(request.digest))throw Error('invalid_verified_chunk');
  const method='file.resumable.stage';
  const args={...lease,object,index,bindingId:request.id,requestDigest:request.digest,...(receiptOnly?{receiptOnly:true}:{})};
  if(this.byteHost){const value=await this.byteHost<T>(method,args,bytes);if(value&&typeof value==='object'&&'error' in value&&typeof value.error==='string')throw new VerifiedChunkError(value.error,'detail' in value?String(value.detail):undefined);return value;}
  return this.invoke(method,{...args,dataBase64:encodeBase64(bytes)});
 }
 async finalize(lease:ObjectLease,object:VerifiedObject,path?:string):Promise<VerifiedPublication>{
  validateVerifiedObject(object);const r=await this.invoke<VerifiedPublication>('file.resumable.finalize',{...lease,object,...(path===undefined?{}:{path})});
  if(r.size!==object.size||r.sha256!==object.sha256||typeof r.path!=='string'||(path===undefined?!r.path.startsWith('resumable-published/'):r.path!==path))throw Error('invalid_verified_publication');
  return r;
 }
 /** Published artifacts are caller-owned immutable business references. Release
  * retires only the native temporary receiver; it never removes the publication.
  */
 async release(lease:ObjectLease,object:VerifiedObject,discardPublication=false):Promise<void>{validateVerifiedObject(object);await this.invoke('file.resumable.release',{...lease,object,discardPublication});}
}
