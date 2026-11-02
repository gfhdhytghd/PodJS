import {expect,test} from 'bun:test';
import {VideoSessionPool,type VideoSessionPort} from '../src/video-session-pool';
import type {VideoSessionState} from '../src/services';

// Production pool with a deterministic transport/CPU model. This does not
// execute Android decoders or prove device timing, temperature or PSS.
class Model {
 now=0;prepareCount=0;cancelCount=0;readyBefore=0;targetBytes=4*1024*1024;
 states=new Map<string,VideoSessionState>();jobs:Array<{due:number;run():void}>=[];
 restoreBacklog=800;foregroundMedia=false;restoreReceipts=0;
 later(ms:number,run:()=>void){this.jobs.push({due:this.now+ms,run});}
 value(id:string,prewarm=false):VideoSessionState{return {ok:true,sessionId:id,state:prewarm?'prewarmed':'playing',positionMs:0,durationMs:10000,width:360,height:640,muted:prewarm,volume:1,prewarming:prewarm,presented:!prewarm,hasRenderedFirstFrame:true,targetBufferBytes:this.targetBytes,bufferedMs:1500,networkStalled:false};}
 port={
  capabilities:async()=>({capability:'media.video.sessions',maxSessions:3,decoderLimit:3,decoderInUse:this.states.size,targetBufferBytesPerSession:this.targetBytes,targetBufferBytesTotal:this.targetBytes*3,prewarmTimeoutMs:2500,prewarmMode:'hidden_first_frame',surfacePromotion:true,verticalTransition:true,surfaceComposition:'android_texture_view',backgroundPlayback:false}),
  prepare:(options:any)=>{this.prepareCount++;let cancelled=false;
   const result=new Promise<VideoSessionState>((resolve,reject)=>{
    // 3x CPU cost plus a bounded already-running 4ms restore unit.
    const cost=options.prewarm?120*3+4:100*3+4;
    this.later(cost,()=>{if(cancelled){reject(Error('cancelled'));return;}const value=this.value(options.sessionId,options.prewarm);this.states.set(options.sessionId,value);resolve(value);});
   });return {result,cancel:()=>{cancelled=true;this.cancelCount++;}};
  },
  release:async(id:string)=>{this.states.delete(id);return {sessionId:id,released:true};},
  state:async(id:string)=>this.states.get(id)!,
  setLoop:async(id:string)=>this.states.get(id)!,
  setBounds:async(id:string,bounds:any)=>{const next={...this.states.get(id)!,presented:bounds.visible!==false};this.states.set(id,next);return next;},
  promote:(id:string,options:any)=>new Promise<VideoSessionState>(resolve=>this.later(16,()=>{const value={...this.states.get(id)!,prewarming:false,presented:true,state:'playing'};this.states.set(id,value);resolve(value);})),
  transition:async(options:any)=>({...options,composited:true}),
 } as unknown as VideoSessionPort;
 async advance(ms:number){
  const target=this.now+ms;
  for(let steps=0;steps<1000;steps++){
   for(let i=0;i<16;i++)await Promise.resolve();
   this.jobs.sort((a,b)=>a.due-b.due);const next=this.jobs[0];
   if(!next||next.due>target){this.now=target;break;}
   this.now=next.due;this.jobs.shift();next.run();
   if(!this.foregroundMedia&&this.restoreBacklog){this.restoreBacklog--;this.restoreReceipts++;}
  }
  for(let i=0;i<16;i++)await Promise.resolve();
 }
}
const source=(n:number)=>({key:String(n)}),options=(n:number)=>({url:'https://fixture.invalid/'+n,bounds:{x:0,y:0,width:233,height:233}});

