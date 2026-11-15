import {expect,mock,test} from 'bun:test';
const events:any[]=[],emitted:any[]=[];
(globalThis as any).pod={capabilities:()=>JSON.stringify(['host.lifecycle']),takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line))};
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
const {__pumpPodEvents}=await import('../src/watch.ts');
const {registerShellCommands,ShellCommandError,shellDiagnostics}=await import('../src/shell.ts');
const tick=async()=>{for(let n=0;n<16;n++)await Promise.resolve();};
test('registry returns JSON, redacts errors, cancels queued work and owns its listener',async()=>{
 let calls=0;
 const stop=registerShellCommands({'demo.go':async()=>{calls++;return {number:1};},'demo.fail':()=>{throw Error('sensitive sample');},'demo.bad':()=>{throw new ShellCommandError('invalid_argument');}});
 expect(()=>registerShellCommands({})).toThrow();
 for(const [id,method] of [[1,'demo.go'],[2,'demo.fail'],[3,'demo.bad']] as const){events.push({t:'shell.command',id,method,args:{}});__pumpPodEvents();await tick();}
 expect(emitted.find(e=>e.id===1).json).toBe('{"number":1}');expect(emitted.find(e=>e.id===2).error).toBe('command_failed');expect(emitted.find(e=>e.id===3).error).toBe('invalid_argument');expect(JSON.stringify(emitted)).not.toContain('sensitive sample');
 events.push({t:'shell.command',id:4,method:'demo.go',args:{}},{t:'shell.cancel',id:4});__pumpPodEvents();await tick();expect(calls).toBe(1);expect(emitted.some(e=>e.id===4)).toBe(false);
 stop();stop();events.push({t:'shell.command',id:5,method:'demo.go',args:{}});__pumpPodEvents();await tick();expect(calls).toBe(1);registerShellCommands({})();
});

test('numeric diagnostics is admitted by the host service contract',async()=>{
 const request=shellDiagnostics();const sent=emitted.at(-1);expect(sent.method).toBe('shell.diagnostics');
 const value={pssKb:42,decoderInstances:1,decoderLeases:1,sql:{},videoSessions:{active:1,created:1,released:0}};events.push({t:'service.result',id:sent.id,ok:true,value});__pumpPodEvents();expect(await request).toEqual(value);
});
