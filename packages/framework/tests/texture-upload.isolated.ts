import {expect,test,mock} from 'bun:test';
const calls:unknown[][]=[];let host:any={uploadTexture:(...args:unknown[])=>{calls.push(['rgba',...args]);return 9;},freeTexture:(h:number)=>calls.push(['free',h])};
const realHost=await import('@pocketjs/framework/host');
mock.module('@pocketjs/framework/host',()=>({...realHost,getOps:()=>host}));
const {createTextureUploader}=await import('../src/texture-upload.ts');
test('sampled RGBA preserves byte identity and arguments; only unavailable results fall back',()=>{
 const bytes=Uint8Array.of(255,0,128,255),u=createTextureUploader();calls.length=0;
 expect(u.uploadRgba(bytes,1,1)).toBe(9);expect(calls).toEqual([['rgba',bytes,1,1,3]]);
 for(const handle of [0,4,-1,null,undefined]){
  calls.length=0;host.uploadTextureSampled=(...args:unknown[])=>{calls.push(['sampled',...args]);return handle;};
  expect(u.uploadRgba(bytes,1,1,{psm:2,sample:8})).toBe(handle??9);
  expect(calls[0]).toEqual(['sampled',bytes,1,1,2,8]);expect(calls.length).toBe(handle==null?2:1);expect(calls[0]?.[1]).toBe(bytes);
 }
 calls.length=0;host.uploadTextureSampled=()=>{throw Error('upload failed');};expect(()=>u.uploadRgba(bytes,1,1,{sample:1})).toThrow('upload failed');expect(calls).toEqual([]);
 delete host.uploadTextureSampled;
});
test('file ownership transfers before native invocation and restores only when unavailable',()=>{
 for(const outcome of [null,-1,0,12,'throw'] as const){
  const events:unknown[]=[];let owned=true;
  const u=createTextureUploader({uploadFile:(...args)=>{events.push(['upload',owned,...args]);if(outcome==='throw')throw Error('host failed');return outcome;}});
  const run=()=>u.uploadFile('scratch/pixels',2,4,{take:()=>{events.push('take');owned=false;},unavailable:()=>{events.push('restore');owned=true;}},3);
  if(outcome==='throw')expect(run).toThrow('host failed');else expect(run()).toBe(outcome);
  expect(events).toEqual(['take',['upload',false,'scratch/pixels',2,4,3],...(outcome===null?['restore']:[])]);expect(owned).toBe(outcome===null);
 }
 // A caller may deliberately keep the old consume-on-unavailable policy.
 let owned=true;createTextureUploader({uploadFile:()=>null}).uploadFile('x',1,1,{take:()=>{owned=false;}});expect(owned).toBe(false);
});
test('default file binding distinguishes real host absence, rejection, success and throw',()=>{
 const prior=(globalThis as any).pod;const events:unknown[]=[];const u=createTextureUploader();
 const ownership={take:()=>events.push('take'),unavailable:()=>events.push('restore')};
 try{
  delete (globalThis as any).pod;expect(u.uploadFile('x',1,1,ownership)).toBeNull();expect(events).toEqual(['take','restore']);
  for(const handle of [-1,0,77]){events.length=0;(globalThis as any).pod={uploadTextureFile:()=>{events.push('host');return handle;}};expect(u.uploadFile('x',1,1,ownership)).toBe(handle);expect(events).toEqual(['take','host']);}
  events.length=0;(globalThis as any).pod={uploadTextureFile:()=>{events.push('host');throw Error('native');}};expect(()=>u.uploadFile('x',1,1,ownership)).toThrow('native');expect(events).toEqual(['take','host']);
 }finally{(globalThis as any).pod=prior;}
});
test('release has no implicit scheduling; caller strategy and current default host determine exact timing',()=>{
 calls.length=0;const defaultUpload=createTextureUploader(),bytes=new Uint8Array(4);
 const h=defaultUpload.uploadRgba(bytes,1,1);expect(calls.map(c=>c[0])).toEqual(['rgba']);
 const oldHost=host;try{host={...host,freeTexture:(h:number)=>calls.push(['replacement-free',h])};defaultUpload.release(h);expect(calls.at(-1)).toEqual(['replacement-free',9]);}finally{host=oldHost;}
 const queued:number[]=[];const deferred=createTextureUploader({ops:()=>host,release:h=>queued.push(h)});
 calls.length=0;const handle=deferred.uploadRgba(bytes,1,1);deferred.release(handle);expect(queued).toEqual([9]);expect(calls.map(c=>c[0])).toEqual(['rgba']);
 host.freeTexture(queued.shift()!);expect(calls.at(-1)).toEqual(['free',9]);
 const oldFree=host.freeTexture;try{delete host.freeTexture;expect(()=>defaultUpload.release(0)).not.toThrow();}finally{host.freeTexture=oldFree;}
});
