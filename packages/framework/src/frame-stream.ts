export interface FrameTiming {bytes:number;readMs:number;decodeMs:number;encodeMs:number;writeMs:number;direction:'read'|'write'}
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
 private timing:((sample:FrameTiming)=>void)|undefined;
 setFrameTiming(listener:((sample:FrameTiming)=>void)|undefined){this.timing=listener;}
 private reportTiming(sample:FrameTiming){try{this.timing?.(sample);}catch{/* Observability must not fail a transfer. */}}
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
   const began=this.now();const header=new Uint8Array(4);await this.readInto(header,deadline,false);this.progress?.({direction:'read',bytes:4});
   const length=new DataView(header.buffer).getInt32(0,false);if(length<1||length>this.codec.maxFrameBytes)throw new Error('Sync frame length out of range');
   const frame=new Uint8Array(length+4);frame.set(header);await this.readInto(frame.subarray(4),deadline);const received=this.now();const value=await this.codec.decode(frame,{cancelled:()=>this.closed});this.reportTiming({direction:'read',bytes:frame.length,readMs:Math.max(0,received-began),decodeMs:Math.max(0,this.now()-received),encodeMs:0,writeMs:0});return value;
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
   const began=this.now();const bytes=await this.codec.encode(frame,{cancelled:()=>this.closed});const encoded=this.now();
   const write=async(part:Uint8Array)=>{if(this.closed)throw new Error('Sync connection closed');await this.stream.write(part);if(this.closed)throw new Error('Sync connection closed');this.progress?.({direction:'write',bytes:part.length});};
   await write(bytes.subarray(0,4));
   for(let i=4;i<bytes.length;i+=(this.codec.chunkBytes??TRANSFER_CHUNK_BYTES))await write(bytes.subarray(i,i+(this.codec.chunkBytes??TRANSFER_CHUNK_BYTES)));
   this.reportTiming({direction:'write',bytes:bytes.length,readMs:0,decodeMs:0,encodeMs:Math.max(0,encoded-began),writeMs:Math.max(0,this.now()-encoded)});
  }catch(error){await this.close();throw error;}
  finally{this.writing=false;}
 }
 async close():Promise<void>{if(this.closed)return;this.closed=true;await this.stream.close();}
}


/** Framing treats the body as opaque bytes; wire formats and codecs belong to callers. */
export interface FrameFacadeErrors {
 length(length:number):Error;
 prefix():Error;
 size():Error;
 failed():Error;
 truncated():Error;
 finished():Error;
}
export interface FrameFacadeOptions<T, Work> {
 maxFrameBytes:number;
 /** Four-byte big-endian length. Default is signed. */
 prefix?:'signed'|'unsigned';
 encodeBody(value:T):Uint8Array;
 decodeBody(bytes:Uint8Array):T;
 encodeBodyAsync(value:T,work:Work):Promise<Uint8Array>;
 decodeBodyAsync(bytes:Uint8Array,work:Work):Promise<T>;
 errors?:Partial<FrameFacadeErrors>;
 /** Push errors discard pending bytes; false permits a new frame after an error. */
 poisonOnPushError?:boolean;
 /** A truncated finish normally poisons the decoder. */
 poisonOnFinishError?:boolean;
 /** A successful finish normally closes the decoder. False permits further pushes. */
 closeOnFinish?:boolean;
}
export function createFrameFacade<T, Work>(options:FrameFacadeOptions<T,Work>) {
 const signed=options.prefix!=='unsigned',max=options.maxFrameBytes;
 if(!Number.isSafeInteger(max)||max<1||max>(signed?0x7fffffff:0xffffffff))throw new RangeError('Invalid frame byte limit');
 const errors:FrameFacadeErrors={
  length:n=>new RangeError(`Frame length out of range: ${n}`),prefix:()=>new Error('Truncated frame prefix'),
  size:()=>new Error('Truncated or trailing frame bytes'),failed:()=>new Error('Frame stream already failed'),
  truncated:()=>new Error('Truncated frame stream'),finished:()=>new Error('Frame stream finished'),...options.errors,
 };
 const validate=(n:number)=>{if(n<1||n>max)throw errors.length(n);return n;};
 const length=(bytes:Uint8Array)=>{const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);return validate(signed?view.getInt32(0,false):view.getUint32(0,false));};
 const wrap=(body:Uint8Array)=>{
  validate(body.byteLength);const frame=new Uint8Array(4+body.byteLength),view=new DataView(frame.buffer);
  if(signed)view.setInt32(0,body.byteLength,false);else view.setUint32(0,body.byteLength,false);
  frame.set(body,4);return frame;
 };
 const unwrap=(frame:Uint8Array)=>{
  if(frame.byteLength<4)throw errors.prefix();const size=length(frame);
  if(frame.byteLength!==4+size)throw errors.size();return frame.subarray(4);
 };
 class FrameDecoder {
  private readonly header=new Uint8Array(4);
  private headerSize=0;
  private body:Uint8Array|null=null;
  private bodySize=0;
  private failed=false;
  private finished=false;
  private check(){if(this.failed)throw errors.failed();if(this.finished)throw errors.finished();}
  push(bytes:Uint8Array):T[]{
   this.check();const frames:T[]=[];let offset=0;
   try{
    while(offset<bytes.length){
     if(!this.body){
      const count=Math.min(4-this.headerSize,bytes.length-offset);
      this.header.set(bytes.subarray(offset,offset+count),this.headerSize);this.headerSize+=count;offset+=count;
      if(this.headerSize<4)break;
      this.body=new Uint8Array(length(this.header));this.bodySize=0;
     }
     const count=Math.min(this.body.length-this.bodySize,bytes.length-offset);
     this.body.set(bytes.subarray(offset,offset+count),this.bodySize);offset+=count;this.bodySize+=count;
     if(this.bodySize===this.body.length){frames.push(options.decodeBody(this.body));this.body=null;this.headerSize=0;this.bodySize=0;}
    }
    return frames;
   }catch(error){
    this.body=null;
    if(options.poisonOnPushError!==false)this.failed=true;
    else {this.headerSize=0;this.bodySize=0;}
    throw error;
   }
  }
  finish():void {
   this.check();
   if(this.headerSize||this.body){if(options.poisonOnFinishError!==false){this.failed=true;this.body=null;}throw errors.truncated();}
   if(options.closeOnFinish!==false)this.finished=true;
  }
 }
 return {
  encodeFrame:(value:T)=>wrap(options.encodeBody(value)),
  encodeFrameAsync:async(value:T,work:Work)=>wrap(await options.encodeBodyAsync(value,work)),
  decodeFrame:(frame:Uint8Array)=>options.decodeBody(unwrap(frame)),
  decodeFrameAsync:async(frame:Uint8Array,work:Work)=>options.decodeBodyAsync(unwrap(frame),work),
  FrameDecoder,
 };
}
