import {expect,test,afterEach,beforeEach} from 'bun:test';
import {__setServiceTraceEnabled,traceServiceWork} from '../src/service-trace.ts';
let previousHost:unknown;
beforeEach(()=>{previousHost=(globalThis as any).pod;});
afterEach(()=>{__setServiceTraceEnabled(false);(globalThis as any).pod=previousHost;});
test('default path returns identity, preserves throws and does not touch a clock or host',()=>{
 const clock=Date.now;let clocks=0,effects=0;
 Date.now=()=>{clocks++;return 0;};
 (globalThis as any).pod={emit(){effects++;}};
 try {const value={};expect(traceServiceWork('sql.map',()=>value)).toBe(value);
  const error=new Error('product');expect(()=>traceServiceWork('sql.map',()=>{throw error;})).toThrow(error);
  expect(clocks).toBe(0);expect(effects).toBe(0);
 }finally{Date.now=clock;}
});
test('opt-in nested spans subtract traced children and never emit function data',()=>{
 const clock=Date.now,records:any[]=[];let now=0;Date.now=()=>now;
 (globalThis as any).pod={emit:(line:string)=>records.push(JSON.parse(line))};
 __setServiceTraceEnabled(true);
 try{expect(traceServiceWork('sql.map',()=>{now+=2;traceServiceWork('sql.decode',()=>{now+=3;});now+=5;return 'private body';})).toBe('private body');
  expect(records).toEqual([{t:'diagnostic.serviceWork',label:'sql.decode',elapsedMs:3,exclusiveMs:3,beginEpochMs:2,endEpochMs:5},{t:'diagnostic.serviceWork',label:'sql.map',elapsedMs:10,exclusiveMs:7,beginEpochMs:0,endEpochMs:10}]);
  traceServiceWork('private body with spaces',()=>{});expect(records).toHaveLength(2);
 }finally{Date.now=clock;}
});
test('failed diagnostics preserve returns and product errors',()=>{
 __setServiceTraceEnabled(true);(globalThis as any).pod={emit(){throw Error('diagnostic failure');}};
 expect(traceServiceWork('sql.map',()=>42)).toBe(42);
 expect(()=>traceServiceWork('sql.map',()=>{throw Error('product failure');})).toThrow('product failure');
});
test('size metadata is numeric only and invalid values are omitted',()=>{
 const records:any[]=[];(globalThis as any).pod={emit:(line:string)=>records.push(JSON.parse(line))};
 __setServiceTraceEnabled(true);
 traceServiceWork('storage.write',()=>{},4096);traceServiceWork('storage.write',()=>{},NaN);
 expect(records[0].units).toBe(4096);expect(records[1].units).toBeUndefined();
});
