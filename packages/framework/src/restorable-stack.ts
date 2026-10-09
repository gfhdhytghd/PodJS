import {stringToUtf8} from './bytes.ts';
import type {ActivityState} from './services.ts';
export interface StackMemory {id:string;saved:Record<string,unknown>;retained:Record<string,unknown>}
/** Instance-owned identities; the caller's decoder validates externally supplied IDs. */
export function createRouteMemoryAllocator<R extends {memory?:StackMemory}>(prefix='route-') {
 let sequence=0;
 return {
  memory(entry:R):StackMemory {return entry.memory??={id:`${prefix}${++sequence}`,saved:{},retained:{}};},
  observe(ids:Iterable<string>):void {for(const id of ids){const suffix=id.startsWith(prefix)?id.slice(prefix.length):'';if(/^\d+$/.test(suffix))sequence=Math.max(sequence,Number(suffix));}},
 };
}
export interface RestorableStackOptions<R,D extends {id:string},C> {
 namespace:string;version?:number;maxEntries?:number;maxSavedBytes?:number;maxRetainedBytes?:number;
 memory(entry:R):StackMemory;
 encode(entry:R,memory:StackMemory):D|null;
 decode(raw:unknown,retained:unknown,context:C):R|null;
 captureContext:C;
 retained(memory:StackMemory):Record<string,unknown>;
 isRoot(entry:R):boolean;
 restoredIds?(ids:ReadonlySet<string>):void;
}
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
/** Bounded saved/retained envelopes and valid-prefix recovery; codecs own route policy. */
export function createRestorableStack<R,D extends {id:string},C>(options:RestorableStackOptions<R,D,C>) {
 const version=options.version??1,maxEntries=options.maxEntries??64,maxSaved=options.maxSavedBytes??65536,maxRetained=options.maxRetainedBytes??1048576;
 if(!options.namespace||![version,maxEntries,maxSaved,maxRetained].every(n=>Number.isSafeInteger(n)&&n>0))throw new RangeError('invalid stack bounds');
 return {
  capture(prior:ActivityState,entries:readonly R[]):ActivityState {
   const saved={...prior.saved},retained={...prior.retained},descriptors:D[]=[],live:Record<string,unknown>={};
   for(const entry of entries.slice(0,maxEntries)){
    const memory=options.memory(entry),descriptor=options.encode(entry,memory);
    if(!descriptor||!options.decode(descriptor,{},options.captureContext))break;
    if(stringToUtf8(JSON.stringify([...descriptors,descriptor])).length>maxSaved)break;
    descriptors.push(descriptor);
    const ui=options.retained(memory);
    if(stringToUtf8(JSON.stringify({...live,[memory.id]:ui})).length<=maxRetained)live[memory.id]=ui;
   }
   saved[options.namespace]={version,entries:descriptors};retained[options.namespace]={version,entries:live};
   return {saved,retained};
  },
  restore(state:ActivityState,context:C):R[]|null {
   const saved=state.saved[options.namespace],retained=state.retained[options.namespace];
   if(!object(saved)||saved.version!==version||!Array.isArray(saved.entries)||saved.entries.length>maxEntries)return null;
   const live=object(retained)&&retained.version===version&&object(retained.entries)?retained.entries:{};
   const result:R[]=[],ids=new Set<string>();
   for(const raw of saved.entries){
    const entry=options.decode(raw,object(raw)?live[String(raw.id)]:undefined,context);
    if(!entry)break;
    const id=options.memory(entry).id;if(ids.has(id))break;ids.add(id);result.push(entry);
   }
   if(!result.length||!options.isRoot(result[0]!))return null;
   options.restoredIds?.(ids);return result;
  },
  async resolve<T>(entries:readonly R[],prepare:(entry:R)=>boolean|{result:Promise<T>;accept(value:T):boolean},fallback:()=>R):Promise<R[]> {
   const result:R[]=[];for(const entry of entries){const step=prepare(entry);if(!(typeof step==='boolean'?step:step.accept(await step.result)))break;result.push(entry);}return result.length?result:[fallback()];
  },
 };
}
