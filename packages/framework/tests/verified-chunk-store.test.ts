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
