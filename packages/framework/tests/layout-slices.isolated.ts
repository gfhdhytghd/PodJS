import {expect,mock,test} from 'bun:test';
import {createRoot,createSignal,createEffect,mapArray,onCleanup} from 'solid-js';
const events:Record<string,unknown>[]=[],emitted:{t:string;id:number;method:string;args:{milliseconds?:number}}[]=[];
(globalThis as any).pod={takeEvents:()=>events.length?JSON.stringify(events.splice(0)):undefined,emit:(line:string)=>emitted.push(JSON.parse(line)),capabilities:()=>JSON.stringify(['runtime.timer'])};
mock.module("@pocketjs/framework/renderer",()=>Object.fromEntries(["createElement","insertNode","insert","setProp"].map(name=>[name,()=>{throw Error("unexpected component renderer call: "+name);}])));
mock.module('@pocketjs/framework/components',()=>({View:()=>null,Image:()=>null}));
mock.module('@pocketjs/framework/host',()=>({getOps:()=>({})}));
mock.module('@pocketjs/framework/gesture',()=>({cancelActiveTouches(){}}));
mock.module('@pocketjs/framework/input',()=>({dispatchAccessibilityAction(){}}));
mock.module('@pocketjs/framework/services',()=>({registerServicePump(){}}));
// Import public package exports, rather than an application implementation.
const {prepareTextLayout}=await import('@podjs/framework/text');
const {createLiveWindowRows,createWindowRows,createIndexedWindowSource,createWindowedList,createWindowValue,visibleWindowRange}=await import('@podjs/framework/windowed-list');
const {__pumpPodEvents}=await import('../src/watch.ts');
const settle=async()=>{for(let i=0;i<8;i++)await Promise.resolve();};
async function nextTurn(){const req=emitted.shift()!;expect(req).toMatchObject({t:'service.request',method:'runtime.delay',args:{milliseconds:1}});events.push({t:'service.result',id:req.id,ok:true,value:null});__pumpPodEvents();await settle();}
type Row={key:number;top:number;height:number;retain:boolean;data:{baseline:number;caret:number}};
function* fixture(count=513):Generator<Row,{height:number}> {let top=0;for(let i=0;i<count;i++){const height=20+i%3;yield {key:i,top,height,retain:true,data:{baseline:15+i%2,caret:i*2}};top+=height;}return {height:top};}
const callbacks=(publish:(value:any)=>void)=>({key:(row:Row)=>row.key,entry:(row:Row)=>row,publish});
test('default scheduler yields real runtime.delay(1), bounds rows and publishes a single coherent indexed snapshot',async()=>{
 const expected=[...fixture()],published:any[]=[];
 const task=prepareTextLayout(fixture(),callbacks(value=>published.push(value)));
 expect(task.stats.rows).toBe(0);expect(published).toHaveLength(0);
 while(emitted.length){const before=task.stats.rows;await nextTurn();expect(task.stats.rows-before).toBeLessThanOrEqual(64);expect(published.length).toBeLessThanOrEqual(1);}
 await task.done;expect(published).toHaveLength(1);expect(published[0].entries).toEqual(expected);expect(published[0].layout.height).toBe(expected.reduce((sum,row)=>sum+row.height,0));
 expect(task.stats.turns).toBeGreaterThan(7);expect(task.stats.rows).toBe(513);expect(task.stats.maxMs).toBeGreaterThanOrEqual(0);
 for(const [index,row] of expected.entries())expect(published[0].indices.get(row.key)).toBe(index);
});
test('default layout and byte codecs complete through Android timer validation and real macrotasks',async()=>{
 const {encodeUtf8Async,encodeBase64Async,decodeBase64Async,decodeUtf8Async}=await import('../src/async-bytes.ts');
 let complete=false,failure:unknown,turns=0;const published:any[]=[];
 const work=(async()=>{
  await prepareTextLayout(fixture(35),callbacks(value=>published.push(value))).done;
  // Exceed a codec slice; completed small metadata codecs no longer add timers.
  const text='中🙂'.repeat(14000),bytes=await encodeUtf8Async(text);
  const base64=await encodeBase64Async(bytes);expect(await decodeBase64Async(base64)).toEqual(bytes);
  expect(await decodeUtf8Async(bytes)).toBe(text);
 })().catch(error=>{failure=error;}).finally(()=>{complete=true;});
 while(!complete){
  await new Promise(resolve=>setTimeout(resolve,1));
  for(const req of emitted.splice(0)){
   expect(req.method).toBe('runtime.delay');turns++;
   // Exactly PodServices' contract, rather than accepting arbitrary SDK requests.
   const ms=req.args.milliseconds!;
   if(ms<1||ms>120000)events.push({t:'service.result',id:req.id,ok:false,code:'invalid_argument',message:'Timer delay must be 1..120000ms'});
   else {await new Promise(resolve=>setTimeout(resolve,ms));events.push({t:'service.result',id:req.id,ok:true,value:null});}
   __pumpPodEvents();
  }
 }
 await work;expect(failure).toBeUndefined();expect(published).toHaveLength(1);expect(published[0].entries).toHaveLength(35);expect(turns).toBeGreaterThanOrEqual(5);expect(turns).toBeLessThanOrEqual(12);
});
test('mid-task cancellation cancels the host operation, closes its iterator once and ignores late replies',async()=>{
 let closes=0,published=0;function* rows(){try{yield* fixture();return {height:0};}finally{closes++;}}
 const task=prepareTextLayout(rows(),callbacks(()=>published++));await nextTurn();const late=emitted.shift()!;task.cancel();task.cancel();expect(emitted.shift()).toMatchObject({t:'service.cancel',id:late.id});
 events.push({t:'service.result',id:late.id,ok:true,value:null});__pumpPodEvents();await task.done;expect(closes).toBe(1);expect(published).toBe(0);expect(emitted).toHaveLength(0);
});
test('errors and duplicate keys fail closed and release the iterator without publishing',async()=>{
 for(const mode of ['duplicate','throw','host-failure']){
  let closes=0,published=0;function* rows(){yield* fixture(1);if(mode==='throw')throw Error('iterator failed');yield* fixture(1);return {height:0};}
  const iterator=rows();const task=prepareTextLayout({next:()=>iterator.next(),return:()=>{closes++;return iterator.return({height:0});}},callbacks(()=>published++));let settled=false;const error=task.done.catch(value=>value).finally(()=>{settled=true;});
  if(mode==='host-failure'){const req=emitted.shift()!;events.push({t:'service.result',id:req.id,ok:false,code:'unavailable',message:'yield failed'});__pumpPodEvents();await settle();}else {
   // The default 1ms budget may cross a clock boundary after the first row.
   // Deliver every requested host turn until the duplicate/throw is observed.
   for(let turns=0;!settled&&turns<8;turns++)await nextTurn();
  }
  expect(await error).toBeInstanceOf(Error);expect(closes).toBe(1);expect(published).toBe(0);expect(emitted).toHaveLength(0);
 }
});
test('explicit budgets stop work on the clock boundary and invalid options are rejected',async()=>{
 let clock=0;const published:any[]=[];
 const task=prepareTextLayout(fixture(20),{...callbacks(value=>published.push(value)),entry:(row:Row)=>{clock+=.2;return row;}},{clock:()=>clock,budgetMs:.5,rowLimit:16});
 while(emitted.length){const before=task.stats.rows;await nextTurn();expect(task.stats.rows-before).toBeLessThanOrEqual(3);}
 await task.done;expect(published).toHaveLength(1);expect(task.stats.maxMs).toBeCloseTo(.6);
 for(const options of [{budgetMs:NaN},{budgetMs:0},{budgetMs:Infinity},{rowLimit:0},{rowLimit:1.5}])expect(()=>prepareTextLayout(fixture(),callbacks(()=>{}),options)).toThrow(RangeError);
});
test('keyed source replacement preserves retained history and only reads a bounded 10,000-row window',()=>{
 const scope={},make=(height:number)=>{const rows=Array.from({length:10000},(_,i)=>({key:i,top:i*height,height,retain:true}));let reads=0;return {length:rows.length,scope,at:(i:number)=>{reads++;return rows[i];},indexOfKey:(key:unknown)=>typeof key==='number'?key:undefined,reads:()=>reads};};
 const before=make(20),after=make(21),window=createWindowRows<NonNullable<ReturnType<typeof before.at>>>(24);
 window(before,0,100);const previous=window(before,200,300),next=window(after,210,315);
 for(const row of previous)expect(next.some(value=>value.key===row.key)).toBe(true);
 expect(next.every(row=>row.top===row.key*21)).toBe(true);expect(after.reads()).toBeLessThan(100);
 expect(window({...after,scope:{}},210,315).every(row=>row.top+row.height>=210&&row.top<=315)).toBe(true);
});
test('public window hook keeps Solid row owners, updates geometry/carets, suppresses equal rows and disposes on exit',async()=>{
 let mounts=0,disposed=0,readings:{top:number;baseline:number;caret:number}[]=[],stop!:()=>void;
 createRoot(dispose=>{
  stop=dispose;const scope={},first=[...fixture(1)],[source,setSource]=createSignal(createIndexedWindowSource({entries:first,indices:new Map([[0,0]]),scope}));
  // Production default equality and retention; no mock hook or keying logic.
  const window=createWindowedList({source,lower:()=>0,upper:()=>100,retainRows:24});
  const map=mapArray(window,item=>{mounts++;onCleanup(()=>disposed++);const row=createWindowValue(item.value);createEffect(()=>readings.push({top:row.top,baseline:row.data.baseline,caret:row.data.caret}));return item;});createEffect(()=>{map();});
  queueMicrotask(()=>{
   const original=window()[0],next={...first[0]!,top:3,height:22,data:{baseline:17,caret:9}};
   setSource(createIndexedWindowSource({entries:[next],indices:new Map([[0,0]]),scope}));expect(window()[0]).toBe(original);
   const count=readings.length;setSource(createIndexedWindowSource({entries:[{...next}],indices:new Map([[0,0]]),scope}));expect(readings.length).toBe(count);
  });
 });
 await settle();expect(mounts).toBe(1);expect(disposed).toBe(0);expect(readings).toEqual([{top:0,baseline:15,caret:0},{top:3,baseline:17,caret:9}]);stop();expect(disposed).toBe(1);
});
test('indexed headers/footers and object keys keep placement and exact viewport boundaries without copying the body',()=>{
 const key={},body={entries:[{key,top:0,height:20,retain:true}],indices:new Map([[key,0]]),scope:{}};
 const source=createIndexedWindowSource(body,[{height:10,key:'header',retain:false}],[{height:30,key:'footer',retain:false}],{padding:2,gap:3});
 expect(source.length).toBe(3);expect(source.at(0)?.top).toBe(2);expect(source.at(1)?.top).toBe(15);expect(source.at(-1)?.top).toBe(35);expect(source.at(-4)).toBeUndefined();expect(source.at(3)).toBeUndefined();expect(source.indexOfKey?.(key)).toBe(1);
 expect(visibleWindowRange(source,12,3)).toBeNull();expect(visibleWindowRange(source,12,4)).toEqual({first:1,last:1});
});


