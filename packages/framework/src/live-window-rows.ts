import {batch,createSignal,untrack} from 'solid-js';
/** Only mounted/retained entries own signals. A new layout updates their data
 * without changing the objects used by Solid's For. A new document scope starts
 * new owners. Optional recycling requires compatible, stateless renderers whose
 * row-dependent values all read the accessor; required keyed rows are never spares.
 */
export function createLiveWindowRows<T extends {key?:unknown},Value=T>(wrap:(read:()=>T)=>Value,equals:(a:T,b:T)=>boolean,recycleKey?:(row:T)=>unknown) {
 type Slot={write:(row:T)=>unknown;row:Value;entry:T};
 let previousScope:unknown,previousSelection:readonly T[]|undefined,result:Value[]=[];
 const mounted=new Map<unknown,Slot>();
 return (selected:readonly T[],scope:unknown):Value[]=>batch(()=>{
  if(previousSelection===selected&&previousScope===scope)return result;
  previousSelection=selected;
  if(previousScope!==scope){mounted.clear();previousScope=scope;}
  const keep=new Set<unknown>(selected.map(entry=>entry.key??entry));
  const spare=recycleKey?new Map<unknown,{key:unknown;slot:Slot}[]>():undefined;
  if(spare)for(const [key,slot] of mounted)if(!keep.has(key)){
   const group=recycleKey!(slot.entry);if(group===undefined||group===null)continue;
   const list=spare.get(group)??[];list.push({key,slot});spare.set(group,list);
  }
  const next=selected.map(entry=>{
   const key=entry.key??entry;let slot=mounted.get(key);
   if(!slot&&spare){
    const group=recycleKey!(entry),reuse=group===undefined||group===null?undefined:spare.get(group)?.pop();
    if(reuse){mounted.delete(reuse.key);slot=reuse.slot;slot.write(entry);slot.entry=entry;mounted.set(key,slot);}
   }
   if(!slot){const [read,write]=createSignal(entry,{equals});slot={write,row:untrack(()=>wrap(read)),entry};mounted.set(key,slot);}
   else if(slot.entry!==entry){slot.write(entry);slot.entry=entry;}
   return slot.row;
  });
  for(const key of mounted.keys())if(!keep.has(key))mounted.delete(key);
  if(result.length!==next.length||next.some((value,index)=>value!==result[index]))result=next;
  return result;
 });
}
