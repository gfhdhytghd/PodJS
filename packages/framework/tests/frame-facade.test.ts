import {expect,test} from 'bun:test';
import {createFrameFacade,ProgressDeadline,StreamFrameConnection,type FrameFacadeOptions} from '../src/frame-stream.ts';
const options:FrameFacadeOptions<Uint8Array,{tag:string}>={maxFrameBytes:10,encodeBody:v=>v,decodeBody:v=>v,encodeBodyAsync:async(v)=>v,decodeBodyAsync:async(v)=>v};
const header=(n:number)=>{const v=new Uint8Array(4);new DataView(v.buffer).setUint32(0,n,false);return v;};
test('opaque bodies support both signed dialects, exact byte views, work context and injected errors',async()=>{
 const seen:unknown[]=[];
 const codec=createFrameFacade({...options,encodeBodyAsync:async(v,work)=>{seen.push(work);return v;},decodeBodyAsync:async(v,work)=>{seen.push(work);return v;}});
 const body=Uint8Array.of(0,255,128),work={tag:'custom'};
 const bytes=await codec.encodeFrameAsync(body,work);expect([...bytes]).toEqual([0,0,0,3,0,255,128]);
 const backing=new Uint8Array(bytes.length+8);backing.set(bytes,5);
 expect(await codec.decodeFrameAsync(backing.subarray(5,5+bytes.length),work)).toEqual(body);expect(seen).toEqual([work,work]);
 expect(()=>codec.decodeFrame(header(0xffffffff))).toThrow('Frame length out of range: -1');
 const unsigned=createFrameFacade({...options,prefix:'unsigned',errors:{length:n=>new Error('example length '+n)}});
 expect(()=>unsigned.decodeFrame(header(0xffffffff))).toThrow('example length 4294967295');
 expect(()=>codec.encodeFrame(new Uint8Array())).toThrow(RangeError);expect(()=>codec.encodeFrame(new Uint8Array(11))).toThrow(RangeError);
 expect(()=>codec.decodeFrame(new Uint8Array(3))).toThrow('Truncated frame prefix');
 expect(()=>codec.decodeFrame(Uint8Array.of(0,0,0,1))).toThrow('Truncated or trailing');
 for(const maxFrameBytes of [0,NaN,1.5,0x80000000])expect(()=>createFrameFacade({...options,maxFrameBytes})).toThrow(RangeError);
 expect(()=>createFrameFacade({...options,prefix:'unsigned',maxFrameBytes:0xffffffff})).not.toThrow();
});
test('poison, resumable EOF and permanent finish are explicit independent policies',()=>{
 const codec=createFrameFacade(options),frame=codec.encodeFrame(Uint8Array.of(9));
 const d=new codec.FrameDecoder();d.push(frame.subarray(0,4));expect(()=>d.finish()).toThrow('Truncated');expect(()=>d.push(frame)).toThrow('already failed');
 const clean=new codec.FrameDecoder();expect(clean.push(frame)).toEqual([Uint8Array.of(9)]);clean.finish();expect(()=>clean.push(frame)).toThrow('finished');expect(()=>clean.finish()).toThrow('finished');
 const resumable=createFrameFacade({...options,poisonOnPushError:false,poisonOnFinishError:false,closeOnFinish:false});
 const r=new resumable.FrameDecoder();r.push(frame.subarray(0,2));expect(()=>r.finish()).toThrow('Truncated');expect(r.push(frame.subarray(2))).toEqual([Uint8Array.of(9)]);r.finish();r.finish();
 expect(()=>r.push(header(0))).toThrow(RangeError);expect(r.push(frame)).toEqual([Uint8Array.of(9)]);
 const poison=createFrameFacade({...options,decodeBody:()=>{throw new SyntaxError('opaque decoder failure');},errors:{failed:()=>new Error('example poison')}});
 const p=new poison.FrameDecoder();expect(()=>p.push(frame)).toThrow(SyntaxError);expect(()=>p.push(new Uint8Array())).toThrow('example poison');expect(()=>p.finish()).toThrow('example poison');
});