test('reactive window data supports frozen snapshots and added/removed native fields',()=>{
 const [current,setCurrent]=createSignal<Readonly<{text:string;baseline?:number}>>(Object.freeze({text:'same line'}));
 const row=createWindowValue(current);expect(row.text).toBe('same line');expect(Object.keys(row)).toEqual(['text']);
 setCurrent(Object.freeze({text:'same line',baseline:17}));expect(row.baseline).toBe(17);expect({...row}).toEqual({text:'same line',baseline:17});
 setCurrent(Object.freeze({text:'same line'}));expect(Object.keys(row)).toEqual(['text']);expect(row.baseline).toBeUndefined();
});

test('one immutable first prefix precedes complete geometry through the default host scheduler',async()=>{
 const prefixes:any[]=[],final:any[]=[];
 const task=prepareTextLayout(fixture(200),{...callbacks(value=>final.push(value)),preview:{rows:8,publish:value=>prefixes.push(value)}});
 for(let turns=0;!prefixes.length&&turns<16;turns++)await nextTurn();expect(prefixes).toHaveLength(1);expect(final).toHaveLength(0);
 const count=prefixes[0].entries.length;expect(count).toBeGreaterThanOrEqual(8);
 while(emitted.length)await nextTurn();await task.done;
 expect(prefixes).toHaveLength(1);expect(prefixes[0].entries).toHaveLength(count);expect(prefixes[0].indices.size).toBe(count);
 expect(final).toHaveLength(1);expect(final[0].entries).toEqual([...fixture(200)]);
 expect(final[0].entries.slice(0,count)).toEqual(prefixes[0].entries);
});
test('cancel after the first prefix closes work and suppresses all later publication',async()=>{
 let prefixes=0,final=0;const task=prepareTextLayout(fixture(200),{...callbacks(()=>final++),preview:{rows:1,publish:()=>prefixes++}});
 await nextTurn();const late=emitted.shift()!;task.cancel();expect(emitted.shift()).toMatchObject({t:'service.cancel',id:late.id});
 events.push({t:'service.result',id:late.id,ok:true,value:null});__pumpPodEvents();await task.done;
 expect(prefixes).toBe(1);expect(final).toBe(0);
});