test('production pool: both neighbours under 3x CPU cost and queued restore load; model thresholds only',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);expect(await pool.supportsPrewarm()).toBe(true);
 const start=pool.prepare(source(10),options(10)).result;await m.advance(400);await start;m.foregroundMedia=true;
 let current=10,ready=0;const latency:number[]=[];
 const warm=async()=>{const a=pool.prewarm(source(current+1),options(current+1)),b=pool.prewarm(source(current-1),options(current-1));await m.advance(500);await Promise.all([a,b]);};
 await warm();
 for(let i=0;i<120;i++){
  // Consecutive 3-5 swipes followed by reversals; 500ms between settles.
  const direction=i%10<5?1:-1,target=current+direction;
  expect(pool.hasStandby(String(current+1))).toBe(true);expect(pool.hasStandby(String(current-1))).toBe(true);
  if(pool.hasStandby(String(target)))ready++;
  expect(pool.selectStandby(String(target))).toBe(true);
  const before=m.prepareCount;await pool.transition(1,direction>0?'up':'down',options(target).bounds);
  const began=m.now,promote=pool.promote(direction>0?'up':'down',options(target).bounds);await m.advance(16);await promote;latency.push(m.now-began);
  expect(m.prepareCount).toBe(before);expect(pool.hasStandby(String(current))).toBe(true);
  current=target;await warm();expect(m.states.size).toBeLessThanOrEqual(3);
 }
 expect(ready/120).toBeGreaterThanOrEqual(.95);expect(Math.max(...latency)).toBeLessThanOrEqual(200);
 expect(m.restoreReceipts).toBe(1); // Initial prepare before foreground media.
 m.foregroundMedia=false;await pool.releaseAll();m.later(4,()=>{});await m.advance(4);expect(m.restoreReceipts).toBe(2);
 console.log('PREWARM_MODEL '+JSON.stringify({swipes:120,readyBeforeSwipe:ready,readyRate:ready/120,maxAfterSettleMs:Math.max(...latency),cpuMultiplier:3,queuedRestoreUnits:800,intervalMs:500,bufferTargetBytes:3*m.targetBytes,deviceProof:false}));
});

test('deduplicates same-key preparation; concurrent neighbours do not cancel each other; stale completions cannot return',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1)).result;await m.advance(400);await active;
 const next=pool.prewarm(source(2),options(2)),same=pool.prewarm(source(2),options(2)),prev=pool.prewarm(source(0),options(0));
 await m.advance(500);await Promise.all([next,same,prev]);expect(m.prepareCount).toBe(3);expect(m.cancelCount).toBe(0);
 expect(pool.hasStandby('0')).toBe(true);expect(pool.hasStandby('2')).toBe(true);
 pool.invalidateStandby('0');const stale=pool.prewarm(source(-1),options(-1));const rejection=stale.then(()=>{throw Error('stale prewarm resolved');},error=>expect(String(error)).toMatch(/stale|cancel/));
 await pool.releaseAll();await m.advance(500);await rejection;expect(pool.hasStandby('-1')).toBe(false);expect(m.states.size).toBe(0);
});

test('explicit sessions strip legacy background preference and a two-decoder host never starts a third slot',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),decoderLimit:2,maxSessions:2});
 const original=m.port.prepare;m.port.prepare=(o:any)=>{expect(o.backgroundPlayback).toBeUndefined();return original(o);};
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();expect(pool.neighbourCapacity).toBe(1);
 const active=pool.prepare(source(1),{...options(1),backgroundPlayback:true}).result;await m.advance(400);await active;
 const a=pool.prewarm(source(2),{...options(2),backgroundPlayback:true});await m.advance(500);await a;
 const b=pool.prewarm(source(0),options(0));await m.advance(500);await b;expect(m.states.size).toBe(2);expect(pool.hasStandby('2')).toBe(false);expect(pool.hasStandby('0')).toBe(true);
});


test('an outgoing active that never decoded a frame is not falsely retained as ready',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1)).result;await m.advance(400);await active;
 const id=(await pool.diagnostics())!.slots[0].sessionId;m.states.set(id,{...m.states.get(id)!,hasRenderedFirstFrame:false});
 const next=pool.prewarm(source(2),options(2));await m.advance(500);await next;pool.selectStandby('2');
 const promotion=pool.promote('up',options(2).bounds);await m.advance(16);await promotion;expect(pool.hasStandby('1')).toBe(false);
});

