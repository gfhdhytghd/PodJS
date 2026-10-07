import {createContext,useContext,createSignal,createComponent,type JSX,type Signal} from 'solid-js';
export interface RouteStateMemory {saved:Record<string,unknown>;retained:Record<string,unknown>}
export interface RouteStateOwner {
 memory:()=>RouteStateMemory;
 changed:()=>void;checkpoints:Set<()=>void>;scrollSlots:Set<number>;
}
const Context=createContext<RouteStateOwner>();
export function RouteStateProvider(props:RouteStateOwner&{children:JSX.Element}){return createComponent(Context.Provider,{value:props,get children(){return props.children;}});}
export function routeState(){return useContext(Context);}
/** Schema validation and key/namespace selection belong to the caller. */
export function restoredSignal<T>(channel:'saved'|'retained',key:string,initial:T,valid:(value:unknown)=>value is T,enabled=true):Signal<T>{
 const context=enabled?routeState():undefined,memory=context?.memory();
 const restored=memory?.[channel][key];
 const [read,write]=createSignal<T>(valid(restored)?restored:initial);
 const setter:Signal<T>[1]=((value:unknown)=>{
  const result=write(value as never);
  if(memory){memory[channel][key]=result;context!.changed();}
  return result;
 }) as Signal<T>[1];
 return [read,setter];
}
export const booleanState=(v:unknown):v is boolean=>typeof v==='boolean';
export const stringState=(v:unknown):v is string=>typeof v==='string';
export const finiteState=(v:unknown):v is number=>typeof v==='number'&&Number.isFinite(v);
