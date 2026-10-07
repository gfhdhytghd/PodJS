export interface WindowRow {top:number;height:number;retain?:boolean;key?:unknown}
export interface WindowRowList<T> {readonly length:number;at(index:number):T|undefined;indexOfKey?:(key:unknown)=>number|undefined;scope?:unknown}
const at=<T>(rows:readonly T[]|WindowRowList<T>,index:number):T=>rows.at(index)!;
/** Retain a bounded keyed history across indexed layouts of the same document.
 * Unkeyed lists retain their original source-replacement invalidation semantics.
 */
export function createWindowRows<T extends WindowRow>(extraRows=0) {
 const limit=Number.isFinite(extraRows)?Math.max(0,Math.floor(extraRows)):0;
 let source:readonly T[]|WindowRowList<T>|undefined,first=-1,end=-1,result:T[]=[];
 const recent=new Map<unknown,{row:T;index:number}>();
 return (rows:readonly T[]|WindowRowList<T>,lower:number,upper:number):T[]=>{
  let lo=0,hi=rows.length;
  while(lo<hi){const mid=(lo+hi)>>>1,row=at(rows,mid);if(row.top+row.height<lower)lo=mid+1;else hi=mid;}
  const nextFirst=lo;hi=rows.length;
  while(lo<hi){const mid=(lo+hi)>>>1;if(at(rows,mid).top<=upper)lo=mid+1;else hi=mid;}
  const nextEnd=lo;
  if(source===rows&&first===nextFirst&&end===nextEnd)return result;
  if(source!==rows){
   const indexed=rows as WindowRowList<T>,old=source as WindowRowList<T>|undefined;
   if(indexed.indexOfKey&&old?.indexOfKey&&indexed.scope===old.scope){
    for(const [key] of recent){const index=indexed.indexOfKey(key);if(index===undefined)recent.delete(key);else {const row=at(rows,index);if(row.retain)recent.set(key,{row,index});else recent.delete(key);}}
   }else recent.clear();
   source=rows;
  }
  first=nextFirst;end=nextEnd;
  const visible:T[]=[];for(let i=first;i<end;i++)visible.push(at(rows,i));
  if(limit){
   for(let i=first;i<end;i++){const row=visible[i-first]!;if(row.retain){const key=row.key??row;recent.delete(key);recent.set(key,{row,index:i});}}
   let outside=0;for(const {index} of recent.values())if(index<first||index>=end)outside++;
   for(const [key,{index}] of recent){if(outside<=limit)break;if(index<first||index>=end){recent.delete(key);outside--;}}
   const kept=[...recent.values()].filter(({index})=>index<first||index>=end);
   for(let i=first;i<end;i++)kept.push({row:visible[i-first]!,index:i});
   kept.sort((a,b)=>a.index-b.index);
   const next=kept.map(({row})=>row);
   if(result.length===next.length&&result.every((row,i)=>row===next[i]))return result;
   result=next;
  }else result=visible;
  return result;
 };
}