test('DYFIX memory capacity recovers 3 to 2 to 1 to 3 without releasing foreground',async()=>{
 const m=new Model();let limit=3;const capability=m.port.capabilities;
 m.port.capabilities=async()=>({...await capability(),decoderLimit:limit});
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1)).result;await m.advance(400);await active;
 const next=pool.prewarm(source(2),options(2),'next'),prev=pool.prewarm(source(0),options(0),'prev');
 await m.advance(500);await Promise.all([next,prev]);expect(m.states.size).toBe(3);
 limit=2;expect(await pool.supportsPrewarm()).toBe(true);expect(pool.hasStandby('2')).toBe(true);expect(pool.hasStandby('0')).toBe(false);expect(m.states.size).toBe(2);
 limit=1;expect(await pool.supportsPrewarm()).toBe(false);expect(m.states.size).toBe(1);expect((await pool.state()).hasRenderedFirstFrame).toBe(true);
 limit=3;expect(await pool.supportsPrewarm()).toBe(true);expect(pool.neighbourCapacity).toBe(2);
 const nextAgain=pool.prewarm(source(2),options(2),'next'),prevAgain=pool.prewarm(source(0),options(0),'prev');
 await m.advance(500);await Promise.all([nextAgain,prevAgain]);
 expect(pool.hasStandby('2')).toBe(true);expect(pool.hasStandby('0')).toBe(true);expect(m.states.size).toBe(3);
 expect(m.prepareCount).toBe(5); // One foreground prepare, four neighbour prepares.
 await pool.releaseAll();expect(m.states.size).toBe(0);
});

test('initial foreground preparation overlaps navigation and is adopted without rebuilding its decoder',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port),value=source(1),config=options(1);
 const prime=pool.prime(value,config);await m.advance(100);expect(m.prepareCount).toBe(1);
 const opened=pool.prepare(value,config).result;expect(m.prepareCount).toBe(1);await m.advance(300);await Promise.all([prime.result,opened]);
 expect(m.prepareCount).toBe(1);expect(m.states.size).toBe(1);await pool.releaseAll();expect(m.states.size).toBe(0);
});
test('releasing an initial reservation fences a late completion and changed media never adopts it',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port),prime=pool.prime(source(1),options(1));const rejected=prime.result.catch(()=>undefined);
 await pool.releaseAll();await m.advance(400);await rejected;expect(m.states.size).toBe(0);
 const again=pool.prime(source(1),options(1));await m.advance(400);await again.result;
 const changed=pool.prepare(source(1),{...options(1),url:'https://fixture.invalid/new'}).result;await m.advance(400);await changed;expect(m.prepareCount).toBe(3);await pool.releaseAll();
});

test('an initial prepare failure retains its code and does not start a second decoder',async()=>{
 const m=new Model(),error=Object.assign(Error('fixture'),{code:'decode_error'});m.port.prepare=()=>{m.prepareCount++;return {result:Promise.reject(error),cancel(){}};};
 const pool=new VideoSessionPool(m.port),prime=pool.prime(source(1),options(1));const first=prime.result.catch(e=>e),opened=pool.prepare(source(1),options(1)).result.catch(e=>e);
 expect(await first).toBe(error);expect(await opened).toBe(error);expect(m.prepareCount).toBe(1);await pool.releaseAll();
});

test('DYSWIPE3 following decode overlaps settle and survives promotion in both directions',async()=>{
 for(const direction of ['up','down'] as const){
  const m=new Model(),pool=new VideoSessionPool(m.port),step=direction==='up'?1:-1;await pool.supportsPrewarm();
  const active=pool.prepare(source(10),options(10)).result;await m.advance(400);await active;
  const neighbours=[pool.prewarm(source(9),options(9),'prev'),pool.prewarm(source(11),options(11),'next')];await m.advance(400);await Promise.all(neighbours);
  pool.selectStandby(String(10+step));
  const following=pool.prewarm(source(10+2*step),options(10+2*step),step===1?'next':'prev');
  await m.advance(96);const promoted=pool.promote(direction,options(10+step).bounds,true,undefined,[String(10+2*step)]);await m.advance(16);await promoted;
  expect(pool.hasStandby('10')).toBe(true);expect(pool.hasStandby(String(10+2*step))).toBe(false);
  const duplicate=pool.prewarm(source(10+2*step),options(10+2*step));await m.advance(252);await Promise.all([following,duplicate]);
  expect(pool.hasStandby(String(10+2*step))).toBe(true);expect(m.prepareCount).toBe(4);expect(m.states.size).toBe(3);
  await pool.releaseAll();expect(m.states.size).toBe(0);
 }
});

