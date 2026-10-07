import {batch,createSignal,untrack} from 'solid-js';
/** Only mounted/retained entries own signals. A new layout updates their data
 * without changing the objects used by Solid's For; leaving the bounded window
 * releases the state. A different document scope starts new row identities.
 */
export function createLiveWindowRows<T extends {key?:unknown},Value=T>(wrap:(read:()=>T)=>Value,equals:(a:T,b:T)=>boolean) {
 let previousScope:unknown;
 const mounted=new Map<unknown,{write:(row:T)=>unknown;row:Value}>();
 return (selected:readonly T[],scope:unknown):Value[]=>batch(()=>{
  if(previousScope!==scope){mounted.clear();previousScope=scope;}
  const keep=new Set<unknown>();
  const result=selected.map(entry=>{
   const key=entry.key??entry;keep.add(key);let item=mounted.get(key);
   if(!item){const [read,write]=createSignal(entry,{equals});item={write,row:untrack(()=>wrap(read))};mounted.set(key,item);}
   else item.write(entry);
   return item.row;
  });
  for(const key of mounted.keys())if(!keep.has(key))mounted.delete(key);
  return result;
 });
}
