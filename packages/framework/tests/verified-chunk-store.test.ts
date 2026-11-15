import {test,expect} from 'bun:test';
import {VerifiedChunkStore,VERIFIED_CHUNK_METHODS,type ChunkHost} from '../src/verified-chunk-store';
import {ResumableObject} from '../src/resumable-object';
import {platformMethods} from '../src/platform-contract';
import {PodCapability,POD_TARGETS} from '../src/targets';
import {readFileSync} from 'node:fs';
import {javaHostMethods,literalHostMethods} from '../../cli/src/host-method-contract';
const runtime={version:1,methods:[...VERIFIED_CHUNK_METHODS],chunkBytes:65536,maxObjectBytes:16777216,maxInFlightObjects:4,maxStagingBytes:134217728,maxJournalBytes:16777216,durable:true,fencing:true,elapsedMillis:1234};
test('all five methods require the distinct sandbox capability in JS/native/worker catalogues',()=>{
 for(const method of VERIFIED_CHUNK_METHODS)expect(platformMethods[method]).toBe('data.files.resumable');
 expect(POD_TARGETS['android-watch'].capabilities).toContain(PodCapability.ResumableObjects);
 const native=readFileSync(new URL('../../../platforms/android/runtime/src/main/java/dev/podjs/runtime/PodServices.java',import.meta.url),'utf8');expect(native).toContain('resumableObjects.execute(method,args)');
 const worker=javaHostMethods(readFileSync(new URL('../../../platforms/android/runtime/src/main/java/dev/podjs/runtime/PodCloudWorker.java',import.meta.url),'utf8'));
 for(const method of VERIFIED_CHUNK_METHODS)expect(worker.has(method)).toBe(true);
 expect(literalHostMethods([{path:'port.ts',text:"request('file.resumable.stage',{});"}]).has('file.resumable.stage')).toBe(true);
});
test('capability readiness checks actual host methods and guarantees, including native elapsed time',async()=>{
 for(const change of [{methods:runtime.methods.slice(1)},{durable:false},{fencing:false},{version:2},{elapsedMillis:undefined},{maxInFlightObjects:5}]){
  const host:ChunkHost=async<T>()=>({...runtime,...change}) as T;expect(await new VerifiedChunkStore(host).runtime()).toBeUndefined();
 }
 expect(await new VerifiedChunkStore(async<T>()=>runtime as T).runtime()).toEqual(runtime);
 expect(await new VerifiedChunkStore(async()=>{throw Error('unsupported_method');}).runtime()).toBeUndefined();
});
test('facade binds local lease/object/request and never labels file publication APPLIED',async()=>{
 const calls:{method:string;args:Record<string,unknown>}[]=[],object={id:'a'.repeat(64),size:1,sha256:'b'.repeat(64),chunkHashes:['c'.repeat(64)]};
 const host:ChunkHost=async<T>(method,args)=>{calls.push({method,args});return (args.operation==='acquire'?{epoch:2}:method.endsWith('finalize')?{path:'resumable-published/x',size:1,sha256:object.sha256}:{state:'PARTIAL',missing:[]}) as T;};
 const store=new VerifiedChunkStore(host),lease=await store.acquire('d'.repeat(64),'e'.repeat(32)),facade=new ResumableObject(store,lease,object);
 await facade.stage(0,new Uint8Array([120]),{id:'f'.repeat(32),digest:'0'.repeat(64)});expect(calls.at(-1)?.args).toMatchObject({...lease,object,index:0,dataBase64:'eA==',bindingId:'f'.repeat(32)});
 expect(await facade.finalize()).not.toHaveProperty('state');
 await expect(facade.stage(1,new Uint8Array([1]),{id:'f'.repeat(32),digest:'0'.repeat(64)})).rejects.toThrow('invalid_verified_chunk');
 await expect(store.acquire('../escape','e'.repeat(32))).rejects.toThrow('invalid_object_authorization');
 await store.revokeWrites(lease);expect(calls.at(-1)?.args.operation).toBe('readOnly');
 await store.release(lease,object,true);expect(calls.at(-1)?.args.discardPublication).toBe(true);
});
test('native structured failures preserve the closed error code without guessing transport text',async()=>{
 const store=new VerifiedChunkStore(async<T>()=>({error:'STALE_EPOCH'}) as T);
 await expect(store.check({scope:'a'.repeat(64),connectionId:'b'.repeat(32),epoch:1})).rejects.toThrow('STALE_EPOCH');
 expect(await store.runtime()).toBeUndefined();
});

test('RVRESUMEC-IMPL-06 facade reserves four copies within unchanged 32 MiB core quota',async()=>{
 const calls:Record<string,unknown>[]=[];const store=new VerifiedChunkStore(async<T>(_m,a)=>{calls.push(a);return {epoch:1} as T;});
 await store.acquire('a'.repeat(64),'b'.repeat(32));expect(calls[0]!.maxStagingBytes).toBe(64*1024*1024);
 const native=readFileSync(new URL('../../../platforms/android/runtime/src/main/java/dev/podjs/runtime/PodResumableObjects.java',import.meta.url),'utf8'),core=readFileSync(new URL('../../../platforms/android/runtime/src/main/java/dev/podjs/runtime/PodSyncFileReceiver.java',import.meta.url),'utf8');
 expect(native).toContain('maxStagingBytes",64*1024*1024');expect(native).toContain('budget=67108864');expect(native).toContain('reserved+m.getLong("size")*4>budget');expect(native).toContain('catch(PodSyncFileReceiver.QuotaException capacity){throw new IOException("WINDOW_FULL");}');
 expect(core).toContain('reserved > 32L * 1024 * 1024');expect(core).toContain('throw new QuotaException("Application file quota exceeded")');
 // Two maximum-size objects require 128 MiB four-copy reservation. The native
 // AVD regression exercises these actual interleaved offers and WINDOW_FULL.
 expect(2*16777216*4).toBeGreaterThan(Number(calls[0]!.maxStagingBytes));
});