test('DYSWIPE3 late drag transitions cannot race a pending native promotion',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1)).result;await m.advance(400);await active;
 const warm=pool.prewarm(source(2),options(2));await m.advance(400);await warm;pool.selectStandby('2');
 let transitions=0;const transition=m.port.transition;m.port.transition=async value=>{transitions++;return transition(value);};
 const promoted=pool.promote('up',options(2).bounds);
 expect(pool.selectStandby('2')).toBe(false);
 await expect(pool.transition(.2,'up',options(2).bounds)).rejects.toThrow(/stale/);expect(transitions).toBe(0);
 await m.advance(16);await promoted;expect(pool.selectStandby('1')).toBe(true);
 await pool.transition(.2,'down',options(1).bounds);expect(transitions).toBe(1);
});

test('T1202 pool forwards paging ownership independently from telemetry',async()=>{
 const m=new Model(),calls:any[]=[];const pool=new VideoSessionPool({...m.port,
  pause:async(id,action,handoff)=>{calls.push({operation:'pause',action,handoff});return m.value(id);},
  retire:async(id,handoff)=>{calls.push({operation:'retire',handoff});return m.value(id);},
 });
 await pool.pause(undefined,true);await pool.retire(true);await pool.pause();await pool.retire();
 expect(calls).toEqual([{operation:'pause',action:undefined,handoff:true},{operation:'retire',handoff:true},{operation:'pause',action:undefined,handoff:false},{operation:'retire',handoff:false}]);
});


test('T1202 cancelling a completed initial adoption cannot release the active player',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);pool.prime(source(1),options(1));
 const adopted=pool.prepare(source(1),options(1));await m.advance(400);await adopted.result;
 expect(m.states.size).toBe(1);const cancelled=m.cancelCount;
 adopted.cancel();adopted.cancel();
 expect(m.cancelCount).toBe(cancelled);expect(m.states.size).toBe(1);expect((await pool.state()).hasRenderedFirstFrame).toBe(true);
 await pool.releaseAll();expect(m.states.size).toBe(0);
});

test('T1202 cancelling an unfinished adoption still fences and releases its player',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);pool.prime(source(1),options(1));
 let unblock!:()=>void;const bounds=m.port.setBounds;m.port.setBounds=async(id,b)=>{await new Promise<void>(resolve=>{unblock=resolve;});if(!m.states.has(id))throw Error('stale released player');return bounds(id,b);};
 const adopted=pool.prepare(source(1),options(1));const error=adopted.result.catch(value=>value);
 await m.advance(400);adopted.cancel();adopted.cancel();unblock();
 expect(String(await error)).toContain('stale');expect(m.cancelCount).toBe(1);expect(m.states.size).toBe(0);
});

