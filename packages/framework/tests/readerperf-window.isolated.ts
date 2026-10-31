import {test,expect} from 'bun:test';
import {createRoot,createSignal,createEffect} from 'solid-js';
import {createWindowedList} from '../src/windowed-list.ts';
test('sub-row motion preserves the mounted array; crossing rows and source updates still publish',()=>{
 let dispose=()=>{},read!:ReturnType<typeof createWindowedList>,move!:(value:number)=>void,replace!:(value:any)=>void,runs=0;
 const rows=Array.from({length:100},(_,key)=>({key,top:key*20,height:20,text:'before'}));
 createRoot(stop=>{dispose=stop;const [lower,setLower]=createSignal(1),[source,setSource]=createSignal(rows);move=setLower;replace=setSource;
  read=createWindowedList({source,lower,upper:()=>lower()+8});createEffect(()=>{read();runs++;});
 });
 const initial=read();for(let i=2;i<10;i++)move(i);
 expect(read()).toBe(initial);expect(runs).toBe(1);
 move(21);expect(read()).not.toBe(initial);expect(read()[0]!.value().key).toBe(1);
 replace(rows.map(row=>({...row,text:'after'})));expect((read()[0]!.value() as any).text).toBe('after');
 dispose();
});

test('same keys and scope update row accessors without notifying the array consumer',()=>{
 let dispose=()=>{},replace!:(value:any)=>void,read!:ReturnType<typeof createWindowedList>,runs=0;
 const scope={},first={key:1,top:0,height:20,text:'before'};
 createRoot(stop=>{dispose=stop;const [row,setRow]=createSignal(first);replace=setRow;
  read=createWindowedList({source:()=>({length:1,scope,indexOfKey:()=>0,at:()=>row()}),lower:()=>1,upper:()=>10});
  createEffect(()=>{read();runs++;});
 });
 const mounted=read();replace({...first,text:'after'});
 expect(read()).toBe(mounted);expect((read()[0]!.value() as any).text).toBe('after');expect(runs).toBe(1);dispose();
});
