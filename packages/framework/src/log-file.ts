import {fileChunks} from './services.ts';
import {base64ToBytes,encodeBase64,encodeUtf8Async,decodeUtf8Async} from './bytes.ts';
import {yieldWorkTurn} from './text-layout.ts';
const yieldCodec=async()=>{await yieldWorkTurn().result;};
import type {LogFile} from './logging.ts';
export type LogFilePorts=Pick<typeof fileChunks,'stat'|'read'|'write'|'append'|'createTemp'|'hash'|'commit'|'remove'>;
/** Bounded UTF-8 transfers and hash-checked atomic replacement. Storage names and copy are caller policy. */
export function createHostLogFile(options:{path:string;temporaryDirectory:string;chunkBytes?:number;messages?:{write:string;read:string}},files:LogFilePorts=fileChunks):LogFile {
 const chunkBytes=options.chunkBytes??65536;
 if(!Number.isSafeInteger(chunkBytes)||chunkBytes<1||chunkBytes>65536)throw new RangeError('invalid log chunk bound');
 const append=async(path:string,text:string,offset:number)=>{
  const bytes=await encodeUtf8Async(text,'replacement',{yield:yieldCodec});
  for(let start=0;start<bytes.length;start+=chunkBytes){const part=bytes.subarray(start,start+chunkBytes);const result=await files.append(path,encodeBase64(part),offset+start);if(result.size!==offset+start+part.length)throw Error(options.messages?.write??'incomplete_log_write');}
 };
 return {
  async size(){const stat=await files.stat(options.path);return stat.exists?stat.size:0;},
  async read(){
   const stat=await files.stat(options.path);if(!stat.exists||!stat.size)return null;
   const bytes=new Uint8Array(stat.size);
   for(let offset=0;offset<bytes.length;){const chunk=await files.read(options.path,offset,Math.min(chunkBytes,bytes.length-offset));const part=base64ToBytes(chunk.dataBase64);if(chunk.offset!==offset||chunk.size!==bytes.length||!part.length||part.length>bytes.length-offset)throw Error(options.messages?.read??'incomplete_log_read');bytes.set(part,offset);offset+=part.length;}
   return decodeUtf8Async(bytes,{yield:yieldCodec});
  },
  async replace(text){
   const temp=await files.createTemp(options.temporaryDirectory);
   try{await append(temp.path,text,0);const hash=await files.hash(temp.path);await files.commit(temp.path,options.path,hash.size,hash.sha256);}
   finally{await files.remove(temp.path).catch(()=>{});}
  },
  async append(text,offset){if(offset===0&&!(await files.stat(options.path)).exists)await files.write(options.path,'');await append(options.path,text,offset);},
 };
}