test('HTML fragments use default valid host timers and cancellation releases native jobs',async()=>{
 const {requestHtmlFragment}=await import('../src/html-slices.ts');
 const pod=(globalThis as any).pod;let releases=0,published:any;
 Object.assign(pod,{htmlBegin:()=>1,htmlAppend:()=>true,htmlNext:()=>[true,[[0,'p',[],''],[1,'',[],'body']]],htmlCancel:()=>releases++});
 try{
  const work=requestHtmlFragment('<p>body</p>',value=>published=value);
  while(emitted.length)await nextTurn();await work.done;
  expect(published.children[0].children).toEqual(['body']);expect(releases).toBe(1);
  let late=0;const cancelled=requestHtmlFragment('<p>body</p>',()=>late++,{rowLimit:1});
  await nextTurn();const request=emitted.shift()!;cancelled.cancel();expect(emitted.shift()).toMatchObject({t:'service.cancel',id:request.id});
  events.push({t:'service.result',id:request.id,ok:true,value:null});__pumpPodEvents();await settle();await cancelled.done;expect(late).toBe(0);expect(releases).toBe(2);
 }finally{for(const key of ['htmlBegin','htmlAppend','htmlNext','htmlCancel'])delete pod[key];}
});


test('cumulative prefixes are immutable, retain prior row identities and stop on cancellation',async()=>{
 const prefixes:any[]=[],final:any[]=[];
 const task=prepareTextLayout(fixture(200),{...callbacks(value=>final.push(value)),progress:{rows:64,publish:value=>prefixes.push(value)}});
 while(emitted.length)await nextTurn();await task.done;
 expect(prefixes.map(p=>p.entries.length)).toEqual([64,128,192]);expect(final).toHaveLength(1);
 expect(prefixes[0].entries).toHaveLength(64);expect(prefixes[0].indices.size).toBe(64);
 expect(prefixes[1].entries[0]).toBe(prefixes[0].entries[0]);expect(final[0].entries[0]).toBe(prefixes[0].entries[0]);
 let partials=0,complete=0;const cancelled=prepareTextLayout(fixture(200),{...callbacks(()=>complete++),progress:{rows:1,publish:()=>partials++}});
 await nextTurn();cancelled.cancel();await cancelled.done;expect(partials).toBe(1);expect(complete).toBe(0);
 emitted.splice(0);events.splice(0);
});


