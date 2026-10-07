import {expect,test} from 'bun:test';
import {createRoot,createSignal} from 'solid-js';
import {useRestorableInput} from '../src/restorable-input';
const tick=()=>new Promise<void>(r=>setTimeout(r,0));
test('ordinary input works without recovery and stale completion cannot mutate new owner',async()=>{
 let stop!:()=>void,finish!:(v:string)=>void;const values:string[]=[],[active,setActive]=createSignal(true);
 const open=createRoot(d=>{stop=d;return useRestorableInput({title:'Prompt',value:()=>'',active,input:()=>new Promise(r=>finish=r),onValue:v=>{values.push(v);},onError:()=>{}});});
 await tick();const first=open();await open();setActive(false);await tick();finish('stale');await first;expect(values).toEqual([]);
 setActive(true);await tick();const next=open();finish('current');await next;expect(values).toEqual(['current']);stop();
});
test('missing input reports neutral error and missing recovery is optional',async()=>{
 const errors:string[]=[];let stop!:()=>void;const open=createRoot(d=>{stop=d;return useRestorableInput({title:'Prompt',value:()=>'',active:()=>true,onValue:()=>{},onError:e=>errors.push(String(e))});});
 await open();expect(errors).toEqual(['Error: Text input is unavailable']);stop();
});
