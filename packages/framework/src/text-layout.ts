import {delay,type HostOperation} from './services.ts';

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
}
/** Prepare shaped paragraph rows, prefixes and a stable-key index in bounded
 * host turns. The caller's iterator owns geometry; a single completed snapshot
 * is published for a windowed consumer. No partially built source is exposed.
 * An iterator step must itself be bounded: a single indivisible shaping call
 * cannot be preempted by this scheduler. Cancellation closes the iterator and
 * cancels the outstanding runtime.delay; late replies never publish.
 */
export function prepareTextLayout<Row,Layout,Entry,Key>(steps:Iterator<Row,Layout>,
 callbacks:{key:(row:Row)=>Key;entry:(row:Row)=>Entry;publish:(snapshot:TextLayoutSnapshot<Layout,Entry,Key>)=>void},
 options:TextLayoutSliceOptions={}) {
 const clock=options.clock??Date.now,yieldTurn=options.yieldTurn??(()=>delay(0));
 const budget=options.budgetMs??.5,limit=options.rowLimit??16;
 if(!Number.isFinite(budget)||budget<=0||!Number.isSafeInteger(limit)||limit<1)throw new RangeError('Invalid text layout slice budget');
 const entries:Entry[]=[],indices=new Map<Key,number>(),stats:TextLayoutSliceStats={turns:0,maxMs:0,rows:0};
 let cancelled=false,closed=false,pending:HostOperation<unknown>|undefined;
 const close=()=>{if(!closed){closed=true;steps.return?.();}};
 const done=(async()=>{
  try{
   // Leave even an already-resolved paragraph service's completion turn.
   while(!cancelled){
    pending=yieldTurn();try{await pending.result;}catch(error){if(cancelled)return;throw error;}finally{pending=undefined;}
    if(cancelled)return;
    const began=clock();let count=0,finished:IteratorResult<Row,Layout>|undefined;
    while(!cancelled){
     const next=steps.next();
     if(next.done){finished=next;closed=true;break;}
     const key=callbacks.key(next.value);
     if(indices.has(key))throw new Error('Duplicate text layout row key');
     indices.set(key,entries.length);entries.push(callbacks.entry(next.value));count++;stats.rows++;
     if(count>=limit||clock()-began>=budget)break;
    }
    const elapsed=clock()-began;stats.turns++;stats.maxMs=Math.max(stats.maxMs,elapsed);options.onSlice?.(elapsed,count);
    if(cancelled)return;
    if(finished?.done){callbacks.publish({layout:finished.value,entries,indices});return;}
   }
  }finally{close();}
 })();
 return {done,stats,cancel(){if(cancelled)return;cancelled=true;pending?.cancel();close();}};
}
