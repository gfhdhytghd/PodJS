export interface TransferByteProgress {direction:'read'|'write';bytes:number}
export interface FrameCodec<T> {
 maxFrameBytes:number;chunkBytes?:number;
 encode(frame:T,options:{cancelled:()=>boolean}):Promise<Uint8Array>;
 decode(frame:Uint8Array,options:{cancelled:()=>boolean}):Promise<T>;
}
export const TRANSFER_CHUNK_BYTES=16*1024;
export interface FrameByteStream {
 /** Return at most maxBytes; null is EOF. Empty reads are not progress. */
 read(maxBytes:number,timeoutMs:number):Promise<Uint8Array|null>;
 /** Resolve only after all bytes have been handed to the transport. */
 write(bytes:Uint8Array):Promise<void>;
 close():Promise<void>;
}
/** A bounded, single-reader connection. Read deadlines cover the entire frame,
 * so a peer cannot extend a deadline by sending one byte at a time. */
export class StreamFrameConnection<T> {
 private closed=false;
 private reading=false;
 private writing=false;
 private progress:((progress:TransferByteProgress)=>void)|undefined;
 setTransferProgress(listener:((progress:TransferByteProgress)=>void)|undefined){this.progress=listener;}
 constructor(private readonly stream:FrameByteStream,private readonly codec:FrameCodec<T>,private readonly now=Date.now){
  if(!Number.isSafeInteger(codec.maxFrameBytes)||codec.maxFrameBytes<1||codec.maxFrameBytes>0x7fffffff)throw new RangeError('Invalid frame byte limit');
  if(codec.chunkBytes!==undefined&&(!Number.isSafeInteger(codec.chunkBytes)||codec.chunkBytes<1))throw new RangeError('Invalid frame chunk budget');
 }
 async readFrame(timeoutMs:number):Promise<T>{
  if(this.closed)throw new Error('Sync connection closed');if(this.reading)throw new Error('Concurrent sync frame reads');
  if(!Number.isFinite(timeoutMs)||timeoutMs<=0)throw new Error('Invalid sync read deadline');
  this.reading=true;const deadline=this.now()+timeoutMs;
  try{
   const header=new Uint8Array(4);await this.readInto(header,deadline,false);this.progress?.({direction:'read',bytes:4});
   const length=new DataView(header.buffer).getInt32(0,false);if(length<1||length>this.codec.maxFrameBytes)throw new Error('Sync frame length out of range');
   const frame=new Uint8Array(length+4);frame.set(header);await this.readInto(frame.subarray(4),deadline);return await this.codec.decode(frame,{cancelled:()=>this.closed});
  }catch(error){await this.close();throw error;}finally{this.reading=false;}
 }
 private async readInto(target:Uint8Array,deadline:number,report=true):Promise<void>{
  let offset=0;
  while(offset<target.length){
   if(this.closed)throw new Error('Sync connection closed');const remaining=deadline-this.now();if(remaining<=0)throw new Error('Sync frame read timed out');
   const max=Math.min(this.codec.chunkBytes??TRANSFER_CHUNK_BYTES,target.length-offset),bytes=await this.stream.read(max,remaining);
   if(this.closed)throw new Error('Sync connection closed');if(this.now()>deadline)throw new Error('Sync frame read timed out');
   if(bytes===null)throw new Error('Truncated sync stream');if(!bytes.length)throw new Error('Sync stream read made no progress');if(bytes.length>max)throw new Error('Sync stream exceeded read limit');
   target.set(bytes,offset);offset+=bytes.length;if(report)this.progress?.({direction:'read',bytes:bytes.length});
  }
 }
 async writeFrame(frame:T):Promise<void>{
  if(this.closed)throw new Error('Sync connection closed');if(this.writing)throw new Error('Concurrent sync frame writes');this.writing=true;
  try{
   const bytes=await this.codec.encode(frame,{cancelled:()=>this.closed});
   const write=async(part:Uint8Array)=>{if(this.closed)throw new Error('Sync connection closed');await this.stream.write(part);if(this.closed)throw new Error('Sync connection closed');this.progress?.({direction:'write',bytes:part.length});};
   await write(bytes.subarray(0,4));
   for(let i=4;i<bytes.length;i+=(this.codec.chunkBytes??TRANSFER_CHUNK_BYTES))await write(bytes.subarray(i,i+(this.codec.chunkBytes??TRANSFER_CHUNK_BYTES)));
  }catch(error){await this.close();throw error;}
  finally{this.writing=false;}
 }
 async close():Promise<void>{if(this.closed)return;this.closed=true;await this.stream.close();}
}
