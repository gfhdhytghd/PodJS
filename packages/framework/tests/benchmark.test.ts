import {expect,test} from 'bun:test';
import {benchmark} from '../src/benchmark';
test('benchmark is optional and captures no guest wall clock or asynchronous host request',()=>{
 const original=(globalThis as any).pod;delete (globalThis as any).pod;
 expect(benchmark.begin('entry_start')).toBe(0);
 const lines:any[]=[];(globalThis as any).pod={benchmark:(line:string)=>{lines.push(JSON.parse(line));return 51;}};
 try{
  expect(benchmark.begin('gesture_down',{direction:'next'})).toBe(51);
  expect(benchmark.mark('gesture_release',51,{direction:'next',generation:2,source_generation:1,prewarmed:true})).toBe(51);
  expect(benchmark.presented('spinner_shown',51,{generation:2})).toBe(51);benchmark.cancelPresented('spinner_shown',51);expect(benchmark.confirmVideoFrame(51,2)).toBe(51);
  expect(lines).toEqual([{code:'gesture_down',direction:'next'},{code:'gesture_release',direction:'next',generation:2,source_generation:1,prewarmed:true,seq:51},{code:'spinner_shown',generation:2,seq:51,presented:true},{code:'spinner_shown',seq:51,cancel_presented:true},{video_frame:51,generation:2}]);
  expect(JSON.stringify(lines)).not.toContain('mono_ms');
 }finally{(globalThis as any).pod=original;}
});
