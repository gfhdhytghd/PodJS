import {splitTextLineSteps} from './line-split.ts';
import * as services from './services.ts';
import type {HostOperation} from './services.ts';
import {traceServiceWork} from './service-trace.ts';

export interface TextLayoutSnapshot<Layout,Entry,Key> {
 readonly layout:Layout;
 readonly entries:Entry[];
 readonly indices:ReadonlyMap<Key,number>;
}
export interface TextLayoutSliceStats {turns:number;maxMs:number;rows:number}
export interface TextLayoutSliceOptions {
 /** Wall-clock work budget, excluding the host yield and final publication. */
 budgetMs?:number;
 /** Also bounds work when the host clock has only millisecond precision. */
 rowLimit?:number;
 clock?:()=>number;
 yieldTurn?:()=>HostOperation<unknown>;
 onSlice?:(ms:number,rows:number)=>void;
 /** Fixed code-authored diagnostic label; default tracing remains disabled. */
 traceLabel?:string;
}
/** Prepare shaped paragraph rows, prefixes and a stable-key index in bounded
 * host turns. The caller's iterator owns geometry; a single completed snapshot
 * is published for a windowed consumer. An optional first prefix can be
 * published once for immediate display; later work never mutates that prefix.
 * An iterator step must itself be bounded: a single indivisible shaping call
 * cannot be preempted by this scheduler. Cancellation closes the iterator and
 * cancels the outstanding runtime.delay; late replies never publish.
 */
export function prepareTextLayout<Row,Layout,Entry,Key>(steps:Iterator<Row,Layout>,
 callbacks:{key:(row:Row)=>Key;entry:(row:Row)=>Entry;publish:(snapshot:TextLayoutSnapshot<Layout,Entry,Key>)=>void;
  preview?:{rows:number;publish:(snapshot:Pick<TextLayoutSnapshot<Layout,Entry,Key>,"entries"|"indices">)=>void}},
 options:TextLayoutSliceOptions={}) {
 // Android runtime.timer accepts 1..120000ms; zero is a rejected request,
 // so every default slice must use the minimum valid host timer.
 const clock=options.clock??Date.now,yieldTurn=options.yieldTurn??(()=>services.delay(1));
 // 64 cheap steps avoid a host roundtrip for every 16 scan/row operations.
 // The 1ms wall limit still applies to expensive steps and coarse host clocks.
 const budget=options.budgetMs??1,limit=options.rowLimit??64;
 const preview=callbacks.preview;
 if(preview&&(!Number.isSafeInteger(preview.rows)||preview.rows<1))throw new RangeError('Invalid text layout preview size');
 if(!Number.isFinite(budget)||budget<=0||!Number.isSafeInteger(limit)||limit<1)throw new RangeError('Invalid text layout slice budget');
 const entries:Entry[]=[],indices=new Map<Key,number>(),stats:TextLayoutSliceStats={turns:0,maxMs:0,rows:0};
 let previewed=false,cancelled=false,closed=false,pending:HostOperation<unknown>|undefined;
 const close=()=>{if(!closed){closed=true;steps.return?.();}};
 const done=(async()=>{
  try{
   // Leave even an already-resolved paragraph service's completion turn.
   while(!cancelled){
    pending=yieldTurn();try{await pending.result;}catch(error){if(cancelled)return;throw error;}finally{pending=undefined;}
    if(cancelled)return;
    const began=clock();let count=0,finished:IteratorResult<Row,Layout>|undefined;
    const collect=()=>{while(!cancelled){
     const next=steps.next();
     if(next.done){finished=next;closed=true;break;}
     const key=callbacks.key(next.value);
     if(indices.has(key))throw new Error('Duplicate text layout row key');
     indices.set(key,entries.length);entries.push(callbacks.entry(next.value));count++;stats.rows++;
     if(count>=limit||clock()-began>=budget)break;
    }};
    if(options.traceLabel)traceServiceWork(options.traceLabel,collect);else collect();
    const elapsed=clock()-began;stats.turns++;stats.maxMs=Math.max(stats.maxMs,elapsed);options.onSlice?.(elapsed,count);
    if(cancelled)return;
    if(!finished?.done&&preview&&!previewed&&entries.length>=preview.rows){
     previewed=true;preview.publish({entries:entries.slice(),indices:new Map(indices)});
    }
    if(cancelled)return;
    if(finished?.done){callbacks.publish({layout:finished.value,entries,indices});return;}
   }
  }finally{close();}
 })();
 return {done,stats,cancel(){if(cancelled)return;cancelled=true;pending?.cancel();close();}};
}

/** Host frame boundary when available; portable timer for non-host callers.
 * Existing layout callers retain their original host-only default scheduler.
 */
export function yieldWorkTurn():HostOperation<void> {
 const host=(globalThis as {pod?:{capabilities?:()=>string}}).pod;
 if(host?.capabilities&&JSON.parse(host.capabilities()).includes('runtime.timer'))return services.delay(1);
 let timer:ReturnType<typeof setTimeout>,reject:(error:Error)=>void=()=>{};
 const result=new Promise<void>((resolve,fail)=>{reject=fail;timer=setTimeout(resolve,1);});
 return {result,cancel(){clearTimeout(timer);reject(Error('work cancelled'));}};
}

export * from "./text-lines.ts";

/** Bounded line scanning with the same empty/trailing/mixed-newline behavior as
 * String.split(/\r\n|\n|\r/). The default yields before every 16 KiB piece. */
export async function splitTextLinesAsync(source:string,options:TextLayoutSliceOptions&{chunkUnits?:number}={}):Promise<string[]> {
 let index=0,lines:string[]|undefined;
 const task=prepareTextLayout(splitTextLineSteps(source,options.chunkUnits),{key:()=>index++,entry:()=>undefined,publish:snapshot=>{lines=snapshot.layout;}},{rowLimit:1,yieldTurn:yieldWorkTurn,...options});
 await task.done;if(!lines)throw Error('line split cancelled');return lines;
}