test('foreground adopts matching in-flight neighbour without cancelling or reopening its source',async()=>{
 const m=new Model();let resolveWarm!:(value:VideoSessionState)=>void;
 const original=m.port.prepare;
 m.port.prepare=(o:any)=>{
  if(!o.prewarm)return original(o);
  m.prepareCount++;m.states.set(o.sessionId,{...m.value(o.sessionId,true),hasRenderedFirstFrame:false,state:'buffering'});
  return {result:new Promise<VideoSessionState>(resolve=>{resolveWarm=resolve;}),cancel:()=>{m.cancelCount++;}};
 };
 let adoptedId='';m.port.promote=async(id:string,o:any)=>{
  expect(o.adoptPreparing).toBe(true);adoptedId=id;
  const next={...m.states.get(id)!,prewarming:false,presented:true};m.states.set(id,next);return next;
 };
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1));await m.advance(400);await active.result;
 const pending=pool.prewarm(source(2),options(2));const stale=pending.catch(error=>expect(String(error)).toContain('stale'));
 const foreground=pool.prepare(source(2),options(2));expect(m.prepareCount).toBe(2);expect(m.cancelCount).toBe(0);
 expect((await foreground.result).hasRenderedFirstFrame).toBe(false);
 expect(pool.ownsActiveSession(adoptedId)).toBe(true);
 expect(pool.hasStandby('1')).toBe(true);
 resolveWarm(m.value(adoptedId));await stale;foreground.cancel();
 expect(m.cancelCount).toBe(0);expect(pool.ownsActiveSession(adoptedId)).toBe(true);
 expect(m.states.has(adoptedId)).toBe(true);await pool.releaseAll();
});

test('in-flight adoption requires identical authorized source parameters',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1));await m.advance(400);await active.result;
 const pending=pool.prewarm(source(2),{...options(2),headers:{Cookie:'fixture-one'}}).catch(()=>{});
 const replacement=pool.prepare(source(2),{...options(2),headers:{Cookie:'fixture-two'}});
 await m.advance(500);await replacement.result;await pending;
 expect(m.prepareCount).toBe(3);expect(m.cancelCount).toBe(1);await pool.releaseAll();
});

test('cancelling pending foreground adoption retains the native session for the following retire',async()=>{
 const m=new Model();let resolvePromote!:(value:VideoSessionState)=>void;
 const original=m.port.prepare;
 m.port.prepare=(o:any)=>{
  if(!o.prewarm)return original(o);
  m.prepareCount++;m.states.set(o.sessionId,{...m.value(o.sessionId,true),hasRenderedFirstFrame:false,state:'buffering'});
  return {result:new Promise<VideoSessionState>(()=>{}),cancel:()=>{m.cancelCount++;}};
 };
 let target='';m.port.promote=(id:string)=>{target=id;return new Promise(resolve=>{resolvePromote=resolve;});};
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1));await m.advance(400);await active.result;
 void pool.prewarm(source(2),options(2));const foreground=pool.prepare(source(2),options(2));
 const stale=foreground.result.catch(error=>expect(String(error)).toContain('stale'));
 foreground.cancel();expect(m.cancelCount).toBe(1);
 expect(m.states.has(target)).toBe(true);expect(pool.ownsActiveSession(target)).toBe(true);
 resolvePromote(m.value(target));await stale;await pool.releaseAll();
});

test('DYSWAP decoded adoption publishes the atomic promotion receipt without diagnostic round trips',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const active=pool.prepare(source(1),options(1));await m.advance(400);await active.result;
 const warm=pool.prewarm(source(2),options(2));await m.advance(400);await warm;
 let promoteOptions:any;
 m.port.promote=async(id,o)=>{promoteOptions=o;return {...m.value(id),replacedHasRenderedFirstFrame:true};};
 // A congested diagnostic/state or loop reply must not gate a retained frame.
 m.port.state=()=>new Promise(()=>{});m.port.setLoop=()=>new Promise(()=>{});
 let published=false;
 const adoption=pool.prepare(source(2),{...options(2),autoplay:true});void adoption.result.then(()=>{published=true;});
 for(let i=0;i<32;i++)await Promise.resolve();
 expect(promoteOptions.autoplay).toBe(true);expect(promoteOptions.loop).toBe(false);
 expect(published).toBe(true);expect(pool.hasStandby('1')).toBe(true);
 expect(m.prepareCount).toBe(2);expect(m.cancelCount).toBe(0);
});

