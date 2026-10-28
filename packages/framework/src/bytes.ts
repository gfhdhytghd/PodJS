/** Portable byte codecs plus an optional host-accelerated chunk decoder. */
export {encodeUtf8,utf8EncodeChunks,encodeBase64,base64EncodeChunks,decodeBase64,base64DecodeChunks} from './byte-codecs.ts';
export type {SurrogatePolicy} from './byte-codecs.ts';
export {base64ToBytes,bytesToBase64,stringToUtf8,utf8ToString} from "../../../vendor/pocketjs/framework/src/bytes.ts";

import {utf8ToString} from "../../../vendor/pocketjs/framework/src/bytes.ts";
export interface Utf8Chunk { text:string; next:number }
export interface Utf8ChunkOptions {
  /** Native/strict chunk budget, at least four bytes; defaults to 16KiB. */
  maxBytes?:number;
  /** Optional legacy decoder, called on host absence/rejection. Owns its chunk budget. */
  fallback?:(bytes:Uint8Array,start:number)=>Utf8Chunk;
}
export function nativeUtf8Decoder():((bytes:Uint8Array)=>string|null|undefined)|undefined {
  return (globalThis as {pod?:{decodeUtf8?:(bytes:Uint8Array)=>string|null|undefined}}).pod?.decodeUtf8;
}
/** One bounded piece; callers can yield between pieces. Default decoding is strict. */
export function decodeUtf8Chunk(bytes:Uint8Array,start=0,options:Utf8ChunkOptions={}):Utf8Chunk {
  const budget=options.maxBytes??16384;
  if(!Number.isInteger(start)||start<0||start>bytes.length)throw new RangeError("invalid UTF-8 offset");
  if(!Number.isInteger(budget)||budget<4)throw new RangeError("invalid UTF-8 chunk budget");
  let end=Math.min(bytes.length,start+budget);
  if(end<bytes.length)for(let back=0;back<3&&end>start&&(bytes[end]&0xc0)===0x80;back++)end--;
  const native=nativeUtf8Decoder();
  if(native&&end>start){const text=native(bytes.subarray(start,end));if(typeof text==='string')return {text,next:end};}
  if(options.fallback)return options.fallback(bytes,start);
  return {text:utf8ToString(bytes.subarray(start,end)),next:end};
}

export {encodeUtf8Async,decodeUtf8Async,encodeBase64Async,decodeBase64Async,equalBytesAsync} from './async-bytes.ts';
export type {CodecWorkOptions} from './async-bytes.ts';
export {crc32,crc32Async} from './checksum.ts';

/** Optional pure native SHA-256 for at most 256 KiB; null requests a caller's fallback. */
export function sha256Bytes(bytes:Uint8Array):Uint8Array|null {
 const hash=(globalThis as {pod?:{sha256Bytes?:(bytes:Uint8Array)=>Uint8Array|null|undefined}}).pod?.sha256Bytes;
 if(bytes.length>256*1024||typeof hash!=='function')return null;
 const digest=hash(bytes);
 if(digest==null)return null;
 if(!(digest instanceof Uint8Array)||digest.length!==32)throw Error('Invalid native SHA-256 digest');
 return digest;
}

/** Optional pure gzip fast path: at most 256 KiB input and decompressed output. */
export function gunzipBytes(bytes:Uint8Array):Uint8Array|null {
 const decode=(globalThis as {pod?:{gunzipBytes?:(bytes:Uint8Array)=>Uint8Array|null|undefined}}).pod?.gunzipBytes;
 if(bytes.length>256*1024||!decode)return null;const result=decode(bytes);
 if(result==null)return null;if(!(result instanceof Uint8Array)||result.length>256*1024)throw Error('Invalid native gzip result');return result;
}

import {utf8EncodeChunks as encodeTextParts,type SurrogatePolicy as Utf16Policy} from './byte-codecs.ts';
export interface ByteStreamWriteOptions {maxBytes:number;blockBytes?:number;cancelled?:()=>boolean}
/** Bounded, backpressured publication into any append sink. No full-stream copy;
 * append receipts are checked before a caller may publish a durable reference. */
export async function writeByteStream(parts:AsyncIterable<Uint8Array>|Iterable<Uint8Array>,append:(bytes:Uint8Array,offset:number)=>Promise<number>,options:ByteStreamWriteOptions):Promise<number>{
 const block=options.blockBytes??65536;if(!Number.isSafeInteger(options.maxBytes)||options.maxBytes<0||!Number.isSafeInteger(block)||block<1||block>1024*1024)throw RangeError('invalid stream write bounds');
 let offset=0,used=0;const buffer=new Uint8Array(block),check=()=>{if(options.cancelled?.())throw Error('stream write cancelled');};
 const flush=async()=>{if(!used)return;check();const next=await append(buffer.subarray(0,used),offset);if(next!==offset+used)throw Error('stream append receipt mismatch');offset=next;used=0;check();};
 for await(const part of parts){check();if(!(part instanceof Uint8Array)||part.length>options.maxBytes-offset-used)throw RangeError('stream write exceeds limit');for(let at=0;at<part.length;){const count=Math.min(part.length-at,block-used);buffer.set(part.subarray(at,at+count),used);used+=count;at+=count;if(used===block)await flush();}}
 await flush();return offset;
}
export async function writeUtf8Stream(parts:AsyncIterable<string>|Iterable<string>,append:(bytes:Uint8Array,offset:number)=>Promise<number>,options:ByteStreamWriteOptions&{surrogatePolicy?:Utf16Policy}):Promise<number>{
 async function* bytes(){for await(const text of parts)yield* encodeTextParts(text,options.surrogatePolicy??'strict');}
 return writeByteStream(bytes(),append,options);
}
