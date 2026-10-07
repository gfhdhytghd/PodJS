import {expect,test} from 'bun:test';
import {createRoot,createSignal,createMemo,getOwner} from 'solid-js';
import {installDebugMemoryStats,trackDebugMemoryOwner,solidMemoryResourceCounts} from '../src/debug-memory.ts';

test('default owner tracking is off; explicit debug tracking disposes its real root',()=>{
 createRoot(dispose=>{
  trackDebugMemoryOwner();
  expect(solidMemoryResourceCounts()).toBeNull();
  expect(getOwner()!.cleanups).toBeNull();
  dispose();
 });
 installDebugMemoryStats();
 let stop=()=>{};
 createRoot(dispose=>{
  stop=dispose;trackDebugMemoryOwner();
  const [enabled,setEnabled]=createSignal(true),active=createMemo(()=>enabled());
  const initial=solidMemoryResourceCounts();
  expect(initial).toMatchObject({roots:1,computations:1,signalObservers:1});
  for(let round=0;round<6;round++){
   for(let frame=0;frame<900;frame++)expect(active()).toBe(true);
   setEnabled(false);expect(active()).toBe(false);setEnabled(true);
   expect(solidMemoryResourceCounts()).toEqual(initial);
  }
 });
 stop();
 expect(solidMemoryResourceCounts()).toEqual({roots:0,owners:0,computations:0,signals:0,signalObservers:0});
});