test('DYSWAP an initially primed paused frame still starts when foreground requests autoplay',async()=>{
 const m=new Model();let plays=0;
 m.port.play=async id=>{plays++;return {...m.value(id),state:'playing'};};
 const pool=new VideoSessionPool(m.port),item=source(1),config=options(1);
 const initial=pool.prime(item,config);await m.advance(400);await initial.result;
 const adopted=await pool.prepare(item,{...config,autoplay:true}).result;
 expect(plays).toBe(1);expect(adopted.state).toBe('playing');expect(m.prepareCount).toBe(1);
});

test('DYDRAG native pointer tracking removes repeated move RPCs but releases cancellation back to JS',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const start=pool.prepare(source(1),options(1)).result;await m.advance(400);await start;
 const warm=pool.prewarm(source(2),options(2));await m.advance(400);await warm;pool.selectStandby('2');let calls=0;
 m.port.transition=async args=>{calls++;expect(args.trackPointer).toBe(calls===1);return {...args,fromOffsetYPx:0,toOffsetYPx:0,composited:true,nativeTracking:args.trackPointer===true};};
 (pool as any).beginDrag?.();await pool.setBounds(options(1).bounds);
 await pool.transition(.1,'up',options(1).bounds);for(let i=2;i<10;i++)await pool.transition(i/10,'up',options(1).bounds);
 expect(calls).toBe(1);
 pool.endDrag();await pool.transition(.4,'up',options(1).bounds);expect(calls).toBe(2);
 pool.endDrag();await pool.transition(0,'up',options(1).bounds);expect(calls).toBe(3);
});

test('DYDRAG a late native tracking receipt cannot capture the next gesture or rebound',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const start=pool.prepare(source(1),options(1)).result;await m.advance(400);await start;
 const warm=pool.prewarm(source(2),options(2));await m.advance(400);await warm;pool.selectStandby('2');let release!:(value:any)=>void,calls=0;
 m.port.transition=args=>{calls++;return calls===1?new Promise(resolve=>release=resolve):Promise.resolve({...args,fromOffsetYPx:0,toOffsetYPx:0,composited:true,nativeTracking:false});};
 pool.beginDrag();const first=pool.transition(.1,'up',options(1).bounds);pool.endDrag();release({nativeTracking:true});await first;
 await pool.transition(.2,'up',options(1).bounds);await pool.transition(.3,'up',options(1).bounds);expect(calls).toBe(3);
});


test('consecutive prepared adoptions retain the immediate reverse neighbour, not an older active',async()=>{
 const m=new Model(),pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();
 const initial=pool.prepare(source(10),options(10));await m.advance(400);await initial.result;
 for(let current=10;current<16;current++){
  const both=[pool.prewarm(source(current-1),options(current-1),'prev'),pool.prewarm(source(current+1),options(current+1),'next')];
  await m.advance(400);await Promise.all(both);
  expect(pool.hasStandby(String(current-1))).toBe(true);
  expect(pool.hasStandby(String(current+1))).toBe(true);
  const adopted=pool.prepare(source(current+1),options(current+1));await m.advance(30);await adopted.result;
 }
});

test('five-slot window keeps four decoded neighbours while consecutive foreground adoptions rotate once per step',async()=>{
 const m=new Model(),pool=new VideoSessionPool({...m.port,capabilities:async()=>({...await m.port.capabilities(),maxSessions:5,decoderLimit:5})});
 await pool.supportsPrewarm();expect(pool.neighbourCapacity).toBe(4);
 const initial=pool.prepare(source(20),options(20));await m.advance(400);await initial.result;
 let current=20;
 for(const step of [...Array(12).fill(1),...Array(12).fill(-1)]){
  const keys=[current-2,current-1,current+1,current+2];pool.retainNeighbours(keys.map(String));
  const all=keys.map(n=>pool.prewarm(source(n),options(n),n<current?'prev':'next'));await m.advance(400);await Promise.all(all);
  for(const key of keys)expect(pool.sourceFor(String(key))?.key).toBe(String(key));
  expect(m.states.size).toBe(5);
  current+=step;const adopted=pool.prepare(source(current),options(current));await m.advance(30);await adopted.result;
 }
 expect(m.prepareCount).toBeLessThan(34);
});