test('opted-in compatible rows recycle owners while required keys and document scopes stay distinct',()=>{
 let allocations=0;const scope={};
 type Entry={key:string;kind:string;text:string};
 const live=createLiveWindowRows<Entry,{token:number;read:()=>Entry}>(read=>({token:++allocations,read}),(a,b)=>a.key===b.key&&a.text===b.text,row=>row.kind==='text'?'text':undefined);
 const a={key:'a',kind:'text',text:'A'},b={key:'b',kind:'text',text:'B'},header={key:'h',kind:'header',text:'header'};
 const first=live([a,b,header],scope),second=live([b,{key:'c',kind:'text',text:'C'},header],scope);
 expect(second[0]).toBe(first[1]);expect(second[1]).toBe(first[0]);expect(second[1].read().key).toBe('c');expect(second[2]).toBe(first[2]);expect(allocations).toBe(3);
 const reversed=live([a,b,header],scope);expect(reversed[0]).toBe(second[1]);expect(reversed[0].read()).toBe(a);expect(allocations).toBe(3);
 const next=live([a,b,header],{});expect(next[0]).not.toBe(reversed[0]);expect(allocations).toBe(6);
 const select=createWindowRows<any>(0,8),rows=Array.from({length:20},(_,i)=>({top:i*10,height:10,key:i}));
 expect(select(rows,-5,15).length).toBe(8);const tail=select(rows,190,210);expect(tail.length).toBe(8);expect(tail.at(-1).key).toBe(19);
});
