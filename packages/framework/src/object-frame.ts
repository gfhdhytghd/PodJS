/** WRP2 data frames share an ordered stream with bounded length-prefixed JSON
 * controls. The caller supplies strict canonical header and control codecs. */
import type {FrameByteStream} from './frame-stream';
export const OBJECT_PART_BYTES=65536,OBJECT_HEADER_BYTES=4096,OBJECT_CONTROL_BYTES=262144;
const MAGIC=0x57525032;
export interface ObjectFrameCodecs<T,H> {
 encodeControl(value:T):Promise<Uint8Array>;decodeControl(bytes:Uint8Array):Promise<T>;
 encodeHeader(value:H):Uint8Array;decodeHeader(bytes:Uint8Array):H;
 partBytes(header:H):number;
 /** Must check scope/session/transfer/offset/hash before a caller persists. */
 validatePart(header:H,payload:Uint8Array):Promise<void>;
}
const readers=new WeakSet<object>(),writers=new WeakSet<object>();
export class ObjectFrameConnection<T,H> {
 private closed=false;
 constructor(private readonly stream:FrameByteStream,private readonly codecs:ObjectFrameCodecs<T,H>,private readonly enabled:()=>boolean,private readonly now=Date.now){}
 private check(){if(this.closed||!this.enabled())throw Error('framing_not_committed');}
 private async exact(size:number,deadline:number){
  const out=new Uint8Array(size);let at=0;
  while(at<size){this.check();const left=deadline-this.now();if(left<=0)throw Error('frame_timeout');const limit=Math.min(65536,size-at),part=await this.stream.read(limit,left);this.check();if(this.now()>deadline)throw Error('frame_timeout');if(!part?.length||part.length>limit)throw Error('truncated_frame');out.set(part,at);at+=part.length;}
  return out;
 }
 async read(timeoutMs:number):Promise<{kind:'control';value:T}|{kind:'part';header:H;payload:Uint8Array}>{
  this.check();if(readers.has(this.stream))throw Error('concurrent_frame_read');if(!Number.isFinite(timeoutMs)||timeoutMs<=0)throw Error('frame_timeout');readers.add(this.stream);
  try{
   const deadline=this.now()+timeoutMs,prefix=await this.exact(4,deadline),n=new DataView(prefix.buffer).getUint32(0,false);
   if(n!==MAGIC){if(n<1||n>OBJECT_CONTROL_BYTES)throw Error('invalid_frame_length');const value=await this.codecs.decodeControl(await this.exact(n,deadline));this.check();return {kind:'control',value};}
   const size=new DataView((await this.exact(4,deadline)).buffer).getUint32(0,false);if(size<1||size>OBJECT_HEADER_BYTES)throw Error('invalid_header_length');
   const header=this.codecs.decodeHeader(await this.exact(size,deadline)),length=this.codecs.partBytes(header);if(!Number.isSafeInteger(length)||length<1||length>OBJECT_PART_BYTES)throw Error('invalid_part_length');
   const payload=await this.exact(length,deadline);await this.codecs.validatePart(header,payload);this.check();return {kind:'part',header,payload};
  }catch(e){await this.close();throw e;}finally{readers.delete(this.stream);}
 }
 private async write(build:()=>Promise<Uint8Array[]>){
  this.check();if(writers.has(this.stream))throw Error('concurrent_frame_write');writers.add(this.stream);
  try{const parts=await build();for(const part of parts)for(let at=0;at<part.length;at+=65536){this.check();await this.stream.write(part.subarray(at,at+65536));this.check();}}
  catch(e){await this.close();throw e;}finally{writers.delete(this.stream);}
 }
 writeControl(value:T){return this.write(async()=>{const body=(await this.codecs.encodeControl(value)).slice();if(body.length<1||body.length>OBJECT_CONTROL_BYTES)throw Error('invalid_frame_length');const prefix=new Uint8Array(4);new DataView(prefix.buffer).setUint32(0,body.length,false);return [prefix,body];});}
 writePart(header:H,payload:Uint8Array){return this.write(async()=>{
  const raw=this.codecs.encodeHeader(header).slice(),part=payload.slice();if(raw.length<1||raw.length>OBJECT_HEADER_BYTES)throw Error('invalid_header_length');const captured=this.codecs.decodeHeader(raw);if(part.length<1||part.length>OBJECT_PART_BYTES||this.codecs.partBytes(captured)!==part.length)throw Error('invalid_part_length');
  await this.codecs.validatePart(captured,part);const prefix=new Uint8Array(8),v=new DataView(prefix.buffer);v.setUint32(0,MAGIC,false);v.setUint32(4,raw.length,false);return [prefix,raw,part];
 });}
 async close(){if(!this.closed){this.closed=true;await this.stream.close();}}
}

/** Strict membership for a bounded immutable-object batch. The caller binds
 * scope/window and verifies each returned object's digest before reuse. */
export function decodeObjectBatch(expected:readonly string[],response:{objects:any[];nextOids:string[]},decode:(value:string)=>Uint8Array,verify:(id:string,bytes:Uint8Array)=>void){
 if(!Array.isArray(response.objects)||!Array.isArray(response.nextOids)||new Set(expected).size!==expected.length)throw Error('invalid_schema');
 const remaining=new Set(expected),objects=new Map<string,Uint8Array>();let prior=-1;
 for(const item of response.objects){if(!item||Object.keys(item).sort().join(',')!=='bytes,oid'||typeof item.oid!=='string'||typeof item.bytes!=='string'||!remaining.delete(item.oid))throw Error('invalid_schema');const index=expected.indexOf(item.oid);if(index<=prior)throw Error('invalid_schema');prior=index;const bytes=decode(item.bytes);verify(item.oid,bytes);objects.set(item.oid,bytes);}
 prior=-1;for(const id of response.nextOids){if(!remaining.delete(id))throw Error('invalid_schema');const index=expected.indexOf(id);if(index<=prior)throw Error('invalid_schema');prior=index;}
 if(remaining.size)throw Error('invalid_schema');return {objects,nextOids:response.nextOids};
}
