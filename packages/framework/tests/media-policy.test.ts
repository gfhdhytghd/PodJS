import {expect,test} from 'bun:test';
import {PlaybackRecoveryBudget,PlaybackPrepareDeadline,playbackFailure,mediaSourceExpiry,selectSustainableVariant} from '../src/media-policy.ts';

test('recovery has a shared attempt ceiling, single refresh and elapsed deadline',()=>{
  let now=0;const b=new PlaybackRecoveryBudget(()=>now,30000,3);
  b.claim(true);expect(()=>b.claim(true)).toThrow('exhausted');b.claim();b.claim();expect(()=>b.claim()).toThrow('exhausted');
  b.reset();b.claim();now=30000;expect(()=>b.check()).toThrow('deadline');b.reset();expect(()=>b.claim()).not.toThrow();
});
test('deadline rejects hanging work, cancels timeout and does not accept a late result',async()=>{
  let expire!:()=>void,complete!:(v:number)=>void,cancelled=0;
  const b=new PlaybackRecoveryBudget();b.claim();
  const result=b.run(()=>new Promise<number>(r=>complete=r),()=>({result:new Promise(r=>expire=()=>r(null)),cancel:()=>{cancelled++;}}));
  expire();await expect(result).rejects.toThrow('deadline');complete(7);await Promise.resolve();expect(cancelled).toBe(1);
});
test('known source expiries use earliest track, safety margin and never exceed fixed TTL',()=>{
  const now=1800000000000;
  expect(mediaSourceExpiry(['https://fixture/v?deadline=1800000100&sign=opaque','https://fixture/a?expires=1800000080'],now)).toBe(now+50000);
  expect(mediaSourceExpiry(['https://fixture/?signature=1800000001'],now)).toBe(now+300000);
  expect(mediaSourceExpiry(['https://fixture/?deadline=1799999999'],now)).toBe(now);
});
test('AVC candidates follow measured network and decoder dimensions without assuming qn labels',()=>{
  const low={bitrate:200000,width:640,height:360},mid={bitrate:900000,width:960,height:540},high={bitrate:1800000,width:1280,height:720};
  expect(selectSustainableVariant([low,mid,high],mid,{throughputBps:1000000})).toBe(low);
  expect(selectSustainableVariant([low,mid,high],mid,{throughputBps:5000000})).toBe(mid);
  expect(selectSustainableVariant([low,mid,high],mid,{rebufferCount:1})).toBe(low);
  expect(selectSustainableVariant([low,mid,high],mid,{maxWidth:640})).toBe(low);
  expect(selectSustainableVariant([mid,high],mid,{maxWidth:640})).toBeUndefined();
});
test('access restrictions and decode errors never masquerade as CDN retries',()=>{
  for(const status of [401,403,412,429])expect(playbackFailure({status})).toBe('restricted');
  expect(playbackFailure({code:'decode_error'})).toBe('decode');expect(playbackFailure({code:'cdn_error'})).toBe('network');
  expect(playbackFailure({status:410})).toBe('expired');expect(playbackFailure(new Error('secret URL'))).toBe('other');
});


test('prepare deadline cancels current generation and ignores cancelled, stale or rendered generations',async()=>{
 let finish!:()=>void,cancelled=0,expired=0,current=true;
 const timer=()=>({result:new Promise<void>(r=>finish=r),cancel(){cancelled++;}}),guard=new PlaybackPrepareDeadline();
 guard.arm(100,timer,()=>current,()=>expired++);finish();await Promise.resolve();expect(expired).toBe(1);
 guard.arm(100,timer,()=>current,()=>expired++);guard.cancel();finish();await Promise.resolve();expect(expired).toBe(1);
 guard.arm(100,timer,()=>current,()=>expired++);current=false;finish();await Promise.resolve();expect(expired).toBe(1);expect(cancelled).toBeGreaterThan(0);
});

function progressClock(){
 let now=0,bytes=0;const pending:Array<{at:number;resolve:()=>void;cancelled:boolean}>=[];
 const timer=(ms:number)=>{let resolve!:()=>void;const result=new Promise<void>(r=>resolve=r),item={at:now+ms,resolve,cancelled:false};pending.push(item);return {result,cancel(){item.cancelled=true;}};};
 return {timer,now:()=>now,read:()=>bytes,async second(productive=true){now+=1000;if(productive)bytes+=8192;for(const item of pending.splice(0)){if(item.at<=now){if(!item.cancelled)item.resolve();}else pending.push(item);}await Promise.resolve();}};
}
test('productive media parsing outlives 20 seconds but an idle source still expires',async()=>{
 const c=progressClock(),guard=new PlaybackPrepareDeadline();let expired=0;
 guard.arm(20_000,c.timer,()=>true,()=>expired++,{read:c.read,now:c.now,maxMs:120_000});
 for(let i=0;i<35;i++)await c.second();expect(expired).toBe(0);
 for(let i=0;i<19;i++)await c.second(false);expect(expired).toBe(0);
 await c.second(false);expect(expired).toBe(1);
});
test('continuous preparation progress cannot extend the absolute bound or a cancelled generation',async()=>{
 const c=progressClock(),guard=new PlaybackPrepareDeadline();let expired=0;
 guard.arm(20_000,c.timer,()=>true,()=>expired++,{read:c.read,now:c.now,maxMs:120_000});
 for(let i=0;i<119;i++)await c.second();expect(expired).toBe(0);
 await c.second();expect(expired).toBe(1);
 guard.arm(20_000,c.timer,()=>true,()=>expired++,{read:c.read,now:c.now,maxMs:120_000});guard.cancel();
 for(let i=0;i<120;i++)await c.second();expect(expired).toBe(1);
});
