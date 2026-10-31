import {batch,createSignal,untrack} from 'solid-js';
/** Only mounted/retained entries own signals. A new layout updates their data
 * without changing the objects used by Solid's For; leaving the bounded window
 * releases the state. A different document scope starts new row identities.
 */
export function createLiveWindowRows<T extends {key?:unknown},Value=T>(wrap:(read:()=>T)=>Value,equals:(a:T,b:T)=>boolean) {
 let previousScope:unknown,previousSelection:readonly T[]|undefined,result:Value[]=[];
 const mounted=new Map<unknown,{write:(row:T)=>unknown;row:Value;entry:T}>();
 return (selected:readonly T[],scope:unknown):Value[]=>batch(()=>{
  // Window selection is immutable and reused while its bounds contain the same
  // rows. Sub-row scroll motion must not rewrite every signal or diff the For.
  if(previousSelection===selected&&previousScope===scope)return result;
  previousSelection=selected;
  if(previousScope!==scope){mounted.clear();previousScope=scope;}
  const keep=new Set<unknown>();
  const next=selected.map(entry=>{
   const key=entry.key??entry;keep.add(key);let item=mounted.get(key);
   if(!item){const [read,write]=createSignal(entry,{equals});item={write,row:untrack(()=>wrap(read)),entry};mounted.set(key,item);}
   else if(item.entry!==entry){item.write(entry);item.entry=entry;}
   return item.row;
  });
  for(const key of mounted.keys())if(!keep.has(key))mounted.delete(key);
  if(result.length!==next.length||next.some((value,index)=>value!==result[index]))result=next;
  return result;
 });
}
