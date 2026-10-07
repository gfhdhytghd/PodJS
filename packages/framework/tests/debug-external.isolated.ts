import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['host.lifecycle']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {debugExternal,serviceResourceCounts}=await import('../src/services.ts');
const tick=async()=>{for(let i=0;i<16;i++)await Promise.resolve();};
const reply=async(value:any)=>{events.push({t:'service.result',id:emitted.at(-1).id,ok:true,value});__pumpPodEvents();await tick();};
test('default bridge fails closed for release before subscribing',async()=>{const result=debugExternal.listen(async()=>({}));await reply({debugBuild:false});await expect(result).rejects.toThrow();expect(emitted.map(r=>r.method)).toEqual(['app.buildInfo']);expect(serviceResourceCounts().pending).toBe(0);});
test('default bridge delivers cold queued events, replies, expires and unsubscribes',async()=>{
 const calls:number[]=[];const result=debugExternal.listen(async event=>{calls.push(event.id);return {code:0,data:'ok'};});await reply({debugBuild:true});expect(emitted.at(-1).method).toBe('debug.external.subscribe');
 // Host may emit a cold-start request before acknowledging subscription.
 const subscribe=emitted.at(-1);events.push({t:'debug.external',id:72,deadline:Date.now()+4000,kind:'intent',action:'example.OPEN',extras:{},args:[]},{t:'service.result',id:subscribe.id,ok:true,value:null});__pumpPodEvents();await tick();const stop=await result;
 expect(calls).toEqual([72]);expect(emitted.at(-1).args).toEqual({id:72,code:0,data:'ok'});await reply(null);
 events.push({t:'debug.external',id:73,deadline:Date.now()-1});__pumpPodEvents();await tick();expect(calls).toEqual([72]);
 stop();expect(emitted.at(-1).method).toBe('debug.external.unsubscribe');await reply(null);events.push({t:'debug.external',id:74,deadline:Date.now()+4000});__pumpPodEvents();await tick();expect(calls).toEqual([72]);expect(serviceResourceCounts().pending).toBe(0);
});
