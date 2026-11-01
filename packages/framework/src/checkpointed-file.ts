import {encodeBase64,encodeUtf8} from './byte-codecs';
export interface FileCheckpoint {
 scope?():Promise<string>;
 has(unit:string):boolean;
 commit(unit:string,bytes?:number):Promise<void>;
 check():Promise<void>;
}
export interface PublishedFile {path:string;size:number;sha256:string;}
/** Immutable source, atomic per-piece publication, then atomic whole-file
 * publication. The caller owns the secure receipt transaction and scope.
 * Temporary writes never count as progress. No plaintext receipt is trusted
 * without a native hash comparison against the current immutable source.
 */
export async function checkpointedFile(request:<T>(method:string,args:Record<string,unknown>)=>Promise<T>,bytes:Uint8Array,options:{directory:string;identity:string;hash:(b:Uint8Array)=>Promise<string>;checkpoint:FileCheckpoint;target:(binding:string,digest:string)=>string}):Promise<PublishedFile>{
 const {checkpoint:cp,hash}=options;await cp.check();
 if(!cp.scope||!Number.isSafeInteger(bytes.length)||bytes.length>256*1024*1024)throw Error('invalid_file_checkpoint');
 const digest=await hash(bytes),binding=await hash(encodeUtf8(JSON.stringify([await cp.scope(),options.identity,bytes.length,digest])));
 if(!/^[a-f0-9]{64}$/.test(digest)||!/^[a-f0-9]{64}$/.test(binding))throw Error('invalid_file_digest');
 const target=options.target(binding,digest),unit='file:'+binding,parts:{path:string;size:number;sha256:string}[]=[];
 const existing=await request<{exists:boolean;size:number}>('file.stat',{path:target});
 if(existing.exists&&existing.size===bytes.length){const actual=await request<PublishedFile>('file.hash',{path:target});if(actual.size===bytes.length&&actual.sha256===digest){if(!cp.has(unit))await cp.commit(unit,bytes.length);return {path:target,size:bytes.length,sha256:digest};}}
 for(let offset=0;offset<bytes.length;offset+=262144){
  await cp.check();const piece=bytes.subarray(offset,offset+262144),sha256=await hash(piece),path=options.directory+'/part-'+binding+'-p'+offset+'.bin',part={path,size:piece.length,sha256};parts.push(part);
  // A file can survive a crash before its receipt. Authenticate and adopt it;
  // a stale/missing/tampered file is replaced without trusting an old receipt.
  const stat=await request<{exists:boolean;size:number}>('file.stat',{path});let valid=false;
  if(stat.exists&&stat.size===piece.length){const actual=await request<PublishedFile>('file.hash',{path});valid=actual.size===piece.length&&actual.sha256===sha256;}
  if(!valid){const temp=await request<{path:string}>('file.createTemp',{directory:options.directory});
   try{const written=await request<{size:number}>('file.write',{path:temp.path,dataBase64:encodeBase64(piece)});if(written.size!==piece.length)throw Error('file_piece_incomplete');
    const published=await request<PublishedFile>('file.commit',{path:temp.path,target:path,expectedSize:piece.length,expectedSha256:sha256});if(published.path!==path||published.size!==piece.length||published.sha256!==sha256)throw Error('file_piece_publication_invalid');
   }finally{await request('file.delete',{path:temp.path}).catch(()=>{});}
  }
  if(!cp.has(unit+':'+offset))await cp.commit(unit+':'+offset,piece.length);
 }
 await cp.check();const result=await request<PublishedFile>('file.compose',{parts,target,expectedSize:bytes.length,expectedSha256:digest});
 if(result.path!==target||result.size!==bytes.length||result.sha256!==digest)throw Error('file_publication_invalid');
 if(!cp.has(unit))await cp.commit(unit,bytes.length);
 // Only complete publication can retire disposable piece files. A failed
 // cleanup cannot revoke its receipt; later reads still verify the final hash.
 for(const part of parts)await request('file.delete',{path:part.path}).catch(()=>{});
 await cp.check();return result;
}
