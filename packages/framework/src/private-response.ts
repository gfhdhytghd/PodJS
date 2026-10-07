import {bytesToBase64,base64ToBytes} from './services.ts';
export interface DownloadedFile {path:string;size:number}
export interface EncodedFileChunk {offset:number;size:number;dataBase64:string;eof:boolean}
/** Compatibility profile: size allocation and raw chunk checks only. The caller
 * retains path ownership, download limits, cleanup and platform error messages. */
export async function readDownloadedBytes(path:string,size:number,read:(path:string,offset:number)=>Promise<Uint8Array>,incomplete:()=>Error):Promise<Uint8Array>{
 const bytes=new Uint8Array(size);let offset=0;
 while(offset<bytes.length){
  const chunk=await read(path,offset);
  if(!chunk.length||offset+chunk.length>bytes.length)throw incomplete();
  bytes.set(chunk,offset);offset+=chunk.length;
 }
 return bytes;
}
export interface StrictResponseOptions {maxBytes:number;chunkBytes:number;invalidResponse:()=>Error;incomplete:()=>Error}
/** Strict profile: only read the owned path; validate size before allocation and
 * every offset/size/EOF and canonical base64 chunk before copying it. */
export async function readDownloadedChunks(path:string,response:DownloadedFile,read:(path:string,offset:number,maxBytes:number)=>Promise<EncodedFileChunk>,options:StrictResponseOptions):Promise<Uint8Array>{
 if(!Number.isSafeInteger(options.chunkBytes)||options.chunkBytes<=0||!Number.isSafeInteger(options.maxBytes)||options.maxBytes<0)throw new RangeError('Invalid response read limits');
 if(response.path!==path||!Number.isSafeInteger(response.size)||response.size<0||response.size>options.maxBytes)throw options.invalidResponse();
 const body=new Uint8Array(response.size);
 for(let offset=0;offset<body.length;){
  const maximum=Math.min(options.chunkBytes,body.length-offset);
  const chunk=await read(path,offset,maximum);
  if(chunk.offset!==offset||chunk.size!==body.length||typeof chunk.dataBase64!=='string'||chunk.dataBase64.length>4*Math.ceil(maximum/3))throw options.incomplete();
  const part=base64ToBytes(chunk.dataBase64);
  if(!part.length||part.length>maximum||bytesToBase64(part)!==chunk.dataBase64||chunk.eof!==(offset+part.length===body.length))throw options.incomplete();
  body.set(part,offset);offset+=part.length;
 }
 return body;
}

/** Own only the caller's accepted private path. Allocation/validation must finish
 * before ownership is acquired; never adopt a download result's path. Error
 * reporting precedes cleanup, late failures still clean up, and cleanup failure
 * does not replace the request result. The request settles after cleanup. */
export async function withOwnedPrivateFile<T>(
 source:string|(()=>Promise<string>),
 remove:(path:string)=>Promise<unknown>,
 work:(path:string)=>Promise<T>,
 failed:(error:unknown)=>void,
):Promise<T>{
 let path:string|undefined;
 try{
  path=typeof source==='string'?source:await source();
  return await work(path);
 }catch(error){failed(error);throw error;}
 finally{if(path!==undefined)await remove(path).catch(()=>{});}
}
