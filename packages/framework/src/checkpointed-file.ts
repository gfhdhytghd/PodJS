import {encodeBase64,encodeUtf8} from './byte-codecs';
export interface FileCheckpoint {
 scope?():Promise<string>;
 has(unit:string):boolean;
 commit(unit:string,bytes?:number):Promise<void>;
 check():Promise<void>;
}
export interface PublishedFile {path:string;size:number;sha256:string;}
const atomicHosts=new WeakMap<Function,boolean>();
const utf8UnsupportedHosts=new WeakSet<Function>();
function unsupported(error:unknown,method:string,optionalPermission=false){
 const message=String(error);
 return message==='Error: '+method||/unsupported.*(?:service|method)|unknown.*(?:service|method)|unexpected.*(?:host|method)/i.test(message)||
  optionalPermission&&/permission_denied|cloud_host_capability_unavailable/i.test(message);
}
const fileBinding=async(cp:FileCheckpoint,hash:(bytes:Uint8Array)=>Promise<string>,identity:string,bytes:number,digest:string)=>
 hash(encodeUtf8(JSON.stringify([await cp.scope!(),identity,bytes,digest])));

async function publishBytes(request:Function,bytes:Uint8Array,target:string,digest:string,durability:{syncDirectory?:boolean}):Promise<PublishedFile|undefined>{
 if((globalThis as {__podDisableAtomicFilePublish?:boolean}).__podDisableAtomicFilePublish||atomicHosts.get(request)===false)return;
 try{
  const result=await request('file.publishBytes',{target,dataBase64:encodeBase64(bytes),expectedSize:bytes.length,expectedSha256:digest,...durability});
  if(!result||result.path!==target||result.size!==bytes.length||result.sha256!==digest)throw Error('file_publication_invalid');
  return result;
 }catch(error){
  // Compatibility only. I/O, checksum and cancellation failures must propagate.
  if(unsupported(error,'file.publishBytes')){atomicHosts.set(request,false);return;}
  throw error;
 }
}
/** Immutable source, atomic per-piece publication, then atomic whole-file
 * publication. The caller owns the secure receipt transaction and scope.
 * Temporary writes never count as progress. No plaintext receipt is trusted
 * without a native hash comparison against the current immutable source.
 */
