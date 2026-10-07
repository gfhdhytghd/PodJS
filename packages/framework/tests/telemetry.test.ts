import {expect,test} from 'bun:test';
import {DailyCounterStore,CaptureUploadScheduler,ActiveBatterySession} from '../src/telemetry.ts';
import {DisclosureSession} from '../src/disclosure.ts';
const disk=()=>{const m=new Map<string,string>();return {get:(k:string)=>m.get(k),set:(k:string,v:string)=>{m.set(k,v);}};};
test('default production clock works; configurable daily counters retain prototypes as data keys',()=>{
 const storage=disk(),store=new DailyCounterStore(storage,{key:'counts',utcOffsetMinutes:0,retentionDays:7});
 store.record('__proto__');store.record('screen_opened',{screen:'constructor'});expect(store.backlogCount()).toBe(1);
 expect(store.snapshots()[0].eventCounts.__proto__).toBe(1);expect(Object.getOwnPropertyDescriptor(store.snapshots()[0].screenOpenCounts,"constructor")?.value).toBe(1);
 expect(store.snapshots()[0].day).toBe(new Date().toISOString().slice(0,10));
});
test('scheduler with default debounce coalesces pending captures and retries only on another capture',async()=>{
 const timers:Array<()=>void>=[];let sends=0;
 const scheduler=new CaptureUploadScheduler(ms=>{expect(ms).toBe(750);return new Promise(r=>timers.push(()=>r(null)));},async()=>{sends++;throw Error('offline');});
 scheduler.capture();scheduler.capture();expect(timers).toHaveLength(1);timers.shift()!();for(let i=0;i<10;i++)await Promise.resolve();expect(sends).toBe(1);expect(timers).toHaveLength(0);
 scheduler.capture();expect(timers).toHaveLength(1);
});
test('battery segments preserve event duration under late reads, coalesce repeated active status, and include source switches',async()=>{
 let now=1;const reads:Array<(v:number|null)=>void>=[],samples:unknown[]=[];
 const session=new ActiveBatterySession(()=>new Promise(r=>reads.push(r)),sample=>samples.push(sample),()=>now);
 const start=session.update('a',true);expect(session.update('a',true)).toBe(start);for(let i=0;i<3;i++)await Promise.resolve();expect(reads).toHaveLength(1);
 now=60001;const stop=session.stop();reads.shift()!(90);await start;for(let i=0;i<3;i++)await Promise.resolve();reads.shift()!(89);await stop;
 expect(samples).toEqual([{source:'a',durationMs:60000,batteryStart:90,batteryEnd:89}]);
});
test('battery rejects short, unknown, charging, and failed samples; default clock is usable',async()=>{
 for(const levels of [[50,49],[null,40],[40,41],[40,null],[-1,0],[101,99]] as const){
  let now=1;const samples:unknown[]=[];let i=0;
  const session=new ActiveBatterySession(async()=>levels[i++]??null,v=>samples.push(v),()=>now);
  await session.update('video',true);now=levels[0]===50?59999:70001;await session.stop();expect(samples).toHaveLength(0);
 }
 const session=new ActiveBatterySession(async()=>{throw Error('unsupported');},()=>{throw Error('invented sample');});await session.update('video',true);await session.stop();
});
test('persistent initial acknowledgement followed by per-instance confirmation never accepts dismissal',()=>{
 const m=new Map<string,unknown>(),storage={get:<T>(k:string)=>m.get(k) as T|undefined,set:<T>(k:string,v:T)=>{m.set(k,v);}};
 const entry=new DisclosureSession(storage,'v1',true);expect(entry.pending()).toBe('initial');expect(entry.dismiss()).toBe(false);entry.acknowledge();expect(entry.pending()).toBe('platform');entry.acknowledge();expect(entry.pending()).toBeUndefined();
 expect(new DisclosureSession(storage,'v1',true).pending()).toBe('platform');expect(new DisclosureSession(storage,'v2').pending(true)).toBeUndefined();
});
