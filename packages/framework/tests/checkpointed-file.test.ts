import {test,expect} from 'bun:test';
import {createHash} from 'node:crypto';
import {checkpointedFile} from '../src/checkpointed-file';
const hash=async(b:Uint8Array)=>createHash('sha256').update(b).digest('hex');
function fixture(){
 const files=new Map<string,Uint8Array>(),receipts=new Set<string>(),writes=new Map<string,number>();let serial=0,interrupt='',interruptAt=1,scope='scope-a';
 const request=async<T>(method:string,a:any):Promise<T>=>{let r:any;
  if(method==='file.createTemp'){const path=a.directory+'/.pod-write-'+ ++serial+'.part';files.set(path,new Uint8Array());r={path};}
  else if(method==='file.stat')r={exists:files.has(a.path),size:files.get(a.path)?.length??0};
  else if(method==='file.hash'){const b=files.get(a.path)!;r={size:b.length,sha256:await hash(b)};}
  else if(method==='file.write'){const b=new Uint8Array(Buffer.from(a.dataBase64,'base64'));files.set(a.path,b);r={size:b.length};}
  else if(method==='file.commit'){const b=files.get(a.path)!;expect(await hash(b)).toBe(a.expectedSha256);expect(b.length).toBe(a.expectedSize);files.set(a.target,b);files.delete(a.path);writes.set(a.target,(writes.get(a.target)??0)+1);r={path:a.target,size:b.length,sha256:await hash(b)};if(interrupt==='file'&&--interruptAt===0){interrupt='';throw Error('interrupt');}}
  else if(method==='file.compose'){const b=new Uint8Array(Buffer.concat(a.parts.map((p:any)=>{const b=files.get(p.path)!;expect(b.length).toBe(p.size);return b;})));expect(await hash(b)).toBe(a.expectedSha256);expect(b.length).toBe(a.expectedSize);files.set(a.target,b);r={path:a.target,size:b.length,sha256:await hash(b)};if(interrupt==='final'){interrupt='';throw Error('interrupt');}}
  else if(method==='file.delete'){files.delete(a.path);r={};}else throw Error(method);return r as T;
 };
 const checkpoint={scope:async()=>scope,has:(u:string)=>receipts.has(u),check:async()=>{},commit:async(u:string)=>{if(interrupt==='before-receipt'&&--interruptAt===0){interrupt='';throw Error('interrupt');}receipts.add(u);if(interrupt==='receipt'&&--interruptAt===0){interrupt='';throw Error('interrupt');}}};
 const run=(bytes:Uint8Array)=>checkpointedFile(request,bytes,{checkpoint,directory:'private',identity:'immutable-input',hash,target:(id,digest)=>'private/'+id+'-'+digest});
 return {files,receipts,writes,checkpoint,run,stop:(s:string,n=1)=>{interrupt=s;interruptAt=n;},scope:(s:string)=>{scope=s;}};
}
for(const boundary of ['file','before-receipt','receipt','final'])for(let part=1;part<=(boundary==='final'?1:4);part++)test('restart after '+boundary+' piece '+part+' authenticates and adopts immutable pieces without rewriting them',async()=>{
 const f=fixture(),body=new Uint8Array(3*262144+17).fill(37);f.files.set('private/old',new Uint8Array([9]));f.stop(boundary,part);await expect(f.run(body)).rejects.toThrow('interrupt');expect(f.files.get('private/old')).toEqual(new Uint8Array([9]));
 const result=await f.run(body);expect(f.files.get(result.path)).toEqual(body);expect([...f.writes.values()].every(n=>n===1)).toBe(true);const before=f.writes.size;expect(await f.run(body)).toEqual(result);expect(f.writes.size).toBe(before);
});
test('tampered piece is repaired; a changed scope/input cannot adopt old publication',async()=>{
 const f=fixture(),body=new Uint8Array(262145).fill(19);f.stop('receipt');await expect(f.run(body)).rejects.toThrow();const part=[...f.files.keys()].find(p=>p.includes('-p0.bin'))!;f.files.get(part)![0]^=1;
 const result=await f.run(body);expect(f.files.get(result.path)).toEqual(body);expect(f.writes.get(part)).toBe(2);f.scope('scope-b');const other=await f.run(body);expect(other.path).not.toBe(result.path);body[0]^=1;expect((await f.run(body)).path).not.toBe(other.path);
});
test('failed temporary write cannot publish a receipt; empty input uses verified final publication',async()=>{
 const f=fixture(),body=new Uint8Array(1);f.checkpoint.check=async()=>{throw Error('cancelled');};await expect(f.run(body)).rejects.toThrow('cancelled');expect(f.receipts.size).toBe(0);expect(f.files.size).toBe(0);
 const empty=fixture(),result=await empty.run(new Uint8Array());expect(result.size).toBe(0);expect(empty.receipts.size).toBe(1);
});
