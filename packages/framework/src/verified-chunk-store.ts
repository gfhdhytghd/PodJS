import {encodeBase64} from './byte-codecs';

/** Host capability, deliberately independent of companion.sync.file consent. */
export const VERIFIED_CHUNK_CAPABILITY='data.files.resumable' as const;
export const VERIFIED_CHUNK_METHODS=Object.freeze(['file.resumable.stage','file.resumable.status','file.resumable.missing','file.resumable.finalize','file.resumable.release'] as const);
export const VERIFIED_CHUNK_BYTES=65536;
export interface VerifiedObject {id:string;size:number;sha256:string;chunkHashes:readonly string[]}
export interface ObjectLease {scope:string;connectionId:string;epoch:number}
export interface VerifiedObjectStatus {state:'PENDING'|'PARTIAL'|'STAGED';missing:number[]}
export interface VerifiedPublication {path:string;size:number;sha256:string}
export interface VerifiedChunkRuntime {version:1;methods:readonly string[];chunkBytes:65536;maxObjectBytes:number;maxInFlightObjects:number;maxStagingBytes:number;maxJournalBytes:number;durable:true;fencing:true;elapsedMillis:number}
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
 constructor(private readonly host:ChunkHost){}
 private async invoke<T>(method:string,args:Record<string,unknown>):Promise<T>{const value=await this.host<T>(method,args);if(value&&typeof value==='object'&&'error' in value&&typeof value.error==='string')throw new VerifiedChunkError(value.error,'detail' in value?String(value.detail):undefined);return value;}
 async runtime():Promise<VerifiedChunkRuntime|undefined>{
  try{const r=await this.invoke<VerifiedChunkRuntime>('file.resumable.status',{operation:'probe'});
   if(r.version!==1||r.chunkBytes!==VERIFIED_CHUNK_BYTES||r.durable!==true||r.fencing!==true||!Number.isSafeInteger(r.elapsedMillis)||r.elapsedMillis<0||!Array.isArray(r.methods)||VERIFIED_CHUNK_METHODS.some(m=>!r.methods.includes(m))||![r.maxObjectBytes,r.maxInFlightObjects,r.maxStagingBytes,r.maxJournalBytes].every(v=>Number.isSafeInteger(v)&&v>0)||r.maxObjectBytes>16777216||r.maxInFlightObjects>4)return;
   return r;
  }catch{return;}
 }
 async acquire(scope:string,connectionId:string,readOnly=false,bounds={maxInFlightObjects:4,maxStagingBytes:134217728}):Promise<ObjectLease>{
  if(!hex(scope)||!/^[0-9a-f]{32}$/.test(connectionId))throw Error('invalid_object_authorization');
  const r=await this.invoke<{epoch:number}>('file.resumable.status',{operation:'acquire',scope,connectionId,readOnly,...bounds});
  if(!Number.isSafeInteger(r.epoch)||r.epoch<1)throw Error('invalid_object_epoch');
  return Object.freeze({scope,connectionId,epoch:r.epoch});
 }
 async check(lease:ObjectLease):Promise<void>{await this.invoke('file.resumable.status',{operation:'check',...lease});}
 async revokeWrites(lease:ObjectLease):Promise<void>{await this.invoke('file.resumable.status',{operation:'readOnly',...lease});}
 async collect(scope:string,discardPublication=false):Promise<void>{await this.invoke('file.resumable.status',{operation:'collect',scope,discardPublication});}
 async status(lease:ObjectLease,object:VerifiedObject):Promise<VerifiedObjectStatus>{validateVerifiedObject(object);return this.invoke('file.resumable.status',{operation:'object',...lease,object});}
 async missing(lease:ObjectLease,object:VerifiedObject):Promise<number[]>{validateVerifiedObject(object);return (await this.invoke<{missing:number[]}>('file.resumable.missing',{...lease,object})).missing;}
 async stage(lease:ObjectLease,object:VerifiedObject,index:number,bytes:Uint8Array,request:{id:string;digest:string}):Promise<VerifiedObjectStatus>{
  validateVerifiedObject(object);
  if(!Number.isSafeInteger(index)||index<0||index>=object.chunkHashes.length||bytes.length!==Math.min(VERIFIED_CHUNK_BYTES,object.size-index*VERIFIED_CHUNK_BYTES)||!/^[0-9a-f]{32}$/.test(request.id)||!hex(request.digest))throw Error('invalid_verified_chunk');
  return this.invoke('file.resumable.stage',{...lease,object,index,dataBase64:encodeBase64(bytes),bindingId:request.id,requestDigest:request.digest});
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
