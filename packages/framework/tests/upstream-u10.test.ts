import {expect,test} from 'bun:test';
import {FailClosedGate} from '../src/build-gate.ts';
import {CrownVolumeController,emptyCrownGuard,applyCrownVolumeGuard,scaledCrownVolumeDelta} from '../src/media-volume.ts';
import {waitForFlush,ExitFlushGuard} from '../src/async.ts';
import {readDownloadedBytes,readDownloadedChunks,withOwnedPrivateFile} from '../src/private-response.ts';
import {PreviewSession,type PreviewSessionOptions} from '../src/preview-session.ts';
import {textMatches,matchLines} from '../src/text-match.ts';
const settle=async()=>{for(let i=0;i<20;i++)await Promise.resolve();};
test('host capability gate shares one pending query and a rejected query remains closed',async()=>{
 let reads=0,done!:(value:unknown)=>void;const gate=new FailClosedGate(value=>value==='supported');const first=gate.initialize(()=>{reads++;return new Promise(resolve=>done=resolve);});expect(gate.initialize(async()=>true)).toBe(first);expect(gate.allowed).toBe(false);await settle();expect(reads).toBe(1);done('supported');expect(await first).toBe(true);expect(gate.allowed).toBe(true);
 const failed=new FailClosedGate(value=>value===true);expect(await failed.initialize(async()=>{throw Error('unsupported');})).toBe(false);expect(await failed.initialize(async()=>true)).toBe(false);
});
test('volume Float profile and timer values are supplied by a non-reader application',async()=>{
 const profile={sessionGapMs:100,capFraction:.5,rawScale:.02,rangeScale:.1,hideMs:77};
 const first=applyCrownVolumeGuard(0,9,0,10,true,emptyCrownGuard(),1,profile);expect(first.target).toBe(5);expect(first.notify).toBe(true);
 expect(applyCrownVolumeGuard(5,9,0,10,true,first.state,101,profile).target).toBe(5);expect(applyCrownVolumeGuard(5,9,0,10,true,first.state,102,profile).target).toBe(10);
 expect(scaledCrownVolumeDelta(-100,0,10,profile)).toBe(2);
 let state={min:0,max:10,current:0,fixed:false};const writes:number[]=[],views:any[]=[],timers:any[]=[],notified:any[]=[];
 const controller=new CrownVolumeController({state:async()=>state,setIndex:async index=>{writes.push(index);return state={...state,current:index};}},{after:(ms,callback)=>{timers.push([ms,callback]);return()=>{};}},v=>views.push(v),()=>notified.push(true),()=>{},profile);
 controller.adjust(-100,1,true);await settle();expect(writes).toEqual([2]);expect(views.at(-1)).toEqual({visible:true,percent:20});expect(timers[0][0]).toBe(77);timers[0][1]();expect(views.at(-1)).toEqual({visible:false,percent:20});
});
test('bounded write race uses explicit deadline and cancels timer on both outcomes',async()=>{
 const calls:any[]=[];let timeout!:(value:unknown)=>void;const timer=(ms:number)=>{calls.push(ms);return {result:new Promise(resolve=>timeout=resolve),cancel:()=>calls.push('cancel')};};
 expect(await waitForFlush(async()=>{},timer,41)).toEqual({status:'saved'});expect(calls).toEqual([41,'cancel']);
 calls.length=0;const waiting=waitForFlush(()=>new Promise(()=>{}),timer,18);timeout(null);expect(await waiting).toEqual({status:'timed-out',timeoutMs:18});expect(calls).toEqual([18,'cancel']);
});
test('exit attempts use object identity rather than route value and reallow completed targets',async()=>{
 let writes=0;const timers:any[]=[],completed:any[]=[],tasks:any[]=[];const guard=new ExitFlushGuard<{id:number}>(ms=>{timers.push(ms);return {result:new Promise(()=>{}),cancel(){}};},88);
 const a={id:1},b={id:1};const begin=(target:{id:number})=>guard.begin(target,()=>{writes++;return new Promise(resolve=>tasks.push(resolve));},value=>completed.push(value));
 begin(a);begin(a);begin(b);expect(writes).toBe(2);tasks[0]();await settle();begin(b);expect(writes).toBe(2);tasks[1]();await settle();begin(b);expect(writes).toBe(3);tasks[2]();await settle();expect(completed).toEqual([{status:'saved'},{status:'saved'},{status:'saved'}]);expect(timers).toEqual([88,88,88]);
});
test('private-file ownership starts only after allocation acceptance and cleanup is awaited after errors',async()=>{
 const events:any[]=[],failure=Error('late failure');let finish!:(value:unknown)=>void,settled=false;
 const result=withOwnedPrivateFile(async()=>{events.push('allocate');return 'private/owned';},path=>{events.push(['remove',path]);return new Promise(resolve=>finish=resolve);},async path=>{events.push(['read',path]);throw failure;},error=>events.push(['error',error])).catch(error=>{settled=true;return error;});
 await settle();expect(settled).toBe(false);expect(events).toEqual(['allocate',['read','private/owned'],['error',failure],['remove','private/owned']]);finish(null);expect(await result).toBe(failure);
 events.length=0;await expect(withOwnedPrivateFile(async()=>{throw Error('unowned');},async path=>{events.push(path);},async()=>1,()=>{})).rejects.toThrow('unowned');expect(events).toEqual([]);
 expect(await withOwnedPrivateFile('owned',async()=>{throw Error('remove');},async()=>42,()=>{})).toBe(42);
});
test('raw profile remains separate from strict response path/chunk validation',async()=>{
 expect(await readDownloadedBytes('raw',3,async(_path,offset)=>Uint8Array.of(offset+1),()=>Error('incomplete'))).toEqual(Uint8Array.of(1,2,3));
 const options={maxBytes:10,chunkBytes:2,invalidResponse:()=>Error('metadata'),incomplete:()=>Error('chunk')};const reads:any[]=[];
 const body=await readDownloadedChunks('owned',{path:'owned',size:3},async(path,offset,max)=>{reads.push([path,offset,max]);return {offset,size:3,dataBase64:offset===0?'AQI=':'Aw==',eof:offset===2};},options);expect(body).toEqual(Uint8Array.of(1,2,3));expect(reads).toEqual([['owned',0,2],['owned',2,1]]);
 let touched=false;await expect(readDownloadedChunks('owned',{path:'other',size:3},async()=>{touched=true;throw Error();},options)).rejects.toThrow('metadata');expect(touched).toBe(false);
 await expect(readDownloadedChunks('owned',{path:'owned',size:1},async()=>({offset:0,size:1,dataBase64:'AB==',eof:true}),options)).rejects.toThrow('chunk');
});
test('generic preview accepts a zero-valued payload, coalesces frames and retains a failed delta sequence',()=>{
 let now=0,id=0;const timers=new Map<number,{ms:number;run:()=>void}>(),frames:any[]=[];type Frame={sessionId:string;sequence:number;value:number;extended:boolean;updatedAt:number};
 const options:PreviewSessionOptions<number,{amount?:number},Frame>={timeoutMs:12,extendedTimeoutMs:90,publishIntervalMs:5,check:(disposed,id,sequence)=>{if(disposed||!id||sequence<0)throw Error('invalid');},normalize:value=>value,apply:(value,delta)=>{if(delta.amount===-1)throw Error('delta');return value+(delta.amount??0);},isEmpty:delta=>delta.amount===undefined,missingBaseline:()=>Error('baseline'),frame:(sessionId,sequence,value,extended,updatedAt)=>({sessionId,sequence,value,extended,updatedAt}),isExtended:frame=>frame.extended};
 const session=new PreviewSession({now:()=>now,monotonic:()=>now,after:(ms,run)=>{const key=++id;timers.set(key,{ms,run});return()=>{timers.delete(key);};}},options);session.subscribe(value=>frames.push(value));
 expect(session.update('s',0,0,true)).toBe(true);expect(session.updateDelta('s',1,{amount:2})).toBe(true);expect(session.updateDelta('s',2,{amount:3})).toBe(true);expect(frames.map(v=>v.value)).toEqual([0]);
 const publish=[...timers].find(([,job])=>job.ms===5)!;timers.delete(publish[0]);now=5;publish[1].run();expect(frames.map(v=>v.value)).toEqual([0,5]);expect(()=>session.updateDelta('s',3,{amount:-1})).toThrow('delta');expect(session.updateDelta('s',3,{amount:99})).toBe(false);expect(session.heartbeat('s',2)).toBe(3);expect(session.state()?.sequence).toBe(2);session.dispose();expect(timers.size).toBe(0);
});
test('matching uses original UTF-16 folding policy and source matches span wrapped lines',()=>{
 expect(textMatches('😀AbAB','ab')).toEqual([{start:2,end:4},{start:4,end:6}]);expect(textMatches('aaaa','aa')).toEqual([{start:0,end:2},{start:2,end:4}]);
 expect(matchLines('Abcd',['Ab','cd…'],'bc',s=>s.length*5)).toEqual([[{text:'b',x:5,width:5}],[{text:'c',x:0,width:5}]]);
});

test('strict reader rejects invalid limits before touching a response file',async()=>{
 const response={path:'owned',size:1},valid={maxBytes:8,chunkBytes:2,invalidResponse:()=>Error('metadata'),incomplete:()=>Error('chunk')};let reads=0;
 for(const chunkBytes of [0,-1,.5,Infinity,NaN])await expect(readDownloadedChunks('owned',response,async()=>{reads++;throw Error();},{...valid,chunkBytes})).rejects.toThrow('Invalid response read limits');
 for(const maxBytes of [-1,.5,Infinity,NaN])await expect(readDownloadedChunks('owned',response,async()=>{reads++;throw Error();},{...valid,maxBytes})).rejects.toThrow('Invalid response read limits');
 expect(reads).toBe(0);
});
