export interface DirectoryWitnessEntry {path:string;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}
/** Small immutable-resource directories: hash native metadata, never file
 * contents. An old host without a change witness cannot certify this domain.
 * Missing/changed resources still need normal content-integrity validation. */
export async function directoryWitness(paths:readonly string[],list:(path:string)=>Promise<{entries:DirectoryWitnessEntry[]}>,hash:(value:string)=>Promise<string>):Promise<string|undefined>{
 const rows:unknown[]=[];
 for(const path of [...paths].sort()){
  const {entries}=await list(path);if(entries.length>10000)return;
  const sorted=[...entries].sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0),seen=new Set<string>();
  for(const e of sorted){
   if(!e.path.startsWith(path+'/')||e.path.slice(path.length+1).includes('/')||seen.has(e.path)||!e.file||e.directory||!e.changeToken||!Number.isSafeInteger(e.size)||e.size<0)return;
   seen.add(e.path);rows.push([e.path,e.size,e.changeToken]);
  }
  rows.push([path,'end']);
 }
 return hash(JSON.stringify(rows));
}

export interface WitnessStat {exists:boolean;file:boolean;directory:boolean;size:number;modified:number;changeToken?:string}
/** One directory inventory per physical exchange. Revalidation uses only the
 * known directory/file stat tokens, including directory membership changes.
 * Missing tokens fail closed; no cached digest can hide a late new file. */
export async function directoryWitnessWindow(paths:readonly string[],list:(path:string)=>Promise<{entries:DirectoryWitnessEntry[]}>,stat:(path:string)=>Promise<WitnessStat>,hash:(value:string)=>Promise<string>){
 const files=new Map<string,string>(),directories=new Map<string,string|null>();
 for(const p of paths){const s=await stat(p);if(!s.exists){directories.set(p,null);continue;}if(!s.directory||!s.changeToken)return undefined;directories.set(p,s.changeToken);}
 const digest=await directoryWitness(paths,async p=>{const result=await list(p);for(const e of result.entries){if(!e.changeToken)return result;files.set(e.path,e.changeToken);}return result;},hash);
 if(!digest)return undefined;
 const valid=async()=>{
  for(const [p,token]of directories){const s=await stat(p);if(token===null?s.exists:!s.exists||!s.directory||s.changeToken!==token)return false;}
  // Independent metadata probes share a bounded host turn. Sequential awaits
  // cost one bridge/looper round trip per immutable file on a cold connection.
  // Drain the entire batch even on rejection, then preserve ordered failure semantics.
  const entries=[...files];
  for(let at=0;at<entries.length;at+=8){
   const results=await Promise.allSettled(entries.slice(at,at+8).map(async([p,token])=>{const s=await stat(p);return s.exists&&s.file&&s.changeToken===token;}));
   for(const r of results){if(r.status==='rejected')throw r.reason;if(!r.value)return false;}
  }
  return true;
 };
 if(!await valid())return undefined;
 return {digest,valid};
}
