import {test,expect} from 'bun:test';
import {createHash} from 'node:crypto';
import {checkpointedFile,checkpointedUtf8File} from '../src/checkpointed-file';
const hash=async(b:Uint8Array)=>createHash('sha256').update(b).digest('hex');
function fixture(){
 const files=new Map<string,Uint8Array>(),receipts=new Set<string>(),writes=new Map<string,number>(),durable=new Set<string>();let serial=0,interrupt='',interruptAt=1,scope='scope-a';
 const request=async<T>(method:string,a:any):Promise<T>=>{let r:any;
  if(a.syncDirectory)durable.add(a.target);
  if(method==='file.createTemp'){const path=a.directory+'/.pod-write-'+ ++serial+'.part';files.set(path,new Uint8Array());r={path};}
  else if(method==='file.stat')r={exists:files.has(a.path),size:files.get(a.path)?.length??0};
  else if(method==='file.hash'){const b=files.get(a.path)!;r={size:b.length,sha256:await hash(b)};}
  else if(method==='file.write'){const b=new Uint8Array(Buffer.from(a.dataBase64,'base64'));files.set(a.path,b);r={size:b.length};}
  else if(method==='file.commit'){const b=files.get(a.path)!;expect(await hash(b)).toBe(a.expectedSha256);expect(b.length).toBe(a.expectedSize);files.set(a.target,b);files.delete(a.path);writes.set(a.target,(writes.get(a.target)??0)+1);r={path:a.target,size:b.length,sha256:await hash(b)};if(interrupt==='file'&&--interruptAt===0){interrupt='';throw Error('interrupt');}}
  else if(method==='file.compose'){const b=new Uint8Array(Buffer.concat(a.parts.map((p:any)=>{const b=files.get(p.path)!;expect(b.length).toBe(p.size);return b;})));expect(await hash(b)).toBe(a.expectedSha256);expect(b.length).toBe(a.expectedSize);files.set(a.target,b);r={path:a.target,size:b.length,sha256:await hash(b)};if(interrupt==='final'){interrupt='';throw Error('interrupt');}}
  else if(method==='file.delete'){files.delete(a.path);r={};}else throw Error(method);return r as T;
 };
 const checkpoint={scope:async()=>scope,has:(u:string)=>receipts.has(u),check:async()=>{},commit:async(u:string)=>{if(interrupt==='before-receipt'&&--interruptAt===0){interrupt='';throw Error('interrupt');}receipts.add(u);if(interrupt==='receipt'&&--interruptAt===0){interrupt='';throw Error('interrupt');}}};
 const run=(bytes:Uint8Array,options:{piecesPerReceipt?:number;syncDirectory?:boolean}={})=>checkpointedFile(request,bytes,{checkpoint,directory:'private',identity:'immutable-input',hash,target:(id,digest)=>'private/'+id+'-'+digest,...options});
 return {request,files,receipts,writes,durable,checkpoint,run,stop:(s:string,n=1)=>{interrupt=s;interruptAt=n;},scope:(s:string)=>{scope=s;}};
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
test('bounded atomic host path adopts publication after a lost receipt and rejects forged replies',async()=>{
 const body=new Uint8Array(65536).fill(71),digest=await hash(body);let commits=0,calls=0,fail=true,forged=false;
 const request=async<T>(method:string,args:any):Promise<T>=>{expect(method).toBe('file.publishBytes');calls++;expect(new Uint8Array(Buffer.from(args.dataBase64,'base64'))).toEqual(body);return {path:args.target,size:body.length,sha256:forged?'f'.repeat(64):digest} as T;};
 const checkpoint={scope:async()=>'scope',has:()=>commits>0,check:async()=>{},commit:async()=>{if(fail){fail=false;throw Error('lost reply');}commits++;}};
 const run=()=>checkpointedFile(request,body,{checkpoint,hash,directory:'private',identity:'a',target:()=> 'private/final'});
 await expect(run()).rejects.toThrow('lost reply');expect(commits).toBe(0);expect((await run()).sha256).toBe(digest);expect(commits).toBe(1);expect(calls).toBe(2);
 forged=true;await expect(run()).rejects.toThrow('file_publication_invalid');expect(commits).toBe(1);
});

test('grouped receipts commit only durable pieces and authenticate an unreceipted prefix on retry',async()=>{
 const f=fixture(),body=new Uint8Array(5*262144+17).fill(19),options={piecesPerReceipt:4,syncDirectory:true};
 f.stop('file',3);
 await expect(f.run(body,options)).rejects.toThrow('interrupt');
 expect(f.receipts.size).toBe(0);
 const corrupt=[...f.files.keys()].find(p=>p.endsWith('-p262144.bin'))!;
 f.files.get(corrupt)![0]^=1;
 const result=await f.run(body,options);
 expect(f.files.get(result.path)).toEqual(body);
 expect(f.receipts.size).toBe(3);
 expect(f.writes.get(corrupt)).toBe(2);
 expect(f.writes.size).toBe(6);
 expect(f.durable.has(result.path)).toBe(true);
 expect([...f.writes.keys()].every(path=>f.durable.has(path))).toBe(true);
});

test('UTF-8 publication and compatibility fallback share one receipt and preserve Java encoding',async()=>{
 const f=fixture(),text='Reader 汉😀\ud800';
 const expected=new TextEncoder().encode('Reader 汉😀?'),sha256=await hash(expected),target='private/body-'+sha256+'.txt';
 let calls=0;const request=async<T>(method:string,args:Record<string,unknown>)=>{
  expect(method).toBe('file.publishUtf8');expect(args.text).toBe(text);expect(args.syncDirectory).toBe(true);calls++;
  return {path:target,size:expected.length,sha256} as T;
 };
 const options={directory:'private',identity:'bounded-text',targetPattern:'private/body-{sha256}.txt',checkpoint:f.checkpoint,hash,syncDirectory:true};
 expect((await checkpointedUtf8File(request,text,options)).path).toBe(target);
 const retry=async<T>(method:string,args:Record<string,unknown>)=>{
  if(method==='file.publishUtf8'){calls++;throw Error('permission_denied');}
  return f.request<T>(method,args);
 };
 expect((await checkpointedUtf8File(retry,text,options)).path).toBe(target);
 expect(f.receipts.size).toBe(1);
 expect(f.files.get(target)).toEqual(expected);
 await checkpointedUtf8File(retry,text,options);
 expect(calls).toBe(2);
 await expect(checkpointedUtf8File(async()=>{throw Error('storage failure');},text,options)).rejects.toThrow('storage failure');
});

test('a durable enclosing page may own the UTF-8 file receipt',async()=>{
 const f=fixture(),text='正文',sha256=await hash(new TextEncoder().encode(text));
 const request=async<T>()=>({path:'private/'+sha256,size:6,sha256}) as T;
 f.checkpoint.scope=async()=>{throw Error('individual file binding is unnecessary');};
 expect((await checkpointedUtf8File(request,text,{directory:'private',identity:'page-body',targetPattern:'private/{sha256}',
  checkpoint:f.checkpoint,hash,pageReceipt:true})).sha256).toBe(sha256);
 expect(f.receipts.size).toBe(0);
});
