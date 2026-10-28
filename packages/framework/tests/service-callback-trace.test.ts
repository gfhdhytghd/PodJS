import {test,expect,afterEach} from 'bun:test';
import {__setServiceCallbackTraceEnabled,traceServiceCallback} from '../src/service-trace.ts';
const host=(globalThis as any).pod,clock=Date.now;
afterEach(()=>{__setServiceCallbackTraceEnabled(false);Date.now=clock;(globalThis as any).pod=host;});
test('disabled has no clock/effect; strict 50ms and bounded callback labels',()=>{
 let clocks=0,now=0;const records:any[]=[];
 Date.now=()=>{clocks++;return now;};(globalThis as any).pod={emit:(s:string)=>records.push(JSON.parse(s))};
 const value={};expect(traceServiceCallback('file.read','resolve',()=>value)).toBe(value);expect(clocks).toBe(0);
 __setServiceCallbackTraceEnabled(true);
 expect(traceServiceCallback('file.read','resolve',()=>{now+=50;return value;})).toBe(value);expect(records).toHaveLength(0);
 traceServiceCallback('file.read','status',()=>{now+=51;});
 expect(records).toEqual([{t:'diagnostic.serviceCallback',service:'file.read',callback:'status',elapsedMs:51}]);
 for(let i=0;i<1000;i++)traceServiceCallback('file.read','resolve',()=>{now+=100;return 'PRIVATE_BODY';});
 expect(records).toHaveLength(64);expect(JSON.stringify(records)).not.toContain('PRIVATE');
});
test('diagnostic failures cannot replace callback return or thrown product error',()=>{
 let now=0;Date.now=()=>now;__setServiceCallbackTraceEnabled(true);
 (globalThis as any).pod={emit(){throw Error('diagnostic');}};
 expect(traceServiceCallback('sql.query','resolve',()=>{now+=51;return 42;})).toBe(42);
 const error=Error('original');
 expect(()=>traceServiceCallback('sql.query','reject',()=>{now+=51;throw error;})).toThrow(error);
});
test('a replaced or failing clock cannot suppress a callback or replace its error',()=>{
 __setServiceCallbackTraceEnabled(true);Date.now=()=>{throw Error('clock');};
 expect(traceServiceCallback('file.read','resolve',()=>42)).toBe(42);
 const error=Error('original');expect(()=>traceServiceCallback('file.read','reject',()=>{throw error;})).toThrow(error);
 let calls=0;Date.now=()=>{if(++calls>1)throw Error('end clock');return 1;};
 expect(()=>traceServiceCallback('file.read','reject',()=>{throw error;})).toThrow(error);
});