export async function checkpointedFile(request:<T>(method:string,args:Record<string,unknown>)=>Promise<T>,bytes:Uint8Array,options:{directory:string;identity:string;hash:(b:Uint8Array)=>Promise<string>;checkpoint:FileCheckpoint;target:(binding:string,digest:string)=>string;
 /** Flush a directory before returning a receipt callers may publish to SQLite. */
 syncDirectory?:boolean;
 /** A bounded group shares a journal receipt. Each file is still authenticated on retry. */
 piecesPerReceipt?:number;
}):Promise<PublishedFile>{
 const {checkpoint:cp,hash,piecesPerReceipt=1}=options;await cp.check();
 if(!Number.isInteger(piecesPerReceipt)||piecesPerReceipt<1||piecesPerReceipt>16)throw Error('invalid_file_receipt_group');
 const durability=options.syncDirectory?{syncDirectory:true}:{};
 if(!cp.scope||!Number.isSafeInteger(bytes.length)||bytes.length>256*1024*1024)throw Error('invalid_file_checkpoint');
 const digest=await hash(bytes),binding=await fileBinding(cp,hash,options.identity,bytes.length,digest);
 if(!/^[a-f0-9]{64}$/.test(digest)||!/^[a-f0-9]{64}$/.test(binding))throw Error('invalid_file_digest');
 const target=options.target(binding,digest),unit='file:'+binding,parts:{path:string;size:number;sha256:string}[]=[];
 if(bytes.length<=262144){const result=await publishBytes(request,bytes,target,digest,durability);if(result){if(!cp.has(unit))await cp.commit(unit,bytes.length);await cp.check();return result;}}
 const existing=await request<{exists:boolean;size:number}>('file.stat',{path:target});
 if(existing.exists&&existing.size===bytes.length){const actual=await request<PublishedFile>('file.hash',{path:target});if(actual.size===bytes.length&&actual.sha256===digest){if(!cp.has(unit))await cp.commit(unit,bytes.length);return {path:target,size:bytes.length,sha256:digest};}}
 // One bounded piece is already a complete atomic publication. Avoid a second
 // file, compose pass and receipt. A lost reply adopts the verified target above.
 if(bytes.length<=262144){
  const temp=await request<{path:string}>('file.createTemp',{directory:options.directory});
  try{
   await cp.check();const written=await request<{size:number}>('file.write',{path:temp.path,dataBase64:encodeBase64(bytes)});
   if(written.size!==bytes.length)throw Error('file_piece_incomplete');
   const result=await request<PublishedFile>('file.commit',{path:temp.path,target,expectedSize:bytes.length,expectedSha256:digest,...durability});
   if(result.path!==target||result.size!==bytes.length||result.sha256!==digest)throw Error('file_publication_invalid');
   if(!cp.has(unit))await cp.commit(unit,bytes.length);await cp.check();return result;
  }finally{await request('file.delete',{path:temp.path}).catch(()=>{});}
 }
 let groupStart=0;
 for(let offset=0;offset<bytes.length;offset+=262144){
  await cp.check();const piece=bytes.subarray(offset,offset+262144),sha256=await hash(piece),path=options.directory+'/part-'+binding+'-p'+offset+'.bin',part={path,size:piece.length,sha256};parts.push(part);
  // A file can survive a crash before its receipt. Authenticate and adopt it;
  // a stale/missing/tampered file is replaced without trusting an old receipt.
  const published=await publishBytes(request,piece,path,sha256,durability);
  const stat=published?{exists:true,size:piece.length}:await request<{exists:boolean;size:number}>('file.stat',{path});let valid=!!published;
  if(!valid&&stat.exists&&stat.size===piece.length){const actual=await request<PublishedFile>('file.hash',{path});valid=actual.size===piece.length&&actual.sha256===sha256;}
  if(!valid){const temp=await request<{path:string}>('file.createTemp',{directory:options.directory});
   try{const written=await request<{size:number}>('file.write',{path:temp.path,dataBase64:encodeBase64(piece)});if(written.size!==piece.length)throw Error('file_piece_incomplete');
    const published=await request<PublishedFile>('file.commit',{path:temp.path,target:path,expectedSize:piece.length,expectedSha256:sha256,...durability});if(published.path!==path||published.size!==piece.length||published.sha256!==sha256)throw Error('file_piece_publication_invalid');
   }finally{await request('file.delete',{path:temp.path}).catch(()=>{});}
  }
  const end=offset+piece.length;
  if(parts.length%piecesPerReceipt===0||end===bytes.length){
   const receipt=piecesPerReceipt===1?unit+':'+offset:unit+':pieces:'+groupStart+':'+end;
   if(!cp.has(receipt))await cp.commit(receipt,end-groupStart);
   groupStart=end;
  }
 }
 await cp.check();const result=await request<PublishedFile>('file.compose',{parts,target,expectedSize:bytes.length,expectedSha256:digest,...durability});
 if(result.path!==target||result.size!==bytes.length||result.sha256!==digest)throw Error('file_publication_invalid');
 if(!cp.has(unit))await cp.commit(unit,bytes.length);
 // Only complete publication can retire disposable piece files. A failed
 // cleanup cannot revoke its receipt; later reads still verify the final hash.
 for(const part of parts)await request('file.delete',{path:part.path}).catch(()=>{});
 await cp.check();return result;
}

/** Publish bounded Java-compatible UTF-8 with its digest in the immutable path.
 * Native hosts return after publication. Older hosts use the same byte receipt. */
export async function checkpointedUtf8File(
 request:<T>(method:string,args:Record<string,unknown>)=>Promise<T>,text:string,
 options:{directory:string;identity:string;hash:(bytes:Uint8Array)=>Promise<string>;
  checkpoint:FileCheckpoint;targetPattern:string;syncDirectory?:boolean;
  /** The enclosing transaction records the file after its directory is synced. */
  pageReceipt?:boolean},
):Promise<PublishedFile>{
 const {checkpoint:cp,targetPattern,hash,identity}=options;
 const marker='{sha256}';
 if(!cp.scope||!targetPattern.includes(marker)||targetPattern.indexOf(marker)!==targetPattern.lastIndexOf(marker))throw Error('invalid_file_checkpoint');
 await cp.check();
 const target=(_binding:string,digest:string)=>targetPattern.replace(marker,digest);
 if(text.length<=65536&&!utf8UnsupportedHosts.has(request)){
  let result:PublishedFile|undefined;
  try{result=await request<PublishedFile>('file.publishUtf8',{text,targetPattern,syncDirectory:!!options.syncDirectory});}
  catch(error){if(!unsupported(error,'file.publishUtf8',true))throw error;utf8UnsupportedHosts.add(request);}
  if(!utf8UnsupportedHosts.has(request)){
   if(!result||!Number.isSafeInteger(result.size)||result.size<0||result.size>text.length*3||
      !/^[a-f0-9]{64}$/.test(result.sha256)||result.path!==target('',result.sha256))throw Error('file_publication_invalid');
   if(!options.pageReceipt){
    const binding=await fileBinding(cp,hash,identity,result.size,result.sha256);
    if(!/^[a-f0-9]{64}$/.test(binding))throw Error('invalid_file_digest');
    const unit='file:'+binding;
    if(!cp.has(unit))await cp.commit(unit,result.size);
   }
   await cp.check();
   return result;
  }
 }
 return checkpointedFile(request,encodeUtf8(text,'question'),{...options,target});
}
