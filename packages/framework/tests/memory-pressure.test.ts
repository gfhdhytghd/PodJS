import {expect,test} from 'bun:test';
import {dispatchMemoryPressure,onMemoryTrim,memoryPressureLevel,processMemoryBudgetBytes} from '../src/memory';
test('process trim preserves owner priority, validates events, and releases subscriptions',()=>{
 const previous=(globalThis as any).pod;
 try{
  (globalThis as any).pod={memoryBudgetBytes:()=>128000000};
  expect(processMemoryBudgetBytes()).toBe(128000000);
  const order:string[]=[];
  const text=onMemoryTrim(2,()=>order.push('text')),image=onMemoryTrim(1,()=>order.push('image'));
  for(const event of [{t:'memory.pressure',level:-1},{t:'memory.pressure',level:4},{t:'memory.pressure',level:1.5},{t:'other',level:3}])dispatchMemoryPressure(event);
  expect(order).toEqual([]);
  dispatchMemoryPressure({t:'memory.pressure',level:3});expect(order).toEqual(['image','text']);
  expect(memoryPressureLevel()).toBe(3);image();order.length=0;
  dispatchMemoryPressure({t:'memory.pressure',level:0});expect(order).toEqual(['text']);
  text();expect(memoryPressureLevel()).toBe(0);
 }finally{(globalThis as any).pod=previous;}
});