test('DYFRAME serially decodes seven neighbours with a 3-lease budget and restores nearest live slots',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),keyframeCache:true});
 const cached=new Set<string>();const original=m.port.prepare;
 m.port.prepare=(o:any)=>{const operation=original(o);return {...operation,result:operation.result.then(value=>{if(o.prewarm)cached.add(o.url);expect(m.states.size).toBeLessThanOrEqual(3);if(o.keyframeOnly)m.states.delete(o.sessionId);return {...value,keyframeAvailable:o.prewarm};})};};
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const active=pool.prepare(source(10),options(10)).result;await m.advance(500);await active;
 const ids=[11,9,12,8,13,14,15];let resolved=0;
 const warm=pool.warmNeighbourhood(ids.map(n=>async()=>{resolved++;return {source:source(n),options:options(n),direction:n>10?'next' as const:'prev' as const};}));
 await m.advance(6000);await warm;expect(resolved).toBe(7);expect(cached.size).toBe(7);expect(pool.hasStandby('11')).toBe(true);expect(pool.hasStandby('9')).toBe(true);expect(m.states.size).toBe(3);await pool.releaseAll();
});

test('DYFRAME foreground preparation cancels queued neighbourhood work and releases its worker',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),keyframeCache:true});
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const a=pool.prepare(source(10),options(10)).result;await m.advance(400);await a;
 let loaded=0;const warm=pool.warmNeighbourhood([11,12,13,14,15].map(n=>async()=>{loaded++;return {source:source(n),options:options(n),direction:'next' as const};})).catch(()=>{});
 await m.advance(20);const foreground=pool.prepare(source(20),options(20)).result;await m.advance(1000);await Promise.all([foreground,warm]);expect(loaded).toBe(1);expect(m.cancelCount).toBeGreaterThan(0);expect((await pool.state()).hasRenderedFirstFrame).toBe(true);await pool.releaseAll();
});

test('DYFRAME honors codec-advertised capacity above three and never exceeds eight',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),decoderLimit:16,maxSessions:8,keyframeCache:true});
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();expect(pool.neighbourCapacity).toBe(7);const a=pool.prepare(source(10),options(10)).result;await m.advance(400);await a;
 for(let n=11;n<=17;n++){const warm=pool.prewarm(source(n),options(n));await m.advance(400);await warm;}
 expect(m.states.size).toBe(8);expect(pool.hasStandby('11')).toBe(true);expect(pool.hasStandby('17')).toBe(true);await pool.releaseAll();expect(m.states.size).toBe(0);
});

test('DYFRAME navigation while worker eviction awaits cannot enqueue a stale decoder',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),keyframeCache:true});
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const a=pool.prepare(source(10),options(10)).result;await m.advance(400);await a;
 const originalRelease=m.port.release;let unblock!:()=>void;const gate=new Promise<void>(r=>unblock=r);let first=true;
 m.port.release=async(id:string)=>{if(first){first=false;await gate;}return originalRelease(id);};
 const warm=pool.warmNeighbourhood([async()=>({source:source(11),options:options(11),direction:'next' as const})]);
 await m.advance(5);const fg=pool.prepare(source(20),options(20)).result;unblock();await m.advance(1000);await Promise.all([warm,fg]);expect(m.prepareCount).toBe(2);await pool.releaseAll();
});

test('DYFRAME cached foreground reveal does not wait behind unrelated standby release commands',async()=>{
 const m=new Model();m.port.capabilities=async()=>({...await new Model().port.capabilities(),keyframeCache:true});
 const pool=new VideoSessionPool(m.port);await pool.supportsPrewarm();const a=pool.prepare(source(10),options(10)).result;await m.advance(400);await a;
 for(const n of [11,9]){const warm=pool.prewarm(source(n),options(n));await m.advance(400);await warm;}
 const before=m.states.size,foreground=pool.prepare(source(15),options(15)).result;expect(m.states.size).toBe(before);await m.advance(400);await foreground;expect(m.states.size).toBe(1);await pool.releaseAll();
});
