import {test,expect} from 'bun:test';
import {WorkWindow,windowedHost} from '../src/work-window';
test('default clock admits host work; no dependency injection is required',async()=>{
 const window=new WorkWindow({deadlineMs:Date.now()+300000,reserveMillis:10000,expired:()=>Error('expired')});
 expect(await window.run('host',1000,async()=>42)).toBe(42);
});
test('a long individual transform is deferred before dispatch, then admitted in a fresh window',async()=>{
 let now=280000,calls:string[]=[];const metrics:any[]=[];
 const make=()=>windowedHost(async<T>(method:string,args:Record<string,unknown>)=>{calls.push(method);return (method==='file.stat'?{size:8*1024*1024}:null) as T;},new WorkWindow({deadlineMs:300000,reserveMillis:10000,now:()=>now,expired:()=>Error('expired'),report:m=>metrics.push(m)}));
 let host=make();await host('file.stat',{path:'input'});await expect(host('crypto.largeAesGcmDecrypt',{inputPath:'input'})).rejects.toThrow('expired');expect(calls).toEqual(['file.stat']);expect(metrics.at(-1)).toMatchObject({stage:'decrypt',outcome:'deferred'});
 now=0;host=make();await host('file.stat',{path:'input'});await host('crypto.largeAesGcmDecrypt',{inputPath:'input'});expect(calls.at(-1)).toBe('crypto.largeAesGcmDecrypt');
});
test('many small secure writes stop before the deadline and preserve completed writes across windows',async()=>{
 let now=0,at=0;const saved:number[]=[];
 for(let round=0;round<8&&at<200;round++){
  now=0;const window=new WorkWindow({deadlineMs:10000,reserveMillis:1000,now:()=>now,expired:()=>Error('expired')});
  const host=windowedHost(async<T>(_m:string,a:Record<string,unknown>)=>{now+=150;saved.push(Number(a.value));return null as T;},window);
  try{for(;at<200;at++)await host('secure.set',{value:at});}catch(e){expect(String(e)).toContain('expired');}
  expect(now).toBeLessThan(9000);
 }
 expect(at).toBe(200);expect(saved).toEqual(Array.from({length:200},(_,i)=>i));
});
test('measured slow units increase future admission; a running write is awaited with its lease',async()=>{
 let now=0,held=true;const w=new WorkWindow({deadlineMs:10000,reserveMillis:1000,now:()=>now,expired:()=>Error('expired')});
 await w.run('file-commit',1000,async()=>{expect(held).toBe(true);now+=3500;});
 await expect(w.run('file-commit',1000,async()=>{throw Error('must not dispatch');})).rejects.toThrow('expired');held=false;
});

test('temporary cleanup drains after admission closes, but cannot cross the shutdown reserve',async()=>{
 let now=288500;const calls:string[]=[];const w=new WorkWindow({deadlineMs:300000,reserveMillis:10000,now:()=>now,expired:()=>Error('expired')});
 const host=windowedHost(async<T>(m:string,_a:Record<string,unknown>)=>{calls.push(m);return null as T;},w);
 await expect(host('crypto.largeAesGcmEncrypt',{inputPath:'tmp'})).rejects.toThrow('expired');
 await host('file.delete',{path:'tmp'});expect(calls).toEqual(['file.delete']);
 now=299000;await expect(host('file.delete',{path:'tmp'})).rejects.toThrow('expired');
 expect(calls).toEqual(['file.delete']);await host('cloud.release',{});expect(calls.at(-1)).toBe('cloud.release');
});

test('network total/connect/read timeouts fit the remaining window',async()=>{
 let received:Record<string,unknown>={};
 const w=new WorkWindow({deadlineMs:300000,reserveMillis:10000,now:()=>280000,expired:()=>Error('expired')});
 const host=windowedHost(async<T>(_m:string,a:Record<string,unknown>)=>{received=a;return null as T;},w);
 await host('http.download',{callTimeoutMs:120000,timeoutMs:15000,readTimeoutMs:30000});
 expect(received).toEqual({callTimeoutMs:9000,timeoutMs:9000,readTimeoutMs:9000});
});
