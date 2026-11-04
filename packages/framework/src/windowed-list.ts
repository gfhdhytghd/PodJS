import {createMemo,type Accessor} from 'solid-js';
import {createWindowRows,type WindowRow,type WindowRowList} from './windowed-rows.ts';
import {createLiveWindowRows} from './live-window-rows.ts';
export {createWindowRows,createLiveWindowRows};
export type {WindowRow,WindowRowList};

export interface IndexedWindowEntries<T extends WindowRow,Key=unknown> {
 entries:readonly T[];
 indices:ReadonlyMap<Key,number>;
 /** Same immutable scope across layout revisions, different for a new document. */
 scope:unknown;
}
export interface WindowItem<T> {key:unknown;value:Accessor<T>}
/** Reactive object view for the data owned by a mounted window item. */
export function createWindowValue<T extends object>(value:Accessor<T>):T {
 return new Proxy({} as T,{get:(_target,key)=>Reflect.get(value(),key),ownKeys:()=>Reflect.ownKeys(value()),
  getOwnPropertyDescriptor:(_target,key)=>Reflect.has(value(),key)?{enumerable:true,configurable:true,value:Reflect.get(value(),key)}:undefined});
}
function sameFields<T extends object>(a:T,b:T):boolean {
 if(a===b)return true;
 const keys=Object.keys(a);return keys.length===Object.keys(b).length&&keys.every(key=>Object.is(Reflect.get(a,key),Reflect.get(b,key)));
}
/** A keyed Solid window. Source changes reconcile only the visible rows and
 * bounded retained history using indexOfKey; equal rows do not notify owners.
 * Unkeyed arrays keep source replacement invalidation and object identity.
 * The immutable source and its index must agree and keys must be unique.
 */
export function createWindowedList<T extends WindowRow>(options:{source:Accessor<readonly T[]|WindowRowList<T>>;lower:Accessor<number>;upper:Accessor<number>;retainRows?:number;minRows?:number;recycleKey?:(row:T)=>unknown;equals?:(a:T,b:T)=>boolean}):Accessor<WindowItem<T>[]> {
 const select=createWindowRows<T>(options.retainRows,options.minRows);
 const mount=createLiveWindowRows<T,WindowItem<T>>(read=>options.recycleKey?({get key(){return read().key??read();},value:read}):({key:read().key??read(),value:read}),options.equals??sameFields,options.recycleKey);
 return createMemo(()=>{
  const source=options.source(),selected=select(source,options.lower(),options.upper());
  const scope=(source as WindowRowList<T>).indexOfKey?(source as WindowRowList<T>).scope:source;
  return mount(selected,scope);
 });
}
/** Compose small headers/footers with pre-positioned body rows without copying
 * or remapping the full body. Body keys must match its prebuilt indices.
 */
export function createIndexedWindowSource<T extends WindowRow,Key>(body:IndexedWindowEntries<T,Key>,header:readonly Omit<T,'top'>[]=[],footer:readonly Omit<T,'top'>[]=[],options:{padding?:number;gap?:number}={}):WindowRowList<T> {
 let top=options.padding??0;const gap=options.gap??0;
 const before=header.map(entry=>{const row={...entry,key:entry.key??entry,top} as T;top+=entry.height+gap;return row;});
 const bodyTop=top,last=body.entries.at(-1);top+=last?last.top+last.height:0;
 const after=footer.map(entry=>{const row={...entry,key:entry.key??entry,top} as T;top+=entry.height+gap;return row;});
 const length=before.length+body.entries.length+after.length;
 return {length,scope:body.scope,
  at(index){if(index<0)index+=length;if(index<0||index>=length)return undefined;if(index<before.length)return before[index];index-=before.length;if(index<body.entries.length){const row=body.entries[index]!;return {...row,top:bodyTop+row.top};}return after[index-body.entries.length];},
  indexOfKey(key){const index=body.indices.get(key as Key);if(index!==undefined)return before.length+index;const h=before.findIndex(row=>row.key===key);if(h>=0)return h;const f=after.findIndex(row=>row.key===key);return f<0?undefined:before.length+body.entries.length+f;}
 };
}
/** Actual viewport intersection (exclusive end edges), without overscan. */
export function visibleWindowRange<T extends {top:number;height:number}>(rows:readonly T[]|WindowRowList<T>,offset:number,height:number):{first:number;last:number}|null {
 if(height<=0||!rows.length)return null;
 let low=0,high=rows.length;
 while(low<high){const mid=(low+high)>>>1,row=rows.at(mid)!;if(row.top+row.height<=offset)low=mid+1;else high=mid;}
 const first=low;high=rows.length;
 while(low<high){const mid=(low+high)>>>1;if(rows.at(mid)!.top<offset+height)low=mid+1;else high=mid;}
 return first<low?{first,last:low-1}:null;
}
