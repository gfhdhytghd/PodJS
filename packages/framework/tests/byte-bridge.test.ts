import {test,expect,afterEach} from 'bun:test';
const events:any[]=[],requests:any[]=[],slots=new Map<number,Uint8Array>();let next=1;
const pod:any={byteBridgeVersion:()=>1,putBytes:(v:Uint8Array)=>{if(slots.size>=64)return 0;const id=next++;slots.set(id,v.slice());return id;},takeBytes:(id:number)=>{const b=slots.get(id);slots.delete(id);return b;},dropBytes:(id:number)=>slots.delete(id),emit:(line:string)=>requests.push(JSON.parse(line)),takeEvents:()=>JSON.stringify(events.splice(0)),capabilities:()=>JSON.stringify(['net.stream','data.files.chunks','data.files.resumable'])};
(globalThis as any).pod=pod;
const {__pumpPodEvents}=await import('../src/watch');
const {byteHostOperation,byteStreams,fileChunks,hasByteBridge,serviceResourceCounts}=await import('../src/services');
const {VerifiedChunkStore}=await import('../src/verified-chunk-store');
const complete=(request:any,value:any,bytes?:Uint8Array)=>{events.push({t:'service.result',id:request.id,ok:true,value,...(bytes?{byteHandle:pod.putBytes(bytes)}:{})});__pumpPodEvents();};
afterEach(()=>{expect(slots.size).toBe(0);expect(serviceResourceCounts().pending).toBe(0);requests.length=0;events.length=0;pod.byteBridgeVersion=()=>1;});
test('binary snapshots respect subarray, empty, 1 B, 64 KiB and maximum; old write stays Base64',async()=>{
 for(const size of [0,1,65535,65536]){
  const original=Uint8Array.from({length:size+2},(_,i)=>i%256),view=original.subarray(1,size+1),expected=view.slice();
  const op=byteHostOperation<{size:number}>('file.write',{path:'a'},view),r=requests.at(-1);original.fill(7);
  expect(r.method).toBe('file.write.bytes');expect(r.args.dataBase64).toBeUndefined();expect(pod.takeBytes(r.args.byteHandle)).toEqual(expected);complete(r,{size});await op.result;
  const old=byteStreams.write('stream',expected),legacy=requests.at(-1);expect(legacy.method).toBe('stream.write');expect(Buffer.from(legacy.args.dataBase64,'base64')).toEqual(Buffer.from(expected));complete(legacy,{written:size});await old.result;
 }
 expect(()=>byteHostOperation('file.write',{},new Uint8Array(65537))).toThrow('Invalid byte attachment');
});
test('read byte differential, EOF and file range; unsupported host falls back before any mutation',async()=>{
 for(const size of [0,1,65536]){const bytes=Uint8Array.from({length:size},(_,i)=>i%256),op=byteStreams.readBytes('s',65536,1000),r=requests.at(-1);complete(r,{eof:size===0},bytes);expect((await op.result).bytes).toEqual(bytes);}
 const f=fileChunks.readBytes('a',7,1),r=requests.at(-1);complete(r,{size:8,offset:7,eof:true},new Uint8Array([255]));expect((await f).bytes[0]).toBe(255);
 pod.byteBridgeVersion=()=>0;expect(hasByteBridge()).toBe(false);const old=byteStreams.readBytes('s',1,1000),legacy=requests.at(-1);expect(legacy.method).toBe('stream.read');complete(legacy,{eof:false,dataBase64:'/w=='});expect((await old.result).bytes[0]).toBe(255);
});
test('cancel, late completions, native failure and denied capability release attachments without replay',async()=>{
 const op=byteHostOperation('file.write',{path:'a'},new Uint8Array([1])),r=requests.at(-1);op.cancel();await expect(op.result).rejects.toMatchObject({code:'cancelled'});complete(r,{size:1},new Uint8Array([2]));
 const failed=byteHostOperation('file.write',{},new Uint8Array([3])),q=requests.at(-1);events.push({t:'service.result',id:q.id,ok:false,code:'host_error'});__pumpPodEvents();await expect(failed.result).rejects.toMatchObject({code:'host_error'});expect(requests.filter(x=>x.method==='file.write')).toHaveLength(0);
 await expect(byteHostOperation('no.capability',{},new Uint8Array([9])).result).rejects.toMatchObject({code:'unsupported'});
});
test('full slots, missing reply attachment and emit failure fail closed and release snapshots',async()=>{
 const held=Array.from({length:64},()=>pod.putBytes(new Uint8Array([1])));
 expect(()=>byteHostOperation('file.write',{},new Uint8Array([1]))).toThrow('Byte bridge full');held.forEach(pod.dropBytes);
 const missing=byteStreams.readBytes('s',1,1000);complete(requests.at(-1),{eof:false});await expect(missing.result).rejects.toMatchObject({code:'invalid_bytes'});
 const emit=pod.emit;pod.emit=()=>{throw Error('emit failed');};
 try{await expect(byteHostOperation('file.write',{},new Uint8Array([1])).result).rejects.toThrow('emit failed');}finally{pod.emit=emit;}
});
test('synchronous argument construction failures release snapshots and preserve the original error',async()=>{
 const failure=Error('argument access failed'),bytes=new Uint8Array(65536);
 const getter={get path(){throw failure;}},proxy=new Proxy({},{ownKeys(){throw failure;}});
 for(const args of [getter,proxy])for(let i=0;i<128;i++){
  let caught:unknown;try{byteHostOperation('file.write',args,bytes);}catch(error){caught=error;}
  expect(caught).toBe(failure);expect(slots.size).toBe(0);expect(serviceResourceCounts().pending).toBe(0);
 }
 expect(requests).toHaveLength(0);
 const op=byteHostOperation('file.write',{path:'after-errors'},bytes),r=requests.at(-1);
 expect(pod.takeBytes(r.args.byteHandle)).toEqual(bytes);complete(r,{size:bytes.length});await op.result;
});
test('chunk store byte stage preserves metadata and errors; legacy stage stays canonical Base64',async()=>{
 const lease={scope:'a'.repeat(64),connectionId:'b'.repeat(32),epoch:1},object={id:'c'.repeat(64),size:1,sha256:'d'.repeat(64),chunkHashes:['e'.repeat(64)]},request={id:'f'.repeat(32),digest:'0'.repeat(64)},data=new Uint8Array([255]);
 let legacy:any,raw:any;
 const store=new VerifiedChunkStore(async(_,a)=>{legacy=a;return {state:'STAGED',missing:[]} as any;},async(m,a,b)=>{raw={m,a,b};return {state:'STAGED',missing:[]} as any;});
 await store.stage(lease,object,0,data,request);expect(raw.a.dataBase64).toBeUndefined();expect(raw.b).toBe(data);expect(raw.a.bindingId).toBe(request.id);
 const old=new VerifiedChunkStore(async(_,a)=>{legacy=a;return {state:'STAGED',missing:[]} as any;});await old.stage(lease,object,0,data,request);expect(legacy.dataBase64).toBe('/w==');
});
test('10000 completed operations retain zero attachment slots and zero promises',async()=>{
 for(let i=0;i<10000;i++){const op=byteStreams.writeBytes('s',new Uint8Array([i%256])),r=requests.at(-1);expect(pod.takeBytes(r.args.byteHandle)).toEqual(new Uint8Array([i%256]));complete(r,{written:1});await op.result;requests.length=0;}
});