test('frame timing separates codec and IO clocks; observer errors never change bytes',async()=>{
 const {StreamFrameConnection}=await import('../src/frame-stream.ts');let now=0,offset=0;const raw=Uint8Array.of(0,0,0,1,7),writes:number[]=[];
 const stream={read:async(max:number)=>{now+=3;const b=raw.slice(offset,offset+max);offset+=b.length;return b.length?b:null;},write:async(b:Uint8Array)=>{now+=5;writes.push(...b);},close:async()=>{}};
 const conn=new StreamFrameConnection(stream,{maxFrameBytes:100,encode:async()=>{now+=11;return raw;},decode:async()=>{now+=13;return 7;}},()=>now),samples:any[]=[];
 conn.setFrameTiming(sample=>{samples.push(sample);throw Error('observer');});expect(await conn.readFrame(1000)).toBe(7);await conn.writeFrame(7);expect(writes).toEqual([...raw]);
 expect(samples).toEqual([{direction:'read',bytes:5,readMs:6,decodeMs:13,encodeMs:0,writeMs:0},{direction:'write',bytes:5,readMs:0,decodeMs:0,encodeMs:11,writeMs:10}]);await conn.close();
});

function deadlineClock(){let now=0;const jobs:{at:number;cancelled:boolean;finish():void}[]=[];const delay=(ms:number)=>{let finish!:()=>void,reject!:(e:Error)=>void;const result=new Promise<void>((a,b)=>{finish=a;reject=b;});const job={at:now+ms,cancelled:false,finish};jobs.push(job);return {result,cancel(){job.cancelled=true;reject(Error('cancelled'));}};};return {delay,now:()=>now,jobs,async advance(ms:number){now+=ms;for(const j of jobs)if(!j.cancelled&&j.at<=now){j.cancelled=true;j.finish();}for(let i=0;i<12;i++)await Promise.resolve();}};}
test('progress deadline ignores empty activity, renews on bytes, bounds timer churn and drains cancellation',async()=>{
 const c=deadlineClock();let closed=0;const d=new ProgressDeadline(c.delay,90,20,c.now,()=>closed++);const result=d.run(()=>new Promise<never>(()=>{})).then(()=>null,error=>error);
await c.advance(80);d.progress(4);await c.advance(19);d.progress(0);await c.advance(1);expect((await result)?.message).toContain('idle deadline');expect(closed).toBe(1);
 const n=deadlineClock();let resolve!:(v:string)=>void;const p=new ProgressDeadline(n.delay,90,20,n.now);const task=p.run(()=>new Promise<string>(r=>resolve=r));p.progress(1);for(let i=0;i<1000;i++)p.progress(1);expect(n.jobs).toHaveLength(2);await n.advance(19);p.progress(10);await n.advance(1);resolve('done');expect(await task).toBe('done');await n.advance(100);expect(n.jobs.every(j=>j.cancelled)).toBe(true);
});

test('byte-complete disarms network idle before a slow codec; cancellation still settles the operation',async()=>{
 const clock=deadlineClock();let resolve!:(value:Uint8Array)=>void;let reads=0;const payload=Uint8Array.of(9);
 const connection=new StreamFrameConnection({read:async()=>++reads===1?header(1):payload,write:async()=>{},close:async()=>{}},{maxFrameBytes:10,encode:async v=>v,decode:async()=>new Promise<Uint8Array>(r=>resolve=r)},clock.now);
 const idle=new ProgressDeadline(clock.delay,90,20,clock.now);let complete=0;connection.setTransferProgress(p=>idle.progress(p.bytes));connection.setReadBytesComplete(()=>{complete++;idle.stop();});
 const done=idle.run(()=>connection.readFrame(1000)).then(v=>v,error=>error);for(let i=0;i<20;i++)await Promise.resolve();expect(complete).toBe(1);await clock.advance(100);idle.cancel();expect((await done).message).toBe('Stream operation cancelled');resolve(payload);await connection.close();
});
