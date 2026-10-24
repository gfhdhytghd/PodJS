import {expect,test} from 'bun:test';
import {runWorkSteps,stableSortSteps} from '../src/async';
const collect=<T>(steps:Iterator<unknown,T>)=>{for(;;){const r=steps.next();if(r.done)return r.value;}};
test('stable sort handles lexical numeric, reversed, duplicate and NaN-equal keys without changing its input',()=>{
 const rows=Array.from({length:13830},(_,i)=>({n:i,index:i})).sort((a,b)=>String(a.n)<String(b.n)?-1:1),before=rows.slice();let comparisons=0;
 expect(collect(stableSortSteps(rows,(a,b)=>{comparisons++;return a.n-b.n;})).map(x=>x.n)).toEqual(Array.from({length:rows.length},(_,i)=>i));expect(rows).toEqual(before);expect(comparisons).toBeLessThan(rows.length*Math.ceil(Math.log2(rows.length)));
 const equal=Array.from({length:40},(_,i)=>({key:i%4,index:i}));expect(collect(stableSortSteps(equal,(a,b)=>a.key-b.key))).toEqual(equal.slice().sort((a,b)=>a.key-b.key));expect(collect(stableSortSteps(equal,()=>NaN))).toEqual(equal);
 expect(collect(stableSortSteps([4,3,2,1],(a,b)=>a-b))).toEqual([1,2,3,4]);expect(collect(stableSortSteps<number>([],(a,b)=>a-b))).toEqual([]);
});
test('work slices bound steps, yield real turns and do not retain an entry for each comparison',async()=>{
 const input=Array.from({length:1000},(_,i)=>1000-i),slices:number[]=[];let heartbeat=false;
 setTimeout(()=>{heartbeat=true;},0);
 const output=await runWorkSteps(stableSortSteps(input,(a,b)=>a-b),{stepLimit:256,budgetMs:100,yieldTurn:()=>new Promise(resolve=>setTimeout(resolve,0)),onSlice:(_ms,n)=>slices.push(n)});
 expect(heartbeat).toBe(true);expect(slices.length).toBeGreaterThan(8);expect(Math.max(...slices)).toBeLessThanOrEqual(256);expect(output).toEqual(input.slice().reverse());
});
test('pause during a yielded slice closes delegated inputs and cannot publish a partial sort',async()=>{
 let closed=false,cancelled=false,yields=0,reads=0;
 function* input(){try{for(let i=100;i>0;i--){reads++;yield i;}}finally{closed=true;}}
 await expect(runWorkSteps(stableSortSteps(input(),(a,b)=>a-b),{stepLimit:8,yieldTurn:async()=>{if(++yields===2)cancelled=true;},check(){if(cancelled)throw Error('suspended');}})).rejects.toThrow('suspended');expect(reads).toBe(8);expect(closed).toBe(true);
});
test('work failures close the iterator and invalid budgets fail before executing it',async()=>{
 let closed=false;function* steps(){try{yield;throw Error('work failed');}finally{closed=true;}}
 await expect(runWorkSteps(steps(),{yieldTurn:async()=>{}})).rejects.toThrow('work failed');expect(closed).toBe(true);
 await expect(runWorkSteps(steps(),{yieldTurn:async()=>{},stepLimit:0})).rejects.toThrow('budget');
});

test('host I/O completions start a fresh CPU budget instead of causing an extra host request',async()=>{
 const {HostWorkBudget}=await import('../src/async');let now=0,yields=0,cancelled=false;
 const gate=new HostWorkBudget({clock:()=>now,yieldTurn:async()=>{yields++;now+=10;},check:()=>{if(cancelled)throw Error('cancelled');},budgetMs:2,stepLimit:32});
 for(let i=0;i<2135;i++){now+=8;gate.boundary();await gate.checkpoint();}expect(yields).toBe(0);
 now+=3;await gate.checkpoint();expect(yields).toBe(1);
 for(let i=0;i<32;i++)await gate.checkpoint();expect(yields).toBe(2);
 cancelled=true;await expect(gate.checkpoint()).rejects.toThrow('cancelled');
});
