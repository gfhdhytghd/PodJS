import {getOwner,onCleanup,type Owner} from 'solid-js';
import {systemTextMemoryStats} from "./text.tsx";
import {serviceResourceCounts} from "./services.ts";
import {watchResourceCounts} from "./watch.ts";
import * as gesture from "@pocketjs/framework/gesture";
import * as devtools from "@pocketjs/framework/devtools";
import * as renderer from "@pocketjs/framework/renderer";
// Older PocketJS runtimes have no registry diagnostics. Leave them absent
// instead of loading a second renderer/registry or inventing zero counts.
const registry=renderer as unknown as {nativeTreeResourceCounts?:()=>unknown;frameResourceCounts?:()=>unknown;clockResourceCounts?:()=>unknown};
let enabled=false;
const roots=new Map<Owner,number>();
/** Call inside a mounted component after the build-info debug gate. The
 * default/release path neither retains owners nor registers cleanup. */
export function trackDebugMemoryOwner():void {
 if(!enabled)return;
 const root=getOwner();if(!root)return;
 const observed=root;roots.set(observed,(roots.get(observed)??0)+1);
 onCleanup(()=>{const count=roots.get(observed)??0;if(count<=1)roots.delete(observed);else roots.set(observed,count-1);});
}
/** Demand-only graph reachable from registered render roots and their signal
 * observers. Detached roots without a path to this graph are not included. */
export function solidMemoryResourceCounts(){
 if(!enabled)return null;
 const seen=new Set<Owner>(),signals=new Set<any>(),pending=[...roots.keys()];
 let owners=0,computations=0,signalObservers=0;
 while(pending.length){const node=pending.pop() as any;if(!node||seen.has(node))continue;seen.add(node);
  if(node.fn)computations++;else owners++;
  if(node.owner)pending.push(node.owner);
  for(const child of node.owned??[])pending.push(child);
  for(const source of node.sources??[]){if(signals.has(source))continue;signals.add(source);signalObservers+=(source.observers??[]).length;for(const observer of source.observers??[])pending.push(observer);}
 }
 return {roots:roots.size,owners,computations,signals:signals.size,signalObservers};
}
export {debugMemoryStats} from "./services.ts";

/** Install only after application.buildInfo().debugBuild is true. This adds
 * no frame hook, timer or retained samples. Native reads it between turns. */
export function installDebugMemoryStats():void {
 enabled=true;
 (globalThis as {__podMemoryStats?:()=>string}).__podMemoryStats=()=>JSON.stringify({
  solid:solidMemoryResourceCounts(),systemText:systemTextMemoryStats(),services:serviceResourceCounts(),watch:watchResourceCounts(),
  clock:registry.clockResourceCounts?.()??null,
  tree:registry.nativeTreeResourceCounts?.()??null,frame:registry.frameResourceCounts?.()??null,
  gesture:(gesture as any).gestureResourceCounts?.()??null,
  devtools:(devtools as any).devtoolsResourceCounts?.()??null,
 });
}
