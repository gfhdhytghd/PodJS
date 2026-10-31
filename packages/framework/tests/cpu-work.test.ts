import {test,expect} from 'bun:test';
import {cpuWork,cpuWorkAsync,cpuWorkAsyncIterable,cpuSizeClass,observeCpuWork,pulseCpuWork,cpuJsonParse,cpuJsonStringify,cpuSort,type CpuWorkMetric} from '../src/cpu-work';
test('CPU observer reports only coarse scales and completes/fails without retaining input or errors',async()=>{
 const rows:CpuWorkMetric[]=[];const stop=observeCpuWork(row=>{rows.push(row);});
 try{expect(cpuWork('json-parse','chars',70000,()=>37)).toBe(37);await expect(cpuWorkAsync('crc32','bytes',1048576,async()=>{throw Error('private url?token=secret');})).rejects.toThrow('private');}finally{stop();}
 expect(rows.map(r=>r.outcome)).toEqual(['started','complete','started','failed']);expect(rows[0]).toMatchObject({stage:'json-parse',scale:'chars',sizeClass:1});expect(JSON.stringify(rows)).not.toMatch(/secret|private|token|70000|1048576/);
 cpuWork('json-parse','chars',70000,()=>1);expect(rows).toHaveLength(4);
});
test('async start is published before computation, observer rejection does not change work',async()=>{
 const order:string[]=[];let release!:()=>void;const ready=new Promise<void>(resolve=>{release=resolve;});const stop=observeCpuWork(async row=>{order.push(row.outcome);if(row.outcome==='started')await ready;});
 const result=cpuWorkAsync('crc32','bytes',65536,async()=>{order.push('compute');return 9;});expect(order).toEqual(['started']);release();expect(await result).toBe(9);expect(order).toEqual(['started','compute','complete']);stop();
 const broken=observeCpuWork(async()=>{throw Error('observer failed');});try{expect(await cpuWorkAsync('crc32','bytes',65536,async()=>4)).toBe(4);expect(cpuWork('crc32','bytes',65536,()=>5)).toBe(5);}finally{broken();}
});
test('small work is quiet; closed scopes and nested ownership do not leak heartbeats',()=>{
 const outer:CpuWorkMetric[]=[],inner:CpuWorkMetric[]=[];const stop=observeCpuWork(r=>{outer.push(r);});cpuWork('json-parse','chars',31,()=>0);
 const restore=observeCpuWork(r=>{inner.push(r);});cpuWork('crc32','bytes',65536,()=>{pulseCpuWork();});restore();cpuWork('crc32','bytes',65536,()=>0);stop();pulseCpuWork();expect(outer).toHaveLength(2);expect(inner).toHaveLength(2);
 for(const scale of ['bytes','chars','items']as const){expect(cpuSizeClass(scale,0)).toBe(0);expect(cpuSizeClass(scale,Infinity)).toBe(5);}
});
test('JSON and sort wrappers preserve builtin result/errors and stringify getter behavior',()=>{
 const stop=observeCpuWork(()=>{});try{const value={n:0,get x(){this.n++;return 'x';}};expect(cpuJsonStringify(value)).toBe('{"n":0,"x":"x"}');expect(value.n).toBe(1);expect(cpuJsonParse('{"x":1}')).toEqual({x:1});expect(()=>cpuJsonParse('bad')).toThrow(SyntaxError);const values=[3,1,2];expect(cpuSort(values,(a,b)=>a-b)).toBe(values);expect(values).toEqual([1,2,3]);}finally{stop();}
});

test('lazy CPU traversal keeps its input band and closes the source on early return or completion',async()=>{
 const rows:CpuWorkMetric[]=[];let closed=0;const stop=observeCpuWork(row=>{rows.push(row);});
 const source=async function*(){try{yield 1;yield 2;}finally{closed++;}};
 try{for await(const value of cpuWorkAsyncIterable('alias-match','items',100,source)){expect(value).toBe(1);break;}expect(closed).toBe(1);expect(rows.map(r=>r.outcome)).toEqual(['started','failed']);rows.length=0;const values:number[]=[];for await(const value of cpuWorkAsyncIterable('alias-match','items',100,source))values.push(value);expect(values).toEqual([1,2]);expect(rows.map(r=>r.outcome)).toEqual(['started','complete']);}finally{stop();}
});
