/** Portable byte codecs plus an optional host-accelerated chunk decoder. */
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
