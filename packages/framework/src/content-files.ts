import {encodeBase64,decodeBase64} from './byte-codecs';
export type ContentHost=<T>(method:string,args:Record<string,unknown>)=>Promise<T>;
export const CONTENT_BATCH_ITEMS=32,CONTENT_BATCH_BYTES=262144,CONTENT_FRAME_BYTES=65536;
export interface ContentFileStat {path:string;exists:boolean;size:number;durableOffset:number;checkpoint:string|null;}
/** Host checks bounds again. One frame + checkpoint is one durable operation. */
export class ContentFiles {
 constructor(readonly host:ContentHost){}
 async stat(paths:string[]):Promise<ContentFileStat[]>{
  if(paths.length>CONTENT_BATCH_ITEMS)throw Error('content_batch_limit');return (await this.host<{entries:ContentFileStat[]}>('content.files.stat',{paths})).entries;
 }
 async read(ranges:{path:string;offset:number;length:number}[]):Promise<Uint8Array[]>{
  if(ranges.length>CONTENT_BATCH_ITEMS||ranges.reduce((n,r)=>n+r.length,0)>CONTENT_BATCH_BYTES)throw Error('content_batch_limit');
  const value=await this.host<{entries:{dataBase64:string}[]}>('content.files.read',{ranges});
  if(value.entries.length!==ranges.length)throw Error('invalid_content_batch');
  return value.entries.map((v,i)=>{const b=decodeBase64(v.dataBase64);if(b.length!==ranges[i]!.length)throw Error('invalid_content_batch');return b;});
 }
 async frame(path:string,offset:number,bytes:Uint8Array,checkpoint:string){
  if(bytes.length>CONTENT_FRAME_BYTES||!bytes.length||offset%CONTENT_FRAME_BYTES||checkpoint.length>8192)throw Error('invalid_content_frame');
  const result=await this.host<{durableOffset:number}>('content.files.frame',{path,offset,dataBase64:encodeBase64(bytes),checkpoint});
  if(result.durableOffset!==offset+bytes.length)throw Error('invalid_content_frame');return result.durableOffset;
 }
 async publish(path:string,bytes:Uint8Array,checkpoint?:string){if(bytes.length>266264)throw Error('content_batch_limit');return this.host('content.files.publish',{path,dataBase64:encodeBase64(bytes),...(checkpoint===undefined?{}:{checkpoint})});}
}
