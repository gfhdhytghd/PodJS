/** Caller-owned memo entries retain values only while their dependency identities
 * match. Clear on source replacement/disposal; no global application cache. */
export function createKeyedMemo<Key>() {
 const entries=new Map<Key,{dependencies:readonly unknown[];value:unknown}>();
 return {
  get<Value>(key:Key,dependencies:readonly unknown[],compute:()=>Value):Value {
   const current=entries.get(key);
   if(current&&current.dependencies.length===dependencies.length&&current.dependencies.every((value,i)=>Object.is(value,dependencies[i])))return current.value as Value;
   const value=compute();entries.set(key,{dependencies:[...dependencies],value});return value;
  },
  peek<Value>(key:Key,dependencies:readonly unknown[]):Value|undefined {const current=entries.get(key);return current&&current.dependencies.length===dependencies.length&&current.dependencies.every((value,i)=>Object.is(value,dependencies[i]))?current.value as Value:undefined;},
  clear(){entries.clear();},
  get size(){return entries.size;},
 };
}
